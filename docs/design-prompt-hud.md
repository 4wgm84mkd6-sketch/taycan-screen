# Design prompt: "engineer's HUD" restyle of Taycan Screen

Paste everything below the line into a new Claude chat (claude.ai, not Claude Code). Attach 2–3 photos
of the current screen in the car (day and night, Drive screen and Music panel).

---

Redesign the look of my custom car dashboard app so it feels like the in-car interface a genius
billionaire engineer would build for himself: a futuristic, holographic heads-up-display aesthetic.
Think precise thin line-work, luminous cyan/amber accents on deep black, technical micro-labels,
segmented arcs and rings, subtle scan-line and glow, data that feels alive. It must be an ORIGINAL
design: no film or comic logos, character or AI-assistant names, "arc reactor" replicas, or company
branding from any franchise. Evoke the genre, don't copy it.

## What it is
- An Android app on a box that drives the center screen of my 2025 Porsche Taycan (shown inside the
  Porsche CarPlay frame). Built in Jetpack Compose by Claude Code, which will implement your design.
- Existing design (layout reference, keep the structure): https://claude.ai/artifact/PhA2Jt5qDhHqoA658Q2WDw
  (artboards Drive day/night, Audio & EQ).
- Photos of the current build in the car are attached.

## Hard constraints (non-negotiable)
- Canvas: 1602 × 720 px window (density 1.49 → about 1076 × 484 dp). The Porsche frame's own icon
  column (left) and temp/4G column (right) sit outside this area. Design artboards at 1602 × 720.
- Touch is ONE finger only (no pinch, no multi-touch). Targets at least 72 px (48 dp).
- Glanceable at arm's length while driving: big numerals, high contrast, minimal text, no video.
  Motion is subtle while moving; flourishes only at start-up and when parked.
- The instrument cluster stays the official speedometer; our speed readout is secondary.
- Must work in Day and Night themes (switched by sun position). Day must stay readable in Hawaii
  sunlight: don't make it black-only.
- Cheap to render on a modest Snapdragon (SM6225): no full-screen blurs, no 3D, no particle systems.
  Glow = layered strokes/soft shadows; animations 60 fps; vector line art over bitmaps.
- Fonts: free/OFL only so they can be bundled (e.g. Rajdhani, Exo 2, Saira, Orbitron for accents,
  Inter or JetBrains Mono for numbers/labels). Say which and why.

## Live data available (design for these, nothing invented)
- Speed (mph), gear P/R/N/D, battery % with range (mi), power in kW (negative = drawing, positive =
  charging/regen, roughly −400…+270), HV battery voltage/current, pack temperature min/max, outside
  temperature, 12 V, car link status, clock.
- Map position and heading (OpenStreetMap tiles; we can tint/recolor tiles with a color matrix).
- Spotify: now playing (title, artist, album, cover art, progress, play/pause/skip), browse lists
  with covers, search results.
- Equalizer: 10 bands (31 Hz–16 kHz, ±12 dB), presets, saved EQs, speed-adaptive bass switch.
- Volume, day/night mode, connection/GPS status chips.
- NOT available: turn signals, cameras, drive mode (planned later), turn-by-turn directions.

## Screens to design (keep current structure, restyle everything)
1. Drive: left vehicle panel (~300 dp: gear row, battery + range, speed, power, now-playing card),
   map on the right with overlays (clock/temp pill, zoom −/+, heading/north toggle, recenter, GPS
   chip), dock along the bottom (vehicle, navigate, music, equalizer, day/night, setup, volume).
2. Drive while charging (power becomes charge rate; battery becomes the hero).
3. Music: big Now playing (cover, title, progress, large controls) and Browse/Search list over the map.
4. Audio: 10-band EQ with curve, presets, Save, speed-adaptive switch.
5. Vehicle details: diagnostic readouts (tiles with values + raw data line).
6. Start-up sequence (2–4 s while it connects): a short "systems online" moment with status checks
   (car link, GPS, Spotify) that resolves into the Drive screen.

## Future-proofing
- Drive-mode accent themes: Range = green, Normal = default accent, Sport = amber, Sport Plus = deep
  red. Build the palette so only the accent tokens swap.

## Deliverables (in this order)
1. A short design direction: name, 3–4 principles, mood words, what to avoid.
2. A design canvas with artboards for every screen above, Night and Day for Drive.
3. A component sheet: speed numeral, gear selector, battery ring/bar, power arc (center-zero),
   cards, list rows, chips, dock buttons, EQ slider, toggle, map overlays, alerts.
4. Design tokens as JSON I can hand to Claude Code: colors (night, day, mode accents), type scale,
   spacing, radii, stroke widths, glow (color, radius, opacity), opacity levels, and motion (durations,
   easings for value changes, panel transitions, start-up).
5. An SVG icon set in one consistent line style (24 px grid, stroke-based) for every icon used.
6. Map styling: target colors for land, water, roads, parks in Night and Day, so we can derive a
   color-matrix tint for the tiles.
7. Implementation notes for Jetpack Compose: what's cheap vs. expensive, how to fake glow, which
   animations to drop while the car is moving.
