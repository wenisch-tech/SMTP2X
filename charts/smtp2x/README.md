# SMTP2X Helm chart

This chart deploys SMTP2X with separate HTTP and SMTP Services, persistent storage, health probes, optional HTTP ingress, and environment-based application configuration.

## Install

```bash
helm install smtp2x ./charts/smtp2x \
  --namespace smtp2x \
  --create-namespace \
  --set-string secrets.SMTP2X_ADMIN_PASSWORD='change-me-now'
```

SMTP2X uses a local H2 database and persistent volume by default. `replicaCount` must remain `1` because message attachments are stored on the pod's persistent volume. The chart uses a `Recreate` update strategy so an updated pod never starts against the same H2 data directory as the old one. The SMTP and HTTP Services default to `ClusterIP`; the HTTP Service is intended for ingress access.

The application exposes `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness`. The startup probe allows 150 seconds for the JVM, Flyway migration, and SMTP listener to start before Kubernetes evaluates liveness. A failed readiness probe only removes the pod from Services; a failed startup or liveness probe restarts it.

The HTTP Service is annotated for Prometheus scraping at `/actuator/prometheus` by default. Disable discovery with `metrics.serviceAnnotations.enabled=false`, or change the advertised path with `metrics.path`.

## Ingress

The ingress follows the same host/path structure as the Kairos chart and forwards HTTP traffic to the SMTP2X HTTP Service. This example enables HTTPS with nginx and cert-manager:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt
  hosts:
    - host: smtp2x.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: smtp2x-tls
      hosts:
        - smtp2x.example.com
```

Install these values from a file with `helm upgrade --install smtp2x ./charts/smtp2x -f values-production.yaml`.

The ingress covers the web application only. Expose SMTP through `service.smtp`, or through the TCP configuration of your ingress controller. If SMTP CIDR allowlists are enabled, make sure the load balancer preserves the client address.

## OIDC through environment variables

SMTP2X uses the Spring Security OAuth2 client named `keycloak`. Every part of its OIDC configuration can be supplied as an environment variable through `env` and `secrets`:

```yaml
env:
  SMTP2X_SECURITY_OIDC_ENABLED: "true"
  SMTP2X_SECURITY_OIDC_ROLE_MAPPING_ENABLED: "true"
  OIDC_IGNORE_TLS: "false"
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID: smtp2x
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE: openid,profile,email
  SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI: https://auth.example.com/realms/smtp2x
secrets:
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET: replace-me
```

The corresponding command-line form is:

```bash
helm upgrade --install smtp2x ./charts/smtp2x \
  --namespace smtp2x --create-namespace \
  --set-string env.SMTP2X_SECURITY_OIDC_ENABLED=true \
  --set-string env.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID=smtp2x \
  --set-string env.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email \
  --set-string env.SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/smtp2x \
  --set-string secrets.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET=replace-me
```

Register this redirect URI with the OIDC provider:

```text
https://smtp2x.example.com/login/oauth2/code/keycloak
```

The ingress must preserve `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port`. SMTP2X already enables Spring's forwarded-header support so it generates the public HTTPS callback URL.

New OIDC users start with the `PENDING` role. When role mapping is enabled, `SMTP2X_Admin` maps to Admin and `SMTP2X_Viewer` maps to Viewer.

For temporary troubleshooting with a private or self-signed issuer certificate, set `env.OIDC_IGNORE_TLS` to `"true"`. This disables certificate and hostname verification for OIDC discovery, token exchange, and JWK retrieval. Install the issuer CA in the container trust store for production deployments instead.

## PostgreSQL

Activate the PostgreSQL profile and pass the datasource settings as environment variables:

```yaml
env:
  SPRING_PROFILES_ACTIVE: postgres
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/smtp2x
  SPRING_DATASOURCE_USERNAME: smtp2x
secrets:
  SPRING_DATASOURCE_PASSWORD: replace-me
```

## Values

| Value | Default | Description |
|---|---|---|
| `image.repository` | `ghcr.io/wenisch-tech/smtp2x` | Container image repository |
| `image.tag` | Chart app version | Container tag; an empty value uses `appVersion` |
| `image.pullPolicy` | `IfNotPresent` | Kubernetes image pull policy |
| `replicaCount` | `1` | Deployment replicas |
| `updateStrategy.type` | `Recreate` | Deployment update strategy for the single-writer H2 volume |
| `securityContext` | See `values.yaml` | Container privilege settings |
| `service.http.type` | `ClusterIP` | HTTP Service type |
| `service.http.port` | `8080` | HTTP Service port |
| `service.smtp.type` | `ClusterIP` | SMTP Service type |
| `service.smtp.port` | `2525` | SMTP Service port |
| `metrics.path` | `/actuator/prometheus` | Prometheus scrape path |
| `metrics.serviceAnnotations.enabled` | `true` | Add Prometheus scrape annotations to the HTTP Service |
| `ingress.enabled` | `false` | Create an HTTP Ingress |
| `ingress.className` | empty | Ingress class name |
| `ingress.annotations` | `{}` | Ingress annotations |
| `ingress.hosts` | `smtp2x.example.com` | Ingress hosts and paths |
| `ingress.tls` | `[]` | TLS hosts and Secret names |
| `persistence.enabled` | `true` | Create a PVC for `/app/data` |
| `persistence.storageClassName` | empty | Storage class, or the cluster default |
| `persistence.size` | `10Gi` | Requested storage size |
| `resources` | See `values.yaml` | Container requests and limits |
| `startupProbe` | See `values.yaml` | Startup health probe; delays liveness until startup succeeds |
| `livenessProbe` | See `values.yaml` | Liveness probe; failures restart the container |
| `readinessProbe` | See `values.yaml` | Readiness probe; failures remove the pod from Services |
| `env.OIDC_IGNORE_TLS` | `"false"` | Temporarily disable OIDC certificate and hostname verification |
| `env` | See `values.yaml` | Plain environment variables |
| `secrets` | `{}` | Secret-backed environment variables |

When the same variable exists in both `env` and `secrets`, the Secret-backed value takes precedence.

The chart validates supplied values against `values.schema.json` during Helm install, upgrade, lint, and template operations.
