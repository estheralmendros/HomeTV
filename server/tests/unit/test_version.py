"""The repo-root ``version.txt`` is the release version; every place that reports one follows it.

The TV app reads ``version.txt`` itself (``tv/app/build.gradle.kts``); the server and the web
package carry a copy, checked here.
"""

import json
import tomllib
from pathlib import Path

from hometv import __version__

REPO = Path(__file__).resolve().parents[3]
RELEASE = (REPO / "version.txt").read_text().strip()


def test_server_reports_the_release_version():
    assert __version__ == RELEASE


def test_pyproject_version_matches_release():
    project = tomllib.loads((REPO / "server" / "pyproject.toml").read_text())["project"]
    assert project["version"] == RELEASE


def test_web_package_version_matches_release():
    package = json.loads((REPO / "web" / "package.json").read_text())
    assert package["version"] == RELEASE
