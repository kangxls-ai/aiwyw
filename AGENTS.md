# Project development rules

- Follow the confirmed 微云湾 requirements in `docs/REQUIREMENTS.md` and the implementation status in `docs/DEVELOPMENT.md`.
- Target Java 17, Spring Boot, Nuxt with Vue 3 and production MySQL. Local H2 is for development and tests only.
- Put operational settings in System Management → Configuration Management. Register typed definitions in `Settings`, validate server-side, persist values in the database, encrypt secrets and never return their plaintext or write them to audit logs. Do not ask users to edit YAML, JSON or environment files for business configuration.
- Startup database credentials and the encryption key must be created by the installation flow, not checked into source. Preserve backups and do not silently switch databases or delete user data.
- Per user decision 2026-11-05, MFA policy defaults ON for any ADMIN/FINANCE membership; an administrator may disable it for emergencies from Configuration Management. Audit changes and preserve enrolled secrets; never bypass policy merely for tests. Users may hold multiple roles; enforce menu grants on the server.
- Real payment is disabled until the real-channel workflow and reconciliation are verified. Uploading a payment code does not enable payments.
- Keep transaction changes atomic, auditable and idempotent. Snapshot purchased terms; do not replace historic entitlements on a product update.
- Run focused backend tests, frontend type checks/builds and meaningful browser flows for changed functionality. Do not claim full V1.0 completion when only a development stage is implemented.
- Never commit generated data, keys, test traces, build outputs or dependency directories. User has authorized coding; the user authorized committing and pushing to https://github.com/kangxls-ai/aiwyw.git on 2026-11-05. Production deployment remains unrequested.

- All A decisions in docs/FINAL-SCOPE-CONFIRMATION.md are approved. Build production behavior; do not restore prototype wallets, fake downloads, fabricated inventory or version notices in the public UI. Retain truthful simulated-payment labeling until real payment approval.
- Add Chinese explanatory comments to backend/frontend business code and SQL tables/columns. Document invariants, transaction boundaries, state transitions, failure recovery and safe manual intervention. Comments must match the actual implementation.

- The user explicitly requests automatic local deployment after every completed stage. After relevant checks pass, run `npm run deploy:local`, verify http://127.0.0.1:3000/ and the admin/install route, and report the local result. Do not ask for renewed approval for routine local builds, restarts or updates.
- Use the managed local release scripts: preserve backend/data, back up before updates, keep prior release files, use hidden background processes and verify process ownership before stopping anything. Never terminate unrelated Java/Node/MySQL services. Production deployment and real-payment activation still require their existing approval conditions.
