package icu.nullptr.playintegritybreak.core.playstore

/**
 * A Dagger switching provider that makes the request sender factory: a provider made with an id returns, from its `get()`,
 * the object bound to that id. The Play Store makes its factory only when something first asks for it, so a process that
 * has made no request to the server yet has none to find, and this is how to have one made.
 *
 * @property className the provider class, as a binary name
 * @property constructorParameters the types of its constructor's parameters: the component first, then the others
 * @property parameterFields the field each parameter is stored in, or null if not seen
 * @property idParameter the index of the parameter that is the id
 * @property id the id whose case in `get()` makes the factory
 */
internal class FactoryProvider(
    val className: String,
    val constructorParameters: List<String>,
    val parameterFields: List<String?>,
    val idParameter: Int,
    val id: Int,
) {
    /** The type of the first parameter, the component (it can be just Object, as in a class that R8 merged). */
    val componentType: String get() = constructorParameters[0]

    override fun toString(): String = "$className#$id($componentType)"
}

/**
 * Finds the switching providers that make the factory, from the Play Store's dex files.
 *
 * R8 merges classes, so the factory's class has constructors of several original classes. The real one is the constructor
 * that makes the map its factory method reads (a ConcurrentHashMap stored in the field that the method casts). Its callers
 * are, directly or through one static helper, the case of a provider's `get()`: a switch on an `int` field of the provider.
 * The id is the switch key whose case holds the call.
 */
internal object FactoryProviderLocator {
    private const val STRING_TYPE = "Ljava/lang/String;"
    private const val INT_TYPE = "I"
    private const val MAP_SUFFIX = "ConcurrentHashMap;"

    // The switch is at the start of the method, after the instructions that set it up.
    private const val SWITCH_WITHIN_INSTRUCTIONS = 24

    private class MethodRef(val className: String, val name: String, val parameters: List<String>, val returnType: String)

    fun locate(factories: List<DecodeEndpoint.Factory>, dexes: () -> Sequence<Dex>): List<FactoryProvider> {
        val classes = factories.map { descriptor(it.className) }.distinct()
        val methodNames = factories.map { it.methodName }.toSet()
        val realConstructors = realConstructors(classes, methodNames, dexes)

        val providers = LinkedHashMap<String, FactoryProvider>()
        val helpers = ArrayList<MethodRef>()
        for (dex in dexes()) {
            val constructors = classes.flatMap { dex.findMethodIds(it, "<init>") }.filter { id ->
                realConstructors == null || dex.methodParameterTypes(id) in realConstructors
            }.toSet()
            if (constructors.isEmpty()) continue
            dex.forEachMethodWithCode { method, code ->
                for (site in callSites(dex, code, constructors, Dex.OP_INVOKE_DIRECT, Dex.OP_INVOKE_DIRECT_RANGE)) {
                    val provider = providerAt(dex, method, code, site)
                    if (provider != null) {
                        providers[provider.toString()] = provider
                    } else {
                        helpers.add(MethodRef(dex.methodClass(method), dex.methodName(method), dex.methodParameterTypes(method), dex.methodReturnType(method)))
                    }
                }
                true
            }
        }
        if (helpers.isEmpty()) return providers.values.toList()

        // A static helper that makes the factory is called from the provider's case.
        for (dex in dexes()) {
            val callees = helpers.flatMap { helper ->
                dex.findMethodIds(helper.className, helper.name).filter {
                    dex.methodParameterTypes(it) == helper.parameters && dex.methodReturnType(it) == helper.returnType
                }
            }.toSet()
            if (callees.isEmpty()) continue
            dex.forEachMethodWithCode { method, code ->
                for (site in callSites(dex, code, callees, Dex.OP_INVOKE_STATIC, Dex.OP_INVOKE_STATIC_RANGE)) {
                    providerAt(dex, method, code, site)?.let { providers[it.toString()] = it }
                }
                true
            }
        }
        return providers.values.toList()
    }

    /**
     * The parameter lists of the factory class's constructors that make the map that the factory method reads, or null when
     * that cannot be told (then every constructor counts).
     */
    private fun realConstructors(classes: List<String>, methodNames: Set<String>, dexes: () -> Sequence<Dex>): Set<List<String>>? {
        val protos = LinkedHashSet<List<String>>()
        for (dex in dexes()) {
            for (className in classes) {
                val methods = dex.methodsWithCode(className)
                if (methods.isEmpty()) continue
                val field = methods.firstNotNullOfOrNull { (method, code) ->
                    if (dex.methodName(method) in methodNames && dex.methodParameterTypes(method) == listOf(STRING_TYPE)) {
                        castMapField(dex, code, className)
                    } else {
                        null
                    }
                } ?: continue
                for ((method, code) in methods) {
                    if (dex.methodName(method) == "<init>" && storesNewMap(dex, code, className, field)) {
                        protos.add(dex.methodParameterTypes(method))
                    }
                }
            }
        }
        return protos.ifEmpty { null }
    }

    /** The name of the field of [className] that the code loads last before it casts to a ConcurrentHashMap. */
    private fun castMapField(dex: Dex, code: Int, className: String): String? {
        var field: Int? = null
        var found: String? = null
        dex.instructions(code) { pc, op ->
            when (op) {
                Dex.OP_IGET_OBJECT -> {
                    val index = dex.codeUnit(code, pc + 1)
                    if (dex.fieldClass(index) == className) field = index
                }
                Dex.OP_CHECK_CAST -> if (dex.typeDescriptor(dex.codeUnit(code, pc + 1)).endsWith(MAP_SUFFIX)) {
                    found = field?.let { dex.fieldName(it) }
                    return@instructions false
                }
            }
            true
        }
        return found
    }

    /** Whether a constructor allocates a ConcurrentHashMap and stores it into the field [fieldName] of [className]. */
    private fun storesNewMap(dex: Dex, code: Int, className: String, fieldName: String): Boolean {
        val maps = HashSet<Int>()
        var stored = false
        dex.instructions(code) { pc, op ->
            val unit = dex.codeUnit(code, pc)
            when (op) {
                Dex.OP_NEW_INSTANCE -> if (dex.typeDescriptor(dex.codeUnit(code, pc + 1)).endsWith(MAP_SUFFIX)) maps.add((unit shr 8) and 0xff)
                Dex.OP_IPUT_OBJECT -> {
                    val index = dex.codeUnit(code, pc + 1)
                    if (((unit shr 8) and 0xf) in maps && dex.fieldClass(index) == className && dex.fieldName(index) == fieldName) {
                        stored = true
                        return@instructions false
                    }
                }
            }
            true
        }
        return stored
    }

    /** The positions in the code of calls with an opcode of [opcodes] to one of the [targets]. */
    private fun callSites(dex: Dex, code: Int, targets: Set<Int>, vararg opcodes: Int): List<Int> {
        var sites: ArrayList<Int>? = null
        dex.instructions(code) { pc, op ->
            if (op in opcodes && dex.codeUnit(code, pc + 1) in targets) (sites ?: ArrayList<Int>().also { sites = it }).add(pc)
            true
        }
        return sites.orEmpty()
    }

    /**
     * The provider if the method is a provider's code and the call at [site] is in the case of one id: the method switches
     * on an int field of its class, set up just before, and the class has a constructor that stores that field from a
     * parameter (the id), and a first parameter for the component.
     */
    private fun providerAt(dex: Dex, method: Int, code: Int, site: Int): FactoryProvider? {
        val className = dex.methodClass(method)
        val (id, idField) = switchKeyOfCase(dex, code, className, site) ?: return null
        for ((constructor, constructorCode) in dex.methodsWithCode(className)) {
            if (dex.methodName(constructor) != "<init>") continue
            val parameters = dex.methodParameterTypes(constructor)
            if (parameters.size < 2 || !parameters[0].startsWith("L") || parameters.any { it != INT_TYPE && !it.startsWith("L") }) continue
            val fields = parameterFields(dex, constructorCode, className, parameters.size)
            val idParameter = fields.indexOf(idField)
            if (idParameter < 1 || parameters[idParameter] != INT_TYPE) continue
            return FactoryProvider(
                binaryName(className),
                parameters.map { if (it == INT_TYPE) "int" else binaryName(it) },
                fields,
                idParameter,
                id,
            )
        }
        return null
    }

    /** The field of [className] that each of the constructor's [count] parameters is stored into (null if none is seen). */
    private fun parameterFields(dex: Dex, code: Int, className: String, count: Int): List<String?> {
        val first = dex.registerCount(code) - dex.argumentRegisterCount(code) // `this`; the parameters follow it
        val fields = arrayOfNulls<String>(count)
        dex.instructions(code) { pc, op ->
            if (op >= Dex.OP_IPUT && op <= Dex.OP_IPUT + 6) {
                val unit = dex.codeUnit(code, pc)
                val value = (unit shr 8) and 0xf
                val target = (unit shr 12) and 0xf
                val parameter = value - first - 1
                val index = dex.codeUnit(code, pc + 1)
                if (target == first && parameter in 0 until count && fields[parameter] == null && dex.fieldClass(index) == className) {
                    fields[parameter] = dex.fieldName(index)
                }
            }
            true
        }
        return fields.toList()
    }

    /** The key of the case that holds [site], and the name of the int field it switches on, in the method's first such switch. */
    private fun switchKeyOfCase(dex: Dex, code: Int, className: String, site: Int): Pair<Int, String>? {
        val fields = HashMap<Int, Int>() // register -> the int field of the class that it holds
        var result: Pair<Int, String>? = null
        var seen = 0
        dex.instructions(code) { pc, op ->
            if (++seen > SWITCH_WITHIN_INSTRUCTIONS) return@instructions false
            val unit = dex.codeUnit(code, pc)
            when (op) {
                Dex.OP_IGET -> {
                    val index = dex.codeUnit(code, pc + 1)
                    val register = (unit shr 8) and 0xf
                    if (dex.fieldClass(index) == className && dex.fieldType(index) == INT_TYPE) fields[register] = index else fields.remove(register)
                }
                Dex.OP_PACKED_SWITCH, Dex.OP_SPARSE_SWITCH -> {
                    val field = fields[(unit shr 8) and 0xff]
                    if (field != null) {
                        caseHolding(dex, code, pc, op == Dex.OP_PACKED_SWITCH, site)?.let { result = it to dex.fieldName(field) }
                    }
                    return@instructions false
                }
            }
            true
        }
        return result
    }

    /** The key whose case starts last at or before [site]: the case blocks are laid out one after the other. */
    private fun caseHolding(dex: Dex, code: Int, switchPc: Int, packed: Boolean, site: Int): Int? {
        val payload = switchPc + dex.codeInt(code, switchPc + 1)
        val size = dex.codeUnit(code, payload + 1)
        var bestKey: Int? = null
        var bestTarget = -1
        for (i in 0 until size) {
            val key: Int
            val target: Int
            if (packed) {
                key = dex.codeInt(code, payload + 2) + i
                target = switchPc + dex.codeInt(code, payload + 4 + 2 * i)
            } else {
                key = dex.codeInt(code, payload + 2 + 2 * i)
                target = switchPc + dex.codeInt(code, payload + 2 + 2 * size + 2 * i)
            }
            if (target <= site && target > bestTarget) {
                bestTarget = target
                bestKey = key
            }
        }
        return bestKey
    }

    private fun descriptor(binaryName: String): String = "L" + binaryName.replace('.', '/') + ";"

    private fun binaryName(descriptor: String): String = DecodeEndpointLocator.binaryName(descriptor)
}
