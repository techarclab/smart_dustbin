# SmartBin — Fleet Provisioning Rollout & Operations Guide

How to stand up zero-touch onboarding and roll out devices at scale. Read
`INDUSTRIAL_PROVISIONING_ARCHITECTURE.md` first for the "why".

## Files in this package

| File | Role |
|------|------|
| `INDUSTRIAL_PROVISIONING_ARCHITECTURE.md` | The design / blueprint |
| `fleet_provisioning_template.json` | RegisterThing template (params -> Thing) |
| `claim_policy.json` | Low-privilege policy for the shared claim cert |
| `device_policy.json` | Scoped per-device policy (policy variables) |
| `lambda_preprovision_hook.py` | Gatekeeper: validates + writes registry |
| `deploy_fleet_provisioning.sh` / `.ps1` | One-shot AWS setup |
| `main_provisioning.c` | ESP32 zero-touch firmware |

## One-time AWS setup

From the folder with the JSON + Python files:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy_fleet_provisioning.ps1
```
(or `bash deploy_fleet_provisioning.sh` in CloudShell / Git Bash.)

This creates the Thing group, both IoT policies, the **claim certificate**
(saved to `./claim_certs/`), the provisioning IAM role, the pre-provisioning
hook Lambda, and the provisioning template — wired together. It prints your IoT
endpoint at the end.

## Build the factory firmware image (one image for ALL bins)

1. Copy the claim files into the firmware tree:
   ```
   main/certs/claim/claim.cert.pem      <- from ./claim_certs/claim.cert.pem
   main/certs/claim/claim.private.key   <- from ./claim_certs/claim.private.key
   ```
2. Embed them in `main/CMakeLists.txt`:
   ```cmake
   idf_component_register(SRCS "main_provisioning.c"
       INCLUDE_DIRS "."
       REQUIRES nvs_flash esp_wifi wifi_provisioning mqtt json esp-tls driver esp_timer esp_netif
       EMBED_TXTFILES "certs/claim/claim.cert.pem" "certs/claim/claim.private.key")
   ```
   (The binary symbol names in the firmware — `claim_cert_pem`,
   `claim_private_key` — match these paths.)
3. `idf.py set-target esp32` (or esp32c3) → `idf.py build`.
4. Flash this **same image** to every board. There are no per-device certs in
   the image — that's the whole point.

## Field onboarding (per bin, by the operator)

1. Power on a new bin. It has no WiFi creds, so it starts SoftAP
   `SmartBin-Setup-XXXX`.
2. On a phone, open the **ESP SoftAP Prov** app (Espressif, iOS/Android),
   enter PoP `smartbin-pop-001`, pick the site WiFi, and enter the password.
3. Send the install metadata to the `installation` endpoint (bin_id, bin_name,
   ward, route, place, latitude, longitude). (A simple companion screen or the
   app's custom-data field carries this JSON.)
4. The device stores everything in NVS, reboots, connects to WiFi, then runs
   Fleet Provisioning automatically: it gets a unique certificate, registers as
   `esp32-smartbin-<bin_id>`, and the hook writes the registry row.
5. Within seconds it appears live on the dashboard. Done — no cables, no certs.

## Verify a provisioning run

- CloudWatch → Lambda `SmartBinPreProvisionHook` logs: look for
  `ALLOW: provisioning bin_id=...`.
- IoT Core → Things: `esp32-smartbin-<bin_id>` exists with attributes ward/route.
- DynamoDB `SmartBinRegistry`: row with `onboarding=fleet`, `status=PROVISIONED`.
- Device serial log: `FLEET: provisioned as ... — identity stored` then
  `OPERATIONAL: connected`.

## Security checklist before real rollout

- [ ] Turn on the shared-secret gate: set `PROVISIONING_SECRET` env var on the
      hook Lambda and send a matching `provisioning_secret` in the metadata.
- [ ] Move WiFi provisioning to `WIFI_PROV_SECURITY_2` (SRP6a) and a per-batch PoP.
- [ ] Enable **NVS encryption** + **Flash encryption** + **Secure Boot v2** on
      production boards so the stored device key and claim cert can't be dumped.
- [ ] Keep the claim policy minimal (it already is) and be ready to deactivate
      the claim cert if a device is compromised — issued device certs keep working.

## Scaling & lifecycle

- The IoT Rule already uses `smartbin/+/telemetry`, so new bins need no rule
  changes.
- Decommission: deactivate+delete the device cert, set registry `status=RETIRED`.
- Re-deploy: factory-reset (clear NVS) → device re-onboards as a new/renamed bin.
- OTA: add OTA partitions and push firmware with AWS IoT Jobs to the
  `SmartBinFleet` Thing group (canary a few bins first).

## How this changes the existing v2 system

- The Admin "Register bin" form still works for back-office/manual entries, but
  field devices now self-register via the hook — both write the same
  `SmartBinRegistry` table, so the dashboard is unchanged.
- Telemetry pipeline (IoT Rule → SmartBinAPI → DynamoDB → API → dashboard) is
  untouched; only the device *identity* path changed.
