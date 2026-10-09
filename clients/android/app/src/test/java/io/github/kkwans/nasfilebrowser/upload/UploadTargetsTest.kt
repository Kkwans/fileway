package io.github.kkwans.nasfilebrowser.upload

import io.github.kkwans.nasfilebrowser.data.SearchResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UploadTargetsTest {
    private fun row(id: String) = UploadRecord(id, 7900001, "original-account", "original-profile", 7,
        "content://owned/$id", id, "application/octet-stream", 1, 1, "/$id", "/$id", "/", "Owned", createdAt = 1, updatedAt = 1)

    @Test fun canceledBatchStopsBeforeNextDispatchAndKeepsUnscheduledRowsForInterruption() = runBlocking {
        val rows = listOf(row("one"), row("two"), row("three"))
        val scheduled = hashSetOf<String>(); val started = arrayListOf<String>(); val failed = arrayListOf<String>()
        val work = Job()
        val error = runCatching { withContext(work) {
            dispatchUploadRows(rows, scheduled, { true }, { started.add(it.id); work.cancel() }) { failed.add(it.id) }
        } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(listOf("one"), started)
        assertEquals(setOf("one"), scheduled)
        assertEquals(listOf("two", "three"), rows.filterNot { it.id in scheduled }.map { it.id })
        assertTrue(failed.isEmpty())
        assertTrue(rows.all { it.accountKey == "original-account" && it.sourceRevision == 7L })
    }

    @Test fun schedulerCancellationIsNotAnOrdinaryFailureAndSourceSwitchStopsNextRow() = runBlocking {
        val rows = listOf(row("one"), row("two"))
        val scheduled = hashSetOf<String>(); var failures = 0
        val error = runCatching {
            dispatchUploadRows(rows, scheduled, { true }, { throw CancellationException("owned cancellation") }) { failures++ }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(scheduled.isEmpty())
        assertEquals(0, failures)
        var current = true; val started = arrayListOf<String>()
        val switched = runCatching {
            dispatchUploadRows(rows, scheduled, { current }, { started.add(it.id); current = false }) { failures++ }
        }.exceptionOrNull()
        assertTrue(switched is IllegalStateException)
        assertEquals(listOf("one"), started)
        assertEquals(setOf("one"), scheduled)
        assertEquals(0, failures)
    }

    private fun draft(name: String, parent: String = "/dir", identity: String? = null, source: String = parent + name,
        wireParent: String = SearchResult.encodePath(parent), choice: UploadConflict = UploadConflict.KEEP_BOTH) =
        UploadDraft(LocalUploadSource("content://owned/$source", name, 1, 1, "application/octet-stream"),
            "$parent/$name", "$wireParent/${SearchResult.encodePath(name)}", identity, choice)

    @Test fun generatedNamesCannotStealLaterOriginalOrExistingServerTarget() = runBlocking {
        val first = draft("a.txt", identity = "old")
        val second = draft("a（2）.txt")
        val remote = setOf(first.targetWire, "/dir/" + SearchResult.encodePath("a（3）.txt"))
        val targets = resolveUploadTargets(listOf(first, second), emptyList()) { it in remote }
        assertEquals(listOf("/dir/a（4）.txt", "/dir/a（2）.txt"), targets.map { it.path })
        assertEquals(2, targets.map { uploadTargetKey(it.wire) }.distinct().size)
    }

    @Test fun repeatedPickedItemDoesNotCreateASecondTask() = runBlocking {
        val source = draft("a.txt")
        val targets = resolveUploadTargets(listOf(source, source.copy()), emptyList()) { false }
        assertEquals(1, targets.size)
        assertEquals(source.targetWire, targets.single().wire)
    }

    @Test fun distinctSourcesCannotShareOriginalCanonicalWireTarget() = runBlocking {
        val first = draft("a.txt", source = "first")
        val second = draft("a.txt", source = "second").copy(targetWire = "/dir/%61.txt")
        var lookups = 0
        val error = runCatching { resolveUploadTargets(listOf(first, second), emptyList()) { lookups++; false } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Duplicate targets must fail before any work starts", 0, lookups)
    }

    @Test fun unresolvedCaseOnlyOriginalsRejectWholeSelectionBeforeLookup() = runBlocking {
        var lookups = 0
        val input = listOf(draft("a.txt"), draft("A.txt"))
        val error = runCatching { resolveUploadTargets(input, emptyList()) { lookups++; false } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("大小写冲突"))
        assertEquals(0, lookups)
        assertEquals(listOf("a.txt", "A.txt"), input.map { it.source.name })
    }

    @Test fun automaticCandidatesReserveCaseAliasesAndCanonicalPendingTargets() = runBlocking {
        val first = draft("a.txt", identity = "old")
        val other = draft("A（2）.txt")
        val reserved = listOf("/dir/%61" + SearchResult.encodePath("（3）.txt"))
        val targets = resolveUploadTargets(listOf(first, other), reserved) { false }
        assertEquals(listOf("/dir/a（4）.txt", "/dir/A（2）.txt"), targets.map { it.path })
    }

    @Test fun pendingOriginalGetsAnotherNameButCannotBeExplicitlyReplaced() = runBlocking {
        val original = draft("a.txt")
        val targets = resolveUploadTargets(listOf(original), listOf("/dir/%61.txt")) { false }
        assertEquals("/dir/a（2）.txt", targets.single().path)
        val error = runCatching { resolveUploadTargets(listOf(original.copy(existingIdentity = "old", choice = UploadConflict.REPLACE)),
            listOf(original.targetWire)) { true } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("未结束的上传"))
    }

    @Test fun relativeDirectoriesAndOpaqueSameDisplayParentsStayDistinct() = runBlocking {
        val inputs = listOf(draft("a.txt", "/one", identity = "old"), draft("a.txt", "/two"),
            draft("a.txt", "/�", source = "opaque-ff", wireParent = "/%FF"),
            draft("a.txt", "/�", source = "opaque-fe", wireParent = "/%FE"))
        val targets = resolveUploadTargets(inputs, emptyList()) { false }
        assertEquals(listOf("/one/a%EF%BC%882%EF%BC%89.txt", "/two/a.txt", "/%FF/a.txt", "/%FE/a.txt"), targets.map { it.wire })
        assertEquals(uploadTargetKey("/%ff/a.txt"), uploadTargetKey("/%FF/%61.txt"))
        assertNotEquals(uploadTargetKey("/%FF/a.txt", true), uploadTargetKey("/%FE/a.txt", true))
    }

    @Test fun skippedOriginalStillReservesItsNameForTheWholePlan() = runBlocking {
        val targets = resolveUploadTargets(listOf(draft("a.txt", identity = "old"),
            draft("a（2）.txt", identity = "old", choice = UploadConflict.SKIP)), emptyList()) { false }
        assertEquals("/dir/a（3）.txt", targets.single().path)
    }
}
