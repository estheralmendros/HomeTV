# HomeTV — developer commands. Later PRDs extend this file (tv).
SERVER_DIR := server
WEB_DIR := web
DEV_DATA_DIR ?= $(CURDIR)/.dev-data

.PHONY: install test lint format run perf docker-build compose-config \
	server-install server-test server-lint \
	web-install web-test web-typecheck web-build web-dev web-e2e

install: server-install web-install ## Install server (uv) and web (npm ci) dependencies

test: server-test web-test ## Server tests, then the web suite (Vitest, no network)

lint: server-lint web-typecheck ## Ruff lint + format check, web typecheck

server-install: ## Install server dependencies (uv)
	cd $(SERVER_DIR) && uv sync

server-test: ## Run server tests (unit + service + api; integration when ffmpeg is installed)
	cd $(SERVER_DIR) && uv run pytest -m "not perf"

server-lint: ## Ruff lint + format check
	cd $(SERVER_DIR) && uv run ruff check . && uv run ruff format --check .

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
