"""Query parameters shared by GET /api/films and GET /api/shows (PRD REST "Catalog").

``PageNumber`` (``MAX_PAGE``) is also used by GET /api/watchlist.
"""

from typing import Annotated, Literal

from fastapi import Query

from hometv.api.errors import ApiError
from hometv.catalog import ListParams, Order, SortKey

Year = Annotated[int | None, Query(ge=1900, le=2100)]

# Upper bound for every paged list: keeps (page - 1) * page_size inside SQLite's int64 OFFSET.
MAX_PAGE = 1_000_000
PageNumber = Annotated[int, Query(ge=1, le=MAX_PAGE)]


def list_params(
    page: PageNumber = 1,
    page_size: Annotated[int, Query(ge=1, le=200)] = 50,
    q: Annotated[str | None, Query(min_length=1, max_length=100)] = None,
    sort: SortKey = "title",
    order: Order = "asc",
    year_from: Year = None,
    year_to: Year = None,
    genre: int | None = None,
    watched: Literal["true", "false"] | None = None,  # strict: not FastAPI's lenient bool
) -> ListParams:
    if year_from is not None and year_to is not None and year_from > year_to:
        raise ApiError(
            422,
            "validation_error",
            "Request validation failed",
            {"errors": [{"loc": ["query", "year_from"], "msg": "year_from must be <= year_to"}]},
        )
    return ListParams(
        q=q,
        sort=sort,
        order=order,
        year_from=year_from,
        year_to=year_to,
        genre=genre,
        watched=None if watched is None else watched == "true",
        page=page,
        page_size=page_size,
    )
