/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import io.legado.app.ai.core.*
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.Executors

object AiRuntime {
    lateinit var store: FileProjectStore; private set
    lateinit var reader: AiReader; private set
    lateinit var settings: AiApiSettings; private set
    lateinit var actions: ProjectActions; private set
    val io = Executors.newFixedThreadPool(3)
    @Volatile var runningId: String? = null; private set
    @Volatile private var cancellation: Cancellation? = null
    @Volatile private var initialized = false
    @Synchronized fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        settings = AiApiSettings(app)
        store = FileProjectStore(File(app.noBackupFilesDir, "ai_projects"))
        reader = AiReader(app)
        actions = ProjectActions(store, reader, cancelNetwork = { id, _ ->
            if (runningId == id) cancellation?.cancel()
        })
        actions.recoverInterrupted(); initialized = true
    }
    @Synchronized fun claim(id: String): Cancellation {
        check(runningId == null) { "已有小说正在创作，请先停止当前任务" }
        val token = Cancellation(); runningId = id; cancellation = token; return token
    }
    @Synchronized fun release(id: String, token: Cancellation) {
        if (runningId == id && cancellation === token) { runningId = null; cancellation = null }
    }
    fun stop(id: String) { if (runningId == id) cancellation?.cancel(); actions.stop(id) }
    fun beforeDelete(bookUrl: String) {
        if (!bookUrl.contains("/ai_reading/")) return
        init(appCtx)
        val id = reader.projectId(bookUrl) ?: return
        val p = store.get(id) ?: return
        if (p.deletedAt == null) actions.trash(id)
    }
    fun engine(id: String, context: Context): NovelEngine {
        val demo = settings.isDemo(id)
        return NovelEngine(store, if (demo) AiDemoGateway() else AiHttpGateway(settings), reader,
            device = object : DeviceConditions {
                override fun pauseReason(limits: RunLimits): String? {
                    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                    if (level >= 0 && scale > 0 && plugged == 0 && level * 100 / scale < limits.minBattery)
                        return "电量低于 ${limits.minBattery}%，已暂停新请求"
                    if (!demo) {
                        val net = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val caps = net.getNetworkCapabilities(net.activeNetwork)
                        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) return "网络不可用，已暂停"
                    }
                    return null
                }
            })
    }
    fun sync(id: String) {
        val p = store.get(id) ?: return
        if (p.pendingPublish) engine(id, appCtx).synchronizeReader(id)
    }
}

/** An explicitly selected offline fixture. Never a fallback for failed paid requests. */
class AiDemoGateway : CompletionGateway {
    override fun complete(request: CompletionRequest, cancellation: Cancellation, onPartial: (String) -> Unit): CompletionResult {
        val chapter = Regex("为第 (\\d+) 章|写第 (\\d+) 章").find(request.messages.last().content)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: "1"
        val text = when (request.purpose) {
            Purpose.PLAN -> "离线演示章节卡：林砚在旧码头寻找失踪的递信人，发现一枚有潮汐刻痕的铜扣。冲突在于必须在退潮前决定是否信任摆渡人。保留灯塔信号与旧案的联系，不揭露幕后身份。"
            Purpose.BODY -> "第${chapter}章 退潮之前\n\n" + AiDemoText.body
            Purpose.MEMORY -> "离线演示记忆：林砚仍在雾港调查失踪的递信人；人物状态：衣袖被雨水打湿，持有旧信与铜扣，对摆渡人保持谨慎信任。时间线：入夜至退潮前。地点：旧码头、潮汐门。伏笔 F01：铜扣三道刻痕，含义待定；F02：灯塔逆时闪烁，尚未回收。尚未揭露幕后人物身份。"
            Purpose.CONNECTION_TEST -> "离线演示，不连接 API"
        }
        val out = StringBuilder()
        for (chunk in text.chunked(180)) {
            cancellation.check(); out.append(chunk); onPartial(out.toString()); Thread.sleep(35)
        }
        return CompletionResult(text, "stop")
    }
}
object AiDemoText {
    val body = """雾港的雨直到晚钟响过才停。林砚站在旧码头的屋檐下，把那封没有署名的信翻到背面。纸边泛着淡淡的盐白，封口却很干净，像是有人特意护着它走过了海。

卖热汤的老妇正收拾炉子，见他第三次望向灯塔，终于开口：“你等的人，未必从那边来。”

“您见过他？”林砚问。

老妇没有答，只拿木勺敲了敲铜锅。空巷里响了三声。他下意识数着，忽然想起信纸右下角也有三个压得极浅的凹点。他抬起头时，老妇已经拎着炉子走进了暗门，门后的灯灭得比街上任何一家都早。

码头尽头有人在解缆。那人穿灰色蓑衣，右脚落地比左脚轻，船篷上还挂着半盏破灯。林砚沿着湿滑的木板走过去，没有直接问递信人的下落。他在离船三步远的地方停住，摊开掌心，让那枚铜扣露出来。

摆渡人的手停在绳结上。

“这不是你的东西。”

“那么你知道它是谁的。”

浪从桥墩下卷过，发出揉皱纸张似的声音。摆渡人把绳结重新拉紧，没有看林砚的脸。他望着岸边不断降低的水痕，仿佛那比任何问话都要紧。

“上船要两枚银币。”他说。

林砚口袋里只有一枚。他没有讨价还价，而是把旧信压在铜扣下面，一起放在脚边的木箱上。摆渡人终于转过身来，目光在封口上停了片刻。

“你没拆？”

“信不是给我的。”

“拿着它找人的时候，你倒没这样想。”

林砚的手指在袖口里收紧。半天前，他也可以把信交给巡街的差役，让别人去问这些麻烦的问题。但递信人临走前说过一句很轻的话：别让灯塔先看到它。当时他只当是笑话，现在灯塔的光每扫过一次，他都会往木箱后面退半步。

摆渡人蹲下来，用一根细竹签拨开铜扣背面的淤泥。三道刻痕并不平行，中间那道更深，末端微微弯向右侧。林砚看见他指甲下同样沾着青灰色的泥，而码头这一侧，只有黑色的烂沙。

“退潮以后，门才会露出来。”摆渡人把铜扣推回给他，“但这一次，只能带一个人。”

“谁在等？”

“我不知道。”

林砚盯着他。摆渡人的眼睛没有躲，拇指却反复摩挲着那根竹签，直到签尾断在掌心。他这才明白，对方也许没有说谎，只是把知道的另一半留在了嘴里。

远处的灯塔忽然灭了一息。再亮时，光柱没有顺着海面转过去，而是慢慢扫回城里。岸上有扇窗随之合拢，紧接着，第二扇，第三扇。街道像被一只看不见的手逐段折起。

林砚捡起信和铜扣，跨过船舷。船底很凉，积水没过鞋边。他坐稳以后才发现，篷下还放着一只小布包，包口露出递信人惯用的红绳。

他伸手去碰，摆渡人却先用船桨挡住了。

“到了再看。”

绳索从木桩上滑落，小船离开码头。林砚没有再问。他记住了布包打结的方向，也记住了摆渡人说那句话时，望向的是城里，不是海。

身后的晚钟又响了一次，比惯常多出半拍。雾从水面升起来，旧码头渐渐缩成一条灰线。林砚把信收进内袋，将铜扣扣在自己的袖口上。三道刻痕贴着腕骨，凉意清楚得像一声尚未说完的提醒。

船行到桥影底下时，摆渡人熄了灯。黑暗并没有立刻变得完整，前方水线之下，有一道窄窄的亮光正随着退去的潮水露出来。

“从现在起，”摆渡人压低声音，“听到自己的名字，也不要答。”"""
}
