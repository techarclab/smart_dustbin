"""
SmartBinRegister  —  AWS Lambda (Python 3.12)
=============================================
Handles the Admin panel's bin-registration requests via API Gateway.

Routes (HTTP API, payload format 2.0):
    POST /bins   -> register a new bin (auto-creates IoT Thing + certificate)
    GET  /bins   -> list all registered bins (metadata only, no secrets)

Registering a bin, fully automated:
    1. Validate input JSON.
    2. Reject duplicate bin_id  (primary key).
    3. Reject duplicate bin_name (GSI lookup).
    4. Create IoT Thing  : esp32c3-smartbin-<bin_id>
    5. Create keys + X.509 certificate (active).
    6. Attach SmartBinPolicy to the certificate.
    7. Attach the certificate to the Thing.
    8. Persist metadata (+ thing/cert refs) in DynamoDB.
    9. Return cert PEM + private key ONCE (key is never retrievable again).

Env vars (set by deploy.sh):
    REGISTRY_TABLE   default "SmartBinRegistry"
    IOT_POLICY_NAME  default "SmartBinPolicy"
    THING_PREFIX     default "esp32c3-smartbin-"
"""

import json
import os
import re
import time
from decimal import Decimal
import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

REGISTRY_TABLE  = os.environ.get("REGISTRY_TABLE", "SmartBinRegistry")
IOT_POLICY_NAME = os.environ.get("IOT_POLICY_NAME", "SmartBinPolicy")
THING_PREFIX    = os.environ.get("THING_PREFIX", "esp32c3-smartbin-")

dynamodb = boto3.resource("dynamodb")
table    = dynamodb.Table(REGISTRY_TABLE)
iot      = boto3.client("iot")

CORS = {
    "Access-Control-Allow-Origin":  "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Content-Type":                 "application/json",
}

REQUIRED_FIELDS = [
    "bin_id", "bin_name", "place", "ward",
    "route", "capacity", "latitude", "longitude",
]

ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{2,32}$")


def _json_default(o):
    # DynamoDB returns numbers as Decimal; make them JSON-serializable.
    if isinstance(o, Decimal):
        return int(o) if o % 1 == 0 else float(o)
    raise TypeError(f"not serializable: {type(o)}")


def _resp(status, body):
    return {"statusCode": status, "headers": CORS,
            "body": json.dumps(body, default=_json_default)}


def lambda_handler(event, context):
    method = (
        event.get("requestContext", {}).get("http", {}).get("method")
        or event.get("httpMethod")
        or "GET"
    ).upper()

    if method == "OPTIONS":
        return _resp(200, {"ok": True})
    if method == "GET":
        return list_bins()
    if method == "POST":
        return register_bin(event)
    return _resp(405, {"error": f"Method {method} not allowed"})


def list_bins():
    try:
        items = table.scan().get("Items", [])
    except ClientError as e:
        return _resp(500, {"error": "DynamoDB scan failed", "detail": str(e)})

    safe = []
    for it in items:
        it.pop("private_key", None)
        it.pop("certificate_pem", None)
        safe.append(it)
    safe.sort(key=lambda b: b.get("bin_id", ""))
    return _resp(200, {"count": len(safe), "bins": safe})


def register_bin(event):
    # 1. Parse body
    try:
        body = event.get("body") or "{}"
        if event.get("isBase64Encoded"):
            import base64
            body = base64.b64decode(body).decode("utf-8")
        data = json.loads(body)
    except (ValueError, TypeError):
        return _resp(400, {"error": "Request body is not valid JSON"})

    # 2. Validate
    missing = [f for f in REQUIRED_FIELDS if data.get(f) in (None, "")]
    if missing:
        return _resp(400, {"error": "Missing required fields", "fields": missing})

    bin_id   = str(data["bin_id"]).strip()
    bin_name = str(data["bin_name"]).strip()

    if not ID_PATTERN.match(bin_id):
        return _resp(400, {
            "error": "bin_id must be 2-32 chars, letters/digits/_/- only "
                     "(it becomes part of the IoT Thing name and MQTT topic)"
        })

    try:
        capacity  = float(data["capacity"])
        latitude  = float(data["latitude"])
        longitude = float(data["longitude"])
    except (ValueError, TypeError):
        return _resp(400, {"error": "capacity, latitude, longitude must be numbers"})

    if not (-90 <= latitude <= 90) or not (-180 <= longitude <= 180):
        return _resp(400, {"error": "latitude/longitude out of range"})

    # 3. Duplicate checks
    try:
        if table.get_item(Key={"bin_id": bin_id}).get("Item"):
            return _resp(409, {"error": f"bin_id '{bin_id}' already exists"})
        dup_name = table.query(
            IndexName="bin_name-index",
            KeyConditionExpression=Key("bin_name").eq(bin_name),
        )
        if dup_name.get("Items"):
            return _resp(409, {"error": f"bin_name '{bin_name}' already exists"})
    except ClientError as e:
        return _resp(500, {"error": "Duplicate check failed", "detail": str(e)})

    # 4-7. Provision in AWS IoT
    thing_name = f"{THING_PREFIX}{bin_id}"
    cert_arn = cert_id = cert_pem = private_key = None
    try:
        iot.create_thing(thingName=thing_name)
        keys = iot.create_keys_and_certificate(setAsActive=True)
        cert_arn    = keys["certificateArn"]
        cert_id     = keys["certificateId"]
        cert_pem    = keys["certificatePem"]
        private_key = keys["keyPair"]["PrivateKey"]
        iot.attach_policy(policyName=IOT_POLICY_NAME, target=cert_arn)
        iot.attach_thing_principal(thingName=thing_name, principal=cert_arn)
    except ClientError as e:
        _rollback(thing_name, cert_arn, cert_id)
        return _resp(500, {"error": "IoT provisioning failed", "detail": str(e)})

    # 8. Persist metadata
    record = {
        "bin_id":     bin_id,
        "bin_name":   bin_name,
        "place":      str(data["place"]).strip(),
        "ward":       str(data["ward"]).strip(),
        "route":      str(data["route"]).strip(),
        "capacity":   int(capacity),
        "latitude":   str(latitude),
        "longitude":  str(longitude),
        "thing_name": thing_name,
        "cert_id":    cert_id,
        "cert_arn":   cert_arn,
        "created_at": int(time.time()),
    }
    try:
        table.put_item(Item=record, ConditionExpression="attribute_not_exists(bin_id)")
    except ClientError as e:
        _rollback(thing_name, cert_arn, cert_id)
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return _resp(409, {"error": f"bin_id '{bin_id}' already exists"})
        return _resp(500, {"error": "Failed to save registration", "detail": str(e)})

    # 9. Return secrets once
    return _resp(201, {
        "message":    "Bin registered and provisioned in AWS IoT.",
        "bin":        record,
        "thing_name": thing_name,
        "topics": {
            "telemetry": f"smartbin/{bin_id}/telemetry",
            "alert":     f"smartbin/{bin_id}/alert",
            "status":    f"smartbin/{bin_id}/status",
        },
        "credentials": {
            "note": "Save these now. The private key cannot be retrieved again. "
                    "Place them in main/certs/ and reflash the device.",
            "certificate_pem": cert_pem,
            "private_key":     private_key,
        },
    })


def _rollback(thing_name, cert_arn, cert_id):
    """Best-effort cleanup so a partial failure leaves nothing behind."""
    try:
        if cert_arn and thing_name:
            iot.detach_thing_principal(thingName=thing_name, principal=cert_arn)
    except ClientError:
        pass
    try:
        if cert_arn:
            iot.detach_policy(policyName=IOT_POLICY_NAME, target=cert_arn)
    except ClientError:
        pass
    try:
        if cert_id:
            iot.update_certificate(certificateId=cert_id, newStatus="INACTIVE")
            iot.delete_certificate(certificateId=cert_id, forceDelete=True)
    except ClientError:
        pass
    try:
        if thing_name:
            iot.delete_thing(thingName=thing_name)
    except ClientError:
        pass