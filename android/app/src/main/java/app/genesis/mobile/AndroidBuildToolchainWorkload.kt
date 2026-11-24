package app.genesis.mobile

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Receives one pinned android.jar in bounded, resumable chunks into Genesis-private storage. */
object AndroidBuildToolchainWorkload {
    private const val VERSION = "android-api-35-r02"
    private const val ARCHIVE_URL = "https://dl.google.com/android/repository/platform-35_r02.zip"
    private const val ARCHIVE_BYTES = 64273788L
    private const val ARCHIVE_SHA256 = "0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0"
    private const val DOWNLOAD_CHUNK_BYTES = 1024 * 1024
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_CHUNK = 24 * 1024

    private fun dir(root: File) = File(root, "android-build-toolchain").apply { mkdirs() }
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun execute(payload: JSONObject, appFilesDir: File?, shouldStop: () -> Boolean = { false }): JSONObject {
        require(appFilesDir != null) { "Toolchain installation requires Genesis app-private storage" }
        val action = payload.optString("action")
        val directory = dir(appFilesDir)
        val part = File(directory, "android.jar.part")
        val metadata = File(directory, "android.jar.transfer.json")
        when (action) {
            "download-begin" -> {
                require(payload.length() == 1) { "Pinned toolchain download accepts no caller-supplied URL or digest" }
                val archive = File(directory, "platform-35.zip.part")
                val downloadMeta = File(directory, "platform-35.download.json")
                if (downloadMeta.isFile && archive.isFile) {
                    val old = JSONObject(downloadMeta.readText())
                    require(old.optString("sha256") == ARCHIVE_SHA256 && old.optLong("totalBytes") == ARCHIVE_BYTES) {
                        "A different pinned Android platform download is staged"
                    }
                    return JSONObject().put("status", "downloading").put("receivedBytes", archive.length())
                }
                archive.delete()
                archive.createNewFile()
                downloadMeta.writeText(JSONObject().put("version", VERSION).put("url", ARCHIVE_URL)
                    .put("sha256", ARCHIVE_SHA256).put("totalBytes", ARCHIVE_BYTES).toString())
                return JSONObject().put("status", "downloading").put("receivedBytes", 0)
            }
            "download-chunk" -> {
                require(payload.keys().asSequence().all { it == "action" || it == "offset" }) {
                    "Pinned toolchain download chunk accepts only its offset"
                }
                if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
                val archive = File(directory, "platform-35.zip.part")
                val downloadMeta = File(directory, "platform-35.download.json")
                require(archive.isFile && downloadMeta.isFile) { "Pinned Android platform download is not initialized" }
                val offset = payload.optLong("offset", -1)
                require(offset == archive.length() && offset in 0 until ARCHIVE_BYTES) {
                    "Android platform download chunks must be sequential"
                }
                val count = minOf(DOWNLOAD_CHUNK_BYTES.toLong(), ARCHIVE_BYTES - offset).toInt()
                val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 12000
                    connection.readTimeout = 25000
                    connection.setRequestProperty("Range", "bytes=$offset-${offset + count - 1}")
                    val code = connection.responseCode
                    require(code == 206) { "Google Android platform server did not honor the pinned byte range (HTTP $code)" }
                    val expectedRange = "bytes $offset-${offset + count - 1}/$ARCHIVE_BYTES"
                    require(connection.getHeaderField("Content-Range") == expectedRange) {
                        "Android platform download returned an unexpected byte range"
                    }
                    val bytes = connection.inputStream.use { it.readBytes() }
                    require(bytes.size == count) { "Android platform download chunk length mismatch" }
                    RandomAccessFile(archive, "rw").use { file -> file.seek(offset); file.write(bytes) }
                } finally {
                    connection.disconnect()
                }
                return JSONObject().put("status", "downloading").put("receivedBytes", archive.length())
                    .put("totalBytes", ARCHIVE_BYTES)
            }
            "download-finalize" -> {
                require(payload.length() == 1) { "Pinned toolchain finalization accepts no extra fields" }
                if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
                val archive = File(directory, "platform-35.zip.part")
                val downloadMeta = File(directory, "platform-35.download.json")
                require(archive.isFile && downloadMeta.isFile && archive.length() == ARCHIVE_BYTES) {
                    "Pinned Android platform archive download is incomplete"
                }
                if (digest(archive) != ARCHIVE_SHA256) {
                    archive.delete()
                    downloadMeta.delete()
                    throw IllegalStateException("Pinned Android platform archive SHA-256 mismatch; staged download was discarded")
                }
                val extracted = File(directory, "android.jar.extract")
                var found = false
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "android-35/android.jar" && !entry.isDirectory) {
                            extracted.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    require(total <= MAX_BYTES) { "Android platform android.jar exceeds the approved size" }
                                    output.write(buffer, 0, read)
                                }
                            }
                            found = true
                        }
                        zip.closeEntry()
                    }
                }
                require(found && extracted.isFile && extracted.length() in 1024..MAX_BYTES.toLong()) {
                    "Pinned Android platform archive did not contain the expected android.jar"
                }
                val target = File(directory, "android.jar")
                require(!target.exists() || target.delete()) { "Unable to replace prior Android toolchain" }
                require(extracted.renameTo(target)) { "Unable to activate Android toolchain" }
                File(directory, "android.jar.json").writeText(JSONObject().put("version", VERSION)
                    .put("sha256", digest(target)).put("sizeBytes", target.length())
                    .put("archiveSha256", ARCHIVE_SHA256).toString())
                archive.delete()
                downloadMeta.delete()
                return JSONObject().put("status", "installed").put("version", VERSION)
                    .put("sha256", digest(target)).put("sizeBytes", target.length())
                    .put("archiveSha256", ARCHIVE_SHA256)
            }
            "begin" -> {
                require(payload.keys().asSequence().all { it in setOf("action", "version", "totalBytes", "sha256") })
                require(payload.optString("version") == VERSION) { "Unsupported Android toolchain version" }
                val total = payload.optInt("totalBytes", -1)
                val hash = payload.optString("sha256")
                require(total in 1024..MAX_BYTES && hash.matches(Regex("[a-f0-9]{64}"))) {
                    "Invalid Android platform archive declaration"
                }
                if (metadata.exists() && part.exists()) {
                    val old = JSONObject(metadata.readText())
                    require(old.optString("version") == VERSION && old.optString("sha256") == hash &&
                        old.optInt("totalBytes") == total) { "A different toolchain transfer is already staged" }
                    return JSONObject().put("status", "receiving").put("receivedBytes", part.length())
                }
                part.delete()
                metadata.writeText(JSONObject().put("version", VERSION).put("sha256", hash)
                    .put("totalBytes", total).toString())
                part.createNewFile()
                return JSONObject().put("status", "receiving").put("receivedBytes", 0)
            }
            "append" -> {
                require(payload.keys().asSequence().all { it in setOf("action", "offset", "chunkBase64", "chunkSha256") })
                if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
                require(metadata.isFile && part.isFile) { "No Android toolchain transfer is active" }
                val meta = JSONObject(metadata.readText())
                val offset = payload.optLong("offset", -1)
                val encoded = payload.optString("chunkBase64")
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.isNotEmpty() && bytes.size <= MAX_CHUNK &&
                    Base64.getEncoder().encodeToString(bytes) == encoded) { "Invalid toolchain chunk encoding or size" }
                require(offset == part.length() && offset + bytes.size <= meta.getInt("totalBytes")) {
                    "Toolchain chunks must be sequential and within the declared archive size"
                }
                val chunkHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(chunkHash == payload.optString("chunkSha256")) { "Toolchain chunk digest mismatch" }
                RandomAccessFile(part, "rw").use { file -> file.seek(offset); file.write(bytes) }
                return JSONObject().put("status", "receiving").put("receivedBytes", part.length())
            }
            "finalize" -> {
                require(payload.keys().asSequence().all { it == "action" })
                if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
                require(metadata.isFile && part.isFile) { "No Android toolchain transfer is active" }
                val meta = JSONObject(metadata.readText())
                require(part.length() == meta.getLong("totalBytes")) { "Android toolchain transfer is incomplete" }
                require(digest(part) == meta.getString("sha256")) { "Android toolchain SHA-256 mismatch" }
                val target = File(directory, "android.jar")
                require(!target.exists() || target.delete()) { "Unable to replace prior Android toolchain" }
                require(part.renameTo(target)) { "Unable to activate Android toolchain" }
                File(directory, "android.jar.json").writeText(JSONObject().put("version", VERSION)
                    .put("sha256", meta.getString("sha256")).put("sizeBytes", target.length()).toString())
                metadata.delete()
                return JSONObject().put("status", "installed").put("version", VERSION)
                    .put("sha256", meta.getString("sha256")).put("sizeBytes", target.length())
            }
            "status" -> {
                require(payload.length() == 1) { "Toolchain status accepts no extra fields" }
                val active = metadata.isFile && part.isFile
                val installedJar = File(directory, "android.jar")
                val installedMetaFile = File(directory, "android.jar.json")
                var installedMeta: JSONObject? = null
                var installed = false
                if (installedJar.isFile && installedMetaFile.isFile) {
                    try {
                        installedMeta = JSONObject(installedMetaFile.readText())
                        installed = installedMeta.optString("version") == VERSION &&
                            installedMeta.optString("archiveSha256") == ARCHIVE_SHA256 &&
                            installedMeta.optLong("sizeBytes", -1) == installedJar.length() &&
                            installedMeta.optString("sha256").matches(Regex("[a-f0-9]{64}")) &&
                            digest(installedJar) == installedMeta.optString("sha256")
                    } catch (_: Exception) { installed = false }
                }
                return JSONObject().put("transferActive", active).put("receivedBytes", if (active) part.length() else 0)
                    .put("installed", installed)
                    .put("version", if (installed) VERSION else JSONObject.NULL)
                    .put("sha256", if (installed) installedMeta!!.getString("sha256") else JSONObject.NULL)
                    .put("archiveSha256", if (installed) ARCHIVE_SHA256 else JSONObject.NULL)
            }
            "cleanup" -> {
                require(payload.length() == 1) { "Toolchain cleanup accepts no extra fields" }
                part.delete(); metadata.delete()
                File(directory, "platform-35.zip.part").delete()
                File(directory, "platform-35.download.json").delete()
                File(directory, "android.jar.extract").delete()
                return JSONObject().put("status", "transfer-cleaned")
            }
            else -> throw IllegalArgumentException("Unsupported Android toolchain action")
        }
    }
}
