package org.locatues.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { LocaTuesApp() } }
}

@Composable
private fun LocaTuesApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var selected by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    var full by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    fun scan() { songs = scanAudio(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; if (it) scan() }
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
        while (true) { if (!scrubbing) pos = player.currentPosition.coerceAtLeast(0); dur = player.duration.coerceAtLeast(0); delay(350) }
    }
    fun play(i: Int) {
        if (i !in songs.indices) return
        selected = i
        player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, i, 0L)
        player.prepare(); player.play(); full = true
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Bg, surface = Panel)) {
        if (full && selected in songs.indices) {
            PlayerScreen(
                song = songs[selected], playing = playing, position = pos, duration = dur,
                shuffle = shuffle, repeat = repeat,
                back = { full = false },
                toggle = { if (player.isPlaying) player.pause() else player.play() },
                previous = { player.seekToPreviousMediaItem() }, next = { player.seekToNextMediaItem() },
                seek = { pos = it; player.seekTo(it) }, scrubbing = { scrubbing = it },
                toggleShuffle = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle },
                toggleRepeat = {
                    repeat = when (repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                    player.repeatMode = repeat
                }
            )
        } else {
            Scaffold(containerColor = Bg, bottomBar = {
                if (selected in songs.indices) {
                    Row(Modifier.fillMaxWidth().background(Panel).clickable { full = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Album, null, tint = Lime, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(songs[selected].title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songs[selected].artist, color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Lime) }
                        IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                    }
                }
            }) { pad ->
                Column(Modifier.fillMaxSize().background(Bg).padding(pad).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(26.dp))
                    Text("LOCATUES", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("Your music.\nYour device.", color = Color.White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Your library", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold); Text("${songs.size} songs on this device", color = Muted, fontSize = 13.sp) }
                        IconButton(onClick = { if (allowed) scan() else ask.launch(audioPermission()) }) { Icon(Icons.Default.Refresh, "Refresh", tint = Lime) }
                    }
                    when {
                        !allowed -> EmptyLibrary("Allow music access", "LocaTues only reads audio files on this device.", "Grant permission") { ask.launch(audioPermission()) }
                        songs.isEmpty() -> EmptyLibrary("No music found", "Add music files to your phone and refresh.", null, null)
                        else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(songs) { i, s ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected == i) Color(0xFF252D21) else Panel).clickable { play(i) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MusicNote, null, tint = if (selected == i) Lime else Muted, modifier = Modifier.size(34.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) { Text(s.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(s.artist, color = Muted, fontSize = 12.sp) }
                                    Text(time(s.duration), color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    song: Song, playing: Boolean, position: Long, duration: Long, shuffle: Boolean, repeat: Int,
    back: () -> Unit, toggle: () -> Unit, previous: () -> Unit, next: () -> Unit,
    seek: (Long) -> Unit, scrubbing: (Boolean) -> Unit, toggleShuffle: () -> Unit, toggleRepeat: () -> Unit
) {
    var slider by remember(song.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(position, duration) { slider = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Default.KeyboardArrowDown, "Back", tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}) { Icon(Icons.Default.MoreHoriz, "More", tint = Color.White) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Color(0xFF202A27)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = Lime, modifier = Modifier.size(112.dp))
        }
        Spacer(Modifier.height(34.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {}) { Icon(Icons.Default.FavoriteBorder, "Favourite", tint = Muted) }
        }
        Spacer(Modifier.height(18.dp))
        Slider(value = slider, onValueChange = { slider = it; scrubbing(true) }, onValueChangeFinished = { seek(slider.toLong()); scrubbing(false) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = Color(0xFF343B46)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(position), color = Muted, fontSize = 11.sp); Text(time(duration), color = Muted, fontSize = 11.sp) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = toggleShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) Lime else Muted) }
            IconButton(onClick = previous, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(36.dp)) }
            FilledIconButton(onClick = toggle, modifier = Modifier.size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Bg)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", modifier = Modifier.size(40.dp)) }
            IconButton(onClick = next, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = toggleRepeat) { Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (repeat == Player.REPEAT_MODE_OFF) Muted else Lime) }
        }
        Spacer(Modifier.weight(1f))
        Text("PLAYING FROM YOUR LIBRARY", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EmptyLibrary(title: String, subtitle: String, action: String?, click: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LibraryMusic, null, tint = Lime, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp)); Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
            if (action != null && click != null) { Spacer(Modifier.height(16.dp)); Button(onClick = click) { Text(action) } }
        }
    }
}
private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
private fun scanAudio(ctx: android.content.Context): List<Song> {
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
    val out = mutableListOf<Song>()
    ctx.contentResolver.query(uri, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) { val songId = c.getLong(id); out += Song(songId, c.getString(title) ?: "Unknown title", c.getString(artist)?.takeUnless { it == "<unknown>" } ?: "Unknown artist", c.getLong(duration), ContentUris.withAppendedId(uri, songId)) }
    }
    return out
}
private fun time(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60) }
