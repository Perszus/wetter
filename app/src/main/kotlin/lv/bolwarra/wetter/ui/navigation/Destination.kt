package lv.bolwarra.wetter.ui.navigation

/**
 * Wetter has three places to be, and no plans for a fourth.
 *
 * Plain string routes rather than type-safe navigation arguments: nothing here
 * carries an argument, so the serialization plugin and the generated route types
 * would buy nothing (docs/design-principles.md). Revisit if a destination ever needs a
 * parameter.
 */
enum class Destination(val route: String) {
    Weather("weather"),
    Locations("locations"),
    Settings("settings"),
}
