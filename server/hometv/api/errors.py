"""Error envelope + exception handlers.

Every 4xx/5xx JSON error: ``{"error": {"code", "message", "details"}}``. Raise ``ApiError`` from
routes/services; ``message`` stays a generic English sentence, machine-readable extras go in
``details`` (e.g. ``details.detail``).
"""

import logging
from collections.abc import Mapping
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("hometv.api")

_STATUS_CODES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "conflict",
    413: "payload_too_large",
    415: "unsupported_media_type",
    422: "validation_error",
    429: "too_many_requests",
    500: "internal_error",
    502: "bad_gateway",
    503: "service_unavailable",
    504: "gateway_timeout",
}


class ApiError(Exception):
    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        details: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = dict(details or {})
        self.headers = dict(headers or {})


def unauthorized() -> ApiError:
    return ApiError(401, "unauthorized", "Authentication required")


def error_response(
    status_code: int,
    code: str,
    message: str,
    details: Mapping[str, Any] | None = None,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    body = {"error": {"code": code, "message": message, "details": dict(details or {})}}
    return JSONResponse(status_code=status_code, content=body, headers=dict(headers or {}))


async def _api_error(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, ApiError)
    return error_response(exc.status_code, exc.code, exc.message, exc.details, exc.headers)


async def _http_error(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)
    code = _STATUS_CODES.get(exc.status_code, "http_error")
    message = exc.detail if isinstance(exc.detail, str) else code.replace("_", " ").capitalize()
    return error_response(exc.status_code, code, message, headers=exc.headers)


async def _validation_error(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)
    errors = [
        {"loc": list(err.get("loc", ())), "msg": str(err.get("msg", ""))} for err in exc.errors()
    ]
    return error_response(422, "validation_error", "Request validation failed", {"errors": errors})


async def _unhandled(_request: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled_error", exc_info=exc)
    return error_response(500, "internal_error", "Internal server error")


def install_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(ApiError, _api_error)
    app.add_exception_handler(StarletteHTTPException, _http_error)
    app.add_exception_handler(RequestValidationError, _validation_error)
    app.add_exception_handler(Exception, _unhandled)
