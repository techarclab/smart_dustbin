<#  SmartBin — Fleet Provisioning setup (PowerShell, idempotent)
    Run from the folder containing:
      device_policy.json claim_policy.json fleet_provisioning_template.json
      lambda_preprovision_hook.py
    Usage: powershell -ExecutionPolicy Bypass -File .\deploy_fleet_provisioning.ps1
    Requires AWS CLI v2 configured for account 978236789991.
#>
$ErrorActionPreference = "Continue"

$REGION="eu-north-1"; $ACCOUNT_ID="978236789991"
$TEMPLATE="SmartBinFleetTemplate"; $THING_GROUP="SmartBinFleet"
$DEVICE_POLICY="SmartBinDevicePolicy"; $CLAIM_POLICY="SmartBinClaimPolicy"
$PROV_ROLE="SmartBinProvisioningRole"; $HOOK_FN="SmartBinPreProvisionHook"
$HOOK_ROLE="SmartBinHookRole"; $REGISTRY_TABLE="SmartBinRegistry"
function Say($m){ Write-Host "`n==> $m" -ForegroundColor Cyan }
$tmp = Join-Path $env:TEMP "sb_fleet"; New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Say "Checking AWS CLI"
$acct = aws sts get-caller-identity --query Account --output text 2>$null
if ($LASTEXITCODE -ne 0){ Write-Host "AWS CLI not configured. Run 'aws configure'." -ForegroundColor Red; exit 1 }
Write-Host "  account: $acct"

Say "Thing group"
aws iot describe-thing-group --thing-group-name $THING_GROUP --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -ne 0){ aws iot create-thing-group --thing-group-name $THING_GROUP --region $REGION | Out-Null }
Write-Host "  ok"

Say "IoT policies"
aws iot get-policy --policy-name $DEVICE_POLICY --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -ne 0){ aws iot create-policy --policy-name $DEVICE_POLICY --policy-document file://device_policy.json --region $REGION | Out-Null }
aws iot get-policy --policy-name $CLAIM_POLICY --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -ne 0){ aws iot create-policy --policy-name $CLAIM_POLICY --policy-document file://claim_policy.json --region $REGION | Out-Null }
Write-Host "  device + claim policies ready"

Say "Claim certificate"
New-Item -ItemType Directory -Force -Path ".\claim_certs" | Out-Null
if (-not (Test-Path ".\claim_certs\claim.cert.pem")){
  $arn = aws iot create-keys-and-certificate --set-as-active `
    --certificate-pem-outfile claim_certs/claim.cert.pem `
    --public-key-outfile      claim_certs/claim.public.key `
    --private-key-outfile     claim_certs/claim.private.key `
    --region $REGION --query certificateArn --output text
  aws iot attach-policy --policy-name $CLAIM_POLICY --target $arn --region $REGION
  Write-Host "  created claim cert -> .\claim_certs\"
} else { Write-Host "  claim cert exists (reusing)" }

Say "Provisioning role"
$iotTrust = '{ "Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"iot.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
$iotTrustPath = Join-Path $tmp "iot_trust.json"; $iotTrust | Set-Content $iotTrustPath -Encoding ascii
aws iam get-role --role-name $PROV_ROLE 2>$null | Out-Null
if ($LASTEXITCODE -ne 0){
  aws iam create-role --role-name $PROV_ROLE --assume-role-policy-document "file://$iotTrustPath" | Out-Null
  aws iam attach-role-policy --role-name $PROV_ROLE --policy-arn arn:aws:iam::aws:policy/service-role/AWSIoTThingsRegistration | Out-Null
  Write-Host "  created (waiting 10s)"; Start-Sleep 10
} else { Write-Host "  exists" }
$PROV_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${PROV_ROLE}"

Say "Hook Lambda role"
$lamTrust = '{ "Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
$lamTrustPath = Join-Path $tmp "lam_trust.json"; $lamTrust | Set-Content $lamTrustPath -Encoding ascii
aws iam get-role --role-name $HOOK_ROLE 2>$null | Out-Null
if ($LASTEXITCODE -ne 0){
  aws iam create-role --role-name $HOOK_ROLE --assume-role-policy-document "file://$lamTrustPath" | Out-Null
  aws iam attach-role-policy --role-name $HOOK_ROLE --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole | Out-Null
  Write-Host "  created (waiting 10s)"; Start-Sleep 10
} else { Write-Host "  exists" }
$ddb = '{ "Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["dynamodb:PutItem","dynamodb:GetItem"],"Resource":"arn:aws:dynamodb:' + $REGION + ':' + $ACCOUNT_ID + ':table/' + $REGISTRY_TABLE + '"}]}'
$ddbPath = Join-Path $tmp "hook_ddb.json"; $ddb | Set-Content $ddbPath -Encoding ascii
aws iam put-role-policy --role-name $HOOK_ROLE --policy-name HookDDB --policy-document "file://$ddbPath" | Out-Null
$HOOK_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${HOOK_ROLE}"

Say "Hook Lambda"
Copy-Item lambda_preprovision_hook.py (Join-Path $tmp "lambda_function.py") -Force
Compress-Archive -Path (Join-Path $tmp "lambda_function.py") -DestinationPath (Join-Path $tmp "hook.zip") -Force
$hookZip = Join-Path $tmp "hook.zip"
aws lambda get-function --function-name $HOOK_FN --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -eq 0){
  aws lambda update-function-code --function-name $HOOK_FN --zip-file "fileb://$hookZip" --region $REGION | Out-Null
  aws lambda wait function-updated --function-name $HOOK_FN --region $REGION
} else {
  aws lambda create-function --function-name $HOOK_FN --runtime python3.12 `
    --role $HOOK_ROLE_ARN --handler lambda_function.lambda_handler --timeout 8 `
    --environment "Variables={REGISTRY_TABLE=$REGISTRY_TABLE}" `
    --zip-file "fileb://$hookZip" --region $REGION | Out-Null
}
$HOOK_FN_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${HOOK_FN}"
aws lambda add-permission --function-name $HOOK_FN --statement-id iot-preprov `
  --action lambda:InvokeFunction --principal iot.amazonaws.com `
  --source-arn "arn:aws:iot:${REGION}:${ACCOUNT_ID}:provisioningtemplate/${TEMPLATE}" `
  --region $REGION 2>$null | Out-Null
Write-Host "  hook ready"

Say "Provisioning template"
aws iot describe-provisioning-template --template-name $TEMPLATE --region $REGION 2>$null | Out-Null
if ($LASTEXITCODE -eq 0){
  aws iot update-provisioning-template --template-name $TEMPLATE --enabled `
    --provisioning-role-arn $PROV_ROLE_ARN --pre-provisioning-hook "targetArn=$HOOK_FN_ARN" --region $REGION | Out-Null
  aws iot create-provisioning-template-version --template-name $TEMPLATE `
    --template-body file://fleet_provisioning_template.json --set-as-default --region $REGION 2>$null | Out-Null
  Write-Host "  updated"
} else {
  aws iot create-provisioning-template --template-name $TEMPLATE `
    --provisioning-role-arn $PROV_ROLE_ARN --template-body file://fleet_provisioning_template.json `
    --pre-provisioning-hook "targetArn=$HOOK_FN_ARN" --enabled --type FLEET_PROVISIONING --region $REGION | Out-Null
  Write-Host "  created"
}

$endpoint = aws iot describe-endpoint --endpoint-type iot:Data-ATS --region $REGION --query endpointAddress --output text
Say "DONE"
Write-Host "IoT data endpoint : $endpoint"
Write-Host "Claim cert        : .\claim_certs\claim.cert.pem + claim.private.key  (embed in firmware)"
