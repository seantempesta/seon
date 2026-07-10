"""Hint rendering + error parsing — model-agnostic, NO mlx/torch imports.

The repair INTELLIGENCE lives oracle-side as of Phase 2: the worker-eval
bundle's `op:"repair"` (see `EvalSession.repair`) generates candidates
from the LIVE session env (cljs.core + session defs + program-graph
names), proves them with compile-only trials, and evals only the unique
winner — the Python candidate shim this module once carried is deleted.
What remains here is the CONTENT-CHANNEL half shared by the diffusion
canvas and any AR path: the `; fix:` hint vocabulary and the error-message
parsing that feeds it.
"""

import re

HINT_PREFIX = "; fix:"     # transient marker — stripped before commit
ORIENT_PREFIX = "; slot:"  # orientation line — where the model is + legal
                           # values; measured 0/3→3/3 slot correctness
                           # (typeahead research round 7); stripped like hints


def orient_for(label, candidates=None):
    """One `; slot:` orientation line for a template expansion — tells the
    model WHERE it is and, for closed holes, the legal values. Content
    channel, transient (strip_hints removes it before commit)."""
    parts = []
    if label:
        parts.append(f"completing {label}")
    for hi, cands in sorted((candidates or {}).items()):
        if cands:
            parts.append(f"hole {int(hi) + 1} takes one of: {' '.join(cands)}")
    if not parts:
        return ""
    return f"{ORIENT_PREFIX} {' — '.join(parts)}\n"


def undeclared_var(msg):
    m = re.search(r"undeclared Var (?:[\w.\-]+/)?([^\s;]+)", msg or "")
    return m.group(1) if m else None


def hint_for(err):
    """One `; fix:` comment line for a flagged span — feedback in the
    content channel the model actually reads."""
    kind = err.get("error-kind", "?")
    src = (err.get("source") or "").replace("\n", " ")[:80]
    if kind == "eval":
        var = undeclared_var(src)
        sugg = err.get("suggest")
        if var and sugg:
            return f"{HINT_PREFIX} '{var}' is not defined — did you mean '{sugg}'?\n"
        return f"{HINT_PREFIX} {src}\n"
    hints = {
        "def-vs-defn": "use (defn name [args] body) — def takes no arg vector",
        "phase-violation": "this form's kind is not allowed in the current phase",
        "eof": "the removed form was unfinished/unbalanced — rewrite it completely",
        "unmatched-delimiter": "unbalanced delimiter — rewrite the removed form",
    }
    return f"{HINT_PREFIX} {hints.get(kind, f'{kind} in `{src}`')}\n"


def strip_hints(text):
    return "".join(l for l in text.splitlines(keepends=True)
                   if not (l.lstrip().startswith(HINT_PREFIX)
                           or l.lstrip().startswith(ORIENT_PREFIX)))
