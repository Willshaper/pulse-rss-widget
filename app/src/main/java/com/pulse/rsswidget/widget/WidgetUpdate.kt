package com.pulse.rsswidget.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for fire-and-forget widget updates triggered from the UI.
 * Using a screen's own scope would cancel the update the moment the user navigates
 * away to look at the home screen — which is exactly when the widget needs updating.
 */
internal val pulseWidgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
