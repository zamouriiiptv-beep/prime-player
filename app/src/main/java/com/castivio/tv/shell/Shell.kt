package com.castivio.tv.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.castivio.core.common.AppError
import com.castivio.core.common.ScreenState
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.CastivioShell
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.IconLabel
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.NowPlayingBadge
import com.castivio.core.design.components.ScreenScaffold
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.components.WatchState
import com.castivio.core.design.components.WatchedTag
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.castivioBackdrop
import com.castivio.core.design.theme.rememberMetrics
import com.castivio.core.navigation.BackPolicy
import com.castivio.core.navigation.ShellBack
import com.castivio.domain.CatalogQuery
import com.castivio.domain.HiddenKind
import com.castivio.domain.MediaKind as CatalogKind
import com.castivio.domain.PlayerPulse
import com.castivio.domain.PressVerdict
import com.castivio.domain.pressVerdictFor
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SteppedChannel
import com.castivio.feature.activation.ActivationEntry
import com.castivio.feature.activation.ActivationRoute
import com.castivio.feature.activation.LanguagePicker
import com.castivio.feature.home.BrowseScreen
import com.castivio.feature.home.CatalogSearchScreen
import com.castivio.feature.home.CatalogSection
import com.castivio.feature.home.CatalogSelection
import com.castivio.feature.home.ChannelHidingViewModel
import com.castivio.feature.home.ChannelLockViewModel
import com.castivio.feature.home.ChannelZapViewModel
import com.castivio.feature.home.ChannelsScreen
import com.castivio.feature.home.ChannelsViewModel
import com.castivio.feature.home.HomeScreen
import com.castivio.feature.home.MovieScreen
import com.castivio.feature.home.MoviesScreen
import com.castivio.feature.home.PinPrompt
import com.castivio.feature.home.PinPurpose
import com.castivio.feature.home.R as CatalogStrings
import com.castivio.feature.home.RecentChannelsViewModel
import com.castivio.feature.home.ShowScreen
import com.castivio.feature.home.asSelection
import com.castivio.feature.home.watchedChannel
import com.castivio.feature.licence.R as LicenceStrings
import com.castivio.feature.player.PlayerMode
import com.castivio.feature.player.PlayerRequest
import com.castivio.feature.player.PlayerRoute
import com.castivio.playback.api.MediaKind
import com.castivio.tv.R
import com.castivio.tv.licence.LicenceWithLanguage
import com.castivio.tv.locale.LocalLocaleController
import com.castivio.tv.player.PlayerHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The top-level destinations the shell can be on. */
private enum class Dest { Home, Live, Movies, Series, Radio, Favourites, Library, Search, Settings }

/** An overlay drawn above the shell: a show's episodes, the player, or Settings' extras. */
private sealed interface Overlay {

    /**
     * Whether this one **replaces** the destination underneath rather than covering it.
     *
     * The default is false, and that is the older and the commoner case: the shell keeps
     * composing and the overlay is drawn over it. `Play` needs exactly that — collapsing
     * the large player back to the Channels plate depends on the board still being there
     * to collapse *to*, and removing it would release the engine on the way in.
     *
     * True means the destination is not composed at all while this is up. It is not a
     * matter of taste: an overlay is responsible for the ground it stands on, and an
     * overlay that paints none is legible only over nothing. `ShowScreen` calls
     * `castivioBackdrop()` and `StateBoardOverlay` does too, so both are opaque and both
     * may sit over Home. `ActivationRoute` deliberately paints nothing — it was written
     * for the gate, where it is the whole window and `CastivioTheme`'s one backdrop is
     * already behind it — so over a composed Home it reads as two screens at once.
     *
     * Answered by removing the screen underneath rather than by giving the flow a
     * background of its own: a second painted ground would be a second implementation of
     * the backdrop, and the gate would then draw it twice.
     */
    val replacesShell: Boolean get() = false

    /** One show's seasons. Series rows are not streams, so a press has to land here. */
    data class Show(val show: SeriesSummary) : Overlay

    /** One film's page: what a press on a poster opens, and where Back out of its player returns to. */
    data class Movie(val movie: com.castivio.domain.Movie) : Overlay

    /**
     * A real stream, with everything the engine is allowed to be given.
     *
     * [back] is where leaving the player returns to when it was not opened from the board it covers: a
     * film is started from its page, and Back out of the picture lands on that page and not on the wall
     * under it. Null for every other way in, which leave to what is underneath, as they always did.
     */
    data class Play(val request: PlayerRequest, val back: Overlay? = null) : Overlay

    data object StateBoard : Overlay

    /**
     * The subscription flow, over the shell.
     *
     * The same `ActivationRoute` the gate shows before there is a catalogue, opened
     * here by a working app to add a second provider or replace the one showing. It
     * is an overlay rather than a rail destination for the reason the licence screen
     * is: the flow owns the whole viewport — its own header, its own back ladder, its
     * own immersive mode — and drawing it inside the rail would be a different
     * composition from the one that was designed and measured.
     *
     * Nothing about the flow changed to make this work. It already reported success
     * through `onActivated` and exhaustion of its back stack through `onExit`; the
     * gate turns those into "re-ask where the app should open" and the shell turns
     * both into "close me". What makes the new content appear afterwards is not a
     * callback at all — Home reads Room through flows, so the counts move on their
     * own while this is still on screen.
     */
    data class AddSource(
        /**
         * Which door into the flow this press opened.
         *
         * Home's playlist menu has two: "add playlist" wants the chooser, and "my
         * subscriptions" wants the list that renames and deletes. One overlay carrying
         * the entry rather than two overlays, because it is one flow and a second
         * variant would be a second thing to keep in step with it.
         */
        val entry: ActivationEntry = ActivationEntry.Default,
    ) : Overlay {

        /**
         * **The flow is a screen, not a sheet.** See [replacesShell].
         *
         * This is also what the gate already does: there, `ActivationRoute` is the only
         * thing composed. Entering it from a working app now means the same composition
         * rather than a second one layered over Home, which is why the two look alike.
         */
        override val replacesShell: Boolean get() = true
    }

    /**
     * Castivio's own licence, reached from Settings.
     *
     * An overlay rather than a rail destination, and that is the design decision
     * rather than an implementation shortcut: the licence screen owns the whole
     * viewport -- immersive, full-bleed, no scroll, hairlines edge to edge -- and
     * a screen drawn inside the rail and the bottom bar would be a different
     * composition from the one that was approved and measured. Back returns to
     * Settings, which is where it was opened from.
     */
    data object Licence : Overlay

    /**
     * The language chooser, over the shell.
     *
     * The same picker the licence screen opens, hosted here because Home now offers
     * the choice too. An overlay and not a destination for the reason the others are:
     * the grid owns the viewport and dismisses back to whatever opened it.
     */
    data object Language : Overlay

    /**
     * The newest channels, films and series, whose screen this build does not have yet.
     *
     * The control is on Home's rail now; its screen is built after the section screens
     * it takes its cards from. What it opens until then is the app's own sentence for a
     * part of Castivio that is not ready yet — the same `NOT_CONFIGURED` copy every other
     * screen uses — rather than nothing at all. A button that does nothing when pressed
     * teaches a user that the row is decorative; one that explains itself teaches them
     * the feature is coming.
     *
     * It took Time Shift's place. Time shift and catch-up act on what is playing, so
     * they are built into the player, not opened from Home.
     */
    data object Latest : Overlay
}

/**
 * The application shell, over the provider's real catalogue.
 *
 * Every section here reads the database the activation flow imported into: categories
 * from the group table, rows from the pager, counts from an indexed `COUNT`, and a
 * press opens the provider's own stream URL in the real engine. Nothing on this screen
 * is a fixture any more, which is what the comment on `MainActivity` used to promise
 * and this slice delivers.
 *
 * Back follows the rule [BackPolicy] encodes: an overlay pops, a section returns Home,
 * and Home asks whether to leave.
 */
@Composable
fun ShellScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    /**
     * Ask to leave Castivio.
     *
     * Ask, not leave. The confirmation is the application's now and is drawn
     * above this screen; what happens once the user answers it is not the
     * shell's business, which is why this used to be `finish()` and is not.
     */
    onExit: () -> Unit,
) {
    var dest by remember { mutableStateOf(Dest.Home) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }

    // **What the search screen is allowed to answer with, decided by where it was opened
    // from.**
    //
    // Held beside `dest` rather than folded into it because `Dest` is an enum and the
    // restriction is a parameter, not a destination: there is one search screen and it is
    // reached from five places. Null is the catalogue-wide search, which is what every
    // caller but one wants.
    //
    // The one is the Channels board, whose field says **Search channels**. A viewer who
    // types "sport" into a field on a screen of channels is looking for a channel, and
    // answering with films is answering a question they did not ask. The restriction goes
    // into the query rather than over its result -- see `CatalogRepository.search`.
    var searchKind by remember { mutableStateOf<CatalogKind?>(null) }

    // **What the Channels board is playing in its preview plate, if anything.**
    //
    // Held here, beside the overlay, because the compact player and the large one are the
    // same player: one `PlayerRoute` bound to one activity-scoped `PlayerViewModel`, drawn
    // in one shape or the other. This is the request both shapes open, and the reason
    // expanding and collapsing does not interrupt the stream -- `PlayerViewModel.open`
    // returns early when it is handed the request it is already playing, so the swap moves
    // the surface and leaves the decoder alone.
    //
    // The two shapes are never composed together. The overlay covers the board but does
    // not remove it from the composition, so the compact slot is withheld while the
    // expanded player is up; two surfaces would both claim the engine's single output.
    var livePreview by remember { mutableStateOf<PlayerRequest?>(null) }

    // **What the player is doing, as the player says it.**
    //
    // Reported by whichever shape of the player is composed -- see `PlayerRoute.onPulseChange`
    // -- and held here only to carry it to the board, which must not know the player exists.
    // It is not the request: a channel that was pressed and has not drawn, or that failed, is
    // not "playing" in it, and the channel before it is not in it either once the engine has
    // moved on. That is the whole of why the board's playing mark, its preview and its quality
    // badge cannot be fooled by a press.
    var pulse by remember { mutableStateOf<PlayerPulse?>(null) }

    // ## Back, and the one place it asks before it acts
    //
    // Always enabled now, where it used to stand aside at the root and let the
    // system close the app. The ladder is: an overlay, then a section, then the
    // root — and only at the root is there nothing left to go back *to*, which
    // is the whole condition for asking.
    //
    // Asking anywhere else would be the familiar mistake of confirming a
    // navigation, and on a remote — where back is the most-pressed key on the
    // device — a dialog between the user and Home is a dialog they learn to
    // dismiss without reading.
    BackHandler(enabled = true) {
        when (
            BackPolicy.fromShell(
                overlayOpen = overlay != null,
                atRoot = dest == Dest.Home,
            )
        ) {
            ShellBack.CloseOverlay -> overlay = null
            ShellBack.GoToRoot -> dest = Dest.Home
            ShellBack.ConfirmExit -> onExit()
        }
    }

    // One conversion, in the one place that knows both the catalogue and the player.
    //
    // `:feature:home` must not depend on `:feature:player`, so a press arrives here as
    // what the row already had on screen and becomes a request here -- exactly as a
    // local file does in `PlayerHost`. The player still fetches nothing before its
    // first frame, because there is nothing left for it to fetch.
    //
    // **A locked channel asks for its password here too.** The Channels board asks for its own presses,
    // because it has to hold back what it selects until the password is given. Every other way to a
    // channel -- a search result, above all -- arrives as this, and the password is asked for before
    // the player is opened and not after: a locked channel that started and then asked would already
    // have shown a picture.
    val lockModel: ChannelLockViewModel = hiltViewModel()
    // What the viewer has hidden from the channel board, and the way back from Settings.
    val hidingModel: ChannelHidingViewModel = hiltViewModel()
    val hiddenBouquets by hidingModel.hiddenBouquets.collectAsState()
    val hiddenChannels by hidingModel.hiddenChannels.collectAsState()
    var asking by remember { mutableStateOf<ShellPin?>(null) }
    val scope = rememberCoroutineScope()
    val zapModel: ChannelZapViewModel = hiltViewModel()
    // The board's own holder, which is the activity's: the same one `ChannelsScreen` binds.
    val board: ChannelsViewModel = hiltViewModel()
    var zapping by remember { mutableStateOf<Zapping?>(null) }
    val play: (CatalogSelection) -> Unit = { selection ->
        val start = {
            val request = selection.asPlayerRequest()
            // A live channel is one the channel keys step from. Remembered with the request it was
            // started by, so the keys only ever step from the channel that is actually on screen.
            zapping = selection.mediaId?.takeIf { selection.live }?.let { Zapping(request, it, selection.listed) }
            overlay = Overlay.Play(request)
        }
        val id = selection.mediaId
        when (pressVerdictFor(live = selection.live, channelId = id, known = lockModel.locked.value)) {
            PressVerdict.Start -> start()
            PressVerdict.Ask -> { asking = ShellPin(PinPurpose.Play) { start() } }
            PressVerdict.Wait -> {
                scope.launch {
                    if (id != null && lockModel.isLocked(id)) asking = ShellPin(PinPurpose.Play) { start() } else start()
                }
            }
        }
    }

    // Live is the one section where a press does not open the large player.
    //
    // It plays into the board's own plate instead, which is what the preview column has
    // always been for: a viewer stepping down a list of 55,000 channels is sampling them,
    // and a full-screen player per press means a press to open and a press to come back
    // for every channel they reject. Expanding is then one further press, on the picture.
    val playInPreview: (CatalogSelection) -> Unit = { selection ->
        val request = selection.asPlayerRequest()
        livePreview = request
        zapping = selection.mediaId?.let { Zapping(request, it, selection.listed) }
    }

    // **Making the small picture large is opening its channel, and a locked one asks first.**
    //
    // Both ways there come through here -- a press on the picture, and a second press on its row in
    // the list -- and both made it large with nothing asked, so a channel locked while it was in the
    // picture opened full size at the next press. Now the password is asked as for any press on a
    // locked channel, and the picture grows only once it is given. Asked here and not in the board as
    // well, so one press is never two questions.
    val expandPreview: (PlayerRequest) -> Unit = { request ->
        val id = zapping?.takeIf { it.request == request }?.channelId
        val open = { overlay = Overlay.Play(request) }
        when (pressVerdictFor(live = true, channelId = id, known = lockModel.locked.value)) {
            PressVerdict.Start -> open()
            PressVerdict.Ask -> { asking = ShellPin(PinPurpose.Play) { open() } }
            PressVerdict.Wait -> {
                scope.launch {
                    if (id != null && lockModel.isLocked(id)) asking = ShellPin(PinPurpose.Play) { open() } else open()
                }
            }
        }
    }

    // **The channel keys of the large player: the channel after this one, and the one before.**
    //
    // The player says a key was pressed -- see `PlayerRoute.onChannelStep` -- and this is the one
    // place that knows what a channel is: it reads the neighbour from the channel list, in the order
    // that list is in, plays it in the large player where the viewer is, and does not go back to a
    // list. One step at a time, in the order the keys were pressed, so a second press steps from the
    // channel the first arrived at and not from the one it left.
    //
    // A locked neighbour asks for its password like any other press on it, over the player, which goes
    // on playing the channel it has until the password is given. And while that is asked, the keys are
    // taken and do nothing: a second step must not open a second question.
    val stepping = remember { Mutex() }
    val stepChannel: (Int) -> Unit = { by ->
        if (asking == null) {
            scope.launch {
                stepping.withLock {
                    val from = zapping ?: return@withLock
                    val stepped = zapModel.step(from.list, from.channelId, by) ?: return@withLock
                    val channel = stepped.channel
                    val selection = channel.asSelection() ?: return@withLock
                    val begin = {
                        // Only if the viewer is still where the key was pressed: a question that was
                        // answered after they had left must not play behind whatever they went to.
                        if (zapping?.request == from.request) {
                            val request = selection.asPlayerRequest()
                            // The plate follows the large player, so that collapsing lands on the channel
                            // that is playing and not on the one the viewer opened it from.
                            if (livePreview == from.request) livePreview = request
                            zapping = Zapping(request, channel.id, from.list, moved = stepped)
                            overlay = Overlay.Play(request)
                        }
                    }
                    if (lockModel.isLocked(channel.id)) {
                        asking = ShellPin(PinPurpose.Play, begin)
                    } else {
                        begin()
                    }
                }
            }
        }
    }

    // Back out of the large player to the board, with the channel still playing.
    //
    // If the keys changed the channel, the board's well is told so now and not on every step: it names
    // the channel that was pressed in its list, and a well that said one channel while the picture was
    // another is the contradiction it is built to avoid. Told once, here, so a viewer who steps through
    // forty channels costs the board one guide read and not forty.
    val collapse: () -> Unit = {
        overlay = null
        zapping?.moved?.let { board.select(it.channel, it.position + 1) }
    }

    // **A live channel is watched once its picture shows, and recorded then** -- for the rail's Recently
    // watched entry. In the plate, in the large player, by the channel keys and from a search alike: each is
    // `zapping` here, and the player says when its picture is drawn. Not on the press: a channel that never
    // drew was not watched. Keyed by the channel, so one picture is one record, and a channel watched again
    // after another is recorded again and goes back to the top.
    val recentModel: RecentChannelsViewModel = hiltViewModel()
    val watchedId = watchedChannel(zapping?.channelId, zapping?.request?.url, pulse)
    LaunchedEffect(watchedId) { watchedId?.let(recentModel::watched) }

    // The compact player, or nothing.
    //
    // Withheld while the expanded shape is up -- see `livePreview`. Also withheld off the
    // Live board: a stream left decoding behind Settings is a stream nobody is watching.
    val playing = livePreview
    val preview: (@Composable () -> Unit)? =
        if (playing != null && dest == Dest.Live && overlay !is Overlay.Play) {
            {
                PlayerRoute(
                    request = playing,
                    mode = PlayerMode.Compact,
                    // The press that makes it the large player, carrying the same request
                    // -- which is what the view model recognises and declines to reopen.
                    // A locked channel's password first -- see `expandPreview`.
                    onExpand = { expandPreview(playing) },
                    onLeave = { livePreview = null },
                    // Asked as the plate goes away, and it has two questions to separate,
                    // not one. Leaving the board removes this composition in the same pass
                    // that changes `dest`, so `dest == Dest.Live` means the expanded shape
                    // is taking the stream over rather than nobody watching it.
                    //
                    // The second clause is what makes it safe for the request to be
                    // cleared while still on Live -- `LaunchedEffect(dest)` below does
                    // exactly that on the way out, and `dest` alone would have said
                    // "retain" and left a channel decoding with nothing drawing it.
                    retainOnDispose = { dest == Dest.Live && livePreview != null },
                    onPulseChange = { pulse = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            null
        }

    // And forgets it, so returning to Channels shows the board rather than resuming a
    // channel the viewer left some time ago. The engine has already been released by then
    // — see `retainOnDispose` above; this only clears what the plate would draw next.
    LaunchedEffect(dest) { if (dest != Dest.Live) livePreview = null }

    Box(Modifier.fillMaxSize()) {
        // The destination, unless something above it has taken the window. See
        // `Overlay.replacesShell`: this is the whole of the fix for Home reading through
        // the subscription flow, and it is a composition decision rather than a paint one.
        if (overlay?.replacesShell != true) {
            CastivioShell {
                when (dest) {
                    Dest.Home -> HomeScreen(
                        onSeeSection = { dest = it.destination },
                        // Unchanged: the empty state's button opens where the flow has
                        // always opened, on the address step most people came for.
                        onAddSource = { overlay = Overlay.AddSource() },
                        // The chooser, not the address step. "Add a playlist" is pressed
                        // from a menu of the playlists already here, by somebody who knows
                        // what one is — the question they are asking is *how*, and that
                        // screen is the one that asks it.
                        onAddPlaylist = { overlay = Overlay.AddSource(ActivationEntry.AddSource) },
                        onManageSources = { overlay = Overlay.AddSource(ActivationEntry.SavedSources) },
                        onSettings = { dest = Dest.Settings },
                        onLanguage = { overlay = Overlay.Language },
                        onLatest = { overlay = Overlay.Latest },
                        // No `onAbout`: "about" is this build's licence, its device and its
                        // version, and Settings already opens exactly that screen through
                        // `onShowLicence` below. Home's rail carried a second door to the
                        // same room, and the room it was taking space from was the rail
                        // itself -- six controls in the height of three cards left every
                        // one of them under the touch floor.
                        onExit = onExit,
                    )
                    // Live has its own board rather than the generic section screen: the
                    // approved reference is three columns whose third is a preview of the
                    // channel the remote is on, which the other three sections have no
                    // equivalent of. They keep `BrowseScreen` exactly as it was.
                    //
                    // **A section is entered once it is on the device, and not before.**
                    //
                    // Home loads it on its own card and opens it when the load has ended --
                    // or at once, with what it already holds, while a refresh runs behind
                    // it. So each screen composes over rows that are there, and no screen
                    // stands between Home and the section to say they are not. Live TV is
                    // entered sooner, by Home itself, with its first complete category: the
                    // board lists the categories that are complete, and the rest join it.
                    Dest.Live -> ChannelsScreen(
                        onPlay = playInPreview,
                        // Two presses on the name under the picture, reaching the very action
                        // the picture's own press reaches — `Overlay.Play` on the request that
                        // is already playing, which `PlayerViewModel.open` recognises and
                        // declines to reopen, so expanding moves the surface and leaves the
                        // decoder alone. Guarded here and not in the board: the request is what
                        // decides whether there is anything to expand, and the shell holds it.
                        onExpand = { livePreview?.let(expandPreview) },
                        // Read only while a player exists to be playing it: a stream that was
                        // playing when the plate went away has no one left to say it stopped.
                        pulse = pulse.takeIf { livePreview != null || overlay is Overlay.Play },
                        preview = preview,
                    )
                    // The films have a screen of their own: a sidebar of categories, a wall of posters and a
                    // page per film -- see `MoviesScreen`. Series and radio keep the generic section screen.
                    Dest.Movies -> MoviesScreen(
                        onOpenMovie = { overlay = Overlay.Movie(it) },
                    )
                    Dest.Series -> BrowseScreen(
                        section = CatalogSection.Series,
                        onPlay = play,
                        onOpenShow = { overlay = Overlay.Show(it) },
                        onSearch = { searchKind = null; dest = Dest.Search },
                    )
                    Dest.Radio -> BrowseScreen(
                        section = CatalogSection.Radio,
                        onPlay = play,
                        onOpenShow = { overlay = Overlay.Show(it) },
                        onSearch = { searchKind = null; dest = Dest.Search },
                    )
                    Dest.Favourites -> FavouritesScreen()
                    Dest.Library -> LibraryScreen(onOpenSection = { dest = it })
                    Dest.Search -> CatalogSearchScreen(onPlay = play, restrictTo = searchKind)
                    Dest.Settings -> SettingsScreen(
                        motionLevel = motionLevel,
                        onMotionLevel = onMotionLevel,
                        dark = dark,
                        onDark = onDark,
                        onShowStateBoard = { overlay = Overlay.StateBoard },
                        onShowLicence = { overlay = Overlay.Licence },
                        onChangePin = { asking = ShellPin(PinPurpose.Change) {} },
                        hiddenBouquets = hiddenBouquets,
                        hiddenChannels = hiddenChannels,
                        onRestoreBouquets = { hidingModel.restore(HiddenKind.BOUQUET) },
                        onRestoreChannels = { hidingModel.restore(HiddenKind.CHANNEL) },
                    )
                }
            }
        }

        when (val o = overlay) {
            is Overlay.Show -> ShowScreen(
                show = o.show,
                onPlay = play,
                onBack = { overlay = null },
            )
            // A film is played from its page, and the player's Back returns to that page -- the wall
            // underneath is still composed, so the viewer lands where they chose from.
            is Overlay.Movie -> MovieScreen(
                movie = o.movie,
                onPlay = { selection ->
                    zapping = null
                    overlay = Overlay.Play(selection.asPlayerRequest(), back = o)
                },
                onBack = { overlay = null },
            )
            // The same route, the same view model, the same engine -- drawn large. Back
            // out of it collapses to the plate on the board it was expanded from, with the
            // channel still playing, and only releases when there is no plate to go back
            // to: a film opened from Movies has nowhere to collapse to and leaves as it
            // always did.
            is Overlay.Play -> PlayerRoute(
                request = o.request,
                onLeave = { overlay = o.back },
                onCollapse = if (o.request == livePreview) collapse else null,
                onPulseChange = { pulse = it },
                // Only for a live channel that was started from somewhere to step from -- see `Zapping` --
                // and not while a password is being asked, whose keypad the remote's arrows walk.
                onChannelStep = if (zapping?.request == o.request && asking == null) stepChannel else null,
            )
            is Overlay.StateBoard -> StateBoardOverlay(onBack = { overlay = null })
            // Reached from a working app, so leaving means returning to
            // Settings. Reached from the gate it means leaving Castivio, and
            // that difference is the caller's -- the screen itself has no
            // opinion about where back goes.
            is Overlay.Licence -> LicenceWithLanguage(onLeave = { overlay = null })
            is Overlay.Latest -> NotReadyOverlay(onBack = { overlay = null })
            is Overlay.Language -> {
                val locale = LocalLocaleController.current
                LanguagePicker(
                    selected = locale.current.language,
                    onPick = { language ->
                        overlay = null
                        // No `recreate()`: the controller records the choice and the
                        // composition re-reads its strings in place. See its own note.
                        locale.choose(language)
                    },
                    onDismiss = { overlay = null },
                )
            }
            // Both seams mean the same thing from here. `onActivated` fires when an
            // import succeeds and `onExit` when the flow runs out of back stack, and
            // in a working app either one is "put me back where I was".
            //
            // `PlayerHost` for the same reason the gate wraps it: the flow lists the
            // device's own media and a press on one of those is a press on a file, not
            // on a subscription. Without it, that press would be swallowed.
            is Overlay.AddSource -> {
                val locale = LocalLocaleController.current
                PlayerHost { onPlayLocal ->
                    ActivationRoute(
                        onActivated = { overlay = null },
                        onExit = { overlay = null },
                        language = locale.current.language,
                        onLanguage = locale::choose,
                        onPlay = onPlayLocal,
                        entry = o.entry,
                    )
                }
            }
            null -> {}
        }

        // Above every overlay, because it is asked for from under one: a locked channel found by search
        // is pressed on the search screen, and the player has not opened yet.
        asking?.let { ask ->
            PinPrompt(
                purpose = ask.purpose,
                onGranted = {
                    asking = null
                    ask.granted()
                },
                onDismiss = { asking = null },
                model = lockModel,
            )
        }
    }
}

/** A password asked for from the shell, and what is done when it is given. */
private class ShellPin(val purpose: PinPurpose, val granted: () -> Unit)

/**
 * The live channel on screen, as far as the large player's channel keys are concerned.
 *
 * Held with the request it was started by: the keys step only while the player is showing exactly
 * that request, so a channel opened some other way -- a film, a file -- is never stepped from a
 * channel that was there before it.
 */
private class Zapping(
    val request: PlayerRequest,
    val channelId: String,
    /** The list it was pressed in, or null, which is its own bouquet. See [ChannelZapViewModel.step]. */
    val list: CatalogQuery?,
    /** Where the keys have stepped to, once they have: what the board's well is told when the player collapses. */
    val moved: SteppedChannel? = null,
)

/**
 * A catalogue press, as the player's request.
 *
 * `SERIES_EPISODE` rather than `VOD` for an episode: the engine reads it to decide
 * what "next" means, and a season that behaves like a single film is the bug this
 * distinction prevents.
 */
private fun CatalogSelection.asPlayerRequest() = PlayerRequest(
    url = url,
    title = title,
    kind = when {
        live -> MediaKind.LIVE
        subtitle?.startsWith('S') == true || subtitle?.startsWith('E') == true -> MediaKind.SERIES_EPISODE
        else -> MediaKind.VOD
    },
    subtitle = subtitle,
    channelNumber = channelNumber,
    epgChannelId = epgChannelId,
    catchUpHours = catchUpHours,
)

/** Which rail entry a section belongs to, so Home's "see all" lands somewhere real. */
private val CatalogSection.destination: Dest
    get() = when (this) {
        CatalogSection.Live -> Dest.Live
        CatalogSection.Movies -> Dest.Movies
        CatalogSection.Series -> Dest.Series
        CatalogSection.Radio -> Dest.Radio
    }

/**
 * A part of Castivio that is not ready in this build, said in the app's own words.
 *
 * `ScreenState.Failed(NOT_CONFIGURED)` and not a bespoke dialog: the scaffold already
 * owns that sentence — "Not available yet · This part of Castivio isn't ready in this
 * build" — and it already offers Back rather than a retry, because retrying is not
 * what fixes it. A second copy of the same message is a second copy to translate.
 */
@Composable
private fun NotReadyOverlay(onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(CastivioTheme.colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
    ) {
        ScreenScaffold<Unit>(
            state = ScreenState.Failed(AppError.NOT_CONFIGURED, retryable = false),
            onAction = onBack,
        ) { _, _ -> }
    }
}

// -------------------------------------------------------------------- sections

/**
 * Favourites, which is empty because nothing can be favourited yet.
 *
 * Drawn rather than hidden, and saying exactly that. A destination that vanishes when
 * it has no content teaches people the app is unreliable; one that explains itself
 * costs a sentence. The store and the paged reader for this already exist — what is
 * missing is the control that adds to it, and that is the next slice rather than
 * something to fake here.
 */
@Composable
private fun FavouritesScreen() = PlaceholderStage { frame ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(frame.edge),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        SectionHeader(title = "Favourites", count = 0)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Nothing favourited yet",
                detail = "Marking a channel or a film as a favourite arrives with the next " +
                    "update. Until then this list stays empty rather than showing you " +
                    "something you did not choose.",
                actionLabel = "OK",
                onAction = {},
            )
        }
    }
}

@Composable
private fun LibraryScreen(onOpenSection: (Dest) -> Unit) {
    val entries = listOf(
        Triple(Icons.Filled.Movie, stringResource(CatalogStrings.string.browse_movies), Dest.Movies),
        Triple(Icons.Filled.Tv, stringResource(CatalogStrings.string.browse_series), Dest.Series),
        Triple(Icons.Filled.Radio, stringResource(CatalogStrings.string.browse_radio), Dest.Radio),
        Triple(Icons.Filled.Favorite, "Favourites", Dest.Favourites),
    )
    PlaceholderStage { frame ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(frame.edge),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        SectionHeader(title = "Library")
        entries.forEach { (icon, label, target) ->
            SettingRow(icon = icon, label = label, onClick = { onOpenSection(target) })
        }
    }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsScreen(
    motionLevel: MotionLevel,
    onMotionLevel: (MotionLevel) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    onShowStateBoard: () -> Unit,
    onShowLicence: () -> Unit,
    onChangePin: () -> Unit,
    /** How many bouquets and channels the viewer has hidden from the channel board, and the way to bring them back. */
    hiddenBouquets: Int,
    hiddenChannels: Int,
    onRestoreBouquets: () -> Unit,
    onRestoreChannels: () -> Unit,
) {
    val colors = CastivioTheme.colors
    PlaceholderStage { frame ->
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(frame.edge),
        verticalArrangement = Arrangement.spacedBy(frame.bandTop),
    ) {
        SectionHeader(title = stringResource(R.string.settings_title))

        // Two chips and nothing else: the same control the motion level takes, so a
        // reader meets one pattern rather than a switch here and chips there.
        Text(
            stringResource(R.string.settings_appearance),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        Text(
            stringResource(R.string.settings_appearance_detail),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CategoryChipPlain(
                label = stringResource(R.string.settings_ground_deep),
                selected = dark,
                onClick = { onDark(true) },
            )
            CategoryChipPlain(
                label = stringResource(R.string.settings_ground_slate),
                selected = !dark,
                onClick = { onDark(false) },
            )
        }

        Text(
            stringResource(R.string.settings_motion),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        Text(
            stringResource(R.string.settings_motion_detail),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MotionLevel.entries.forEach { level ->
                CategoryChipPlain(
                    label = stringResource(level.label),
                    selected = level == motionLevel,
                    onClick = { onMotionLevel(level) },
                )
            }
        }

        // The channel lock's password, which is 0000 until it is changed here. Asked for the current
        // one first, then the new one twice -- see `PinPromptState`.
        Text(
            stringResource(CatalogStrings.string.settings_pin_section),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        Text(
            stringResource(CatalogStrings.string.settings_pin_detail),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        SettingRow(
            icon = Icons.Filled.Lock,
            label = stringResource(CatalogStrings.string.settings_pin_change),
            onClick = onChangePin,
        )

        // What was hidden from the channel board, and the way back. Hiding is local: nothing was deleted
        // from the subscription, so restoring only stops leaving the items out. Each row says how many it
        // would bring back and, with none hidden, is a line of information and nothing to press.
        Text(
            stringResource(CatalogStrings.string.settings_hidden_section),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        Text(
            stringResource(CatalogStrings.string.settings_hidden_detail),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        SettingRow(
            icon = Icons.Filled.Visibility,
            label = stringResource(CatalogStrings.string.settings_hidden_bouquets),
            value = hiddenBouquets.toString(),
            onClick = if (hiddenBouquets > 0) onRestoreBouquets else null,
        )
        SettingRow(
            icon = Icons.Filled.Visibility,
            label = stringResource(CatalogStrings.string.settings_hidden_channels),
            value = hiddenChannels.toString(),
            onClick = if (hiddenChannels > 0) onRestoreChannels else null,
        )

        Text(
            stringResource(R.string.settings_player),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        SettingRow(
            icon = Icons.Filled.PlayArrow,
            label = stringResource(R.string.settings_internal_player),
            value = stringResource(R.string.settings_player_default),
        )

        Text(
            stringResource(R.string.settings_design),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        SettingRow(
            icon = Icons.Filled.VideoLibrary,
            label = stringResource(R.string.settings_state_board),
            onClick = onShowStateBoard,
        )
        // Castivio's licence, which is not the provider's subscription. The two
        // are separate systems and this row says so by living under its own
        // heading rather than beside the playlist.
        Text(
            stringResource(R.string.settings_licence),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        SettingRow(
            icon = Icons.Filled.Settings,
            label = stringResource(LicenceStrings.string.licence_title),
            onClick = onShowLicence,
        )

        SettingRow(
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.settings_device_class),
            value = CastivioTheme.device.name,
        )
        SettingRow(
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.settings_version),
            value = "1.0.0",
        )
    }
    }
}

/**
 * What each animation level is called on screen.
 *
 * The chips used to be labelled `level.name.lowercase().replaceFirstChar { it.uppercase() }`
 * — the enum constant, title-cased. That is a programming detail arriving in front of a
 * user: untranslatable by construction, so the row stayed English on an Arabic device,
 * and `DISABLED` is not a word anybody chose for it. A `when` instead, which the
 * compiler makes exhaustive: a fourth level cannot be added without deciding what to
 * call it.
 */
private val MotionLevel.label: Int
    get() = when (this) {
        MotionLevel.FULL -> R.string.settings_motion_full
        MotionLevel.REDUCED -> R.string.settings_motion_reduced
        MotionLevel.DISABLED -> R.string.settings_motion_disabled
    }

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, RoundedCornerShape(Radius.md))
            .then(
                if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick)
                else Modifier,
            )
            .padding(Spacing.lg),
    ) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
        Text(label, style = CastivioType.bodyMedium, color = colors.onBackground, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = CastivioType.bodySmall, color = colors.onBackgroundMuted)
        }
    }
}

@Composable
private fun CategoryChipPlain(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Text(
        label,
        style = CastivioType.labelLarge,
        color = if (selected) colors.onBackground else colors.onBackgroundVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) colors.secondaryContainer.copy(alpha = 0.6f) else colors.glassFill)
            .border(
                1.dp,
                if (selected) colors.secondary else colors.glassBorderSoft,
                RoundedCornerShape(Radius.pill),
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

/**
 * The measured stage the shell's placeholder screens compose on.
 *
 * These three are UX-validation screens, not product ones, and they were the last
 * consumers of `DeviceClass.screenPadding` — a margin chosen by what kind of box this
 * is, 24dp or 48. They take the product's own stage now, from the surface each is
 * actually given, so a placeholder does not sit differently from the screen that will
 * replace it.
 *
 * `statusBarsPadding` stays here rather than in each screen: it is an inset the system
 * demands, and the margin inside it is the design's.
 */
@Composable
private fun PlaceholderStage(content: @Composable (CastivioMetrics) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        content(rememberMetrics(maxWidth, maxHeight))
    }
}

// ------------------------------------------------------------------ overlays

/**
 * The four readings of an item's history, in one place.
 *
 * A design surface rather than a product one, reached from Settings, and it is the one
 * screen in the shell that is allowed to draw cards with no data behind them: what it
 * is showing *is* the state language.
 */
@Composable
private fun StateBoardOverlay(onBack: () -> Unit) {
    val colors = CastivioTheme.colors
    BackHandler(onBack = onBack)
    // The backdrop is on the box rather than the column so it still runs under the
    // status bar, and the margin inside it is measured rather than looked up: this
    // was the application's last reader of `DeviceClass.screenPadding`, a margin
    // chosen by what kind of box this is.
    BoxWithConstraints(
        Modifier
            // An overlay over a destination, so it carries the backdrop rather
            // than the flat colour that sits beneath it.
            .fillMaxSize()
            .castivioBackdrop(),
    ) {
    val m = rememberMetrics(maxWidth, maxHeight)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = m.edge, vertical = m.stageTop),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("State language", style = CastivioType.headlineSmall, color = colors.onBackground)
        Text(
            "Four readings of an item's history, one grammar, one place. Switch Motion to " +
                "Disabled in Settings — all four still read.",
            style = CastivioType.bodyMedium,
            color = colors.onBackgroundVariant,
        )
        val samples = listOf(
            "Not started" to WatchState.None,
            "In progress" to WatchState.InProgress(0.58f),
            "Recently watched" to WatchState.Watched,
            "Playing now" to WatchState.Playing,
        )
        samples.forEach { (label, state) ->
            MediaCard(
                title = label,
                shape = CardShape.Landscape,
                width = 240.dp,
                artworkSeed = 2,
                watchState = state,
                badge = when (state) {
                    is WatchState.Playing -> {
                        { NowPlayingBadge() }
                    }
                    is WatchState.Watched -> {
                        { WatchedTag("Watched") }
                    }
                    else -> null
                },
                onClick = onBack,
            )
        }
        IconLabel(Icons.Filled.LiveTv, "Aqua is now · violet is navigation · neutral is the past")
    }
    }
}
