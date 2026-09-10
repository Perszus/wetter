package lv.bolwarra.wetter.ui.navigation

/**
 * Wetter has two places to be, and no plans for a third.
 *
 * Settings is not among them. It is an overlay over whatever is on screen rather
 * than a place you travel to: it is opened for one change and shut again, and a
 * destination would put the forecast behind a back press for that.
 *
 * Plain string routes rather than type-safe navigation arguments: nothing here
 * carries an argument, so the serialization plugin and the generated route types
 * would buy nothing (docs/design-principles.md). Revisit if a destination ever needs a
 * parameter.
 */
enum class Destination(val route: String) {
    Weather("weather"),
    Locations("locations"),
}
