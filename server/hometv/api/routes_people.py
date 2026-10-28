"""People: GET /api/people/{person_id} (PersonDetail, the actor screen)."""

from fastapi import APIRouter

from hometv.api.deps import CurrentProfile, PeopleDep
from hometv.api.schemas import ErrorResponse, PersonDetail

router = APIRouter(
    prefix="/api",
    tags=["catalog"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)


@router.get(
    "/people/{person_id}",
    response_model=PersonDetail,
    responses={404: {"model": ErrorResponse, "description": "not_found"}},
)
async def get_person(person_id: int, profile: CurrentProfile, people: PeopleDep) -> PersonDetail:
    # async: awaits the shared TMDB fetch; PersonService reaches the DB only via to_thread.
    return await people.detail(person_id, profile.id)
