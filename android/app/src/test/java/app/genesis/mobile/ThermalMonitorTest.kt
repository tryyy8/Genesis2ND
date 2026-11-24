package app.genesis.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalMonitorTest {
    @Test
    fun recognizedTemperatureZonesConvertMillidegreesOnlyForKnownTemperatureSensors() {
        val cpu = interpretThermalZoneReading("cpuss-0", 26000.0)
        assertEquals("temperature", cpu.measurementKind)
        assertEquals("°C", cpu.unit)
        assertEquals(26.0, cpu.displayValue ?: Double.NaN, 0.0001)
        val pmic = interpretThermalZoneReading("pm6450_tz", 37000.0)
        assertEquals("temperature", pmic.measurementKind)
        assertEquals(37.0, pmic.displayValue ?: Double.NaN, 0.0001)
        val camera = interpretThermalZoneReading("cam-therm", 24508.0)
        assertEquals(24.508, camera.displayValue ?: Double.NaN, 0.0001)
    }

    @Test
    fun androidBatteryBroadcastUsesTenthsOfADegreeWithoutKernelSentinelGuessing() {
        val battery = interpretBatteryTemperatureTenthsC(345.0)
        assertEquals("temperature", battery.measurementKind)
        assertEquals(34.5, battery.displayValue ?: Double.NaN, 0.0001)
        assertEquals("valid", battery.readingStatus)
        val unavailable = interpretBatteryTemperatureTenthsC(Int.MIN_VALUE.toDouble())
        assertEquals("unavailable-sentinel", unavailable.readingStatus)
        assertNull(unavailable.displayValue)
    }

    @Test
    fun batteryVoltageAndCurrentKeepTheirOwnUnits() {
        val voltage = interpretThermalZoneReading("vbat", 3786.0)
        assertEquals("voltage", voltage.measurementKind)
        assertEquals("V", voltage.unit)
        assertEquals("low", voltage.thresholdDirection)
        assertEquals(3.786, voltage.displayValue ?: Double.NaN, 0.0001)
        val current = interpretThermalZoneReading("pm7250b-ibat-lvl1", 78.0)
        assertEquals("current", current.measurementKind)
        assertEquals("mA", current.unit)
        assertEquals("high", current.thresholdDirection)
        assertEquals(78.0, current.displayValue ?: Double.NaN, 0.0001)
    }

    @Test
    fun unknownAndStatusLikeZonesRemainRawInsteadOfBecomingTemperatures() {
        for (name in listOf("socd", "zeroc-0-step", "pm7250b-bcl-lvl0", "pm6150l-bcl-lvl0", "pm7250b-ibat-M0", "bcl-warn")) {
            val reading = interpretThermalZoneReading(name, 0.0)
            assertEquals("unknown", reading.measurementKind)
            assertEquals("raw", reading.unit)
            assertNull(reading.displayValue)
            assertEquals("unclassified", reading.readingStatus)
        }
    }

    @Test
    fun knownTemperatureDriverSentinelsAreUnavailableWithoutImplausibleWarnings() {
        for (raw in listOf(-40000.0, -273000.0, Int.MIN_VALUE.toDouble())) {
            val reading = interpretThermalZoneReading("usb", raw)
            assertEquals("temperature", reading.measurementKind)
            assertEquals("unavailable-sentinel", reading.readingStatus)
            assertNull(reading.displayValue)
        }
        val highButReadable = interpretThermalZoneReading("cpuss-0", 312000.0)
        assertEquals("valid", highButReadable.readingStatus)
        assertEquals(312.0, highButReadable.displayValue ?: Double.NaN, 0.0001)
    }

    @Test
    fun lowVoltageThresholdsUseLowSideComparison() {
        assertEquals("normal", classifyThermalReading(3.786, "valid", 2.989, 2.6, "low"))
        assertEquals("concerning", classifyThermalReading(2.7, "valid", 2.989, 2.6, "low"))
        assertEquals("dangerous", classifyThermalReading(2.5, "valid", 2.989, 2.6, "low"))
    }

    @Test
    fun highCurrentThresholdsUseHighSideComparison() {
        assertEquals("normal", classifyThermalReading(78.0, "valid", 6000.0, null, "high"))
        assertEquals("concerning", classifyThermalReading(6500.0, "valid", 6000.0, null, "high"))
    }

    @Test
    fun unknownReadingsNeverReceiveAStatusClassification() {
        assertEquals("unknown", classifyThermalReading(65.0, "unclassified", 100.0, 1.0, "high"))
        assertEquals("unknown", classifyThermalReading(65.0, "valid", null, null, "none"))
    }
}
