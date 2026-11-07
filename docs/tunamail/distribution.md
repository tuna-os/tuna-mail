# Distributing Tuna Mail

**Decision:** Tuna Mail is distributed as signed APKs on this repository's GitHub releases, and
installed from there directly or kept up to date with [Obtainium](https://obtainium.imranr.dev/).
F-Droid is not possible yet; what it needs is below. Google Play is out of scope.

## GitHub releases

Every `tunamail-v<versionName>` tag becomes a release "Tuna Mail <versionName>"
([MAINTAINING.md](MAINTAINING.md#releasing)) with:

- `tuna-mail-<versionName>-arm64-v8a.apk`: phones and tablets;
- `tuna-mail-<versionName>-x86_64.apk`: x86_64 devices and emulators;
- `SHA256SUMS`;
- the source: GitHub's source archives of the tag, and links to the tag's tree and to the Tuta and
  Proton SDK sources the build used (Tuna Mail builds are GPL-3.0-or-later, see MAINTAINING.md).

Both APKs have the same `versionCode`; nothing has to choose between them by version code, since
each device installs the one for its ABI. Both are signed with the release key, whose fingerprints
are in [oauth.md](oauth.md#release-key). Check a download with

```bash
sha256sum -c SHA256SUMS --ignore-missing
apksigner verify --print-certs tuna-mail-<versionName>-arm64-v8a.apk   # SHA-256 41:47:22:60:…:E8:AD
```

## Obtainium

Obtainium installs and updates apps straight from GitHub releases. Add Tuna Mail with:

| Setting | Value |
|---|---|
| App source URL | `https://github.com/tuna-os/thunderbird-android` |
| Filter release titles by regex | `^Tuna Mail ` |
| Filter links by regex | `-arm64-v8a\.apk$` (or `-x86_64\.apk$` on an x86_64 device) |
| Version string extraction regular expression | `[0-9]+(\.[0-9]+)*` (the tags are `tunamail-v<versionName>`) |
| Include prereleases | off |

The title filter keeps Obtainium on Tuna Mail releases should the repository ever carry others; the
version regex turns the tag `tunamail-v0.1.0` into `0.1.0`, the app's `versionName`, so Obtainium's
version detection matches the installed app.

## F-Droid (not yet)

F-Droid's main repository builds every app from source on its own servers and signs it with its own
key, unless the build is reproducible (then it ships our APK with our signature, as it does for
Thunderbird for Android). For Tuna Mail that means:

- **The Tuta and Proton SDKs must be built from source.** The build currently downloads them as
  prebuilt Maven zips (`tools/tunamail/tunamail.settings.gradle.kts`): the Rust `libtuta_ffi.so`
  from tuna-os/tuta-unofficial-sdk and the Go `libgojni.so` from tuna-os/proton-unofficial-sdk.
  F-Droid rejects prebuilt binaries and downloads from outside its trusted repositories, so the
  recipe would build both SDKs as srclibs (Rust with cargo-ndk, Go with gomobile, both at the pinned
  tags) and Tuna Mail would need a way to use those builds instead of the downloads. JNA's prebuilt
  `libjnidispatch.so`, which the Tuta SDK pulls from Maven Central, may have to be built from source
  too.
- **Reproducible builds** of the app and both native libraries, so F-Droid can ship the APKs signed
  with the release key and existing installs keep updating; otherwise an F-Droid install is signed
  by F-Droid and cannot update a GitHub install, or the reverse.
- **Anti-features:** expect *Non-Free Network Services* (`NonFreeNet`): Tuta and Proton are
  proprietary services the app offers in its account setup. F-Droid marks the official Tuta app
  `NonFreeNet` ("tethered to the Tutanota eMail service"); Tuna Mail is less tied to them, since
  it also speaks IMAP, but promoting a proprietary service is enough for the flag. Google and
  Microsoft sign-in is upstream's OAuth support through the browser, with no proprietary library,
  and K-9 Mail and Thunderbird carry no anti-feature for it.

Until the SDKs build reproducibly from source inside the Tuna Mail build, GitHub and Obtainium are
the channels.
