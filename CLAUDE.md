# CLAUDE.md

Guidance for Claude Code in this repository.

## Build

```bash
./mvnw clean package   # tests skipped by default (skipTests=true in pom.xml), run them in IntelliJ
./mvnw verify          # + checkstyle (checkstyle.xml), same check runs in CI on every PR
```

Docker images are built only by GitHub Actions from `DockerfileYandex`
(base image with resolved dependencies — `Dockerfile-base`, workflow `base-image.yml`).

## Conventions

- Package `kz.janapost.dwhservice`; layout: `config/` (+`interceptor/`), `controller/{client,admin,call_centre}/[v1,v2]`,
  `service/`, `dal/`, `model/{co,fo,vo}`, `exception/`, `scheduled/`, `rmi/`, `util/`.
- All controllers are POST. Responses are `GenericResult.ok(...)`; get/delete take `GetByIdFO` in the body.
  The caller comes from `@RequestAttribute("payload")` (set by `ClientAuthInterceptor` / `AdminAuthInterceptor` from `X-TokenInfo`).
- Services: interface + implementation with a creation-date suffix, e.g. `ExportService` → `ExportService_12_11_2026`.
- Config: production reads only `share/application-yandex.properties`. All secrets/addresses via `${ENV}`,
  no dangerous defaults, own keys under `kz.janapost.dwh-service.*`. Never commit secrets to `src/main/resources/application.properties`.
- Time zone is `Asia/Aqtau` (set in `DwhServiceApplication` and in the Docker image).
- Feature docs go to `context-files/dwh-service/`.
