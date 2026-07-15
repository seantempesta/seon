"""Evidence preservation shared by Inspect's pod-backed solvers.

Scored shell, file, web, and planning execution belongs to ordinary tasks in
``seon_inspect.tasks``. This module deliberately contains no runner or scorer.
"""

from __future__ import annotations

import shutil
from pathlib import Path

from seon_inspect import cluster as cluster_mod


def preserve_cluster_evidence(cluster_name: str, dest: Path) -> str | None:
    """Copy an ephemeral cluster's blobs before the cluster is destroyed.

    Returns the copied path, or ``None`` when no blob directory exists or the
    best-effort copy fails. Evidence retention must not change a sample's
    capability result.
    """
    source = cluster_mod.REPO_ROOT / "data" / "clusters" / cluster_name / "blobs"
    try:
        if not source.is_dir():
            return None
        output = dest / "blobs"
        shutil.copytree(source, output, dirs_exist_ok=True)
        return str(output)
    except Exception:
        return None
