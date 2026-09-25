# Authentication providers

Production starts at Flyway V5 with no users. V1–V5 are unchanged. V6 adds email
verification and OTP tokens; V7 creates `auth_providers` and drops `password` and
`google_subject` from `users`. `local_password_enabled` is no longer created.
No credentials or legacy OTP purposes are migrated. V7 stops before dropping any
credential columns if users exist; deployments with existing accounts need a
separate credential migration first.

Each user can have one LOCAL provider and one GOOGLE provider:

| Provider | `provider_subject` | `password_hash` |
| --- | --- | --- |
| LOCAL | null | BCrypt hash |
| GOOGLE | Verified Google `sub` | null |

The database enforces unique `(user_id, provider)` and `(provider, provider_subject)`
pairs, a required user foreign key with cascade deletion, and provider-specific
credential shapes. Email, verification state, role, and status remain on `users`.

Registration creates an unverified user plus a LOCAL provider and sends an email
OTP. Verification enables login. Retrying registration before verification keeps
existing credentials and resends only within OTP limits. Local login, password
reset, and password change require a LOCAL provider. Password change retains the
existing `/change-password/request` → `/reset-password` OTP flow. Reset updates
only the LOCAL hash and revokes all refresh tokens; Google linking is preserved.

Google login resolves accounts by verified subject. A new subject creates a new
verified user and a GOOGLE provider only if its email is unused. An existing
email returns `GOOGLE_ACCOUNT_CONFLICT` (11007); it is never automatically linked.
Returning Google users are resolved by subject even if their Google email changes.

## Link Google

`POST /api/auth/google/link` with `Authorization: Bearer <accessToken>`:

```json
{"idToken":"<Google ID token>"}
```

The current user must be active and email verified. The verified Google email
must match the user's email. The subject cannot belong to another user, and an
existing Google provider cannot be replaced. Linking the same subject again is
idempotent. The LOCAL password stays unchanged. Success returns:

```json
{"success":true,"code":200,"message":"Success","data":"Google account linked successfully"}
```

## Set a first local password

`POST /api/auth/set-password` with a Google user's application Bearer token:

```json
{"newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
```

The user must be active, email verified, have a GOOGLE provider, and have no LOCAL
provider. Passwords must match and contain 8–72 characters, at most 72 UTF-8 bytes.
This creates a LOCAL provider, retains GOOGLE, and revokes all refresh tokens.
Existing access tokens retain their normal JWT expiration. Success returns:

```json
{"success":true,"code":200,"message":"Success","data":"Password set successfully. Please log in again"}
```

An existing LOCAL provider returns code 11013; use change-password instead.
Mismatched confirmation returns 11003; invalid password content returns 11012
(or validation code 400 for malformed HTTP input). Neither new endpoint accepts
a target user ID or email; the authenticated principal determines the user.

## Tests

Run `./gradlew test` against a disposable PostgreSQL database using the existing
DB configuration. Databases that already applied an older V6 need a fresh schema
or database; do not repair checksums without rebuilding the matching schema.
To use a separate schema, set the same schema name in
`SPRING_FLYWAY_DEFAULT_SCHEMA`, `SPRING_FLYWAY_SCHEMAS`,
`SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA`, and
`SPRING_DATASOURCE_HIKARI_SCHEMA` before running Gradle. SMTP and Google verification
are mocked by auth integration tests; provider persistence, constraints, locking,
password hashing, authentication, and token revocation use real application code.
