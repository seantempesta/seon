---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Seon runtime — the plan

**You are in the right place. Read these four files, in this order, and nothing
else.**

| file | what it is | trust |
|---|---|---|
| **this file** | the ONE ordering: the rulings, the nucleus ladder, the landmines | current as of its last edit |
| [handbook.md](handbook.md) | the UNDERSTANDING: why the fresh tree, how the construction loop runs, the mentality, where things are defined — read it to GET IT without re-deriving a week of conversation | current |
| [unsettled.md](unsettled.md) | what is UNDECIDED (needs a ruling), UNKNOWN (needs an experiment), UNBUILT — and where the primitives do not yet compose | current |
| [history.md](history.md) | what was tried before, with commit hashes, and the mistakes worth not repeating | permanent |

`reference/` holds the audits these steps were derived from, copied here so
everything is colocated. The originals stay in `../research/`; neither is
deleted. Cite them, do not re-derive them.

**The tree is the state document (owner ruling 2026-07-27: `bin/plan-state`
and its generated `state.md` are DELETED — a cached snapshot of the tree is
stored derived state; derive on demand instead).** When this file makes a
claim about the tree, verify it with one live command (`rg`, `find`,
`bin/test`) before acting on it — this program repeatedly found one-day-old
prose wrong, six of six assumptions falsified in a single sitting.

**A second ordered list anywhere in this chunk is a defect.** Seven once existed
across six files in five naming schemes, which is why "follow the plan" had no
referent. An eighth was created and collapsed on 2026-07-26. If you find
another, delete it and point here.

---

## Sci execution runtime roadmap

## The final system gate (owner, 2026-07-25 night) — READ THIS FIRST

This is what "done" means. Not a slogan: every line is falsifiable, and a
session that cannot point at one of these is not finished, however green its
suite is.

**Live agents, really running.** Not a fixture, not a drive script. Real agents
take real turns against a real model in the default cluster, author functions
into the one corpus, message each other, and are still running an hour later.
The proof is a transcript and committed datoms, not a passing test.

**Load-tested, by us, on purpose.** We drive it until something breaks, and we
know which thing broke and why. Not "it seems fine" — a number, a ceiling, and
the name of the resource that hit it. We already know the shape of the answer:
the commit path is one core, SCI's share is measured rather than assumed, and
the model dwarfs both. Find the real wall.

**Kick-ass fast, measured.** Boot in seconds, agent start in milliseconds, a
turn dominated by the model call and nothing else. Every performance claim
carries the conditions it was measured under — this program has already been
misled twice by a number without its context.

**Speed-clause status — SATISFIED 2026-07-26.** The invalid broken-turn
waterfall remains visible, and §18 supplies the corrected fresh-cluster
measurement, durable component reconciliation, conditions, exclusions, and
target-only reset proof.

**Every weird smell chased to its cause.** A coercion, an inconsistency, a
duplicate mechanism, a silently-wrong default: each one is a bug until proven
otherwise, and it gets an issue with evidence even when it is not fixed today.
Today alone produced the vector-order defect, `read-string` honouring
`*read-eval*`, and limits that did not bound — all found by pulling on
something that merely looked odd.

**Clojure already solved most of this — go read it.** Before inventing any
mechanism, find where Clojure, `core.async`, `core.async.flow`, SCI, or
Datahike already answers it, and take their answer *and their name for it*.
This program's best decisions were all of that shape: `:interrupt-fn` over an
invented door, `:io`/`:compute` over invented pool names, flow's `transform`
discipline, the admin surface we get for free by putting state in a database,
and `[:set X]` over a bridge rewrite. **The wheel is round. Every time we
reinvented it today, the evidence took it away from us.**

**And the standing test, from the owner:** *is this simpler than it was?* If it
is equally complex, the model was ported, not applied.

## THE PLAN (2026-07-26) — this section owns implementation order

**Read this and nothing else to decide what to do next.** Owner ruling O17: this
is the ONLY ordering in the chunk. Seven orderings once existed across six files
in five naming schemes, which is why "follow the plan" had no referent; they are
deleted (`24053c64e`) and git is the archive. An eighth was created and collapsed
here on the same day — if you find a second ordered list anywhere in this chunk,
it is a defect: delete it and point at this section.

**Colocated with this file:** [unsettled.md](unsettled.md) — what is UNDECIDED,
UNKNOWN and UNBUILT, including where the primitives do not yet compose and a
list of things believed true that were wrong within a day. **Read it before
designing anything.**

Four reference documents survive and none of them sequences anything:
`research/measurements-2026-07-25.md` (every number with its conditions — never
quote one without them), `conversion-wiki.md` (portable-core scars),
`research/capability-ledger-2026-07-26.md` + `pod-cut-verdict-2026-07-26.md` +
`jvm-render-design-2026-07-26.md` + `scheduling-design-2026-07-26.md` (the audits
that produced these steps; delete each when its step closes), and
`research/preprocessing-design-2026-07-23.md` (cited by the root vocabulary
table).

**A step whose evidence has not been re-grepped since the last cut is a
hypothesis, not work.** One cut discharged five rows on 2026-07-26, and a step
carried stale evidence within a day. Re-verify before starting.

### Discharged 2026-07-26

| owner | what | discharged by |
|---|---|---|
| `src/seon/host.clj` + all `src/seon/host/` | the old guarded door, 5,715 src + ~7,000 test lines. Took with it: per-agent ctx retention (R-8a leak *and* the rejected model), the fixed 10-thread pool, D9's walk-away cancel, `policy-either`-style resource-as-agent-fault mis-filing, the second IPC path, and D7's tools.reader `*read-eval*` path | `8dc8623ad`, seams filed `ef1f815a5` |
| `seon.error.frame` | ordering vocabulary reconciled to one spelling, `ordinal` | `ee000a4e7` |
| `seon.sci.ctx` / `seon.sci.eval` | D15 catch-class surface; the interrupt marker proven un-swallowable by `(catch Throwable …)` | `ce5e061f2` |
| `seon.agent.driver` | duplicate run admission; D5's residual wake loop; D2 lease readiness | `71f3cb0e0`, `1832764de`, `3946b7192` |
| `bin/codex-agent` | the sandbox dial, which made an audit's own output unrecordable | `42a9faf2e` |
| `reference-code/http-kit` | vendored as a submodule | `2953a3b2f` |

### Rulings 2026-07-26 PM (owner, conversational session) — applied by the same-day sweep

- **Vocabulary.** Plan execution is a **reduce** (never "fold" — `r/fold` is
  parallel); the **run loop** (never "driver") claims runs via Datahike's
  `:db.fn/cas`; the one system-side effect owner is **`seon.effect`**
  (`effect/request!`) — every "door"/"capability dispatch" phrase dies; the
  **program graph** is the collective name for `:seon.fn`/`:seon.ns`/
  `:seon.schema` facts, which stay TOP LEVEL — `seon.fn`/`seon.ns`/`seon.schema`/`seon.test`
  (owner 2026-07-27, superseding the `seon.code.*` rename); a "latest-wins mailbox" is a `(sliding-buffer 1)` tap; the cluster
  JVM entry becomes **`seon.cluster`** at the merge; receipts move under
  `seon.agent.run`.
- **Agent world is `my.*`, flat.** Tools are flat siblings (`my.fs`,
  `my.shell`, `my.web` beside `my.blob`); every agent gets scratch at
  `my.agents.<id>`; a real namespace has at most one assigned agent; temp
  agents need none. **No disk write-back, ever** — an agent override of a
  core function is a later transaction on the same program-graph facts; the
  base is the compiled package's pages; reset returns to base.
- **`core.async.flow` ADOPTED, Path A**
  (`research/flow-api-adoption-2026-07-26.md`): `seon.flow` implements
  flow's own `flow.spi` protocols, zero forked files; flow-monitor is the
  graph-visualization/ops surface; the testbed
  (`research/flow-testbed-2026-07-26.md`) derisks the full scenario matrix
  before any production mechanism moves.
- **O14 DISSOLVED — nothing rendered is stored.** Web-render merges into the
  cluster JVM; crash protection is supervision + bounded evals + component
  restart, not process walls. The render pipeline is in-process flow:
  `listen!` interest wake → render proc through the ONE
  `seon.sci.eval/evaluate` (fork, `:interrupt-fn`, admission) → equality
  suppression (snapshot held in registration memory) → `mult` → per-tab
  per-render-unit `(sliding-buffer 1)` taps → per-tab `:io` writer → one SSE
  connection per tab carrying datastar element patches, bounded writes.
  Restart = one re-render per pinned canvas. Streamed reply partials ride
  the same pipeline; their coalesced no-history fact is retired. State B
  process kinds: **two** — cluster JVM(s) + disposable leaves.
- **O4.** The allocation metric stays a diagnostic; the limit reaction is a
  process-heap watermark checked at the heartbeat cadence, loud. A 1 ms
  spike stays invisible to any cadence; that case remains the process
  boundary.
- **O2.** Clusters never share a store; one cluster JVM per store; a second
  open REFUSES via one `flock` assert at store open — the one fenced place
  where coordination precedes the database (you cannot coordinate opening
  the database through the database; the realistic accident is the orphan
  JVM).
- **Datom size.** No string-size limit exists (the 65k folklore was
  Fressian's chunk buffer); the measured cost is ~2.2× index amplification,
  so bulk content stays in blobs (`laws.md`,
  `research/datom-size-limits-2026-07-26.md`).
- **Ordered collections.** Five of the seven "ordered" attributes were sets;
  two use child positions (gate green, 551/3,881/0/0). The general mechanism
  for a plain-data ordered list is the value IS a vector — a homogeneous
  tuple, one datom, whole-value replace; the 8-cap fork lift plus the
  `run/forms` conversion is QUEUED behind this sweep. Child positions only
  for lists of entities (`turn/evals`).
- **Errors, the flow way (two classes, never mixed).** Agent faults stay
  values — `evaluate` returns flat `:seon.error` values, terminal receipts
  commit them; they never touch flow's channels. Core faults ride flow's
  `error-chan`/`report-chan` — which are `(sliding-buffer 100)` and
  therefore transport, never a record: one `mult` fan-out owner feeds tap A
  (a fault-committer proc committing every core fault as a durable fact
  through the `:seon.config/on-core-error` dial, with a loud committed drop
  counter on tap overflow) and tap B (flow-monitor). A throwing step-fn
  keeps its proc alive with pre-step state per flow's own contract
  (`flow/impl.clj:96-172`); the testbed proves the design
  (`flow-testbed-2026-07-26.md`, error extension in flight).
  **Routing is derivation, never a router:** the committed fault carries its
  provenance (namespace, var, proc, run), so "who should fix this" is a
  query — the namespace's assigned agent (`:seon.agent/namespace`, unique,
  at most one) wakes on faults touching its namespace exactly the way
  messages wake (one derived rule, L8-disjoint); a fault whose program-graph
  call path spans namespaces derives multiple interested owners; root
  coordinates from the same facts. No dispatch table, no subscription
  registry — commit good provenance and the routing already exists.
- **Step 1 lands messaging + db first**, then blob/fs/web as the same
  pattern; the double-send experiment becomes runnable and is a step-1
  acceptance item.
- **Pod cut groups 1–4 launched** the same day, delete-never-port; group 5
  waits for step 5's producer replacement.
- **Division of construction (owner, 2026-07-26 night).** The system is
  built spec-first BY US, not by live agents: the Fable orchestrator
  personally authors every contract layer — data schemas, `:=>` function
  contracts with relational `:fn` properties, and the generative/property
  tests — and sol lanes implement against those contracts until green.
  Live agents building the system is explicitly NOT the construction
  model; the generate-code loop is a product capability, not our build
  process.
- **The render contract is two projections on one unit (owner, 2026-07-26
  night).** Any value carrying `:seon.render/ai` and `:seon.render/html` is
  renderable to both destinations: the `ai` projection derives into agent
  context; the `html` projection rides the step-7 flow pipeline to the
  ruled surfaces — **canvas** (focal, `:seon.render.canvas/content`) and
  **surface** (a context render). No third surface noun; "tile" stays
  banned. Step 1's effect families and step 7's pipeline both honor these
  two keys as the one render contract.
- **A smaller suite is a desired outcome, not a regression.** Port ideas
  (invariants), never tests; one regression per failure class at one choke
  point. When simplification removes mechanisms, their tests die with them
  and the count drops — the health metric is class coverage
  (`research/pod-test-coverage-2026-07-26.md`'s invariant list), never test
  count. No lane re-inflates the suite to match an old number.

### Rulings 2026-07-26 night, session 2 (owner, conversational) — effect model sealed, pilot before alignment

- **Effect identity is `(run, form-ordinal, effect-ordinal)`.** The
  effect-ordinal counts effect requests within one form execution; claim
  epoch is a fence ONLY and never part of logical identity. Resolves both
  op-id blockers (`effect-operation-id-collides-within-one-form`,
  `effect-operation-id-changes-on-run-recovery`).
- **The closed `::family` enum dies.** A hand list by the house standard.
  Capability identity on a request/receipt is the owner function's own
  symbol, derived from what was called; adding a capability = writing the
  owner function with its schema, zero registration. Rides the same
  contract revision as the identity fix.
- **Code always re-runs on recovery; the four declared effect classes
  (`:pure`/`:read`/`:idempotent`/`:external`) die entirely.** World-effect
  dedup happens inside `seon.effect` at request time: ledgered owners
  (db, message) replay by op-id and return the recorded result; each
  family core owns its replay contract. No per-function replay
  declaration exists anywhere.
- **Receipt-before-dispatch for every ledgerless family** (fs, shell,
  web, llm) — the provider attempt pattern generalized. Writer ordering
  guarantees: no receipt → never fired → safe to execute. Open receipt +
  untagged leaf → the may-have-happened steering error, NEVER a refire.
  The one opt-in is `redispatch-on-crash true` on first-party ledgerless
  leaves whose repeat is harmless (reads); absence means never. Safety is
  the default, not a tag (`fire-the-missles!` is safe by construction).
- **The complete annotation surface is two facts**: `:malli/schema` on
  durable defns (agents author this and nothing else), and
  `redispatch-on-crash` on a few first-party leaves. Effectful?, family,
  purity, workload, placement, replayability are ALL derived —
  effectfulness is reachability of the one door; family is the owner
  symbol in the call graph.
- **Workload is core.async's enum `{:io :compute :mixed}`, never
  declared.** Derived `:compute` = reaches no capability owner; derived
  `:io` = a leaf hop (pure blocking transport); **`:mixed` is the
  fail-closed default for anything the graph cannot resolve** — core.
  async's own bucket for unknown code (supersedes the earlier
  unknown→`:io` lean; unknown code may compute, violating `:io`'s
  contract). A sequential chain is never pool-hopped per frame: it splits
  into tasks at effect boundaries — compute segments on the eval's
  `:compute` platform thread (with the `:interrupt-fn`), leaf hops on
  `:io`. `:mixed` being unused by resolved chains is evidence the door
  decomposes the workload; needing it for resolved code would mean
  something bypasses the door. Future, measured-not-asserted: receipts'
  durations/`fn-entries`/`allocated-bytes` can feed profile-informed
  classing with zero author annotation.
- **Turn phases: receipts subsume.** The six-phase cursor shrinks to what
  receipts cannot express (the provider attempt boundary); plan freeze +
  per-form receipts are the one recovery cursor.
- **Construction model** is codified in `docs/seon/architecture/`
  (construction.md, covering both our spec-first build AND the product's
  agent accretion gate as one discipline) — DEFERRED until the pilot
  below reports.
- **Pilot before full alignment (owner: "minimum weirdness").** No
  architecture rewrite yet. One test run, implemented BY THE ORCHESTRATOR
  at top level: db + message (ledgered replay) + `my.fs` (`read` =
  redispatch-on-crash, `write!` = untagged steering) on the revised
  identity contract, with a crash-scenario walk table in the contract and
  the live kill/resume falsifiers (double-send experiment; kill between
  fs receipt and terminal). Acceptance includes the weirdness count: the
  agent-visible reply is standard Clojure — no envelope, no identity
  argument, no effect vocabulary; target zero constructs beyond
  `:malli/schema`. What the pilot does not need, the architecture never
  gets.
- **Gate cadence**: the edit hook's affected selection per commit; full
  suites at frozen-tree checkpoints only. **Test selection's target** is
  function-level derivation over the program graph (changed functions →
  reverse call-graph closure → test roots; unknown widens) — spec now,
  build once the indexer's emitted rows are current facts; it replaces
  the hook's clj-kondo/Shadow namespace closure (a second graph builder).

### Rulings 2026-07-26 late night, session 3 (owner) — the crash model, the nucleus, the build split

- **The crash model.** Errors are caught and shown to the agent — nothing
  throws into the loop and nothing crashes on an agent mistake. Process
  crashes are RARE. Recovery = reopen the database, mark dangling receipts
  `:interrupted`, and the agent adapts from derived context. **NOTHING ever
  re-executes a form or refires an effect.** `fire-the-missles!` is safe
  because the system never refires anything, not because of an identity
  scheme. Presentation on resume: every eval has a result; results that
  never happened are absent facts, surfaced as ONE derived warning in the
  context block ("interrupted at form N; later results missing") — never
  per-eval marker tokens. Receipts: effect attribution is the transaction's
  provenance metadata (already written); the only receipt entities are the
  run machinery's eval receipts (running → done/error/interrupted).
- **The effect-replay layer is DELETED** (`10c12e1c1`): the crash model
  gives it no job. `:seon.capability/op-id` remains the writer's
  transport-retry identity, which predates it. What the pilot proved is
  recorded (`research/effect-pilot-evidence-2026-07-26.md`); what survives
  it: the computed binding table, home-require exposure derivation, the
  invocation-binding mechanism, and the crash-walk authoring discipline.
- **The dev/publish build split.** DEV runs from the source classpath
  everywhere — no AOT, hot reload through the REPL, slow first boot
  accepted. PUBLISH is one deliberate build — AOT + AppCDS + the JVM
  program index + initialization pages + digests — that clusters deploy
  and reset from (the template store, `c669c2f6b`, is the deploy half).
  The build-audit lane inventories every build against this axis.
- **The State B nucleus replaces topology-first sequencing** (§3 below):
  a fresh, small namespace set with zero baggage, composed from the
  trusted components; old `src/` files stay unlinked unless adopted as
  libraries; delete-never-port stays in force.
- **The construction experiment is explicit**: per nucleus namespace, the
  orchestrator authors the data model + `:=>` contracts + tests (with the
  crash-walk discipline); ONE sol lane implements THAT namespace until
  green without touching a schema or test; the orchestrator reviews,
  integrates, and proves live. This tests the generate-code thesis on our
  own core.

### Ruling 2026-07-27 (owner) — the ten-second start

**Starting the system must never take longer than 10 seconds.** This is a
hard bound, not a wish: it is B0's primary falsifier (`bin/seon start` →
REPL reachable and store open inside 10 s) and the standing measure the
dev/publish split is judged by. The old operator chain structurally cannot
meet it (serial shadow-cljs build → ~45 s AOT/CDS republish on any JVM
edit → pod start-gates, with a downstream failure tearing the writer REPL
down); that chain is condemned, not tuned. Context, same day: a deleted
`my.skills` var wedged the watcher build and took the whole boot — and the
JVM REPL — with it. The REPL must never be hostage to a downstream
process's build or gate.

**Same ruling, second clause (owner, 2026-07-27): the CLJS build is OFF.
No shadow-cljs anywhere in development — CLJ and the JVM only.** The
operator stops owning the watcher and pod processes (the dev process set
is writer + host + web-render); the changed-test hook stops running any
CLJS boundary. This applies O13 (the pod dies unconditionally) to the
development loop today instead of waiting for the group-5 cut: nothing
may reintroduce a shadow build into the dev feedback path.

### Rulings 2026-07-27 session 2 (owner, conversational) — the fresh tree IS the project

- **Stop thinking temporary; drop the "nucleus" vocabulary.** The fresh
  tree is not a side experiment with a codename — it is SEON, set up as
  if it was the target the entire time: a solid dev environment, a
  production build, and a testing system, each done properly and then
  improved on. No piling ideas on top of each other; no interim
  constructs that a later rung deletes. (The ladder's rung names R0/B0…
  remain as plan bookkeeping; the system itself is just Seon.)
- **The fresh tree gets its own proper `deps.edn` story.** Fresh `src/`
  and `test/` are the DEFAULT project (paths, REPL, tests); the old system
  is DISABLED by default and reachable only behind an explicit alias for
  quarrying and old gates. This inverts the R0 audit's keep-everything-
  running posture — "disable the old system entirely if you need to" is
  granted. bin/seon and the artifact machinery serve the old system only
  until their replacements land; nothing new invests in them.
- **web-render is cut from the dev process set.** UI comes later (N4's
  in-process pipeline). The target boot has no wire protocol, no way to
  crash, and efficient many-thread launch — reimagined on
  `core.async.flow`, whose source is required reading.
- **Boot starts from the flow testbed.** `seon.flow` (1,020 lines,
  flow.spi launchers, suite green 4/31 on 2026-07-27) is the foundation
  the boot design grows from, per the flow-per-cluster research: one
  flow graph per cluster, root-owned shared executors, cluster reset
  stops only its own graph.
- **Standing discipline, owner's words:** we do not repeat the mistakes
  we made before. Quarry first — assume a previous implementation exists
  in `src-old/` or git history, read it, then design better. When unsure,
  ask the owner with concrete options and concise pros/cons BEFORE
  implementation bakes a taste call in.
- **Program-graph namespaces stay TOP LEVEL (supersedes the
  `seon.code.*` rename).** The core system's own names: `seon.fn`,
  `seon.ns`, `seon.schema`, `seon.test`. Schema facts remain one global
  population with derived reverse lookup (the N5 clause), unchanged.
- **Datahike is PART of Seon, not a library we call (owner,
  2026-07-27 late).** It is our fork under `reference-code/`; calling
  internal functions is sanctioned; a small fork change is acceptable
  when it buys real isolation. Konserve stays the default backend.
  VERDICT (b2-plan §0, probe-grounded): **BRANCH-PER-CLUSTER ADOPTED**,
  conditional on one ~15-line fork change. Two branches of one store
  share exactly ONE mutable durable key (`:branches`, touched only on
  cluster create/delete); commits touch only content-addressed values
  and the branch's own head. GC isolation is STRUCTURAL (every roster
  branch head is unconditionally seeded; proven live: deleting one
  cluster reclaimed its tail, the survivor and ancestor intact).
  Branch-off = 17 ms, ancestor bytes stored once, any backend. The
  fork change is BLOCKING and falsifier-first: concurrent `branch!`
  from two connections silently lost clusters (11 :ok / 9 in roster —
  the L6 scar at branch granularity, inside one process where the
  flock cannot fence); the fix applies the fork's own gc_guard
  store-id-keyed in-flight idiom to the roster mutations
  (issue: datahike-branch-roster-read-modify-write-race). O2 restates
  as: clusters never share a BRANCH; one physical store per process
  (its clusters live in that process — matching the one-JVM shape;
  export/import is the escape hatch and clone survives only there and
  for base-building). Owed experiment: GC pass cost over ten warm
  clusters. B0/B1 take author revisions (store to the process root,
  `open-branch!`, `cluster-paths` drops store-dir).

  **Rulings 2026-07-27 late (owner, plain-language batch):** the
  3-name core-process trust list ships as-is with the computed rule as
  its own follow-up task; the run loop claims BEFORE the model call
  and NOTHING retries paid calls (a lost call is lost; the agent
  adapts from the interruption note); http-kit's socket buffer is
  MEASURED in N4, forked only on evidence; the value-admission gate is
  its own small package scoped to forcing + size-capping values
  leaving the sandbox — allocation is watched by the O4 heap
  watermark, runaway → interrupt + flat steering error + agent
  restart, process restart the backstop; leverage SCI internals (our
  fork) for oversight rather than pretending to bound what we cannot.
  **Rulings 2026-07-27 night (owner, plain-language batch — N3
  authoring inputs):** (1) crash resume is INTERRUPTED + ADAPT, no
  auto-retry: the run is created and claimed at wake (duplicate-turn
  fence), a mid-turn death marks that run `:interrupted`, and the
  agent's NEXT turn (next trigger or manual nudge) carries the derived
  interrupted-warning — zero retry code, the trigger sits unanswered
  until something wakes the agent (supersedes n3-plan §9.1's Option A
  auto-re-call half; the claim-early half stands). (2) `my.run` seals
  with exactly two pure disposition values — `complete` and `wait`;
  no `start!`, no pause/resume/terminate until an agent-lifecycle
  entity exists. (3) A run's WHY is transaction metadata: the
  run-opening transition carries a ref to the triggering message as
  tx-meta ("has this trigger been answered?" = does any run-opening
  tx point at it) — the one deliberate extension of minimal tx-meta
  (user + process) on one transition; never copied onto the run
  entity.
  **Ruling 2026-07-27 night (owner, plain language): FAIL LOUD ≠ FALL
  DOWN.** "Fail loud in development" means everyone using the system
  KNOWS immediately that shit has hit the fan — the failing operation
  halts loudly, the error fact commits, the surfaces scream — while
  the SYSTEM STAYS UP: the REPL and the html interface survive
  precisely so the error can be dug into and understood. Dev :panic
  stops the offending activity (the eval errors, the turn aborts),
  never the process, the REPL, or the UI. Corollaries already
  consistent: the REPL survives failed boot (B0 ruling); the recorder
  never panics ("the fire alarm doesn't burn" — orchestrator carve-out
  confirmed by this ruling); error facts are durable and projectable
  so the digging has something to dig.
  **Rulings 2026-07-28 (owner, plain-language batch — presence-not-kinds
  session, grounded in state-without-kinds-2026-07-28.md +
  simplification-catalog-2026-07-28.md):** (1) OMISSION = NIL-PUNNING
  (replaces context-blocks Decision 1): nil and absent unify under `get`
  — a projection with nothing to say may return nil or omit the key;
  consumers ALWAYS read render keys with `get`, never `contains?`;
  `[:maybe]` is allowed in in-memory function RETURN contracts (stored
  attributes stay nil-free — the bridge forces absence there). An
  omitted block keeps its identified wrapper element so its morph
  target survives. (2) STATE IS PRESENCE, never a stored discriminator:
  delete `:seon.ai.attempt/outcome`, stored `:seon.ai/disposition`/
  `error-class` (derive at read from stored observations), and — sealed
  N3 revision — `:seon.cluster.eval/status`: `:running`/`:done`/
  `:error` collapse into presence of `result-edn`/`error`,
  `:interrupted` becomes an `interrupted-at` instant, settlement fences
  on ABSENCE of terminal facts. `:seon.render/kind` is exempt: a
  request argument, never stored. (3) Context-blocks Decisions 2–4 go
  with the researched recommendations: ONE computed projection-
  invocation seam in the one router (compiled Var or N5 SCI Var, same
  result union); installed/derived name collisions REFUSE loudly naming
  both sources; exact-prompt capture commits BEFORE the provider call.
  (4) Test-design units 1–3 (false/racing-proof removal, canonical
  database fixture, minimal shared support) approved; units 4–9 return
  to the owner after those land.
- **The bootstrap is a shared database ancestor.** One deliberate build
  indexes ALL code and produces the bootstrap; a freshly started cluster
  loads it, a restarted cluster resumes from it. Every cluster shares the
  SAME bootstrap, so creating a new cluster is a database fork of that
  ancestor — near-instant, never a re-index. This is the template-store
  mechanism (`c669c2f6b`) named as what it is; O2 still holds (clusters
  never share a LIVE store — the fork copies, the ancestor stays
  immutable). Design lands in the B2 contract package.

## 1. The base constructs

Everything the runtime is made of. Every step below is an application of
these and nothing else; a proposal that needs an eighth construct is wrong.

1. **The database value.** One immutable value at a named basis. Every read
   anywhere — eval, prompt, render, resume — is a pointer into one
   (`seon.db/db`).
2. **The transaction.** The only way anything changes. One writer per store;
   the report's `:db-after` is the next basis. All coordination is committed
   facts, so any process may die and a survivor resumes from them.
3. **The plan fold.** A reply freezes into one ordered form plan
   (`seon.agent.driver/plan-tx-data`, absent→digest CAS). Execution is a
   fold: running receipt → eval at the previous step's `:db-after` → terminal
   receipt (`execute-form!`). Custody is CAS + epoch + lease facts
   (`seon.agent.run.core/claim-plan`). Resume is a query
   (`seon.eval.receipt/next-ordinal`: first ordinal without a terminal
   receipt).
4. **The guarded eval.** One shared SCI base (`seon.sci.ctx/base`), one fork
   per evaluation, one `:interrupt-fn` with time as the only limit
   (`seon.sci.interrupt/start`), on a `:compute` platform thread behind a
   semaphore (`seon.sci.eval/evaluate`). Everything leaving is deeply
   realized and bounded at that one choke point; the heap's boundary is the
   process.
5. **The effect owner (`seon.effect`).** The single guarded function every
   genuine capability request enters — db, blob, fs, shell, web, messaging,
   LLM — reached through flat `my.*` tool bindings computed from the program
   graph, never listed. Effects carry the one request identity (`seon.db`
   operation IDs); the honest ceiling is at-least-once.
6. **The program graph.** Code is facts: `:seon.fn`/`:seon.ns`/`:seon.schema`
   (top-level names, owner 2026-07-27) committed like any data, acquired at a
   basis into a fresh fork. One graph answers "what exists?" and "load it."
   A compile-time JVM index is its first-party producer; agents are its
   runtime producer, overriding by later transaction — never by disk write.
7. **The derived view.** Prompt, page, warning, context: pure functions of a
   database value on one reactive chain — interest wake → equality
   suppression → per-consumer `(sliding-buffer 1)` taps (`seon.reactive`,
   `listen!`). Nothing rendered is stored (O14 dissolved 2026-07-26).

Corollary (owner ruling 2026-07-24): the agent-facing surface is three shapes
only — values the driver interprets, requests through the door, facts the
driver commits. Three processes carry all seven: cluster JVM, web-render JVM,
disposable leaves (`architecture.md:239-262`).

## 2. The quarry map — State A as it was (historical, kept for mining)

**AMENDED 2026-07-27: this section and §2b describe STATE A — the old
system under `src-old/` — as it stood before the tree split. They are no
longer "where we are"; they are the MAP OF THE QUARRY. The system is
built bottom-up in fresh `src/` (§3); before implementing any rung, mine
here and in git history for the previous implementation, pull the good
parts, and translate them into the better design — never implement from
scratch, and never port a shape unexamined (owner rulings, 2026-07-27).**

The live path was four files, 1,181 lines measured 2026-07-26:
`seon.agent.driver` (888) → `seon.sci.eval` (146) → `seon.sci.ctx` (42) +
`seon.sci.interrupt` (105), with `seon.repl.parse` the pure parser.
Constructs 1–4 work and are proven: the fold survived six kill positions plus
a double kill, one re-execution per crash, zero torn transactions; receipt
identity is deterministic `(run, ordinal, epoch)`
(`seon.eval.receipt/receipt-id`); a rejected agent transaction becomes a
terminal error receipt, not a wedge (`execute-form!`); an authored infinite
loop dies at ~55 ms with the server healthy.

What an agent can DO is almost nothing. The callable surface is
`clojure.core`, `clojure.string`, and five `seon.agent.lifecycle` vars
(`seon.sci.ctx/base`) — no construct 5. Construct 6 is broken three ways: the
driver commits no corpus facts, boot installs none, and a `defn` in form 1 is
invisible to form 2 because `drive-sources!` never passes
`::sci.eval/base-ctx` or a basis. Construct 7 runs on the dying pod except
the JVM `/data` feed. Bounding is incomplete: `terminal-receipt-data`
`pr-str`s unbounded and drops `fn-entries`/`allocated-bytes`; prints are
lost; lazy values realize outside the armed boundary. `bin/test-writer`
discovers 0 tests. Five supervised processes; the target is three.

## 2b. The piece map — where each State A piece's lesson lands

**AMENDED 2026-07-27: read this as a mining index, not a conversion
plan.** Each row names an old piece, what it really is, and the fresh
rung whose authoring must read it. The old framing ("the distance from
here to there") is superseded by the bottom-up build; the rows survive
because they say WHERE the lessons are buried. This table names each one, says
which base construct (§1) it really is, and points at the step that
discharges it. **It sequences nothing — order lives only in §3.** Verify a
row against the live tree (`rg`/`find`) before acting on it.

| piece today (State A) | what it really is | State B shape | discharged by |
|---|---|---|---|
| `seon.sci.eval`'s `Semaphore` | two mechanisms in one object — queueing and parallelism | bounded submission channel per workload class (backpressure) + bounded `:compute` executor (parallelism), both config facts; semaphore deleted (`3564882a3`) | scheduling design; wedge-N experiment in [unsettled.md](unsettled.md) §2 |
| the pod — 63 files, 30,962 lines | three unrelated jobs in one process: the pages producer (construct 6's first-party half), the render tier (construct 7), and a duplicate execution engine (construct 4, already owned by the JVM) | producer → JVM compile-time indexer; renderer → web-render over committed facts; engine → deleted, no replacement | steps 5–7 per `pod-cut-verdict-2026-07-26.md` |
| `seon.db.host/writer-session` UDS wire on the agent path | a process-boundary artifact mislabeled as database access | O1 co-location: a read is a pointer into a database value, a write is a function call in the cluster JVM | step 6 |
| `seon.sci.ctx/base`'s `:namespaces` literal | a hand list where a derived view belongs (L17) | computed binding table from corpus facts, every capability fn entering the one door | step 1 |
| code as source strings | the corpus with only its write half — facts committed, nothing resolves them | terminal tx commits `:seon.fn`/`:seon.ns`/`:seon.schema`; acquisition materializes a namespace from facts at a basis | steps 4–5 |
| `terminal-receipt-data`'s unbounded `pr-str`, dropped `fn-entries`/`allocated-bytes`, and the `persisted-value?` / wire-predicate split | value admission scattered across consumers instead of one choke point (L3) | one admission operation inside `evaluate` before disarm; one `ordinary-wire-value?` | step 3 |
| reply message with a freshly allocated id; ~~wake filtered on `:origin :human`~~ (fixed `4dbaeda0e`) | allocated identity where derived identity belongs | message identity = sending receipt `(run, ordinal, epoch)`; idempotency proof is a step-1 acceptance item (`research/double-send-experiment-2026-07-26.md`) | step 2 |
| run opened before its plan commits | custody split across two transactions that recovery reads as one | the pre-plan window recoverable (or run+plan one commit) | **no step owns it end to end** — [[../../../seon/issues/run-is-unrecoverable-before-its-plan-commits]] |
| `:seon.ai.attempt/*` 24 used / 0 registered; `:seon.agent.turn/*` 17 / 9; `:seon.agent/run` registered twice, once in a `.cljs` | facts written without their schema half; a registration on the deletion list | one surviving `.cljc` owner per attribute, moved before the pod cut | step 6 precondition (verify live with `rg`) |
| hand-rolled `newCachedThreadPool` called `:compute` | borrowed vocabulary without the mechanism | core.async's own `executor-for :compute`, or the name goes | §5 flow ruling |
| the "or derive from raw initialization" branch | a second pages producer | deleted; missing pages fail loudly (O16) | step 5 |
| five supervised processes | writer+host+web-render are one construct split by history; the pod is the three jobs above | **two** process kinds: cluster JVM(s) + disposable leaves (ruled 2026-07-26 PM) | steps 6, 8 |
| two writers on one store both winning the CAS | a configuration that must be impossible, currently merely documented (L6) | refuses to open, loudly — one `flock` assert at store open | step 6, O2 (ruled: refuse) |
| a stored render snapshot (O14) | a question that dissolved: serving moved in-process, so there is nothing to transport | registration memory + per-tab `(sliding-buffer 1)` taps; restart = one re-render per pinned canvas | step 7 (revised per rulings) |

The pattern across every row is the one [unsettled.md](unsettled.md) §4
names: the write half of a primitive exists and the read half does not, or
one object is doing two constructs' jobs. Nothing here needs an eighth
construct.

## 3. The construction ladder — the one ordering (amended 2026-07-27)

**The build is BOTTOM-UP in the fresh tree** (owner ruling 2026-07-27:
the fresh tree IS the project; stop thinking temporary; State A is
quarry only). Each rung: the orchestrator authors the contract package
(data model + `:=>` contracts + sealed generative tests + crash walk,
quarrying the old implementation first), one implementation lane makes
it green (stop-on-friction; sol and Opus 5 both proven), the
orchestrator reviews, proves live, and a recurring quality-review lane
audits the standing result.

**Rung status (verify against `bin/test`, not this prose):**

- **R0 — DONE** (`f25e34594`): the tree split; fresh default project,
  own `deps.edn`, `bin/test` gate, old system behind aliases.
- **N2 (run model) — GREEN, revised** (`c65ddeeda` + `ba5cb0c1e`):
  transitions inside the transaction via `[:db.fn/call #'f request]`;
  model-based state-machine suite.
- **B0 (entry) — GREEN** (`f1956f8f6` + `1e3aff7d6`): REPL at second
  zero, closed bootstrap schema, `(pid, start-instant)`-fenced
  advertisements, shared root executors; ten-second bound asserted
  in-suite.
- **B1 (store) — sealed, implementation in flight** (`c82f790f4` +
  `5bfc0e73f`): flock-before-everything on the canonical path, genesis
  window repaired by recreate, child-JVM cross-process falsifier.
- **B2 — NEXT authoring: config → facts + the bootstrap ancestor.**
  Two-phase config (closed bootstrap schema exists in B0; the database
  phase reconciles a manifest into facts, converged = zero writes);
  schema EDN under `src/seon/schema/` with the one admission gate; and
  the SHARED BOOTSTRAP ANCESTOR (owner 2026-07-27): one deliberate
  build indexes all code into the ancestor store; a new cluster is a
  near-instant FORK of it, never a re-index. QUARRY: the template
  store (`c669c2f6b` — State A's "reset = template clone" is the
  working prior art), `src-old/seon/config/resolve.cljc`, the runtime
  state reconciler, and the JVM program indexer.
- **B3 — dev loop closure**: `bin/repl` + MCP eval_clj discovery of
  fresh advertisements (in flight), function-level test selection
  later (task #6).
- **N3 (run loop as flow proc), N4 (render), N5 (corpus), N6 (final
  gates/leaves)** — as specified below, each with its own quarry read
  and port manifest.

The remainder of this section is the original rung detail; where it
says "old step N", that content is design truth carried forward, not a
conversion instruction.

The system is built spec-first, per namespace, from a small base.
Each phase names its trusted libraries, its falsifier, and absorbs what the
old capability-ordered steps still owed. The old steps are superseded — do
not resurrect them; their surviving content is named inside each phase.

**The method (owner, s3 close): simple building blocks that compose.**
Every rung ships the SMALLEST block that composes with the ones below it,
and three metrics are reported at every rung review, trending the right
way or the rung is not done:

1. **Blocks compose** — each namespace is one block: its data model, its
   pure transitions, nothing else; a block that needs to know another
   block's internals is two blocks welded, split it. Composition is
   proven by the next rung using only the block's public contract.
2. **The data model tightens** — count the registered attributes the
   nucleus needs; every attribute earns its place (no projection twins,
   no stored derivables, no bool mirrors of absence — the s3 rulings).
   Fewer, sharper attributes each rung is the goal; an attribute nobody
   queries is deleted, not kept warm.
3. **The codebase shrinks** — nucleus lines grow slowly; `src-old/` only
   ever shrinks (adopt = mv out, dead = delete when convenient). Report
   both numbers at each rung. A rung that grew the total without
   retiring old surface explains itself or is rejected.

**Generative behavior properties GUIDE the design (owner, s3 close).**
Every rung's acceptance surface is generative from the start: properties
over the whole input domain (fixed seeds, shrunk counterexamples
printed), with example tests kept only as teaching documentation of call
shapes. **The edge-case tripwire is a design verdict:** the moment we
catch ourselves writing point tests to fence individual edge cases, we
STOP and reassess — accumulating edge cases means the design admits
states it shouldn't, and the fix is a construction that makes the class
unrepresentable, never another test. (Precedent: the ordered-collection
sweep and `34a5da97c`'s edge-case-count law.) A rung whose property
count stays flat while its example count grows is failing this test.

**Expect to shed, not to port (owner, s3 close).** Tight integration
plus resilience-by-construction should make most old machinery
unnecessary — the port manifest's default verdict is `dead`, and a rung
that ports more than it sheds is suspect. The error mentality is R41,
unchanged and load-bearing at every rung: **fail loud and hard in dev**
(a violated invariant panics at the owning transition, immediately);
**never fail in production** (the same event records one bounded core
fault and degrades on the configured dial); **agents always get proper
errors** (every agent-facing failure is a flat `:seon.error` value with
a message that steers — never a throw, never a swallow, never a stack
trace as prose). One config dial decides dev/prod; no per-site judgment.

**Boot-order construction (owner directive, 2026-07-26 s3 late).** This is
a deliberate ground-up rewrite: build in the order things RUN, make each
rung solid before the next, and rule every ported piece explicitly. Each
rung's contract package carries a **port manifest** — a table naming every
candidate old piece with a verdict (`adopt` as-is / `adapt` at a named
seam / `rewrite` fresh / `dead` — stays unlinked) and why. Nothing enters
the nucleus through an undecided require. The extra layers and wire
protocols are what this removes: no host/writer split, no UDS on the agent
path, no pod. N1 decomposes into the ladder:

- **R0 — the tree split (owner, s3 close: "start truly fresh").**
  `src/` → `src-old/` (kept on the classpath so every running gate and
  cluster still works), and a fresh `src/` holding ONLY the nucleus. The
  port manifest becomes physical: adopting an old piece is a `git mv`
  from `src-old/` into `src/`, reviewed like any port verdict; "what have
  we adopted" is `ls src/`. The orchestrator executes the move ATOMICALLY
  at a lane-quiet point (cross-cutting renames are orchestrator-owned);
  an audit lane first inventories every consumer of the literal `"src"`
  path (deps/bb/shadow edn, the changed-test hook's corpus scan, indexer
  roots, AOT discovery, kondo config) so the split is one coherent
  commit, not a bleed. **`test/` splits in the SAME atomic commit**
  (owner, s3 close): `test/` → `test-old/` (still discovered by the
  runners), fresh `test/` holds only the nucleus acceptance suites —
  so `ls test/` is the honest list of what the nucleus proves, and an
  old suite is adopted the same way code is: by `git mv`, with a port
  verdict. The framing stays honest: this is not literally zero — git
  history and State A are the quarry and the rough map to B; starting
  fresh is how we find what is actually CORE and build only that.

- **B0 — the entry.** `seon.cluster` main: the process starts from source,
  prints its identity, and opens its io-prepl IMMEDIATELY — the REPL is
  online from second zero, before anything else exists. Ports: none
  (clojure.core.server only).
  **Multi-instance from day 0 (owner, s3 close):** the process identity IS
  (cluster-name, pid, start-instant); every path derives from the cluster
  name (`data/clusters/<name>/`, per-cluster process dir); each instance
  publishes its REPL coordinate to the discoverable per-cluster
  advertisement (the dynamic-port-file convention — adopt), so the MCP
  REPL reaches EVERY instance always. Banned by construction: any
  process-global singleton that assumes "the" cluster — connections,
  caches, and sessions are keyed by cluster/store, never ambient-one.
  Falsifier includes: start instances for clusters `a` and `b`
  side-by-side, REPL into BOTH by name, prove isolation — and the
  ten-second bound (owner, 2026-07-27): `bin/seon start` to a reachable
  REPL in under 10 s, always.
- **B1 — the store.** Open Datahike in-process (`:self` writer), the one
  `flock` single-writer assert, clean reopen after kill -9. Port decision
  rung: Datahike direct first; the `seon.db` facade is adopted
  deliberately or not at all — and the old per-process one-authority-
  session cache is `adapt-or-dead` (it is exactly the ambient-one-cluster
  singleton B0 bans). Invariant stays O1/O2: ONE live write connection
  per store, clusters never share a store; a process MAY host several
  stores — nothing in the nucleus may assume one.
- **B2 — schema + pages + config.** `seon.schema` (adopt — trusted), the
  initialization-pages consumer (adapt from `seon.db.protocol`), and the
  config machinery: the explicitly selected manifest reconciles into
  database facts at boot; runtime reads the database, never the file.
  Boot inputs come from the publish artifact/template store — the JVM
  indexer is a BUILD-time producer (rung "-1", the publish command), not
  something the runtime launches; its proper name and invocation are the
  build audit's deliverable.

  **The launch/config design (owner, s3 close — smart defaults, no magic
  numbers):** ONE verb, everything optional —
  `bin/seon start [cluster] [--config <path>]`; bare `start` = cluster
  `default` with the shipped default config; a name = that cluster, same
  defaults; `--config` = that cluster's own manifest. `default` is just a
  name. Config has exactly TWO phases:
  1. **bootstrap** — the closed, enumerated, deliberately TINY key set the
     process needs before the store opens (store backend/path, prepl
     bind, log dir — everything else derivable from (cluster-name,
     root) by convention). A key trying to enter bootstrap that the
     database could own is a design smell caught by the closed schema.
  2. **database** — everything else reconciles into facts after open
     (the existing explicit-apply pattern: converged = zero writes,
     drift repaired, effective config queryable). Later changes are
     database modifications — re-apply a manifest or transact the facts;
     the file is just one producer of that transaction.
  The shipped default config is THE defaults document: complete,
  explicit, every constant named with units and calibration provenance.
  A user manifest declares only overrides; absent key = default. **No
  numeric fallback in code, ever** — `(or x 60000)` is the banned shape;
  a tuned constant outside the defaults document is a review flag.
- **B3 — the loop's tools.** The nucleus edit-test feedback (in-memory
  suites, seconds per cycle), the MCP REPL pointed at the nucleus process,
  and the hook selecting nucleus tests — a really good feedback loop from
  zero is itself a rung with a falsifier.

Then N2 (run model — in flight), N3 (loop), N4 (render), N5 (corpus),
N6 (proofs/leaves) as below, each with its own port manifest.

**ACME is TABLED (owner, 2026-07-26 s3 close) — possibly permanently.**
Git is the archive. No `acme-client` build identity, no downstream
consideration in the publish design, no ACME row in any port manifest; it
returns only by an explicit future ruling. Standing bar for the nucleus
era, owner's words: **simplify everything — clean builds and clean code
from now on.** Every rung's review asks "is this simpler than it was?"
and a build or namespace that cannot answer is not done.

**Trusted components (libraries, not baggage):** Datahike (`:self` writer,
in-process), `seon.schema` (register!/bridge), `seon.sci.eval` (guarded
eval + computed binding table + home-require exposure), `seon.flow`
(flow.spi), the JVM indexer + initialization pages + template store
(`2ef6f0bbd`, `c669c2f6b` — old step 5, DONE), `seon.repl.parse`,
`seon.eval.receipt`. Everything else in `src/` stays unlinked until a phase
adopts it deliberately.

### N1 — `seon.cluster.boot`: one process, from source

One JVM opens the store directly (`:self` writer in-process, the one
`flock` assert — L6/O2), loads the initialization pages from the publish
artifact/template store, publishes a readiness fact, serves io-prepl, and
in dev runs FROM SOURCE (no AOT — the build split ruling). Orchestrator
authors: boot data model (readiness/identity facts), refusal contracts,
tests. **Falsifier:** boot in seconds; edit a namespace and REPL-reload
changes behavior with zero rebuild; a second open of the same store
refuses loudly; kill -9 and reopen recovers cleanly.

### N2 — `seon.cluster.run`: the run data model

Run/claim/lease/eval-receipt facts and their PURE transitions: open,
claim (CAS + epoch), heartbeat, release, close, and the crash rule —
mark dangling receipts `:interrupted` at boot; nothing re-executes.
Absorbs the custody-B verdict (a run is not claimable until its plan
exists) and the run-opened-before-plan window. The heaviest generative
property surface in the nucleus. **Falsifier:** generated interleavings
of claim/kill/reopen never yield two live claims, a lost commit, or a
re-executed form; the interrupted warning derives from facts and vanishes
when the run closes.

### N3 — `seon.cluster.loop`: the run loop as a flow proc

Wake (`listen!` interest) → claim → derived prompt → model call on `:io`
→ plan freeze (absent→digest CAS) → reduce forms through the guarded
eval → commit facts with provenance meta. Errors are values the agent
sees. Absorbs the old step-1 capability goal: the computed binding table
and home-require exposure already land db/message/lifecycle in the eval
world; blob/fs/shell/web arrive as ordinary owners with schemas when a
phase needs them. **Falsifier:** a real agent turn against DeepSeek on
the one JVM; then kill -9 mid-turn — next wake shows the one interrupted
warning and the agent adapts; zero refires.

### N4 — `seon.cluster.render`: the in-process render pipeline

Old step 7 unchanged in content (interest wake → render proc through the
one guarded eval → equality suppression → mult → per-tab
`(sliding-buffer 1)` taps → one bounded SSE per tab; two projections
:seon.render/ai + :seon.render/html), now targeting the nucleus JVM.
**Falsifier:** 32 tabs → one authored evaluation; the loop canvas costs
one bounded eval and the agent learns why.

### N5 — corpus round trip (old step 4)

defn in form 1 → committed program facts → callable in form 2 and by
another agent after restart; acquisition at a basis; `:malli/schema`
required for durable defns. **Falsifier:** old step 4 verbatim.
**Schema facts stay GLOBAL in the program-graph port (owner, s3
close):** a schema is one row in the one global population, looked up
on demand — never attached to or embedded in a fn/ns entity. Code
entities carry REFERENCES to schema keys (a contract naming its
shapes); the reverse lookup — which fns/namespaces/attributes reference
this schema — is a DERIVED query over those references (how dynamic
agent context already pulls relevant specs), never a stored
back-pointer.

### N6 — proofs, gates, leaves

`bin/test-writer` claims every nucleus class (old step 2 standing);
function-level affected-test selection over the program graph replaces
the namespace-closure hook; disposable leaves last (old step 8, O10).
The final system gate (§top) is unchanged: live agents for an hour,
load-tested to a named wall, fast with conditions, every smell chased.

## 4. The landmines — standing constraints

Each measured or a dependency property; violating one is silently wrong.

- **L1** `Thread.stop` is removed (JDK 26); no suspend/resume of an SCI
  eval. Containment is one time-bounded `:interrupt-fn` plus process
  replacement — never a third mechanism.
- **L2** No in-process memory bound: a host-call allocation shows 0 fn
  entries; the allocation metric is cumulative, anti-correlated with
  footprint. Heap is the process boundary.
- **L3** Lazy values escape the armed boundary; deep-realize inside it, at
  one choke point, never a guard per consumer.
- **L5** Form granularity is forced (0 vs 9 at turn vs step basis); the turn
  is a fold of forms, the resume unit is the form, the basis is `:db-after`.
- **L6** One write connection per store: two writers silently destroyed
  40/40 commits. The unsafe configuration must refuse to open.
- **L7** `listen` fires on transact only; a stale lease is not a commit; a
  firing clock backstop is itself a bug report.
- **L8** No wake attribute the wake path's own work commits (7.0 → 124.8
  commits/run, then OOM).
- **L9** No retrofitted eval atomicity; mid-eval entity ids are wrong the
  moment anyone else commits.
- **L10** No exactly-once external effects; the ceiling is "may have
  happened, here is what it was."
- **L11** The writer is one serial loop (knee 65,536 callers / 4,336 tx/s,
  APFS metadata force); snapshot isolation, never serializability — full-head
  basis pinning degrades badly.
- **L13** Order is never a collection-type property: stored ordinal, tx id,
  or sort key. Cardinality-many is unordered; tuples cap at 8.
- **L14** `:load-fn` cannot resolve a bare same-namespace symbol, and
  `:namespaces` is consulted first.
- **L15** Compiling agent code to a native fn deletes the `:interrupt-fn`;
  availability is interning, nothing else.
- **L16** SCI is 0.15% of a measured turn; the provider is 78.5%
  (`research/measurements-2026-07-25.md` §18, conditions there). Interpreter
  speed justifies nothing.
- **L17** No second mechanism, no hand-maintained list, ever; every
  exception is a computed structural rule.
- **L18** Clusters always reset to current code and pages; no data migration.
- **L19** Never sandbox a lane; a read-only audit lost its own evidence.
- **L20** Agent code needs no reader conditionals; portability is derived.

## 5. Open owner decisions

**All four resolved 2026-07-26 PM — see the Rulings section above.** O14
dissolved (no stored render; web-render merges in-process). O4: diagnostic +
heartbeat heap watermark. O2: refuse via the `flock` assert. Flow: the
non-adoption recommendation was REVERSED — `core.async.flow` is adopted,
Path A, with `seon.flow` implementing `flow.spi` and flow-monitor as the
visualization surface; the hand-rolled `newCachedThreadPool` is replaced by
the bounded `:compute` executor the flow launchers own.

Newly open (queued, not blocking): the homogeneous-tuple 8-cap fork lift +
`run/forms` vector-value conversion (behind the sweep); the run-opened-
before-plan-commits window still has no owning step.

## 6. What done looks like

The owner-written final system gate (`roadmap.md`) — live agents really
running for an hour, load-tested to a named wall, fast with conditions, every
smell chased — plus the reset-boundary live proof after step 6 and the
acceptance exit: the `src/seon/` diff for the photos demo capability is ZERO.

The one measurement: re-run the §18 self-attributing turn on the
three-process system with a real plan — agent A authors a corpus function
through the door, agent B calls it next turn, a human watches the canvas —
every driver component attributed within the predeclared tolerance, provider
dominant, conditions stated, transcript and datoms retained. If that turn
cannot be measured and repeated after a cluster reset, the program is not
done, whatever the suites say.
