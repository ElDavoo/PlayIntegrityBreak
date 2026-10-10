package icu.nullptr.playintegritybreak.core.playstore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A read-only view of one DEX file, with what it takes to find a method by the strings and fields its code uses:
 * the id tables, the class data and the instruction stream. Nothing is loaded or run; the Play Store's own code is
 * only read. Offsets and indexes are the DEX format's (https://source.android.com/docs/core/runtime/dex-format).
 */
internal class Dex(bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private val stringIdsSize: Int
    private val stringIdsOff: Int
    private val typeIdsSize: Int
    private val typeIdsOff: Int
    private val protoIdsOff: Int
    private val fieldIdsSize: Int
    private val fieldIdsOff: Int
    private val methodIdsSize: Int
    private val methodIdsOff: Int
    private val classDefsSize: Int
    private val classDefsOff: Int

    init {
        require(bytes.size >= HEADER_SIZE && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()) { "not a dex file" }
        require(buf.getInt(40) == ENDIAN_CONSTANT) { "unexpected dex byte order" }
        stringIdsSize = buf.getInt(56)
        stringIdsOff = buf.getInt(60)
        typeIdsSize = buf.getInt(64)
        typeIdsOff = buf.getInt(68)
        protoIdsOff = buf.getInt(76)
        fieldIdsSize = buf.getInt(80)
        fieldIdsOff = buf.getInt(84)
        methodIdsSize = buf.getInt(88)
        methodIdsOff = buf.getInt(92)
        classDefsSize = buf.getInt(96)
        classDefsOff = buf.getInt(100)
    }

    // Strings and types

    fun string(index: Int): String {
        var pos = buf.getInt(stringIdsOff + 4 * index)
        while (buf.get(pos).toInt() and 0x80 != 0) pos++ // the length in UTF-16 units, as a uleb128
        pos++
        val out = StringBuilder()
        while (true) {
            val b = buf.get(pos++).toInt() and 0xff
            when {
                b == 0 -> return out.toString()
                b < 0x80 -> out.append(b.toChar())
                b and 0xe0 == 0xc0 -> {
                    out.append(((b and 0x1f) shl 6 or (buf.get(pos++).toInt() and 0x3f)).toChar())
                }
                else -> {
                    val b2 = buf.get(pos++).toInt() and 0x3f
                    val b3 = buf.get(pos++).toInt() and 0x3f
                    out.append(((b and 0x0f) shl 12 or (b2 shl 6) or b3).toChar())
                }
            }
        }
    }

    /** The index of [text] in the string table, or -1. The table is sorted, so this is a binary search. */
    fun findString(text: String): Int {
        var low = 0
        var high = stringIdsSize - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val order = string(mid).compareTo(text)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The descriptor of a type, such as "Lqir;". */
    fun typeDescriptor(typeIndex: Int): String = string(buf.getInt(typeIdsOff + 4 * typeIndex))

    /** The index of the type with this descriptor, or -1. The type table is sorted by string index. */
    fun findType(descriptor: String): Int {
        val stringIndex = findString(descriptor)
        if (stringIndex < 0) return -1
        var low = 0
        var high = typeIdsSize - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = buf.getInt(typeIdsOff + 4 * mid)
            when {
                at < stringIndex -> low = mid + 1
                at > stringIndex -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    // Fields

    fun fieldClass(fieldIndex: Int): String = typeDescriptor(u16(fieldIdsOff + 8 * fieldIndex))
    fun fieldType(fieldIndex: Int): String = typeDescriptor(u16(fieldIdsOff + 8 * fieldIndex + 2))
    fun fieldName(fieldIndex: Int): String = string(buf.getInt(fieldIdsOff + 8 * fieldIndex + 4))

    /** The index of the field [className].[name] of type [type] in this file's field table, or -1. */
    fun findField(className: String, name: String, type: String): Int {
        val classIndex = findType(className)
        val typeIndex = findType(type)
        val nameIndex = findString(name)
        if (classIndex < 0 || typeIndex < 0 || nameIndex < 0) return -1
        for (i in 0 until fieldIdsSize) {
            val at = fieldIdsOff + 8 * i
            if (u16(at) == classIndex && u16(at + 2) == typeIndex && buf.getInt(at + 4) == nameIndex) return i
        }
        return -1
    }

    // Methods

    fun methodClass(methodIndex: Int): String = typeDescriptor(u16(methodIdsOff + 8 * methodIndex))
    fun methodName(methodIndex: Int): String = string(buf.getInt(methodIdsOff + 8 * methodIndex + 4))

    /** The return type of a method, as a descriptor. */
    fun methodReturnType(methodIndex: Int): String = typeDescriptor(buf.getInt(protoOffset(methodIndex) + 4))

    /** The parameter types of a method, as descriptors. */
    fun methodParameterTypes(methodIndex: Int): List<String> {
        val list = buf.getInt(protoOffset(methodIndex) + 8)
        if (list == 0) return emptyList()
        return List(buf.getInt(list)) { typeDescriptor(u16(list + 4 + 2 * it)) }
    }

    /** The indexes in the method table of the methods named [name] of the class [className]. */
    fun findMethodIds(className: String, name: String): List<Int> {
        val classIndex = findType(className)
        val nameIndex = findString(name)
        if (classIndex < 0 || nameIndex < 0) return emptyList()
        return (0 until methodIdsSize).filter {
            u16(methodIdsOff + 8 * it) == classIndex && buf.getInt(methodIdsOff + 8 * it + 4) == nameIndex
        }
    }

    /** The method index and code offset of each method with code that the class [className] defines in this file. */
    fun methodsWithCode(className: String): List<Pair<Int, Int>> {
        val classIndex = findType(className)
        if (classIndex < 0) return emptyList()
        val result = ArrayList<Pair<Int, Int>>()
        for (classDef in 0 until classDefsSize) {
            if (buf.getInt(classDefsOff + 32 * classDef) != classIndex) continue
            forEachMethodOfClass(classDef) { method, code ->
                result.add(method to code)
                true
            }
            break
        }
        return result
    }

    /** The indexes in the method table of the methods that return the type [descriptor]. */
    fun methodsReturning(descriptor: String): List<Int> {
        val typeIndex = findType(descriptor)
        if (typeIndex < 0) return emptyList()
        return (0 until methodIdsSize).filter { buf.getInt(protoOffset(it) + 4) == typeIndex }
    }

    private fun protoOffset(methodIndex: Int): Int = protoIdsOff + 12 * u16(methodIdsOff + 8 * methodIndex + 2)

    /** The superclass and the interfaces a class in this file declares, as descriptors. Empty if it is not defined here. */
    fun supertypes(descriptor: String): List<String> {
        val typeIndex = findType(descriptor)
        if (typeIndex < 0) return emptyList()
        for (classDef in 0 until classDefsSize) {
            val at = classDefsOff + 32 * classDef
            if (buf.getInt(at) != typeIndex) continue
            val result = ArrayList<String>()
            val superclass = buf.getInt(at + 8)
            if (superclass != NO_INDEX) result.add(typeDescriptor(superclass))
            val interfaces = buf.getInt(at + 12)
            if (interfaces != 0) repeat(buf.getInt(interfaces)) { result.add(typeDescriptor(u16(interfaces + 4 + 2 * it))) }
            return result
        }
        return emptyList()
    }

    /**
     * Calls [visit] with the method index and code offset of every method that has code. Stops when [visit] returns
     * false. The code offset is what [instructions] reads.
     */
    inline fun forEachMethodWithCode(visit: (methodIndex: Int, codeOffset: Int) -> Boolean) {
        for (classDef in 0 until classDefCount()) {
            if (!forEachMethodOfClass(classDef, visit)) return
        }
    }

    /** Like [forEachMethodWithCode], for the class definition [classDef] only. Returns false if [visit] stopped it. */
    inline fun forEachMethodOfClass(classDef: Int, visit: (methodIndex: Int, codeOffset: Int) -> Boolean): Boolean {
        val classData = classDataOffset(classDef)
        if (classData == 0) return true
        val reader = Uleb(classData)
        val staticFields = reader.next()
        val instanceFields = reader.next()
        val directMethods = reader.next()
        val virtualMethods = reader.next()
        repeat(staticFields + instanceFields) {
            reader.next()
            reader.next()
        }
        for (count in intArrayOf(directMethods, virtualMethods)) {
            var methodIndex = 0
            repeat(count) {
                methodIndex += reader.next()
                reader.next()
                val codeOffset = reader.next()
                if (codeOffset != 0 && !visit(methodIndex, codeOffset)) return false
            }
        }
        return true
    }

    fun classDefCount(): Int = classDefsSize

    fun classDataOffset(classDef: Int): Int = buf.getInt(classDefsOff + 32 * classDef + 24)

    /** Reads uleb128 values from the file, from a start offset. */
    inner class Uleb(private var pos: Int) {
        fun next(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = buf.get(pos++).toInt() and 0xff
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }

    // Code

    /**
     * Calls [visit] with the position (in 16-bit code units) and opcode of each instruction of the method's code.
     * Switch and array payloads are skipped. Stops when [visit] returns false.
     */
    inline fun instructions(codeOffset: Int, visit: (position: Int, opcode: Int) -> Boolean) {
        val size = codeUnitCount(codeOffset)
        var pc = 0
        while (pc < size) {
            val unit = codeUnit(codeOffset, pc)
            val payload = payloadUnits(codeOffset, pc, unit)
            if (payload > 0) {
                pc += payload
                continue
            }
            val opcode = unit and 0xff
            if (!visit(pc, opcode)) return
            pc += OPCODE_UNITS[opcode].toInt()
        }
    }

    fun codeUnitCount(codeOffset: Int): Int = buf.getInt(codeOffset + 12)

    /** The number of registers of the method, and how many of them are its arguments (`this` included). */
    fun registerCount(codeOffset: Int): Int = u16(codeOffset)
    fun argumentRegisterCount(codeOffset: Int): Int = u16(codeOffset + 2)

    /** The 16-bit code unit at [position] in the method's instruction stream. */
    fun codeUnit(codeOffset: Int, position: Int): Int = u16(codeOffset + 16 + 2 * position)

    /** The 32-bit value in the two code units from [position], low unit first. */
    fun codeInt(codeOffset: Int, position: Int): Int = codeUnit(codeOffset, position) or (codeUnit(codeOffset, position + 1) shl 16)

    /** The size in code units of the payload pseudo-instruction at [position], or 0 if the position is a real instruction. */
    fun payloadUnits(codeOffset: Int, position: Int, unit: Int): Int = when (unit) {
        PACKED_SWITCH_PAYLOAD -> 4 + 2 * codeUnit(codeOffset, position + 1)
        SPARSE_SWITCH_PAYLOAD -> 2 + 4 * codeUnit(codeOffset, position + 1)
        FILL_ARRAY_PAYLOAD -> 4 + (codeUnit(codeOffset, position + 1) * codeInt(codeOffset, position + 2) + 1) / 2
        else -> 0
    }

    private fun u16(pos: Int): Int = buf.getShort(pos).toInt() and 0xffff

    companion object {
        private const val HEADER_SIZE = 0x70
        private const val ENDIAN_CONSTANT = 0x12345678
        private const val NO_INDEX = -1
        private const val PACKED_SWITCH_PAYLOAD = 0x0100
        private const val SPARSE_SWITCH_PAYLOAD = 0x0200
        private const val FILL_ARRAY_PAYLOAD = 0x0300

        const val OP_NEW_INSTANCE = 0x22
        const val OP_CHECK_CAST = 0x1f
        const val OP_PACKED_SWITCH = 0x2b
        const val OP_SPARSE_SWITCH = 0x2c
        const val OP_IPUT = 0x59
        const val OP_IGET = 0x52
        const val OP_IGET_OBJECT = 0x54
        const val OP_IPUT_OBJECT = 0x5b
        const val OP_INVOKE_DIRECT = 0x70
        const val OP_INVOKE_STATIC = 0x71
        const val OP_INVOKE_DIRECT_RANGE = 0x76
        const val OP_INVOKE_STATIC_RANGE = 0x77
        const val OP_CONST_STRING = 0x1a
        const val OP_CONST_STRING_JUMBO = 0x1b
        const val OP_SGET_OBJECT = 0x62
        const val OP_SPUT_OBJECT = 0x69

        /** The length in code units of each opcode's instruction (the DEX instruction formats). */
        @JvmField
        val OPCODE_UNITS: ByteArray = ByteArray(256) { 1 }.also { units ->
            fun set(range: IntRange, length: Int) = range.forEach { units[it] = length.toByte() }
            set(0x02..0x02, 2); set(0x03..0x03, 3); set(0x05..0x05, 2); set(0x06..0x06, 3)
            set(0x08..0x08, 2); set(0x09..0x09, 3)
            set(0x13..0x13, 2); set(0x14..0x14, 3); set(0x15..0x16, 2); set(0x17..0x17, 3)
            set(0x18..0x18, 5); set(0x19..0x1a, 2); set(0x1b..0x1b, 3); set(0x1c..0x1c, 2)
            set(0x1f..0x20, 2); set(0x22..0x23, 2); set(0x24..0x26, 3)
            set(0x29..0x29, 2); set(0x2a..0x2c, 3); set(0x2d..0x3d, 2)
            set(0x44..0x6d, 2); set(0x6e..0x72, 3); set(0x74..0x78, 3)
            set(0x90..0xaf, 2); set(0xd0..0xe2, 2)
            set(0xfa..0xfb, 4); set(0xfc..0xfd, 3); set(0xfe..0xff, 2)
        }
    }
}
