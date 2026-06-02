#!/usr/bin/env bash
###############################################################################
# SmartBin — Fleet Provisioning setup (one-shot, idempotent)
# -----------------------------------------------------------------------------
# Creates everything needed for zero-touch onboarding:
#   * Thing group        : SmartBinFleet
#   * Device IoT policy  : SmartBinDevicePolicy   (scoped per-device)
#   * Claim IoT policy   : SmartBinClaimPolicy     (provisioning topics only)
#   * Claim certificate  : saved to ./claim_certs/ (embed in firmware)
#   * Provisioning role  : SmartBinProvisioningRole (IoT assumes to make Things)
#   * Hook Lambda        : SmartBinPreProvisionHook (+ IoT invoke permission)
#   * Provisioning template: SmartBinFleetTemplate (with the hook attached)
#
# Run from the folder containing:
#   device_policy.json  claim_policy.json  fleet_provisioning_template.json
#   lambda_preprovision_hook.py
#
# Prereq: AWS CLI v2 configured for account 978236789991.
###############################################################################
set -euo pipefail

REGION="eu-north-1"
ACCOUNT_ID="978236789991"
TEMPLATE="SmartBinFleetTemplate"
THING_GROUP="SmartBinFleet"
DEVICE_POLICY="SmartBinDevicePolicy"
CLAIM_POLICY="SmartBinClaimPolicy"
PROV_ROLE="SmartBinProvisioningRole"
HOOK_FN="SmartBinPreProvisionHook"
HOOK_ROLE="SmartBinHookRole"
REGISTRY_TABLE="SmartBinRegistry"

say(){ printf "\n\033[1;36m==> %s\033[0m\n" "$1"; }

# ---- 1. Thing group ---------------------------------------------------------
say "Thing group"
aws iot describe-thing-group --thing-group-name "$THING_GROUP" --region "$REGION" >/dev/null 2>&1 \
  || aws iot create-thing-group --thing-group-name "$THING_GROUP" --region "$REGION" >/dev/null
echo "  ok"

# ---- 2. IoT policies --------------------------------------------------------
say "IoT policies"
aws iot get-policy --policy-name "$DEVICE_POLICY" --region "$REGION" >/dev/null 2>&1 \
  || aws iot create-policy --policy-name "$DEVICE_POLICY" --policy-document file://device_policy.json --region "$REGION" >/dev/null
aws iot get-policy --policy-name "$CLAIM_POLICY" --region "$REGION" >/dev/null 2>&1 \
  || aws iot create-policy --policy-name "$CLAIM_POLICY" --policy-document file://claim_policy.json --region "$REGION" >/dev/null
echo "  device + claim policies ready"

# ---- 3. Claim certificate ---------------------------------------------------
say "Claim certificate"
mkdir -p claim_certs
if [ ! -f claim_certs/claim.cert.pem ]; then
  ARN=$(aws iot create-keys-and-certificate --set-as-active \
        --certificate-pem-outfile claim_certs/claim.cert.pem \
        --public-key-outfile      claim_certs/claim.public.key \
        --private-key-outfile     claim_certs/claim.private.key \
        --region "$REGION" --query certificateArn --output text)
  aws iot attach-policy --policy-name "$CLAIM_POLICY" --target "$ARN" --region "$REGION"
  echo "  created claim cert -> ./claim_certs/  (embed claim.cert.pem + claim.private.key in firmware)"
else
  echo "  claim_certs/claim.cert.pem already exists (reusing)"
fi

# ---- 4. Provisioning role (IoT assumes this to create Things) ---------------
say "Provisioning role"
cat > /tmp/iot_trust.json <<'JSON'
{ "Version":"2012-10-17","Statement":[{"Effect":"Allow",
  "Principal":{"Service":"iot.amazonaws.com"},"Action":"sts:AssumeRole"}]}
JSON
if ! aws iam get-role --role-name "$PROV_ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$PROV_ROLE" --assume-role-policy-document file:///tmp/iot_trust.json >/dev/null
  aws iam attach-role-policy --role-name "$PROV_ROLE" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSIoTThingsRegistration
  echo "  created $PROV_ROLE (waiting 10s for IAM)"; sleep 10
else
  echo "  $PROV_ROLE exists"
fi
PROV_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${PROV_ROLE}"

# ---- 5. Hook Lambda role ----------------------------------------------------
say "Hook Lambda role"
cat > /tmp/lambda_trust.json <<'JSON'
{ "Version":"2012-10-17","Statement":[{"Effect":"Allow",
  "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
JSON
if ! aws iam get-role --role-name "$HOOK_ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$HOOK_ROLE" --assume-role-policy-document file:///tmp/lambda_trust.json >/dev/null
  aws iam attach-role-policy --role-name "$HOOK_ROLE" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "  created $HOOK_ROLE (waiting 10s for IAM)"; sleep 10
else
  echo "  $HOOK_ROLE exists"
fi
cat > /tmp/hook_ddb.json <<JSON
{ "Version":"2012-10-17","Statement":[{"Effect":"Allow",
  "Action":["dynamodb:PutItem","dynamodb:GetItem"],
  "Resource":"arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${REGISTRY_TABLE}"}]}
JSON
aws iam put-role-policy --role-name "$HOOK_ROLE" --policy-name HookDDB --policy-document file:///tmp/hook_ddb.json
HOOK_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${HOOK_ROLE}"

# ---- 6. Hook Lambda ---------------------------------------------------------
say "Hook Lambda"
TMP=$(mktemp -d); cp lambda_preprovision_hook.py "$TMP/lambda_function.py"
( cd "$TMP" && zip -q hook.zip lambda_function.py )
if aws lambda get-function --function-name "$HOOK_FN" --region "$REGION" >/dev/null 2>&1; then
  aws lambda update-function-code --function-name "$HOOK_FN" --zip-file "fileb://$TMP/hook.zip" --region "$REGION" >/dev/null
  aws lambda wait function-updated --function-name "$HOOK_FN" --region "$REGION"
else
  aws lambda create-function --function-name "$HOOK_FN" --runtime python3.12 \
    --role "$HOOK_ROLE_ARN" --handler lambda_function.lambda_handler --timeout 8 \
    --environment "Variables={REGISTRY_TABLE=$REGISTRY_TABLE}" \
    --zip-file "fileb://$TMP/hook.zip" --region "$REGION" >/dev/null
fi
HOOK_FN_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${HOOK_FN}"
# allow IoT to invoke the hook
aws lambda add-permission --function-name "$HOOK_FN" --statement-id iot-preprov \
  --action lambda:InvokeFunction --principal iot.amazonaws.com \
  --source-arn "arn:aws:iot:${REGION}:${ACCOUNT_ID}:provisioningtemplate/${TEMPLATE}" \
  --region "$REGION" >/dev/null 2>&1 || echo "  (invoke permission already present)"
rm -rf "$TMP"
echo "  hook ready"

# ---- 7. Provisioning template ----------------------------------------------
say "Provisioning template"
if aws iot describe-provisioning-template --template-name "$TEMPLATE" --region "$REGION" >/dev/null 2>&1; then
  aws iot update-provisioning-template --template-name "$TEMPLATE" --enabled \
    --provisioning-role-arn "$PROV_ROLE_ARN" \
    --pre-provisioning-hook "targetArn=$HOOK_FN_ARN" --region "$REGION" >/dev/null
  # body update is a separate add-version
  aws iot create-provisioning-template-version --template-name "$TEMPLATE" \
    --template-body file://fleet_provisioning_template.json --set-as-default --region "$REGION" >/dev/null 2>&1 || true
  echo "  updated $TEMPLATE"
else
  aws iot create-provisioning-template --template-name "$TEMPLATE" \
    --provisioning-role-arn "$PROV_ROLE_ARN" \
    --template-body file://fleet_provisioning_template.json \
    --pre-provisioning-hook "targetArn=$HOOK_FN_ARN" \
    --enabled --type FLEET_PROVISIONING --region "$REGION" >/dev/null
  echo "  created $TEMPLATE"
fi

# ---- done -------------------------------------------------------------------
ENDPOINT=$(aws iot describe-endpoint --endpoint-type iot:Data-ATS --region "$REGION" --query endpointAddress --output text)
say "DONE"
echo "IoT data endpoint : $ENDPOINT"
echo "Template          : $TEMPLATE"
echo "Claim cert        : ./claim_certs/claim.cert.pem  +  claim.private.key"
echo "Embed the claim cert/key in firmware (main/certs/claim/). Devices use it"
echo "ONLY to provision, then switch to their unique cert stored in NVS."
