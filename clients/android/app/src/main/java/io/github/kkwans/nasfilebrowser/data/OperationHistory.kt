package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import java.net.URLEncoder

enum class OperationHistoryStatus(val wire: String, val label: String) {
    SUCCESS("success", "成功"), FAILED("failed", "失败"), SUBMITTED("submitted", "已提交");
}

val OPERATION_HISTORY_ACTIONS = linkedMapOf(
    "file.upload" to "上传文件", "file.rename" to "重命名或移动", "file.move" to "移动文件",
    "file.copy" to "复制文件", "file.delete" to "删除文件", "file.mkdir" to "新建文件夹", "file.save" to "保存文件",
    "trash.move" to "移入回收站", "trash.restore" to "恢复文件", "trash.delete" to "永久删除", "trash.clear" to "清空回收站",
    "task.cancel" to "取消任务", "task.retry" to "重试任务", "task.archive" to "归档任务", "task.unarchive" to "移出归档",
    "task.batch.archive" to "批量归档任务", "task.batch.unarchive" to "批量移出归档", "task.batch.retry" to "批量重试任务",
    "analysis.duplicates" to "查找重复文件", "analysis.duplicates.cleanup" to "清理重复文件", "analysis.storage" to "存储分析",
    "archive.extract" to "解压文件", "media.hls" to "媒体兼容处理"
)

/** Target/detail are display values from the server, never decoded or reused as resource wire paths. */
data class OperationHistoryEntry(val id: String, val action: String, val target: String, val detail: String,
    val status: String, val createdAt: Long) {
    val actionLabel get() = OPERATION_HISTORY_ACTIONS[action] ?: action
    val statusLabel get() = OperationHistoryStatus.entries.firstOrNull { it.wire == status }?.label ?: status
    companion object {
        fun from(row: JSONObject): OperationHistoryEntry = OperationHistoryEntry(
            row.getString("id").also { require(it.isNotBlank()) { "操作历史 ID 无效" } },
            row.getString("action"), row.getString("target"), row.optString("detail"), row.getString("status"), row.getLong("createdAt"))
    }
}

data class OperationHistoryFilter(val text: String = "", val action: String = "", val status: OperationHistoryStatus? = null,
    val from: Long = 0, val to: Long = 0) {
    val active get() = text.isNotBlank() || action.isNotEmpty() || status != null || from > 0 || to > 0
    fun query(cursor: String = "", limit: Int = 30): String {
        require(limit in 1..100 && from >= 0 && to >= 0 && (from == 0L || to == 0L || from <= to))
        val values = linkedMapOf("limit" to limit.toString())
        if (text.isNotBlank()) values["text"] = text.trim()
        if (action.isNotEmpty()) values["action"] = action
        status?.let { values["status"] = it.wire }
        if (from > 0) values["from"] = from.toString()
        if (to > 0) values["to"] = to.toString()
        if (cursor.isNotEmpty()) values["cursor"] = cursor
        return "/api/history?" + values.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
    }
}

data class OperationHistoryPage(val items: List<OperationHistoryEntry>, val nextCursor: String, val total: Int) {
    companion object {
        fun from(response: JSONObject): OperationHistoryPage {
            val rows = response.getJSONArray("items")
            val total = response.getInt("total").also { require(it >= 0) { "操作历史总数无效" } }
            return OperationHistoryPage((0 until rows.length()).map { OperationHistoryEntry.from(rows.getJSONObject(it)) },
                response.optString("nextCursor"), total)
        }
    }
}
