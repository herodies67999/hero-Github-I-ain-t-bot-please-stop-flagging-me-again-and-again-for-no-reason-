@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.yoru

import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val Ink = Color(0xFF0B0911)
private val Panel = Color(0xFF19151F)
private val Muted = Color(0xFFA39AAE)
private val Lilac = Color(0xFFC2A5FF)
private val Editorial = FontFamily(Font(R.font.manrope))

@Composable
fun YoruApp(model: AppModel) {
    val light = model.user.theme == "light"
    val accent = Color(model.user.accent)
    val colors =
        if (light)
            lightColorScheme(
                primary = accent,
                surface = Color(0xFFF7F2FF),
                background = Color(0xFFF7F2FF),
            )
        else
            darkColorScheme(
                primary = accent,
                surface = if (model.user.theme == "oled") Color.Black else Ink,
                background = Ink,
                surfaceVariant = Panel,
                onSurface = Color(0xFFF4EFFA),
            )
    MaterialTheme(
        colorScheme = colors,
        typography =
            Typography(
                headlineLarge =
                    Typography()
                        .headlineLarge
                        .copy(fontFamily = Editorial, fontWeight = FontWeight.Bold)
            ),
    ) {
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(model.notice) {
            model.notice?.let {
                snack.showSnackbar(it)
                model.notice = null
            }
        }
        BackHandler(
            enabled = model.reading != null || model.detail != null || model.screen != "home"
        ) {
            when {
                model.reading != null -> model.reading = null
                model.detail != null -> model.detail = null
                else -> model.screen = "home"
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (model.reading == null && model.detail == null) BottomNavigation(model)
            },
        ) { insets ->
            Box(
                Modifier.fillMaxSize()
                    .padding(
                        bottom =
                            if (model.reading == null && model.detail == null)
                                insets.calculateBottomPadding()
                            else 0.dp
                    )
            ) {
                AnimatedContent(
                    targetState = model.screen,
                    label = "navigation",
                    transitionSpec = {
                        fadeIn(tween(220)) + slideInHorizontally { it / 16 } togetherWith
                            fadeOut(tween(140))
                    },
                ) { screen ->
                    when (screen) {
                        "extensions" -> ExtensionsScreen(model)
                        "library" -> LibraryScreen(model)
                        "history" -> HistoryScreen(model)
                        "settings" -> SettingsScreen(model)
                        "browse" -> BrowseScreen(model)
                        else -> DiscoverScreen(model)
                    }
                }
                AnimatedVisibility(
                    model.detail != null,
                    enter = fadeIn() + scaleIn(initialScale = .96f),
                    exit = fadeOut() + scaleOut(targetScale = .98f),
                ) {
                    model.detail?.let { DetailScreen(model, it) }
                }
                model.reading?.let { ReaderScreen(model, it) }
                if (model.busy != null)
                    Box(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(30.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 16.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(model.busy!!, fontSize = 12.sp)
                            }
                        }
                    }
            }
        }
        model.error?.let {
            AlertDialog(
                onDismissRequest = { model.error = null },
                title = { Text("Couldn’t finish that") },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { model.error = null }) { Text("Close") } },
            )
        }
        model.sourcePicker?.let { media ->
            ModalBottomSheet(onDismissRequest = { model.sourcePicker = null }) {
                Column(Modifier.padding(24.dp)) {
                    Eyebrow("FIND YOUR STORY")
                    Text(media.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Search an installed ${if(model.user.mode=="manga")"manga" else "anime"} source",
                        color = Muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    val sources = model.sources.filter { it.manga == (model.user.mode == "manga") }
                    if (sources.isEmpty()) {
                        Text("Install an extension to start exploring.")
                        Primary("Open extensions", Icons.Rounded.Extension) {
                            model.sourcePicker = null
                            model.screen = "extensions"
                        }
                    }
                    sources.forEach { SourceRow(it) { model.browse(it, media.title) } }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
        model.pending?.let { p ->
            AlertDialog(
                onDismissRequest = model::discard,
                title = { Text("Trust ${p.entry.name}?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Extensions run code on your device. Install only from a source you trust."
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "${p.entry.type.uppercase()} · ${p.entry.version}",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (p.signer.isNotEmpty()) "APK signature verified. Signer:"
                            else "Cloudstream plugins do not provide APK signing. Package SHA-256:",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            p.signer.ifEmpty { p.digest },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "Updates require this repository identity and a new confirmation.",
                            fontSize = 12.sp,
                            color = Muted,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = model::install) { Text("Trust & install") }
                },
                dismissButton = { TextButton(onClick = model::discard) { Text("Cancel") } },
            )
        }
        model.candidateRepo?.let { r ->
            AlertDialog(
                onDismissRequest = { model.candidateRepo = null },
                title = { Text("Add ${r.name}?") },
                text = {
                    Column {
                        Text(r.url, fontSize = 12.sp)
                        if (r.signer.isNotEmpty()) {
                            Text(
                                "Repository signing identity",
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            Text(r.signer, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = model::addRepo) { Text("Trust repository") }
                },
                dismissButton = {
                    TextButton(onClick = { model.candidateRepo = null }) { Text("Cancel") }
                },
            )
        }
        model.playback?.let { pair ->
            val context = LocalContext.current
            ModalBottomSheet(onDismissRequest = model::dismissStreams) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Eyebrow("READY WHEN YOU ARE")
                    Text(pair.second.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Choose a stream",
                        color = Muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    model.streams.forEach { stream ->
                        ListItem(
                            headlineContent = { Text(stream.label.ifBlank { "Original quality" }) },
                            supportingContent = {
                                Text(
                                    "${if(stream.hls)"HLS" else "Video"} · ${stream.subtitles.size} subtitle tracks"
                                )
                            },
                            leadingContent = { Icon(Icons.Rounded.PlayCircle, null) },
                            modifier = Modifier.clickable { model.startStream(context, stream) },
                        )
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(model: AppModel) {
    Box(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .97f),
            tonalElevation = 8.dp,
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .06f)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                listOf(
                        Triple("home", "Discover", Icons.Rounded.AutoAwesome),
                        Triple("library", "Library", Icons.Rounded.CollectionsBookmark),
                        Triple("extensions", "Sources", Icons.Rounded.Extension),
                        Triple("settings", "You", Icons.Rounded.Tune),
                    )
                    .forEach { (id, label, icon) ->
                        val selected = model.screen == id
                        Column(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .clickable { model.screen = id }
                                .padding(horizontal = 17.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                icon,
                                label,
                                tint = if (selected) MaterialTheme.colorScheme.primary else Muted,
                                modifier = Modifier.size(21.dp),
                            )
                            Text(
                                label,
                                fontSize = 9.sp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Muted,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String, color: Color = Muted) {
    Text(text, letterSpacing = 2.sp, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
}

@Composable
private fun Primary(
    text: String,
    icon: ImageVector = Icons.Rounded.ArrowOutward,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 15.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Heading(kicker: String, title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(kicker)
            Text(
                title,
                fontFamily = Editorial,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        action?.invoke()
    }
}

@Composable
private fun ModeSwitch(model: AppModel) {
    Row(
        Modifier.clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = .07f))
            .padding(4.dp)
    ) {
        listOf(false to "Anime", true to "Manga").forEach { (manga, label) ->
            val selected = (model.user.mode == "manga") == manga
            Row(
                Modifier.clip(RoundedCornerShape(24.dp))
                    .background(if (selected) Color.White.copy(alpha = .15f) else Color.Transparent)
                    .clickable { model.mode(manga) }
                    .padding(horizontal = 17.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (manga) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.PlayArrow,
                    null,
                    Modifier.size(15.dp),
                    tint = if (selected) Lilac else Muted,
                )
                Text(
                    label,
                    color = if (selected) Color.White else Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoverScreen(model: AppModel) {
    val list = rememberLazyListState()
    val heroHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 155).coerceIn(480, 650).dp
    val manga = model.user.mode == "manga"
    val items = model.editorial
    val hero = items.firstOrNull() ?: return
    var ambient by remember(hero.key) { mutableStateOf(Color(0xFF47305E)) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(heroHeight).background(ambient)) {
                AsyncImage(
                    hero.cover,
                    "${hero.title} key art",
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            translationY = list.firstVisibleItemScrollOffset * .17f
                            scaleX = 1.04f
                            scaleY = 1.04f
                        },
                    onSuccess = { result ->
                        (result.result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                            Palette.from(bitmap).generate { palette ->
                                palette?.getDarkMutedColor(0xFF47305E.toInt())?.let {
                                    ambient = Color(it)
                                }
                            }
                        }
                    },
                )
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Ink.copy(alpha = .45f),
                                    Color.Transparent,
                                    Ink.copy(alpha = .35f),
                                    Ink,
                                ),
                                startY = 0f,
                            )
                        )
                )
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Ink.copy(alpha = .35f), Color.Transparent)
                            )
                        )
                )
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "yoru",
                            fontFamily = Editorial,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-1).sp,
                        )
                        Text(
                            "夜",
                            fontSize = 18.sp,
                            color = Lilac,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { model.screen = "extensions" }) {
                            Icon(Icons.Rounded.Search, "Search sources", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(17.dp))
                    ModeSwitch(model)
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 27.dp)
                        .padding(bottom = 28.dp)
                ) {
                    Surface(
                        color = Lilac.copy(alpha = .14f),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, Lilac.copy(alpha = .3f)),
                    ) {
                        Text(
                            if (manga) "THE READING ROOM" else "TONIGHT’S SPOTLIGHT",
                            color = Color(0xFFE0D0FF),
                            fontSize = 9.sp,
                            letterSpacing = 1.8.sp,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                    Text(
                        if (hero.editorialId == 154587) "Beyond the\njourney." else hero.title,
                        fontFamily = Editorial,
                        fontSize = 44.sp,
                        lineHeight = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-2).sp,
                        color = Color.White,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Row(
                        Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Star,
                            null,
                            tint = Color(0xFFFFD68A),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            " ${hero.score}   ·   ${hero.genres.take(2).joinToString(" / ")}",
                            color = Color(0xFFD0C4DA),
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        if (hero.editorialId == 154587)
                            "Some stories begin when the adventure ends."
                        else hero.description.take(105).substringBeforeLast(' ') + "…",
                        color = Color(0xFFB9AEC5),
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 22.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Primary(
                            if (manga) "Find chapters" else "Find episodes",
                            if (manga) Icons.AutoMirrored.Rounded.MenuBook
                            else Icons.Rounded.PlayArrow,
                        ) {
                            model.open(hero)
                        }
                        OutlinedIconButton(
                            onClick = { model.saveTitle(hero) },
                            shape = RoundedCornerShape(15.dp),
                            modifier = Modifier.size(49.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .22f)),
                        ) {
                            Icon(
                                if (model.user.library.any { it.key == hero.key })
                                    Icons.Rounded.Bookmark
                                else Icons.Rounded.BookmarkBorder,
                                "Save title",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
        val history = model.user.history.filter { it.reader == manga }.take(5)
        if (history.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Heading("RIGHT WHERE YOU LEFT OFF", "Your next chapter")
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(history) { progress ->
                        Surface(
                            Modifier.width(285.dp).clickable {
                                model.playOrRead(progress.media, progress.episode)
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = Panel,
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                AsyncImage(
                                    progress.media.cover,
                                    null,
                                    Modifier.size(60.dp, 83.dp).clip(RoundedCornerShape(9.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Column(Modifier.weight(1f).padding(top = 5.dp)) {
                                    Text(
                                        progress.media.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        progress.episode.name,
                                        maxLines = 1,
                                        fontSize = 11.sp,
                                        color = Muted,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                    Text(
                                        if (progress.reader) "Page ${progress.position+1}"
                                        else "Resume · ${progress.position/60000} min",
                                        fontSize = 10.sp,
                                        color = Lilac,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Heading("CURATED WORLDS", "Stay a little longer.") {
                    Icon(Icons.Rounded.NorthEast, null, tint = Lilac)
                }
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items.drop(1)) { media ->
                    Poster(media, Modifier.width(153.dp)) { model.open(media) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Heading("YOUR DOORS TO DISCOVERY", "From your sources") {
                    TextButton(onClick = { model.screen = "extensions" }) {
                        Text("Manage", fontSize = 11.sp)
                    }
                }
            }
        }
        val sources = model.sources.filter { it.manga == manga }
        if (sources.isEmpty())
            item {
                Surface(
                    Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border =
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
                ) {
                    Column(Modifier.padding(23.dp)) {
                        Icon(
                            Icons.Rounded.Extension,
                            null,
                            tint = Lilac,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            "A whole world. Your choice.",
                            fontSize = 20.sp,
                            fontFamily = Editorial,
                            modifier = Modifier.padding(top = 17.dp),
                        )
                        Text(
                            "Add a ${if(manga)"Mihon" else "Cloudstream"} repository to browse and ${if(manga)"read" else "watch"} from your own sources.",
                            fontSize = 12.sp,
                            color = Muted,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        Primary("Explore extensions") { model.screen = "extensions" }
                    }
                }
            }
        else
            items(sources) { s ->
                Box(Modifier.padding(horizontal = 24.dp)) { SourceRow(s) { model.browse(s) } }
            }
        item {
            Text(
                "A universe of stories. A space of your own.",
                fontSize = 10.sp,
                color = Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(35.dp),
            )
        }
    }
}

@Composable
private fun Poster(media: Title, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(.68f)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
        ) {
            AsyncImage(
                media.cover,
                media.title,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .35f))
                        )
                    )
            )
            if (media.score.isNotEmpty())
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(9.dp),
                    color = Ink.copy(alpha = .8f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "★ ${media.score}",
                        color = Color(0xFFFFD68A),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(5.dp),
                    )
                }
        }
        Text(
            media.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 11.dp),
        )
        Text(
            media.source?.name ?: media.genres.firstOrNull().orEmpty(),
            fontSize = 10.sp,
            color = Muted,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SourceRow(source: Source, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(source.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
        supportingContent = {
            Text(
                "${source.lang.uppercase()} · ${if(source.manga)"Mihon" else "Cloudstream"}",
                fontSize = 11.sp,
                color = Muted,
            )
        },
        leadingContent = {
            Box(
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (source.manga) Icons.AutoMirrored.Rounded.MenuBook
                    else Icons.Rounded.PlayCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                null,
                tint = Muted,
                modifier = Modifier.size(17.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}

@Composable
private fun BrowseScreen(model: AppModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.screen = "extensions" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Text(
                model.source?.name.orEmpty(),
                modifier = Modifier.weight(1f),
                fontSize = 23.sp,
                fontFamily = Editorial,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    model.source?.let { s ->
                        context.startActivity(
                            android.content
                                .Intent(context, SourcePreferencesActivity::class.java)
                                .putExtra("source", s.id)
                                .putExtra("type", s.type)
                        )
                    }
                }
            ) {
                Icon(Icons.Rounded.Settings, null)
                Text("Source settings")
            }
        }
        var text by remember(model.source) { mutableStateOf(model.query) }
        OutlinedTextField(
            text,
            { text = it },
            singleLine = true,
            placeholder = { Text("Search this source") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                IconButton(onClick = { model.source?.let { model.browse(it, text) } }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Search")
                }
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
            keyboardActions =
                androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { model.source?.let { model.browse(it, text) } }
                ),
        )
        if (model.results.isEmpty() && model.busy == null)
            EmptyState(
                "No results yet",
                "Try a title, or check this source’s settings and availability.",
            )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(135.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(model.results, key = { it.key }) { media -> Poster(media) { model.open(media) } }
            if (model.hasMore)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = { model.source?.let { model.browse(it, model.query, true) } }
                    ) {
                        Text("Load more")
                    }
                }
        }
    }
}

@Composable
private fun LibraryScreen(model: AppModel) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Heading("KEEP WHAT MOVES YOU", "Your library.") {
            IconButton(onClick = { model.screen = "history" }) {
                Icon(Icons.Rounded.History, "History")
            }
        }
        ModeSwitch(model)
        Spacer(Modifier.height(22.dp))
        val list =
            model.user.library.filter {
                it.source?.manga?.let { m -> m == (model.user.mode == "manga") }
                    ?: model.editorial.any { e -> e.editorialId == it.editorialId }
            }
        if (list.isEmpty())
            EmptyState(
                "Make room for a favorite.",
                "Save titles as you explore. Your library stays on this device.",
            )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(135.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(list, key = { it.key }) { media -> Poster(media) { model.open(media) } }
        }
    }
}

@Composable
private fun HistoryScreen(model: AppModel) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp)) {
        item { Heading("THE STORIES SO FAR", "Your journey.") }
        if (model.user.history.isEmpty())
            item {
                EmptyState("A fresh beginning.", "Reading and playback progress will appear here.")
            }
        items(model.user.history, key = { it.key }) { p ->
            ListItem(
                headlineContent = {
                    Text(p.media.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        "${p.episode.name}\n${if(p.reader)"Page ${p.position+1}" else "${p.position/60000} min"}",
                        fontSize = 11.sp,
                    )
                },
                leadingContent = {
                    AsyncImage(
                        p.media.cover,
                        null,
                        Modifier.size(45.dp, 65.dp).clip(RoundedCornerShape(7.dp)),
                        contentScale = ContentScale.Crop,
                    )
                },
                trailingContent = { Icon(Icons.Rounded.PlayArrow, null) },
                modifier = Modifier.clickable { model.playOrRead(p.media, p.episode) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Lilac, modifier = Modifier.size(36.dp))
        Text(
            title,
            fontFamily = Editorial,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            body,
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ExtensionsScreen(model: AppModel) {
    var tab by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Installed?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Heading("BUILT BY A COMMUNITY", "Your sources.") {
            IconButton(onClick = { add = true }) { Icon(Icons.Rounded.Add, "Add repository") }
        }
        Text(
            "New worlds. No app rebuild required.",
            fontSize = 12.sp,
            color = Muted,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Installed", "Repositories", "Available").forEachIndexed { i, label ->
                FilterChip(
                    selected = tab == i,
                    onClick = { tab = i },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }
        LaunchedEffect(model.available) { if (model.available.isNotEmpty()) tab = 2 }
        when (tab) {
            0 ->
                LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                    if (model.loadErrors.isNotEmpty())
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.padding(vertical = 12.dp),
                            ) {
                                Text(
                                    model.loadErrors.joinToString("\n"),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(14.dp),
                                )
                            }
                        }
                    if (model.sources.isEmpty())
                        item {
                            EmptyState(
                                "Start with a source.",
                                "Add a repository, install an extension, then browse its titles here.",
                            )
                        }
                    items(model.sources, key = { it.key }) { source ->
                        SourceRow(source) { model.browse(source) }
                    }
                    if (model.user.installed.isNotEmpty())
                        item { Heading("INSTALLED PACKAGES", "Manage extensions") }
                    items(model.user.installed, key = { it.extension.key }) { record ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        ) {
                            Row(
                                Modifier.padding(17.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        record.extension.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "${record.extension.type.uppercase()} · ${record.extension.version}",
                                        fontSize = 10.sp,
                                        color = Muted,
                                        modifier = Modifier.padding(top = 5.dp),
                                    )
                                    Text(
                                        "Verified locally",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 5.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        model.user.repos
                                            .find { it.url == record.extension.repo }
                                            ?.let(model::sync)
                                            ?: run {
                                                model.error =
                                                    "Re-add the original repository to check updates."
                                            }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Refresh, "Check for updates")
                                }
                                IconButton(onClick = { remove = record }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        "Remove extension",
                                        tint = Muted,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        TextButton(onClick = model::reload) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                            Text(" Reload installed sources")
                        }
                    }
                }
            1 ->
                LazyColumn(contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp)) {
                    items(model.user.repos, key = { it.url }) { repo ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp),
                            border =
                                BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = .1f),
                                ),
                        ) {
                            Column(Modifier.padding(22.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(43.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(
                                                    alpha = .12f
                                                ),
                                                RoundedCornerShape(13.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Extension,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.width(15.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            repo.name,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            repo.type.uppercase(),
                                            fontSize = 9.sp,
                                            letterSpacing = 1.sp,
                                            color = Muted,
                                            modifier = Modifier.padding(top = 5.dp),
                                        )
                                    }
                                    IconButton(onClick = { model.removeRepo(repo) }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            "Remove repository",
                                            Modifier.size(18.dp),
                                            tint = Muted,
                                        )
                                    }
                                }
                                Text(
                                    repo.url,
                                    fontSize = 10.sp,
                                    color = Muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 17.dp),
                                )
                                Primary("Browse extensions", Icons.Rounded.ArrowOutward) {
                                    model.sync(repo)
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { add = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(17.dp),
                            contentPadding = PaddingValues(18.dp),
                        ) {
                            Icon(Icons.Rounded.Add, null)
                            Text(" Add a repository")
                        }
                    }
                }
            else -> {
                OutlinedTextField(
                    filter,
                    { filter = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    singleLine = true,
                    placeholder = { Text("Filter extensions") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = RoundedCornerShape(16.dp),
                )
                val matches =
                    model.available.filter {
                        !it.adult &&
                            (it.name.contains(filter, true) || it.languages.contains(filter, true))
                    }
                Text(
                    "${matches.size} extensions · adult-only sources hidden",
                    fontSize = 10.sp,
                    color = Muted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                LazyColumn {
                    items(matches, key = { it.key }) { entry ->
                        val installed = model.user.installed.find { it.extension.key == entry.key }
                        ListItem(
                            headlineContent = {
                                Text(entry.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            },
                            supportingContent = {
                                Text(
                                    "${entry.languages} · v${entry.version}",
                                    fontSize = 10.sp,
                                    color = Muted,
                                    maxLines = 2,
                                )
                            },
                            leadingContent = {
                                if (entry.icon.isNotBlank())
                                    AsyncImage(
                                        entry.icon,
                                        null,
                                        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                else
                                    Icon(
                                        Icons.Rounded.Extension,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                            },
                            trailingContent = {
                                TextButton(
                                    enabled =
                                        model.busy == null &&
                                            (installed == null ||
                                                installed.extension.code < entry.code),
                                    onClick = { model.prepare(entry) },
                                ) {
                                    Text(
                                        if (installed == null) "Install"
                                        else if (installed.extension.code < entry.code) "Update"
                                        else "Installed",
                                        fontSize = 10.sp,
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = .04f))
                    }
                }
            }
        }
    }
    if (add) {
        var url by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("cloudstream") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("A new door opens.") },
            text = {
                Column {
                    Text(
                        "Add a repository URL from its maintainer.",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(type == "mihon", { type = "mihon" }, label = { Text("Mihon") })
                        FilterChip(
                            type == "cloudstream",
                            { type = "cloudstream" },
                            label = { Text("Cloudstream") },
                        )
                    }
                    OutlinedTextField(
                        url,
                        { url = it },
                        label = {
                            Text(
                                if (type == "mihon") "HTTPS index.pb URL" else "HTTPS repo.json URL"
                            )
                        },
                        maxLines = 3,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Only install extensions you trust. They execute code with this app’s permissions.",
                        fontSize = 10.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 13.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        add = false
                        model.inspectRepo(url, type)
                    },
                    enabled = url.isNotBlank(),
                ) {
                    Text("Inspect repository")
                }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } },
        )
    }
    remove?.let { record ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove ${record.extension.name}?") },
            text = {
                Text("Saved library titles remain. This source must be reinstalled to open them.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        remove = null
                        model.remove(record)
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DetailScreen(model: AppModel, detail: Detail) {
    val media = detail.media
    var episodeFilter by remember(media.key) { mutableStateOf("") }
    var reverse by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    AsyncImage(
                        media.cover,
                        null,
                        Modifier.fillMaxSize().blur(25.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Ink.copy(alpha = .15f),
                                        MaterialTheme.colorScheme.surface,
                                    )
                                )
                            )
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 25.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        AsyncImage(
                            media.cover,
                            media.title,
                            Modifier.width(120.dp)
                                .height(178.dp)
                                .shadow(20.dp, RoundedCornerShape(15.dp))
                                .clip(RoundedCornerShape(15.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Column(Modifier.weight(1f)) {
                            Eyebrow(
                                if (media.source?.manga == true) "THE READING ROOM"
                                else "NOW SHOWING",
                                MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                media.title,
                                fontFamily = Editorial,
                                fontSize = 25.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Text(
                                media.source?.name.orEmpty(),
                                fontSize = 11.sp,
                                color = Muted,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { model.detail = null },
                        modifier =
                            Modifier.statusBarsPadding()
                                .padding(16.dp)
                                .background(Ink.copy(alpha = .5f), CircleShape),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (detail.episodes.isNotEmpty())
                            Primary(
                                if (media.source?.manga == true) "Start reading" else "Watch now",
                                Icons.Rounded.PlayArrow,
                            ) {
                                model.playOrRead(media, detail.episodes.last())
                            }
                        OutlinedButton(
                            onClick = { model.saveTitle(media) },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(15.dp),
                        ) {
                            Icon(
                                if (model.user.library.any { it.key == media.key })
                                    Icons.Rounded.Bookmark
                                else Icons.Rounded.BookmarkBorder,
                                "Save",
                            )
                        }
                    }
                    Text(
                        media.genres.take(5).joinToString("  ·  "),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 23.dp, bottom = 13.dp),
                    )
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        media.description
                            .ifBlank { "This source has no synopsis." }
                            .replace(Regex("<[^>]+>"), ""),
                        fontSize = 12.sp,
                        lineHeight = 21.sp,
                        color = Muted,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Less" else "Read synopsis", fontSize = 10.sp)
                    }
                    Heading(
                        "YOUR NEXT CHAPTER",
                        "${detail.episodes.size} ${if(media.source?.manga==true)"chapters" else "episodes"}",
                    ) {
                        IconButton(onClick = { reverse = !reverse }) {
                            Icon(Icons.Rounded.SwapVert, "Reverse order")
                        }
                    }
                    OutlinedTextField(
                        episodeFilter,
                        { episodeFilter = it },
                        placeholder = { Text("Find a chapter or episode", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
            }
            val episodes =
                detail.episodes
                    .filter { it.name.contains(episodeFilter, true) }
                    .let { if (reverse) it.reversed() else it }
            items(episodes, key = { it.url }) { ep ->
                val progress = model.user.history.find { it.key == "${media.key}:${ep.url}" }
                ListItem(
                    headlineContent = {
                        Text(
                            ep.name,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        if (progress != null)
                            Text(
                                if (progress.reader) "Resume · page ${progress.position+1}"
                                else "Resume · ${progress.position/60000} min",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                    },
                    leadingContent = {
                        Icon(
                            if (media.source?.manga == true) Icons.AutoMirrored.Rounded.MenuBook
                            else Icons.Rounded.PlayCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = Muted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier.padding(horizontal = 10.dp).clickable {
                            model.playOrRead(media, ep)
                        },
                )
            }
        }
    }
}

@Composable
private fun ReaderScreen(model: AppModel, reading: Reading) {
    var webtoon by remember { mutableStateOf(false) }
    var rtl by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    val pager = rememberPagerState(initialPage = reading.start, pageCount = { reading.pages.size })
    val list = rememberLazyListState(initialFirstVisibleItemIndex = reading.start)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager, webtoon) {
        if (!webtoon)
            snapshotFlow { pager.currentPage }
                .distinctUntilChanged()
                .collect { model.record(reading.media, reading.episode, it.toLong(), true) }
    }
    LaunchedEffect(list, webtoon) {
        if (webtoon)
            snapshotFlow { list.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { model.record(reading.media, reading.episode, it.toLong(), true) }
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (webtoon)
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(reading.pages) { page ->
                        ReaderImage(
                            ImageRequest.Builder(context)
                                .data(page.url)
                                .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
                                .build(),
                            "Manga page",
                            modifier = Modifier.fillMaxWidth().clickable { controls = !controls },
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
            else
                HorizontalPager(
                    state = pager,
                    reverseLayout = rtl,
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    val page = reading.pages[index]
                    var zoom by remember(index) { mutableFloatStateOf(1f) }
                    var x by remember(index) { mutableFloatStateOf(0f) }
                    var y by remember(index) { mutableFloatStateOf(0f) }
                    Box(
                        Modifier.fillMaxSize().clickable { controls = !controls },
                        contentAlignment = Alignment.Center,
                    ) {
                        ReaderImage(
                            ImageRequest.Builder(context)
                                .data(page.url)
                                .apply { page.headers.forEach { (k, v) -> addHeader(k, v) } }
                                .build(),
                            "Page ${index+1}",
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier.fillMaxSize()
                                    .pointerInput(index) {
                                        detectTransformGestures { _, pan, scale, _ ->
                                            zoom = (zoom * scale).coerceIn(1f, 5f)
                                            x =
                                                if (zoom > 1)
                                                    (x + pan.x).coerceIn(
                                                        -size.width.toFloat(),
                                                        size.width.toFloat(),
                                                    )
                                                else 0f
                                            y =
                                                if (zoom > 1)
                                                    (y + pan.y).coerceIn(
                                                        -size.height.toFloat(),
                                                        size.height.toFloat(),
                                                    )
                                                else 0f
                                        }
                                    }
                                    .graphicsLayer {
                                        scaleX = zoom
                                        scaleY = zoom
                                        translationX = x
                                        translationY = y
                                    },
                        )
                    }
                }
            AnimatedVisibility(
                controls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(color = Ink.copy(alpha = .94f)) {
                    Column(Modifier.statusBarsPadding().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { model.reading = null }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    "Close reader",
                                    tint = Color.White,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    reading.media.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    reading.episode.name,
                                    fontSize = 10.sp,
                                    color = Muted,
                                    maxLines = 1,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            FilterChip(!webtoon, { webtoon = false }, label = { Text("Paged") })
                            Spacer(Modifier.width(9.dp))
                            FilterChip(webtoon, { webtoon = true }, label = { Text("Webtoon") })
                            Spacer(Modifier.width(9.dp))
                            FilterChip(
                                rtl,
                                { rtl = !rtl },
                                label = { Text(if (rtl) "Right → left" else "Left → right") },
                            )
                        }
                    }
                }
            }
            if (controls)
                Surface(
                    color = Ink.copy(alpha = .94f),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Column(
                        Modifier.navigationBarsPadding()
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val current = if (webtoon) list.firstVisibleItemIndex else pager.currentPage
                        Text(
                            "${current+1} / ${reading.pages.size}",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        if (reading.pages.size > 1)
                            Slider(
                                current.toFloat(),
                                onValueChange = { value ->
                                    scope.launch {
                                        if (webtoon) list.scrollToItem(value.toInt())
                                        else pager.scrollToPage(value.toInt())
                                    }
                                },
                                valueRange = 0f..reading.pages.lastIndex.toFloat(),
                            )
                    }
                }
        }
    }
}

@Composable
private fun SettingsScreen(model: AppModel) {
    val context = LocalContext.current
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(model.export().toByteArray())
                        }
                    }
                    .onFailure { model.error = it.message }
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            Engine.bounded(it, 4_000_000).decodeToString()
                        }
                    }
                    .onSuccess { it?.let(model::restore) }
                    .onFailure { model.error = it.message }
        }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp)) {
        item {
            Heading("A SPACE OF YOUR OWN", "Make it yours.")
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(23.dp)) {
                    Eyebrow("THE ATMOSPHERE")
                    Text(
                        "A different kind of dark.",
                        fontSize = 21.sp,
                        fontFamily = Editorial,
                        modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf("dark" to "Dusk", "oled" to "Midnight", "light" to "Daylight")
                            .forEach { (id, name) ->
                                FilterChip(
                                    model.user.theme == id,
                                    { model.theme(id) },
                                    label = { Text(name, fontSize = 11.sp) },
                                )
                            }
                    }
                }
            }
        }
        item {
            Heading("YOUR SIGNATURE", "Choose a little color")
            Row(horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                listOf(0xFFC2A5FF, 0xFFF4AAC1, 0xFF9ACDBE, 0xFFDFCA9B, 0xFF9BBFF9).forEach { color
                    ->
                    Box(
                        Modifier.size(39.dp)
                            .background(Color(color), CircleShape)
                            .border(
                                if (model.user.accent == color) 3.dp else 0.dp,
                                Color.White,
                                CircleShape,
                            )
                            .clickable { model.accent(color) }
                    )
                }
            }
        }
        item {
            Heading("TAKE YOUR STORIES WITH YOU", "Library & history")
            ListItem(
                headlineContent = { Text("Export backup") },
                supportingContent = {
                    Text("Library, progress and repository settings", fontSize = 11.sp)
                },
                leadingContent = { Icon(Icons.Rounded.Download, null) },
                modifier = Modifier.clickable { export.launch("yoru-backup.json") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Restore backup") },
                supportingContent = {
                    Text("Extension code is never restored from a backup", fontSize = 11.sp)
                },
                leadingContent = { Icon(Icons.Rounded.Upload, null) },
                modifier = Modifier.clickable { restore.launch(arrayOf("application/json")) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Reading & watch history") },
                leadingContent = { Icon(Icons.Rounded.History, null) },
                modifier = Modifier.clickable { model.screen = "history" },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            Heading("OPEN BY DESIGN", "Made to be yours.")
            Text(
                "Yoru 0.2 · Native Android\nMihon / Tachiyomi source APIs, Cloudstream providers & extractors, AnymeX Runtime Bridge, and AndroidX Media3.",
                fontSize = 12.sp,
                lineHeight = 21.sp,
                color = Muted,
            )
            Text(
                "Original UI and integration by Yoru contributors. Runtime includes GPLv3/UPL and Apache-licensed upstream work. Title artwork belongs to its respective owners.",
                fontSize = 10.sp,
                lineHeight = 17.sp,
                color = Muted,
                modifier = Modifier.padding(top = 15.dp),
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://github.com/herodies67999/hero-Github-I-ain-t-bot-please-stop-flagging-me-again-and-again-for-no-reason-/tree/coderabbit/anime-manga-extension-client/37485fbe"
                            ),
                        )
                    )
                }
            ) {
                Text("Source code & licenses ↗")
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ReaderImage(model: ImageRequest, contentDescription: String, modifier: Modifier = Modifier, contentScale: ContentScale) {
    var attempt by remember(model.data) { mutableIntStateOf(0) }
    key(attempt) {
        coil.compose.SubcomposeAsyncImage(model, contentDescription, modifier = modifier, contentScale = contentScale,
            loading = { Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lilac) } },
            error = { Column(Modifier.fillMaxWidth().heightIn(min = 180.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Page could not load", color = Color.White); TextButton(onClick = { attempt++ }) { Text("Retry") } } })
    }
}
