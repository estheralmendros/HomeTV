"""The repo-root ``version.txt`` is the release version and the only place it is written.

The TV app reads it in ``tv/app/build.gradle.kts``; the server package takes it at build time
(hatchling ``[tool.hatch.version]``); the web package carries no version. A bump edits
``version.txt`` only.
"""

import json
import tomllib
from pathlib import Path

from hometv import __version__

REPO = Path(__file__).resolve().parents[3]
RELEASE = (REPO / "version.txt").read_text().strip()


def test_server_reports_the_release_version():
    assert __version__ == RELEASE


def test_pyproject_reads_the_version_from_version_txt():
    pyproject = tomllib.loads((REPO / "server" / "pyproject.toml").read_text())
    project = pyproject["project"]
    assert "version" not in project, "the version lives in version.txt only"
    assert "version" in project["dynamic"]
    assert pyproject["tool"]["hatch"]["version"]["path"] == "../version.txt"
    # uv reinstalls the package (new metadata) when version.txt changes.
    assert {"file": "../version.txt"} in pyproject["tool"]["uv"]["cache-keys"]


def test_web_package_carries_no_version():
    package = json.loads((REPO / "web" / "package.json").read_text())
    assert "version" not in package, "the version lives in version.txt only"
    lock = json.loads((REPO / "web" / "package-lock.json").read_text())
    assert "version" not in lock
    assert "version" not in lock["packages"][""]
