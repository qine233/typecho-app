package noline.zhouzhou.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * SSH 模型层的单测。
 *
 * 最要紧的是指纹算法要和 OpenSSH 一模一样——对不上就等于我们自己在编一套「凭据」，
 * 用户拿 `ssh-keygen -lf` 核不了。三条样本（ed25519 / rsa / ecdsa）的正文和指纹
 * 都是本机 `ssh-keygen` 现生成的公开数据，不是秘密。
 */
class SshModelsTest {

    private fun blobOf(pubLine: String): ByteArray =
        Base64.getDecoder().decode(pubLine.trim().split(' ')[1])

    @Test
    fun fingerprintMatchesOpenSsh() {
        val ed = SshHostKey.of("192.0.2.1", 22, blobOf(Ed25519Pub))
        assertEquals("ssh-ed25519", ed.algorithm)
        assertEquals(Ed25519Fingerprint, ed.fingerprint)

        val rsa = SshHostKey.of("192.0.2.1", 22, blobOf(RsaPub))
        assertEquals("ssh-rsa", rsa.algorithm)
        assertEquals(RsaFingerprint, rsa.fingerprint)

        val ec = SshHostKey.of("192.0.2.1", 22, blobOf(EcdsaPub))
        assertEquals("ecdsa-sha2-nistp256", ec.algorithm)
        assertEquals(EcdsaFingerprint, ec.fingerprint)
    }

    @Test
    fun pinLinesRoundTrip() {
        val key = SshHostKey.of("192.0.2.1", 2222, blobOf(Ed25519Pub))
        assertEquals(key, SshHostKey.parse(key.line()))
        val text = SshHostKey.toText(listOf(key, SshHostKey("example.com", 22, "ssh-rsa", "SHA256:x")))
        val restored = SshHostKey.fromText(text)
        assertEquals(2, restored.size)
        assertEquals("192.0.2.1:2222", restored.first().id)
    }

    /** 一行写坏不能把整本密钥本丢掉。 */
    @Test
    fun brokenPinLinesAreSkipped() {
        val key = SshHostKey.of("192.0.2.1", 22, blobOf(Ed25519Pub))
        val text = "乱七八糟\n\n${key.line()}\n192.0.2.1|abc|ssh-rsa|SHA256:x\n192.0.2.1|22|ssh-rsa|"
        val restored = SshHostKey.fromText(text)
        assertEquals(listOf(key), restored)
    }

    @Test
    fun hostKeyFromPanelAddress() {
        assertEquals("192.0.2.1", SshProfile.hostFromPanel("https://192.0.2.1:9964/"))
        assertEquals("bt.example.com", SshProfile.hostFromPanel("http://bt.example.com:8888"))
        assertEquals("192.0.2.1", SshProfile.hostFromPanel("  192.0.2.1  "))
        assertEquals("", SshProfile.hostFromPanel(""))
    }

    @Test
    fun profileValidationCatchesCommonTypos() {
        val ok = SshProfile(host = "192.0.2.1", user = "root", secret = "pw")
        assertTrue(ok.issues().isEmpty())
        assertTrue(ok.configured)
        assertEquals("root@192.0.2.1:22", ok.display)

        assertTrue(SshProfile(user = "root", secret = "pw").issues().isNotEmpty())
        assertTrue(SshProfile(host = "", user = "", secret = "").issues().size >= 2)
        // 地址里带协议、带路径，八成是把面板地址整段粘过来了
        assertTrue(SshProfile(host = "https://192.0.2.1:9964/", secret = "pw").issues().isNotEmpty())
        assertTrue(SshProfile(host = "192.0.2.1/path", secret = "pw").issues().isNotEmpty())
        assertTrue(SshProfile(host = "192.0.2.1", secret = "pw", port = 0).issues().isNotEmpty())
        assertTrue(SshProfile(host = "192.0.2.1", port = 70000, secret = "pw").issues().isNotEmpty())
        assertFalse(SshProfile(host = "192.0.2.1", user = "root").configured)
    }

    @Test
    fun validatedTrimsAndReturns() {
        val fixed = SshProfile(host = " 192.0.2.1 ", user = " root ", secret = "pw").validated()
        assertEquals("192.0.2.1", fixed.host)
        assertEquals("root", fixed.user)
    }

    @Test
    fun validatedRejectsEmpty() {
        val thrown = runCatching { SshProfile(secret = "pw").validated() }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun fingerprintOfUnknownBytesStillShapesRight() {
        val key = SshHostKey.of("example.com", 22, ByteArray(0))
        assertEquals("", key.algorithm)
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        assertNull(SshHostKey.parse("example.com|notaport|ssh-rsa|SHA256:x"))
    }

    private companion object {
        const val Ed25519Pub =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIB/nuypgnJ+fIx/5rQ8sENnuuylcpzLrYcBIrhk6s7/u aa-test"
        const val Ed25519Fingerprint = "SHA256:nndTLgdmCSelA+lmLF2TiPQVXbCSbqIdtDJfyhWTehw"

        const val RsaPub =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCsPiVgecer284Aiuh1Vm1MFeUuYSoZ1E06FL9fd66/5sblFocBbnai4bns8eVFzrejnZmOS2EXHexrPffysVqPjQccM6uNHShvbKsGqfKyd1A5w2SaGCRPBknGCYV4GNIwbTW23LTwRtOlkSA4GCdSjxPcCHZ75MZP/6Hf+TmFP2IE6AWGbFT4vmeF8odV892eCj8nwxqemXGpNIpErfd4Yzt5wshE+sJLz+UEQCc07GUQFF9LyKuVfPQVwZpMYAfYC5aQDWUyrnKxtZ2Jnp2YEX0tD6pzZThuGwA+NOPVJAypB8J7jtrPCGlNr/j91kJcp7z1IU3GUvk7NurIyCkcfnRTqNfHksy/uj9lxZAbS6k+jz3BcGK1YM23CKBeruKuyXl0SmuoDnuSRMWtNp9VVIYfhPU1NN3X42wgX+haEEKgSq93xB9YK3rpXdACHikHuYSY/ixYiA0pn3XgjJxsIM4YX+LWHKmdJVaRtiIA7BKg/zvvj5P3VpTYMirfUMU= aa-test"
        const val RsaFingerprint = "SHA256:4jKbrhK7qMaia3+Nt/95Y6WLjQAn8H0Td64v6lsmqts"

        const val EcdsaPub =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBPIzqSNWFlOFpV1xzCVuY/SSOU3mfxN0H0dOfdQ67NXbXqMHDegIZLMERWJ1UB/bbLyUsgHweT4i1V5pYr3+Ws4= aa-test"
        const val EcdsaFingerprint = "SHA256:/WuVT2TotVJVJDy9OCZ/Z+ynKEKfe+Yq0juQrXV1Wg4"
    }
}
