---
type: research
status: active
tags: [research, agent, capability]
---

# BFCL native completion correction — 2026-07-15

## Dependency ledger

- Inspect AI is the vendored `reference-code/inspect-ai/` build identified in
  `src-inspect-ai/pyproject.toml` as `0.1.dev1+g92dd737b9`; the local path is the
  installed dependency selected by `src-inspect-ai/uv.lock`.
- Inspect Evals supplies the maintained BFCL dataset and
  `inspect_evals.bfcl.score.ast_match` scorer. Seon replaces only the generation
  step through `src-inspect-ai/src/seon_inspect/bfcl_adapter.py`.
- `seon.agent.lifecycle/complete` is the existing agent-facing run-result and
  close function. `seon.web.serve` returns its delivered message through the
  existing `POST /agents/run` door.
- The first-party focused proof is
  `src-inspect-ai/tests/test_bfcl_adapter.py`; the live evidence source is the
  exact turn bundle retained by `seon_inspect.solver`.
- Source baseline before the correction was Seon commit
  `7fcd1d457f21c4c3d801bfecf6c2844d0a705c1f`.

## Falsifiable failure

Frozen BFCL sample `multiple_0` asks for triangle properties from sides 5, 4,
and 3. Under Qwen 3.5 2B, the shipped adapter produced four turns, zero evals,
`:no-forms`, and score 0. The first reply was a JSON object with the right
argument values but a home-namespace function identity; the remaining replies
were empty.

The exact prompt showed the cause. Seon's stable system message says bare
replies run nothing and results must use `message/user` or `complete`. The task
then said `Do NOT execute anything` and demanded only bare JSON. A prior form
adapter instead asked the agent to invoke candidate symbols that were absent
from the program graph, guaranteeing undefined-function errors.

Acceptance was therefore: request an existing executable Seon form, preserve
BFCL's JSON values and unchanged scorer, land at least one eval, close
`:completed`, and retain exact evidence whether the selected call scores or
not.

## Result

The adapter now asks for one form:

```clojure
(complete "[{\"name\":\"triangle_properties.get\",\"arguments\":{...}}]")
```

The runtime delivers only the unescaped JSON string to `/agents/run`, so the
existing JSON parser and Inspect `ToolCall` synthesis remain unchanged. The
focused adapter suite passes 15 tests against BFCL's real AST scorer.

The identical live ACME sample with `Qwen/Qwen3.5-2B` then produced:

- one turn and one eval;
- raw reply `(complete "[{...}]")`;
- door completion containing the correct JSON array;
- close reason `:completed` in 6,154 ms; and
- unchanged BFCL score 1.0.

The native log is
`evals/runs/2026-07-15-bfcl-native-complete-qwen-smoke/inspect-logs/
2026-07-15T01-37-01-00-00_bfcl_fxM4rRBVc2UjXZF9dxDVqT.eval`.

The prompt remained roughly 24k tokens. Success in one 41-token reply shows
that context weight did not cause this particular failure. It does not prove
the namespace surface is efficient: that audit must use tasks which actually
select, navigate, and compose real Seon functions.

## Architectural implication

Non-executable function-selection benchmarks need no fake function registry.
They can return structured specifications through the existing lifecycle
result path. Executable third-party integrations may later import external
contracts as real analyzer-backed program facts and capture calls as database
facts, but that mechanism must strengthen the one program graph rather than
create a second tool catalog.
