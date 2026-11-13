// Cases for the rules in android.yaml, checked by `semgrep --test`.

val trusting =
    object : X509TrustManager {
        // ruleid: trust-all-certificates
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("no client certificates")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

val checking =
    object : X509TrustManager {
        // ok: trust-all-certificates
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            system.checkServerTrusted(chain, authType)
        }
    }

// ruleid: hostname-verifier-allows-all
val anyHost = HostnameVerifier { _, _ -> true }

// ok: hostname-verifier-allows-all
val oneHost = HostnameVerifier { host, _ -> host == "example.org" }

fun web(view: WebView) {
    // ruleid: webview-unsafe-settings
    view.settings.javaScriptEnabled = true
    // ok: webview-unsafe-settings
    view.settings.javaScriptEnabled = false
    // ruleid: webview-unsafe-settings
    view.addJavascriptInterface(Bridge(), "bridge")
}

fun files(context: Context, file: File) {
    // ruleid: world-accessible-file
    context.openFileOutput("words", Context.MODE_WORLD_READABLE)
    // ok: world-accessible-file
    context.openFileOutput("words", Context.MODE_PRIVATE)
    // ruleid: world-accessible-file
    file.setReadable(true, false)
    // ok: world-accessible-file
    file.setReadable(true, true)
}

// ruleid: mutable-pending-intent
val mutable = PendingIntent.FLAG_MUTABLE

// ok: mutable-pending-intent
val immutable = PendingIntent.FLAG_IMMUTABLE

// ruleid: cleartext-url
val plain = "http://example.org/v1"

// ok: cleartext-url
val secure = "https://example.org/v1"

// ok: cleartext-url
val emulator = "http://10.0.2.2:8080"
