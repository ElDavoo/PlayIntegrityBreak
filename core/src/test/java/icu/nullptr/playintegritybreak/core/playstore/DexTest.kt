package icu.nullptr.playintegritybreak.core.playstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexTest {
    private val dex = Dex(javaClass.getResourceAsStream("/playstore-locator-fixture.dex")!!.use { it.readBytes() })

    @Test
    fun findsStringsInTheSortedTable() {
        val index = dex.findString("decodeintegritytoken")
        assertTrue(index >= 0)
        assertEquals("decodeintegritytoken", dex.string(index))
        assertEquals(-1, dex.findString("decodeintegritytokeN"))
        assertEquals(-1, dex.findString(""))
    }

    @Test
    fun resolvesTypesFieldsAndMethods() {
        assertTrue(dex.findType("Lfx/Sender;") >= 0)
        assertEquals(-1, dex.findType("Lfx/Missing;"))
        val field = dex.findField("Lfx/Endpoints;", "DECODE", "Landroid/net/Uri;")
        assertTrue(field >= 0)
        assertEquals("Lfx/Endpoints;", dex.fieldClass(field))
        assertEquals("DECODE", dex.fieldName(field))
        assertEquals(-1, dex.findField("Lfx/Endpoints;", "DECODE", "Ljava/lang/String;"))
    }

    @Test
    fun readsMethodSignatures() {
        val returning = dex.methodsReturning("Lfx/Sender;")
        assertEquals(listOf("senderFor"), returning.map { dex.methodName(it) }.distinct())
        assertEquals(listOf("Ljava/lang/String;"), dex.methodParameterTypes(returning.first()))
        assertEquals("Lfx/Sender;", dex.methodReturnType(returning.first()))
    }

    @Test
    fun readsDeclaredSupertypes() {
        assertEquals(listOf("Ljava/lang/Object;", "Lfx/Api;"), dex.supertypes("Lfx/ApiImpl;"))
        assertEquals(emptyList<String>(), dex.supertypes("Lfx/NotHere;"))
    }

    @Test
    fun rejectsAFileThatIsNotADex() {
        try {
            Dex(ByteArray(200))
            throw AssertionError("expected an exception")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
