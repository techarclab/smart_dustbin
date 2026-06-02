/*
 * SmartBin — Zero-Touch Provisioning Firmware (ESP-IDF v5.x/v6.x)
 * ==============================================================
 * Self-contained captive portal (no wifi_provisioning dependency).
 *
 * Boot state machine:
 *   BOOT -> [no WiFi creds in NVS?] CAPTIVE_PORTAL (SoftAP + web form)
 *        -> CONNECT_WIFI (STA, stored creds)
 *        -> [no device cert?] FLEET_PROVISION (claim cert -> unique cert)
 *        -> OPERATIONAL (telemetry)
 *
 * Field UX: operator connects phone/laptop to WiFi "SmartBin-Setup-XXXX",
 * opens http://192.168.4.1/ in any browser, fills WiFi + bin details, submits.
 * Creds + metadata go to NVS; device reboots and onboards itself to AWS via
 * Fleet Provisioning by claim (AWS-generated keys).
 *
 * Components (main/CMakeLists.txt REQUIRES):
 *   nvs_flash esp_wifi esp_event esp_netif esp_http_server mqtt esp-tls
 *   mbedtls json esp_driver_gpio esp_timer esp_hw_support
 * Embed the SHARED claim cert/key via EMBED_TXTFILES:
 *   certs/claim/claim.cert.pem  certs/claim/claim.private.key
 */

#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "lwip/dns.h"
#include "esp_https_ota.h"
#include "esp_ota_ops.h"
#include "esp_wifi.h"
#include "esp_mac.h"
#include "esp_http_server.h"
#include "esp_http_client.h"
#include <stdlib.h>
#include <ctype.h>
#include "nvs_flash.h"
#include "nvs.h"
#include "mqtt_client.h"
#include "esp_crt_bundle.h"
#include "driver/gpio.h"

#define AWS_IOT_ENDPOINT     "a1v5cwm72cbvh5-ats.iot.eu-north-1.amazonaws.com"
#define AWS_IOT_PORT         8883
#define FW_VERSION           "1.0.0"   /* bump on every OTA build */
#define FLEET_TEMPLATE       "SmartBinFleetTemplate"
#define GOOGLE_GEO_API_KEY   "YOUR_GOOGLE_API_KEY"   /* Geolocation API key */
#define AP_PREFIX            "SmartBin-Setup-"
#define NVS_NS               "smartbin"
#define PUBLISH_INTERVAL_MS  2000
#define BIN_HEIGHT_CM        200
#define ALERT_THRESHOLD      80
#define TRIG_PIN             18
#define ECHO_PIN             19

static const char *TAG = "SMARTBIN";

extern const uint8_t claim_cert_start[] asm("_binary_claim_cert_pem_start");
extern const uint8_t claim_cert_end[]   asm("_binary_claim_cert_pem_end");
extern const uint8_t claim_key_start[]  asm("_binary_claim_private_key_start");
extern const uint8_t claim_key_end[]    asm("_binary_claim_private_key_end");

typedef struct {
    char bin_id[40], bin_name[64], ward[32], route[32], place[64], lat[24], lng[24];
} bin_meta_t;

static bin_meta_t g_meta;
static char g_thing_name[64], g_dev_cert[2048], g_dev_key[2048], g_topic_tel[80];
static char g_ssid[40], g_pass[64], g_ownership_token[1100];

static EventGroupHandle_t g_events;
#define WIFI_OK_BIT   BIT0
#define PROV_DONE_BIT BIT1   /* fleet provisioning finished */
#define FORM_OK_BIT   BIT2   /* captive-portal form submitted */

static esp_mqtt_client_handle_t g_mqtt = NULL;
static httpd_handle_t g_httpd = NULL;

/* ---- NVS helpers ----------------------------------------------------------*/
static esp_err_t nv_set(const char *k, const char *v) {
    nvs_handle_t h; esp_err_t e = nvs_open(NVS_NS, NVS_READWRITE, &h);
    if (e != ESP_OK) return e;
    e = nvs_set_str(h, k, v); if (e == ESP_OK) e = nvs_commit(h);
    nvs_close(h); return e;
}
static bool nv_get(const char *k, char *out, size_t cap) {
    nvs_handle_t h; if (nvs_open(NVS_NS, NVS_READONLY, &h) != ESP_OK) return false;
    size_t len = cap; esp_err_t e = nvs_get_str(h, k, out, &len);
    nvs_close(h); return e == ESP_OK;
}
static bool load_device_identity(void) {
    return nv_get("dev_cert", g_dev_cert, sizeof(g_dev_cert))
        && nv_get("dev_key",  g_dev_key,  sizeof(g_dev_key))
        && nv_get("thing",    g_thing_name, sizeof(g_thing_name));
}
static void load_meta(void) {
    nv_get("bin_id", g_meta.bin_id, sizeof(g_meta.bin_id));
    nv_get("bin_name", g_meta.bin_name, sizeof(g_meta.bin_name));
    nv_get("ward", g_meta.ward, sizeof(g_meta.ward));
    nv_get("route", g_meta.route, sizeof(g_meta.route));
    nv_get("place", g_meta.place, sizeof(g_meta.place));
    nv_get("lat", g_meta.lat, sizeof(g_meta.lat));
    nv_get("lng", g_meta.lng, sizeof(g_meta.lng));
}

/* ---- tiny URL decoder (for form fields) -----------------------------------*/
static void url_decode(char *s) {
    char *o = s;
    for (char *p = s; *p; p++) {
        if (*p == '+') { *o++ = ' '; }
        else if (*p == '%' && p[1] && p[2]) {
            char hex[3] = { p[1], p[2], 0 };
            *o++ = (char)strtol(hex, NULL, 16); p += 2;
        } else { *o++ = *p; }
    }
    *o = 0;
}


/* ---- minimal JSON string extractor (no cJSON dependency) ------------------*/
static bool json_str(const char *src, const char *key, char *out, size_t cap) {
    char pat[48];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(src, pat);
    if (!p) return false;
    p += strlen(pat);
    while (*p == ' ' || *p == ':') p++;
    if (*p != '"') return false;
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i + 1 < cap) {
        if (*p == '\\' && p[1]) {
            p++;
            switch (*p) {
                case 'n': out[i++] = '\n'; break;
                case 'r': out[i++] = '\r'; break;
                case 't': out[i++] = '\t'; break;
                case '/': out[i++] = '/';  break;
                case '"': out[i++] = '"';  break;
                case '\\': out[i++] = '\\'; break;
                default:  out[i++] = *p;   break;
            }
            p++;
        } else {
            out[i++] = *p++;
        }
    }
    out[i] = 0;
    return true;
}

/* ==========================================================================
   WiFi-based geolocation (Google Geolocation API) - device finds its own GPS
   ========================================================================== */
static char s_geo[1024];
static int  s_geo_len;

static esp_err_t geo_evt(esp_http_client_event_t *e) {
    if (e->event_id == HTTP_EVENT_ON_DATA && s_geo_len + e->data_len < (int)sizeof(s_geo) - 1) {
        memcpy(s_geo + s_geo_len, e->data, e->data_len);
        s_geo_len += e->data_len; s_geo[s_geo_len] = 0;
    }
    return ESP_OK;
}

/* extract a JSON numeric value (e.g. "lat": 17.38) as a string */
static bool json_num(const char *src, const char *key, char *out, size_t cap) {
    char pat[40]; snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(src, pat); if (!p) return false;
    p += strlen(pat);
    while (*p == ' ' || *p == ':') p++;
    size_t i = 0;
    while (*p && (isdigit((int)*p) || *p=='-' || *p=='+' || *p=='.' || *p=='e' || *p=='E') && i+1 < cap)
        out[i++] = *p++;
    out[i] = 0;
    return i > 0;
}

static void geolocate_via_wifi(void) {
    if (!GOOGLE_GEO_API_KEY[0] || GOOGLE_GEO_API_KEY[0] == 'Y') {
        ESP_LOGW(TAG, "No Geolocation API key set; skipping auto-location");
        return;
    }
    wifi_scan_config_t sc = { .show_hidden = false };
    if (esp_wifi_scan_start(&sc, true) != ESP_OK) { ESP_LOGW(TAG, "WiFi scan failed"); return; }
    uint16_t n = 0; esp_wifi_scan_get_ap_num(&n);
    if (n == 0) { ESP_LOGW(TAG, "no APs to geolocate from"); return; }
    if (n > 20) n = 20;
    wifi_ap_record_t *recs = calloc(n, sizeof(wifi_ap_record_t));
    if (!recs) return;
    esp_wifi_scan_get_ap_records(&n, recs);

    char *body = malloc(2048); if (!body) { free(recs); return; }
    int off = snprintf(body, 2048, "{\"considerIp\":true,\"wifiAccessPoints\":[");
    for (int i = 0; i < n; i++) {
        off += snprintf(body + off, 2048 - off,
            "%s{\"macAddress\":\"%02x:%02x:%02x:%02x:%02x:%02x\",\"signalStrength\":%d}",
            i ? "," : "", recs[i].bssid[0], recs[i].bssid[1], recs[i].bssid[2],
            recs[i].bssid[3], recs[i].bssid[4], recs[i].bssid[5], recs[i].rssi);
        if (off > 1900) break;
    }
    off += snprintf(body + off, 2048 - off, "]}");
    free(recs);

    s_geo_len = 0; s_geo[0] = 0;
    esp_http_client_config_t cfg = {
        .url = "https://www.googleapis.com/geolocation/v1/geolocate?key=" GOOGLE_GEO_API_KEY,
        .event_handler = geo_evt, .crt_bundle_attach = esp_crt_bundle_attach,
        .timeout_ms = 10000, .method = HTTP_METHOD_POST,
    };
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    esp_http_client_set_header(c, "Content-Type", "application/json");
    esp_http_client_set_post_field(c, body, off);
    esp_err_t err = esp_http_client_perform(c);
    int status = esp_http_client_get_status_code(c);
    esp_http_client_cleanup(c);
    free(body);

    char lat[24], lng[24];
    if (err == ESP_OK && status == 200 &&
        json_num(s_geo, "lat", lat, sizeof(lat)) && json_num(s_geo, "lng", lng, sizeof(lng))) {
        strlcpy(g_meta.lat, lat, sizeof(g_meta.lat));
        strlcpy(g_meta.lng, lng, sizeof(g_meta.lng));
        nv_set("lat", g_meta.lat); nv_set("lng", g_meta.lng);
        ESP_LOGI(TAG, "Geolocation OK: %s, %s", g_meta.lat, g_meta.lng);
    } else {
        ESP_LOGW(TAG, "Geolocation failed (status=%d): %.160s", status, s_geo);
    }
}

/* ==========================================================================
   PHASE 1 — Captive portal (SoftAP + HTTP form)
   ========================================================================== */

static const char *FORM_HTML =
"<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
"<title>SmartBin Setup</title><style>body{font-family:sans-serif;max-width:420px;margin:24px auto;padding:0 16px}"
"h2{color:#0a7}input{width:100%;padding:8px;margin:6px 0;box-sizing:border-box}"
".note{font-size:13px;color:#555;margin:8px 0}"
"button{width:100%;padding:12px;background:#0a7;color:#fff;border:0;font-size:16px}</style></head>"
"<body><h2>SmartBin Setup</h2><form method=POST action=/save>"
"<b>WiFi</b><input name=ssid placeholder='WiFi name' required>"
"<input name=password type=password placeholder='WiFi password'>"
"<b>Bin details</b>"
"<input name=bin_id placeholder='Bin ID (e.g. bin010)' required>"
"<input name=bin_name placeholder='Bin name' required>"
"<input name=ward placeholder='Ward' required>"
"<input name=route placeholder='Route' required>"
"<input name=place placeholder='Place / landmark' required>"
"<div class=note>Location is detected automatically by the device after it connects.</div>"
"<button type=submit>Save &amp; Connect</button></form></body></html>";

static esp_err_t form_get(httpd_req_t *r) {
    httpd_resp_set_type(r, "text/html");
    return httpd_resp_send(r, FORM_HTML, HTTPD_RESP_USE_STRLEN);
}

static void field(const char *body, const char *key, char *out, size_t cap) {
    if (httpd_query_key_value(body, key, out, cap) == ESP_OK) url_decode(out);
    else out[0] = 0;
}

static esp_err_t save_post(httpd_req_t *r) {
    char buf[600];
    int len = r->content_len < (int)sizeof(buf) - 1 ? r->content_len : (int)sizeof(buf) - 1;
    int got = httpd_req_recv(r, buf, len);
    if (got <= 0) return ESP_FAIL;
    buf[got] = 0;

    field(buf, "ssid", g_ssid, sizeof(g_ssid));
    field(buf, "password", g_pass, sizeof(g_pass));
    field(buf, "bin_name", g_meta.bin_name, sizeof(g_meta.bin_name));
    field(buf, "ward", g_meta.ward, sizeof(g_meta.ward));
    field(buf, "route", g_meta.route, sizeof(g_meta.route));
    field(buf, "place", g_meta.place, sizeof(g_meta.place));

    /* GPS coords from the app: accept either "latitude"/"longitude" or "lat"/"lng" */
    field(buf, "latitude",  g_meta.lat, sizeof(g_meta.lat));
    field(buf, "longitude", g_meta.lng, sizeof(g_meta.lng));
    if (g_meta.lat[0] == 0) field(buf, "lat", g_meta.lat, sizeof(g_meta.lat));
    if (g_meta.lng[0] == 0) field(buf, "lng", g_meta.lng, sizeof(g_meta.lng));

    nv_set("wifi_ssid", g_ssid);  nv_set("wifi_pass", g_pass);
    nv_set("bin_name", g_meta.bin_name);
    nv_set("ward", g_meta.ward);       nv_set("route", g_meta.route);
    nv_set("place", g_meta.place);
    nv_set("lat",  g_meta.lat);        nv_set("lng",  g_meta.lng);

    ESP_LOGI(TAG, "Provisioning POST saved: ssid=%s bin_id=%s gps=%s,%s",
             g_ssid, g_meta.bin_id, g_meta.lat, g_meta.lng);
    const char *ok = "<html><body style='font-family:sans-serif;text-align:center;margin-top:40px'>"
                     "<h2>Saved.</h2><p>Device is connecting to WiFi and onboarding to AWS.</p></body></html>";
    httpd_resp_set_type(r, "text/html");
    httpd_resp_send(r, ok, HTTPD_RESP_USE_STRLEN);
    xEventGroupSetBits(g_events, FORM_OK_BIT);
    return ESP_OK;
}

static void start_captive_portal(void) {
    ESP_LOGI(TAG, "STATE: CAPTIVE_PORTAL");
    esp_netif_create_default_wifi_ap();

    uint8_t mac[6]; esp_read_mac(mac, ESP_MAC_WIFI_SOFTAP);
    wifi_config_t ap = { 0 };
    snprintf((char *)ap.ap.ssid, sizeof(ap.ap.ssid), "SMARTBIN-%02X%02X", mac[4], mac[5]);
    ap.ap.ssid_len = strlen((char *)ap.ap.ssid);
    ap.ap.max_connection = 4;
    /* WPA2-secured AP. Shared common password the app knows -> auto-connect, no prompt. */
    ap.ap.authmode = WIFI_AUTH_WPA2_PSK;
    strlcpy((char *)ap.ap.password, "SmartBin@2024", sizeof(ap.ap.password));

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "SoftAP up: %s  -> connect, open http://192.168.4.1/", (char *)ap.ap.ssid);

    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    ESP_ERROR_CHECK(httpd_start(&g_httpd, &cfg));
    httpd_uri_t u_get  = { .uri = "/",     .method = HTTP_GET,  .handler = form_get };
    httpd_uri_t u_post = { .uri = "/save", .method = HTTP_POST, .handler = save_post };
    httpd_register_uri_handler(g_httpd, &u_get);
    httpd_register_uri_handler(g_httpd, &u_post);
    /* Captive auto-redirect removed: provisioning is done by the mobile app
       (POST /save). The bin's HTTP server only responds when the app calls it. */
}


/* Override DHCP-provided DNS with public ones (Google + Cloudflare).
   Phone hotspots and some routers hand out a broken DNS server, causing
   getaddrinfo() to fail before MQTT can even connect. */
static void set_static_dns(void) {
    ip_addr_t a;
    IP_ADDR4(&a, 8, 8, 8, 8);   dns_setserver(0, &a);
    IP_ADDR4(&a, 1, 1, 1, 1);   dns_setserver(1, &a);
    ESP_LOGI(TAG, "DNS pinned to 8.8.8.8 / 1.1.1.1");
}

/* ==========================================================================
   WiFi (STA) events
   ========================================================================== */
static void wifi_evt(void *a, esp_event_base_t base, int32_t id, void *data) {
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) esp_wifi_connect();
    else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(g_events, WIFI_OK_BIT); esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ESP_LOGI(TAG, "WiFi connected");
        set_static_dns();
        vTaskDelay(pdMS_TO_TICKS(500));   /* let DNS settle before MQTT */
        xEventGroupSetBits(g_events, WIFI_OK_BIT);
    }
}

/* ==========================================================================
   PHASE 2 — Fleet Provisioning over MQTT (claim cert)
   ========================================================================== */
#define T_CREATE      "$aws/certificates/create/json"
#define T_CREATE_ACC  "$aws/certificates/create/json/accepted"
#define T_CREATE_REJ  "$aws/certificates/create/json/rejected"
#define T_REG         "$aws/provisioning-templates/" FLEET_TEMPLATE "/provision/json"
#define T_REG_ACC     "$aws/provisioning-templates/" FLEET_TEMPLATE "/provision/json/accepted"
#define T_REG_REJ     "$aws/provisioning-templates/" FLEET_TEMPLATE "/provision/json/rejected"

static void publish_register_thing(esp_mqtt_client_handle_t c) {
    char body[2048];
    snprintf(body, sizeof(body),
        "{\"certificateOwnershipToken\":\"%s\",\"parameters\":{"
        "\"bin_id\":\"%s\",\"bin_name\":\"%s\",\"ward\":\"%s\","
        "\"route\":\"%s\",\"place\":\"%s\","
        "\"latitude\":\"%s\",\"longitude\":\"%s\"}}",
        g_ownership_token, g_meta.bin_id, g_meta.bin_name, g_meta.ward,
        g_meta.route, g_meta.place, g_meta.lat, g_meta.lng);
    esp_mqtt_client_publish(c, T_REG, body, 0, 1, 0);
    ESP_LOGI(TAG, "RegisterThing published for bin_id=%s", g_meta.bin_id);
}

static void fleet_handler(void *a, esp_event_base_t base, int32_t id, void *ed) {
    esp_mqtt_event_handle_t ev = ed; esp_mqtt_client_handle_t c = ev->client;
    switch ((esp_mqtt_event_id_t)id) {
    case MQTT_EVENT_CONNECTED:
        ESP_LOGI(TAG, "FLEET: claim-cert MQTT connected");
        esp_mqtt_client_subscribe(c, T_CREATE_ACC, 1);
        esp_mqtt_client_subscribe(c, T_CREATE_REJ, 1);
        esp_mqtt_client_subscribe(c, T_REG_ACC, 1);
        esp_mqtt_client_subscribe(c, T_REG_REJ, 1);
        esp_mqtt_client_publish(c, T_CREATE, "{}", 0, 1, 0);
        break;
    case MQTT_EVENT_DATA: {
        static char dbuf[6400];
        char topic[160] = {0};
        int tl = ev->topic_len < 159 ? ev->topic_len : 159;
        memcpy(topic, ev->topic, tl);
        int dl = ev->data_len < (int)sizeof(dbuf) - 1 ? ev->data_len : (int)sizeof(dbuf) - 1;
        memcpy(dbuf, ev->data, dl); dbuf[dl] = 0;

        if (strstr(topic, "certificates/create/json/accepted")) {
            if (json_str(dbuf, "certificatePem", g_dev_cert, sizeof(g_dev_cert)) &&
                json_str(dbuf, "privateKey", g_dev_key, sizeof(g_dev_key)) &&
                json_str(dbuf, "certificateOwnershipToken", g_ownership_token, sizeof(g_ownership_token))) {
                ESP_LOGI(TAG, "FLEET: received unique cert; registering thing");
                publish_register_thing(c);
            }
        } else if (strstr(topic, "provision/json/accepted")) {
            if (json_str(dbuf, "thingName", g_thing_name, sizeof(g_thing_name))) {
                nv_set("dev_cert", g_dev_cert);
                nv_set("dev_key",  g_dev_key);
                nv_set("thing",    g_thing_name);
                ESP_LOGI(TAG, "FLEET: provisioned as %s - identity stored", g_thing_name);
                xEventGroupSetBits(g_events, PROV_DONE_BIT);
            }
        } else if (strstr(topic, "rejected")) {
            ESP_LOGE(TAG, "FLEET: rejected: %s", dbuf);
            xEventGroupSetBits(g_events, PROV_DONE_BIT);
        }
        break;
    }
    default: break;
    }
}

static void run_fleet_provisioning(void) {
    ESP_LOGI(TAG, "STATE: FLEET_PROVISION");
    esp_mqtt_client_config_t cfg = {
        .broker = {
            .address = { .hostname = AWS_IOT_ENDPOINT, .port = AWS_IOT_PORT,
                         .transport = MQTT_TRANSPORT_OVER_SSL },
            .verification = { .crt_bundle_attach = esp_crt_bundle_attach },
        },
        .credentials = {
            .client_id = "smartbin-claim",
            .authentication = {
                .certificate     = (const char *)claim_cert_start,
                .certificate_len = (size_t)(claim_cert_end - claim_cert_start),
                .key             = (const char *)claim_key_start,
                .key_len         = (size_t)(claim_key_end - claim_key_start),
            },
        },
        .buffer = { .size = 6144 },
    };
    esp_mqtt_client_handle_t c = esp_mqtt_client_init(&cfg);
    esp_mqtt_client_register_event(c, ESP_EVENT_ANY_ID, fleet_handler, NULL);
    esp_mqtt_client_start(c);
    xEventGroupWaitBits(g_events, PROV_DONE_BIT, pdTRUE, pdTRUE, pdMS_TO_TICKS(30000));
    esp_mqtt_client_stop(c);
    esp_mqtt_client_destroy(c);
}


/* ============================================================
   OTA — pull firmware from an HTTPS URL on demand
   Publish to MQTT topic smartbin/<bin_id>/ota a JSON like:
     { "url": "https://your-bucket.s3.amazonaws.com/firmware.bin",
       "version": "1.0.1" }
   ============================================================ */
static void ota_task(void *arg) {
    char *url = (char *)arg;
    ESP_LOGI(TAG, "OTA: starting download from %s", url);
    esp_http_client_config_t http_cfg = {
        .url = url,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .timeout_ms = 60000,
        .keep_alive_enable = true,
    };
    esp_https_ota_config_t cfg = { .http_config = &http_cfg };
    esp_err_t err = esp_https_ota(&cfg);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "OTA success, rebooting into new firmware");
        vTaskDelay(pdMS_TO_TICKS(500));
        esp_restart();
    } else {
        ESP_LOGE(TAG, "OTA failed: %s", esp_err_to_name(err));
    }
    free(url);
    vTaskDelete(NULL);
}

static void handle_ota_message(const char *data, int len) {
    static char buf[512];
    int dl = len < (int)sizeof(buf) - 1 ? len : (int)sizeof(buf) - 1;
    memcpy(buf, data, dl); buf[dl] = 0;

    char url[400] = {0}, ver[40] = {0};
    if (!json_str(buf, "url", url, sizeof(url))) {
        ESP_LOGW(TAG, "OTA: no 'url' in message"); return;
    }
    json_str(buf, "version", ver, sizeof(ver));
    if (ver[0] && strcmp(ver, FW_VERSION) == 0) {
        ESP_LOGI(TAG, "OTA: version %s already running, skipping", ver);
        return;
    }
    ESP_LOGI(TAG, "OTA: queued update from %s (target ver=%s, current=%s)",
             url, ver[0] ? ver : "?", FW_VERSION);
    char *cpy = strdup(url);
    if (cpy) xTaskCreate(ota_task, "ota", 8192, cpy, 5, NULL);
}

/* ==========================================================================
   PHASE 3 — Operational telemetry (permanent device cert)
   ========================================================================== */
static float read_distance_cm(void) {
    gpio_set_level(TRIG_PIN, 0); esp_rom_delay_us(2);
    gpio_set_level(TRIG_PIN, 1); esp_rom_delay_us(10); gpio_set_level(TRIG_PIN, 0);
    int64_t t0 = esp_timer_get_time();
    while (gpio_get_level(ECHO_PIN) == 0) if (esp_timer_get_time() - t0 > 30000) return -1;
    int64_t s = esp_timer_get_time();
    while (gpio_get_level(ECHO_PIN) == 1) if (esp_timer_get_time() - s > 30000) return -1;
    return ((float)(esp_timer_get_time() - s) / 2.0f) / 29.1f;
}

static char g_topic_ota[80] = {0};

static void op_handler(void *a, esp_event_base_t b, int32_t id, void *d) {
    esp_mqtt_event_handle_t ev = (esp_mqtt_event_handle_t)d;
    if ((esp_mqtt_event_id_t)id == MQTT_EVENT_CONNECTED) {
        ESP_LOGI(TAG, "OPERATIONAL: connected as %s (fw %s)", g_thing_name, FW_VERSION);
        snprintf(g_topic_ota, sizeof(g_topic_ota), "smartbin/%s/ota", g_meta.bin_id);
        esp_mqtt_client_subscribe(ev->client, g_topic_ota, 1);
        ESP_LOGI(TAG, "OTA: subscribed to %s", g_topic_ota);
    } else if ((esp_mqtt_event_id_t)id == MQTT_EVENT_DATA) {
        if (ev->topic_len && strncmp(ev->topic, g_topic_ota, ev->topic_len) == 0) {
            handle_ota_message(ev->data, ev->data_len);
        }
    }
}

static void start_operational(void) {
    ESP_LOGI(TAG, "STATE: OPERATIONAL");
    snprintf(g_topic_tel, sizeof(g_topic_tel), "smartbin/%s/telemetry", g_meta.bin_id);
    esp_mqtt_client_config_t cfg = {
        .broker = {
            .address = { .hostname = AWS_IOT_ENDPOINT, .port = AWS_IOT_PORT,
                         .transport = MQTT_TRANSPORT_OVER_SSL },
            .verification = { .crt_bundle_attach = esp_crt_bundle_attach },
        },
        .credentials = {
            .client_id = g_thing_name,
            .authentication = {
                .certificate = g_dev_cert, .certificate_len = strlen(g_dev_cert) + 1,
                .key = g_dev_key, .key_len = strlen(g_dev_key) + 1,
            },
        },
        .network = { .disable_auto_reconnect = false, .reconnect_timeout_ms = 5000 },
    };
    g_mqtt = esp_mqtt_client_init(&cfg);
    esp_mqtt_client_register_event(g_mqtt, ESP_EVENT_ANY_ID, op_handler, NULL);
    esp_mqtt_client_start(g_mqtt);

    gpio_set_direction(TRIG_PIN, GPIO_MODE_OUTPUT);
    gpio_set_direction(ECHO_PIN, GPIO_MODE_INPUT);

    while (1) {
        float dist = read_distance_cm();
        if (dist >= 0) {
            int fill = (int)(((BIN_HEIGHT_CM - dist) / BIN_HEIGHT_CM) * 100.0f);
            if (fill < 0)   fill = 0;
            if (fill > 100) fill = 100;
            const char *st = fill >= ALERT_THRESHOLD ? "FULL" : (fill >= 50 ? "HALF" : "OK");
            wifi_ap_record_t ap; int rssi = (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) ? ap.rssi : 0;
            char payload[420];
            snprintf(payload, sizeof(payload),
                "{\"bin_id\":\"%s\",\"fill_percent\":%d,\"distance_cm\":%.1f,"
                "\"status\":\"%s\",\"rssi\":%d,\"latitude\":%s,\"longitude\":%s,"
                "\"alert\":%s,\"timestamp\":%lld}",
                g_meta.bin_id, fill, dist, st, rssi, g_meta.lat, g_meta.lng,
                fill >= ALERT_THRESHOLD ? "true" : "false",
                (long long)(esp_timer_get_time() / 1000000LL));
            esp_mqtt_client_publish(g_mqtt, g_topic_tel, payload, 0, 1, 0);
            ESP_LOGI(TAG, "telemetry: fill=%d%% dist=%.1f", fill, dist);
        }
        vTaskDelay(pdMS_TO_TICKS(PUBLISH_INTERVAL_MS));
    }
}

/* ==========================================================================
   BOOT
   ========================================================================== */
void app_main(void) {
    ESP_LOGI(TAG, "=== SmartBin zero-touch firmware boot ===");
    esp_err_t e = nvs_flash_init();
    if (e == ESP_ERR_NVS_NO_FREE_PAGES || e == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase(); nvs_flash_init();
    }
    g_events = xEventGroupCreate();

    /* Auto-assign bin_id from ESP32 MAC (last 4 hex chars) -> e.g. Bin-7898.
       The app no longer sends bin_id; this guarantees a unique, deterministic id. */
    uint8_t _mac[6]; esp_read_mac(_mac, ESP_MAC_WIFI_STA);
    snprintf(g_meta.bin_id, sizeof(g_meta.bin_id), "Bin-%02X%02X", _mac[4], _mac[5]);
    nv_set("bin_id", g_meta.bin_id);
    ESP_LOGI(TAG, "Auto bin_id from MAC: %s", g_meta.bin_id);
    ESP_LOGI(TAG, "*** I AM THE NEW OTA FIRMWARE 1.0.1 ***");

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_evt, NULL, NULL);
    esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_evt, NULL, NULL);
    wifi_init_config_t wcfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&wcfg));

    load_meta();

    /* No WiFi creds? Run the captive portal, then restart into STA path. */
    if (!nv_get("wifi_ssid", g_ssid, sizeof(g_ssid))) {
        start_captive_portal();
        xEventGroupWaitBits(g_events, FORM_OK_BIT, pdTRUE, pdTRUE, portMAX_DELAY);
        vTaskDelay(pdMS_TO_TICKS(1500));   /* let the HTTP response flush */
        ESP_LOGI(TAG, "Restarting into STA mode");
        esp_restart();
    }
    nv_get("wifi_pass", g_pass, sizeof(g_pass));

    /* STA connect with stored creds */
    esp_netif_create_default_wifi_sta();
    wifi_config_t sta = { 0 };
    strlcpy((char *)sta.sta.ssid, g_ssid, sizeof(sta.sta.ssid));
    strlcpy((char *)sta.sta.password, g_pass, sizeof(sta.sta.password));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "STATE: CONNECT_WIFI (%s)", g_ssid);
    xEventGroupWaitBits(g_events, WIFI_OK_BIT, pdFALSE, pdTRUE, portMAX_DELAY);

    /* Auto-detect location (WiFi-based) if we don't have it yet */
    if (strlen(g_meta.lat) == 0) {
        ESP_LOGI(TAG, "Auto-locating via WiFi geolocation...");
        geolocate_via_wifi();
        if (strlen(g_meta.lat) == 0) {           /* don't block provisioning */
            strlcpy(g_meta.lat, "0.0", sizeof(g_meta.lat));
            strlcpy(g_meta.lng, "0.0", sizeof(g_meta.lng));
            nv_set("lat", g_meta.lat); nv_set("lng", g_meta.lng);
            ESP_LOGW(TAG, "Geolocation unavailable; defaulting 0,0 (set on dashboard)");
        }
    }

    /* Permanent identity yet? */
    if (!load_device_identity()) {
        run_fleet_provisioning();
        if (!load_device_identity()) {
            ESP_LOGE(TAG, "Provisioning failed; rebooting to retry");
            vTaskDelay(pdMS_TO_TICKS(5000)); esp_restart();
        }
    } else {
        ESP_LOGI(TAG, "Permanent identity in NVS: %s", g_thing_name);
    }

    start_operational();   /* never returns */
}