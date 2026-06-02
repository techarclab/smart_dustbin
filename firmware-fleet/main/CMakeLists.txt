# Zero-touch (Fleet Provisioning) firmware. ONE image for ALL bins.
# Self-contained captive portal (esp_http_server) - no wifi_provisioning, no cJSON.
# Embeds the SHARED claim cert + key. Symbol names used in main_provisioning.c:
#   claim_cert_pem     <- certs/claim/claim.cert.pem
#   claim_private_key  <- certs/claim/claim.private.key
idf_component_register(
    SRCS "main_provisioning.c"
    INCLUDE_DIRS "."
    REQUIRES nvs_flash esp_wifi esp_event esp_netif esp_http_server mqtt
             esp-tls mbedtls esp_http_client esp_https_ota app_update
             esp_driver_gpio esp_timer esp_hw_support
    EMBED_TXTFILES "certs/claim/claim.cert.pem" "certs/claim/claim.private.key"
)