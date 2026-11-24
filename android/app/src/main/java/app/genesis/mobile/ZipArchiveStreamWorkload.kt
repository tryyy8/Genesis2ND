package app.genesis.mobile

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Single-session, RAM-only streaming ZIP builder for bounded release artifacts. */
object ZipArchiveStreamWorkload {
    private const val MAX_TOTAL_BYTES = 1024 * 1024
    private const val MAX_CHUNK_BYTES = 32 * 1024
    private const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
    private const val MAX_PATH_BYTES = 128
    private const val TTL_MS = 20 * 60 * 1000L

    private class SecureBuffer : ByteArrayOutputStream() {
        fun copyRange(offset: Int, length: Int): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= count)
            return buf.copyOfRange(offset, offset + length)
        }
        fun digest(): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(buf.copyOf(count))
            return hash.joinToString("") { "%02x".format(it) }
        }
        fun wipe() { buf.fill(0); reset() }
    }

    private data class Stage(
        val id: String, val path: String, val totalBytes: Int, val sourceSha256: String,
        val output: SecureBuffer, val sourceDigest: MessageDigest, var zip: ZipOutputStream?,
        var receivedBytes: Int = 0, var archiveSha256: String? = null, var expiresAt: Long
    )
    @Volatile private var active: Stage? = null

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || '\\' in path || ':' in path ||
            path.any { it.code < 0x20 || it.code == 0x7f }) return false
        val parts = path.split("/")
        return parts.none { it.isEmpty() || it == "." || it == ".." } && path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES
    }
    private fun requireKeys(payload: JSONObject, allowed: Set<String>) {
        require(payload.keys().asSequence().all { it in allowed }) { "ZIP stream payload contains unsupported fields" }
    }
    private fun clear(stage: Stage) {
        try { stage.zip?.close() } catch (_: Exception) {}
        stage.zip = null
        stage.output.wipe()
        active = null
    }
    private fun stage(stageId: String): Stage {
        val current = active ?: throw IllegalStateException("ZIP stream staging session is unavailable")
        if (System.nanoTime() / 1_000_000L > current.expiresAt) {
            clear(current)
            throw IllegalStateException("ZIP stream staging session expired")
        }
        require(current.id == stageId) { "ZIP stream staging ID does not match" }
        current.expiresAt = System.nanoTime() / 1_000_000L + TTL_MS
        return current
    }

    @Synchronized
    fun execute(payload: JSONObject, shouldStop: () -> Boolean = { false }): JSONObject {
        val action = payload.optString("action", "")
        fun checkStop() { if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED") }
        when (action) {
            "begin" -> {
                requireKeys(payload, setOf("action","stageId","path","totalBytes","sourceSha256"))
                val id=payload.optString("stageId",""); val path=payload.optString("path","")
                val total=payload.optInt("totalBytes",-1); val sourceHash=payload.optString("sourceSha256","")
                require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid ZIP stream staging ID" }
                require(safePath(path)) { "Invalid ZIP stream entry path" }
                require(total in 1..MAX_TOTAL_BYTES) { "ZIP stream input exceeds the 1 MiB limit" }
                require(sourceHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid ZIP stream source SHA-256" }
                val old=active
                if(old!=null && System.nanoTime()/1_000_000L>old.expiresAt) clear(old)
                val existing=active
                if(existing!=null) {
                    require(existing.id==id && existing.path==path && existing.totalBytes==total &&
                        existing.sourceSha256==sourceHash && existing.zip!=null) { "Another ZIP stream is already active" }
                    return JSONObject().put("status","receiving").put("stageId",id).put("path",path)
                        .put("totalBytes",total).put("receivedBytes",existing.receivedBytes)
                }
                val output=SecureBuffer()
                val zip=ZipOutputStream(output).apply { setLevel(Deflater.DEFAULT_COMPRESSION); putNextEntry(ZipEntry(path)) }
                val now=System.nanoTime()/1_000_000L
                active=Stage(id,path,total,sourceHash,output,MessageDigest.getInstance("SHA-256"),zip,expiresAt=now+TTL_MS)
                return JSONObject().put("status","receiving").put("stageId",id).put("path",path)
                    .put("totalBytes",total).put("receivedBytes",0)
            }
            "append" -> {
                requireKeys(payload,setOf("action","stageId","offset","chunkBase64","chunkSha256"))
                val current=stage(payload.optString("stageId",""));checkStop()
                val offset=payload.optInt("offset",-1);val encoded=payload.optString("chunkBase64","")
                val bytes=try{Base64.getDecoder().decode(encoded)}catch(_:IllegalArgumentException){throw IllegalArgumentException("Invalid ZIP stream chunk base64")}
                require(bytes.isNotEmpty()&&bytes.size<=MAX_CHUNK_BYTES&&Base64.getEncoder().encodeToString(bytes)==encoded) { "ZIP stream chunk must be canonical base64 and at most 32 KiB" }
                require(offset==current.receivedBytes) { "ZIP stream chunks must be sequential and non-overlapping" }
                require(offset+bytes.size<=current.totalBytes) { "ZIP stream chunk exceeds declared input size" }
                require(sha256(bytes)==payload.optString("chunkSha256","")) { "ZIP stream chunk SHA-256 mismatch" }
                current.zip?.write(bytes) ?: throw IllegalStateException("ZIP stream is already finalized")
                current.sourceDigest.update(bytes);current.receivedBytes+=bytes.size;checkStop()
                return JSONObject().put("status","receiving").put("stageId",current.id).put("receivedBytes",current.receivedBytes)
            }
            "finalize" -> {
                requireKeys(payload,setOf("action","stageId"))
                val current=stage(payload.optString("stageId",""));checkStop()
                require(current.receivedBytes==current.totalBytes) { "ZIP stream source is incomplete" }
                val sourceHash=current.sourceDigest.digest().joinToString("") { "%02x".format(it) }
                require(sourceHash==current.sourceSha256) { "ZIP stream source SHA-256 mismatch" }
                current.zip?.closeEntry();current.zip?.close();current.zip=null
                require(current.output.size() in 22..MAX_ARCHIVE_BYTES) { "Generated ZIP exceeds the 2 MiB archive limit" }
                current.archiveSha256=current.output.digest()
                val entries=JSONArray().put(JSONObject().put("path",current.path).put("sizeBytes",current.totalBytes).put("sha256",current.sourceSha256))
                return JSONObject().put("status","ready").put("stageId",current.id).put("archiveBytes",current.output.size())
                    .put("archiveSha256",current.archiveSha256).put("entryCount",1).put("entries",entries).put("chunkSize",MAX_CHUNK_BYTES)
            }
            "read" -> {
                requireKeys(payload,setOf("action","stageId","offset","length"))
                val current=stage(payload.optString("stageId",""));checkStop()
                require(current.zip==null&&current.archiveSha256!=null) { "ZIP stream is not finalized" }
                val offset=payload.optInt("offset",-1);val length=payload.optInt("length",-1)
                require(offset>=0&&offset<current.output.size()&&length in 1..MAX_CHUNK_BYTES) { "Invalid ZIP stream read range" }
                val bytes=current.output.copyRange(offset,minOf(length,current.output.size()-offset))
                return JSONObject().put("status","ready").put("stageId",current.id).put("offset",offset)
                    .put("chunkBytes",bytes.size).put("chunkBase64",Base64.getEncoder().encodeToString(bytes))
                    .put("chunkSha256",sha256(bytes)).put("archiveBytes",current.output.size()).put("archiveSha256",current.archiveSha256)
            }
            "cleanup" -> {
                requireKeys(payload,setOf("action","stageId"))
                val id=payload.optString("stageId","");val current=active
                if(current==null)return JSONObject().put("status","cleaned").put("stageId",id)
                require(current.id==id) { "ZIP stream staging ID does not match" }
                clear(current)
                return JSONObject().put("status","cleaned").put("stageId",id)
            }
            else -> throw IllegalArgumentException("Unsupported ZIP stream action")
        }
    }
}
