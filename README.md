# Throttle VPN

Android-App mit lokalem VPN (kein Server, alles bleibt auf dem Gerät), das Upload und Download
getrennt begrenzt:

- pro App (beliebig viele Apps, jede mit eigenen Limits, alle anderen Apps laufen normal)
- für das ganze Gerät (zusätzlich oder allein)

APK: siehe Releases → "Latest build" (wird bei jedem Push auf `main` von GitHub Actions gebaut).

Hinweise: Limits werden live per Slider geändert. App-Auswahl / "Ganzes Gerät" starten das VPN neu.
IPv6 wird im VPN verworfen, Apps weichen auf IPv4 aus. DNS: 1.1.1.1 / 8.8.8.8.
UDP (z. B. QUIC) wird über Paketverwurf gedrosselt, TCP über Rückstau.
