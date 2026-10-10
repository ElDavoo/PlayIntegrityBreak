package icu.nullptr.playintegritybreak.core.playstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FactoryProviderLocatorTest {
    private fun fixture(): () -> Sequence<Dex> {
        val bytes = javaClass.getResourceAsStream("/playstore-locator-fixture.dex")!!.use { it.readBytes() }
        return { sequenceOf(Dex(bytes)) }
    }

    @Test
    fun findsTheProvidersCasesThatMakeTheRealFactory() {
        val endpoint = DecodeEndpointLocator.locate("fixturecold", fixture())
        assertEquals("fx.ColdSender", endpoint.senderClass)
        val providers = FactoryProviderLocator.locate(endpoint.factories, fixture())
        // Provider1 builds it in a case of sparse keys, Provider2 through a static helper in a case of dense keys,
        // Provider3 has a constructor with a component of type Object and a second int.
        // Provider1's case 1149 makes a constructor that is not the real one.
        assertEquals(
            setOf("fx.Provider1#1148(fx.Component)", "fx.Provider2#9(fx.Component)", "fx.Provider3#5(java.lang.Object)"),
            providers.map { it.toString() }.toSet(),
        )
        // Provider3's constructor is (Object, int id, int variant): the id is the second parameter, stored in field "id".
        val merged = providers.single { it.className == "fx.Provider3" }
        assertEquals(listOf("java.lang.Object", "int", "int"), merged.constructorParameters)
        assertEquals(1, merged.idParameter)
        assertEquals(listOf("component", "id", "variant"), merged.parameterFields)
    }

    @Test
    fun findsNothingForAFactoryThatNothingConstructs() {
        val endpoint = DecodeEndpointLocator.locate(dexes = fixture())
        // fx.Factory is made by no provider.
        assertEquals(emptyList<FactoryProvider>(), FactoryProviderLocator.locate(endpoint.factories, fixture()))
    }

    private val apks: List<String> = System.getenv("PIB_PLAYSTORE_APKS")?.split(':')?.filter { it.isNotEmpty() }.orEmpty()

    @Test
    fun findsProvidersInEveryGivenBuild() {
        assumeTrue("PIB_PLAYSTORE_APKS is not set", apks.isNotEmpty())
        val failures = ArrayList<String>()
        for (apk in apks) {
            assertTrue("$apk is missing", File(apk).exists())
            try {
                val endpoint = DecodeEndpointLocator.locate { ApkDex.dexes(listOf(apk)) }
                val started = System.nanoTime()
                val providers = FactoryProviderLocator.locate(endpoint.factories) { ApkDex.dexes(listOf(apk)) }
                println("PROVIDERS ${File(apk).name} in ${(System.nanoTime() - started) / 1_000_000} ms: $providers")
                if (providers.isEmpty()) failures.add("${File(apk).name}: no provider found")
            } catch (e: Exception) {
                println("NO PROVIDERS ${File(apk).name}: $e")
                failures.add("${File(apk).name}: $e")
            }
        }
        assertTrue("builds without a provider:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
