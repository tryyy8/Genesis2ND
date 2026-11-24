package app.genesis.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuestWasmVmTest {
    private val answerModule = byteArrayOf(
        0,97,115,109,1,0,0,0,
        1,5,1,96,0,1,127,
        3,2,1,0,
        7,7,1,3,114,117,110,0,0,
        10,6,1,4,0,65,42,11
    )

    @Test fun executesImportFreeWasmAndReturnsExportValue() {
        assertEquals(42L, GuestWasmVm.execute(answerModule, "run"))
    }

    @Test fun rejectsMalformedModule() {
        assertThrows(RuntimeException::class.java) {
            GuestWasmVm.execute(byteArrayOf(1,2,3), "run")
        }
    }

    @Test fun rejectsUnknownExport() {
        assertThrows(RuntimeException::class.java) {
            GuestWasmVm.execute(answerModule, "missing")
        }
    }

    @Test fun rejectsInvalidExecutionBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestWasmVm.execute(answerModule, "run", maxSteps = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestWasmVm.execute(answerModule, "run", maxMemoryPages = 0)
        }
    }

    @Test fun rejectsImportBearingModule() {
        val imported = byteArrayOf(
            0,97,115,109,1,0,0,0,
            1,4,1,96,0,0,
            2,7,1,1,101,1,102,0,0
        )
        assertThrows(IllegalArgumentException::class.java) {
            GuestWasmVm.execute(imported, "run")
        }
    }

    @Test fun instructionMeterStopsInfiniteLoop() {
        val loop = "0061736d01000000010401600000030201000707010372756e00000a0901070003400c000b0b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertThrows(RuntimeException::class.java) {
            GuestWasmVm.execute(loop, "run", maxSteps = 20)
        }
    }


    @Test fun boundsDeclaredLinearMemory() {
        val onePage = byteArrayOf(
            0,97,115,109,1,0,0,0,
            1,5,1,96,0,1,127,
            3,2,1,0,
            5,4,1,1,0,1,
            7,7,1,3,114,117,110,0,0,
            10,6,1,4,0,65,42,11
        )
        assertEquals(42L, GuestWasmVm.execute(onePage, "run", maxMemoryPages = 1))
        val twoPageMinimum = byteArrayOf(
            0,97,115,109,1,0,0,0,
            1,5,1,96,0,1,127,
            3,2,1,0,
            5,4,1,1,2,2,
            7,7,1,3,114,117,110,0,0,
            10,6,1,4,0,65,42,11
        )
        assertThrows(IllegalArgumentException::class.java) {
            GuestWasmVm.execute(twoPageMinimum, "run", maxMemoryPages = 1)
        }
    }

    @Test fun doesNotWidenModuleDeclaredMemoryMaximum() {
        val fixedZeroPageMemory = byteArrayOf(
            0,97,115,109,1,0,0,0,
            1,5,1,96,0,1,127,
            3,2,1,0,
            5,4,1,1,0,0,
            7,7,1,3,114,117,110,0,0,
            10,8,1,6,0,65,1,64,0,11
        )
        assertEquals(-1L, GuestWasmVm.execute(fixedZeroPageMemory, "run", maxMemoryPages = 64))
    }

    @Test fun rejectsUnmeteredModuleStartFunctions() {
        val startModule = byteArrayOf(
            0,97,115,109,1,0,0,0,
            1,4,1,96,0,0,
            3,2,1,0,
            8,1,0,
            10,4,1,2,0,11
        )
        assertThrows(IllegalArgumentException::class.java) {
            GuestWasmVm.execute(startModule, "run")
        }
    }
}
