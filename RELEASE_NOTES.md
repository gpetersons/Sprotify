# Sprotify 1.0.0 Beta 15

## Android Auto queue scrolling

Disable Media3 periodic session position broadcasts to work around Android Auto resetting the queue scroll position during playback (androidx/media#2192). Event-driven playback updates remain enabled.

This release changes only this session setting, the version and release metadata. Home, Browse, Search selection, queue insertion and the audio engine are unchanged from Beta 14. Search selection starting playback is not fixed in this release.

## Validation

CI runs the existing Android, Spotify and desktop tests, debug build and release lint. Device behavior has not been verified here. Please check queue scrolling during playback, progress display, pause/resume, seeking and track changes on Android Auto.

Reference: https://github.com/androidx/media/issues/2192

## Installation

Version code 15, version name `1.0.0-beta.15`, package `lv.guntify.player`, existing signing identity. The signed release can update Beta 14 without uninstalling.
