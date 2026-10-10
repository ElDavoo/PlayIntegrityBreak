package icu.nullptr.playintegritybreak.core.playstore

/**
 * Where the Play Store's request for fdfe/decodeintegritytoken is in a given build of the Play Store, found from the
 * Play Store's own code instead of from its obfuscated names.
 *
 * @property senderClass the class of the request sender (the Play Store's API object for one account), as a binary name
 * @property senderMethod the sender's method that sends the request: (body, success listener, error listener)
 * @property bodyType the request body message type
 * @property listenerTypes the two listener types of [senderMethod], in parameter order
 * @property factories the methods that give a sender for an account name: they take a String and return the sender
 */
internal class DecodeEndpoint(
    val senderClass: String,
    val senderMethod: String,
    val bodyType: String,
    val listenerTypes: List<String>,
    val factories: List<Factory>,
) {
    class Factory(val className: String, val methodName: String)

    override fun toString(): String = "$senderClass.$senderMethod($bodyType, ${listenerTypes.joinToString()}) " +
        "from " + factories.joinToString { "${it.className}.${it.methodName}" }
}

/** The endpoint could not be found in this build; the message says which step failed. */
internal class EndpointNotFound(message: String) : Unsupported(message)

/**
 * Finds the decode request in the Play Store's dex files by what does not change between builds: the endpoint's path
 * string, and the way the Play Store uses it.
 *
 * 1. The path "decodeintegritytoken" is stored in a static `Uri` field, in a class that holds the Play Store's endpoint
 *    URIs (a `const-string`, `Uri.parse` and `sput-object` in the class initializer).
 * 2. The request sender method is the one that reads that field. It takes the request body and two listeners.
 * 3. The sender for an account comes from a method that takes the account name (a String) and returns the sender.
 *
 * Class, method and field names are only read from the code; none is assumed.
 */
internal object DecodeEndpointLocator {
    const val ENDPOINT_PATH = "decodeintegritytoken"

    private const val URI_TYPE = "Landroid/net/Uri;"
    private const val STRING_TYPE = "Ljava/lang/String;"
    private const val OBJECT_TYPE = "Ljava/lang/Object;"

    // The URI is built as: const-string, Uri.parse, move-result-object, sput-object.
    private const val SPUT_WITHIN_INSTRUCTIONS = 6

    private class FieldRef(val className: String, val name: String, val type: String)

    private class MethodRef(val className: String, val name: String, val parameters: List<String>, val returnType: String) {
        val key: String get() = "$className.$name(${parameters.joinToString("")})$returnType"
    }

    /**
     * Locates the endpoint of [path]. [dexes] gives the dex files of the app one after another; it is read more than
     * once, and each file should be dropped after use.
     */
    fun locate(path: String = ENDPOINT_PATH, dexes: () -> Sequence<Dex>): DecodeEndpoint {
        val field = findUriField(dexes, path)
            ?: throw EndpointNotFound("no static Uri field is set from \"$path\"")
        val senders = findReaders(dexes, field)
        val sender = when (senders.size) {
            0 -> throw EndpointNotFound("no sender method reads ${field.className}.${field.name}")
            1 -> senders.first()
            else -> throw EndpointNotFound(
                "${senders.size} sender methods read ${field.className}.${field.name}: ${senders.joinToString { it.key }}",
            )
        }
        val factories = findFactories(dexes, sender.className)
        if (factories.isEmpty()) throw EndpointNotFound("nothing returns ${sender.className} for an account name")
        return DecodeEndpoint(
            senderClass = binaryName(sender.className),
            senderMethod = sender.name,
            bodyType = binaryName(sender.parameters[0]),
            listenerTypes = sender.parameters.drop(1).map(::binaryName),
            factories = factories.map { DecodeEndpoint.Factory(binaryName(it.className), it.name) },
        )
    }

    /** The static Uri field that the class initializer sets from the endpoint's path string. */
    private fun findUriField(dexes: () -> Sequence<Dex>, path: String): FieldRef? {
        val found = LinkedHashMap<String, FieldRef>()
        for (dex in dexes()) {
            val string = dex.findString(path)
            if (string < 0) continue
            dex.forEachMethodWithCode { _, code ->
                val at = firstConstString(dex, code, string)
                if (at >= 0) uriFieldSetAfter(dex, code, at)?.let { found[it.className + "." + it.name] = it }
                true
            }
        }
        return found.values.singleOrNull()
    }

    private fun firstConstString(dex: Dex, code: Int, string: Int): Int {
        var at = -1
        dex.instructions(code) { pc, op ->
            val match = when (op) {
                Dex.OP_CONST_STRING -> dex.codeUnit(code, pc + 1) == string
                Dex.OP_CONST_STRING_JUMBO -> dex.codeInt(code, pc + 1) == string
                else -> false
            }
            if (match) at = pc
            !match
        }
        return at
    }

    /** The Uri field written by the first sput-object after the instruction at [after], if it follows closely. */
    private fun uriFieldSetAfter(dex: Dex, code: Int, after: Int): FieldRef? {
        var field: FieldRef? = null
        var seen = 0
        dex.instructions(code) { pc, op ->
            if (pc <= after) return@instructions true
            if (op == Dex.OP_SPUT_OBJECT) {
                val index = dex.codeUnit(code, pc + 1)
                if (dex.fieldType(index) == URI_TYPE) field = FieldRef(dex.fieldClass(index), dex.fieldName(index), URI_TYPE)
                return@instructions false
            }
            // Another string loaded first: this one was not stored into a field.
            if (op == Dex.OP_CONST_STRING || op == Dex.OP_CONST_STRING_JUMBO) return@instructions false
            ++seen < SPUT_WITHIN_INSTRUCTIONS
        }
        return field
    }

    /** The methods that read [field] and have the shape of a sender: (body, listener, listener), no result. */
    private fun findReaders(dexes: () -> Sequence<Dex>, field: FieldRef): List<MethodRef> {
        val found = LinkedHashMap<String, MethodRef>()
        for (dex in dexes()) {
            val index = dex.findField(field.className, field.name, field.type)
            if (index < 0) continue
            dex.forEachMethodWithCode { method, code ->
                if (reads(dex, code, index) && dex.methodName(method) != "<clinit>") {
                    val ref = methodRef(dex, method)
                    if (isSenderShape(ref)) found[ref.key] = ref
                }
                true
            }
        }
        return found.values.toList()
    }

    private fun reads(dex: Dex, code: Int, fieldIndex: Int): Boolean {
        var read = false
        dex.instructions(code) { pc, op ->
            read = op == Dex.OP_SGET_OBJECT && dex.codeUnit(code, pc + 1) == fieldIndex
            !read
        }
        return read
    }

    private fun isSenderShape(ref: MethodRef): Boolean =
        ref.returnType == "V" && ref.parameters.size == 3 && ref.parameters.all { it.startsWith("L") && it != OBJECT_TYPE }

    /** The methods that take an account name and return the sender (or, failing that, a type the sender implements). */
    private fun findFactories(dexes: () -> Sequence<Dex>, sender: String): List<MethodRef> {
        val exact = LinkedHashMap<String, MethodRef>()
        val supertypes = LinkedHashSet<String>()
        for (dex in dexes()) {
            supertypes.addAll(dex.supertypes(sender))
            for (method in dex.methodsReturning(sender)) {
                val ref = methodRef(dex, method)
                if (ref.parameters == listOf(STRING_TYPE)) exact[ref.key] = ref
            }
        }
        if (exact.isNotEmpty()) return exact.values.toList()

        val inherited = LinkedHashMap<String, MethodRef>()
        for (dex in dexes()) {
            for (type in supertypes) {
                if (type == OBJECT_TYPE) continue
                for (method in dex.methodsReturning(type)) {
                    val ref = methodRef(dex, method)
                    if (ref.parameters == listOf(STRING_TYPE)) inherited[ref.key] = ref
                }
            }
        }
        return inherited.values.toList()
    }

    private fun methodRef(dex: Dex, method: Int) = MethodRef(
        dex.methodClass(method),
        dex.methodName(method),
        dex.methodParameterTypes(method),
        dex.methodReturnType(method),
    )

    /** "Lcom/example/Outer$Inner;" as "com.example.Outer$Inner", as `Class.forName` takes it. */
    fun binaryName(descriptor: String): String {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            throw EndpointNotFound("not a class type: $descriptor")
        }
        return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }
}
