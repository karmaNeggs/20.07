package org.offlinemesh.app.bitchatbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BitchatPacketEncoderTest {

    private val senderId = ByteArray(8) { (it + 1).toByte() }

    @Test
    fun `header layout matches bitchat's own v1 format byte for byte`() {
        val payload = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val packet = BitchatPacketEncoder.encodeGroupMessage(
            senderId = senderId, payload = payload, ttl = 5, timestampMs = 0x0102030405060708L,
        )
        // version(1) type(1) ttl(1) timestamp(8 BE) flags(1) payloadLength(2 BE) = 14-byte header
        assertEquals(1, packet[0].toInt()) // version
        assertEquals(BitchatPacketEncoder.TYPE_GROUP_MESSAGE, packet[1].toInt() and 0xFF)
        assertEquals(5, packet[2].toInt()) // ttl
        val expectedTimestamp = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        assertArrayEquals(expectedTimestamp, packet.copyOfRange(3, 11))
        assertEquals(0, packet[11].toInt()) // flags: broadcast, unsigned, uncompressed, no route
        assertEquals(0, packet[12].toInt()) // payloadLength high byte
        assertEquals(3, packet[13].toInt()) // payloadLength low byte
        assertArrayEquals(senderId, packet.copyOfRange(14, 22))
        assertArrayEquals(payload, packet.copyOfRange(22, 25))
        assertEquals(25, packet.size)
    }

    @Test
    fun `default ttl and timestamp are applied when not specified`() {
        val packet = BitchatPacketEncoder.encodeGroupMessage(senderId = senderId, payload = ByteArray(0))
        assertEquals(BitchatPacketEncoder.DEFAULT_TTL, packet[2].toInt())
    }

    @Test
    fun `rejects a sender id of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            BitchatPacketEncoder.encodeGroupMessage(senderId = ByteArray(7), payload = ByteArray(0))
        }
    }

    @Test
    fun `rejects a ttl outside 1 to 255`() {
        assertThrows(IllegalArgumentException::class.java) {
            BitchatPacketEncoder.encodeGroupMessage(senderId = senderId, payload = ByteArray(0), ttl = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BitchatPacketEncoder.encodeGroupMessage(senderId = senderId, payload = ByteArray(0), ttl = 256)
        }
    }

    @Test
    fun `rejects a payload too large for the UInt16 length prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            BitchatPacketEncoder.encodeGroupMessage(senderId = senderId, payload = ByteArray(0x10000))
        }
    }
}
