# Winchivist

A Windows app for [TubeArchivist](https://www.tubearchivist.com) and [Jellyfin](https://jellyfin.org), the desktop
companion of [Tubedroid](https://github.com/JustSomecmd/findroid). Videos play with mpv, the same player Jellyfin's own
desktop app uses.

- Browse your TubeArchivist channels, videos and playlists, and search them
- Download videos and playlists on the server, and follow the download queue
- Watch with Jellyfin-style controls over the video: chapters, subtitle and audio tracks, speed, skip buttons for
  SponsorBlock segments and Jellyfin's intros and credits, and Jellyfin's preview pictures on the seek bar
- Pick up where you left off, and carry on with playlists where you stopped, also when you stopped on the phone:
  what was played last comes first, on whichever device that was
- Jellyfin's movies and shows, played straight from the server's files, with its libraries, collections, playlists,
  favourites and search
- A JRWI archive, when a [JRWI archiver](https://github.com/JustSomecmd/jrwi-archiver) is set up: its feed, shows and
  posts with their comments and files, an audio player beside the video player, transcripts that follow what plays,
  and the archiver's status and controls
- Windows notifications when Jellyfin adds a movie or an episode and when YouTube's bot detection stops a
  TubeArchivist task. Two switches, off at first, keep Winchivist in the tray and start it with Windows
- A compute box: this PC can hear episodes for the JRWI archiver's timed transcripts, by running the archiver's own
  `jrwi_hear.py` with a Python on the PC that has faster-whisper. The program is fetched from the archiver whenever
  its version changes. Unless changed, a card in the window asks first whether now is a good time, and nothing is
  heard before the answer is yes

## Install and update

Download the `.msi` from the [Releases](https://github.com/JustSomecmd/Winchivist/releases) page and run it. Winchivist
checks for new versions itself and installs them when you agree; running a newer `.msi` over an older install works
too. Settings are kept either way.

Versions up to 1.0.x were released from the Tubedroid repository. From 1.1 on, they come from here.

## Building

Every push to `main` builds the installer on GitHub and publishes it as a release. Pictures of each page, drawn with
sample data, go to the `screens` branch.

To run it yourself, you need JDK 17. On Windows, download an mpv-dev-x86_64 build (for example from
[zhongfly/mpv-winbuild](https://github.com/zhongfly/mpv-winbuild/releases)) and put its `libmpv-2.dll` in
`app/resources/windows-x64`, then:

```
./gradlew :app:run
```

The `tubearchivist` and `jrwi` modules are the same as Tubedroid's, so both apps talk to TubeArchivist and the JRWI
archiver the same way. Changes to them are made in both repositories. `app/src/main/resources/hear/jrwi_hear.py` is a
copy of the JRWI archiver's PC program (`jrwi/pc/jrwi_hear.py` there), for an archiver older than 0.14.10; a newer one
hands out its own copy, which Winchivist fetches and runs in its place.

## License

GPL-3.0, like Tubedroid and Findroid, which it grew out of. See [LICENSE](LICENSE).
