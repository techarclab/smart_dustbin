# SmartBin — Master Build Guide (from zero to industrial fleet)

Build the entire platform in order. Each phase ends with a **CHECKPOINT** —
don't move on until it passes. File names in `code font` are the files in this
folder. Region everywhere: **eu-north-1**. Account: **978236789991**.

Strategy: get ONE bin fully working with the simple path first (Phases 1–6),
then upgrade to zero-touch fleet provisioning (Phases 7–8), then harden/scale
(Phase 9). Working-first, fancy-later.

---

## PHASE 0 — Tools & accounts (one time)

1. Install **AWS CLI v2** and run `aws configure` (region `eu-north-1`, JSON).
   - Verify: `aws sts get-caller-identity` shows account `978236789991`.
2. Install **ESP-IDF v6.x** (Espressif's installer) + VS Code ESP-IDF extension.
3. Install **Node.js 18+** (for the React dashboard).
4. Install a serial monitor (the ESP-IDF one is fine) and know your COM port.

**CHECKPOINT 0:** `aws sts get-caller-identity`, `idf.py --version`, and
`node -v` all succeed.

---

## PHASE 1 — Hardware assembly

Per bin you need: 1 ESP32 board, fill sensor(s), 5V power, jumper wires.

1. **Ultrasonic bin (HC-SR04):** mount the sensor at the TOP, pointing DOWN.
   - VCC → 5V, GND → GND, TRIG → GPIO18, ECHO → GPIO19.
   - ECHO is 5V; protect the GPIO with a divider (ECHO → 1k → GPIO19, then 2k → GND).
2. **Proximity bin (IR/overflow):** sensor digital out → GPIO23, VCC/GND as specified.
3. **(Optional) battery sense:** battery → 100k/100k divider → GPIO34 (ADC1).

**CHECKPOINT 1:** board powers on, sensor LED/indicator lights, nothing gets hot.

---

## PHASE 2 — AWS foundation

1. **IoT endpoint:** `aws iot describe-endpoint --endpoint-type iot:Data-ATS`
   → note it (used in firmware).
2. **Base IoT policy** (for the simple path): create `SmartBinPolicy` allowing
   `iot:Connect/Publish/Subscribe/Receive` (start permissive, tighten later).
3. Confirm region is `eu-north-1` for ALL services.

**CHECKPOINT 2:** `aws iot get-policy --policy-name SmartBinPolicy` returns it.

---

## PHASE 3 — Backend: data + APIs

Files: `lambda_telemetry.py`, `lambda_register.py`, `deploy.ps1` (or `deploy.sh`).

1. Put those three files in one folder.
2. Run the deployer:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\deploy.ps1
   ```
   It creates DynamoDB tables `SmartBinTelemetry` + `SmartBinRegistry`, the two
   Lambdas, the API routes (`GET /telemetry`, `GET /bins`, `POST /bins`), CORS,
   and permissions. It prints your base URL.
3. Confirm the **IoT Rule** `SmartBinTelemetryRule`
   (`SELECT * FROM 'smartbin/+/telemetry'`) targets the `SmartBinAPI` Lambda.

**CHECKPOINT 3:**
```
curl https://<your-api>/bins        -> {"count":0,"bins":[]}
curl https://<your-api>/telemetry   -> {}
```

---

## PHASE 4 — Dashboard

File: `smartbin_dashboard.jsx`.

1. Scaffold a React app: `npm create vite@latest smartbin -- --template react`
   then `cd smartbin && npm install`.
2. `npm install lucide-react` and set up TailwindCSS.
3. Drop `smartbin_dashboard.jsx` in as your main component; set `API_BASE` to
   your base URL from Phase 3.
4. `npm run dev` → open the localhost URL.

**CHECKPOINT 4:** dashboard loads. With no data it shows the demo banner; that's
expected until a device or the Admin tab adds real data.

---

## PHASE 5 — First bin live (SIMPLE path)

Goal: prove the whole pipeline with one bin before automating.

1. **Register the bin** in the dashboard's **Admin** tab (bin001, ward, route,
   GPS). It auto-creates the IoT Thing + certificate and shows the cert + key.
2. **Save** `certificate.pem.crt` and `private.pem.key`.
3. **Firmware:** open `main.c`, set `#define DEVICE_ID "bin001"`, put the two
   files in `main/certs/`, embed them in `main/CMakeLists.txt`.
4. Build + flash:
   ```
   idf.py set-target esp32
   idf.py build
   idf.py -p COMxx flash monitor
   ```
5. Watch for `MQTT connected` and `Telemetry Published`.

**CHECKPOINT 5:** the bin appears live on the **Dashboard** tab with fill %,
distance, signal, and a status. The whole loop works end to end.

> Repeat for bin002 (ultrasonic) and bin003 (proximity: also uncomment
> `#define USE_PROXIMITY_SENSOR`). You now have a working multi-bin system.

---

## PHASE 6 — (Optional) alerts & polish

- Wire an SNS "bin full" alert: IoT Rule on `smartbin/+/alert` → SNS topic →
  email/SMS. (Ask me to generate this; firmware already publishes alerts.)
- Tighten `SmartBinPolicy` from `Resource:"*"` to specific topic ARNs.

**CHECKPOINT 6:** filling a bin past 80% turns it red on the dashboard (and
emails you, if SNS is wired).

---

## PHASE 7 — Upgrade to ZERO-TOUCH provisioning (industrial)

Read `INDUSTRIAL_PROVISIONING_ARCHITECTURE.md` first. Files:
`fleet_provisioning_template.json`, `claim_policy.json`, `device_policy.json`,
`lambda_preprovision_hook.py`, `deploy_fleet_provisioning.ps1`.

1. Run the fleet setup:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\deploy_fleet_provisioning.ps1
   ```
   Creates the provisioning template, claim cert (→ `./claim_certs/`), both
   policies, the IoT role, and the pre-provisioning hook Lambda.
2. **Build the factory image** from `main_provisioning.c`:
   - Copy `claim_certs/claim.cert.pem` and `claim.private.key` into
     `main/certs/claim/`.
   - Use the `idf_component_register(... EMBED_TXTFILES ...)` line from
     `FLEET_PROVISIONING_ROLLOUT.md`.
   - `idf.py build`. Flash this SAME image to every board (no per-device certs).

**CHECKPOINT 7:** a freshly flashed board boots into SoftAP
`SmartBin-Setup-XXXX`.

---

## PHASE 8 — Field onboarding (per bin, zero-touch)

1. Power on the bin → it makes its own WiFi hotspot.
2. On a phone, open **ESP SoftAP Prov** app, enter PoP `smartbin-pop-001`,
   choose site WiFi + password.
3. Send install metadata to the `installation` endpoint (bin_id, bin_name,
   ward, route, place, GPS).
4. Device stores it, reboots, connects, runs Fleet Provisioning, gets its
   unique cert, registers itself, and starts telemetry — no cables, no certs.

**CHECKPOINT 8:** hook Lambda logs show `ALLOW: provisioning bin_id=...`; the
Thing `esp32-smartbin-<bin_id>` exists; the bin is live on the dashboard.

---

## PHASE 9 — Hardening & scale (before real deployment)

- [ ] Enable the hook's `PROVISIONING_SECRET` gate and send it from the device.
- [ ] Move WiFi provisioning to `WIFI_PROV_SECURITY_2`.
- [ ] Enable **NVS encryption + Flash encryption + Secure Boot v2** on boards.
- [ ] Adopt the scoped `SmartBinDevicePolicy` for all devices.
- [ ] Add **OTA** (OTA partitions + AWS IoT Jobs to the `SmartBinFleet` group).
- [ ] For high volume, move telemetry to a time-series table or Timestream.
- [ ] Add Google Maps to the dashboard's location view.

**CHECKPOINT 9:** a fresh board can be deployed by a non-technical operator with
only a phone, securely, and shows up on the map within a minute.

---

## Quick file map

| Need | File |
|------|------|
| Backend Lambdas | `lambda_telemetry.py`, `lambda_register.py` |
| Backend deploy | `deploy.ps1` / `deploy.sh` |
| Dashboard | `smartbin_dashboard.jsx` |
| Simple firmware | `main.c` |
| AWS step-by-step (detail) | `AWS_SETUP_STEP_BY_STEP.md` |
| Provisioning design | `INDUSTRIAL_PROVISIONING_ARCHITECTURE.md` |
| Provisioning AWS setup | `deploy_fleet_provisioning.ps1` + the 3 JSON + hook |
| Zero-touch firmware | `main_provisioning.c` |
| Provisioning rollout | `FLEET_PROVISIONING_ROLLOUT.md` |

Build Phases 1–5 first. Everything after is enhancement.
