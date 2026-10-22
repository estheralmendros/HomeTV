# syntax=docker/dockerfile:1
# check=skip=FromPlatformFlagConstDisallowed
# HomeTV server image (PRD "Deployment"). Build from the repo root:
#   docker build --platform=linux/amd64 -f deploy/Dockerfile -t hometv .
# docker-compose.yml does this with `build: { context: .., dockerfile: deploy/Dockerfile }`.
# prd2.md adds a `web-build` stage (node:24-bookworm-slim, `npm ci && npm run build` in web/)
# whose web/dist is copied to /app/web/dist. Until then the server serves "Web UI not built".

ARG PYTHON_IMAGE=python:3.12-slim-bookworm
ARG UV_IMAGE=ghcr.io/astral-sh/uv:0.12.5

FROM ${UV_IMAGE} AS uv

# ---------------------------------------------------------------------------------------------
# Stage 1: server-build. Locked, dev-free virtualenv in /app/.venv.
# ---------------------------------------------------------------------------------------------
FROM --platform=linux/amd64 ${PYTHON_IMAGE} AS server-build
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app
# Dependencies first (cached layer), then the project itself.
COPY server/pyproject.toml server/uv.lock server/.python-version ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project
COPY server/hometv ./hometv
COPY server/alembic.ini ./alembic.ini
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

# ---------------------------------------------------------------------------------------------
# Stage 2: runtime. ffmpeg + ffprobe from Debian bookworm (5.1), util-linux (ionice) from slim.
# ---------------------------------------------------------------------------------------------
FROM --platform=linux/amd64 ${PYTHON_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && command -v ffmpeg && command -v ffprobe && command -v ionice && command -v nice \
    && groupadd --gid 1000 hometv \
    && useradd --uid 1000 --gid 1000 --no-create-home --home-dir /app --shell /usr/sbin/nologin hometv \
    && mkdir -p /data /app/web \
    && chown hometv:hometv /data
WORKDIR /app
COPY --from=server-build /app/.venv /app/.venv
COPY --from=server-build /app/hometv /app/hometv
COPY --from=server-build /app/alembic.ini /app/alembic.ini
ENV PATH=/app/.venv/bin:$PATH \
    HOMETV_DATA_DIR=/data \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
USER hometv
VOLUME ["/data"]
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["python", "-c", "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/api/health' % os.environ.get('HOMETV_PORT', '8000'), timeout=4).read()"]
# `hometv` = hometv.main:run: reads settings (HOMETV_HOST/HOMETV_PORT), exactly one uvicorn
# worker, proxy headers on (tailscale serve proxies from 127.0.0.1).
CMD ["hometv"]
