# Tuna Mail OAuth registrations

OAuth clients are registered to one app: a package name plus the certificate it is signed with.
Tuna Mail cannot use the client ids K-9 Mail and Thunderbird ship, so it ships none of its own
in source. The ids are build settings, empty by default:

|        Setting (Gradle property)         |        Environment variable         |                                                What it is                                                |
|------------------------------------------|-------------------------------------|----------------------------------------------------------------------------------------------------------|
| `tunamail.oauth.google.clientId`         | `TUNAMAIL_GOOGLE_CLIENT_ID`         | Client id of a Google OAuth client of type **Android**                                                   |
| `tunamail.oauth.microsoft.clientId`      | `TUNAMAIL_MICROSOFT_CLIENT_ID`      | Application (client) id of a Microsoft Entra app registration                                            |
| `tunamail.oauth.microsoft.signatureHash` | `TUNAMAIL_MICROSOFT_SIGNATURE_HASH` | Base64 SHA-1 of the signing certificate, exactly as Entra shows it (e.g. `Dx8yUsuhyU3dYYba1aA16Wxu5eM=`) |

`app-tunamail/build.gradle.kts` reads them into `BuildConfig`, and
`TunaMailOAuthConfigurationFactory` leaves out every provider whose settings are empty
(Microsoft needs both its id and its hash). Without them the app still builds and runs; adding
a Gmail or Outlook account then falls back to password sign-in, which Google and Microsoft 365
refuse for most accounts. AOL, Yahoo, Fastmail and Thundermail are not configured at all: their
upstream registrations are bound to the upstream apps' redirect URIs.

Set them in `~/.gradle/gradle.properties` (never commit them), on the command line
(`./gradlew -Ptunamail.oauth.google.clientId=… :app-tunamail:assembleRelease`), or in the
environment, which `tools/build-env/run.sh` passes into the build container. Client ids are not
secrets, but keep them out of the repository so forks of the fork register their own.

## One registration per package and key

Each build type is its own app to Google and Microsoft:

|   Build   |      Package name       |                    Signed with                    |
|-----------|-------------------------|---------------------------------------------------|
| `debug`   | `org.tunaos.mail.debug` | the debug keystore of the machine that builds it  |
| `release` | `org.tunaos.mail`       | the release key (`tunamail.signing.*` properties) |

The package name comes from `app-tunamail/brand.properties` (`applicationId`, plus `.debug` for
debug builds). Renaming the app means new registrations.

Builds made with `tools/build-env/run.sh` use the debug keystore kept in
`~/android-build/cache/android/debug.keystore` on the build host, so debug registrations stay
valid across builds on that host. A debug APK built anywhere else has a different key.

### Getting the certificate fingerprints

SHA-1, hex (for Google):

```bash
keytool -list -v -keystore <keystore> -alias <alias>
# debug keystore in the build container:
tools/build-env/run.sh keytool -list -v -keystore /root/.android/debug.keystore \
    -alias androiddebugkey -storepass android
```

SHA-1, base64 (Microsoft's "signature hash"):

```bash
keytool -exportcert -keystore <keystore> -alias <alias> | openssl sha1 -binary | openssl base64
```

## Google (Gmail)

In the Google Cloud console, project of your choice:

1. **OAuth consent screen** (Google Auth Platform): user type External, app name "Tuna Mail",
   and add the scope `https://mail.google.com/`. This is a *restricted* scope: until the app
   passes Google's verification and security assessment it works only for the test users listed
   on the consent screen (at most 100), and their tokens expire after 7 days.
2. **Credentials → Create OAuth client ID → Android**, once per row of the table above:
   - Package name: `org.tunaos.mail` (or `org.tunaos.mail.debug`)
   - SHA-1 certificate fingerprint: the hex SHA-1 of the key that signs that build
   - **Advanced settings → Enable custom URI scheme**: on. Tuna Mail signs in through the browser
     with AppAuth and receives the result on the redirect URI
     **`org.tunaos.mail:/oauth2redirect`** (debug: `org.tunaos.mail.debug:/oauth2redirect`).
     Android clients have no redirect URI field; the scheme is the package name.
3. Put the client id (`…apps.googleusercontent.com`) in `tunamail.oauth.google.clientId` for
   that build.

## Microsoft (Microsoft 365 and Outlook.com)

In the Microsoft Entra admin center, **App registrations → New registration**:

1. Name "Tuna Mail"; supported account types **Accounts in any organizational directory and
   personal Microsoft accounts**; no redirect URI yet.
2. **Authentication → Add a platform → Android**, once per row of the table above:
   - Package name: `org.tunaos.mail` (or `org.tunaos.mail.debug`)
   - Signature hash: the base64 SHA-1 of the key that signs that build
   - Entra then shows the redirect URI
     **`msauth://org.tunaos.mail/<signature hash, URL-encoded>`**, e.g.
     `msauth://org.tunaos.mail/Dx8yUsuhyU3dYYba1aA16Wxu5eM%3D`. The app builds the same URI from
     `tunamail.oauth.microsoft.signatureHash`, and its manifest already accepts
     `msauth://<applicationId>/…`.
3. **API permissions → Add a permission**, all delegated:
   - Microsoft Graph: `openid`, `profile`, `email`, `offline_access`
   - Office 365 Exchange Online (APIs my organization uses → "Office 365 Exchange Online"):
     `IMAP.AccessAsUser.All`, `SMTP.Send`
4. Put the Application (client) id in `tunamail.oauth.microsoft.clientId` and the signature hash
   in `tunamail.oauth.microsoft.signatureHash` for that build.

Microsoft 365 tenants can block user consent to third-party apps; their admins then have to
grant consent to "Tuna Mail" once for the tenant.

## Checking a build

The values end up in `app-tunamail/build/generated/source/buildConfig/<buildType>/org/tunaos/mail/BuildConfig.java`
(`OAUTH_GOOGLE_CLIENT_ID`, `OAUTH_MICROSOFT_CLIENT_ID`, `OAUTH_MICROSOFT_SIGNATURE_HASH`). With
them set, adding an account on `imap.gmail.com` or `outlook.office365.com` shows the provider's
sign-in button instead of a password field.
