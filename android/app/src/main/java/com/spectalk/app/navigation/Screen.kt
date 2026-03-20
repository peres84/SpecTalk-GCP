package com.spectalk.app.navigation

sealed interface Screen {
    val route: String

    data object Splash : Screen { override val route = "splash" }
    data object Login : Screen { override val route = "login" }
    data object Register : Screen { override val route = "register" }
    data object Home : Screen { override val route = "home" }
    data object Settings : Screen { override val route = "settings" }
    data object Gallery : Screen { override val route = "gallery" }
    data object VoiceSession : Screen {
        override val route = "voice_session?conversationId={conversationId}"
        fun routeWith(conversationId: String?) = buildString {
            append("voice_session")
            if (conversationId != null) append("?conversationId=$conversationId")
        }
    }
}
