# SmartBin — Industrial IoT Waste Platform

Start here:  docs/SMARTBIN_MASTER_BUILD_GUIDE.md

Layout:
  aws/             AWS backend + fleet provisioning (Lambdas, policies, deploy scripts)
  firmware-simple/ ESP-IDF project — one bin, manual cert (Phase 5)
  firmware-fleet/  ESP-IDF project — zero-touch fleet provisioning (Phase 7)
  dashboard/       React + Vite operations dashboard + admin panel
  docs/            All guides and architecture

Build order: Phases 1-5 first (get one bin live), then 6-9 (alerts, zero-touch, scale).
