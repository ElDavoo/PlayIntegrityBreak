package icu.nullptr.playintegritybreak.core.playstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The locator on a small dex built from `tools/playstore-re/locator-fixture`, which has the shapes of the Play Store's
 * classes under readable names, and on real Play Store APKs when they are given.
 */
class DecodeEndpointLocatorTest {
    private fun fixture(): () -> Sequence<Dex> {
        val bytes = javaClass.getResourceAsStream("/playstore-locator-fixture.dex")!!.use { it.readBytes() }
        return { sequenceOf(Dex(bytes)) }
    }

    @Test
    fun findsTheSenderMethodThatReadsTheEndpointUri() {
        val endpoint = DecodeEndpointLocator.locate(dexes = fixture())
        assertEquals("fx.Sender", endpoint.senderClass)
        assertEquals("decode", endpoint.senderMethod)
        assertEquals("fx.Body", endpoint.bodyType)
        assertEquals(listOf("fx.Ok", "fx.Err"), endpoint.listenerTypes)
    }

    @Test
    fun findsTheFactoryThatTakesAnAccountNameAndReturnsTheSender() {
        val endpoint = DecodeEndpointLocator.locate(dexes = fixture())
        assertEquals(listOf("fx.Factory.senderFor"), endpoint.factories.map { "${it.className}.${it.methodName}" })
    }

    @Test
    fun anotherEndpointGivesItsOwnSender() {
        val endpoint = DecodeEndpointLocator.locate("fixtureunused", fixture())
        assertEquals("other", endpoint.senderMethod)
        assertEquals("fx.OtherBody", endpoint.bodyType)
    }

    @Test
    fun usesTheInterfaceTheSenderImplementsWhenNothingReturnsTheSenderItself() {
        val endpoint = DecodeEndpointLocator.locate("fixtureviainterface", fixture())
        assertEquals("fx.ApiImpl", endpoint.senderClass)
        assertEquals(listOf("fx.Factory.apiFor"), endpoint.factories.map { "${it.className}.${it.methodName}" })
    }

    @Test
    fun refusesWhenTheEndpointPathIsNotInTheCode() {
        try {
            DecodeEndpointLocator.locate("notinthisbuild", fixture())
            fail("expected EndpointNotFound")
        } catch (e: EndpointNotFound) {
            assertTrue(e.message!!, "notinthisbuild" in e.message!!)
        }
    }

    @Test
    fun refusesWhenTwoMethodsReadTheEndpointUri() {
        try {
            DecodeEndpointLocator.locate("fixtureambiguous", fixture())
            fail("expected EndpointNotFound")
        } catch (e: EndpointNotFound) {
            assertTrue(e.message!!, "2 sender methods" in e.message!!)
        }
    }

    // Real Play Store APKs are not in the repository: give the paths of base APKs in PIB_PLAYSTORE_APKS (separated by ':').

    private val apks: List<String> = System.getenv("PIB_PLAYSTORE_APKS")?.split(':')?.filter { it.isNotEmpty() }.orEmpty()

    @Test
    fun locatesTheEndpointInEveryGivenBuild() {
        assumeTrue("PIB_PLAYSTORE_APKS is not set", apks.isNotEmpty())
        val failures = ArrayList<String>()
        for (apk in apks) {
            assertTrue("$apk is missing", File(apk).exists())
            val started = System.nanoTime()
            try {
                val endpoint = DecodeEndpointLocator.locate { ApkDex.dexes(listOf(apk)) }
                println("LOCATED ${File(apk).name} in ${(System.nanoTime() - started) / 1_000_000} ms: $endpoint")
                assertEquals(2, endpoint.listenerTypes.size)
            } catch (e: Exception) {
                println("NOT LOCATED ${File(apk).name}: $e")
                failures.add("${File(apk).name}: $e")
            }
        }
        assertTrue("builds without a located endpoint:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The instruction walk must end exactly at the end of every method's code: that is how the opcode table is checked. */
    @Test
    fun instructionLengthsAgreeWithTheMethodSizeInEveryGivenBuild() {
        assumeTrue("PIB_PLAYSTORE_APKS is not set", apks.isNotEmpty())
        val failures = ArrayList<String>()
        for (apk in apks) {
            var methods = 0
            var wrong = 0
            for (dex in ApkDex.dexes(listOf(apk))) {
                dex.forEachMethodWithCode { _, code ->
                    methods++
                    val size = dex.codeUnitCount(code)
                    var pc = 0
                    while (pc < size) {
                        val unit = dex.codeUnit(code, pc)
                        val payload = dex.payloadUnits(code, pc, unit)
                        pc += if (payload > 0) payload else Dex.OPCODE_UNITS[unit and 0xff].toInt()
                    }
                    if (pc != size) wrong++
                    true
                }
            }
            println("WALKED ${File(apk).name}: $methods methods, $wrong with a wrong end")
            if (wrong != 0) failures.add("${File(apk).name}: $wrong of $methods methods")
        }
        assertTrue("methods whose instructions do not end at the code size:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
