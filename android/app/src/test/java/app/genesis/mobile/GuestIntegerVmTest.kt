package app.genesis.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuestIntegerVmTest {
    private fun program(vararg instructions: Pair<Int, Long?>): ByteArray {
        val bytes = ByteArray(instructions.fold(0) { total, instruction -> total + if (instruction.first == GuestIntegerVm.OP_PUSH_I64) 9 else 1 })
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        for ((opcode, operand) in instructions) {
            buffer.put(opcode.toByte())
            if (opcode == GuestIntegerVm.OP_PUSH_I64) buffer.putLong(requireNotNull(operand))
        }
        return bytes
    }

    @Test fun executesBoundedIntegerArithmetic() {
        val code = program(
            GuestIntegerVm.OP_PUSH_I64 to 20L,
            GuestIntegerVm.OP_PUSH_I64 to 22L,
            GuestIntegerVm.OP_ADD to null,
            GuestIntegerVm.OP_HALT to null
        )
        assertEquals(42L, GuestIntegerVm.execute(code, 10))
    }

    @Test fun rejectsUnknownOpcodeAndTruncatedImmediate() {
        assertThrows(IllegalStateException::class.java) {
            GuestIntegerVm.execute(byteArrayOf(99), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestIntegerVm.execute(byteArrayOf(GuestIntegerVm.OP_PUSH_I64.toByte(), 1), 10)
        }
    }

    @Test fun rejectsStackUnderflowAndInvalidHaltShape() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestIntegerVm.execute(byteArrayOf(GuestIntegerVm.OP_ADD.toByte(), GuestIntegerVm.OP_HALT.toByte()), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestIntegerVm.execute(byteArrayOf(GuestIntegerVm.OP_HALT.toByte()), 10)
        }
    }

    @Test fun rejectsArithmeticOverflowAndDivisionByZero() {
        val overflow = program(
            GuestIntegerVm.OP_PUSH_I64 to Long.MAX_VALUE,
            GuestIntegerVm.OP_PUSH_I64 to 1L,
            GuestIntegerVm.OP_ADD to null,
            GuestIntegerVm.OP_HALT to null
        )
        assertThrows(ArithmeticException::class.java) { GuestIntegerVm.execute(overflow, 10) }

        val divideByZero = program(
            GuestIntegerVm.OP_PUSH_I64 to 7L,
            GuestIntegerVm.OP_PUSH_I64 to 0L,
            GuestIntegerVm.OP_DIV to null,
            GuestIntegerVm.OP_HALT to null
        )
        assertThrows(IllegalArgumentException::class.java) { GuestIntegerVm.execute(divideByZero, 10) }
    }

    @Test fun enforcesInstructionProgramAndStackBounds() {
        val code = program(
            GuestIntegerVm.OP_PUSH_I64 to 20L,
            GuestIntegerVm.OP_PUSH_I64 to 22L,
            GuestIntegerVm.OP_ADD to null,
            GuestIntegerVm.OP_HALT to null
        )
        assertThrows(IllegalArgumentException::class.java) { GuestIntegerVm.execute(code, 2) }
        assertThrows(IllegalArgumentException::class.java) { GuestIntegerVm.execute(code, 10, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            GuestIntegerVm.execute(ByteArray(GuestIntegerVm.MAX_PROGRAM_BYTES + 1), 10)
        }
    }

    @Test fun rejectsTrailingBytesAndMissingHalt() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestIntegerVm.execute(byteArrayOf(GuestIntegerVm.OP_HALT.toByte(), 0), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestIntegerVm.execute(program(GuestIntegerVm.OP_PUSH_I64 to 1L), 10)
        }
    }
}
