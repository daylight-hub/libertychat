# Security Policy

## Verifying a download

Each Liberty Chat release on GitHub includes a `.sha256` file for every APK.
Check the APK against it before installing:

```bash
sha256sum -c libertychat-x.y.z-official-rns-py-arm64-v8a.apk.sha256
```

If the check fails, don't install the APK. Delete it and download it again from
this repository's Releases page.

Android checks that every update is signed with the same key as the installed
app, so once your first install is verified, later updates are protected.

## Reporting a vulnerability

Report security problems through this repository's Issues page with the
"security" label, or privately through the contact details in the app
(Settings > About). Include the steps to reproduce it and what an attacker
could do with it.

## Supported versions

Only the latest release receives security fixes.
