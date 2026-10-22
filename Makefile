# HomeTV — developer commands. Later PRDs extend this file (web, tv).
SERVER_DIR := server
DEV_DATA_DIR ?= $(CURDIR)/.dev-data

.PHONY: install test lint format run perf docker-build compose-config

install: ## Install server dependencies (uv)
	cd $(SERVER_DIR) && uv sync

test: ## Run server tests (unit + service + api; integration when ffmpeg is installed)
	cd $(SERVER_DIR) && uv run pytest -m "not perf"

lint: ## Ruff lint + format check
	cd $(SERVER_DIR) && uv run ruff check . && uv run ruff format --check .

format: ## Auto-fix lint issues and format
	cd $(SERVER_DIR) && uv run ruff check --fix . && uv run ruff format .

run: ## Dev server with reload on 127.0.0.1:8000 (data in .dev-data/)
	mkdir -p $(DEV_DATA_DIR)
	cd $(SERVER_DIR) && HOMETV_DATA_DIR=$(DEV_DATA_DIR) HOMETV_SCAN_ON_STARTUP=false \
		uv run uvicorn hometv.main:create_app --factory --reload --no-access-log --host 127.0.0.1 --port 8000

perf: ## Browse p95 benchmark (marker perf, excluded from make test)
	cd $(SERVER_DIR) && uv run pytest -m perf -s

docker-build: ## Build the linux/amd64 server image (deploy/Dockerfile, context = repo root)
	docker build --platform=linux/amd64 -f deploy/Dockerfile -t hometv .

compose-config: ## Validate deploy/docker-compose.yml with the example env
	docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example config -q
