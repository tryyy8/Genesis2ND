package app.genesis.mobile

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Transfers bounded WASM bytes through a Binder-carried descriptor rather than
 * embedding the module in a Messenger Bundle. The isolated process receives
 * either a read-only SharedMemory mapping or a read-only private-file descriptor.
 */
object GuestWasmModuleTransfer {
    const val KEY_PROGRAM_SIZE = "program_size"
    const val KEY_SHARED_MEMORY = "program_shared_memory"
    const val KEY_PROGRAM_FD = "program_fd"

    /** The extra byte is permitted only so the receiver can test its rejection boundary. */
    fun attach(context: Context, bundle: Bundle, program: ByteArray): Closeable {
        require(program.isNotEmpty() && program.size <= GuestWasmVm.MAX_MODULE_BYTES + 1) {
            "WASM transfer payload exceeds the transport test bound"
        }
        bundle.putInt(KEY_PROGRAM_SIZE, program.size)
        return if (Build.VERSION.SDK_INT >= 27) {
            attachSharedMemory(bundle, program)
        } else {
            attachReadOnlyFile(context, bundle, program)
        }
    }

    private fun attachSharedMemory(bundle: Bundle, program: ByteArray): SharedMemory {
        val memory = SharedMemory.create("genesis-guest-wasm", program.size)
        try {
            val writable = memory.mapReadWrite()
            try {
                writable.put(program)
            } finally {
                SharedMemory.unmap(writable)
            }
            check(memory.setProtect(OsConstants.PROT_READ)) {
                "Could not restrict guest module shared memory to read-only"
            }
            bundle.putParcelable(KEY_SHARED_MEMORY, memory)
            return memory
        } catch (error: Exception) {
            memory.close()
            throw error
        }
    }

    private fun attachReadOnlyFile(context: Context, bundle: Bundle, program: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("genesis-wasm-", ".bin", context.cacheDir)
        try {
            FileOutputStream(file).use { output -> output.write(program) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            if (!file.delete()) {
                descriptor.close()
                throw IOException("Could not unlink temporary guest module after opening it read-only")
            }
            bundle.putParcelable(KEY_PROGRAM_FD, descriptor)
            return descriptor
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    fun close(bundle: Bundle) {
        try { (bundle.getParcelable(KEY_SHARED_MEMORY) as? SharedMemory)?.close() } catch (_: Exception) { }
        try { (bundle.getParcelable(KEY_PROGRAM_FD) as? ParcelFileDescriptor)?.close() } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    fun read(bundle: Bundle): ByteArray {
        val sharedMemory = bundle.getParcelable(KEY_SHARED_MEMORY) as? SharedMemory
        val descriptor = bundle.getParcelable(KEY_PROGRAM_FD) as? ParcelFileDescriptor
        var descriptorStreamOwnsClose = false
        try {
            val size = bundle.getInt(KEY_PROGRAM_SIZE, -1)
            require(size in 8..GuestWasmVm.MAX_MODULE_BYTES) {
                "WASM module size outside allowed range"
            }
            require((sharedMemory != null) xor (descriptor != null)) {
                "Exactly one guest module descriptor is required"
            }

            if (sharedMemory != null) {
                require(sharedMemory.getSize() == size) {
                    "Shared WASM module size does not match its declared length"
                }
                val mapping = sharedMemory.mapReadOnly()
                try {
                    val program = ByteArray(size)
                    mapping.position(0)
                    mapping.get(program)
                    return program
                } finally {
                    SharedMemory.unmap(mapping)
                }
            }

            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor!!)
            descriptorStreamOwnsClose = true
            input.use {
                val program = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val count = it.read(program, offset, size - offset)
                    if (count < 0) throw EOFException("Guest module descriptor ended before its declared length")
                    if (count == 0) continue
                    offset += count
                }
                if (it.read() != -1) throw IOException("Guest module descriptor contains trailing bytes")
                return program
            }
        } finally {
            try { sharedMemory?.close() } catch (_: Exception) { }
            if (!descriptorStreamOwnsClose) {
                try { descriptor?.close() } catch (_: Exception) { }
            }
        }
    }
}
