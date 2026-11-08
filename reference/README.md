# Reference

Put the Car Scanner custom PID definitions (export file or screenshots) and car photos here.
OBD code must use these definitions exactly. See CLAUDE.md.

## What's here so far

`car-scanner-sensor-list/`: screenshots of Car Scanner's sensor picker (2026-11-08, parked, charging).
These show sensor **names, module tags and live values only**. They are NOT the definitions
(header, request, formula, protocol) and must not be used to guess addresses.

Module tags seen: `[01.ENG]` HV Battery SoC Display (17.18 %), `[8C.BMS]` HV Battery State of health
(66.17 %), `[19.Gate]` DC-DC converter values, max energy content of traction battery (18500 Wh),
estimated range (internal/CAN/displayed), average consumption per road category, `[PTC]` heater
current / water pump, plus Car Scanner's standard/calculated items (vehicle speed, control module
voltage, HV EV Battery Power 1.22 kW, min/max cell voltage, energy used/costs).
`[5F.MMI] Reboot Multimedia System [Action]` is a command: never send it.
