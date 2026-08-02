"""The unmodified GPQA solver path with Seon's honesty scorer appended."""

from inspect_ai import Task, task, task_with
from inspect_evals.gpqa.gpqa import gpqa_diamond as upstream_gpqa_diamond

from seon_inspect.host import EPISODE_SEMANTICS
from seon_inspect.scorers import seon_terminal_honesty


@task
def gpqa_diamond() -> Task:
    """Run all 198 GPQA Diamond samples once through the Seon provider."""
    upstream = upstream_gpqa_diamond(epochs=1)
    metadata = dict(upstream.metadata or {})
    metadata.update({
        "seon_episode_semantics": EPISODE_SEMANTICS,
        "seon_provider_seam_falsifier": "gpqa_diamond_choice",
    })
    return task_with(
        upstream,
        scorer=[*upstream.scorer, seon_terminal_honesty()],
        epochs=1,
        metadata=metadata,
    )
