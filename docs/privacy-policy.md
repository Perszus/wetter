# Privacy policy

**Last updated: 2 September 2026**

Wetter collects nothing about you. There is no account, no analytics, no crash
reporting, no advertising and no identifier of any kind. Nobody, including the
developer, learns that you use this app.

This document exists because Google Play requires every app to publish one, not
because there is much to say.

## What leaves your device

One thing: **a latitude and a longitude**, sent to a weather service so that it
can return a forecast for that place.

That is unavoidable — a weather app that never says where you are cannot tell
you the weather. What is avoidable, and avoided here, is everything else.

- The request goes **directly from your device to the weather service**. There is
  no server belonging to this project in between. There is no server belonging
  to this project at all.
- The request carries **no identifier**: no account, no device id, no advertising
  id, no cookie, no session.
- Coordinates sent to MET Norway are **truncated to four decimal places** — about
  eleven metres — as their terms ask. This also caps how precisely a request can
  describe where somebody is.

The weather services receive these requests under their own privacy policies:

- Open-Meteo — <https://open-meteo.com/en/terms>
- MET Norway — <https://api.met.no/doc/TermsOfService>

## What stays on your device

- The locations you have chosen
- The most recent forecast for each of them, so the app works offline
- Your settings

All of it is stored in the app's private storage. **Cloud backup and
device-to-device transfer are switched off explicitly**, so this data is not
copied off the device by the operating system. Uninstalling the app deletes it.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | To ask a weather service for a forecast. This is the only permission the app requires. |
| `ACCESS_NETWORK_STATE` | To tell "you are offline" apart from "the weather service is not answering", so the app can say which. |

**Location access is optional.** If a future version offers to use your device's
position, it will ask at the moment you choose that, and the app will remain
fully usable if you decline. A location obtained this way is used to fetch a
forecast and is not sent anywhere else.

## Children

Wetter collects no personal information from anyone, of any age.

## Changes

If this policy ever changes, the change will appear in this file, whose full
history is public at <https://github.com/Perszus/wetter>. The date at the top
will be updated.

## Verifying any of this

You do not have to take it on trust. Wetter is free software: the complete
source is at <https://github.com/Perszus/wetter>, and you can read exactly which
requests it makes and build it yourself.

## Contact

Open an issue at <https://github.com/Perszus/wetter/issues>.
