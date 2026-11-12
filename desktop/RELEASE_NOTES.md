StreamFlix for Windows and macOS: the phone app's sources and look on a big screen.

**Downloads**
- Windows: `StreamFlix-Desktop-*-windows-setup.exe` (or the `.msi`)
- macOS (Apple Silicon and Intel): `StreamFlix-Desktop-*-macos.dmg`

VLC is built in; nothing else to install.

**New in 1.1.3**
- Same sources as the phone app 1.0.21: VixSrc is tried first, and servers that no longer work (2Embed, Moviesapi, Vidsrc.net, Vidflix, Vidlove, Videm, Vidrock) are gone, so playback starts faster.
- OpenSubtitles fixes and the other shared improvements from the phone app 1.0.15 to 1.0.21.

**New in 1.1.2**
- Updates itself. When a new version is out, a card in the corner offers it (also Settings → About → Check for updates).
  - Windows: click Update, StreamFlix closes, installs the new version in place and opens again (about a minute, no questions asked).
  - macOS: the new .dmg downloads and opens; drag StreamFlix into Applications to replace the old one.
- This version still has to be installed by hand once; every version after it arrives through the app.

**New in 1.1.1**
- Esc leaves the player again, also on macOS and after clicking a button or the seek bar. With a menu open, Esc closes the menu first.
- The quality chip (1080p, 4K…) is a real button now: it opens the server list with each server's quality, so you can switch from there. Before, clicking it only paused.
- Widescreen films at 1920x800 show as 1080p instead of 800p.
- Uses far less memory during and after playback (about 200 MB less in testing): Skia's GPU cache for video frames is capped at 64 MB instead of 256 MB.

**New in 1.1**
- True full screen: no title bar and the taskbar is hidden. Quit with Alt+F4 or Settings → Quit.
- Starts at 1080p, always. In the background it tests a 4K server on a second, muted player and only switches if that server really keeps up. The switch is lined up to the second, with no loading screen.
- Streams that turn out to be below 1080p are skipped automatically.
- Sage colours by default (Crimson and Amoled in Settings → Colours).
- Subtitle language is a drop-down: English, Spanish, French, German, Portuguese, Italian, Russian, Arabic, Turkish, Hungarian.
- Queue works like the phone: Watchlist, In progress and Watched are separate lists, and it opens on Watchlist.
- The logo is gone from the side rail.
- Crashes are written to `crash.log` in the data folder.

**What's in it**
- The same TMDb catalogue and servers as the Android app (Videasy, VixSrc, Vidzee, Vidlove…)
- Hardware decoding (DXVA2 on Windows, VideoToolbox on macOS)
- Home rows, search as you type, movie and show pages with seasons and episodes
- Queue with the Spin wheel, resume where you left off, next episode autoplay, subtitles and audio tracks

**Keys in the player**: Space play/pause · ←/→ 10 s · ↑/↓ volume · M mute · N next episode · Esc back

**macOS first launch**: the app isn't notarised yet. Right-click StreamFlix in Applications and choose Open (once), or run `xattr -cr /Applications/StreamFlix.app`.
