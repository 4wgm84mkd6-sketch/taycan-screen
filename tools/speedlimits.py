#!/usr/bin/env python3
"""
Offline speed limits for the app (run by CI before the APK is built).

Reads `osmium export -f geojsonseq` output of OpenStreetMap roads that carry a "maxspeed" tag and
writes app/src/main/assets/speedlimits.txt, one road per line:

    <mph> TAB <name> TAB <lat*1e5>,<lon*1e5>,<dlat>,<dlon>,...

The first line is a "#" header with the data's date. Coordinates are integers in 1e-5 degrees
(about 1 m), the first point absolute and the rest as differences from the previous point.

Usage: speedlimits.py <roads.geojsonseq> <out.txt> <data timestamp>
"""
import json
import re
import sys

MAXSPEED = re.compile(r"^(\d+(?:\.\d+)?)\s*(mph|km/h|kmh)?$")


def mph(tag):
    """Same rules as SpeedLimit.parseMaxspeed in the app: "35 mph" -> 35, "50" (km/h) -> 31."""
    if not tag:
        return None
    t = tag.split(";")[0].strip().lower()
    m = MAXSPEED.match(t)
    if not m:
        return None
    n = float(m.group(1))
    return round(n) if m.group(2) == "mph" else round(n * 0.621371)


def main(src, dst, stamp):
    roads = 0
    points = 0
    with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as out:
        out.write(f"# OpenStreetMap speed limits (ODbL, (c) OpenStreetMap contributors), data {stamp}\n")
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            feat = json.loads(line)
            geom = feat.get("geometry") or {}
            props = feat.get("properties") or {}
            if geom.get("type") != "LineString" or "highway" not in props:
                continue
            limit = mph(props.get("maxspeed"))
            if limit is None or limit <= 0:
                continue
            coords = geom.get("coordinates") or []
            if len(coords) < 2:
                continue
            name = (props.get("name") or props.get("ref") or "").replace("\t", " ").replace("\n", " ")
            nums = []
            plat = plon = 0
            for i, (lon, lat) in enumerate(coords):
                ilat, ilon = round(lat * 1e5), round(lon * 1e5)
                if i == 0:
                    nums += [ilat, ilon]
                else:
                    nums += [ilat - plat, ilon - plon]
                plat, plon = ilat, ilon
            out.write(f"{limit}\t{name}\t{','.join(map(str, nums))}\n")
            roads += 1
            points += len(coords)
    print(f"speedlimits: {roads} roads, {points} points -> {dst}")
    if roads == 0:
        sys.exit("no roads with speed limits found")


if __name__ == "__main__":
    main(*sys.argv[1:4])
