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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private boolean pageLoaded;
    private boolean loadFailed;
    private boolean destroyed;

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

        setContentView(root);
        Ui.insets(this, root);

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
        webView.setBackgroundColor(Ui.color(this, R.color.bg));
        WebView.setWebContentsDebuggingEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " DSH-Android/" + appVersion());
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new DshWebClient());
        webView.setWebChromeClient(new DshChromeClient());
        webView.setDownloadListener(new DshDownloadListener());
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    private final class DshWebClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String value) {
            return handleUri(Uri.parse(value));
        }

        private boolean handleUri(Uri uri) {
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if (("http".equals(scheme) || "https".equals(scheme)) && isLoopback(uri)) {
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
        public void onPageCommitVisible(WebView view, String loadedUrl) {
            hideLoading();
        }

        @Override
        public void onPageFinished(WebView view, String loadedUrl) {
            pageProgress.setVisibility(View.GONE);
            hideLoading();
            view.evaluateJavascript(
                    "(function(){try{"
                            + "document.documentElement.style.overscrollBehavior='none';"
                            + "var m=document.querySelector('meta[name=theme-color]');"
                            + "if(!m){m=document.createElement('meta');m.name='theme-color';"
                            + "document.head.appendChild(m);}m.content='#0B0E14';"
                            + "}catch(e){}})();", null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                showLoadError(error.getDescription() == null
                        ? "The interface failed to load." : error.getDescription().toString());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (!request.isForMainFrame()) return;
            int status = response.getStatusCode();
            if (status == 401 || status == 403) {
                if (authRetries < 2 && pendingUrl != null) {
                    authRetries++;
                    view.postDelayed(() -> {
                        if (!destroyed) view.loadUrl(pendingUrl);
                    }, 600);
                } else {
                    showLoadError("The interface rejected the session (HTTP " + status
                            + "). Restart the engine to get a fresh token.");
                }
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            if (destroyed) return true;
            LogStore.append("web", "renderer gone, rebuilding WebView");
            webContainer.removeView(view);
            view.destroy();
            webView = null;
            ensureWebView();
            showLoading("Reloading the interface…");
            if (url != null) webView.loadUrl(url);
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
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            return false;
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
        loadFailed = false;
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (loading.getVisibility() == View.VISIBLE && pageLoaded) {
            moveTaskToBack(true);
            return;
        }
        moveTaskToBack(true);
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
