package ca.ilianokokoro.umihi.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.managers.PlaylistMembership
import ca.ilianokokoro.umihi.music.core.helpers.AccountInfoHelper
import ca.ilianokokoro.umihi.music.core.managers.ScreenAwakeManager
import ca.ilianokokoro.umihi.music.core.managers.UpdateManager
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.SongRepository
import ca.ilianokokoro.umihi.music.ui.navigation.NavigationRoot
import ca.ilianokokoro.umihi.music.ui.theme.MusicTheme
import cat.ereza.customactivityoncrash.config.CaocConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val songRepository: SongRepository = SongRepository()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    // Startup data is read asynchronously in onCreate while the splash screen
    // stays visible, so the first drawn frame always has correct values without
    // blocking the main thread with runBlocking.
    private var showWelcome by mutableStateOf(true)
    private var isAuthenticated by mutableStateOf(false)
    private var keepSplashOn by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        // Keep the splash up until the DataStore reads below complete, so the
        // welcome/auth decision is never rendered from the stale defaults.
        splashScreen.setKeepOnScreenCondition { keepSplashOn }

        initCaoc()

        val datastoreRepo = DatastoreRepository(this@MainActivity)
        // Read DataStore off the main thread. Wrap each read in try-catch: a
        // corrupted DataStore file or a first-install race can throw, so we
        // fall back to the same safe defaults as before instead of crashing.
        lifecycleScope.launch {
            val welcome = try {
                !datastoreRepo.hasSeenWelcome()
            } catch (_: Exception) {
                true   // safe default — show welcome/onboarding
            }
            val auth = try {
                datastoreRepo.cookies.first().isNotEmpty()
            } catch (_: Exception) {
                false  // safe default — require login
            }
            showWelcome = welcome
            isAuthenticated = auth
            keepSplashOn = false

            // Only set Compose content once the real values are known, so
            // NavigationRoot's first composition — and therefore its
            // NavBackStack's initial screen — is correct from the start.
            // (rememberNavBackStack only reads its initial key on first
            // composition, so composing with the stale isAuthenticated=false
            // would pin the app to AuthScreenKey for the whole session.)
            // The splash screen covers this window, so there's no perceived delay.
            enableEdgeToEdge()
            setContent {
                MusicTheme {
                    NavigationRoot(
                        modifier = Modifier.fillMaxSize(),
                        showWelcome = showWelcome,
                        isAuthenticated = isAuthenticated
                    )
                }
            }
        }

        ScreenAwakeManager.registerActivity(this)

        // Self-healing account profile: if the login-time account_menu fetch
        // failed or stored a blank avatar/name/email, refill them on the next
        // launch so the profile picture shows without the user having to open
        // the Profile screen. No-op when logged out or already complete.
        lifecycleScope.launch {
            AccountInfoHelper.fetchAndSaveIfMissing(this@MainActivity)
        }

        // Initialise the app-wide playlist membership tracker so every screen can
        // reactively observe which songs belong to at least one local playlist.
        PlaylistMembership.initialize(this, lifecycleScope)

        // Fire-and-forget update check — a background nice-to-have, never
        // startup-gating data. Gated per-flavor so the store build is inert.
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            UpdateManager.checkForUpdate(this)
        }

        handleShareIntent(intent)
        handleViewIntent(intent)

        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        PlayerManager.connectController(this)
    }

    override fun onDestroy() {
        ScreenAwakeManager.unregisterActivity(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleViewIntent(intent)
    }

    override fun attachBaseContext(newBase: Context) {
        // This runBlocking is unavoidable: attachBaseContext runs before
        // super.onCreate(), so no lifecycleScope or splash screen exists yet,
        // and the special-language flag must be known synchronously to build
        // the configuration context returned below. It is a single DataStore
        // read and only runs on activity (re)creation, not per frame.
        val useSpecialLanguage = try {
            runBlocking { DatastoreRepository(newBase).settings.first().useSpecialLanguage }
        } catch (_: Exception) {
            false  // safe default — use system locale
        }

        val context = if (useSpecialLanguage) {
            val locale = java.util.Locale.forLanguageTag(Constants.Locale.Special.CODE)
            val config = Configuration(newBase.resources.configuration)
            config.setLocales(android.os.LocaleList(locale))
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }

        super.attachBaseContext(context)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        if (intent.type != "text/plain") {
            return
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return

        val urlRegex = Regex(Constants.YoutubeApi.URL_REGEX)
        val url = urlRegex.find(text)?.value ?: return
        val videoId = YoutubeDataExtractor.extractYouTubeVideoId(url) ?: return

        playVideoFromId(videoId)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) {
            return
        }
        val data: Uri = intent.data ?: return
        val videoId = YoutubeDataExtractor.extractYouTubeVideoId(data.toString()) ?: return
        playVideoFromId(videoId)
    }

    private fun playVideoFromId(id: String) {
        lifecycleScope.launch {
            songRepository.getSongInfo(id).collect { apiResult ->
                when (apiResult) {
                    is ApiResult.Error -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.get_song_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    ApiResult.Loading -> {}
                    is ApiResult.Success -> {
                        PlayerManager.playSong(apiResult.data)
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun initCaoc() {
        CaocConfig.Builder.create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_CRASH)
            .trackActivities(true)
            .apply()
    }
}
