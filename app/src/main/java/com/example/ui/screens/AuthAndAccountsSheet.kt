package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.local.AccountEntity
import com.example.data.remote.XEndpointRegistry
import com.example.ui.components.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<AccountEntity>,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onAddAccountClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "X Accounts",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Sessions stay strictly on-device. Switch accounts anytime with zero middleman servers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (accounts.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Currently browsing in Public Preview mode. Sign in to unlock your Home timeline, posting, replies, and notifications.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                accounts.forEach { acc ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (acc.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (acc.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                onSwitchAccount(acc.userId)
                                onDismiss()
                            }
                            .testTag("account_row_${acc.handle}")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(acc.avatarUrl, acc.displayName, size = 42.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = acc.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "@${acc.handle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (acc.isActive) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Active account",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(onClick = { onRemoveAccount(acc.userId) }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "Remove account",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    onDismiss()
                    onAddAccountClick()
                },
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("add_account_button"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add X Account", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Full-screen X sign-in overlay (rendered directly in the root activity window instead of a Compose
 * Dialog so Android WebView receives exact MATCH_PARENT dimensions and proper IME/keyboard resizing).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XAuthDialog(
    onCookiesCaptured: (authToken: String, ct0: String, twidUserId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = WebView, 1 = Manual Cookies
    var authTokenInput by remember { mutableStateOf("") }
    var ct0Input by remember { mutableStateOf("") }
    var twidInput by remember { mutableStateOf<String?>(null) }
    var webStatus by remember { mutableStateOf("Sign in to X below — Ilay detects your session automatically.") }
    var capturedOnce by remember { mutableStateOf(false) }
    var isWebLoading by remember { mutableStateOf(true) }

    fun extractTwidUserId(rawTwid: String?): String? {
        if (rawTwid.isNullOrBlank()) return null
        return Regex("u(?:%3D|=)(\\d+)", RegexOption.IGNORE_CASE)
            .find(rawTwid)
            ?.groupValues
            ?.getOrNull(1)
    }

    fun tryExtractCookiesFromManager(): Boolean {
        if (capturedOnce) return true
        val cm = CookieManager.getInstance()
        val combined = listOf(
            cm.getCookie("https://x.com").orEmpty(),
            cm.getCookie("https://twitter.com").orEmpty(),
            cm.getCookie("https://api.x.com").orEmpty(),
        ).joinToString("; ")
        val cookieMap = combined.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .toMap()
        val auth = cookieMap["auth_token"].orEmpty()
        val ct0 = cookieMap["ct0"].orEmpty()
        val twidUserId = extractTwidUserId(cookieMap["twid"])
        return if (auth.length >= 16 && ct0.length >= 16) {
            capturedOnce = true
            onCookiesCaptured(auth, ct0, twidUserId)
            true
        } else {
            false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_auth_dialog")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close sign in")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Sign in to X",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (selectedTab == 0) {
                    FilledTonalButton(
                        onClick = {
                            if (!tryExtractCookiesFromManager()) {
                                webStatus = "Session cookies not ready yet — finish signing in first."
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.testTag("check_session_button"),
                    ) {
                        Text("Verify Session")
                    }
                }
            }

            // Tab Switcher
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) {
                    Text("X WebView Login")
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.Cookie, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) {
                    Text("Import Cookies")
                }
            }

            if (selectedTab == 0) {
                if (isWebLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = webStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    var webViewRef by remember { mutableStateOf<WebView?>(null) }
                    DisposableEffect(Unit) {
                        onDispose {
                            webViewRef?.apply {
                                stopLoading()
                                webViewClient = WebViewClient()
                                destroy()
                            }
                            webViewRef = null
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                isFocusable = true
                                isFocusableInTouchMode = true
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(false)
                                settings.userAgentString = XEndpointRegistry.WEB_USER_AGENT
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        tryExtractCookiesFromManager()
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isWebLoading = false
                                        if (tryExtractCookiesFromManager()) {
                                            webStatus = "Session detected! Syncing your X profile…"
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            isWebLoading = false
                                            webStatus = "X login page could not load (${error?.description ?: "network error"}). You can switch to Import Cookies."
                                        }
                                    }

                                    override fun onRenderProcessGone(
                                        view: WebView?,
                                        detail: android.webkit.RenderProcessGoneDetail?
                                    ): Boolean {
                                        isWebLoading = false
                                        webStatus = "WebView renderer restarted. Tap Import Cookies or reopen sign-in."
                                        return true
                                    }
                                }
                                webViewRef = this
                                cm.removeAllCookies {
                                    cm.flush()
                                    post { loadUrl("https://x.com/i/flow/login") }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Outlined.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Direct Zero-Cost Connection: Ilay communicates exclusively with X's internal GraphQL API using your local browser session cookies (`auth_token` and `ct0`). You can paste individual values or a full Cookie header string.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = authTokenInput,
                        onValueChange = { raw ->
                            if (raw.contains("auth_token=") && raw.contains("ct0=")) {
                                val parsedAuth = Regex("auth_token=([^;\\s]+)").find(raw)?.groupValues?.getOrNull(1)
                                val parsedCt0 = Regex("ct0=([^;\\s]+)").find(raw)?.groupValues?.getOrNull(1)
                                val parsedTwid = Regex("twid=([^;\\s]+)").find(raw)?.groupValues?.getOrNull(1)
                                if (!parsedAuth.isNullOrBlank()) authTokenInput = parsedAuth
                                if (!parsedCt0.isNullOrBlank()) ct0Input = parsedCt0
                                twidInput = extractTwidUserId(parsedTwid)
                            } else {
                                authTokenInput = raw
                            }
                        },
                        label = { Text("auth_token cookie") },
                        placeholder = { Text("Paste auth_token or full Cookie header") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_token_input"),
                    )

                    OutlinedTextField(
                        value = ct0Input,
                        onValueChange = { ct0Input = it },
                        label = { Text("ct0 (CSRF token) cookie") },
                        placeholder = { Text("Paste ct0 cookie value") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ct0_input"),
                    )

                    Button(
                        onClick = { onCookiesCaptured(authTokenInput, ct0Input, twidInput) },
                        enabled = authTokenInput.isNotBlank() && ct0Input.isNotBlank(),
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("submit_cookies_button"),
                    ) {
                        Text("Connect X Session", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
