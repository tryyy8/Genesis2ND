package app.genesis.mobile

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.stream.Collectors

/** Compiles the fixed GenB Java profile and converts its class files to DEX on-device. */
object AndroidBuildCompileDexWorkload {
    private const val PROFILE = "genb-analytics-java-webview-v1"
    private const val TOOLCHAIN_VERSION = "android-api-35-r02"
    private const val MAX_DEX_BYTES = 640 * 1024
    private const val MAX_SOURCE_BYTES = 40 * 1024

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun execute(payload: JSONObject, appFilesDir: File?, shouldStop: () -> Boolean = { false }): JSONObject {
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
        require(appFilesDir != null) { "Android build requires Genesis app-private storage" }
        val allowed = setOf("profileId", "applicationId", "versionCode", "versionName", "minSdk",
            "targetSdk", "entryPoint", "manifest", "toolchainVersion", "androidJarSha256", "sourceSha256", "files")
        require(payload.keys().asSequence().all { it in allowed }) { "Android compile payload contains unsupported fields" }
        require(payload.optString("profileId") == PROFILE && payload.optString("applicationId") == "com.genb.analytics") {
            "Unsupported Android build profile"
        }
        require(payload.optString("entryPoint") == "com.genb.analytics.MainActivity" &&
            payload.optString("manifest") == "android/app/src/main/AndroidManifest.xml") {
            "Unexpected GenB entry point or manifest path"
        }
        require(payload.optInt("minSdk", -1) == 26 && payload.optInt("targetSdk", -1) in 26..35) {
            "Unsupported Android SDK range"
        }
        require(payload.optInt("versionCode", -1) in 1..1000000) { "Invalid versionCode" }
        require(payload.optString("versionName").matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) {
            "Invalid versionName"
        }
        require(payload.optString("toolchainVersion") == TOOLCHAIN_VERSION) { "Unsupported Android toolchain version" }
        val expectedJarHash = payload.optString("androidJarSha256")
        val sourceHash = payload.optString("sourceSha256")
        require(expectedJarHash.matches(Regex("[a-f0-9]{64}")) &&
            sourceHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid source or Android platform digest" }

        val toolchainDir = File(appFilesDir, "android-build-toolchain")
        val jar = File(toolchainDir, "android.jar")
        val metaFile = File(toolchainDir, "android.jar.json")
        require(jar.isFile && metaFile.isFile) { "Pinned Android API toolchain is not installed on this worker" }
        val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
        require(meta.optString("version") == TOOLCHAIN_VERSION && meta.optString("sha256") == expectedJarHash &&
            meta.optLong("sizeBytes", -1) == jar.length()) { "Installed Android toolchain metadata does not match this build" }
        require(sha256(jar) == expectedJarHash) { "Installed Android API archive failed SHA-256 verification" }

        val files = payload.optJSONArray("files") ?: throw IllegalArgumentException("Java source files are required")
        require(files.length() in 1..8) { "Java source bundle must contain 1 to 8 files" }
        val root = File(appFilesDir, "android-build-work").apply {
            deleteRecursively()
            require(mkdirs()) { "Unable to create private build workspace" }
        }
        try {
            val sourceRoot = File(root, "src")
            val classesDir = File(root, "classes")
            val dexDir = File(root, "dex")
            require(sourceRoot.mkdirs() && classesDir.mkdirs() && dexDir.mkdirs()) { "Unable to create build directories" }
            var sourceBytes = 0
            val sourceFiles = ArrayList<File>()
            val seen = HashSet<String>()
            var hasManifest = false
            var hasEntryPoint = false
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val orderedFiles = (0 until files.length()).map { files.getJSONObject(it) }.sortedBy { it.optString("path") }
            for (item in orderedFiles) {
                if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
                require(item.keys().asSequence().all { it == "path" || it == "contentBase64" }) {
                    "Source item contains unsupported fields"
                }
                val path = item.optString("path")
                val isManifest = path == "android/app/src/main/AndroidManifest.xml"
                val isJava = path.startsWith("android/app/src/main/java/com/genb/analytics/") && path.endsWith(".java")
                require((isManifest || isJava) && path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                    '\\' !in path && path.length <= 240) {
                    "Source path is outside the approved GenB manifest and Java package"
                }
                require(seen.add(path)) { "Duplicate source path" }
                val encoded = item.optString("contentBase64")
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.isNotEmpty() && Base64.getEncoder().encodeToString(bytes) == encoded) {
                    "Source file base64 is invalid"
                }
                sourceBytes += bytes.size
                require(bytes.size <= 32 * 1024 && sourceBytes <= MAX_SOURCE_BYTES) { "Source bundle exceeds its size limit" }
                val text = bytes.toString(Charsets.UTF_8)
                require(!text.contains('\u0000')) { "Source contains a NUL byte" }
                val nameBytes = path.toByteArray(Charsets.UTF_8)
                sourceDigest.update(nameBytes.size.toString().toByteArray(Charsets.US_ASCII))
                sourceDigest.update(0.toByte())
                sourceDigest.update(nameBytes)
                sourceDigest.update(bytes)
                if (isManifest) {
                    require(text.contains("<manifest") && text.contains("android.permission.INTERNET") &&
                        text.contains("android:name=\".MainActivity\"")) {
                        "GenB manifest failed the approved profile checks"
                    }
                    hasManifest = true
                } else {
                    val relative = path.removePrefix("android/app/src/main/java/")
                    val destination = File(sourceRoot, relative)
                    require(destination.canonicalPath.startsWith(sourceRoot.canonicalPath + File.separator)) {
                        "Source path escaped the private build workspace"
                    }
                    destination.parentFile?.mkdirs()
                    destination.writeBytes(bytes)
                    sourceFiles.add(destination)
                    if (path == "android/app/src/main/java/com/genb/analytics/MainActivity.java") {
                        require(text.contains("package com.genb.analytics;") &&
                            text.contains("extends Activity") && text.contains("WebView")) {
                            "GenB entry point failed the approved profile checks"
                        }
                        hasEntryPoint = true
                    }
                }
            }
            require(hasManifest && hasEntryPoint) { "GenB manifest or MainActivity.java is missing" }
            val actualSourceHash = sourceDigest.digest().joinToString("") { "%02x".format(it) }
            require(actualSourceHash == sourceHash) { "Source bundle SHA-256 does not match validated source" }
            val buildConfig = File(sourceRoot, "com/genb/analytics/BuildConfig.java")
            buildConfig.parentFile?.mkdirs()
            buildConfig.writeText(
                "package com.genb.analytics; public final class BuildConfig { " +
                    "public static final String DEFAULT_SERVER_URL = \"\"; private BuildConfig() {} }",
                Charsets.UTF_8
            )
            sourceFiles.add(buildConfig)

            if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
            val compilerOut = ByteArrayOutputStream()
            val compilerErr = ByteArrayOutputStream()
            val args = arrayListOf("-proc:none", "-nowarn", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8",
                "-bootclasspath", jar.absolutePath, "-classpath", jar.absolutePath, "-d", classesDir.absolutePath)
            sourceFiles.forEach { args.add(it.absolutePath) }
            val compiled = BatchCompiler.compile(args.toTypedArray(),
                PrintWriter(compilerOut, true), PrintWriter(compilerErr, true), null)
            if (!compiled) throw IllegalArgumentException(
                ("JAVA_COMPILE_FAILED: " + compilerErr.toString(Charsets.UTF_8).take(6000)).take(6200)
            )
            if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
            val classFiles = Files.walk(classesDir.toPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                    .map { it.toFile() }.collect(Collectors.toList())
            }
            require(classFiles.isNotEmpty() && classFiles.size <= 64) { "Compiler emitted an unexpected class-file count" }
            D8.run(D8Command.builder().setMinApiLevel(26).addLibraryFiles(jar.toPath())
                .addProgramFiles(classFiles.map { it.toPath() })
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed).build())
            if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
            val dexFiles = dexDir.listFiles()?.filter { it.isFile && it.name.matches(Regex("classes[0-9]*\\.dex")) }
                ?.sortedBy { it.name } ?: emptyList()
            require(dexFiles.size == 1 && dexFiles[0].name == "classes.dex") {
                "Focused GenB profile must produce exactly one classes.dex"
            }
            val dex = dexFiles[0].readBytes()
            require(dex.size in 112..MAX_DEX_BYTES && dex.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "dex\n") {
                "Generated DEX is invalid or exceeds the approved output limit"
            }
            return JSONObject().put("status", "dex-ready").put("profileId", PROFILE)
                .put("applicationId", "com.genb.analytics").put("versionCode", payload.getInt("versionCode"))
                .put("versionName", payload.getString("versionName")).put("minSdk", 26)
                .put("targetSdk", payload.getInt("targetSdk")).put("toolchainVersion", TOOLCHAIN_VERSION)
                .put("androidJarSha256", expectedJarHash).put("sourceSha256", sourceHash).put("sourceBytes", sourceBytes)
                .put("classFiles", classFiles.size).put("dexFiles", 1).put("dexBytes", dex.size)
                .put("dexSha256", MessageDigest.getInstance("SHA-256").digest(dex).joinToString("") { "%02x".format(it) })
                .put("classesDexBase64", Base64.getEncoder().encodeToString(dex)).put("apkProduced", false)
        } finally {
            root.deleteRecursively()
        }
    }
}
