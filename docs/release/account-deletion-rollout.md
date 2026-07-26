# External account deletion rollout

## Current source state

`/account-deletion` is a public, server-rendered Google Play account-deletion surface. The
repository intentionally ships with automatic deletion disabled. There is no trusted email
delivery enabled by default, verified-email ownership timestamp, or SNS provider re-authentication
adapter in this repository. A conditional SMTP adapter is included for reviewed COMMON-account
email proof; enabling the feature without its complete SMTP/TLS configuration blocks startup.

While disabled, the page explains the deletion and retention scope and directs unsupported cases
to the explicitly configured support owner. It does not bind a submitted identifier to a member,
send a code, mint a deletion grant, or invoke account cleanup. Generic receipts do not prove that
an account exists.

## Security boundary

An enabled deployment performs these separate steps:

1. Normalize the submitted email, keep only domain-separated HMAC digests, and consume Redis
   identifier/requester rate limits. Redis failure denies the request.
2. Look up the active member only after the policy and verifier readiness gates pass.
3. Deliver a high-entropy, short-lived confirmation code through the trusted adapter. Delivery is
   attempted for registered and unregistered syntactically valid addresses to reduce account
   enumeration signals.
4. Consume that code under a database row lock and mint a different short-lived deletion grant.
5. Consume the deletion grant once, commit its `PROCESSING` state, and then call the existing
   `MemberUseCase` / `AccountCleanupService` withdrawal boundary.
6. Re-lock the member and compare the session generation captured before verification. Any
   intervening login makes the proof stale and prevents cleanup.

Raw email, requester network address, confirmation code, and deletion grant are not persisted.
The request record is retained for 30 days and removed by a periodic cleanup. A stale
`PROCESSING` row is changed to outcome-unknown, its member binding is cleared, and it is never
automatically replayed.

An existing account whose provider cannot be proven by the configured adapter is marked for
manual review. After confirmation it receives a support-required result, never a deletion-complete
result. A nonexistent decoy never reaches cleanup.

## Required decisions and evidence before enablement

- Set `ACCOUNT_DELETION_PUBLIC_ORIGIN` to the reviewed canonical HTTPS origin. There is no default,
  and production startup rejects a missing or noncanonical origin.
- Set `ACCOUNT_DELETION_SUPPORT_EMAIL` to a monitored address with a documented manual
  identity-verification and deletion procedure.
- Decide whether current email control is sufficient ownership proof for COMMON accounts; current
  signup does not establish a verified-email timestamp. If approved, configure the bundled
  conditional SMTP `AccountDeletionIdentityVerificationPort` with
  `ACCOUNT_DELETION_EMAIL_VERIFICATION_ENABLED=true`, an explicit sender, authenticated SMTP,
  required STARTTLS, bounded timeouts, and startup connection testing.
- SNS accounts should use provider re-authentication/OAuth or a reviewed provider-specific recovery
  procedure rather than treating a synthetic or mutable email as provider proof. The bundled SMTP
  adapter deliberately returns `supports=false` for SNS login types.
- Name the selected SMTP/email processor and its handling period in the privacy policy before
  production enablement.
- Generate a JWT-independent `ACCOUNT_DELETION_HMAC_SECRET` with at least 32 random bytes.
- Confirm and publish the retention period for the anonymized member row, shared-calendar terminal
  membership/audit state, and any legally retained records. Then set
  `ACCOUNT_DELETION_RETENTION_POLICY_CONFIRMED=true`.
- Decide what an active shared-calendar OWNER must do. The current cleanup fails closed and routes
  the user to support; it does not silently delete or transfer other users' shared data.
- Confirm Redis persistence/HA and eviction policy for replay/rate-limit keys. Database secrets and
  grants are durable, but rate-limit counters use Redis.
- Confirm trusted reverse-proxy handling. The implementation uses the servlet remote address and
  deliberately does not trust caller-supplied forwarding headers.
- Apply `docs/member/migrations/2026-07-26-account-deletion-requests.sql` during a maintenance
  window and verify the `2026-07-26-account-deletion-v1` schema marker.

Only after all items are evidenced should operators set both
`ACCOUNT_DELETION_ENABLED=true` and `ACCOUNT_DELETION_RETENTION_POLICY_CONFIRMED=true`.
