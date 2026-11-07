# Tuna Mail

Tuna Mail (`org.tunaos.mail`) is the email app of TunaOS for Android: Thunderbird for Android with
native [Tuta](https://tuta.com) and [Proton Mail](https://proton.me/mail) accounts next to IMAP and
POP3. It is built from this soft fork of
[thunderbird/thunderbird-android](https://github.com/thunderbird/thunderbird-android) as a third app
beside upstream's K-9 Mail and Thunderbird, from the same shared code, and installs beside both.
Android 7.0 (API 24) or later.

## Install

Tuna Mail is distributed as signed APKs on this repository's
[GitHub releases](https://github.com/tuna-os/tuna-mail/releases), coming with the first release,
`tunamail-v0.1.0`. There is no Play Store or F-Droid listing yet.

**Download the APK** from the latest release:

- `tuna-mail-<version>-arm64-v8a.apk` for phones and tablets;
- `tuna-mail-<version>-x86_64.apk` for x86_64 devices and emulators.

Open it on the device and allow your browser or file manager to install apps when Android asks.

**Or install with [Obtainium](https://obtainium.imranr.dev/)**, which follows the releases and
updates the app (see [Updates](#updates)). On the device, open
[this link](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/tuna-os/tuna-mail)
(the deep link `obtainium://add/https://github.com/tuna-os/tuna-mail`), or in Obtainium tap
**Add App** and enter the source URL by hand. Before tapping **Add**, set:

| Setting | Value |
|---|---|
| App source URL | `https://github.com/tuna-os/tuna-mail` |
| Filter release titles by regular expression | `^Tuna Mail ` |
| Filter APKs by regular expression | `-arm64-v8a\.apk$` (`-x86_64\.apk$` on an x86_64 device) |
| Version extraction regular expression | `[0-9]+(\.[0-9]+)*` |
| Include prereleases | off |

**Verify the download.** Every release APK is signed with the Tuna Mail release key, whose
certificate SHA-256 is

```
41:47:22:60:29:05:33:43:2A:BF:DB:7D:80:11:FE:A8:25:67:9D:74:FB:36:03:6D:32:C6:31:D7:5B:F1:E8:AD
```

Check it, and the release's `SHA256SUMS`, with:

```sh
sha256sum -c SHA256SUMS --ignore-missing
apksigner verify --print-certs tuna-mail-<version>-arm64-v8a.apk
```

`apksigner` (from the Android SDK build tools) prints `Signer #1 certificate SHA-256 digest:` with
the same value, without the colons and in lower case. Android refuses an update signed with any
other key, so a later release can only replace an install made from these APKs.

## Updates

Releases are tagged `tunamail-v<version>` and published as "Tuna Mail <version>". Obtainium checks
for them and installs the new APK; a manually installed APK is updated by installing the next
release's APK over it, which keeps your accounts and mail. The release notes link the exact source
and the Tuta and Proton SDK versions each build used.

## Accounts

- **Tuta:** native, through the Tuta SDK. Sign in with the address and password, and an
  authenticator code if the account has one; Tuna Mail keeps the session, never the password.
  Confidential (password-protected) mail to people outside Tuta, from the composer's menu.
  Addresses on a custom domain are recognized by their MX record.
- **Proton Mail:** native, without Bridge, through Proton's own libraries. Sign in with the
  password, then the authenticator code, mailbox password or CAPTCHA Proton asks for. New mail is
  checked every 30 seconds while the screen is on, since Proton has no push for other clients.
- **IMAP and POP3:** any provider, as in Thunderbird for Android.
- **Gmail and Microsoft 365 / Outlook.com:** upstream's OAuth sign-in needs Tuna Mail's own
  registrations ([oauth.md](../docs/tunamail/oauth.md)), which release builds do not have yet; until
  then they offer password sign-in, which Google and Microsoft refuse for most accounts.

Security keys (U2F, WebAuthn) are not supported as a second factor for Tuta or Proton. How the
native accounts work, and their limits, is in [providers.md](../docs/tunamail/providers.md).

## Build from source

Builds run in a pinned container with JDK 21, the Android SDK and NDK, Rust and Go
([`tools/build-env/Containerfile`](../tools/build-env/Containerfile)), through podman:

```sh
git clone https://github.com/tuna-os/tuna-mail.git
cd tuna-mail
tools/build-env/run.sh --build-image
tools/build-env/run.sh ./gradlew :app-tunamail:assembleDebug
```

The APKs land in `app-tunamail/build/outputs/apk/debug/`. The Tuta and Proton SDKs are downloaded
as pinned, checksummed releases on the first build. A debug build is `org.tunaos.mail.debug` and
installs beside a release. Building, the resource limits `run.sh` applies, signing and releasing
are in [MAINTAINING.md](../docs/tunamail/MAINTAINING.md#building).

## Relationship to upstream

This repository is a soft fork of
[thunderbird/thunderbird-android](https://github.com/thunderbird/thunderbird-android). It adds the
`:app-tunamail` app and the Tuta and Proton backends in files of its own, and merges upstream's
`main` every day, never rebasing. Everything that is not Tuta, Proton, the Tuna Mail app or fork
plumbing goes to upstream first; [MAINTAINING.md](../docs/tunamail/MAINTAINING.md) has the rules,
and [`tools/tunamail/touchpoints.txt`](../tools/tunamail/touchpoints.txt) lists the few upstream
files the fork changes. The K-9 Mail and Thunderbird apps built from this repository are
upstream's.

Tuna Mail is not affiliated with or endorsed by Mozilla, MZLA Technologies, Tuta GmbH or Proton
AG. Neither Tuta nor Proton endorses third-party clients built this way; use those accounts knowing
that.

GitHub shows this page in place of the repository's [`README.md`](../README.md), which stays
upstream's own so the daily merge never conflicts on it.

## Licence

Tuna Mail is distributed under the GNU General Public License v3.0 or later (GPL-3.0-or-later):
it links the [Tuta](https://github.com/tuna-os/tuta-unofficial-sdk) and
[Proton](https://github.com/tuna-os/proton-unofficial-sdk) SDKs, which are GPL-3.0-or-later. The
source in this repository, upstream's and the fork's own, is Apache-2.0 ([LICENSE](../LICENSE),
[NOTICE](../NOTICE)), and K-9 Mail and Thunderbird builds from it keep that licence.

## Issues

Report problems with Tuna Mail, and anything about Tuta or Proton accounts, on
[tuna-os/tuna-mail's issues](https://github.com/tuna-os/tuna-mail/issues). A bug that also happens
in Thunderbird for Android belongs
[upstream](https://github.com/thunderbird/thunderbird-android/issues); please don't report Tuna
Mail there.
