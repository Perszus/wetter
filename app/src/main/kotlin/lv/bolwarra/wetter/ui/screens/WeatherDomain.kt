package lv.bolwarra.wetter.ui.screens

import androidx.annotation.StringRes
import lv.bolwarra.wetter.R

/**
 * The three things somebody comes to a weather app to ask, each with its own
 * page of tiles.
 *
 * One question per page rather than everything stacked down one scroll. What you
 * want to know about the next two hours and what you want to know about the next
 * two weeks are different questions, and a single page answering both answers
 * neither at a glance.
 *
 * [Today] is the default because it is the question asked most often, by a long
 * way — the other two are things you go and look at.
 */
enum class WeatherDomain(@StringRes val label: Int) {
    Today(R.string.domain_today),
    Week(R.string.domain_week),
    Month(R.string.domain_month),
}
