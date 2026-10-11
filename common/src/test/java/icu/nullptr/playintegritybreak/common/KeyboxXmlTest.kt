package icu.nullptr.playintegritybreak.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxXmlTest {

    private val keybox = """
        <?xml version="1.0"?>
        <AndroidAttestation>
          <NumberOfKeyboxes>1</NumberOfKeyboxes>
          <Keybox DeviceID="test">
            <Key algorithm="ecdsa">
              <PrivateKey format="pem">not a real key</PrivateKey>
              <CertificateChain>
                <NumberOfCertificates>1</NumberOfCertificates>
                <Certificate format="pem">not a real certificate</Certificate>
              </CertificateChain>
            </Key>
          </Keybox>
        </AndroidAttestation>
    """.trimIndent()

    private fun String.bytes() = toByteArray(Charsets.UTF_8)

    @Test
    fun acceptsAKeyboxWithAKeyAndItsChain() {
        assertTrue(KeyboxXml.isKeybox(keybox.bytes()))
    }

    @Test
    fun refusesOtherDocuments() {
        assertFalse(KeyboxXml.isKeybox("<html><body>not found</body></html>".bytes()))
        assertFalse(KeyboxXml.isKeybox("not xml at all".bytes()))
        assertFalse(KeyboxXml.isKeybox(ByteArray(0)))
    }

    @Test
    fun refusesAKeyboxWithoutAPrivateKeyOrChain() {
        val withoutChain = keybox.replace("CertificateChain", "Chain")
        assertFalse(KeyboxXml.isKeybox(withoutChain.bytes()))
    }

    @Test
    fun refusesDocumentTypeDeclarations() {
        val withEntity = "<!DOCTYPE AndroidAttestation [<!ENTITY x \"y\">]>\n" + keybox
        assertFalse(KeyboxXml.isKeybox(withEntity.bytes()))
    }

    @Test
    fun refusesFilesOverTheSizeLimit() {
        val padded = keybox + " ".repeat(KeyboxXml.MAX_BYTES)
        assertFalse(KeyboxXml.isKeybox(padded.bytes()))
    }

    @Test
    fun idIsTheMd5OfTheBytes() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", KeyboxXml.id("abc".bytes()))
    }
}
