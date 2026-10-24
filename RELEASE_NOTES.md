# Sprotify 1.0.0 Beta 14

## Android Auto

- Home displays collections directly, with artwork, titles and shelf labels from the phone Home presentation. It reuses the Spotify feed cache, local rotation, hidden-item preferences and saved discoveries. Large queue/continue/recommendation navigation tiles are removed. Native Queue remains on the player; Recents keeps history.
- Browse uses the same genre/mood catalogue and playlist search as the phone Search screen. Categories open playlists, then tracks. Compact category rows use playlist cover art when available. Artwork work has bounded concurrency and timeouts. Podcasts and the misleading keyword-based Made For You category are excluded in the car. This is a playlist search catalogue, not Spotify's official curated Browse API.
- Queue timeline entries are cached independently of progress updates. Resolved track durations remain stable across engine polls and selection changes. Play/pause no longer sends browse queue/Home children invalidations.
- Restore saved queue/track metadata before creating the media session, without opening the phone Activity or starting audio. Controllers inherit the shared button layout so later updates use one layout source.

## Validation and limits

Regression tests cover unchanged queue events during progress/pause/duration jitter, real queue edits, selection without reverting previous duration, and Home/Browse content policies, alongside existing tests. CI runs Android and Spotify tests, desktop tests, debug build and release lint.

No physical DHU/BYD test has been performed. The reported scroll reset and missing initial controls require verification on Android Auto, including a cold start with the phone locked. These are targeted fixes, not a claim that the device symptoms have already been reproduced or eliminated. Hosts control the final layout and button placement.

## Installation

Version code 14, version name `1.0.0-beta.14`, package `lv.guntify.player`, existing signing identity. The signed release can update Beta 13 without uninstalling. No Car App API 8 is added.
