# Build a Precision Weather Intelligence Engine

## Mission

We are not building a conventional weather app.

We are building a **precision meteorological data-fusion system** whose purpose is to produce the most reliable possible local weather estimate and forecast using freely available data sources.

The application should behave more like a small forecasting system than an API wrapper.

The core principle is:

> **Never blindly trust one weather provider when multiple independent observations and models are available.**

The system should continuously ingest observations, radar, numerical weather prediction models, satellite-derived information where available, weather stations, alerts and other useful datasets; normalize them; assess their quality; compare them; and produce a unified forecast with explicit confidence and uncertainty.

The system must degrade gracefully when sources are unavailable.

---

# 1. Core Architecture

Create a provider-agnostic meteorological engine:

```text
                    LOCATION
                       │
                       ▼
               PROVIDER ROUTER
                       │
       ┌───────────────┼────────────────┐
       │               │                │
       ▼               ▼                ▼
 OBSERVATIONS        RADAR             NWP
       │               │                │
       │               │                │
       └───────────────┼────────────────┘
                       ▼
                DATA NORMALIZATION
                       │
                       ▼
                QUALITY CONTROL
                       │
                       ▼
              FORECAST FUSION ENGINE
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
       NOWCAST       SHORT TERM    LONG TERM
       0–2h           2–24h         1–16d
          │            │            │
          └────────────┼────────────┘
                       ▼
              LOCAL CORRECTION LAYER
                       │
                       ▼
              PROBABILISTIC OUTPUT
                       │
                       ▼
                     API/UI
```

The UI must never need to know which provider supplied a value.

---

# 2. Data-source philosophy

Implement providers as independent adapters.

Every provider must expose normalized meteorological variables rather than provider-specific structures.

Example:

```typescript
interface WeatherProvider {
    id: string;
    capabilities: ProviderCapabilities;

    getCurrent(location): Promise<Observation>;
    getForecast(location, range): Promise<Forecast>;
    getRadar(location, range): Promise<RadarData>;
    getAlerts(location): Promise<Alert[]>;
}
```

Do NOT hard-code the application around Open-Meteo, OpenWeather, RainViewer, etc.

Providers are replaceable.

---

# 3. Primary global numerical weather source: Open-Meteo

Use Open-Meteo as the principal global baseline.

Open-Meteo currently provides 15-minute variables including:

* temperature
* relative humidity
* dew point
* apparent temperature
* precipitation
* rain
* snowfall
* snow depth/height
* freezing level
* weather code
* wind speed
* wind direction
* wind gusts
* visibility
* CAPE
* solar radiation
* lightning potential
* etc.

Its 15-minute data is particularly useful in Central Europe and North America. In Central Europe it uses high-resolution models including DWD ICON-D2 and Météo-France AROME; in North America it can use NOAA HRRR. Outside regions with native 15-minute model data, some values are interpolated and MUST NOT be treated as genuinely 15-minute model forecasts.

Source:
https://open-meteo.com/en/docs

Implement model selection explicitly rather than assuming the default model is always optimal.

Maintain:

```text
model:
    ECMWF
    ICON
    ICON-D2
    AROME
    GFS
    HRRR
    regional models
```

Where available.

---

# 4. MET Norway

Integrate MET Norway.

MET Norway provides:

* global location forecasts
* observations
* weather alerts
* Nordic nowcasting
* precipitation forecasts
* radar-derived nowcasting

Its Nowcast service is particularly valuable.

MET Norway's Nowcast provides approximately two hours of forecast and is updated every five minutes.

It uses radar observations from Norway, Sweden and Finland and an optical-flow algorithm to estimate precipitation movement. MET Norway explicitly notes that where radar coverage is good, this approach generally outperforms the MEPS precipitation forecast for the next two hours.

This should therefore be treated as a **high-value near-term precipitation source**, not merely another generic forecast.

Sources:

https://api.met.no/

https://api.met.no/weatherapi/nowcast/2.0/documentation

https://api.met.no/doc/nowcast/datamodel

Important:

MET Norway also incorporates observations and dense citizen-observation networks into its nowcasting system.

Use this as architectural inspiration for our own observation-fusion layer.

---

# 5. Radar architecture

Radar is fundamentally different from numerical weather prediction.

NWP predicts atmospheric evolution.

Radar observes precipitation that is actually happening.

Therefore:

```text
NWP:
"What should happen?"

RADAR:
"What is happening right now?"

NOWCAST:
"Given what is happening, where will it move?"
```

For precipitation in the next 0–120 minutes, radar should generally receive significantly more weight than long-range NWP.

---

# 6. European radar sources

Investigate and implement legal integrations for European radar sources.

## EUMETNET OPERA

OPERA is Europe's major meteorological radar collaboration.

The current European radar ecosystem uses the OPERA Data Information Model (ODIM), based on HDF5.

OPERA currently provides the CIRRUS maximum-reflectivity composite at approximately:

* 5-minute update
* 1 km grid

However:

**DO NOT assume OPERA data is unrestricted commercial/open data.**

EUMETNET explicitly states that commercial exploitation requires appropriate licensing.

Therefore implement OPERA as:

```text
Optional provider
    ↓
license-aware
    ↓
enabled only when permitted
```

Never bypass licensing restrictions.

Source:

https://www.eumetnet.eu/data-access/

https://www.eumetnet.eu/observations/opera-radar-animation/

---

# 7. National meteorological radar networks

Create a regional-provider framework.

Potential sources include:

* DWD — Germany
* MET Norway — Norway
* FMI — Finland
* SMHI — Sweden
* DMI — Denmark
* MeteoSwiss — Switzerland
* Météo-France — France
* AEMET — Spain
* Met Office — UK
* Environment Canada / MSC
* NOAA / NWS — United States
* other national meteorological agencies

Do not automatically scrape graphical websites.

Prefer:

```text
official API
official open-data endpoint
official radar files
official OGC services
official downloadable datasets
```

where available.

---

# 8. DWD

Integrate DWD open radar data where licensing and technical conditions permit.

DWD provides radar datasets through its open-data infrastructure.

Available products include radar composites and radar-related datasets.

Source:

https://opendata.dwd.de/weather/radar/

Use DWD particularly strongly for Central Europe.

---

# 9. NOAA / NEXRAD

For North America, integrate NEXRAD.

NEXRAD provides Doppler radar observations including reflectivity and atmospheric motion information.

Support:

* Level II
* Level III
* derived products where useful

The official NWS infrastructure provides radar data.

IMPORTANT CURRENT DETAIL:

NWS has announced that NEXRAD Level II data is moving from the NOMADS HTTPS location to TGFTP effective September 15, 2026.

Use the current TGFTP endpoint rather than building new infrastructure around the retiring NOMADS endpoint.

Source:

https://www.weather.gov/tg/radar

https://www.weather.gov/media/roc/Documentation/2620010E.pdf

---

# 10. RainViewer

Treat RainViewer as a convenience/fallback radar source rather than the fundamental meteorological layer.

Important:

RainViewer changed its API offering in 2026 and discontinued its own future radar nowcast functionality from January 1, 2026.

Therefore:

```text
RainViewer:
    radar observation / visualization source

NOT:
    primary nowcasting engine
```

Check the current licensing terms before commercial use.

---

# 11. Canadian radar

Integrate Environment and Climate Change Canada / MSC GeoMet where useful.

MSC GeoMet provides public access to large quantities of meteorological data through OGC APIs.

It exposes:

* real-time weather
* historical weather
* precipitation
* temperature
* wind
* radar-related datasets
* forecasts
* alerts
* climate data

It also supports spatial querying and reprojection.

Source:

https://api.weather.gc.ca/

Use it as a first-class regional source in Canada.

---

# 12. Observation layer

Build a dedicated observation subsystem.

Collect:

```text
official weather stations
airport METAR observations
synoptic stations
automatic weather stations
rain gauges
crowdsourced stations where licensing permits
```

Variables:

```text
temperature
dew point
humidity
pressure
wind speed
wind direction
gusts
precipitation
visibility
cloud base
weather phenomena
snow depth
```

Observation data should be timestamped and geolocated.

Do not simply use the nearest station.

Instead:

```text
find nearby stations
    ↓
distance weighting
    +
elevation correction
    +
station quality
    +
age of observation
    +
terrain similarity
    ↓
local observation estimate
```

---

# 13. Station quality scoring

Every observation receives a quality score.

Example:

```text
quality =
    source_reliability
    × temporal_freshness
    × spatial_relevance
    × historical_consistency
    × sensor_quality
```

Detect:

* frozen sensors
* stuck values
* impossible temperature changes
* impossible wind readings
* precipitation sensor failures
* stale observations
* sudden sensor jumps

Never allow a single obviously broken station to corrupt the forecast.

---

# 14. Radar processing

Do not merely display radar imagery.

Turn radar into data.

Pipeline:

```text
RAW RADAR
   ↓
decode
   ↓
georeference
   ↓
quality control
   ↓
clutter removal
   ↓
terrain/blockage correction where possible
   ↓
precipitation classification
   ↓
precipitation intensity field
   ↓
motion estimation
   ↓
nowcast
```

Support radar-derived:

```text
reflectivity
precipitation intensity
rain/snow classification
storm-cell detection
cell movement
cell growth/decay
motion vector
```

---

# 15. Radar nowcasting

Implement an optical-flow / motion-estimation system.

Input:

```text
radar(t-10)
radar(t)
```

Estimate:

```text
motion_vector(x,y)
```

Then advect the precipitation field:

```text
radar(t) + motion_vector
        ↓
t+10
t+20
t+30
...
t+120
```

Do NOT simply translate the entire radar image.

Calculate spatially varying motion where possible.

Use techniques such as:

* optical flow
* Lucas-Kanade
* Horn-Schunck
* Farneback
* phase correlation
* pyramidal optical flow
* feature/cell tracking

Experiment and benchmark.

---

# 16. Radar growth and decay

Pure advection eventually fails because storms:

* develop
* intensify
* weaken
* split
* merge
* change direction

Therefore introduce growth/decay estimation.

For each precipitation cell:

```text
intensity(t-20)
intensity(t-10)
intensity(t)
```

Estimate:

```text
dI/dt
```

Then incorporate this into the nowcast.

Example:

```text
moving + intensifying
moving + stable
moving + weakening
developing
dissipating
```

The system should recognize that pure radar extrapolation becomes progressively less trustworthy with forecast lead time.

---

# 17. NWP + radar fusion

This is the heart of the system.

Do NOT choose:

```text
radar OR NWP
```

Use:

```text
radar nowcast
+
NWP forecast
```

with dynamically changing weights.

Example conceptual weighting:

```text
0–30 min:
    radar 80–95%
    NWP   5–20%

30–60 min:
    radar 60–85%
    NWP   15–40%

1–2 h:
    radar 40–70%
    NWP   30–60%

2–6 h:
    radar 10–40%
    NWP   60–90%

6h+:
    NWP dominant
```

These are initial heuristics only.

The system MUST eventually learn/derive weights from historical verification.

---

# 18. Model ensemble

Never rely on one NWP model when multiple models are available.

For every forecast variable, collect:

```text
ECMWF
ICON
regional high-resolution models
GFS
HRRR where available
AROME where available
other accessible models
```

Then calculate:

```text
ensemble_mean
ensemble_median
ensemble_spread
min
max
```

For precipitation also calculate:

```text
probability > 0.1mm
probability > 0.5mm
probability > 1mm
probability > 5mm
```

Model disagreement becomes a direct uncertainty signal.

---

# 19. Dynamic model selection

Do not use static weights globally.

Model performance varies by:

* geography
* season
* time of day
* terrain
* weather regime
* forecast horizon
* variable

Therefore support:

```text
location
+
season
+
hour
+
forecast horizon
+
weather regime
```

when calculating historical model skill.

Example:

```text
ICON may outperform GFS for precipitation
in region X during convective summer situations.

ECMWF may perform better at longer-range synoptic prediction.

A regional model may dominate during local storm events.
```

The system should discover this from verification data rather than assuming it.

---

# 20. Forecast verification engine

This is essential.

Store predictions.

Later compare them with observations.

For every provider/model:

```text
forecast
vs
actual observation
```

Calculate:

### Temperature

* MAE
* RMSE
* bias
* correlation

### Wind

* speed MAE
* direction error
* gust MAE

### Precipitation

* probability calibration
* Brier score
* CSI
* POD
* FAR
* bias
* MAE
* precipitation accumulation error

### Timing

Measure:

```text
predicted rain start
actual rain start

predicted rain end
actual rain end
```

This allows the engine to learn which sources deserve trust.

---

# 21. Local bias correction

For every location, maintain historical correction models.

Example:

```text
raw forecast:
18.4°C

historical local bias:
+0.7°C

corrected:
17.7°C
```

For precipitation:

```text
model consistently predicts
rain 25 minutes too late

→ shift near-term precipitation probability
```

For wind:

```text
model underestimates local gusts
by ~15%
```

Do not hard-code these corrections.

Learn them from historical verification.

---

# 22. Downscaling

The user may request weather for:

```text
latitude / longitude
```

Do not blindly return the nearest grid point.

Perform local interpolation and correction.

Consider:

```text
elevation
terrain
coastline
urban density
land cover
distance from water
nearby observations
model grid
```

Temperature should receive elevation correction.

Wind should consider terrain exposure where appropriate.

Precipitation should NOT be naïvely interpolated like temperature.

---

# 23. Forecast horizons

Treat each horizon as a different forecasting problem.

### NOW

0–15 minutes:

```text
observations
radar
stations
```

### NOWCAST

15–120 minutes:

```text
radar motion
radar growth/decay
MET Norway nowcast where available
high-resolution NWP
observations
```

### SHORT RANGE

2–12 hours:

```text
high-resolution NWP
radar
observations
model ensemble
```

### MEDIUM RANGE

12–72 hours:

```text
regional NWP
ECMWF
ICON
GFS
ensemble
```

### EXTENDED

3–16 days:

```text
ensemble NWP
ECMWF
GFS
ICON
probabilistic products
```

Never pretend that 10-minute precision exists equally across all horizons.

The temporal resolution of the output can be 10 minutes while the underlying forecast certainty varies enormously.

---

# 24. 10-minute output engine

The UI should be capable of presenting a 10-minute timeline.

Example:

```text
NOW

02:10   12.4°C   rain 0%    wind 9 km/h
02:20   12.3°C   rain 3%    wind 10 km/h
02:30   12.2°C   rain 8%    wind 11 km/h
02:40   12.1°C   rain 22%   wind 11 km/h
02:50   12.0°C   rain 61%   wind 13 km/h
03:00   11.9°C   rain 84%   wind 15 km/h
```

But internally preserve the uncertainty.

---

# 25. Confidence is mandatory

Every forecast output should contain:

```text
value
probability
confidence
source_count
source_agreement
forecast_age
```

Example:

```json
{
    "temperature": 17.3,
    "temperature_confidence": 0.91,

    "rain_probability": 0.78,
    "rain_amount_mm": 0.9,

    "confidence": 0.84,

    "sources": 7,
    "model_agreement": 0.81
}
```

Never manufacture false precision.

A prediction of:

```text
17.342°C
```

does not mean we know the temperature to three decimal places.

Precision of representation ≠ precision of prediction.

---

# 26. Source health monitoring

Every provider should have:

```text
availability
latency
data_age
coverage
quality
error_rate
```

Example:

```text
OPEN-METEO
✓ healthy
data age: 4 min

DWD
✓ healthy
data age: 7 min

RADAR
✓ healthy
data age: 5 min

MET Norway
⚠ degraded
data age: 18 min
```

The provider router should automatically reduce the weight of degraded providers.

---

# 27. Failure handling

If a source disappears:

```text
provider unavailable
      ↓
reduce confidence
      ↓
recalculate ensemble
      ↓
continue using remaining sources
```

The application must never simply stop because one API failed.

---

# 28. Cache everything intelligently

Do not repeatedly request identical data.

Use:

```text
provider cache
normalized-data cache
radar tile cache
model-run cache
forecast cache
observation cache
```

Respect provider rate limits.

Persist model runs because historical forecast data is extremely valuable for verification.

---

# 29. Historical learning database

Store:

```text
location
timestamp
provider
model
forecast_horizon
forecast
actual
```

This eventually becomes one of the most valuable components of the system.

The system can learn:

```text
Which model is best here?
Which provider is best for rain?
Which provider predicts wind best?
How much does the local temperature differ from the model?
How reliable is radar here?
How quickly do storms normally move?
```

---

# 30. Weather-regime detection

Eventually classify the current weather regime.

Examples:

```text
clear stable
frontal
stratiform rain
convective
thunderstorm
snow event
fog
strong wind
heat event
cold air outbreak
```

Different forecasting methods should receive different weights under different regimes.

For example:

```text
CONVECTIVE STORM

radar:
VERY HIGH

high-resolution NWP:
HIGH

global NWP:
LOW

station:
MEDIUM
```

---

# 31. Severe-weather layer

Integrate official alerts separately from probabilistic forecasting.

Never replace government warnings with our own interpretation.

Display:

```text
official warning
+
our probabilistic assessment
```

Potential variables:

```text
lightning
CAPE
wind gusts
hail risk
heavy precipitation
freezing level
visibility
snow
ice
```

---

# 32. Precipitation is a special subsystem

Build precipitation separately from generic weather forecasting.

Output:

```text
precipitating: true/false

rain_probability
snow_probability
sleet_probability

rain_rate_mm_h
accumulation_10m
accumulation_30m
accumulation_1h
accumulation_3h
accumulation_24h

start_time
peak_time
end_time
```

The user-facing feature should be able to answer:

> "Will I get wet if I leave in 18 minutes?"

That is a more meaningful product than simply showing a weather icon.

---

# 33. "Will I get wet?" engine

Implement a dedicated event detector.

Given:

```text
user location
current time
walking/travel duration
```

calculate:

```text
probability of measurable precipitation
during the user's exposure window
```

Example:

```text
Leave now
Probability of rain during next 25 min: 8%

Leave in 20 min
Probability: 71%

Leave in 40 min
Probability: 92%
```

This should be derived from the precipitation probability field rather than a generic hourly weather icon.

---

# 34. Temperature forecasting

Temperature should combine:

```text
NWP
station observations
recent temperature trend
solar radiation
cloud cover
wind
humidity
elevation
urban effects
```

Near-term temperature can be corrected using the latest station observations.

Do not blindly interpolate hourly temperatures.

---

# 35. Wind forecasting

Treat wind as vector data.

Do not average:

```text
north 10 km/h
south 10 km/h
```

as:

```text
10 km/h
```

Represent:

```text
u component
v component
```

and calculate:

```text
speed
direction
gust
```

after fusion.

Use station observations to correct local wind.

---

# 36. Humidity / dew point

Calculate and cross-check:

```text
relative humidity
dew point
wet bulb temperature
apparent temperature
```

Avoid showing unnecessary meteorological information by default.

The backend can be extremely sophisticated while the UI remains simple.

---

# 37. Atmospheric pressure

Store:

```text
station pressure
sea-level pressure
pressure tendency
```

Pressure tendency can be useful for weather-regime detection.

---

# 38. Solar layer

Where available:

```text
GHI
DNI
DHI
cloud cover
sun elevation
sunrise
sunset
UV
```

Use cloud/radiation information to improve temperature estimates.

---

# 39. Astronomy

Keep astronomical calculations separate from meteorological forecasting.

Support:

```text
sunrise
sunset
civil twilight
nautical twilight
astronomical twilight
moonrise
moonset
moon phase
```

This is enrichment, not weather intelligence.

---

# 40. Geographic provider selection

Implement location-aware routing.

Example:

```text
USER IN CENTRAL EUROPE

Primary:
DWD / AROME / ICON-D2
Open-Meteo

Radar:
regional radar
DWD
OPERA where licensed

Fallback:
ECMWF
GFS
MET Norway
```

North America:

```text
NEXRAD
HRRR
NWS
Open-Meteo
GFS
```

Nordics:

```text
MET Norway Nowcast
FMI
SMHI
DMI
regional radar
Open-Meteo
```

The exact hierarchy should be configurable rather than hard-coded.

---

# 41. Provider capability matrix

Create a machine-readable registry:

```json
{
    "provider": "example",
    "coverage": "europe",
    "variables": [
        "temperature",
        "precipitation",
        "wind"
    ],
    "resolution": {
        "temporal": 15,
        "spatial": 1000
    },
    "update_interval": 300,
    "forecast_horizon_hours": 48,
    "radar": true,
    "commercial_use": true,
    "api_key": false
}
```

The engine should use this registry when selecting providers.

---

# 42. Licensing engine

Every provider must include:

```text
license
commercial_use
attribution_required
redistribution_allowed
api_restrictions
rate_limit
```

Never integrate a source simply because its data is technically accessible.

The engine must distinguish:

```text
free
open
non-commercial
commercially permitted
research-only
license-required
```

This is especially important for radar composites and national meteorological datasets.

---

# 43. Privacy

Location privacy is a first-class requirement.

Prefer:

```text
device coordinates
      ↓
direct weather provider
```

rather than:

```text
device
 ↓
our server
 ↓
weather provider
```

unless server-side processing is necessary.

Where possible:

* no user accounts
* no location history
* no unnecessary telemetry
* no location retention
* no third-party analytics required for weather functionality

---

# 44. Technology architecture

Prefer a modular backend.

Potential architecture:

```text
weather-engine/
    providers/
        open_meteo/
        met_norway/
        dwd/
        nws/
        radar/
        stations/

    normalization/
    quality/
    radar/
        decoder/
        clutter/
        motion/
        nowcast/

    forecasting/
        ensemble/
        fusion/
        bias_correction/
        downscaling/

    verification/
        metrics/
        observations/
        model_skill/

    routing/
    caching/
    alerts/
    astronomy/
    api/
```

Keep all meteorological calculations deterministic and testable.

---

# 45. Do not start with machine learning

First build:

```text
observations
+
radar
+
NWP
+
fusion
+
verification
```

Only after sufficient historical data exists should ML be introduced.

Then use ML where it genuinely improves forecast skill:

```text
bias correction
model weighting
precipitation probability calibration
radar growth/decay
local downscaling
error correction
```

Do not use AI simply because it sounds impressive.

---

# 46. Validation requirement

Before claiming "precision", establish benchmarks.

Create test locations covering:

```text
urban
rural
coastal
mountain
flat terrain
continental
maritime
```

Test:

```text
1h
3h
6h
12h
24h
48h
72h
```

and specifically:

```text
rain onset
rain cessation
rain amount
temperature
wind
gust
```

Compare:

```text
single provider
vs
multi-provider fusion
vs
radar nowcast
vs
radar + NWP fusion
```

The system should only adopt complexity that measurably improves forecast skill.

---

# 47. Important scientific principle

Do not confuse:

```text
higher temporal resolution
```

with:

```text
higher accuracy
```

A 10-minute forecast generated from hourly data is not inherently a 10-minute forecast.

Likewise:

```text
10-minute output
```

does not mean:

```text
10-minute meteorological certainty
```

Every output should retain its source resolution and confidence.

---

# 48. Final product philosophy

The application should answer questions like:

### "Is it raining?"

Use:

```text
radar
+
stations
+
METAR
+
near-term nowcast
```

### "Will it rain in 20 minutes?"

Use:

```text
radar motion
+
radar intensity
+
nowcast
+
high-resolution NWP
```

### "What will temperature be tonight?"

Use:

```text
regional NWP
+
ensemble
+
local station
+
bias correction
```

### "What will the weather be tomorrow?"

Use:

```text
multiple NWP models
+
ensemble
+
local correction
```

### "What will the weather be in 10 days?"

Use:

```text
ensemble probability
+
large-scale NWP
```

and explicitly communicate uncertainty.

---

# 49. The final forecast object

Design one unified internal representation:

```typescript
interface PrecisionForecast {
    timestamp: Date;
    location: GeoPoint;

    temperature: ForecastValue;
    apparentTemperature: ForecastValue;

    humidity: ForecastValue;
    dewPoint: ForecastValue;

    pressure: ForecastValue;

    wind: {
        speed: ForecastValue;
        direction: ForecastValue;
        gust: ForecastValue;
    };

    precipitation: {
        probability: ForecastValue;
        rate: ForecastValue;
        accumulation: ForecastValue;
        type: PrecipitationType;
    };

    visibility: ForecastValue;
    cloudCover: ForecastValue;

    radiation: ForecastValue;

    confidence: number;

    sources: SourceContribution[];

    uncertainty: Uncertainty;
}
```

Every `ForecastValue` should retain:

```text
value
unit
timestamp
valid_time
source
model
confidence
uncertainty
```

---

# 50. Ultimate objective

The system should become progressively better through verification.

The long-term loop is:

```text
FORECAST
   ↓
OBSERVATION
   ↓
ERROR
   ↓
MODEL PERFORMANCE DATABASE
   ↓
UPDATED SOURCE WEIGHTS
   ↓
BETTER FORECAST
   ↓
FORECAST
```

The application should therefore not be thought of as:

> "a weather app with lots of APIs."

It should be thought of as:

> **a continuously verified local weather estimation and forecasting engine that happens to have a UI.**

Build the meteorological engine first.

Build the beautiful interface second.


So the endgame isn't “find the best weather API.”

It's “make the APIs compete, measure who wins, and dynamically trust the winner.”

noticed that the speed of the circling gradient doesnt change but the level of wind speed climbed to level 2. The circling speed should be adaptive, so it circles faster when wind picks up or slows down. Also, we should have a little tile that shows up when you click the umbrella or the wind levels sines that explains what it is. So when umbrella is clicked - You should probably take an umbrella today. 
For wind levels, explains what the levels are and also mentions that the circling gradient is corresponding to the speed of the wind. Also, the line in the circle that is supposed to be time doesnt move as time goes, probably have to fix that one as well.
Also. I think all of the sunrise/sunset and Air pressure stuff need to be under a dropdown called "Advanced", thats a tile under the next 6 hours rain. We will include all the metrics we can possibly get there if people actually want to check in depth metrics, also humidity and anything else we can get - moon phases and such. But as for daily use noone needs that and it shouldnt trash the main screen. 