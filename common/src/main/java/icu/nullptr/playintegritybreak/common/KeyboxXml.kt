package icu.nullptr.playintegritybreak.common

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Keybox files, in the form TrickyStore reads as keybox.xml. */
object KeyboxXml {
    /** Bigger than any keybox published so far: a larger download is refused, not read whole. */
    const val MAX_BYTES = 256 * 1024

    /**
     * Whether [bytes] is a keybox: an AndroidAttestation document with a Keybox that has a private key and a certificate
     * chain. The keys are not checked here: the Play Store's verdict is the test.
     */
    fun isKeybox(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        // The file comes from a server: no DTDs, so no entities either. Android's parser supports neither feature below
        // and ignores them, so a document type declaration is refused here, before any parser sees it.
        if (bytes.decodeToString().contains("<!DOCTYPE", ignoreCase = true)) return false
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            root.tagName == "AndroidAttestation" && hasKeyWithChain(root)
        }.getOrDefault(false)
    }

    /** The ID of a keybox file: the MD5 of its bytes in hex, which is the name the scavenger stores them under. */
    fun id(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hasKeyWithChain(root: Element): Boolean {
        val keyboxes = root.getElementsByTagName("Keybox")
        return (0 until keyboxes.length).any { index ->
            val keybox = keyboxes.item(index) as Element
            keybox.getElementsByTagName("PrivateKey").length > 0 &&
                keybox.getElementsByTagName("CertificateChain").length > 0
        }
    }
}
