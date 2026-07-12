"""Oracle scorers for the bespoke tool rows (shell_use / web_fetch / file_edit).

CORRECTNESS only (eval-design): the scorer verifies the OUTCOME the task
stated — file contents / command effects re-read from the workspace, or the
ground-truth answer computed at generation time — never style, and never a
string-match over the agent's narration. Where the target is code, the check
runs through the REAL oracles (`oracle_scorers.bb` parse; the node cljs.js
eval bundle for behavioral cases). Every check evaluated here is stated in
the generated task text (`seon_inspect.generators`) — the load-bearing
finding: an unstated check measures prompt-omission, not capability.

Pure check functions (`check_workspace`, `check_answer`) return data
(`{"ok": bool, "failures": [...]}`) and are unit-tested against synthetic
transcripts/workspaces; the inspect `@scorer` wrappers just adapt them to
`Score`. Run wiring (per-sample metadata): `workspace` = the absolute per-run
workspace dir (shell_use / file_edit); web_fetch scores the completion
against `metadata["oracle"]`.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

BEHAVIORAL_TOL = 1e-6

_INT = re.compile(r"-?\d+")


# ---------------------------------------------------------------------------
# Pure checks — data in, data out
# ---------------------------------------------------------------------------


def _check_clj_parses(path: Path) -> str | None:
    from seon_inspect.oracle_scorers import oracle_parse

    text = path.read_text()
    pr = oracle_parse(text)
    # clean parse = no reader errors on non-empty content. `forms` counts only
    # list forms (a bare EDN map reports forms=0), so it is NOT the gate here.
    if text.strip() and not pr.get("errors"):
        return None
    return f"{path.name}: does not parse as Clojure/EDN ({pr.get('errors')})"


def _check_behavioral(path: Path, spec: dict[str, Any]) -> str | None:
    from seon_inspect.oracle_scorers import (EVAL_BUDGET_MS, _parse_value_vec,
                                             evalsrv)

    code = path.read_text()
    srv = evalsrv()
    dv = srv.call({"op": "eval", "code": code, "budget-ms": EVAL_BUDGET_MS})
    if not (dv and dv.get("ok")):
        return f"{path.name}: file does not eval ({(dv or {}).get('error')})"
    calls = "[" + " ".join(c["call"] for c in spec["cases"]) + "]"
    ev = srv.call({"op": "eval", "code": code + "\n" + calls,
                   "budget-ms": EVAL_BUDGET_MS})
    got = _parse_value_vec(ev.get("value", "")) if ev and ev.get("ok") else []
    for i, c in enumerate(spec["cases"]):
        g = got[i] if i < len(got) else None
        if g is None or abs(g - c["expect"]) > BEHAVIORAL_TOL:
            return (f"{path.name}: {c['call']} returned {g!r}, "
                    f"expected {c['expect']}")
    return None


def check_workspace(workspace: str | Path,
                    oracle: dict[str, Any]) -> dict[str, Any]:
    """Verify a shell_use / file_edit oracle spec against the workspace.

    Check kinds (all stated in the task text): `equals` (exact file content),
    `absent` (path must not exist), `clj_parses` (bb parse oracle),
    `behavioral` (node eval oracle: call cases, numeric compare)."""
    root = Path(workspace)
    failures: list[str] = []
    for check in oracle["checks"]:
        path = root / check["path"]
        if check.get("absent"):
            if path.exists():
                failures.append(f"{check['path']}: expected absent, exists")
            continue
        if not path.is_file():
            failures.append(f"{check['path']}: expected file missing")
            continue
        if "equals" in check:
            got = path.read_text()
            if got != check["equals"]:
                failures.append(
                    f"{check['path']}: content mismatch "
                    f"(got {got!r:.120}, expected {check['equals']!r:.120})")
        if check.get("clj_parses"):
            f = _check_clj_parses(path)
            if f:
                failures.append(f)
        if "behavioral" in check:
            f = _check_behavioral(path, check["behavioral"])
            if f:
                failures.append(f)
    return {"ok": not failures, "failures": failures}


def _word_present(word: str, text: str) -> bool:
    return re.search(r"\b" + re.escape(word.casefold()) + r"\b",
                     text.casefold()) is not None


def check_answer(completion: str, oracle: dict[str, Any]) -> dict[str, Any]:
    """Verify a web_fetch reply against the generation-time ground truth.

    integer kind: the stripped reply is the answer, or its LAST integer token
    equals the answer (the task says "reply with only the integer", so a
    conversational wrapper still resolves to one final number). text kind:
    the answer word is present AND no generator-known distractor is — an
    ambiguous reply naming several candidates scores wrong.
    """
    answer = oracle["answer"]
    text = (completion or "").strip()
    if oracle.get("kind") == "integer":
        ints = _INT.findall(text)
        ok = text == answer or (bool(ints) and ints[-1] == answer)
    else:
        ok = _word_present(answer, text) and not any(
            _word_present(d, text) for d in oracle.get("distractors", []))
    return {"ok": ok, "answer": answer, "reply_tail": text[-120:]}


# ---------------------------------------------------------------------------
# inspect @scorer wrappers
# ---------------------------------------------------------------------------

from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer,  # noqa: E402
                               Target, accuracy, scorer)
from inspect_ai.solver import TaskState  # noqa: E402


@scorer(metrics=[accuracy()])
def workspace_scorer() -> Scorer:
    """Score a shell_use / file_edit sample by re-reading its workspace.

    Requires `state.metadata["workspace"]` (absolute per-run dir, set by the
    run wiring when it materializes the sample's setup) and the generated
    `metadata["oracle"]`. CORRECT iff every stated outcome holds."""

    async def score(state: TaskState, target: Target) -> Score:
        import anyio

        meta = state.metadata or {}
        res = await anyio.to_thread.run_sync(
            check_workspace, meta["workspace"], meta["oracle"])
        return Score(
            value=CORRECT if res["ok"] else INCORRECT,
            explanation=json.dumps(res["failures"]),
            metadata=res,
        )

    return score


@scorer(metrics=[accuracy()])
def fixture_answer_scorer() -> Scorer:
    """Score a web_fetch sample against its fixture-derived ground truth."""

    async def score(state: TaskState, target: Target) -> Score:
        res = check_answer(state.output.completion,
                           (state.metadata or {})["oracle"])
        return Score(
            value=CORRECT if res["ok"] else INCORRECT,
            answer=res["reply_tail"],
            explanation=json.dumps(res),
            metadata=res,
        )

    return score
