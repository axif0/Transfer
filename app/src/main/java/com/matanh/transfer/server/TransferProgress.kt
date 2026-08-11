package com.matanh.transfer.server

/**
 * In-flight upload/download progress for notification + MainActivity.
 * [totalBytes] null → indeterminate (e.g. some multipart uploads).
 */
data class TransferProgress(
    val id: String,
    val fileName: String,
    val direction: Direction,
    val bytesTransferred: Long,
    val totalBytes: Long?,
) {
    enum class Direction { DOWNLOAD, UPLOAD }

    val percent: Int?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return ((bytesTransferred * 100) / total).toInt().coerceIn(0, 100)
        }
}
