/*
 * Copyright (C) 2025 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package net.ankio.auto.adapter

import android.net.Uri
import android.os.Bundle
import net.ankio.auto.App
import net.ankio.auto.autoApp
import net.ankio.auto.constant.BookFeatures
import net.ankio.auto.storage.Logger
import net.ankio.auto.ui.utils.ToastUtils
import org.ezbook.server.constant.BillAction
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tally 适配器。
 *
 * Tally 是一个纯本地记账 App（无账号、无服务端），它为自动记账开放了两条写入口：
 * - `content://io.github.guohangbk.tally.autobill` 的 `call("addbill", …)`：推荐，所有 Android 版本
 *   都能校验调用方包名，Tally 默认只接受 [net.ankio.auto] 的写入；
 * - `tally://addbill?...`：通用 URL Scheme，需要用户在 Tally 里打开「允许任意应用」。
 *
 * 这里用 ContentProvider 通道：`call()` 的 `getCallingPackage()` 在 API 28–33 上也可用，
 * 而 `Activity.getLaunchedFromPackage()` 要 34+ 才有——自动账写的是钱，来源能校验比少写几行重要。
 *
 * 字段与钱迹那套 Scheme 对齐（type/money/time/remark/catename/bookname/id），
 * 便于规则、文档与排障共用一套心智模型。
 */
class TallyAdapter : IAppAdapter {
    override val pkg: String
        get() = "io.github.guohangbk.tally"
    override val link: String
        get() = "https://github.com/guohangbk/tally-app"
    override val icon: String
        get() = ""
    override val desc: String
        get() = "纯本地、无账号的安卓记账 App：多账本合并统计、预算、周期与分期；" +
            "数据只存在手机里，自动记账写入后立刻落成一笔带「自动」角标的账单。"
    override val name: String
        get() = "Tally"

    /** Tally 没有账户、资产与转账，也没有手续费概念：只声明多账本，别让表单上出现用不了的开关。 */
    override fun features(): List<BookFeatures> = listOf(BookFeatures.MULTI_BOOK)

    override fun supportSyncAssets(): Boolean = false

    override fun syncAssets() {
        // Tally 没有账户模型，无从同步资产。
    }

    override fun syncWaitBills(billAction: BillAction, bookName: String) {
        // Tally 暂无「待报销 / 待退款」这类可回读的队列。
    }

    override fun syncBill(billInfoModel: BillInfoModel) {
        val (parent, child) = billInfoModel.categoryPair()
        val category = when {
            parent.isEmpty() -> ""
            child.isEmpty() -> parent
            else -> "$parent/::/$child"
        }

        val extras = Bundle().apply {
            putString("type", toTallyType(billInfoModel.type).toString())
            putString("money", yuan(billInfoModel.money))
            putString("time", formatTime(billInfoModel.time))
            putString("remark", billInfoModel.remark)
            if (category.isNotEmpty()) putString("catename", category)
            if (billInfoModel.bookName.isNotEmpty()) putString("bookname", billInfoModel.bookName)
            if (billInfoModel.currency.isNotEmpty()) putString("currency", billInfoModel.currency)
            if (billInfoModel.id > 0) putString("id", "ankio-${billInfoModel.id}")
        }

        val result = runCatching {
            autoApp.contentResolver.call(URI, METHOD_ADD_BILL, null, extras)
        }.onFailure { Logger.e("Tally 写入失败：${it.message}", it) }.getOrNull()

        if (result?.getBoolean(AutoBillProviderContract.EXTRA_OK) == true) {
            AppAdapterManager.markSynced(billInfoModel)
            return
        }

        val message = result?.getString(AutoBillProviderContract.EXTRA_MESSAGE)
            ?: "Tally 没有响应，确认它仍安装在本机"
        Logger.e("Tally 未接收这一笔（${billInfoModel.remark}）：$message")
        // 不动 synced 状态：宁可让它留在待同步里，也不要静默丢一笔。
        // Toast 回主线程：同步流程本身可能在 IO 线程上跑。
        App.launch { ToastUtils.warn("Tally 未记账：$message") }
    }

    override fun sleep(): Long = 0L

    /**
     * 转账与信用卡还款在 Tally 里没有落点（无账户模型）。照常发 2/3，
     * 由 Tally 回一句「暂不支持」——失败要有声音，不能悄悄改成收入或支出。
     */
    private fun toTallyType(type: BillType): Int = when (type) {
        BillType.Expend -> 0
        BillType.Income -> 1
        BillType.Transfer -> 2
        BillType.ExpendReimbursement,
        BillType.ExpendLending,
        BillType.ExpendRepayment -> 0
        BillType.IncomeReimbursement,
        BillType.IncomeLending,
        BillType.IncomeRepayment,
        BillType.IncomeRefund -> 1
    }

    /** Tally 只认人民币；非 CNY 由它自己拒绝，这里不做二次换算。 */
    private fun yuan(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun formatTime(timeMillis: Long): String {
        val value = if (timeMillis > 0) timeMillis else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
    }

    private companion object {
        val URI: Uri = Uri.parse("content://io.github.guohangbk.tally.autobill")
        const val METHOD_ADD_BILL = "addbill"
    }
}

/** 与 Tally 侧 `AutoBillProvider` 的返回 Bundle 对齐。 */
private object AutoBillProviderContract {
    const val EXTRA_OK = "ok"
    const val EXTRA_MESSAGE = "message"
}
