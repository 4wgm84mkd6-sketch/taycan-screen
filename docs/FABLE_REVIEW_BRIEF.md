# Review brief: make Taycan Screen feel production-ready

Written 2026-11-10 at the owner's request, as a handoff from the session that built builds 104–156.

## Your goal
Review everything built so far, critique it on whatever metrics you judge matter, and then improve it until it looks
and feels like mass-production software, as if Apple or Tesla had designed it: smooth, calm, obvious, and safe to use
while driving. Also fix the bugs that are still open and improve the driving experience.

You have full authority to judge. "It works" is not the bar. Look for anything a polished in-car product would not
ship with, including:
- jank and dropped frames
- inconsistent spacing, type or colour
- unclear states and dead ends
- slow start-up or reconnects
- touch targets that are hard to hit on a moving car's screen
- text that's hard to read at a glance
- error messages written for developers
- features that silently do nothing

## Read first
1. `CLAUDE.md`: the project's binding rules. Read it in full; it overrides this brief where they differ.
2. `docs/HANDOFF.md`: hardware, what's proven in the car, and a dated status log for every build (newest at the
   bottom). It's the history of every owner request and decision.
3. `PROJECT_BRIEF.md` / `HANDOFF_PROMPT.md` (if present) and `design/`: the product spec and designs.
4. The design source: the "Taycan Center Screen Concept" artifact, `https://claude.ai/artifact/PhA2Jt5qDhHqoA658Q2WDw`
   (v3 boards Drive night/day and Audio). Read it with the Artifact tool's `read` action.
5. Real logs and screenshots from the car: private repo `4wgm84mkd6-sketch/taycan-logs`. Attach it read-only with
   add_repo. Each upload is under `logs/<date>/<time>-build<N>/`: gzipped session logs plus `screenshots/*.jpg`
   that the app captured on the box. These screenshots are the only true picture of how the app looks; study them.
   Files over 256 KB arrive as `.gz.partNN`: `cat` the parts in order before `zcat`.

## The owner and the loop (unchanged, non-negotiable)
- The owner is not a developer and works only from an iPhone. Give numbered, plain-English steps for anything they
  must do. Never assume a computer.
- You can't reach the box. The loop:
  1. You push to `main`.
  2. GitHub Actions builds a signed APK and publishes release `build-N`.
  3. The owner taps **Check for update**, tests in the car, and taps **Send log to Claude** (it uploads to
     taycan-logs), then sends photos.
- Check that the Actions run succeeded before telling the owner a build is ready.
- Every push to `main` that touches code produces a release the owner may install. Batch your work into a few
  meaningful, tested builds rather than many small ones, and say clearly what to test in each.
- Owner decisions recorded in CLAUDE.md and HANDOFF.md stand: no phone features, OBD read-only rules, the start-up
  sequence timing, the full-height left panel, the map controls and the recent decluttering. If your critique says one
  of them should change, propose it to the owner with your reasoning and let them decide; don't silently reverse it.
- Never put a token, key or secret in the APK or the repo. Keep the repo public. Don't change the package name, the
  release tag scheme or the asset name (the in-app updater depends on them).
- Don't put model names or identifiers in commits, code or docs.

## Current state (build 156)
Kotlin + Jetpack Compose app on a Carlinkit Android 13 box (Snapdragon, 1602×720 px window, the Porsche frame keeps
its right column). Touch from the car is **single-finger only** (logged in build 135), so gestures must work with one
finger.

The Drive screen is drawn in design px: `DriveScreen` sets the density so the 1602×720 board fits.
- **Left panel:** status row top right (hotspot icon, small battery, clock), big speed with gear and speed limit, the
  centre-zero power line, then a large now-playing player.
- **Right side:** a MapLibre vector map (OpenFreeMap tiles, recoloured per v3 palette, Esri satellite option) with the
  search box, Home/Work, the turn card plus route card, and map buttons. A 128 px dock sits under the map.
- **Panels over the map:** destination search (own keyboard, Photon search, OSRM routes), Music (Spotify App Remote
  plus the Web API, with Up First quick play), Audio EQ, and the energy Ledger (trip, battery health and cells, tyres,
  power, 12 V).
- **Background services:**
  - OBD: OBDLink MX+ over Bluetooth, read-only, signals from OBDb in `reference/obdb/`
  - trip computer
  - offline speed limits: CI bundles the OSM limits for Hawaii into `assets/speedlimits.txt`
  - in-app updater
  - log upload

Key files:
- `ui/DriveScreen.kt`, `ui/DriveMap.kt`, `ui/RouteLayer.kt`, `ui/Search.kt`, `ui/Music.kt`, `ui/Ledger.kt`,
  `ui/Startup.kt`, `ui/Filament.kt` (type and icons), `ui/DriveTheme.kt`
- `map/*`, `nav/*`, `car/*`, `obd/*`, `music/*`, `audio/EqEngine.kt`, `net/NetMonitor.kt`, `update/Updater.kt`,
  `log/*`

## Known open issues (verify each against the logs; some may be fixed or stale)
- Builds 155 and 156 haven't been tested in the car yet. That includes the offline speed limits, the decluttered
  panel, the big player, the custom map size and Home/Work, including pull-to-go-home.
- OBD start-up: in build 154 the first two Bluetooth connects failed ("read failed, socket might closed or timeout")
  and car data arrived ~32 s after the first frame. Make reconnects faster and the waiting state graceful.
- `10 03` to the BMS (7E5) answers NO DATA, yet the reads still work. Check whether the session is needed at all.
- Re-attaching the EQ to a remembered audio session fails with error -3 (believed harmless). The EQ effect on the
  car's sound hasn't been verified.
- GPS drops to ±100 m in garages; the marker and heading behaviour there needs to stay calm.
- Leftover code: unused `VolumeControl`, unused `headingName` / `arrivePct` params in `V3Panel`, comments
  describing older layouts (Filament/Saira names on fonts that are now Inter Tight). Clean up.
- Little automated testing beyond `app/src/test` unit tests. Consider adding Compose screenshot tests (e.g.
  Roborazzi or Paparazzi run in CI) so you can see your own UI changes before the owner does. Upload the images as
  CI artifacts or render them in an Artifact for review.

## What to deliver
1. **Critique first.** Write `docs/REVIEW.md` with your metrics, findings ranked by impact on the driver, and a plan.
   Also publish it as an Artifact page the owner can read on their iPhone, with screenshots from the logs repo
   where they help.
2. Send the owner a short plain-English summary and the link. Ask them about anything that needs their decision,
   above all any change to something they chose.
3. **Then build** in a few well-tested releases: bug fixes and robustness first, then smoothness (animation, frame
   pacing, transitions, start-up), then the visual and interaction polish. After each build, tell the owner exactly
   what to test in the car, in numbered steps.
4. Keep `docs/HANDOFF.md`'s status log current, one entry per build, so the next session can pick up.
