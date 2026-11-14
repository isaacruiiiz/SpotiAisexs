package com.spotiaisexs.app.ui.shell

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import android.view.HapticFeedbackConstants
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.core.graphics.scale
import com.spotiaisexs.app.ui.theme.drawInteractiveGlass
import com.spotiaisexs.app.ui.theme.liquidGlass
import com.spotiaisexs.app.ui.theme.rememberGlassInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.IntBuffer
import kotlin.time.Duration.Companion.seconds
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotiaisexs.app.ui.common.ExpressiveMotion
import com.spotiaisexs.app.ui.common.PredictiveBackScreen
import com.spotiaisexs.app.ui.common.adaptiveContentWidth
import com.spotiaisexs.app.ui.feed.FeedScreen
import com.spotiaisexs.app.ui.home.HomeScreen
import com.spotiaisexs.app.ui.player.LocalMiniPlayerScrollClearance
import com.spotiaisexs.app.ui.playlist.PlaylistScreen
import androidx.compose.foundation.shape.CornerBasedShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.sign
import com.spotiaisexs.app.ui.theme.LocalIsDarkTheme
import com.spotiaisexs.app.ui.theme.LocalLiquidGlass
import com.spotiaisexs.app.ui.theme.LiquidGlassPreset
import com.spotiaisexs.app.ui.theme.liquidGlassChrome
import com.spotiaisexs.app.ui.theme.liquidGlassContainerColor
import com.spotiaisexs.app.ui.theme.LayerBackdrop
import com.spotiaisexs.app.ui.theme.SquircleShape
import com.spotiaisexs.app.ui.theme.rememberLayerBackdrop
import com.spotiaisexs.app.ui.theme.isLiquidGlassBackdropSupported
import com.spotiaisexs.app.ui.theme.liquidGlassSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Thin bridge exposing AppUpdateManager's live update state to MainShell */
@HiltViewModel
class MainShellViewModel @Inject constructor(
    val appUpdateManager: com.spotiaisexs.app.data.update.AppUpdateManager,
) : ViewModel() {
    val updateInfo = appUpdateManager.updateInfo

    fun dismissUpdate(version: String) {
        appUpdateManager.dismissUpdate(version)
    }

    fun openUpdate(context: android.content.Context) {
        appUpdateManager.openUpdate(context)
    }
}

// Pager order: the two the bottom bar shows, then the statistics page, which is
// reached from the home header (and by swiping past the library).
private enum class MainTab(val labelRes: Int) {
    FEED(com.spotiaisexs.app.R.string.nav_feed),
    PLAYLISTS(com.spotiaisexs.app.R.string.nav_playlists),
    STATS(com.spotiaisexs.app.R.string.nav_stats),
}

private val DialNavHeight = 58.dp

/** Shared with any screen hosted inside [MainShell] so their scrolling
 *  lists know how much bottom content padding to reserve — the nav
 *  overlays content (it's not a Scaffold bottomBar reserving space), so
 *  each screen leaves this much room for its last item to clear it. */
object FloatingNavDefaults {
    val ContentBottomPadding = 66.dp

    /**
     * Full bottom clearance for edge-to-edge scrolling content: the floating
     * dock's visual height + margins ([ContentBottomPadding]) PLUS the live
     * navigation-bar (gesture area) inset. Screens that let their list draw
     * beneath the transparent gesture area must use this instead of the raw
     * constant, otherwise the last row hides behind the dock/gesture bar.
     */
    @Composable
    fun contentBottomPadding(): Dp =
        ContentBottomPadding +
            LocalMiniPlayerScrollClearance.current +
            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
}

// Design "Cristal, color de portada": a thin capsule (46 dp) instead of the tall dock.
private val DockShape: CornerBasedShape = RoundedCornerShape(23.dp)
private val NavBarHeight = 46.dp
private val NavItemHeight = 36.dp
private val PillShape: Shape = CircleShape

private fun <T> navSpring() = ExpressiveMotion.spatialSpring<T>()

@Composable
fun MainShell(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFriendProfile: (username: String, displayName: String?, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    onOpenNewReleases: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    mainShellViewModel: MainShellViewModel = hiltViewModel(),
) {
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Drive the dock selection from an explicit tab index instead of
    // pagerState.currentPage. currentPage flips mid-scroll (halfway through
    // animateScrollToPage), which re-triggers the pill width animation + the
    // generator FAB enter/exit while the pager is still moving — the two
    // competing size animations clip the dock (rectangular, limited) and let
    // the FAB draw over the pill. Updating immediately on tap and syncing from
    // settledPage on swipe keeps one clean transition.
    var selectedTabIndex by remember { mutableIntStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.settledPage) {
        if (selectedTabIndex != pagerState.settledPage) {
            selectedTabIndex = pagerState.settledPage
        }
    }
    val updateInfo by mainShellViewModel.updateInfo.collectAsStateWithLifecycle()
    val showUpdateBanner = updateInfo.isUpdateAvailable && !updateInfo.isDismissed
    val backgroundColor = MaterialTheme.colorScheme.background
    // Unconditional remember keeps composition stable; usage gated below.
    val navigationBackdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
    val navGlass = isLiquidGlassBackdropSupported()

    Box(Modifier.fillMaxSize()) {
        val feedIndex = tabs.indexOf(MainTab.FEED)
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize().liquidGlassSource(if (navGlass) navigationBackdrop else null),
        ) { page ->
            val isCurrent = page == pagerState.currentPage
            PredictiveBackScreen(
                enabled = isCurrent && tabs[page] != MainTab.FEED,
                onBack = { scope.launch { pagerState.animateScrollToPage(feedIndex) } },
            ) {
                when (tabs[page]) {
                    MainTab.FEED -> com.spotiaisexs.app.ui.radio.RadioHomeScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = onOpenSearch,
                        onOpenStats = { scope.launch { pagerState.animateScrollToPage(tabs.indexOf(MainTab.STATS)) } },
                        onOpenFeedPlaylist = onOpenFeedPlaylist,
                    )
                    MainTab.STATS -> HomeScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = onOpenSearch,
                        onOpenDiscover = onOpenDiscover,
                        onOpenGenres = onOpenGenres,
                        onOpenFriends = onOpenFriends,
                        onOpenDownloads = onOpenDownloads,
                    )
                    MainTab.PLAYLISTS -> PlaylistScreen(onOpenPlaylist = onOpenPlaylist)
                }
            }
        }

        // App update prompt banner (only shown on app open when an update is available and not dismissed)
        AnimatedVisibility(
            visible = showUpdateBanner,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .zIndex(10f),
        ) {
            UpdatePromptCard(
                version = updateInfo.latestVersion,
                onUpdate = { mainShellViewModel.openUpdate(context) },
                onDismiss = { mainShellViewModel.dismissUpdate(updateInfo.latestVersion) },
            )
        }

        // "Escuchando en Navegador": shown while the web player is the active device.
        com.spotiaisexs.app.ui.connect.ConnectRemoteBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .zIndex(9f),
        )

        fun goTo(tab: MainTab) {
            val index = tabs.indexOf(tab)
            if (index != selectedTabIndex) selectedTabIndex = index
            scope.launch { pagerState.animateScrollToPage(index) }
        }
        // The playlist generator used to live in the old dock; in the library it
        // stays one tap away as a small round button above the bars.
        if (tabs[selectedTabIndex] == MainTab.PLAYLISTS) {
            Surface(
                onClick = onOpenGenerator,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(end = 16.dp, bottom = DialNavHeight + LocalMiniPlayerScrollClearance.current + 14.dp)
                    .size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = androidx.compose.ui.res.stringResource(com.spotiaisexs.app.R.string.nav_create_playlist),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        DialNavBar(
            selected = if (tabs[selectedTabIndex] == MainTab.FEED) DialNavItem.RADIO else DialNavItem.LIBRARY,
            onRadio = { goTo(MainTab.FEED) },
            onSearch = onOpenSearch,
            onLibrary = { goTo(MainTab.PLAYLISTS) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun UpdatePromptCard(
    version: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.spotiaisexs.app.R.string.update_available),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(com.spotiaisexs.app.R.string.update_ready_to_install, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onUpdate),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.spotiaisexs.app.R.string.update),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(com.spotiaisexs.app.R.string.dismiss_update),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Which of the three bottom items is lit. "Buscar" is a screen of its own, never selected here. */
private enum class DialNavItem { RADIO, LIBRARY }

/**
 * Design "Dial": a flat bar with a hairline on top and three items (Radio,
 * Buscar, Biblioteca). The active one is orange with a 2 dp marker above it.
 */
@Composable
private fun DialNavBar(
    selected: DialNavItem,
    onRadio: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) },
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .fillMaxWidth()
                .height(DialNavHeight),
        ) {
            DialNavButton("Radio", Icons.Filled.Sensors, selected == DialNavItem.RADIO, onRadio, Modifier.weight(1f))
            DialNavButton("Buscar", Icons.Filled.Search, false, onSearch, Modifier.weight(1f))
            DialNavButton("Biblioteca", Icons.Filled.LibraryMusic, selected == DialNavItem.LIBRARY, onLibrary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DialNavButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                if (selected) drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            }
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
    }
}
