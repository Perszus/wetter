# Changelog

Notable changes to Wetter. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0] — 2026-10-16

The first release.

### Added

- **Rain, down to the minute.** For the next two hours the timeline comes from
  the rain radar, projected along the rain's measured motion; after that it
  hands over to the weather models. Below half a millimetre an hour it is called
  drizzle, not rain.
- **A rain map**: the last hour of radar and the next two hours projected,
  played over the surrounding region.
- **The right source for the place.** Open-Meteo and MET Norway, chosen per
  location, with failover when one is unavailable, and several models compared
  hour by hour. Where a regional model runs out of hourly detail, a global one
  continues it.
- **Today, Week and Month.** The next day hour by hour, the week with each day
  opening into its hours, and a month that turns into local climate normals
  where the forecast runs out.
- **Five home-screen widgets**: the next four hours of rain, the weather now,
  the coming hours, the week, and an overview. Each lays itself out for the size
  it is given.
- **Severe weather warnings** for gales, torrential rain, heavy snow, ice,
  thunderstorms and dangerous temperatures, judged against what is normal for
  the place.
- **Places**: search by name or coordinates, a pin on a map, the device's
  current location on request, and saved places.
- **Details on request**: cloud decks, air quality, wind, pressure, humidity,
  dew point, UV, sun, moon, tides and stargazing.
- **Works offline**: the last forecast stays on screen with its age, and a
  background refresh keeps it current.
- Units in Celsius or Fahrenheit, millimetres or inches, and four wind units;
  light and dark themes.

[1.0]: https://github.com/Perszus/wetter/releases/tag/v1.0
