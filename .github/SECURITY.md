# Tuna Mail security policy

This is the security policy of Tuna Mail (`org.tunaos.mail`), the app built from this soft fork of
[thunderbird/thunderbird-android](https://github.com/thunderbird/thunderbird-android). GitHub shows
this page in place of the repository's [`SECURITY.md`](../SECURITY.md), which stays upstream's own
and speaks for Thunderbird for Android and K-9 Mail: its report form and its signing certificates
are theirs, not Tuna Mail's.

## Reporting a vulnerability

**Do not open a public issue for a vulnerability.**

Report it here when it is in Tuna Mail or in what the fork adds: Tuta and Proton accounts (sign-in,
stored sessions, sending, sync), the Tuna Mail app and its release builds, or the fork's build and
CI.

1. Open **Security → Report a vulnerability** on this repository
   ([new report](https://github.com/tuna-os/tuna-mail/security/advisories/new)) and describe the
   affected component, a minimal reproduction, the impact, and a suggested fix if you have one.
2. If that button is not offered to you, open an ordinary issue titled `security contact request`
   with **no details in it**. A maintainer will open a private advisory and add you to it, and the
   conversation continues there.

A vulnerability that also exists in Thunderbird for Android or K-9 Mail, with no Tuta or Proton
account and no Tuna Mail app involved, belongs upstream: use
[upstream's report form](https://github.com/thunderbird/thunderbird-android/security/advisories/new).
Tuna Mail receives the fix through the daily merge of upstream. When you cannot tell which side a
flaw is on, report it here.

The Tuta and Proton protocol code lives in
[tuna-os/tuta-unofficial-sdk](https://github.com/tuna-os/tuta-unofficial-sdk) and
[tuna-os/proton-unofficial-sdk](https://github.com/tuna-os/proton-unofficial-sdk). A flaw there
reaches Tuna Mail through the SDK release the build pins
([`tools/tunamail/tunamail.settings.gradle.kts`](../tools/tunamail/tunamail.settings.gradle.kts)),
so it can be reported in either place.

## Verifying a download

Every Tuna Mail release APK is signed with the Tuna Mail release key, whose certificate SHA-256 is

```
41:47:22:60:29:05:33:43:2A:BF:DB:7D:80:11:FE:A8:25:67:9D:74:FB:36:03:6D:32:C6:31:D7:5B:F1:E8:AD
```

Check it with

```sh
apksigner verify --print-certs tuna-mail-<version>-arm64-v8a.apk
```

The fingerprints in upstream's `SECURITY.md` belong to Thunderbird, Thunderbird Beta and K-9 Mail
and match no Tuna Mail APK. The [front page](README.md#install) says how to check a release's
`SHA256SUMS` as well.
