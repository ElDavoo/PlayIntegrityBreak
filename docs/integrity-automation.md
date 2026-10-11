# Integrity automation

The integrity monitor can act when the device verdict drops. Everything here is opt-in and lives in the monitor screen,
under "Automation". The logic is in `it.eldavo.pib.monitor`. The decisions are in `common` (`IntegrityAutomationPhase`,
`IntegrityAutomationPlan`, `InterventionSnapshot`), where they are unit tested.

## Gate and phases

The automation does nothing until the device has passed `MEETS_STRONG_INTEGRITY` once, and its switches cannot be changed
until then. A check without a verdict changes nothing. The periodic check, when on, runs every 3 hours.

- `UNARMED`: no STRONG verdict seen yet.
- `PASSING`: the device passed STRONG. A drop below STRONG moves to `DEGRADED`.
- `DEGRADED`: the chosen actions are on. A return to STRONG moves back to `PASSING` and turns them off.

Arming only happens on a check that runs with this code. Past history does not arm it. A drop is told from the last verdict
the automation saw, which it keeps in its preferences, so clearing the history does not hide the next drop.

## Actions

Each action has an opposite that runs when the verdict is back to STRONG.

- **Notify when the verdict drops / is back to STRONG**: one notification for each drop and each recovery, after arming.
- **Synthetic errors for all apps**: while degraded, intervention and synthetic errors are turned on for every app but the
  Play Store, which the monitor needs. Each app's own settings are saved first, and put back when the verdict recovers,
  except where the user changed them in the meantime: that change is kept. The Play Store is pinned to "off" while this is
  on. A config exported meanwhile has the user's own settings, and a config imported meanwhile is forced again.
- **Try keyboxes from `davidepalma.it/pib`** (root only): the fallback below.
- **Synthetic errors if no keybox works** (needs the keybox action): the same as the errors action, but only once the
  keybox run has given up. A run gives up when every keybox was tried without STRONG, or when it could not start (for
  example, no root).

## Keybox fallback

The app requires root to write the keybox, and it asks for root only when the keybox action is on.

1. Read the list of published keyboxes from the public nginx index at `https://www.davidepalma.it/pib/`. Only file names that
   are `keybox.xml` or a 32-hex-digit `.xml` name are used, so a listed name is never a path. `revoked/` is skipped.
2. Keep a copy of the keybox the device has, to put back if nothing passes.
3. Try `keybox.xml` first, then the others newest first, up to 20 installed keyboxes. Each download is capped at 256 KB
   and must be a keybox: an `AndroidAttestation` document with a `Keybox` that has a private key and a certificate chain.
   Each keybox is tried once, by its MD5 (`keybox.xml` is a copy of one of the others), and the one the device has is skipped.
4. Each keybox is installed with a root `cp` and an atomic `mv` over the file, then the Play Store check runs. The first one
   that gives STRONG ends the run. TrickyStore reads the new file without a restart.
5. If none passes, the copy is put back and the run counts as exhausted, as does a run that could not start.
6. A check that gives no verdict is made once more. When the second one has no verdict either, the run ends and the copy is
   put back, but the run does not count as exhausted: the no-keybox errors stay off, since no keybox was seen to fail.
7. A run that ended either way waits 6 hours before the next one. A return to STRONG ends that wait, and so does turning the
   keybox action on, which starts a run at once. Turning the action off during a run stops it and puts the copy back.

The copy is kept in the app's no-backup storage, so a keybox's private key never goes to Auto Backup.

The keybox file the module reads is `/data/adb/tricky_store/keybox.xml` when that directory exists (TEESimulator-RS keeps its
keybox there). Otherwise it is `/data/adb/modules/tricky_store/keybox.xml`.

PIB remembers the last answer it got for root in its preferences. The telemetry reads the keybox ID with root only where that
cannot bring up a root prompt the user did not ask for: in a process whose root shell is open already, or with the keybox
action on and root granted the last time PIB asked.

## Telemetry

With telemetry on, each check that gets a verdict is sent at once (`IntegrityTelemetry`). During a keybox run every check is
sent, one per keybox tried (two when the first gave no verdict), and a check without a verdict is sent with no `verdict` and
`success: false`. It is not queued, and one attempt is made. The event has `eventType: "integrity"`, the verdict label, and
the MD5 of the keybox the device had, read as described above, so that a check never asks for root. The backend stores both
(`pib-backend`, branch `integrity-events`).
