"""Experimental native Inspect rows for namespace/function reachability.

These four rows are focused development candidates, not members of the frozen
P0 suite.  They have no mock-model arm: live execution always goes through the
ordinary static-pod solver and source-admission/final-log path.
"""

from __future__ import annotations

from inspect_ai import Task, task
from inspect_ai.dataset import MemoryDataset, Sample

from seon_inspect import source_admission
from seon_inspect.reachability import ROWS, reachability_scorer
from seon_inspect.solver import seon_pod_solver


CONTRACTS = {
    "root_orchestration": (
        "You are the root orchestrator. Spawn exactly one new idle child whose "
        "purpose is `audit invoices reachability`. Do not choose an id and do "
        "not send the child a task. End that turn after the spawn so you can "
        "read the real returned id. In a later turn, run a scalar Datalog query "
        "that finds the child whose parent is root and whose purpose is exactly "
        "`audit invoices reachability`. Report the queried child id to your "
        "human with `message/user`, then `complete` with the same id. Never "
        "invent the id and do not inspect source files."
    ),
    "namespace_discovery": (
        "Without reading files or using filesystem or graph search, use the "
        "callable-function discovery operation available in your ordinary home "
        "tool surface to list the positively callable functions in "
        "`seon.agent.web`. In that same turn move into `seon.agent.web`, then "
        "end the turn. On the next turn, confirm from the full current-namespace "
        "context that those functions are present and call `grants` unqualified. "
        "Return to your home namespace, report the discovered function names to "
        "your human, and `complete` with the same names."
    ),
    "skill_lifecycle": (
        "Without reading files or searching the program graph, use the skill "
        "operations available in your ordinary home tool surface. List the "
        "available skills, then load the `repl` skill and end that turn. On the "
        "next turn, confirm that the REPL skill body is present in your dynamic "
        "context, unload it, and end the turn. On the following turn, confirm "
        "that the body is absent. Report the completed load-and-unload cycle to "
        "your human and `complete`. Do not merely claim that the context changed."
    ),
    "acme_product_tools": (
        "Use ACME's ordinary downstream product tools visible in your home "
        "namespace. Call the product branding operation to obtain ACME's "
        "tagline and call the product widget operation to set the location to "
        "`Boston`. End that turn so you can read both real returned values. On "
        "the next turn report both exact values to your human and `complete` "
        "with both. Do not inspect files, search the program graph, or guess "
        "either return value."
    ),
}


def _identity(row: str) -> dict[str, str]:
    return {
        "name": f"namespace_reachability:{row}",
        "module": "seon_inspect.tasks.namespace_reachability",
        "attribute": "namespace_reachability",
        "kind": "seon-native-experimental",
    }


@task
def namespace_reachability(
    row: str = "namespace_discovery",
    cluster_url: str | None = None,
    timeout_s: int | None = None,
    _admission: dict | None = None,
) -> Task:
    """Construct one live-only experimental reachability row."""
    if row not in ROWS:
        raise ValueError(f"unknown reachability row {row!r}; expected one of {ROWS}")
    if not cluster_url:
        raise ValueError("cluster_url is required for a live reachability row")
    admission = _admission or source_admission.verify_sources(_identity(row))
    sample = Sample(
        id=f"namespace-reachability-{row}",
        input=CONTRACTS[row],
        target="retained reachability trajectory",
        metadata={
            "seon_reachability_row": row,
            "seon_source_admission": admission,
        },
    )
    return Task(
        dataset=MemoryDataset([sample]),
        solver=seon_pod_solver(
            cluster_url=cluster_url,
            timeout_s=timeout_s,
            agent_id="root" if row == "root_orchestration" else None,
        ),
        scorer=reachability_scorer(),
        metadata={
            "seon_reachability_candidate": True,
            "seon_reachability_row": row,
            "seon_source_admission": admission,
        },
    )
