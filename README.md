# Nuvio TV – XMPlayer edition

**XMPlayer's modified build of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) for Android TV and Google TV.**

> This is an unofficial build. It is **not affiliated with, endorsed by, or supported by the Nuvio project**.
> "Nuvio" and its logos belong to their owners. Please report problems with this build to XMPlayer, not to the Nuvio team.

## What this build adds

- **Sign in with XMPlayer** by QR code: your XMPlayer history, watch progress, favourites and collections sync both ways.
- **Cloud DVR recordings** from XMPlayer, playable in the app.
- **Trakt and Simkl through XMPlayer** (Premium and Pro), with no separate sign-in when they're already linked in XMPlayer.
- **Movie and show details** through XMPlayer's backend, with no API keys needed.
- Installs **beside** the official Nuvio app (its own package, `com.nuvio.tv.xmplayer`) and updates itself from this repository's releases.

## Download

[Latest release](https://github.com/Xerge-xmp/NuvioTV/releases/latest): pick **arm64-v8a** for most newer devices,
**armeabi-v7a** for older or lower-cost ones, or **universal** if you're not sure.

Looking for the official Nuvio app? Get it from [nuvio.tv](https://nuvio.tv).

## Build from source

```bash
git clone https://github.com/Xerge-xmp/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.
Service settings (account server, XMPlayer client id) go in `local.properties`; see `local.example.properties`.

## License

[GNU General Public License v3.0](./LICENSE), the same as the original Nuvio TV.
This is a modified version of Nuvio TV; every change is in this repository's history on top of the original project's code.
