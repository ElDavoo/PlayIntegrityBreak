package icu.nullptr.playintegritybreak.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboxPoolTest {

    /** The nginx JSON index of the keybox directory, as the server returns it. */
    private val listing = """
        [
        { "name":"revoked", "type":"directory", "mtime":"Tue, 29 Sep 2026 18:47:05 GMT" },
        { "name":"1d515a77860e0cf97b0dfb30640e01c0.xml", "type":"file", "mtime":"Sat, 03 Oct 2026 20:14:12 GMT", "size":12842 },
        { "name":"aaf89ab74a8a19548a616e2b367db889.xml", "type":"file", "mtime":"Sun, 04 Oct 2026 00:53:45 GMT", "size":12834 },
        { "name":"deec2d5559b4e0496ff2581adedfb79f.xml", "type":"file", "mtime":"Thu, 03 Sep 2026 04:02:02 GMT", "size":12834 },
        { "name":"keybox.xml", "type":"file", "mtime":"Sun, 04 Oct 2026 20:12:21 GMT", "size":12834 }
        ]
    """.trimIndent()

    @Test
    fun putsTheBestKeyboxFirstThenTheOthersNewestFirst() {
        assertEquals(
            listOf(
                "keybox.xml",
                "aaf89ab74a8a19548a616e2b367db889.xml",
                "1d515a77860e0cf97b0dfb30640e01c0.xml",
                "deec2d5559b4e0496ff2581adedfb79f.xml",
            ),
            KeyboxPool.parseListing(listing),
        )
    }

    @Test
    fun skipsDirectoriesAndNamesThatAreNotKeyboxes() {
        val hostile = """
            [
            { "name":"../../etc/passwd", "type":"file", "mtime":"Sun, 04 Oct 2026 20:12:21 GMT" },
            { "name":"notes.txt", "type":"file", "mtime":"Sun, 04 Oct 2026 20:12:21 GMT" },
            { "name":"0123456789abcdef0123456789abcdef.xml", "type":"file", "mtime":"Sun, 04 Oct 2026 20:12:21 GMT" },
            { "name":"ffffffffffffffffffffffffffffffff.xml", "type":"directory", "mtime":"Sun, 04 Oct 2026 20:12:21 GMT" }
            ]
        """.trimIndent()
        assertEquals(listOf("0123456789abcdef0123456789abcdef.xml"), KeyboxPool.parseListing(hostile))
    }

    @Test
    fun aBrokenListingIsToldFromAnEmptyOne() {
        assertNull(KeyboxPool.parseListing("<html>maintenance</html>"))
        assertEquals(emptyList<String>(), KeyboxPool.parseListing("[]"))
    }
}
