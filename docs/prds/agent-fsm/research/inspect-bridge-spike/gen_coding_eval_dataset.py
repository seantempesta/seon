"""Generator for the coding/eval axis dataset (held-out, regeneratable).

Each sample gives a crisp spec for a SMALL pure function (name, arg shape, 2-3
input->output examples) and asks the agent to define it in one of its own my.*
namespaces WITH a correct :malli/schema, then call it on a HOLD-OUT probe input
and report the probe result as the final answer. The probe answer is NOT in the
examples, so copying an example can't pass — the agent must run its own code.

The :malli/schema requirement means the fn must survive the pod's always-on
instrumentation to produce ANY output — a wrong schema throws, no probe result,
miss. So the axis scores schema-first coding end to end.

Four task families x 3 variants = 12 samples. Each family is a pure fn a weak
model can plausibly write with only core Clojure (no library knowledge).

Run:  python gen_coding_eval_dataset.py   # rewrites coding_eval_dataset.jsonl
"""

from __future__ import annotations

import json
import os

# Each entry: (family-id, spec-sentence, examples[(in,out)], probe-in, probe-out).
# probe_out is the host-side target (a unique short token / integer NOT among the
# example outputs).
FAMILIES = [
    # --- vowel count (string -> int) ---
    ("vowels",
     "returns the number of vowels (a e i o u) in a lowercase string",
     [("hello", 2), ("sky", 0), ("aeiou", 5)],
     [("benchmark", 3), ("clojure", 3), ("rhythm", 0)]),
    # --- sum of a vector of ints (vector -> int) ---
    ("sumvec",
     "returns the sum of a vector of integers",
     [([1, 2, 3], 6), ([], 0), ([10, 5], 15)],
     [([7, 8, 9], 24), ([100, 1, 1], 102), ([4, 4, 4, 4], 16)]),
    # --- count words (string -> int) ---
    ("wordcount",
     "returns the number of whitespace-separated words in a string",
     [("one two", 2), ("solo", 1), ("a b c d", 4)],
     [("the quick brown fox jumps", 5), ("just three words here", 4), ("x", 1)]),
    # --- longest string in a vector (vector of strings -> the longest string) ---
    ("longest",
     "returns the longest string in a non-empty vector of strings (first on ties)",
     [(["a", "bbb", "cc"], "bbb"), (["one"], "one"), (["xx", "yy"], "xx")],
     [(["cat", "elephant", "dog"], "elephant"),
      (["red", "green", "blue"], "green"),
      (["alpha", "beta", "gamma", "delta"], "alpha")]),
]


def _fn_name(family: str) -> str:
    return {"vowels": "count-vowels", "sumvec": "sum-ints",
            "wordcount": "count-words", "longest": "longest-string"}[family]


def _fmt(v) -> str:
    """Render an example value as Clojure-ish literal for the prompt."""
    if isinstance(v, str):
        return f'"{v}"'
    if isinstance(v, list):
        inner = " ".join(_fmt(x) for x in v)
        return f"[{inner}]"
    return str(v)


def _sample(family: str, spec: str, examples, probe_in, probe_out, variant: int) -> dict:
    fn = _fn_name(family)
    ex_txt = "; ".join(f"{_fmt(i)} -> {_fmt(o)}" for i, o in examples)
    prompt = (
        "You are being tested on writing a small, correct, SCHEMA'D function. "
        f"Define a function `{fn}` in one of your own namespaces that {spec}. "
        "Give it a correct :malli/schema so it passes instrumentation. "
        f"Examples: {ex_txt}. "
        f"Then CALL your function on this input: {_fmt(probe_in)} — and report the "
        "single result value it returns as your final answer, then complete."
    )
    return {"id": f"{family}-{variant}", "input": prompt, "target": str(probe_out)}


def main() -> None:
    out = os.path.join(os.path.dirname(__file__), "coding_eval_dataset.jsonl")
    n = 0
    with open(out, "w") as f:
        for family, spec, examples, probes in FAMILIES:
            for variant, (pin, pout) in enumerate(probes):
                f.write(json.dumps(_sample(family, spec, examples, pin, pout, variant)) + "\n")
                n += 1
    print(f"wrote {n} samples -> {out}")


if __name__ == "__main__":
    main()
