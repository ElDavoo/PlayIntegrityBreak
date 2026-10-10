# Play Store built-in checker: detailed plan for the next steps

Read `playstore-checker-di-chain.md` first; it has the evidence this plan relies on. This plan replaces the
open items of `playstore-checker-TODO.md` for the cold-acquisition work.

## Where things stand

- **Works, measured on the device:** the app's "Play Store built-in" source reaches the Play Store payload
  over the service binder and stores the result in history. An in-process experiment builds the integrity
  `xpe` from the Dagger graph (key 922) inside an activity that is attached, themed and created, but never
  shown. `xpe.c` is `wnb`, `xpe.a` is `cgnz`, and `xpe.f(null)` returns without an exception.
- **Superseded (session 5):** the verdict is established headlessly. The decode's device id must be the nonce's
  suffix; see `playstore-checker-di-chain.md`, section 12. The consumer and dev-options work below is no longer on the path.
- **Blocked:** DexKit cannot load its native library in the injected process, so the locate step must
  not depend on it.
- **Committed on this branch with this plan:** the findings, this plan, and the research tools in
  `tools/playstore-re/`.
- **Not committed (local working tree, by design):** the feature code (`PlayStoreIntegrityCheck`, monitor
  and UI changes) and the temporary development hooks (`DevExperiment.kt`, the `System.load` workaround).
  The development hook runs dex from the Play Store's data directory when it receives a broadcast, so it
  must never be pushed.

## Phase 0: confirm a verdict (blocker for everything else)

Goal: the probe in `tools/playstore-re/experiment/Exp.java` logs `VERDICT labels=... testId=...` for a
`f(null)` call, twice in the same process.

Status after the first pass: `f(null)` returns; `onStart`/`onResume` did not help; `cgnz`'s observer array is
`null`, so the event is queued and never consumed. The likely consumer is the Compose navigation host of the
dev-options screen, which needs an attached window. Phase 0 now asks whether that is true, and what a headless
or invisible composition would require.

Tasks, in order. Stop at the first that produces a verdict.

1. Look for evidence that a token request was made. Grep logcat for `IntegrityServiceHook`,
   `IntegrityService`, `integrity`, `Token`, `nonce`, `DecodeIntegrityToken`, starting from the time of the
   `f(null)` call. `IntegrityServiceHook` already hooks the classic integrity service and logs its calls.
2. Wait longer. Raise the wait to 60 s and log every second, in case the verdict lands late.
3. Start the activity's lifecycle without showing it: call `onStart()` and `onResume()` on the probe
   activity through reflection (the `Activity` methods are protected), then call `f(null)` again.
   Do not call `makeVisible`, do not add the window to the WindowManager, and do not start any task.
4. If a token request is seen but no verdict is: capture at the next layer. Hook `zdc.cO`'s callers, or
   the code that builds the labels (`bujd` → `buiz`, see the findings doc), and log which of them run.
5. If there is no token request at all: the starter enqueues an event on `cgnz` (`xpe.a`). Find what
   consumes that queue, and whether it runs on the main looper, a coroutine scope tied to the lifecycle,
   or a worker. Check whether the event is dropped when no UI state is `STARTED`.

Acceptance: two `VERDICT` lines in one process run, with labels parsed by `PlayStoreVerdict.parseLabels`.

Record the result in `playstore-checker-di-chain.md`, section 6, whatever it is.

## Phase 1: an in-process reader for the anchors (replaces DexKit)

Goal: find every anchor from the app's own dex files, with no native code, so the feature works in the
injected process.

Design:

- Read `ApplicationInfo.sourceDir` (the app's `base.apk`) with `java.util.zip`. The process can read it,
  because it loads its own code from there. Parse each `classesN.dex` on demand. Read one dex at a time so
  memory stays bounded (the largest is about 11 MB).
- `DexFile` (in `core`, pure Kotlin): header, string/type/proto/field/method ids, class defs, class data,
  code items, and the instruction decoder. The decoder is the one in `tools/playstore-re/dexrd.py`, which
  agreed with `dexdump` on all 42,759 methods of this build.
- `PlayStoreAnchors` (in `core`): the discovery rules below. The rules are generic; only the key 922 choice
  is build-specific, and it is derived, not hardcoded.

Discovery rules:

1. Starter: the method that uses the string `testing-` and invokes `UUID.randomUUID()`, with one parameter.
   Must be unique. Expected `xpe.f(Laobx;)V`.
2. Verdict builder: the method that uses the string `"\n        Build fingerprint: "`, with three parameters.
   Must be unique. Expected `zdc.cO`.
3. Integrity constructor: among `new-instance` sites of the starter's class, pick the constructor whose
   parameter count is 4 and whose third parameter is the type stored into `c` by the starter's reads
   (`wnb`). Expected `xpe(aqjj, bdry, wnb, cejk)`.
4. Provider class and key: the class that contains a `new-instance` of the integrity constructor is the
   provider (`Lrmw`). Its switch key is the unique key (in its `packed-switch` tables) whose case reaches that
   construction and whose constructor has four parameters. Expected 922.
5. Constructor parameter types of the provider give the component types without any dex parsing: the
   provider's constructor is `(Lrnk, Lrmc, Lrmq, Lrme, int)`, and `Lrme`'s constructor is
   `(Lrnk, Lrmc, Lrmq, Lasou)`. `Lrmc`'s constructor is `(Lrnk, Lcela)`.
6. Root `Lrnk`: find an instance of the provider's first parameter type in the Application's object graph
   (fields, to a depth of about three). Expected path: application → `Lrii` state → `Lcekt` → `Lrnk`.
   Fallback: `Lrii.y()`, found as the Application superclass method with no parameters that returns Object.
7. Activity: the Finsky activity class (`SystemServicesActivity`) and its base (`Lbape`) with methods
   `onCreate`, `aN`. Keep these as named anchors. Expected names are in the findings doc.

Validation after building the object (this pins the variant by behaviour, not by name):

- `xpe.c` must be an instance of the constructor's third parameter type.
- `xpe.a` must implement an interface with a single method `e(Object)`.
- `xpe.f` must exist with one parameter whose type is the starter's parameter type.

If any check fails, return `Unavailable("unsupported Play Store build ...")` with the versionCode, and never
call `f`.

Tests:

- JVM unit tests in `core` with a synthetic dex. Build a small Java fixture with a dense `switch`, compile
  it with `d8`, and commit the resulting dex (a few kilobytes) under `core/src/test/resources`. The tests
  check the decoder, the switch table, and the anchor rules against the fixture.
- An opt-in test that reads a real `base.apk`, selected by a system property. It runs the anchor rules on
  this build and checks the expected names and key 922. It is skipped in CI.

Acceptance: the anchor rules find the expected names on this build from the device's own `base.apk`, with
no DexKit, and the unit tests pass.

## Phase 2: the headless activity, as production code

Goal: the activity state from experiment 5 (`attach` by parameter type, `setTheme`, `onCreate(null)`), in
`core`, with failure handling.

Tasks:

- `HeadlessActivity` (name to decide): owns one instance of the Finsky activity per process. Creates it on
  first use under a lock, not from the binder thread.
- Attach: fill each `attach` parameter by type as the probe does. Use the manifest `ActivityInfo` for the
  theme. Fall back to the application theme if the component is not declared.
- Create: `onCreate(null)`. Do not call `onStart`, `onResume`, `makeVisible`, or `setContentView` from our
  side (the activity's own `onCreate` may call `setContentView`; that is fine as long as nothing is added to
  the WindowManager). Phase 0 may require `onStart`/`onResume`; if so, add them with a comment explaining why.
- Lifetime: one per process. If it is missing or its state is wrong, recreate it once.

Acceptance:

- `dumpsys window` and `dumpsys activity` show no window or record for the Play Store's probe activity.
- `dumpsys meminfo com.android.vending` before and after creation: record the difference.
- A cold Play Store process (after `am force-stop`) reaches the verdict with no prior UI.

## Phase 3: wire the acquisition into the check

Goal: `PlayStoreIntegrityCheck.run()` acquires the integrity object itself when no cached one exists.

Tasks:

- `run()`: if there is no cached object, run the Phase 1 anchors, the Phase 2 activity, then
  `Lrmw(...).get()` for key 922. Validate, cache, then call `f(null)`. Keep `runPermit`.
- Keep the cache hook (2.3). It stays as a faster path after the first acquisition, and as a fallback if
  the anchors fail.
- Map results: `NOT_PRIMED` is no longer the normal cold result. Keep it only if acquisition is disabled
  by configuration. `UNAVAILABLE` carries the reason, and every `Failed` carries the exception summary.

Acceptance: a verdict from a cold process with no prior UI, and a second verdict from the same process
without re-acquiring.

## Phase 4: packaging and cleanup of the workaround

- Remove DexKit from `gradle/libs.versions.toml` and `core/build.gradle.kts`. Remove the `System.load`
  workaround and `MODULE_LIB_DIR`.
- Remove `DevExperiment.kt` and its call in `Bootstrap.kt`. Remove the probe dex from the Play Store's data
  directory on the phone.
- Keep the module packaging as it is. `attachNativeLibs` is no longer needed.
- Keep `SERVICE_VERSION` 107.

## Phase 5: on-device acceptance

Each test needs an unlocked phone. Ask the user to unlock it when a UI step is reached.

1. Install the final module and app. Do not install any development build.
2. Force-stop the Play Store. In PIB, open Integrity monitor, choose "Play Store built-in", and tap
   "Check now". Expect a `PLAY_STORE` entry with `OK` and device labels.
3. Repeat "Check now" without restarting the Play Store. Expect a second `OK`.
4. Force-stop the Play Store and repeat step 2. Expect the same result (cold path).
5. Check that no PIB or Play Store window is visible during the check (screenshot or `dumpsys window`).
6. Let the periodic worker run once (set a short interval for the test) and confirm the history entry.

## Phase 6: version handling and monitoring

- Record the Play Store versionCode and the anchor names in every `PLAY_STORE` history entry's details, so a
  broken build is visible.
- If the anchors or validation fail, the feature disables itself with a clear status. It never crashes and
  never calls `f`.
- Keep `tools/playstore-re/` current: when the Play Store changes, run the index and the anchor report on
  the new `base.apk`, and update the findings.

## Decisions needed from the user

0. The cold path cannot produce a verdict without the dev-options screen composed. Choose one:
   a. Keep the spike's behaviour as the product path: the user opens the screen once per Play Store process
      (a tap), and later checks run in the background. No visible launch from PIB. Phase 2 is then not needed.
   b. Allow a one-time visible launch of the screen, only when the monitor runs in a cold process. Brief
      flash, and the user sees the Play Store.
   c. Keep researching the composition: find out how much of the screen (and the window) the consumer
      needs, and whether an invisible window is possible. Open-ended.
   Recommendation: (a) now, with the Phases 1, 3 and 5 work kept for when a headless verdict is possible.
1. Approve the in-process reader (Phase 1) in place of DexKit. This is the recommendation: DexKit cannot load
   in the injected process on this device, and the reader needs no native code.
2. Approve running the Play Store's activity `onCreate` in the Play Store process (Phase 2). It inflates the
   Finsky UI in the background, so it has a memory and CPU cost. Measure it before agreeing to ship.
3. Confirm when to push code. Recommendation: push once Phase 5 passes, with the development hooks removed.

## Current device state (to restore)

- The phone runs the development payload (module `pib_zygisk` at version `145-playstore-checker-dirty+22`,
  with the temporary receiver and the `System.load` workaround) and the PIB app built from this working tree.
- The probe dex is in the Play Store's data directory: `/data/user/0/com.android.vending/files/pib-exp.dex`.
  Remove it.
- The original module is backed up in the session scratch directory (`device-backup/pib_zygisk_original`)
  if it is needed. Phase 4 replaces it with the final build.

## Working-tree state (uncommitted by design)

- `core/.../PlayStoreIntegrityCheck.kt`, `core/.../DevExperiment.kt`, `core/.../PIBLoggerService.kt`,
  `core/.../Bootstrap.kt`, the monitor and UI changes in `app/`, `common/` changes, the Gradle files.
- `docs/playstore-checker-TODO.md` and `docs/playstore-checker-plan.md` have local edits that describe the
  uncommitted code. They will be committed together with it.

## Decode body (new path, session 3)

Goal: make the official `decodeintegritytoken` request return HTTP 200, so the verdict arrives in `bujd`.

Known: the classic token works in-process; the request reaches the server; the body fields are listed in
`playstore-checker-di-chain.md`, section 10. The decode path needs only the root component graph, not the headless
activity, so Phase 2 is not needed for this route. Open:

1. Which field the server needs that the probe omits. Candidates: the `request.token.sid` from the classic callback
   (a field of `Lbuje` or `Lbuiv`, or a header), the `Lbuiw.c` meaning (2 is the main-path value, 0 and 1 return 400,
   3 returns 500), and the `Lwns.a` builder, which may be the variant the dev-options check actually uses.
2. Trace `Lwns.a` (which caller uses it, and where `Lyqr.b` comes from), and compare its body with `Llzg.b`.
3. Once HTTP 200 is returned: parse `bujd` (`b == 1`, `c.b` labels) and log the labels. That verifies the verdict
   without the composed screen.

Acceptance: a `Labels: [...]` string produced by the official decode, in a process with no visible UI.
