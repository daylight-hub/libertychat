# Security Policy

## Verifying a download

Liberty Chat releases are built on GitHub from the source in this repository,
and each file carries a signed GitHub attestation of the commit it was built
from. With the GitHub CLI installed, check a download with:

```bash
gh attestation verify libertychat-x.y.z-official-rns-py-arm64-v8a.apk -R daylight-hub/libertychat
```

This proves the file was built from this repository's public source and hasn't
been changed since. Releases before the first attested version don't have
attestations; use the checksum below for those.

Each release also includes a `.sha256` file for every APK. Check the APK against
it before installing:

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
