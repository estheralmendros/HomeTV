import logging
import os
import stat
from pathlib import Path

import pytest

from hometv.config import (
    ConfigError,
    Settings,
    check_library_folders,
    load_settings,
    prepare_hls_dir,
    prepare_runtime,
    wipe_hls_sessions,
)
from hometv.libraries import LibraryFolderSet

SESSION_ID = "0123456789abcdef0123456789abcdef"


def write_yaml(path: Path, text: str) -> Path:
    path.write_text(text, encoding="utf-8")
    return path


# --- Loading and precedence ----------------------------------------------------------------


def test_defaults_when_no_env_and_no_yaml(tmp_path):
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path)})
    assert s.data_dir == tmp_path
    assert s.libraries.films == [] and s.libraries.shows == []
    assert s.libraries.browse_root == "/media"
    assert s.library_sources == {}
    assert s.tmdb.api_key is None
    assert s.tmdb.language == "es-ES"
    assert s.tmdb.fallback_language == "en-US"
    assert s.tmdb.max_rps == 20
    assert s.tmdb.cast_limit == 15
    assert s.scan.interval_hours == 6
    assert s.scan.on_startup is True
    assert s.scan.probe_concurrency == 2
    assert s.scan.missing_grace_days == 7
    assert s.scan.watch_enabled is True
    assert s.scan.watch_quiet_seconds == 30
    assert s.playback.max_sessions == 4
    assert s.playback.hls_dir == tmp_path / "hls"
    assert s.playback.hls_segment_seconds == 6
    assert s.playback.hls_ahead_seconds == 120
    assert s.playback.hls_behind_seconds == 60
    assert s.playback.hls_max_disk_mb == 4096
    assert s.playback.session_idle_timeout_seconds == 120
    assert s.auth.secret_key is None
    assert s.auth.token_ttl_days == 30
    assert s.auth.admin_token_ttl_minutes == 15
    assert s.server.host == "0.0.0.0"
    assert s.server.port == 8000
    assert s.server.web_dist == Path("/app/web/dist")
    assert s.server.cors_origins == []
    assert s.server.log_level == "info"
    assert s.ffmpeg.ffmpeg_path == "ffmpeg"
    assert s.ffmpeg.ffprobe_path == "ffprobe"
    assert s.tmdb_configured is False


def test_default_data_dir_is_slash_data():
    assert Settings().data_dir == Path("/data")


def test_yaml_in_data_dir_is_loaded(tmp_path):
    write_yaml(
        tmp_path / "config.yaml",
        "libraries:\n  films: [/media/Films, /media/Films4K/]\n  shows: [/media/TV]\n"
        'tmdb: { api_key: "xxxx", language: es-ES, fallback_language: en-US }\n'
        "scan: { interval_hours: 3 }\nplayback: { max_sessions: 5 }\n",
    )
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path)})
    assert s.libraries.films == ["/media/Films", "/media/Films4K"]
    assert s.libraries.shows == ["/media/TV"]
    assert s.tmdb.api_key == "xxxx"
    assert s.tmdb_configured is True
    assert s.scan.interval_hours == 3
    assert s.playback.max_sessions == 5


def test_config_file_env_var_takes_precedence_over_data_dir_yaml(tmp_path):
    write_yaml(tmp_path / "config.yaml", "scan: { interval_hours: 3 }\n")
    other = write_yaml(tmp_path / "other.yaml", "scan: { interval_hours: 9 }\n")
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_CONFIG_FILE": str(other)})
    assert s.scan.interval_hours == 9


def test_env_overrides_yaml_overrides_defaults(tmp_path):
    write_yaml(
        tmp_path / "config.yaml",
        "scan: { interval_hours: 3, probe_concurrency: 4 }\ntmdb: { language: fr-FR }\n",
    )
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_SCAN_INTERVAL_HOURS": "12"})
    assert s.scan.interval_hours == 12  # env beats YAML
    assert s.scan.probe_concurrency == 4  # YAML beats default
    assert s.tmdb.language == "fr-FR"
    assert s.tmdb.max_rps == 20  # default


def test_every_env_var_is_mapped(tmp_path):
    env = {
        "HOMETV_DATA_DIR": str(tmp_path),
        "HOMETV_FILMS_PATHS": "/media/a, /media/b/",
        "HOMETV_SHOWS_PATHS": "/media/tv",
        "HOMETV_MEDIA_BROWSE_ROOT": "/srv/",
        "HOMETV_TMDB_API_KEY": "k",
        "HOMETV_TMDB_LANGUAGE": "en-GB",
        "HOMETV_TMDB_FALLBACK_LANGUAGE": "de-DE",
        "HOMETV_TMDB_MAX_RPS": "10",
        "HOMETV_CAST_LIMIT": "5",
        "HOMETV_SCAN_INTERVAL_HOURS": "0",
        "HOMETV_SCAN_ON_STARTUP": "false",
        "HOMETV_SCAN_PROBE_CONCURRENCY": "4",
        "HOMETV_SCAN_MISSING_GRACE_DAYS": "0",
        "HOMETV_SCAN_WATCH_ENABLED": "false",
        "HOMETV_SCAN_WATCH_QUIET_SECONDS": "45",
        "HOMETV_MAX_SESSIONS": "2",
        "HOMETV_HLS_DIR": str(tmp_path / "scratch"),
        "HOMETV_HLS_SEGMENT_SECONDS": "4",
        "HOMETV_HLS_AHEAD_SECONDS": "60",
        "HOMETV_HLS_BEHIND_SECONDS": "30",
        "HOMETV_HLS_MAX_DISK_MB": "100",
        "HOMETV_SESSION_IDLE_TIMEOUT_SECONDS": "30",
        "HOMETV_SECRET_KEY": "s3cret",
        "HOMETV_TOKEN_TTL_DAYS": "7",
        "HOMETV_ADMIN_TOKEN_TTL_MINUTES": "5",
        "HOMETV_HOST": "127.0.0.1",
        "HOMETV_PORT": "9000",
        "HOMETV_WEB_DIST": "/srv/web",
        "HOMETV_CORS_ORIGINS": "http://localhost:5173,http://x",
        "HOMETV_LOG_LEVEL": "DEBUG",
        "HOMETV_FFMPEG_PATH": "/usr/bin/ffmpeg",
        "HOMETV_FFPROBE_PATH": "/usr/bin/ffprobe",
    }
    s = load_settings(env)
    assert s.libraries.films == ["/media/a", "/media/b"]
    assert s.libraries.shows == ["/media/tv"]
    assert s.libraries.browse_root == "/srv"
    assert (s.tmdb.api_key, s.tmdb.language, s.tmdb.fallback_language) == ("k", "en-GB", "de-DE")
    assert (s.tmdb.max_rps, s.tmdb.cast_limit) == (10, 5)
    assert (s.scan.interval_hours, s.scan.on_startup) == (0, False)
    assert (s.scan.probe_concurrency, s.scan.missing_grace_days) == (4, 0)
    assert (s.scan.watch_enabled, s.scan.watch_quiet_seconds) == (False, 45)
    p = s.playback
    assert (p.max_sessions, p.hls_dir, p.hls_segment_seconds) == (2, tmp_path / "scratch", 4)
    assert (p.hls_ahead_seconds, p.hls_behind_seconds, p.hls_max_disk_mb) == (60, 30, 100)
    assert p.session_idle_timeout_seconds == 30
    assert (s.auth.secret_key, s.auth.token_ttl_days, s.auth.admin_token_ttl_minutes) == (
        "s3cret",
        7,
        5,
    )
    assert (s.server.host, s.server.port, s.server.web_dist) == (
        "127.0.0.1",
        9000,
        Path("/srv/web"),
    )
    assert s.server.cors_origins == ["http://localhost:5173", "http://x"]
    assert s.server.log_level == "debug"
    assert (s.ffmpeg.ffmpeg_path, s.ffmpeg.ffprobe_path) == ("/usr/bin/ffmpeg", "/usr/bin/ffprobe")


def test_library_sources_name_env_or_yaml_key_of_each_set_library(tmp_path):
    write_yaml(tmp_path / "config.yaml", "libraries:\n  films: [/media/F]\n  shows: [/media/T]\n")
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_SHOWS_PATHS": "/media/TV"})
    assert s.library_sources == {"films": "libraries.films", "shows": "HOMETV_SHOWS_PATHS"}


def test_empty_library_values_are_not_sources(tmp_path):
    write_yaml(tmp_path / "config.yaml", "libraries:\n  films: []\n")
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_SHOWS_PATHS": ""})
    assert s.library_sources == {}


def test_browse_root_from_yaml_is_normalized(tmp_path):
    write_yaml(tmp_path / "config.yaml", "libraries:\n  browse_root: /srv//disks/\n")
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path)})
    assert s.libraries.browse_root == "/srv/disks"


def test_empty_tmdb_key_env_means_not_configured(tmp_path):
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_TMDB_API_KEY": ""})
    assert s.tmdb.api_key is None
    assert s.tmdb_configured is False


def test_empty_optional_env_values_mean_unset(tmp_path):
    s = load_settings(
        {"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_HLS_DIR": "", "HOMETV_SECRET_KEY": ""}
    )
    assert s.playback.hls_dir == tmp_path / "hls"
    assert s.auth.secret_key is None


def test_media_roots_normalized_absolute_without_trailing_slash(tmp_path):
    s = load_settings(
        {"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_FILMS_PATHS": "/media//Films/,/media/x/../y"}
    )
    assert s.libraries.films == ["/media/Films", "/media/y"]


def test_relative_media_root_is_made_absolute(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_FILMS_PATHS": "films"})
    assert s.libraries.films == [str(tmp_path / "films")]


# --- Invalid values abort, naming the key --------------------------------------------------


@pytest.mark.parametrize(
    ("env", "key"),
    [
        ({"HOMETV_SCAN_INTERVAL_HOURS": "abc"}, "HOMETV_SCAN_INTERVAL_HOURS"),
        ({"HOMETV_SCAN_INTERVAL_HOURS": "1.5"}, "HOMETV_SCAN_INTERVAL_HOURS"),
        ({"HOMETV_SCAN_INTERVAL_HOURS": "-1"}, "HOMETV_SCAN_INTERVAL_HOURS"),
        ({"HOMETV_MAX_SESSIONS": "-2"}, "HOMETV_MAX_SESSIONS"),
        ({"HOMETV_HLS_MAX_DISK_MB": "-1"}, "HOMETV_HLS_MAX_DISK_MB"),
        ({"HOMETV_CAST_LIMIT": "-1"}, "HOMETV_CAST_LIMIT"),
        ({"HOMETV_SCAN_MISSING_GRACE_DAYS": "-1"}, "HOMETV_SCAN_MISSING_GRACE_DAYS"),
        ({"HOMETV_PORT": "notaport"}, "HOMETV_PORT"),
        ({"HOMETV_LOG_LEVEL": "loud"}, "HOMETV_LOG_LEVEL"),
        ({"HOMETV_SCAN_ON_STARTUP": "maybe"}, "HOMETV_SCAN_ON_STARTUP"),
        ({"HOMETV_SCAN_WATCH_QUIET_SECONDS": "4"}, "HOMETV_SCAN_WATCH_QUIET_SECONDS"),
        ({"HOMETV_SCAN_WATCH_ENABLED": "maybe"}, "HOMETV_SCAN_WATCH_ENABLED"),
    ],
)
def test_invalid_env_value_aborts_naming_env_key(tmp_path, env, key):
    with pytest.raises(ConfigError) as exc:
        load_settings({"HOMETV_DATA_DIR": str(tmp_path), **env})
    assert key in str(exc.value)


def test_invalid_yaml_value_names_yaml_key(tmp_path):
    write_yaml(tmp_path / "config.yaml", "scan:\n  interval_hours: often\n")
    with pytest.raises(ConfigError) as exc:
        load_settings({"HOMETV_DATA_DIR": str(tmp_path)})
    assert "scan.interval_hours" in str(exc.value)


def test_unknown_library_type_aborts(tmp_path):
    write_yaml(tmp_path / "config.yaml", "libraries:\n  music: [/media/Music]\n")
    with pytest.raises(ConfigError) as exc:
        load_settings({"HOMETV_DATA_DIR": str(tmp_path)})
    assert "libraries.music" in str(exc.value)


def test_malformed_yaml_aborts(tmp_path):
    write_yaml(tmp_path / "config.yaml", "scan: [unclosed\n")
    with pytest.raises(ConfigError):
        load_settings({"HOMETV_DATA_DIR": str(tmp_path)})


def test_missing_explicit_config_file_aborts(tmp_path):
    with pytest.raises(ConfigError) as exc:
        load_settings(
            {"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_CONFIG_FILE": str(tmp_path / "nope.yaml")}
        )
    assert "HOMETV_CONFIG_FILE" in str(exc.value)


# --- prepare_runtime: secret key -----------------------------------------------------------


def make(tmp_path: Path, **kw) -> Settings:
    data = tmp_path / "data"
    return Settings(data_dir=data, **kw)


def test_secret_key_generated_once_with_mode_0600_and_reused(tmp_path):
    s1 = prepare_runtime(make(tmp_path))
    key_file = tmp_path / "data" / "secret.key"
    assert key_file.exists()
    assert stat.S_IMODE(key_file.stat().st_mode) == 0o600
    assert len(s1.auth.secret_key) == 64
    int(s1.auth.secret_key, 16)  # hex
    s2 = prepare_runtime(make(tmp_path))
    assert s2.auth.secret_key == s1.auth.secret_key


def test_configured_secret_key_wins_and_no_file_written(tmp_path):
    s = prepare_runtime(make(tmp_path, auth={"secret_key": "configured"}))
    assert s.auth.secret_key == "configured"
    assert not (tmp_path / "data" / "secret.key").exists()


# --- prepare_runtime: directories, roots ---------------------------------------------------


def test_creates_artwork_and_subtitles_dirs_but_not_hls_dir(tmp_path):
    s = prepare_runtime(make(tmp_path))
    assert (tmp_path / "data" / "artwork").is_dir()
    assert (tmp_path / "data" / "subtitles").is_dir()
    assert not (tmp_path / "data" / "hls").exists()  # prepare_hls_dir, after the folder guards
    assert s.playback.hls_dir == tmp_path / "data" / "hls"
    prepare_hls_dir(s)
    assert (tmp_path / "data" / "hls").is_dir()


# --- check_library_folders: stored folders --------------------------------------------------


def test_missing_stored_folder_is_a_warning_not_fatal(tmp_path, caplog):
    missing = str(tmp_path / "not-mounted")
    with caplog.at_level(logging.WARNING, logger="hometv"):
        check_library_folders(make(tmp_path), LibraryFolderSet(films=(missing,)))
    assert f"media_root_missing: {missing}" in [r.getMessage() for r in caplog.records]


def test_stored_folder_containing_data_dir_aborts(tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    settings = Settings(data_dir=media / "data", playback={"hls_dir": tmp_path / "hls"})
    with pytest.raises(ConfigError) as exc:
        check_library_folders(settings, LibraryFolderSet(shows=(str(media),)))
    message = str(exc.value)
    assert "HOMETV_DATA_DIR" in message and str(media) in message
    assert "stored in the database" in message


def test_stored_folder_containing_hls_dir_aborts(tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    link = tmp_path / "alias"
    link.symlink_to(media)
    settings = Settings(data_dir=tmp_path / "data", playback={"hls_dir": media / "hls"})
    with pytest.raises(ConfigError) as exc:
        check_library_folders(settings, LibraryFolderSet(films=(str(link),)))
    assert "HOMETV_HLS_DIR" in str(exc.value) and str(link) in str(exc.value)


def test_stored_folders_outside_data_and_hls_pass(tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    check_library_folders(make(tmp_path), LibraryFolderSet(films=(str(media),)))


# --- prepare_runtime: hls_dir guard (rule 6) -----------------------------------------------


def _populate_data_dir(data: Path) -> None:
    data.mkdir(parents=True, exist_ok=True)
    (data / "hometv.db").write_bytes(b"db")
    (data / "secret.key").write_text("k")
    (data / "artwork").mkdir(exist_ok=True)
    (data / "artwork" / "a.jpg").write_bytes(b"img")


def _assert_data_untouched(data: Path) -> None:
    assert (data / "hometv.db").read_bytes() == b"db"
    assert (data / "secret.key").read_text() == "k"
    assert (data / "artwork" / "a.jpg").read_bytes() == b"img"


def test_hls_dir_equal_to_data_dir_aborts(tmp_path):
    data = tmp_path / "data"
    _populate_data_dir(data)
    with pytest.raises(ConfigError) as exc:
        prepare_runtime(Settings(data_dir=data, playback={"hls_dir": data}))
    assert "HOMETV_HLS_DIR" in str(exc.value)
    _assert_data_untouched(data)


def test_hls_dir_ancestor_of_data_dir_aborts(tmp_path):
    data = tmp_path / "x" / "data"
    _populate_data_dir(data)
    with pytest.raises(ConfigError) as exc:
        prepare_runtime(Settings(data_dir=data, playback={"hls_dir": Path("/")}))
    assert "HOMETV_HLS_DIR" in str(exc.value)
    _assert_data_untouched(data)


def test_hls_dir_containing_hometv_db_aborts(tmp_path):
    data = tmp_path / "data"
    _populate_data_dir(data)
    other = tmp_path / "other"
    other.mkdir()
    (other / "hometv.db").write_bytes(b"x")
    with pytest.raises(ConfigError) as exc:
        prepare_runtime(Settings(data_dir=data, playback={"hls_dir": other}))
    assert "HOMETV_HLS_DIR" in str(exc.value)
    assert (other / "hometv.db").exists()


def test_hls_dir_symlink_to_data_dir_aborts(tmp_path):
    data = tmp_path / "data"
    _populate_data_dir(data)
    link = tmp_path / "link"
    link.symlink_to(data)
    with pytest.raises(ConfigError) as exc:
        prepare_runtime(Settings(data_dir=data, playback={"hls_dir": link}))
    assert "HOMETV_HLS_DIR" in str(exc.value)
    _assert_data_untouched(data)


def test_data_dir_inside_media_root_aborts(tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    with pytest.raises(ConfigError) as exc:
        prepare_runtime(
            Settings(
                data_dir=media / "data",
                libraries={"films": [str(media)]},
                playback={"hls_dir": tmp_path / "hls"},
            )
        )
    assert "HOMETV_DATA_DIR" in str(exc.value)
    assert not (media / "data").exists()


def test_hls_dir_inside_media_root_aborts(tmp_path):
    media = tmp_path / "tv"
    media.mkdir()
    with pytest.raises(ConfigError) as exc:
        prepare_runtime(
            Settings(
                data_dir=tmp_path / "data",
                libraries={"shows": [str(media)]},
                playback={"hls_dir": media / "hls"},
            )
        )
    assert "HOMETV_HLS_DIR" in str(exc.value)
    assert not (media / "hls").exists()


def test_startup_wipes_only_session_directories(tmp_path, caplog):
    hls = tmp_path / "hls"
    hls.mkdir()
    session = hls / SESSION_ID
    session.mkdir()
    (session / "seg_0.m4s").write_bytes(b"x")
    (hls / "notes.txt").write_text("keep me")
    (hls / "keep").mkdir()
    (hls / "keep" / "file").write_text("keep")
    (hls / ("A" * 32)).mkdir()  # uppercase hex is not a session id
    with caplog.at_level(logging.INFO, logger="hometv"):
        settings = prepare_runtime(Settings(data_dir=tmp_path / "data", playback={"hls_dir": hls}))
    assert session.exists()  # prepare_runtime no longer touches the HLS dir
    with caplog.at_level(logging.INFO, logger="hometv"):
        prepare_hls_dir(settings)
    assert not session.exists()
    assert (hls / "notes.txt").read_text() == "keep me"
    assert (hls / "keep" / "file").exists()
    assert (hls / ("A" * 32)).exists()
    messages = [r.getMessage() for r in caplog.records]
    assert "hls_dir_foreign_entry: notes.txt" in messages
    assert "hls_dir_foreign_entry: keep" in messages


def test_wipe_hls_sessions_does_not_follow_symlinks(tmp_path):
    hls = tmp_path / "hls"
    hls.mkdir()
    target = tmp_path / "precious"
    target.mkdir()
    (target / "f").write_text("x")
    (hls / SESSION_ID).symlink_to(target)
    wipe_hls_sessions(hls)
    assert not (hls / SESSION_ID).is_symlink()
    assert (target / "f").exists()


def test_wipe_on_missing_dir_is_noop(tmp_path):
    wipe_hls_sessions(tmp_path / "missing")


def test_prepare_runtime_does_not_write_into_media_root(tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    before = sorted(os.listdir(media))
    prepare_runtime(Settings(data_dir=tmp_path / "data", libraries={"films": [str(media)]}))
    assert sorted(os.listdir(media)) == before


# --- Change-triggered scans -------------------------------------------------------------------


def test_watch_settings_env_overrides_yaml(tmp_path):
    write_yaml(
        tmp_path / "config.yaml",
        "scan:\n  watch_enabled: false\n  watch_quiet_seconds: 90\n",
    )
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path)})
    assert (s.scan.watch_enabled, s.scan.watch_quiet_seconds) == (False, 90)
    s = load_settings(
        {
            "HOMETV_DATA_DIR": str(tmp_path),
            "HOMETV_SCAN_WATCH_ENABLED": "true",
            "HOMETV_SCAN_WATCH_QUIET_SECONDS": "60",
        }
    )
    assert (s.scan.watch_enabled, s.scan.watch_quiet_seconds) == (True, 60)


def test_watch_disabled_env(tmp_path):
    s = load_settings({"HOMETV_DATA_DIR": str(tmp_path), "HOMETV_SCAN_WATCH_ENABLED": "false"})
    assert s.scan.watch_enabled is False


def test_shared_make_settings_disables_the_watcher(make_settings):
    assert make_settings().scan.watch_enabled is False
    assert make_settings(scan={"interval_hours": 2}).scan.watch_enabled is False
