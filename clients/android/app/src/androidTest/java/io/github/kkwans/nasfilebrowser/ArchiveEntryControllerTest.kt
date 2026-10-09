package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveEntryControllerTest {
    private val entry = ArchiveEntry("movie.mp4", "movie.mp4", "movie.mp4", false, 10, 0)
    private val archive = ResourceRef("/bundle.tar", "/bundle.tar", "bundle.tar", false, "", 2048)
    private val listing = ArchiveListing("/bundle.tar", "/bundle.tar", "tar", 2048, 1000, listOf(entry), 10, 0, emptyList(), false, "", 10000, 8L shl 30, 20L shl 30)
    private class Authority {
        val id = "1".repeat(64)
        var posts = 0; var leases = 0; var revokes = 0; var cancels = 0
        var foreignAsset = false
        var hold: CompletableDeferred<Unit>? = null
        val polling = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        fun status(ready: Boolean) = JSONObject().put("id", id).put("state", if (ready) "ready" else "queued")
            .put("stage", if (ready) "所选文件已就绪" else "等待准备所选条目").put("sourceSize", 2048).put("sourceModified", 1000)
            .put("entryWirePath", "movie.mp4").put("size", if (ready) 10 else 0).put("processedBytes", if (ready) 10 else 0)
            .apply { if (ready) put("asset", if (foreignAsset) "https://foreign.invalid/content" else "/api/archives/open/$id/content") }
        suspend fun context(owner: String): SessionContext {
            val payload = Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val token = "e30.$payload.fixture"
            val profile = ServerProfile(id="owned-$owner",name="Owned archive entry fixture",address="https://archive.invalid")
            val api = NasSession.restore(profile,token,1) { command -> when(command.getString("op")) {
                "open" -> "owned-archive-$owner"
                "token" -> token
                "asset" -> { leases++; check(command.getString("endpoint") == "/api/archives/open/$id/content"); "http://127.0.0.1:32123/stream/owned-entry" }
                "revoke" -> { revokes++; JSONObject() }
                "request" -> {
                    val method = command.getString("method")
                    val body = when(method) {
                        "POST" -> { posts++; val request=command.getJSONObject("body"); check(request.getString("archiveWirePath")=="/bundle.tar" && request.getString("entryWirePath")=="movie.mp4"); status(false) }
                        "GET" -> { polling.complete(Unit); hold?.let { withContext(NonCancellable) { it.await(); finished.complete(Unit) } }; status(true) }
                        "DELETE" -> { cancels++; JSONObject() }
                        else -> error("Unexpected archive entry request")
                    }
                    JSONObject().put("status",if(method=="POST")202 else 200).put("body",body.toString())
                }
                else -> error("Unexpected archive entry operation")
            } }
            return SessionContext(profile,AccountRecord(owner,profile.id,0,1,"fixture","fixture-only",0),api,1,owner)
        }
    }
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }

    @Test fun readyUsesSameServiceLeaseAndTransfersReadOnlyResourceOnlyOnce(): Unit = runBlocking {
        val authority=Authority(); val owner=authority.context("ready"); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var callbacks=0; var ownedLease: PreviewLease?=null
        val controller=ArchiveEntryController(scope,{it===owner}) { bound,file,lease,kind ->
            assertSame(owner,bound); assertEquals("movie.mp4",file.wirePath); assertEquals(ArchiveEntryKind.VIDEO,kind)
            callbacks++; ownedLease=lease
        }
        try {
            main { controller.bind(owner); controller.open(archive,listing,entry,owner.api.id) }
            withTimeout(5000) { controller.state.first { !it.preparing && it.phase=="ready" } }
            assertEquals(1,callbacks); assertEquals(1,authority.posts); assertEquals(1,authority.leases); assertEquals(0,authority.revokes)
            ownedLease!!.release(); assertEquals(1,authority.revokes)
        } finally { ownedLease?.release(); scope.cancel() }
    }
    @Test fun foreignAssetAndWrongSourceNeverReachMediaCallback(): Unit = runBlocking {
        val authority=Authority(); authority.foreignAsset=true; val owner=authority.context("foreign"); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var callbacks=0; val controller=ArchiveEntryController(scope,{it===owner}) { _,_,_,_->callbacks++ }
        try {
            main { controller.bind(owner); controller.open(archive,listing,entry,"wrong-session") }; assertEquals(0,authority.posts)
            main { controller.open(archive,listing,entry,owner.api.id) }
            withTimeout(5000) { controller.state.first { !it.preparing && it.error!=null } }
            assertEquals(0,callbacks); assertEquals(0,authority.leases)
        } finally { scope.cancel() }
    }
    @Test fun lateReadyAfterAccountSwitchIsCanceledWithoutIssuingAnotherAccountLease(): Unit = runBlocking {
        val authority=Authority(); authority.hold=CompletableDeferred(); val old=authority.context("old"); val next=Authority().context("next")
        var current=old; var callbacks=0; val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val controller=ArchiveEntryController(scope,{it===current}) { _,_,_,_->callbacks++ }
        try {
            main { controller.bind(old); controller.open(archive,listing,entry,old.api.id) }
            withTimeout(5000) { authority.polling.await() }
            main { current=next; controller.bind(next) }
            authority.hold!!.complete(Unit); withTimeout(5000) { authority.finished.await() }; main { }
            assertEquals(next.owner,controller.state.value.scope); assertEquals(0,callbacks); assertEquals(0,authority.leases)
        } finally { authority.hold?.complete(Unit); scope.cancel() }
    }
}
