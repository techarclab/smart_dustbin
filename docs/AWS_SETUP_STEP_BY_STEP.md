# SmartBin v2 — AWS Setup, Step by Step (point to point)

This walks you from your working v1 system to the full v2 (multi-bin dashboard,
admin panel, automated registration) — in order, nothing skipped.

You can use **Path A (automated, one command)** or **Path B (manual console
clicks)**. Do ONE of them, then continue to "Verify" and "Onboard the 3 bins".

Region everywhere: **eu-north-1**.  Account: **978236789991**.

---

## 0. What you are building

```
3x ESP32  ──MQTT/TLS──►  IoT Core  ──IoT Rule──►  Lambda: SmartBinAPI
                                                       │ writes latest per bin
                                                       ▼
                                            DynamoDB: SmartBinTelemetry
                                                       ▲ GET /telemetry
                          DynamoDB: SmartBinRegistry   │
                                   ▲                    │
   Admin panel ──POST /bins──► Lambda: SmartBinRegister │
   (creates Thing + cert)                               │
                                            API Gateway (HTTP API)
                                                       │
                                            React dashboard
```

Two NEW tables, ONE new Lambda (SmartBinRegister), ONE upgraded Lambda
(SmartBinAPI), THREE new API routes. The IoT Core Thing/cert/policy and the
IoT Rule from v1 stay as they are.

---

## 1. Prerequisites (do this once)

1. **Install AWS CLI v2** — https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html
2. **Configure credentials** for the account:
   ```bash
   aws configure
   # Access key / secret for account 978236789991
   # Default region: eu-north-1
   # Output format: json
   ```
   Verify:
   ```bash
   aws sts get-caller-identity
   # Account should read 978236789991
   ```
3. **Gather the files** in one folder: `lambda_telemetry.py`, `lambda_register.py`, `deploy.sh`.
4. Confirm the v1 IoT policy exists:
   ```bash
   aws iot get-policy --policy-name SmartBinPolicy --region eu-north-1
   ```

---

## PATH A — Automated (recommended)

```bash
cd /folder/with/the/files
bash deploy.sh
```

That single script does every step in Path B for you (tables, IAM, Lambdas,
API routes, CORS, permissions) and is safe to re-run. When it finishes it
prints your base URL. Then jump to **Verify**.

> If you prefer to understand/do each piece by hand, follow Path B instead.

---

## PATH B — Manual console, step by step

### Step 1 — Create the telemetry table

AWS Console → **DynamoDB** → Tables → **Create table**
- Table name: `SmartBinTelemetry`
- Partition key: `bin_id`  (type **String**)
- Settings: **On-demand** (PAY_PER_REQUEST)
- Create table.

CLI equivalent:
```bash
aws dynamodb create-table \
  --table-name SmartBinTelemetry \
  --attribute-definitions AttributeName=bin_id,AttributeType=S \
  --key-schema AttributeName=bin_id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST --region eu-north-1
```

### Step 2 — Create the registry table (with a name index)

DynamoDB → Create table
- Table name: `SmartBinRegistry`
- Partition key: `bin_id` (String)
- On-demand.
- Create table, then open it → **Indexes** tab → **Create index**:
  - Partition key: `bin_name` (String)
  - Index name: `bin_name-index`
  - Create. (This lets the duplicate-name check run fast.)

CLI equivalent:
```bash
aws dynamodb create-table \
  --table-name SmartBinRegistry \
  --attribute-definitions AttributeName=bin_id,AttributeType=S AttributeName=bin_name,AttributeType=S \
  --key-schema AttributeName=bin_id,KeyType=HASH \
  --global-secondary-indexes 'IndexName=bin_name-index,KeySchema=[{AttributeName=bin_name,KeyType=HASH}],Projection={ProjectionType=ALL}' \
  --billing-mode PAY_PER_REQUEST --region eu-north-1
```

### Step 3 — Let the telemetry Lambda write to DynamoDB

Console → **IAM** → Roles → search `SmartBinProcessor-role-fxlipadp` →
**Add permissions** → **Create inline policy** → JSON tab → paste:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["dynamodb:PutItem","dynamodb:GetItem","dynamodb:Scan"],
    "Resource": [
      "arn:aws:dynamodb:eu-north-1:978236789991:table/SmartBinTelemetry",
      "arn:aws:dynamodb:eu-north-1:978236789991:table/SmartBinTelemetry/index/*"
    ]
  }]
}
```
Name it `SmartBinTelemetryDDB` → Create.

### Step 4 — Create the registration Lambda's role

IAM → Roles → **Create role** → Trusted entity: **AWS service** → **Lambda** → Next.
- Attach `AWSLambdaBasicExecutionRole` (for CloudWatch logs). Next.
- Role name: `SmartBinRegisterRole` → Create.
- Open the role → **Create inline policy** → JSON → paste:
```json
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
      "Resource": [
        "arn:aws:dynamodb:eu-north-1:978236789991:table/SmartBinRegistry",
        "arn:aws:dynamodb:eu-north-1:978236789991:table/SmartBinRegistry/index/*"
      ]
    }
  ]
}
```
Name it `SmartBinRegisterInline` → Create.

### Step 5 — Update the telemetry Lambda (SmartBinAPI)

Console → **Lambda** → `SmartBinAPI`:
- **Code** tab → replace the code with the contents of `lambda_telemetry.py` → **Deploy**.
- **Configuration** → **Environment variables** → add:
  `TELEMETRY_TABLE = SmartBinTelemetry`
- **Configuration** → **General configuration** → Timeout: `15s` → Save.

### Step 6 — Create the registration Lambda (SmartBinRegister)

Lambda → **Create function** → Author from scratch:
- Name: `SmartBinRegister`
- Runtime: **Python 3.12**
- Architecture: x86_64
- Permissions → Use an existing role → `SmartBinRegisterRole`
- Create function.
Then:
- **Code** tab → paste `lambda_register.py` → **Deploy**.
- **Configuration** → Environment variables → add three:
  `REGISTRY_TABLE = SmartBinRegistry`
  `IOT_POLICY_NAME = SmartBinPolicy`
  `THING_PREFIX = esp32c3-smartbin-`
- Timeout: `30s` → Save.

### Step 7 — Wire the API Gateway routes

Console → **API Gateway** → open your HTTP API (id `aoacx6u7g2`).

Create the integrations + routes:
- **Routes** → **Create**:
  - `GET /telemetry`  → attach integration → Lambda → `SmartBinAPI`
  - `GET /bins`       → attach integration → Lambda → `SmartBinRegister`
  - `POST /bins`      → attach integration → Lambda → `SmartBinRegister`
  (When you attach a Lambda integration the console auto-adds the invoke
  permission. If asked, allow it.)
- **CORS** (left menu) → Configure:
  - Access-Control-Allow-Origin: `*`
  - Access-Control-Allow-Methods: `GET, POST, OPTIONS`
  - Access-Control-Allow-Headers: `content-type`
  - Save.
- Routes deploy automatically on the `default` stage (auto-deploy on).

### Step 8 — Confirm the IoT Rule (no change, just verify)

Console → **IoT Core** → Message routing → **Rules** → `SmartBinTelemetryRule`:
- SQL is `SELECT * FROM 'smartbin/+/telemetry'`
- Action target is the `SmartBinAPI` Lambda.
That's all — telemetry now lands in DynamoDB via the upgraded Lambda.

---

## Verify

1. **List bins (empty at first):**
   ```bash
   curl https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com/bins
   # {"count":0,"bins":[]}
   ```
2. **Register a test bin:**
   ```bash
   curl -X POST https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com/bins \
     -H "Content-Type: application/json" \
     -d '{"bin_id":"bin001","bin_name":"Test One","place":"Charminar","ward":"Ward 12","route":"Route A","capacity":200,"latitude":17.3616,"longitude":78.4747}'
   ```
   You should get `201` with a `certificate_pem` and `private_key`.
   Then check IoT Core → Things — `esp32c3-smartbin-bin001` should exist.
3. **Telemetry endpoint:**
   ```bash
   curl https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com/telemetry
   # {} until a device publishes, then {"bin001":{...}}
   ```
4. **Duplicate guard:** repeat the POST — you should get `409 already exists`.

---

## Onboard the 3 real bins (via the dashboard)

1. Run the dashboard (`smartbin_dashboard.jsx` in a React app — `npm run dev`).
2. **Admin** tab → register each bin:
   - bin001 — ultrasonic — its place/ward/route/GPS
   - bin002 — ultrasonic
   - bin003 — proximity
3. On each success, the panel shows `certificate.pem.crt` + `private.pem.key`
   **once** — copy/save both immediately.

## Flash the 3 boards

For each board, in `main/certs/` put that bin's `certificate.pem.crt` +
`private.pem.key`, then in `main.c` set:
- bin001: `#define DEVICE_ID "bin001"`  (ultrasonic — leave proximity off)
- bin002: `#define DEVICE_ID "bin002"`  (ultrasonic)
- bin003: `#define DEVICE_ID "bin003"`  AND uncomment `#define USE_PROXIMITY_SENSOR`

```bash
idf.py build
idf.py -p COMxx flash monitor
```
Watch for `MQTT connected to AWS IoT Core` and `Telemetry Published`.

## Confirm live

Open the dashboard → all three bins appear with fill %, clearance, battery,
signal, and Optimal/Watch/Critical status. The "Recent telemetry" panel ticks
every few seconds.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|--------|--------------|-----|
| `/telemetry` returns `{}` forever | IoT Rule not hitting upgraded Lambda, or device not publishing | Check IoT Core → MQTT test client on `smartbin/+/telemetry`; check device serial |
| Register returns 500 "IoT provisioning failed" | `SmartBinRegisterRole` missing IoT perms | Re-check Step 4 inline policy |
| Register returns 500 "DynamoDB ..." | table/index missing or Lambda env var wrong | Verify table names + GSI `bin_name-index` |
| Two bins keep disconnecting | duplicate MQTT client_id | Use the v2 firmware (client_id derives from DEVICE_ID) |
| Dashboard shows demo banner | API unreachable / CORS | Confirm routes deployed + CORS set (Step 7) |
| Battery always 0 or wrong | divider ratio / wrong ADC pin | Battery on GPIO34 (ADC1), set BATTERY_DIVIDER_RATIO |

> Note: the IoT policy is still `Resource:"*"`. Fine for the project; tighten
> to specific topic/client ARNs before any production use.
