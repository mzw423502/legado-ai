/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.update.AppUpdate
import kotlinx.coroutines.CoroutineScope

object AiAppUpdate : AppUpdate.AppUpdateInterface {
    override fun check(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> = Coroutine.async(scope) {
        throw NoStackTraceException("这是独立签名的阅读 AI 版，请使用本项目提供的 AI 更新包。原版阅读的 APK 不能覆盖升级本应用。")
    }
}
