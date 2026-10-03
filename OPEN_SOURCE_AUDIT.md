# Axon Input v2.4.5 — Open-source publication audit

Audit target: `Axon_Input_v2.4.5_V20_BuildFix109_BitmapFontShadowMask_Source(1).zip`

## Result

This publication snapshot has been sanitized for accidental credential/infrastructure disclosure and includes automated checks for future commits.

### Removed or sanitized

- Removed the production `cloud-control/security.json` because it contained a real client-side password verifier.
- Added `cloud-control/security.example.json` containing schema-only placeholders.
- Replaced the production Supabase project reference in deployment documentation with `<your-supabase-project-ref>`.
- Removed the production Tencent COS bucket and region defaults from the Edge Function; deployments must provide `COS_BUCKET` and `COS_REGION` through environment/secrets.
- Replaced production COS identifiers in documentation with placeholders.
- Added `.gitignore` rules for keystores, private keys, environment files, production-only control material, Supabase local state, and generated build outputs.
- Added `tools/oss_secret_scan.py`, `tools/pre_publish_check.sh`, and a GitHub Actions secret-scan workflow.

### Not found in the supplied source archive

No committed Android signing keystore/private key, keystore password, Supabase `service_role`/`sb_secret`, Tencent SecretId/SecretKey, database password, `.env` secret file, GitHub PAT, common cloud API key, or private-key PEM block was found by the audit.

### Public-facing identifiers intentionally retained

The source still contains project/community links such as the GitHub repository, QQ/KOOK references, and Bilibili/QQ profile links because they are user-facing application behavior rather than authentication secrets. Remove or parameterize them separately if the public repository should be anonymous.

## Important security limitation

The current entry-password design verifies a password on the client using a remotely supplied verifier. Once the client/source is public, any client-visible verifier can be copied and attacked offline. Do not publish the production verifier. If this password protects meaningful access, move verification to a rate-limited server-side endpoint.

## Third-party licensing

The tree includes multiple third-party assets and notices. The project itself states that Live2D Cubism Core is proprietary redistributable code rather than open-source code. Confirm its redistribution terms before applying one repository-wide open-source license.

The supplied tree also has no single top-level license that clearly grants rights to all first-party Axon Input source. Add a deliberate project license before announcing the repository as open source.

## Validation performed

- `python3 tools/oss_secret_scan.py` — passed.
- `bash tools/pre_publish_check.sh` — passed.
- JSON syntax validation — passed.
- Shell syntax validation for build/setup/signing scripts — passed.
- Known production password hash, Supabase project reference, COS bucket and COS region values — absent from the sanitized tree.
- Raster assets were inspected for metadata; no GPS coordinates, personal author name, or private local filesystem path was found.
- Current BuildFix109 regression test passes in the sanitized tree.

Several older repository tests are already stale against the supplied v2.4.5 source (for example tests that still require an older versionCode or pre-refactor bitmap-font implementation). The same sampled failures reproduce against the original uploaded source and are not caused by sanitization.

## Required release discipline

Never commit production secrets to Git, even temporarily. If a credential was ever committed, rotate it; deleting it in a later commit does not remove it from Git history. Run `bash tools/pre_publish_check.sh` before every public push/release.
