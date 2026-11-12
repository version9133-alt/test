# Signing keys

## `debug.keystore` (committed, **not** a secret)

`tools/build-apk.sh`, the Gradle project and the GitHub Actions workflow all sign with this key by
default so that every build - local or CI - can be installed *over* the previous one without
uninstalling first.

| Field          | Value                                |
|----------------|--------------------------------------|
| Type           | PKCS12                               |
| Alias          | `opcam-debug`                        |
| Store password | `android`                            |
| Key password   | `android`                            |
| Algorithm      | RSA 2048, SHA256withRSA, 30 years    |

Because the key is public, treat the resulting APKs like any Android *debug* build: fine for
sideloading on your own phone, **not** for publishing to a store or sharing widely.

## Using a private release key

Generate one (any JDK's `keytool`, or Android Studio > Build > Generate Signed Bundle/APK):

```sh
keytool -genkeypair -keystore my-release.jks -storetype PKCS12 -alias opcam \
        -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950
```

Then point the build at it (the script never prints the passwords):

```sh
KEYSTORE=/path/my-release.jks KS_PASS='***' KEY_ALIAS=opcam KEY_PASS='***' tools/build-apk.sh
```

Keep `*.jks` / `*.p12` / `*.properties` out of git - `.gitignore` already blocks them; only
`debug.keystore` and this file are allowed through. An app signed with a different key is a
different app to Android: you must uninstall the debug-signed build before installing a
release-signed one.
