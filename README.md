# Taycan Screen

A custom Android app for the 2025 Porsche Taycan's center screen. It runs on a Carlinkit
Android 13 AI box plugged into the car's CarPlay USB port and reads vehicle data from an
OBDLink MX+ over Bluetooth.

Everything is done from an iPhone; there is no computer in the loop.

## How a change reaches the car

1. Claude Code writes code in this repository and pushes it to `main`.
2. GitHub Actions (`.github/workflows/build.yml`) builds a signed release APK and publishes it
   as a GitHub Release tagged `build-<number>`, with the file attached as `taycan-screen.apk`.
   The build number goes up by one on every build.
3. In the car, tap **Check for update** in the app. It downloads the newest build and installs it
   over the existing app.
4. Test, tap **Share log**, and send the log (plus photos) back to Claude Code.

The repository must be **public**: the app and the box's browser download releases without
signing in to GitHub. Nothing secret lives in the code in readable form.

## Signing

Every build is signed with the same release key, so updates install over the existing app.
The keystore is committed encrypted (AES-256) at `signing/release.jks.enc`; the workflow decrypts
it with the repository secret `SIGNING_PASSWORD`, which is also the keystore and key password
(key alias `taycan`). Without the secret the workflow builds nothing.

If the password is ever lost, a new key can be made, but the app then has to be uninstalled once
and installed fresh from the browser (Android refuses an update signed with a different key).

There is no Gradle wrapper in the repo; CI installs Gradle 8.11.1 itself.

## First install on the box (one time)

Do this parked.

1. On the box, open the web browser.
2. Go to `github.com/4wgm84mkd6-sketch/taycan-screen/releases/latest`.
3. Under **Assets**, tap **taycan-screen.apk**. Wait for the download to finish.
4. Tap the download notification (or open the Downloads / Files app and tap `taycan-screen.apk`).
5. If Android says installs from this source aren't allowed, tap **Settings**, turn on
   **Allow from this source**, then tap back.
6. Tap **Install**, then **Open**.

From then on, use **Check for update** inside the app. The first time it installs an update,
Android asks you to allow Taycan Screen to install apps: tap **Open install setting**, turn on
**Allow from this source**, go back, and tap **Install build …** again.

## Project files

- `CLAUDE.md`: project rules for Claude Code sessions.
- `docs/HANDOFF.md`: hardware, what's proven so far, and the task list.
- `reference/`: Car Scanner custom PID definitions and car photos/screenshots.
- `design/`: screen designs.
