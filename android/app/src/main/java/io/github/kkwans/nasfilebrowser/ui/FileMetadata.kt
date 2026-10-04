package io.github.kkwans.nasfilebrowser.ui

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val modifiedFormat = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.ROOT)

/** NAS modified is RFC3339. Display its instant in the device's local zone, as the web list does. */
internal fun displayModified(value: String, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (value.isBlank() || value.length > 128) return null
    return try {
        val date = OffsetDateTime.parse(value)
        if (date.year !in 2..9999) null else modifiedFormat.format(date.toInstant().atZone(zone))
    } catch (_: DateTimeException) { null }
}

internal fun fileTypeLabel(file: ResourceRef): String {
    if (file.directory) return "文件夹"
    val kind = when (file.type) { "video" -> "视频"; "image" -> "图片"; "audio" -> "音频"; "text" -> "文本"; else -> "文件" }
    val extension = file.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return if (extension.isEmpty() || extension.length > 12) kind else "$extension $kind"
}
