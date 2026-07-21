---
type: research
status: active
tags: [research, agent, capability]
---

# Require-edge context and Inspect plan — 2026-07-15

## Question

What is the smallest global change that makes root and ordinary agents see the
functions their namespaces actually grant, removes the rejected
`:seon.fn/agent-facing?` presentation allowlist, and produces a decisive small
model experiment through the maintained Inspect AI path?

## Dependency ledger

- Context selection is the existing database-derived namespace block in
  `seon.agent.ctx.namespaces`.
- Namespace dependencies are the existing component rows at
  `:seon.ns/require-edges`, read through
  `seon.eval/persisted-require-edges`. Each edge already carries its target and
  optional alias or referred symbols.
- Function and schema contracts are the existing `:seon.fn` and
  `:seon.schema` program facts. `compact-fn-head` is the one compact callable
  renderer and `referenced-schema-block` is the one transitive schema closure.
- Root's additive context is the ordinary/downstream home workbench plus
  `[seon.agent :refer [start! delegate! set-purpose!]]`.
- Inspect AI is pinned under `reference-code/inspect-ai/`. Formal Seon-native
  runs go through `seon_inspect.catalog.run_native_task`, which checks selected
  source, static target, and immutable model-server identity at both ends and
  retains the finalized `.eval` artifact.
- The maintained database dependencies remain Datahike
  `9ada755087228e10cfb179fa5779ce227a6ed220`, Konserve
  `b5c99bc02a7175652a610324215288b78551801f`, and Proximum
  `9846d3e79e1aee48474bc876d3d563d7137209c6`.

## Observed contradiction

Commit `3c08c176` correctly gives root one additive context tree and the three
explicit orchestration refers. The root role and fleet canvas are present, and
the combined config/context/render/lifecycle gate passes 106 tests and 530
assertions.

The compact renderer still pulls and filters functions by the optional positive
`:seon.fn/agent-facing?` fact. `set-purpose!` has the correct root require edge
but no marker, so it disappears from root's compact `seon.agent` card. Adding a
marker would repair one symptom while preserving a second capability-selection
system. The same stale fact is also consumed by `my.ns/functions`, menus,
autocomplete projection, and the Inspect reachability oracle.

No live REPL measurement was admitted during this audit: the default MCP
advertisement had no live `root`, and the restore coordinator owned the source
and lifecycle freeze. Absence of a live result is not evidence of correctness.

## One context rule

The namespace edge is both the dependency fact and the presentation selection
fact:

- a `:refer [f g]` edge renders exactly those non-private, schema-complete
  public functions and the referenced-schema closure of their specs;
- an `:as alias` edge renders every non-private, schema-complete public
  function and schema in that required namespace; and
- a compact current namespace renders all of its non-private,
  schema-complete public definitions.

Full current namespace source remains unchanged. Ordinary, root, and ACME use
the same renderer over different persisted require edges. There is no role
registry, function allowlist, benchmark inventory, or root-specific renderer.
Public helpers that prove distracting belong private or in an existing
`*.internal` namespace; model evidence should rank that cleanup rather than a
hidden eligibility bit.

## Source migration

1. Replace the flattened required namespace set with a target-to-edge
   projection. Thread the optional refers set into `render-one-ns-compact` and
   select function rows by ordinary public/private, real function, complete
   spec, and edge semantics.
2. Make `my.ns/functions`, the disabled-by-default menu/typeahead projections,
   and autocomplete export consume the same public row rule. Do not create a
   shared allowlist under a new name.
3. Update the namespace renderer tests to prove that a referred card includes
   exactly its referred functions, an alias card includes additional public
   functions, private functions stay absent, bodies stay elided, and referenced
   schema closure remains complete.
4. Remove `:seon.fn/agent-facing?` from analyzer projection, boot indexing,
   eval tee/reconciliation, schemas, source metadata, and tests after every
   consumer ignores it. Existing databases retract stale facts through the
   existing program reconciliation path.
5. Correct [[../../../seon/architecture/toolkit]] and the stale root lifecycle
   paragraph in [[../../../seon/architecture/agent-runtime]]. They must describe
   namespace requirements plus public contracts, not a curated scalar list or
   eligibility fact.

The first implementation commit should establish edge-selected presentation
and its tests. Mechanical fact deletion may follow as a separate coherent
commit; it must not delay measuring the globally correct renderer.

## Inspect oracle migration

`namespace_reachability` must observe production context rather than recreate a
Python capability registry:

- delete the positive-fact check and fixed root function inventory;
- parse the current home namespace's exact require specs from retained prompt
  evidence;
- require one complete compact function record for every explicit root refer;
- derive discovery symbols from the retained `my.ns/functions` database result,
  excluding only private rows, and compare them with the compact card;
- after namespace movement, require a later full namespace block containing
  the derived public definitions; and
- keep only task-specific outcome assertions such as a successful `grants`,
  `start!`, brand tagline, or widget location update.

Config tests own ordinary/root/ACME require composition. Inspect proves that the
exact retained context reached the model and enabled movement, calls, and the
reported result.

## First experiment matrix

After the restore source checkpoint and sequential default/ACME rebuild:

1. Run one admitted `namespace_discovery` row through `run_native_task` against
   the static ACME target and a dedicated exact-snapshot MLX listener.
2. Start with Qwen2.5 Coder 0.5B, then Qwen 3.5 0.8B, Qwen2.5 Coder 1.5B,
   Qwen 3.5 2B, and Qwen2.5 Coder 3B. Hold temperature, token budget, and
   thinking mode equal.
3. Reopen the finalized native log and admit a result only when source,
   static-target, and model-server identities agree at start and end.
4. Run `skill_lifecycle`, root orchestration, and ACME product rows one at a
   time only after discovery produces usable evidence.
5. Then run one frozen `shell_use`, `file_edit`, and `web_fetch` row each. These
   score actual isolated workspace/fixture outcomes and provide better tool
   composition evidence than BFCL alone.
6. Use BFCL only for structured selection/parallel-call signal. Keep the
   existing native `complete` bridge and unchanged AST scorer.

Direct `inspect eval` is not formal Seon evidence. Ollama is diagnostic until
its loaded model digest is provable. Remote Muse and DeepSeek are sanity checks,
not immutable local-model baselines.

## Missing experiments

- No maintained native Inspect task yet compares the same task under batch and
  stream execution while scoring several independent forms, narration repair,
  malformed delimiters, next-turn result visibility, and fabricated result
  echoes. Build this as an Inspect Task after namespace discovery, reusing the
  retained eval/turn evidence and the existing typeahead corpus rather than
  creating a runner.
- The long-term planning scorer and three offline arms are ready, but live
  database outcome, plan provenance/history, close expectation, report, and
  address-observation adapters remain missing. Restart-resumption trials also
  require the operator lease.
- No representative frozen suite has reached the roadmap's ninety-percent
  graduation threshold. A single model failure must not drive standing prose.

## Downstream root navigation

Root management functions already exist. The missing browser contract is the
database provenance chain:

```text
tab session -> human message -> exact turn cause -> context-only injection
            -> root selection -> existing feed redirect

```

That belongs to [[../../root-workspace-sessions/roadmap]] after the reactive
render-unit prerequisite. It stores only session identity, user, normalized
location, message session ref, and turn cause-message ref. It must extend the
existing router and feed registry, not introduce a navigation command queue or
global selected-agent fact.

## Acceptance evidence

- Focused CLJS context/config/index/eval projections pass with no marker
  consumer remaining.
- Exact root context contains `start!`, `delegate!`, and `set-purpose!` through
  the persisted refer edge; an ordinary agent has no `seon.agent` card.
- Alias and refer cards have deterministic byte output and complete referenced
  schemas over one immutable database value.
- The migrated Python reachability selector passes its falsifiers and one
  formally admitted `namespace_discovery` run retains matching identities and
  scored context/call evidence.
- Default and ACME simultaneously serve their pages and gzip feeds from the
  same selected source and maintained dependency coordinates.
