# BLE-M3 Fixer

LSPosed module that redirects the BLE-M3 mouse center-button click from `(1012, 1740)` to `(2048, 3600)` in the 0–4095 coordinate space.

Enable the module for **System Framework (`android`) only**, then reboot. Adjust `TARGET_X` and `TARGET_Y` in `MainHook.kt` and rebuild if the shutter position needs calibration.

The hook only handles mouse-source `ACTION_DOWN` events near the known default coordinate and catches failures to avoid taking down `system_server`.
