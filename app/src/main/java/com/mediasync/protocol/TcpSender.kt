package com.mediasync.protocol

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "TcpSender"

// File type constants — must match server protocol.rs
const val FILE_TYPE_VIDEO: Byte = 0x01
const val FILE_TYPE_PHOTO: Byte = 0x02
const val FILE_TYPE_PNG: Byte   = 0x03
const val FILE_TYPE_WEBP: Byte  = 0x04
const val FILE_TYPE_HEIC: Byte  = 0x05
const val FILE_TYPE_GIF: Byte   = 0x06
const val FILE_TYPE_BMP: Byte   = 0x07
const val FILE_TYPE_MKV: Byte   = 0x08
const val FILE_TYPE_MOV: Byte   = 0x09
const val FILE_TYPE_MPEG: Byte  = 0x0A
const val FILE_TYPE_3GP: Byte   = 0x0B
const val FILE_TYPE_RAW: Byte   = 0x0C
const val FILE_TYPE_WEBM: Byte  = 0x0D

// Auth constants
const val PACKET_AUTH: Byte = 0x10
const val AUTH_OK: Byte   = 0x01
const val AUTH_FAIL: Byte = 0x00

// Response constants
const val RESP_OK:   Byte = 0xAA.toByte()
const val RESP_SKIP: Byte = 0xBB.toByte()
const val RESP_NACK: Byte = 0xFF.toByte()

sealed class SendResult {
    object Ok   : SendResult()
    object Skip : SendResult()
    data class Error(val message: String) : SendResult()
}

/**
 * Performs the auth handshake with the server.
 * 1. Read 32-byte nonce from server
 * 2. Compute HMAC-SHA256(password_sha256, nonce)
 * 3. Send auth packet
 * 4. Read auth response
 * Returns true if auth succeeded, false if legacy server (no nonce sent).
 * Throws on auth failure.
 */
fun authenticate(
    host:     String,
    port:     Int,
    username: String,
    password: String,
    timeoutMs: Int = 10_000,
): Boolean {
    val socket = Socket()
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    socket.soTimeout = timeoutMs

    return try {
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        // Try to read the first byte — could be nonce byte or server might
        // just send it. We read 32 bytes.
        val nonce = ByteArray(32)
        var total = 0
        while (total < 32) {
            val n = inp.read(nonce, total, 32 - total)
            if (n <= 0) break
            total += n
        }

        if (total == 0) {
            // Server didn't send nonce — might be legacy server
            // Try to peek: if first byte is 0x01/0x02, it's legacy
            socket.soTimeout = 5000
            val peek = ByteArray(1)
            val n = socket.getInputStream().read(peek)
            if (n > 0 && (peek[0] == FILE_TYPE_VIDEO || peek[0] == FILE_TYPE_PHOTO)) {
                Log.d(TAG, "detected legacy server (no auth)")
                return true // legacy mode — proceed
            }
            return false
        }

        // Compute HMAC
        val passwordSha256 = sha256Hex(password)
        val hmacKey = passwordSha256.toByteArray(Charsets.US_ASCII)
        val hmac = hmacSha256(hmacKey, nonce)

        // Build auth packet: [1B: 0x10][4B: username_len][username][32B: HMAC]
        val usernameBytes = username.toByteArray(Charsets.UTF_8)
        val packet = ByteBuffer.allocate(1 + 4 + usernameBytes.size + 32).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(PACKET_AUTH)
            putInt(usernameBytes.size)
            put(usernameBytes)
            put(hmac)
        }.array()

        out.write(packet)
        out.flush()

        // Read response (1 byte)
        val resp = inp.read().toByte()
        when (resp) {
            AUTH_OK -> {
                Log.d(TAG, "auth OK for user '$username'")
                true
            }
            AUTH_FAIL -> {
                throw AuthException("auth failed for user '$username'")
            }
            else -> {
                throw AuthException("unexpected auth response: 0x${resp.toUByte().toString(16)}")
            }
        }
    } finally {
        socket.close()
    }
}

class AuthException(message: String) : Exception(message)

/**
 * Computes SHA-256 of a file by streaming — no full load into memory.
 */
fun computeFileHash(
    context: Context,
    uri: Uri,
    fileSizeHint: Long,
): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)

        context.contentResolver.openInputStream(uri)?.use { stream ->
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        } ?: return null

        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e(TAG, "computeFileHash failed for $uri: ${e.message}")
        null
    }
}

/**
 * Sends a file by streaming from ContentResolver — never loads full file into memory.
 * Caller must have already computed SHA-256 via computeFileHash.
 */
fun sendFileStream(
    host:       String,
    port:       Int,
    username:   String,
    password:   String,
    fileType:   Byte,
    fileSize:   Long,
    timestamp:  Long,
    fileName:   String,
    sha256hex:  String,
    context:    Context,
    uri:        Uri,
    onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    timeoutMs:  Int = 60_000,
): SendResult {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.sendBufferSize = 256 * 1024

        socket.use { sock ->
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            // Auth handshake
            authenticateSocket(sock, username, password, out, inp)

            Log.d(TAG, "sending '$fileName' type=0x${fileType.toUByte().toString(16)} size=$fileSize sha256=${sha256hex.take(12)}...")

            // ── 1. Заголовок (53 байта) ────────────────────────────
            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            val header = ByteBuffer.allocate(53).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(fileType)
                putLong(fileSize)
                put(hexToBytes(sha256hex))
                putLong(timestamp)
                putInt(nameBytes.size)
            }.array()

            out.write(header)

            // ── 2. Имя файла ───────────────────────────────────────
            out.write(nameBytes)

            // ── 3. Тело файла стримом из ContentResolver ───────────
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val chunkSize = 65536
                val buf = ByteArray(chunkSize)
                var sent = 0L
                var n: Int

                while (inputStream.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent, fileSize)
                }
            } ?: return SendResult.Error("cannot open file stream")

            out.flush()
            Log.d(TAG, "sent all bytes, waiting for ACK...")

            // ── 4. Читаем ответ (1 байт) ──────────────────────────
            val resp = inp.read().toByte()
            Log.d(TAG, "server: 0x${resp.toUByte().toString(16).uppercase()}")

            when (resp) {
                RESP_OK   -> SendResult.Ok
                RESP_SKIP -> SendResult.Skip
                RESP_NACK -> SendResult.Error("server NACK")
                else      -> SendResult.Error("unknown response 0x${resp.toUByte().toString(16)}")
            }
        }

    } catch (e: AuthException) {
        SendResult.Error("auth failed: ${e.message}")
    } catch (e: Exception) {
        Log.e(TAG, "sendFile failed: ${e.message}", e)
        SendResult.Error(e.message ?: "unknown error")
    }
}

/**
 * Sends one file to the server.
 * Creates a new connection, authenticates, then sends the file.
 */
fun sendFile(
    host:      String,
    port:      Int,
    username:  String,
    password:  String,
    fileType:  Byte,
    fileSize:  Long,
    timestamp: Long,
    fileName:  String,
    fileBytes: ByteArray,
    sha256hex: String,
    onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    timeoutMs: Int = 60_000,
): SendResult {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.sendBufferSize = 256 * 1024

        socket.use { sock ->
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            // Auth handshake
            authenticateSocket(sock, username, password, out, inp)

            Log.d(TAG, "sending '${fileName}' type=0x${fileType.toUByte().toString(16)} size=${fileSize} sha256=${sha256hex.take(12)}...")

            // ── 1. Заголовок (53 байта) ────────────────────────────
            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            val header = ByteBuffer.allocate(53).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(fileType)
                putLong(fileSize)
                put(hexToBytes(sha256hex))
                putLong(timestamp)
                putInt(nameBytes.size)
            }.array()

            out.write(header)

            // ── 2. Имя файла ───────────────────────────────────────
            out.write(nameBytes)

            // ── 3. Тело файла чанками ──────────────────────────────
            val chunkSize = 65536
            var sent = 0L
            var offset = 0

            while (offset < fileBytes.size) {
                val toWrite = minOf(chunkSize, fileBytes.size - offset)
                out.write(fileBytes, offset, toWrite)
                offset += toWrite
                sent += toWrite
                onProgress(sent, fileSize)
            }

            out.flush()
            Log.d(TAG, "sent $sent bytes, waiting for ACK...")

            // ── 4. Читаем ответ (1 байт) ──────────────────────────
            val resp = inp.read().toByte()
            Log.d(TAG, "server: 0x${resp.toUByte().toString(16).uppercase()}")

            when (resp) {
                RESP_OK   -> SendResult.Ok
                RESP_SKIP -> SendResult.Skip
                RESP_NACK -> SendResult.Error("server NACK")
                else      -> SendResult.Error("unknown response 0x${resp.toUByte().toString(16)}")
            }
        }

    } catch (e: AuthException) {
        SendResult.Error("auth failed: ${e.message}")
    } catch (e: Exception) {
        Log.e(TAG, "sendFile failed: ${e.message}", e)
        SendResult.Error(e.message ?: "unknown error")
    }
}

/**
 * Authenticate on an already-connected socket.
 * Throws AuthException on failure.
 * Returns silently for legacy servers (no auth needed).
 */
fun authenticateSocket(
    socket: Socket,
    username: String,
    password: String,
    out: java.io.OutputStream,
    inp: java.io.InputStream,
): Boolean {
    // Try to read 32-byte nonce
    val nonce = ByteArray(32)
    var total = 0
    while (total < 32) {
        val n = inp.read(nonce, total, 32 - total)
        if (n <= 0) break
        total += n
    }

    if (total == 0) {
        // No nonce — legacy server
        Log.d(TAG, "detected legacy server")
        return true
    }

    // Compute HMAC
    val passwordSha256 = sha256Hex(password)
    val hmacKey = passwordSha256.toByteArray(Charsets.US_ASCII)
    val hmac = hmacSha256(hmacKey, nonce)

    // Build auth packet
    val usernameBytes = username.toByteArray(Charsets.UTF_8)
    val packet = ByteBuffer.allocate(1 + 4 + usernameBytes.size + 32).apply {
        order(ByteOrder.BIG_ENDIAN)
        put(PACKET_AUTH)
        putInt(usernameBytes.size)
        put(usernameBytes)
        put(hmac)
    }.array()

    out.write(packet)
    out.flush()

    val resp = inp.read().toByte()
    return when (resp) {
        AUTH_OK -> {
            Log.d(TAG, "auth OK for user '$username'")
            true
        }
        AUTH_FAIL -> {
            throw AuthException("auth failed for user '$username'")
        }
        else -> {
            throw AuthException("unexpected auth response: 0x${resp.toUByte().toString(16)}")
        }
    }
}

// ── Crypto helpers ───────────────────────────────────────────────────────

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hexToBytes(hex: String): ByteArray {
    check(hex.length == 64) { "SHA-256 hex must be 64 chars, got ${hex.length}" }
    return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
