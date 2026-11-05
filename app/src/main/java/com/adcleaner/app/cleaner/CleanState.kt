package com.adcleaner.app.cleaner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 全局清理状态总线：UI 与无障碍服务之间的单向通道。 */
object CleanState {
    data class Ui(
        val running: Boolean = false,
        val section: String = "",
        val progress: Int = 0,
        val total: Int = 0,
        val done: Boolean = false,
        val aborted: Boolean = false,
        val log: String = ""
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private const val MAX_LINES = 800
    private val lines = ArrayDeque<String>()

    fun log(line: String) {
        val ts = line
        synchronized(lines) {
            lines.addLast(ts)
            while (lines.size > MAX_LINES) lines.removeFirst()
            val text = lines.joinToString("\n")
            _ui.update { it.copy(log = text) }
        }
    }

    fun section(name: String, idx: Int, total: Int) {
        log("\n── $name ──")
        _ui.update { it.copy(running = true, section = name, progress = idx, total = total, done = false, aborted = false) }
    }

    fun step(msg: String) = log("  $msg")

    fun ok(msg: String) = log("  ✓ $msg")
    fun warn(msg: String) = log("  ! $msg")
    fun fail(msg: String) = log("  ✗ $msg")

    fun done() = _ui.update { it.copy(running = false, done = true) }
    fun aborted() = _ui.update { it.copy(running = false, aborted = true) }

    fun reset() {
        synchronized(lines) { lines.clear() }
        _ui.value = Ui()
    }
}
