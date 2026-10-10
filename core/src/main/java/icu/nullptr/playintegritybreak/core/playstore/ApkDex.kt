package icu.nullptr.playintegritybreak.core.playstore

import java.util.zip.ZipFile

/** The dex files inside APKs. */
internal object ApkDex {
    private val dexEntry = Regex("""classes\d*\.dex""")

    /**
     * The dex files of [apkPaths] one at a time, in order. Each is read when the sequence reaches it, so only one dex
     * (up to about 11 MB for the Play Store) is held at a time.
     */
    fun dexes(apkPaths: List<String>): Sequence<Dex> = sequence {
        for (path in apkPaths) {
            ZipFile(path).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.filter { dexEntry.matches(it) }.sorted().toList()
                for (name in names) yield(Dex(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }))
            }
        }
    }
}
