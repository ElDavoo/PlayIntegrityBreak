#!/system/bin/sh
# "Action" button in the KernelSU/Magisk manager: opens the PIB app.
PIB_PACKAGE=it.eldavo.pib.test

# am start can exit with 0 even when the activity doesn't exist, so check its output too.
OUTPUT=$(am start -n "$PIB_PACKAGE/it.eldavo.pib.ui.activity.MainActivity" 2>&1)
case "$?:$OUTPUT" in
  0:*Error*|[1-9]*)
    echo "! Cannot open PIB: is the app ($PIB_PACKAGE) installed?"
    exit 1
    ;;
esac
echo "- Opened PIB"
