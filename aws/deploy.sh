#!/usr/bin/env bash
###############################################################################
# SmartBin — one-shot AWS provisioning script
# -----------------------------------------------------------------------------
# Stands up the automated backend for the new dashboard + admin panel:
#   * DynamoDB: SmartBinRegistry (with bin_name GSI) + SmartBinTelemetry
#   * IAM role for the registration Lambda (IoT + DynamoDB)
#   * DynamoDB permissions added to the existing telemetry Lambda role
#   * Deploys/updates both Lambdas (SmartBinAPI, SmartBinRegister)
#   * Wires the existing HTTP API: GET /telemetry, GET /bins, POST /bins (+CORS)
#
# Safe to re-run: every step checks for existing resources first.
#
# Prereqs:
#   - AWS CLI v2 configured with credentials for account 978236789991
#   - lambda_telemetry.py and lambda_register.py next to this script
#   - The SmartBinPolicy IoT policy already exists (it does)
#
# Usage:   bash deploy.sh
###############################################################################
set -euo pipefail

# ---- Settings ---------------------------------------------------------------
REGION="eu-north-1"
ACCOUNT_ID="978236789991"
API_ID="aoacx6u7g2"                       # existing HTTP API (from your URL)
STAGE="default"

REGISTRY_TABLE="SmartBinRegistry"
TELEMETRY_TABLE="SmartBinTelemetry"

TELEMETRY_FN="SmartBinAPI"                 # existing function — we update its code/role
TELEMETRY_ROLE="SmartBinProcessor-role-fxlipadp"   # existing role

REGISTER_FN="SmartBinRegister"            # new function
REGISTER_ROLE="SmartBinRegisterRole"      # new role

IOT_POLICY_NAME="SmartBinPolicy"

say(){ printf "\n\033[1;36m==> %s\033[0m\n" "$1"; }

# ---- 1. DynamoDB tables -----------------------------------------------------
say "Creating DynamoDB tables (if missing)"

if ! aws dynamodb describe-table --table-name "$TELEMETRY_TABLE" --region "$REGION" >/dev/null 2>&1; then
  aws dynamodb create-table \
    --table-name "$TELEMETRY_TABLE" \
    --attribute-definitions AttributeName=bin_id,AttributeType=S \
    --key-schema AttributeName=bin_id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region "$REGION" >/dev/null
  echo "  created $TELEMETRY_TABLE"
else
  echo "  $TELEMETRY_TABLE already exists"
fi

if ! aws dynamodb describe-table --table-name "$REGISTRY_TABLE" --region "$REGION" >/dev/null 2>&1; then
  aws dynamodb create-table \
    --table-name "$REGISTRY_TABLE" \
    --attribute-definitions \
        AttributeName=bin_id,AttributeType=S \
        AttributeName=bin_name,AttributeType=S \
    --key-schema AttributeName=bin_id,KeyType=HASH \
    --global-secondary-indexes \
        "IndexName=bin_name-index,KeySchema=[{AttributeName=bin_name,KeyType=HASH}],Projection={ProjectionType=ALL}" \
    --billing-mode PAY_PER_REQUEST \
    --region "$REGION" >/dev/null
  echo "  created $REGISTRY_TABLE (with bin_name-index GSI)"
else
  echo "  $REGISTRY_TABLE already exists"
fi

say "Waiting for tables to become ACTIVE"
aws dynamodb wait table-exists --table-name "$TELEMETRY_TABLE" --region "$REGION"
aws dynamodb wait table-exists --table-name "$REGISTRY_TABLE"  --region "$REGION"

REGISTRY_ARN="arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${REGISTRY_TABLE}"
TELEMETRY_ARN="arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${TELEMETRY_TABLE}"

# ---- 2. Give the telemetry Lambda role DynamoDB access ----------------------
say "Attaching DynamoDB access to telemetry Lambda role"
cat > /tmp/sb_telemetry_ddb.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["dynamodb:PutItem","dynamodb:GetItem","dynamodb:Scan"],
    "Resource": ["${TELEMETRY_ARN}","${TELEMETRY_ARN}/index/*"]
  }]
}
EOF
aws iam put-role-policy \
  --role-name "$TELEMETRY_ROLE" \
  --policy-name "SmartBinTelemetryDDB" \
  --policy-document file:///tmp/sb_telemetry_ddb.json
echo "  done"

# ---- 3. Create the registration Lambda role ---------------------------------
say "Creating registration Lambda role (if missing)"
cat > /tmp/sb_trust.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "lambda.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
EOF

if ! aws iam get-role --role-name "$REGISTER_ROLE" >/dev/null 2>&1; then
  aws iam create-role \
    --role-name "$REGISTER_ROLE" \
    --assume-role-policy-document file:///tmp/sb_trust.json >/dev/null
  aws iam attach-role-policy \
    --role-name "$REGISTER_ROLE" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "  created $REGISTER_ROLE"
  echo "  (waiting 10s for IAM propagation)"; sleep 10
else
  echo "  $REGISTER_ROLE already exists"
fi

cat > /tmp/sb_register_inline.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "iot:CreateThing","iot:DeleteThing",
        "iot:CreateKeysAndCertificate",
        "iot:UpdateCertificate","iot:DeleteCertificate",
        "iot:AttachPolicy","iot:DetachPolicy",
        "iot:AttachThingPrincipal","iot:DetachThingPrincipal"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": ["dynamodb:PutItem","dynamodb:GetItem","dynamodb:Scan","dynamodb:Query"],
      "Resource": ["${REGISTRY_ARN}","${REGISTRY_ARN}/index/*"]
    }
  ]
}
EOF
aws iam put-role-policy \
  --role-name "$REGISTER_ROLE" \
  --policy-name "SmartBinRegisterInline" \
  --policy-document file:///tmp/sb_register_inline.json
REGISTER_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${REGISTER_ROLE}"
echo "  permissions attached"

# ---- 4. Package + deploy the Lambdas ----------------------------------------
say "Packaging Lambda code"
TMP=$(mktemp -d)
cp lambda_telemetry.py "$TMP/lambda_function.py"; ( cd "$TMP" && zip -q telemetry.zip lambda_function.py )
cp lambda_register.py  "$TMP/lambda_function.py"; ( cd "$TMP" && zip -q register.zip  lambda_function.py )

say "Deploying $TELEMETRY_FN (update code + env)"
aws lambda update-function-code \
  --function-name "$TELEMETRY_FN" \
  --zip-file "fileb://$TMP/telemetry.zip" \
  --region "$REGION" >/dev/null
aws lambda wait function-updated --function-name "$TELEMETRY_FN" --region "$REGION"
aws lambda update-function-configuration \
  --function-name "$TELEMETRY_FN" \
  --environment "Variables={TELEMETRY_TABLE=$TELEMETRY_TABLE}" \
  --timeout 15 \
  --region "$REGION" >/dev/null
echo "  done"

say "Deploying $REGISTER_FN (create or update)"
if aws lambda get-function --function-name "$REGISTER_FN" --region "$REGION" >/dev/null 2>&1; then
  aws lambda update-function-code \
    --function-name "$REGISTER_FN" \
    --zip-file "fileb://$TMP/register.zip" \
    --region "$REGION" >/dev/null
  aws lambda wait function-updated --function-name "$REGISTER_FN" --region "$REGION"
  aws lambda update-function-configuration \
    --function-name "$REGISTER_FN" \
    --environment "Variables={REGISTRY_TABLE=$REGISTRY_TABLE,IOT_POLICY_NAME=$IOT_POLICY_NAME,THING_PREFIX=esp32c3-smartbin-}" \
    --timeout 30 \
    --region "$REGION" >/dev/null
else
  aws lambda create-function \
    --function-name "$REGISTER_FN" \
    --runtime python3.12 \
    --role "$REGISTER_ROLE_ARN" \
    --handler lambda_function.lambda_handler \
    --timeout 30 \
    --environment "Variables={REGISTRY_TABLE=$REGISTRY_TABLE,IOT_POLICY_NAME=$IOT_POLICY_NAME,THING_PREFIX=esp32c3-smartbin-}" \
    --zip-file "fileb://$TMP/register.zip" \
    --region "$REGION" >/dev/null
fi
echo "  done"

TELEMETRY_FN_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${TELEMETRY_FN}"
REGISTER_FN_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${REGISTER_FN}"

# ---- 5. Wire API Gateway (HTTP API) routes ----------------------------------
say "Wiring API Gateway routes"

ensure_integration () {  # $1 = lambda arn  -> echoes integration id
  local fn_arn="$1"
  local existing
  existing=$(aws apigatewayv2 get-integrations --api-id "$API_ID" --region "$REGION" \
    --query "Items[?IntegrationUri=='${fn_arn}'].IntegrationId | [0]" --output text)
  if [ "$existing" != "None" ] && [ -n "$existing" ]; then
    echo "$existing"; return
  fi
  aws apigatewayv2 create-integration \
    --api-id "$API_ID" --region "$REGION" \
    --integration-type AWS_PROXY \
    --integration-uri "$fn_arn" \
    --payload-format-version 2.0 \
    --query IntegrationId --output text
}

ensure_route () {  # $1 = "GET /bins"   $2 = integration id
  local key="$1" intg="$2" rid
  rid=$(aws apigatewayv2 get-routes --api-id "$API_ID" --region "$REGION" \
    --query "Items[?RouteKey=='${key}'].RouteId | [0]" --output text)
  if [ "$rid" != "None" ] && [ -n "$rid" ]; then
    echo "  route '$key' exists"; return
  fi
  aws apigatewayv2 create-route \
    --api-id "$API_ID" --region "$REGION" \
    --route-key "$key" \
    --target "integrations/${intg}" >/dev/null
  echo "  route '$key' created"
}

TEL_INTG=$(ensure_integration "$TELEMETRY_FN_ARN")
REG_INTG=$(ensure_integration "$REGISTER_FN_ARN")

ensure_route "GET /telemetry" "$TEL_INTG"
ensure_route "GET /bins"      "$REG_INTG"
ensure_route "POST /bins"     "$REG_INTG"

# CORS on the API itself
aws apigatewayv2 update-api --api-id "$API_ID" --region "$REGION" \
  --cors-configuration AllowOrigins="*",AllowMethods="GET,POST,OPTIONS",AllowHeaders="content-type" >/dev/null
echo "  CORS configured"

# ---- 6. Lambda invoke permissions for API Gateway ---------------------------
say "Granting API Gateway permission to invoke the Lambdas"
add_perm () {  # $1 fn name  $2 statement id
  aws lambda add-permission \
    --function-name "$1" \
    --statement-id "$2" \
    --action lambda:InvokeFunction \
    --principal apigateway.amazonaws.com \
    --source-arn "arn:aws:execute-api:${REGION}:${ACCOUNT_ID}:${API_ID}/*/*" \
    --region "$REGION" >/dev/null 2>&1 || echo "  ($1 permission already present)"
}
add_perm "$TELEMETRY_FN" "apigw-telemetry"
add_perm "$REGISTER_FN"  "apigw-register"

rm -rf "$TMP"

say "DONE"
echo "Base URL : https://${API_ID}.execute-api.${REGION}.amazonaws.com"
echo "  GET  /telemetry   -> live readings for all bins"
echo "  GET  /bins        -> registered bin metadata"
echo "  POST /bins        -> register a new bin (auto-creates IoT Thing + cert)"
