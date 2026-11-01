# Step 2 decisions (from the real feature dump, 2026-11-01)

Where `docs/feature-dump/` differs from the original spec, these decisions apply.

| Topic | Dump shows | Decision |
|---|---|---|
| Devices | `0` = `E3_Vitodens_100_0421_1` (boiler), `TCU` = `E3_TCU41_x04` (gateway). One heating circuit (`heating.circuits.0`); circuit 1 exists but is disabled. | Curated screens target device `0` and circuit `0`; the data model still supports several. |
| Hot water | `heating.dhw.*` enabled with executable commands, but mode `off`, cylinder size 0 L. | Hidden by default; a setting shows them (spec). |
| Return temperature | No feature. | Not shown. |
| Error messages | No `device.messages.errors.raw`. Present: `status.raw` (S-codes), `service.raw`, `info.raw`, `device.lock.malfunction`. | Only **F.** faults (and the malfunction lock) are issues: red banner, notification, Messages tab. **A.** warnings: amber banner and notification. **S.**, **P.** and **I.** codes are informational only and listed under More → Status messages (changed on user request; originally status codes also warned). Descriptions come from Viessmann's error-database API; the offline manual table was removed on user request. |
| Consumption | Only `heating.gas/power.consumption.summary.*` and `heating.heat.production.summary.*` counters (`currentDay`, `lastSevenDays`, `currentMonth`, `lastMonth`, `currentYear`, `lastYear`). No arrays. | Summary cards with month/year comparisons now. Day/week/month/year bar charts built from the app's own snapshots of these counters. Gas m³ → kWh with an editable factor (default 10.55 kWh/m³). Efficiency = heat produced / gas energy. |
| Supply limits | `heating.circuits.0.temperature.levels` commands are not executable (Basic plan). | Shown read-only. |
| Comfort program | `activate`/`deactivate` not executable; `setTemperature` is. | Only the temperature is editable. |
| Holiday | `schedule`/`unschedule` executable; `changeEndDate` not. | Date-range picker schedules or cancels; no end-date edit. |
| Schedule | `setSchedule` constraints: modes `normal`, `comfort`; `maxEntries` 2; `resolution` 10 min; `defaultMode` `reduced`; `overlapAllowed` false. | Enforced by the schedule editor rules. |
| Online status / Wi-Fi | `gateway.devices` (per-device status) and `gateway.wifi` in gateway features; `tcu.wifi` in device features. | A full refresh = device features + gateway features (2 calls). |
