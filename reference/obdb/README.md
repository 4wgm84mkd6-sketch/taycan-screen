# OBDb Porsche Taycan signal definitions

`porsche-taycan-default.json` is `signalsets/v3/default.json` from
https://github.com/OBDb/Porsche-Taycan at commit `badd73d2024e475053d77af02cc00eb4240378d1`
(2026-11-07), licensed CC BY-SA 4.0 by the OBDb contributors. Unmodified copy for reference.

Why this source: Car Scanner's Porsche profile ("Porsche EV: eTron, eTron GT, Taycan") is built in
and does not show headers/requests/formulas. The owner approved OBDb as the definition source on
2026-11-08. OBDb's module tags match Car Scanner's: [01.ENG] = 7E0, [8C.BMS] = 7E5, [19.Gate] = 710.

How to read an entry: `hdr` = request CAN ID, `rax` = response CAN ID (default hdr+8),
`cmd` {"22": "1801"} = UDS ReadDataByIdentifier 22 1801, `din: "03"` = module must be in the extended
diagnostic session (10 03) first, `fcm1`/`fc=1` = custom ISO-TP flow control needed for multi-frame
replies. `fmt`: take `len` bits starting at bit `bix` of the data after the DID (big-endian),
value = raw * mul / div + add; `sign` = two's complement; `nullmin`/`nullmax` = "no data" values.
The 2025+ cars ("J1.2") use different formulas for some signals (e.g. battery health).
