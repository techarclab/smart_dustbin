"""
SmartBinPreProvisionHook  —  AWS Lambda (Python 3.12)
=====================================================
Pre-provisioning hook for the SmartBinFleetTemplate (Fleet Provisioning by claim).

AWS calls this *before* it creates the Thing/cert, passing the parameters the
device sent in its RegisterThing request. We are the gatekeeper:
  - validate bin_id format and required parameters
  - (optional) enforce a shared provisioning secret
  - reject obviously bad requests
  - upsert the registry row (so onboarding auto-registers the bin)
  - return {"allowProvisioning": true|false}

IMPORTANT: this must return within ~5 seconds. Keep it lightweight.

Event shape (provided by AWS):
{
  "claimCertificateId": "...",
  "certificateId": "...",
  "templateArn": "...",
  "templateName": "SmartBinFleetTemplate",
  "parameters": { "bin_id": "...", "bin_name": "...", "ward": "...",
                  "route": "...", "place": "...",
                  "latitude": "...", "longitude": "...",
                  "provisioning_secret": "..."  (optional) }
}

Env vars:
    REGISTRY_TABLE       default "SmartBinRegistry"
    PROVISIONING_SECRET  if set, the device MUST send a matching
                         parameters.provisioning_secret or it is denied
"""

import os
import re
import time
import boto3
from botocore.exceptions import ClientError

REGISTRY_TABLE      = os.environ.get("REGISTRY_TABLE", "SmartBinRegistry")
PROVISIONING_SECRET = os.environ.get("PROVISIONING_SECRET", "")

table = boto3.resource("dynamodb").Table(REGISTRY_TABLE)

ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{2,32}$")
REQUIRED   = ["bin_id", "bin_name", "ward", "route", "place", "latitude", "longitude"]

DENY  = {"allowProvisioning": False}
ALLOW = {"allowProvisioning": True}


def lambda_handler(event, context):
    params = (event or {}).get("parameters", {}) or {}

    # 0. Optional shared-secret gate -------------------------------------
    if PROVISIONING_SECRET:
        if params.get("provisioning_secret", "") != PROVISIONING_SECRET:
            print("DENY: bad/missing provisioning_secret")
            return DENY

    # 1. Required params --------------------------------------------------
    missing = [k for k in REQUIRED if not str(params.get(k, "")).strip()]
    if missing:
        print(f"DENY: missing params {missing}")
        return DENY

    bin_id = str(params["bin_id"]).strip()
    if not ID_PATTERN.match(bin_id):
        print(f"DENY: bad bin_id '{bin_id}'")
        return DENY

    # 2. Numeric sanity ---------------------------------------------------
    try:
        lat = float(params["latitude"])
        lng = float(params["longitude"])
    except (ValueError, TypeError):
        print("DENY: lat/long not numeric")
        return DENY
    if not (-90 <= lat <= 90) or not (-180 <= lng <= 180):
        print("DENY: lat/long out of range")
        return DENY

    # 3. Upsert registry (idempotent — re-provisioning same bin is allowed)
    #    We do NOT hard-reject an existing bin_id here: a real device that
    #    lost its cert must be able to re-onboard. Operator-typo protection
    #    lives in the Admin form; field provisioning is trusted + secret-gated.
    now = int(time.time())
    item = {
        "bin_id":         bin_id,
        "bin_name":       str(params["bin_name"]).strip(),
        "ward":           str(params["ward"]).strip(),
        "route":          str(params["route"]).strip(),
        "place":          str(params["place"]).strip(),
        "latitude":       str(lat),
        "longitude":      str(lng),
        "thing_name":     f"esp32-smartbin-{bin_id}",
        "status":         "PROVISIONED",
        "provisioned_at": now,
        "onboarding":     "fleet",
    }
    try:
        table.put_item(Item=item)
        print(f"ALLOW: provisioning bin_id={bin_id}")
    except ClientError as e:
        # Don't block provisioning on a registry write hiccup; log and allow.
        print(f"WARN: registry write failed for {bin_id}: {e}")

    return ALLOW
