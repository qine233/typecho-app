package noline.zhouzhou.data.remote

import net.schmizz.sshj.common.Buffer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey
import java.security.Security

/**
 * SSHJ 的密码学底座。
 *
 * Android 自带的那个 `BC` provider 是被裁过功能的壳（`com.android.org.bouncycastle…`），
 * Ed25519 和新的签名算法都不在里面；同名 provider 装不进去，所以先把壳摘掉再放完整版。
 * 只在真正要连 SSH 时做一次，不连就不动全局。
 */
object SshCrypto {

    @Volatile
    private var ready = false

    /** 我们自带的 BouncyCastle 的类名，用来认出系统那个壳。 */
    private const val OurProviderClass = "org.bouncycastle.jce.provider.BouncyCastleProvider"

    fun ensureProvider() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val existing = Security.getProvider("BC")
            if (existing == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } else if (existing.javaClass.name != OurProviderClass) {
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            ready = true
        }
    }

    /** 公钥换成 SSH 线上的那一串字节，指纹就算在它上面（和 `ssh-keygen` 一致）。 */
    fun publicKeyBlob(key: PublicKey): ByteArray =
        Buffer.PlainBuffer().putPublicKey(key).compactData
}
