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

private val Background = Color(0xFF0B0D12)
private val Panel = Color(0xFF171B24)
private val Accent = Color(0xFFB7F36B)
private val Muted = Color(0xFF9AA3B2)

data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocaTuesApp() }
    }
}

@Composable
private fun LocaTuesApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var selected by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }

    fun refresh() { songs = readSongs(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) refresh()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                selected = player.currentMediaItemIndex
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(Unit) { if (granted) refresh() }

    fun play(index: Int) {
        if (index !in songs.indices) return
        selected = index
        player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, index, 0L)
        player.prepare()
        player.play()
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Scaffold(
            containerColor = Background,
            bottomBar = {
                if (selected in songs.indices) {
                    NowPlayingBar(
                        song = songs[selected],
                        playing = playing,
                        onToggle = { if (player.isPlaying) player.pause() else player.play() },
                        onNext = { player.seekToNextMediaItem() },
                        onPrevious = { player.seekToPreviousMediaItem() }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().background(Background).padding(padding).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(26.dp))
                Text("LOCATUES", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Spacer(Modifier.height(8.dp))
                Text("Your music.\nYour device.", color = Color.White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your library", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("${songs.size} songs on this device", color = Muted, fontSize = 13.sp)
                    }
                    IconButton(onClick = { if (granted) refresh() else permission.launch(audioPermission()) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Accent)
                    }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    !granted -> EmptyState("Allow music access", "LocaTues only reads audio files on this device.", "Grant permission") {
                        permission.launch(audioPermission())
                    }
                    songs.isEmpty() -> EmptyState("No music found", "Add audio files to your phone, then refresh.", null, null)
                    else -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(songs) { index, song ->
                            SongItem(song, selected == index) { play(index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, action: String?, onAction: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun SongItem(song: Song, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (active) Color(0xFF252D21) else Panel)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF29313D)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = if (active) Accent else Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDuration(song.duration), color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun NowPlayingBar(song: Song, playing: Boolean, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Album, contentDescription = null, tint = Accent, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
        IconButton(onClick = onToggle) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Accent)
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
    }
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

private fun readSongs(context: android.content.Context): List<Song> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
    val songs = mutableListOf<Song>()
    context.contentResolver.query(
        collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            songs += Song(
                id, cursor.getString(titleCol) ?: "Unknown title",
                cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                cursor.getLong(durationCol), ContentUris.withAppendedId(collection, id)
            )
        }
    }
    return songs
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
