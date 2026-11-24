package app.genesis.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream

class AndroidBuildPackageWorkloadTest {
    private fun u16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or (bytes[offset + 3].toInt() shl 24)

    @Test fun emitsBinaryManifestAndDexWithoutClaimingSignature() {
        val dex = ByteArray(128)
        dex[0] = 'd'.code.toByte(); dex[1] = 'e'.code.toByte()
        dex[2] = 'x'.code.toByte(); dex[3] = 10
        val dexHash = MessageDigest.getInstance("SHA-256").digest(dex).joinToString("") { "%02x".format(it) }
        val request = JSONObject().put("profileId", "genb-analytics-java-webview-v1")
            .put("applicationId", "com.genb.analytics").put("versionCode", 1)
            .put("versionName", "0.1.0").put("minSdk", 26).put("targetSdk", 35)
            .put("toolchainVersion", "android-api-35-r02").put("androidJarSha256", "a".repeat(64))
            .put("sourceSha256", "c".repeat(64))
            .put("dexSha256", dexHash).put("classesDexBase64", Base64.getEncoder().encodeToString(dex))
        val result = AndroidBuildPackageWorkload.execute(request)
        assertEquals("unsigned-apk-ready", result.getString("status"))
        assertTrue(!result.getBoolean("signed"))
        val apk = Base64.getDecoder().decode(result.getString("apkBase64"))
        assertEquals(apk.size, result.getInt("apkBytes"))
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val manifest = entries["AndroidManifest.xml"]!!
        assertEquals(0x0003, (manifest[0].toInt() and 0xff) or ((manifest[1].toInt() and 0xff) shl 8))
        assertEquals(8, (manifest[2].toInt() and 0xff) or ((manifest[3].toInt() and 0xff) shl 8))
        assertTrue(manifest.size > 100)
        assertEquals(manifest.size, u32(manifest, 4))
        var offset = 8
        val chunkTypes = ArrayList<Int>()
        while (offset < manifest.size) {
            val type = u16(manifest, offset)
            val headerSize = u16(manifest, offset + 2)
            val chunkSize = u32(manifest, offset + 4)
            assertTrue(headerSize >= 8)
            assertTrue(chunkSize >= headerSize)
            assertTrue(offset + chunkSize <= manifest.size)
            chunkTypes.add(type)
            offset += chunkSize
        }
        assertEquals(manifest.size, offset)
        assertEquals(0x0001, chunkTypes.first())
        assertEquals(0x0180, chunkTypes[1])
        assertEquals(0x0100, chunkTypes[2])
        assertEquals(0x0101, chunkTypes.last())
        assertEquals(8, chunkTypes.count { it == 0x0102 })
        assertEquals(8, chunkTypes.count { it == 0x0103 })
        assertTrue(entries["classes.dex"]!!.contentEquals(dex))
        val attestation = JSONObject(entries["META-INF/genesis-build.json"]!!.toString(Charsets.UTF_8))
        assertEquals("genb-analytics-java-webview-v1", attestation.getString("profileId"))
        assertEquals("c".repeat(64), attestation.getString("sourceSha256"))
        assertEquals(dexHash, attestation.getString("dexSha256"))
    }

    @Test fun rejectsTamperedDexDigest() {
        val request = JSONObject().put("profileId", "genb-analytics-java-webview-v1")
            .put("applicationId", "com.genb.analytics").put("versionCode", 1).put("versionName", "0.1.0")
            .put("minSdk", 26).put("targetSdk", 35).put("toolchainVersion", "android-api-35-r02")
            .put("androidJarSha256", "a".repeat(64)).put("sourceSha256", "c".repeat(64)).put("dexSha256", "b".repeat(64))
            .put("classesDexBase64", Base64.getEncoder().encodeToString(byteArrayOf(100, 101, 120, 10) + ByteArray(124)))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AndroidBuildPackageWorkload.execute(request)
        }
    }
}
