package com.openandroidintelligence.plugin.wasm

import org.junit.Assert.*
import org.junit.Test

class InvocationIsolationTest {
    private val limits = InvocationBudget(1000, 16 * 65536, 65536)
    private fun runtime() = ChicoryPluginRuntime(budget = limits, moduleSource = { WasmFixtures.echo() })

    @Test fun rejectsInitialMemoryUnderTheInvocationBudgetBeforeAllocation() {
        val module = runtime().load(WasmFixtures.echo(2))
        val error = runCatching { module.invoke(byteArrayOf(7), limits.copy(maxMemoryBytes = 65536)) }.exceptionOrNull()
        assertTrue(error is PluginRejected && error.message!!.contains("MEMORY_LIMIT"))
    }
    @Test fun floorsFractionalPagesAndBoundsMemoryGrow() {
        val module = runtime().load(WasmFixtures.growOnce())
        assertArrayEquals(byteArrayOf(7), module.invoke(byteArrayOf(7), limits.copy(maxMemoryBytes = 2 * 65536)))
        assertTrue(runCatching { module.invoke(byteArrayOf(7), limits.copy(maxMemoryBytes = 65536 + 32768)) }.exceptionOrNull() is PluginRejected)
        assertTrue(runCatching { module.invoke(byteArrayOf(7), limits.copy(maxMemoryBytes = 1)) }.exceptionOrNull() is PluginRejected)
    }
    @Test fun runningAnotherModuleCannotReplaceAnInstancesHostCallGuard() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val runtime = ChicoryPluginRuntime(budget = limits, moduleSource = { WasmFixtures.echo() }, clock = clock::get)
        val first = runtime.load(WasmFixtures.echo(logCalls = 2))
        val second = runtime.load(WasmFixtures.echo())
        var reentered = false
        runtime.logSink = {
            if (!reentered) {
                reentered = true
                assertArrayEquals(byteArrayOf(2), second.invoke(byteArrayOf(2), limits))
                clock.set(100)
            }
        }
        val error = runCatching { first.invoke(byteArrayOf(1), limits.copy(maxInvocationMillis = 50)) }.exceptionOrNull()
        assertTrue(reentered)
        assertTrue("actual failure: $error", error is BudgetExceeded && error.message!!.contains("DEADLINE"))
    }
}
