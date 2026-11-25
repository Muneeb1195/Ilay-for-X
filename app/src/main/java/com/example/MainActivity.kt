package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.IlayAppRoot
import com.example.ui.components.IlayLinkRouter
import com.example.ui.components.PendingDeepLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = MutableStateFlow<PendingDeepLink?>(null)
    private val pendingDeepLinkFlow = pendingDeepLink.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }
        val appContainer = (application as IlayApplication).container
        setContent {
            IlayAppRoot(
                container = appContainer,
                pendingDeepLinkFlow = pendingDeepLinkFlow,
                onConsumeDeepLink = { consumedId ->
                    if (pendingDeepLink.value?.id == consumedId) {
                        pendingDeepLink.value = null
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val url = IlayLinkRouter.extractSupportedDeepLinkUrl(intent) ?: return
        pendingDeepLink.value = PendingDeepLink(url = url)
    }
}
