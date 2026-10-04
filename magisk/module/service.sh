#!/system/bin/sh
# Magisk runs this after boot_completed. The daemon waits for BLE-M3 to connect.
MODDIR=${0%/*}
LOG=/data/adb/ble-m3-remapper.log
chmod 0700 "$MODDIR/bin/ble-m3-remapper"
exec "$MODDIR/bin/ble-m3-remapper" >> "$LOG" 2>&1
