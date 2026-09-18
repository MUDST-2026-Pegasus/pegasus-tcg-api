# ──────────────────────────────────────────────────────────────
# Pegasus TCG API — Makefile
# ──────────────────────────────────────────────────────────────

.PHONY: help build run test clean \
        up down restart logs ps \
        db-migrate db-clean db-repair db-info db-validate \
        codegen compile \
        env check deps bootjar \
        docker-build docker-run docker-stop \
        swagger api-docs seed-catalog test-swagger

# ── Tool aliases ──────────────────────────────────────────────
ifeq ($(OS),Windows_NT)
    gd = gradlew.bat
else
    gd = ./gradlew
endif

dk = docker compose

# ── Default target ────────────────────────────────────────────

help: ## Show this help message (Command: awk … Makefile)
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "\033[36m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ── Spring Boot / Gradle ─────────────────────────────────────

build: ## Build the project, skip tests (Command: ./gradlew build -x test)
	$(gd) build -x test

run: ## Start the application via bootRun (Command: ./gradlew bootRun)
	$(gd) bootRun

test: ## Run all tests with JUnit Platform (Command: ./gradlew test)
	$(gd) test

clean: ## Remove build artifacts (Command: ./gradlew clean)
	$(gd) clean

compile: ## Compile Java sources, triggers codegen (Command: ./gradlew compileJava)
	$(gd) compileJava

bootjar: ## Package the app as an executable JAR (Command: ./gradlew bootJar)
	$(gd) bootJar

deps: ## Display the project dependency tree (Command: ./gradlew dependencies --configuration runtimeClasspath)
	$(gd) dependencies --configuration runtimeClasspath

# ── Docker Compose (PostgreSQL) ──────────────────────────────

up: ## Start containers in detached mode (Command: docker compose up -d)
	$(dk) up -d

down: ## Stop and remove containers (Command: docker compose down)
	$(dk) down

restart: down up ## Restart containers — down then up (Command: docker compose down && docker compose up -d)

logs: ## Tail container logs (Command: docker compose logs -f)
	$(dk) logs -f

ps: ## List running containers (Command: docker compose ps)
	$(dk) ps

# ── Flyway database migrations ───────────────────────────────

db-migrate: ## Run pending Flyway migrations (Command: ./gradlew flywayMigrate)
	$(gd) flywayMigrate

db-clean: ## Drop all objects managed by Flyway — DESTRUCTIVE (Command: ./gradlew flywayClean)
	$(gd) flywayClean

db-repair: ## Repair the Flyway schema history table (Command: ./gradlew flywayRepair)
	$(gd) flywayRepair

db-info: ## Show current Flyway migration status (Command: ./gradlew flywayInfo)
	$(gd) flywayInfo

db-validate: ## Validate applied migrations against available ones (Command: ./gradlew flywayValidate)
	$(gd) flywayValidate

# ── jOOQ code generation ─────────────────────────────────────

codegen: ## Generate jOOQ sources from the database schema (Command: ./gradlew jooqCodegen)
	$(gd) jooqCodegen

# ── Docker image (backend) ───────────────────────────────────

docker-build: ## Build the backend Docker image (Command: docker compose build backend)
	$(dk) build backend

docker-run: ## Build and start the full stack — postgres, minio, backend (Command: docker compose up -d --build)
	$(dk) up -d --build

docker-stop: ## Stop all containers and remove volumes (Command: docker compose down -v)
	$(dk) down -v

# ── Environment setup ────────────────────────────────────────

env: ## Create .env from .env.example, will not overwrite (Command: copy .env.example .env)
	@if not exist .env ( copy .env.example .env && echo .env created from .env.example ) else ( echo .env already exists — skipping )

check: up db-migrate codegen build ## Full check: start DB, migrate, codegen, build (Command: make up db-migrate codegen build)

# ── OpenAPI / Swagger UI ─────────────────────────────────────

swagger: ## Open Swagger UI in the default browser (Command: start http://localhost:8080/swagger-ui.html)
ifeq ($(OS),Windows_NT)
	@start http://localhost:8080/swagger-ui.html
else
	@xdg-open http://localhost:8080/swagger-ui.html 2>/dev/null || open http://localhost:8080/swagger-ui.html
endif

api-docs: ## Download the OpenAPI 3 JSON spec to stdout (Command: curl localhost:8080/v3/api-docs)
	@curl -sS http://localhost:8080/v3/api-docs

test-swagger: ## Run the OpenAPI / Swagger smoke tests (Command: ./gradlew test --tests OpenApiSwaggerSmokeTest)
	$(gd) test --tests '*OpenApiSwaggerSmokeTest'

# ── Catalog dev seed ─────────────────────────────────────────

seed-catalog: ## Load sample catalog data into the local DB (Command: docker exec … psql … < dev_seed.sql)
	@docker exec -i pegasus-tcg-postgres psql -U postgres -d pegasus_tcg -v ON_ERROR_STOP=1 < src/test/resources/db/dev_seed.sql
