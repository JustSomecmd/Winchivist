# Winchivist

A Windows app for [TubeArchivist](https://www.tubearchivist.com) and [Jellyfin](https://jellyfin.org), the desktop
companion of [Tubedroid](https://github.com/JustSomecmd/findroid). Videos play with mpv, the same player Jellyfin's own
desktop app uses.

- Browse your TubeArchivist channels, videos and playlists, and search them
- Download videos and playlists on the server, and follow the download queue
- Watch with Jellyfin-style controls over the video: chapters, subtitle and audio tracks, speed, skip buttons for
  SponsorBlock segments and Jellyfin's intros and credits, and Jellyfin's preview pictures on the seek bar
- Pick up where you left off, and carry on with playlists where you stopped, also when you stopped on the phone
- Jellyfin's movies and shows, played straight from the server's files

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

The `tubearchivist` module is the same as Tubedroid's, so both apps talk to TubeArchivist the same way. Changes to it
are made in both repositories.

## License

GPL-3.0, like Tubedroid and Findroid, which it grew out of. See [LICENSE](LICENSE).
