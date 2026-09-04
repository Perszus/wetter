# Visual language

One instrument, seen under different skies. Not a set of themed screens.

This document is the rules. The hex values are generated from them and are not
written down anywhere, on purpose — a value you can read is a value somebody will
copy into a component, and that is how the last palette came apart.

## What the audit found

The palette this replaces was seventeen hand-picked values. Measuring it:

| Finding | Detail |
|---|---|
| The most-used colour failed WCAG AA | `textTertiary` measured **3.28** on the light plate and **4.07** on the dark, while carrying every label, unit and axis tick — thirty call sites |
| The two themes were not one system | every accent on the light plate failed AA (precipitation 4.08, temperatureWarm 3.85, temperatureCool 4.11) while the same roles on the dark plate passed at AAA |
| Two roles were the same colour | `precipitation` and `accent` were both `#0E7FB8` |
| A temperature was an alert | on the dark plate `temperatureWarm` and `warning` were both `#E08A5E` |
| Roles existed with no job | `night` and `precipitationTrack` were used nowhere |
| Emphasis was nine private opinions | nine alpha constants across three files, no two components agreeing what "faint" meant |
| A component invented a light source | the dial mixed its surface toward `Color.White`, which on the dark plate bloomed bright enough to read as a second lamp in the room |

None of that is carelessness. It is what happens when values are picked in
isolation and judged by eye, because sRGB is not perceptual: `#767676` and
`#808080` look a step apart and are, while `#101010` and `#1A1A1A` look identical
and are numerically further apart.

## Rule 1 — tones are generated, never chosen

Every colour is declared in CIE L\*C\*h: how light it is, how coloured, and which
colour. `Tone.of(lightness, chroma, hue)` converts. Equal steps in L\* look equal,
which is the only reason a ramp can be designed rather than nudged.

## Rule 2 — a tone is the answer to a contrast requirement

Ink is never given a lightness. It is given a ratio, and `Tone.lightnessFor`
solves for the lightness that achieves it against the ground:

| Role | Solved for | Why |
|---|---|---|
| `textPrimary` | 13:1 | readings, the thing the screen was opened for |
| `textSecondary` | 7.2:1 | supporting prose, AAA |
| `textTertiary` | 4.7:1 | labels, units, ticks — read, so AA with margin |
| `textDisabled` | 2.4:1 | **below** the readable bar on purpose, so it cannot be mistaken for live |
| `hairline` | 1.7:1 | found when looked for, never drawing the eye |
| `gridline` | 1.35:1 | quieter still; it sits behind live data |
| accents | 4.7:1 | any accent may carry text or a thin line |
| `surfaceStrong` | 5.4:1 | the one filled block |

Because the requirement is solved rather than approximated, the same role carries
the same weight on both plates. The dark theme is the light theme seen at night,
not a second design.

## Rule 3 — one specification, read twice

`PlateSpec(ground, inkIsDarker)` is the whole difference between the plates.
Light is ground L\*94, ink darker. Night is ground L\*9, ink lighter.

Neither goes to an extreme. Pure white is a light source, not a surface, and
everything on it has to fight it. Pure black leaves nothing for a sunken surface
to recede into and makes every hairline vanish.

## Rule 4 — the neutrals are not neutral

Every grey carries `NEUTRAL_CHROMA = 2.4` at hue 250. Low enough that nobody
would call it blue; enough that it reads as material rather than as absence, the
way paper and graphite are never quite grey either. It also means the one
saturated hue in the app sits *in* the surface rather than on top of it.

## Rule 5 — rain owns the only loud hue

Chroma is the hierarchy, not hue:

- precipitation — 40
- temperature warm/cool — 22, a little over half
- day / night — 11
- warning / danger — 43 / 54, the only roles permitted above rain, because a
  hazard outranks everything on the screen by definition

A temperature can never out-shout rain. There is a test that measures this in CIE
chroma, after an earlier version measured it as an sRGB channel spread and ranked
a saturated blue below a duller orange.

## Rule 6 — one light, from above, with no colour of its own

There are no drop shadows. Depth is lightness and nothing else.

- `surfaceHighlight` — where the light lands. **Never white.** A highlight is the
  same surface catching more of the one light, not a brighter material added on
  top of it.
- `surfaceShade` — where it does not. **Never black**, for the same reason.
- Shade reaches less far than highlight (1.5 steps against 2.2): a surface turned
  away from the light still receives the ambient of the room, so the fall-off is
  not symmetrical.

Raised surfaces are lighter than their ground **on both plates**. A dark theme
that darkens what it raises is lighting the scene from below, which reads as a
hole rather than as a step, and is the commonest way a dark theme goes wrong.

## Rule 7 — emphasis is a ladder, not a judgement

`Emphasis.FULL / STRONG / MUTED / FAINT / GHOST` — 1.0, 0.70, 0.42, 0.20, 0.08.
Five steps, because a sixth would not be distinguishable and would only invite a
tenth private opinion. No component declares its own alpha.

## Rule 8 — weather changes the light, not the colours

An `Atmosphere` is four numbers and no palette:

| | what it moves |
|---|---|
| `ground` | L\* added to the page |
| `contrast` | multiplier on **every** ink contrast target |
| `chroma` | multiplier on **every** accent's colourfulness |
| `veil` | how much of the page is drawn back over its content as haze |

It is applied to the plate's *specification*, before any tone is generated, so
every role moves together and none can drift out of relation with the others.

The table, in full:

| Condition | ground | contrast | chroma | veil |
|---|---|---|---|---|
| Clear | +1.4 | ×1.06 | — | — |
| Mainly clear | +0.7 | ×1.03 | — | — |
| Partly cloudy | — | — | — | — |
| Overcast | −2.0 | ×0.95 | ×0.90 | — |
| Fog | −0.8 | ×0.82 | ×0.55 | 0.10 |
| Drizzle | −1.4 | ×0.96 | ×0.96 | — |
| Rain / showers | −2.4 | ×0.98 | ×1.05 | — |
| Freezing rain | −1.0 | ×0.92 | ×0.85 | — |
| Sleet | −1.6 | ×0.94 | ×0.80 | — |
| Snow | **+2.0** | ×0.90 | ×0.70 | — |
| Thunderstorm | −3.6 | ×1.08 | ×1.12 | — |

Snow is the only entry that brightens *and* flattens at once, because that is
what snow does: everything reflects and everything loses its edges.

Heavier precipitation multiplies whatever the condition established (×1.35)
rather than switching to a state of its own — one axis, not two. Night damps the
whole modulation to 0.55, because after dark the plate is already dark and a sky
that darkened it further would only spend contrast.

Fog is the only condition allowed a veil, being the only one that genuinely takes
information away.

**The whole range of `ground` is about six lightness steps and of `contrast`
about a fifth.** That is deliberately near the threshold of noticing. The test is
not that somebody sees the screen change when it starts raining; it is that it
feels different and they could not say why.

Which plate is used stays a system setting. Somebody who asked for a dark
interface asked for one, and the weather does not overrule that. Weather changes
the light *within* the chosen plate — an atmosphere, not a mode.

## Rule 9 — hierarchy is scale, weight and luminance, not colour

| Information | Style | Ink |
|---|---|---|
| Current temperature | `reading` — 64sp Light, −0.03em | `textPrimary` |
| Unit and degree | `readingUnit` — 24sp Light | `textTertiary` |
| Location | `place` — 20sp Medium | `textPrimary` |
| Condition | `title` — 16sp Medium | `textSecondary` |
| Forecast values | `figure` — 15sp, tabular | `textPrimary` |
| Body and explanations | `body` — 14sp, tabular | `textSecondary` |
| Timestamps, provenance | `meta` — 12sp, tabular | `textTertiary` |
| Group headings | `groupLabel` — 12sp SemiBold, 0.2em caps | `onSurfaceStrong` on a band |
| Field labels | `sectionLabel` — 11sp Medium, 0.14em caps | `textTertiary` |
| Axis ticks | `axis` — 11sp | `textTertiary` |
| Absent reading | `figure` | `textDisabled` |

Every style that can carry a number sets `tnum`. A temperature ticking 9 → 10, or
a column of times, must not jitter — that is exactly the imprecision an
instrument may not show.

## Rule 10 — the band's text is the page showing through

`onSurfaceStrong` is literally the ground tone. That makes the band's contrast
true by construction instead of a second value to keep in step, and it is why the
pair cannot drift apart. The band inverts its plate — dark on light, light on
dark — which is the same idea mirrored, like everything else here.

## Adding to this system

1. A new role must be describable as a **job**. If it cannot be, it is a
   component's opinion and belongs in the component — or nowhere.
2. Declare it as a contrast requirement plus a hue and chroma. Never as a hex.
3. Add it to `PaletteTest`. A rule that is not measured is a preference.
4. A new weather condition is a **row in the atmosphere table**, not a palette.

`PaletteTest` enforces: everything read is readable on both plates; both plates
carry the same weights; no role duplicates another; neither ground is an extreme;
light falls from above on both; disabled sits below the readable bar; rain
out-chromas temperature and only danger out-chromas rain; structure is present
but never loud; the band carries its own text.
