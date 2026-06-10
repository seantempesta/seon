---
type: prd
status: draft
tags: [prd, agent]
---

# Context focus redesign — full exemplar source over exhaustive catalogs (2026-06-10)

User direction (verbatim intent): "Right now we are including every function
and schema and it's overwhelming and not detailed. I want to move towards
more clojure code — decide on core namespaces we want included, by default
include all child namespaces in the indexing, and give FULL clojure code for
them so the agent has many good examples of writing schemas, functions, and
tests."

This is a design spec — no implementation. It decides the core namespace
set, the inclusion mechanism, the budget split, what shrinks/dies, and an
orderable migration plan. Baseline facts come from the parent PRD
(`cljs-finish-clj-pivot-plan-2026-06-09.md` §3), the context audit
(`research/context-audit-2026-06-09.md`), a real rendered prompt
(`logs/prompts/ZVS-2606101025/kgT-2606101035.txt`), and the live section
code in `src/seon/agent.cljs` / `src/seon/client.cljs` (read 2026-06-10).

## 0. TL;DR — the recommended design

- **Exemplar roots: `seon.search` + `seon.fs`, plus their test siblings
  (today that is `seon.search-test`).** Their FULL file source renders into
  every prompt as a new `:exemplars` section — 44,756 measured chars
  (~11.2k tokens), byte-stable within a pod run.
- **Mechanism: real full-file text persisted on `:seon.ns/source` for the
  root set at boot index; the section renders that one attr verbatim from
  the program graph.** No re-reading files at render time, no reconstituting
  from clipped per-fn blocks.
- **The exhaustive substrate `:functions-catalog` collapses to a thin
  per-ns count index** (~5.8k → ~1.5k chars). Agent-authored fns and the
  domain-attrs / stored-findings blocks stay fully salient (PRD §3
  verdicts; #26).
- New turn-0 total ≈ **59k chars (~14.8k tokens)**, up from 18.5k chars
  (~4.6k tokens); the byte-stable cache prefix grows from ~14k to ~57k
  chars, so the marginal per-turn cost after turn 0 is mostly cache-hit
  tokens.

## 1. Why these namespaces — the core set

Criterion: the best in-conventions examples of the three things agents must
author — **schemas** (`register!` shapes), **functions** (map-in/map-out
with `:malli/schema`, errors-as-values envelopes), and **tests** (`deftest`
with fixtures + envelope assertions). Secondary criteria: written at the
agent's altitude (domain-style data code, not compiler internals), already
loaded in the pod build (indexable at boot), and small enough to include
whole.

Measured candidates (chars, on-disk 2026-06-10):

| Namespace | Chars | Verdict |
|---|---:|---|
| `seon.search` | 15,438 | **IN.** Self-described "THE EXEMPLAR npm-package wrapper" — wrapper doctrine in the ns docstring, 17 `register!` calls, map-in/map-out request/response schemas, error envelopes with guiding messages, capability gating, async/await. The single best teaching file in the build. |
| `seon.fs` | 18,143 | **IN.** The agent's most-used API; 23 `register!` calls, config map pattern, allowlist/default-deny envelopes, sync + async fns. Complements search without duplicating it (search shows wrapping a process; fs shows owning stateful config). |
| `seon.search-test` | 11,175 | **IN** (the model test ns). 11 deftests: runtime fixtures under `tmp/`, save/restore of live config, async `deftest`, envelope-contract assertions, `testing` blocks. Pairs with an included source ns, so the agent reads contract and proof together. |
| `seon.db` | 80,621 | **OUT as full source** — 4.3× the entire current turn-0. Stays taught via capabilities worked examples + the schema-catalog; its key fns remain `:seon.fn` rows one pull away. |
| `seon.schema` | 18,968 | **OUT.** It is the registry *mechanism*, not a usage exemplar; agents must call `register!`, not reimplement it. Usage sites are already modeled in capabilities and in both included nses. |
| `seon.test.runner` | 35,222 | **OUT** (size; runner internals are not what agents write). `run!` stays in the curated fn index. |
| `seon.handlers.message` / `seon.render.default` | 5,402 / 9,100 | **DEFERRED** — tile/render exemplars are a candidate second tier once the gym shows tile-authoring is the bottleneck. |

Total exemplar payload: **44,756 chars ≈ 11.2k tokens.**

Lean fallback (if the user rejects the budget in §3): `seon.search` +
`seon.search-test` only = 26,613 chars; `seon.fs` drops to the thin index.

### Child-namespace rule

The configuration is a small set of ROOT symbols, not a file list. An
indexed ns is included iff:

- its name equals a root, or starts with `<root>.` (children by default —
  the user's requirement; e.g. a future `seon.search.index` would ride
  along automatically), or
- it is the TEST SIBLING of an included ns: `<included-ns>-test` or
  `<included-ns>.<child>-test` (test nses are not name-children —
  `seon.search-test` does not start with `seon.search.` — so the sibling
  rule is explicit).

The root set lives in code as a def (`exemplar-roots` in `seon.client`,
next to `curated-substrate-vars` — same lifecycle: changes ship with the
build that contains the source). A DB-resident override is deliberately NOT
in v1 (see open question 3).

## 2. Mechanism — full source from the program graph

### What exists today (read, not guessed)

- `index-substrate!` (`client.cljs:982`) builds `:seon.fn` rows by reading
  the REAL source file at each var's `:file`/`:line`
  (`read-src-file` + `extract-form-at-line`) — but only for **specced
  public fns + a curated list** (102 fns). Private helpers, bare `def`s,
  and inter-form comments are not in the graph.
- `:seon.ns/source` for substrate nses is a **stub** — `"(ns seon.search)"`
  (`client.cljs:1018`) — kept minimal when the replay path still re-evaled
  ns sources. The Step-4 replay discriminator has since landed:
  `query-program-graph-entries` SKIPS any entry whose owning ns is in
  `(substrate-ns-set)` (`client.cljs:560,590,642`), so substrate ns sources
  are never re-evaled. The stub is now just an un-upgraded leftover; its
  own docstring says "when that lands this can carry real ns source".
- `render-namespace` / `render-one-ns-ai` (`agent.cljs:2181`) reconstitute
  a ns from per-member rows — sorted by sym (not declaration order), source
  clipped at 240 chars/fn, schemas at 200, and only graph-indexed members.
  Good for the agent's own evolving ns; structurally WRONG for exemplars
  (loses ordering, comments, privates, and all bodies over 240 chars).

### The design

**One indexing change + one new section. No render-time file reads.**

1. **Boot index upgrade (`client.cljs`):** for each ns matched by the
   exemplar root rule, persist the REAL FULL FILE TEXT as
   `:seon.ns/source` (via the existing `read-src-file`, which already
   probes `src` / `test` / `guest-cljs/src`). All other substrate nses keep
   the `(ns x)` stub. Safe because substrate rows are replay-skipped
   (verified above — but unit E1 re-proves it live before relying on it).
   Per-fn `:seon.fn` rows are unchanged; the rendered exemplar is the file
   text, not reconstituted blocks. This keeps the code-as-data invariant:
   the boot indexer is the ONE file-reader; everything downstream —
   sections, `(source x)` shims, the inspector — reads the graph.
2. **New section fn `exemplars-section` (`agent.cljs`):** pulls
   `:seon.ns/source` for each root-set ns (dependency order: `seon.fs`
   before `seon.search` since search requires fs; tests after their
   subject), wraps each in `<exemplar ns="…">…</exemplar>`, with a short
   fixed header:

   > These complete namespaces are THE models for code you write: this is
   > what a finished schema set, a specced map-in/map-out fn, an error
   > envelope, and a test suite look like here. Copy the SHAPE — register!
   > shapes, `::request`/`::response` pairs, `:malli/schema` on every
   > public fn, errors as values, deftest + fixture + envelope assertions.
   > These fns already exist — call them; never re-define them.

   A root whose `:seon.ns/source` is missing or still a stub renders
   nothing for that ns and logs fail-loud (`seon.log/error!`) — never
   throws, never silently pads with the stub.
3. **Layout position — priority 22**, between `:capabilities` (20) and
   `:schema-catalog` (25) in `substrate-default-ctx`. Rationale: both
   system and capabilities and the exemplars are fully byte-stable; the
   catalogs are only semi-static (fuzzy counts move on corpus growth).
   Static-before-semi-static maximizes the cache prefix (§3 of the parent
   PRD; audit §4).

### What replaces the exhaustive catalogs

- **`:functions-catalog` → a thin index.** Today it renders one-line
  callable signatures for every small substrate ns (handlers.*, inspect,
  render.*, log, …) — 5,848 chars in the sample prompt, mostly fns agents
  never call. New shape: the existing teach-the-query header + ONE count
  line per substrate ns (the existing `fn-catalog-summary-line`, applied to
  ALL substrate nses regardless of size) + agent-authored nses at the
  current brief/full depth. Exemplar-root nses get a cross-reference suffix
  ("— full source above"). The curated capability fns are already taught in
  `:capabilities`; everything else is one `:seon.fn` pull away, which the
  header teaches. Estimated: ~1.5k chars.
- **Own-ns full source moves OUT of functions-catalog.** Today the agent's
  own ns renders full source in BOTH `:functions-catalog` and
  `:namespace-context` (duplicate mechanism — code smell, flagged). Keep it
  in `:namespace-context` (the deep current-ns view, which also carries
  schemas + tests); functions-catalog drops the own-ns special case.
- **`:schema-catalog` stays.** It answers a different question (what DATA
  exists — entity kinds, domain attrs, stored findings) and is the #26
  reuse/consult surface; the PRD §3 verdict keeps it. One trim: the
  "all registered schemas, by namespace" index lines for exemplar-root nses
  collapse into the cross-reference ("shown in full above").
- **`:capabilities` stays intact for now.** The audit calls it the best
  section and #26 is actively rewriting it; trimming it in the same window
  guarantees collisions. Revisit after run 8: the `listen!` and tile blocks
  are the trim candidates (~1.2k) once exemplars prove they carry pattern
  teaching.

## 3. Budget math (measured, not estimated)

Current real prompt (`kgT-2606101035.txt`, 23,309 chars total at turn 4):

| Section | Chars today | Proposed | Delta |
|---|---:|---:|---:|
| `:system` | 1,779 | 1,779 | 0 |
| `:capabilities` | 7,337 | 7,337 | 0 (post-#26 shape) |
| `:exemplars` (new) | — | ~45,200 | +45,200 (44,756 source + ~450 header/tags) |
| `:schema-catalog` | 3,578 | ~3,400 | −180 |
| `:functions-catalog` | 5,848 | ~1,500 | −4,350 |
| `:namespace-context` | 253 | 253 | 0 (grows with own ns, as today) |
| `:warnings` | 0 (clean) | 0 | 0 |
| `:transcript` | dynamic, 24k budget | unchanged | 0 |
| `:prompt` | ~250 | ~250 | 0 |
| **Turn-0 total** | **~18.5k (~4.6k tok)** | **~59.4k (~14.9k tok)** | **+40.9k chars** |

Cache analysis:

- The exemplar source is **byte-stable for the life of a pod process** —
  it can only change when the build changes, which restarts the pod and
  cold-starts the cache anyway. It belongs inside the stable prefix.
- New stable-prefix boundary: system (1.8k) + capabilities (7.3k) +
  exemplars (45.2k) ≈ **57k chars (~14.2k tokens) cached**, vs ~14k chars
  today. First divergence stays where it is today: the semi-static catalog
  counts, then the per-turn tail.
- Worst-case spend: a 20-turn cap run ≈ 20 × ~15k input tokens ≈ 300k
  input tokens, the bulk at cache-hit pricing on DeepSeek. Tolerable for
  the demo loop; the transcript (24k-char budget) remains the real
  run-scale lever, untouched here.
- Lean fallback (search + test only, no fs): turn-0 ≈ 41k chars
  (~10.2k tok).

The honest cost is **attention, not dollars**: ~3× more tokens before the
transcript. That is exactly the trade the user is asking for — fewer
mechanisms explained at full depth instead of 102 signatures at zero depth
— and the agent-gym (§5, U4) is the instrument that tells us whether
DeepSeek's behavior improves or degrades. This spec treats the gym
scorecard, not intuition, as the accept/revert gate.

## 4. Detail vs overwhelm — what shrinks or dies

| Surface | Fate |
|---|---|
| Substrate per-fn one-liner blocks in `:functions-catalog` (handlers.*, inspect, render.*, log, store.wire, web.serve, …) | **DIE** — replaced by per-ns count lines; bodies stay one pull away. |
| Own-ns full source in `:functions-catalog` | **DIES there** — lives only in `:namespace-context` (kills today's duplication). |
| "all registered schemas, by namespace" lines for exemplar nses | **SHRINK** to a cross-reference. |
| `seon.fs` / `seon.search` signature lines in `:functions-catalog` | **DIE** — full source above supersedes. |
| `:capabilities` worked examples | **KEEP** (usage teaching ≠ authorship teaching); trim candidates noted for post-run-8. |
| Schema-catalog kind blocks, domain-attrs, stored-findings | **KEEP** — the #26 consult/reuse surface. |
| Warnings, transcript, prompt | **UNCHANGED.** |

Net non-exemplar context SHRINKS by ~4.5k chars; everything cut remains
reachable through taught queries.

## 5. Migration plan — orderable units, ≤7 files each

Sequencing constraint: **#26 (finding-salience + instruction-clarity) is
landing in `agent.cljs` now.** Units E2/E3 edit the same file — they start
only after #26 is committed. E1 touches `client.cljs` and can run in
parallel with #26.

### E1 — exemplar indexing (full `:seon.ns/source` for roots)

- Files: `src/seon/client.cljs` (add `exemplar-roots` + root-match fn;
  `index-substrate!` ns-rows carry full file text for matched roots),
  `test/seon/index_substrate_test.cljs`, `test/seon/resume_replay_test.cljs`
  (replay-skip guard for a full-source substrate ns).
- Pass predicates (live, not just tests):
  `(:seon.ns/source (seon.db/entity {:seon.db/ref [:seon.ns/name :seon.search]}))`
  on the live pod equals the on-disk file bytes; Nth-boot
  `substrate-index-tx` still returns `[]` (dedup holds — note: identity
  upsert means the FIRST boot after this change rewrites the stub row;
  verify exactly one `:seon.ns/source` assertion lands); pod restart on the
  cluster store replays zero exemplar forms; `bin/test-cljs` green.

### E2 — `exemplars-section` + layout entry

- Files: `src/seon/agent.cljs` (new section fn + `substrate-default-ctx`
  entry at priority 22), `test/seon/agent_context_test.cljs`.
- Note: agents persist in the durable store and carry a STORED
  `:seon.agent/ctx` that overrides `substrate-default-ctx` — existing
  agents need `reset-ctx!` (or the unit decides boot re-arms layouts);
  state the choice in the implementation.
- Pass predicates: a fresh agent's turn-0 render contains
  `(ns seon.search` and at least one full `deftest` body; two consecutive
  renders of the static prefix are byte-identical; turn-0 total ≤ 65k chars
  (budget guard asserted in the context test); a missing/stub root logs
  fail-loud and renders nothing for that ns.

### E3 — catalog slimming

- Files: `src/seon/agent.cljs` (`functions-catalog-section` → thin index,
  drop own-ns special case; `schema-ns-summary-block` cross-reference),
  `test/seon/agent_context_test.cljs`.
- Pass predicates: turn-0 `<functions>` ≤ 2k chars; an agent-authored fn
  still appears after `defn` (live detect-and-tee check); own-ns source
  still renders exactly once per prompt (in `:namespace-context`).

### E4 — run 9 + agent-gym scenario (the accept gate)

- Files: one scenario EDN under the gym harness (PRD §7.12) + scorecard
  notes in `research/`.
- Scenario: the prompt-thin spots the parent PRD names — **writes-tests**
  (never well-taught) and **reuses-FUNCTIONS** (never yet observed) — plus
  the existing rubric axes. Ask a fresh agent to build a small domain
  (schemas + fns + tests) and a second agent to extend it.
- Pass predicates (datalog over the post-run store + transcript): agent's
  `register!` attrs are multi-segment; its public `defn`s carry
  `:malli/schema`; ≥1 `deftest` written AND run via
  `seon.test.runner/run!` with a recorded pass; agent #2 calls (not
  re-defines) an agent-#1 fn; zero convention warnings on the agent's ns at
  end-of-run. Compare against a pre-change scorecard on the same scenario —
  **if the scorecard regresses, revert E2/E3's layout flip (one-line ctx
  change), keep E1.**

## 6. Alternatives considered (brief)

- **Reconstitute exemplars from per-fn `:seon.fn` rows** (extend
  `render-namespace` with a `:full` detail level): rejected — loses
  declaration order (no line attr today), inter-form comments (much of
  search's teaching value), private helpers, and bare `def`s; fixing all
  four re-implements "the file" badly. One attr carrying the real text is
  the smaller mechanism.
- **Excerpted seon.db** (just its register!/validation block): rejected for
  v1 — excerpting needs hand-curated ranges that drift; seon.db patterns
  are already modeled in capabilities and both exemplar nses.
- **DB-resident exemplar config** (sticky-style entity, runtime-editable):
  deferred — the root set's lifecycle is the build's lifecycle (the source
  ships with the build); a DB knob invites drift between the store and the
  loaded code. Revisit if per-cluster exemplar tailoring becomes real.
- **Dropping `:capabilities` in favor of exemplars**: rejected — usage
  worked-examples (how to CALL) and authorship exemplars (how to WRITE) are
  different lessons; run evidence says agents copy the example in front of
  them, so the call-shape examples stay in front.

## 7. Open questions for the user

1. **Budget ceiling.** ~59k chars (~15k tok) per turn before transcript,
   ~3× today, mostly cache-hit after turn 0. Accept, or start at the lean
   tier (search + test, ~41k) and add `seon.fs` only if the gym shows fs
   misuse?
2. **Does `seon.fs` earn full inclusion**, given search demonstrates nearly
   every pattern and fs adds 18k chars largely for the config-map +
   allowlist patterns? (Recommended: yes — it is the agent's most-called
   API; but it is the first cut if attention degrades.)
3. **Tile/render exemplar tier**: should `seon.handlers.message` or
   `seon.render.default` (+5–9k) join the set now to push the
   agents-update-their-own-tiles lane (§6 of the parent PRD), or wait for
   gym evidence that tile authorship is the bottleneck?
