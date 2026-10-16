# Privacy policy

**Last updated: 16 October 2026**

Wetter collects nothing about you. There is no account, no analytics, no crash
reporting, no advertising and no identifier of any kind. Nobody, including the
developer, learns that you use this app.

## What leaves your device

**Locations.** The app asks weather services about the places you have chosen,
and a request about a place has to say where it is: a latitude and a longitude,
or, for the radar and the maps, the map tiles covering it. That is unavoidable
for a weather app. Everything else is avoided.

**What you type into the search box**, sent to the place search so it can find
matching places.

- Requests go **directly from your device to each service**. There is no server
  belonging to this project in between, and no server belonging to this project
  at all.
- Requests carry **no identifier**: no account, no device id, no advertising
  id, no cookie, no session. They carry a User-Agent naming the app and its
  version, which MET Norway's terms require.
- Coordinates sent to MET Norway are **truncated to four decimal places** —
  about eleven metres — as their terms ask.

The services and what each is asked:

| Service | Asked for | Policy |
|---|---|---|
| Open-Meteo | Forecasts, model comparisons, air quality and past weather for a location; place search | <https://open-meteo.com/en/terms> |
| MET Norway | Forecasts for a location | <https://api.met.no/doc/TermsOfService> |
| RainViewer | Radar images of the area around a location | <https://www.rainviewer.com/privacy.html> |
| NOAA / NWS Aviation Weather Center | Airport weather reports near a location, used only to check past forecasts | <https://www.weather.gov/privacy> |
| Photon (komoot) | The name of a point you have placed a pin on | <https://www.komoot.com/privacy> |
| OpenStreetMap | Map tiles for the area you are looking at | <https://osmfoundation.org/wiki/Privacy_Policy> |

## What stays on your device

- The locations you have chosen, and your settings
- The latest forecast, radar projection and model comparison for each location,
  so the app works offline
- What each location's weather usually does at this time of year
- A record of past forecasts and what actually happened, which the app uses to
  correct its own forecasts for that place
- Which warnings have already been shown, so none is shown twice
- Which services have recently failed to answer

All of it is stored in the app's private storage. **Cloud backup and
device-to-device transfer are switched off explicitly**, so the operating
system does not copy it off the device. Uninstalling the app deletes it.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | To ask the services above about a location. |
| `ACCESS_NETWORK_STATE` | To tell "you are offline" apart from "the service is not answering". |
| `ACCESS_COARSE_LOCATION` | Optional. One approximate fix when you ask the map for your current location. The app does not follow your location, in the background or otherwise. |
| `POST_NOTIFICATIONS` | Optional. Severe weather warnings, which can be switched off in Settings. |
| `RECEIVE_BOOT_COMPLETED` | To restart the background refresh after the phone restarts. |
| `SCHEDULE_EXACT_ALARM` | Optional. To time the background refresh to each new radar image. |

The optional permissions are asked for when they are first needed, or offered in
Settings. Declining any of them leaves the rest of the app working.

## Children

Wetter collects no personal information from anyone, of any age.

## Changes

If this policy changes, the change will appear in this file, whose full history
is public at <https://github.com/Perszus/wetter>. The date at the top will be
updated.

## Verifying any of this

You do not have to take it on trust. Wetter is free software: the complete
source is at <https://github.com/Perszus/wetter>, and you can read exactly which
requests it makes and build it yourself.

## Contact

Open an issue at <https://github.com/Perszus/wetter/issues>.
