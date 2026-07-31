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

## 3. The render-unit contract (revised per falsification + rulings #7)

Context is a TREE: leaf = renderable data, ref = branch; "block" is the
informal name of a render unit in both projections, never a data type.
THE CACHE IS PER FUNCTION CALL, not per walk: `(renderer-fn × explicit
args) → bytes`, so the walk dumbly calls renderers and composition is
free. Hidden walk state may never influence a render (the falsified
viewer-leak came from the per-path visited set acting as an invisible
argument): the per-path visited set is replaced by a per-walk rendered
set emitting BACK-REFERENCES, which also kills the fan-in explosion
(measured 1,112 nodes at d3 on a 12-entity clique). A cached call
yields:

```clojure
{:seon.render.block/bytes      bytes-or-string   ; ai text | html string
 :seon.render.block/digest     digest            ; equality suppression
 :seon.render.block/deps       #{attribute ...}  ; the dependency plan
 :seon.render.block/revisions  {attribute id}    ; last-seen per-attribute
                                                 ; COMMIT IDS (UUIDs, not
                                                 ; counters — compare not=)
 :seon.render.block/changed-at t}                ; basis when the DIGEST
                                                 ; last transitioned
```

- `deps` capture comes from the READ FORM (`query-attribute-dependencies`,
  3.75 µs) — never the +52% evidence-wrapping pass. Concrete pull
  selectors derive mechanically from the entity's schema family,
  intersected with `(:schema db)` (pulling an uninstalled attribute
  throws); reverse expansion uses per-family ref subsets derived from the
  schema EDN (the unbound-attribute reverse query registers `:all` AND
  falsely matches plain longs — filed defect, W1).
- Staleness of a cached call, three fail-closed comparisons:
  `∃ a ∈ deps : not= revisions[a] current[a]` **∨** conservative-revision
  moved (the ONLY signal on schema commits — omitting it was falsified)
  **∨** the process-local CODE REVISION moved (renderer redefinition,
  agent-published renderers, schema registration — none of which transact
  domain attributes). `:db/txInstant` never enters `deps`; the check is
  defined only on committed db values (`as-of`/`history`/`d/with` values
  carry no cache context).
- `changed-at` is the ordering key: the basis at which the cached call's
  DIGEST last transitioned — derived by the cache at render time, never
  stored as a fact (max-tx moves on no-op re-asserts and pulls return no
  tx, so fact-side derivation was falsified). Display order is dumb
  last-changed across ALL units regardless of tree position; near-equal
  stamps cluster by branch so related units stay together (ruling #7(2)).
- The call cache is cluster-global, digest-deduped (each output stored at
  most once). Process-local, losable, rebuilt from facts — never a second
  truth. Measured: the staleness sweep for 100 blocks × 1,702 deps is
  66 µs per commit.

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

## 8. Settled by owner (2026-07-31 batch 3) and remaining opens

SETTLED: base-Var redefinition = ACCEPT AND WARN (Clojure-shadowing
style), composed with the DISTRIBUTED OWNERSHIP PROTOCOL: changing a
symbol in a namespace you do not own means messaging its owner agent and
receiving commit/rejection by reply; a message to an unowned namespace
spins up and assigns an owner agent on demand (`owner-of` exists;
on-demand creation is a new creation trigger, modeled in W1). Override
resolution = one corpus query (functions + input/output schemas are
facts) through the same query cache — nothing special. HTML floor = the
data-panel/admission side, the value renderer's presentation ported into
it (orchestrator pick, owner offered veto) — and the floor is OPT-IN in
the HTML projection (ruling #8): units that would fall to the generic
data browser hide by default behind a "show everything" checkbox
(transient per-tab state); the AI projection always renders everything. Render cache = per function
call; ordering = dumb last-changed with branch tie-clustering (§3).

STILL OPEN:
- Root fleet oversight derives from live flow pings, not facts — it stays
  a first-party renderer (allowed: first-party, not agent-authored), but
  its data is process-local; decide whether ping summaries should commit
  as periodic facts so the walk (and other agents) can see fleet state.
- `:system` instruction row rides the user role unless `loop.cljc:878-881`
  changes; decide if a true system-role message is wanted in v1.
