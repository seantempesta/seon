"""Model-agnostic form repair + hint rendering — NO mlx/torch imports.

This is the reusable half of the guided loop: it operates on TEXT plus the
co-located oracles (bb parse/lint/phase, stateful eval session), so the
SAME machinery serves the diffusion canvas, an autoregressive provider's
reply, or a post-hoc scorer. The diffusion-specific canvas mechanics
(clamp/scramble/harvest) live in control.py.

Repair doctrine (owner, 2026-07-05): if a fix is PROVABLE, apply it for
$0 model tokens — substitute the fuzzy-matched candidate and let the eval
sandbox prove it (undeclared-var and fn-arity surface as compile errors).
Scramble/regenerate is the fallback, never the first move.

SHIM NOTICE — candidate generation here (difflib over a demunged core-name
list) is a Phase-1 stopgap that DUPLICATES seon's retrieval intelligence.
Phase 2 moves it oracle-side: `seon.worker-eval` gains op:"repair"
(candidates from the LIVE session env — cljs.core + session defs —
substitution + proof in ONE call), and the pod retrieval leg
(`seon.diffusion.retrieval`, Levenshtein + program graph) supplies
project-fn candidates. `try_repair`/`suggest_candidates`/`core_names`
delete then; only orchestration stays Python-side.
"""

import difflib
import re

HINT_PREFIX = "; fix:"    # transient marker — stripped before commit
MAX_REPAIRS_PER_FORM = 3  # distinct undeclared vars fixed per form
# a symbol boundary in Clojure — chars that may ADJOIN a symbol without
# being part of it are everything NOT in this class
_SYM_CHAR = r"[\w?!*+<>=./-]"


def undeclared_var(msg):
    m = re.search(r"undeclared Var (?:[\w.\-]+/)?([^\s;]+)", msg or "")
    return m.group(1) if m else None


def suggest_candidates(eval_session, var, n=3):
    return difflib.get_close_matches(var, eval_session.core_names(),
                                     n=n, cutoff=0.75)


def try_repair(src, first_error_msg, eval_session):
    """Deterministic near-miss repair: substitute fuzzy-matched candidates
    for undeclared vars; the eval sandbox PROVES each candidate. Returns
    (fixed_src, [(from, to), …]) or None."""
    msg = first_error_msg
    fixes = []
    for _ in range(MAX_REPAIRS_PER_FORM):
        var = undeclared_var(msg)
        if not var:
            return None
        for cand in suggest_candidates(eval_session, var):
            candidate_src = re.sub(
                rf"(?<!{_SYM_CHAR}){re.escape(var)}(?!{_SYM_CHAR})",
                cand, src)
            if candidate_src == src:
                continue
            ev = eval_session.eval(candidate_src)
            if ev.get("ok"):
                return candidate_src, fixes + [(var, cand)]
            new_msg = (ev.get("error") or {}).get("message", "")
            nxt = undeclared_var(new_msg)
            if nxt and nxt != var:        # this fix held; another var remains
                src, msg = candidate_src, new_msg
                fixes.append((var, cand))
                break
        else:
            return None
    return None


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
                   if not l.lstrip().startswith(HINT_PREFIX))
