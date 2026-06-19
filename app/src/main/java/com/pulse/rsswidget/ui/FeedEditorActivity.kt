@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pulse.rsswidget.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.pulse.rsswidget.data.SettingsStore
import com.pulse.rsswidget.widget.PulseWidget
import com.pulse.rsswidget.widget.pulseWidgetScope
import com.pulse.rsswidget.work.RefreshScheduler
import kotlinx.coroutines.launch

class FeedEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        setContent {
            PulseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FeedEditorScreen(url = url, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "feed_url"
    }
}

@Composable
private fun FeedEditorScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { SettingsStore(context) }

    var customTitle by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var autoTitle by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        store.getFeeds().firstOrNull { it.url == url }?.let {
            customTitle = it.customTitle
            keywords = it.keywords
            autoTitle = it.autoTitle.ifBlank { it.url }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit feed") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Text(autoTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = customTitle,
                onValueChange = { customTitle = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Custom title") },
                placeholder = { Text("Leave blank to use the feed's own title") }
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Keyword filter") },
                placeholder = { Text("e.g. android, kotlin") },
                supportingText = { Text("Comma-separated. Only entries whose title contains one of these are kept. Leave blank for all.") }
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val newTitle = customTitle.trim()
                    val newKeywords = keywords.trim()
                    pulseWidgetScope.launch {
                        store.editFeed(url, newTitle, newKeywords)
                        store.getFeeds().firstOrNull { it.url == url }?.let { store.purgeHistoryForFeed(it) }
                        PulseWidget().updateAll(appContext)
                        RefreshScheduler.refreshNow(appContext)
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}
