package app.genesis.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64 as JavaBase64

class AndroidBuildSourceValidationWorkloadTest {
    private val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
      <uses-permission android:name="android.permission.INTERNET" />
      <application android:label="GenB Analytics">
        <activity android:name=".MainActivity" android:exported="true" />
      </application>
    </manifest>"""

    private val activity = """package com.genb.analytics;
      import android.app.Activity;
      import android.webkit.WebView;
      public final class MainActivity extends Activity { private WebView view; }
    """

    private fun file(path: String, content: String) = JSONObject()
        .put("path", path)
        .put("contentBase64", JavaBase64.getEncoder().encodeToString(content.toByteArray()))

    private fun request(activityPath: String = "android/app/src/main/java/com/genb/analytics/MainActivity.java"): JSONObject {
        return JSONObject()
            .put("profileId", "genb-analytics-java-webview-v1")
            .put("applicationId", "com.genb.analytics")
            .put("versionCode", 1)
            .put("versionName", "0.1.0")
            .put("minSdk", 26)
            .put("targetSdk", 35)
            .put("entryPoint", "com.genb.analytics.MainActivity")
            .put("manifest", "android/app/src/main/AndroidManifest.xml")
            .put("files", JSONArray()
                .put(file("android/app/src/main/AndroidManifest.xml", manifest))
                .put(file(activityPath, activity)))
    }

    @Test fun validatesCurrentGenBSourceProfile() {
        val result = AndroidBuildSourceValidationWorkload.execute(request())
        assertEquals("source-validated", result.getString("status"))
        assertEquals("com.genb.analytics", result.getString("applicationId"))
        assertEquals(2, result.getInt("sourceFiles"))
        assertFalse(result.getBoolean("apkProduced"))
        assertEquals("f1d92b31e4e75acb10424524a37341c633e6b32c469fd396d9c92f980ad5bbe7", result.getString("sourceSha256"))
    }

    @Test fun rejectsKotlinSource() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidBuildSourceValidationWorkload.execute(request("android/app/src/main/java/com/genb/analytics/MainActivity.kt"))
        }
    }

    @Test fun rejectsUnsupportedProfileAndPathTraversal() {
        val badProfile = request().put("profileId", "arbitrary-gradle-project")
        assertThrows(IllegalArgumentException::class.java) {
            AndroidBuildSourceValidationWorkload.execute(badProfile)
        }
        val badPath = request("../MainActivity.java")
        assertThrows(IllegalArgumentException::class.java) {
            AndroidBuildSourceValidationWorkload.execute(badPath)
        }
    }

    @Test fun honorsCancellationBeforeProcessing() {
        assertThrows(IllegalStateException::class.java) {
            AndroidBuildSourceValidationWorkload.execute(request()) { true }
        }
    }
}
