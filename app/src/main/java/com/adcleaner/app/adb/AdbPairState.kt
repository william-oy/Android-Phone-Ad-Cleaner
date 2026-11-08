package com.adcleaner.app.adb

import kotlinx.coroutines.flow.MutableStateFlow

/** 自动配对流程的状态总线：无障碍服务在后台跑，界面订阅展示。 */
object AdbPairState {
    /** 当前进度文案。 */
    val status = MutableStateFlow("")

    /** null=进行中；true=成功；false=失败。 */
    val done = MutableStateFlow<Boolean?>(null)

    /** 成功时记录连接端口。 */
    val connectedPort = MutableStateFlow(-1)

    fun reset() {
        status.value = ""
        done.value = null
        connectedPort.value = -1
    }
}
