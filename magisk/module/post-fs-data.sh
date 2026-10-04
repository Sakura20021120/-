#!/system/bin/sh
# Configuration uses the BLE-M3's original 0..4095 coordinate range.
CFG=/data/adb/ble-m3-remapper.conf
if [ ! -f "$CFG" ]; then
  cat > "$CFG" <<'EOF'
# BLE-M3 raw coordinates (0..4095). Reboot after editing.
default_x=1012
default_y=1740
target_x=2048
target_y=3600
tolerance=5
EOF
  chmod 0600 "$CFG"
fi
