package icu.nullptr.playintegritybreak.core.playstore

import java.lang.reflect.Modifier

/** The decode request as the Play Store's own message class, made by the Play Store's own protobuf parser. */
internal object RequestMessage {

    /**
     * The message of type [type] for the request. Throws [Unsupported] if the message class does not take the fields: the
     * wire bytes would then parse into unknown fields, and the request would not carry them.
     */
    fun build(type: Class<*>, token: String, device: DecodeRequest.Device): Any {
        val message = parse(type, DecodeRequest.encode(token, device))
        val held = ObjectGraph.stringsIn(message)
        if ((listOf(token) + device.values).any { it !in held }) {
            throw Unsupported("the request message does not take the expected fields (layout changed)")
        }
        return message
    }

    /**
     * Parses [bytes] as the protobuf message [type] with the parse methods of the Play Store's protobuf runtime. They are
     * static methods of the message's base class that take the base's default instance, which the message class holds in a
     * static field of its own type. Which overloads a build keeps varies:
     * (default, bytes), (default, bytes, registry) or (default, bytes, offset, length, registry).
     */
    private fun parse(type: Class<*>, bytes: ByteArray): Any {
        val defaultInstance = type.declaredFields
            .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == type }
            ?.apply { isAccessible = true }?.get(null)
            ?: throw Unsupported("the request message has no default instance")
        val parsers = generateSequence<Class<*>>(type.superclass) { it.superclass }.flatMap { base ->
            base.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.returnType == base && it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == base && it.parameterTypes[1] == ByteArray::class.java
            }.asSequence()
        }.toList()
        val int = Int::class.javaPrimitiveType
        val parser = parsers.firstOrNull { it.parameterTypes.size == 2 }
            ?: parsers.firstOrNull { it.parameterTypes.size == 3 }
            ?: parsers.firstOrNull { it.parameterTypes.size == 5 && it.parameterTypes[2] == int && it.parameterTypes[3] == int }
            ?: throw Unsupported("no parser for the request message")
        parser.isAccessible = true
        val arguments: Array<Any?> = when (parser.parameterTypes.size) {
            2 -> arrayOf(defaultInstance, bytes)
            3 -> arrayOf(defaultInstance, bytes, extensionRegistry(parser.parameterTypes[2]))
            else -> arrayOf(defaultInstance, bytes, 0, bytes.size, extensionRegistry(parser.parameterTypes[4]))
        }
        return parser.invoke(null, *arguments) ?: throw Unsupported("the request message did not parse")
    }

    /** An empty extension registry of the runtime's type [registry]: a static method of it with no arguments that returns one. */
    private fun extensionRegistry(registry: Class<*>): Any = registry.declaredMethods
        .filter { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == registry }
        .firstNotNullOfOrNull { method -> runCatching { method.apply { isAccessible = true }.invoke(null) }.getOrNull() }
        ?: throw Unsupported("no extension registry for the request message parser")
}
