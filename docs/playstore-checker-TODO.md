# Play Store built-in checker — TODO

Tracking the work to turn the proven spike (see `playstore-checker-findings.md`) into a shipped
second checker source for the integrity monitor.

## Spike / feasibility (done)
- [x] Confirm the dev-options check decodes in-Play-Store (no third-party backend).
- [x] Capture the decoded verdict by hooking `zdc.cO`.
- [x] Find the real check starter: `xpe.f(aobx)` (fresh `"testing-"+UUID` nonce per call).
- [x] Prove a **headless** `xpe.f(null)` call does a **fresh** check (new token request + new testId).
- [x] Confirm capture works with **no dialog bound / Play Store backgrounded**. Proven: with the
      launcher in the foreground and the Play Store backgrounded, an `adb` broadcast triggered
      `xpe.f(null)` → new token requests → verdict captured at `zdc.cO` (new testId …05464516).

> **Decision:** cold `xpe` will be obtained via the **DI graph** (see `playstore-checker-plan.md`).
> Virtual-display seeding was tried and rejected (not robust; the secondary display is visible).
> Also found: `xpe` is a **merged class** — the integrity variant must be pinned by behaviour
> (`f` building `"testing-"+UUID`, field `c`=`wnb`), not by name; the 5-arg ctor at ~line 578 is a
> different variant.

## Productionisation
- [ ] **Obtain `xpe` cold — the main open question.** Confirmed on-device: in a fresh Play Store
      process where the dev-options screen was never opened, **no `xpe` instance exists** (the
      integrity object graph is built lazily when the screen opens), so `xpe.f` cannot be called.
      Once the screen has been opened once, the captured `xpe` works headlessly for the process
      lifetime (survives backgrounding). Options:
      - (A) Build/obtain `xpe` from the DI graph (reach the dev-options feature's Dagger
        provider / construct `xpe(Context, cejk, amtq, Account, Optional)`). Robust, no seed,
        but fragile RE + DexKit.
      - (B) One-time **seed** (user opens dev-options / runs one check when enabling the feature)
        + cache `xpe` for the process lifetime + keep the Play Store process warm (PIB already
        binds its integrity service). Re-seed only after a process death. Simple; needs a manual
        first run and degrades if the process dies.
      - (C) Open the dev-options screen **invisibly** to seed on demand (brief flash).
- [ ] **Headless capture.** If `zdc.cO` does not fire without a bound dialog, capture at the decode
      result instead (locate the `bujd`-producing / verdict-assembly step).
- [ ] **DexKit lookups.** Replace all hardcoded obfuscated names with DexKit signature searches
      (anchors in findings doc). Add DexKit to the `:core` / zygisk + xposed builds; `libdexkit.so`
      already runs fine on-device (ReVanced Xposed uses it here). Handle lookup-miss gracefully.
- [ ] **Define the verdict mapping.** Parse `zdc.cO` labels (or the decoded `bujd`) into PIB's
      `IntegrityCheck` model (device labels only — no app/account/environment from this source).
- [ ] **Trigger path.** Expose a "run Play Store built-in check" over `IPIBService`
      (new method, like `runIntegrityCheck`), called from the app; the hook calls `xpe.f(null)` and
      returns the captured verdict.
- [ ] **Monitor integration.** Add it as a selectable checker source in `IntegrityMonitor`
      (alongside the 1nikolas checker), with history + periodic + drop-notification reuse.
- [ ] **UI.** Let the user pick the checker source in `IntegrityMonitorFragment`.
- [ ] **Robustness.** Rate-limiting (`TOO_MANY_REQUESTS`/-8) handling; behaviour when the Play
      Store process is dead (wake it like the existing path); Play-Store-version drift (DexKit miss).

## Cleanup
- [ ] Remove the throwaway `CheckerDevOptionsSpike.kt` and its call in `Bootstrap.start`.
- [ ] Ensure no stale Gradle/Kotlin daemons (see memory `no-stale-gradle-daemons`).
