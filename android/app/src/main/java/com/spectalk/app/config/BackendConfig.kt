package com.spectalk.app.config

/**
 * Centralized backend URL configuration.
 *
 * Initialized once in [com.spectalk.app.SpecTalkApplication.onCreate] from
 * R.string.backend_base_url. To point at a different environment, update that string resource.
 *
 * Dev (Android emulator → host localhost): http://10.0.2.2:8080
 * Production: https://gervis-backend-xxxxxxxxxx-uc.a.run.app
 */
object BackendConfig {

    /** HTTP base URL — no trailing slash. */
    var baseUrl: String = "http://10.0.2.2:8080"
        private set

    /** WebSocket base URL derived from [baseUrl]. */
    val wsBaseUrl: String
        get() = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")

    fun init(url: String) {
        baseUrl = url.trimEnd('/')
    }
}
