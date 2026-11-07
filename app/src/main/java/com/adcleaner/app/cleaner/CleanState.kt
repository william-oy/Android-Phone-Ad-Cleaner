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
        val okCount: Int = 0,     // 成功跑完的段数
        val failCount: Int = 0,
        val closedCount: Int = 0, // 实际关掉的广告开关总数（完成页大字展示 + 语音播报用）
        val lastStep: String = "",// 最近一条动作（界面大字展示，老人不用看日志）
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

    fun step(msg: String) {
        log("  $msg")
        _ui.update { it.copy(lastStep = msg) }
    }

    fun ok(msg: String) {
        log("  ✓ $msg")
        _ui.update { it.copy(okCount = it.okCount + 1) }
    }
    fun warn(msg: String) = log("  ! $msg")
    fun fail(msg: String) {
        log("  ✗ $msg")
        _ui.update { it.copy(failCount = it.failCount + 1) }
    }

    /** 累计本次实际关掉的广告开关数（引擎每关一批调用一次）。 */
    fun addClosed(n: Int) {
        if (n > 0) _ui.update { it.copy(closedCount = it.closedCount + n) }
    }

    fun done() = _ui.update { it.copy(running = false, done = true) }
    fun aborted() = _ui.update { it.copy(running = false, aborted = true) }

    fun reset() {
        synchronized(lines) { lines.clear() }
        _ui.value = Ui()
    }
}
