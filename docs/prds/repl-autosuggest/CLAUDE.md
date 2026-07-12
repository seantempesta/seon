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
1.82× token cost). KT0 fired: only ~224 minable turns (all acme) —
data recipe inverted to synthetic/gold-primary. A1
(`seon.repl.autocomplete` profile + exporter) and the verbs-rename /
deprecated-deletion cleanup in flight. Next: KT1-KT4 kill tests before
any training spend. Live status: [[roadmap.md]].

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
- Shared branch: commit with explicit pathspecs only
  (`docs/prds/repl-autosuggest/`, `src-needle/`,
  `src/seon/repl/autocomplete*`, `src/my/plan*`).

## Entry points

- [[design.md]] — the contract. [[roadmap.md]] — we-are-here.
- [[research/design-review-2026-07-12.md]] — the 9-agent review
  (readers' file:line evidence, judges, kill thresholds).
- `docs/prds/diffusion-dynamic-context/planner-worker-design.md` — the
  P7 planner/worker design the plan targets build on.
