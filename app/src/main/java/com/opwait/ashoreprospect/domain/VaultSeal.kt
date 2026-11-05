package com.opwait.ashoreprospect.domain

/** Desktop Vault wire prefix (`AP1.`). Not shown in UI — host cannot read user fields. */
object VaultSeal {
    const val PREFIX = "AP1."

    fun isSealed(raw: String?): Boolean = raw.orEmpty().startsWith(PREFIX)

    fun displayTitle(raw: String, fallback: String = "Untitled"): String {
        if (!isSealed(raw)) return raw
        val opened = VaultCrypto.open(raw)
        return if (isSealed(opened)) fallback else opened
    }
}
