package app.yoru.fixture
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.*
import rx.Observable
class OpenCinema : AnimeCatalogueSource {
 override val id = 90000000003L
 override val name = "Open Cinema"
 override val lang = "en"
 override val supportsLatest = true
 private fun title() = SAnime.create().apply { title="Big Buck Bunny"; url="/bunny"; description="An open movie by the Blender Foundation." }
 override fun fetchPopularAnime(page:Int) = Observable.just(AnimesPage(listOf(title()),false))
 override fun fetchLatestUpdates(page:Int) = fetchPopularAnime(page)
 override fun fetchSearchAnime(page:Int,query:String,filters:AnimeFilterList) = fetchPopularAnime(page)
 override suspend fun getAnimeEpisodeUpdate(anime:SAnime, episodes:List<SEpisode>, fetchDetails:Boolean,fetchEpisodes:Boolean) = SAnimeEpisodeUpdate(title(), listOf(SEpisode.create().apply { name="A sunny morning"; url="/bunny/1"; episode_number=1f }))
 override suspend fun getHosterList(episode:SEpisode) = listOf(Hoster("https://download.blender.org", "Blender"))
 override suspend fun getVideoList(hoster:Hoster) = listOf(Video(videoUrl="https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4", videoTitle="180p", headers=okhttp3.Headers.headersOf("Referer","https://download.blender.org"), timestamps=listOf(TimeStamp(0.0,5.0,"Opening"))))
}
