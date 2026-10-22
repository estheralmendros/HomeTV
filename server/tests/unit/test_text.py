import pytest

from hometv.text import normalize_title, search_text, sort_title


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Amélie", "amelie"),
        ("AMÉLIE", "amelie"),
        ("Fast & Furious", "fast and furious"),
        ("Fast&Furious", "fast and furious"),
        ("Spider-Man: No Way Home", "spiderman no way home"),
        ("  The   Office  ", "the office"),
        ("L'Âge d'or", "lage dor"),
        ("Straße", "strasse"),
        ("Mr. Robot", "mr robot"),
        ("2001: A Space Odyssey", "2001 a space odyssey"),
        ("Pokémon: The Movie", "pokemon the movie"),
        ("snake_case", "snakecase"),
        ("L’Âge", "lage"),
        ("", ""),
        ("!!!", ""),
    ],
)
def test_normalize_title(raw, expected):
    assert normalize_title(raw) == expected


@pytest.mark.parametrize(
    ("title", "expected"),
    [
        ("The Matrix", "matrix"),
        ("A Quiet Place", "quiet place"),
        ("An American Tail", "american tail"),
        ("El laberinto del fauno", "laberinto del fauno"),
        ("La casa de papel", "casa de papel"),
        ("Los otros", "otros"),
        ("Las chicas del cable", "chicas del cable"),
        ("Un franco, 14 pesetas", "franco 14 pesetas"),
        ("Una historia verdadera", "historia verdadera"),
        ("Le Fabuleux Destin d'Amélie Poulain", "fabuleux destin damelie poulain"),
        ("Les Misérables", "miserables"),
        ("L'Auberge espagnole", "auberge espagnole"),
        ("THE OFFICE", "office"),
        ("Theodore Rex", "theodore rex"),
        ("Anastasia", "anastasia"),
        ("Alien", "alien"),
        ("The", "the"),
        ("Dune", "dune"),
    ],
)
def test_sort_title(title, expected):
    assert sort_title(title) == expected


def test_search_text_combines_title_and_original_title():
    assert search_text("El viaje de Chihiro", "Sen to Chihiro no Kamikakushi") == (
        "el viaje de chihiro sen to chihiro no kamikakushi"
    )


def test_search_text_without_original_title():
    assert search_text("Dune", None) == "dune"


def test_search_text_accents_and_ampersand():
    assert search_text("Amélie & Co", "Amélie") == "amelie and co amelie"
