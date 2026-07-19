"""Capability-milestone rows as an inspect-ai task (the minimal-buildup drives).

The `namespaces` and `db` milestones from the context-rebuild build-up matrix
(`evals/runs/2026-07-10-minimal-buildup/`), retired here from the bespoke
`tools/min-drive.sh` shell lineage into the standard bench. Fixed regression
contracts and seeded generated variants share the capability oracle in
`seon_inspect.milestone`. The `plan`
milestone is `seon_inspect.planning` (already first-class) and the code-fix
`repl` milestone (poker / two-bucket) is covered by the existing `shell_use` /
`file_edit` tool rows — neither is re-created here.

Two solver modes (arm switch, like `ladder_lift`):
  - `endpoint="mock:<good|bad>"` — OFFLINE proof: the sample's frozen golden
    `(reply, eval_rows)` fixtures drive the scorer, so the harness's
    DISCRIMINATION is provable with no pod and no LLM (`mock:good` scores
    CORRECT, `mock:bad` INCORRECT).
  - `endpoint="pod"` — LIVE: `seon_inspect.milestone.pod_milestone_driver`
    POSTs to an explicitly supplied static cluster URL, consumes the response's
    database-derived eval rows, and scores. The model column
    (deepseek vs spark) is
    RUNTIME-DERIVED from the pod's `model_config` — no arm plumbing needed
    (scorecard.model_provenance_from_run).

RUN (offline, both arms — proves the oracle discriminates):
    .venv/bin/inspect eval src/seon_inspect/tasks/milestone_lift.py@milestone_lift \\
      -T milestone=namespaces -T endpoint=mock:good \\
      --model mockllm/model --display plain
"""

from __future__ import annotations

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import pass_at
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect import source_admission
from seon_inspect.milestone import (
    DB_MEMORY_CONTRACT, NS_MOVEMENT_CONTRACT, fabrication_metric,
    milestone_scorer)

# ---------------------------------------------------------------------------
# The rows — contract text + a frozen golden pair for the offline proof. The
# golden eval rows are the MINIMAL sequence that satisfies (good) / misses
# (bad) each check, so the mock arms exercise the real oracle end-to-end.
# ---------------------------------------------------------------------------

ROWS: dict[str, dict] = {
    "namespaces": {
        "contract": NS_MOVEMENT_CONTRACT,
        "good": {
            "reply": ("Done. Total meters 42.5, total feet 139.44."),
            "eval_rows": [
                {"source": "(in-ns 'my.units)", "ok": True},
                {"source": "(schema/register! :my.units/name "
                           "[:string {:seon.db/identity true}])", "ok": True},
                {"source": "(in-ns 'my.convert)", "ok": True},
                {"source": "(defn to-feet [m] (* m 3.28))", "ok": True},
                {"source": "(require '[clojure.string :as str])", "ok": True},
                {"source": "(defn to-feet [m] (* m 3.28084))", "ok": True},
            ],
        },
        # bad = never moved into my.convert, and a PARALLEL to-feet-v2 fork
        # instead of an in-place redefine → movement + no_parallel_fork miss.
        "bad": {
            "reply": "Total meters 42.5, total feet 139.44.",
            "eval_rows": [
                {"source": "(in-ns 'my.units)", "ok": True},
                {"source": "(schema/register! :my.units/name "
                           "[:string {:seon.db/identity true}])", "ok": True},
                {"source": "(defn to-feet-v2 [m] (* m 3.28084))", "ok": True},
            ],
        },
    },
    "db": {
        "contract": DB_MEMORY_CONTRACT,
        "good": {
            "reply": "The total weight of caches over 10 kg is 59.5 kg.",
            "eval_rows": [
                {"source": "(schema/register! :my.cache/name "
                           "[:string {:seon.db/identity true}])",
                 "ok": True},
                {"source": "(schema/register! :my.cache/weight-kg :int)",
                 "ok": True},
                {"source": "(db/transact! [{:my.cache/name \"KESTREL\" "
                           ":my.cache/weight-kg 42.5} "
                           "{:my.cache/name \"MARMOT\" "
                           ":my.cache/weight-kg 17.0} "
                           "{:my.cache/name \"TERN\" "
                           ":my.cache/weight-kg 8.25} "
                           "{:my.cache/name \"PLOVER\" "
                           ":my.cache/weight-kg 3.75}])", "ok": True},
                {"source": "(db/query '[:find (sum ?w) . :where "
                           "[?e :my.cache/weight-kg ?w] [(> ?w 10)]])",
                 "ok": True},
                {"source": "(message/user \"The recalled total is 59.5\")",
                 "ok": True},
                {"source": "(complete \"The recalled total is 59.5\")",
                 "ok": True},
            ],
        },
        # bad = recall query ran BEFORE the transact (re-derived, not recalled)
        # and the answer never reaches the reply.
        "bad": {
            "reply": "I have designed a schema for the caches.",
            "eval_rows": [
                {"source": "(db/query '[:find (sum ?w) . :where "
                           "[?e :my.cache/weight-kg ?w]])", "ok": True},
                {"source": "(db/transact! [{:my.cache/name \"KESTREL\" "
                           ":my.cache/weight-kg 42.5}])", "ok": True},
            ],
        },
    },
}


WORKFLOW_ROWS = {"db": "database_workflow",
                 "namespaces": "namespace_workflow"}


def task_identity(milestone: str) -> dict[str, str]:
    """Stable admitted-source identity for one native milestone task."""
    return {
        "name": WORKFLOW_ROWS[milestone],
        "module": "seon_inspect.tasks.milestone_lift",
        "attribute": "milestone_lift",
        "kind": "seon-native",
    }


def _sample(milestone: str, admission: dict | None = None) -> Sample:
    row = ROWS[milestone]
    metadata = {"milestone": milestone}
    if admission is not None:
        metadata["seon_source_admission"] = admission
    return Sample(id=milestone, input=row["contract"], target="correct",
                  metadata=metadata)


def _generated_samples(milestone: str, seed: int | str,
                       positions: list[int],
                       admission: dict | None = None) -> list[Sample]:
    from seon_inspect.generators import generate_rows

    if not positions or any(isinstance(p, bool) or not isinstance(p, int)
                            or p < 0 for p in positions):
        raise ValueError("positions must be nonempty non-negative integers")
    positions = sorted(set(positions))
    rows = generate_rows(WORKFLOW_ROWS[milestone], seed, positions[-1] + 1)
    samples = []
    for position in positions:
        row = rows[position]
        metadata = dict(row["metadata"])
        if admission is not None:
            metadata["seon_source_admission"] = admission
        samples.append(Sample(id=row["id"], input=row["input"],
                              target=row["target"], metadata=metadata))
    return samples


@solver
def milestone_solver(milestone: str, endpoint: str,
                     cluster_url: str | None = None,
                     admission: dict | None = None):
    """Drive the pod (endpoint="pod") or replay a frozen golden pair (mock:*)."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        row = ROWS[milestone]
        if endpoint.startswith("mock:"):
            golden = row[endpoint.split(":", 1)[1]]  # "good" | "bad"
            state.output.completion = golden["reply"]
            state.metadata["eval_rows"] = golden["eval_rows"]
            return state
        if endpoint == "pod":
            import anyio

            from seon_inspect.milestone import pod_milestone_driver
            from seon_inspect.solver import (
                _record_result,
                require_scorable_pod_state,
            )
            res = await anyio.to_thread.run_sync(
                lambda: pod_milestone_driver(
                    state.input_text, milestone,
                    cluster_url=cluster_url))
            require_scorable_pod_state(_record_result(state, res["run"]))
            state.metadata["eval_rows"] = res["eval_rows"]
            state.metadata["milestone_run"] = {
                "cluster": res.get("cluster"), "agent_id": res.get("agent_id"),
                "fabrication": res["fabrication"]}
            if admission is not None:
                end = source_admission.verify_sources(task_identity(milestone))
                if end != admission:
                    raise source_admission.SourceAdmissionError(
                        "milestone source changed during the sample")
            return state
        raise ValueError(f"unknown endpoint {endpoint!r} "
                         "(mock:good | mock:bad | pod)")

    return solve


@task
def milestone_lift(milestone: str = "namespaces",
                   endpoint: str = "mock:good",
                   epochs: int = 4,
                   seed: int | str | None = None,
                   positions: list[int] | None = None,
                   cluster_url: str | None = None,
                   _admission: dict | None = None,
                   ):
    """Capability milestone variants x pass^k (namespaces | db).

    `seed=None` keeps the fixed regression contract. A seed selects generated
    workflow variants at the requested positions. Live execution is serial on
    one explicitly provisioned target; this task owns no lifecycle operation.
    """
    if milestone not in ROWS:
        raise ValueError(f"unknown milestone {milestone!r} "
                         f"(known: {sorted(ROWS)})")
    if endpoint == "pod" and not cluster_url:
        raise ValueError(
            "endpoint='pod' requires an explicit cluster_url from the owned "
            "static target")
    if seed is not None and endpoint.startswith("mock:"):
        raise ValueError("generated workflow variants require endpoint='pod'")
    admission = (_admission or source_admission.verify_sources(
        task_identity(milestone))) if endpoint == "pod" else None
    samples = ([_sample(milestone, admission)] if seed is None else
               _generated_samples(
                   milestone, seed, positions or [0], admission))
    return Task(
        dataset=MemoryDataset(samples),
        solver=milestone_solver(milestone, endpoint, cluster_url, admission),
        scorer=[milestone_scorer(), fabrication_metric()],
        epochs=Epochs(epochs, ["mean", pass_at(epochs)]),
        metadata=({"seon_source_admission": admission}
                  if admission is not None else {}),
    )
