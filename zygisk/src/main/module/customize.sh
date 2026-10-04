#!/system/bin/sh
# shellcheck disable=SC2034
SKIPUNZIP=0

PIB_PACKAGE=it.eldavo.pib.test
PIB_APK=$MODPATH/pib.apk

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
  ui_print "- LSPosed detected: if the PIB Xposed module is also enabled,"
  ui_print "  only one backend will hook the Play Store"
fi

# Install or update the bundled PIB app. pm cannot read /data/adb, so stage it in /data/local/tmp.
install_for_user() {
  if ! pm install -r --user "$1" "$STAGED_APK" >/dev/null 2>&1; then
    ui_print "! Cannot install the PIB app for user $1"
    ui_print "  (if another build of PIB is installed, uninstall it and flash again)"
  fi
}

STAGED_APK=/data/local/tmp/pib-zygisk-install.apk
if [ "$BOOTMODE" != "true" ]; then
  ui_print "! Not flashed from a root manager app: install the PIB app manually"
else
  cp -f "$PIB_APK" "$STAGED_APK" && chmod 644 "$STAGED_APK"
  UPDATED=0
  for user in $(pm list users | sed -n 's/.*UserInfo{\([0-9]*\):.*/\1/p'); do
    # Skip Xiaomi's dual app space, and users that don't have PIB.
    [ "$user" = "999" ] && continue
    pm path --user "$user" "$PIB_PACKAGE" >/dev/null 2>&1 || continue
    ui_print "- Updating PIB app for user $user"
    install_for_user "$user"
    UPDATED=1
  done
  if [ "$UPDATED" = "0" ]; then
    ui_print "- Installing PIB app for user 0"
    install_for_user 0
  fi
fi
rm -f "$STAGED_APK" "$PIB_APK"

set_perm_recursive "$MODPATH" 0 0 0755 0644
