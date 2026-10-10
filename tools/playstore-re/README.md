# Play Store reverse-engineering tools

Scripts used to map the Play Store's obfuscated dependency injection graph for the built-in integrity
check. They are for analysing a Play Store build you have on your own device. Nothing here changes the app.
The findings they support are in `docs/playstore-checker-di-chain.md`.

## Get the input

The tools read the Play Store's `base.apk` and its `classes*.dex` files. Pull them from a phone with the
app installed:

```sh
adb shell pm path com.android.vending       # lists base.apk and the splits
adb pull <path-to-base.apk> base.apk
python3 -c "import zipfile,sys; z=zipfile.ZipFile('base.apk'); [z.extract(n,'dex') for n in z.namelist() if n.endswith('.dex')]"
```

The dex files are not committed (the APK is about 60 MB).

## Tools

- `dexrd.py`: a DEX reader and Dalvik instruction decoder. Decodes all opcodes and switch payloads, and
  resolves references. Validated against `dexdump` on every method of `classes3.dex` in the build the
  findings describe: 42,759 methods, every method's instructions end exactly at its size.
- `dexset.py`: indexes all `classes*.dex` of an app. `DexSet.from_dir('dex')`, then `ds.code(desc, name)`
  returns the method's registers and instructions, and `listing(...)` prints them with resolved names.
- `buildindex.py <dex-dir> <out.pkl>`: indexes every allocation, invoke and field access in the app. The
  index is large (about 90 MB for the Play Store) and takes about a minute. Queries are then instant.
- `analyze_switch.py <classesN.dex>`: for `Lrmw.o()`, lists the switch keys whose case reaches a
  `new-instance` of the integrity `xpe`. Shows key 922 for this build.

Quick check that the decoder works on your copy:

```sh
python3 - <<'EOF'
import struct
from dexrd import Dex, decode
d = Dex('dex/classes3.dex')
bad = 0
for desc in d.class_by_name:
    for name, proto, access, off in d.class_methods(desc):
        if not off:
            continue
        n = struct.unpack_from('<I', d.data, off + 12)[0]
        insns = list(struct.unpack_from('<' + 'H' * n, d.data, off + 16))
        end = 0
        for pc, op, nm, fmt, ln in decode(insns):
            end = pc + ln
        bad += end != n
print('bad methods:', bad)
EOF
```

## Locator fixture and tests (`locator-fixture/`)

`core` finds the decode request in the Play Store's dex while it runs (see findings, section 13). Its tests use a tiny
dex built from the Java sources in `locator-fixture/fx`, which have the Play Store's shapes under readable names:

```sh
ANDROID_HOME=... locator-fixture/build.sh   # needs javac and d8; rewrites core/src/test/resources/playstore-locator-fixture.dex
```

To run the locator on real builds, give base APKs (for example the installed one and the system image's
`/product/priv-app/Phonesky/Phonesky.apk`):

```sh
PIB_PLAYSTORE_APKS=/path/new/base.apk:/path/old/base.apk ./gradlew :core:testDebugUnitTest
```

The test prints what it located and checks the opcode table against every method of each APK.

## Experiment: the probe activity (`experiment/Exp.java`)

`Exp.java` builds the integrity `xpe` from the Play Store's Dagger graph inside the Play Store process: it
attaches a Finsky activity without showing it, creates it, builds the activity-scoped components, takes key
922 from the provider, and calls `f(null)`. It also hooks `zdc.cO` through PIB's `Backend` to log verdicts.

It needs:

- The PIB payload loaded into the Play Store process, with a receiver that loads and runs `pibexp.Exp`.
  That receiver is a temporary development hook, not part of the committed code.
- Android `android.jar` (API 37) and PIB's compiled core classes on the compile classpath.
- `d8` from the Android build-tools.

Build (paths depend on your SDK and JDK):

```sh
javac --release 8 -cp "$ANDROID_JAR:pib-core.jar" -d out experiment/Exp.java
d8 --lib "$ANDROID_JAR" --classpath pib-core.jar --min-api 29 --output dexout out/pibexp/*.class
```

Run (the dex must be read-only, owned by the Play Store's user):

```sh
adb push dexout/classes.dex /data/local/tmp/pib-exp.dex
adb shell 'cp /data/local/tmp/pib-exp.dex /data/user/0/com.android.vending/files/pib-exp.dex && chmod 400 /data/user/0/com.android.vending/files/pib-exp.dex'
adb shell am broadcast -a icu.nullptr.pib.EXP
adb logcat -d | grep PIB-EXP
```

The obfuscated names in `Exp.java` are for Play Store 52.9.21-34 (versionCode 85292140). They change with
Play Store updates (the shipped check no longer names them). Re-derive them with `buildindex.py` and `dexset.py` before reusing the probe.

## Experiment files (current)

`experiment/Token.java` requests the classic token from inside the Play Store process (works, see the findings,
section 10). `experiment/Decode.java` sends the official `decodeintegritytoken` request through Finsky's own request
object with a body built the way the Play Store builds it. It still gets HTTP 400, see the findings, section 10.
`experiment/Exp.java` builds the integrity object and runs `Token` then `Decode`.
