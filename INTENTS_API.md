# External Intents API

PIB exposes an optional Intents API that lets other apps change per-app policy values and default policy values.

This API is disabled by default.

## Enablement and safety model

1. Open Settings in PIB.
2. Enable External Intents API.
3. Confirm the warning dialog.

When enabled, any app on the device can call this API. Only enable it if you trust the apps you install.

## Transport

- Entry point: exported broadcast receiver
- Receiver: icu.nullptr.playintegritybreak.receiver.IntentApiReceiver (in package it.eldavo.pib)
- Action: it.eldavo.pib.action.SET_APP_SETTING
- Recommended: send explicit broadcasts with package set to it.eldavo.pib
- Response style: ordered-broadcast result code plus result extras

## Request extras

Required for all requests:

- targetPackage (String): target app package name, or default for global default policy
- settingKey (String): one supported key

Value extras:

- booleanValue (Boolean): required for boolean keys
- intValue (Int): required for integer keys

## Supported setting keys

Boolean keys (require booleanValue):

- enableIntervention
- enableLogger
- deliverSyntheticResponse
- delaySyntheticResponseDelivery
- rewriteIntegrityErrorRemediable
- integrityRequestAlert

Integer keys (require intValue):

- rewriteIntegrityErrorCode

Notes:

- For per-app updates, only the requested key is stored for the app. Every setting you never set follows the global default policy, including later changes to it.
- Setting enableLogger, deliverSyntheticResponse, delaySyntheticResponseDelivery, rewriteIntegrityErrorCode or rewriteIntegrityErrorRemediable for an app also turns enableIntervention on for that app.
- Values must have the right extra type (`--ez` for booleans, `--ei` for integers); anything else returns INVALID_VALUE.
- Set targetPackage to default to update the global default policy values.
- Default policy updates only the requested key and keep other default values unchanged.

## Result contract

Receiver sets ordered-broadcast result code and result extras:

- status (Int): same numeric status as result code
- message (String): human-readable message
- targetPackage (String): echoed when available
- settingKey (String): echoed when available

Status values:

- 0: APPLIED
- 1: API_DISABLED
- 2: INVALID_ACTION
- 3: MISSING_EXTRA
- 4: INVALID_PACKAGE
- 5: INVALID_KEY
- 6: INVALID_VALUE
- 7: INTERNAL_ERROR

## Example with adb

Boolean setting example:

```bash
adb shell am broadcast \
  -a it.eldavo.pib.action.SET_APP_SETTING \
  -p it.eldavo.pib \
  --es targetPackage com.example.bankapp \
  --es settingKey enableIntervention \
  --ez booleanValue true
```

Integer setting example:

```bash
adb shell am broadcast \
  -a it.eldavo.pib.action.SET_APP_SETTING \
  -p it.eldavo.pib \
  --es targetPackage com.example.bankapp \
  --es settingKey rewriteIntegrityErrorCode \
  --ei intValue -8
```

Default policy example:

```bash
adb shell am broadcast \
  -a it.eldavo.pib.action.SET_APP_SETTING \
  -p it.eldavo.pib \
  --es targetPackage default \
  --es settingKey enableLogger \
  --ez booleanValue false
```

## Example from Android app

```kotlin
val intent = Intent("it.eldavo.pib.action.SET_APP_SETTING").apply {
    setPackage("it.eldavo.pib")
    putExtra("targetPackage", "com.example.bankapp")
    putExtra("settingKey", "enableLogger")
    putExtra("booleanValue", true)
}

context.sendOrderedBroadcast(
    intent,
    null,
    object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = resultExtras
            val status = resultCode
            val message = extras?.getString("message")
            // Handle status + message
        }
    },
    null,
    0,
    null,
    null,
)
```
