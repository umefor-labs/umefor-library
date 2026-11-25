package io.lightknight.omniread.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        return file.inputStream().use { sha256(it) }
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var read = input.read(buffer)
        while (read > 0) {
            md.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
