# BLE-M3 Fixer

LSPosed module that redirects the BLE-M3 center-button click from `(1012, 1740)` to `(2048, 3600)` in the hardware's 0–4095 coordinate space.

Enable the module for **System Framework (`android`) only**, then reboot. Adjust `TARGET_X` and `TARGET_Y` in `MainHook.kt` and rebuild if the shutter position needs calibration.

The module installs a guarded system input filter because modern Android versions do not expose motion coordinates through `interceptMotionBeforeQueueing`. It handles only `ACTION_DOWN` events near the known normalized coordinate and forwards all other input unchanged.

## Magisk module (recommended)

`magisk/` contains a root-level remapper that takes exclusive control of the BLE-M3 Linux input device and mirrors it through `uinput`. It replaces only the raw `1012,1740` center-key coordinate; it forwards every other event unchanged. After flashing the Magisk ZIP and rebooting, edit `/data/adb/ble-m3-remapper.conf` if calibration is needed, then reboot. Runtime logs are in `/data/adb/ble-m3-remapper.log`.
