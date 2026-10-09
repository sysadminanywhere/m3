# Independent Community and Scale applications

Community (`m3`) and Scale (`m3-scale`) are separate single-project Maven builds.
Neither has Maven modules, edition profiles or dependencies on private M3 artifacts.
Both have a normal `src/` tree and produce an executable Spring Boot JAR.

| Project | Source | Main class | Output |
| --- | --- | --- | --- |
| Community | Its own `src/` only | `Application` | `target/m3-community.jar` |
| Scale | Its own private `src/` plus matching Community source | `ScaleApplication` | `target/m3-full.jar` |

Community never reads or builds Scale and works without that checkout. Scale needs
Community source when compiling, normally in `../m3`; use
`-Dcommunity.source=/absolute/path/to/m3` for a different location. No preliminary
Community build, Maven install or private artifact repository is needed.

Scale copies shared Java source into its ignored `target/generated-sources/community`
directory during `generate-sources`. It excludes the Community policy and license
API filter, and supplies its own implementations. Shared resources and tests come
from Community. Scale supplies its license UI translations in optional
`i18n/extra_ui_<language>.json` catalogs; these are absent from Community. The resulting
JAR contains the full application and runs without either source checkout. Generated
copies must not be committed or published.

Community has no license page or license-key configuration. Managed worker settings
are supplied by the execution policy; only Scale supplies its verification key.
The existing `023-scale-license` migration remains unchanged for Liquibase checksum
compatibility and because it also creates the shared worker-slot and drain tables.
The Community API filter returns 404 for absent activation endpoints instead of
letting them reach Vaadin’s HTML fallback.

## Build and run

Run the same commands from the root of either project, with JDK 21:

```powershell
.\mvnw.cmd clean package
.\scripts\Initialize-LocalSecurity.ps1
.\scripts\run-app.ps1
```

Tests using Testcontainers need Docker. Add `-DskipTests` if building without it.
On Linux/macOS, use `./mvnw clean package` and run the project's JAR with `java -jar`.
PostgreSQL and RabbitMQ must be configured and running. A separate worker starts with:

```powershell
.\scripts\run-worker.ps1 -Pool default
```

Each application reads its own `.m3/local-security.properties` from its working
directory. When moving an installation between editions, preserve its database,
encryption key and configuration; do not initialize a replacement encryption key
for existing data. Stop the old UI and workers before starting the new edition.
Do not mix editions in an installation.

## IntelliJ IDEA

Open the chosen project's `pom.xml` as a Maven project and select JDK 21. Community
can run `Application.main()` directly. For Scale, run `generate-sources` once and
reload Maven so IDEA imports the generated source directory, then run
`ScaleApplication.main()`. The working directory is the chosen project's root.
There are no edition profiles to select or extra M3 modules to import.

After changing shared Community source, refresh Scale with `generate-sources` or
delegate IDEA build/run actions to Maven. Normal Maven builds refresh it automatically.

## Docker and licensing

From either repository, `docker compose up --build -d` builds and starts its own
stack. Community uses `m3:community`; Scale uses `m3:full` for UI and managed workers.
Compose requires `M3_ADMIN_PASSWORD` and a persistent Base64 AES-256 `M3_SECRET_KEY`
in the environment or a private `.env` file; the local Java credential file does
not supply these Compose variables.
Scale's Dockerfile reads shared source through the named `community` build context:

```powershell
docker build --build-context community=../m3 -t m3:full .
```

The full image starts in Community mode without a signed entitlement. A valid
entitlement enables purchased features without replacing the image. Expiry and
grace restore Community limits while preserving installation identity and data.
Community contains no verifier or activation API and cannot activate Scale.
Keycloak/AD connectors remain a separate implementation stage.

## Validation and publication

From Community:

```powershell
.\mvnw.cmd test
node --test src/test/js/*.test.cjs
python scripts/verify-distribution.py target/m3-community.jar community
python scripts/export-community.py output/community-source.zip
```

From Scale:

```powershell
.\mvnw.cmd test
python ../m3/scripts/verify-distribution.py target/m3-full.jar full
```

Boundary checks verify policy selection and absence of private code in Community.
The Community export includes current source without Git history, secrets, generated
files or the private sibling. Earlier Git history may contain combined implementations;
the export can be used for a clean first public release. Both license agreements and
applicable third-party notices must accompany the full distribution.
