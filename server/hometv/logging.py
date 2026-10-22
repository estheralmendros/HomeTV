"""Logging setup and the per-request access log line.

One line per request: ``METHOD /path STATUS 12.3ms``. The query string is never logged (it may
carry ``?token=``), and neither are headers.
"""

import logging
import time
from typing import Any

_FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"

access_logger = logging.getLogger("hometv.access")


def configure_logging(level: str = "info") -> None:
    numeric = getattr(logging, level.upper(), logging.INFO)
    root = logging.getLogger()
    if not any(getattr(h, "_hometv", False) for h in root.handlers):
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(_FORMAT))
        handler._hometv = True  # type: ignore[attr-defined]
        root.addHandler(handler)
    logging.getLogger("hometv").setLevel(numeric)


class AccessLogMiddleware:
    """Pure ASGI middleware logging one line per HTTP request."""

    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        start = time.perf_counter()
        status = 500

        async def send_wrapper(message: dict[str, Any]) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            elapsed_ms = (time.perf_counter() - start) * 1000
            access_logger.info(
                "%s %s %d %.1fms", scope["method"], scope["path"], status, elapsed_ms
            )
