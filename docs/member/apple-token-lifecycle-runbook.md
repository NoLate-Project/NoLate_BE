# Sign in with Apple token lifecycle

NoLate consumes the native Apple authorization code only on final SNS login/signup. Registration
status verifies the identity token but never consumes Apple's five-minute, single-use code.

## Transaction and compensation boundary

The final flow has deliberately separate boundaries:

1. A `REQUIRES_NEW` transaction inserts an immutable authorization receipt with a unique code
   fingerprint. It commits before Apple is called, so replay is rejected before provider I/O.
2. `/auth/token` runs with no Spring transaction and no member-row lock.
3. After Apple succeeds, a second `REQUIRES_NEW` transaction AES-GCM encrypts and stores the refresh
   token as unbound `CAPTURED`. The access token is never persisted.
4. The exchanged identity token is verified again for exact subject and audience.
5. The final member/session transaction creates JWT/refresh state and binds `CAPTURED` to `ACTIVE`.

If step 4 or 5 fails, the capture becomes `PENDING` in an independent transaction. A process crash
that prevents that immediate transition is covered by `capture.binding-deadline-seconds`: the
worker promotes an expired `CAPTURED` to compensation work. It never promotes before the deadline.

Apple offers no distributed transaction or idempotency key for `/auth/token`. Therefore a crash
after Apple accepts the code but before the encrypted capture transaction commits is irreducible
without 2PC. The implementation minimizes that window to response validation, AES-GCM encryption,
and one small insert. Every failure after capture commit is durably compensatable.

Authorization receipts are plaintext-free, not anonymous: they retain one-way code and Apple
subject fingerprints. A new authorization code always creates a new immutable row, even when Apple
returns an existing refresh token. No receipt is overwritten.

## Runtime contract

Production requires:

- `SNS_APPLE_AUDIENCES` contains the exact `APPLE_TOKEN_CLIENT_ID`;
- `APPLE_TOKEN_CLIENT_ID`, `APPLE_TOKEN_TEAM_ID`, and `APPLE_TOKEN_KEY_ID`;
- `APPLE_TOKEN_PRIVATE_KEY`, PKCS#8 PEM with literal `\n` or `base64:<base64-of-PEM>`;
- `APPLE_TOKEN_ENCRYPTION_KEY_ID`;
- `APPLE_TOKEN_ENCRYPTION_KEY`, exactly 32 random bytes as standard Base64;
- the revocation worker enabled.

`APPLE_TOKEN_REDIRECT_URI` stays empty for native iOS. A web/Services ID redirect must be its exact
registered absolute HTTPS URI. Production rejects localhost, `.localhost`, IPv4 literals, and IPv6
literals. Production accepts only `https://appleid.apple.com` as the provider base URL.

The private and encryption keys belong only in the deployment secret store, never YAML, an image,
a WAR, logs, or support bundles. Startup parses the exact named P-256 parameters and performs a
real ES256 signature. A merely 256-bit non-P-256 key is not accepted.

Configuration invariants are fail-closed:

- capture binding deadline: 10–600 seconds;
- worker fixed delay: 1 second–24 hours;
- batch: 1–200;
- max attempts: 1–100;
- initial retry delay: 1 second–24 hours;
- maximum retry delay: at least the initial delay and at most 7 days;
- processing timeout: 10–3,600 seconds.

## Deployment

1. Stop every old API instance and back up the database.
2. Apply
   [`migrations/2026-07-26-apple-token-lifecycle.sql`](migrations/2026-07-26-apple-token-lifecycle.sql).
3. Confirm `apple_authorization_receipt_column_count=6`,
   `apple_token_lifecycle_column_count=25`, and no `missing_required_schema_marker` row.
4. Confirm the receipt table has the reviewed six non-null columns, `PRIMARY(id)`, and unique
   `receipt_key`/`authorization_code_hash` indexes. The migration rejects an existing near-match.
5. Confirm MySQL reports the reviewed `ck_apple_provider_credentials_status`; malformed `PENDING`
   or `PROCESSING` envelopes, identifying manual states, and value-bearing revoked states must be
   rejected.
6. Inject production secrets and start one instance. Startup must fail for invalid keys,
   audience/client ID, worker settings, redirect, or Apple endpoint.
7. Complete Apple login. Only ciphertext/fingerprints may exist—never authorization code, access
   token, refresh token, identity token, or client secret.
8. Withdraw the test account. The response finishes after local commit; Apple provider latency is
   handled only on the dedicated executor. A confirmed `200` changes the row to `REVOKED` and
   clears member, subject, receipt pointer, token fingerprint, key id, IV, and ciphertext.

## State and queue operation

- `CAPTURED`: encrypted, unbound, and protected until its bind deadline.
- `ACTIVE`: bound to a current local Apple member.
- `PENDING`: complete envelope, due time, no lease.
- `PROCESSING`: complete envelope plus owner/time lease.
- `BLOCKED`: operator intervention required; never selected by the worker.
- `MANUAL_ACTION`: value-free tombstone proving the authenticated client was instructed to
  disconnect Apple manually or reauthenticate. It has no member, subject, receipt, or token value.
- `REVOKED`: provider-confirmed, value-free tombstone.

The worker scans multiple ordered candidates. A malformed legacy `PENDING`, `PROCESSING`, or
expired `CAPTURED` row is changed to `BLOCKED`, then scanning continues. A contended candidate also
does not stop later rows. Every provider call has a committed `PROCESSING` lease. A crash after
Apple's `200` is safe because revoke is idempotent and stale-lease recovery retries it.

Scheduler shutdown invalidates its executor generation before interruption, waits at most one
second, and serializes a subsequent start. If provider code ignores interruption, its late result
cannot update the database or schedule another wake under either the stopped or replacement
generation; the retained `PROCESSING` lease is handled by normal stale recovery.

`/auth/token` parses at most 2 KiB of error JSON. Only `invalid_grant` becomes an app credential
error. `invalid_client`, malformed/unknown JSON, and other token failures remain operational
failures. For revoke, transient status/network failures and `invalid_client` are retryable;
`invalid_request`, unknown 4xx, key mismatch, decryption failure, and retry exhaustion become
`BLOCKED`. Provider descriptions/bodies are never retained or logged.

Monitor these source-of-truth counts:

```sql
SELECT status, COUNT(*) AS backlog_count, MIN(updated_at) AS oldest_updated_at
FROM apple_provider_credentials
WHERE status IN ('PENDING', 'PROCESSING', 'BLOCKED', 'MANUAL_ACTION')
GROUP BY status;
```

The worker emits `Apple revocation blocked backlog. count=<n>` each run while `BLOCKED > 0`.
Alert immediately for any nonzero `BLOCKED`, or `PROCESSING` older than the configured timeout.
`MANUAL_ACTION` is an aggregate product-support metric, not an account lookup mechanism.

An Apple member with no usable credential, a missing subject, or a subject/credential mismatch is
still deleted locally. The authenticated `DELETE /withdraw` response returns
`data.manualAppleRevocationRequired=true`; the app must tell that user to disconnect NoLate in
Apple ID settings or reauthenticate for cleanup. Any public/external account-deletion web response
must remain generic and must not expose whether an account, credential, or manual tombstone exists.

## BLOCKED handling and retention

A subject mismatch or incomplete envelope is not automatically revoked: doing so could revoke a
token attached to the wrong local owner. It is quarantined as `BLOCKED`; encrypted provider
material is retained solely to support provider deletion after ownership/configuration review.
Do not export ciphertext or fingerprint values into tickets.

After repairing a reviewed configuration or envelope-key issue, requeue one inspected complete
row:

```sql
UPDATE apple_provider_credentials
SET status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6),
    capture_expires_at = NULL,
    locked_at = NULL,
    locked_by = NULL,
    last_failure_code = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = :inspected_credential_id
  AND status = 'BLOCKED'
  AND source_receipt_key IS NOT NULL
  AND apple_subject_hash IS NOT NULL
  AND refresh_token_hash IS NOT NULL
  AND encryption_key_id IS NOT NULL
  AND initialization_vector IS NOT NULL
  AND encrypted_refresh_token IS NOT NULL;
```

Security/privacy owns every `BLOCKED` incident. Triage within 24 hours and either repair/requeue or
confirm deletion through an approved Apple/manual procedure. There is no time-based automatic
deletion of encrypted `BLOCKED` material: discarding it before provider-confirmed deletion would
destroy the only cleanup capability. Once deletion is confirmed, transition through the reviewed
revoked/wipe operation.

Value-free `MANUAL_ACTION` and `REVOKED` tombstones may be deleted after 90 days under the normal
operations retention job. Fingerprint-only authorization receipts may be deleted after 30 days
only when no non-revoked credential references `source_receipt_key`:

```sql
DELETE FROM apple_authorization_code_receipts
WHERE reserved_at < CURRENT_TIMESTAMP(6) - INTERVAL 30 DAY
  AND receipt_key NOT IN (
      SELECT source_receipt_key
      FROM apple_provider_credentials
      WHERE source_receipt_key IS NOT NULL
        AND status <> 'REVOKED'
  );

DELETE FROM apple_provider_credentials
WHERE status IN ('MANUAL_ACTION', 'REVOKED')
  AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 90 DAY;
```

For key rotation, deploy the new current key and retain old decrypt-only entries in
`APPLE_TOKEN_PREVIOUS_ENCRYPTION_KEYS` as `key-id=base64-key`. Remove an old key only after no
non-`REVOKED` row references it.

## Explicit post-launch follow-up

This release does not run TN3194's recommended periodic validation of stored long-lived refresh
tokens. Add it post-launch using the same encrypted-at-rest, bounded provider response, value-free
logging, and reviewed `invalid_grant` transitions. It must not weaken deletion-time `/auth/revoke`.

Apple references:

- [Token validation](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens)
- [Token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens)
- [TN3194](https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple)
