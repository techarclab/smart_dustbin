# SmartBin v2 — Dashboard + Admin Panel + Automated AWS

This extends the working v1 system. **Nothing in v1 firmware MQTT config changes.**
What's new: a redesigned operator dashboard, an admin panel that registers bins
and provisions them in AWS automatically, multi-bin support, and battery + signal
telemetry.

## New architecture

```
ESP32 #1 (bin001, ultrasonic) ┐
ESP32 #2 (bin002, ultrasonic) ┼─ MQTT/TLS ─► AWS IoT Core (eu-north-1)
ESP32 #3 (bin003, proximity)  ┘                     │
                                IoT Rule: SELECT * FROM 'smartbin/+/telemetry'
                                                     │
                                          Lambda: SmartBinAPI
                                          (writes latest per bin to DynamoDB)
                                                     │
                          DynamoDB: SmartBinTelemetry        DynamoDB: SmartBinRegistry
                                     │                                  ▲
                          API Gateway (HTTP API)                        │
            GET /telemetry ─┘        GET/POST /bins ─► Lambda: SmartBinRegister
                                                       (creates IoT Thing + cert,
                                                        checks duplicates)
                                     │
                          React dashboard (smartbin_dashboard.jsx)
                          - Dashboard tab: live monitoring
                          - Admin tab: register bins
```

## Files

| File | What it is |
|------|-----------|
| `smartbin_dashboard.jsx` | New React dashboard + admin panel (single file) |
| `lambda_telemetry.py` | Upgraded `SmartBinAPI` — DynamoDB-backed, multi-bin, battery+rssi |
| `lambda_register.py` | New `SmartBinRegister` — registration + auto IoT provisioning |
| `deploy.sh` | One-shot AWS CLI provisioning (idempotent) |
| `firmware_additions.md` | Drop-in firmware code: battery, RSSI, proximity, multi-bin |

## Deploy (one command)

```bash
# put lambda_telemetry.py, lambda_register.py, deploy.sh in one folder
aws configure          # creds for account 978236789991, region eu-north-1
bash deploy.sh
```

It creates the two DynamoDB tables, the IAM role for the registration Lambda,
adds DynamoDB access to the existing telemetry Lambda role, deploys/updates both
Lambdas, and wires the API routes with CORS. Safe to re-run.

> One manual step the script can't do: confirm the **IoT Rule** still points at
> `SmartBinAPI` (it does in v1, unchanged) so telemetry keeps flowing to DynamoDB.

## API contract

| Method | Path | Body | Returns |
|--------|------|------|---------|
| GET | `/telemetry` | — | `{ "bin001": {...}, "bin002": {...} }` latest per bin |
| GET | `/bins` | — | `{ count, bins: [ {metadata} ] }` |
| POST | `/bins` | `{bin_id,bin_name,place,ward,route,capacity,latitude,longitude}` | 201 + cert/key, or 409 on duplicate |

## Per-bin sensor mapping

| Board | bin_id | Sensor | Firmware flag |
|-------|--------|--------|---------------|
| #1 | bin001 | HC-SR04 ultrasonic | — |
| #2 | bin002 | HC-SR04 ultrasonic | — |
| #3 | bin003 | Proximity | `#define USE_PROXIMITY_SENSOR` |

## Onboarding a new bin (fully automated path)

1. Admin tab → fill the form → **Register bin**.
2. Lambda creates the IoT Thing + certificate, checks for duplicate ID/name,
   stores metadata in DynamoDB.
3. The panel shows `certificate.pem.crt` + `private.pem.key` **once** — copy them.
4. Drop them in `main/certs/`, set `DEVICE_ID`, build & flash.
5. Bin appears live on the dashboard automatically.

## Fill-level status (dashboard)

| Fill | Status | Meaning |
|------|--------|---------|
| < 50% | Optimal | safe |
| 50–79% | Watch | collection soon |
| ≥ 80% | Critical | empty urgently (matches firmware alert threshold) |

## Notes / known limits

- The dashboard falls back to **demo data** with a visible banner if the API is
  unreachable, so you can preview the UI before deploying.
- The private key is shown **once** by AWS design — it cannot be re-downloaded.
- The IoT policy is still `Resource:"*"`. Fine for the project; tighten before
  any production use.
- Battery % needs a real battery + voltage divider into an ADC pin (see
  `firmware_additions.md`); RSSI is read directly from the WiFi driver.
