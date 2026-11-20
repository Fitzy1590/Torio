package com.fitzy.torio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.fitzy.torio.data.BookEntity
import com.fitzy.torio.net.Provider
import com.fitzy.torio.net.SourceResult
import com.fitzy.torio.net.formatSize

private enum class Tab(val label: String) { Search("Search"), Browse("Browse"), Library("Library"), Player("Player"), Settings("Settings") }

@Composable
fun TorioRoot(vm: TorioViewModel) {
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var tab by remember { mutableStateOf(Tab.Search) }
        val upd by vm.update.collectAsStateWithLifecycle()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Downloaded update is ready: open the system installer (the user confirms there).
        LaunchedEffect(upd) {
            (upd as? UpdateState.Ready)?.let { vm.installLaunched(it.info, com.fitzy.torio.net.Updater.install(ctx, it.file)) }
        }
        val p by vm.player.ui.collectAsStateWithLifecycle()
        LaunchedEffect(p.bookId) { if (p.bookId != null && tab == Tab.Search && p.isPlaying) { /* stay */ } }
        Scaffold(
            bottomBar = {
                Column {
                    if (p.bookId != null && tab != Tab.Player) MiniPlayer(p, vm) { tab = Tab.Player }
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(selected = tab == t, onClick = { tab = t },
                                icon = { Icon(when (t) { Tab.Search -> Icons.Default.Search; Tab.Browse -> Icons.Default.Public; Tab.Library -> Icons.Default.LibraryBooks
                                    Tab.Player -> Icons.Default.Headphones; Tab.Settings -> Icons.Default.Settings }, t.label) },
                                label = { Text(t.label) })
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.Search -> SearchScreen(vm) { tab = Tab.Player }
                    Tab.Browse -> BrowseScreen(vm)
                    Tab.Library -> LibraryScreen(vm) { tab = Tab.Player }
                    Tab.Player -> PlayerScreen(vm)
                    Tab.Settings -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(p: com.fitzy.torio.player.PlayerUi, vm: TorioViewModel, open: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.coverUrl != null) AsyncImage(p.coverUrl, "Cover", contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
            TextButton(onClick = open, modifier = Modifier.weight(1f)) {
                Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = { vm.player.toggle() }) {
                Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (p.isPlaying) "Pause" else "Play")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(vm: TorioViewModel, goPlayer: () -> Unit) {
    val s by vm.search.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text("Search audiobooks") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.runSearch(q) }),
            trailingIcon = { IconButton({ vm.runSearch(q) }) { Icon(Icons.Default.Search, "Search") } })
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        s.messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.results, key = { it.id }) { r -> SourceCard(r, busy = s.busyId == r.id,
                onStream = { vm.pick(r, true, goPlayer) }, onDownload = { vm.pick(r, false) {} }) }
        }
    }
}

@Composable
private fun SourceCard(r: SourceResult, busy: Boolean, onStream: () -> Unit, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf("🔴", "🟡", "🟢")[r.health] + " " + when (r.provider) {
                    Provider.TORBOX_CLOUD -> "⚡ TorBox Cloud"; Provider.TORBOX_SEARCH -> if (r.cached) "⚡ TorBox Cached" else "Torrent"
                } + if (r.usenet) " · Usenet" else "", style = MaterialTheme.typography.labelLarge)
            }
            Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
            val rate = if (r.bitrateKbps > 0) " | ${r.bitrateKbps}kbps" else ""
            val health = when { r.provider == Provider.TORBOX_CLOUD -> "In your cloud"; r.cached -> "Instant"; r.seeders > 0 -> "${r.seeders} seeds"; else -> "Direct" }
            Text("${r.codec} | ${formatSize(r.sizeBytes)}$rate | $health", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(32.dp)) else {
                    Button(onStream) { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(4.dp)); Text("Stream Live") }
                    OutlinedButton(onDownload) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text("Download") }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(vm: TorioViewModel, goPlayer: () -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val prog by vm.progress.collectAsStateWithLifecycle()
    val cur by vm.player.ui.collectAsStateWithLifecycle()
    var section by remember { mutableIntStateOf(0) }
    val names = listOf("In progress", "Downloaded", "Finished")
    val shown = books.filter { b ->
        val p = prog[b.id]
        when (section) { 0 -> p != null && !p.finished || (p == null && !b.downloaded)
            1 -> b.downloaded; else -> p?.finished == true }
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section) { names.forEachIndexed { i, n -> Tab(section == i, { section = i }, text = { Text(n) }) } }
        if (shown.isEmpty()) Text("Nothing here yet.", Modifier.padding(24.dp))
        LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { b ->
                val p = prog[b.id]
                Card(Modifier.fillMaxWidth().clickable { vm.play(b); goPlayer() }) {
                  Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.size(72.dp).clip(MaterialTheme.shapes.small), tonalElevation = 4.dp) {
                        if (b.coverUrl != null) AsyncImage(b.coverUrl, "Cover", contentScale = ContentScale.Crop)
                        else Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Headphones, null, Modifier.size(32.dp)) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${b.codec} | ${formatSize(b.sizeBytes)}" + if (b.downloaded) " | Offline" else " | Streaming", style = MaterialTheme.typography.bodySmall)
                        if (p != null) LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton({ vm.play(b); goPlayer() }) {
                                Icon(if (cur.bookId == b.id && cur.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                Text(if (cur.bookId == b.id) (if (cur.isPlaying) "Playing" else "Resume") else if (p?.percent ?: 0 > 0) "Resume ${p?.percent}%" else "Play")
                            }
                            if (!b.downloaded) IconButton({ vm.download(b) }) { Icon(Icons.Default.Download, "Download") }
                            IconButton({ vm.markFinished(b, p?.finished != true) }) { Icon(Icons.Default.CheckCircle, "Toggle finished") }
                            IconButton({ vm.delete(b) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                  }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(vm: TorioViewModel) {
    val p by vm.player.ui.collectAsStateWithLifecycle()
    var drawer by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    if (p.bookId == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Pick a book from Search or Library") }; return }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Surface(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = false).clip(RoundedCornerShape(20.dp)), tonalElevation = 4.dp) {
                if (p.coverUrl != null) AsyncImage(p.coverUrl, "Cover", contentScale = ContentScale.Crop)
                else Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Headphones, null, Modifier.size(96.dp)) }
            }
        }
        Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        p.trackTitles.getOrNull(p.trackIndex)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val max = p.durationMs.coerceAtLeast(1).toFloat()
        Slider(drag ?: p.positionMs.toFloat(), { drag = it }, valueRange = 0f..max,
            onValueChangeFinished = { drag?.let { vm.player.seekTo(it.toLong()) }; drag = null }, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((drag ?: p.positionMs.toFloat()).toLong())); Text(fmt(p.durationMs))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton({ vm.player.skip(-30_000) }) { Icon(Icons.Default.Replay30, "Back 30 seconds", Modifier.size(36.dp)) }
            FilledIconButton({ vm.player.toggle() }, Modifier.size(72.dp)) {
                Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (p.isPlaying) "Pause" else "Play", Modifier.size(40.dp))
            }
            IconButton({ vm.player.skip(30_000) }) { Icon(Icons.Default.Forward30, "Forward 30 seconds", Modifier.size(36.dp)) }
        }
        Text("Speed ${"%.1f".format(p.speed)}x", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)) { s ->
                FilterChip(Math.abs(p.speed - s) < 0.05f, { vm.player.setSpeed(s) }, { Text("${s}x") })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Voice clarity", Modifier.weight(1f)); Switch(p.dsp, { vm.player.setDsp(it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Skip silence", Modifier.weight(1f)); Switch(p.skipSilence, { vm.player.setSkipSilence(it) })
        }
        OutlinedButton({ drawer = true }) { Icon(Icons.Default.List, null); Text(" Chapters (${p.trackTitles.size})") }
    }
    if (drawer) ModalBottomSheet({ drawer = false }) {
        LazyColumn { itemsIndexed(p.trackTitles) { i, t ->
            ListItem({ Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) }, Modifier.clickableNoRipple { vm.player.selectTrack(i); drawer = false },
                leadingContent = { if (i == p.trackIndex) Icon(Icons.Default.PlayArrow, "Playing") })
        } }
    }
}

private fun Modifier.clickableNoRipple(f: () -> Unit) = this.then(Modifier.clickable(onClick = f))

@Composable
private fun SettingsScreen(vm: TorioViewModel) {
    val s = vm.settings
    var key by remember { mutableStateOf(s.torboxKey) }
    var abb by remember { mutableStateOf(s.abbUrl) }
    var auto by remember { mutableStateOf(s.autoResume) }
    var autoUpd by remember { mutableStateOf(s.autoUpdate) }
    val upd by vm.update.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(key, { key = it; s.torboxKey = it }, label = { Text("TorBox API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedTextField(abb, { abb = it; s.abbUrl = it }, label = { Text("AudiobookBay address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-resume on launch", Modifier.weight(1f)); Switch(auto, { auto = it; s.autoResume = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-update on launch", Modifier.weight(1f)); Switch(autoUpd, { autoUpd = it; s.autoUpdate = it })
        }
        Text("Torio build ${com.fitzy.torio.net.Updater.currentBuild(ctx)}", style = MaterialTheme.typography.bodySmall)
        when (val u = upd) {
            is UpdateState.Checking -> Text("Checking for updates...")
            is UpdateState.UpToDate -> Text("You're on the latest build." + (u.latest?.let { " (GitHub latest: build $it)" } ?: " (no release found on GitHub)"))
            is UpdateState.Downloading -> Text("Downloading build ${u.info.build}...")
            is UpdateState.Failed -> Text(u.message, color = MaterialTheme.colorScheme.error)
            is UpdateState.Available -> {
                Text("Build ${u.info.build} is available." + (u.note?.let { " $it" } ?: ""))
                Button({ vm.downloadUpdate(u.info) }, Modifier.fillMaxWidth()) { Text("Update to build ${u.info.build}") }
            }
            else -> {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.checkForUpdates(auto = false) }, Modifier.weight(1f)) { Text("Check for updates") }
            OutlinedButton({ uri.openUri((upd as? UpdateState.Available)?.info?.pageUrl ?: com.fitzy.torio.net.RELEASES_PAGE) }, Modifier.weight(1f)) { Text("View on GitHub") }
        }
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(vm: TorioViewModel) {
    val sites = listOf(
        "AudiobookBay" to vm.settings.abbUrl,
        "Full Length Audiobooks" to "https://fulllengthaudiobooks.com",
        "LibriVox" to "https://librivox.org",
        "Internet Archive" to "https://archive.org/details/audiobooks",
        "Loyal Books" to "https://www.loyalbooks.com",
    )
    var url by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    androidx.activity.compose.BackHandler(url != null) { if (web?.canGoBack() == true) web?.goBack() else url = null }
    if (url == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sites", style = MaterialTheme.typography.titleMedium)
            Text("Log in on the site as normal; it stays signed in. Tap a magnet link to send it to TorBox.", style = MaterialTheme.typography.bodySmall)
            sites.forEach { (n, u) -> OutlinedButton({ url = u }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(n) } }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ url = null }) { Icon(Icons.Default.Close, "Close site") }
            Text(note ?: "Browsing", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest): Boolean {
                        val u = r.url.toString()
                        if (u.startsWith("magnet:")) { note = "Adding to TorBox..."; vm.addMagnet(u) { note = it }; return true }
                        return !u.startsWith("http")
                    }
                }
                web = this
                loadUrl(url!!)
            }
        })
    }
}
