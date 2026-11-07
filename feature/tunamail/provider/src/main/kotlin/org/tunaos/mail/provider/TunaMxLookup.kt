package org.tunaos.mail.provider

import org.minidns.hla.DnssecResolverApi
import org.minidns.record.MX

/** Looks up the mail servers of a domain. Blocks on the network; call it off the main thread. */
fun interface TunaMxLookup {
    /** The MX hosts of [domain], most preferred first. Throws an `IOException` when the lookup fails. */
    fun lookup(domain: String): MxHosts
}

/**
 * The MX hosts of a domain, most preferred first, in lower case without a trailing dot. [isTrusted] when the answer was
 * signed (DNSSEC).
 */
data class MxHosts(val hosts: List<String>, val isTrusted: Boolean)

/** [TunaMxLookup] through MiniDNS, the resolver the app's own MX-based configuration lookup uses. */
class MiniDnsMxLookup : TunaMxLookup {
    override fun lookup(domain: String): MxHosts {
        val result = DnssecResolverApi.INSTANCE.resolve(domain, MX::class.java)
        val hosts = result.answersOrEmptySet
            .sortedBy { it.priority }
            .map { it.target.toString().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() }
        return MxHosts(hosts = hosts, isTrusted = result.wasSuccessful() && result.isAuthenticData)
    }
}
