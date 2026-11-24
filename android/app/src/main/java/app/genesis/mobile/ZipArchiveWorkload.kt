package app.genesis.mobile

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded in-memory ZIP creation. No filesystem, shell, network, or path-based host access. */
object ZipArchiveWorkload {
    private const val MAX_ENTRIES = 16
    private const val MAX_TOTAL_BYTES = 32 * 1024
    private const val MAX_PATH_BYTES = 128
    private const val MAX_ARCHIVE_BYTES = 128 * 1024
    private data class InputFile(val path: String, val bytes: ByteArray)

    private fun checkStop(shouldStop: () -> Boolean) {
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
    }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || '\\' in path || ':' in path ||
            path.any { it.code < 0x20 || it.code == 0x7f }) return false
        val parts = path.split("/")
        return parts.none { it.isEmpty() || it == "." || it == ".." } &&
            path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES
    }

    fun execute(payload: JSONObject, shouldStop: () -> Boolean = { false }): JSONObject {
        require(payload.keys().asSequence().all { it == "files" }) { "ZIP payload contains unsupported fields" }
        val supplied = payload.getJSONArray("files")
        require(supplied.length() in 1..MAX_ENTRIES) { "ZIP must contain 1 to $MAX_ENTRIES files" }
        val seen = HashSet<String>()
        val files = ArrayList<InputFile>(supplied.length())
        var totalBytes = 0
        for (index in 0 until supplied.length()) {
            checkStop(shouldStop)
            val item = supplied.getJSONObject(index)
            require(item.keys().asSequence().all { it == "path" || it == "contentBase64" } && item.length() == 2) {
                "Invalid ZIP file entry at index $index"
            }
            val path = item.optString("path", "")
            require(safePath(path)) { "Invalid ZIP entry path at index $index" }
            require(seen.add(path)) { "Duplicate ZIP entry path: $path" }
            val encoded = item.optString("contentBase64", "")
            val bytes = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid base64 content for $path") }
            require(Base64.getEncoder().encodeToString(bytes) == encoded) {
                "ZIP file content must use canonical base64: $path"
            }
            totalBytes += bytes.size
            require(totalBytes <= MAX_TOTAL_BYTES) { "ZIP input exceeds the $MAX_TOTAL_BYTES-byte uncompressed limit" }
            files += InputFile(path, bytes)
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            for (file in files) {
                checkStop(shouldStop)
                zip.putNextEntry(ZipEntry(file.path))
                var offset = 0
                while (offset < file.bytes.size) {
                    checkStop(shouldStop)
                    val count = minOf(4096, file.bytes.size - offset)
                    zip.write(file.bytes, offset, count)
                    offset += count
                }
                zip.closeEntry()
            }
        }
        val archive = output.toByteArray()
        require(archive.isNotEmpty() && archive.size <= MAX_ARCHIVE_BYTES) { "Generated ZIP archive exceeds its size limit" }
        val manifest = JSONArray()
        files.forEach { file ->
            manifest.put(JSONObject().put("path", file.path).put("sizeBytes", file.bytes.size).put("sha256", sha256(file.bytes)))
        }
        return JSONObject()
            .put("archiveBase64", Base64.getEncoder().encodeToString(archive))
            .put("archiveBytes", archive.size).put("sha256", sha256(archive))
            .put("entryCount", files.size).put("entries", manifest)
    }
}
