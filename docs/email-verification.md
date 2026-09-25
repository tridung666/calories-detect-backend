# Email verification

`POST /api/auth/register` accepts fullName, email, and password. It creates an
unverified user, stores a BCrypt LOCAL provider credential, and sends a six-digit
OTP by email. The response includes the canonical email and emailVerified=false;
it does not issue access or refresh tokens.

`POST /api/auth/verify-email` accepts email and otp as strings. Successful
verification consumes the OTP and enables login. `POST /api/auth/resend-otp`
accepts email and returns a generic success message whether or not delivery is
eligible. Registration retries preserve the original identity/password.

Codes expire after five minutes. Resend has a 60-second cooldown and a maximum
of five codes per hour, including registration. The fifth incorrect attempt
invalidates the code. Leading zeroes must be preserved. Only password hashes
and OTP hashes are stored; SMTP failure rolls back issuance. Successful
verification sends a welcome email.

Configure MAIL_USERNAME and MAIL_PASSWORD before starting the app. Email
integration tests replace the SMTP sender; they do not send external messages.
Run them against a disposable PostgreSQL database with the normal DB settings.

Unverified local accounts cannot log in or refresh a session. Login error 11005
should open the verification page. Verification does not create a session;
login requires the cookie/CSRF flow in [the frontend contract](auth-frontend-integration.md).

V6 introduces verification and OTP storage; V7 introduces authentication
providers. See [migration prerequisites](auth-providers.md) before deployment.
