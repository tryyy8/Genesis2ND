package app.genesis.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkloadEngineCancellationTest {
    @Test
    fun longSimulationStopsAtCooperativeCheckpoint() {
        var checks = 0
        var stopped = false
        try {
            NativeWorkloadEngine.execute(
                "small-simulation",
                JSONObject().put("steps", 10000),
                shouldStop = { checks += 1; checks >= 3 }
            )
        } catch (error: IllegalStateException) {
            stopped = error.message == "WORKER_STOP_REQUESTED"
        }
        assertTrue(stopped)
        assertEquals(3, checks)
    }

    @Test
    fun probabilityLoopHonorsCancellationCheck() {
        var checks = 0
        var stopped = false
        try {
            NativeWorkloadEngine.execute(
                "probability-simulation",
                JSONObject().put("trials", 1000000).put("probability", 0.5),
                shouldStop = { checks += 1; checks >= 2 }
            )
        } catch (error: IllegalStateException) {
            stopped = error.message == "WORKER_STOP_REQUESTED"
        }
        assertTrue(stopped)
        assertEquals(2, checks)
    }
}
