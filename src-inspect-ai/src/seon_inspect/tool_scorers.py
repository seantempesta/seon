"""Outcome scorers for the surviving shell and web evaluation rows.

Checks are pure data-in/data-out and cover only task-stated filesystem outcomes
or fixture-derived answers. Clojure code grading belongs to the current
clojure.test/test.check provider boundary, not an embedded parser/evaluator.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

_INT = re.compile(r"-?\d+")


# ---------------------------------------------------------------------------
# Pure checks — data in, data out
# ---------------------------------------------------------------------------


def check_workspace(workspace: str | Path,
                    oracle: dict[str, Any]) -> dict[str, Any]:
    """Verify task-stated exact and absent filesystem outcomes."""
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
    """Score a shell-use sample by re-reading its workspace.

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
