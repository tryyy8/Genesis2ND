package app.genesis.mobile

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import java.util.concurrent.CancellationException

/**
 * Bounded, import-free WebAssembly execution for the Android isolated process.
 * This does not expose WASI, filesystem, network, or host functions.
 */
object GuestWasmVm {
    const val MAX_MODULE_BYTES = 1024 * 1024
    const val MAX_STEPS = 100_000
    const val MAX_MEMORY_PAGES = 64
    const val MAX_EXECUTION_MS = 5_000L
    private const val MAX_ARGUMENTS = 64
    private const val MAX_TABLE_ENTRIES = 4096L

    fun execute(
        program: ByteArray,
        exportName: String,
        arguments: LongArray = longArrayOf(),
        maxSteps: Int = MAX_STEPS,
        maxMemoryPages: Int = MAX_MEMORY_PAGES,
        maxExecutionMs: Long = MAX_EXECUTION_MS,
        isCancelled: () -> Boolean = { false }
    ): Long {
        require(program.isNotEmpty() && program.size <= MAX_MODULE_BYTES) { "WASM module size outside allowed range" }
        require(exportName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}"))) { "Invalid WASM export name" }
        require(arguments.size <= MAX_ARGUMENTS) { "Too many WASM arguments" }
        require(maxSteps in 1..MAX_STEPS) { "WASM instruction budget outside allowed range" }
        require(maxMemoryPages in 1..MAX_MEMORY_PAGES) { "WASM memory budget outside allowed range" }
        require(maxExecutionMs in 1..MAX_EXECUTION_MS) { "WASM deadline outside allowed range" }

        val module = Parser.parse(program)
        require(module.importSection().importCount() == 0) { "WASM imports are not permitted in the isolated guest" }
        require(!module.startSection().isPresent) { "WASM start functions are not permitted in the isolated guest" }

        val memorySection = module.memorySection().orElse(null)
        if (memorySection != null) {
            require(memorySection.memoryCount() <= 1) { "Only one WASM linear memory is supported" }
            if (memorySection.memoryCount() == 1) {
                val declared = memorySection.getMemory(0).limits()
                require(!declared.shared()) { "Shared WASM memory is not permitted" }
                require(declared.initialPages() <= maxMemoryPages) { "WASM initial memory exceeds configured limit" }
            }
        }

        val tableSection = module.tableSection()
        for (index in 0 until tableSection.tableCount()) {
            val limits = tableSection.getTable(index).limits()
            require(!limits.shared() && limits.min() <= MAX_TABLE_ENTRIES && limits.max() <= MAX_TABLE_ENTRIES) {
                "WASM table exceeds configured entry limit"
            }
        }

        val deadline = System.nanoTime() + maxExecutionMs * 1_000_000L
        var steps = 0
        val builder = Instance.builder(module).withStart(false).withUnsafeExecutionListener { _, _ ->
            if (isCancelled()) throw CancellationException("WASM execution cancelled")
            steps += 1
            if (steps > maxSteps) throw IllegalStateException("WASM instruction budget exhausted")
            if (System.nanoTime() > deadline) throw IllegalStateException("WASM execution deadline exceeded")
        }
        if (memorySection != null && memorySection.memoryCount() == 1) {
            val initialPages = memorySection.getMemory(0).limits().initialPages()
            builder.withMemoryLimits(MemoryLimits(initialPages, minOf(memorySection.getMemory(0).limits().maximumPages(), maxMemoryPages)))
        }
        val instance = builder.build()
        val results = instance.export(exportName).apply(*arguments)
        require(results.size == 1) { "WASM export must return exactly one numeric result" }
        return results[0]
    }
}
