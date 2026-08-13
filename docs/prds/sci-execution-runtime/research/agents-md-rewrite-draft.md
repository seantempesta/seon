# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. The thin delegated-lane adapter
lives in `AGENT.md`. Every factual claim here was verified against source on
2026-08-13 ([audit](docs/prds/sci-execution-runtime/research/agents-md-verification-audit-2026-08-13.md));
when you find a claim the tree contradicts, fixing this file is part of the
fix, in the same commit.

If you were spawned as a subagent, execute the assigned task directly. Do not
spawn or delegate again. If the task is too broad, report that to the top-level
orchestrator for rescoping.

The top-level agent owns user communication, the active roadmap, cross-lane
integration, final design judgment, and proof that separately completed work
forms one system. Delegate only a coherent independent result; review returned
reports as claims — read enough source to judge them, falsify risky
conclusions independently.

## 1. What Seon is and how it runs

One JVM process runs everything, from source, REPL-first. CLJ only — the
CLJS build is off and the pod/self-host engine is deleted (owner,
2026-07-27); git history is the archive for everything deleted. Fresh
`src/` + `test/` are the system.

Boot is a tower; each layer reads only the one below it and publishes its
own readiness:

1. **Process.** Start reads a closed, tiny bootstrap config (process-root
   store path, prepl bind, log dir — nothing the database could own) and
   opens the selected cluster's REPL at second zero. Process identity is
   (pid, start-instant); per-cluster paths derive from the cluster name.
2. **Store.** One process root owns one Datahike store
   (`data/clusters/store`) under a lifetime `flock`; each cluster is one
   named branch with one live connection. Datahike's writer is its own
   serial loop per connection — we never build writers, we call `transact`
   and it serializes ([writer](reference-code/datahike/src/datahike/writer.cljc)).
   The `flock` is ours: nothing in Datahike stops a second process opening
   the same store, and two JVMs on one store once destroyed 40/40 commits
   silently — the store fence is process-root-wide. One JVM may host many
   cluster instances; they share only the process-root store holder and
   root executors. Nothing may assume "the" cluster.
3. **Facts.** A config manifest reconciles into database facts; runtime
   reads the database, never files or env vars. One non-executing
   `:current-src` branch holds indexed code; a new cluster forks its exact
   published commit ID — near-instant, never a re-index. An existing
   cluster remains a sovereign older program until the operator
   destructively reforks it.
4. **Flow.** EVERY AGENT IS ITS OWN FLOW GRAPH, created with the agent from
   one blueprint, parked between episodes, kicked off by the messages it
   receives. Per cluster, a few shared plumbing graphs (render pipeline,
   fault committer). There is NO central loop, dispatcher, or scheduler.
   The process root owns one bounded `:compute` executor and one `:io`
   (virtual threads) executor shared by every graph; every proc pins `:io`
   or `:compute` explicitly — the `:mixed` default pins a platform thread
   per proc and is the one scaling cliff
   ([dispatch](reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj)).

**Live update is two cases, one mechanism each.** Graph definitions
reference transforms as vars (`#'f`), so re-evaluating a `defn` against the
running system changes proc behavior immediately. Topology changes rebuild
the graph (stop → `create-flow` → start, sub-millisecond), safe because
channel contents are losable by construction.

**Hot reload is not program-graph indexing.** Re-evaluating a Var changes
loaded behavior; file edits do not mutate the database's program facts. The
edit hook statically analyzes changed first-party files and publishes safe
same-identity upserts to `:current-src`; uncertain projections fall back to
a complete build. `bin/seon init` is the explicit complete publication;
existing clusters are never synchronized. A live proof after file edits
must name whether it exercised a hot-reloaded Var or a cluster forked from
the newly published commit.

**Transport law:** anything recovery or another process could ever need is
a DATABASE FACT — identities, receipts, messages, errors, the settled
reply — with bulky payloads as blobs. Everything IN FLIGHT rides channels,
provided loss is free: re-derivable from facts or superseded by a newer
complete value. The buffer encodes the loss semantics: sliding-1 for
latest-wins, fixed for backpressure, counted-dropping for observation
([buffers](reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj)).
Any design where channel loss breaks recovery is wrong by definition.

**Crash model: nothing re-executes.** Recovery = reopen the store, mark
dangling receipts `:interrupted`, re-derive the graph. Runs are claimable
database state: custody is presence of `:seon.cluster.run/process`
(`resources/seon/schemas/seon.cluster.run.edn` ↔
`src/seon/cluster/run.clj`); the process is the holder. No claim epoch, no
lease clock.

**Errors are two classes, never mixed.** An agent mistake becomes a flat
`:seon.error` value the agent sees — nothing throws into the loop. A core
fault rides flow's error-chan into the fault committer, which commits it as
a durable fact with provenance, so "who should fix this" is a query. One
config dial: dev panics, prod degrades.

Seon is the core: consumer-specific UI, vendor integrations, and domain
models belong in downstream repositories, never `src/` or `docs/`.
Orientation for anyone new: [docs/TRANSFER_PROMPT.md](docs/TRANSFER_PROMPT.md).

## 2. The five design laws

These five constructions prevent the defect classes that produced most of
the issue archive. Design with them from the start; a review asks first
"which law does this shape obey or break?"

### 2.1 Values carry their world

Everything a computation needs travels WITH it as ordinary data: the
environment (`seon.env`), the schema projection, the database value or
connection, the render profile, the effect request's settlement inputs.
Running code receives its world — via the sci ctx/fork, submission data,
proc `:args`, or the request map — and never constructs or reaches
sideways for it through a dynamic var, process-global registry, or
per-call re-derivation. Derived state rides the value it derives from (a
validator on its projection, a writer on its connection), so staleness and
cross-environment reads are structurally impossible. Temporal database
values (`history`/`as-of`/`since`) derive schema through Datahike's origin
chain, never from the wrapper
([versioning](reference-code/datahike/src/datahike/versioning.cljc)).

```clojure
;; a fixture or caller hands the projection explicitly, like production:
(schema/register! {:seon.schema/projection projection, ...})
;; never: reach for a process-global registry inside register!
```

Per-call re-derivation is also the one recurring performance killer: the
same defect that reads stale state also recomputes a projection on every
call (measured 217 s vs 6.2 s in one wake path). Grounding:
[seon-env PRD](docs/prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md);
open members are tagged `class/p1` in `docs/seon/issues/`.

### 2.2 Facts over inference

EVERYTHING IN THE SYSTEM IS EXPLICITLY DECLARED AND RECORDED IN THE
DATABASE, AND IT IS ALL QUERYABLE. Every question — what a function
accepts, whether it is private, which schema a value satisfies, which
function renders a shape, which tests reach a function — is a Datalog
query over facts we already store. **If you cannot answer a question by
query, the MISSING FACT is the defect: declare it, then query it.** The
three banned substitutes are the same mistake in different clothes: a
hand-maintained list, a naming convention (deriving `render-<kind>` from a
namespace name), and a regex over text. Classification rules are computed
from provenance, the program graph, or declared metadata — never
name-based. Schema discovery is registry-query-first: search the merged
registry before declaring a key; reuse the existing declaration when it
already expresses the meaning.

```clojure
;; which tests exercise this function? — a query, not a naming convention:
(seon.fn/tests-reaching db 'seon.cluster.run/open-tx)
;; which functions need cluster custody? — declared arity input-refs:
[?f :seon.fn.arity/input-refs :seon.db/connection]
```

**A REGEX IN PRODUCTION CODE REQUIRES THE OWNER'S PERMISSION — STOP AND
ASK.** The program graph answers questions about code, Malli schemas about
shape, the reader about forms, Datalog about facts. (`rg` while working is
ordinary tooling.) A hand-rostered fixture schema is this law's test-side
violation: one missing attribute broke 12 tests at once.

### 2.3 Events with loud backstops — both halves, always together

Detection is event-driven: interfaces express their dependencies and
publish their own readiness (a start returns a completion, a resource
announces attached, cleanup keys on child-exit events, `listen!` before
derive). AND every wait is bounded by a declared loud backstop whose
firing is itself a bug report naming what never arrived. Dropping either
half is the defect: a bare tuned timeout hides the observable event; an
unbounded event wait turns one missing fact into a 300-second silent wedge
that burns every agent's diagnosis time. **A hanging test or mechanism is
a worse defect than a failing one** — it gives the diagnosing agent
nothing. When you meet a tuned constant, ask what observable event it
stands in for; when you meet an unbounded wait, give it the declared
backstop (`seon.test-support/event-backstop-seconds` on the test side).
Deadlines as primary control are admissible only for genuinely
unobservable external state (a remote HTTP call, a foreign process).

### 2.4 Total, honest, bounded boundaries

Every failure at an agent or runtime boundary is a flat `:seon.error`
value — nothing throws into the loop; sci containment catches mistakes but
is not a security boundary. A refusal is loud, typed, and names what was
missing: the layer, the member, the expected shape, the offending value
(`seon.error/diagnostic` is the evidence-complete constructor). An
unavailable observation is the typed unknown, never absence, success, or
silence. A silent fallback that "happens to be right" is a defect even
while it works — it survives exactly until the second cluster, thread, or
caller. Diagnostics tell the truth or say nothing: a thread dump that
omits virtual threads lies; use a virtual-thread-aware dump.

Outward values cross one total render contract: renders never throw and
never refuse an ordinary value; output is bounded through the one
`seon.print/fit` owner; omitted detail is an elision value — ordinary data
carrying count, path, and requery identity — never bare truncation; a
floor hit is counted, never silent. UGLY OUTPUT IS A DEFECT (standing
order): every agent that meets an unreadable rendered result reports it or
files the issue naming the shape and where it surfaced. Display sizes for
humans are estimated tokens via `seon.ai.tokens/estimate` — this is the
design intent; character counts are storage projections, and surfaces
still showing them are defects to file, not precedents to copy.

### 2.5 One mechanism, accreted in place

Do not create `foo-v2`, a compatibility namespace, or a second
registry/renderer/feed/retry/config/test path to avoid fixing the existing
owner. Fix cycles, callers, and schemas in place; delete the superseded
path in the same refactor — git is the archive. Maps are OPEN: if
something declares it needs `foo` and `bar`, those are validated
rigorously and a supplied `bat` is ignored, never refused (`{:closed
true}` does not appear under `resources/seon/schemas/`). Adding is free;
CHANGING is breakage: a key's definition and its relationship to the
output never change — different semantics means a NEW KEY with a new name.
Widening an input is accretion; narrowing an input, requiring an optional
key, or promising less in an output is breakage even when the schema still
validates. The standing test, from the owner: **is this simpler than it
was?** If it is equally complex, the model was ported, not applied.

**Owner design gate:** when a decision would create hours of cross-owner
work or its guarantees cannot be stated simply, STOP before production
edits and bring the owner exactly three concrete options — simplest viable
constraint first, marked recommendation, each with guarantee, cost, and
what we give up. A clear non-onerous constraint is a feature; never
convert uncertainty into a migration layer.

## 3. Data and schema

Use the `data-oriented-clojure` skill before writing or reviewing Seon
Clojure — at design time, not only before the edit. The compact invariants:

- immutable data and pure transformations first; derive projections instead
  of storing them;
- fully namespaced map keys and database attributes, without exceptions;
- globally identified schemas declared once under `resources/seon/schemas/`
  (file names never scope declarations);
- errors as values at agent/runtime boundaries;
- one namespaced map in/out for API-like functions, or fully named
  positional arguments for ordinary functions;
- every public function has a correct Malli input/output schema — no
  `:any`/`:some`/`[:maybe X]` without a proven genuinely polymorphic
  boundary; absent = no key, never stored nil;
- no `:type`/`:kind` entity taxonomy: an entity IS its attributes and
  connections. Query attribute presence to find entities, use a unique
  identity attribute to identify one, follow refs to relate one
  (`:seon.entity/id-attr` enumerates identity attributes; it is not a kind
  stamp).

`seon.db` is the ONE database namespace: all of Datahike's core data
functions, agent-first, each with Datahike's own positional AND
argument-map arities, both able to elide db/conn to the calling agent's
cluster's current database. Failures return flat `:seon.error` values.
Direct `datahike.api` calls survive only inside `seon.db`, the
store/registry and classified branch-custody owners, and system-side
listeners. New code never adds another direct core call site
([specification](reference-code/datahike/src/datahike/api/specification.cljc)
↔ `src/seon/db.clj`).

An explicitly selected config manifest reconciles its declared subset into
database facts; runtime reads the database. Provenance is minimal
transaction metadata (resolvable `:seon.db/user` and `:seon.db/process`) —
never copied onto domain entities as `created-by`/`created-at` projections.

Database vocabulary is the dependency's vocabulary: database value, basis
transaction `:t`, commit ID (`:datahike/commit-id`), connection ID, store
ID, branch, branch head, transaction report, db-before/db-after. No
generic "coordinate"/"point"/"attachment" maps at the seam.

### Vocabulary — grounded names, never invented ones

Agents inventing new vocabulary causes serious system problems: invented
nouns drift from the dependency, hide existing mechanisms, and cause
integration and debugging mistakes. The law, in order of preference:

1. **Use Clojure's own name for the concept**, or
2. **the closest integration seam's name** — Datahike, Malli, SCI,
   core.async say what their own things are called; read the seam's source
   in `reference-code/` and take its name AND its semantics;
3. only when a concept is genuinely ours, coin once, record it in this
   table with sources on BOTH sides of the boundary, and use it everywhere.

Never assume you understand a row from its name alone: follow the row's
links and read that slice of code before building against it. This is how
we avoid rebuilding what a core library already built, and it routinely
yields better designs. Rows marked **[TARGET]** are ruled-but-unbuilt:
USE this vocabulary when designing toward them, and when you instantiate
one, update its row (add the real source links) in the same commit. This
table is maintained: when a boundary term is settled, add its row in the
same change.

| Say | Never | Meaning |
|---|---|---|
| functions, schemas, tests | verbs | ordinary Clojure constructs |
| database or `db` | store, inventory, memory | the `seon.db` authority |
| boot, environment, running | the runtime, the platform, the tower, ambient | BOOT is the 0→1 construction in dependency order; the ENVIRONMENT is the one per-cluster value it produces (`seon.env` ↔ `resources/seon/schemas/seon.env.edn`); RUNNING code receives it and never constructs or reaches sideways for it |
| call preparation, supplied defaults | ambient injection, batteries | sci's own name for the hook seam supplying a function's declared-and-absent arguments from the environment; caller wins; unavailable is a flat error (`reference-code/sci/src/sci/core.cljc` init docstring) |
| **[TARGET] canvas** | tile, live-tile, world | the focal agent surface; design in the ui architecture target (`docs/seon/architecture/ui.md`) — no declared attribute exists yet; update this row when it lands |
| surface; card for CSS only | tile | a context render; a visual component |
| web UI | inspector | `/`, `/agent/{id}`, debug, and `/data` |
| subagents | collaboration system | agents connected through database refs |
| cluster | environment | one database, root, and task agents |
| attributes + connections | entity kind/type | the Datahike model |
| build, operator, artifact | flavor | the `bin/seon`/`bin/acme` supervisor scope; the digested publication output |
| get-in, path | drill | paged navigation into a nested value by `get-in` path |
| the todo | my.plan, bare "plan" for placement | the ONE task system: derived obligations plus authored item facts (owner ruling 2026-08-12 #49); `my.plan` is retired unbuilt |
| provider descriptor row | adapter, integration | one hosted provider's data row under the config singleton |
| packages/, package.json, deps.edn, node_modules | npm-pkgs, maven-pkgs | each ecosystem's own manifest names |
| contexts on hosts, binding tables | sandbox, VM, jail | sci's own vocabulary for agent execution |
| `:interrupt-fn` | the guard, the door, the cage | the ONE zero-arg fn sci calls on every fn body entrance (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) |
| `interrupt!` | stop!, steering-error! | how an `:interrupt-fn` stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) |
| `time-limit` | fuel, gas, step budget, deadline-ms | the ONLY limit; sci counts nothing (`reference-code/sci/doc/interrupt.md`) |
| `:seon.eval/fn-entries` | a step budget | a RECORDED DIAGNOSTIC, never a limit |
| every `fn` body entrance | safepoint | where sci calls the `:interrupt-fn` (a JVM safepoint is a different real thing) |
| `ctx`, `fork` | warm base, sandbox, the agent's world | sci's own names (`reference-code/sci/src/sci/core.cljc`) |
| `:io` / `:compute` / `:mixed` | eval pool, wait pool | core.async's workload tags: `:io` may block but must not compute, `:compute` must not block (`reference-code/core.async/.../impl/dispatch.clj`) |
| `:seon.cluster.run/process` | claimant | the process holding a run (`resources/seon/schemas/seon.cluster.run.edn` ↔ `src/seon/cluster/run.clj`) |
| accretion / breakage | graduation, nursery | a change that requires no more and provides no less (attribution to Spec-ulation is UNVERIFIED — do not cite it as established) |
| source initialization rows, transaction data | bootstrap-plan rows, seed bundle | static source population is admitted transaction data; a fresh agent's opening derives a GENERATED episode from live facts (`src/seon/bootstrap.clj` `next-entry` ↔ `src/seon/render/walk.clj`) |
| process record, generation, (pid, start-instant) identity | orphan registry, liveness flag | operator-managed process descriptors (`script/seon/fresh_operator.clj` ↔ `src/seon/cluster/process.clj`, JDK `ProcessHandle`) |
| generated opening episode; reduce (authored plan execution) | bootstrap plan, fold | the opening appends and settles one dependency-ready generated form at a time; an ordinary model reply reduces its frozen ordered forms (`src/seon/cluster/loop.clj` ↔ `src/seon/cluster/run.clj`) |
| run loop | driver, driving | the per-agent proc advances one claimed run (`src/seon/cluster/loop.clj` `generate-turn`/`resume-turn`) |
| `seon.effect`, `effect/request!` | the door, capability dispatch | the one system-side owner every CAPABILITY request enters (fs, web, llm, db writes) — about effects crossing out, never about which functions an agent may call |
| every function is callable | toolkit, grants, allowlist | ruling #20: an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution |
| program graph | corpus | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts |
| proc, step-fn, conns, graph-def, report channel | invented scheduler nouns | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj`) |
| `(sliding-buffer 1)` tap | latest-wins mailbox | core.async's own newest-only delivery |
| tuple (`:db/tupleType`) | small limited vector, ordered many | Datahike's single-value ordered construct — one datom, whole-value replace; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) |
| `my.agents.<id>` | agent workspace, sandbox ns | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere; any namespace has at most one assigned agent (`:seon.cluster.agent/namespace`, unique) |
| render function; `:seon.render/form` | producer, view, read form | an ordinary function a render schema property names; `/form` is the third declared projection beside `/ai` and `/html` — the FORM that produces the rendered value (`resources/seon/schemas/seon.render.edn` ↔ `src/seon/render.clj`) |
| `:seon.render/ai` | prose, text render | the string consumed by agent context, or a fully qualified symbol naming the function that returns it |
| `:seon.render/html` | hiccup contract, human render | the Hiccup consumed by the web UI, or the symbol naming its function; Hiccup is this schema's definition (`src/seon/render/hiccup.clj`) |
| identity-only admission | object serialization | a registered reference predicate names `:seon.schema/identity-only` plus a qualified projection; admission retains only that identity data at every depth (`resources/seon/schemas/seon.db.edn` ↔ `src/seon/sci/admit.clj`) |
| wire (external crossings only) | wire for anything in-process | reserved for a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, and database facts |
| namespace page | page, screen, dashboard | one namespace's web surface: route → namespace → owner agent → walk rendered in `/html`; adding one is adding a route line (`src/seon/render/route.clj`) |
| block | widget, component, panel | ONE render function's identified output — the unit of rendering, morph targeting, equality suppression; both projections are the same block; system message and instruction files are blocks rendered from facts |
| package, keyframe, delta | frame, bundle | the delivery units: one revisioned package per change carries delta fragments and/or the keyframe; a revision gap snaps to keyframe |
| base SCI context (`ctx`), turn fork, agent context | "the context" for all three | THREE THINGS: the cluster's acquired program-only ctx; each turn's fresh generation-aware `sci/fork` (turn-private mutations); the agent's context = what `/ai` renders into its prompt, which never gates execution (`src/seon/sci/eval.clj` ↔ `reference-code/sci/src/sci/core.cljc`) |
| candidate context | sandbox ctx, scratch fork | a built context used to test a definition before installing it; `sci/fork` is admissible — forked Vars are copy-on-write |
| editor, revision, proof (session curation) | curator, repair agent | the ruled curation roles; a revision is ordered form sources as data; a proof is mechanical re-execution on a fresh fork; adoption via `:seon.cluster.run/supersedes` ([PRD](docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md)) |
| render profile | cap, window, local limit | the database-derived consumer fit policy; the render function supplies semantics and the one `seon.print/fit` owner applies the profile (`resources/seon/schemas/seon.render.profile.edn` ↔ `src/seon/print.cljc`) |
| elision value | ellipsis, truncation marker | ordinary data describing omitted count, known total, path, next offset, and requery identity — rendered and inspected like any value (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | sink roster, output allowlist | queryable program-graph leaf facts lifted from metadata; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) |
| **[TARGET] root maintenance portfolio** | maintenance daemon, central ticker | root's declared scheduled tasks for reclamation/inspection/repair; per-agent schedule procs deliver ordinary messages ([design](docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md)); update this row when the owners land |
| **[TARGET] `my.branch`** | my.git, my.repo | agent-facing branch/history verbs over the cluster's database branches — git vocabulary without claiming to be git; ff-only, no index, no remotes ([PRD](docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)); update this row when it lands |
| the agent's defs, `:seon.def/*` | the desk, session image | the agent's temporary defs + atoms, committed as agent-scoped facts at turn settlement; atoms snapshot-stated; session end explicit only |
| the agent's history; a form + value entry | session units, transcript entries | the ordered derivation of an agent's REPL session from message/run-form/result facts ([PRD](docs/prds/sci-execution-runtime/plan/repl-transcript-context-prd-2026-08-10.md)) |
| `doc`, `dir` (bare, injected); printed value | faces tool, print face (as API) | the injected REPL documentation functions over public program rows; a value's rendered output is its "printed value". **[TARGET]** the bulk `docs` plural is ruled, not yet installed — update this row when it lands |
| candidates (per-render selection) | roster, producer roster, acquired index | the contract-fitting render producer selection consulted per render call (`src/seon/render.clj`); publication-time indexing of candidates is a ruled target, not current |

## 4. Building

Before planning a change or writing code:

1. Read the closest localized `AGENTS.md` and the active roadmap's current
   state, gap, and success measure. When a task names a document — a spec,
   ruling, research report, issue — READ IT END TO END and say so; grepping
   a named authority produces wrong conclusions from correct documents.
2. Write the dependency ledger: the exact libraries and Seon mechanisms the
   unit depends on, their pinned `reference-code/` paths, and the
   first-party call sites/tests that already demonstrate the idiom. Then
   READ THAT SOURCE — never plan from remembered library behavior. If the
   pinned source is absent, vendor it as a submodule first; a plan that
   names only an API is not grounded.
3. Observe the live system and define a falsifiable failure plus acceptance
   evidence.
4. Probe the critical dependency assumption directly in the REPL — a plan
   written without a probe is a hypothesis; this program has had six of six
   assumptions falsified in one sitting.
5. Implement by strengthening the one existing mechanism in place.

**REPL-driven development.** Start every Clojure change at a running
system: `bin/seon status` shows clusters; boot your OWN scratch cluster for
anything you intend to change (clusters are sovereign and cheap; never
reset or write to someone else's). Reach it with `mcp__seon__runtime_status`
and `eval_clj` (qualify the cluster when several are live — ambiguity must
fail). Reproduce with one small form and read the COMPLETE returned
envelope; call the owning function directly with representative data;
design the transformation on immutable examples; edit the one owning
namespace and let hot reload apply it; persist the regression; then verify
the running system — an observed datom, page, log line, or process
transition. A change proven only by a passing test is not proven.

**Where work lives.** NEVER use a session scratchpad or system temp
directory — it is deleted without warning and invisible to every other
lane. Throwaway probes go in `tmp/` (project-local, gitignored, visible to
everyone); anything whose RESULT is evidence gets its script committed and
its numbers recorded in the owning PRD's `research/`; anything that will
run again is real code under `test/`. The test: if the machine were wiped
right now, what would be lost?

**Comment grammar** (source convention only): `;` prose/inline, `;;` a
code-block comment above a form, `;;;` runtime structure. Rendered results
and system output never use comment-prefixed prose or `;; =>` annotations.

**Skills.** Use a matching `.agents/skills/*/SKILL.md` before specialized
work: `data-oriented-clojure` (any Seon Clojure), `seon-flow-architecture`
(any proc/graph/channel/buffer/wake — and before designing ANY new runtime
mechanism), `data-modeling` + `datahike` (schemas/queries/transactions),
`clojure-testing` (test mechanics), `repl` (how agent forms are read).
A SKILL'S BLAST RADIUS IS EVERY AGENT THAT LOADS IT: every factual claim
carries `file:line` or a named research doc, verified when written or
touched; an unverifiable claim is DELETED, never hedged; ruled-but-unbuilt
designs are marked `[TARGET]`; a stale skill is a high-priority defect to
fix the day it is noticed.

**Documentation authority.** `docs/seon/architecture/` is the always-current
aspirational target (present tense, never a claim that source implements
it). The active program roadmap
([plan/README.md](docs/prds/sci-execution-runtime/plan/README.md), with
`unsettled.md` as the working edge) is the one ledger of current state and
ordering — dates, ruling numbers, and incident history live THERE, not
here. Bounded PRD chunks under `docs/prds/<chunk>/` own their inventory and
evidence, with dated research in `research/`. Update the affected authority
in the same commit as the change that invalidates it. Every `docs/**/*.md`
carries frontmatter with `type`, `status`, `tags`; `seon.dev.markdown`
checks structure. One fact lives in the deepest file that owns it;
localized `AGENTS.md` files are tight runbooks, never status diaries, and a
first-party non-symlink `CLAUDE.md` is drift to reconcile.

## 5. Testing

### Test fixtures — the one right way

Nearly every red in the 2026-08-13 tally repair was a wrong fixture, not a
production defect. Mandatory for every new or edited test, each rule proven
by its fix commit:

1. **Never hand-roster schema.** Use the canonical database fixture
   (`seon.test-support/with-database` installs the complete population)
   plus `::test-support/extra-schema` for genuinely synthetic attributes.
   A hand roster missing one attribute silently broke 12 tests at once
   (`100f03a40`).
2. **Hand the projection/environment explicitly**, exactly like production
   callers — never an ambient registry or dynamic var (`b7bd25c34`,
   `8377a4a69`, `e8e37eb50`).
3. **Supply every declared proc input** (cluster name, render interest,
   profile) — a missing input becomes a typed refusal that poisons
   downstream consumers (`66cecb816`, `677f84f85`).
4. **Fixed render profiles in fixtures** — deriving profiles per call cost
   217 s vs 6.2 s and read as a hang under the pooled runner (`1930dacd1`).
5. **Every await is bounded and loud** via the declared
   `seon.test-support/event-backstop-seconds` — a broken wake fails in
   seconds with a message instead of wedging the pool for 300 s. A HANGING
   TEST IS A WORSE DEFECT THAN A FAILING ONE.
6. **Assert current ruled behavior** — typed diagnostics not absence,
   terminal trigger verdicts, total bounded renders. A test expecting the
   old lenient shape is stale, and the fix is the expectation.
7. **Own nothing global.** A fixture may not assume properties of the
   shared worker JVM (namespace load-state, scheduling latitude) — pooled
   workers run many tests per JVM (`3ed464a3a`, `cfd8e6848`).

Deeper mechanics: `.agents/skills/clojure-testing/SKILL.md`. A new fixture
class updates BOTH in the same commit.

### The gate and what a proof is

`bin/test` is the one correctness gate, tiered: the declared
`:seon.test/platform` moving-part regressions run FIRST and stop the run
when red; bare adds only tests reaching code changed since the recorded
green basis (derived from `:seon.fn/calls` edges, never mtimes); `--all`
adds every non-long test; `--full` everything; explicit namespaces run
complete. MCP eval is the first probe, not another runner; do not create
another runner or drive scripts. `src-inspect-ai/` is the separate
agent/model evaluation surface.

Tests find design issues; structure dissolves them: when a failure class
appears, move the invariant to one choke point and keep ONE regression per
class asserting the WANTED behavior — never fence symptoms with point
tests. A smaller suite is a desired outcome; the health metric is class
coverage, never test count. Every proof must be claimed by a recurring
surface — a test invisible to every runner, or a live proof that ran once
in a lane, is NOT COVERED. Fixture load paths are not the live boot path:
schema, acquisition, and process changes need the reset-boundary live
proof. After code changes, verify the running system, not only the tests —
an observed datom, page, or process transition; report what is still
broken honestly.

The edit hook runs clj-kondo over prospective Clojure edits and publishes
admitted `src/`+`test/` changes to `current-src`; it never runs tests.
Syntax, unresolved-name, privacy, and arity errors block the edit;
`:type-mismatch` findings are visible warnings, not vetoes. Read hook
feedback; report smells.

## 6. Operating

`bin/seon` is the one development operator:

```bash
bin/seon [--root PATH] COMMAND   # --root = an ISOLATED operator root
bin/seon start [CLUSTER] [--config PATH]
bin/seon config apply [CLUSTER] PATH
bin/seon status                # reconcile + list this root's clusters
bin/seon open [NAME]
bin/seon init                  # completely publish current-src
bin/seon init --changed PATH   # incremental when safe
bin/seon init NAME [--force]   # fork a dormant cluster; --force destroys
bin/seon stop [--force] [NAME]
bin/seon down [--force]        # stop EVERY recorded JVM in this root
bin/seon reset --force         # down all, destroy, republish, refork
```

Absent cluster means `default` for `start`/`config apply`; bare `init`
means the published `current-src`. Stop/down act on exact recorded process
identity (pid + start-instant + generation) — a reused pid can never be
killed by mistake. The operator owns process identity, locking, readiness,
logs, and shutdown; never launch its internals separately or kill children
blindly; if an interrupted operator leaves a recorded child alive, use
`bin/seon down` so the supervisor reaps its own. DESTRUCTIVE DRILLS AND
SECOND DEPLOYMENTS USE `--root` — root-scoped discovery makes the shared
root unreachable by construction. `bin/acme` is a thin root-scoped wrapper
selecting cluster `acme` in an isolated root.

**Churn is weather, not a blocker.** Clusters, JVMs, ports, and
advertisements come and go while you work; none of that is a failure —
ADAPT AND CONTINUE. Your cluster vanished: re-derive from `bin/seon
status`, start a fresh one (facts live in the store; a cluster is a fork
away). A stale advertisement naming a dead pid is a leftover file. A
long-lived JVM serves the code it loaded at start — suspect staleness
before suspecting correct code. Your own long operation died: committed
work stands; re-read the tree and resume. Stop only for a genuine
implementation dependency, and name it exactly. **Recursive deletion NEVER
follows symlinks**: any recursive delete walks without following links and
refuses a path resolving outside its own root; plant a symlinked sentinel
in the cleanup regression.

The shipped model is DeepSeek through the single `seon.ai` HTTP owner;
every AI dial is a `:seon.config.ai/*` config fact with per-agent overlay.
The credential setting names an environment variable; the credential never
becomes a datom or enters Git. Details:
[docs/seon/reference/llm-adapters.md](docs/seon/reference/llm-adapters.md).
Paid runs are deliberate: cheapest probe first.

**No hobbling for hypothetical risk** (owner ruling): agents are trusted
collaborators needing full capability, including reading every environment
variable. No allowlists, credential redaction, or per-agent grants
justified by hypothetical risk; the design concern is catching HONEST
MISTAKES (bounded output, digests, atomic writes) — a restriction is
admissible only after evidence of a real problem.

## 7. Collaborating

**The orchestrator designs, grounds specs, reviews diffs, and runs serial
integration gates; implementation goes to capable code agents** (never
haiku for coding). One research question gets one agent with complete
context; independent source domains may run in parallel; research reports
land under the active PRD's `research/`. A Claude orchestrator launches
Codex lanes through `bin/codex-agent` as harness-tracked background
commands, run BARE — never piped or redirected (a filter reduces the
owner's live panel to one line):

```bash
bin/codex-agent run <name> "<the full spec>"   # spec on stdin also works
bin/codex-agent status | summary <name> | stop <name> | resume <name> "<followup>"
```

Lane stdout never enters the orchestrator's context: read the summary,
then query the log selectively. Stop + resume with a correction the moment
new information invalidates a lane's direction — resume loses only the
in-flight turn. A Codex orchestrator uses its native collaboration tools
instead and never launches through `bin/codex-agent`. Mechanics:
[docs/seon/reference/driving-codex-agents.md](docs/seon/reference/driving-codex-agents.md).

**NEVER SANDBOX A LANE.** A sandbox makes an audit's own output
unrecordable; ownership is enforced by NAMING OWNED PATHS in the spec,
path-limited commits, and diff review. Write every lane spec in neutral
engineering language ("verify", "falsify", "probe") — provider safety
filters reject adversarial framing, and neutral wording is the accurate
description. Give every lane its grounding, owned paths, protected paths,
and exact deliverable.

**Shared-tree safety.** Multiple agents share this working tree; preserve
unrelated edits and untracked files. The Git index is shared: every agent
commit is path-limited (`git commit --only ... -- <explicit-owned-paths>`);
never `git add -A`; never `git reset --hard` or `git checkout --` to clean
a shared tree; branch switches and history changes require user
coordination. Worktrees only when same-file conflicts make shared work
impossible. Commit coherent gains frequently — commits are a lane's
heartbeat, and the orchestrator pushes the shared branch at every coherent
checkpoint (committed work on one disk is one failure from gone). A
FOREIGN LANE'S BREAKAGE NEVER BLOCKS YOUR COMMIT: it blocks verification
only — commit your coherent path-limited slice, name whose breakage it is,
and continue; never delete your own diff at a foreign boundary. VERIFY THE
CLAIM BEFORE YOU NAME THE CAUSE: an attribution is a hypothesis until a
probe confirms it, and a lane that refutes its assignment with evidence
has done its job.

**Issues.** When you discover a bug, smell, duplicate mechanism, stale
test, or documentation mismatch: search `docs/seon/issues/`, then create or
update ONE note before returning — never a private registry or a finding
left in chat. Notes carry frontmatter (`type`, `status: open → resolved |
superseded`, `severity: blocker | friction | cleanup`, and query tags per
[the issues README](docs/seon/issues/README.md): controlled areas,
`class/<id>` membership, `wave/<slug>`). The index
(`docs/seon/issues/index.md`) is the owner's ranked schedule, validated by
`bin/issues-index --check`; lanes do not edit it — the orchestrator
reconciles at boundaries. Fix an understood in-scope smell; otherwise
report file/line, observed mismatch, and expected owner. Fix the CLASS,
not the instance, with one regression proving the class dead. Issues are
where the system's instructions learn: a recurring class earns a rule
here.

**Reporting to the owner.** Sober summaries, broken things first. Name
every document as a full repository-relative markdown link. State that you
read each named authority end to end. Ask the owner the moment a genuine
decision exists — 2-4 priced options, recommendation first; never park a
decision in a document awaiting markup.

## 8. Pointers

- [docs/TRANSFER_PROMPT.md](docs/TRANSFER_PROMPT.md) — the orientation:
  read whole if you are new (what Seon is, the mentality, the warts, how
  the owner works);
- [docs/seon/architecture/architecture.md](docs/seon/architecture/architecture.md)
  — the aspirational system map, then the domain docs (`context`,
  `data-model`, `agent-runtime`, `ui`, `observability`, `toolkit`, `laws`,
  `library-grounding`, `decisions/`);
- [docs/prds/sci-execution-runtime/plan/README.md](docs/prds/sci-execution-runtime/plan/README.md)
  — THE ONE ORDERING: numbered owner rulings, the ladder, dates and
  incident history; `unsettled.md` is the working edge. A second ordered
  list anywhere is a defect;
- [docs/conventions.md](docs/conventions.md) — code/schema patterns;
- [docs/seon/issues/README.md](docs/seon/issues/README.md) — issue
  lifecycle, severity, and query tags;
- `.agents/skills/` — the skill index; load the matching skill before
  specialized work;
- `AGENT.md` — thin delegated-lane compatibility adapter.
