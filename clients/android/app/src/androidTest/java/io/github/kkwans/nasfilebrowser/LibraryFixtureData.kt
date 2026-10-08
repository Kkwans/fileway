package io.github.kkwans.nasfilebrowser

import io.github.kkwans.nasfilebrowser.data.SearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Owned HTTP authority for native library UI. No real NAS data or private media. */
internal class LibraryFixtureData {
    val tags = JSONArray()
    val trash = JSONArray()
    val tasks = JSONArray()
    val files = linkedMapOf<String, JSONObject>()
    val mutations = mutableListOf<Pair<String, String>>()
    val snapshots = mutableMapOf<String, Pair<String, String>>()
    val reports = linkedMapOf<String, JSONObject>()
    var conflictOnce = false
    @Volatile var rejectNextTagWrite = false
    @Volatile var rejectNextFavoriteWrite = false
    @Volatile var rejectTrashPathOnce: String? = null
    val trashAttempts = mutableListOf<String>()
    var renameAllowed = false
    var liveDirectoryListing = false
    @Volatile var rejectNextRename = false
    val renameAttempts = mutableListOf<String>()
    @Volatile var rejectNextTransferStatus = 0
    val transferAttempts = mutableListOf<JSONObject>()
    private val transferJobs = linkedMapOf<String, JSONObject>()
    private var sequence = 0
    fun file(path: String, directory: Boolean = false) {
        files[path] = JSONObject().put("path", path).put("wirePath", SearchResult.encodePath(path)).put("name", path.substringAfterLast('/'))
            .put("isDir", directory).put("type", if (directory) "" else "image").put("size", 1234).put("modified", "2026-10-08T01:00:00Z")
    }
    fun task(id: String, type: String, status: String, title: String): JSONObject = JSONObject().put("id", id).put("userId", 1).put("ownerName", "one")
        .put("type", type).put("status", status).put("title", title).put("createdAt", System.currentTimeMillis()).put("totalItems", 100).put("processedItems", 35)
        .put("totalBytes", 0).put("processedBytes", 0)
    private fun transferPath(value: String) = URLDecoder.decode(value.removePrefix("/files").replace("+", "%2B"), "UTF-8")
    fun finishTransfers(favorites: JSONArray) = synchronized(this) {
        for ((id, input) in transferJobs.toMap()) {
            val task = row(tasks, id) ?: continue
            if (task.getString("status") != "queued") continue
            val moving = input.getString("action") == "move"
            val entries = input.getJSONArray("items")
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i); val from = item.getString("from"); val to = item.getString("to")
                fun changed(value: String) = value == from || value.startsWith("$from/")
                val copied = files.filterKeys(::changed).map { (path, data) -> (to + path.removePrefix(from)) to JSONObject(data.toString()) }
                if (moving) files.keys.toList().filter(::changed).forEach(files::remove)
                if (item.optBoolean("overwrite")) files.keys.toList().filter { it == to || it.startsWith("$to/") }.forEach(files::remove)
                for ((path, data) in copied) { data.put("path", path).put("wirePath", SearchResult.encodePath(path)).put("name", path.substringAfterLast('/')); files[path] = data }
                if (moving) {
                    for (f in 0 until favorites.length()) favorites.getJSONObject(f).let { data ->
                        val path = data.getString("path"); if (changed(path)) data.put("path", to + path.removePrefix(from))
                    }
                    for (t in 0 until tags.length()) tags.getJSONObject(t).getJSONArray("paths").let { paths ->
                        for (p in 0 until paths.length()) { val path = paths.getString(p); if (changed(path)) paths.put(p, to + path.removePrefix(from)) }
                    }
                }
            }
            task.put("status", "completed").put("processedItems", entries.length()).put("processedBytes", task.optLong("totalBytes"))
                .put("finishedAt", System.currentTimeMillis())
        }
    }
    private fun row(rows: JSONArray, id: String) = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    private fun query(uri: URI) = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
    fun route(method: String, uri: URI, body: String, favorites: JSONArray, reply: (String, Int) -> Unit): Boolean = synchronized(this) {
        val path = uri.path; val input = if (body.isBlank()) JSONObject() else JSONObject(body)
        if (path.startsWith("/api/favorites") && method != "GET" && rejectNextFavoriteWrite) {
            rejectNextFavoriteWrite = false
            reply("{\"error\":\"本次收藏保存失败，请重试\"}", 503)
            return true
        }
        if (path == "/api/analysis/recent") {
            val tool = query(uri)["tool"] ?: "storage"
            val values = (0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { it.getString("type") == "analysis.$tool" }
                .map { JSONObject().put("id", it.getString("id")).put("tool", tool).put("status", it.getString("status")).put("createdAt", it.optLong("createdAt"))
                    .put("scopes", JSONArray().put("/scan")).put("resultReady", reports.containsKey(it.getString("id"))) }
            reply(JSONObject().put("items", JSONArray(values)).toString(), 200); return true
        }
        if (path == "/api/volumes") { reply("{}", 403); return true }
        if (path in setOf("/api/analysis/storage", "/api/analysis/duplicates") && method == "POST") {
            val type = if (path.endsWith("storage")) "analysis.storage" else "analysis.duplicates"
            val value = task("analysis-${++sequence}", type, "queued", "扫描样本")
            tasks.put(value); mutations.add(method to path); reply(value.toString(), 202); return true
        }
        if (path.startsWith("/api/analysis/")) {
            if (path.endsWith("/cleanup") && method == "GET") { reply("{}", 404); return true }
            val result = reports[path.substringAfterLast('/')]
            if (result != null) { reply(result.toString(), 200); return true }
        }
        if (path == "/api/resources/batch" && method == "POST") {
            val paths = input.getJSONArray("paths"); val result = JSONArray()
            for (i in 0 until paths.length()) {
                val name = paths.getString(i); val value = files[name]
                result.put(JSONObject().put("path", name).put("status", if (value == null) 404 else 200).apply { if (value != null) put("item", value) })
            }
            reply(result.toString(), 200); return true
        }
        if (path == "/api/resources/transfer" && method == "POST") {
            transferAttempts.add(JSONObject(input.toString()))
            if (rejectNextTransferStatus != 0) { val status = rejectNextTransferStatus; rejectNextTransferStatus = 0; reply("{}", status); return true }
            check(input.getString("action") in setOf("copy", "move"))
            val requested = input.getJSONArray("items"); val normalized = JSONArray()
            var totalBytes = 0L
            for (i in 0 until requested.length()) {
                val entry = JSONObject(requested.getJSONObject(i).toString())
                val from = transferPath(entry.getString("from")); var to = transferPath(entry.getString("to"))
                if (!files.containsKey(from)) { reply("{}", 404); return true }
                if (from == to) { reply("{}", 400); return true }
                if (files.containsKey(to)) {
                    if (entry.optBoolean("rename")) {
                        val dot = to.lastIndexOf('.').takeIf { it > to.lastIndexOf('/') } ?: to.length
                        val base = to.substring(0, dot); val extension = to.substring(dot); var version = 1
                        do { to = "$base(${version++})$extension" } while (files.containsKey(to))
                    } else if (!entry.optBoolean("overwrite")) { reply("{}", 409); return true }
                }
                totalBytes += files.getValue(from).optLong("size")
                normalized.put(entry.put("from", from).put("to", to))
            }
            val id = "transfer-${++sequence}"
            val task = task(id, "file.${input.getString("action")}", "queued", "${if (input.getString("action") == "copy") "复制" else "移动"} ${requested.length()} 项")
                .put("totalItems", requested.length()).put("processedItems", 0).put("totalBytes", totalBytes)
            tasks.put(task); transferJobs[id] = JSONObject(input.toString()).put("items", normalized)
            mutations.add(method to path); reply(task.toString(), 202); return true
        }
        if (path.startsWith("/api/resources/") && method == "GET" && uri.rawQuery == "metadata=1" && files.containsKey(path.removePrefix("/api/resources"))) {
            reply(files.getValue(path.removePrefix("/api/resources")).toString(), 200); return true
        }
        if (liveDirectoryListing && path.startsWith("/api/resources/") && method == "GET" && uri.rawQuery == null) {
            val directory = path.removePrefix("/api/resources").trimEnd('/')
            reply(JSONObject().put("items", JSONArray(files.filterKeys { it.substringBeforeLast('/') == directory }.values.toList())).toString(), 200)
            return true
        }
        if (path.startsWith("/api/resources/") && method == "PATCH") {
            val q = query(uri)
            check(q["action"] == "rename" && q["override"] == "false" && q["rename"] == "false")
            renameAttempts.add(uri.rawPath + "?" + uri.rawQuery)
            if (!renameAllowed) { reply("{}", 403); return true }
            if (rejectNextRename) { rejectNextRename = false; reply("{}", 503); return true }
            val original = path.removePrefix("/api/resources"); val destination = q.getValue("destination")
            if (!files.containsKey(original)) { reply("{}", 404); return true }
            if (files.containsKey(destination)) { reply("{}", 409); return true }
            fun rewrite(value: String) = if (value == original || value.startsWith("$original/")) destination + value.removePrefix(original) else value
            for (name in files.keys.toList().filter { rewrite(it) != it }) {
                val renamed = rewrite(name); val row = files.remove(name)!!
                row.put("path", renamed).put("wirePath", SearchResult.encodePath(renamed))
                if (name == original) row.put("name", destination.substringAfterLast('/'))
                files[renamed] = row
            }
            for (i in 0 until favorites.length()) favorites.getJSONObject(i).let { it.put("path", rewrite(it.getString("path"))) }
            for (i in 0 until tags.length()) tags.getJSONObject(i).getJSONArray("paths").let { paths ->
                for (j in 0 until paths.length()) paths.put(j, rewrite(paths.getString(j)))
            }
            mutations.add(method to path); reply("", 200); return true
        }
        if (path.startsWith("/api/tags")) {
            if (method != "GET" && rejectNextTagWrite) {
                rejectNextTagWrite = false
                reply("{\"error\":\"本次标记保存失败，请重试\"}", 503)
                return true
            }
            if (method != "GET") mutations.add(method to path)
            if (path == "/api/tags") {
                if (method == "GET") reply(tags.toString(), 200)
                else {
                    val value = JSONObject(input.toString()).put("id", "tag-${++sequence}").put("paths", JSONArray())
                    tags.put(value); reply(value.toString(), 201)
                }
            } else {
                val id = path.removePrefix("/api/tags/").substringBefore('/'); val value = row(tags, id)
                if (value == null) reply("{}", 404)
                else if (path.endsWith("/paths")) {
                    val paths = value.getJSONArray("paths"); val wanted = input.getString("path")
                    val index = (0 until paths.length()).firstOrNull { paths.getString(it) == wanted }
                    if (method == "POST" && index == null) paths.put(wanted)
                    if (method == "DELETE" && index != null) paths.remove(index)
                    reply(value.toString(), 200)
                } else if (method == "DELETE") {
                    tags.remove((0 until tags.length()).single { tags.getJSONObject(it).getString("id") == id }); reply("", 204)
                } else { input.keys().forEach { value.put(it, input.get(it)) }; reply(value.toString(), 200) }
            }
            return true
        }
        if (path.startsWith("/api/resources/") && method == "DELETE") {
            check(query(uri)["mode"] == "trash")
            val original = path.removePrefix("/api/resources")
            trashAttempts.add(uri.rawPath)
            if (original == rejectTrashPathOnce) {
                rejectTrashPathOnce = null
                reply("{\"error\":\"本次回收操作失败，请重试\"}", 503)
                return true
            }
            val value = files.remove(original)
            if (value == null) { reply("{}", 404); return true }
            val id = "trash-${++sequence}"
            snapshots[id] = favorites.toString() to tags.toString()
            for (i in favorites.length() - 1 downTo 0) if (favorites.getJSONObject(i).getString("path") == original) favorites.remove(i)
            for (i in 0 until tags.length()) {
                val paths = tags.getJSONObject(i).getJSONArray("paths")
                for (p in paths.length() - 1 downTo 0) if (paths.getString(p) == original) paths.remove(p)
            }
            val item = JSONObject().put("id", id).put("userId", 1).put("ownerName", "one").put("originalPath", original)
                .put("name", value.getString("name")).put("isDir", value.getBoolean("isDir")).put("size", value.optLong("size"))
                .put("sizeState", "accurate").put("deletedAt", System.currentTimeMillis()).put("status", "available")
            trash.put(item); mutations.add(method to path); reply(item.toString(), 200); return true
        }
        if (path == "/api/trash") { check(method == "GET"); reply(trash.toString(), 200); return true }
        if (path.startsWith("/api/trash/")) {
            mutations.add(method to path)
            val id = path.removePrefix("/api/trash/").substringBefore('/'); val item = row(trash, id)
            if (item == null) { reply("{}", 404); return true }
            if (path.endsWith("/restore")) {
                if (conflictOnce && input.optString("conflict") == "fail") { conflictOnce = false; reply("{}", 409); return true }
                if (input.optString("conflict") == "skip") { reply(JSONObject().put("path", item.getString("originalPath")).put("skipped", true).toString(), 200); return true }
                val original = item.getString("originalPath"); file(original, item.getBoolean("isDir"))
                snapshots.remove(id)?.let { (savedFavorites, savedTags) ->
                    while (favorites.length() > 0) favorites.remove(0)
                    JSONArray(savedFavorites).let { rows -> for (i in 0 until rows.length()) favorites.put(rows.getJSONObject(i)) }
                    while (tags.length() > 0) tags.remove(0)
                    JSONArray(savedTags).let { rows -> for (i in 0 until rows.length()) tags.put(rows.getJSONObject(i)) }
                }
                trash.remove((0 until trash.length()).single { trash.getJSONObject(it).getString("id") == id })
                reply(JSONObject().put("path", original).put("skipped", false).toString(), 200)
            } else if (path.endsWith("/size")) {
                val task = task("size-${++sequence}", "trash.size", "queued", "统计大小样本"); tasks.put(task); reply(task.toString(), 202)
            } else error("Direct permanent deletion must not be used by the UI")
            return true
        }
        if (path == "/api/deletions/pending") {
            check(method == "POST" && input.getString("kind") in setOf("trash-items", "trash-all"))
            val task = task("delete-${++sequence}", if (input.getString("kind") == "trash-all") "trash.clear" else "trash.delete.permanent", "queued", "回收站删除样本")
                .put("undoUntil", System.currentTimeMillis() + 20_000)
            tasks.put(task); mutations.add(method to path); reply(task.toString(), 202); return true
        }
        if (path == "/api/tasks" && method == "GET") {
            val q = query(uri); val category = q["category"] ?: "background"
            val base = (0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { value ->
                (category == "file") == (value.getString("type") in setOf("file.copy", "file.move")) &&
                    (q["type"].isNullOrEmpty() || q["type"] == value.getString("type")) &&
                    (q["user"].isNullOrEmpty() || q["user"] == value.getString("ownerName")) &&
                    (q["text"].isNullOrEmpty() || value.getString("title").contains(q["text"]!!, true))
            }
            val statuses = q["status"].orEmpty().split(',').filter { it.isNotEmpty() }
            val matched = base.filter { (it.optLong("archivedAt") > 0) == (q["archived"] == "true") && (statuses.isEmpty() || it.getString("status") in statuses) }.sortedByDescending { it.optLong("createdAt") }
            val offset = q["cursor"]?.substringAfterLast(':')?.toInt() ?: 0; val limit = q["limit"]?.toInt() ?: 30
            val counts = JSONObject().put("all", base.count { it.optLong("archivedAt") == 0L }).put("archived", base.count { it.optLong("archivedAt") > 0L })
                .put("active", base.count { it.getString("status") in setOf("queued", "running") }).put("attention", base.count { it.getString("status") in setOf("failed", "interrupted") })
                .put("completed", base.count { it.getString("status") == "completed" }).put("canceled", base.count { it.getString("status") == "canceled" })
            val value = JSONObject().put("items", JSONArray(matched.drop(offset).take(limit))).put("total", matched.size).put("counts", counts).put("owners", JSONArray(listOf("one")))
            if (matched.size > offset + limit) value.put("nextCursor", "owned+/=%:${offset + limit}")
            reply(value.toString(), 200); return true
        }
        if (path.startsWith("/api/tasks/") && !path.endsWith("/batch")) {
            val id = path.removePrefix("/api/tasks/").substringBefore('/'); val value = row(tasks, id)
            if (value == null) { reply("{}", 404); return true }
            if (method == "POST") {
                mutations.add(method to path)
                when (path.substringAfterLast('/')) {
                    "cancel" -> value.put("status", "canceled")
                    "archive" -> value.put("archivedAt", System.currentTimeMillis())
                    "unarchive" -> value.put("archivedAt", 0)
                    "retry" -> {
                        val retry = task("retry-${++sequence}", value.getString("type"), "queued", value.getString("title"))
                        tasks.put(retry); reply(retry.toString(), 202); return true
                    }
                    else -> error("Unexpected task mutation")
                }
            }
            reply(value.toString(), 200); return true
        }
        false
    }
}
