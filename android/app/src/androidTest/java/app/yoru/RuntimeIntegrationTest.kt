package app.yoru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anymex.runtimehost.RuntimeBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeIntegrationTest {
    @Test fun invalidBackupDoesNotReplaceLibrary() {
        val engine = Engine(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = engine.export()
        for (bad in listOf("{}", "null", "{\"library\":[{}]}", "{\"mode\":null}")) {
            assertTrue(runCatching { engine.restore(bad) }.isFailure)
            assertEquals(before, engine.export())
        }
        engine.restore(before)
        assertEquals(before, engine.export())
    }

    @Test fun externalMihonAndCloudstreamExecuteInsideHost() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=Engine(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{engine.initialize()}
        val repo=Repo("Keiyoushi",KEIYOUSHI,"mihon",KEIYOUSHI_SIGNER)
        val (_,packages)=engine.loadRepo(repo)
        val xkcd=packages.first{it.id=="eu.kanade.tachiyomi.extension.all.xkcd"}
        val pending=engine.prepare(xkcd)
        assertEquals(KEIYOUSHI_SIGNER,pending.signer)
        engine.install(pending)
        val source=engine.installedSources().first{it.packageId==xkcd.id&&it.lang=="en"}
        val (titles,_)=engine.browse(source,"",1)
        assertTrue("Source returned titles",titles.isNotEmpty())
        val detail=engine.detail(titles.first())
        assertTrue("Source returned chapters",detail.episodes.isNotEmpty())
        val pages=engine.pages(detail.media,detail.episodes.first())
        assertTrue("Source returned image URLs",pages.isNotEmpty()&&pages.first().url.isNotBlank())
        val request=okhttp3.Request.Builder().url(pages.first().url).apply { pages.first().headers.forEach { (key,value) -> header(key,value) } }.build()
        okhttp3.OkHttpClient().newCall(request).execute().use { response ->
            assertTrue("Page image response", response.isSuccessful)
            val bytes=response.body.bytes()
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            assertTrue("Page image decodes", bounds.outWidth > 0 && bounds.outHeight > 0)
        }
        val plugin=File(context.filesDir,"test-open-cinema.cs3")
        assertTrue("Plugin fixture pushed by test runner",plugin.isFile)
        plugin.setReadOnly()
        assertTrue(RuntimeBridge.csLoadPlugin(context,plugin.absolutePath))
        val cs=Source("Yoru Open Cinema","Yoru Open Cinema","en","cloudstream","test")
        val (movies,_)=engine.browse(cs,"Bunny",1)
        assertEquals("Big Buck Bunny",movies.single().title)
        val film=engine.detail(movies.single())
        val videos=engine.streams(film.media,film.episodes.single())
        assertTrue(videos.single().url.startsWith("https://download.blender.org/"))
        assertEquals("https://download.blender.org",videos.single().headers["Referer"])
        RuntimeBridge.csUnloadPlugin("Yoru Open Cinema")
        engine.remove(engine.user.installed.first{it.extension.id==xkcd.id})
        assertFalse(engine.installedSources().any{it.packageId==xkcd.id})
    }
}
