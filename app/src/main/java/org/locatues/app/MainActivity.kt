package org.locatues.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.util.Locale

private val Bg = Color(0xFF0B0D12)
private val Panel = Color(0xFF171B24)
private val Lime = Color(0xFFB7F36B)
private val Muted = Color(0xFF9AA3B2)

data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri, val album: String = "Unknown album", val path: String = "", val format: String = "", val embeddedLyrics: String = "")
data class LyricLine(val timeMs: Long, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocaTuesApp() }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun LocaTuesApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var selected by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    var full by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showMetadata by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    val lyricsBySong = remember { mutableStateMapOf<Long, List<LyricLine>>() }

    fun scan() { songs = scanAudio(ctx) + scanPickedFolders(ctx) ; songs = songs.distinctBy { it.uri }.sortedBy { it.title.lowercase(Locale.ROOT) } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val prefs = ctx.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getStringSet("folders", emptySet()).orEmpty().toMutableSet()
            saved.add(uri.toString())
            prefs.edit().putStringSet("folders", saved).apply()
            scan()
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        if (it) scan()
    }
    val pickLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selected in songs.indices) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { text ->
                lyricsBySong[songs[selected].id] = parseLrc(text)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { selected = player.currentMediaItemIndex }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(Unit) { if (allowed) scan() }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) pos = player.currentPosition.coerceAtLeast(0)
            dur = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }

    fun play(i: Int) {
        if (i !in songs.indices) return
        selected = i
        player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, i, 0L)
        player.prepare()
        player.play()
        full = true
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Bg, surface = Panel)) {
        AnimatedContent(
            targetState = full && selected in songs.indices,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(220)) },
            label = "home-player-transition"
        ) { showPlayer ->
            if (showPlayer && selected in songs.indices) {
                PlayerScreen(
                    song = songs[selected], playing = playing, position = pos, duration = dur,
                    shuffle = shuffle, repeat = repeat,
                    lyrics = lyricsBySong[songs[selected].id].orEmpty(),
                    showLyrics = { showLyrics = true }, metadata = { showMetadata = true },
                    loadLyrics = { pickLyrics.launch(arrayOf("text/*", "application/octet-stream")) },
                    back = { full = false },
                    toggle = { if (player.isPlaying) player.pause() else player.play() },
                    previous = { player.seekToPreviousMediaItem() }, next = { player.seekToNextMediaItem() },
                    seek = { pos = it; player.seekTo(it) }, scrubbing = { scrubbing = it },
                    toggleShuffle = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle },
                    toggleRepeat = {
                        repeat = when (repeat) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player.repeatMode = repeat
                    }
                )
                if (showMetadata) SongMetadataDialog(songs[selected], onDismiss = { showMetadata = false })
                if (showLyrics) LyricsDialog(
                    song = songs[selected], lyrics = lyricsBySong[songs[selected].id] ?: parseEmbeddedLyrics(songs[selected].embeddedLyrics),
                    position = pos, loadLyrics = { pickLyrics.launch(arrayOf("text/*", "application/octet-stream")) },
                    onDismiss = { showLyrics = false }, seek = { player.seekTo(it) }
                )
            } else {
                HomeScreen(
                    songs = songs, selected = selected, playing = playing, allowed = allowed,
                    refresh = { if (allowed) scan() else ask.launch(audioPermission()) },
                    grant = { ask.launch(audioPermission()) },
                    chooseFolder = { pickFolder.launch(null) },
                    play = ::play,
                    openPlayer = { full = true },
                    toggle = { if (player.isPlaying) player.pause() else player.play() },
                    next = { player.seekToNextMediaItem() }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    songs: List<Song>, selected: Int, playing: Boolean, allowed: Boolean,
    refresh: () -> Unit, grant: () -> Unit, chooseFolder: () -> Unit, play: (Int) -> Unit,
    openPlayer: () -> Unit, toggle: () -> Unit, next: () -> Unit
) {
    Scaffold(containerColor = Bg, bottomBar = {
        if (selected in songs.indices) {
            Row(
                Modifier.fillMaxWidth().background(Panel).clickable { openPlayer() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Album, null, tint = Lime, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(songs[selected].title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(songs[selected].artist, color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = toggle) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Lime)
                }
                IconButton(onClick = next) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().background(Bg).padding(pad).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(26.dp))
            Text("LOCATUES", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Your music.\nYour device.", color = Color.White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your library", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("${songs.size} songs across your device", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = chooseFolder) { Icon(Icons.Default.FolderOpen, "Choose music folder", tint = Lime) }
                IconButton(onClick = refresh) { Icon(Icons.Default.Refresh, "Refresh library", tint = Lime) }
            }
            when {
                !allowed -> EmptyLibrary("Allow music access", "LocaTues reads audio files across your device.", "Grant permission", grant)
                songs.isEmpty() -> EmptyLibrary("No music found", "Choose a Music folder to scan it and its subfolders.", "Choose folder", chooseFolder)
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(songs) { i, song ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (selected == i) Color(0xFF252D21) else Panel)
                                .clickable { play(i) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote, null, tint = if (selected == i) Lime else Muted, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, color = Muted, fontSize = 12.sp)
                            }
                            Text(time(song.duration), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PlayerScreen(
    song: Song, playing: Boolean, position: Long, duration: Long, shuffle: Boolean, repeat: Int,
    lyrics: List<LyricLine>, showLyrics: () -> Unit, metadata: () -> Unit, loadLyrics: () -> Unit,
    back: () -> Unit, toggle: () -> Unit, previous: () -> Unit, next: () -> Unit,
    seek: (Long) -> Unit, scrubbing: (Boolean) -> Unit, toggleShuffle: () -> Unit, toggleRepeat: () -> Unit
) {
    var slider by remember(song.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(position, duration) {
        slider = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f
    }
    val activeLyric = lyrics.indexOfLast { it.timeMs <= position }
    Column(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(Icons.Default.KeyboardArrowDown, "Back", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = metadata) { Icon(Icons.Default.MoreHoriz, "Song details", tint = Color.White) }
        }
        Spacer(Modifier.weight(0.7f))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Color(0xFF202A27)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.GraphicEq, null, tint = Lime, modifier = Modifier.size(112.dp))
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {}) { Icon(Icons.Default.FavoriteBorder, "Favourite", tint = Muted) }
        }

        Column(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (lyrics.isEmpty()) {
                Text("SYNCED LYRICS", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Text("Load an .lrc lyrics file", color = Color.White, fontSize = 13.sp)
                Row { TextButton(onClick = showLyrics) { Text("OPEN LYRICS", color = Lime, fontSize = 11.sp) }; TextButton(onClick = loadLyrics) { Text("LOAD .LRC", color = Muted, fontSize = 11.sp) } }
            } else {
                AnimatedContent(
                    targetState = activeLyric,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                    label = "synced-lyric-line"
                ) { index ->
                    Text(
                        text = lyrics.getOrNull(index)?.text ?: "♪",
                        color = Lime, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Row { TextButton(onClick = showLyrics) { Text("FULL LYRICS", color = Lime, fontSize = 10.sp) }; TextButton(onClick = loadLyrics) { Text("CHANGE .LRC", color = Muted, fontSize = 10.sp) } }
            }
        }

        Spacer(Modifier.height(6.dp))
        Slider(
            value = slider,
            onValueChange = { slider = it; scrubbing(true) },
            onValueChangeFinished = { seek(slider.toLong()); scrubbing(false) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = Color(0xFF343B46))
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(position), color = Muted, fontSize = 11.sp)
            Text(time(duration), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = toggleShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) Lime else Muted) }
            IconButton(onClick = previous, modifier = Modifier.size(54.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = toggle, modifier = Modifier.size(76.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Bg)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = next, modifier = Modifier.size(54.dp)) {
                Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = toggleRepeat) {
                Icon(
                    if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat", tint = if (repeat == Player.REPEAT_MODE_OFF) Muted else Lime
                )
            }
        }
        Spacer(Modifier.weight(0.7f))
        Text("PLAYING FROM YOUR LIBRARY", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EmptyLibrary(title: String, subtitle: String, action: String?, click: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LibraryMusic, null, tint = Lime, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
            if (action != null && click != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = click) { Text(action) }
            }
        }
    }
}

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun scanAudio(ctx: android.content.Context): List<Song> {
    val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(ctx)
    else setOf("external")
    val songs = mutableListOf<Song>()
    val columns = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DATA
    )
    for (volume in volumes) {
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(volume)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        runCatching {
            ctx.contentResolver.query(
                collection, columns, null, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val duration = cursor.getLong(durationCol)
                    if (duration <= 0L) continue
                    val songId = cursor.getLong(idCol)
                    val itemUri = ContentUris.withAppendedId(collection, songId)
                    val embeddedLyrics = runCatching {
                        val retriever = MediaMetadataRetriever()
                        try { retriever.setDataSource(ctx, itemUri); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LYRICS).orEmpty() }
                        finally { retriever.release() }
                    }.getOrDefault("")
                    songs += Song(
                        songId,
                        cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                        cursor.getString(artistCol)?.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown artist",
                        duration,
                        itemUri,
                        if (albumCol >= 0) cursor.getString(albumCol) ?: "Unknown album" else "Unknown album",
                        if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else "",
                        itemUri.lastPathSegment?.substringAfterLast(".")?.uppercase().orEmpty(),
                        embeddedLyrics
                    )
                }
            }
        }
    }
    return songs.distinctBy { it.uri }.sortedBy { it.title.lowercase(Locale.ROOT) }
}

private fun scanPickedFolders(ctx: android.content.Context): List<Song> {
    val roots = ctx.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
        .getStringSet("folders", emptySet()).orEmpty()
    val result = mutableListOf<Song>()
    val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "opus", "wma", "3gp")
    fun walk(dir: DocumentFile) {
        runCatching {
            dir.listFiles().forEach { file ->
                when {
                    file.isDirectory -> walk(file)
                    file.isFile && file.name?.substringAfterLast(".", "")?.lowercase() in extensions -> {
                        val uri = file.uri
                        val retriever = MediaMetadataRetriever()
                        val data = runCatching {
                            retriever.setDataSource(ctx, uri)
                            listOf(
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name?.substringBeforeLast(".") ?: "Unknown title",
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown artist",
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown album",
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0",
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LYRICS) ?: ""
                            )
                        }.getOrElse { listOf(file.name ?: "Unknown title", "Unknown artist", "Unknown album", "0", "") }
                        runCatching { retriever.release() }
                        val duration = data[3].toLongOrNull() ?: 0L
                        if (duration > 0L) result += Song(
                            uri.toString().hashCode().toLong(), data[0], data[1], duration, uri,
                            data[2], file.uri.path.orEmpty(),
                            file.name?.substringAfterLast(".", "")?.uppercase().orEmpty(), data[4]
                        )
                    }
                }
            }
        }
    }
    roots.forEach { raw ->
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(raw))?.let(::walk) }
    }
    return result
}

private fun parseEmbeddedLyrics(content: String): List<LyricLine> {
    if (content.isBlank()) return emptyList()
    val synced = parseLrc(content)
    if (synced.isNotEmpty()) return synced
    return content.replace("\\r", "").lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[ar:", true) && !it.startsWith("[ti:", true) &&
            !it.startsWith("[al:", true) && !it.startsWith("[by:", true) }
        .map { LyricLine(0L, it) }.toList()
}

private fun parseLrc(content: String): List<LyricLine> {
    val pattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")
    return content.lineSequence().flatMap { line ->
        val match = pattern.find(line)
        if (match == null) emptySequence()
        else {
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                3 -> fraction.toLong()
                else -> 0L
            }
            val lyric = match.groupValues[4].trim()
            if (lyric.isEmpty()) emptySequence()
            else sequenceOf(LyricLine((minutes * 60 + seconds) * 1000 + millis, lyric))
        }
    }.sortedBy { it.timeMs }.toList()
}

private fun time(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}


@Composable
private fun SongMetadataDialog(song: Song, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Song details", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                MetadataRow("Title", song.title)
                MetadataRow("Artist", song.artist)
                MetadataRow("Album", song.album)
                MetadataRow("Duration", time(song.duration))
                MetadataRow("Format", song.format.ifBlank { "Unknown" })
                MetadataRow("File", song.path.ifBlank { song.uri.lastPathSegment ?: "Audio file" })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = Lime) } }
    )
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Muted, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun LyricsDialog(
    song: Song, lyrics: List<LyricLine>, position: Long, loadLyrics: () -> Unit,
    onDismiss: () -> Unit, seek: (Long) -> Unit
) {
    val active = lyrics.indexOfLast { it.timeMs <= position }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("LYRICS", color = Lime, fontSize = 11.sp, letterSpacing = 2.sp)
                    Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = loadLyrics) { Text("LOAD .LRC", color = Lime) }
            }
            Spacer(Modifier.height(16.dp))
            if (lyrics.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No embedded or loaded lyrics found.\nLoad an .lrc file to display synced lyrics.", color = Muted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    itemsIndexed(lyrics) { index, line ->
                        Text(
                            line.text,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { seek(line.timeMs) }.padding(12.dp),
                            color = if (index == active) Lime else Color.White.copy(alpha = 0.62f),
                            fontSize = if (index == active) 22.sp else 18.sp,
                            fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
