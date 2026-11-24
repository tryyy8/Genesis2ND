package app.genesis.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure, bounded guest integer bytecode interpreter with no host capabilities. */
object GuestIntegerVm {
    const val MAX_STEPS = 100_000
    const val MAX_PROGRAM_BYTES = 64 * 1024
    const val MAX_STACK = 1024
    const val MAX_EXECUTION_MS = 5_000L
    const val OP_PUSH_I64 = 1
    const val OP_ADD = 2
    const val OP_SUB = 3
    const val OP_MUL = 4
    const val OP_DIV = 5
    const val OP_HALT = 6

    fun execute(program: ByteArray, maxSteps: Int, maxExecutionMs: Long = MAX_EXECUTION_MS): Long {
        require(program.isNotEmpty() && program.size <= MAX_PROGRAM_BYTES) { "Program size outside allowed range" }
        require(maxSteps in 1..MAX_STEPS) { "Step limit outside allowed range" }
        require(maxExecutionMs in 1..MAX_EXECUTION_MS) { "Execution deadline outside allowed range" }
        val input = ByteBuffer.wrap(program).order(ByteOrder.BIG_ENDIAN)
        val stack = ArrayList<Long>(32)
        val deadline = System.nanoTime() + maxExecutionMs * 1_000_000L
        var steps = 0
        var halted = false

        fun pop(): Long {
            require(stack.isNotEmpty()) { "Bytecode stack underflow" }
            return stack.removeAt(stack.lastIndex)
        }

        while (input.hasRemaining()) {
            require(!halted) { "Trailing instructions after HALT" }
            require(++steps <= maxSteps) { "Guest instruction budget exhausted" }
            require(System.nanoTime() <= deadline) { "Guest execution deadline exceeded" }
            when (input.get().toInt() and 0xff) {
                OP_PUSH_I64 -> {
                    require(input.remaining() >= java.lang.Long.BYTES) { "Truncated PUSH_I64" }
                    require(stack.size < MAX_STACK) { "Guest stack limit exceeded" }
                    stack.add(input.long)
                }
                OP_ADD -> { val b = pop(); val a = pop(); stack.add(Math.addExact(a, b)) }
                OP_SUB -> { val b = pop(); val a = pop(); stack.add(Math.subtractExact(a, b)) }
                OP_MUL -> { val b = pop(); val a = pop(); stack.add(Math.multiplyExact(a, b)) }
                OP_DIV -> {
                    val b = pop(); val a = pop()
                    require(b != 0L) { "Division by zero" }
                    require(!(a == Long.MIN_VALUE && b == -1L)) { "Integer division overflow" }
                    stack.add(a / b)
                }
                OP_HALT -> {
                    require(!input.hasRemaining()) { "Trailing bytes after HALT" }
                    require(stack.size == 1) { "HALT requires exactly one result value" }
                    halted = true
                }
                else -> error("Unknown guest opcode")
            }
        }
        require(halted) { "Program did not HALT" }
        return stack.single()
    }
}
