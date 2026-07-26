# Deployment health probes

NoLate exposes three opaque, unauthenticated health contracts:

- `GET /health/liveness`: process liveness only
- `GET /health/readiness`: Spring application traffic readiness
- `GET /health`: compatibility alias for liveness

Each response contains only `{"status":"UP"}` on success or a single non-UP status on failure.
They never include dependency details, configuration, exception messages, or member data.

## Container contract

The production image runs `/app/readiness-probe.jar` as its Docker `HEALTHCHECK`. The probe uses
the configured `SERVER_PORT` (default `5522`), calls the loopback readiness endpoint, follows no
redirects, and succeeds only on HTTP 200. It uses only the existing JRE, so the runtime image does
not gain a package manager dependency solely for probing.

Docker health is a local container signal. A deployment platform should configure both paths
explicitly:

```yaml
livenessProbe:
  httpGet:
    path: /health/liveness
    port: 5522
  initialDelaySeconds: 90
  timeoutSeconds: 3
readinessProbe:
  httpGet:
    path: /health/readiness
    port: 5522
  initialDelaySeconds: 30
  timeoutSeconds: 3
```

The platform configuration remains deployment-owned. Before rollout, request the same paths
through the actual ingress and verify that liveness failure restarts an instance while readiness
failure only removes it from traffic.
