package app.yoru.fixture

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

/** Separately compiled plugin fixture: exercises the actual Cloudstream ABI and DEX loader. */
class OpenCinemaPlugin:Plugin(){override fun load(context:Context){registerMainAPI(OpenCinema())}}
class OpenCinema:MainAPI(){
    override var name="Yoru Open Cinema"
    override var mainUrl="https://download.blender.org"
    override var lang="en"
    override val supportedTypes=setOf(TvType.Movie)
    override suspend fun search(query:String):List<SearchResponse> = if(query.isBlank()||"Big Buck Bunny".contains(query,true)) listOf(newMovieSearchResponse("Big Buck Bunny","https://download.blender.org/peach/bigbuckbunny_movies/",TvType.Movie){posterUrl="https://peach.blender.org/wp-content/uploads/bbb-splash.png"})else emptyList()
    override suspend fun load(url:String):LoadResponse=newMovieLoadResponse("Big Buck Bunny",url,TvType.Movie,"https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_640x360.m4v"){plot="An open animated short by the Blender Foundation, licensed CC BY 3.0.";posterUrl="https://peach.blender.org/wp-content/uploads/bbb-splash.png"}
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {callback(newExtractorLink(name,"Open movie · 360p",data,ExtractorLinkType.VIDEO){quality=360;referer=mainUrl});return true}
}
