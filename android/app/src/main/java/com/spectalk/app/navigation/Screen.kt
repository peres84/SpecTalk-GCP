package com.spectalk.app.navigation

sealed interface Screen {
    val route: String

    data object Splash : Screen { override val route = "splash" }
    data object Login : Screen { override val route = "login" }
    data object Register : Screen { override val route = "register" }
    data object Home : Screen { override val route = "home" }
    data object Settings : Screen { override val route = "settings" }
    data object VoiceSession : Screen {
        override val route = "voice_session?conversationId={conversationId}&isActive={isActive}"
        fun routeWith(conversationId: String?, isActive: Boolean) = buildString {
            append("voice_session?isActive=$isActive")
            if (conversationId != null) append("&conversationId=$conversationId")
        }
    }
}
