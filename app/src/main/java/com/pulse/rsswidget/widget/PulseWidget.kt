package com.pulse.rsswidget.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pulse.rsswidget.R
import com.pulse.rsswidget.data.FaviconStore
import com.pulse.rsswidget.data.FeedItem
import com.pulse.rsswidget.data.SettingsStore
import com.pulse.rsswidget.data.parseKeywords
import com.pulse.rsswidget.data.visibleWidgetItems
import com.pulse.rsswidget.ui.SettingsActivity

class PulseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = SettingsStore(context)
        val faviconStore = FaviconStore(context)
        provideContent {
            // Reactive reads: the live session recomposes whenever any of these change,
            // so the refresh overlay, enabled state and keyword filters all reflect live.
            val feeds by store.feedsFlow.collectAsState(initial = emptyList())
            val history by store.historyFlow.collectAsState(initial = emptyList())
            val showFavicons by store.showFaviconsFlow.collectAsState(initial = true)
            val refreshing by store.refreshingFlow.collectAsState(initial = false)
            val mute by store.muteKeywordsFlow.collectAsState(initial = "")

            val muteWords = remember(mute) { parseKeywords(mute) }
            val items = remember(feeds, history, muteWords) {
                visibleWidgetItems(feeds, history, muteWords, SettingsStore.WIDGET_LIMIT)
            }
            val favicons = remember(items, showFavicons) {
                if (!showFavicons) emptyMap()
                else items.map { it.domain }.distinct()
                    .mapNotNull { d -> faviconStore.loadCached(d)?.let { d to it } }
                    .toMap()
            }

            GlanceTheme {
                WidgetBody(items, showFavicons, favicons, refreshing)
            }
        }
    }
}

@Composable
private fun WidgetBody(
    items: List<FeedItem>,
    showFavicons: Boolean,
    favicons: Map<String, Bitmap>,
    refreshing: Boolean
) {
    Box(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(16.dp)) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            TopBar()
            Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(GlanceTheme.colors.outline))
            if (items.isEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No feeds yet — tap the gear to add some",
                        style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(items, itemId = { it.link.hashCode().toLong() }) { item ->
                        HeadlineRow(item, showFavicons, favicons[item.domain])
                    }
                }
            }
        }
        if (refreshing) {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0x66000000))),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GlanceTheme.colors.primary)
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(16.dp).clickable(actionRunCallback<RefreshAction>())
        )
        Spacer(GlanceModifier.defaultWeight())
        Image(
            provider = ImageProvider(R.drawable.ic_settings),
            contentDescription = "Settings",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(16.dp).clickable(actionStartActivity<SettingsActivity>())
        )
    }
}

@Composable
private fun HeadlineRow(item: FeedItem, showFavicons: Boolean, favicon: Bitmap?) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.link))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .clickable(androidx.glance.appwidget.action.actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showFavicons) {
            if (favicon != null) {
                Image(
                    provider = ImageProvider(favicon),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp)
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_favicon_fallback),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size(14.dp)
                )
            }
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(
            text = item.title,
            maxLines = 1,
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurface)
        )
    }
}
