"""
SmartBinAPI  —  AWS Lambda (Python 3.12)   [UPGRADED]
=====================================================
Replaces the old in-memory version. Now backed by DynamoDB so it is
multi-bin and survives cold starts / multiple warm containers.

This single Lambda handles TWO trigger sources:

  A) IoT Rule  (SELECT * FROM 'smartbin/+/telemetry')
     The event IS the raw MQTT payload and contains "bin_id".
     -> upsert the latest reading for that bin into DynamoDB.

  B) API Gateway  (GET /telemetry)
     -> return the latest reading for EVERY bin as a JSON object
        keyed by bin_id, e.g. { "bin001": {...}, "bin002": {...} }.

Telemetry payload now also carries (from firmware):
    battery   : int  battery percent 0-100
    rssi      : int  WiFi signal strength in dBm (negative; -50 strong, -90 weak)
in addition to the original fill_percent, distance_cm, status, alert,
latitude, longitude, location, timestamp.

Environment variables (set by deploy.sh):
    TELEMETRY_TABLE  default "SmartBinTelemetry"
"""

import json
import os
import time
from decimal import Decimal
import boto3

TELEMETRY_TABLE = os.environ.get("TELEMETRY_TABLE", "SmartBinTelemetry")

dynamodb = boto3.resource("dynamodb")
table    = dynamodb.Table(TELEMETRY_TABLE)

CORS = {
    "Access-Control-Allow-Origin":  "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET,OPTIONS",
    "Content-Type":                 "application/json",
}


def _to_dynamo(obj):
    """DynamoDB rejects float; convert floats to Decimal recursively."""
    if isinstance(obj, float):
        return Decimal(str(obj))
    if isinstance(obj, list):
        return [_to_dynamo(v) for v in obj]
    if isinstance(obj, dict):
        return {k: _to_dynamo(v) for k, v in obj.items()}
    return obj


def _from_dynamo(obj):
    """Convert Decimals back to int/float for clean JSON output."""
    if isinstance(obj, Decimal):
        return int(obj) if obj % 1 == 0 else float(obj)
    if isinstance(obj, list):
        return [_from_dynamo(v) for v in obj]
    if isinstance(obj, dict):
        return {k: _from_dynamo(v) for k, v in obj.items()}
    return obj


def lambda_handler(event, context):
    # ---- A) IoT Rule trigger: raw telemetry payload ----------------------
    if isinstance(event, dict) and "bin_id" in event:
        return ingest(event)

    # ---- CORS pre-flight -------------------------------------------------
    method = (
        event.get("requestContext", {}).get("http", {}).get("method")
        or event.get("httpMethod")
        or "GET"
    ).upper()
    if method == "OPTIONS":
        return {"statusCode": 200, "headers": CORS, "body": json.dumps({"ok": True})}

    # ---- B) API Gateway GET ---------------------------------------------
    return serve()


def ingest(payload):
    item = dict(payload)
    item["updated_at"] = int(time.time())          # server-side receive time
    try:
        table.put_item(Item=_to_dynamo(item))
        print("Telemetry stored:", json.dumps(payload))
    except Exception as e:                          # noqa: BLE001
        print("ERROR storing telemetry:", str(e))
        return {"statusCode": 500, "body": json.dumps({"error": str(e)})}
    return {"statusCode": 200, "body": json.dumps({"message": "stored"})}


def serve():
    try:
        items = table.scan().get("Items", [])
    except Exception as e:                          # noqa: BLE001
        return {
            "statusCode": 500,
            "headers": CORS,
            "body": json.dumps({"error": str(e)}),
        }

    by_bin = {}
    for it in items:
        clean = _from_dynamo(it)
        by_bin[clean["bin_id"]] = clean

    return {
        "statusCode": 200,
        "headers": CORS,
        "body": json.dumps(by_bin),
    }
