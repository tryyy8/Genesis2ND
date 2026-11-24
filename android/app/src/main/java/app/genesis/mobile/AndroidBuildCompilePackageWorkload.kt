package app.genesis.mobile

import org.json.JSONObject

/** Runs compile, D8 and APK packaging in one worker job so large DEX bytes never cross the 64 KiB task-input boundary. */
object AndroidBuildCompilePackageWorkload {
    fun execute(payload: JSONObject, appFilesDir: java.io.File?, shouldStop: () -> Boolean = { false }): JSONObject {
        val compiled = AndroidBuildCompileDexWorkload.execute(payload, appFilesDir, shouldStop)
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
        val packagePayload = JSONObject()
            .put("profileId", compiled.getString("profileId"))
            .put("applicationId", compiled.getString("applicationId"))
            .put("versionCode", compiled.getInt("versionCode"))
            .put("versionName", compiled.getString("versionName"))
            .put("minSdk", compiled.getInt("minSdk"))
            .put("targetSdk", compiled.getInt("targetSdk"))
            .put("classesDexBase64", compiled.getString("classesDexBase64"))
            .put("dexSha256", compiled.getString("dexSha256"))
            .put("sourceSha256", compiled.getString("sourceSha256"))
            .put("toolchainVersion", compiled.getString("toolchainVersion"))
            .put("androidJarSha256", compiled.getString("androidJarSha256"))
        val packaged = AndroidBuildPackageWorkload.execute(packagePayload, shouldStop)
        packaged.put("compileSummary", JSONObject()
            .put("classFiles", compiled.getInt("classFiles"))
            .put("dexBytes", compiled.getInt("dexBytes"))
            .put("dexSha256", compiled.getString("dexSha256"))
            .put("sourceBytes", compiled.getInt("sourceBytes"))
            .put("sourceSha256", compiled.getString("sourceSha256")))
        return packaged.put("buildMode", "phone-b-native-compile-dex-package")
    }
}
