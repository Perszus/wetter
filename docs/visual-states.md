# Visual states: what the app can actually resolve

A row-by-row reading of `exhaustive_weather_visual_state_catalogue.md` against
what this app holds today. The catalogue lists **338 states**, which is
**169 conditions x day/night**. This is the answer to "do we have them all":
**70 yes, 21 partly, 76 no**, plus two that
carry a naming trap serious enough to have its own section below.

## Read this first: the enum is not the answer

`WeatherCondition` has sixteen values and the catalogue has 169 states, but
comparing those two numbers gets the wrong answer. The catalogue's own
resolution rule is compositional - phenomena, then intensity, then combination,
then visual priority - and the app already carries most of those inputs as
separate fields. `HEAVY_RAIN` is not a missing enum value; it is `RAIN` plus a
rate the app has had all along.

So the useful question is not which enum values are missing. It is **which
inputs are missing**, and there are only eight of them:

| Missing input | Costs us | Could come from |
|---|---|---|
| visibility | 6 states in section 26, plus mist/dense fog and obscured storms | Open-Meteo publishes it; MET Norway does not |
| fog fraction | patchy and shallow fog | MET Norway publishes it; Open-Meteo does not |
| ice pellets | all of section 12 and 13, most of 25, part of 10 | neither provider separates them from snow or sleet |
| graupel / snow pellets | all of section 15 | neither provider reports them |
| hail without thunder | all of section 14 | neither; only `THUNDERSTORM_WITH_HAIL` exists |
| airborne dust as weather | all of section 20 | the air-quality endpoint has `dust`; the forecast does not |
| lying snow depth | section 18, and blizzard | MET Norway reports liquid equivalent only |
| a warnings feed | section 21, and severe-storm grading | MET Norway has MetAlerts; Open-Meteo has nothing |

Two of those - visibility and fog fraction - are each published by one provider
and not the other, so adding either means one region gets the state and the
rest of the world does not. That is the same trade that kept thunder
probability out of the drawer.

## The naming trap: SLEET means two different things

The app's `SLEET` is **rain and snow falling together**. Its own KDoc says so,
and in a Baltic winter it is most of what falls.

The catalogue files `SLEET` under section 10, next to `ICE_PELLETS`, and gives
rain-and-snow its own section 11 as `RAIN_SNOW`.

Mapped by name, every wet Riga winter hour gets an ice-pellet picture. The app's
`SLEET` maps to **`RAIN_SNOW`**, and catalogue `SLEET` is a state the app cannot
currently produce at all.

## Day and night is free, and unused

`isDay` is on both `CurrentWeather` and `HourlyWeather`, computed from the sun's
position by `SolarTime` where the provider does not publish it. The x2 axis of
the catalogue is therefore already correct and costs nothing.

Nothing in `app/src/main` reads it. The palette even defines a `night` colour
that no composable uses. Today the condition reaches the screen as one word of
text on the dial and nothing else, so there is no visual path to hang an image
on yet - that is the work this document exists to scope.

## The table

`yes` means every input needed is already held and correct. `partly` means the
state can be approximated but something real is lost, and the column says what.
`no` means an input is missing outright.

### 1. Clear / Cloud States

*9 states - 9 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `CLEAR` | yes | `condition` | - |
| `MOSTLY_CLEAR` | yes | `condition` (`MAINLY_CLEAR`) | - |
| `PARTLY_CLOUDY` | yes | `condition` | - |
| `MOSTLY_CLOUDY` | yes | `cloudCover` + `cloudLow/Medium/High` | - |
| `OVERCAST` | yes | `condition` | - |
| `BROKEN_CLOUDS` | yes | `cloudCover` + `cloudLow/Medium/High` | - |
| `DARK_OVERCAST` | yes | `cloudCover` + `cloudLow/Medium/High` | - |
| `CLOUDS_DEVELOPING` | yes | `cloudCover` + `cloudLow/Medium/High` across the hourly series (trend) | - |
| `CLOUDS_DISSIPATING` | yes | `cloudCover` + `cloudLow/Medium/High` across the hourly series (trend) | - |

### 2. Haze / Smoke / Atmospheric Obscuration

*6 states - 2 no, 4 partly*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `HAZE` | partly | `AirQuality.pm25` | **visibility** — Open-Meteo publishes it, MET Norway does not |
| `SMOKE` | no | - | **smoke** — no provider field; PM2.5 alone cannot say what is burning |
| `DUST_HAZE` | partly | `AirQuality.pm25` | **airborne dust as weather** — the air-quality endpoint has `dust`, the forecast does not |
| `SAND_HAZE` | partly | `AirQuality.pm25` | **airborne dust as weather** — the air-quality endpoint has `dust`, the forecast does not |
| `THICK_HAZE` | partly | `AirQuality.pm25` | **visibility** — Open-Meteo publishes it, MET Norway does not |
| `SMOKE_AND_HAZE` | no | - | **smoke** — no provider field; PM2.5 alone cannot say what is burning |

### 3. Mist / Fog

*10 states - 8 no, 2 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `MIST` | no | - | **visibility** — Open-Meteo publishes it, MET Norway does not |
| `FOG` | yes | `condition` | - |
| `DENSE_FOG` | no | - | **visibility** — Open-Meteo publishes it, MET Norway does not |
| `PATCHY_FOG` | no | - | **fog fraction** — MET Norway publishes it, Open-Meteo does not |
| `SHALLOW_FOG` | no | - | **fog fraction** — MET Norway publishes it, Open-Meteo does not |
| `FREEZING_FOG` | yes | `condition` + `temperature` | - |
| `FOG_WITH_DRIZZLE` | no | - | **fog and precipitation together** — the condition is a single code, so one hides the other |
| `FOG_WITH_RAIN` | no | - | **fog and precipitation together** — the condition is a single code, so one hides the other |
| `FOG_WITH_SNOW` | no | - | **fog and precipitation together** — the condition is a single code, so one hides the other |
| `FOG_WITH_FREEZING_PRECIPITATION` | no | - | **fog and precipitation together** — the condition is a single code, so one hides the other |

### 4. Drizzle

*5 states - 5 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_DRIZZLE` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `DRIZZLE` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_DRIZZLE` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `FREEZING_DRIZZLE` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_FREEZING_DRIZZLE` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |

### 5. Rain

*5 states - 5 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `VERY_HEAVY_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `TORRENTIAL_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |

### 6. Rain Showers

*5 states - 5 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_RAIN_SHOWERS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `RAIN_SHOWERS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_RAIN_SHOWERS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `VERY_HEAVY_RAIN_SHOWERS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `PASSING_RAIN_SHOWERS` | yes | `condition` + `precipitationSpells()` length | - |

### 7. Freezing Rain

*5 states - 2 no, 3 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_FREEZING_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `FREEZING_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_FREEZING_RAIN` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `FREEZING_RAIN_AND_DRIZZLE` | no | - | **two liquid types at once** — the condition is a single code |
| `HEAVY_FREEZING_RAIN_AND_DRIZZLE` | no | - | **two liquid types at once** — the condition is a single code |

### 8. Snow

*8 states - 8 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_SNOW` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `SNOW` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_SNOW` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `VERY_HEAVY_SNOW` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `SNOW_FLURRIES` | yes | `condition` + `precipitation` → `PrecipitationIntensity` at trace | - |
| `SNOW_SHOWERS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_SNOW_SHOWERS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `PASSING_SNOW_SHOWERS` | yes | `condition` + `precipitationSpells()` length | - |

### 9. Snow Grains / Diamond Dust

*4 states - 1 partly, 3 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_SNOW_GRAINS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `SNOW_GRAINS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_SNOW_GRAINS` | yes | `condition` + `precipitation` → `PrecipitationIntensity` | - |
| `DIAMOND_DUST` | partly | `condition` + `temperature` well below freezing | no provider names it; would be inferred from snow grains in very cold clear air |

### 10. Ice Pellets / Sleet

*5 states - 2 careful, 3 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `HEAVY_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `SLEET` | **see note** | `condition` — but see the naming warning above | - |
| `HEAVY_SLEET` | **see note** | `condition` — but see the naming warning above | - |

### 11. Rain + Snow

*5 states - 2 partly, 3 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_RAIN_SNOW` | yes | `condition` (`SLEET`) + `precipitation` → `PrecipitationIntensity` | - |
| `RAIN_SNOW` | yes | `condition` (`SLEET`) + `precipitation` → `PrecipitationIntensity` | - |
| `HEAVY_RAIN_SNOW` | yes | `condition` (`SLEET`) + `precipitation` → `PrecipitationIntensity` | - |
| `RAIN_SNOW_SHOWERS` | partly | `condition` (`SLEET`) + `precipitation` → `PrecipitationIntensity` | showery versus steady sleet is not distinguished |
| `HEAVY_RAIN_SNOW_SHOWERS` | partly | `condition` (`SLEET`) + `precipitation` → `PrecipitationIntensity` | showery versus steady sleet is not distinguished |

### 12. Rain + Ice Pellets

*3 states - 3 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_RAIN_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `RAIN_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `HEAVY_RAIN_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |

### 13. Snow + Ice Pellets

*3 states - 3 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_SNOW_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `SNOW_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |
| `HEAVY_SNOW_ICE_PELLETS` | no | - | **ice pellets** — neither provider separates them from snow or sleet |

### 14. Hail

*5 states - 5 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_HAIL` | no | - | **hail without thunder** — only reachable today as `THUNDERSTORM_WITH_HAIL` |
| `HAIL` | no | - | **hail without thunder** — only reachable today as `THUNDERSTORM_WITH_HAIL` |
| `HEAVY_HAIL` | no | - | **hail without thunder** — only reachable today as `THUNDERSTORM_WITH_HAIL` |
| `HAIL_SHOWERS` | no | - | **hail without thunder** — only reachable today as `THUNDERSTORM_WITH_HAIL` |
| `HEAVY_HAIL_SHOWERS` | no | - | **hail without thunder** — only reachable today as `THUNDERSTORM_WITH_HAIL` |

### 15. Small Hail / Snow Pellets / Graupel

*6 states - 6 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `LIGHT_SNOW_PELLETS` | no | - | **graupel / snow pellets** — neither provider reports them |
| `SNOW_PELLETS` | no | - | **graupel / snow pellets** — neither provider reports them |
| `HEAVY_SNOW_PELLETS` | no | - | **graupel / snow pellets** — neither provider reports them |
| `LIGHT_GRAUPEL` | no | - | **graupel / snow pellets** — neither provider reports them |
| `GRAUPEL` | no | - | **graupel / snow pellets** — neither provider reports them |
| `HEAVY_GRAUPEL` | no | - | **graupel / snow pellets** — neither provider reports them |

### 16. Thunderstorms

*24 states - 11 no, 8 partly, 5 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `THUNDERSTORM` | yes | `condition` | - |
| `THUNDERSTORM_LIGHT_RAIN` | yes | `condition` + `precipitation` -> `PrecipitationIntensity` | - |
| `THUNDERSTORM_RAIN` | yes | `condition` + `precipitation` -> `PrecipitationIntensity` | - |
| `THUNDERSTORM_HEAVY_RAIN` | yes | `condition` + `precipitation` -> `PrecipitationIntensity` | - |
| `THUNDERSTORM_VERY_HEAVY_RAIN` | yes | `condition` + `precipitation` -> `PrecipitationIntensity` | - |
| `THUNDERSTORM_LIGHT_SNOW` | partly | `condition` + `precipitation` -> `PrecipitationIntensity` + `kind` | MET Norway's symbol carries the type under thunder; Open-Meteo's code 95 does not |
| `THUNDERSTORM_SNOW` | partly | `condition` + `precipitation` -> `PrecipitationIntensity` + `kind` | MET Norway's symbol carries the type under thunder; Open-Meteo's code 95 does not |
| `THUNDERSTORM_HEAVY_SNOW` | partly | `condition` + `precipitation` -> `PrecipitationIntensity` + `kind` | MET Norway's symbol carries the type under thunder; Open-Meteo's code 95 does not |
| `THUNDERSTORM_LIGHT_HAIL` | partly | `condition` (`THUNDERSTORM_WITH_HAIL`) + `precipitation` -> `PrecipitationIntensity` | no hail size or amount is published |
| `THUNDERSTORM_HAIL` | partly | `condition` (`THUNDERSTORM_WITH_HAIL`) + `precipitation` -> `PrecipitationIntensity` | no hail size or amount is published |
| `THUNDERSTORM_HEAVY_HAIL` | partly | `condition` (`THUNDERSTORM_WITH_HAIL`) + `precipitation` -> `PrecipitationIntensity` | no hail size or amount is published |
| `THUNDERSTORM_RAIN_SNOW` | partly | `condition` + `kind` | MET Norway only |
| `THUNDERSTORM_RAIN_HAIL` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `THUNDERSTORM_SNOW_HAIL` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `THUNDERSTORM_RAIN_SNOW_HAIL` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `THUNDERSTORM_RAIN_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |
| `THUNDERSTORM_SNOW_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |
| `DISTANT_LIGHTNING` | no | - | **lightning location** - no provider says where the strike is relative to you |
| `LIGHTNING_WITHOUT_RAIN` | no | - | **lightning location** - no provider says where the strike is relative to you |
| `EMBEDDED_THUNDERSTORM` | no | - | **lightning location** - no provider says where the strike is relative to you |
| `OBSCURED_THUNDERSTORM` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `SEVERE_THUNDERSTORM` | partly | `condition` + `windSpeed` / `windGust` + `precipitation` -> `PrecipitationIntensity` | **a warnings feed** - MET Norway has MetAlerts, Open-Meteo has nothing |
| `SQUALL_LINE` | no | - | **storm structure** - radar could see a line, the nowcaster does not classify shape |
| `SQUALL_LINE_HAIL` | no | - | **storm structure** - radar could see a line, the nowcaster does not classify shape |

### 17. Wind

*15 states - 15 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `CALM` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `BREEZY` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `WINDY` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `STRONG_WIND` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `VERY_STRONG_WIND` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `GALE` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `SEVERE_GALE` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `STORM_FORCE_WIND` | yes | `windSpeed` / `windGust` on the Beaufort scale | - |
| `WINDY_RAIN` | yes | `windSpeed` / `windGust` + `condition` | - |
| `STRONG_WIND_RAIN` | yes | `windSpeed` / `windGust` + `condition` | - |
| `GALE_RAIN` | yes | `windSpeed` / `windGust` + `condition` | - |
| `WINDY_SNOW` | yes | `windSpeed` / `windGust` + `condition` | - |
| `STRONG_WIND_SNOW` | yes | `windSpeed` / `windGust` + `condition` | - |
| `GALE_SNOW` | yes | `windSpeed` / `windGust` + `condition` | - |
| `WINDY_SLEET` | yes | `windSpeed` / `windGust` + `condition` | - |

### 18. Blowing / Drifting Snow

*3 states - 2 no, 1 partly*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `BLOWING_SNOW` | no | - | **lying snow depth** — MET Norway reports liquid equivalent only |
| `DRIFTING_SNOW` | no | - | **lying snow depth** — MET Norway reports liquid equivalent only |
| `BLIZZARD` | partly | `windSpeed` / `windGust` + `condition` + `precipitation` -> `PrecipitationIntensity` | **visibility** - Open-Meteo publishes it, MET Norway does not; and **lying snow depth** - MET Norway reports liquid equivalent only |

### 19. Squalls

*5 states - 1 no, 4 partly*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `SQUALL` | partly | `windGust` against `windSpeed` across the hourly series | a squall is a sudden jump held for a minute or two; hourly rows cannot see it |
| `SQUALL_WITH_RAIN` | partly | `windGust` against `windSpeed` across the hourly series | a squall is a sudden jump held for a minute or two; hourly rows cannot see it |
| `SQUALL_WITH_SNOW` | partly | `windGust` against `windSpeed` across the hourly series | a squall is a sudden jump held for a minute or two; hourly rows cannot see it |
| `SQUALL_WITH_HAIL` | no | - | **hail without thunder** - only reachable today as `THUNDERSTORM_WITH_HAIL` |
| `SQUALL_WITH_THUNDERSTORM` | partly | `windGust` against `windSpeed` across the hourly series | a squall is a sudden jump held for a minute or two; hourly rows cannot see it |

### 20. Dust / Sand

*8 states - 8 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `DUST_SUSPENSION` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `BLOWING_DUST` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `BLOWING_SAND` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `DUST_WHIRL` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `SANDSTORM` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `DUSTSTORM` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `HEAVY_DUSTSTORM` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |
| `HEAVY_SANDSTORM` | no | - | **airborne dust as weather** - the air-quality endpoint has `dust`, the forecast does not |

### 21. Funnel / Tornado / Waterspout

*3 states - 3 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `FUNNEL_CLOUD` | no | - | **a warnings feed** — MET Norway has MetAlerts, Open-Meteo has nothing |
| `TORNADO` | no | - | **a warnings feed** — MET Norway has MetAlerts, Open-Meteo has nothing |
| `WATERSPOUT` | no | - | **a warnings feed** — MET Norway has MetAlerts, Open-Meteo has nothing |

### 22. Volcanic Ash

*2 states - 2 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `VOLCANIC_ASH` | no | - | **a volcanic-ash advisory feed** — no source |
| `HEAVY_VOLCANIC_ASH` | no | - | **a volcanic-ash advisory feed** - no source |

### 23. Extreme Cold

*5 states - 5 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `EXTREME_COLD_CLEAR` | yes | `apparentTemperature` + `cloudCover` + decks | - |
| `EXTREME_COLD_CLOUDY` | yes | `apparentTemperature` + `cloudCover` + decks | - |
| `EXTREME_COLD_FOG` | yes | `apparentTemperature` + `condition` | - |
| `EXTREME_COLD_SNOW` | yes | `apparentTemperature` + `condition` | - |
| `EXTREME_COLD_ICE` | yes | `apparentTemperature` + `condition` (`FREEZING_RAIN`) | - |

### 24. Extreme Heat

*3 states - 1 partly, 2 yes*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `EXTREME_HEAT_CLEAR` | yes | `apparentTemperature` + `cloudCover` + decks | - |
| `EXTREME_HEAT_HAZY` | partly | `apparentTemperature` + `AirQuality.pm25` | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `EXTREME_HEAT_CLOUDY` | yes | `apparentTemperature` + `cloudCover` + decks | - |

### 25. Special Precipitation Combinations

*11 states - 11 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `DRIZZLE_RAIN` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `DRIZZLE_SNOW` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `DRIZZLE_SNOW_GRAINS` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `DRIZZLE_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |
| `RAIN_DRIZZLE_SNOW` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `RAIN_DRIZZLE_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |
| `SNOW_SNOW_GRAINS` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `SNOW_SNOW_GRAINS_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |
| `SNOW_RAIN_SNOW_GRAINS` | no | - | **two types at once** - the condition is a single code, so one hides the other |
| `RAIN_SNOW_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |
| `RAIN_DRIZZLE_SNOW_ICE_PELLETS` | no | - | **ice pellets** - neither provider separates them from snow or sleet |

### 26. Reduced Visibility + Precipitation

*6 states - 6 no*

| State | Have it | Resolved from | What is missing |
|---|---|---|---|
| `RAIN_LOW_VISIBILITY` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `HEAVY_RAIN_LOW_VISIBILITY` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `SNOW_LOW_VISIBILITY` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `HEAVY_SNOW_LOW_VISIBILITY` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `SLEET_LOW_VISIBILITY` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |
| `FREEZING_RAIN_LOW_VISIBILITY` | no | - | **visibility** - Open-Meteo publishes it, MET Norway does not |

## What "extreme weather" covers, and what it cannot

The warning mark reads nine hazards off the forecast: heat, cold, damaging wind,
torrential rain, heavy snow, ice, thunderstorms, ultraviolet and unhealthy air.
Wind is judged on the gust against Beaufort's own definitions, so a tropical
cyclone at your coordinates does raise the mark at its highest level, and above
Beaufort 12 it is named hurricane-force rather than merely damaging.

What it cannot do is name the storm. Whether a cyclone has a name, where its eye
is, which category it is and when it makes landfall come from cyclone track
feeds, and every one of them is regional - the NHC for the Atlantic and eastern
Pacific, the JTWC elsewhere, MetAlerts for Norway, Meteoalarm for Europe. This
app takes global sources only, so it has none of them.

There is also a resolution limit worth stating plainly. The forecast gust comes
from a global model on an 11 km grid, which under-resolves an eyewall: near a
strong cyclone the number this app shows will be lower than what actually
arrives. It will tell you the wind is extreme. It will not tell you it is
Hurricane Whatever, and it will not tell you the worst of it.

Anyone in a cyclone basin needs their national service for that, and this app
does not pretend otherwise.

## Where this leaves the image work

Seventy states are reachable today with no new data at all - they need a
resolver that composes condition, intensity, kind, wind, cloud decks,
temperature and `isDay` into an image id, which is exactly the pipeline the
catalogue describes.

Twenty-one more are reachable if approximation is acceptable, and the table
says per state what would be approximated.

The remaining seventy-eight are blocked on the eight inputs above, not on
effort. Adding visibility alone unblocks ten of them; a warnings feed unblocks
four and would also be the honest source for severe-storm grading.

This file was derived from the catalogue mechanically, so it can be regenerated
when the catalogue changes. The verdicts are judgements and should be argued
with where they are wrong.
