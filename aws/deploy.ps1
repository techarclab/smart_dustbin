<#  SmartBin — one-shot AWS provisioning (PowerShell version of deploy.sh)
    Run from the folder containing lambda_telemetry.py and lambda_register.py:
        powershell -ExecutionPolicy Bypass -File .\deploy.ps1
    Requires: AWS CLI v2 configured for account 978236789991 (aws configure).
    Safe to re-run.
#>

$ErrorActionPreference = "Continue"

# ---- Settings ---------------------------------------------------------------
$REGION          = "eu-north-1"
$ACCOUNT_ID      = "978236789991"
$API_ID          = "aoacx6u7g2"
$REGISTRY_TABLE  = "SmartBinRegistry"
$TELEMETRY_TABLE = "SmartBinTelemetry"
$TELEMETRY_FN    = "SmartBinAPI"
$TELEMETRY_ROLE  = "SmartBinProcessor-role-fxlipadp"
$REGISTER_FN     = "SmartBinRegister"
$REGISTER_ROLE   = "SmartBinRegisterRole"
$IOT_POLICY_NAME = "SmartBinPolicy"

function Say($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }

# ---- 0. Sanity checks -------------------------------------------------------
Say "Checking AWS CLI + credentials"
$who = aws sts get-caller-identity --query Account --output text 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "AWS CLI not found or not configured. Run 'aws configure' first." -ForegroundColor Red; exit 1 }
Write-Host "  account: $who"

$tmp = Join-Path $env:TEMP "smartbin_deploy"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$REGISTRY_ARN  = "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${REGISTRY_TABLE}"
$TELEMETRY_ARN = "arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${TELEMETRY_TABLE}"

# ---- 1. DynamoDB tables -----------------------------------------------------
Say "Creating DynamoDB tables (if missing)"
aws dynamodb describe-table --table-name $TELEMETRY_TABLE --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  aws dynamodb create-table --table-name $TELEMETRY_TABLE `
    --attribute-definitions AttributeName=bin_id,AttributeType=S `
    --key-schema AttributeName=bin_id,KeyType=HASH `
    --billing-mode PAY_PER_REQUEST --region $REGION | Out-Null
  Write-Host "  created $TELEMETRY_TABLE"
} else { Write-Host "  $TELEMETRY_TABLE exists" }

aws dynamodb describe-table --table-name $REGISTRY_TABLE --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  $gsi = 'IndexName=bin_name-index,KeySchema=[{AttributeName=bin_name,KeyType=HASH}],Projection={ProjectionType=ALL}'
  aws dynamodb create-table --table-name $REGISTRY_TABLE `
    --attribute-definitions AttributeName=bin_id,AttributeType=S AttributeName=bin_name,AttributeType=S `
    --key-schema AttributeName=bin_id,KeyType=HASH `
    --global-secondary-indexes $gsi `
    --billing-mode PAY_PER_REQUEST --region $REGION | Out-Null
  Write-Host "  created $REGISTRY_TABLE (with bin_name-index)"
} else { Write-Host "  $REGISTRY_TABLE exists" }

Say "Waiting for tables to become ACTIVE"
aws dynamodb wait table-exists --table-name $TELEMETRY_TABLE --region $REGION
aws dynamodb wait table-exists --table-name $REGISTRY_TABLE  --region $REGION

# ---- 2. Telemetry Lambda role -> DynamoDB -----------------------------------
Say "Attaching DynamoDB access to telemetry Lambda role"
$telDdb = @"
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["dynamodb:PutItem","dynamodb:GetItem","dynamodb:Scan"],
    "Resource": ["$TELEMETRY_ARN","$TELEMETRY_ARN/index/*"]
  }]
}
"@
$telDdbPath = Join-Path $tmp "tel_ddb.json"
$telDdb | Set-Content -Path $telDdbPath -Encoding ascii
aws iam put-role-policy --role-name $TELEMETRY_ROLE --policy-name "SmartBinTelemetryDDB" --policy-document "file://$telDdbPath" | Out-Null
Write-Host "  done"

# ---- 3. Registration Lambda role --------------------------------------------
Say "Creating registration Lambda role (if missing)"
$trust = @"
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "lambda.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
"@
$trustPath = Join-Path $tmp "trust.json"
$trust | Set-Content -Path $trustPath -Encoding ascii

aws iam get-role --role-name $REGISTER_ROLE 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  aws iam create-role --role-name $REGISTER_ROLE --assume-role-policy-document "file://$trustPath" | Out-Null
  aws iam attach-role-policy --role-name $REGISTER_ROLE --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole | Out-Null
  Write-Host "  created $REGISTER_ROLE (waiting 10s for IAM propagation)"
  Start-Sleep -Seconds 10
} else { Write-Host "  $REGISTER_ROLE exists" }

$regInline = @"
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "iot:CreateThing","iot:DeleteThing","iot:CreateKeysAndCertificate",
        "iot:UpdateCertificate","iot:DeleteCertificate",
        "iot:AttachPolicy","iot:DetachPolicy",
        "iot:AttachThingPrincipal","iot:DetachThingPrincipal"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": ["dynamodb:PutItem","dynamodb:GetItem","dynamodb:Scan","dynamodb:Query"],
      "Resource": ["$REGISTRY_ARN","$REGISTRY_ARN/index/*"]
    }
  ]
}
"@
$regInlinePath = Join-Path $tmp "reg_inline.json"
$regInline | Set-Content -Path $regInlinePath -Encoding ascii
aws iam put-role-policy --role-name $REGISTER_ROLE --policy-name "SmartBinRegisterInline" --policy-document "file://$regInlinePath" | Out-Null
$REGISTER_ROLE_ARN = "arn:aws:iam::${ACCOUNT_ID}:role/${REGISTER_ROLE}"
Write-Host "  permissions attached"

# ---- 4. Package + deploy Lambdas --------------------------------------------
Say "Packaging Lambda code"
Copy-Item lambda_telemetry.py (Join-Path $tmp "lambda_function.py") -Force
Compress-Archive -Path (Join-Path $tmp "lambda_function.py") -DestinationPath (Join-Path $tmp "telemetry.zip") -Force
Copy-Item lambda_register.py (Join-Path $tmp "lambda_function.py") -Force
Compress-Archive -Path (Join-Path $tmp "lambda_function.py") -DestinationPath (Join-Path $tmp "register.zip") -Force
$telZip = Join-Path $tmp "telemetry.zip"
$regZip = Join-Path $tmp "register.zip"

Say "Deploying $TELEMETRY_FN"
aws lambda update-function-code --function-name $TELEMETRY_FN --zip-file "fileb://$telZip" --region $REGION | Out-Null
aws lambda wait function-updated --function-name $TELEMETRY_FN --region $REGION
aws lambda update-function-configuration --function-name $TELEMETRY_FN `
  --environment "Variables={TELEMETRY_TABLE=$TELEMETRY_TABLE}" --timeout 15 --region $REGION | Out-Null
Write-Host "  done"

Say "Deploying $REGISTER_FN"
aws lambda get-function --function-name $REGISTER_FN --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
  aws lambda update-function-code --function-name $REGISTER_FN --zip-file "fileb://$regZip" --region $REGION | Out-Null
  aws lambda wait function-updated --function-name $REGISTER_FN --region $REGION
  aws lambda update-function-configuration --function-name $REGISTER_FN `
    --environment "Variables={REGISTRY_TABLE=$REGISTRY_TABLE,IOT_POLICY_NAME=$IOT_POLICY_NAME,THING_PREFIX=esp32c3-smartbin-}" `
    --timeout 30 --region $REGION | Out-Null
} else {
  aws lambda create-function --function-name $REGISTER_FN --runtime python3.12 `
    --role $REGISTER_ROLE_ARN --handler lambda_function.lambda_handler --timeout 30 `
    --environment "Variables={REGISTRY_TABLE=$REGISTRY_TABLE,IOT_POLICY_NAME=$IOT_POLICY_NAME,THING_PREFIX=esp32c3-smartbin-}" `
    --zip-file "fileb://$regZip" --region $REGION | Out-Null
}
Write-Host "  done"

$TELEMETRY_FN_ARN = "arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${TELEMETRY_FN}"
$REGISTER_FN_ARN  = "arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${REGISTER_FN}"

# ---- 5. API Gateway routes --------------------------------------------------
Say "Wiring API Gateway routes"
function Ensure-Integration($fnArn) {
  $existing = aws apigatewayv2 get-integrations --api-id $API_ID --region $REGION --query "Items[?IntegrationUri=='$fnArn'].IntegrationId | [0]" --output text
  if ($existing -and $existing -ne "None") { return $existing }
  return (aws apigatewayv2 create-integration --api-id $API_ID --region $REGION --integration-type AWS_PROXY --integration-uri $fnArn --payload-format-version 2.0 --query IntegrationId --output text)
}
function Ensure-Route($key, $intg) {
  $rid = aws apigatewayv2 get-routes --api-id $API_ID --region $REGION --query "Items[?RouteKey=='$key'].RouteId | [0]" --output text
  if ($rid -and $rid -ne "None") { Write-Host "  route '$key' exists"; return }
  aws apigatewayv2 create-route --api-id $API_ID --region $REGION --route-key $key --target "integrations/$intg" | Out-Null
  Write-Host "  route '$key' created"
}
$telIntg = Ensure-Integration $TELEMETRY_FN_ARN
$regIntg = Ensure-Integration $REGISTER_FN_ARN
Ensure-Route "GET /telemetry" $telIntg
Ensure-Route "GET /bins"      $regIntg
Ensure-Route "POST /bins"     $regIntg

$cors = @"
{
  "AllowOrigins": ["*"],
  "AllowMethods": ["GET","POST","OPTIONS"],
  "AllowHeaders": ["content-type"]
}
"@
$corsPath = Join-Path $tmp "cors.json"
$cors | Set-Content -Path $corsPath -Encoding ascii
aws apigatewayv2 update-api --api-id $API_ID --region $REGION --cors-configuration "file://$corsPath" | Out-Null
Write-Host "  CORS configured"

# ---- 6. Lambda invoke permissions for API Gateway ---------------------------
Say "Granting API Gateway permission to invoke the Lambdas"
$srcArn = "arn:aws:execute-api:${REGION}:${ACCOUNT_ID}:${API_ID}/*/*"
aws lambda add-permission --function-name $TELEMETRY_FN --statement-id "apigw-telemetry" --action lambda:InvokeFunction --principal apigateway.amazonaws.com --source-arn $srcArn --region $REGION 2>$null | Out-Null
aws lambda add-permission --function-name $REGISTER_FN  --statement-id "apigw-register"  --action lambda:InvokeFunction --principal apigateway.amazonaws.com --source-arn $srcArn --region $REGION 2>$null | Out-Null
Write-Host "  done (already-present permissions are ignored)"

Say "DONE"
Write-Host "Base URL : https://$API_ID.execute-api.$REGION.amazonaws.com"
Write-Host "  GET  /telemetry   -> live readings for all bins"
Write-Host "  GET  /bins        -> registered bin metadata"
Write-Host "  POST /bins        -> register a new bin (auto-creates IoT Thing + cert)"
