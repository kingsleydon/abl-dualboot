# Security policy

ABL Dual Boot runs as root and writes to the `devinfo` and `abl` partitions, so problems can affect whether a device boots.

Please report vulnerabilities privately through
[GitHub's private reporting](https://github.com/kingsleydon/abl-dualboot/security/advisories/new),
not as a public issue. Only the latest release is supported.

Release files are built by GitHub Actions and carry a signed build attestation. Verify a download with:

```sh
gh attestation verify ABL-Dual-Boot.apk -R kingsleydon/abl-dualboot
```
