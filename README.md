# Pegasus TCG API

REST API for the Pegasus trading-card marketplace. It provides authentication, addresses, seller onboarding, catalogue management, collections, uploads, and platform administration.

Base URL when running locally: `http://localhost:8080/api/v1`

The Postman collection contains the same 72 application endpoints: [Pegasus-API](https://godgame.postman.co/workspace/Personal-Workspace~e5d0e575-1d67-4cda-ae96-d28efd26af90/collection/50039359-015a9714-1e65-45ad-a9f6-fcb9efd922e9?action=share&source=copy-link&creator=50039359).

## Requirements

- Java 25 (the Gradle toolchain downloads or uses Java 25)
- Docker Desktop with Docker Compose
- A free local PostgreSQL port `5432` and MinIO ports `9000` and `9001`

## Install and run

```bash
cp .env.example .env
# Edit .env: at minimum replace JWT_SECRET and passwords.
openssl rand -base64 48

docker compose up -d
./gradlew bootRun
```

The application runs on port `8080` by Spring Boot's default. On startup, Flyway applies migrations and jOOQ generates database code; PostgreSQL must be healthy before running Gradle.

Verify the service:

```bash
curl http://localhost:8080/api/v1/games
```

Run the unit tests:

```bash
./gradlew test
```

Stop local services while retaining database and object-storage data:

```bash
docker compose down
```

To remove local service data too, run `docker compose down -v`.

### Environment variables

Copy `.env.example` rather than committing `.env`. The important values are:

| Variable | Purpose |
| --- | --- |
| `JWT_SECRET` | Base64 string generated with `openssl rand -base64 48`; required to start the API. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection used by the app, Flyway, and jOOQ. |
| `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | MinIO credentials; required by Compose and the API. |
| `MINIO_ENDPOINT` | MinIO URL used by the API, normally `http://localhost:9000`. |
| `MINIO_PUBLIC_ENDPOINT` | URL put into browser-facing presigned URLs. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated web origins, default `http://localhost:3000`. |
| `AUTH_DEV_EXPOSE_TOKENS` | Set `true` only for local password-reset development. |

MinIO's S3 API is at `http://localhost:9000`; its console is at `http://localhost:9001`.

### Optional local catalogue seed

After migrations, load sample Pokemon catalogue data:

```bash
docker exec -i pegasus-tcg-postgres psql -U postgres -d pegasus_tcg \
  -v ON_ERROR_STOP=1 < src/test/resources/db/dev_seed.sql
```

The seed is safe to rerun and does not create an admin user.

## Build and Run with Docker (full stack)

You can run the entire backend infrastructure — PostgreSQL, MinIO, **and** the Spring Boot application — with a single command using Docker Compose. The Dockerfile performs a multi-stage build that runs Gradle inside an isolated build container, so **you do not need Java or Gradle installed on your host machine**.

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) installed.
- [Docker Compose](https://docs.docker.com/compose/install/) installed.

### Setup

1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```
2. Open `.env` and fill in any required secrets such as `JWT_SECRET`, `MINIO_ACCESS_KEY`, and `MINIO_SECRET_KEY`.

### Running the Application

To build the Spring Boot backend image and start all services (Backend, PostgreSQL, MinIO) in the background, run:

```bash
docker compose up -d --build
```

### Accessing the services

- **Backend API:** `http://localhost:8080` (or `BACKEND_PORT` in `.env`)
- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **MinIO Console:** `http://localhost:9001` (login with `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`)
- **PostgreSQL:** `localhost:5432`

### Checking logs

To check the logs of the backend service to verify it started successfully:

```bash
docker compose logs -f backend
```

### Stopping the application

Stop services while keeping data volumes:

```bash
docker compose stop
```

Stop and remove containers (data volumes are retained):

```bash
docker compose down
```

## Authentication and roles

Most endpoints expect a bearer token:

```http
Authorization: Bearer <accessToken>
```

`POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/password/reset`, and public catalogue reads do not need a token. Admin endpoints also require the `ADMIN` role. Access tokens expire after 15 minutes; refresh tokens expire after 30 days.

For local development, register an account, then grant it the admin role directly in PostgreSQL if you need to exercise admin routes:

```sql
INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id FROM user_account u, app_role r
WHERE u.username = 'yourname' AND r.code = 'ADMIN';
```

## Quick examples

Register and save the returned access token:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Ploy","username":"ploy","email":"ploy@example.com","password":"correct-horse-battery-staple"}'
```

Browse public catalogue data:

```bash
curl 'http://localhost:8080/api/v1/catalog/products?gameId=1&q=Pikachu&page=0&size=20'
```

Add a card to the signed-in user's collection:

```bash
curl -X POST http://localhost:8080/api/v1/collection \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{"catalogVariantId":1,"condition":"NM","quantity":1,"publicItem":false}'
```

Upload flow: first request a presigned URL with `POST /uploads/presign`, upload the file directly to the returned URL, then pass the returned `key` to the resource that owns the image. Use `CATALOG_IMAGE` for catalogue art and `COLLECTION_IMAGE` for a personal collection photo.

## Endpoint list

All paths below are relative to `/api/v1`. `*` requires a bearer token and `ADMIN` requires a bearer token with the admin role.

### Auth and user access (8)

```text
POST   /auth/register
POST   /auth/login
POST   /auth/refresh
POST   /auth/logout
POST   /auth/logout-all                         *
GET    /auth/me                                 *
POST   /auth/password/reset
POST   /auth/password/change                    *
```

### Addresses (5, signed-in user)

```text
GET    /addresses                               *
GET    /addresses/{addressId}                   *
POST   /addresses                               *
PUT    /addresses/{addressId}                   *
DELETE /addresses/{addressId}                   *
```

### Seller and verification (14)

```text
GET    /sellers/me                              *
POST   /sellers/me/apply                        *
PUT    /sellers/me/settings                     *
GET    /sellers/me/verifications                *
POST   /sellers/me/verifications                *
GET    /sellers/me/shipping-options             *
POST   /sellers/me/shipping-options             *
PUT    /sellers/me/shipping-options/{optionId}  *
DELETE /sellers/me/shipping-options/{optionId}  *
GET    /sellers/me/payout-account               *

GET    /admin/verifications                     ADMIN
POST   /admin/verifications/{verificationId}/start-review ADMIN
POST   /admin/verifications/{verificationId}/approve      ADMIN
POST   /admin/verifications/{verificationId}/reject       ADMIN
```

### Catalogue taxonomy (17)

```text
GET    /games
GET    /games/{gameId}
GET    /games/{gameId}/attributes
GET    /categories?gameId={gameId}
GET    /card-sets?gameId={gameId}

GET    /admin/games?includeInactive=true                         ADMIN
POST   /admin/games                                               ADMIN
PUT    /admin/games/{gameId}                                      ADMIN
GET    /admin/games/{gameId}/attributes                           ADMIN
POST   /admin/games/{gameId}/attributes                           ADMIN
PUT    /admin/games/{gameId}/attributes/{attributeId}             ADMIN
DELETE /admin/games/{gameId}/attributes/{attributeId}             ADMIN
GET    /admin/categories?gameId={gameId}&includeInactive=true     ADMIN
POST   /admin/categories                                          ADMIN
PUT    /admin/categories/{categoryId}                             ADMIN
POST   /admin/games/{gameId}/card-sets                            ADMIN
PUT    /admin/card-sets/{cardSetId}                               ADMIN
```

### Catalogue products (15)

```text
GET    /catalog/products?gameId={gameId}&q={query}&page=0&size=20
GET    /catalog/variants/by-code/{code}
GET    /catalog/products/{idOrSlug}
GET    /catalog/products/{productId}/variants
GET    /catalog/variants/{variantId}
GET    /catalog/products/{productId}/images

GET    /admin/catalog/products?activeOnly=false                   ADMIN
POST   /admin/catalog/products                                    ADMIN
PUT    /admin/catalog/products/{productId}                        ADMIN
GET    /admin/catalog/products/{productId}/variants               ADMIN
POST   /admin/catalog/products/{productId}/variants               ADMIN
PUT    /admin/catalog/products/{productId}/variants/{variantId}   ADMIN
POST   /admin/catalog/products/{productId}/images                 ADMIN
PUT    /admin/catalog/products/{productId}/images/{imageId}/primary ADMIN
DELETE /admin/catalog/products/{productId}/images/{imageId}       ADMIN
```

`GET /catalog/products` and its admin counterpart support `gameId`, `categoryId`, `cardSetId`, `productType`, `q`, `sort`, `page`, `size`, plus dynamic attribute filters such as `attr.hp=200`. Supported sort values are `name`, `newest`, and `cardnumber`.

### Collections and uploads (8)

```text
POST   /uploads/presign                          *

GET    /collection                               *
GET    /collection/summary                       *
GET    /collection/{itemId}                      *
POST   /collection                               *
PUT    /collection/{itemId}                      *
DELETE /collection/{itemId}                      *
GET    /users/{username}/collection
```

`GET /collection` accepts optional `gameId`, `variantId`, `publicItem`, `page`, and `size`. Public collections return only items their owner marked public and exclude personal purchase, note, certificate, and image-key data.

### Admin settings and user roles (5)

```text
GET    /admin/settings                           ADMIN
GET    /admin/settings/{key}                     ADMIN
PUT    /admin/settings/{key}                     ADMIN
POST   /users/{userId}/roles                     ADMIN
DELETE /users/{userId}/roles/{role}              ADMIN
```

## API conventions

- JSON requests use `Content-Type: application/json`.
- Successful responses use the common `ApiResponse` envelope with `success`, `message`, and `data`.
- List endpoints that paginate use zero-based `page` and a `size` default of `20`.
- Error responses include a stable code in `data.code`; use the code rather than matching the human-readable message.
- Catalogued products and variants are deactivated with `active: false`; they are not deleted because other records may reference them.

## Project layout

```text
src/main/java/.../controller   HTTP endpoints
src/main/java/.../service      application rules
src/main/java/.../repository   jOOQ database access
src/main/resources/db/migration Flyway migrations
src/test/resources/db/dev_seed.sql optional local catalogue seed
docker-compose.yaml            PostgreSQL, MinIO, and backend
Dockerfile                     multi-stage build for the backend image
```

## Common problems

- `./gradlew` fails before compile: start PostgreSQL first; jOOQ code generation depends on a migrated database.
- MinIO refuses to start: set `MINIO_ACCESS_KEY` and `MINIO_SECRET_KEY` in `.env`.
- The API rejects a token: make sure `JWT_SECRET` did not change after the token was issued.
- Browser image upload fails: `MINIO_PUBLIC_ENDPOINT` must be reachable from the browser and `CORS_ALLOWED_ORIGINS` must include the web app's origin.


## Local Development with Makefile

The project includes a `Makefile` that wraps common Gradle and Docker Compose commands into short, memorable targets. Run `make help` to list every target with its description.

### Windows prerequisites

The Makefile uses GNU Make, which is not bundled with Windows. Pick **one** of these options:

- **winget** (recommended): `winget install GnuWin32.Make` — then run `make` from any terminal.
- **Git Bash**: ships with `make` out of the box. Open Git Bash and run targets from there.
- **IntelliJ IDEA**: install the **Makefile Language** plugin (Settings → Plugins → Marketplace). It adds a run-gutter icon next to each target and a Makefile tool window.

On Windows the Makefile automatically selects `gradlew.bat` instead of `./gradlew`; no manual configuration is needed.

### Target reference

#### General

| Target | Description |
| --- | --- |
| `make help` | Print all available targets with descriptions |
| `make env` | Create `.env` from `.env.example` (will not overwrite an existing file) |
| `make check` | Full pipeline: start containers → migrate → codegen → build |

#### Build and run

| Target | Description |
| --- | --- |
| `make build` | Build the project, skipping tests (`./gradlew build -x test`) |
| `make run` | Start the application via Spring Boot (`./gradlew bootRun`) |
| `make test` | Run the full test suite with JUnit Platform (`./gradlew test`) |
| `make clean` | Remove build artifacts (`./gradlew clean`) |
| `make compile` | Compile Java sources; triggers jOOQ codegen (`./gradlew compileJava`) |
| `make bootjar` | Package the app as an executable JAR (`./gradlew bootJar`) |
| `make deps` | Display the runtime dependency tree |

#### Docker Compose

| Target | Description |
| --- | --- |
| `make up` | Start PostgreSQL and MinIO in detached mode (`docker compose up -d`) |
| `make down` | Stop and remove containers (`docker compose down`) |
| `make restart` | Shortcut for `down` then `up` |
| `make logs` | Tail container logs (`docker compose logs -f`) |
| `make ps` | List running containers |
| `make docker-build` | Build the backend Docker image |
| `make docker-run` | Build and start the full stack — Postgres, MinIO, backend |
| `make docker-stop` | Stop all containers **and remove volumes** (`docker compose down -v`) |

#### Database

| Target | Description |
| --- | --- |
| `make db-migrate` | Run pending Flyway migrations (`./gradlew flywayMigrate`) |
| `make db-clean` | Drop all Flyway-managed objects — **destructive** (`./gradlew flywayClean`) |
| `make db-repair` | Repair the Flyway schema history table |
| `make db-info` | Show current migration status |
| `make db-validate` | Validate applied migrations against available ones |
| `make codegen` | Generate jOOQ sources from the database schema |
| `make seed-catalog` | Load sample Pokemon catalogue data into the local DB (`dev_seed.sql`) |

#### OpenAPI / Swagger

| Target | Description |
| --- | --- |
| `make swagger` | Open Swagger UI in the default browser (`http://localhost:8080/swagger-ui.html`) |
| `make api-docs` | Download the OpenAPI 3 JSON spec to stdout via `curl` |
| `make test-swagger` | Run the OpenAPI / Swagger smoke tests only |

### Typical workflows

**First-time setup:**

```bash
make env          # create .env from template
# edit .env — set JWT_SECRET, MinIO keys, etc.
make check        # start DB, migrate, codegen, build
make run          # start the API on port 8080
```

**Day-to-day development:**

```bash
make up           # ensure containers are running
make run          # start the API
make test         # run tests after changes
make swagger      # open Swagger UI to explore endpoints
```

**Reset and reseed the database:**

```bash
make db-clean     # wipe the schema
make db-migrate   # reapply all migrations
make seed-catalog # load sample catalogue data
```