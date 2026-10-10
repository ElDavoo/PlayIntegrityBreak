package icu.nullptr.playintegritybreak.core.playstore

import java.util.zip.ZipFile

/** The dex files inside APKs. */
internal object ApkDex {
    private val dexEntry = Regex("""classes\d*\.dex""")

    /**
     * The dex files of [apkPaths] one at a time, in order. Each is read when the sequence reaches it, so only one dex
     * (up to about 11 MB for the Play Store) is held at a time. No APK is open between two dex files: a consumer that
     * stops early, or throws, leaves nothing to close.
     */
    fun dexes(apkPaths: List<String>): Sequence<Dex> = sequence {
        for (path in apkPaths) {
            val names = ZipFile(path).use { zip -> zip.entries().asSequence().map { it.name }.filter { dexEntry.matches(it) }.sorted().toList() }
            for (name in names) {
                val bytes = ZipFile(path).use { zip -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
                yield(Dex(bytes))
            }
        }
    }
}
