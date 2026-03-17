package com.spectalk.app.navigation

sealed interface Screen {
    val route: String

    data object Splash : Screen { override val route = "splash" }
    data object Login : Screen { override val route = "login" }
    data object Register : Screen { override val route = "register" }
    data object Home : Screen { override val route = "home" }
    data object Settings : Screen { override val route = "settings" }
    data object VoiceSession : Screen {
        override val route = "voice_session?conversationId={conversationId}"
        fun routeWith(conversationId: String?) =
            if (conversationId != null) "voice_session?conversationId=$conversationId"
            else "voice_session"
    }
}
