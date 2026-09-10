package com.redtv.app.data

import java.security.MessageDigest

object Identity {
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    fun scoped(provider: String, id: String) = "${hash(provider)}:$id"
}
