# Taycan Screen: production-readiness review

Written 2026-11-10, against build 156 (code) and the car logs and on-box screenshots up to build 154.
Builds 155 and 156 have not been driven yet, so anything about the decluttered panel, the big player,
Home/Work and the offline speed limits is judged from code only.

## Verdict

The app does a lot and most of it works: the car data is correct, the vector map is sharp, the route
drawing and the start-up sequence are well made. What separates it from something Porsche or Tesla
would ship is not features. It is four things:

1. **The first minute after the car wakes is unreliable.** Spotify never connects after a cold start,
   the car link can take 30 to 160 s, and the box's clock is wrong for the first minutes, which breaks
   several things at once.
2. **The speed number and the map move in steps.** Speed refreshes about twice a second with no
   easing, and the map camera is capped at 30 frames a second.
3. **Much of the small text and several buttons are below what is safe to use while driving.**
4. **It looks like three different apps.** The Drive screen, the Music and Audio panels, and Setup each
   use their own shapes, fonts and button styles.

None of this needs a redesign or reverses an owner decision. It is engineering and finishing work.

## How it was judged

| Metric | What "production" means | Today | Grade |
|---|---|---|---|
| Start-up and reconnect | Usable in 4 s; car data, music and position within ~10 s, every time | Usable at 3.4 s (good). Car data +0.06 s at best, +32 to +159 s at worst. Spotify fails on every cold boot | D |
| Data correctness | Never a wrong or stale number | Decoding is right. Staleness uses the wall clock, which jumps at boot; speed may be 5 s old before it is hidden | C |
| Smoothness | 60 fps, values ease, panels animate | Map 30 fps by design; speed 2 Hz stepped; panels and day/night switch cut instantly; no frame timing in the log | C− |
| Glanceability | Text ≥ 3 mm tall for anything read while moving | Speed, clock, song title are fine. Labels are 1.5 to 2 mm (LIMIT, MPH, REGEN, kW, turn hints, map direction labels) | C |
| Touch | Targets ≥ 11 mm, spaced, with press feedback | Dock is good (13×15 mm). WAZE / END / ledger tabs / Music links / EQ chips are 5 to 8 mm | C |
| Driving safety | No typing while moving, nothing opens by itself | Destination typing locks above 5 mph. Spotify search does not, and Spotify's permission screen can open on its own while driving | D |
| Wording | Plain English, no codes | Good in places; raw errors still reach the screen ("UserNotAuthorizedException", "HTTP 502", "Error: -3") | C |
| Dead ends | Every failure offers a way forward | Several spinners never end; update panel can stick on "Installing…" | C |
| Visual consistency | One design language | Three | C− |
| Testing | Changes are seen before the owner sees them | Unit tests for decoding and route maths only; no UI tests | D |

Sizes assume the 10.9-inch centre display (about 0.14 mm per pixel).

## Findings, ranked by impact on the driver

### 1. Reliability in the first minute

- **Spotify never connects after a cold boot.** In all three cold-start logs it failed with
  "UserNotAuthorized" and stayed disconnected until the app restarted. The code makes one automatic
  attempt, then waits for a tap that the Drive panel does not offer.
- **The box's clock reads 30 July 2025 until it syncs**, then jumps 15 months forward mid-drive.
  Effects seen in the logs:
  - secure connections fail ("Chain validation failed") for updates, and probably Spotify sign-in;
  - the adapter read timeout expired instantly at the jump, replies went out of step, and speed, battery
    and gear went blank for about 2.5 s while driving (build 154, 17:23:13);
  - the trip computer closed the trip as "−673036 min" and started a new one mid-drive;
  - a backward jump would make old values look fresh, which breaks the "never show stale data" rule.
- **Car link is slow when the adapter is not ready.** Three logs: +32 s, +71 s, +159 s. Each failed
  attempt tries two connect methods (10 to 15 s), leaks two Bluetooth sockets, then sleeps up to 15 s.
  About half of the 159 s was sleeping.
- **`10 03` to the battery module is never answered** (26 of 26 "NO DATA", about 0.43 s each) and the
  readings work without it (about 2,190 clean answers each). It only costs time. Stop sending it first;
  keep it as a one-off fallback.
- **Speed can be 5 s old before it is hidden**, and values are not cleared when the link drops.
- **Two loops can talk to the adapter at once** for a moment when the adapter is re-chosen.
- **A route no longer clears itself after arrival** once END has been tapped within a minute of arriving
  on an earlier trip. Re-routing while offline retries every 20 s forever and re-raises the error.
- **A destination picked before GPS has a fix is lost.**
- **The update panel can stick on "Installing…"** if Android's confirmation is cancelled. Android asks
  for that confirmation every time because one permission is missing from the app.
- **Song picked from a playlist can be the wrong one** when the playlist has unavailable tracks.
- **EQ error −3**: a remembered Spotify audio session that no longer exists (Spotify makes a new one
  each time it starts playing: 121 → 129 → 137 → 145 in the logs). Harmless but it should be handled
  quietly. The EQ is also driven from two threads, which can crash the car-data loop.
- **Speed-limit lookup runs on the screen's thread** every 0.8 s, and the online fallback hammered three
  servers for 47 s per try in build 154. Build 155's offline file should end that; to confirm in the car.

### 2. Smoothness

- **Speed**: read about every 0.54 s (median), up to 1.4 s in bunches, and drawn as-is. It should be read
  4 to 5 times a second and eased, so the number glides like the cluster's.
- **Power line**: same stepping.
- **Map**: the camera is deliberately updated every other frame (30 fps). It should run every frame.
- **Everything on the Drive screen is redrawn for every value that arrives** (10 to 20 times a second),
  including the player and the dock. Each part should only redraw when its own value changes.
- **No transitions**: panels (search, music, EQ, ledger) appear and vanish in one frame; day/night
  switches in one frame; the turn card and route card pop in.
- **The log is flushed to storage and copied 50 times a second**, on whichever thread writes it.
- **Nothing measures frame time.** The log should say how many frames were late, so smoothness can be
  checked from a drive instead of guessed.

### 3. Glanceability and touch

- **Small labels** (cap height on the screen): LIMIT 1.3 mm, MPH / REGEN / kW 1.7 mm, turn hint
  ("RIGHT · MAUKA") 1.7 mm, map direction labels 1.5 mm, route card details 1.8 mm, ledger captions
  1.5 mm. The speed limit matters most: it is the one small thing a driver checks at speed.
- **Small targets**: WAZE and END (text only, 7.6 mm tall), "TIRE PRESSURE LOW ›" (text only), ledger
  tabs and DONE, Music's Browse / Try again / Allow (5 to 6 mm), EQ chips, Save, Reset, Done (5 mm,
  side by side, Reset has no undo).
- **Status in developer words**: "CONNECTING TO OBDLINK MX+ 70341…" in amber under the speed. The
  driver needs "Connecting to car…", calmly, and nothing in amber unless something is wrong.
- **Missing values** draw as heavy dashes of different sizes (see the build 154 screenshot).
- **Abbreviations**: HDG, N UP, SAT, LEDGER, REGEN with no unit context.
- **Dock**: six unlabelled icons; the dot under Navigate is lit whenever no panel is open, which reads
  as "navigation is on".

### 4. Driving safety

- **Spotify search uses the box's own small Android keyboard and works at any speed.** Destination
  search has our large keyboard and locks above 5 mph. Music should match.
- **The typing lock fails open**: if the car's speed is unknown, typing is allowed.
- **Spotify's permission screen opens by itself** (47 s after a cold boot in one log, and whenever its
  one-hour sign-in lapses). It must only open on a tap, or when parked.
- **The EQ sliders are fully usable while moving.** Presets are fine; dragging ten sliders is not.

### 5. Consistency and finish

- Drive screen: square panels, Inter Tight and Plex Mono, mint accent. Music and Audio panels: rounded
  cards, pill chips, the system font, white filled buttons. Setup: a third look with a bright green.
- Code still carries the previous two designs (gauge arc, gear chips, 20-segment battery, volume
  control, font names from two designs ago). About 700 lines are unused.
- Setup mixes owner actions (update, send log) with engineering tools (discovery scan, raw log,
  screen measurements).

## What is already good

- Start-up meets its promise: touches work 3.4 s after the first frame in every log.
- The car's numbers are right: speed within about 1 mph of GPS for a whole drive.
- Map quality, route drawing, local direction names, turn card content.
- Safety decisions already made: read-only car access, typing lock for destinations, "—" for missing data.
- The update and log loop works from an iPhone alone.

## Plan

Three builds. Each is tested here first (unit tests, plus rendered screenshots of the screens made in
CI so changes are seen before the car), then in the car.

**Build A, "Dependable".** No visible redesign.
- Timing immune to the box's clock; speed and gear hidden after 2 s without an answer; values cleared
  when the link drops.
- Faster car link: one method per attempt, sockets closed, no long sleeps, no `10 03` up front.
- Spotify keeps trying after a cold boot and when the internet or the clock comes good; never opens
  its permission screen while moving.
- Trip computer, route, update panel, playlist, EQ fixes listed above.
- Plain-English status everywhere.
- Frame timing in the log.

**Build B, "Smooth".**
- Speed read 4 to 5 times a second and eased; power line eased.
- Map camera every frame; calm marker when GPS is poor (garage).
- Each part of the screen redraws only for its own data; log writing off the hot path; speed-limit
  lookup off the screen thread.
- Panels slide and fade; day/night cross-fades; cards animate in and out.

**Build C, "Finished".**
- One type scale with a floor of about 3 mm for anything read while driving; bigger speed-limit sign.
- All targets at least 11 mm with press feedback; WAZE / END / tabs become real buttons.
- Music and Audio redrawn in the v3 look, with our keyboard and the same driving lock.
- Setup split into "Updates & logs" and "Diagnostics".
- Unused code removed.

## Decisions (answered by the owner, 2026-11-10)

1. **Dock labels:** no. Icons only.
2. **Speed locks:** none. Every feature must work at any speed. The EQ sliders stay usable, Spotify
   search is not locked, and the lock on destination typing above 5 mph is removed.

Consequences for this review: the "typing while moving" findings under Driving safety are closed by
the owner's decision and are not counted as open issues. Two safety points remain and are kept:
nothing opens by itself while the car is moving (Spotify's permission screen), and Spotify search
uses the large keyboard instead of the box's small one.

## Status

- Build 158 = Build A, released.
- Build B and Build C are written and checked on the build server (compile, unit tests, and pictures
  of the screens), each on its own branch. They are released one at a time after the previous build
  has been driven, so a problem can be traced to one build.
