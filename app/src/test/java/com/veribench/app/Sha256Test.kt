package com.veribench.app

import com.veribench.app.suites.Sha256
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the pure-Kotlin SHA-256 against FIPS 180-4 / NIST test vectors. */
class Sha256Test {

    private fun hash(data: ByteArray): String {
        val h = Sha256()
        h.update(data, 0, data.size)
        return h.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun emptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash(ByteArray(0)),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hash("abc".toByteArray()),
        )
    }

    @Test
    fun twoBlockMessage() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()),
        )
    }

    @Test
    fun oneMillionA() {
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hash(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun chunkedUpdateMatchesSingleUpdate() {
        val data = ByteArray(100_000) { (it * 31).toByte() }
        val whole = hash(data)
        val h = Sha256()
        var off = 0
        var chunk = 1
        while (off < data.size) {
            val take = minOf(chunk, data.size - off)
            h.update(data, off, take)
            off += take
            chunk = (chunk * 3 + 1) % 1000 + 1
        }
        assertEquals(whole, h.digest().joinToString("") { "%02x".format(it) })
    }
}
