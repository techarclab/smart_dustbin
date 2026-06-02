# SmartBin Firmware Additions — battery, signal, proximity, multi-bin

> These are **drop-in additions** to your existing, confirmed-working `main.c`.
> They are purely additive. **Do not change** the MQTT config, cert lengths,
> client ID, or `esp_crt_bundle_attach` — those stay exactly as documented.
>
> What's added:
> 1. Battery percent via ADC (real reading)
> 2. WiFi signal strength (RSSI) via `esp_wifi_sta_get_ap_info`
> 3. Two extra JSON fields in the telemetry payload: `battery`, `rssi`
> 4. A proximity-sensor variant for **bin003** (same fill formula)
> 5. Per-device `DEVICE_ID` for the 3-bin fleet

---

## 1. New `#define`s (add near your existing defines)

```c
// ---- Battery sense (ADC) ----
// Wire the battery through a divider into an ADC-capable GPIO.
// ESP32-C3 ADC1 channels live on GPIO0..GPIO4. GPIO2 used here.
#define BATTERY_ADC_GPIO        GPIO_NUM_2
#define BATTERY_ADC_CHANNEL     ADC_CHANNEL_2     // GPIO2 -> ADC1_CH2 on C3
#define BATTERY_DIVIDER_RATIO   2.0f              // e.g. 100k/100k divider = 2.0
#define BATTERY_FULL_V          4.20f             // Li-ion full
#define BATTERY_EMPTY_V         3.30f             // Li-ion empty (cutoff)

// ---- Proximity bin (bin003) ----
// Define USE_PROXIMITY_SENSOR only on the bin003 build.
// Leave it commented for the two ultrasonic bins (bin001, bin002).
// #define USE_PROXIMITY_SENSOR
#define PROXIMITY_PIN           GPIO_NUM_6        // digital out of proximity module
```

For the three devices, set `DEVICE_ID` per board before flashing:

```c
#define DEVICE_ID  "bin001"   // ultrasonic  (board #1)
// #define DEVICE_ID  "bin002"   // ultrasonic  (board #2)
// #define DEVICE_ID  "bin003"   // proximity   (board #3) — also enable USE_PROXIMITY_SENSOR
```

---

## 2. Battery reading (ADC oneshot)

Add the include and a small helper. Uses the ESP-IDF v5/6 `adc_oneshot` API.

```c
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"

static adc_oneshot_unit_handle_t s_adc1 = NULL;
static adc_cali_handle_t         s_adc_cali = NULL;

static void battery_init(void)
{
    adc_oneshot_unit_init_cfg_t init = { .unit_id = ADC_UNIT_1 };
    adc_oneshot_new_unit(&init, &s_adc1);

    adc_oneshot_chan_cfg_t chan = {
        .atten    = ADC_ATTEN_DB_12,      // full ~0..3.3V range
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    adc_oneshot_config_channel(s_adc1, BATTERY_ADC_CHANNEL, &chan);

    // Curve-fitting calibration (C3 supports this scheme)
    adc_cali_curve_fitting_config_t cali = {
        .unit_id  = ADC_UNIT_1,
        .chan     = BATTERY_ADC_CHANNEL,
        .atten    = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    adc_cali_create_scheme_curve_fitting(&cali, &s_adc_cali);
}

// Returns battery percent 0..100
static int battery_read_percent(void)
{
    int raw = 0, mv = 0, acc = 0;
    for (int i = 0; i < 16; i++) {                 // average 16 samples
        adc_oneshot_read(s_adc1, BATTERY_ADC_CHANNEL, &raw);
        if (s_adc_cali) {
            adc_cali_raw_to_voltage(s_adc_cali, raw, &mv);
        } else {
            mv = (raw * 3300) / 4095;              // crude fallback
        }
        acc += mv;
    }
    float pin_v = (acc / 16) / 1000.0f;            // volts at the ADC pin
    float batt_v = pin_v * BATTERY_DIVIDER_RATIO;  // undo the divider

    float pct = (batt_v - BATTERY_EMPTY_V) /
                (BATTERY_FULL_V - BATTERY_EMPTY_V) * 100.0f;
    if (pct < 0)   pct = 0;
    if (pct > 100) pct = 100;
    return (int)(pct + 0.5f);
}
```

Call `battery_init();` once inside `app_main()` after WiFi is up.

> If you wired a different divider, set `BATTERY_DIVIDER_RATIO` to
> `(R1 + R2) / R2`. A 100k/100k divider gives 2.0.

---

## 3. WiFi signal strength (RSSI)

```c
#include "esp_wifi.h"

// Returns RSSI in dBm (negative). 0 if not connected.
static int wifi_read_rssi(void)
{
    wifi_ap_record_t ap;
    if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {
        return ap.rssi;        // e.g. -58
    }
    return 0;
}
```

---

## 4. Proximity bin (bin003) — same fill formula

Your existing ultrasonic routine produces `distance_cm`. For the proximity
board we produce the **same `distance_cm` variable** so the rest of the code,
the fill formula, and AWS are identical — no special-casing downstream.

Most digital proximity modules (IR / inductive with a pot) output LOW when an
object is within the set range and HIGH otherwise. We map that to a near/far
distance so `fill_percent` still comes out via the existing formula:

```c
#include "driver/gpio.h"

static void proximity_init(void)
{
    gpio_config_t io = {
        .pin_bit_mask = 1ULL << PROXIMITY_PIN,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
    };
    gpio_config(&io);
}

// Returns a distance_cm compatible with the ultrasonic path.
// Triggered (waste detected near top) -> small distance -> high fill.
static float proximity_read_distance_cm(void)
{
    int level = gpio_get_level(PROXIMITY_PIN);   // 0 = object detected
    // detected => near the sensor at the top => ~5 cm clearance (≈full)
    // not detected => clear => full bin height (≈empty)
    return (level == 0) ? 5.0f : (float)BIN_HEIGHT_CM;
}
```

Then where you currently read the ultrasonic sensor, branch on the build flag:

```c
float distance_cm;
#ifdef USE_PROXIMITY_SENSOR
    distance_cm = proximity_read_distance_cm();
#else
    distance_cm = ultrasonic_read_distance_cm();   // your existing function
#endif

// existing fill formula stays untouched:
float fill_percent = ((BIN_HEIGHT_CM - distance_cm) / BIN_HEIGHT_CM) * 100.0f;
if (fill_percent < 0)   fill_percent = 0;     // clamp (recommended)
if (fill_percent > 100) fill_percent = 100;
```

> If your proximity module is actually **analog** (outputs a varying voltage
> with distance, e.g. Sharp GP2Y0A), read it on an ADC channel instead and
> convert to cm — then the fill formula works with a true continuous value.
> The digital mapping above is the safe default for a simple on/off module.

---

## 5. Updated telemetry payload

Add `battery` and `rssi` to the JSON you publish. Everything else is unchanged.

```c
int battery = battery_read_percent();
int rssi    = wifi_read_rssi();

char payload[384];
snprintf(payload, sizeof(payload),
    "{"
      "\"bin_id\":\"%s\","
      "\"fill_percent\":%d,"
      "\"distance_cm\":%.1f,"
      "\"status\":\"%s\","
      "\"battery\":%d,"
      "\"rssi\":%d,"
      "\"latitude\":%s,"
      "\"longitude\":%s,"
      "\"location\":\"%s\","
      "\"alert\":%s,"
      "\"timestamp\":%lld"
    "}",
    DEVICE_ID,
    (int)fill_percent,
    distance_cm,
    status_str,                       // "OK" / "HALF" / "FULL"
    battery,
    rssi,
    FAKE_LATITUDE, FAKE_LONGITUDE, FAKE_LOCATION_NAME,
    (fill_percent >= ALERT_THRESHOLD) ? "true" : "false",
    (long long)(esp_timer_get_time() / 1000000)
);
```

The new fields flow automatically: IoT Rule (`SELECT *`) → Lambda stores them in
DynamoDB → dashboard shows Battery and Signal.

---

## 6. Per-board flashing recap

| Board | `DEVICE_ID` | Sensor | Extra flag |
|-------|-------------|--------|------------|
| #1 | `bin001` | HC-SR04 ultrasonic | — |
| #2 | `bin002` | HC-SR04 ultrasonic | — |
| #3 | `bin003` | Proximity | `#define USE_PROXIMITY_SENSOR` |

For each board: register it in the **Admin panel** first → download the
returned `certificate.pem.crt` + `private.pem.key` → drop them in `main/certs/`
→ set `DEVICE_ID` (and the proximity flag for #3) → `idf.py build flash`.
```
```
