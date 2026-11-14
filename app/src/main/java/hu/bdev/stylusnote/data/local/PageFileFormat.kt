package hu.bdev.stylusnote.data.local

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Container header metadata written at byte offset 0 of every `page.snote` snapshot and
 * `page.journal` append-only log (Section 9.2).
 */
data class SnotFileHeader(
    val formatVersion: Int = PageFileFormat.CURRENT_FORMAT_VERSION,
    val minReaderVersion: Int = PageFileFormat.MIN_READER_VERSION,
    val inkVersion: String = PageFileFormat.INK_VERSION_STRING,
    val flags: Int = 0
)

/**
 * Strongly typed record markers inside `SNOT` containers.
 * Unknown future codes remain safe to skip whenever their CRC32 matches.
 */
enum class SnotRecordType(val code: Int) {
    STROKE_SNAPSHOT(0x01),
    OBJECT_SNAPSHOT(0x02),
    ADD_STROKE(0x10),
    REMOVE_STROKE(0x11),
    TRANSFORM_STROKE(0x12),
    RECOLOR_STROKE(0x13),
    ADD_OBJECT(0x20),
    REMOVE_OBJECT(0x21),
    UPDATE_OBJECT(0x22);

    companion object {
        fun fromCode(code: Int): SnotRecordType? = entries.firstOrNull { it.code == code }
    }
}

data class SnotFrame(
    val rawTypeCode: Int,
    val payload: ByteArray
) {
    val knownType: SnotRecordType?
        get() = SnotRecordType.fromCode(rawTypeCode)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnotFrame) return false
        return rawTypeCode == other.rawTypeCode && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * rawTypeCode + payload.contentHashCode()
}

data class SnotFrameReadResult(
    val header: SnotFileHeader,
    val frames: List<SnotFrame>,
    val discardedCorruptRecords: Int,
    val skippedUnknownRecords: Int
)

/**
 * Binary framing and CRC32 integrity protocol for `pages/<pageId>/page.snote` and
 * `pages/<pageId>/page.journal` (Section 9.1 – 9.2).
 *
 * File header:
 * - Magic: `"SNOT"` (`0x53, 0x4E, 0x4F, 0x54`)
 * - `formatVersion`: unsigned 16-bit big-endian
 * - `minReaderVersion`: unsigned 16-bit big-endian
 * - `flags`: 32-bit int (`FLAG_SNAPSHOT`, `FLAG_JOURNAL`)
 * - `inkVersion`: length-prefixed UTF-8 string (e.g. `"1.1.0-alpha10"`)
 *
 * Each record frame:
 * - `recordType`: unsigned 8-bit code
 * - `payloadLength`: signed 32-bit int (>= 0)
 * - `crc32`: unsigned 32-bit CRC32 computed over `[recordType] + payload`
 * - `payload`: exact `payloadLength` bytes
 */
object PageFileFormat {
    val MAGIC_BYTES: ByteArray = byteArrayOf(0x53, 0x4E, 0x4F, 0x54) // "SNOT"
    const val CURRENT_FORMAT_VERSION: Int = 1
    const val MIN_READER_VERSION: Int = 1
    const val INK_VERSION_STRING: String = "1.1.0-alpha10"

    const val FLAG_SNAPSHOT: Int = 0x01
    const val FLAG_JOURNAL: Int = 0x02

    private const val MAX_REASONABLE_PAYLOAD_BYTES: Int = 64 * 1024 * 1024 // 64 MiB safety ceiling

    fun writeHeader(output: OutputStream, header: SnotFileHeader) {
        val dataOut = DataOutputStream(output)
        dataOut.write(MAGIC_BYTES)
        dataOut.writeShort(header.formatVersion and 0xFFFF)
        dataOut.writeShort(header.minReaderVersion and 0xFFFF)
        dataOut.writeInt(header.flags)
        val versionBytes = header.inkVersion.toByteArray(Charsets.UTF_8)
        dataOut.writeShort(versionBytes.size.coerceAtMost(0xFFFF))
        dataOut.write(versionBytes, 0, versionBytes.size.coerceAtMost(0xFFFF))
        dataOut.flush()
    }

    fun readHeader(input: InputStream): SnotFileHeader {
        val dataIn = DataInputStream(input)
        val magic = ByteArray(MAGIC_BYTES.size)
        dataIn.readFully(magic)
        if (!magic.contentEquals(MAGIC_BYTES)) {
            throw IOException("Invalid SNOT magic header")
        }
        val formatVersion = dataIn.readUnsignedShort()
        val minReaderVersion = dataIn.readUnsignedShort()
        val flags = dataIn.readInt()
        val inkLen = dataIn.readUnsignedShort()
        val inkBytes = ByteArray(inkLen)
        dataIn.readFully(inkBytes)
        return SnotFileHeader(
            formatVersion = formatVersion,
            minReaderVersion = minReaderVersion,
            inkVersion = String(inkBytes, Charsets.UTF_8),
            flags = flags
        )
    }

    fun encodeFrameBytes(type: SnotRecordType, payload: ByteArray): ByteArray =
        encodeFrameBytes(type.code, payload)

    fun encodeFrameBytes(rawTypeCode: Int, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(1 + 4 + 4 + payload.size)
        val dataOut = DataOutputStream(baos)
        val crc = computeRecordCrc32(rawTypeCode, payload)
        dataOut.writeByte(rawTypeCode and 0xFF)
        dataOut.writeInt(payload.size)
        dataOut.writeInt(crc.toInt())
        dataOut.write(payload)
        dataOut.flush()
        return baos.toByteArray()
    }

    fun writeFrame(output: OutputStream, type: SnotRecordType, payload: ByteArray) {
        output.write(encodeFrameBytes(type, payload))
    }

    /**
     * Streaming-decodes header + all valid CRC32-verified frames from [input].
     *
     * Per Section 9.3 recovery rules, if a crash left a truncated tail record or a record whose
     * CRC32 does not match, that corrupt tail is discarded cleanly rather than failing page load.
     */
    fun readAllValidFrames(
        input: InputStream,
        stopOnCorruptTail: Boolean = true
    ): SnotFrameReadResult {
        val header = readHeader(input)
        val dataIn = DataInputStream(input)
        val frames = ArrayList<SnotFrame>()
        var discardedCorrupt = 0
        var skippedUnknown = 0

        while (true) {
            val rawType = input.read()
            if (rawType == -1) {
                break
            }
            try {
                val payloadLen = dataIn.readInt()
                if (payloadLen < 0 || payloadLen > MAX_REASONABLE_PAYLOAD_BYTES) {
                    discardedCorrupt++
                    break
                }
                val expectedCrc = dataIn.readInt().toLong() and 0xFFFFFFFFL
                val payload = ByteArray(payloadLen)
                dataIn.readFully(payload)

                val actualCrc = computeRecordCrc32(rawType, payload)
                if (actualCrc != expectedCrc) {
                    discardedCorrupt++
                    if (stopOnCorruptTail) break else continue
                }

                val frame = SnotFrame(rawTypeCode = rawType and 0xFF, payload = payload)
                if (frame.knownType == null) {
                    skippedUnknown++
                }
                frames.add(frame)
            } catch (_: EOFException) {
                // Truncated tail caused by process kill mid-write: drop the unfinished record safely.
                discardedCorrupt++
                break
            }
        }

        return SnotFrameReadResult(
            header = header,
            frames = frames,
            discardedCorruptRecords = discardedCorrupt,
            skippedUnknownRecords = skippedUnknown
        )
    }

    fun computeRecordCrc32(rawTypeCode: Int, payload: ByteArray): Long {
        val crc = CRC32()
        crc.update(rawTypeCode and 0xFF)
        crc.update(payload)
        return crc.value
    }
}

