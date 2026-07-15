"""BFCL single-turn AST subset over the pod door — native completion bridge.

BFCL (Berkeley Function-Calling Leaderboard) is THE canonical established
tool-calling bench. Its V1 single-turn non-live non-exec categories are scored
by a pure-Python AST matcher (`inspect_evals.bfcl.score.ast_match`) — NO code
runs, NO sandbox, NO tool bridge (even the single-turn `exec_*` ground truth is
preprocessed into the same matcher; adoption plan
docs/prds/agent-ctx/research/agentic-benchmark-adoption-2026-07-04.md). That
deterministic host-side oracle fits our `POST /agents/run` door and the
"scorers gate correctness" rule exactly — the ONE gap is the shape of the
answer the scorer reads.

THE GAP AND THE BRIDGE. `bfcl`'s own single-turn solver drives inspect's native
FC path (`generate(tool_calls="none")`) and the scorer harvests structured
`ToolCall`s off the assistant message (`score/scorer.py:_extract_tool_calls`).
The Seon pod is a Clojure agent behind `/agents/run`; it returns TEXT, never
OpenAI-style `tool_calls`. So we swap the bench's solver (never its scorer) for
a three-step chain that IS the FC path's text equivalent:

  1. `bfcl_prompt`  — render the sample's candidate function schemas + the
                      user request into the prompt, asking the agent to pass a
                      JSON call array to Seon's real `complete` function.
                      STATES every check the scorer makes
                      (exact function/param names, all required params, no
                      extra params, JSON types, one call for simple/multiple &
                      all calls for parallel) — the load-bearing "every scorer
                      check must be in context" law (0/2 -> ~1.0).
  2. the pod solver — drives `/agents/run` unchanged (seon_pod_solver /
                      seon_cluster_solver); records the reply as the completion.
  3. `bfcl_parse`   — extract the JSON call(s) from the reply text and append a
                      `ChatMessageAssistant` carrying the synthesized
                      `ToolCall`s, so the bench's OWN `ast_match` reads exactly
                      what it expects. A reply with no parseable call appends an
                      assistant message with no tool_calls (scorer -> 0) and
                      stamps `bfcl_parse_error` in metadata: a PARSE MISS is a
                      HARNESS/adapter quality signal (fix the bridge), distinct
                      from a MODEL MISS (a parseable but wrong call).

The model-facing answer is one native Clojure form:

    (complete "[{\"name\":\"f\",\"arguments\":{...}}]")

`complete` closes the run and delivers its string through the ordinary message
path, so `/agents/run` returns only the unescaped JSON value. `json.loads` then
yields the native Python values BFCL compares. This avoids the contradictory
old contract (the Seon system requires executable forms while the task demanded
bare JSON and said not to execute anything) without inventing a second parser
or asking the agent to invoke candidate names that are not real Seon functions.

Scope = the PYTHON single-turn AST categories only (simple_python, multiple,
parallel, parallel_multiple) — one uniform set of type rules (python allows int
where float is expected). Java/JS AST categories are loadable via `categories=`
but deferred (their literal-float rule is a separate prompt concern); exec_*,
rest, live_*, multi_turn_*, and irrelevance/relevance are out of scope (exec/
multi-turn need a sandbox/tool bridge; irrelevance measures abstention, a
different scorer).
"""

from __future__ import annotations

import json
from typing import Any

from inspect_ai.model import ChatMessageAssistant
from inspect_ai.solver import Generate, Solver, TaskState, solver
from inspect_ai.tool import ToolCall

# The python single-turn AST categories — pure `ast_match`, host-side, no exec.
# (simple_python -> "simple"; multiple -> "multiple"; parallel &
# parallel_multiple -> "parallel"; all language "python".)
BFCL_AST_CATEGORIES: list[str] = [
    "simple_python",
    "multiple",
    "parallel",
    "parallel_multiple",
]

# Keys a model might use for the arguments map — normalized to "arguments".
_ARG_KEYS = ("arguments", "args", "parameters", "params")


# ---------------------------------------------------------------------------
# Prompt rendering — state every check the AST scorer makes
# ---------------------------------------------------------------------------


def _render_param(name: str, schema: dict[str, Any], required: bool) -> str:
    parts = [f"    - {name}"]
    typ = schema.get("type")
    if typ is not None:
        parts.append(f" ({typ})")
    if required:
        parts.append(" [required]")
    desc = schema.get("description")
    if desc:
        parts.append(f": {desc}")
    if schema.get("enum"):
        parts.append(f"  (one of: {schema['enum']})")
    items = schema.get("items") or {}
    if items.get("type"):
        parts.append(f"  (element type: {items['type']})")
    return "".join(parts)


def _render_function(tool: dict[str, Any]) -> str:
    params = (tool.get("parameters") or {})
    props: dict[str, Any] = params.get("properties") or {}
    required = set(params.get("required") or [])
    lines = [f"Function: {tool.get('name', '')}"]
    desc = tool.get("description")
    if desc:
        lines.append(f"  {desc}")
    if props:
        lines.append("  Parameters:")
        for pname, pschema in props.items():
            lines.append(_render_param(pname, pschema or {}, pname in required))
    else:
        lines.append("  Parameters: (none)")
    return "\n".join(lines)


def render_bfcl_prompt(question: str, tools: list[dict[str, Any]],
                       matching: str) -> str:
    """The pod prompt: candidates + request + native `complete` contract.

    `matching` is the category's matching function ("simple" | "multiple" |
    "parallel") — it fixes the call-count contract the scorer enforces:
    simple/multiple expect EXACTLY ONE call, parallel expects ALL the calls
    the request implies. Every other scorer check (exact names, required
    params, no extras, JSON types) is stated verbatim so the bench measures
    tool-calling, not prompt omission."""
    catalog = "\n\n".join(_render_function(t) for t in tools)
    if matching == "parallel":
        count_rule = (
            "This request may require SEVERAL function calls. Put one JSON "
            "object per call the request implies into the array (there can be "
            "more than one).")
    else:
        count_rule = (
            "Choose the single correct function and emit EXACTLY ONE call.")
    return (
        "You are given a set of candidate functions and a user request. Decide "
        "which function(s) should be called and with what arguments. Report "
        "the call specification through Seon's existing `complete` lifecycle "
        "function; do not invoke the candidate functions themselves.\n\n"
        "Candidate functions:\n\n"
        f"{catalog}\n\n"
        f"User request:\n{question}\n\n"
        "Your entire reply must be ONE executable Clojure form shaped:\n"
        '    (complete "[{\\"name\\":\\"<function name>\\",'
        '\\"arguments\\":{<escaped JSON arguments>}}]")\n'
        "The string passed to `complete` is a JSON array of call objects. "
        "Rules the answer must satisfy:\n"
        "- Use the EXACT function name and parameter names from the schema "
        "above (no renaming, no extra parameters).\n"
        "- Include every REQUIRED parameter; include an optional one only when "
        "the request calls for it.\n"
        "- Give each argument its schema JSON type (integer, number/float, "
        "string, boolean, array, object) — a value taken from the request.\n"
        f"- {count_rule}\n"
        "- Escape every double quote inside the Clojure string with `\\\"`.\n"
        "- Output only the single `(complete \"...\")` form: no prose, code "
        "fence, predicted result, or candidate-function invocation."
    )


# ---------------------------------------------------------------------------
# Reply parsing — text -> ToolCall(s)
# ---------------------------------------------------------------------------


def _json_spans(text: str) -> list[str]:
    """Every balanced top-level {...}/[...] span in `text`, string-aware.

    Scans once, tracking string/escape state so brackets inside JSON strings
    don't confuse depth. Returns spans in appearance order; nested spans are
    NOT returned separately (only the outermost balanced runs)."""
    spans: list[str] = []
    depth = 0
    start = -1
    in_str = False
    escape = False
    opener = ""
    for i, ch in enumerate(text):
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            continue
        if ch in "[{":
            if depth == 0:
                start = i
                opener = ch
            depth += 1
        elif ch in "]}":
            if depth > 0:
                depth -= 1
                if depth == 0 and start >= 0:
                    close = "]" if opener == "[" else "}"
                    if ch == close:
                        spans.append(text[start:i + 1])
                    start = -1
    return spans


def _as_calls(value: Any) -> list[dict[str, Any]] | None:
    """Normalize a parsed JSON value into a list of {name, arguments} calls.

    Accepts a single call object or a list of them; tolerates the arguments
    map under any of `_ARG_KEYS`. Returns None when `value` isn't call-shaped
    (so the span scanner can skip incidental JSON like an example dict)."""
    items = value if isinstance(value, list) else [value]
    if not items:
        return None
    calls: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            return None
        name = item.get("name")
        if not isinstance(name, str) or not name:
            return None
        args: Any = {}
        for key in _ARG_KEYS:
            if key in item and isinstance(item[key], dict):
                args = item[key]
                break
        calls.append({"name": name, "arguments": dict(args)})
    return calls


def parse_calls(reply: str) -> list[dict[str, Any]]:
    """The call(s) the model reported, as {name, arguments} dicts.

    Prefers the LAST call-shaped JSON span in the reply (agents often reason
    first and conclude with the answer). Returns [] when nothing call-shaped
    parses — the caller treats that as a PARSE MISS (an adapter/format signal,
    not a capability score)."""
    if not reply:
        return []
    found: list[dict[str, Any]] = []
    for span in _json_spans(reply):
        try:
            value = json.loads(span)
        except (json.JSONDecodeError, ValueError):
            continue
        calls = _as_calls(value)
        if calls is not None:
            found = calls  # keep the last valid span
    return found


def _to_tool_calls(calls: list[dict[str, Any]]) -> list[ToolCall]:
    return [
        ToolCall(id=f"bfcl_{i}", function=c["name"], arguments=c["arguments"])
        for i, c in enumerate(calls)
    ]


# ---------------------------------------------------------------------------
# The three-step solver chain (adapt hook for catalog.run_bench)
# ---------------------------------------------------------------------------


@solver
def bfcl_prompt() -> Solver:
    """Rewrite the user prompt to the function catalog + JSON-call contract."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        tools = (state.metadata or {}).get("tools", [])
        matching = (state.metadata or {}).get("scorer", "simple")
        original = state.user_prompt.text
        state.user_prompt.text = render_bfcl_prompt(original, tools, matching)
        return state

    return solve


@solver
def bfcl_parse() -> Solver:
    """Lift the pod's text reply into a `ToolCall`-carrying assistant message.

    Appends ONE `ChatMessageAssistant` so the bench's own `ast_match` reads the
    synthesized calls off `state.messages`. A reply with no call-shaped JSON
    appends the message with no tool_calls (scorer -> 0) and records
    `bfcl_parse_error` — a parse miss is a harness-quality signal to fix, never
    a capability number."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        reply = state.output.completion or ""
        calls = parse_calls(reply)
        state.metadata = state.metadata or {}
        if calls:
            tool_calls = _to_tool_calls(calls)
            state.metadata.pop("bfcl_parse_error", None)
        else:
            tool_calls = None
            state.metadata["bfcl_parse_error"] = (
                "no call-shaped JSON in reply" if reply.strip()
                else "empty reply")
        state.messages.append(
            ChatMessageAssistant(content=reply, tool_calls=tool_calls))
        return state

    return solve


def bfcl_adapt(task_solver: Any, pod_solver: Solver) -> list[Solver]:
    """The `adapt` hook: replace bfcl's FC solver with the text->call bridge.

    Signature-compatible with `catalog.swap_generate` (task_solver, pod_solver)
    -> list[Solver], so `run_bench` selects it by bench name. The bench's own
    solver is discarded (it drove native FC); the SCORER is untouched — the
    chain renders the contract, drives the pod, and synthesizes the tool_calls
    the scorer harvests."""
    return [bfcl_prompt(), pod_solver, bfcl_parse()]
