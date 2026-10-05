# Frontend authentication contract

All routes use `/api`. Success responses contain `success: true`, `code: 200`,
`message`, and `data`. Errors contain `success: false`, `code`, and `message`.
Use `/v3/api-docs` in local development for complete schemas.

## Sessions and CSRF

Login and Google login return `accessToken`, `tokenType`, and `expiresIn` in
`data`. The refresh token is never returned in JSON. Keep access tokens in
memory only and send them as `Authorization: Bearer <accessToken>` to protected
endpoints.

Use credentials on every browser request. Before login, Google login, refresh,
or logout, call `GET /auth/csrf` and send its `data.token` in `X-XSRF-TOKEN`.
The API owns the matching HttpOnly CSRF cookie. Refresh and logout have no JSON
body and do not need an access token.

The refresh cookie is host-only, HttpOnly, SameSite=Lax, and scoped to
`/api/auth`. Local HTTP uses `calories_refresh`; production HTTPS uses
`__Secure-calories_refresh`. Refresh rotates the cookie. Logout revokes it and
its rotated successor, then expires the cookie. Existing access JWTs remain
valid until expiration. Password reset/set also revoke refresh tokens.

On reload, bootstrap through refresh before showing protected pages. Retry a
protected 401 once after refresh. A 403 means insufficient permission and must
not clear the user's session. A network error does not prove session expiry.
CORS must allow the exact frontend origin with credentials and X-XSRF-TOKEN;
the same-origin Vite/Nginx proxy is supported.

### Try CSRF-protected endpoints in Swagger UI

1. Open `/swagger-ui.html` on the API host and execute `GET /api/auth/csrf`.
2. Copy `data.token`, open **Authorize**, and paste it into **csrfHeader** without
   a `Bearer` prefix. Swagger sends it as `X-XSRF-TOKEN` on login, Google login,
   refresh, and logout. The browser keeps and sends the matching HttpOnly cookie.
3. Execute login or Google login with the normal JSON body. This also sets the
   HttpOnly refresh cookie in the browser.
4. Refresh requires both the CSRF header/cookie pair and the refresh cookie.
   Logout requires the CSRF pair but also succeeds without a refresh cookie.
   Neither endpoint needs a JSON body or Bearer token.

Use the same host throughout; do not alternate between `localhost` and
`127.0.0.1`. Do not paste a refresh token into Swagger's cookie authorization
field: the browser manages cookies from `Set-Cookie`; Swagger cannot manually
set the `Cookie` header. See [Swagger's cookie authentication documentation](https://swagger.io/docs/specification/v3_0/authentication/cookie-authentication/).
If you receive 403/code `40301`, execute `/api/auth/csrf` again and update
**csrfHeader** with the new `data.token`. API endpoints using Bearer JWT, including
avatar and meal image uploads, keep their existing Bearer authentication.

## Endpoints

| Method and path after /api | Body | Authentication |
| --- | --- | --- |
| GET /auth/csrf | none | Public |
| POST /auth/register | fullName, email, password | Public |
| POST /auth/verify-email | email, otp | Public |
| POST /auth/resend-otp | email | Public |
| POST /auth/login | email, password | CSRF |
| POST /auth/google | idToken | CSRF |
| POST /auth/refresh-token | none | Cookie + CSRF |
| POST /auth/logout | none | Cookie + CSRF |
| POST /auth/forgot-password | email | Public |
| POST /auth/reset-password | email, otp, newPassword, confirmPassword | Public |
| POST /auth/change-password/request | currentPassword | Bearer |
| POST /auth/google/link | idToken | Bearer |
| POST /auth/set-password | newPassword, confirmPassword | Bearer |

Registration issues no session. Use the canonical email from the registration
response on the verification screen, then sign in after verification. Login
error 11005 directs the user to verification without automatically sending OTP.

Google login never links an existing email automatically. Error 11007 directs
users to their existing sign-in method, followed by explicit Google linking.
The profile response does not expose provider metadata; do not infer providers
from the last sign-in method. Error 11013 from set-password means a local
password already exists.

## Authorization

`GET /user/{id}` allows the account owner or ADMIN. `/admin/users` requires
ADMIN. Authenticated permission failures return JSON 403; missing or invalid
credentials return JSON 401. Meal and item ownership is enforced separately.

See [email verification](email-verification.md), [password reset](password-reset.md),
and [authentication providers](auth-providers.md) for lifecycle details.
