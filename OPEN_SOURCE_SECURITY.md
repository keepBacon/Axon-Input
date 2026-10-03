# Axon Input — Open-source release security notes

This tree is a publication-sanitized source snapshot. It intentionally does **not** contain the production entry-password policy or signing credentials.

## Removed / sanitized before publication

- `cloud-control/security.json`: removed because the previous file contained a real client-verifiable password hash. A non-runtime template is provided as `cloud-control/security.example.json`.
- Supabase production project reference: replaced by `<your-supabase-project-ref>`.
- Tencent COS production bucket/region defaults: removed from server code and replaced by deployment-time environment variables/placeholders in documentation.
- Signing keys/passwords: none are stored in this tree; `.gitignore` blocks common keystore/key formats and the build already keeps the official keystore outside the project directory.
- Common secret/token formats are checked by `python3 tools/oss_secret_scan.py`.
- Raster assets were metadata-audited; no GPS coordinates, personal author name, or private local path was found. Original asset bytes are preserved to avoid visual/resource regressions.

## Critical architecture note: entry password

A password that is verified entirely on the client cannot remain secret once the client/source is public. PBKDF2 is better than plain SHA-256 for slowing guesses, but the verifier is still available for offline guessing.

If the entry password must protect something meaningful, move verification to a server-side endpoint with rate limiting and keep the password/verifier only on the server. Do not commit the production `cloud-control/security.json` to the public repository.

## Production secrets that must stay outside Git

Keep these in deployment secret stores or local environment variables only:

- Supabase `service_role` / `sb_secret` values
- database password / database connection strings
- Tencent Cloud `SecretId` / `SecretKey`
- Android release keystore and `AXON_KEYSTORE_PASS`
- any private signing keys, OAuth client secrets, PATs, API tokens, or administrator credentials

The Supabase publishable/anon key is designed to be distributed to clients, but RLS and Edge Function authorization must still be correct; never treat it as an administrator secret.

## Before making the repository public

Run:

```bash
python3 tools/oss_secret_scan.py
```

Also rotate any credential that was ever committed to a Git repository, sent in a public build log, or shared in a source archive. Removing it from the latest commit is not enough because Git history may retain it.

## Licensing

This snapshot contains third-party components under their own licenses. In particular, the bundled Live2D Cubism Core is identified by the project as proprietary redistributable code rather than open-source code. Review its redistribution terms before applying a repository-wide open-source license. The project currently does not contain a single top-level license that unambiguously licenses all first-party source code; choose and add one before announcing the repository as open source.
