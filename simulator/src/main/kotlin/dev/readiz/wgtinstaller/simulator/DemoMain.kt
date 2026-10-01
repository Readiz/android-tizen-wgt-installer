// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.simulator
import dev.readiz.wgtinstaller.core.*
fun main() {
    println("OFFLINE SIMULATION — no real GitHub/Samsung/TV connection")
    DemoEnvironment().use { demo ->
        val receipt = demo.run(Cancellation()) { if (it.progress < 0) println("${it.phase}: ${it.message}") }
        check(demo.tv.installCalls.get() == 1)
        check(demo.tv.files.keys == setOf(REMOTE_WGT, REMOTE_PROFILE))
        check(demo.tv.errors.isEmpty()) { demo.tv.errors.toString() }
        println("SIMULATION PASS: ${receipt.packageId} ${receipt.version}; ${demo.tv.files.size} non-private-key files; exactly one install command")
    }
}
