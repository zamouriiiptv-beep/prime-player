package com.castivio.feature.player

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How captions look, as four choices a person can make in four seconds.
 *
 * ## Why presets and not sliders
 *
 * Every one of these could be a continuous control — a point size, a colour wheel, an
 * opacity, a vertical offset — and every one of them would be worse. A viewer adjusting
 * subtitles is answering one question: *can I read that, from here, without losing the
 * picture*. Four steps they can try in a second answer it; a number they have to nudge
 * while a film plays does not, and the settings screen that results is one nobody opens
 * twice.
 *
 * It is also what makes the choices safe. A slider can produce white-on-white at 8 points
 * behind a transparent box. These combinations were each drawn against a bright frame and
 * a dark one, and there is no way to reach an unreadable one.
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.Medium,
    val ink: SubtitleInk = SubtitleInk.White,
    val backdrop: SubtitleBackdrop = SubtitleBackdrop.Shadow,
    val place: SubtitlePlace = SubtitlePlace.Bottom,
)

/**
 * Four steps, and the middle one is the default.
 *
 * [Huge] exists for a television across a room, which is the frame this product is used on
 * that the developer is least likely to be sitting at.
 */
enum class SubtitleSize { Small, Medium, Large, Huge }

/**
 * White, or amber.
 *
 * Two rather than eight. White is what broadcast and cinema use and it is right over
 * almost everything; amber is the one alternative that earns its place, because it
 * separates from the white clothing, snow, paper and blown-out skies that defeat white —
 * which is why teletext captions have used it for the same reason for forty years.
 */
enum class SubtitleInk { White, Amber }

/**
 * What sits behind the words.
 *
 * [Shadow] is the default rather than [None], because "no backdrop" over an unknown film
 * is white text on a white frame sooner or later. A shadow costs nothing of the picture
 * and cannot fail that way. [Solid] is for the viewer who wants the words legible above
 * everything else and accepts a box over the film to get it.
 */
enum class SubtitleBackdrop { None, Shadow, Soft, Solid }

/**
 * How high the caption sits.
 *
 * [Bottom] is the convention. [Raised] is for the films whose own burnt-in text lives
 * along the bottom edge — a second line of words over the first is the commonest reason a
 * viewer reaches for this setting at all. [Top] is for the rest: a picture whose lower
 * third is where everything happens.
 */
enum class SubtitlePlace { Bottom, Raised, Top }

/**
 * Where the choice lives between films.
 *
 * An interface in the feature rather than a type from `:data:preferences`, for the reason
 * [ProgrammeSource] gives: a narrow seam the player can be tested against, and no route
 * from this screen to a store that can be asked for anything else.
 *
 * Synchronous, and `SharedPreferences` behind it rather than DataStore. The style is read
 * once when the player opens and written when a person taps a row — four values, no
 * migration, no observation across processes. `LanguageStore` made the same call for the
 * same reason and says so at length.
 */
interface SubtitleStyleStore {
    fun read(): SubtitleStyle
    fun write(style: SubtitleStyle)
}

/**
 * The stored version.
 *
 * Each value is written as its enum name, and an unrecognised one falls back to the
 * default rather than throwing. That is not defensiveness for its own sake: renaming a
 * constant is an ordinary refactor, and a player that crashed on opening a film because a
 * preference from a previous version said `Medium` where the code now says `Normal` would
 * be a crash with no way out but clearing the app's data.
 */
@Singleton
class StoredSubtitleStyle @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SubtitleStyleStore {

    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): SubtitleStyle {
        val stored = prefs
        val fallback = SubtitleStyle()
        return SubtitleStyle(
            size = stored.enum(SIZE, SubtitleSize.entries, fallback.size),
            ink = stored.enum(INK, SubtitleInk.entries, fallback.ink),
            backdrop = stored.enum(BACKDROP, SubtitleBackdrop.entries, fallback.backdrop),
            place = stored.enum(PLACE, SubtitlePlace.entries, fallback.place),
        )
    }

    override fun write(style: SubtitleStyle) {
        prefs.edit()
            .putString(SIZE, style.size.name)
            .putString(INK, style.ink.name)
            .putString(BACKDROP, style.backdrop.name)
            .putString(PLACE, style.place.name)
            .apply()
    }

    private fun <T : Enum<T>> android.content.SharedPreferences.enum(
        key: String,
        values: List<T>,
        fallback: T,
    ): T {
        val name = getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == name } ?: fallback
    }

    private companion object {
        const val FILE = "castivio.subtitles"
        const val SIZE = "size"
        const val INK = "ink"
        const val BACKDROP = "backdrop"
        const val PLACE = "place"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SubtitleStyleModule {
    @Binds
    abstract fun store(stored: StoredSubtitleStyle): SubtitleStyleStore
}
