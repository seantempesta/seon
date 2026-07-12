---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# repl-autosuggest — PRD index

**Current state (2026-07-12):** design reviewed adversarially and
re-founded ([[research/design-review-2026-07-12.md]] — verdict
right-track-with-changes, changes folded into [[design.md]]). B1 MLX
port SHIPPED (`5481ab36`: parity 20/20, ~0.25s/suggestion, Clojure
1.82× token cost). **A1 SHIPPED** (`af67b188`):
`seon.repl.autocomplete` — `context` through the ONE producer
(`render-context` + the `:autocomplete` profile, config→DB
`:seon.config/context-profiles`), `rate!` curation, `export!` →
`data/tune/acme-2026-07-12.jsonl` (214 rows, **0 determinism
mismatches**, contexts ≤678 tok, coverage mean .64). Context generation
is OWNER-FROZEN: profiles = selection + caps only; default prompt path
byte-parity. Verbs-rename / deprecated-deletion cleanup in flight.
**KT2b RUN** ([[research/kt2b-legibility-lint-2026-07-12.md]]): stock
checkpoint on the fn index in needle-home JSON — 0.283 @8-tool menus vs
BFCL anchor 0.65 (weak band, informs not kills); leaderboard v0 +
per-fn attributions shipped; arg copy 0.73; false-suggestion 0.25;
16-tool menus bust the 1024 envelope. Fix list = docstrings/schemas,
owner-gated. Next: KT1/KT3-KT4 before any training spend. Live status:
[[roadmap.md]].

## Settled — do NOT re-litigate

- **Byte-exact, ONE renderer**: encoder input =
  `seon.repl.autocomplete/context`, a thin wrapper over
  `seon.agent.ctx/render-context` with the optional
  `:seon.agent.ctx/profile` request key; profiles stored config→DB
  (`:seon.config/context-profiles`); absent profile ⇒ byte-parity.
  Prompt blobs = debug ground truth, never training input. Stale
  exports re-derived, never patched.
- **Scope**: serving north star = every turn, every domain; v0 model
  contract = copy-heavy whole-form kinds (plan/transact/register);
  query `:where` graphs + defn bodies = bounded continuations or
  explicit v0 exclusion.
- **Plan targets** (existing `my.plan` surface ONLY): empty-tree
  authoring → `reconcile!`+markdown heredoc; non-empty trees → shape
  selection + resolved deltas with live ids; loose-markdown mechanical
  compilation as gold labels is BANNED (structured-markdown parses +
  reconcile-oracle filtering only).
- **Data recipe**: synthetic/gold PRIMARY (staged real db states,
  real profile renders); the ~224 mined turns are the held-out eval
  set, never training data.
- **Contrastive head is NOT the retriever** (pretrained weights are
  zero; B1-verified). v0 cards = deterministic selection + existing
  `seon.embed` top-up. A persisted needle-embedding index needs an
  owner ruling.
- **Model roles**: planner = derived predicate
  (`seon.ai/frontier-provider?`); worker = `:seon.ai/agent-provider`
  overlay datoms; autocomplete = `:seon.config/suggest` config section
  + `SEON_NEEDLE_ENDPOINT`. Needle in `provider-locality` or on
  `SEON_DG_ENDPOINT` is FORBIDDEN.
- **Serving**: call-once-then-derive (pre-render `/runsync`); ONE
  capture dial (owner, 2026-07-12) gates prompt/reply/suggestion blobs
  — dev/acme on (row+blob, flywheel pairs), prod off (volatile-tier
  ride, no storage); `rendered-as-of` always-on; `:suggest` block
  reads row-else-stash. Volatile-segment priority; src-diffusion wire
  contract with `mode:"suggest"`; shared submit/poll transport
  extracted from `seon.ai.diffusiongemma`. Second consumer: the
  typeahead offer channel.
- **Kill-test ladder before training spend** (KT1 tokenizer → KT2 copy
  fidelity → KT3 signal ceiling → KT4 channel uptake → KT5
  reachability → $0-baseline ship gate). Envelope 1024/512; no
  retokenize fallback exists (tied embeddings).
- **Context generation is FROZEN to this lane** (owner, 2026-07-12):
  the profile = block selection + caps ONLY, never content rewrites;
  any context-generation change (wording, sections, block content) is
  owner-gated — report gaps with evidence, do not fix. The context
  must hold the INGREDIENTS (the model assembles, it doesn't
  remember): ingredients-coverage is a mechanical dataset gate
  (KT2.5) and coverage misses are context-gap reports. Code over
  prose: cards/suggestions surface real repo code, not instructions.
- Vocabulary: functions, never "verbs" — code surfaces renamed
  2026-07-12.

## Runbook

- Needle source: `reference-code/needle/`; MLX port: `src-needle/`
  (uv; `uv venv && uv pip install -e ".[test]"`; tests `pytest`).
- Held-out eval turns live in the acme store — acme pod:
  `bin/acme build && bin/acme start wire-server && bin/acme start pod`
  (7980/7981).
- **PINNED HARNESS (owner, 2026-07-12 — refactor churn in the main
  tree is expected):** `/Users/sean/src/seon-pin` is a git worktree
  detached at the pinned sha (currently `93c8d8ad`, post-cleanup,
  suite-green 1229/5609). Run this lane's builds, suites, drills, and
  acme from THE PIN, not the main tree — `seon-pin/bin/acme …` is
  fully self-contained (own bundle/store/ports/logs). Bump the pin
  deliberately when a needed commit lands:
  `git -C /Users/sean/src/seon-pin checkout <sha>` + rebuild. The
  main-tree acme store (`seon/data/clusters/acme`) holds the 224
  mined turns — copy it into the pin's `data/clusters/acme` if a
  pinned run needs the historical turns (single-writer: never run two
  wire-servers on one store).
- Shared branch: commit with explicit pathspecs only
  (`docs/prds/repl-autosuggest/`, `src-needle/`,
  `src/seon/repl/autocomplete*`, `src/my/plan*`).

## Entry points

- [[design.md]] — the contract. [[roadmap.md]] — we-are-here.
- [[research/design-review-2026-07-12.md]] — the 9-agent review
  (readers' file:line evidence, judges, kill thresholds).
- `docs/prds/diffusion-dynamic-context/planner-worker-design.md` — the
  P7 planner/worker design the plan targets build on.
