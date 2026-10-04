package io.github.aedev.flow.data.folders

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * Android ships a stripped provider that is already registered as "BC", so sshj neither registers its bundled
 * BouncyCastle nor finds curve25519/ed25519 under that name. The full BouncyCastle is therefore exposed under a
 * private provider name that only sshj selects; the platform "BC" and every other JCA lookup stay untouched.
 */
internal object SftpSecurity {
    private const val PROVIDER_NAME = "MilkbeatSshBC"

    @Synchronized
    fun install() {
        if (Security.getProvider(PROVIDER_NAME) != null) return
        Security.addProvider(SshCryptoProvider())
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.setSecurityProvider(PROVIDER_NAME)
    }

    @Suppress("DEPRECATION")
    private class SshCryptoProvider : Provider(PROVIDER_NAME, 1.0, "Bundled BouncyCastle for SSH") {
        init {
            putAll(BouncyCastleProvider())
        }
    }
}
