# Sprotify 1.0.0 Beta 16

## Playback and Android Auto fixes

- Prevent repeated completion of a retained, ended audio stream from rapidly skipping songs while the next track is still loading.
- Require the newly requested stream to be installed before it can report completion, including retries of the same song. Ignore errors from the retained old stream during lookup.
- Keep the Android Auto session in a loading state while resolving the next track or awaiting radio continuation, addressing unintended exits from Now Playing.
- Discard end-of-track radio continuation results after the playback request changes or the user pauses.

## Validation and device testing

The playback fix passed GitHub Android and Spotify unit tests, debug compilation, release lint and desktop UI tests. The signed release workflow runs tests again. Physical Samsung/BYD Android Auto behavior has not been verified here.

Please test automatic track changes with the phone locked, Radio continuation without leaving Now Playing, Previous on earlier skipped songs, manual Next/Previous, pause/resume, repeat and crossfade. The relationship between phone locking and the reported skipping is not established.

## Installation

Version code 16, version name `1.0.0-beta.16`, package `lv.guntify.player`, existing signing identity. Install over the existing beta without uninstalling to retain app data.
