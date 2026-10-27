"""``changes.mounts`` (prd.md FR-1): mountinfo parsing, mount lookup, watchability."""

import pytest

from hometv.changes.mounts import Mount, is_watchable, mount_for, parse_mountinfo

SAMPLE = "\n".join(
    [
        # no optional fields
        "22 1 8:1 / / rw,relatime - ext4 /dev/sda1 rw",
        # one optional field
        "36 22 8:17 / /media rw,noatime shared:1 - fuseblk /dev/sdb1 rw,user_id=0",
        # two optional fields, escaped space in the mount point
        r"37 36 0:50 / /media/My\040Disk rw master:2 propagate_from:1 - 9p drvfs rw",
        # three optional fields, tab / newline / backslash escapes
        r"38 22 0:51 / /mnt/a\011b\012c\134d rw shared:3 master:4 unbindable - nfs4 srv:/x rw",
        "39 22 0:52 / /run/desktop/mnt/host/c rw - fuse.grpcfuse grpcfuse rw",
        "40 22 0:53 / /host rw - fakeowner fakeowner rw",
        "41 22 0:54 / /virt rw - virtiofs share rw",
        "42 22 0:55 / /smb rw - cifs //nas/share rw",
        "malformed line without separator",
        "43 22 0:56 / - ",  # malformed: too few fields
        "",
    ]
)


def test_parse_mountinfo_fields_and_escapes():
    mounts = parse_mountinfo(SAMPLE)
    assert mounts == [
        Mount("/", "ext4"),
        Mount("/media", "fuseblk"),
        Mount("/media/My Disk", "9p"),
        Mount("/mnt/a\tb\nc\\d", "nfs4"),
        Mount("/run/desktop/mnt/host/c", "fuse.grpcfuse"),
        Mount("/host", "fakeowner"),
        Mount("/virt", "virtiofs"),
        Mount("/smb", "cifs"),
    ]


@pytest.mark.parametrize(
    ("path", "expected"),
    [
        ("/media", "/media"),
        ("/media/MKV", "/media"),
        ("/mediaX", "/"),
        ("/media/My Disk/películas", "/media/My Disk"),
        ("/srv", "/"),
    ],
)
def test_mount_for_longest_component_prefix(path, expected):
    found = mount_for(path, parse_mountinfo(SAMPLE))
    assert found is not None
    assert found.mount_point == expected


def test_mount_for_over_mount_last_line_wins():
    mounts = [Mount("/", "ext4"), Mount("/media", "ext4"), Mount("/media", "9p")]
    assert mount_for("/media/MKV", mounts) == Mount("/media", "9p")


def test_mount_for_none_without_match():
    assert mount_for("/media", [Mount("/srv", "ext4")]) is None


@pytest.mark.parametrize(
    ("fs_type", "watchable"),
    [
        ("ext4", True),
        ("xfs", True),
        ("btrfs", True),
        ("ntfs3", True),
        ("fuseblk", True),
        ("exfat", True),
        ("vfat", True),
        ("overlay", True),
        ("tmpfs", True),
        ("9p", False),
        ("fuse.grpcfuse", False),
        ("grpcfuse", False),
        ("fakeowner", False),
        ("virtiofs", False),
        ("nfs4", False),
        ("nfs", False),
        ("cifs", False),
        ("smb3", False),
        ("drvfs", False),
        ("fuse.sshfs", False),
    ],
)
def test_is_watchable(fs_type, watchable):
    assert is_watchable(fs_type) is watchable


def test_read_mountinfo_absent_file(tmp_path):
    from hometv.changes.mounts import read_mountinfo

    assert read_mountinfo(tmp_path / "nope") is None
    path = tmp_path / "mountinfo"
    path.write_text(SAMPLE)
    assert read_mountinfo(path) == SAMPLE
