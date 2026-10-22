"""Static ``web_dist`` serving + SPA fallback (PRD §12).

Any ``GET``/``HEAD`` outside ``/api``:

- an existing file inside ``web_dist`` is served (``/assets/*`` immutable, others ``no-cache``);
- a missing ``/assets/*`` file is a plain-text 404 (never ``index.html``: a stale lazy chunk must
  make the dynamic ``import()`` fail instead of parsing HTML as JavaScript);
- anything else gets ``index.html`` (``no-cache``), or the "Web UI not built" placeholder when
  the dist (or its ``index.html``) is missing.

``/api`` paths never reach this route (``SpaRoute.matches``), so unknown API paths keep the JSON
404 envelope and other methods on API paths keep their 404/405 semantics.
"""

from pathlib import Path

from fastapi import FastAPI
from starlette.requests import Request
from starlette.responses import FileResponse, HTMLResponse, PlainTextResponse, Response
from starlette.routing import Match, Route, get_route_path
from starlette.types import Scope

ASSETS_CACHE = "public, max-age=31536000, immutable"
NO_CACHE = "no-cache"

PLACEHOLDER_HTML = """<!doctype html>
<html lang="es">
<head><meta charset="utf-8"><title>HomeTV</title></head>
<body>
<h1>Web UI not built</h1>
<p>La interfaz web no está compilada (falta <code>web/dist</code>). La API está disponible en
<a href="/api/docs">/api/docs</a>.</p>
</body>
</html>
"""


def is_api_path(path: str) -> bool:
    return path == "/api" or path.startswith("/api/")


def _not_found() -> Response:
    return PlainTextResponse("Not Found", status_code=404, headers={"Cache-Control": "no-store"})


def resolve_inside(root: Path, rel: str) -> Path | None:
    """``root / rel`` resolved, or ``None`` when it escapes ``root`` (traversal) or is invalid."""
    if "\x00" in rel:
        return None
    candidate = (root / rel.lstrip("/")).resolve()
    if candidate != root and not candidate.is_relative_to(root):
        return None
    return candidate


def serve(web_dist: Path, path: str) -> Response:
    """Response for the URL ``path`` (leading ``/``) under ``web_dist``."""
    rel = path.lstrip("/")
    is_asset = rel.startswith("assets/")
    try:
        root = web_dist.resolve(strict=True)
    except OSError:
        root = None
    if root is None or not root.is_dir():
        return _not_found() if is_asset else _placeholder()
    target = resolve_inside(root, rel)
    if target is None:
        return _not_found()
    if target.is_file():
        cache = ASSETS_CACHE if is_asset else NO_CACHE
        return FileResponse(target, headers={"Cache-Control": cache})
    if is_asset:
        return _not_found()
    index = root / "index.html"
    if index.is_file():
        return FileResponse(index, media_type="text/html", headers={"Cache-Control": NO_CACHE})
    return _placeholder()


def _placeholder() -> Response:
    return HTMLResponse(PLACEHOLDER_HTML, headers={"Cache-Control": NO_CACHE})


class SpaRoute(Route):
    """Catch-all route that never matches ``/api`` paths."""

    def matches(self, scope: Scope) -> tuple[Match, Scope]:
        if scope["type"] == "http" and is_api_path(get_route_path(scope)):
            return Match.NONE, {}
        return super().matches(scope)


def _endpoint(request: Request) -> Response:
    # Plain sync endpoint: Starlette runs it in the thread pool (filesystem stats).
    return serve(request.app.state.settings.server.web_dist, get_route_path(request.scope))


def install_spa(app: FastAPI) -> None:
    """Append the catch-all route; call after every API router is included."""
    app.router.routes.append(
        SpaRoute("/{path:path}", _endpoint, methods=["GET", "HEAD"], include_in_schema=False)
    )
