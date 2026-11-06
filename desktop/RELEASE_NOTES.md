StreamFlix for Windows and macOS: the phone app's sources and look on a big screen.

**Downloads**
- Windows: `StreamFlix-Desktop-*-windows-setup.exe` (or the `.msi`)
- macOS (Apple Silicon and Intel): `StreamFlix-Desktop-*-macos.dmg`

VLC is built in; nothing else to install.

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
