@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pulse.rsswidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulse.rsswidget.data.AniListSource
import com.pulse.rsswidget.data.Feed
import com.pulse.rsswidget.data.FeedRepository
import com.pulse.rsswidget.data.SettingsStore
import com.pulse.rsswidget.data.WidgetStyle
import com.pulse.rsswidget.data.aniListFeedUrl
import com.pulse.rsswidget.widget.PulseWidget
import com.pulse.rsswidget.widget.PulseWidgetReceiver
import com.pulse.rsswidget.widget.pulseWidgetScope
import com.pulse.rsswidget.work.RefreshScheduler
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedFeed(intent)
        setContent {
            PulseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(onBack = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedFeed(intent)
    }

    /** Add a feed from a shared link (Share → Pulse RSS Widget). */
    private fun handleSharedFeed(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val url = text?.let { normalizeUrl(it) }
        if (url == null) {
            Toast.makeText(this, "That doesn't look like a feed URL", Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        pulseWidgetScope.launch {
            val store = SettingsStore(app)
            val existing = store.getFeeds().any { it.url.equals(url, ignoreCase = true) }
            if (!existing) {
                val title = FeedRepository(app).fetchFeedTitle(url) ?: ""
                store.addFeed(Feed(url = url, autoTitle = title))
                RefreshScheduler.schedule(app, store.getInterval(), store.getWifiOnly())
                RefreshScheduler.refreshNow(app)
                PulseWidget().updateAll(app)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (existing) "Feed already added" else "Feed added", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val repo = remember { FeedRepository(context) }

    val feeds by store.feedsFlow.collectAsState(initial = emptyList())
    val interval by store.intervalFlow.collectAsState(initial = SettingsStore.DEFAULT_INTERVAL)
    val showFavicons by store.showFaviconsFlow.collectAsState(initial = true)
    val widgetStyle by store.widgetStyleFlow.collectAsState(initial = WidgetStyle.NEUTRAL)
    val wifiOnly by store.wifiOnlyFlow.collectAsState(initial = false)
    val failedFeeds by store.failedFeedsFlow.collectAsState(initial = emptySet())
    val lastRefresh by store.lastRefreshFlow.collectAsState(initial = 0L)
    val storedMute by store.muteKeywordsFlow.collectAsState(initial = "")

    var newUrl by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var muteText by remember { mutableStateOf("") }
    var muteWasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RefreshScheduler.schedule(context, store.getInterval(), store.getWifiOnly())
    }
    // Keep the field in sync with the stored value (e.g. after a restore), but never
    // clobber what the user is actively typing.
    LaunchedEffect(storedMute) {
        if (!muteWasFocused) muteText = storedMute
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) pulseWidgetScope.launch {
            val ok = writeBackup(appContext, uri, store)
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, if (ok) "Backup saved" else "Backup failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pulseWidgetScope.launch {
            val ok = restoreBackup(appContext, uri, store)
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, if (ok) "Backup restored" else "Couldn't read that backup", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pulse RSS Widget") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                Text(
                    text = if (lastRefresh > 0)
                        "Last updated ${DateUtils.getRelativeTimeSpanString(lastRefresh, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
                    else "Not refreshed yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            item { SectionLabel("Feeds") }

            if (feeds.isEmpty()) {
                item {
                    Text(
                        "No feeds yet. Add one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            items(feeds, key = { it.url }) { feed ->
                FeedRow(
                    feed = feed,
                    failed = feed.url in failedFeeds,
                    onToggle = { enabled ->
                        pulseWidgetScope.launch {
                            store.setEnabled(feed.url, enabled)
                            PulseWidget().updateAll(appContext)
                            RefreshScheduler.refreshNow(appContext)
                        }
                    },
                    onEdit = {
                        context.startActivity(
                            Intent(context, FeedEditorActivity::class.java).putExtra(FeedEditorActivity.EXTRA_URL, feed.url)
                        )
                    },
                    onDelete = {
                        pulseWidgetScope.launch {
                            store.removeFeed(feed.url)
                            PulseWidget().updateAll(appContext)
                            RefreshScheduler.refreshNow(appContext)
                        }
                    }
                )
            }

            item {
                AddFeedRow(
                    value = newUrl,
                    onValueChange = { newUrl = it; error = null },
                    adding = adding,
                    onAdd = {
                        val url = normalizeUrl(newUrl)
                        if (url == null) {
                            error = "Enter a valid feed URL"
                        } else {
                            scope.launch {
                                adding = true
                                val title = repo.fetchFeedTitle(url) ?: ""
                                store.addFeed(Feed(url = url, autoTitle = title))
                                newUrl = ""
                                adding = false
                                RefreshScheduler.schedule(context, store.getInterval(), store.getWifiOnly())
                                RefreshScheduler.refreshNow(context)
                            }
                        }
                    }
                )
            }
            error?.let { msg ->
                item {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item { SectionLabel("AniList") }
            item {
                AniListSection(source = feeds.firstOrNull { it.isAniList }) { user ->
                    val check = repo.checkAniListUser(user)
                    if (check is AniListSource.UserCheck.Found) {
                        store.addFeed(Feed(url = aniListFeedUrl(check.userName), autoTitle = "AniList · ${check.userName}"))
                        RefreshScheduler.schedule(appContext, store.getInterval(), store.getWifiOnly())
                        RefreshScheduler.refreshNow(appContext)
                    }
                    check
                }
            }

            item { SectionLabel("Options") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show favicons", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = showFavicons,
                        onCheckedChange = {
                            pulseWidgetScope.launch {
                                store.setShowFavicons(it)
                                PulseWidget().updateAll(appContext)
                                if (it) RefreshScheduler.refreshNow(appContext)   // prefetch icons only when turning on
                            }
                        }
                    )
                }
            }
            item {
                WidgetStylePicker(selected = widgetStyle) { style ->
                    pulseWidgetScope.launch {
                        store.setWidgetStyle(style)
                        PulseWidget().updateAll(appContext)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Refresh on Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Background refreshes wait for Wi-Fi. Manual refresh always runs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            pulseWidgetScope.launch { store.setWifiOnly(it); RefreshScheduler.schedule(appContext, store.getInterval(), it) }
                        }
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = muteText,
                    onValueChange = { muteText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onFocusChanged { f ->
                            if (f.isFocused) {
                                muteWasFocused = true
                            } else if (muteWasFocused) {
                                muteWasFocused = false
                                pulseWidgetScope.launch {
                                    store.setMuteKeywords(muteText.trim())
                                    PulseWidget().updateAll(appContext)
                                }
                            }
                        },
                    singleLine = true,
                    label = { Text("Mute keywords") },
                    supportingText = { Text("Hide entries whose title contains any of these (comma-separated), across all feeds.") }
                )
            }
            item {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, HistoryActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text("History") }
            }

            item { SectionLabel("Refresh interval") }
            item {
                IntervalChips(selected = interval) { minutes ->
                    pulseWidgetScope.launch { store.setInterval(minutes); RefreshScheduler.schedule(appContext, minutes, store.getWifiOnly()) }
                }
            }

            item { SectionLabel("Backup") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val name = "pulse-rss-backup-" +
                                SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".json"
                            exportLauncher.launch(name)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Export") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restore") }
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { pinWidget(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add widget to home screen")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun FeedRow(feed: Feed, failed: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(feed.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (feed.isAniList) "Airing shows on AniList (all lists except Dropped)" else feed.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (feed.keywords.isNotBlank()) {
                Text(
                    "Filter: ${feed.keywords}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (failed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Couldn't load", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Switch(checked = feed.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit feed") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete feed") }
    }
}

@Composable
private fun AddFeedRow(value: String, onValueChange: (String) -> Unit, adding: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Add a feed URL") }
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAdd, enabled = !adding && value.isNotBlank()) {
            if (adding) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Add, contentDescription = "Add feed")
        }
    }
}

/**
 * Add the AniList source by username. Once added it lives in the feed list (toggle, rename,
 * filter, delete), so this section then only explains what it shows.
 */
@Composable
private fun AniListSection(source: Feed?, onAdd: suspend (String) -> AniListSource.UserCheck) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (source != null) {
        Text(
            "Showing new episodes of the airing shows on ${source.aniListUser}'s AniList lists (all except Dropped). " +
                "Turn it off, rename or filter it under Feeds; delete it there to switch user.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = user,
            onValueChange = { user = it; error = null },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("AniList username") }
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    when (val result = onAdd(parseAniListUser(user))) {
                        is AniListSource.UserCheck.Found -> {
                            user = ""
                            Toast.makeText(context, "AniList added: ${result.shows} current shows", Toast.LENGTH_SHORT).show()
                        }
                        AniListSource.UserCheck.NotFound -> error = "No AniList user by that name, or their list is private."
                        AniListSource.UserCheck.Unreachable -> error = "Couldn't reach AniList. Try again."
                    }
                    busy = false
                }
            },
            enabled = !busy && user.isNotBlank()
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Add, contentDescription = "Add AniList user")
        }
    }
    Text(
        error ?: ("Adds new episodes of the airing shows on your AniList lists (all except Dropped). " +
            "Tapping one opens the show on AniList. Your list must be public."),
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** A bare username, or one taken from a pasted profile/list link (anilist.co/user/<name>/…). */
private fun parseAniListUser(raw: String): String {
    val trimmed = raw.trim()
    Regex("anilist\\.co/user/([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(trimmed)?.let { return it.groupValues[1] }
    return trimmed.removePrefix("@")
}

@Composable
private fun WidgetStylePicker(selected: WidgetStyle, onSelect: (WidgetStyle) -> Unit) {
    val options = listOf(
        WidgetStyle.NEUTRAL to "Neutral",
        WidgetStyle.WALLPAPER to "Wallpaper",
        WidgetStyle.BLACK to "Black"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Widget colors", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (style, label) ->
                FilterChip(selected = selected == style, onClick = { onSelect(style) }, label = { Text(label) })
            }
        }
        Text(
            when (selected) {
                WidgetStyle.NEUTRAL -> "Plain gray, no wallpaper tint. Follows light/dark mode."
                WidgetStyle.WALLPAPER -> "Material You colors taken from your wallpaper."
                WidgetStyle.BLACK -> "Pure black, for OLED screens."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IntervalChips(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(15, 30, 60, 120, 360)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { minutes ->
            FilterChip(
                selected = selected == minutes,
                onClick = { onSelect(minutes) },
                label = { Text(intervalLabel(minutes)) }
            )
        }
    }
}

private fun intervalLabel(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} hr"

private fun normalizeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || !trimmed.contains('.')) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

private fun pinWidget(context: Context) {
    val manager = context.getSystemService(AppWidgetManager::class.java)
    if (manager != null && manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, PulseWidgetReceiver::class.java), null, null)
    }
}

private suspend fun writeBackup(context: Context, uri: Uri, store: SettingsStore): Boolean =
    runCatching {
        val json = store.exportBackup()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray()); true
            } ?: false
        }
    }.getOrDefault(false)

private suspend fun restoreBackup(context: Context, uri: Uri, store: SettingsStore): Boolean {
    val json = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
    } ?: return false
    val ok = store.importBackup(json)
    if (ok) {
        RefreshScheduler.schedule(context, store.getInterval(), store.getWifiOnly())
        RefreshScheduler.refreshNow(context)
        PulseWidget().updateAll(context)
    }
    return ok
}
