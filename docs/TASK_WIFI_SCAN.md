# Task brief: Wi‑Fi network scan (read‑only discovery)

For the model doing the build. Read `docs/HANDOFF.md` and `CLAUDE.md` first; this task
lives inside those rules, it does not replace them.

## Why this exists
The owner wants to know, with evidence rather than assertion, whether the car exposes
anything — ideally camera feeds — to a device joined to its Wi‑Fi hotspot (`Taycan_2211`).
The honest expectation is that the car isolates its internal systems and the scan finds
nothing of interest. That's fine: the point is to settle the question by looking, not to
get in anywhere. **The deliverable is a passive look at what is already reachable, and
nothing more.**

## The line — read this before writing any code
This is network **discovery**, not intrusion. You are enumerating what hosts/services on a
network the box is *already joined to* freely offer to any device on that network.

MUST NOT, under any circumstances, even if it looks easy or the owner later asks in the heat
of the moment:
- No authentication attempts of any kind: no logins, no default/guessed credentials, no
  brute force, no token replay.
- No exploiting a service: no crafted, malformed, oversized or fuzzing payloads; no known‑CVE
  probes; no attempts to trigger a bug.
- No writes anywhere. Observe only. No command, config change, reboot, or upload to any host.
- Nothing to the car's control systems. This is **IP/Wi‑Fi only**. Do not touch OBD, CAN,
  the adapter, or any vehicle request — that path already has its own rules in CLAUDE.md and
  is unrelated here.
- No attempt to defeat segmentation. If a host or port does not answer, that silence **is the
  result** — log it and move on. Do not try to route around a firewall, pivot, spoof, ARP‑
  poison, or reach a subnet the box isn't on.

Finding something protected is a **STOP‑and‑report**, not a green light. The scan's job ends
at "here is what responded." Any decision about a thing that is found is the owner's, in a
later session, with a human in the loop. Build the feature so that it *cannot* do more than
look.

If a requirement here seems to push past this line, stop and leave a note in the log/PR
rather than improvising.

## What to build
A **"Wi‑Fi network scan"** button in the Setup screen. Runs only when the owner taps it, only
while parked (speed 0 / unknown, same gate the Spotify auth screen uses). One shot per tap,
with a visible progress line and a plain‑English result summary on screen, full detail to the
log.

Behaviour on tap:
1. Confirm the box is on a Wi‑Fi network. If not, say so in plain words ("The box isn't on
   Wi‑Fi — join the car's hotspot first") and stop. Report the SSID if available so the owner
   can confirm it's actually `Taycan_2211` and not the box's 4G.
2. Read the local IP, netmask, gateway and DNS from the active Wi‑Fi link. Derive the local
   subnet (cap the sweep at a /24 — do not scan beyond the box's own subnet).
3. Host discovery: read the ARP table, plus a bounded reachability sweep of the subnet
   (InetAddress.isReachable / TCP connect to a common port). Collect live hosts.
4. For each live host, a **TCP connect scan** of a short, fixed port list (see below). Connect,
   note open/closed/filtered, read any banner the service offers on connect, then close. Short
   timeouts; bounded concurrency so the box doesn't choke.
5. For open HTTP/HTTPS ports: a single plain `GET /` (no auth header). Log status code, `Server`
   header, and `<title>`. Follow at most one redirect. Nothing else — do not crawl, do not post.
6. Write everything to the session log as `[wifiscan]` lines: scan start with SSID/subnet, each
   live host, each open port + banner, each HTTP result, and a final summary count. On‑screen:
   a short summary ("Found N devices, M open ports — detail in the log").

Port list (keep it short and generic — discovery, not a pentest sweep): 80, 443, 554 (RTSP),
8080, 8443, 8554, 1935 (RTMP), 22, 23, 21, 445, 139, 5000, 8000, 37777, 49152. RTSP/RTMP/HTTP
are the ones that would matter if a feed were ever exposed; the rest just characterise the host.
For an open RTSP port, log the banner from a bare `OPTIONS` handshake only — **no** `DESCRIBE`
with credentials, no stream pull.

## Project fit (match existing patterns)
- Setup button, same style as the other Setup actions. Parked‑only gate.
- Log via `AppLog` with a `[wifiscan]` tag, so "Send log to Claude" carries it.
- Respect/extend `NetMonitor` for the current link state rather than duplicating it.
- Plain‑English on‑screen text and a numbered owner step somewhere visible (how to join the
  hotspot first). The owner works only from the iPhone and can't do anything on a computer.
- Android 13 (SM6225). Check the local‑network permission situation yourself — recent Android
  gates LAN access and may need `NEARBY_WIFI_DEVICES` and/or a local‑network permission in
  addition to `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` / `INTERNET`. Request at tap time
  with a plain explanation, and if the platform refuses LAN scanning, log that and tell the
  owner rather than failing silently.

## Prerequisite to flag to the owner
The box can only scan `Taycan_2211` if it can actually **join** it, which needs an active
Porsche Data Pass — we believed the owner's was lapsed as of 2026‑11‑08. Build the scan to run
on **any** Wi‑Fi so the owner can test it on a home network first and confirm it works, then
try the car hotspot. Put a line on screen to that effect.

## Done means
- Button present, parked‑gated, one‑shot, with progress + summary on screen.
- `[wifiscan]` lines cover SSID/subnet, hosts, open ports, banners, HTTP titles, summary.
- Scope is provably limited to look‑only on the box's own subnet (no auth, no payloads, no
  writes, no cross‑subnet, no OBD/CAN).
- CI build green before telling the owner it's ready (per CLAUDE.md).
- Expected real‑world result, stated to the owner up front: likely "a few devices, little or
  nothing camera‑shaped," which answers the question. Anything that does turn up is reported,
  not acted on.
