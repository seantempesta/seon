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
owner-gated. **KT1 FIRED** (envelope-fit, not tokenizer) → owner ruled
EXTEND the context; **extended-context prep RUN**
([[research/extended-context-prep-2026-07-12.md]]): v2 dataset
(`data/tune/acme-2026-07-12-v2.jsonl` — compact cards, next-form
targets, JSON-native `json_tools`/`json_target` via the KT2b layer),
fit tables (2048 holds 100% of rows; card budget +14 @2048 / +55
@4096), and the `enc_rope_scale` position-interpolation scaffold
(`seon_needle.extend`, default byte-parity) with a green 2048 overfit
smoke in both arms — recommendation 2048. **Surface-tuning sweep RUN**
([[research/surface-tuning-sweep-2026-07-12.md]]): 16 needle + 6 Qwen
presentation ablations over the KT2b lint — docstring rewrites transfer
cross-model (+.041/+.048), ns-strip reverses on Qwen (+.152/−.055),
stack-of-real-fn-fixes ≥ facades on both models (no toolkit facades
justified except the kb recall contract gap), compact cards @8 = the
serving default, 16-menu = a discrimination cliff even when it fits.
**Toolkit contract gaps SHIPPED** (`e2e4ce92`): `my.kb/recall` (the
symmetric ask — deterministic token match + `SEON_EMBED` top-up) +
`my.ns/functions` (fn listing through the ONE compact-card mechanism);
the NEXT sweep re-run must include the new cards so the 0/3
aggregation-ask shape is re-measured against a real contract.
**Scorer FN audit RUN**
([[research/scorer-false-negative-audit-2026-07-12.md]]): self-test
214/214 PASS; frontier zeros 40% reasonable-alternatives (plan-idiom
acts on visible ids) vs 0% for small models — KT3's below-band STOP
arithmetic softens (corrected ceiling ~.36–.46), KT3b's "1.5B ties
frontier" is a scorer artifact; fix = mechanical prescribed-act
accept-set + drop 3 junk targets, NO LLM judge inside the metric.
**Plan-preload live-drive pilot RUN**
([[research/plan-preload-drive-2026-07-12.md]]): markdown hand-down ×3
DeepSeek scenarios on an own pinned cluster — authoring variance
t0/t17/never, expect-verified closes when the tree exists,
expect-BLIND wrong fact into `my.kb` (`:verified`) when it doesn't;
first ORGANIC escalation firing (2 authority defects:
`reopen!`-of-verified-done, id-less-root re-mint); `recall` round-trip
across a pod restart; recommendation = pre-transacted vs
markdown-authored vs no-plan ARMS + store-derived metrics (oracle,
plan integrity, prose-rate). Harvest store:
`/Users/sean/src/seon-plan-pilot/data/clusters/acme`.
**LoRA training-data audit RUN**
([[research/lora-data-audit-2026-07-12.md]]): all 557 non-abstain kept
pairs driven through the LIVE turn pipeline on hermetic worlds in the
pin — **26.8% hard-fail** (49% of DeepSeek-authored, 0% of mechanical
gold); root blindspot = bare heads pass name-existence but are
undeclared in an agent's ns (128/149); coverage 15/170 fns via
resolvable heads; kind mix register-heavy 6.5×/query-light 4.6× vs the
mined 214; contexts teach unbalanced echoes (70) + fabricated query
results (45). Two core smells by-catch: quoted-arg undeclared heads
record `ok? true` silently (taints ok-eval mining), `db/query` returns
`#{}` on a request without `:seon.db/query`. A2 gate = the audit
harness (`src-needle/audit/` + `lora_audit_*.py`); any LoRA eval win
must be re-scored for head resolvability before it counts.
Live status: [[roadmap.md]].

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
