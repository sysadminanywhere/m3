# Application version

Change only `<revision>` in [`build-config/pom.xml`](../build-config/pom.xml).
Community and Scale inherit this shared Maven parent, including Spring Boot build
metadata. Use `MAJOR.MINOR.PATCH` or a Docker-compatible prerelease such as
`1.1.0-rc.1`. Do not override Maven's `revision` for release builds.

Shared library versions (Vaadin, Spring Integration, MinIO, OkHttp and FTPServer)
are also configured in this parent. Spring Boot manages the remaining platform
dependencies. Update the common parent to keep Community and Scale aligned.

The version is used in Maven artifact coordinates, the JAR manifest
(`Implementation-Version`), `META-INF/build-info.properties`, Settings → About,
Docker's `org.opencontainers.image.version` label and versioned image tags.
JAR filenames stay `m3-community.jar` and `m3-full.jar` for existing launch scripts.
An IDE launch without generated metadata displays “Development build”.

## Build

Build either repository normally with `./mvnw package` or `.\mvnw.cmd package`.
Scale needs its matching shared `../m3` checkout, including `build-config/`.
No installation of the shared parent into the Maven repository is required.

From the Community or Scale repository root, build the Docker image with:

```powershell
.\scripts\docker.ps1 build
```

```sh
sh scripts/docker.sh build
```

The helpers read the shared version automatically and build `m3:community-<version>`
or `m3:<version>`, respectively. Set `M3_IMAGE` to use a different repository/tag.
Extra Docker arguments can follow `build`, for example `--no-cache`.
For a direct Docker build, pass `--build-arg APP_VERSION=<shared version>` and, for
Scale, `--build-context community=../m3`. The Dockerfile rejects a missing or
mismatching version before packaging the application.

## Compose

Use the same helper to pass the current version to Compose without editing `.env`:

```powershell
.\scripts\docker.ps1 compose up --build -d
.\scripts\docker.ps1 compose ps
```

```sh
sh scripts/docker.sh compose up --build -d
sh scripts/docker.sh compose ps
```

The application and managed workers use the same versioned image. Existing
`M3_IMAGE` values in the environment or `.env` take precedence, including published
images. For deployments using only a published image, normal `docker compose`
commands still work with `M3_IMAGE` configured; version helpers are needed for
local image builds.

## Publishing Scale

The Docker Hub workflow reads the version from the selected Community checkout,
passes it to Docker, publishes the corresponding version tag, and verifies the
JAR manifest, build metadata and image label agree. A release Git tag must be
`v<version>`; a mismatch stops publication. SHA and requested aliases such as
`latest` point to the same image. Select matching immutable Community revisions
for releases. See Scale's `docs/dockerhub-publishing.md` for configuration.
