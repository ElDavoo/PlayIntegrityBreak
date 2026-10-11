package icu.nullptr.playintegritybreak.core.playstore

import java.io.ByteArrayOutputStream

/**
 * The body of fdfe/decodeintegritytoken in protobuf wire format. This is the server's contract, so it is built here
 * from field numbers and not through the Play Store's message classes, whose field names change with every build.
 *
 *     message DecodeRequest   { string token = 1; DeviceHolder device = 2; }
 *     message DeviceHolder    { DeviceInfo info = 1; }
 *     message DeviceInfo      { string fingerprint = 1; string brand = 2; string device = 3; string model = 4; string id = 5; }
 *
 * `id` is the nonce suffix of the token request (see docs/playstore-checker-di-chain.md, section 12).
 */
internal object DecodeRequest {
    class Device(val fingerprint: String, val brand: String, val device: String, val model: String, val id: String) {
        /** The values of the device fields, in field order. */
        val values: List<String> get() = listOf(fingerprint, brand, device, model, id)
    }

    private const val TOKEN = 1
    private const val DEVICE = 2
    private const val DEVICE_INFO = 1

    fun encode(token: String, device: Device): ByteArray {
        val info = ByteArrayOutputStream()
        device.values.forEachIndexed { index, value -> writeString(info, index + 1, value) }
        val holder = ByteArrayOutputStream()
        writeBytes(holder, DEVICE_INFO, info.toByteArray())
        val body = ByteArrayOutputStream()
        writeString(body, TOKEN, token)
        writeBytes(body, DEVICE, holder.toByteArray())
        return body.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) = writeBytes(out, field, value.toByteArray())

    private fun writeBytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        writeVarint(out, (field shl 3) or LENGTH_DELIMITED)
        writeVarint(out, value.size)
        out.write(value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (rest and 0x7f.inv() != 0) {
            out.write((rest and 0x7f) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }

    private const val LENGTH_DELIMITED = 2
}
