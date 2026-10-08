# Community and full distributions

The source is physically separated into two sibling checkouts:

| Checkout / module | Contents |
| --- | --- |
| `m3/m3-extension-api` | Execution policy contracts and immutable demand/state records |
| `m3/m3-core` | Shared messaging, persistence, UI, worker leases, resources and migrations |
| `m3/m3-community` | Fixed one-slot policy and information page; no signed-license reader |
| `m3/m3-app` | Application entry point and common application/integration tests |
| `m3-scale` (private repository) | Signature verifier, paid policy, activation UI/API and issuer tools |

The public checkout's default Maven reactor does not access the private checkout.
The full profile adds `../m3-scale` and replaces the app's Community runtime
dependency with Scale. Library modules are ordinary JARs; only `m3-app` is repackaged
as an executable Spring Boot JAR. Both UI and headless workers use that same JAR.
Scale depends on a pinned Community version (`1.0`); shared code is not copied.
Use the `full` profile alone; do not combine it with the `community` profile.

## Build and run

From the Community root, using Java 21:

```powershell
.\mvnw.cmd package
.\scripts\run-app.ps1
.\scripts\run-worker.ps1 -Pool default
```

Outputs: `m3-app/target/m3-community.jar`. Local launch scripts preserve the root
working directory so `.m3/local-security.properties` is still loaded. Root Maven's
default goal is now `package`. The equivalent cross-platform launch is
`java -jar m3-app/target/m3-community.jar`; workers add `--spring.profiles.active=worker`.

With the private sibling present:

```powershell
.\mvnw.cmd -Pfull clean package
.\scripts\run-app.ps1 -Edition full
.\scripts\run-worker.ps1 -Edition full -Pool default
```

Outputs: `m3-app/target/m3-full.jar`. No signed entitlement means Community mode.
Installing a valid entitlement enables the purchased capabilities without replacing
the image. The private verifier and allocator retain installation binding, expiry,
grace and draining behavior. Standalone Community cannot activate Scale by installing
a document because the verifier and activation API are absent.

The database schema is shared, including installation identity and worker leases.
Keep the same database and encryption key when switching distributions; back them up
first. No data migration is needed for this code split. Stop old UI/workers and replace
both with the selected edition. Mixed editions are unsupported; standalone Community
workers always enforce one slot even when the database contains a Scale document.
Existing queues from a prior full installation are retained; the Community slot
rotates among nonempty queues rather than discarding their jobs.

## Docker

The public `Dockerfile` and `compose.yaml` build and use `m3:community` independently.
The private checkout contains the full Dockerfile and Compose overlay:

```powershell
docker compose -f compose.yaml -f ../m3-scale/compose.full.yaml build app
docker compose -f compose.yaml -f ../m3-scale/compose.full.yaml up -d
```

The overlay builds only the two named checkout contexts and sets both the UI and
managed-worker image to `m3:full`. It does not expose the parent directory containing
other repositories to Docker. Private signing keys, local credentials and build
outputs are excluded from image contexts. Both agreements are included in the full
image under `/licenses`; the standalone image includes the Community agreement.
Collect and include dependency license/notices as required before external release.

## Validation and publication

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pfull test
node --test m3-app/src/test/js/*.test.cjs
python scripts/verify-distribution.py m3-app/target/m3-community.jar community
python scripts/verify-distribution.py m3-app/target/m3-full.jar full
```

Boundary tests check runtime class availability, a single selected execution policy,
and Community-mode startup without an entitlement. Artifact checks inspect nested
JARs for paid classes and bundled policy modules. Tests for signed entitlements and
paid allocation are in the private checkout; common PostgreSQL and transport tests
are in the public application module.

Only publish the current Community sources. The original checkout's Git history
may contain earlier combined implementations; a source split does not rewrite it.
`scripts/export-community.py` produces an allowlisted source archive without Git
history, secrets, old build outputs or the private sibling. It can be used to start
a clean public repository. The private repository is initialized locally with no
remote. No publication or remote repository creation is performed by this split.

Keycloak/AD connectors remain a separate implementation stage. Add them to the
private checkout and enforce `ExecutionPolicy.requireExternalAuthentication()` on
login and continued use of external sessions. Keep local administrator access.
