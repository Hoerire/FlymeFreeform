package io.github.hoerire.free.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SystemUI 侧的分身目录发布器。
 *
 * 应用打开气泡设置页时发一条 request，这里扫一遍当前所有分身用户，把结果定向发回模块应用。
 * 与 [CloneCatalogRepository] 共用一套协议，但请求/应答两端都在本模块自己的进程里，
 * 不经过 system_server，因此不需要整机重启——装完模块重启一次 SystemUI 就能用。
 */
internal class CloneCatalogPublisher(
    private val context: Context,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val executor =
        Executors.newSingleThreadExecutor { task -> Thread(task, "Free-BubbleClones") }
    private val scanner = CloneCatalogScanner(context, logger)

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != CloneCatalogProtocol.ACTION_REQUEST) return
                if (intent.getStringExtra(CloneCatalogProtocol.EXTRA_REQUESTER_PACKAGE) !=
                    CloneCatalogProtocol.MODULE_PACKAGE
                ) {
                    return
                }
                requestPublish()
            }
        }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(CloneCatalogProtocol.ACTION_REQUEST),
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { logger("BUBBLE_CLONE_RECEIVER_FAILED", it) }
        requestPublish()
    }

    private fun requestPublish() {
        executor.execute {
            runCatching { publish(scanner.scan()) }
                .onFailure { logger("BUBBLE_CLONE_SCAN_FAILED", it) }
        }
    }

    private fun publish(entries: List<CloneApp>) {
        context.sendBroadcast(
            Intent(CloneCatalogProtocol.ACTION_CATALOG)
                .setPackage(CloneCatalogProtocol.MODULE_PACKAGE)
                .putStringArrayListExtra(
                    CloneCatalogProtocol.EXTRA_ENTRIES,
                    CloneCatalogProtocol.encode(entries),
                ),
        )
        logger("BUBBLE_CLONE_CATALOG_" + entries.size, null)
    }
}
