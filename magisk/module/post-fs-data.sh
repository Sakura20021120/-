#!/system/bin/sh
# Configuration uses the BLE-M3's measured axes: X 0..1800, Y 0..4100.
CFG=/data/adb/ble-m3-remapper.conf
if [ ! -f "$CFG" ]; then
  cat > "$CFG" <<'EOF'
# BLE-M3 raw coordinates: X 0..1800, Y 0..4100. Reboot after editing.
default_x=1012
default_y=1740
target_x=900
target_y=3370
tolerance=5
EOF
  chmod 0600 "$CFG"
fi
