package app.genesis.mobile

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal binary AndroidManifest.xml + classes.dex APK payload for the fixed GenB profile. */
object AndroidBuildPackageWorkload {
    private const val PROFILE = "genb-analytics-java-webview-v1"
    private const val MAX_DEX_BYTES = 640 * 1024
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private data class Attr(val name: String, val value: String, val type: Int = 0x03, val data: Int = -1)
    private data class Element(val name: String, val attrs: List<Attr> = emptyList(), val children: List<Element> = emptyList())

    private fun le16(out: DataOutputStream, value: Int) { out.writeByte(value and 0xff); out.writeByte((value ushr 8) and 0xff) }
    private fun le32(out: DataOutputStream, value: Int) = out.writeInt(Integer.reverseBytes(value))
    private fun length8(out: DataOutputStream, value: Int) {
        if (value > 0x7f) { out.writeByte((value shr 8) or 0x80); out.writeByte(value and 0xff) }
        else out.writeByte(value)
    }
    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        strings.forEach { value ->
            offsets.add(data.size())
            val bytes = value.toByteArray(Charsets.UTF_8)
            length8(DataOutputStream(data), value.length)
            length8(DataOutputStream(data), bytes.size)
            data.write(bytes); data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val chunkSize = stringsStart + data.size()
        val outBytes = ByteArrayOutputStream()
        val out = DataOutputStream(outBytes)
        le16(out, 0x0001); le16(out, headerSize); le32(out, chunkSize)
        le32(out, strings.size); le32(out, 0); le32(out, 0x100); le32(out, stringsStart); le32(out, 0)
        offsets.forEach { le32(out, it) }
        out.write(data.toByteArray()); out.flush()
        return outBytes.toByteArray()
    }
    private fun chunk(type: Int, headerSize: Int, body: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        le16(out, type); le16(out, headerSize); le32(out, headerSize + body.size); out.write(body); out.flush()
        return bytes.toByteArray()
    }
    private fun nodeBody(): Pair<ByteArrayOutputStream, DataOutputStream> {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        le32(out, 1); le32(out, -1)
        return bytes to out
    }
    private fun namespace(type: Int, prefix: Int, uri: Int): ByteArray {
        val (bytes, out) = nodeBody()
        le32(out, prefix); le32(out, uri)
        return chunk(type, 16, bytes.toByteArray())
    }
    private fun endElement(ns: Int, name: Int): ByteArray {
        val (bytes, out) = nodeBody()
        le32(out, ns); le32(out, name)
        return chunk(0x0103, 16, bytes.toByteArray())
    }
    private fun startElement(ns: Int, name: Int, attrs: List<Attr>, ids: Map<String, Int>): ByteArray {
        val (bytes, out) = nodeBody()
        le32(out, ns); le32(out, name)
        le16(out, 20); le16(out, 20); le16(out, attrs.size)
        le16(out, 0); le16(out, 0); le16(out, 0)
        attrs.forEach { attr ->
            le32(out, if (attr.name.startsWith("android:")) ids[ANDROID_NS] ?: -1 else -1)
            le32(out, ids[attr.name.substringAfter(':')] ?: error("Missing attribute string " + attr.name))
            le32(out, ids[attr.value] ?: -1)
            le16(out, 8); out.writeByte(0); out.writeByte(attr.type)
            le32(out, if (attr.type == 0x03) (ids[attr.value] ?: error("Missing value string " + attr.value)) else attr.data)
        }
        return chunk(0x0102, 16, bytes.toByteArray())
    }

    private fun binaryManifest(versionCode: Int, versionName: String, targetSdk: Int): ByteArray {
        val root = Element("manifest", listOf(
            Attr("package", "com.genb.analytics"),
            Attr("android:versionCode", versionCode.toString(), 0x10, versionCode),
            Attr("android:versionName", versionName)
        ), listOf(
            Element("uses-sdk", listOf(Attr("android:minSdkVersion", "26", 0x10, 26),
                Attr("android:targetSdkVersion", targetSdk.toString(), 0x10, targetSdk))),
            Element("uses-permission", listOf(Attr("android:name", "android.permission.INTERNET"))),
            Element("application", listOf(
                Attr("android:allowBackup", "false", 0x12, 0),
                Attr("android:label", "GenB Analytics"),
                Attr("android:usesCleartextTraffic", "false", 0x12, 0),
                Attr("android:supportsRtl", "true", 0x12, 1)
            ), listOf(
                Element("activity", listOf(Attr("android:name", ".MainActivity"),
                    Attr("android:exported", "true", 0x12, 1)), listOf(
                    Element("intent-filter", children = listOf(
                        Element("action", listOf(Attr("android:name", "android.intent.action.MAIN"))),
                        Element("category", listOf(Attr("android:name", "android.intent.category.LAUNCHER")))
                    ))
                ))
            ))
        ))
        val strings = linkedSetOf<String>()
        fun collect(element: Element) {
            strings.add(element.name)
            element.attrs.forEach { strings.add(it.name.substringAfter(':')); strings.add(it.value) }
            element.children.forEach { collect(it) }
        }
        strings.add("android"); strings.add(ANDROID_NS); collect(root)
        val list = strings.toList()
        val ids = list.withIndex().associate { it.value to it.index }
        val androidAttrIds = mapOf(
            "versionCode" to 0x0101021b, "versionName" to 0x0101021c,
            "minSdkVersion" to 0x0101020c, "targetSdkVersion" to 0x01010270,
            "name" to 0x01010003, "label" to 0x01010001,
            "allowBackup" to 0x01010280, "usesCleartextTraffic" to 0x010104ec,
            "supportsRtl" to 0x010103af, "exported" to 0x01010010
        )
        val map = ByteArrayOutputStream()
        val mapOut = DataOutputStream(map)
        list.forEach { le32(mapOut, androidAttrIds[it] ?: 0) }
        val xml = ByteArrayOutputStream()
        xml.write(stringPool(list))
        xml.write(chunk(0x0180, 8, map.toByteArray()))
        val ns = ids[ANDROID_NS]!!
        val prefix = ids["android"]!!
        xml.write(namespace(0x0100, prefix, ns))
        fun write(element: Element) {
            xml.write(startElement(-1,
                ids[element.name]!!, element.attrs, ids))
            element.children.forEach { write(it) }
            xml.write(endElement(-1, ids[element.name]!!))
        }
        write(root)
        xml.write(namespace(0x0101, prefix, ns))
        val body = xml.toByteArray()
        val result = ByteArrayOutputStream()
        val out = DataOutputStream(result)
        le16(out, 0x0003); le16(out, 8); le32(out, body.size + 8); out.write(body); out.flush()
        return result.toByteArray()
    }

    fun execute(payload: JSONObject, shouldStop: () -> Boolean = { false }): JSONObject {
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
        require(payload.keys().asSequence().all { it in setOf("profileId", "applicationId", "versionCode",
            "versionName", "minSdk", "targetSdk", "classesDexBase64", "dexSha256", "sourceSha256", "toolchainVersion",
            "androidJarSha256") }) { "APK package request contains unsupported fields" }
        require(payload.optString("profileId") == PROFILE && payload.optString("applicationId") == "com.genb.analytics") {
            "Unsupported APK package profile"
        }
        require(payload.optInt("minSdk", -1) == 26 && payload.optInt("targetSdk", -1) in 26..35) {
            "Unsupported APK SDK range"
        }
        val versionCode = payload.optInt("versionCode", -1)
        val versionName = payload.optString("versionName")
        require(versionCode in 1..1000000 && versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) {
            "Invalid APK version metadata"
        }
        require(payload.optString("toolchainVersion") == "android-api-35-r02" &&
            payload.optString("androidJarSha256").matches(Regex("[a-f0-9]{64}")) &&
            payload.optString("sourceSha256").matches(Regex("[a-f0-9]{64}"))) {
            "APK package source or toolchain attestation is invalid"
        }
        val encoded = payload.optString("classesDexBase64")
        val dex = Base64.getDecoder().decode(encoded)
        require(dex.size in 112..MAX_DEX_BYTES && Base64.getEncoder().encodeToString(dex) == encoded) {
            "DEX payload is invalid or exceeds the package limit"
        }
        val dexHash = MessageDigest.getInstance("SHA-256").digest(dex).joinToString("") { "%02x".format(it) }
        require(dexHash == payload.optString("dexSha256")) { "DEX payload digest mismatch" }
        require(dex.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "dex\n") { "Input is not a DEX file" }
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")

        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(binaryManifest(versionCode, versionName, payload.getInt("targetSdk")))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/genesis-build.json"))
            zip.write(JSONObject().put("profileId", PROFILE).put("applicationId", "com.genb.analytics")
                .put("versionCode", versionCode).put("versionName", versionName)
                .put("sourceSha256", payload.getString("sourceSha256"))
                .put("dexSha256", payload.getString("dexSha256"))
                .put("toolchainVersion", payload.getString("toolchainVersion"))
                .put("androidJarSha256", payload.getString("androidJarSha256"))
                .toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex)
            zip.closeEntry()
        }
        val apk = archive.toByteArray()
        require(apk.size <= 700 * 1024) { "Unsigned APK exceeds the approved transfer limit" }
        return JSONObject().put("status", "unsigned-apk-ready").put("applicationId", "com.genb.analytics")
            .put("versionCode", versionCode).put("versionName", versionName).put("minSdk", 26)
            .put("targetSdk", payload.getInt("targetSdk")).put("toolchainVersion", payload.getString("toolchainVersion"))
            .put("androidJarSha256", payload.getString("androidJarSha256"))
            .put("sourceSha256", payload.getString("sourceSha256")).put("dexSha256", dexHash)
            .put("apkBytes", apk.size)
            .put("apkSha256", MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) })
            .put("apkBase64", Base64.getEncoder().encodeToString(apk)).put("signed", false)
    }
}
