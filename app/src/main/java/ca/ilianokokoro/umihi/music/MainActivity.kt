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
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.managers.PlaylistMembership
import ca.ilianokokoro.umihi.music.core.managers.ScreenAwakeManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        initCaoc()

        val datastoreRepo = DatastoreRepository(this@MainActivity)
        // Wrap each runBlocking read in try-catch: a corrupted DataStore file or a
        // first-install race can throw here, crashing the app before it renders.
        val showWelcome = try {
            runBlocking { !datastoreRepo.hasSeenWelcome() }
        } catch (_: Exception) {
            true   // safe default — show welcome/onboarding
        }
        val isAuthenticated = try {
            runBlocking { datastoreRepo.cookies.first().isNotEmpty() }
        } catch (_: Exception) {
            false  // safe default — require login
        }

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

        ScreenAwakeManager.registerActivity(this)

        // Initialise the app-wide playlist membership tracker so every screen can
        // reactively observe which songs belong to at least one local playlist.
        PlaylistMembership.initialize(this, lifecycleScope)

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
