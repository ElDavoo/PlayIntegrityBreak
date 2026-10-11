# Integrity automation

The integrity monitor can act when the device verdict drops. Everything here is opt-in and lives in the monitor screen,
under "Automation". The logic is in `it.eldavo.pib.monitor`, and the phase rules in `common` (`IntegrityAutomationPhase`).

## Gate and phases

The automation does nothing until the device has passed `MEETS_STRONG_INTEGRITY` once. A check without a verdict changes
nothing.

- `UNARMED`: no STRONG verdict seen yet.
- `PASSING`: the device passed STRONG. A drop below STRONG moves to `DEGRADED`.
- `DEGRADED`: the chosen actions are on. A return to STRONG moves back to `PASSING` and turns them off.

Arming only happens on a check that runs with this code. Past history does not arm it.

## Actions

Each action has an opposite that runs when the verdict is back to STRONG.

- **Notify when the verdict drops / is back to STRONG**: one notification for each drop and each recovery, after arming.
- **Synthetic errors for all apps**: while degraded, intervention is turned on for every app but the Play Store, which the
  monitor needs. Each app's own setting is saved first, and put back exactly when the verdict recovers. The Play Store
  is pinned to "off" while this is on.
- **Install keyboxes from `davidepalma.it/pib`** (root only): the fallback below.
- **Synthetic errors if no keybox gives STRONG** (needs the keybox action): the same as the errors action, but only once the
  keybox run has given up. A run gives up when every keybox was tried without STRONG, or when it could not start (for
  example, no root).

## Keybox fallback

The app requires root to write the keybox, and it asks for root only when the keybox action is on.

1. Read the list of published keyboxes from the public nginx index at `https://www.davidepalma.it/pib/`. Only file names that
   are `keybox.xml` or a 32-hex-digit `.xml` name are used, so a listed name is never a path. `revoked/` is skipped.
2. Keep a copy of the keybox the device has, to put back if nothing passes.
3. Try `keybox.xml` first, then the others newest first, up to 20 attempts. Each download is capped at 256 KB and must be a
   keybox: an `AndroidAttestation` document with a `Keybox` that has a private key and a certificate chain.
4. Each keybox is installed with a root `cp` and an atomic `mv` over the file, then the Play Store check runs. The first one
   that gives STRONG ends the run. TrickyStore reads the new file without a restart.
5. If none passes, the copy is put back and the run counts as exhausted. A run that failed waits 6 hours before the next one.

The keybox file the module reads is `/data/adb/tricky_store/keybox.xml` when that directory exists (TEESimulator-RS keeps its
keybox there). Otherwise it is `/data/adb/modules/tricky_store/keybox.xml`.

## Telemetry

With telemetry on, each check that gets a verdict is sent at once (`IntegrityTelemetry`). It is not queued, and one attempt is
made. The event has `eventType: "integrity"`, the verdict label, and the MD5 of the keybox the device had, read only when root
was already granted, so that a check never asks for it. The backend stores both (`pib-backend`).

## What was tested on a device

Tested on a rooted phone (LineageOS, KernelSU, TEESimulator-RS) with the debug build:

- The monitor renders the automation card. The first real check arms the automation.
- A real drop, from a revoked keybox from the server's `revoked/` list, moves to `DEGRADED`, with the notification. The sample
  app then gets `NO_INTEGRITY` with intervention off for it.
- Synthetic errors on: the sample app gets `TOO_MANY_REQUESTS (-8)`. Off: the config goes back byte-identical to the state it
  had before, and the sample app gets the verdict again.
- The no-keybox option: with the keybox run unable to start (no root), the errors came on for all apps, and went off on
  recovery.
- Recovery, with the original keybox put back: the verdict is STRONG again, and the recovery notification is shown.

Not tested on the phone: the keybox download and install path. PIB had no root grant from KernelSU, so the run stops at the
root check. It is covered by the code review only.
