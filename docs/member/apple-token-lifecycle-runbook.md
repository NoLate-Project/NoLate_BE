# Sign in with Apple token lifecycle

NoLate exchanges the native Apple authorization code only on the final SNS login or signup
request. The preceding registration-status request verifies the identity token but does not
consume Apple's single-use, five-minute code.

Once a code fingerprint has been stored, every replay fails closed without opening another app
session. Because this API has no separate one-shot idempotency receipt, a client that loses the
final login response must start Apple authorization again and submit a fresh code.

Apple's token response is checked against the already verified subject and client ID. The
long-lived refresh token is encrypted before persistence; the short-lived access token is never
persisted and leaves scope immediately after validation.

## Runtime contract

Production requires all of the following:

- `SNS_APPLE_AUDIENCES` includes the exact `APPLE_TOKEN_CLIENT_ID`;
- `APPLE_TOKEN_CLIENT_ID`, `APPLE_TOKEN_TEAM_ID`, and `APPLE_TOKEN_KEY_ID`;
- `APPLE_TOKEN_PRIVATE_KEY`, either a PKCS#8 PEM with literal `\n` separators or
  `base64:<base64-of-PEM>`;
- `APPLE_TOKEN_ENCRYPTION_KEY_ID`;
- `APPLE_TOKEN_ENCRYPTION_KEY`, exactly 32 random bytes encoded as standard Base64.

`APPLE_TOKEN_REDIRECT_URI` stays empty for the native iOS flow. If a web/Services ID flow is
introduced, it must be the same registered HTTPS redirect URI used in the authorization request.
Production accepts only `https://appleid.apple.com` as the provider base URL.

The Apple private key and encryption key must be injected by the deployment secret store. They
must not be copied into `application*.yml`, an image, a WAR, logs, or a support bundle.

## Deployment

1. Stop old API instances and back up the database.
2. Apply
   [`migrations/2026-07-26-apple-token-lifecycle.sql`](migrations/2026-07-26-apple-token-lifecycle.sql).
3. Confirm `apple_token_lifecycle_column_count=24` and no
   `missing_required_schema_marker` row.
4. Inject the production secret variables and start one instance. Startup must fail if the
   private key, encryption key, audience/client ID, worker, or Apple HTTPS endpoint is invalid.
5. Complete an Apple login. Confirm the database contains ciphertext and fingerprints only; never
   the authorization code, access token, refresh token, identity token, or client secret.
6. Withdraw that test account. Local cleanup commits even when Apple is unavailable, while the
   credential remains `PENDING` for retry. A confirmed `200` changes it to `REVOKED` and wipes all
   member, subject, code, token, IV, ciphertext, and key-id fields.

## Retry and incident handling

Each provider call has a committed `PROCESSING` lease. A crash after Apple's `200` is safe:
Apple treats a repeated revoke as success, and the stale-lease recovery calls it again. Retryable
network, timeout, revoke-400, rate-limit, and 5xx failures use bounded exponential backoff.
Non-retryable client-credential failures, key mismatch, decryption failure, or retry exhaustion
become `BLOCKED`; encrypted material is retained for operator recovery rather than silently
discarded.

After repairing configuration, an operator may requeue only inspected rows:

```sql
UPDATE apple_provider_credentials
SET status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6),
    locked_at = NULL,
    locked_by = NULL,
    last_failure_code = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = :inspected_credential_id
  AND status = 'BLOCKED'
  AND encrypted_refresh_token IS NOT NULL;
```

For envelope-key rotation, deploy the new current key and retain old decrypt-only entries in
`APPLE_TOKEN_PREVIOUS_ENCRYPTION_KEYS` as `key-id=base64-key`. Remove an old key only after no
non-`REVOKED` row references its `encryption_key_id`.

## Explicit follow-up

This release implements the launch-critical authorization-code exchange and account-deletion
revocation path. It does not yet run TN3194's recommended periodic validation of stored long-lived
refresh tokens. Add that as a separate post-launch operational hardening job: use Apple's refresh
token grant, keep the same encrypted-at-rest and value-free logging boundary, and define reviewed
state transitions for `invalid_grant` before enabling it in production. It must not weaken or
replace the deletion-time `/auth/revoke` path.

Apple references:

- [Token validation](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens)
- [Token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens)
- [TN3194: account deletion and token revocation](https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple)
