# HomeTV — developer commands (server, web, tv).
SERVER_DIR := server
WEB_DIR := web
TV_DIR := tv
DEV_DATA_DIR ?= $(CURDIR)/.dev-data

# Android toolchain for the tv targets: defaults only when unset (or empty) in the environment.
ifeq ($(strip $(JAVA_HOME)),)
JAVA_HOME := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
endif
ifeq ($(strip $(ANDROID_HOME)),)
ANDROID_HOME := /opt/homebrew/share/android-commandlinetools
endif
export JAVA_HOME ANDROID_HOME

.PHONY: install test lint format run perf docker-build compose-config \
	server-install server-test server-lint \
	web-install web-test web-typecheck web-lint web-build web-dev web-e2e \
	tv-test tv-lint tv-build

install: server-install web-install ## Install server (uv) and web (npm ci) dependencies

test: server-test web-test tv-test ## Server tests, the web suite (Vitest, no network), then the TV JVM tests

lint: server-lint web-typecheck web-lint tv-lint ## Ruff lint + format check + mypy, web typecheck + ESLint (react-hooks), TV Android lint

server-install: ## Install server dependencies (uv)
	cd $(SERVER_DIR) && uv sync

server-test: ## Run server tests (unit + service + api; integration when ffmpeg is installed)
	cd $(SERVER_DIR) && uv run pytest -m "not perf"

server-lint: ## Ruff lint + format check + mypy (hometv/, pydantic plugin, unused ignores are errors)
	cd $(SERVER_DIR) && uv run ruff check . && uv run ruff format --check . && uv run mypy

format: ## Auto-fix lint issues and format (server)
	cd $(SERVER_DIR) && uv run ruff check --fix . && uv run ruff format .

run: ## Dev server with reload on 127.0.0.1:8000 (data in .dev-data/, serves web/dist when built)
	mkdir -p $(DEV_DATA_DIR)
	cd $(SERVER_DIR) && HOMETV_DATA_DIR=$(DEV_DATA_DIR) HOMETV_SCAN_ON_STARTUP=false \
		HOMETV_WEB_DIST=$(CURDIR)/$(WEB_DIR)/dist \
		uv run uvicorn hometv.main:create_app --factory --reload --no-access-log --host 127.0.0.1 --port 8000

perf: ## Browse p95 benchmark (marker perf, excluded from make test)
	cd $(SERVER_DIR) && uv run pytest -m perf -s

web-install: ## Install web dependencies from package-lock.json (Node 24)
	cd $(WEB_DIR) && npm ci

web-test: ## Web unit + component tests (Vitest + RTL + MSW)
	cd $(WEB_DIR) && npm test

web-typecheck: ## Web TypeScript check (strict)
	cd $(WEB_DIR) && npm run typecheck

web-lint: ## Web ESLint (react-hooks rules only: rules-of-hooks, exhaustive-deps)
	cd $(WEB_DIR) && npm run lint

web-build: ## Build the SPA into web/dist (served by the server)
	cd $(WEB_DIR) && npm run build

web-dev: ## Vite dev server on http://localhost:5173 (proxies /api to HOMETV_API_TARGET, default 127.0.0.1:8000)
	cd $(WEB_DIR) && npm run dev

web-e2e: ## Optional Playwright smoke (Chromium) against HOMETV_E2E_URL (default http://127.0.0.1:8000)
	cd $(WEB_DIR) && npm run e2e

docker-build: ## Build the linux/amd64 image (deploy/Dockerfile, context = repo root)
	docker build --platform=linux/amd64 -f deploy/Dockerfile -t hometv .

compose-config: ## Validate deploy/docker-compose.yml with the example env
	docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example config -q

tv-test: ## TV app JVM tests (core: JUnit 5 + MockWebServer + coroutines-test; app unit tests)
	cd $(TV_DIR) && ./gradlew :core:test :app:testDebugUnitTest

tv-lint: ## TV app Android lint (HardcodedText/SetTextI18n are errors)
	cd $(TV_DIR) && ./gradlew :app:lintDebug

tv-build: ## Signed release APK (needs tv/keystore.properties) -> tv/app/build/outputs/apk/release/
	cd $(TV_DIR) && ./gradlew :app:assembleRelease
