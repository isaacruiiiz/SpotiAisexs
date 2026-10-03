package com.spotiaisexs.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.spotiaisexs.app.ui.navigation.SpotiAisexsNavHost
import com.spotiaisexs.app.ui.theme.SpotiAisexsTheme
import com.spotiaisexs.app.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

// Sign-in opens Last.fm's real web-auth flow in Chrome Custom Tabs (see
// ui/auth/LoginScreen.kt / AuthRepository.authUrl). The AndroidManifest
// registers this Activity for spotiaisexs://auth-callback (Last.fm's cb=
// redirect target) so approving in the browser brings this app back to
// the foreground automatically — singleTop reuses this same instance so
// Compose/ViewModel state (the in-progress AwaitingApproval token) isn't
// lost, and LoginScreen's own onResume check then completes sign-in.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() and before setContent().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeState by themeViewModel.uiState.collectAsState()

            SpotiAisexsTheme(themeState = themeState) {
                SpotiAisexsNavHost()
            }
        }
    }
}
