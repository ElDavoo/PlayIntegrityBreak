# Play Store built-in "Play Integrity" checker — feasibility findings

Investigation into adding the Play Store's **own** dev-options "Play Integrity" check
(`Opzioni sviluppatore → Play Integrity`, inside `com.android.vending`) as a second checker
source for PIB's integrity monitor, headlessly like the 1nikolas checker.

Status: **feasibility proven.** Headless, fully-background fresh checks work; remaining work is
productionisation (DexKit lookups, obtaining the receiver cold, capture point, monitor wiring).

All obfuscated names below are for the Play Store build tested on-device
(`com.android.vending`, Xiaomi apollo / LineageOS, captured 2026-10-06). They **will change**
between Play Store versions — the real feature must locate them with DexKit, not hardcode them.

## How the dev-options check works

1. A `Verifica l'integrità` button dispatches through a reactive DI "machine"
   (`xkz`/`xkw`/`bdsf`, repackaged Kotlin coroutines) and ultimately calls the check starter.
2. **Check starter:** `xpe.f(aobx)` → `void`. On **each** call it:
   - generates a fresh session: `"testing-" + UUID.randomUUID()`, used as the **nonce**;
   - requests a **classic** integrity token via the Play Store's own `IntegrityService`
     (the request carries `package.name = "com.android.vending"`), and
   - sends the token to an internal **`DecodeIntegrityToken`** RPC (Volley) that Google decodes
     server-side against the Play Store's own project — so **no third-party backend** is needed.
   The dev-options UI passes `aobx = null` (a default request).
3. **Verdict assembly:** the decoded result (`bujd` proto → `buiz` label enum) is formatted for
   the dialog by `zdc.cO(String labels, String testId, wmu deviceInfo) → String`, where `labels`
   is e.g. `"Labels: [MEETS_BASIC_INTEGRITY, MEETS_DEVICE_INTEGRITY, MEETS_STRONG_INTEGRITY]"`.

## The working headless trigger

- **Trigger:** call `xpe.f(null)` on the integrity `xpe` instance, on any thread.
- **Proven:** a replay of `xpe.f(null)` with the Play Store seeded once, 15s after a real tap,
  produced a **new classic token request** (new nonce) and a **different `testId`** — i.e. a
  genuinely fresh check, not a cached result. (Seed `testId …f036cd5b` → replay `…5ad18175`.)
- **Capture:** `zdc.cO` receives the decoded `labels` + `testId`. Confirmed to fire **even with no
  dialog bound and the Play Store backgrounded** (launcher in foreground) — a fully background
  check still produces a fresh, captured verdict.
- **Cold constraint:** in a fresh Play Store process where the dev-options screen was never opened,
  **no `xpe` instance exists** (the integrity object graph is lazily built on screen open), so the
  check cannot be triggered until `xpe` is obtained — see open item #1.

### Why earlier replay attempts failed (caching layers, outermost → innermost)

1. **Result cache** in the machine's state map (`xkz.c` arg[2]) — returns the last result.
2. **Machine cache** in `xkz`'s field `c` map — memoises the decoded verdict object.
3. **Session/token** — tied to the `"testing-"+UUID` nonce created inside `xpe.f`.

Replaying at the machine layer (`xkz.c`) hit caches 1/2 and returned a stale verdict with the
**same** `testId` and **no** new token request. Triggering at `xpe.f` sidesteps all three because
it mints a new session every call.

## Obfuscated-name map (THIS build — DexKit anchors for the real feature)

| Role | Name (this build) | DexKit anchor idea |
|------|-------------------|--------------------|
| Check starter | `xpe.f(aobx)` → void | method generating `"testing-"` + `UUID.randomUUID()`, string `"testing-"` |
| Request proto | `aobx` (GeneratedMessageLite) | param type of the starter |
| Dialog/verdict builder | `zdc.cO(String,String,wmu)` | method containing `"\n        Build fingerprint: "` |
| Verdict labels enum | `buiz` | enum with `"MEETS_STRONG_INTEGRITY"` |
| Decoded response proto | `bujd` | — |
| Device info holder | `wmu` {fingerprint,brand,device,model} | — |
| UI machine runner | `xkz.c(action, event, map, cont)` | — (not needed if we use the starter) |

## Open items (productionisation) — see TODO.md

1. Obtain the `xpe` instance **cold** (no UI seed) — DI graph, or cache at process start.
2. Confirm capture works with **no dialog bound** (Play Store backgrounded); if `zdc.cO` doesn't
   fire headlessly, capture one step earlier (the decode result / `bujd`).
3. Locate all anchors with **DexKit** instead of hardcoded names.
4. Wire into the monitor as a second checker source, triggered over the existing PIB↔Play Store
   binder (`IPIBService`), mapped to `IntegrityCheck` like the 1nikolas path.

## Throwaway spike

`core/.../core/CheckerDevOptionsSpike.kt` (installed from `Bootstrap.start`) is the throwaway
harness used to establish the above (hardcoded names, logs, `adb shell am broadcast
icu.nullptr.pib.REPLAY` to fire a headless check). **Delete it** when the real feature lands.
