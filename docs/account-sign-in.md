# Signing in with a DeepSeek account

DSH Android runs the official DSH web interface, so account sign-in is the same
flow the upstream project uses. The shell's only job is to let the WebView
perform it.

## What the flow does

1. In the DSH web UI, open **Settings → Account** and choose **Sign in**.
2. The account plugin calls `window.open('about:blank', '_blank')`, clears
   `opener`, and then navigates the popup to the DeepSeek Platform
   `authorizeUrl`.
3. After approval, the platform redirects to the loopback callback
   `http://127.0.0.1:<port>/oauth/callback?code=…&state=…`.
4. The engine verifies `state` and the PKCE verifier, stores the account
   credential, and the plugin closes the popup.

The callback only works because the engine is a loopback HTTP server on the
phone. No external redirect URI or custom scheme is involved.

## Requirements

- **Popup support.** The shell enables `setSupportMultipleWindows(true)` and
  implements `WebChromeClient.onCreateWindow`. Without both, step 2 is
  silently dropped and the button appears to do nothing. This is fixed in
  0.1.1.
- **Cookies.** The auth cookie is `HttpOnly`, `SameSite=Strict`, and valid for
  30 days. The popup and the main WebView share Android's cookie jar, and the
  cookie is flushed when the app pauses.
- **A current Android System WebView.** The DSH frontend uses modern
  JavaScript and CSS (`:has()`, CSS nesting, `Array.prototype.at`). An old
  WebView can render a broken page or fail on the platform sign-in screen.
  Check the version under **About → WebView**. Update "Android System WebView"
  from the Play Store if it is old.
- **Working HTTPS.** The sign-in page is on `platform.deepseek.com`. **About →
  Outbound HTTPS** checks `api.deepseek.com` and reports whether TLS works.
- **A trust store the engine honours.** The bundled Node runtime has no
  embedded CA list, and its OpenSSL looks for a host path that does not exist
  on Android (`/etc/tls/cert.pem`). It also ignores `SSL_CERT_DIR`: only a
  single PEM file is accepted, through `SSL_CERT_FILE` or
  `NODE_EXTRA_CA_CERTS`. Before the engine starts, the app therefore merges
  every readable CA source on the device (the Conscrypt/APEX store,
  `/system/etc/security/cacerts`, user-added roots) into one cached PEM file
  and exports it for Node, cURL and Git. A Mozilla bundle ships as an asset
  for devices with no readable system store. Without a usable store every
  engine request fails with `UNABLE_TO_GET_ISSUER_CERT_LOCALLY`, which the
  account plugin reports as `no-response` and the interface shows as
  "Network error, please retry" (fixed in 0.1.3).
- **Engine TLS self-test.** After the engine starts, it performs one request
  against `https://api.deepseek.com/` with the exact environment it uses and
  writes the outcome to the app log under the `tls` tag
  (**More → Logs**). `probe … -> HTTP 401` means the handshake works;
  `probe … -> FAILED UNABLE_TO_GET_ISSUER_CERT_LOCALLY` means the trust store
  is missing.

## If sign-in does not complete

1. In the account dialog, use **Copy sign-in link**.
2. Open the link in a browser on the same phone.
3. Finish the approval there. The browser reaches the loopback callback and
   the engine completes the exchange; return to the app afterwards.

If the popup closes immediately or the app reports HTTP 401, restart the
engine from **Settings → Restart engine**. A restarted engine issues a fresh
one-time token, and the shell reloads the interface with it.

## API key alternative

A DeepSeek account is optional. On **Settings → Models**, the DeepSeek card
also accepts an API key, which is stored in `$DSH_HOME/.credentials.yaml` and
never shown again after saving. The same applies to OpenRouter, Groq,
Cerebras, Together AI, and SiliconFlow.
