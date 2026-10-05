#!/system/bin/sh
# Sourced by the customize.sh that ZygoteLoader generates.

PIB_PACKAGE=it.eldavo.pib
PIB_APK=$MODPATH/pib.apk

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
