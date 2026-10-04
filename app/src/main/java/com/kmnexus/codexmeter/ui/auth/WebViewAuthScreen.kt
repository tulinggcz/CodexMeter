package com.kmnexus.codexmeter.ui.auth

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.providers.common.auth.LoopbackCallbackServer
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auth UI for non-Codex providers that require a browser-based login.
 *
 * - [Cookie] hosts an embedded WebView and auto-captures the target session cookie once login
 *   completes (Kimi, Cursor).
 * - [OAuthIntercept] hosts an embedded WebView and intercepts the OAuth redirect to extract `code`
 *   without ever surfacing the callback URL (Claude).
 * - [OAuthLoopback] opens an external browser (Google blocks OAuth inside WebViews) and captures the
 *   `code` via a single-use [LoopbackCallbackServer] (Antigravity).
 *
 * The authorization `code` / cookie is handed to [onCredentialExtracted]; the callback URL and raw
 * credential are never displayed.
 */
sealed interface WebViewAuthConfig {
    val providerId: ProviderId

    data class LoginRegion(
        val label: String,
        val loginUrl: String,
        val cookieDomain: String,
    )

    data class Cookie(
        override val providerId: ProviderId,
        val loginUrl: String,
        val cookieDomain: String,
        val targetCookieNames: List<String>,
        val regions: List<LoginRegion> = emptyList(),
        /**
         * When false, the target cookie is captured only when the user taps "Done" — for sites that
         * set a guest/anonymous value of the cookie before login (e.g. Kimi), so auto-capture won't
         * submit a pre-login token and fail with 401.
         */
        val autoCapture: Boolean = true,
        /** Extra cookie-store URLs probed in addition to the primary domain when reading the
         *  target cookie (e.g. apex + www variants of the same site). */
        val additionalCookieUrls: List<String> = emptyList(),
        /** Capture automatically once the target cookie's value changes from the value first
         *  observed on load. Kimi sets a guest value before login, so a plain presence check
         *  would submit the pre-login token and fail with 401. */
        val captureOnCookieChange: Boolean = false,
        /** Install the document-start anti-detection shim (UA-CH brands + plugins). Needed for
         *  sites whose front-end or anti-bot SDK refuses to render inside an embedded WebView
         *  (Kimi + TrustDecision). Off for providers that already work (Cursor). */
        val antiDetect: Boolean = false,
        /** Optional JS run on every page load — e.g. to open a provider's login modal automatically. */
        val injectOnLoadJs: String? = null,
        /** Optional one-time tip dialog shown when the screen opens (string resource id). */
        val tipResId: Int? = null,
    ) : WebViewAuthConfig

    data class OAuthIntercept(
        override val providerId: ProviderId,
        val authorizationUrl: String,
        val redirectUriPrefix: String,
        val expectedState: String,
        // Anthropic validates `state` in the token exchange, so pass the captured callback as
        // `code#state` (Claude). Google (Antigravity) needs only the bare code.
        val appendStateToCode: Boolean = false,
        /** Optional one-time tip dialog shown when the screen opens (string resource id). */
        val tipResId: Int? = null,
    ) : WebViewAuthConfig

    data class OAuthLoopback(
        override val providerId: ProviderId,
        val expectedState: String,
        val authorizationUrlForRedirect: (redirectUri: String) -> String,
    ) : WebViewAuthConfig
}

@Composable
fun WebViewAuthScreen(
    config: WebViewAuthConfig,
    onCredentialExtracted: suspend (credential: String, redirectUri: String?) -> Result<Unit>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = onBack,
) {
    val providerConfig = remember(config.providerId) { ProviderRegistry.configFor(config.providerId) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val verificationFailed = stringResource(R.string.auth_verification_failed)
    val scope = rememberCoroutineScope()

    // Single-shot guard so cookie/redirect callbacks fire the import exactly once.
    var consumed by remember { mutableStateOf(false) }

    fun submit(credential: String, redirectUri: String?) {
        if (consumed || credential.isBlank()) return
        consumed = true
        isVerifying = true
        errorMessage = null
        scope.launch {
            val result = runCatching { onCredentialExtracted(credential, redirectUri) }
                .getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                onSaved()
            } else {
                consumed = false
                isVerifying = false
                errorMessage = result.exceptionOrNull()?.message ?: verificationFailed
            }
        }
    }

    val tipResId = when (config) {
        is WebViewAuthConfig.Cookie -> config.tipResId
        is WebViewAuthConfig.OAuthIntercept -> config.tipResId
        is WebViewAuthConfig.OAuthLoopback -> null
    }
    var showTip by remember(config) { mutableStateOf(tipResId != null) }
    // WebView control actions live in the top bar as compact icons, set once the WebView exists.
    // reload: re-open the OAuth page without clearing cookies (Claude). clear: wipe the session and
    // restart. confirm: capture the cookie on demand (Kimi/Cursor).
    var webViewReload by remember(config) { mutableStateOf<(() -> Unit)?>(null) }
    var clearSession by remember(config) { mutableStateOf<(() -> Unit)?>(null) }
    var confirmLogin by remember(config) { mutableStateOf<(() -> Unit)?>(null) }
    var shareLog by remember(config) { mutableStateOf<(() -> Unit)?>(null) }

    AuthScaffold(
        title = providerConfig.displayName,
        onBack = onBack,
        modifier = modifier,
        actions = {
            webViewReload?.let { reload ->
                AuthActionIcon(R.drawable.ic_action_refresh, R.string.auth_reload, reload)
            }
            clearSession?.let { clear ->
                AuthActionIcon(R.drawable.ic_action_clear, R.string.auth_clear_cookies, clear)
            }
            confirmLogin?.let { confirm ->
                AuthActionIcon(R.drawable.ic_action_done, R.string.auth_confirm_login, confirm)
            }
            shareLog?.let { share ->
                AuthActionIcon(R.drawable.ic_action_share, R.string.auth_share_log, share)
            }
        },
    ) {
        when (config) {
            is WebViewAuthConfig.Cookie -> CookieAuthBody(
                modifier = Modifier.weight(1f),
                config = config,
                isVerifying = isVerifying,
                errorMessage = errorMessage,
                onCredential = ::submit,
                onError = { errorMessage = it },
                onClearAction = { clearSession = it },
                onConfirmAction = { confirmLogin = it },
                onShareAction = { shareLog = it },
            )
            is WebViewAuthConfig.OAuthIntercept -> OAuthInterceptBody(
                modifier = Modifier.weight(1f),
                config = config,
                isVerifying = isVerifying,
                errorMessage = errorMessage,
                onCredential = ::submit,
                onWebViewReady = { webView ->
                    webViewReload = { webView.loadUrl(config.authorizationUrl) }
                    clearSession = {
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        webView.clearHistory()
                        webView.clearCache(true)
                        webView.loadUrl(config.authorizationUrl)
                    }
                },
            )
            is WebViewAuthConfig.OAuthLoopback -> OAuthLoopbackBody(
                modifier = Modifier.weight(1f),
                config = config,
                isVerifying = isVerifying,
                errorMessage = errorMessage,
                onCredential = ::submit,
                onError = { errorMessage = it },
            )
        }
    }

    if (showTip && tipResId != null) {
        // The tip references the top-bar action, which is now an icon; render that icon inline where
        // the string's "%1$s" placeholder sits so the text matches what the user actually taps.
        val tipIconResId = when (config) {
            is WebViewAuthConfig.Cookie -> R.drawable.ic_action_done
            is WebViewAuthConfig.OAuthIntercept -> R.drawable.ic_action_refresh
            is WebViewAuthConfig.OAuthLoopback -> null
        }
        AlertDialog(
            onDismissRequest = { showTip = false },
            confirmButton = {
                TextButton(onClick = { showTip = false }) {
                    Text(stringResource(R.string.auth_tip_got_it))
                }
            },
            title = { Text(stringResource(R.string.auth_tip_title)) },
            text = { AuthTipText(stringResource(tipResId), tipIconResId) },
        )
    }
}

/** Tip body that swaps the string's "%1$s" placeholder for the inline top-bar action icon. */
@Composable
private fun AuthTipText(raw: String, iconResId: Int?) {
    if (iconResId == null) {
        Text(raw)
        return
    }
    val parts = remember(raw) { raw.split("%1\$s", limit = 2) }
    val inlineContent = mapOf(
        "icon" to InlineTextContent(
            Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter),
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = CodexMeterTheme.colors.accent,
                modifier = Modifier.size(18.dp),
            )
        },
    )
    val annotated = buildAnnotatedString {
        append(parts[0])
        appendInlineContent("icon", "[icon]")
        if (parts.size > 1) append(parts[1])
    }
    Text(text = annotated, inlineContent = inlineContent)
}

/** Compact icon action for the auth top bar (reload / clear session / confirm login). */
@Composable
private fun AuthActionIcon(iconResId: Int, contentDescriptionResId: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = stringResource(contentDescriptionResId),
            tint = CodexMeterTheme.colors.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CookieAuthBody(
    config: WebViewAuthConfig.Cookie,
    isVerifying: Boolean,
    errorMessage: String?,
    onCredential: (String, String?) -> Unit,
    onError: (String) -> Unit,
    onClearAction: (() -> Unit) -> Unit,
    onConfirmAction: (() -> Unit) -> Unit,
    onShareAction: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedRegion by remember {
        mutableStateOf(config.regions.firstOrNull())
    }
    val loginUrl = selectedRegion?.loginUrl ?: config.loginUrl
    val cookieDomain = selectedRegion?.cookieDomain ?: config.cookieDomain
    val cookieUrls = (listOf("https://$cookieDomain") + config.additionalCookieUrls).distinct()
    val noSession = stringResource(R.string.auth_cookie_no_session)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Publish the top-bar actions; re-published when the active region (and thus login URL / cookie
    // domain) changes. `webViewRef` is read lazily inside the clear lambda so it picks up the WebView
    // once the factory has created it.
    LaunchedEffect(loginUrl, cookieDomain) {
        onClearAction {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            webViewRef?.apply {
                clearHistory()
                clearCache(true)
                loadUrl(loginUrl)
            }
        }
        onConfirmAction {
            CookieManager.getInstance().flush()
            val value = cookieUrls.firstNotNullOfOrNull { readTargetCookie(it, config.targetCookieNames) }
            if (value != null) {
                AuthLogStore.log('I', "manual confirm: target cookie found")
                onCredential(value, null)
            } else {
                AuthLogStore.log('E', "manual confirm: no target cookie in $cookieUrls")
                onError(noSession)
            }
        }
        onShareAction {
            val header = "CodexMeter auth diagnostics\n" +
                "webviewPackage=${WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"}\n" +
                "provider=${config.providerId}\n" +
                "cookieUrls=$cookieUrls\n---\n"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, header + AuthLogStore.dump())
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.auth_share_log)))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (config.regions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.auth_select_region),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xs),
            )
            config.regions.forEach { region ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedRegion == region,
                            onClick = { selectedRegion = region },
                        )
                        .padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedRegion == region, onClick = { selectedRegion = region })
                    Text(region.label, modifier = Modifier.padding(start = CodexMeterSpacing.sm))
                }
            }
        }

        Text(
            text = stringResource(R.string.auth_cookie_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.sm),
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            key(loginUrl) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureAuthWebView(this, useSoftwareLayer = true)
                            if (config.antiDetect) installAntiDetectDocumentStart(this)
                            webViewClient = CookieCaptureClient(
                                cookieUrls = cookieUrls,
                                targetCookieNames = config.targetCookieNames,
                                autoCapture = config.autoCapture,
                                captureOnCookieChange = config.captureOnCookieChange,
                                onLoadJs = config.injectOnLoadJs,
                                onStartJs = if (config.antiDetect) ANTI_DETECT_JS else null,
                                onCookie = { onCredential(it, null) },
                            )
                            webViewRef = this
                            loadUrl(loginUrl)
                        }
                    },
                )
            }
            if (isVerifying) VerifyingOverlay()
        }

        errorMessage?.let { ErrorLine(it) }
    }
}

@Composable
private fun OAuthInterceptBody(
    config: WebViewAuthConfig.OAuthIntercept,
    isVerifying: Boolean,
    errorMessage: String?,
    onCredential: (String, String?) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Hardware layer (the default) here: forcing a software layer makes Google's
                        // tall sign-in page (Antigravity) render vertically compressed with dead space
                        // below the fold. The cookie pages still use the software workaround.
                        configureAuthWebView(this, useSoftwareLayer = false)
                        webViewClient = RedirectInterceptClient(
                            redirectUriPrefix = config.redirectUriPrefix,
                            expectedState = config.expectedState,
                            appendStateToCode = config.appendStateToCode,
                            onCode = { onCredential(it, config.redirectUriPrefix) },
                        )
                        onWebViewReady(this)
                        loadUrl(config.authorizationUrl)
                    }
                },
            )
            if (isVerifying) VerifyingOverlay()
            errorMessage?.let {
                Column(modifier = Modifier.align(Alignment.BottomStart)) { ErrorLine(it) }
            }
        }
    }
}

@Composable
private fun OAuthLoopbackBody(
    config: WebViewAuthConfig.OAuthLoopback,
    isVerifying: Boolean,
    errorMessage: String?,
    onCredential: (String, String?) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val server = remember(config.expectedState) { LoopbackCallbackServer(config.expectedState) }
    val verificationFailed = stringResource(R.string.auth_verification_failed)

    fun openBrowser() {
        val url = config.authorizationUrlForRedirect(server.redirectUri)
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    DisposableEffect(server) {
        server.start()
        onDispose { server.close() }
    }

    LaunchedEffect(server) {
        openBrowser()
        val result = withContext(Dispatchers.IO) { server.awaitCode() }
        result.onSuccess { onCredential(it, server.redirectUri) }
            .onFailure { onError(it.message ?: verificationFailed) }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(CodexMeterSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isVerifying) {
            VerifyingOverlay()
        } else {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Text(
                text = stringResource(R.string.auth_oauth_waiting),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = CodexMeterSpacing.md),
            )
            Text(
                text = stringResource(R.string.auth_oauth_browser_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = CodexMeterSpacing.sm),
            )
            TextButton(
                onClick = { openBrowser() },
                modifier = Modifier.padding(top = CodexMeterSpacing.lg),
            ) { Text(stringResource(R.string.auth_reopen_browser)) }
        }
        errorMessage?.let { ErrorLine(it) }
    }
}

@Composable
private fun VerifyingOverlay() {
    Column(
        modifier = Modifier.fillMaxSize().padding(CodexMeterSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.auth_verifying),
            modifier = Modifier.padding(top = CodexMeterSpacing.md),
        )
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(CodexMeterSpacing.md),
    )
}

/**
 * Ring buffer of auth-WebView diagnostics, mirrored to logcat (tag WebViewAuth). The top-bar
 * share action sends the buffer as plain text so blank-page failures can be reported from the
 * phone without adb. Raw cookie values are never recorded — names and lengths only.
 */
internal object AuthLogStore {
    private const val TAG = "WebViewAuth"
    private const val MAX_LINES = 400
    private val buf = ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    @Synchronized
    fun log(level: Char, msg: String) {
        android.util.Log.println(priority(level), TAG, msg)
        buf.addLast(timeFmt.format(java.util.Date()) + " " + msg)
        while (buf.size > MAX_LINES) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    private fun priority(level: Char): Int = when (level) {
        'E' -> android.util.Log.ERROR
        'W' -> android.util.Log.WARN
        else -> android.util.Log.INFO
    }
}

/**
 * Stealth shim injected at document start (androidx.webkit addDocumentStartJavaScript) so it runs
 * before the page's own scripts — including anti-bot SDKs like Kimi's TrustDecision. The UA string
 * is already rewritten to look like Chrome, but two modern WebView tells survive that rewrite:
 * navigator.userAgentData.brands lists "Android WebView" (UA-CH) and navigator.plugins is empty.
 * Both are standard embedded-browser detection vectors; this shim cleans the brands list (the
 * synchronous property and getHighEntropyValues) and fakes Chrome's PDF plugin/mimeType entries.
 */
private val ANTI_DETECT_JS = """
    (function(){
      function clean(bs){
        var out=[],i;
        for(i=0;i<bs.length;i++){if(!/webview/i.test(bs[i].brand)){out.push(bs[i]);}}
        var hasC=false;
        for(i=0;i<out.length;i++){if(/chrome/i.test(out[i].brand)){hasC=true;break;}}
        if(!hasC){
          var m=/Chrome\/(\d+)/.exec(navigator.userAgent),v=m?m[1]:'120';
          out.push({brand:'Chromium',version:v});
          out.push({brand:'Google Chrome',version:v});
        }
        return out;
      }
      try{
        var uad=navigator.userAgentData;
        if(uad){
          var ob=uad.brands;
          Object.defineProperty(uad,'brands',{configurable:true,enumerable:true,get:function(){return clean(ob);}});
          var oh=uad.getHighEntropyValues;
          if(typeof oh==='function'){
            var bound=oh.bind(uad);
            uad.getHighEntropyValues=function(h){return bound(h).then(function(r){try{r.brands=clean(r.brands);}catch(e){}return r;});};
          }
        }
      }catch(e){}
      try{
        var pl=[{name:'Chrome PDF Viewer',filename:'internal-pdf-viewer',description:'Portable Document Format',length:1}];
        pl[0][0]={type:'application/pdf',suffixes:'pdf',description:'Portable Document Format'};
        pl.refresh=function(){};
        Object.defineProperty(navigator,'plugins',{configurable:true,get:function(){return pl;}});
        var mt=[{type:'application/pdf',suffixes:'pdf',description:'Portable Document Format',enabledPlugin:pl[0]}];
        Object.defineProperty(navigator,'mimeTypes',{configurable:true,get:function(){return mt;}});
      }catch(e){}
    })();
""".trimIndent()

/** Registers [ANTI_DETECT_JS] to run before any page script on kimi.com documents. */
@OptIn(androidx.webkit.ExperimentalWebViewApi::class)
private fun installAntiDetectDocumentStart(webView: WebView) {
    runCatching {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                webView,
                ANTI_DETECT_JS,
                setOf("https://kimi.com", "https://*.kimi.com"),
            )
            AuthLogStore.log('I', "documentStart anti-detect shim installed")
        } else {
            AuthLogStore.log('W', "DOCUMENT_START_SCRIPT unsupported; relying on onPageStarted re-injection")
        }
    }.onFailure { AuthLogStore.log('E', "documentStart install failed: ${it.message}") }
}

/**
 * Anti-detection configuration shared by the embedded auth WebViews (Cursor/Kimi cookie capture,
 * Claude OAuth intercept). Android's default WebView user-agent carries a `; wv` token and a
 * `Version/x.y` marker that Google's sign-in ("disallowed_useragent") and Cloudflare's managed
 * challenge use to reject embedded browsers. Presenting as plain Chrome — while keeping the device's
 * real Chrome version — plus enabling storage/cookies lets those checks pass. This is standard
 * compatibility configuration, not credential or detection tampering.
 */
private fun configureAuthWebView(webView: WebView, useSoftwareLayer: Boolean) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
    // Some login pages (e.g. Google sign-in) need a chrome client to render / drive JS dialogs.
    // Also mirrors every page console message into logcat so blank-page failures (Kimi) can be
    // diagnosed from a bug report without a laptop attached.
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
            message ?: return false
            AuthLogStore.log(
                'W',
                "console[${message.messageLevel()}] ${message.message()} @ ${message.sourceId()}:${message.lineNumber()}"
            )
            return true
        }
    }
    // Cookie-capture pages (Kimi/Cursor) can paint blank on a hardware layer in a Compose
    // AndroidView, so they render on a software layer. The OAuth pages keep the hardware layer:
    // forcing software there compresses Google's tall sign-in page vertically.
    if (useSoftwareLayer) {
        webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    }
    // Claude's OAuth consent disables "Authorize" while document.hasFocus() is false; an embedded
    // WebView in a Compose AndroidView doesn't always grab window focus, leaving the button dead.
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Present as Chrome (drop the WebView "wv"/"Version" tokens) so Google sign-in and Cloudflare
        // accept the client. (Confirmed not the cause of Kimi's blank render.)
        userAgentString = userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }
}

private class CookieCaptureClient(
    private val cookieUrls: List<String>,
    private val targetCookieNames: List<String>,
    private val autoCapture: Boolean,
    private val captureOnCookieChange: Boolean,
    private val onLoadJs: String?,
    private val onStartJs: String?,
    private val onCookie: (String) -> Unit,
) : WebViewClient() {
    /** Target-cookie value per URL as first seen on load; a change from this is a real login. */
    private var baseline: Map<String, String?>? = null
    private var pollStopped = false
    private var ticks = 0

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        // Belt-and-braces re-injection when the document-start API (androidx.webkit) is not
        // available; the shim is idempotent so double execution is harmless.
        onStartJs?.let { view?.evaluateJavascript(it, null) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onLoadJs?.let { view?.evaluateJavascript(it, null) }
        probePageMetrics(view, url, "finished")
        logCookieFingerprint("finished")
        if (captureOnCookieChange && baseline == null) {
            baseline = cookieSnapshot()
        }
        tryExtract()
        if (captureOnCookieChange && !pollStopped) schedulePoll(view)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        // Main-frame failures blank the whole page; subframe/resource failures usually don't.
        if (request?.isForMainFrame == true) {
            AuthLogStore.log('E', "mainFrameError url=${request.url} err=${error?.description}")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: android.webkit.WebResourceResponse?,
    ) {
        if (request?.isForMainFrame == true) {
            AuthLogStore.log(
                'E',
                "mainFrameHttpError url=${request.url} status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase}"
            )
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        // The renderer crashed; the WebView is dead and would stay white forever. Log it and
        // restart the load so the user sees something actionable instead of a blank surface.
        AuthLogStore.log('E', "renderProcessGone crashed=${detail?.didCrash()} — reloading")
        view?.reload()
        // false = this app keeps the (now restarted) WebView instance alive.
        return false
    }

    /**
     * Samples document height + readyState right after load and again once the SPA has had time
     * to hydrate. A normal height with a still-white surface points at the render layer; a 0
     * height points at the page collapsing (kimi /code).
     */
    private fun probePageMetrics(view: WebView?, url: String?, phase: String) {
        view ?: return
        val probe = "(function(){try{var b=navigator.userAgentData?navigator.userAgentData.brands.map(function(x){return x.brand}).join('/'):'nouad';return (document.body?document.body.scrollHeight:'nobody')+'|'+document.readyState+'|plugins='+navigator.plugins.length+'|brands='+b;}catch(e){return 'err:'+e;}})()"
        view.evaluateJavascript(probe) { result ->
            AuthLogStore.log('W', "probe[$phase] url=$url metrics=$result")
        }
        view.postDelayed({
            view.evaluateJavascript(probe) { result ->
                AuthLogStore.log('W', "probe[$phase+4s] url=$url metrics=$result")
            }
        }, 4000)
    }

    /**
     * Kimi's post-login page renders blank in the WebView, so waiting for a manual "Done" tap
     * would strand the user on a white screen. Poll the cookie store instead and capture the
     * moment the target cookie changes from its load-time value (guest -> real session).
     */
    private fun schedulePoll(view: WebView?) {
        view ?: return
        view.postDelayed({
            if (pollStopped) return@postDelayed
            val snapshot = cookieSnapshot()
            if (ticks % 4 == 0) logCookieFingerprint("poll$ticks")
            val changed = snapshot.entries
                .filter { it.value != null && baseline?.get(it.key) != it.value }
                .map { it.value!! }
            if (changed.isNotEmpty()) {
                pollStopped = true
                CookieManager.getInstance().flush()
                AuthLogStore.log('I', "target cookie changed since load -> auto-capture")
                onCookie(changed.first())
                return@postDelayed
            }
            ticks++
            if (ticks < 120) schedulePoll(view)
        }, 1500)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        tryExtract()
    }

    private fun tryExtract() {
        // autoCapture == false means manual-only capture (the user taps "Done").
        if (!autoCapture) return
        val value = cookieUrls.firstNotNullOfOrNull { readTargetCookie(it, targetCookieNames) } ?: return
        CookieManager.getInstance().flush()
        onCookie(value)
    }

    private fun cookieSnapshot(): Map<String, String?> =
        cookieUrls.associateWith { readTargetCookie(it, targetCookieNames) }

    /** Logs cookie names + value lengths only; raw values never enter the log. */
    private fun logCookieFingerprint(phase: String) {
        val sb = StringBuilder()
        for (url in cookieUrls) {
            val raw = CookieManager.getInstance().getCookie(url).orEmpty()
            val fp = raw.split(";").mapNotNull { part ->
                val pair = part.trim().split("=", limit = 2)
                val name = pair.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) null else "$name(${pair.getOrNull(1)?.trim()?.length ?: 0})"
            }.joinToString(",")
            sb.append(" cookies[$url]=[$fp]")
        }
        AuthLogStore.log('I', "fingerprint[$phase]$sb")
    }
}

/** Reads the first non-empty target cookie value for [cookieUrl] from the shared cookie store. */
private fun readTargetCookie(cookieUrl: String, targetCookieNames: List<String>): String? {
    val raw = CookieManager.getInstance().getCookie(cookieUrl) ?: return null
    val cookies = raw.split(";").mapNotNull { part ->
        val pair = part.trim().split("=", limit = 2)
        val name = pair.getOrNull(0)?.trim().orEmpty()
        if (name.isEmpty()) null else name to pair.getOrNull(1)?.trim().orEmpty()
    }.toMap()
    return targetCookieNames.firstNotNullOfOrNull { cookies[it]?.takeIf { v -> v.isNotEmpty() } }
}

private class RedirectInterceptClient(
    private val redirectUriPrefix: String,
    private val expectedState: String,
    private val appendStateToCode: Boolean,
    private val onCode: (String) -> Unit,
) : WebViewClient() {
    private var handled = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (!url.startsWith(redirectUriPrefix)) return false
        maybeCapture(view, url)
        return true
    }

    // Server-side 302 redirects to the (loopback / callback) URI aren't always routed through
    // shouldOverrideUrlLoading, so also catch the navigation here and stop the doomed load.
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        if (url != null && url.startsWith(redirectUriPrefix)) {
            view?.stopLoading()
            maybeCapture(view, url)
        }
    }

    // Give the loaded page window focus so consent screens that gate their primary button on
    // document.hasFocus() (Claude's "Authorize") enable without a manual tap.
    override fun onPageFinished(view: WebView?, url: String?) {
        view?.requestFocus()
    }

    private fun maybeCapture(view: WebView?, url: String) {
        if (handled) return
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code != null && state == expectedState) {
            handled = true
            view?.stopLoading()
            onCode(if (appendStateToCode) "$code#$state" else code)
        }
    }
}
