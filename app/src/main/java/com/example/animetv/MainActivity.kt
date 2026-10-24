package com.example.animetv

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF090A0D)
private val Panel = Color(0xFF17191F)
private val Accent = Color(0xFF72D5FF)
private const val API = "https://api.animapper.net/api/v1"
private val http = OkHttpClient.Builder().callTimeout(18, TimeUnit.SECONDS).build()

data class Anime(val id: Int, val title: String, val cover: String, val description: String = "", val format: String = "ANIME", val status: String = "")
data class Episode(val number: String, val id: String, val server: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9,10,13)
        window.navigationBarColor = android.graphics.Color.rgb(9,10,13)
        setContent { AniTvApp() }
    }
}

private suspend fun api(path: String): JsonObject = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("$API$path").header("Accept", "application/json").build()
    http.newCall(req).execute().use { response ->
        if (response.code == 429) throw Exception("API đang giới hạn yêu cầu. Chờ một chút rồi thử lại.")
        if (!response.isSuccessful) throw Exception("Máy chủ trả lỗi HTTP ${response.code}")
        JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
    }
}
private fun JsonObject.str(key: String, fallback: String = "") = try { get(key)?.takeIf { !it.isJsonNull }?.asString ?: fallback } catch (_: Exception) { fallback }
private fun JsonObject.int(key: String, fallback: Int = 0) = try { get(key)?.asInt ?: fallback } catch (_: Exception) { fallback }
private fun JsonObject.obj(key: String) = try { getAsJsonObject(key) ?: JsonObject() } catch (_: Exception) { JsonObject() }
private fun JsonObject.anime(): Anime {
    val titles = obj("titles"); val images = obj("images")
    return Anime(int("id"), titles.str("vi").ifBlank { titles.str("en", "Anime ${int("id")}") }, images.str("coverXl").ifBlank { images.str("coverLg").ifBlank { images.str("coverMd") } }, obj("descriptions").str("vi").ifBlank { obj("descriptions").str("en") }, str("format"), str("status"))
}

@Composable
fun AniTvApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var provider by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var playingTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var savedDownloads by remember { mutableStateOf(loadDownloads(context)) }
    var showPlayer by remember { mutableStateOf(false) }

    suspend fun search(q: String) {
        loading = true; error = ""
        try {
            val data = api("/search?title=${URLEncoder.encode(q, "UTF-8")}&mediaType=ANIME&limit=30&offset=0&sortBy=POPULARITY&sortOrder=DESC")
            results = data.getAsJsonArray("results")?.mapNotNull { runCatching { it.asJsonObject.anime() }.getOrNull() } ?: emptyList()
            if (results.isEmpty()) error = "Không tìm thấy anime phù hợp."
        } catch (e: Exception) { error = e.message ?: "Không thể kết nối AniMapper." }
        loading = false
    }
    suspend fun loadHome() {
        loading = true; error = ""
        try {
            val data = api("/search?mediaType=ANIME&limit=24&offset=0&sortBy=POPULARITY&sortOrder=DESC")
            trending = data.getAsJsonArray("results")?.mapNotNull { runCatching { it.asJsonObject.anime() }.getOrNull() } ?: emptyList()
            if (trending.isEmpty()) error = "API chưa trả về danh sách anime."
        } catch (e: Exception) { error = e.message ?: "Không thể tải danh sách anime." }
        loading = false
    }
    suspend fun openAnime(a: Anime) {
        selected = a; episodes = emptyList(); provider = ""; error = ""; loading = true; tab = 0
        try {
            val meta = api("/metadata?id=${a.id}").obj("result")
            val providers = meta.obj("streamingProviders")
            provider = providers.keySet().firstOrNull() ?: ""
            if (provider.isNotBlank()) {
                val eps = api("/stream/episodes?id=${a.id}&provider=${URLEncoder.encode(provider, "UTF-8")}&limit=100&offset=0")
                episodes = eps.getAsJsonArray("episodes")?.mapNotNull {
                    runCatching { val e = it.asJsonObject; Episode(e.str("episodeNumber"), e.str("episodeId"), e.str("server")) }.getOrNull()
                } ?: emptyList()
            } else error = "Chưa có provider streaming được ánh xạ cho anime này."
        } catch (e: Exception) { error = e.message ?: "Không tải được thông tin tập phim." }
        loading = false
    }

    LaunchedEffect(Unit) { loadHome() }
    BackHandler(showPlayer || selected != null) {
        if (showPlayer) showPlayer = false else { selected = null; episodes = emptyList() }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
        Scaffold(containerColor = Bg, bottomBar = {
            if (!showPlayer) NavigationBar(containerColor = Color(0xFF111318), contentColor = Color.White) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0; selected = null }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Khám phá") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1; selected = null }, icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Của tôi") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2; selected = null }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Ngoại tuyến") })
            }
        }) { pad ->
            if (showPlayer && streamUrl.isNotBlank()) {
                VideoScreen(streamUrl, playingTitle, { showPlayer = false })
            } else if (selected != null) {
                AnimeDetail(selected!!, episodes, provider, loading, error,
                    onBack = { selected = null },
                    onEpisode = { ep -> scope.launch {
                        loading = true; error = ""
                        try {
                            val source = api("/stream/source?episodeData=${URLEncoder.encode(ep.id, "UTF-8")}&provider=${URLEncoder.encode(provider, "UTF-8")}&server=${URLEncoder.encode(ep.server, "UTF-8")}")
                            val url = source.str("url")
                            if (url.isBlank()) throw Exception("Provider chưa trả về URL phát.")
                            streamUrl = url; playingTitle = "${selected!!.title} · Tập ${ep.number}"; showPlayer = true
                        } catch (e: Exception) { error = e.message ?: "Không lấy được nguồn phát." }
                        loading = false
                    } },
                    favorite = favorites.contains(selected!!.id), onFavorite = {
                        favorites = if (favorites.contains(selected!!.id)) favorites - selected!!.id else favorites + selected!!.id
                        saveIds(context, "favorites", favorites)
                    }, onSaveOffline = { url, title ->
                        if (url.startsWith("https://") && !url.contains(".m3u8", true)) {
                            val intent = android.app.DownloadManager.Request(Uri.parse(url)).setTitle(title).setDescription("Tải tệp được phép lưu ngoại tuyến").setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_MOVIES, safeFileName(title) + ".mp4")
                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                            dm.enqueue(intent); savedDownloads = savedDownloads + title; saveStrings(context, "downloads", savedDownloads)
                        } else error = "Nguồn này là stream/playlist hoặc không phải URL HTTPS trực tiếp. Chỉ tải tệp video trực tiếp mà bạn có quyền lưu; playlist streaming không được tải bởi tính năng này."
                    })
            } else if (tab == 1) {
                Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
                    Text("Thư viện của tôi", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Anime đã lưu yêu thích trên thiết bị", color = Color.LightGray, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    val favItems = (trending + results).distinctBy { it.id }.filter { favorites.contains(it.id) }
                    if (favItems.isEmpty()) Text("Chưa có anime yêu thích. Mở anime rồi nhấn biểu tượng trái tim.", color = Color.LightGray)
                    else AnimeGrid(favItems) { a -> scope.launch { openAnime(a) } }
                }
            } else if (tab == 2) {
                Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
                    Text("Ngoại tuyến", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Tệp được tải bằng trình quản lý tải của Android", color = Color.LightGray, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    if (savedDownloads.isEmpty()) Text("Chưa có lượt tải nào được ghi nhận. Tải chỉ khả dụng với URL video trực tiếp được phép lưu.", color = Color.LightGray)
                    savedDownloads.forEach { title -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.DownloadDone, null, tint = Accent); Text(title, color = Color.White, modifier = Modifier.weight(1f).padding(start = 12.dp)); IconButton(onClick = { savedDownloads = savedDownloads - title; saveStrings(context, "downloads", savedDownloads) }) { Icon(Icons.Default.Delete, "Xóa khỏi danh sách", tint = Color.LightGray) } } }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(pad)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("ANITV", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp); Text("Anime, theo cách của bạn.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                        Icon(Icons.Default.Cast, contentDescription = "Cast", tint = Color.White)
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Tìm anime...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = emptyList() }) { Icon(Icons.Default.Close, null) } }, singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Accent, unfocusedBorderColor = Color.DarkGray))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { search(query.trim()) } }, enabled = query.isNotBlank() && !loading, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Tìm kiếm") }
                        OutlinedButton(onClick = { scope.launch { query = ""; results = emptyList(); loadHome() } }) { Text("Phổ biến") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Accent)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9D9D), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    val list = if (results.isNotEmpty()) results else trending
                    Text(if (results.isNotEmpty()) "KẾT QUẢ TÌM KIẾM" else "ĐƯỢC QUAN TÂM", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp))
                    if (list.isNotEmpty()) AnimeGrid(list) { a -> scope.launch { openAnime(a) } }
                    else if (!loading && error.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có dữ liệu", color = Color.LightGray) }
                }
            }
        }
    }
}

@Composable
private fun AnimeGrid(items: List<Anime>, onClick: (Anime) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(items, key = { it.id }) { anime ->
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Panel).clickable { onClick(anime) }) {
                AsyncImage(model = anime.cover, contentDescription = anime.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF252833)))
                Column(Modifier.padding(10.dp)) { Text(anime.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${anime.format} · ${anime.status}", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun AnimeDetail(anime: Anime, episodes: List<Episode>, provider: String, loading: Boolean, error: String, onBack: () -> Unit, onEpisode: (Episode) -> Unit, favorite: Boolean, onFavorite: () -> Unit, onSaveOffline: (String, String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(310.dp)) {
            AsyncImage(model = anime.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0x88000000), RoundedCornerShape(50))) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0x88000000), RoundedCornerShape(50))) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Accent else Color.White) }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) { Text(anime.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("${anime.format} · ${anime.status}", color = Color.LightGray, modifier = Modifier.padding(top = 5.dp)) }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(anime.description.ifBlank { "Thông tin chi tiết được cung cấp bởi AniMapper." }, color = Color(0xFFD0D2D8), lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayCircle, null, tint = Accent); Text("  TẬP PHIM", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
            if (provider.isNotBlank()) Text("Nguồn: $provider", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Accent)
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9D9D), modifier = Modifier.padding(vertical = 8.dp))
            if (episodes.isEmpty() && !loading) Text("Không có tập được ánh xạ cho anime này hoặc provider hiện không khả dụng.", color = Color.LightGray, modifier = Modifier.padding(vertical = 12.dp))
            episodes.forEach { ep ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Panel).clickable { onEpisode(ep) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(66.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF292D36)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.size(30.dp)) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text("Tập ${ep.number}", color = Color.White, fontWeight = FontWeight.SemiBold); Text("Server ${ep.server.ifBlank { "mặc định" }}", color = Color.LightGray, fontSize = 12.sp) }
                    Icon(Icons.Default.PlayCircleOutline, null, tint = Accent)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VideoScreen(url: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF101116)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }; Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; controllerShowTimeoutMs = 3500; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth().background(Color(0xFF101116)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Tune, null, tint = Accent); Text("Trình phát Media3 · tua, tốc độ và điều khiển chuẩn", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp)) }
    }
}

private fun prefs(c: Context) = c.getSharedPreferences("anitv", Context.MODE_PRIVATE)
private fun loadFavorites(c: Context): Set<Int> = prefs(c).getStringSet("favorites", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
private fun saveIds(c: Context, key: String, values: Set<Int>) { prefs(c).edit().putStringSet(key, values.map { it.toString() }.toSet()).apply() }
private fun loadDownloads(c: Context): Set<String> = prefs(c).getStringSet("downloads", emptySet())?.toSet() ?: emptySet()
private fun saveStrings(c: Context, key: String, values: Set<String>) { prefs(c).edit().putStringSet(key, values).apply() }
private fun safeFileName(s: String) = s.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "anime_video" }
