package com.redtv.app.net

import java.security.SecureRandom

/** In-memory, bounded pairing/session lifetime. Fresh screen = fresh authority. */
class LanAuth(private val code: String = newCode(), private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Session(val token: String, val csrf: String, val expires: Long)
    private val created = now()
    private var attempts = 0
    private val sessions = LinkedHashMap<String, Session>()
    @Synchronized fun pair(candidate: String): Session? {
        if (now() - created > 600_000 || attempts >= 10 || sessions.size >= 8) return null
        attempts++
        if (candidate != code) return null
        return Session(randomToken(), randomToken(), now() + 28_800_000).also { sessions[it.token] = it }
    }
    @Synchronized fun validate(token: String?): Session? = sessions[token]?.takeIf { it.expires > now() }
    @Synchronized fun clear() { sessions.clear(); attempts = 10 }
    companion object {
        private val random = SecureRandom()
        fun newCode() = (100000 + random.nextInt(900000)).toString()
        fun randomToken(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

