# Taycan Screen: rules for Claude Code

Read `docs/HANDOFF.md` first: hardware, what's proven in the car, and the ordered task list.
`PROJECT_BRIEF.md` and `HANDOFF_PROMPT.md` (if present) hold the full product spec; designs are in `design/`.

## The owner
- Not a developer, and works only from an iPhone. Give numbered, plain-English steps for anything
  they must do on the box or phone. Never assume a computer.
- You can't reach the box. The loop is: push to `main` → GitHub Actions builds a signed APK and
  publishes a GitHub Release → owner taps **Check for update** in the app → tests in the car →
  sends back the log from **Share log** plus photos.

## Build and release
- Every push to `main` triggers `.github/workflows/build.yml`: signed `assembleRelease`, versionCode =
  100 + run number, release tag `build-<versionCode>`, asset `taycan-screen.apk`. Don't rename the
  tag scheme or asset: the in-app updater (`update/Updater.kt`) depends on both.
- Signing secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_PASSWORD` (alias `taycan`). Never commit keys.
- The repo must stay public so the app can download releases without a token.
- After pushing, check the Actions run succeeded before telling the owner a build is ready.
- Package name `com.taycanscreen.app` is permanent; changing it breaks updates.

## OBD rules (non-negotiable)
- Use the exact headers, requests and formulas from the Car Scanner custom PID definitions in
  `reference/`. Don't guess module addresses. If the definitions aren't there, ask for them before
  writing OBD code.
- Read-only. Never send "Reboot MMI" or any write/reset/actuation/coding command.
- Don't require `0100` to succeed (the car doesn't answer it). Set the protocol explicitly as the
  definitions indicate.
- Log every raw adapter exchange with timestamps (`AppLog.tx` / `AppLog.rx`).
- Show "—" for unavailable data, never stale values. Recover automatically from Bluetooth/OBD drops.
- Battery % = "SoC Display". Don't display "SoC HR" (raw).
- The "High-voltage system not free of voltage" warning while an adapter is active is expected.

## UI rules
- The instrument cluster stays the official speedometer. Glanceable: large type, high contrast,
  no video.
- The Porsche frame keeps its right column visible, so the app window is narrower than the full
  screen. Size layouts to the measured window (the Screen panel and the log's `[screen]` line
  report it), not to the panel's native resolution.
