package icu.nullptr.playintegritybreak.core.playstore

import android.app.Application
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The Play Store's request senders, one per account, from the Play Store's own objects. The sender factory (a located class)
 * gives the sender for an account name.
 *
 * The Play Store makes its factory when it first sends a request to the server. In a process that has not, there is none
 * to find, and one is made through the dependency injection provider that makes it.
 */
internal object RequestSenders {
    /** How far from the application the objects are searched. */
    private const val SEARCH_DEPTH = 12

    /** The search is repeated this many times, this far apart, for a process that has just started. */
    private const val SEARCH_ATTEMPTS = 4
    private const val SEARCH_PAUSE_MS = 2_000L

    /** At most this many sets of constructor arguments are tried for each provider. */
    private const val MAX_ARGUMENT_SETS = 6

    /** The factory found last time, and the endpoint it was found for, so that later checks need not search again. */
    private class Found(val endpoint: DecodeEndpoint, val factory: WeakReference<Any>)

    @Volatile
    private var lastFound: Found? = null

    /**
     * Returns the lookup from account name to sender (null for none). A factory works if it gives a sender for one of
     * [accounts], the names that will be looked up: the Play Store may not sign in with the first. [providers] gives the
     * providers that make a factory, only asked for when needed. Throws [Unsupported] if there is no working factory.
     */
    fun find(
        app: Application,
        endpoint: DecodeEndpoint,
        accounts: List<String?>,
        providers: () -> List<FactoryProvider>,
    ): (String?) -> Any? {
        val factories = Factories(app.classLoader, endpoint, accounts)
        val remembered = lastFound?.takeIf { it.endpoint === endpoint }?.factory?.get()?.let { factories.working(it) }
        if (remembered != null) return { account -> factories.senderFor(remembered, account) }

        var found: Factories.Working? = null
        var factoryObjects = 0
        var visited = 0
        for (attempt in 1..SEARCH_ATTEMPTS) {
            factoryObjects = 0
            visited = 0
            ObjectGraph.reachableFrom(app, SEARCH_DEPTH) { candidate ->
                visited++
                if (factories.isFactory(candidate)) factoryObjects++
                found = factories.working(candidate)
                found != null
            }
            if (found == null && attempt == 1) found = makeFactory(app, providers(), factories)
            if (found != null || attempt == SEARCH_ATTEMPTS) break
            Thread.sleep(SEARCH_PAUSE_MS)
        }
        val working = found ?: throw Unsupported(
            "the Play Store has no request sender yet ($endpoint): $visited objects searched, $factoryObjects factory objects" +
                (factories.lastError?.let { ", last error $it" } ?: "") +
                (if (factories.notes.isEmpty()) "" else "; making one: " + factories.notes.joinToString("; ")),
        )
        lastFound = Found(endpoint, WeakReference(working.factory))
        return { account -> factories.senderFor(working, account) }
    }

    /**
     * Has the Play Store's dependency injection make a request factory: a provider class takes the component and an id,
     * and its `get()` makes the object bound to that id. Its other constructor arguments, and the component, are taken
     * from a provider of the same class that the Play Store has made (or, failing that, from a component object found by
     * the type of the constructor's first parameter).
     */
    private fun makeFactory(app: Application, providers: List<FactoryProvider>, factories: Factories): Factories.Working? {
        if (providers.isEmpty()) {
            factories.notes.add("no provider found in the dex")
            return null
        }
        val classLoader = app.classLoader
        val providerClasses = providers.mapNotNull { runCatching { classLoader.loadClass(it.className) }.getOrNull() }.toSet()
        val componentTypes = providers.mapNotNull { runCatching { classLoader.loadClass(it.componentType) }.getOrNull() }
            .filter { it != Any::class.java }.toSet()
        val madeProviders = HashMap<Class<*>, ArrayList<Any>>()
        val components = ArrayList<Any>()
        ObjectGraph.reachableFrom(app, SEARCH_DEPTH) { candidate ->
            if (candidate.javaClass in providerClasses) madeProviders.getOrPut(candidate.javaClass) { ArrayList() }.add(candidate)
            if (candidate.javaClass in componentTypes) components.add(candidate)
            false
        }
        for (provider in providers) {
            val providerClass = runCatching { classLoader.loadClass(provider.className) }.getOrNull()
            val parameterTypes = runCatching {
                provider.constructorParameters.map { if (it == "int") Int::class.javaPrimitiveType!! else classLoader.loadClass(it) }
            }.getOrNull()
            if (providerClass == null || parameterTypes == null) {
                factories.notes.add("$provider: class not loadable")
                continue
            }
            val constructor = runCatching {
                providerClass.getDeclaredConstructor(*parameterTypes.toTypedArray()).apply { isAccessible = true }
            }.getOrNull()
            if (constructor == null) {
                factories.notes.add("$provider: no constructor of the located shape")
                continue
            }
            // The interface the provider implements has one method with no arguments: get().
            val get = providerClass.interfaces.asSequence().flatMap { it.methods.asSequence() }
                .firstOrNull { it.parameterCount == 0 && it.returnType == Any::class.java }
                ?.apply { isAccessible = true }
            if (get == null) {
                factories.notes.add("$provider: no get() method")
                continue
            }
            val argumentSets = LinkedHashMap<List<Any?>, Array<Any?>>()
            for (existing in madeProviders[providerClass].orEmpty()) {
                argumentsLike(provider, providerClass, existing)?.let { argumentSets.putIfAbsent(identityKey(it), it) }
            }
            for (component in components.filter { parameterTypes[0].isInstance(it) }) {
                argumentsFor(provider, component)?.let { argumentSets.putIfAbsent(identityKey(it), it) }
            }
            if (argumentSets.isEmpty()) factories.notes.add("$provider: no component or provider object to make one from")
            for (arguments in argumentSets.values.take(MAX_ARGUMENT_SETS)) {
                val made = try {
                    get.invoke(constructor.newInstance(*arguments))
                } catch (e: InvocationTargetException) {
                    factories.notes.add("$provider: get() threw ${e.targetException}")
                    continue
                } catch (e: Exception) {
                    factories.notes.add("$provider: $e")
                    continue
                }
                val working = made?.let { factories.working(it) }
                if (working != null) {
                    Log.i(LOG_TAG, "made the request factory with provider $provider")
                    return working
                }
                factories.notes.add("$provider: made ${made?.javaClass?.name}, not a usable factory")
            }
        }
        return null
    }

    /** The constructor arguments of an existing provider of the same class, with the id of [provider]. */
    private fun argumentsLike(provider: FactoryProvider, providerClass: Class<*>, existing: Any): Array<Any?>? {
        val arguments = arrayOfNulls<Any>(provider.constructorParameters.size)
        for (i in arguments.indices) {
            if (i == provider.idParameter) {
                arguments[i] = provider.id
                continue
            }
            val field = provider.parameterFields[i]
            val value = field?.let {
                runCatching { providerClass.getDeclaredField(it).apply { isAccessible = true }.get(existing) }.getOrNull()
            }
            arguments[i] = value ?: if (provider.constructorParameters[i] == "int") 0 else if (i == 0) return null else null
        }
        return arguments
    }

    /** The constructor arguments for a provider made from a [component] alone: the id, and 0 for any other number. */
    private fun argumentsFor(provider: FactoryProvider, component: Any): Array<Any?>? {
        val arguments = arrayOfNulls<Any>(provider.constructorParameters.size)
        for (i in arguments.indices) {
            arguments[i] = when {
                i == 0 -> component
                i == provider.idParameter -> provider.id
                provider.constructorParameters[i] == "int" -> 0
                else -> return null
            }
        }
        return arguments
    }

    /** A key that tells argument sets apart: objects by identity, numbers by value. */
    private fun identityKey(arguments: Array<Any?>): List<Any?> =
        arguments.map { if (it == null || it is Int) it else IdentityKey(it) }

    private class IdentityKey(private val value: Any) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

    /** The located factory methods, and the test of whether an object is a factory that gives senders. */
    private class Factories(classLoader: ClassLoader, endpoint: DecodeEndpoint, private val probeAccounts: List<String?>) {
        class Working(val factory: Any, val method: Method)

        /** The last error a factory call threw, for the failure message. */
        var lastError: Throwable? = null
            private set

        /** What went wrong in making a factory, for the failure message. */
        val notes = ArrayList<String>()

        private val senderClass = classLoader.loadClass(endpoint.senderClass)
        private val owners = endpoint.factories.map { factory ->
            val owner = classLoader.loadClass(factory.className)
            owner to owner.declaredMethods.filter {
                it.name == factory.methodName && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }.onEach { it.isAccessible = true }
        }

        fun isFactory(candidate: Any): Boolean = owners.any { it.first.isInstance(candidate) }

        /**
         * The factory and method to use, if [candidate] is a factory that gives a sender. An object of the factory's class
         * can be unusable: R8 merges classes, so the Play Store has several, with different state.
         */
        fun working(candidate: Any): Working? {
            for ((owner, methods) in owners) {
                if (!owner.isInstance(candidate)) continue
                for (method in methods) {
                    if (probeAccounts.any { call(candidate, method, it) != null }) return Working(candidate, method)
                }
            }
            return null
        }

        fun senderFor(working: Working, account: String?): Any? = call(working.factory, working.method, account)

        private fun call(factory: Any, method: Method, account: String?): Any? {
            val result = try {
                method.invoke(factory, account)
            } catch (e: InvocationTargetException) {
                lastError = e.targetException
                return null
            } catch (e: Exception) {
                lastError = e
                return null
            }
            return result?.takeIf { senderClass.isInstance(it) }
        }
    }
}
