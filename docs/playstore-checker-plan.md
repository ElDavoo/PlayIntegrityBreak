# Play Store built-in checker — implementation plan

Follow-on from `playstore-checker-findings.md` (what was proven) and `playstore-checker-TODO.md`.
This is the plan to ship the Play Store's own dev-options "Play Integrity" check as a second,
**headless** checker source for PIB's integrity monitor.

## Decisions taken

- **Trigger** = call the check starter `xpe.f(null)` in-process. Proven: fresh token request +
  new `testId` per call, fully background (launcher foreground, no dialog).
- **Capture** = hook `zdc.cO`. Proven to fire even with no dialog bound.
- **Cold-`xpe` acquisition** = **DI graph** (obtain/construct the instance in-process).
  - Virtual-display seeding was considered and **rejected**: not robust, and a secondary display
    is still visible (overlay).
  - "Seed once + keep process warm" rejected: needs a visible/manual first run.

## Key complication found

`xpe` is a **horizontally-merged class**: many unrelated logical classes are compiled into the
single obfuscated name `xpe` (dozens of constructors with different signatures and field layouts).
- The integrity variant is the one whose method `f(aobx)` builds `"testing-" + UUID.randomUUID()`
  and whose field `c` is read as `wnb` in `f`.
- The 5-arg ctor `xpe(Context, cejk, amtq, Account, Optional)` at line ~578 sets `c = cejk` and
  builds an `oxk` into `d` — that is a **different** merged variant, **not** the `f`-capable one.
- Therefore every lookup must pin the *exact* integrity variant by behaviour, not by the class
  name — a precise DexKit job.

## Phases

### Phase 1 — Locate everything with DexKit (resilient, no hardcoded names)
- **Integrity `xpe.f`**: the method that references the string `"testing-"` and calls
  `java.util.UUID.randomUUID()`; its declaring class is the integrity `xpe`, that method is the
  starter. Record its single param type (`aobx`, a GeneratedMessageLite) — the request is passed
  as `null`.
- **`zdc.cO`**: the method referencing the literal `"\n        Build fingerprint: "` (and
  `"\n        TestId: "`). Its string arg[0] is the `Labels: [...]` verdict, arg[1] the testId.
- Add DexKit to the build (zygisk + xposed). `libdexkit.so` already runs on-device here.
  Cache resolved members; disable the feature gracefully on any lookup miss.

### Phase 2 — Obtain the integrity `xpe` cold (DI)
Try in order, first that works wins; all must target the *exact* integrity variant from Phase 1:
1. **Dagger factory / Provider.** An `@Inject`-constructed type has a generated `*_Factory`
   (`create(providers…)` + `get()`). Locate the factory whose `get()` returns the integrity `xpe`
   (DexKit: a class with a method returning that type; fields are `Provider`s). Obtain the factory
   from the root component (held as a field reachable from the `Application`'s singleton component)
   or reconstruct it from reachable singleton providers, then call `get()` → a fully-wired `xpe`.
2. **Direct construction (fallback).** Identify the ctor that sets exactly the fields `f` reads
   (`c = wnb`, plus the others), gather its deps — `Context` = app context, the integrity-service
   provider/`wnb`, the flags singleton `amtq`, `Account` via `AccountManager`, `Optional.empty()` —
   and reflectively `newInstance`.
3. **Opportunistic cache (last-resort).** Hook `f` to cache `xpe` whenever any check runs; keep the
   Play Store process warm (PIB already binds its integrity service). Only the first check after a
   process restart would be unavailable.

Validate the obtained `xpe` by calling `f(null)` and confirming a fresh token request + captured
verdict, exactly as the spike does.

### Phase 3 — Capture (headless), hardened
- Hook `zdc.cO`; read `labels` + `testId`.
- Hardening: if a future Play Store build does not call `zdc.cO` without a bound UI, capture one
  step earlier at the decoded verdict (`bujd` → `buiz` labels assembly). Keep `zdc.cO` as primary.

### Phase 4 — Trigger + result plumbing (in the Play Store hook)
- Add an `IPIBService` method (e.g. `runPlayStoreIntegrityCheck(callback)`), like the existing
  `runIntegrityCheck`, callable from the PIB app.
- On call: ensure `xpe` (Phase 2) → set an in-flight `CompletableDeferred` → call `xpe.f(null)` →
  the `zdc.cO` hook completes the deferred with the verdict (correlate by "a capture that lands
  after our trigger"; guard with a timeout + a single in-flight check via a mutex) → return it.
- Dead Play Store: wake it via the existing bind-to-integrity-service path before triggering.

### Phase 5 — Monitor integration
- Add "Play Store built-in" as a selectable **checker source** in `IntegrityMonitor` alongside the
  1nikolas checker. Map `Labels: […]` → `IntegrityCheck` (device levels only: BASIC/DEVICE/STRONG;
  no app/account/environment from this source). Reuse history, periodic worker, drop-notification.
- `IntegrityMonitorFragment`: a source selector; show which source each history entry came from.

### Phase 6 — Robustness & cleanup
- Handle `TOO_MANY_REQUESTS` (-8) as a transient, non-drop status (as today).
- DexKit miss / Play Store version drift → feature auto-disables with a clear status, never crashes.
- Remove `CheckerDevOptionsSpike.kt` and its `Bootstrap.start` call.
- No stale Gradle/Kotlin daemons when done.

## Risks / open questions
- **DI acquisition is the fragile part.** Merged class + Dagger obfuscation means Phase 2 needs
  robust DexKit signatures and per-Play-Store-version re-validation. If Phase 2.1/2.2 prove too
  brittle, fall back to 2.3 (opportunistic cache + warm process) and surface the limitation.
- **Verdict correlation** under concurrent/overlapping checks — serialize with a mutex + timeout.
- This source yields **less** than the 1nikolas checker (device labels only); it is redundancy /
  independence from the third-party app + backend, not more data.
