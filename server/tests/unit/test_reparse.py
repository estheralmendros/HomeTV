"""Pure decisions of the scanner parse upgrade (``hometv.reparse``)."""

from hometv.reparse import (
    Alias,
    Conflict,
    Defer,
    FilmState,
    Keep,
    Member,
    MergeInto,
    MergePending,
    Noop,
    Rename,
    Retitle,
    Skip,
    Split,
    plan_film_upgrade,
    plan_protected_key,
)

F = 10
OLD = ("quiero", 2020)
NEW = ("te quiero", 2020)


def film(key=OLD, title="quiero", eligible=True) -> FilmState:
    return FilmState(id=F, key=key, title=title, eligible=eligible)


def member(file_id, key, title=None) -> Member:
    return Member(file_id, key, title if title is not None else (key[0] if key else None))


def lookup_of(table: dict):
    return lambda key: table.get(key)


def test_noop_when_every_member_keeps_the_key_and_title():
    plan = plan_film_upgrade(
        film(), [member(1, OLD, "quiero"), member(2, OLD, "quiero")], lookup_of({OLD: F})
    )
    assert plan == Noop()


def test_punctuation_retitle_keeps_the_key():
    old = ("dune parte 2", 2024)
    plan = plan_film_upgrade(
        film(key=old, title="Dune: Parte 2"),
        [member(1, old, "Dune - Parte 2")],
        lookup_of({old: F}),
    )
    assert plan == Retitle("Dune - Parte 2")


def test_rename_when_the_new_key_is_free():
    plan = plan_film_upgrade(film(), [member(1, NEW, "Te quiero")], lookup_of({}))
    assert plan == Rename("Te quiero", 2020)


def test_rename_uses_the_year_of_the_key():
    plan = plan_film_upgrade(film(), [member(1, ("te quiero", None), "Te quiero")], lookup_of({}))
    assert plan == Rename("Te quiero", None)


def test_merge_into_the_film_owning_the_new_key():
    plan = plan_film_upgrade(film(), [member(1, NEW)], lookup_of({NEW: 3}))
    assert plan == MergeInto(3)


def test_merge_via_alias_is_the_same_decision():
    # the lookup (alias first, then film) answers for the key whatever owns it
    plan = plan_film_upgrade(film(), [member(1, NEW), member(2, NEW)], lookup_of({NEW: 7}))
    assert plan == MergeInto(7)


def test_alias_to_the_film_itself_is_not_a_merge():
    plan = plan_film_upgrade(film(), [member(1, NEW, "Te quiero")], lookup_of({NEW: F}))
    assert plan == Retitle("Te quiero")


def test_split_keeps_files_with_the_old_key():
    other = ("otra", 2020)
    plan = plan_film_upgrade(
        film(),
        [member(1, OLD, "quiero"), member(2, other, "Otra")],
        lookup_of({OLD: F}),
    )
    assert plan == Split(stay=(1,), moves=(2,), detached=(), merge_into=None)


def test_split_leaving_none_merges_into_the_majority_key():
    a, b = ("a", 2020), ("b", 2020)
    plan = plan_film_upgrade(film(), [member(1, a), member(2, b), member(3, b)], lookup_of({}))
    assert plan == Split(stay=(), moves=(1, 2, 3), detached=(), merge_into=b)


def test_split_majority_tie_goes_to_the_lowest_existing_film_id():
    a, b = ("a", 2020), ("b", 2020)
    plan = plan_film_upgrade(film(), [member(1, a), member(2, b)], lookup_of({a: 9, b: 4}))
    assert plan.merge_into == b
    plan = plan_film_upgrade(film(), [member(1, a), member(2, b)], lookup_of({a: 4}))
    assert plan.merge_into == a  # an existing film wins over one still to be created


def test_split_tie_between_new_films_follows_the_first_file():
    a, b = ("a", 2020), ("b", 2020)
    plan = plan_film_upgrade(film(), [member(5, b), member(6, a)], lookup_of({}))
    assert plan.merge_into == b


def test_non_film_member_leaves_the_group():
    plan = plan_film_upgrade(
        film(), [member(1, OLD, "quiero"), Member(2, None, None)], lookup_of({OLD: F})
    )
    assert plan == Split(stay=(1,), moves=(), detached=(2,), merge_into=None)


def test_group_where_no_member_is_a_film_keeps_everything_on_the_film():
    plan = plan_film_upgrade(film(), [Member(1, None, None), Member(2, None, None)], lookup_of({}))
    assert plan == Keep()


def test_incomplete_group_is_deferred():
    plan = plan_film_upgrade(film(), [member(1, NEW)], lookup_of({}), complete=False)
    assert plan == Defer()


def test_film_no_longer_eligible_is_skipped():
    plan = plan_film_upgrade(film(eligible=False), [member(1, NEW)], lookup_of({}))
    assert plan == Skip()


def test_empty_group_is_skipped():
    assert plan_film_upgrade(film(), [], lookup_of({})) == Skip()


# --- protected film: key step ---------------------------------------------------------------


def test_protected_same_key_does_nothing():
    assert plan_protected_key(F, OLD, OLD, found=None, found_protected=False) is None


def test_protected_key_already_resolving_to_the_film_does_nothing():
    assert plan_protected_key(F, OLD, NEW, found=F, found_protected=True) is None


def test_protected_free_key_gets_an_alias():
    assert plan_protected_key(F, OLD, NEW, found=None, found_protected=False) == Alias()


def test_protected_key_owned_by_a_pending_film_merges_it():
    assert plan_protected_key(F, OLD, NEW, found=3, found_protected=False) == MergePending(3)


def test_protected_key_owned_by_another_protected_film_is_a_conflict():
    assert plan_protected_key(F, OLD, NEW, found=3, found_protected=True) == Conflict(3)
