package com.matanh.transfer.server

data class ByteRange(val start: Long, val endInclusive: Long) {
    fun contentLength(): Long = endInclusive - start + 1
}

fun etagFor(length: Long, lastModified: Long): String = "\"$length-$lastModified\""

// ponytail: single range only; multi-range (RFC 7233) not implemented
fun parseRangeHeader(rangeHeader: String?, fileSize: Long): ByteRange? {
    if (rangeHeader.isNullOrBlank() || fileSize <= 0L) return null
    if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null
    val spec = rangeHeader.substringAfter('=').trim()
    if (spec.isEmpty() || spec.contains(',')) return null

    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startPart = spec.substring(0, dash)
    val endPart = spec.substring(dash + 1)

    return when {
        startPart.isEmpty() -> {
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            val start = maxOf(0L, fileSize - suffix)
            ByteRange(start, fileSize - 1)
        }
        endPart.isEmpty() -> {
            val start = startPart.toLongOrNull() ?: return null
            if (start >= fileSize) return null
            ByteRange(start, fileSize - 1)
        }
        else -> {
            val start = startPart.toLongOrNull() ?: return null
            val end = endPart.toLongOrNull() ?: return null
            if (start > end || start >= fileSize) return null
            ByteRange(start, minOf(end, fileSize - 1))
        }
    }
}
