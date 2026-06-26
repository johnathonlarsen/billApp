package com.family.bankapp.update

data class AppDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            ((bytesDownloaded * 100) / it).toInt().coerceIn(0, 100)
        }

    val label: String
        get() {
            val downloaded = formatMegabytes(bytesDownloaded)
            val total = totalBytes?.takeIf { it > 0 }?.let { formatMegabytes(it) }
            return if (total != null) "$downloaded / $total MB" else "$downloaded MB downloaded"
        }

    private fun formatMegabytes(bytes: Long): String =
        String.format("%.1f", bytes / (1024.0 * 1024.0))
}
