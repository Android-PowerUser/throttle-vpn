# Throttle VPN

Android app with a local VPN (no server, all traffic stays on the device) that limits upload and
download separately:

- per app (any number of apps, each with its own limits; all other apps run normally)
- for the whole device (in addition to or instead of per-app limits)

Free 5-minute trial, then a one-time in-app purchase (`full_access`, 0.99 EUR) via Google Play Billing.
Purchases only work when the app is installed from Google Play.

APK: see Releases -> "Latest build" (built by GitHub Actions on every push to `main`).

Notes: limits change live via sliders. Changing the app selection or "Whole device" restarts the VPN.
Speed 0 blocks the app completely. System apps can be limited too. IPv6 is dropped inside the VPN so apps fall back to IPv4. The VPN does not set its own DNS servers,
so the system DNS / Private DNS settings stay in effect. UDP (e.g. QUIC) is throttled by dropping packets,
TCP by back-pressure.
