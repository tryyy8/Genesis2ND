package app.genesis.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

class AndroidBuildToolchainWorkloadTest {
    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun installsOnlySequentialDigestVerifiedChunks() {
        val root = Files.createTempDirectory("genesis-toolchain-test").toFile()
        try {
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val digest = hash(bytes)
            val begin = JSONObject().put("action", "begin").put("version", "android-api-35-r02")
                .put("totalBytes", bytes.size).put("sha256", digest)
            assertEquals("receiving", AndroidBuildToolchainWorkload.execute(begin, root).getString("status"))
            val first = bytes.copyOfRange(0, 2048)
            val bad = JSONObject().put("action", "append").put("offset", 0)
                .put("chunkBase64", Base64.getEncoder().encodeToString(first)).put("chunkSha256", "0".repeat(64))
            assertThrows(IllegalArgumentException::class.java) { AndroidBuildToolchainWorkload.execute(bad, root) }
            val goodFirst = JSONObject(bad.toString()).put("chunkSha256", hash(first))
            AndroidBuildToolchainWorkload.execute(goodFirst, root)
            val second = bytes.copyOfRange(2048, bytes.size)
            val appendSecond = JSONObject().put("action", "append").put("offset", 2048)
                .put("chunkBase64", Base64.getEncoder().encodeToString(second)).put("chunkSha256", hash(second))
            AndroidBuildToolchainWorkload.execute(appendSecond, root)
            val installed = AndroidBuildToolchainWorkload.execute(JSONObject().put("action", "finalize"), root)
            assertEquals("installed", installed.getString("status"))
            assertEquals(digest, installed.getString("sha256"))
            assertEquals(bytes.size.toLong(), File(root, "android-build-toolchain/android.jar").length())
        } finally {
            root.deleteRecursively()
        }
    }
}
