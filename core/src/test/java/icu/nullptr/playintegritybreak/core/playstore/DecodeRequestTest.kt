package icu.nullptr.playintegritybreak.core.playstore

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeRequestTest {
    private val device = DecodeRequest.Device("google/panther/panther:15/AP1A:user/release-keys", "google", "panther", "Pixel 7", "xcfp7fnx077030cnkdjsso")

    @Test
    fun hasTheTokenInField1AndTheDeviceInField2Dot1() {
        val message = Wire.parse(DecodeRequest.encode("the-token", device))
        assertEquals("the-token", message.string(1))
        val info = message.message(2).message(1)
        assertEquals(device.fingerprint, info.string(1))
        assertEquals(device.brand, info.string(2))
        assertEquals(device.device, info.string(3))
        assertEquals(device.model, info.string(4))
        assertEquals("xcfp7fnx077030cnkdjsso", info.string(5))
    }

    @Test
    fun writesNothingElse() {
        val message = Wire.parse(DecodeRequest.encode("t", device))
        assertEquals(listOf(1, 2), message.fields.map { it.first })
        assertEquals(listOf(1), message.message(2).fields.map { it.first })
        assertEquals(listOf(1, 2, 3, 4, 5), message.message(2).message(1).fields.map { it.first })
    }

    @Test
    fun longValuesUseMultiByteLengths() {
        val token = "x".repeat(1441)
        val message = Wire.parse(DecodeRequest.encode(token, device))
        assertEquals(token, message.string(1))
    }

    @Test
    fun encodesNonAsciiAsUtf8() {
        val model = "Pixel é中"
        val message = Wire.parse(DecodeRequest.encode("t", DecodeRequest.Device("f", "b", "d", model, "i")))
        assertEquals(model, message.message(2).message(1).string(4))
    }

    /** A reader of length-delimited protobuf fields, to check what was written. */
    private class Wire(val fields: List<Pair<Int, ByteArray>>) {
        fun string(number: Int): String = String(fields.single { it.first == number }.second)
        fun message(number: Int): Wire = parse(fields.single { it.first == number }.second)

        companion object {
            fun parse(bytes: ByteArray): Wire {
                val fields = ArrayList<Pair<Int, ByteArray>>()
                var pos = 0
                fun varint(): Int {
                    var result = 0
                    var shift = 0
                    while (true) {
                        val b = bytes[pos++].toInt() and 0xff
                        result = result or ((b and 0x7f) shl shift)
                        if (b and 0x80 == 0) return result
                        shift += 7
                    }
                }
                while (pos < bytes.size) {
                    val tag = varint()
                    assertEquals("only length-delimited fields", 2, tag and 7)
                    val length = varint()
                    fields.add((tag shr 3) to bytes.copyOfRange(pos, pos + length))
                    pos += length
                }
                return Wire(fields)
            }
        }
    }
}
