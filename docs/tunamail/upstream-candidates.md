# Upstream candidates

Changes the fork carries in upstream files that would make sense in thunderbird/thunderbird-android without Tuta,
Proton or Tuna Mail. Each is drafted here, phrased provider-neutrally, so it can be proposed upstream (see "Sending a
change upstream" in [MAINTAINING.md](MAINTAINING.md)); none has been sent yet. Once upstream merges one, its files
leave `tools/tunamail/touchpoints.txt`.

## Server settings screens for account types with their own sign-in

**Files:** `feature/account/edit/.../navigation/AccountEditExtension.kt` (new), `DefaultAccountEditNavigation.kt`.

**Draft PR text:** The server settings screens validate IMAP, POP3 and SMTP servers. An account whose backend signs
in by itself (the demo account today, or any future account type with a backend of its own) has nothing those screens
can edit, yet the account settings and the "authentication failed" notification both open them. This adds an
`AccountEditExtension` interface: the edit navigation asks the extensions bound in Koin whether one handles the
account, and if so shows its screens in place of the server settings. Without a bound extension nothing changes.

## Exporting and importing accounts of other server types

**Files:** `legacy/core/.../preferences/ServerTypeConverter.kt`, `SettingsImporter.kt`.

**Draft PR text:** Exporting settings fails with `AssertionError("Unsupported type: demo")` when an account's server
type is not IMAP, POP3 or SMTP, which already happens with the demo account. The exporter now writes any other type
upper case, as it does the three it knows, and the importer reads it back as before. Such an account signs in through
its own backend rather than with a server password, so the import no longer asks for one. Covered by
`OwnBackendAccountImportExportTest`.

## Account types offered during a manual setup

**Files:** `feature/account/setup/.../navigation/AccountSetupExtension.kt` (fork-added), `AccountSetupNavHost.kt`.

**Draft PR text:** Only together with the existing `AccountSetupExtension` seam, which upstream does not have yet:
`accountTypeRoute` lets an extension show a screen before the manual server settings, so an address the automatic
configuration does not recognize can still be set up as one of the extension's account types.
