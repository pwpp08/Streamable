StreamFlix for Windows and macOS: the phone app's sources and look on a big screen.

**Downloads**
- Windows: `StreamFlix-Desktop-*-windows-setup.exe` (or the `.msi`)
- macOS (Apple Silicon and Intel): `StreamFlix-Desktop-*-macos.dmg`

VLC is built in; nothing else to install.

**What's in it**
- The same TMDb catalogue and servers as the Android app (Videasy, VixSrc, Vidzee, Vidlove…)
- 4K first: servers are checked side by side and the sharpest stream wins (4K, then 1440p, then 1080p). Nothing below 1080p is picked automatically, and the server menu shows each server's quality.
- Hardware decoding (DXVA2 on Windows, VideoToolbox on macOS) for smooth 4K
- Home rows, search as you type, movie and show pages with seasons and episodes
- Queue: In progress, Watchlist and Watched, plus the Spin wheel
- Resume where you left off, next episode autoplay, subtitles and audio tracks, fullscreen

**Keys in the player**: Space play/pause · ←/→ 10 s · ↑/↓ volume · F fullscreen · M mute · N next episode · Esc back

**macOS first launch**: the app isn't notarised yet. Right-click StreamFlix in Applications and choose Open (once), or run `xattr -cr /Applications/StreamFlix.app`.
