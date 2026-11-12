package org.locatues.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import android.widget.Toast

private val Bg = Color(0xFF0B0D12)
private val Panel = Color(0xFF171B24)
private val Lime = Color(0xFFB7F36B)
private val Muted = Color(0xFF9AA3B2)

private object PlaybackHolder {
    @Volatile private var instance: ExoPlayer? = null

    fun getOrCreate(context: android.content.Context): ExoPlayer =
        instance ?: synchronized(this) {
            instance ?: ExoPlayer.Builder(context.applicationContext).build().also { instance = it }
        }
}

class PlaybackService : Service() {
    private lateinit var player: ExoPlayer
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateNotification()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateNotification()
        override fun onPlaybackStateChanged(playbackState: Int) = updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        player = PlaybackHolder.getOrCreate(this)
        createNotificationChannel()
        player.addListener(listener)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            ACTION_TOGGLE -> if (player.isPlaying) player.pause() else player.play()
            ACTION_NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
        updateNotification()
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls for LocaTues"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    private fun buildNotification(): android.app.Notification {
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "LocaTues"
        val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Local music"
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 10, it, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(player.isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREVIOUS, 1))
            .addAction(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (player.isPlaying) "Pause" else "Play", actionIntent(ACTION_TOGGLE, 2))
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT, 3))
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateNotification() {
        if (!::player.isInitialized) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::player.isInitialized) player.removeListener(listener)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "locatues_playback"
        private const val NOTIFICATION_ID = 2401
        const val ACTION_PREVIOUS = "org.locatues.app.PREVIOUS"
        const val ACTION_TOGGLE = "org.locatues.app.TOGGLE"
        const val ACTION_NEXT = "org.locatues.app.NEXT"
    }
}

data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri, val album: String = "Unknown album", val path: String = "", val format: String = "", val embeddedLyrics: String = "", val albumArt: ByteArray? = null)
data class LyricWord(val timeMs: Long, val text: String)
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList(), val synced: Boolean = true)

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
    val player = remember { PlaybackHolder.getOrCreate(ctx) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var selected by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    var full by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val favoritesPrefs = remember { ctx.getSharedPreferences("favorites", android.content.Context.MODE_PRIVATE) }
    var favorites by remember {
        mutableStateOf(runCatching {
            val array = org.json.JSONArray(favoritesPrefs.getString("song_ids", "[]"))
            buildSet { for (i in 0 until array.length()) add(array.getLong(i)) }
        }.getOrDefault(emptySet()))
    }
    fun toggleFavorite(songId: Long) {
        favorites = if (songId in favorites) favorites - songId else favorites + songId
        val array = org.json.JSONArray()
        favorites.forEach(array::put)
        favoritesPrefs.edit().putString("song_ids", array.toString()).apply()
    }
    var showLyrics by remember { mutableStateOf(false) }
    var showMetadata by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    val lyricsBySong = remember { mutableStateMapOf<Long, List<LyricLine>>() }
    val extrasLoaded = remember { mutableSetOf<Long>() }
    val scope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var downloadedUpdate by remember { mutableStateOf<java.io.File?>(null) }
    val installPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = downloadedUpdate
        if (apk != null && Build.VERSION.SDK_INT >= 26 && ctx.packageManager.canRequestPackageInstalls()) installApk(ctx, apk)
        else if (apk != null) updateMessage = "Allow LocaTues to install apps, then tap Check for updates again."
    }
    val checkForUpdates: () -> Unit = {
        if (!updateBusy) {
            updateBusy = true
            updateMessage = "Checking for updates…"
            scope.launch {
                val result = fetchLatestUpdate(ctx, ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode)
                updateBusy = false
                updateMessage = result.message
                if (result.apk != null) {
                    downloadedUpdate = result.apk
                    if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                        installPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                    } else installApk(ctx, result.apk)
                }
            }
        }
    }

    fun scan() {
        scope.launch {
            val combined = withContext(Dispatchers.IO) { scanAudio(ctx) + scanPickedFolders(ctx) }
            songs = combined.distinctBy { song ->
                val path = song.path.trim().replace('\\', '/').removeSuffix("/")
                if (path.startsWith("/")) path.lowercase(Locale.ROOT) else song.uri.normalizeScheme().toString()
            }.sortedBy { it.title.lowercase(Locale.ROOT) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, flags)
                ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            }.getOrDefault(false)
            if (persisted) {
                val prefs = ctx.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
                val saved = loadSavedFolderUris(prefs).toMutableSet()
                saved.add(uri.toString())
                saveFolderUris(prefs, saved)
                scan()
                Toast.makeText(ctx, "Folder saved. New songs will be found automatically when the library refreshes.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, "Android didn't grant lasting access to this folder. Please choose it again.", Toast.LENGTH_LONG).show()
            }
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
        onDispose { player.removeListener(listener) }
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
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java)) }
        player.setMediaItems(songs.map { song -> MediaItem.Builder().setUri(song.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).build()).build() }, i, 0L)
        player.prepare()
        player.play()
        full = true
        val song = songs[i]
        if (extrasLoaded.add(song.id)) scope.launch {
            val extras = withContext(Dispatchers.IO) {
                readEmbeddedLyrics(ctx, song.uri) to readEmbeddedArtwork(ctx, song.uri)
            }
            val index = songs.indexOfFirst { it.id == song.id }
            if (index >= 0) songs = songs.toMutableList().also { list ->
                list[index] = list[index].copy(embeddedLyrics = extras.first, albumArt = extras.second)
            }
        }
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
                    lyrics = lyricsBySong[songs[selected].id] ?: parseEmbeddedLyrics(songs[selected].embeddedLyrics),
                    isFavorite = songs[selected].id in favorites,
                    toggleFavorite = { toggleFavorite(songs[selected].id) },
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
                    selectedTab = selectedTab, onTabChange = { selectedTab = it },
                    favorites = favorites, toggleFavorite = ::toggleFavorite,
                    refresh = { if (allowed) scan() else ask.launch(audioPermission()) },
                    grant = { ask.launch(audioPermission()) },
                    chooseFolder = { pickFolder.launch(null) },
                    play = ::play,
                    openPlayer = { full = true },
                    toggle = { if (player.isPlaying) player.pause() else player.play() },
                    next = { player.seekToNextMediaItem() },
                    checkUpdates = checkForUpdates
                )
            }
        }
        if (updateMessage != null) AlertDialog(onDismissRequest = { if (!updateBusy) updateMessage = null }, containerColor = Panel, title = { Text("LocaTues updates", color = Color.White) }, text = { Text(updateMessage ?: "", color = Muted) }, confirmButton = { TextButton(onClick = { updateMessage = null }) { Text("OK", color = Lime) } })
    }
}

@Composable
private fun HomeScreen(
    songs: List<Song>, selected: Int, playing: Boolean, allowed: Boolean,
    selectedTab: Int, onTabChange: (Int) -> Unit, favorites: Set<Long>, toggleFavorite: (Long) -> Unit,
    refresh: () -> Unit, grant: () -> Unit, chooseFolder: () -> Unit, play: (Int) -> Unit,
    openPlayer: () -> Unit, toggle: () -> Unit, next: () -> Unit, checkUpdates: () -> Unit
) {
    Scaffold(containerColor = Bg, bottomBar = {
        Column {
            if (selected in songs.indices) {
                Row(
                    Modifier.fillMaxWidth().background(Panel).clickable { openPlayer() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongArtwork(songs[selected], Modifier.size(42.dp), 26)
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
            NavigationBar(containerColor = Panel, contentColor = Lime) {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { onTabChange(0) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") }, label = { Text("Home") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime, indicatorColor = Color(0xFF293323), unselectedIconColor = Muted, unselectedTextColor = Muted)
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { onTabChange(1) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") }, label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime, indicatorColor = Color(0xFF293323), unselectedIconColor = Muted, unselectedTextColor = Muted)
                )
            }
        }
    }) { pad ->
        if (selectedTab == 1) {
            Column(Modifier.fillMaxSize().background(Bg).padding(pad).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(28.dp))
                Text("SETTINGS", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Your app.", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Text("LIBRARY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("${songs.size} songs indexed", color = Color.White, fontSize = 16.sp)
                SettingsAction("Choose music folder", "Add a folder and its subfolders", Icons.Default.FolderOpen, chooseFolder)
                SettingsAction("Refresh library", "Find newly added or removed songs", Icons.Default.Refresh, refresh)
                SettingsAction("Music permission", "Manage access to audio on this device", Icons.Default.MusicNote, grant)
                Spacer(Modifier.height(20.dp))
                Text("APP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                SettingsAction("Check for updates", "Look for the latest LocaTues version", Icons.Default.SystemUpdate, checkUpdates)
                Spacer(Modifier.weight(1f))
                Text("LocaTues • Free and open source", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 20.dp))
            }
        } else Column(Modifier.fillMaxSize().background(Bg).padding(pad).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(26.dp))
            Text("LOCATUES", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Your music.\nYour device.", color = Color.White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your library", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("${songs.size} songs across your device", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = checkUpdates) { Icon(Icons.Default.SystemUpdate, "Check for updates", tint = Lime) }
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
                            SongArtwork(song, Modifier.size(46.dp), 28)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, color = Muted, fontSize = 12.sp)
                            }
                            Text(time(song.duration), color = Muted, fontSize = 11.sp)
                            IconButton(onClick = { toggleFavorite(song.id) }) {
                                Icon(
                                    if (song.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (song.id in favorites) "Remove from favourites" else "Add to favourites",
                                    tint = if (song.id in favorites) Color(0xFFFF5C7A) else Muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 15.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PlayerScreen(
    song: Song, playing: Boolean, position: Long, duration: Long, shuffle: Boolean, repeat: Int,
    isFavorite: Boolean, toggleFavorite: () -> Unit,
    lyrics: List<LyricLine>, showLyrics: () -> Unit, metadata: () -> Unit, loadLyrics: () -> Unit,
    back: () -> Unit, toggle: () -> Unit, previous: () -> Unit, next: () -> Unit,
    seek: (Long) -> Unit, scrubbing: (Boolean) -> Unit, toggleShuffle: () -> Unit, toggleRepeat: () -> Unit
) {
    val playerBg = Color(0xFF19110E)
    val playerPanel = Color(0xFF30221C)
    val peach = Color(0xFFFFB77E)
    val softText = Color(0xFFD6C2B5)
    var slider by remember(song.id) { mutableFloatStateOf(0f) }
    var showInlineLyrics by remember(song.id) { mutableStateOf(false) }
    val lyricListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val synced = lyrics.filter { it.synced }
    val activeLyric = synced.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)

    LaunchedEffect(position, duration) {
        slider = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f
    }
    LaunchedEffect(showInlineLyrics, activeLyric) {
        if (showInlineLyrics && synced.isNotEmpty()) {
            lyricListState.animateScrollToItem((activeLyric - 1).coerceAtLeast(0))
        }
    }

    Column(
        Modifier.fillMaxSize().background(playerBg).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = back) {
                Icon(Icons.Default.KeyboardArrowDown, "Back", tint = softText, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Now Playing", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = showLyrics) {
                Icon(Icons.Default.QueueMusic, "Open full lyrics", tint = softText, modifier = Modifier.size(23.dp))
            }
            IconButton(onClick = metadata) {
                Icon(Icons.Default.MoreVert, "Song details", tint = softText)
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().widthIn(max = 390.dp).height(286.dp)
                .clip(RoundedCornerShape(26.dp)).background(playerPanel),
            contentAlignment = Alignment.Center
        ) {
            if (showInlineLyrics) {
                if (synced.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (lyrics.isEmpty()) "No lyrics available" else "Synced lyrics unavailable", color = softText)
                        TextButton(onClick = loadLyrics) { Text("LOAD .LRC FILE", color = peach) }
                    }
                } else {
                    LazyColumn(
                        state = lyricListState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 92.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(synced) { index, line ->
                            Text(
                                line.text,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .clickable { seek(line.timeMs) }.padding(vertical = 5.dp, horizontal = 4.dp),
                                color = if (index == activeLyric) Color.White else softText.copy(alpha = 0.48f),
                                fontSize = if (index == activeLyric) 21.sp else 17.sp,
                                fontWeight = if (index == activeLyric) FontWeight.Bold else FontWeight.Medium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                SongArtwork(song, Modifier.fillMaxSize(), 112)
            }
        }

        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(
                song.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                song.artist, color = softText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(14.dp))
        Slider(
            value = slider,
            onValueChange = { slider = it; scrubbing(true) },
            onValueChangeFinished = { seek(slider.toLong()); scrubbing(false) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = peach, activeTrackColor = peach,
                inactiveTrackColor = Color(0xFF6D5140)
            ),
            modifier = Modifier.fillMaxWidth().height(30.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(time(position), color = softText, fontSize = 12.sp)
            Row(
                Modifier.clip(RoundedCornerShape(30.dp)).background(playerPanel).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = peach, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text(song.format.ifBlank { "AUDIO" }, color = softText, fontSize = 11.sp)
            }
            Text(time(duration), color = softText, fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = previous,
                modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(23.dp)).background(playerPanel)
            ) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp)) }

            FilledIconButton(
                onClick = toggle,
                modifier = Modifier.weight(1.55f).height(72.dp).clip(RoundedCornerShape(23.dp)),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = peach, contentColor = playerBg)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", modifier = Modifier.size(40.dp))
            }

            IconButton(
                onClick = next,
                modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(23.dp)).background(playerPanel)
            ) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp)) }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showInlineLyrics = !showInlineLyrics },
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (showInlineLyrics) Color(0xFF75400F) else playerPanel)
            ) { Icon(Icons.Default.Lyrics, "Toggle lyrics panel", tint = if (showInlineLyrics) peach else softText) }

            IconButton(
                onClick = toggleShuffle,
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(playerPanel)
            ) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) peach else softText) }

            IconButton(
                onClick = toggleRepeat,
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(playerPanel)
            ) {
                Icon(
                    if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat", tint = if (repeat == Player.REPEAT_MODE_OFF) softText else peach
                )
            }

            IconButton(
                onClick = toggleFavorite,
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(playerPanel)
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Favourite", tint = if (isFavorite) Color(0xFFFF6B78) else softText
                )
            }
        }
        Spacer(Modifier.height(20.dp))
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


private data class UpdateDownloadResult(val apk: java.io.File?, val message: String)

private suspend fun fetchLatestUpdate(context: android.content.Context, currentVersionCode: Int): UpdateDownloadResult =
    withContext(Dispatchers.IO) {
        try {
            val api = URL("https://api.github.com/repos/neko-hub-cat/LocaTues/releases/tags/nightly")
            val connection = (api.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LocaTues-Android")
            }
            val release = try {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
            val body = release.optString("body")
            val latestVersion = Regex("(?i)versionCode\\s*=\\s*(\\d+)").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: release.optString("tag_name").removePrefix("v").toIntOrNull()
                ?: 0
            if (latestVersion <= currentVersionCode) {
                return@withContext UpdateDownloadResult(null, "You're using the latest version (version $currentVersionCode).")
            }
            val assets = release.optJSONArray("assets")
            var downloadUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name") == "app-debug.apk") {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (downloadUrl.isNullOrBlank()) {
                return@withContext UpdateDownloadResult(null, "A newer version exists, but its APK isn't attached yet.")
            }
            val folder = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = java.io.File(folder, "LocaTues.apk")
            val download = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "LocaTues-Android")
            }
            try {
                download.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
            } finally {
                download.disconnect()
            }
            if (!apk.exists() || apk.length() == 0L) {
                apk.delete()
                return@withContext UpdateDownloadResult(null, "The update download was empty. Please try again.")
            }
            UpdateDownloadResult(apk, "Version $latestVersion downloaded. Confirm installation in Android.")
        } catch (error: Exception) {
            UpdateDownloadResult(null, "Couldn't check for updates: ${error.message ?: "network error"}")
        }
    }

private fun installApk(context: android.content.Context, apk: java.io.File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Couldn't open installer: ${it.message}", Toast.LENGTH_LONG).show()
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
                    songs += Song(
                        songId,
                        cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                        cursor.getString(artistCol)?.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown artist",
                        duration,
                        itemUri,
                        if (albumCol >= 0) cursor.getString(albumCol) ?: "Unknown album" else "Unknown album",
                        if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else "",
                        itemUri.lastPathSegment?.substringAfterLast(".")?.uppercase().orEmpty(),
                     )
                }
            }
        }
    }
    return songs.distinctBy { it.uri }.sortedBy { it.title.lowercase(Locale.ROOT) }
}

private fun storagePathFromDocumentUri(uri: Uri): String? {
    return runCatching {
        val documentId = DocumentsContract.getDocumentId(uri)
        val separator = documentId.indexOf(':')
        if (separator <= 0) return@runCatching null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1).trimStart('/')
        if (relative.isBlank()) return@runCatching null
        val root = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
        "$root/$relative"
    }.getOrNull()
}

private fun loadSavedFolderUris(prefs: android.content.SharedPreferences): Set<String> {
    val json = prefs.getString("folder_uris_json", null)
    if (!json.isNullOrBlank()) {
        return runCatching {
            val array = org.json.JSONArray(json)
            buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        }.getOrDefault(emptySet())
    }
    // Migrate folder selections saved by earlier LocaTues versions.
    return prefs.getStringSet("folders", emptySet()).orEmpty().toSet()
}

private fun saveFolderUris(prefs: android.content.SharedPreferences, uris: Set<String>) {
    val array = org.json.JSONArray()
    uris.forEach(array::put)
    prefs.edit().putString("folder_uris_json", array.toString()).remove("folders").apply()
}

private fun scanPickedFolders(ctx: android.content.Context): List<Song> {
    val prefs = ctx.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
    val roots = loadSavedFolderUris(prefs)
    val persisted = ctx.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
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
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0"
                            )
                        }.getOrElse { listOf(file.name ?: "Unknown title", "Unknown artist", "Unknown album", "0") }
                        runCatching { retriever.release() }
                        val duration = data[3].toLongOrNull() ?: 0L
                        if (duration > 0L) result += Song(
                            uri.toString().hashCode().toLong(), data[0], data[1], duration, uri,
                            data[2], storagePathFromDocumentUri(uri) ?: file.uri.path.orEmpty(),
                            file.name?.substringAfterLast(".", "")?.uppercase().orEmpty()
                        )
                    }
                }
            }
        }
    }
    roots.forEach { raw ->
        // Ignore stale selections whose Android URI permission was revoked.
        if (raw in persisted) {
            runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(raw))?.let(::walk) }
        }
    }
    return result
}

private fun readEmbeddedArtwork(ctx: android.content.Context, uri: Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(ctx, uri)
        retriever.embeddedPicture?.copyOf()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
private fun SongArtwork(song: Song, modifier: Modifier = Modifier, iconSize: Int = 48) {
    val bitmap = remember(song.id) {
        song.albumArt?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF202A27)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = "Artwork for ${song.title}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.GraphicEq, null, tint = Lime, modifier = Modifier.size(iconSize.dp))
        }
    }
}

private fun readEmbeddedLyrics(ctx: android.content.Context, uri: Uri): String {
    val temp = runCatching {
        val displayName = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeExt = when (ctx.contentResolver.getType(uri)?.lowercase(Locale.ROOT)) {
            "audio/mpeg" -> "mp3"
            "audio/flac" -> "flac"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/opus" -> "opus"
            else -> "mp3"
        }
        val extension = ext.takeIf { it in setOf("mp3", "flac", "m4a", "mp4", "ogg", "oga", "wav", "aif", "aiff", "wma", "ape", "aac") } ?: mimeExt
        val file = java.io.File.createTempFile("locatues-tags-", ".$extension", ctx.cacheDir)
        ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            ?: throw IllegalStateException("Cannot open audio")
        file
    }.getOrNull() ?: return ""
    return try {
        org.jaudiotagger.audio.AudioFileIO.read(temp).tag?.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS).orEmpty()
    } catch (_: Exception) {
        ""
    } finally {
        temp.delete()
    }
}

private fun parseEmbeddedLyrics(content: String): List<LyricLine> {
    if (content.isBlank()) return emptyList()
    val synced = parseLrc(content)
    if (synced.isNotEmpty()) return synced
    return content.replace("\r", "").lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[ar:", true) && !it.startsWith("[ti:", true) &&
            !it.startsWith("[al:", true) && !it.startsWith("[by:", true) &&
            !it.startsWith("[length:", true) && !it.startsWith("[re:", true) }
        .map { LyricLine(0L, it, synced = false) }.toList()
}

private fun lyricTimestamp(minutes: String, seconds: String, fraction: String): Long {
    val millis = when (fraction.length) {
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        3 -> fraction.toLong()
        else -> 0L
    }
    return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis
}

private fun parseLrc(content: String): List<LyricLine> {
    val linePattern = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
    val wordPattern = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")
    return content.lineSequence().flatMap { raw ->
        val match = linePattern.find(raw) ?: return@flatMap emptySequence()
        val lineStart = lyricTimestamp(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        val lyric = match.groupValues[4].trim()
        if (lyric.isEmpty()) return@flatMap emptySequence()
        val words = mutableListOf<LyricWord>()
        val markers = wordPattern.findAll(lyric).toList()
        if (markers.isNotEmpty()) {
            markers.forEachIndexed { index, marker ->
                val end = if (index + 1 < markers.size) markers[index + 1].range.first else lyric.length
                val token = lyric.substring(marker.range.last + 1, end).trim()
                if (token.isNotEmpty()) {
                    words += LyricWord(
                        lyricTimestamp(marker.groupValues[1], marker.groupValues[2], marker.groupValues[3]),
                        token
                    )
                }
            }
        }
        val cleanText = if (words.isNotEmpty()) words.joinToString(" ") { it.text } else lyric
        sequenceOf(LyricLine(lineStart, cleanText, words, synced = true))
    }.sortedBy { it.timeMs }.toList()
}

@Composable
private fun SyncedLyricText(line: LyricLine, position: Long, modifier: Modifier = Modifier, fontSize: Int = 18, isActive: Boolean = true) {
    val activeWord = line.words.indexOfLast { it.timeMs <= position }
    val annotated = buildAnnotatedString {
        if (line.words.isEmpty()) {
            withStyle(SpanStyle(color = if (line.synced && isActive) Lime else Color.White.copy(alpha = 0.72f), fontWeight = if (line.synced && isActive) FontWeight.Bold else FontWeight.Medium)) {
                append(line.text)
            }
        } else {
            line.words.forEachIndexed { index, word ->
                if (index > 0) append(" ")
                withStyle(SpanStyle(
                    color = if (index == activeWord) Lime else Color.White.copy(alpha = 0.62f),
                    fontWeight = if (index == activeWord) FontWeight.Bold else FontWeight.Normal
                )) { append(word.text) }
            }
        }
    }
    Text(
        text = annotated, modifier = modifier,
        fontSize = fontSize.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        lineHeight = (fontSize + 8).sp
    )
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
    val active = if (lyrics.any { it.synced }) lyrics.indexOfLast { it.synced && it.timeMs <= position }.coerceAtLeast(0) else -1
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active) }
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
                    state = listState,
                    contentPadding = PaddingValues(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(lyrics) { index, line ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .then(if (line.synced) Modifier.clickable { seek(line.timeMs) } else Modifier)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            SyncedLyricText(line, position, Modifier.fillMaxWidth(), if (index == active && line.synced) 21 else 18, index == active)
                        }
                    }
                }
            }
        }
    }
}
