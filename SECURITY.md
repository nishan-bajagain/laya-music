# Security Policy

## Supported Versions
Only the **latest released version** and the **latest beta build** of the app are supported for security updates.  
Please test any reported vulnerability on one of these two versions before submitting your report.

## Reporting a Vulnerability
If you discover a security vulnerability, please send your report to:  
<info@nishanbajagain.com.np>

I will respond as soon as possible after receiving your message.

> ⚠️ This is a completely free project, so I am unable to offer bug bounties at this time.

## Signing Keys

Every Android APK must be updated in place with the **same signing certificate**. The release signing identity for Laya Music is:

| Property | Value |
| :--- | :--- |
| Certificate subject | `CN=Laya Music, OU=Android, O=Laya Music, L=Unknown, ST=Unknown, C=CA` |
| SHA-256 fingerprint (public) | `0b4c236a30842911b3f3f394aed3d69ad33c63d5897acc4b3bf5232064b01a21` |
| Keystore alias | `laya` |

- The keystore file is stored as the `RELEASE_KEYSTORE_BASE64` GitHub Secret (write-only) and must also be backed up durably outside GitHub (see `RELEASING.md` for recovery and backup instructions).
- CI pins this fingerprint and fails the build if a produced APK was signed by anything else.
- **Never rotate this key** — it invalidates in-place updates for every installed user. If you believe it is compromised, treat recovery as a user-visible breaking change and document the migration.

> 🔒 GitHub Secrets are write-only: they cannot be read back. The keystore is recoverable from repository git history (`git show 9810739:app/laya-release.jks > laya-release.jks`) — see `RELEASING.md`.
