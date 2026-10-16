# Visual language

One instrument, seen under different skies. Not a set of themed screens.

This document is the rules. The hex values are generated from them and are not
written down anywhere: a value that can be read is a value that gets copied into
a component. sRGB is not perceptual (`#767676` and `#808080` look a step apart,
`#101010` and `#1A1A1A` look identical), so values picked by eye drift in
contrast; values solved for a contrast cannot.

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
the same weight on every plate.

## Rule 3 — one specification, four plates

`PlateSpec` is the whole difference between the plates:

| Plate | Ground | Ink | Used by |
|---|---|---|---|
| Pure White | L\*96.9, Kenya Hara's paper (`#F6F6F6`), true grey | darker | the app, when chosen |
| Pure Black | L\*9.3, Ansel Adams' Zone I, zones about sixteen hex apart, true grey | lighter | the app, by default |
| Paper | L\*95, a trace of blue | darker | widgets in light mode |
| Night | L\*9, a trace of blue | lighter | widgets in dark mode |

Pure White and Pure Black are shared with the other Bolwarra apps and keep their
own step sizes: Pure Black raises by brightness, Pure White by a slight
darkening, since paper has no headroom above its ground. On Paper and Night every
grey carries `NEUTRAL_CHROMA = 2.4` at hue 250, too little to call blue, so the
rain hue sits in the surface rather than on it.

## Rule 4 — the dial is always dark

The weather's pictures on the dial's glass are drawn as light, and light vanishes
on a light face. So a light plate lends the dial its dark counterpart in the same
tone: Pure Black's under Pure White, Night's under Paper. A future theme does the
same, in its own tone, nearly black. The marks beside the dial stay in the page's
colours.

## Rule 5 — rain owns the only loud hue

Chroma is the hierarchy, not hue:

- precipitation — 40
- temperature warm/cool — 22, a little over half
- day / night — 11
- warning / danger — 43 / 54, the only roles permitted above rain, because a
  hazard outranks everything on the screen by definition

A temperature can never out-shout rain; `PaletteTest` measures it in CIE chroma.

## Rule 6 — one light, from above, with no colour of its own

There are no drop shadows. Depth is lightness and nothing else.

- `surfaceHighlight` — where the light lands. **Never white.** A highlight is the
  same surface catching more of the one light, not a brighter material added on
  top of it.
- `surfaceShade` — where it does not. **Never black**, for the same reason.
- Shade reaches less far than highlight (1.5 steps against 2.2): a surface turned
  away from the light still receives the ambient of the room, so the fall-off is
  not symmetrical.

Raised surfaces are lighter than their ground on every plate except Pure White,
which has no headroom above paper and recesses instead. A dark theme that darkens
what it raises lights the scene from below, which reads as a hole, not a step.

**A recess inverts that, and only that.** The dial is set into the page rather
than standing on it: the near lip overhangs and shadows the top of the interior,
while the far wall at the bottom is turned towards the light and catches it. Same
light, same direction, opposite sign. Get the sign wrong and the identical
gradient makes a dome.

The recess is shallow, so its face sits at the page's own tone and the far wall a
step above it. That is not a contradiction — the floor of a shallow bowl faces
the light more squarely than the flat surface around it, so it genuinely catches
more. **The cue that says "hollow" is the shadow under the near lip**, and that
is the one thing that must not move. Putting the whole face below the page reads
as a recess too, and as a dim one: correct, and a shade too careful.

**A shadow is ink, never a surface.** A tone one step darker than the face is
invisible as shading; a shadow is an absence of light, so it is drawn in the ink
of the edge it sits under, at a lower emphasis.

**Matte, not glazed.** There is no specular anywhere. A radial highlight offset
from the centre is a *reflection of the light source*, which is what a glossy
surface does; a matte one has no reflection to place and is lit only by how far
each part of it is turned towards the light — across a flat disc, a plain
vertical ramp, wide and low in contrast. A bright arc along the dial's edge would
be a specular catch, so there is none.

## Rule 7 — emphasis is a ladder, not a judgement

`Emphasis.FULL / STRONG / MUTED / FAINT / GHOST` — 1.0, 0.70, 0.42, 0.20, 0.08.
Five steps, because a sixth would not be distinguishable. No component declares
its own alpha.

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

The reader chooses the plate in Settings, and the weather never overrules that
choice: it changes the light within the plate, an atmosphere rather than a mode.

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

Every style that can carry a number sets `tnum`, so a temperature ticking 9 → 10,
or a column of times, never jitters.

## Rule 10 — the band's text is the page showing through

`onSurfaceStrong` is the ground tone itself, so the band's contrast holds by
construction. The band inverts its plate: dark on light, light on dark.

## Adding to this system

1. A new role must be describable as a **job**. If it cannot be, it is a
   component's opinion and belongs in the component — or nowhere.
2. Declare it as a contrast requirement plus a hue and chroma. Never as a hex.
3. Add it to `PaletteTest`. A rule that is not measured is a preference.
4. A new weather condition is a **row in the atmosphere table**, not a palette.

`PaletteTest` enforces, on the Paper and Night plates: everything read is
readable; both plates carry the same weights; no role duplicates another; light
falls from above; disabled sits below the readable bar; rain out-chromas
temperature and only danger out-chromas rain; structure is present but never
loud; the band carries its own text.
