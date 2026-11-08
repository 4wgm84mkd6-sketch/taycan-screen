# Taycan Screen: rules for Claude Code

Read `docs/HANDOFF.md` first: hardware, what's proven in the car, and the ordered task list.
`PROJECT_BRIEF.md` and `HANDOFF_PROMPT.md` (if present) hold the full product spec; designs are in `design/`.

## The owner
- Not a developer, and works only from an iPhone. Give numbered, plain-English steps for anything
  they must do on the box or phone. Never assume a computer.
- You can't reach the box. The loop is: push to `main` → GitHub Actions builds a signed APK and
  publishes a GitHub Release → owner taps **Check for update** in the app → tests in the car →
  sends back the log with **Send log to Claude** plus photos.

## Build and release
- Every push to `main` triggers `.github/workflows/build.yml`: signed `assembleRelease`, versionCode =
  100 + run number, release tag `build-<versionCode>`, asset `taycan-screen.apk`. Don't rename the
  tag scheme or asset: the in-app updater (`update/Updater.kt`) depends on both.
- Signing: `signing/release.jks.enc` (AES-256 encrypted keystore) + repo secret `SIGNING_PASSWORD`
  (alias `taycan`). Never commit an unencrypted key.
- No Gradle wrapper in the repo (the GitHub connector can't upload binaries); CI pins Gradle 8.11.1.
- The repo must stay public so the app can download releases without a token.
- Logs: the owner taps **Send log to Claude** (Setup); the app uploads gzipped session logs to the
  private repo `<owner>/taycan-logs` under `logs/<date>/<time>-build<N>/`. Attach that repo (read) to
  read them. Auth: GitHub device-flow sign-in on the box to the owner's "taycan-logs" GitHub App
  (installed only on taycan-logs, Contents R/W). Only the App's public Client ID ships in the APK
  (`taycanLogClientId` in gradle.properties). Never build a token or secret into the APK.
  Files over 256 KB (gzipped) arrive as `<name>.gz.partNN`: join them in order (`cat`) before `zcat`.
- Spotify: App Remote SDK 0.8.0 (`app/libs/`, Apache 2.0) with the owner's Spotify developer app; its
  public Client ID goes in gradle.properties (`spotifyClientId`), redirect URI
  `taycanscreen://spotify-callback`, package `com.taycanscreen.app`, signing SHA-1
  `11:70:96:88:51:D9:AA:37:E7:68:C2:BD:49:72:A4:1F:31:A0:F1:91`.
- After pushing, check the Actions run succeeded before telling the owner a build is ready.
- Package name `com.taycanscreen.app` is permanent; changing it breaks updates.

## OBD rules (non-negotiable)
- Definitions come from OBDb (`reference/obdb/`, owner-approved 2026-11-08; Car Scanner hides its
  built-in Porsche definitions). Use their exact headers, requests and formulas. Don't guess module
  addresses or invent requests; if a signal isn't in OBDb, ask the owner first.
- Exception (owner-approved 2026-11-08): `obd/Discovery.kt` may read (22 xxxx, default session) every
  identifier in fixed ranges on modules from OBDb's ECU list (714, 7E0, 710) to find turn-signal and
  gear signals. Read-only; adds no new command types.
- Discovered (not OBDb), owner-visible to verify: gear `7E0 22 0884` (5 P, 6 R, 7 N, 8 D).
- Every value must be checked against Car Scanner on the owner's iPhone before it's trusted. The MX+
  serves one device at a time, so compare in turns while parked.
- Read-only. Never send "Reboot MMI" or any write/reset/actuation/coding command. The only car
  requests allowed are `22 xxxx` (read data) and `10 03` (extended diagnostic session, owner-approved
  for modules whose OBDb entries have `din: "03"`). `ElmConnection` enforces this allowlist; AT/ST
  commands go to the adapter only.
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
