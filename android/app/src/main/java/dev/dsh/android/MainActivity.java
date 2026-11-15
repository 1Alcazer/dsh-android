package dev.dsh.android;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single native screen: a branded loading surface that hands off to the
 * official DSH web interface, plus the small set of controls a mobile shell
 * must own (menu, downloads, file uploads, microphone, lifecycle).
 */
public final class MainActivity extends Activity implements Engine.Listener {
    private static final int REQ_FILE = 1001;
    private static final int REQ_MIC = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;
    private static final int REQ_DOWNLOAD = 1004;

    private Engine engine;
    private FrameLayout root;
    private LinearLayout topBar;
    private View statusDot;
    private TextView topStatus;
    private ProgressBar pageProgress;
    private FrameLayout webContainer;
    private WebView webView;
    private FrameLayout loading;
    private TextView statusText;
    private TextView percentText;
    private TextView hintText;
    private TextView errorText;
    private TextView retryButton;
    private TextView safeButton;
    private ProgressBar progressBar;
    private ImageView logo;

    private String url;
    private String pendingUrl;
    private int authRetries;
    private boolean authRestarted;
    private boolean historyCleared;
    private boolean pageLoaded;
    private boolean loadFailed;
    private boolean destroyed;

    private FrameLayout popupLayer;
    private FrameLayout popupContainer;
    private WebView popupWebView;
    private TextView popupTitle;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest micRequest;
    private ObjectAnimator pulse;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = Engine.get(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));

        LinearLayout main = Ui.column(this);
        main.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = buildTopBar();
        topBar.setVisibility(View.GONE);
        main.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webContainer = new FrameLayout(this);
        main.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loading = buildLoading();
        root.addView(main);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildPopupLayer(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        Ui.insets(this, root);
        BackCompat.enable(this, this::handleBack);

        ensureWebView();
        engine.addListener(this);
        startEngineService();
        maybeAskNotifications();
    }

    // ------------------------------------------------------------------- ui

    private LinearLayout buildTopBar() {
        LinearLayout outer = Ui.column(this);
        outer.setBackgroundColor(Color.argb(242, 11, 14, 20));
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        bar.setMinimumHeight(Ui.dp(this, 44));

        statusDot = new View(this);
        statusDot.setBackground(Ui.circle(Ui.color(this, R.color.ok)));
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8));
        dotParams.rightMargin = Ui.dp(this, 9);
        bar.addView(statusDot, dotParams);

        TextView name = Ui.text(this, "DSH", 15, Ui.color(this, R.color.text));
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(name);

        topStatus = Ui.text(this, "engine", 12, Ui.color(this, R.color.text_dim));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = Ui.dp(this, 8);
        bar.addView(topStatus, statusParams);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView menu = Ui.text(this, "⋮", 22, Ui.color(this, R.color.text));
        menu.setGravity(Gravity.CENTER);
        menu.setBackground(Ui.rounded(this, Color.argb(34, 255, 255, 255), 18));
        menu.setClickable(true);
        menu.setOnClickListener(v -> openMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        outer.addView(bar);
        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setIndeterminate(true);
        pageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                Ui.color(this, R.color.accent)));
        pageProgress.setVisibility(View.GONE);
        outer.addView(pageProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));
        return outer;
    }

    private FrameLayout buildLoading() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundResource(R.drawable.bg_splash);

        LinearLayout center = Ui.column(this);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(Ui.dp(this, 30), Ui.dp(this, 20), Ui.dp(this, 30), Ui.dp(this, 20));

        logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        center.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)));

        TextView title = Ui.text(this, "DeepSeek Harness", 26, Ui.color(this, R.color.text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = Ui.dp(this, 18);
        center.addView(title, titleParams);

        TextView subtitle = Ui.text(this, "on-device · private · yours", 13,
                Ui.color(this, R.color.text_dim));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = Ui.dp(this, 6);
        center.addView(subtitle, subtitleParams);

        statusText = Ui.text(this, getString(R.string.engine_preparing), 14,
                Ui.color(this, R.color.text));
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Ui.dp(this, 34);
        center.addView(statusText, statusParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                Ui.color(this, R.color.accent)));
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Ui.color(this, R.color.divider)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 230), Ui.dp(this, 4));
        barParams.topMargin = Ui.dp(this, 14);
        center.addView(progressBar, barParams);

        percentText = Ui.text(this, "", 11, Ui.color(this, R.color.text_dim));
        percentText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        percentParams.topMargin = Ui.dp(this, 8);
        center.addView(percentText, percentParams);

        hintText = Ui.text(this,
                "First launch unpacks the engine once (about 450 MB).\nThis can take a minute.",
                12, Ui.color(this, R.color.text_dim));
        hintText.setGravity(Gravity.CENTER);
        hintText.setLineSpacing(Ui.dp(this, 2), 1f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Ui.dp(this, 14);
        center.addView(hintText, hintParams);

        errorText = Ui.text(this, "", 13, Ui.color(this, R.color.danger));
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(Ui.dp(this, 2), 1f);
        errorText.setBackground(Ui.outlined(this, Color.argb(20, 248, 113, 113), 12,
                Color.argb(60, 248, 113, 113)));
        errorText.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.topMargin = Ui.dp(this, 16);
        center.addView(errorText, errorParams);

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.CENTER);
        retryButton = Ui.button(this, "Try again", true);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> {
            resetSplash();
            engine.start();
        });
        safeButton = Ui.button(this, "Safe mode", false);
        safeButton.setVisibility(View.GONE);
        safeButton.setOnClickListener(v -> confirmSafeMode());
        TextView logs = Ui.button(this, "View logs", false);
        logs.setOnClickListener(v -> startActivity(new Intent(this, LogsActivity.class)));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.leftMargin = Ui.dp(this, 5);
        actionParams.rightMargin = Ui.dp(this, 5);
        actions.addView(retryButton, actionParams);
        actions.addView(safeButton, actionParams);
        actions.addView(logs, actionParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = Ui.dp(this, 24);
        center.addView(actions, actionsParams);

        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        centerParams.gravity = Gravity.CENTER;
        overlay.addView(center, centerParams);

        TextView footer = Ui.text(this,
                "engine " + Engine.DSH_VERSION + " · node " + Engine.NODE_VERSION,
                11, Ui.color(this, R.color.text_dim));
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        footerParams.bottomMargin = Ui.dp(this, 18);
        overlay.addView(footer, footerParams);

        pulse = ObjectAnimator.ofFloat(logo, View.ALPHA, 0.72f, 1f);
        pulse.setDuration(1400);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.start();

        return overlay;
    }

    private FrameLayout buildPopupLayer() {
        popupLayer = new FrameLayout(this);
        popupLayer.setBackgroundColor(Color.argb(200, 0, 0, 0));
        popupLayer.setVisibility(View.GONE);
        popupLayer.setClickable(true);

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.outlined(this, Ui.color(this, R.color.bg), 16,
                Ui.color(this, R.color.divider)));

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        popupTitle = Ui.text(this, "Sign in", 15, Ui.color(this, R.color.text));
        popupTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(popupTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.text(this, "✕", 20, Ui.color(this, R.color.text));
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.rounded(this, Color.argb(34, 255, 255, 255), 18));
        close.setClickable(true);
        close.setOnClickListener(v -> closePopup());
        bar.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        card.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        popupContainer = new FrameLayout(this);
        card.addView(popupContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardParams.setMargins(Ui.dp(this, 8), Ui.dp(this, 30), Ui.dp(this, 8), Ui.dp(this, 8));
        popupLayer.addView(card, cardParams);
        return popupLayer;
    }

    private WebView openPopupWindow() {
        closePopup();
        WebView child = new WebView(this);
        configureWebView(child);
        child.setWebViewClient(createWebClient(true));
        child.setWebChromeClient(new DshChromeClient());
        popupContainer.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        popupWebView = child;
        popupTitle.setText("Sign in");
        popupLayer.setVisibility(View.VISIBLE);
        popupLayer.bringToFront();
        return child;
    }

    private void closePopup() {
        WebView child = popupWebView;
        popupWebView = null;
        if (child != null) {
            if (popupContainer != null) popupContainer.removeView(child);
            child.stopLoading();
            child.destroy();
        }
        if (popupLayer != null) popupLayer.setVisibility(View.GONE);
        CookieManager.getInstance().flush();
    }

    private void resetSplash() {
        loadFailed = false;
        errorText.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        safeButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        percentText.setText("");
        loading.setAlpha(1f);
        loading.setVisibility(View.VISIBLE);
    }

    private void showLoading(String status) {
        if (destroyed) return;
        loading.setAlpha(1f);
        loadFailed = false;
        loading.setVisibility(View.VISIBLE);
        statusText.setText(status);
        errorText.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        safeButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        percentText.setText("");
        hintText.setVisibility(View.GONE);
    }

    private void showFailure(String message) {
        if (destroyed) return;
        loadFailed = true;
        resetRetryToStart();
        loading.setAlpha(1f);
        loading.setVisibility(View.VISIBLE);
        statusText.setText("The engine could not start");
        progressBar.setVisibility(View.GONE);
        percentText.setText("");
        hintText.setVisibility(View.GONE);
        errorText.setText(message == null || message.isEmpty() ? "Unknown error" : message);
        errorText.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        safeButton.setVisibility(View.VISIBLE);
        setDot(Ui.color(this, R.color.danger));
        topStatus.setText("stopped");
    }

    private void hideLoading() {
        if (destroyed || loadFailed) return;
        if (loading.getVisibility() != View.VISIBLE) {
            topBar.setVisibility(View.VISIBLE);
            return;
        }
        pageLoaded = true;
        topBar.setVisibility(View.VISIBLE);
        loading.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (!destroyed) loading.setVisibility(View.GONE);
        }).start();
        setDot(Ui.color(this, R.color.ok));
        topStatus.setText("on-device");
    }

    private void setDot(int color) {
        if (statusDot != null) statusDot.setBackground(Ui.circle(color));
    }

    private void openMenu() {
        List<Ui.Item> items = new ArrayList<>();
        items.add(new Ui.Item("Reload interface", () -> {
            if (url != null) {
                showLoading("Reloading the interface…");
                ensureWebView();
                webView.loadUrl(url);
            } else {
                engine.start();
            }
        }));
        items.add(new Ui.Item("Open in browser", () -> {
            if (url == null) {
                toast("The engine is not ready yet.");
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException error) {
                toast("No browser installed.");
            }
        }));
        items.add(new Ui.Item("Settings", () ->
                startActivity(new Intent(this, SettingsActivity.class))));
        items.add(new Ui.Item("Logs", () ->
                startActivity(new Intent(this, LogsActivity.class))));
        items.add(new Ui.Item("Restart engine", () -> {
            showLoading("Restarting the engine…");
            engine.restart();
        }));
        items.add(new Ui.Item("Safe mode", this::confirmSafeMode));
        items.add(new Ui.Item("About", () ->
                startActivity(new Intent(this, AboutActivity.class))));
        items.add(new Ui.Item("Stop engine", () ->
                Ui.confirm(this, "Stop the engine?",
                        "The interface will disconnect until you start it again.",
                        "Stop", "Cancel", () -> {
                            engine.stop();
                            showLoading("Engine stopped");
                            progressBar.setVisibility(View.GONE);
                            retryButton.setVisibility(View.VISIBLE);
                            topBar.setVisibility(View.GONE);
                        }), true));
        Ui.sheet(this, "DSH Android", items);
    }

    private void confirmSafeMode() {
        Ui.confirm(this, "Start in safe mode?",
                "Safe mode backs up your provider patch and boots with the official bundle defaults. "
                        + "Your sessions and installed packages are kept.",
                "Start safe mode", "Cancel", () -> {
                    showLoading("Preparing safe mode…");
                    engine.enterSafeMode();
                });
    }

    // --------------------------------------------------------------- webview

    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(this);
        configureWebView(webView);
        webView.setWebViewClient(createWebClient(false));
        webView.setWebChromeClient(new DshChromeClient());
        webView.setDownloadListener(new DshDownloadListener());
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Settings shared by the main surface and the account sign-in window. */
    private void configureWebView(WebView view) {
        view.setBackgroundColor(Ui.color(this, R.color.bg));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebView.setWebContentsDebuggingEnabled(false);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // DeepSeek account sign-in opens a popup window; without this the
        // request is silently dropped and the button appears to do nothing.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(settings.getUserAgentString() + " DSH-Android/" + appVersion());
        if (Build.VERSION.SDK_INT >= 23) settings.setOffscreenPreRaster(true);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);
    }

    private WebViewClient createWebClient(boolean popup) {
        if (Build.VERSION.SDK_INT >= 26) return new DshWebClient26(popup);
        return new DshWebClient(popup);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "0";
        }
    }

    private static boolean isLoopback(Uri uri) {
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    /** Rewrites the app-only scheme the DSH frontend uses for attachments. */
    private String mapDshApp(Uri uri) {
        if (!"dsh-app".equals(uri.getScheme())) return null;
        int port = engine.port();
        if (port <= 0) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty()) path = "/";
        StringBuilder mapped = new StringBuilder("http://127.0.0.1:").append(port).append(path);
        if (uri.getQuery() != null) mapped.append('?').append(uri.getQuery());
        return mapped.toString();
    }

    private WebResourceResponse fetchLoopback(String mapped) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new java.net.URL(mapped).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            String cookie = CookieManager.getInstance().getCookie(mapped);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            InputStream stream = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return null;
            String contentType = connection.getContentType();
            String mime = "application/octet-stream";
            String encoding = null;
            if (contentType != null) {
                int semi = contentType.indexOf(';');
                mime = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
                int charset = contentType.toLowerCase(Locale.US).indexOf("charset=");
                if (charset >= 0) encoding = contentType.substring(charset + 8).trim();
            }
            Map<String, String> headers = new HashMap<>();
            String disposition = connection.getHeaderField("Content-Disposition");
            if (disposition != null) headers.put("Content-Disposition", disposition);
            return new WebResourceResponse(mime, encoding, code,
                    connection.getResponseMessage(), headers, stream);
        } catch (Exception error) {
            LogStore.append("web", "dsh-app fetch failed: " + error);
            if (connection != null) connection.disconnect();
            return null;
        }
    }

    private class DshWebClient extends WebViewClient {
        final boolean popup;

        DshWebClient(boolean popup) {
            this.popup = popup;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(view, request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String value) {
            return handleUri(view, Uri.parse(value));
        }

        private boolean handleUri(WebView view, Uri uri) {
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if ("dsh-app".equals(scheme)) {
                String mapped = mapDshApp(uri);
                if (mapped != null) view.loadUrl(mapped);
                return true;
            }
            if (("http".equals(scheme) || "https".equals(scheme))
                    && (isLoopback(uri) || popup)) {
                return false;
            }
            if ("http".equals(scheme) || "https".equals(scheme)
                    || "mailto".equals(scheme) || "tel".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException error) {
                    toast("No app can open this link.");
                }
                return true;
            }
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                          WebResourceRequest request) {
            String mapped = mapDshApp(request.getUrl());
            if (mapped == null) return null;
            return fetchLoopback(mapped);
        }

        @Override
        public void onPageCommitVisible(WebView view, String loadedUrl) {
            if (!popup) hideLoading();
        }

        @Override
        public void onPageFinished(WebView view, String loadedUrl) {
            CookieManager.getInstance().flush();
            if (popup) {
                if (view == popupWebView && view.getTitle() != null) {
                    popupTitle.setText(view.getTitle());
                }
                return;
            }
            pageProgress.setVisibility(View.GONE);
            hideLoading();
            if (!historyCleared && loadedUrl != null
                    && isLoopback(Uri.parse(loadedUrl))) {
                // Drop the one-time token URL from the back stack; the auth
                // cookie is already stored.
                view.clearHistory();
                historyCleared = true;
            }
            view.evaluateJavascript(
                    "(function(){try{"
                            + "var d=document.documentElement,s=d.style;"
                            + "s.overscrollBehavior='none';s.webkitTapHighlightColor='transparent';"
                            + "s.webkitTouchCallout='none';"
                            + "var m=document.querySelector('meta[name=theme-color]');"
                            + "if(!m){m=document.createElement('meta');m.name='theme-color';"
                            + "document.head.appendChild(m);}m.content='#0B0E14';"
                            + "var v=document.querySelector('meta[name=viewport]');"
                            + "if(v){var c=v.getAttribute('content')||'';"
                            + "if(c.indexOf('viewport-fit')<0)v.setAttribute('content',c+', viewport-fit=cover');}"
                            + "}catch(e){}})();", null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String detail = error.getDescription() == null
                    ? "The page failed to load." : error.getDescription().toString();
            if (popup) {
                toast("Sign-in page: " + detail);
            } else {
                showLoadError(detail);
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (!request.isForMainFrame() || popup) return;
            int status = response.getStatusCode();
            if (status != 401 && status != 403) return;
            if (authRetries < 2 && pendingUrl != null) {
                authRetries++;
                view.postDelayed(() -> {
                    if (!destroyed && authRetries <= 2 && pendingUrl != null) {
                        view.loadUrl(pendingUrl);
                    }
                }, 800);
            } else if (!authRestarted) {
                // A restarted engine issues a fresh token; reuse it via onReady.
                authRestarted = true;
                showLoading("Refreshing the engine session…");
                engine.restart();
            } else {
                showLoadError("The interface rejected the session (HTTP " + status
                        + "). Open Settings and restart the engine.");
            }
        }
    }

    /**
     * Only loaded on API 26+, where RenderProcessGoneDetail exists. Keeping the
     * override out of the base client avoids a verifier failure on Android 7.
     */
    private final class DshWebClient26 extends DshWebClient {
        DshWebClient26(boolean popup) {
            super(popup);
        }

        @Override
        public boolean onRenderProcessGone(WebView view,
                                           android.webkit.RenderProcessGoneDetail detail) {
            if (destroyed) return true;
            LogStore.append("web", "renderer gone, rebuilding WebView");
            if (popup) {
                root.post(MainActivity.this::closePopup);
                return true;
            }
            webContainer.removeView(view);
            view.destroy();
            webView = null;
            ensureWebView();
            showLoading("Reloading the interface…");
            if (pendingUrl != null) webView.loadUrl(pendingUrl);
            return true;
        }
    }

    private void showLoadError(String message) {
        if (destroyed) return;
        loadFailed = true;
        loading.setAlpha(1f);
        loading.setVisibility(View.VISIBLE);
        statusText.setText("The interface could not load");
        progressBar.setVisibility(View.GONE);
        percentText.setText("");
        hintText.setVisibility(View.GONE);
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        retryButton.setText("Reload");
        retryButton.setOnClickListener(v -> {
            retryButton.setText("Try again");
            retryButton.setOnClickListener(inner -> {
                resetSplash();
                engine.start();
            });
            showLoading("Reloading the interface…");
            ensureWebView();
            if (url != null) webView.loadUrl(url);
        });
        safeButton.setVisibility(View.VISIBLE);
    }

    private final class DshChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress < 100) {
                pageProgress.setVisibility(View.VISIBLE);
            } else {
                pageProgress.setVisibility(View.GONE);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(resolveMime(params));
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try {
                startActivityForResult(Intent.createChooser(intent, "Choose a file"), REQ_FILE);
            } catch (ActivityNotFoundException error) {
                fileCallback = null;
                toast("No file picker installed.");
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            List<String> grant = new ArrayList<>();
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) grant.add(resource);
            }
            if (grant.isEmpty()) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(grant.toArray(new String[0]));
            } else {
                micRequest = request;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
            LogStore.append("web", message.message() + " ("
                    + message.sourceId() + ":" + message.lineNumber() + ")");
            return true;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (view == popupWebView && title != null && !title.isEmpty()) {
                popupTitle.setText(title);
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            // Account sign-in calls window.open('about:blank', '_blank') and then
            // navigates the returned handle. Hand it a real child WebView so the
            // JS handle stays valid and the loopback callback can complete.
            if (view != webView || resultMsg == null
                    || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                return false;
            }
            WebView child = openPopupWindow();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(child);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (window == popupWebView) {
                root.post(MainActivity.this::closePopup);
                return;
            }
            super.onCloseWindow(window);
        }
    }

    private String resolveMime(WebChromeClient.FileChooserParams params) {
        String[] accepted = params.getAcceptTypes();
        if (accepted == null || accepted.length == 0) return "*/*";
        for (String value : accepted) {
            if (value == null) continue;
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (trimmed.contains("/")) return trimmed;
            }
        }
        return "*/*";
    }

    private final class DshDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String downloadUrl, String userAgent, String disposition,
                                    String mimeType, long contentLength) {
            if (downloadUrl == null || downloadUrl.startsWith("blob:")
                    || downloadUrl.startsWith("data:")) {
                toast("This download uses a format Android's downloader cannot save.");
                return;
            }
            // Public Downloads needs WRITE_EXTERNAL_STORAGE up to Android 9.
            if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl = downloadUrl;
                pendingDownloadUserAgent = userAgent;
                pendingDownloadDisposition = disposition;
                pendingDownloadMime = mimeType;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_DOWNLOAD);
                return;
            }
            startDownload(downloadUrl, userAgent, disposition, mimeType);
        }
    }

    private void startDownload(String downloadUrl, String userAgent, String disposition,
                               String mimeType) {
        String name = URLUtil.guessFileName(downloadUrl, disposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            String cookie = CookieManager.getInstance().getCookie(downloadUrl);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.addRequestHeader("User-Agent", userAgent);
            if (mimeType != null) request.setMimeType(mimeType);
            request.setTitle(name);
            request.setDescription("Downloaded from DSH");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            try {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, name);
            } catch (Exception publicStorageError) {
                request.setDestinationInExternalFilesDir(MainActivity.this,
                        Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager manager = getSystemService(DownloadManager.class);
            if (manager == null) throw new IllegalStateException("no download manager");
            manager.enqueue(request);
            toast("Downloading " + name);
        } catch (Exception error) {
            LogStore.append("download", "failed: " + error);
            toast("Could not start the download.");
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onState(Engine.State state, String message, int progressPercent) {
        if (destroyed) return;
        switch (state) {
            case EXTRACTING:
                showLoading(message);
                hintText.setVisibility(View.VISIBLE);
                if (progressPercent >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(progressPercent);
                    percentText.setText(String.format(Locale.US, "%d%%", progressPercent));
                }
                setDot(Ui.color(this, R.color.accent));
                topStatus.setText("preparing");
                break;
            case STARTING:
                showLoading(message);
                progressBar.setIndeterminate(true);
                hintText.setVisibility(View.VISIBLE);
                setDot(Ui.color(this, R.color.accent));
                topStatus.setText("starting");
                break;
            case RUNNING:
                statusText.setText("Loading the interface…");
                setDot(Ui.color(this, R.color.ok));
                topStatus.setText("running");
                break;
            case STOPPED:
                loadFailed = true;
                resetRetryToStart();
                if (!pageLoaded) showLoading("Engine stopped");
                else {
                    showLoading("Engine stopped");
                    progressBar.setVisibility(View.GONE);
                    retryButton.setVisibility(View.VISIBLE);
                }
                setDot(Ui.color(this, R.color.text_dim));
                topStatus.setText("stopped");
                break;
            case FAILED:
                showFailure(engine.error() == null ? message : engine.error());
                break;
            case IDLE:
            default:
                break;
        }
    }

    @Override
    public void onReady(String readyUrl) {
        if (destroyed) return;
        url = readyUrl;
        pendingUrl = readyUrl;
        authRetries = 0;
        authRestarted = false;
        historyCleared = false;
        loadFailed = false;
        closePopup();
        ensureWebView();
        webView.loadUrl(readyUrl);
        topStatus.setText("loading");
    }

    @Override
    public void onLog(String line) {
        // Logs are available in the dedicated screen; no live UI churn here.
    }

    private void startEngineService() {
        Intent intent = new Intent(this, EngineService.class)
                .setAction(EngineService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (popupWebView != null) {
            closePopup();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onPause() {
        // The auth cookie is HttpOnly and lives in the WebView's cookie jar;
        // flush it before the process can be killed in the background.
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (micRequest != null) {
            micRequest.deny();
            micRequest = null;
        }
        closePopup();
        CookieManager.getInstance().flush();
        engine.removeListener(this);
        if (webView != null) {
            webContainer.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_MIC) {
            if (micRequest != null) {
                boolean granted = grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                if (granted) {
                    micRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    micRequest.deny();
                }
                micRequest = null;
            }
            return;
        }
        if (requestCode == REQ_DOWNLOAD) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingDownloadUrl != null) {
                startDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                        pendingDownloadDisposition, pendingDownloadMime);
            } else if (!granted) {
                toast("Storage permission is needed to save into Downloads.");
            }
            pendingDownloadUrl = null;
            pendingDownloadUserAgent = null;
            pendingDownloadDisposition = null;
            pendingDownloadMime = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void resetRetryToStart() {
        retryButton.setText("Try again");
        retryButton.setOnClickListener(inner -> {
            resetSplash();
            engine.start();
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
