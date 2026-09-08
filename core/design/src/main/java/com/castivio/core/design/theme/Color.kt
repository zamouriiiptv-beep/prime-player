package com.castivio.core.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Castivio colour palette.
 *
 * Identity: a deep navy "void" base lit by an aurora of azure and violet.
 * Nothing is pure black and nothing is pure white — every surface carries a
 * trace of the brand hue, which is what separates Castivio from the flat
 * grey/black of typical IPTV players.
 *
 * Raw values live here. Screens should read semantic tokens from
 * [CastivioColors] via `CastivioTheme.colors` rather than these constants.
 */
object Palette {

    // -- Base: deep navy, darkest to lightest -----------------------------
    /**
     * Below [Void], and the reason the identity screen has any depth.
     *
     * Every surface used to sit inside a narrow band — ground near #241E50, glass
     * a few percent above it, text at 95% — so nothing was dark and nothing was
     * bright, and a composition with no black and no white reads as fog however
     * carefully it is arranged. This is the black end that band was missing.
     */
    val Ink = Color(0xFF05040E)
    val Void = Color(0xFF08071A)
    val Abyss = Color(0xFF0D0B22)
    val Deep = Color(0xFF141031)
    val Slate = Color(0xFF1C1840)
    val Haze = Color(0xFF272253)

    // -- Brand: azure ------------------------------------------------------
    val Azure10 = Color(0xFF0A1E3D)
    val Azure40 = Color(0xFF2E6BFF)
    val Azure50 = Color(0xFF4C9BFF)
    val Azure60 = Color(0xFF6FB2FF)
    /** Between [Azure60] and [Azure80]: a numeral that has to carry inside a chip. */
    val Azure70 = Color(0xFF8FC0FF)
    val Azure80 = Color(0xFFB4D6FF)

    // -- Brand: violet -----------------------------------------------------
    val Violet10 = Color(0xFF1B1038)
    val Violet40 = Color(0xFF6E4BD8)
    val Violet50 = Color(0xFF9B6BFF)
    val Violet60 = Color(0xFFB694FF)
    val Violet80 = Color(0xFFDCCBFF)

    // -- Accent: used sparingly, for the play/live identity ----------------
    val Ember = Color(0xFFFF3B5C)
    val Aqua = Color(0xFF2FBF9F)
    val Amber = Color(0xFFFFB020)

    // -- Neutrals: tinted toward the brand, never pure -----------------------
    val White = Color(0xFFFFFFFF)
    val Mist = Color(0xFFF2F2F8)
    val Silver = Color(0xFFC9C9DA)

    /**
     * The ink every description is set in — a step lighter than [Silver] and carrying
     * a little of the violet the product is built on.
     *
     * Its own entry rather than a change to [Silver], because Silver is
     * `onBackgroundVariant` and that role dresses more than descriptions: a tick, an
     * icon tint, a status word. Moving Silver to move the descriptions would have
     * moved all of them.
     */
    val Quartz = Color(0xFFD6D3E8)
    val Muted = Color(0xFFA6A6BF)
    val Faint = Color(0xFF6E6E8A)

    // -- Glass: translucent white layers used on top of the base -----------
    val GlassHigh = Color(0x1FFFFFFF)
    val GlassMid = Color(0x14FFFFFF)
    val GlassLow = Color(0x0AFFFFFF)
    val GlassEdge = Color(0x3DFFFFFF)
    val GlassEdgeSoft = Color(0x14FFFFFF)

    // -- Panes: a field card, which is glass drawn the other way round -------
    //
    // `GlassLow` and its family are *lighter* than the ground, which is what a
    // sheet of glass over a photograph looks like. A field on a dark screen is
    // the opposite: a pane sunk into the surface, darker than what surrounds it,
    // found by its lit edge rather than by its fill. Drawing the identity fields
    // as the lighter form is what removed the only dark the middle of the
    // activation screen had, and is why it read washed out.
    val PaneHigh = Color(0xCC241D5C)
    val PaneLow = Color(0xBD0F0B2C)

    // -- Edges: one for a field, one for a quiet surface, one for the focus ---
    //
    // Three, not four, and not one per component. Four surfaces that mean the
    // same thing were being drawn four slightly different ways — .30 here, .26
    // there, .22 on the footer — which is the decay a token exists to stop.
    val EdgeCard = Color(0x477E8CFF)
    val EdgeQuiet = Color(0x33A0A3D7)
    val EdgeAccent = Color(0x669E74FF)

    // -- Status -------------------------------------------------------------
    /* ------------------------------------------------------------- the light side
     *
     * The same identity in daylight, not a second identity. Every one of these is the
     * dark ground's own hue carried up into the top of the ramp rather than a neutral
     * grey: the page still runs violet at one corner and azure at the other, because
     * that diagonal is what Castivio looks like and it does not belong to the dark.
     *
     * The accents are untouched. Azure50, Violet50, Amber, Aqua and Ember are the brand
     * and they read on both grounds; re-picking them for light would be two brands. */

    /** The page, and the diagonal it runs: violet at one corner, azure at the other. */
    val Cloud = Color(0xFFF6F4FC)
    val CloudHigh = Color(0xFFFBFAFE)
    val CloudLow = Color(0xFFEDEAF8)

    /**
     * A surface, and it is a surface rather than a film.
     *
     * The dark theme's glass is white at four per cent over near-black, and it
     * separates. The same recipe inverted -- black at four per cent over near-white --
     * does not, and that is precisely why the first light attempt came out washed:
     * the cards were there and could not be seen. So a card on this ground takes its
     * own value and a hairline, which is what a surface is.
     */
    val Surface = Color(0xFFF1EFF9)
    val Paper = Color(0xFFFFFFFF)

    /** The three inks, at 16.4:1, 6.0:1 and 4.8:1 on [Cloud]. */
    val Night = Color(0xFF17152B)
    val Dusk = Color(0xFF5F5A72)
    val Ash = Color(0xFF6E6885)

    /**
     * The identity in daylight: the same four hues, further down their own ramps.
     *
     * Not different colours -- violet is still violet -- but the step that *reads*. A
     * hue picked to glow on near-black is a pastel on near-white, which is the second
     * thing the first attempt got wrong. Each carries an ink as well, because an icon
     * drawn in its disc's own hue on a pale tint of that hue is invisible.
     */
    val VioletLit = Color(0xFF8B5CF6);  val VioletInk = Color(0xFF6D28D9)
    val AzureLit = Color(0xFF4F8FEA);   val AzureInk = Color(0xFF1D63C4)
    val GreenLit = Color(0xFF35B982);   val GreenInk = Color(0xFF0F7050)
    val AmberLit = Color(0xFFE5A21A);   val AmberInk = Color(0xFF8F6205)

    /** Edges on a light ground: visible, and soft. */
    val EdgeDay = Color(0xFFC8C3D8)
    val EdgeDayStrong = Color(0xFFB3ACCB)

    /** A card's ground and its lift, as fills rather than as films. */
    val SurfaceHigh = Color(0xF2F4F2FB)
    val SurfaceLow = Color(0xF2ECEAF6)

    val Success = Color(0xFF3DD68C)
    val Warning = Amber
    val Danger = Color(0xFFFF5A5A)
}

/**
 * Semantic colour tokens. Screens use these names, never raw palette values,
 * so the whole app re-skins from one place.
 */
@Suppress("LongParameterList")
class CastivioColors(
    /**
     * Which of the two grounds this is.
     *
     * A flag rather than two subclasses, and it is read in exactly one place: the
     * derived brushes below, which are the only tokens whose *recipe* changes rather
     * than their values. No screen reads it, and none should -- a screen that asks
     * whether it is light is a screen with two designs in it, which is the thing this
     * file exists to prevent.
     */
    val isLight: Boolean,

    // Backgrounds
    val background: Color,
    val backgroundElevated: Color,
    val scrim: Color,

    // Content
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val onBackgroundMuted: Color,

    /**
     * The ink for a description: the secondary prose that explains the thing beside
     * it, wherever it appears.
     *
     * A role of its own so the answer to "what colour is a description" lives in one
     * place and can be changed there. It was `onBackgroundVariant` by hand on six
     * screens, which is the same fact stated six times and therefore the same fact
     * one edit away from being two.
     */
    val description: Color,

    // Brand
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val accent: Color,

    // Glass surfaces
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassBorder: Color,
    val glassBorderSoft: Color,

    // Interaction
    val focusRing: Color,
    val focusGlow: Color,
    val divider: Color,

    /**
     * A choice that is currently in force: the language Castivio is in, the
     * quality that is playing, the sort that is applied.
     *
     * Deliberately not the same channel as focus. On a television both are true
     * of a row at once -- which option is active, and where the remote is -- and
     * a design that carries them on one channel makes a focused row look chosen.
     * Focus is a ring drawn outside the surface; selection is the surface.
     *
     * Never the only cue either. Whatever this fills is also expected to carry a
     * mark and a heavier weight, so the state survives a viewer who cannot
     * separate the fill from the ground.
     */
    val selectedFill: Color,
    val selectedBorder: Color,

    /**
     * The same violet, thrown a little further.
     *
     * A spot colour for the shadow under a focused surface that is already
     * filled and bordered in [selectedFill] and [selectedBorder] — the plan
     * cards, and nothing else so far. It is not a second hue: it is Violet50 at
     * a third alpha, so a focused card reads as the same colour getting nearer
     * rather than as a new colour arriving.
     *
     * Distinct from [focusGlow], which is azure and belongs to the controls
     * whose focus is azure. Two glows because there are two focus colours, not
     * because two looked nice.
     */
    val selectedGlow: Color,

    /**
     * "Now". The one meaning aqua has anywhere in the product: a live programme,
     * a playing stream, the moving meter. Never used for navigation or status.
     */
    val live: Color,

    // Status
    val success: Color,
    val warning: Color,
    val danger: Color,

    /**
     * Deterministic tints for a logo or avatar placeholder when a provider ships
     * no artwork. A screen picks one by a stable index (a channel's id), so the
     * same channel is always the same colour — and the literals stay in here
     * rather than leaking a `Color(0x…)` into a feature.
     */
    val logoTints: List<Color>,

    /* ------------------------------------------------------------- the backdrop
     *
     * The signature ground, as tokens rather than as literals inside the canvas that
     * draws it. It was three palette entries and two glow colours named directly in
     * `Backdrop.kt` -- fine while there is one theme, and the whole problem the moment
     * there are two. The dark values here are the same numbers that file used.
     */

    /** The diagonal, corner to corner: violet at the start, azure at the end. */
    val backdropStops: List<Color>,

    /** The two aurora glows -- the lower-leading one and the upper-trailing one. */
    val backdropWarmGlow: Color,
    val backdropCoolGlow: Color,

    /** The drifting motes. */
    val backdropMote: Color,

    /* ----------------------------------------------------------------- the edges
     *
     * Three hairlines that were `Palette.EdgeQuiet`, `EdgeCard` and `EdgeAccent` at
     * eleven call sites. They are all "a line drawn on the ground", so on a light
     * ground they are all dark -- which a literal cannot express.
     */
    val edgeQuiet: Color,
    val edgeCard: Color,
    val edgeAccent: Color,

    /**
     * The ink for the loudest thing in its box: a screen's name, a card's name, a key.
     *
     * It was `Palette.White` at seven call sites, which is right on a dark ground and
     * unreadable on a light one. [onBackground] is the ordinary reading ink; this is
     * the step above it.
     */
    val onBackgroundStrong: Color,

    /** The ground under a card marked as the default choice. */
    val featuredFill: Color,

    /** What a scrolling row bleeds into at its edge, so it reads as "continues". */
    val rowEdgeFade: Color,

    /* --------------------------------------------------------------- the four hues
     *
     * One per option on a chooser, so four cards tell themselves apart from across a
     * room before a word of them is read. Roles rather than palette entries, because
     * the *step* that reads is not the same on the two grounds: a hue picked to glow
     * on near-black is a pastel on near-white.
     *
     * The ink is the second half of the same problem. On the dark ground the glyph is
     * its disc's own hue and that is legible, because the disc is a dark tint. On the
     * light ground the disc is a pale tint of the hue and the same glyph disappears
     * into it, so the ink is the hue carried further down its ramp.
     */
    val hueAzure: Color,
    val hueViolet: Color,
    val hueGreen: Color,
    val hueAmber: Color,

    /** How much of a hue a disc keeps: at the light, at the foot, in its edge, in its ink. */
    val discTop: Float,
    val discFoot: Float,
    val discEdge: Float,
    val discInk: Float,

    /**
     * How strongly the backdrop's two aurora glows are laid on.
     *
     * The same two circles in the same two corners at the same radii on both grounds —
     * only the strength is asked for again, and it is the single number that decides
     * whether a light page reads as tinted or as stained. Thirty per cent is a bloom
     * on black and a blot on white.
     */
    val backdropGlowWarm: Float,
    val backdropGlowCool: Float,
) {

    /**
     * A disc's fill, its edge and its glyph, from one hue.
     *
     * Here rather than at the two call sites, because "how a hue becomes a disc" is a
     * palette decision and it is a *different* decision on each ground: a radial
     * fall-off from a strong tint reads as a lit object on black, and as a smudge on
     * white, where a flat tint with a firm edge is what reads.
     */
    fun discFill(hue: Color): Brush = Brush.radialGradient(
        listOf(hue.copy(alpha = discTop), hue.copy(alpha = discFoot)),
    )

    fun discBorder(hue: Color): Color = hue.copy(alpha = discEdge)

    /** The glyph: the hue itself on dark, and the hue pulled toward the ink on light. */
    fun discGlyph(hue: Color): Color =
        if (isLight) lerp(hue, onBackground, discInk) else hue

    /** The Castivio signature gradient — background washes and hero fills. */
    val auroraBrush: Brush
        get() = Brush.linearGradient(backdropStops)

    /** Primary action fill. Three stops so the ramp stays smooth on large buttons. */
    val primaryBrush: Brush
        get() = Brush.horizontalGradient(
            listOf(Palette.Azure50, Palette.Azure40, Color(0xFF2C67F0)),
        )

    /** Brand mark / badge fill: violet into azure. */
    val brandBrush: Brush
        get() = Brush.linearGradient(listOf(Palette.Violet40, Palette.Azure40))

    /**
     * The one filled control on a screen, and the only place this ramp appears.
     *
     * Four stops rather than [primaryBrush]'s three, and it starts a good way
     * further into the violet: this is a call to action the width of a column,
     * and a two-stop blue across that distance reads as a bar rather than as a
     * button. Absolute left-to-right in both directions on purpose — the ramp is
     * the brand's, like the wordmark, and a signature that reverses per locale is
     * two signatures.
     */
    val ctaBrush: Brush
        get() = Brush.horizontalGradient(
            0.00f to Color(0xFF8B3FF5),
            0.32f to Color(0xFF6A45EE),
            0.72f to Color(0xFF3D63F5),
            1.00f to Palette.Azure40,
        )

    /**
     * A field card: a pane sunk into the surface, not glass laid over it.
     *
     * Vertical, lighter at the top, so the lit rim along its upper edge reads as
     * a highlight on a solid rather than as a border drawn round a hole.
     */
    val paneBrush: Brush
        get() = if (isLight) {
            Brush.verticalGradient(listOf(Palette.Paper, Palette.Cloud))
        } else {
            Brush.verticalGradient(listOf(Palette.PaneHigh, Palette.PaneLow))
        }

    /**
     * A chip that carries information rather than a control.
     *
     * The trial count and the language button were the same neutral glass, which
     * said they were the same kind of thing. One is a fact the user needs and the
     * other is a control they may never touch, so the fact takes a tint of the
     * brand and the control keeps the glass. A step apart, and still nowhere near
     * the filled button — a chip that competes with a call to action is a chip
     * that has been given the wrong job.
     */
    val trialChipBrush: Brush
        get() = if (isLight) {
            Brush.verticalGradient(listOf(Color(0x2E6A4BD8), Color(0x1F4C9BFF)))
        } else {
            Brush.verticalGradient(listOf(Color(0x47564AD6), Color(0x38281E6E)))
        }

    /**
     * The one lit container on the identity screen.
     *
     * Diagonal rather than vertical, and violet at the corner the light comes
     * from, so the panel reads as catching the ground's own bloom instead of
     * carrying a colour of its own.
     */
    val codePanelBrush: Brush
        get() = if (isLight) {
            Brush.linearGradient(
                listOf(Color(0x2E6A4BD8), Color(0x1F3D63F5), Color(0x14171233)),
            )
        } else {
            Brush.linearGradient(
                listOf(Color(0x8F2E1A74), Color(0x94100B2E), Color(0x9909071C)),
            )
        }

    /** The disc behind an information glyph: a tint, not a button. */
    val infoMarkFill: Color get() = Palette.Violet40.copy(alpha = if (isLight) 0.14f else 0.20f)

    /** Vertical sheen that gives a glass panel its lit top edge. */
    val glassFillBrush: Brush
        get() = Brush.verticalGradient(listOf(glassFillStrong, glassFill))

    /** Border that fades from lit (top) to invisible (bottom). */
    val glassBorderBrush: Brush
        get() = Brush.verticalGradient(listOf(glassBorder, glassBorderSoft))

    /* --------------------------------------------------------------- over video
     *
     * The player is the one screen with no glass container, because the picture *is*
     * the screen. That breaks the rest of the palette: `glassFill` is 7.8% white, which
     * is invisible over a bright frame and a grey smear over a dark one, and a caption
     * on it is unreadable half the time.
     *
     * So a bar over video takes a scrim instead — the product's own void, fading out —
     * and only where a control actually sits. A full-screen veil would dim the whole
     * film for the sake of two rows of type, which is the mistake most players make.
     */

    /** Behind the title row. Strong at the edge, gone by 38% of the height. */
    val videoScrimTop: Brush
        get() = Brush.verticalGradient(
            listOf(Palette.Void.copy(alpha = 0.86f), Color.Transparent),
        )

    /** Behind the timeline and tools. Deeper, because it carries two rows and a strip. */
    val videoScrimBottom: Brush
        get() = Brush.verticalGradient(
            listOf(Color.Transparent, Palette.Void.copy(alpha = 0.90f)),
        )

    /**
     * A panel that has to be read *over* moving video: an error card, the statistics,
     * a sheet. Nearly opaque, because a bitrate that flickers with the frame behind it
     * is not a figure anybody can read.
     */
    val overVideo: Color get() = Palette.Void.copy(alpha = 0.86f)

    /** The same surface at the weight a transient chip takes. */
    val overVideoSoft: Color get() = Palette.Void.copy(alpha = 0.72f)

    /* ------------------------------------------------------------- subtitles */

    /**
     * The two inks a caption may be written in, and why there are only two.
     *
     * White is what broadcast and cinema use, and it is right over almost everything.
     * Amber is the one alternative that earns its place: it separates from white
     * clothing, snow, paper and the blown-out skies that defeat white, and it is the
     * colour television captions have used for that reason since teletext. A palette of
     * eight would be eight ways to make a caption harder to read.
     */
    val subtitleInk: Color get() = Palette.White
    val subtitleInkWarm: Color get() = Palette.Amber

    /**
     * What sits behind the words. Three weights, and none of them is a full-width bar.
     *
     * A box that spans the screen hides more film than the words do. These are drawn
     * behind the text and nothing else, so what is covered is what is being read.
     */
    val subtitleBackdropSoft: Color get() = Palette.Void.copy(alpha = 0.45f)
    val subtitleBackdropSolid: Color get() = Palette.Void.copy(alpha = 0.88f)

    /**
     * The outline a caption keeps when the backdrop is switched off.
     *
     * Not decoration: white text on a white frame is invisible, and a viewer who turned
     * the backdrop off asked for less obstruction rather than for unreadable words. A
     * dark shadow behind the glyphs costs nothing of the picture.
     */
    val subtitleShadow: Color get() = Palette.Void.copy(alpha = 0.95f)
}

/**
 * The dark theme, and the reference.
 *
 * Every value here is the value the product already shipped. The new arguments at the
 * foot are not new colours: they are the literals that used to sit inside `Backdrop.kt`
 * and at eleven `Palette.` call sites in the features, moved to where a second theme
 * can answer them differently. Dark is byte-identical to what it was.
 */
fun castivioDarkColors() = CastivioColors(
    isLight = false,
    background = Palette.Void,
    backgroundElevated = Palette.Deep,
    scrim = Color(0xB3000000),

    onBackground = Palette.White,
    onBackgroundVariant = Palette.Silver,
    onBackgroundMuted = Palette.Muted,
    description = Palette.Quartz,

    primary = Palette.Azure50,
    onPrimary = Palette.White,
    primaryContainer = Palette.Azure10,
    secondary = Palette.Violet50,
    onSecondary = Palette.White,
    secondaryContainer = Palette.Violet10,
    accent = Palette.Ember,

    glassFill = Palette.GlassLow,
    glassFillStrong = Palette.GlassMid,
    glassBorder = Palette.GlassEdge,
    glassBorderSoft = Palette.GlassEdgeSoft,

    focusRing = Palette.Azure60,
    focusGlow = Palette.Azure40.copy(alpha = 0.45f),
    divider = Color(0x1FFFFFFF),
    selectedFill = Palette.Violet50.copy(alpha = 0.16f),
    selectedBorder = Palette.Violet50.copy(alpha = 0.34f),
    selectedGlow = Palette.Violet50.copy(alpha = 0.38f),
    live = Palette.Aqua,

    success = Palette.Success,
    warning = Palette.Warning,
    danger = Palette.Danger,

    logoTints = listOf(
        Palette.Azure40,
        Palette.Violet40,
        Palette.Aqua,
        Palette.Amber,
        Palette.Ember,
        Palette.Azure50,
        Palette.Violet50,
    ),

    backdropStops = listOf(Palette.Deep, Palette.Violet10, Palette.Azure10),
    backdropWarmGlow = Palette.Violet40,
    backdropCoolGlow = Palette.Azure40,
    backdropMote = Palette.Azure80,

    edgeQuiet = Palette.EdgeQuiet,
    edgeCard = Palette.EdgeCard,
    edgeAccent = Palette.EdgeAccent,
    onBackgroundStrong = Palette.White,
    featuredFill = Palette.Violet10,
    rowEdgeFade = Palette.Void,

    hueAzure = Palette.Azure50,
    hueViolet = Palette.Violet50,
    hueGreen = Palette.Success,
    hueAmber = Palette.Amber,

    discTop = 0.34f,
    discFoot = 0.08f,
    discEdge = 0.46f,
    // Unused on this ground: the glyph is its own hue. Stated anyway so the two
    // palettes answer the same set and neither can be read as "not applicable".
    discInk = 0f,

    backdropGlowWarm = 0.30f,
    backdropGlowCool = 0.26f,
)

/**
 * The light theme: the same product in daylight.
 *
 * Built by answering each role again rather than by inverting the dark set, because
 * inversion produces a photograph negative and not a design — the brand hues would come
 * out as their complements, and Castivio would be orange.
 *
 * So the accents are **identical**: azure is still azure, violet still violet, amber,
 * aqua and ember unchanged. What moves is the ground and the ink on it, and the glass,
 * which flips from a light film over dark to a dark film over light. The backdrop keeps
 * its diagonal and its two glows in the same corners at the same radii — only the stops
 * are the top of the ramp instead of the bottom, so the page still runs violet at one
 * corner and azure at the other. It reads as the same screen with the lights on, which
 * is the whole requirement.
 */
fun castivioLightColors() = CastivioColors(
    isLight = true,
    background = Palette.Cloud,
    backgroundElevated = Palette.Paper,
    // Cooler and lighter than the dark scrim: pure black at seventy per cent over a
    // bright page dims it by turning it grey, which is not the same as dimming it.
    scrim = Color(0x99171233),

    onBackground = Palette.Night,
    onBackgroundVariant = Palette.Dusk,
    onBackgroundMuted = Palette.Ash,
    description = Palette.Dusk,

    primary = Palette.AzureLit,
    onPrimary = Palette.White,
    primaryContainer = Color(0xFFE6EEFC),
    secondary = Palette.VioletLit,
    onSecondary = Palette.White,
    secondaryContainer = Color(0xFFEFEAFB),
    accent = Palette.Ember,

    // A surface, not a film. `glassFillBrush` reads these as its two stops, so a card
    // comes out at its own value with a lit upper edge rather than at four per cent of
    // an ink nobody can see.
    glassFill = Palette.SurfaceLow,
    glassFillStrong = Palette.SurfaceHigh,
    glassBorder = Palette.EdgeDay,
    glassBorderSoft = Color(0x66C8C3D8),

    // Azure40 rather than the lighter step: a ring has to separate from a pale ground,
    // and the step that reads on the void disappears on the cloud.
    focusRing = Palette.Azure40,
    focusGlow = Palette.Azure40.copy(alpha = 0.30f),
    divider = Color(0x1F171233),
    selectedFill = Palette.VioletLit.copy(alpha = 0.10f),
    selectedBorder = Palette.VioletLit.copy(alpha = 0.42f),
    selectedGlow = Palette.VioletInk.copy(alpha = 0.22f),
    live = Palette.Aqua,

    success = Palette.Success,
    warning = Palette.Warning,
    danger = Palette.Danger,

    logoTints = listOf(
        Palette.Azure40,
        Palette.Violet40,
        Palette.Aqua,
        Palette.Amber,
        Palette.Ember,
        Palette.Azure50,
        Palette.Violet50,
    ),

    backdropStops = listOf(Palette.CloudHigh, Palette.Cloud, Palette.CloudLow),
    backdropWarmGlow = Palette.VioletLit,
    backdropCoolGlow = Palette.AzureLit,
    backdropMote = Palette.VioletLit,

    edgeQuiet = Palette.EdgeDay,
    edgeCard = Palette.EdgeDayStrong,
    edgeAccent = Palette.VioletLit.copy(alpha = 0.42f),
    onBackgroundStrong = Palette.Night,
    // White, not a lavender: the default card is the one surface that has to lift off
    // a page which is itself lavender, and a tint of the ground cannot do that.
    featuredFill = Palette.Paper,
    rowEdgeFade = Palette.Cloud,

    hueAzure = Palette.AzureLit,
    hueViolet = Palette.VioletLit,
    hueGreen = Palette.GreenLit,
    hueAmber = Palette.AmberLit,

    // Flat and firm rather than a fall-off: on a light ground the dark theme's radial
    // ramp reads as a smudge, and a level tint inside a definite edge reads as a disc.
    discTop = 0.16f,
    discFoot = 0.13f,
    discEdge = 0.42f,
    // The glyph, pulled a little over a third of the way toward the ink -- measured at
    // 5.4, 4.5, 4.1 and 3.9 to one against its own disc, all past the 3:1 that applies
    // to a glyph rather than to a word.
    discInk = 0.38f,

    // A tint of the page rather than a stain on it. The dark theme's own numbers here
    // are what made the first light attempt look saturated.
    backdropGlowWarm = 0.13f,
    backdropGlowCool = 0.11f,
)

/**
 * A placeholder poster fill, chosen deterministically from [index].
 *
 * Real artwork replaces this the moment it loads; until then a card should read
 * as the surface it will become rather than as a grey hole. Kept here so the
 * gradient stops are palette values, not literals in a feature.
 */
fun posterPlaceholderBrush(index: Int): Brush {
    val pairs = listOf(
        Palette.Violet10 to Palette.Violet40,
        Palette.Azure10 to Palette.Azure40,
        Palette.Slate to Palette.Aqua,
        Palette.Deep to Palette.Ember,
        Palette.Haze to Palette.Amber,
        Palette.Abyss to Palette.Violet50,
    )
    val (top, bottom) = pairs[(index % pairs.size + pairs.size) % pairs.size]
    return Brush.linearGradient(listOf(top, bottom))
}

/**
 * The soft edge-fade a scrollable row bleeds into, so it reads as "continues".
 *
 * Reads the theme rather than naming `Palette.Void`: what a row fades into is the page
 * it is on, and there are two pages now. A composable rather than a bare `val` for the
 * same reason -- the answer depends on the tree it is asked in.
 */
val rowEdgeFadeColor: Color
    @Composable @ReadOnlyComposable get() = CastivioTheme.colors.rowEdgeFade
