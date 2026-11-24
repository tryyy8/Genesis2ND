package app.genesis.mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class ThermalZoneInterpretation(
    val measurementKind: String,
    val unit: String,
    val displayValue: Double?,
    val readingStatus: String,
    val thresholdDirection: String
)

private data class ThermalThreshold(
    val index: Int, val type: String, val rawValue: Double, val value: Double,
    val unit: String, val label: String, val direction: String
)

private val knownTemperatureZonePatterns = listOf(
    Regex("^(pa|aoss-\\d+|cpuss-\\d+|gpuss-\\d+|pm7250b_tz|pm6450_tz|cam-therm|quiet-therm|front_temp|back_temp|fuse_temp|mmi_battery|battery|usb)$"),
    Regex("^pa-therm\\d*$")
)

private fun isKnownTemperatureZone(name: String): Boolean {
    val key = name.trim().lowercase(Locale.ROOT)
    return knownTemperatureZonePatterns.any { it.matches(key) }
}

private fun temperatureInterpretation(raw: Double, divisor: Double, sentinels: Set<Double>): ThermalZoneInterpretation {
    val status = when {
        !raw.isFinite() -> "invalid-data"
        raw == Int.MIN_VALUE.toDouble() || raw in sentinels -> "unavailable-sentinel"
        else -> "valid"
    }
    return ThermalZoneInterpretation("temperature", "°C", if (status == "valid") raw / divisor else null, status, "high")
}

internal fun interpretThermalZoneReading(name: String, raw: Double): ThermalZoneInterpretation {
    val key = name.trim().lowercase(Locale.ROOT)
    val kind = when {
        key == "vbat" -> "voltage"
        Regex("^pm7250b-ibat-lvl[01]$").matches(key) -> "current"
        isKnownTemperatureZone(key) -> "temperature"
        else -> "unknown"
    }
    if (kind == "unknown") {
        return ThermalZoneInterpretation("unknown", "raw", null, if (raw.isFinite()) "unclassified" else "invalid-data", "none")
    }
    val unit = when (kind) { "voltage" -> "V"; "current" -> "mA"; else -> "°C" }
    val divisor = when (kind) { "voltage" -> 1000.0; "current" -> 1.0; else -> 1000.0 }
    val status = when {
        !raw.isFinite() -> "invalid-data"
        raw == Int.MIN_VALUE.toDouble() -> "unavailable-sentinel"
        kind == "temperature" && (raw == -40000.0 || raw == -273000.0) -> "unavailable-sentinel"
        else -> "valid"
    }
    return ThermalZoneInterpretation(kind, unit, if (status == "valid") raw / divisor else null, status, if (kind == "voltage") "low" else "high")
}

internal fun interpretTemperatureMilliC(raw: Double): ThermalZoneInterpretation =
    temperatureInterpretation(raw, 1000.0, setOf(-40000.0, -273000.0))
internal fun interpretBatteryTemperatureTenthsC(raw: Double): ThermalZoneInterpretation =
    temperatureInterpretation(raw, 10.0, emptySet())

internal fun classifyThermalReading(current: Double?, readingStatus: String, concerning: Double?, dangerous: Double?, direction: String): String {
    if (readingStatus != "valid" || current == null || !current.isFinite()) return "unknown"
    return when (direction) {
        "low" -> when {
            dangerous != null && current <= dangerous -> "dangerous"
            concerning != null && current <= concerning -> "concerning"
            concerning != null || dangerous != null -> "normal"
            else -> "unknown"
        }
        "high" -> when {
            dangerous != null && current >= dangerous -> "dangerous"
            concerning != null && current >= concerning -> "concerning"
            concerning != null || dangerous != null -> "normal"
            else -> "unknown"
        }
        else -> "unknown"
    }
}

object ThermalMonitor {
    private const val MAX_SENSORS = 128

    private fun readTripPoints(zone: File, kind: String): List<ThermalThreshold> {
        val result = mutableListOf<ThermalThreshold>()
        for (index in 0..15) {
            val valueFile = zone.resolve("trip_point_${index}_temp")
            val typeFile = zone.resolve("trip_point_${index}_type")
            if (!valueFile.canRead() || !typeFile.canRead()) continue
            try {
                val raw = valueFile.readText().trim().toDouble()
                if (!raw.isFinite()) continue
                val type = typeFile.readText().trim().lowercase(Locale.ROOT)
                val unit = when (kind) { "temperature" -> "°C"; "voltage" -> "V"; "current" -> "mA"; else -> "raw" }
                val value = if (kind == "temperature" || kind == "voltage") raw / 1000.0 else raw
                val label = when (kind) {
                    "temperature" -> when (type) {
                        "critical" -> "Critical temperature trip"
                        "passive", "hot", "active" -> "Concerning temperature trip"
                        else -> "Temperature trip"
                    }
                    "voltage" -> "Low-voltage trip"
                    "current" -> "High-current trip"
                    else -> "Unclassified kernel trip"
                }
                val direction = when (kind) { "voltage" -> "low"; "temperature", "current" -> "high"; else -> "none" }
                result += ThermalThreshold(index, type, raw, value, unit, label, direction)
            } catch (_: Exception) { }
        }
        return result
    }

    private fun thresholdBounds(kind: String, points: List<ThermalThreshold>): Pair<Double?, Double?> {
        val values = points.map { it.value }.filter { it.isFinite() }
        return when (kind) {
            "temperature" -> Pair(
                points.filter { it.type in setOf("passive", "hot", "active") }.minOfOrNull { it.value },
                points.filter { it.type == "critical" }.minOfOrNull { it.value }
            )
            "voltage" -> Pair(values.maxOrNull(), values.minOrNull().takeIf { values.size > 1 })
            "current" -> Pair(values.minOrNull(), values.maxOrNull().takeIf { values.size > 1 })
            else -> Pair(null, null)
        }
    }

    private fun thresholdsJson(points: List<ThermalThreshold>): JSONArray {
        val array = JSONArray()
        for (point in points) array.put(JSONObject()
            .put("index", point.index).put("type", point.type).put("rawValue", point.rawValue)
            .put("value", point.value).put("unit", point.unit).put("label", point.label)
            .put("direction", point.direction))
        return array
    }

    private fun sensorJson(
        name: String, type: String, sourcePath: String, reading: ThermalZoneInterpretation,
        raw: Double, points: List<ThermalThreshold>, concerning: Double?, dangerous: Double?
    ): JSONObject {
        val kind = reading.measurementKind
        return JSONObject()
            .put("name", name.take(120)).put("type", type).put("sourcePath", sourcePath)
            .put("measurementKind", kind).put("unit", reading.unit).put("rawValue", raw)
            .put("displayValue", reading.displayValue ?: JSONObject.NULL)
            .put("readingStatus", reading.readingStatus).put("thresholdDirection", reading.thresholdDirection)
            .put("temperatureC", if (kind == "temperature") reading.displayValue ?: JSONObject.NULL else JSONObject.NULL)
            .put("rawTemperatureMilliC", if (kind == "temperature") raw else JSONObject.NULL)
            .put("recommendedMaxC", JSONObject.NULL)
            .put("concerningValue", concerning ?: JSONObject.NULL).put("dangerousValue", dangerous ?: JSONObject.NULL)
            .put("concerningC", if (kind == "temperature") concerning ?: JSONObject.NULL else JSONObject.NULL)
            .put("dangerousC", if (kind == "temperature") dangerous ?: JSONObject.NULL else JSONObject.NULL)
            .put("thresholds", thresholdsJson(points))
            .put("classification", classifyThermalReading(reading.displayValue, reading.readingStatus, concerning, dangerous, reading.thresholdDirection))
    }

    fun collect(context: Context, deviceId: String, label: String = Build.MODEL): JSONObject {
        val sensors = JSONArray()
        var unavailable = 0
        fun addSensor(sensor: JSONObject) {
            if (sensors.length() < MAX_SENSORS) sensors.put(sensor) else unavailable++
        }
        val root = File("/sys/class/thermal")
        val zones = try { root.listFiles()?.filter { it.name.matches(Regex("thermal_zone[0-9]+")) } ?: emptyList() }
            catch (_: Exception) { emptyList() }
        for (zone in zones) {
            try {
                val name = zone.resolve("type").readText().trim().ifBlank { zone.name }
                val raw = zone.resolve("temp").readText().trim().toDouble()
                val reading = interpretThermalZoneReading(name, raw)
                val points = readTripPoints(zone, reading.measurementKind)
                val (concerning, dangerous) = thresholdBounds(reading.measurementKind, points)
                addSensor(sensorJson(name, "thermal-zone", zone.resolve("temp").path, reading, raw, points, concerning, dangerous))
            } catch (_: Exception) { unavailable++ }
        }

        val hwmonRoot = File("/sys/class/hwmon")
        val hwmonDevices = try { hwmonRoot.listFiles()?.filter { it.name.matches(Regex("hwmon[0-9]+")) } ?: emptyList() }
            catch (_: Exception) { emptyList() }
        for (device in hwmonDevices) {
            val deviceName = try { device.resolve("name").readText().trim().ifBlank { device.name } } catch (_: Exception) { device.name }
            val inputs = try { device.listFiles()?.filter { it.name.matches(Regex("temp[0-9]+_input")) } ?: emptyList() }
                catch (_: Exception) { emptyList() }
            for (input in inputs) {
                try {
                    val suffix = input.name.removePrefix("temp").removeSuffix("_input")
                    val raw = input.readText().trim().toDouble()
                    val reading = interpretTemperatureMilliC(raw)
                    val labelFile = device.resolve("temp${suffix}_label")
                    val sensorName = if (labelFile.canRead()) labelFile.readText().trim().ifBlank { "$deviceName ${input.name}" } else "$deviceName ${input.name}"
                    val points = mutableListOf<ThermalThreshold>()
                    fun addLimit(fileName: String, label: String) {
                        val file = device.resolve(fileName)
                        if (!file.canRead()) return
                        try {
                            val thresholdRaw = file.readText().trim().toDouble()
                            if (thresholdRaw.isFinite()) points += ThermalThreshold(points.size, fileName, thresholdRaw, thresholdRaw / 1000.0, "°C", label, "high")
                        } catch (_: Exception) { }
                    }
                    addLimit("temp${suffix}_max", "Concerning temperature limit")
                    addLimit("temp${suffix}_crit", "Critical temperature limit")
                    val concerning = points.firstOrNull { it.label.startsWith("Concerning") }?.value
                    val dangerous = points.firstOrNull { it.label.startsWith("Critical") }?.value
                    addSensor(sensorJson(sensorName, "hwmon", input.path, reading, raw, points, concerning, dangerous))
                } catch (_: Exception) { unavailable++ }
            }
        }

        try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths != Int.MIN_VALUE) {
                val reading = interpretBatteryTemperatureTenthsC(tenths.toDouble())
                addSensor(sensorJson("Battery (Android broadcast)", "battery", "Android ACTION_BATTERY_CHANGED",
                    reading, tenths.toDouble(), emptyList(), null, null).put("rawTemperatureTenthsC", tenths))
            }
        } catch (_: Exception) { unavailable++ }

        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
            try { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus } catch (_: Exception) { -1 }
        } else -1

        return JSONObject()
            .put("deviceId", deviceId).put("label", label.take(80)).put("model", Build.MODEL.take(120))
            .put("androidRelease", Build.VERSION.RELEASE.orEmpty().take(40)).put("thermalStatus", thermalStatus)
            .put("sensors", sensors).put("unavailableSensors", unavailable)
            .put("thermalZonesEnumerated", zones.size).put("hwmonDevicesEnumerated", hwmonDevices.size)
            .put("sensorAccess", if (sensors.length() > 0) "readable-sensors-found" else "no-readable-kernel-sensors")
            .put("capturedAt", System.currentTimeMillis())
    }
}
