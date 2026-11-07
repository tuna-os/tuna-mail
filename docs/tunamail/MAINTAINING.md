# Maintaining the Tuna Mail fork

This repository, tuna-os/tuna-mail, is a soft fork of
[thunderbird/thunderbird-android](https://github.com/thunderbird/thunderbird-android). It adds a
third app, **Tuna Mail** (`org.tunaos.mail`, module `:app-tunamail`), the Android mail client of
TunaOS, next to upstream's K-9 Mail and Thunderbird for Android, built from the same shared code.
The fork exists to add native **Tuta** and **Proton** accounts, which need their providers'
proprietary protocols rather than IMAP. Gmail and Microsoft 365 work through upstream's IMAP +
OAuth support with Tuna Mail's own OAuth registrations ([oauth.md](oauth.md)). Everything else
should be the same app as upstream, kept that way by merging upstream continuously.

There are three rules:

1. **Upstream first.** Anything that is not Tuta, Proton, Tuna Mail branding or fork plumbing goes
   to thunderbird/thunderbird-android as a pull request, and reaches this fork through the
   upstream merge.
2. **Fork-only code stays in fork-only files.** Every line the fork changes in an upstream file is
   a future merge conflict.
3. **Never rewrite `main`.** Upstream is merged in, never rebased onto, so anyone tracking this
   fork can always pull.

## What belongs where

|                                                                Kind of change                                                                 |              Where it goes               |                                                     How                                                     |
|-----------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Bug fix, security fix, UI polish, docs, translations, anything provider-neutral                                                               | **Upstream**                             | A branch from `upstream/main` and a PR to thunderbird/thunderbird-android (see "Sending a change upstream") |
| A seam the fork needs in shared code (a generic backend hook, an account type the setup flow can offer) that has a use beyond Tuta and Proton | **Upstream**, phrased provider-neutrally | Propose it without mentioning Tuta, Proton or the fork; if upstream declines, it stays here as a touchpoint |
| The Tuna Mail app: identity, icon, theme, wiring, OAuth configuration                                                                         | **Fork only**                            | `app-tunamail/`, `components/ui/bolt/.../theme/tunamail/` and `bolt_tunamail_logo.xml`                      |
| Native Tuta and Proton accounts                                                                                                               | **Fork only**                            | New `backend:tuta` / `backend:proton` modules, wired in `app-tunamail`                                      |
| Build environment, fork CI, sync tooling, these docs                                                                                          | **Fork only**                            | `tools/build-env/`, `tools/tunamail/`, `docs/tunamail/`, `.github/workflows/tunamail-*.yml`                 |

When in doubt, ask whether the change would make sense in upstream with no Tuta or Proton account
and no Tuna Mail app. If it would, it goes upstream.

## The boundary, concretely

- **Fork-only files** are files that do not exist upstream. Add as much code there as needed;
  merges never conflict on them. Upstream's white-label design already supports this: an app
  module (`app-k9mail`, `app-thunderbird`, `app-tunamail`) chooses its features, binds its
  providers in Koin and supplies its resources, and a Bolt theme lives beside the other two in
  `components/ui/bolt`.
- **Touchpoints** are upstream files the fork modifies. They are listed in
  [`tools/tunamail/touchpoints.txt`](../../tools/tunamail/touchpoints.txt);
  `tools/tunamail/check-touchpoints.sh` fails when the fork modifies an upstream file that is not
  listed, and the upstream-sync workflow runs it after every merge. `settings.gradle.kts` applies
  `tools/tunamail/tunamail.settings.gradle.kts`, which includes the fork's modules; the rest of the
  list is the provider-neutral seams the native Tuta and Proton accounts need in account setup
  ([providers.md](providers.md)) and upstream fixes carried before upstream merges them
  ([upstream-candidates.md](upstream-candidates.md)), each with its reason in the file.
- **Rules for touchpoints:**
  - Keep each edit as small as the language allows: one line that refers to fork-only code, not
    inline logic.
  - Put fork additions in their own statement, away from lines upstream edits often. (Upstream
    appends new modules to the end of `settings.gradle.kts`, so the fork's `include` sits after
    the app block instead.)
  - Never reformat or reorder upstream code in a touchpoint file.
  - Adding a file to `touchpoints.txt` needs a reason in the commit message.
  - The list should only shrink over time. Each provider-neutral seam upstream accepts removes
    lines from it.
- **The app identity is one file.** `app-tunamail/brand.properties` holds the application id and
  the app name; the build reads them into `applicationId`, the `app_name` resource and the client
  info sent to servers. Renaming the app is a two-line change there (plus new OAuth
  registrations). The Kotlin package `org.tunaos.mail` is the Android namespace and does not need
  to follow a rename.

### How Tuna Mail differs from upstream's apps

`app-tunamail` is wired like `app-k9mail` (its closest upstream sibling), with these choices:

- **No telemetry, no funding, no Thundermail, no Thunderbird migration:** it depends on
  `feature:telemetry:noop`, `feature:funding:noop`, `feature:migration:launcher:noop`,
  `feature:onboarding:migration:noop` and the empty `feature:thundermail:k9mail` module, binds a
  `FundingSettings` that never reminds, and ships no Thundermail OAuth configuration.
- **Feature flags:** the flag catalog knows only the apps `k9` and `thunderbird`, so
  `TunaMailApp.appName` reports `k9` and Tuna Mail follows K-9 Mail's catalog overrides. Flags Tuna
  Mail must not follow are pinned by `TunaMailFeatureFlagProvider`, bound in the evaluator's
  in-memory slot (ahead of the remote and bundled catalogs): `thundermail_onboarding_enabled =
  false`, which hides the Thundermail sign-in panel in account setup, and upstream's Compose
  migration flags ([compose.md](compose.md)). In debug builds the runtime debug overrides still win
  over the pinned values. When upstream adds a flag for a Thunderbird-only service, pin it there.
  When upstream promotes or deletes a pinned flag, the merge stops compiling at
  `GeneratedFeatureFlagKey`; drop the pin then.
- **OAuth:** Google and Microsoft only, with client ids from the build ([oauth.md](oauth.md));
  a provider with no id is left out.
- **Native code:** `ndkVersion` and `abiFilters` (`arm64-v8a`, `x86_64`) are set in
  `app-tunamail/build.gradle.kts` for the Tuta (Rust) and Proton (Go) libraries.

## Keeping up with upstream

- **Automatic:** `.github/workflows/tunamail-upstream-sync.yml` runs daily, and on demand. It:
  1. fetches `thunderbird/thunderbird-android` `main`;
  2. merges it into our `main` with a merge commit;
  3. runs the touchpoint check, then `./gradlew :app-tunamail:assembleDebug
     :app-tunamail:testDebugUnitTest`;
  4. pushes if all of that passed.
- **On a conflict or failure** it pushes nothing, and opens or updates one issue labelled
  `upstream-sync` listing the files involved.
- **Set up once:** GitHub disables Actions on a new fork; enable them in the repository's Actions
  tab. Add an `UPSTREAM_SYNC_TOKEN` secret (a fine-grained token with contents and workflows
  write access on this repository): upstream edits `.github/workflows` often, and `GITHUB_TOKEN`
  may not push such a merge. Enabling Actions also enables upstream's own workflows on this
  fork. Some of them check the repository name and skip themselves here; review the Actions tab
  and disable the ones the fork has no use for (issue triage, translations, releases).
- **Resolving a sync by hand:**
  1. `git fetch upstream && git switch main && git merge upstream/main`
  2. Resolve in favour of upstream's code, then re-apply the fork's small touchpoint edit on top.
     If upstream changed an API `app-tunamail` uses, change `app-tunamail` to fit; `app-k9mail` in
     the same merge shows how upstream adapted its own app, and is usually the edit to copy.
  3. Build and test (see "Building"), and run `tools/tunamail/check-touchpoints.sh`.
  4. Commit the merge and push. Close the `upstream-sync` issue.
- **When upstream merges one of our pull requests,** the next sync brings it in. If the fork
  carried an early copy, the merge resolves to upstream's version; delete any fork-side duplicate
  in the same commit.

## Sending a change upstream

1. `git fetch upstream && git switch -c fix/<topic> upstream/main`. Never branch from our `main`:
   it contains Tuna Mail.
2. Follow upstream's [AGENTS.md](../../AGENTS.md) and [CONTRIBUTING.md](../CONTRIBUTING.md):
   Conventional Commits, the `:api`/`:internal` module boundary, Compose and the Bolt design
   system for UI, fakes over mocks in tests, and `./gradlew test lint detekt spotlessCheck`
   passing.
3. Make sure the diff never mentions Tuta, Proton, the fork or Tuna Mail (`org.tunaos.mail`).
4. Open the PR from `tuna-os:<branch>` to `thunderbird:main`. Say what was verified and what was
   not.
5. **Shipping it in the fork early (optional):** merge the same branch into our `main`. Don't
   cherry-pick: a merge keeps the commit identity, so the upstream merge later resolves cleanly.

## Building

The build host for this fork is a shared machine with little free memory, so builds run in one
pinned container image, defined in [`tools/build-env/Containerfile`](../../tools/build-env/Containerfile):
JDK 21 (per `.java-version`), the Android SDK platform and build tools, the NDK, Rust with the
Android targets and cargo-ndk, and Go with gomobile.

```bash
tools/build-env/run.sh --build-image                     # build or refresh the image
tools/build-env/run.sh ./gradlew :app-tunamail:assembleDebug
tools/build-env/run.sh ./gradlew :app-tunamail:testDebugUnitTest
```

`run.sh` mounts the current directory at `/work` and keeps caches (Gradle, Cargo, Go, the debug
keystore) under `~/android-build/cache`. The build host is shared, and its other services come
first, so `run.sh` serializes jobs with `flock ~/android-build/build.lock` (any other Gradle,
cargo-ndk or gomobile job on the host must take the same lock), refuses to start when less than
3 GiB is available, limits the container to 4500 MiB, caps Gradle with
[`tools/build-env/gradle.properties`](../../tools/build-env/gradle.properties) (2 GiB heap, Kotlin
compiled in-process, 3 workers), runs cargo with 6 jobs, and stops the Gradle daemon afterwards.
The debug APK lands in `app-tunamail/build/outputs/apk/debug/`.

Release builds are signed when the `tunamail.signing.storeFile`, `tunamail.signing.storePassword`,
`tunamail.signing.keyAlias` and `tunamail.signing.keyPassword` Gradle properties are set, and
unsigned otherwise.

## Licence

Upstream is Apache-2.0, and so is everything the fork adds here. Keep upstream's licence headers
and NOTICE intact.

The Tuta and Proton SDKs that `app-tunamail` links are GPL-3.0-or-later, so a Tuna Mail build that
includes them (every build of `:app-tunamail`) is distributed under GPL-3.0-or-later as a whole; the
fork's own source files stay Apache-2.0, which is compatible. NOTICE says so, and
[providers.md](providers.md) lists the libraries. K-9 Mail and Thunderbird builds from this repository
do not link them and keep upstream's licence.
