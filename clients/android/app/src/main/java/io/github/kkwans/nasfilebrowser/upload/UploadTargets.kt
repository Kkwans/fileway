package io.github.kkwans.nasfilebrowser.upload

import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.resourceWireBytes
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal data class ResolvedUploadDraft(val draft: UploadDraft, val path: String, val wire: String)

/** Percent spelling is not identity. Opaque parent bytes remain authoritative. */
internal fun uploadTargetKey(wire: String, foldCase: Boolean = false): List<Byte> {
    val bytes = resourceWireBytes(wire)
    require(wire != "/" && !Regex("%2[fF]|%00").containsMatchIn(wire) &&
        bytes.toString(Charsets.ISO_8859_1).removePrefix("/").split('/').none { it.isEmpty() || it == "." || it == ".." || it.contains('\u0000') }) {
        "上传目标原始路径无效，请重新选择目录"
    }
    if (!foldCase) return bytes.toList()
    val text = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    // Conservative name reservation, not a claim about the remote filesystem.
    // Invalid UTF-8 must never be replaced with a Unicode sibling's display name.
    return text?.uppercase(Locale.ROOT)?.lowercase(Locale.ROOT)?.toByteArray(Charsets.UTF_8)?.toList()
        ?: bytes.map { if (it.toInt() in 65..90) (it + 32).toByte() else it }
}

/** Resolve the entire selection before creating any jobs. Original names are
 * reserved even for later/skipped rows, so generated names cannot steal them. */
internal suspend fun resolveUploadTargets(drafts: List<UploadDraft>, reservedWires: List<String>,
    exists: suspend (String) -> Boolean): List<ResolvedUploadDraft> {
    val selection = drafts.distinct()
    val originals = selection.map { uploadTargetKey(it.targetWire) }
    require(originals.distinct().size == originals.size) { "所选来源有重复目标名称，请分批选择" }
    val folded = selection.map { uploadTargetKey(it.targetWire, foldCase = true) }
    require(folded.distinct().size == folded.size) {
        "本批目标存在大小写冲突，尚不能确认服务器目录区分大小写；请改名或分批上传，当前选择保留"
    }
    val reserved = reservedWires.map { uploadTargetKey(it, foldCase = true) }.toMutableSet()
    val unavailable = (folded + reserved).toMutableSet()
    val claimed = hashSetOf<List<Byte>>()
    val resolved = arrayListOf<ResolvedUploadDraft>()
    for (draft in selection) {
        if (draft.existingIdentity != null && draft.choice == UploadConflict.SKIP) continue
        val original = uploadTargetKey(draft.targetWire, foldCase = true)
        val priorTask = original in reserved
        require(!priorTask || draft.choice != UploadConflict.REPLACE) { "该目标已有未结束的上传，请先继续或清理原任务；当前选择保留" }
        var path = draft.targetPath; var wire = draft.targetWire
        if (draft.choice == UploadConflict.KEEP_BOTH && (draft.existingIdentity != null || priorTask || exists(wire))) {
            val name = draft.source.name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var suffix = 2
            while (true) {
                require(suffix <= 1000) { "同名文件太多，请换一个目录或重命名本机来源" }
                val candidate = name.substring(0, dot) + "（${suffix++}）" + name.substring(dot)
                path = draft.targetPath.substringBeforeLast('/') + "/" + candidate
                wire = draft.targetWire.substringBeforeLast('/') + "/" + SearchResult.encodePath(candidate)
                val key = uploadTargetKey(wire, foldCase = true)
                if (key !in unavailable && !exists(wire)) break
                unavailable.add(key)
            }
        }
        val key = uploadTargetKey(wire, foldCase = true)
        require(claimed.add(key)) { "最终上传目标重复，未启动上传，请重新选择" }
        unavailable.add(key); reserved.add(key)
        resolved.add(ResolvedUploadDraft(draft, path, wire))
    }
    return resolved
}
