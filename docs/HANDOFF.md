# Taycan center-screen app: process and current status

## Project
A custom Android app replacing the factory infotainment view on my 2025 Porsche Taycan's center screen. Full spec: PROJECT_BRIEF.md and HANDOFF_PROMPT.md; designs in /design. A Carlinkit Android 13 AI box (Snapdragon SM6225, 8GB, BT 5.0, 4G) plugs into the car's CarPlay USB port and shows Android on the factory screen. An OBDLink MX+ (Bluetooth Classic) supplies vehicle data.

## Workflow: phone only, no computer
I work entirely from my iPhone. You can't reach the box directly, so:
1. You write code in this GitHub repo.
2. GitHub Actions builds a release APK on every push to main and publishes it as a GitHub Release.
   - Sign every build with the same release key, stored as GitHub secrets, so updates install over the existing app.
   - Auto-increment versionCode on each build.
3. The app has a "Check for update" button that downloads the latest release APK and installs it (PackageInstaller; request "install unknown apps" permission).
4. The app has a "Share log" button that sends the full raw OBD log through Android's share menu, plus an on-screen log viewer.
5. I install, test in the car, and relay logs and photos back to you.

## Proven so far
- The box runs on the center screen and touch works. The Porsche frame keeps its right column visible (outside temp, 4G, park-assist button), so the usable window is narrower than the full screen. Exact size still unknown.
- Sideloading works. A test app (OBDProbe) installed and ran.
- Box-to-adapter Bluetooth works: ELM327 v1.4b / STN2255 v5.13.1, 12V read 13.2V.
- The car does NOT answer standard 0100 with auto protocol (ATSP0): "SEARCHING... UNABLE TO CONNECT".
- Car Scanner on my iPhone, using custom Taycan PID definitions through the same MX+, reads correctly: SoC Display 18.98% (use this as battery %), SoC HR 20.78% (raw, don't display), HV voltage 589.3 V, HV current 2.36 A, batt min/max temp 80.6/82.4 °F, cooling inlet temp, motor rpm, torque, speed. Blank (car doesn't answer): SoH, battery energy, battery power.
- A "High-voltage system not free of voltage" warning appears in the car while an OBD adapter is active. This is expected, not a fault.

## Rules
- Use the exact headers, requests and formulas from the Car Scanner custom PID definitions in /reference. Don't guess module addresses. If the definitions aren't there yet, ask me for them before writing OBD code.
- Never send the "Reboot MMI" command or any write/reset command. Read-only requests only.
- Don't require 0100 to succeed. Set the protocol explicitly as the definitions indicate.
- Log every raw adapter exchange with timestamps.
- Show "—" for unavailable data, never stale values. Recover automatically from Bluetooth/OBD drops.
- The instrument cluster stays the official speedometer. UI must be glanceable: large type, high contrast, no video.
- I'm not a developer: give me numbered, plain-English steps for anything I do on the box or phone.

## Tasks, in order
1. Set up the repo, the GitHub Actions build, signing, releases, and the update and share-log buttons. Tell me how to do the one-time first install on the box (browser download, allow installs).
2. OBD probe v2: connect to the MX+, set the protocol, poll SoC Display, speed, HV voltage, HV current (compute power kW = V × A) and min/max pack temp using the Car Scanner definitions. Show live values and the raw log.
3. Add a diagnostic screen that reports the app's usable window size in px and dp and the screen density, so we can size the layout to the Porsche frame.
4. Once 2 and 3 work, build Phase 1 from the brief: the Drive screen, left vehicle panel, dock, and day/night mode, sized to the measured window.
5. Later: test whether an EQ can attach to Spotify's audio session on the box (gate G4) before building the Audio screen.
6. Later: debug the box. "Box health" page: installed-app inventory (package, system/user, enabled, size), running services, RAM/storage, boot timing baseline. Needs a way to get long logs off the box first ("Send log to Claude" upload to a private log repo, proposed).
7. Later: remove bloatware. User-installed apps via Uninstall (ACTION_DELETE, owner confirms); preinstalled apps via Disable in their Android settings page, only ones identified as safe (never car link, Bluetooth, GPS, 4G, audio); ADB only as a last resort (needs a computer or Android phone).

## Status log
- 2026-11-08: Task 1 done in code: build/sign/release workflow, Check for update, Share log, log viewer, plus a Screen panel that logs window size and density (early part of task 3). CI compiles cleanly. Awaiting first install on the box. Car Scanner definitions not yet in /reference.
- 2026-11-08: Build 104 installed on the box from the browser and running. Check for update reached GitHub ("up to date"). Measured in the car (Porsche frame visible, box dock + status bar shown): window 1602×720 px, max 1602×720 px, 238 dpi (density 1.49), font scale 1.00; app area inside 16 dp padding 975×428 dp (1450×636 px), so the drawable area without padding is about 1007×460 dp (1500×686 px). The box's left dock (~100 px) and top status bar (~34 px) take the rest. Porsche's own left icon column and right column (temp, 4G, park assist) are outside Android's 1602×720 entirely. Box clock appears set to a different time zone than the car (box 13:21 vs car 07:22), so log timestamps are box-local time.
- 2026-11-08: Build 105 adds box-bar inset readout and a Full screen toggle to measure the area with the box's dock/status bar hidden.
- 2026-11-08: TASK 1 DONE. Build 105 installed through the in-app updater (permission prompt, then "Install succeeded"). Measured with Full screen ON: box bars L104 T36 R0 B0 px when shown; with them hidden the app gets the whole window, 1602×720 px = about 1076×484 dp at density 1.49 (238 dpi). Hiding works and survives returning from other screens (re-applied on resume). Plan for Phase 1: run full screen and size layouts to 1602×720 px / ~1076×484 dp, with our own dock; a swipe from the edge still brings back the box's dock.
- 2026-11-08: Share menu on the box offers only Nearby, NFC/Android Beam, APKPure, Bluetooth and ES File Explorer. None reach an iPhone, so logs need another route (proposed: "Send log" button that uploads to a private GitHub log repo; awaiting owner's decision). Photos of the on-screen log work meanwhile.
- 2026-11-08: Car Scanner's Porsche profile is built in and hides its definitions. Owner approved OBDb (reference/obdb/) as the definition source, including 10 03 (extended diagnostic session) for the BMS. Task 2 (OBD probe v2) written: Live tab polls SoC display (7E0 2208D2), speed (7E0 22F40D), HV voltage (7E5 221801, plus 221818 because OBDb's 2025 recordings show 1801 = 0), HV current (7E5 221802, session 03), pack temp max/min (7E5 221E0E/221E0F, session 03); power = V × A. Adapter set up with ATSP6 (no 0100 search). Command allowlist enforces read-only. Decoder unit-tested against OBDb's recorded real-car responses. App now opens full screen on the Live tab.
- 2026-11-08: TASK 2 WORKS IN THE CAR (build 108, car on, stationary). All tiles decode correctly from their raw replies: SoC display 7E8 62 08D2 1DA6 → 75.9 %; speed 0; 1801 → 0.0 V (as OBDb's 2025 recordings predicted) and 1818 → 1A68 = 676.0 V; current 62 1802 0249 → −2.4 A; power −1.6 kW; pack temp min/max 81/83 → 29/31 °C (84/88 °F). Negative current = drawing from the pack (car on, A/C running). Still to do: side-by-side check against Car Scanner (rule), confirm current sign convention while charging.
- Phase 1 design source: the owner's "Taycan Center Screen Concept" design canvas (https://claude.ai/artifact/PhA2Jt5qDhHqoA658Q2WDw) has artboards Main, Drive, DriveNight, Audio, Climate.
- 2026-11-08: Task 4 (Phase 1) built from the design canvas's "Drive — day/night" artboards: Drive screen is now the home screen. Left vehicle panel: car-link dot, battery %, speed (mph, 116sp), range (OBDb 710 222AB6 with flow control; unit per OBDb: miles), battery bar (amber ≤20 %), pack min–max °F, HV volts, and a live power card (centre-zero bar ±200 kW) where the design has "Now playing". Map: OpenStreetMap via osmdroid following the box's GPS, zoom/recenter buttons, clock + outside temp pill (OBDb 7E0 221004), night = inverted tiles. Dock: Vehicle details (diagnostic Live screen), Navigate (opens Google Maps/Waze), Music (Spotify/YouTube Music), Day/Night/Auto (auto = 18:30–06:30 box time), Setup, volume −/+ (STREAM_MUSIC). Not built (no data source yet): gear P/R/N/D (not in OBDb), turn-by-turn card and ETA (needs routing), now playing (needs media-session access), consumption mi/kWh and trip.
- 2026-11-08: Build 109 feedback: map drew over the left panel; Auto went to night mid-morning because the box clock/time zone is wrong (box showed 2:40 when the car showed 08:40). Fixes: map clipped to its area; Auto day/night now uses the sun's altitude at the GPS position (night below −3°), independent of the box's time zone; night map uses invert + 180° hue rotation + dim instead of plain inversion; the panel shows the OBD status reason when not connected.
- 2026-11-08: Box loses power when the car sleeps (USB cut), and boot is slow. Not keeping it powered (12 V drain risk). Instead Taycan Screen registers as a HOME app: once the owner picks it as the default home app it opens right after boot and starts the OBD link. Back never closes it (returns to Drive). Setup has "Choose home app" and "Open the box's launcher" (finds the other HOME activity).
- 2026-11-08: Build 111 confirmed by owner: map no longer overlaps the vehicle panel; Taycan Screen works as the box's home app.
- 2026-11-08: Bluetooth connect failures ("read failed, socket might closed or timeout, read ret: -1") were the MX+ being busy with the iPhone's Car Scanner and/or asleep with the car off. Fixed by closing Car Scanner / iPhone Bluetooth and switching the car on. The app now says that in plain words when the Bluetooth connect itself fails. Box time zone is now correct.
- 2026-11-08: "Send log to Claude" (Setup): uploads the 3 newest session logs, gzipped, to the private repo <owner>/taycan-logs via the contents API. First design (token built into the APK) was rejected because the APK is public; now the box signs in with GitHub's device flow to the owner's GitHub App (installed only on taycan-logs, Contents R/W), token kept in the app's private storage. Awaiting the owner's GitHub App Client ID (goes in gradle.properties: taycanLogClientId).
