"""Mount watchability (prd.md FR-1, pure except ``read_mountinfo``).

Inotify only reports changes made through the local kernel: network and VM-bridge file systems
(Docker Desktop drives, NFS/SMB shares, FUSE bridges) never deliver remote changes, so a root
on one of them is ``unavailable``. The fs type of a root comes from ``/proc/self/mountinfo``;
callers pass ``os.path.realpath(root)`` so a symlinked root resolves to its real mount.
"""

import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from hometv.changes.scope import within

MOUNTINFO_PATH = Path("/proc/self/mountinfo")

UNWATCHABLE_FS_TYPES: frozenset[str] = frozenset(
    {
        "nfs",
        "nfs4",
        "cifs",
        "smb3",
        "smbfs",
        "9p",
        "drvfs",
        "virtiofs",
        "fakeowner",
        "grpcfuse",
        "fuse.grpcfuse",
        "fuse.osxfs",
        "fuse.sshfs",
        "fuse.rclone",
        "fuse.s3fs",
        "fuse.glusterfs",
        "glusterfs",
        "ceph",
        "fuse.ceph",
        "afs",
        "davfs",
        "fuse.davfs2",
    }
)

_OCTAL_ESCAPE = re.compile(r"\\([0-7]{3})")


@dataclass(frozen=True)
class Mount:
    mount_point: str  # unescaped (\040 -> " ", \011, \012, \134)
    fs_type: str  # e.g. "ext4", "fuseblk", "9p", "fuse.grpcfuse"


def _unescape(value: str) -> str:
    return _OCTAL_ESCAPE.sub(lambda match: chr(int(match.group(1), 8)), value)


def parse_mountinfo(text: str) -> list[Mount]:
    """Field 5 is the mount point; the fs type is the first field after the ``-`` separator
    (the optional fields before it vary in count). Malformed lines are skipped."""
    mounts: list[Mount] = []
    for line in text.splitlines():
        fields = line.split()
        if "-" not in fields:
            continue
        sep = fields.index("-")
        if sep < 6 or sep + 1 >= len(fields):
            continue
        mounts.append(Mount(_unescape(fields[4]), fields[sep + 1]))
    return mounts


def mount_for(path: str, mounts: Sequence[Mount]) -> Mount | None:
    """The longest mount point equal to ``path`` or a path-component prefix of it; on equal
    mount points the last line wins (over-mount)."""
    best: Mount | None = None
    for mount in mounts:
        point = mount.mount_point
        if within(path, point) and (best is None or len(point) >= len(best.mount_point)):
            best = mount
    return best


def is_watchable(fs_type: str) -> bool:
    """A denylist: unknown (local) types are watchable."""
    return fs_type not in UNWATCHABLE_FS_TYPES


def read_mountinfo(path: Path = MOUNTINFO_PATH) -> str | None:
    """The mountinfo text, or None when the file is absent/unreadable (not Linux)."""
    try:
        return path.read_text(encoding="utf-8", errors="surrogateescape")
    except OSError:
        return None
