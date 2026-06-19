@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.pulse.rsswidget.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulse.rsswidget.R
import com.pulse.rsswidget.data.FaviconStore
import com.pulse.rsswidget.data.FeedItem
import com.pulse.rsswidget.data.SettingsStore
import com.pulse.rsswidget.data.parseKeywords
import com.pulse.rsswidget.data.titleMatchesAny
import com.pulse.rsswidget.work.RefreshScheduler

class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PulseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val faviconStore = remember { FaviconStore(context) }

    val history by store.historyFlow.collectAsState(initial = emptyList())
    val showFavicons by store.showFaviconsFlow.collectAsState(initial = true)
    val refreshing by store.refreshingFlow.collectAsState(initial = false)
    val mute by store.muteKeywordsFlow.collectAsState(initial = "")
    var query by remember { mutableStateOf("") }

    val muteWords = remember(mute) { parseKeywords(mute) }
    val filtered = history.filter { item ->
        !titleMatchesAny(item.title, muteWords) &&
            (query.isBlank() || item.title.contains(query, ignoreCase = true))
    }

    fun refresh() = RefreshScheduler.refreshNow(context, showIndicator = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search history") }
            )

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                if (history.isEmpty()) "No history yet. Pull down or tap refresh."
                                else "No entries match \"$query\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { it.link }) { item ->
                            HistoryRow(
                                item = item,
                                showFavicons = showFavicons,
                                faviconStore = faviconStore,
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(item.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                },
                                onLongClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.link}")
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: FeedItem,
    showFavicons: Boolean,
    faviconStore: FaviconStore,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showFavicons) {
            Favicon(item.domain, faviconStore)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Favicon(domain: String, faviconStore: FaviconStore) {
    var bmp by remember(domain) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(domain) { bmp = faviconStore.fetchAndCache(domain) }
    val current = bmp
    if (current != null) {
        Image(bitmap = current.asImageBitmap(), contentDescription = null, modifier = Modifier.size(22.dp))
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_favicon_fallback),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun subtitle(item: FeedItem): String {
    val time = if (item.timeMillis > 0) {
        DateUtils.getRelativeTimeSpanString(
            item.timeMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
    } else ""
    return listOf(time, item.domain).filter { it.isNotBlank() }.joinToString(" · ")
}
