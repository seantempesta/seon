"""BFCL single-turn AST subset over the pod door — the form->tool_call bridge.

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
                      user request into the prompt, asking for the call(s) as
                      a Clojure FORM — the agent's NATIVE output surface (it
                      lives at a Clojure REPL; every action it takes is a form
                      it evaluates). STATES every check the scorer makes (exact
                      function/param names, all required params, no extras,
                      literal types, one call for simple/multiple & all calls
                      for parallel) — the load-bearing "every scorer check must
                      be in context" law (0/2 -> ~1.0).
  2. the pod solver — drives `/agents/run` unchanged (seon_pod_solver /
                      seon_cluster_solver); records the reply as the completion.
  3. `bfcl_parse`   — read the reply's Clojure form(s) with a small s-expr
                      reader and append a `ChatMessageAssistant` carrying the
                      synthesized `ToolCall`s, so the bench's OWN `ast_match`
                      reads exactly what it expects. A reply with no parseable
                      call is a `parse_miss` (an adapter/format signal, fix the
                      bridge), distinct from a MODEL MISS (a parseable but wrong
                      call).

WHY A FORM, NOT JSON (the 2026-07-05 rework). The original bridge asked for a
JSON array — trivial to `json.loads`, but a FOREIGN surface: our agents emit
Clojure forms as their native output (their system prompt: "everything you do
— read, compute, store, reply, render — is a Clojure form evaluated here"). A
JSON contract fights the form-oriented context AND tests a surface the agent
was never trained-in — a confound that may UNDERSTATE the tool-calling
capability. The observed proof: even when told to emit JSON, the dev-run agents
still trailed a Clojure form (`(plan/done! {:my.plan/id …})`) in their reply.
Asking for the form ALIGNS the bench with the agent's real surface; the
reader below maps Clojure literals onto the same native Python types the AST
matcher compares (int/float/str/list/dict/bool), so the scorer is untouched.

Scope = the PYTHON single-turn AST categories only (simple_python, multiple,
parallel, parallel_multiple) — one uniform set of type rules (python allows int
where float is expected). Java/JS AST categories are loadable via `categories=`
but deferred (their literal-float rule is a separate prompt concern); exec_*,
rest, live_*, multi_turn_*, and irrelevance/relevance are out of scope (exec/
multi-turn need a sandbox/tool bridge; irrelevance measures abstention, a
different scorer).
"""

from __future__ import annotations

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


def _normalize_name(name: str) -> str:
    """Mirror the scorer's `normalize_function_name` (dots -> underscores)."""
    return name.replace(".", "_")


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
    """The pod prompt: candidate functions + request + the Clojure-form contract.

    `matching` is the category's matching function ("simple" | "multiple" |
    "parallel") — it fixes the call-count contract the scorer enforces:
    simple/multiple expect EXACTLY ONE call, parallel expects ALL the calls
    the request implies. Every other scorer check (exact names, required
    params, no extras, literal types) is stated verbatim so the bench measures
    tool-calling, not prompt omission. The answer surface is a Clojure form —
    the agent's NATIVE output — so the bench does not fight the form-oriented
    context (the 2026-07-05 rework; see module docstring)."""
    catalog = "\n\n".join(_render_function(t) for t in tools)
    if matching == "parallel":
        count_rule = (
            "This request may require SEVERAL function calls. Emit one Clojure "
            "form per call the request implies (there can be more than one), "
            "written one after another, e.g. "
            '(fn_a {:arg 1}) (fn_b {:arg "x"}).')
        example = '(function_name {:param_a "value" :param_b 3})'
    else:
        count_rule = (
            "Choose the single correct function and emit EXACTLY ONE call as a "
            "single Clojure form.")
        example = '(function_name {:param_a "value" :param_b 3})'
    return (
        "You are given a set of candidate functions and a user request. Decide "
        "which function(s) to call and with what arguments to satisfy the "
        "request. Do NOT execute anything or explain — only report the "
        "call(s).\n\n"
        "Candidate functions:\n\n"
        f"{catalog}\n\n"
        f"User request:\n{question}\n\n"
        "Reply with the function call(s) as a Clojure form — the way you would "
        "invoke a verb at your REPL: the function name as the head symbol and "
        "ONE argument map of keyword->value, e.g.\n"
        f"    {example}\n"
        "Rules the answer must satisfy:\n"
        "- Use the EXACT function name as the head symbol and the EXACT "
        "parameter names as keywords (`:param`) from the schema above — no "
        "renaming, no extra parameters.\n"
        "- Include every REQUIRED parameter; include an optional one only when "
        "the request calls for it.\n"
        "- Give each argument a Clojure literal of its schema type — a value "
        "taken from the request:\n"
        "    integer -> 3   number/float -> 3.0   string -> \"text\"   "
        "boolean -> true/false   array -> [\"a\" \"b\"]   object -> {:k \"v\"}.\n"
        f"- {count_rule}\n"
        "- Output ONLY the call form(s) — no prose, no code fence, no other "
        "forms."
    )


# ---------------------------------------------------------------------------
# Reply parsing — a small Clojure s-expr reader -> ToolCall(s)
#
# No EDN dependency (the package deps are just inspect-ai); a real reader over
# a hand-rolled regex, per the rework brief. The reader is deliberately narrow:
# it reads the literal subset a BFCL answer uses — lists (), vectors [], maps
# {}, strings, keywords, symbols, numbers, true/false/nil — and maps them onto
# native Python types (the exact types `ast_match` compares).
# ---------------------------------------------------------------------------


class _Sym(str):
    """A Clojure symbol token (distinguishes a bare `foo` from a string)."""


class _Kw(str):
    """A Clojure keyword token (`:foo` / `:ns/foo`), stored without the colon."""


class _List(list):
    """A `(...)` list (distinguishes a call form from a `[...]` vector)."""


_ATOM_END = set(' \t\r\n,;()[]{}"')
_MACRO_PREFIX = set("'`~@#")


def _skip_ws(s: str, i: int) -> int:
    n = len(s)
    while i < n:
        ch = s[i]
        if ch in " \t\r\n,":
            i += 1
        elif ch == ";":  # comment to end of line
            while i < n and s[i] != "\n":
                i += 1
        else:
            break
    return i


def _read_string(s: str, i: int) -> tuple[str, int]:
    # s[i] == '"'
    i += 1
    out: list[str] = []
    n = len(s)
    while i < n:
        ch = s[i]
        if ch == "\\":
            if i + 1 < n:
                nxt = s[i + 1]
                out.append({"n": "\n", "t": "\t", "r": "\r"}.get(nxt, nxt))
                i += 2
                continue
            i += 1
            continue
        if ch == '"':
            return "".join(out), i + 1
        out.append(ch)
        i += 1
    return "".join(out), i  # unterminated — return what we have


def _read_atom(s: str, i: int) -> tuple[Any, int]:
    n = len(s)
    start = i
    while i < n and s[i] not in _ATOM_END:
        i += 1
    tok = s[start:i]
    return _interpret_atom(tok), i


def _interpret_atom(tok: str) -> Any:
    if tok == "true":
        return True
    if tok == "false":
        return False
    if tok == "nil":
        return None
    # integer
    body = tok[1:] if tok[:1] in "+-" else tok
    if body.isdigit():
        return int(tok)
    # float (has a '.' or exponent, and a digit)
    if any(c.isdigit() for c in tok) and any(c in ".eE" for c in tok):
        try:
            return float(tok)
        except ValueError:
            pass
    return _Sym(tok)


def _read(s: str, i: int) -> tuple[Any, int]:
    i = _skip_ws(s, i)
    n = len(s)
    if i >= n:
        return None, i
    ch = s[i]
    if ch in _MACRO_PREFIX:  # ' ` ~ @ # — drop the reader-macro char, read next
        return _read(s, i + 1)
    if ch == '"':
        return _read_string(s, i)
    if ch == ":":
        val, j = _read_atom(s, i + 1)
        return _Kw(str(val)), j
    if ch == "(":
        items, j = _read_seq(s, i + 1, ")")
        return _List(items), j
    if ch == "[":
        items, j = _read_seq(s, i + 1, "]")
        return list(items), j
    if ch == "{":
        items, j = _read_seq(s, i + 1, "}")
        pairs = {}
        for k in range(0, len(items) - 1, 2):
            pairs[_key_name(items[k])] = _pyify(items[k + 1])
        return pairs, j
    return _read_atom(s, i)


def _read_seq(s: str, i: int, close: str) -> tuple[list[Any], int]:
    items: list[Any] = []
    n = len(s)
    while True:
        i = _skip_ws(s, i)
        if i >= n:
            return items, i
        if s[i] == close:
            return items, i + 1
        if s[i] in ")]}":  # a mismatched closer — stop this seq
            return items, i + 1
        val, i = _read(s, i)
        items.append(val)


def _key_name(k: Any) -> str:
    """A map key -> its string name (`:ns/foo` -> "foo", `:foo` -> "foo")."""
    if isinstance(k, (_Kw, _Sym)):
        raw = str(k)
        return raw.split("/")[-1]
    return str(k)


def _pyify(v: Any) -> Any:
    """Native Python value for an argument (the shape `ast_match` compares)."""
    if isinstance(v, _Kw):
        return str(v).split("/")[-1]          # keyword value -> its name string
    if isinstance(v, _Sym):
        return str(v)                          # stray symbol -> its name string
    if isinstance(v, dict):
        return {k: _pyify(x) for k, x in v.items()}
    if isinstance(v, list):                    # covers _List and vectors
        return [_pyify(x) for x in v]
    return v


# ---------------------------------------------------------------------------
# Form spans + call collection
# ---------------------------------------------------------------------------


def _form_spans(text: str) -> list[str]:
    """Every balanced top-level `(...)`/`[...]` span, string/comment-aware.

    Only `(` and `[` open a span (a bare `{...}` is never a call); `{`/`}`
    inside a span are balanced literals, not depth-tracked. Brackets inside
    strings and `;` comments are ignored so prose can't confuse the scan — and
    `;` comments count at EVERY depth (top-level `; prose (with parens)` is how
    the agent narrates natively, per its REPL surface)."""
    spans: list[str] = []
    depth = 0
    start = -1
    in_str = False
    escape = False
    in_cmt = False
    for i, ch in enumerate(text):
        if in_cmt:
            if ch == "\n":
                in_cmt = False
            continue
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
        if ch == ";":
            in_cmt = True
            continue
        if ch in "([":
            if depth == 0:
                start = i
            depth += 1
        elif ch in ")]":
            if depth > 0:
                depth -= 1
                if depth == 0 and start >= 0:
                    spans.append(text[start:i + 1])
                    start = -1
    return spans


def _collect_calls(datum: Any, out: list[dict[str, Any]]) -> None:
    """Walk a datum, appending each call-shaped `(sym {…})` as {name,arguments}.

    A `_List` whose head is a symbol IS a call (its first map is the args); a
    plain vector/list is recursed into (so `[(f {}) (g {})]` yields both) but a
    call's own arguments are NOT recursed (a nested map arg is data, not a
    call)."""
    if isinstance(datum, _List) and datum and isinstance(datum[0], _Sym):
        args: dict[str, Any] = {}
        for elem in datum[1:]:
            if isinstance(elem, dict):
                args = elem
                break
        out.append({"name": str(datum[0]), "arguments": args})
        return
    if isinstance(datum, list):  # vector or non-call list — look inside
        for elem in datum:
            _collect_calls(elem, out)


def parse_calls(reply: str, candidates: list[str] | None = None,
                matching: str = "parallel") -> list[dict[str, Any]]:
    """The call(s) the model reported, as {name, arguments} dicts.

    Reads every top-level Clojure form in the reply and keeps the call-shaped
    ones. When `candidates` (the sample's candidate function names) is given,
    forms naming a candidate are preferred (bookkeeping forms like
    `(plan/done! …)` fall away); only when NONE name a candidate do the raw
    call forms stand (so a genuine wrong-name call still surfaces as a model
    miss, not a parse miss). For `matching == "parallel"` ALL kept calls are
    returned (the request implies several); otherwise the LAST call is returned
    (agents reason first and conclude with the answer — simple/multiple want
    exactly one). Returns [] when nothing call-shaped parses — a PARSE MISS
    (adapter/format signal, not a capability score)."""
    if not reply:
        return []
    calls: list[dict[str, Any]] = []
    for span in _form_spans(reply):
        try:
            for datum in _read_forms(span):
                _collect_calls(datum, calls)
        except Exception:
            continue
    if not calls:
        return []
    if candidates:
        cand = {_normalize_name(c) for c in candidates}
        matched = [c for c in calls if _normalize_name(c["name"]) in cand]
        if matched:
            calls = matched
    # Materialize arguments to native Python (keyword keys/values -> strings).
    calls = [{"name": c["name"], "arguments": _pyify_map(c["arguments"])}
             for c in calls]
    if matching == "parallel":
        return calls
    return [calls[-1]]


def _read_forms(span: str) -> list[Any]:
    forms: list[Any] = []
    i = 0
    n = len(span)
    while True:
        i = _skip_ws(span, i)
        if i >= n:
            break
        val, j = _read(span, i)
        if j <= i:  # no progress — bail to avoid an infinite loop
            break
        forms.append(val)
        i = j
    return forms


def _pyify_map(args: Any) -> dict[str, Any]:
    if not isinstance(args, dict):
        return {}
    return {str(k): _pyify(v) for k, v in args.items()}


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
    """Rewrite the user prompt to the function catalog + Clojure-form contract."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        tools = (state.metadata or {}).get("tools", [])
        matching = (state.metadata or {}).get("scorer", "simple")
        original = state.user_prompt.text
        state.user_prompt.text = render_bfcl_prompt(original, tools, matching)
        return state

    return solve


@solver
def bfcl_parse() -> Solver:
    """Lift the pod's Clojure-form reply into a `ToolCall`-carrying message.

    Appends ONE `ChatMessageAssistant` so the bench's own `ast_match` reads the
    synthesized calls off `state.messages`. A reply with no call-shaped form
    appends the message with no tool_calls (scorer -> 0) and records
    `bfcl_parse_error` — a parse miss is a harness-quality signal to fix, never
    a capability number."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        reply = state.output.completion or ""
        tools = (state.metadata or {}).get("tools", [])
        matching = (state.metadata or {}).get("scorer", "simple")
        candidates = [t.get("name", "") for t in tools if t.get("name")]
        calls = parse_calls(reply, candidates=candidates, matching=matching)
        state.metadata = state.metadata or {}
        if calls:
            tool_calls = _to_tool_calls(calls)
            state.metadata.pop("bfcl_parse_error", None)
        else:
            tool_calls = None
            state.metadata["bfcl_parse_error"] = (
                "no call-shaped Clojure form in reply" if reply.strip()
                else "empty reply")
        state.messages.append(
            ChatMessageAssistant(content=reply, tool_calls=tool_calls))
        return state

    return solve


def bfcl_adapt(task_solver: Any, pod_solver: Solver) -> list[Solver]:
    """The `adapt` hook: replace bfcl's FC solver with the form->call bridge.

    Signature-compatible with `catalog.swap_generate` (task_solver, pod_solver)
    -> list[Solver], so `run_bench` selects it by bench name. The bench's own
    solver is discarded (it drove native FC); the SCORER is untouched — the
    chain renders the contract, drives the pod, and synthesizes the tool_calls
    the scorer harvests."""
    return [bfcl_prompt(), pod_solver, bfcl_parse()]
