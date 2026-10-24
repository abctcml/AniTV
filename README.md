# AniTV — Android anime app starter

Dark Apple TV-inspired browsing UI, AniMapper API integration, anime search, metadata/detail pages, provider/episode lookup, Media3 playback controls, favorites, and Android DownloadManager support for direct HTTPS video files that the user is authorized to save.

## Build an APK from your phone with GitHub
1. Create a new GitHub repository and upload the contents of this folder (the `.github` folder must be included).
2. Open **Actions** → **Build AniTV APK** → **Run workflow** (or push to `main`).
3. Open the completed workflow run → **Artifacts** → download `AniTV-debug-apk`.
4. Extract the ZIP and install `app-debug.apk` on your Android phone. Android may ask you to allow installs from the browser/file manager you used.

## API
Base URL: `https://api.animapper.net/api/v1`
- Search: `/search?mediaType=ANIME&limit=24&offset=0&sortBy=POPULARITY&sortOrder=DESC`
- Metadata: `/metadata?id={id}`
- Episodes: `/stream/episodes?id={id}&provider={provider}&limit=100&offset=0`
- Source: `/stream/source?episodeData={episodeId}&provider={provider}&server={server}`
- Autocomplete endpoint documented by AniMapper: `/autocomplete?q={query}&mediaType=ANIME&limit=5`

AniMapper documents a limit of 60 requests/minute; the app surfaces 429 errors instead of retrying aggressively. Provider availability and stream URLs depend on AniMapper mappings and provider uptime.

## Offline downloads
The app deliberately downloads only direct HTTPS video files (not HLS `.m3u8` playlists). Use this only for content you have permission to download. This is a starter implementation, not a claim of feature parity with Netflix. Profiles, subscriptions, DRM, recommendations, casting, captions/audio track menus, and a production download library still require additional work.
