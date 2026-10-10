# Cold `xpe` acquisition: findings on the DI chain

Status: **the integrity object can be built in the Play Store process without any visible UI, and its starter
returns, but no verdict is produced without the dev-options screen composed** (section 6). A second route, the
official decode request, runs without the screen and without the activity (section 10). The server does not accept
our request body yet. Everything here was measured on Play Store
52.9.21-34 (versionCode 85292140, `base.apk` sha256 `320a26fcbce3c7d3e9ee3d0aead4178c8af5166181189b498970143205a5d62b`),
on a LineageOS device (Android 17, SDK 37) with KernelSU. Obfuscated names are specific to this build.

Reproduction tooling is in `tools/playstore-re/` (see its README).

## 1. The integrity object

- The starter `xpe.f(aobx)` reads `this.c` (a `wnb`) and `this.a` (a `cgnz`), builds the
  `"testing-" + UUID` nonce and enqueues an event on `this.a`. `f(null)` is the trigger, as in
  `playstore-checker-findings.md`.
- Only the integrity variant has `c = wnb` and `a = cgnz`. Its constructor is `xpe(Laqjj;Lbdry;Lwnb;Lcejk;)V`.
- That constructor is built by the Dagger `SwitchingProvider` `Lrmw`, **switch key 922**: case target
  `0x924`, `new-instance xpe` at `0x92a`, constructor call at `0x986`.
- Other `xpe` constructions in the same switch: key 900 (`0xc7a`) and key 923 (`0x90b`). `Lrme.aJ()` builds an
  eight-argument variant. None of them is the integrity variant.
- Runtime check that passed in the experiment: `xpe.c` is a `wnb`, `xpe.a` is a `cgnz`.

## 2. The provider switch

- `Lrmw implements Lcema` (a provider). Constructor `Lrmw(Lrnk, Lrmc, Lrmq, Lrme, int key)`.
- The key is dispatched in two chunks: `o()` handles keys 900-999 and `b()` keys 0-99.
- Case 922 reads only `Lrmw.a` (the root `Lrnk`, reached as `Lrnk.a` then `Lrnm.a`) and `Lrmw.b` (the `Lrme`),
  plus root providers, and builds `Lzoj`, `Laqjj` and `Lwnb`.

## 3. Components and constructors

| Class | Constructor | Created by | Notes |
|---|---|---|---|
| `Lrnk` (root) | `Lrnk(Lcgzx;)` | `Lcekt.y()` | Process singleton. `Lrii.y()` calls `Lrii.b()` then `Lcekt.y()`. `ClassicApplication` extends `Lrii`. About 4,000 `Lrnj` providers. |
| `Lrmc` | `(Lrnk, Lcela)` | `Lrmr.a(Lcejo)` | Stores the `Lcela`, which is the activity component's entry point. |
| `Lrmq` | `(Lrnk, Lrmc, Activity)` | `Lrmo.a()` | About 600 `Lrmp` providers. Some providers cast the activity to `SystemServicesActivity`. |
| `Lasou` | `(Object a, Object b)` | merged class | `a` is the activity owner (`mbe`). The no-argument `Lasou()` sets `a = new HashMap()`, which breaks the cast. |
| `Lrme` | `(Lrnk, Lrmc, Lrmq, Lasou)` | `Lpoz.kF`, key 8 | Activity-scoped component. The constructor stores `b = Lrmq` and `Bd = Lasou`. `Lrme.L()` returns `Bd`. |
| `Lrmw` | `(Lrnk, Lrmc, Lrmq, Lrme, int)` | `Lrme.aX()` and others | Provider. Key 922 is the integrity object. |

The sub-component builder path (`Lpoz.kF` key 8 with the `Lrnb` builder) is not needed: `Lrme` can be
constructed directly with the same arguments.

## 4. The activity owner (`mbe`)

- `mbe` is an interface. Key 10 of `Lrme` returns `Laspe.b(Lrme.L())`, and `Laspe.b(asou)` is `asou.a`.
  So `Lasou.a` must be the activity object.
- Keys 9 and 0 read the provider `Lrme.c` and cast its value to `mbe`.
- The chain then reaches ViewModel creation (`CreationExtras`), which needs the activity's
  `SAVED_STATE_REGISTRY_OWNER_KEY`. That value only exists once the activity has been created.

## 5. Experiments (all in the Play Store process, triggered over `adb`)

| # | Setup | Result |
|---|---|---|
| 1 | Own `Lrmc`/`Lrmq`/`Lrme`/`Lrmw(922)`, `Lasou()`, no activity state | Fails: `HashMap` cannot be cast to `mbe` (key 9). |
| 2 | Same, `Lasou(proxy, lock)`, owner proxy returns `null` for `S()` | Fails: `mbd.a` read on `null` in `mbc.b`. |
| 3 | Owner proxy returns a fresh `mbd` | Fails: `CreationExtras must have a value by SAVED_STATE_REGISTRY_OWNER_KEY` (ViewModel). |
| 4 | Real activity with `attachBaseContext` and `mApplication` set, `aN()` and `d()` called, no `attach`, no `onCreate` | Fails at the same ViewModel check. |
| 5 | Real activity with generic `Activity.attach` (20 parameters, each filled by type), `setTheme(manifest theme)`, `onCreate(null)` | **Succeeds.** `xpe` is returned with `c = wnb`, `a = cgnz`, and `f(null)` returns without an exception. |

Failures seen on the way to 5, which tell what a headless activity needs:

- No window: `Activity.onCreate` dereferences `getWindow()`. `attach` creates the window.
- No window callback: `Window.setCallback` must be the activity.
- No component name: `PackageManager.getActivityInfo(null)` throws. `attach` takes the component from the intent.
- No `ActivityInfo`: `onCreate` reads `parentActivityName`.
- No fragment host: `FragmentController` has no activity. `attach` sets it up.
- No theme: `setContentView` requires an AppCompat theme. The manifest theme for this activity is `0x7f15092f`.

## 6. The verdict is not observed, and the consumer is the missing piece

Measured in the Play Store process after `f(null)` (headless activity, started and resumed, never shown):

- The starter enqueues its event on `xpe.a`, a `cgnz`. Its counter `cgnz.b` goes 0 to 4 after two calls, and
  the events sit in its queue `cgnz.a` (`Lcgfq`).
- Nothing consumes them. The observer array `Lcgoe.d` is `null`. Observers are added only by `Lcgoe.l()`,
  called from `Lcgnz.pq(Lcglo;Lcfzj;)` and `Lcgnk.f`. Seventeen classes call the suspending consumer
  `Lcgnz.a(Object, Lcfzj)`.
- Starting and resuming the activity (`onStart`, `onResume`, never shown) did not change this.
- No field of the graph objects (root, `Lrmc`, `Lrmq`, `Lrme`, `Lasou`, the activity, `Lrmw`, `Lcekn`) holds
  the same `cgnz` instance as `xpe.a`.
- `Lasou.<init>([B)`, the other `Asou` variant, creates a `cgnz` keyed
  `"Finsky.ComposeNav.NavControllerFacade.back_stack_type_stack"`. That points to Compose navigation.

Conclusion (hypothesis, consistent with all of the above): the event is collected by the composition of the
dev-options screen. A composition needs an attached window, and a headless activity has none, so no verdict can
be produced without showing the screen once. The spike observed the same thing from the other side: after the
screen has run once in a process, the cached `xpe` keeps working in the background
(`playstore-checker-findings.md`). So the cold headless path builds the object, but cannot yet produce a verdict
on its own.

Verifying this (the screen's composition collects `cgnz` events) is part of the next-steps plan, Phase 0.

## 7. Native library (DexKit): not usable in the injected process

- `System.loadLibrary("dexkit")` fails with `couldn't find "libdexkit.so"`. The payload classloader's native
  directories are only `/system/lib64` and `/system_ext/lib64`.
- `System.load("/data/adb/modules/pib_zygisk/lib/arm64-v8a/libdexkit.so")` fails with
  `dlopen failed: library ... not found`. No SELinux denial was found in logcat, so the cause is not confirmed.
- Another module in the same process (PlaySpoofer, Vector) logs `DexKit was not loaded`, so this is an
  environment limit, not a bug in PIB alone.
- The module's `module.prop` says `attachNativeLibs=true` (new build) and `lib/` is in the zip, but the classloader
  does not get the library directory.

Consequence: the anchors must come from an in-process reader of the app's own dex files, with no native code.

## 8. Other facts established

- `DexClassLoader` refuses a writable dex file (`Writable dex file ... is not allowed`). Mode 400 works.
- The UI → `ServiceClient` → payload round trip works: with the Play Store source selected, "Check now" stored a
  `PLAY_STORE` entry with status `UNAVAILABLE` and the loader's error text.
- Starting the Play Store process from `adb` needs a launch that is not a background service start. Binding
  through PIB's "Check now" starts it without UI.
- The lock screen blocks UI automation. `KEYCODE_WAKEUP` only wakes the display.

## 9. What is still unknown

- Whether the xpe's verdict path needs the activity's lifecycle beyond `onCreate`.
- Whether the anchors in section 3 are stable across Play Store updates, and how to re-validate them.
- The cost of running the activity's `onCreate` in the Play Store process (view inflation, memory).

## 10. The official request-and-decode path (session 3)

This is the route the dev-options check uses, and it does not need the dev-options screen to be composed.

- **Classic token, in-process: works.** Bind `com.google.android.play.core.integrityservice.BIND_INTEGRITY_SERVICE`
  for `com.android.vending` from inside the Play Store process, call transaction 2 with package
  `com.android.vending`, a 32-byte nonce and the Play Core version (the same protocol as `CheckerMonitorHook`).
  The callback returns `token` (1457 characters, a JWE with `A256KW`/`A256GCM`), `request.token.sid` and `error=0`.
  Measured on the device.
- **Decode request: reachable, body not accepted yet.** The endpoint is the Finsky request
  `fdfe/decodeintegritytoken` (`https://play-fe.googleapis.com/fdfe/`, path constant in `Lqgg.M`). The request is sent by
  `Lqir.a(Lbuiw, Loxg, Loxf)` through `Lqif`, obtained from `Lvwr.l("decodeintegritytoken")`. `Lvwr` is found in the root
  component graph. Measured: the request goes out through the Play Store's own request stack, and the server
  answers HTTP 400 (`DF-DFERH-01`).
- **Body, as built by `Llzg.b`:** `Lbuiw.e` = the token string (from `Lyqr.a`); `Lbuiw.b` has-bit 1 for the token;
  `Lbuiw.d` = `Lbuiv` (`b` has-bit 1, `c` = `Lbuje`); `Lbuje` holds the device info (`c` fingerprint, `d` brand,
  `e` device, `f` model, `g` product, with its `b` has-bits). `Lbuiw.c` is 2 on the main path.
  Tried: `Lbuiw.c` = 0 and 1 (HTTP 400), 3 (HTTP 500), has-bits `0x1f` (still 400).
- **No activity needed on this path.** With the headless activity step disabled (probe flag `pib.activity=false`),
  the classic token and the decode request still run, and the server answers in the same way. The decode uses only the
  root component graph (`Lvwr` is found from the root). The Compose screen is not involved, and the response comes back
  through a listener, not through the `cgnz` queue of section 6.
- **Test footprint:** about eight decode requests were sent to the endpoint in these tests, all rejected with HTTP 400 or 500.
- **Second builder:** `Lwns.a` writes the same body from `Lyqr`, where `Lyqr.b` is already an `Lbuiw`. Not analysed yet.
- **Response:** `Lwmp.c` reads the decoded `bujd` (`b == 1` is success, `c` is `Lbujc`, whose `b` is the labels string),
  then calls `zdc.cO("Labels: " + labels, requestId, deviceInfo)`. So the verdict is `bujd.c.b`.

What is still open: the field the server requires that the probe does not send. The next steps are in
`playstore-checker-next-steps.md`, section "Decode body".

## 11. Session 4: classic only, warm-up, and the account key

- **The dev-options check uses the classic API only.** A run of the official check logged one Play Core
  `requestIntegrityToken` (nonce `testing-…`, no cloud project number). No standard `prepareIntegrityToken` or
  other warm-up call appeared in logcat.
- **Docs.** Classic requests have no warm-up ("API warm up required: not required"). Standard requests need
  `prepareIntegrityToken()` first, at most 5 times per minute per app instance, and the provider expires with
  `INTEGRITY_TOKEN_PROVIDER_INVALID`. Both are decoded on a server with a service account in the linked Google
  Cloud project, so neither gives a device-only decode. The Play Store's internal `decodeintegritytoken` path
  stays the only device-side route.
- **Request matching.** The official classic request uses the nonce `"testing-"` plus 16 random bytes in
  base64url (token length 1441) and `playcore.integrity.version` 1.6.0. The probes used 1.4.0 and raw nonces.
  Both are matched now. The decode still returns HTTP 400 (`DfeServerError{a=400}`, DF-DFERH-01).
- **Account key.** `yib.C(name)` takes the account name: `Lpmv.f()` is the current account's name, and
  `Lyib.G` maps an empty name to `<UNAUTH>`. The probe called `vwr.l("decodeintegritytoken")`, which asks for
  an account of that name, so its requests were unauthenticated. That is fixed in `PlayStoreIntegrityCheck`
  but the decode still fails with the same 400.
- **Request object.** The official path and ours use the same `qir` object (flow `deferred`). The body matches
  field by field: token 1441 chars, product string stable across runs.
- **Warm path works.** `xpe.f(null)` in a process where the dev-options screen has run once returns a fresh
  verdict, and so does `PlayStoreIntegrityCheck.run()`, which returned `OK` with labels on repeated runs.
  In a cold process it returns `NOT_PRIMED`.

Open: the cold start (the integrity object is built when the dev-options screen composes), and the HTTP 400 on
our own decode request.

### 11.1 Request sequence of the official check (measured)

- The enqueue hook (`Loxe.d(Lowz)`, wire-level view) shows two requests in this order: first
  `buoa` (the warm-up, endpoint `https://play-fe.googleapis.com/fdfe/integrity`, flow `pia_attest_e1`, with a
  31,989-byte blob from DroidGuard; `GmsGuardHandleImpl.initWithRequest(pia_attest_e1, …)` in logcat), then
  `buiw` (the decode, endpoint `fdfe/decodeintegritytoken`).
- Our raw classic token request also enqueues `buoa` before the token returns. Waiting 5 s before the decode
  does not change the HTTP 400, so ordering is not the cause.
- The official classic callback bundle has two keys, `request.token.sid` and `token`. There is no
  `dialog.intent.type`, so the dialog-path handler `Lafvl.g` is not on the official path.
- A fresh token decoded with our body and the warm request object still returns HTTP 400. The official token,
  decoded a second time with our body, also returns 400. That second case may be a replay refusal, so it does
  not prove the body is wrong.

### 11.2 Cold objects (measured in a fresh Play Store process)

- Present: the account factory `yib` (2 instances; one has a `ConcurrentHashMap` cache only after the screen runs),
  `pmv` (account provider), the event queues `cgnz` (3 instances).
- Absent: the consumer chain `blwf`, `agxh`, `lzg` and the integrity object `xpe` (0 instances each).
  After a raw classic bind, the Finsky client `afvl` (1) and the request registry `afuq` (3) appear.

### 11.3 Warm path through PIB's IPC (measured)

- `PIBLoggerService.runPlayStoreIntegrityCheck` (IPC method 13): this measurement is superseded by section 12. The check
  now answers `CHECKER_RESULT_OK` (1) with the backend-shaped JSON in a cold process as well.
  Example: `{"deviceIntegrity":{"deviceRecognitionVerdict":["MEETS_BASIC_INTEGRITY","MEETS_DEVICE_INTEGRITY","MEETS_STRONG_INTEGRITY"]}}`.
- Google's docs (standard page: warm-up required, at most 5 per minute per app instance; classic page: no warm-up;
  both decoded server-side with a service account) do not describe a device-only decode. The official check is
  classic (one `requestIntegrityToken`, nonce `testing-…`, Play Core 1.6.0). The warm-up that appears in its
  traces is `buoa`, the Play Store's own request, not a standard `prepareIntegrityToken` call.

Open: a cold headless verdict. Either the dev-options consumer chain must be built without the screen, or the
server requirement that our decode lacks must be found. Neither is done.

### 11.4 Round 5: request comparisons, timing, and the consumer structure

Sources read with `gh` (raw files): microG's `vending-app/.../IntegrityExtensions.kt` (flow names `pia_attest_e1` and
`pia_express`, `fdfe/integrity`, `fdfe/sync`, `fdfe/intermediateIntegrity`, DroidGuard session handling, `warm.up.sid`) and
a 2026 write-up of the classic token (`zhiyu-zeng/img`, `md/2026/07`). The write-up shows that `fdfe/integrity` returns the
classic token itself: the request carries the DroidGuard blob, the key-attestation chain, the nonce and the Play Core version.
So the `buoa` request is the token request, not a separate warm-up.

Measured, all against our raw token path in a warm process:

- The `buoa` body equals the official one except the 32 KB DroidGuard blob, the timestamps, and one has-bits value of a timestamp
  sub-message. The 64-byte device identifiers are identical.
- The `buiw` decode body equals the official one. The request object equals it too (`owz` fields, URL, options `qgz`, headers
  built by `qis.a`). The only differences are the listeners (ours are proxies; `Lqgh.m` is read only on the response) and the tag.
- The dev-options screen issues no request when it opens. The official check is exactly `buoa` then `buiw`.
- Decoding a fresh raw token after 50 ms, 300 ms, 800 ms, 2 s or 4 s always returns HTTP 400 (once 500 right after the token).

Consumer structure (cold process): a fresh `cgnz` queue delivers its state to a registered observer, and an event enqueued
with `e` reaches it. The official observer `cgoj` (`Lcgoj.a`) casts its input to `cgln` and so needs the combine wrapper
`cgmi` over `psb`, `cgbi`, `cgat` and `cgbe` instead of the raw queue. The screen presenter `Lwmi` needs two `xpe`
objects, the event `zhy`, and `yqr` and `yew`, all of which are UI-scoped.

Status (superseded by section 12): the 400 was never about the token or the process state. The decode's device id must
be the nonce's suffix, and the raw path sent a constant. With that fixed, the cold verdict needs no UI.

## 12. Session 5: the cold verdict is headless

The decode request names the token request it answers. Its device id (field 2.1.5) must be the suffix of the nonce the
token was requested with, which is the 22 characters after `testing-`. The raw path sent a constant, and the server
answered 400 (DF-DFERH-01). Nothing else in the body or the process state was wrong.

Evidence, from the captures in this session:

- The dev-options check's integrity request (`fdfe/integrity`) has nonce `testing-xcfp7fnx077030cnkdjsso` (field 3, base64).
  Its decode request (`fdfe/decodeintegritytoken`) has field 1 = the token, and field 2.1.5 = `xcfp7fnx077030cnkdjsso`.
- The manual decode body equalled the official one in every field except field 2.1.5. Changing only that field to the
  nonce's suffix turned the 400 into HTTP 200 with the verdict list. Three runs with three nonces, each in a cold process
  (no dev-options visit), gave `[MEETS_BASIC_INTEGRITY, MEETS_DEVICE_INTEGRITY, MEETS_STRONG_INTEGRITY]`.
- The classic token is requested through the Play Store's integrity service (binder transaction 2). The Play Store sends
  `fdfe/integrity` itself for it, so no screen, `xpe` or DroidGuard call from PIB is needed.

Flow from process spawn (a temporary Volley trace, not committed): the process starts; the first `fdfe` request is the
GET `fdfe/popups`; the images follow. Cache-served GET requests never reach the HTTP stack, so the enqueue is the only
event they produce. The dev-options check is exactly `fdfe/integrity` (POST, 1495-byte response) and then
`fdfe/decodeintegritytoken` (POST, sent 250 ms later, 120-byte response with the labels).

Formats, from the captured bodies:

- Integrity request: field 1 holds the package name, a timestamp, the nonce and the certificate digests; field 3 is the
  flow name `pia_attest_e1`; field 5 holds the DroidGuard blob and the key-attestation chain (field 9).
- Integrity response: a JWE token (`{"alg":"A256KW","enc":"A256GCM"}`). Its claims cannot be read on the client.
- Decode request: field 1 = the token; field 2 = device, with field 1 = the fingerprint, 2 = brand, 3 = device,
  4 = model and 5 = the nonce suffix.
- Decode response: the verdict list as a string, for example `[MEETS_BASIC_INTEGRITY, MEETS_DEVICE_INTEGRITY]`.
- `X-PS-RH`: gzip and base64url of a `RequestHeader` protobuf (timestamps, device metadata, locality, a UUID). microG
  builds the same header in `vending-app/.../extensions.kt`. In this session the official decode and the manual decode
  sent byte-identical values (same session), so the header is not the binding.

What `PlayStoreIntegrityCheck` does now:

1. Binds the Play Store's integrity service and requests a classic token for `testing-` plus 16 random bytes (the nonce
   prefix of the official check).
2. Takes the request sender for the selected account (`yib.C`) and sends the decode request body above through `qif.a`.
   The device id is the nonce's suffix.
3. Reads the verdict list from the response by its shape, not by field names.

Timing: the classic token took about 0.5 to 3 s; the decode about 0.15 s.

Known gaps:

- Only the `testing-` nonce form was tried. A non-test nonce was not.
- The obfuscated names are for Play Store 52.9.21-34 (versionCode 85292140). A failure reports the installed versionCode.
- PIB must not rewrite `com.android.vending`'s responses (synthetic errors), which is the default from config version 95.
  With rewriting on, the classic token request gets a synthetic error and no token comes back. The monitor says so
  without running the check. The hook does not record PIB's own token request (it is recognised by its nonce), so it
  sends no telemetry event and no "asked for Play Integrity" alert; the app shows its own alert instead.

## 13. Session 6: the decode request is found in the build, not named

The obfuscated names change with every Play Store build (52.9.21-34 sends the decode request with `qir.a(buiw, oxg, oxf)`,
obtained from `yib.C`; 35.8.13-29 uses `itv.f(apwe, hwf, hwe)` from `jtp.f`), so `PlayStoreIntegrityCheck` names none of
them. It reads the Play Store's own dex (`ApplicationInfo.sourceDir`, no native code, one dex in memory at a time) and
anchors on what the build cannot rename: strings, the shape of a signature, and the server's wire format.

1. **The endpoint path.** The string `decodeintegritytoken` is loaded in a class initializer that stores
   `Uri.parse(path)` into a static `Uri` field (`const-string`, `invoke-static Uri.parse`, `move-result-object`,
   `sput-object`). That class is the Play Store's table of endpoints.
2. **The sender method** is the only method with no result and three object parameters that reads that field
   (`sget-object`). Its parameters are the request body type and two listener types. The error listener is the one whose
   method takes a `Throwable`.
3. **The sender factory** is a method that takes a String (the account name) and returns the sender class, or, if none does,
   an interface the sender implements.
4. **The request body** is written in protobuf wire format from the server's field numbers (token = 1; device = 2.1 with
   fingerprint, brand, device, model and the nonce suffix as 1 to 5), then parsed into the Play Store's message class by the
   protobuf runtime's own static parse method (the build keeps one of `(default, bytes)`, `(default, bytes, registry)` or
   `(default, bytes, offset, length, registry)`). No field of the message is named. After parsing, every value must be held
   in a string field of the message, or the check stops without sending: a changed layout would otherwise parse into
   unknown fields.
5. **The factory object** is searched in the object graph from the application (12 levels, 300,000 objects at most). R8
   merges classes, so several objects have the factory's class and only one has the right state: each is tried with a
   call, and the one that returns the sender is used.
6. **A factory is made when there is none.** The Play Store makes its factory when it first sends a request to the server,
   so a process started without one has nothing to find. The factory is made by a Dagger switching provider:
   a class with a constructor `(component, int id)` whose `get()` switches on the id. The locator finds, in the dex,
   - the factory's real constructor: R8 merged several classes into the factory's class, and the real constructor is the one
     that stores a new `ConcurrentHashMap` into the field that the factory method casts to a map;
   - its callers, directly or through one static helper (35.8 has `iqy.r`), and in which case of a switch on an `int`
     field of the provider class they are: the id is the key of the case that holds the call.

   Then `Provider(component, id).get()` is called with the component that the application holds (found by its type in the
   object graph), and the result goes through the same trial as in 5. Providers found: 35.8 `jmy#1148(jmz)`;
   52.9 `rnj#1467(rnk)` and `smk#204(sml)`. This is only done when the object graph has no usable factory.
7. **The account** is a Google account of the device. They are tried in turn (three at most) until the decode answers.

The classic token uses the Play Core integrity service, whose binder protocol is public and did not change between the two
builds below.

Measured, in the same code, on both builds:

| Play Store | locator | instruction walk | on the device |
| --- | --- | --- | --- |
| 52.9.21-34 (85292140) | `qir.a(buiw, oxg, oxf)` from `yib.C`, 0.1 s on a PC, 0.5 s on the phone | 266,678 methods, none wrong | verdict `[MEETS_BASIC, MEETS_DEVICE, MEETS_STRONG]`, from the periodic job, with PIB in the background, in a cold process (the process had made the factory itself) and with the factory made through the provider (forced once, for the test) |
| 35.8.13-29 (83581320, the system image's) | `itv.f(apwe, hwf, hwe)` from `jtp.f`, 0.5 s on a PC, 0.8 s on the phone | 178,762 methods, none wrong | verdict `[MEETS_BASIC]`, from a process that the check itself started (nothing else had made a request): the factory was made through the provider `jmy#1148` |

The 35.8 verdict has the basic level only, where 52.9 has all three. That is what its decode answered; the cause (the older
build's integrity request, or the build itself) was not examined.

Before the factory was made through its provider, 35.8 stopped here with `UNAVAILABLE`: the graph held three objects of the
factory's class (merged variants) and none had the state that the factory method needs.

Other limits: the endpoint path and the wire format are server and protocol facts, not client ones. The dex must be readable
(it is, for the app's own process). A build that renders the endpoint differently (no `Uri` field, or two senders
reading it) returns `UNAVAILABLE` with the reason and sends nothing. The provider search assumes Dagger's switching
provider shape (a `(component, int)` constructor and a switch on the id); a build that changes it still has the live factory
when the Play Store has made one.

Tests: `core/src/test` has the locator on a small dex (`tools/playstore-re/locator-fixture`) and, with
`PIB_PLAYSTORE_APKS=<base.apk>:<base.apk>` set, on real builds. The 35.8 APK is the device's own
`/product/priv-app/Phonesky/Phonesky.apk`. The same run walks every method of the given builds to check the opcode table.

### 13.1 Sweep over the major versions (session 6)

One build per major version, from APKMirror (the latest release of the major, the `.apk` with the highest min-API build
that Android 17 accepts), compared with the device's copies first: 35.8.13-29 is byte-identical to the system image's
`Phonesky.apk`, and 52.9.21-34 has the same eight `classes*.dex` as the installed `base.apk`.

Offline (`PIB_PLAYSTORE_APKS`): the endpoint and the factory providers were found, and every method's instructions end at
the code size, in every build of 35.8 and 36 to 53. On the phone, each build installed over the last in ascending
order, the Play Store force-stopped, then PIB's periodic job started in the background (the check starts the process
itself): every one of them returned a decoded verdict.

| Play Store | Verdict list returned |
| --- | --- |
| 35.8, 36, 37, 38, 39, 40, 45 | basic only |
| 41, 42 | empty list |
| 43, 44, 46 to 49, 50, 51, 52, 53 | basic, device and strong |

The verdict differs per build because the Play Store's own attestation does; the check reports what the decode answers.
38, 39 and 50 were tested with 38.8.28, 39.9.31 and 50.9.23: APKMirror's download flow needs the cookies from the variant page and a pause between steps (the downloader tool's plain fetches sometimes got a redirect or a challenge page).

What the sweep changed: R8 merges several providers into one class (36: `ivo` takes `(component: Object, id, variant)`), so the
locator now reads the provider's constructor (which parameter is the id, and which field each parameter is stored in),
and the other arguments come from a provider of that class that the Play Store has already made.
