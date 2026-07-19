"""Seon-pod-as-inspect-solver — the canonical `POST /agents/run` bridge.

Mechanism (Option B, unchanged in shape since the Phase-0 spike): inspect
supplies dataset + host-side scorer; the Seon pod agent does the work, driven
through the pod door `POST /agents/run` in `seon.web.serve` — start-or-reuse
an agent IN THE POD'S OWN CLUSTER, deliver the input via the real wake path,
run the agent's OWN FSM to idle. Inspect never caps or manages turns.
Deliberately NOT the model-proxy / sandbox_agent_bridge path (that routes the
agent's LLM calls through inspect and replaces Seon's loop — rejected; see the
spike doc §1). Isolation is a whole CLUSTER (one pod per cluster); per-sample
isolation = one ephemeral cluster per sample (`seon_cluster_solver`), while
`seon_pod_solver` drives a LONG-LIVED cluster's pod at a static URL (acme).
Both capability solvers reject infrastructure terminal states after recording
their evidence and before any task parser or scorer runs. The deliberately raw
`seon_diagnostic_pod_solver` exists only for diagnostics such as timeout
honesty, where the infrastructure close itself is the observation.

The pod records honestly under the clock: the door returns `timed_out` +
`closed_reason "timeout"` on a clock cut-off (never a stale :completed/greeting
reply), and `timeout_honesty()` is the scorer that asserts exactly that. A
refusal (unknown `agent_id`, failed mint) is HTTP 422 `{"error": …}` — raised
as `AgentRunRefused`, a distinct class: a harness/wiring defect, never a model
score.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request

from inspect_ai.scorer import Score, Scorer, Target, accuracy, scorer
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect import cluster as cluster_mod
from seon_inspect import config


class AgentRunRefused(Exception):
    """The pod REFUSED the run (HTTP 422: unknown agent_id / failed mint).

    Distinct from a timeout or transport error: the sample never ran, so the
    caller must treat it as harness wiring to fix, not a model result."""


class PodRunInfrastructureError(RuntimeError):
    """A pod run ended before capability scoring was meaningful."""


def pod_run(prompt: str, timeout_ms: int | None = None, url: str | None = None,
            agent_id: str | None = None) -> dict:
    """One request/response call to a cluster pod's /agents/run door.

    POST {input, timeout_ms[, agent_id]} → the pod starts (or, with
    `agent_id`, REUSES — it survives pod restarts, the cluster store is
    durable) an agent in its own cluster, injects the input as a real user
    message, awaits idle, returns the reply + honest metadata (agent_id /
    turns / evals / closed_reason / timed_out), scoped to THIS request's
    window. SERIAL-ONLY per pod (config.POD_MAX_SAMPLES = 1 by construction:
    one cluster = one sample's isolation unit); parallelism = more clusters,
    one URL each. HTTP 422 → AgentRunRefused."""
    payload: dict = {"input": prompt}
    if timeout_ms is not None:
        payload["timeout_ms"] = timeout_ms
    if agent_id is not None:
        payload["agent_id"] = agent_id
    req = urllib.request.Request(
        config.cluster_url(url), data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        # An explicit pod budget gets a bounded transport margin. With no
        # explicit budget, leave the socket unbounded: the pod's database run
        # policy is the one behavioral deadline and owns the response.
        transport_timeout = (
            timeout_ms / 1000 + config.HTTP_MARGIN_S
            if timeout_ms is not None else None
        )
        with urllib.request.urlopen(
            req, timeout=transport_timeout
        ) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 422:
            body = e.read().decode(errors="replace")
            try:
                msg = json.loads(body).get("error", body)
            except json.JSONDecodeError:
                msg = body
            raise AgentRunRefused(msg) from None
        raise


def _resolve_timeout_ms(state: TaskState, timeout_s: int | None) -> int | None:
    """Resolve an explicit sample/run timeout, preserving absence."""
    metadata = state.metadata or {}
    if "timeout_ms" in metadata:
        return int(metadata["timeout_ms"])
    if timeout_s is not None:
        return int(timeout_s * 1000)
    return None


def _prompt_text(state: TaskState) -> str:
    """The prompt the pod agent gets: the TEMPLATED user prompt.

    A bench's answer-format contract (e.g. gsm8k's "ANSWER: $ANSWER") is
    applied by its prompt_template solver onto `state.user_prompt` — the raw
    `input_text` never carries it. `catalog.swap_generate` keeps those
    template solvers ahead of us; reading user_prompt here delivers their
    work. With no template solvers, user_prompt IS the input."""
    try:
        return state.user_prompt.text
    except Exception:  # non-user-message inputs (defensive; case-1 is text)
        return state.input_text


def _record_result(state: TaskState, result: dict) -> TaskState:
    """Set the completion + the pod-side honesty metadata on the state."""
    state.output.completion = result.get("reply", "")
    state.metadata = state.metadata or {}
    state.metadata.update({
        "pod_agent_id": result.get("agent_id"),
        "pod_turns": result.get("turns"),
        "pod_closed_reason": result.get("closed_reason"),
        "pod_evals": result.get("evals"),
        "pod_timed_out": result.get("timed_out", False),
        "pod_elapsed_ms": result.get("elapsed_ms"),
        # Runtime-derived model provenance (2026-07-04): the door COMPUTES
        # model_config at response time via the pod's pure config resolver
        # (seon.ai/resolved-config: agent overrides → config row → shipped
        # defaults) — derive-don't-store; always present on a run response.
        # scorecard.model_provenance_from_run maps it onto ledger-row fields.
        "pod_model_config": result.get("model_config"),
    })
    # Seon captures these on every turn. Keep the complete immutable database
    # point and exact model-facing bytes in the native Inspect sample metadata
    # so a scored failure remains reconstructable after the live cluster
    # advances or is removed. Presence is meaningful: an older/broken pod that
    # omitted evidence must not be normalized into a plausible null/empty set.
    if "database" in result:
        state.metadata["pod_database_value"] = result["database"]
    if "turn_evidence" in result:
        state.metadata["pod_turn_evidence"] = result["turn_evidence"]
    if "model_transport_evidence" in result:
        state.metadata["pod_model_transport_evidence"] = result[
            "model_transport_evidence"]
    if "eval_evidence" in result:
        state.metadata["pod_eval_evidence"] = result["eval_evidence"]
    if "effective_timeout_ms" in result:
        state.metadata["pod_effective_timeout_ms"] = result[
            "effective_timeout_ms"]
    if "timeout_source" in result:
        state.metadata["pod_timeout_source"] = result["timeout_source"]
    if result.get("evidence_blobs") is not None:
        state.metadata["pod_evidence_blobs"] = result["evidence_blobs"]
    return state


def _require_model_transport_evidence(metadata: dict) -> list[dict]:
    """Fail closed on incomplete or inconsistent admitted provider proof."""
    evidence = metadata.get("pod_model_transport_evidence")
    turn_evidence = metadata.get("pod_turn_evidence")
    if (not isinstance(evidence, dict)
            or evidence.get("status") != "inline"
            or evidence.get("transport_drift") is not False
            or not isinstance(turn_evidence, list)
            or not turn_evidence):
        raise PodRunInfrastructureError(
            "admitted pod run lacks inline model transport evidence")

    expected_turn_ids = [
        turn.get("turn_id") if isinstance(turn, dict) else None
        for turn in turn_evidence]
    turns = evidence.get("turns")
    if (not isinstance(turns, list) or not turns
            or [turn.get("turn_id") if isinstance(turn, dict) else None
                for turn in turns] != expected_turn_ids
            or any(not isinstance(turn_id, str) or not turn_id
                   for turn_id in expected_turn_ids)
            or len(set(expected_turn_ids)) != len(expected_turn_ids)):
        raise PodRunInfrastructureError(
            "admitted model transport turn membership/order is invalid")

    comparable_keys = (
        "provider", "adapter", "requested_model", "temperature",
        "max_tokens", "thinking", "endpoint", "adapter_timeout_ms",
        "outer_timeout_ms", "stream", "extra_body_digest", "dg_backend",
        "api_key_env")
    configurations = []
    all_attempts = []
    outcomes = {"success", "provider-error", "adapter-timeout",
                "outer-timeout"}
    for turn_id, turn in zip(expected_turn_ids, turns, strict=True):
        attempts = turn.get("attempts") if isinstance(turn, dict) else None
        if not isinstance(attempts, list) or not attempts:
            raise PodRunInfrastructureError(
                "admitted model transport attempt sequence is absent")
        for ordinal, attempt in enumerate(attempts):
            required = {
                "turn_id", "ordinal", "historical_config_valid",
                "provider", "adapter", "requested_model",
                "outer_timeout_ms", "stream",
                "outcome"}
            if (not isinstance(attempt, dict)
                    or not required.issubset(attempt)
                    or attempt.get("turn_id") != turn_id
                    or attempt.get("ordinal") != ordinal
                    or attempt.get("historical_config_valid") is not True
                    or "evidence_error" in attempt
                    or not isinstance(attempt.get("provider"), str)
                    or not attempt["provider"]
                    or not isinstance(attempt.get("adapter"), str)
                    or not attempt["adapter"]
                    or not isinstance(attempt.get("requested_model"), str)
                    or not attempt["requested_model"]
                    or ("temperature" in attempt
                        and (isinstance(attempt["temperature"], bool)
                             or not isinstance(
                                 attempt["temperature"], (int, float))))
                    or ("max_tokens" in attempt
                        and (isinstance(attempt["max_tokens"], bool)
                             or not isinstance(attempt["max_tokens"], int)
                             or attempt["max_tokens"] <= 0))
                    or ("thinking" in attempt
                        and (not isinstance(attempt["thinking"], str)
                             or not attempt["thinking"]))
                    or isinstance(attempt.get("outer_timeout_ms"), bool)
                    or not isinstance(attempt.get("outer_timeout_ms"), int)
                    or attempt["outer_timeout_ms"] <= 0
                    or not isinstance(attempt.get("stream"), bool)
                    or attempt.get("outcome") not in outcomes):
                raise PodRunInfrastructureError(
                    "admitted model transport attempt is malformed")
            if attempt["adapter"] == "openai-compat" and (
                    not isinstance(attempt.get("endpoint"), str)
                    or not attempt["endpoint"]
                    or isinstance(attempt.get("adapter_timeout_ms"), bool)
                    or not isinstance(attempt.get("adapter_timeout_ms"), int)
                    or attempt["adapter_timeout_ms"] <= 0):
                raise PodRunInfrastructureError(
                    "admitted OpenAI-compatible transport identity is absent")
            configurations.append(tuple(
                (key, attempt[key]) for key in comparable_keys if key in attempt))
            all_attempts.append(attempt)
        if ([attempt.get("outcome") for attempt in attempts].count("success")
                != 1 or attempts[-1].get("outcome") != "success"):
            raise PodRunInfrastructureError(
                "admitted model transport outcome sequence is inconsistent")

    if len(set(configurations)) != 1:
        raise PodRunInfrastructureError(
            "admitted model transport configuration drifted during the run")
    return all_attempts


def _require_model_server_identity(metadata: dict,
                                   attempts: list[dict]) -> None:
    """Join admitted server/artifact identity to every provider attempt."""
    try:
        identity = cluster_mod.validate_model_server_identity(
            metadata.get("seon_model_server_identity"))
    except ValueError as error:
        raise PodRunInfrastructureError(
            f"admitted model server identity is invalid: {error}") from None
    artifact = identity["artifact"]
    if artifact["mechanism"] == "externally-mutable":
        raise PodRunInfrastructureError(
            "externally mutable model weights cannot support formal scoring")
    if artifact["mechanism"] != "huggingface-snapshot":
        raise PodRunInfrastructureError(
            "model artifact mechanism lacks a request-time loaded digest proof")

    endpoint = identity["endpoint"]
    requested_model = artifact["request_model"]
    response = identity["response"]
    for attempt in attempts:
        if (str(attempt.get("endpoint") or "").rstrip("/") != endpoint
                or attempt.get("requested_model") != requested_model):
            raise PodRunInfrastructureError(
                "model attempt does not match the admitted server artifact")
        if attempt.get("outcome") == "success" and (
                attempt.get("response_model") != response["model"]
                or attempt.get("system_fingerprint")
                != response["system_fingerprint"]):
            raise PodRunInfrastructureError(
                "successful model response does not match admitted server identity")


def require_scorable_pod_state(state: TaskState) -> TaskState:
    """Reject infrastructure closes before a task's scorer runs."""
    metadata = state.metadata or {}
    if metadata.get("pod_timed_out"):
        raise PodRunInfrastructureError(
            "pod timed out; model capability was not scored")
    closed_reason = str(metadata.get("pod_closed_reason") or "")
    if closed_reason == ":error":
        raise PodRunInfrastructureError(
            "pod closed with a core error; model capability was not scored")
    if closed_reason == ":quiesced":
        raise PodRunInfrastructureError(
            "pod quiesced during the run; model capability was not scored")
    if "seon_source_admission" in metadata:
        attempts = _require_model_transport_evidence(metadata)
        if metadata.get("seon_model_server_identity") is not None:
            _require_model_server_identity(metadata, attempts)
    return state


@solver
def seon_diagnostic_pod_solver(cluster_url: str | None = None,
                               timeout_s: int | None = None):
    """Record one static-pod result without capability-state admission.

    Diagnostic-only: callers intentionally measuring timeout/close honesty
    need the raw terminal state. Capability tasks must use ``seon_pod_solver``
    so infrastructure closes become native Inspect sample errors, not scores.
    """

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        result = await anyio.to_thread.run_sync(
            pod_run, _prompt_text(state),
            _resolve_timeout_ms(state, timeout_s), cluster_url)
        return _record_result(state, result)

    return solve


@solver
def seon_pod_solver(cluster_url: str | None = None,
                    timeout_s: int | None = None,
                    agent_id: str | None = None):
    """Drive one long-lived cluster through the capability-scoring boundary.

    `cluster_url` (or SEON_CLUSTER_URL) selects the cluster's pod door —
    e.g. acme. `agent_id` addresses one existing agent; absence preserves the
    pod door's ordinary fresh-agent behavior. Every sample lands on the SAME
    cluster serially. Records the
    pod-side metadata (turns / closed_reason / evals / timed_out / elapsed)
    so the eval log proves the multi-turn loop ran, then rejects timeout,
    ``:error``, and ``:quiesced`` before downstream parsing or scoring."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        result = await anyio.to_thread.run_sync(
            pod_run, _prompt_text(state),
            _resolve_timeout_ms(state, timeout_s), cluster_url, agent_id)
        return require_scorable_pod_state(_record_result(state, result))

    return solve


@solver
def seon_cluster_solver(timeout_s: int | None = None,
                        cluster_prefix: str = "bench",
                        evidence_root=None):
    """One EPHEMERAL cluster per sample: create → drive → destroy.

    True per-sample isolation by construction — each sample gets a fresh
    cluster (own db, own pod, own blobs), destroyed afterwards even on
    failure. `evidence_root` (a Path; evidence-retention fix 2026-07-04)
    copies each cluster's blob store (rendered prompts + verbatim replies)
    to `evidence_root/e<epoch>/<sample_id>/blobs` BEFORE destroy — a wrong
    reply stays attributable after the cluster is gone. Serial per solver
    call (bench-cluster-N dispatches N samples concurrently). Budget per
    sample = cluster boot (config.CLUSTER_BOOT_BUDGET_S) + the row timeout."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        from seon_inspect.cluster import ephemeral_cluster

        timeout_ms = _resolve_timeout_ms(state, timeout_s)

        def drive() -> dict:
            from seon_inspect.cluster import bench_cluster_name
            with ephemeral_cluster(bench_cluster_name(cluster_prefix)) as c:
                out = pod_run(_prompt_text(state), timeout_ms, c.url)
                if evidence_root is not None:
                    from seon_inspect.tool_rows import preserve_cluster_evidence
                    epoch = getattr(state, "epoch", 1)
                    dest = (evidence_root / f"e{epoch}"
                            / str(state.sample_id))
                    out["evidence_blobs"] = preserve_cluster_evidence(
                        c.name, dest)
                return out

        result = await anyio.to_thread.run_sync(drive)
        return require_scorable_pod_state(_record_result(state, result))

    return solve


@scorer(metrics=[accuracy()])
def timeout_honesty() -> Scorer:
    """A timed-out sample must be RECORDED honestly (anti-mis-recording gate).

    CORRECT iff the pod reported timed_out=True AND closed_reason contains
    "timeout". A false success (a completed/waited close or a stale reply on a
    clock cut-off) is INCORRECT — the benchmark-corrupting failure this exists
    to keep fixed.
    """

    async def score(state: TaskState, target: Target) -> Score:
        md = state.metadata or {}
        timed_out = bool(md.get("pod_timed_out"))
        reason = str(md.get("pod_closed_reason") or "")
        honest = timed_out and ("timeout" in reason)
        return Score(
            value="C" if honest else "I",
            answer=f"timed_out={timed_out} closed_reason={reason!r}",
            explanation=("honest timeout recorded" if honest else
                         "DISHONEST: a timed-out run must report timed_out+timeout"),
        )

    return score
