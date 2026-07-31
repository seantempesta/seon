---
type: prd
status: active
tags: [prd, render, context, agent, database, schema]
---

# Context/render data-model spec — 2026-07-31

The authored contract for the one context/render system: agent context and
the HTML page both derive from a recursive walk over the agent's entity,
every unit is a block, and nothing renders outside it. Grounded in the seven
2026-07-31 research reports (`research/context-walk-synthesis`,
`render-current-state`, `render-invalidation-caching`,
`agent-entity-graph-audit`, `agent-startup-audit`,
`context-render-retirement`, `render-scheduling-design`) and the four
"Ruling 2026-07-31" batches in `plan/README.md`. Status: DRAFT for
falsification — adversarial REPL lanes run against this before sealing
(ruling #4(5)).

## 1. Ruled invariants this spec implements

1. Block = the one render unit, both projections; no static scaffold path.
2. Walk = discovery over schema'd data; resolution: value's explicit render
   keys → same-schema fn in a governing namespace (viewer's, then owner's)
   → schema-attached default → structural floor. Slot redirect retired.
3. Invalidation = Datahike dependency plans + per-attribute revision
   counters; each render self-checks in O(deps). Never `d/entity`, never
   wildcard pull, on any render path.
4. Ordering = last-change transaction basis, per render, over the render's
   own read set. No pins, no bands, no hysteresis in v1; page and context
   use the same order.
5. Instructions are explicit datoms, mutated in place; cluster owns the
   authoritative set.
6. Agent-authored renderers execute ONLY through the sci door
   (`:interrupt-fn` + `time-limit` + output caps). Flow's
   `compute-timeout-ms` reports and cancels nothing.
7. Scheduling is global through flow. Render procs pin `:io` (Datahike
   lazy-index reads can block — scheduling report C1); sci renderer evals
   go through the bounded launcher's permits.

## 2. Schema changes

### 2.1 New family — `:seon.cluster.instruction`

```clojure
;; resources/seon/schema/instruction.edn  [TARGET]
{:seon.cluster.instruction/id   [:keyword {:seon.db/identity true}]
 :seon.cluster.instruction/text [:string {:min 1}]

 :seon.cluster.instruction/instruction
 [:map {:seon.db/entity true
        :seon.render/ai   seon.cluster.instruction/instruction-ai
        :seon.render/html seon.cluster.instruction/instruction-html}
  [:seon.cluster.instruction/id :seon.cluster.instruction/id]
  [:seon.cluster.instruction/text :seon.cluster.instruction/text]]

 ;; cluster-owned authoritative set (on the config singleton):
 :seon.config/instructions [:set :seon.db/ref]
 ;; per-agent ADDITIVE set, empty by default:
 :seon.cluster.agent/instructions [:set :seon.db/ref]}
```

Plain refs, never `:seon.db/component` (shared entities; component would
cascade-delete). Seed rows at cluster population, from today's constants:
`:reply-grammar` (execution-ai body), `:messaging` (peers-ai grammar tail),
`:declining` (assignment-ai grammar tail), `:global` (the user's global
instruction file bytes, ingested at `bin/seon init`). Edits mutate
`/text` in place; history is forensics.

### 2.2 The cluster edge

```clojure
;; resources/seon/schema/agent.edn addition  [TARGET]
:seon.cluster.agent/cluster :seon.db/ref   ; → the config singleton
```

Written in `creation-tx`. d1 = the cluster (name, agent-relevant dials,
its instruction set at d2); reverse at d2 = every peer agent AND root's
fleet — one derived traversal, no `root`-name rule. The cluster's renderer
must emit a bounded name-sorted projection (reverse fan-out exceeds
`max-collection`).

### 2.3 Relaxations and deletions

- `:seon.ns/ns` entity map: `:seon.ns/source` becomes optional — an agent
  namespace with no file has no source; inventing `"(ns my.agents.<id>)"`
  would be a stored derivation.
- DELETE `:seon.render.block/band` and `:seon.render.block/priority`
  (ruling #4(3)) and the authored-band context ordering.
- DELETE the seeded AI block machinery per the retirement report's waves;
  the four HTML-only blocks convert to walk products in the same wave (the
  UI is unrebuilt — no compatibility posture).
- `creation-tx` routes through `store/transact!` with tx-meta provenance
  (`:seon.db/process`), fixing the bare-vector provenance gap.

### 2.4 Derived edges (computed in the one traversal owner, never stored)

The walk follows real refs AND a small computed-edge table declared in
`seon.render.walk` — each entry a pure function of (db, entity):

| Edge | Derivation |
|---|---|
| ns → required ns rows | resolve `:seon.ns/requires` symbols to `:seon.ns/name` rows; unresolved symbols render as names (external deps) |
| agent → trigger message | the run-opening transaction's `:seon.db/trigger` tx-meta ref, queried, not copied onto the run |
| agent → asked-for runs | runs whose opening tx-meta names this agent's message (dissolves `settlement-ai`'s reach exception) |

These are the ONLY exceptions to refs-only traversal; adding one requires
naming it here. This keeps "no stored derivations" and dissolves both
named walk-reach exceptions (requires, settlement).

## 3. The block contract

A block instance is identified by `(root-entity, renderer-var, distance,
projection)`. Producing it yields:

```clojure
{:seon.render.block/bytes      bytes-or-string   ; ai text | html string
 :seon.render.block/digest     digest            ; equality suppression
 :seon.render.block/deps       #{attribute ...}  ; the dependency plan union
 :seon.render.block/revisions  {attribute rev}   ; last-seen, from db value
 :seon.render.block/basis      t}                ; max tx over the read set
```

- `deps`/`revisions` come from the facade's evidence-capture pass
  (dual-use per ruling 24: same functions, capture bound by the calling
  pass). Concrete pull patterns are derived from the entity's schema
  family — the wildcard-pull sites are replaced, or invalidation is a
  no-op (scheduling F4).
- Staleness: `∃ a ∈ deps : revisions[a] < current-revision[a]` — O(deps),
  conservative-revision fail-closed.
- `basis` is the ordering key. Per-render, over the render's own read set:
  an instruction row untouched since seeding keeps its seed basis forever
  and fronts every prompt; the REPL state line reads churning facts and
  sinks to the tail as the natural cache boundary.
- The production cache is cluster-global, keyed by the block identity plus
  `basis`, digest-deduped (each output stored at most once). Process-local,
  losable, rebuilt from facts — never a second truth.

## 4. Renderer admissibility and execution

- A renderer is an ordinary defn: one namespaced unit map in, data out.
  Agent-authored renderers require a complete `:malli/schema` (selective
  admission — the durability gate, unchanged).
- First-party renderers run inline on the owning `:io` proc (C1: db reads
  can block on lazy index restore).
- Agent-authored renderers execute ONLY via the sci door, as a THIRD door
  operation beside `evaluate`/`acquire!` (sci-door-ctx-sharing report §5):
  arm once per render PASS, invoke the installed sci fn VALUES directly
  (never `requiring-resolve` — it throws on corpus fns), bound by the
  EXISTING `:seon.config.eval/time-limit-ms` and result caps. Measured:
  armed direct invoke 3.2 µs vs full `evaluate` 100.8 µs; the per-entrance
  interrupt check is 7.8 ns. No render-specific dial set.
- BLOCKER dependency (ground truth, sci-interrupt-ground-truth report):
  the escapable limit is SEON'S defect, not sci's — our `arm` cancels
  its scheduled timer, leaving previously created/acquired fns holding a
  permanently disarmed flag. Fix is first-party, no fork change: one
  stable per-run guard object with a thread-scoped flag (shape S2),
  installed on the run ctx before `acquire!`; three edit sites
  (`sci/eval.clj` arm + assoc, `cluster/loop.cljc` run fork). Proven by
  probe: both escapes close at the limit; disarm exact; sibling forks
  isolated. Companion defect: `interrupted?` reads only top-level
  ex-data and misses sci's wrapped interrupt (marker on the CAUSE), so
  interrupts record as `:error` — fix walks the cause chain. Gates every
  renderer wave and repairs today's run fold.
- Renderer failure/interrupt = flat `:seon.error` value; the block renders
  as its error projection (loud), never omits silently, never throws into
  a proc. Catch sites must mirror `admit`'s interrupt pass-through — a
  bare `catch Throwable` swallowing the uncatchable interrupt is a defect.
- Ctx sharing: fork-per-agent-per-basis (fork is 72 ns; one shared
  installed base). Compiled schema state is already an agent-free value
  (`projection-from-database` + fingerprint reuse); it gains a per-cluster
  basis-keyed holder, and the process-global `activate!` retires.

## 5. Message and error flow (owner question settled by existing law)

In-flight delivery rides channels; durable truth commits as facts the walk
reaches (transport law, unchanged):

- messages: already `:seon.cluster.message/to`/`from` refs — reverse walk
  at d1. The transcript is a walk product in v1 via a bounded,
  `at`-ordered message-family projection with age-varying detail
  (transcript-aging quarry shape) and LOUD elision.
- errors: flow error-chan → fault committer → durable `:seon.error` fact
  with `:seon.error/agent` ref — reverse walk at d1. Agent mistakes are
  flat error values in the transcript; core faults are committed facts.
- Fix in the same wave: reverse-ref truncation gains an explicit elision
  marker (today `max-collection` silently drops — audit §7.5).

## 6. Generative properties (the standing test surface)

One property per failure class, driven by schema generators:

- P1 membership completeness: every schema'd entity within depth N of a
  generated agent graph appears in assembly output or leaves an elision
  marker. No silent omission.
- P2 resolution determinism: for any (value, viewer-ns, owner-ns, schema)
  configuration, exactly one renderer wins, per the ruled chain; removing
  the winner promotes the next step.
- P3 staleness soundness: for a generated transaction touching attribute
  set A, every block with `deps ∩ A ≠ ∅` reports stale; no block whose
  rendered bytes change reports fresh (conservative direction only).
- P4 ordering stability: blocks whose read sets a transaction does not
  touch retain relative order; a touched block moves tailward only.
- P5 prefix sharing: two agents referencing the same instruction rows
  produce byte-identical rendered prefixes for those blocks.
- P6 elision loudness: any cap (depth, node budget, collection, tokens)
  that drops content leaves a marker; total bytes stay within budget.
- P7 door totality: a generated looping/allocating renderer is interrupted
  within `time-limit`, yields a flat error value, and the assembling proc
  and its siblings complete their pass (fairness floor).
- P8 walk purity: assembly performs no writes; same db value in, identical
  bytes out (cache transparent).

## 7. Implementation waves (from the retirement report, compressed by the

UI-unrebuilt ruling)

- W0 free cuts (redirect step, dead `seed-tx`, `:identity` fold) — no
  blockers, land now.
- W1 this spec's schema changes + derived-edge table + provenance fix.
- W2 renderers (parallel lanes): namespace renderer (quarry the old one),
  transcript projection, instruction/cluster lenses, error projection.
- W3 one chain + one floor in the router (owner ruling pending on which
  HTML floor survives; recommendation: the admission-codec-backed
  `data-panel` floor, richness restored via family lenses, so eval-return
  rendering and walk rendering share one skeleton).
- W4 walk-derived membership; DELETE all seeded blocks + bands/priority +
  the five superseded `context.clj` projections; page goes recency.
- W5 attribute-revision invalidation + concrete pull patterns (replaces
  the hand wake set's render half; `wake-attributes` itself stays — it is
  a computed disjointness-checked set, not a hand list).
- W6 scheduling repairs alongside: per-agent render proc (ruling 21),
  cluster render proc off `:io`-serialization duty, keyframe-serve for
  new tabs. Delivery packages/keyframes conversion stays TARGET until
  after the walk lands.

Each wave: old tests pinning deleted paths die in the same commit; the
replacing property (P1–P8) lands with the wave that makes it assertable.

## 8. Open for owner

- Base-Var isolation: a fork isolates NEW names only — re-defining an
  existing shared Var (`clojure.string/join`, a `my.*` fn) from one
  agent's fork rebinds it for every fork in the JVM (probed). Needs a
  ruling: freeze the shared base, copy-on-write per fork, or
  accept-and-detect.

- W3 floor choice + whether namespace overrides resolve by `render-<kind>`
  name convention or computed Malli-output matching (house rule favors
  computed; costs a corpus query per resolution — measure first).
- Root fleet oversight derives from live flow pings, not facts — it stays
  a first-party renderer (allowed: first-party, not agent-authored), but
  its data is process-local; decide whether ping summaries should commit
  as periodic facts so the walk (and other agents) can see fleet state.
- `:system` instruction row rides the user role unless `loop.cljc:878-881`
  changes; decide if a true system-role message is wanted in v1.
