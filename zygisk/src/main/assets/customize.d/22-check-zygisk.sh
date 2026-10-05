#!/system/bin/sh
# Sourced by the customize.sh that ZygoteLoader generates.

# Zygisk check (adapted from HMA-OSS): ZygiskNext/ReZygisk ship zygiskd, Magisk has a setting.
ZYGISK_NAME=
for folder in /data/adb/modules/* /data/adb/modules_update/*; do
  { [ -f "$folder/disable" ] || [ -f "$folder/remove" ]; } && continue
  [ ! -f "$folder/bin/zygiskd" ] && [ ! -f "$folder/bin/zygiskd64" ] && continue

  NAME=$(grep "^name=" "$folder/module.prop" | cut -f2 -d"=")
  [ -n "$ZYGISK_NAME" ] && [ "$NAME" != "$ZYGISK_NAME" ] && \
    abort "! Multiple Zygisk implementations found, disable all but one"
  ZYGISK_NAME=$NAME
done
if [ -z "$ZYGISK_NAME" ]; then
  if [ "$ZYGISK_ENABLED" = "1" ] || \
     [ "$(magisk --sqlite "SELECT value FROM settings WHERE key = 'zygisk'" 2>/dev/null | cut -f2 -d=)" = "1" ]; then
    ZYGISK_NAME="Magisk Zygisk"
  fi
fi
[ -z "$ZYGISK_NAME" ] && abort "! Zygisk is not enabled. Enable Zygisk (or install ZygiskNext) first"
ui_print "- Found $ZYGISK_NAME"

if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ]; then
  ui_print "- Xposed framework detected: if the PIB Xposed module is also"
  ui_print "  enabled, only one backend will hook the Play Store"
fi
