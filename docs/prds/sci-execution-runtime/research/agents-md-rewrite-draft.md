---
type: research
status: draft
tags: [research, docs, architecture]
---

# Seon — shared instructions

(DRAFT of the root `AGENTS.md`; this frontmatter block is stripped on
promotion — the root file carries none.)

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. The thin delegated-lane adapter
lives in `AGENT.md`. When the tree contradicts a claim here, the claim is the
bug: fix this file in the same commit as the change that exposed it.

If you were spawned as a subagent, execute the assigned task directly. Do not
spawn or delegate again. If the task is too broad, report that to the top-level
orchestrator for rescoping.

## How we work here

**This is the second implementation.** The first one worked — for months —
and was torn down deliberately by its author, because he had learned enough
to build it properly. That fact should reorganize how you work: almost
everything you are asked to build has been built before, and the previous
version is readable through Git history. `git show` and `git log` are the
quarry; `docs/prds/*/research/` holds dated investigations with `file:line`
evidence and measured numbers; `reference-code/` vendors ~90 dependencies as
submodules so their semantics can be READ rather than remembered. The prime
directive is not "write good code" — it is **do the archaeology before you
design, then design something better than what you found**. The fresh tree is
not zero knowledge; it is zero *baggage*: every piece re-earns its place.

**Ask what the dependency already does before you build anything.** sci keeps
a live env; konserve has GC and content-addressed blobs; Datahike branches
are head pointers, not copies. If your design recomputes something a
dependency already maintains, the design is wrong — this exact mistake once
put a 283 ms cold-start rebuild on every agent turn. The cheapest place to
delete code is before it exists: build the smallest real thing and let live
falsifiers attack the design while it is still a decision.

**Prefer dissolution to addition.** The best change deletes a mechanism.
When you meet a tuned constant, ask what observable event it stands in for.
When a fix feels like hardening a mechanism against its own normal
operation, stop and ask whether the mechanism belongs on that path at all.

**Derive state; do not remember it.** This project has had six of six
assumptions falsified in one sitting. Verify prose — including this file —
with one live command before acting on it. Nothing stores what a query can
derive: `open?` means no `closed-at`; a boolean is legitimate only when
someone genuinely asserts the false.

**The recurring failure class of this whole project is a check that reads
ABSENCE OF SIGNAL as health** — a query against a descriptor that no longer
exists, a regression walking less than the writer admits, a monitor that
stays silent through a crash. When you write any check, ask what it reports
when its subject is absent. If the answer is "fine," the check is worse than
nothing.

**Write it down in the same beat.** Rulings into the plan README, state into
the working edge, settled terms into the vocabulary table, defects into
issues — in the turn it happens, path-limited commit. Conversation memory is
never the only record.

## 1. What Seon is and how it runs

One JVM process runs everything, from source, REPL-first. CLJ only — the
CLJS build is off and the pod/self-host engine is deleted; git history is
the archive for everything deleted. Fresh `src/` + `test/` are the system.

The system has exactly two states: **boot** and **running**. Boot is the
0→1 construction in dependency order, and it opens the REPL at second zero
so a boot failure is always fixable live; each layer reads only the one
below it and publishes its own readiness. Then running code takes over:
platform infrastructure plus agents, all receiving the environment boot
produced. The boot order:

1. **Process.** Start reads a closed, tiny bootstrap config (process-root
   store path, prepl bind, log dir — nothing the database could own).
   Process identity is (pid, start-instant); per-cluster paths derive from
   the cluster name.
2. **Store.** One process root owns one Datahike store (today at
   `data/clusters/store`) under a lifetime `flock`; each cluster is one
   named branch with one live connection. Datahike's writer is its own
   serial loop per connection — we never build writers, we call `transact`
   and it serializes ([writer](reference-code/datahike/src/datahike/writer.cljc)).
   The `flock` is ours: nothing in Datahike stops a second process opening
   the same store, and two JVMs on one store once destroyed 40/40 commits
   silently. One JVM may host many cluster instances; nothing may assume
   "the" cluster.
3. **Facts.** A config manifest reconciles into database facts; running
   code reads the database, never files or env vars. One non-executing
   `:current-src` branch holds indexed code; a new cluster forks its exact
   published commit ID — near-instant, never a re-index. An existing
   cluster remains a sovereign older program until destructively reforked.
4. **Flow.** EVERY AGENT IS ITS OWN FLOW GRAPH, created with the agent from
   one blueprint, parked between episodes, kicked off by the messages it
   receives; per cluster, a few shared plumbing graphs (render pipeline,
   fault committer). There is NO central loop, dispatcher, or scheduler.
   The process root owns one bounded `:compute` executor and one `:io`
   (virtual threads) executor; every proc pins `:io` or `:compute`
   explicitly — the `:mixed` default pins a platform thread per proc and is
   the one scaling cliff
   ([dispatch](reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj)).

A **cluster** is one database branch, its agents, and their shared plumbing;
boot produces ONE environment value per cluster (`seon.env` ↔
`resources/seon/schemas/seon.env.edn`), and each agent receives a scoped
view of it (`seon.env/scope` carries the agent id). The cluster is the
shared substrate; the scoped environment is what an agent's code actually
holds.

**Live update is two cases, one mechanism each.** Graph definitions
reference transforms as vars (`#'f`), so re-evaluating a `defn` against the
running system changes proc behavior immediately. Topology changes rebuild
the graph (stop → `create-flow` → start), safe because channel contents are
losable by construction.

**Hot reload is not program-graph indexing.** Re-evaluating a Var changes
loaded behavior; file edits do not mutate the database's program facts. The
edit hook statically analyzes changed first-party files and publishes safe
same-identity upserts to `:current-src`; uncertain projections fall back to
a complete build. `bin/seon init` is the explicit complete publication;
existing clusters are never synchronized. A live proof after file edits must
name whether it exercised a hot-reloaded Var or a cluster forked from the
newly published commit.

**Transport law:** anything recovery or another process could ever need is
a DATABASE FACT — identities, receipts, messages, errors, the settled
reply — with bulky payloads as blobs. Everything IN FLIGHT rides channels,
provided loss is free: re-derivable from facts or superseded by a newer
complete value. The buffer encodes the loss semantics: sliding-1 for
latest-wins, fixed for backpressure, counted-dropping for observation.
Any design where channel loss breaks recovery is wrong by definition.

**Crash model: nothing re-executes.** Recovery = reopen the store, mark
dangling receipts `:interrupted`, re-derive the graph; the agent adapts
from derived context. Runs are claimable database state: custody is
presence of `:seon.cluster.run/process`
(`resources/seon/schemas/seon.cluster.run.edn` ↔ `src/seon/cluster/run.clj`).
No claim epoch, no lease clock. Absence is the one representation a dead
process cannot corrupt.

**Errors are two classes, never mixed.** An agent mistake becomes a flat
`:seon.error` value the agent sees — nothing throws into the loop. A core
fault rides flow's error-chan into the fault committer, which commits it as
a durable fact with provenance, so "who should fix this" is a query. One
config dial: dev panics, prod degrades.

Seon is the core: consumer-specific UI, vendor integrations, and domain
models belong in downstream repositories, never `src/` or `docs/`.
Orientation for anyone new: [docs/TRANSFER_PROMPT.md](docs/TRANSFER_PROMPT.md).

## 2. The five design laws

These constructions prevent the defect classes that filled the issue
archive. Design with them from the start; a review asks first "which law
does this shape obey or break?"

### 2.1 Values carry their world

Everything a computation needs travels WITH it as ordinary data: the
environment (`seon.env`), the schema projection, the database value or
connection, the render profile, an effect request's settlement inputs.
Running code receives its world — through the sci ctx/fork, submission
data, proc `:args`, or the request map — as arguments and values. It never
fetches its inputs from somewhere else at call time: not from a dynamic
var, not from a process-global registry or atom, not by re-deriving them
fresh on every call. Derived state rides the value it derives from (a
validator on its projection, a writer on its connection), so staleness and
cross-environment reads are structurally impossible. Temporal database
values (`history`/`as-of`/`since`) derive schema through Datahike's origin
chain, never from the wrapper
([versioning](reference-code/datahike/src/datahike/versioning.cljc)).

```clojure
;; a caller or fixture hands the projection explicitly, like production:
(schema/register! {:seon.schema/projection projection, ...})
;; not: register! silently reading a process-global registry
```

Fetch-at-call-time is also the recurring performance killer: the same
defect that reads stale state also recomputes a projection on every call
(measured 217 s vs 6.2 s in one wake path). Grounding:
[seon-env PRD](docs/prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md);
open members are tagged `class/p1` in `docs/seon/issues/`.

### 2.2 Facts over inference

EVERYTHING IN THE SYSTEM IS EXPLICITLY DECLARED AND RECORDED IN THE
DATABASE, AND IT IS ALL QUERYABLE. Every question — what a function
accepts, whether it is private, which schema a value satisfies, which
function renders a shape, which tests reach a function — is a Datalog
query over facts we already store. **If answering a question requires a
convoluted reconstruction — joining text, guessing from names, walking
files — stop: the missing fact is the root problem. Declare it at the one
indexing/declaration seam, then query it.** Queryability is also how bugs
get FOUND: a question the database cannot answer is a defect report about
the data model, not an inconvenience to work around. The three banned
substitutes are one mistake in different clothes: a hand-maintained list, a
naming convention, and a regex over text. Classification rules are computed
from provenance, the program graph, or declared metadata — never
name-based. Schema discovery is registry-query-first: search the merged
registry before declaring a key.

```clojure
;; which tests exercise this function? — a query, not a naming convention:
(seon.fn/tests-reaching db 'seon.cluster.run/open-tx)
;; which functions need cluster custody? — declared arity input-refs:
[?f :seon.fn.arity/input-refs :seon.db/connection]
```

**A REGEX IN PRODUCTION CODE REQUIRES THE OWNER'S PERMISSION — STOP AND
ASK.** The program graph answers questions about code, Malli schemas about
shape, the reader about forms, Datalog about facts. (`rg` while working is
ordinary tooling.)

### 2.3 Bounded, event-driven execution — both halves, always together

Detection is event-driven: interfaces express their dependencies and
publish their own readiness (a start returns a completion, a resource
announces attached, cleanup keys on child-exit events, `listen!` before
derive). AND nothing in this system is allowed to run indefinitely: every
execution surface carries its declared bound, enforced at the seam that
admits the work — sci evals run under the one `time-limit`/`:interrupt-fn`
([interrupt](reference-code/sci/doc/interrupt.md)); capability calls cross
the effect door with deadline and output caps as config facts; test events
wait under the declared `seon.test-support/event-backstop-seconds`; the
suite has a liveness watchdog that dumps every worker JVM. A bound firing
is itself a bug report naming what never arrived — never a silent retry.
Dropping either half is the defect: a bare tuned timeout hides the
observable event, and an unbounded event wait turns one missing fact into a
silent wedge that burns every agent's diagnosis time. **A hang is a worse
defect than a failure** — it gives the diagnosing agent nothing. When you
build a new execution surface, its bound is part of the seam's contract,
not an option; unbounded work should be unconstructable.

### 2.4 Total, honest, bounded boundaries

Every failure at an agent or runtime boundary is a flat `:seon.error`
value — nothing throws into the loop. All public functions carry complete
Malli contracts and are instrumented from the program graph; a function
whose declared contract fails does not run — the violation is a typed value
naming the function and the offending argument. A refusal names what was
missing: the layer, the member, the expected shape, the offending value
(`seon.error/diagnostic` is the evidence-complete constructor). An
unavailable observation is the typed unknown, never absence, success, or
silence. A silent fallback that "happens to be right" is a defect even
while it works — it survives exactly until the second cluster, thread, or
caller. Diagnostics tell the truth or say nothing: a thread dump that omits
virtual threads lies.

Outward values cross one total render contract: renders never throw and
never refuse an ordinary value; output is bounded through the one
`seon.print/fit` owner; omitted detail is an elision value — ordinary data
carrying count, path, and requery identity — never bare truncation; a floor
hit is counted, never silent. UGLY OUTPUT IS A DEFECT (standing order):
every agent that meets an unreadable rendered result reports it or files
the issue naming the shape and where it surfaced. Display sizes for humans
are estimated tokens via `seon.ai.tokens/estimate` — the design intent;
character counts are storage projections, and surfaces still showing them
are defects to file, not precedents to copy.

### 2.5 One mechanism, accreted in place

Do not create `foo-v2`, a compatibility namespace, or a second
registry/renderer/feed/retry/config/test path to avoid fixing the existing
owner. Fix cycles, callers, and schemas in place; delete the superseded
path in the same refactor — git is the archive. Maps are OPEN: if something
declares it needs `foo` and `bar`, those are validated rigorously and a
supplied `bat` is ignored, never refused (`{:closed true}` does not appear
under `resources/seon/schemas/`). Adding is free; CHANGING is breakage: a
key's definition and its relationship to the output never change —
different semantics means a NEW KEY with a new name. Widening an input is
accretion; narrowing an input, requiring an optional key, or promising less
in an output is breakage even when the schema still validates. The standing
test, from the owner: **is this simpler than it was?** If it is equally
complex, the model was ported, not applied.

**Owner design gate:** when a decision would create hours of cross-owner
work or its guarantees cannot be stated simply, STOP before production
edits and bring the owner exactly three concrete options — simplest viable
constraint first, marked recommendation, each with guarantee, cost, and
what we give up.

## 3. Data and schema

Use the `data-oriented-clojure` skill before writing or reviewing Seon
Clojure — at design time, not only before the edit. The compact invariants:

- immutable data and pure transformations first; derive projections instead
  of storing them;
- fully namespaced map keys and database attributes, without exceptions;
- globally identified schemas declared once under `resources/seon/schemas/`;
- errors as values at agent/runtime boundaries;
- one namespaced map in/out for API-like functions, or fully named
  positional arguments for ordinary functions;
- every public function has a correct Malli input/output schema — no
  `:any`/`:some`/`[:maybe X]` without a proven genuinely polymorphic
  boundary; absent = no key, never stored nil.

**An entity IS its attributes, values, and refs — never a stamped kind.**
Do not add `:type`/`:kind` discriminator attributes: query attribute
presence to find entities, use a unique identity attribute to identify one,
follow refs to relate one (`:seon.entity/id-attr` enumerates identity
attributes; it is not a kind stamp). We accrete functionality with fully
Malli-spec'ed, code-validatable understanding of every shape — a kind stamp
freezes taxonomy where attributes would have kept growing. The narrow
exception: a genuinely bounded, closed set of states (a disposition, a
workload tag) may be an enum-valued attribute — rare, justified in the
schema's docstring, and still an attribute describing the entity, never a
table-picker.

**Presence, absence, and the `contains?` trap.** `contains?` answers "is
this key/index present" — on a vector it checks INDICES
(`(contains? [:x :y] 1)` is true; `(contains? [:x :y] :x)` is false), and
on a map a key stored as nil still answers true. Since Seon never stores
nil, prefer `(get m k default)` with a sentinel default, or `find` when you
need presence-and-value in one step. Reach for `contains?` only when you
genuinely mean key membership and the collection is a map or set.

`seon.db` is the ONE database namespace: all of Datahike's core data
functions, agent-first, each with Datahike's own positional AND
argument-map arities, both able to elide db/conn to the calling agent's
cluster's current database. Failures return flat `:seon.error` values.
Direct `datahike.api` calls survive only inside `seon.db`, the
store/registry and classified branch-custody owners, and system-side
listeners
([specification](reference-code/datahike/src/datahike/api/specification.cljc)
↔ `src/seon/db.clj`).

Config reconciles from an explicitly selected manifest into database facts;
running code reads the database. Provenance is minimal transaction metadata
(resolvable `:seon.db/user` and `:seon.db/process`) — never copied onto
domain entities. Database vocabulary is the dependency's vocabulary:
database value, basis transaction `:t`, commit ID, connection ID, store ID,
branch, branch head, transaction report.

### Vocabulary — grounded names, never invented ones

Inventing new vocabulary causes serious system problems: invented nouns
drift from the dependency, hide existing mechanisms, and poison every later
reader. The law, in order of preference:

1. **Use Clojure's own name for the concept**; else
2. **the closest integration seam's name** — Datahike, Malli, SCI,
   core.async already named their things; read the seam's source in
   `reference-code/` and take its name AND its semantics;
3. only when a concept is genuinely ours, coin once, record it here with
   sources on BOTH sides of the boundary, and use it everywhere.

Never assume you understand a row from its name alone: follow its links and
read that slice of code before building against it — that is how we avoid
rebuilding what a core library already built. Rows marked **[TARGET]** are
ruled-but-unbuilt: design toward them with this vocabulary, and when you
instantiate one, update its row with real source links in the same commit.

**Standing order — retire drift on sight:** when you meet older code, docs,
or comments using a legacy spelling from this table, update them to the
current term in the same commit when in scope, or file the issue when not.
Deferring this is how garbage accumulates. Newly ruled terms land in this
table in the same turn they are ruled.

The third column lists legacy spellings you may still meet in older
material — they are recognition aids for reading, never options for
writing.

| Term | Meaning and grounding | Legacy spellings |
|---|---|---|
| functions, schemas, tests | ordinary Clojure constructs | verbs |
| database, `db` | the `seon.db` authority | store, inventory, memory |
| boot / environment / running | boot is the 0→1 construction in dependency order (REPL first); the environment is the one per-cluster value it produces (`seon.env` ↔ `resources/seon/schemas/seon.env.edn`), scoped per agent; running code receives it | the runtime, the platform, the tower, ambient |
| call preparation, supplied defaults | sci's hook seam supplying a function's declared-and-absent arguments from the environment; caller wins; unavailable is a flat error (`reference-code/sci/src/sci/core.cljc` init docstring) | ambient injection, batteries |
| **[TARGET] canvas** | the focal agent surface; design lives in `docs/seon/architecture/ui.md`; no declared attribute exists yet — update this row when it lands | tile, live-tile, world |
| surface; card (CSS only) | a context render; a visual component | tile |
| web UI | `/`, `/agent/{id}`, debug, and `/data` | inspector |
| subagents | agents connected through database refs | collaboration system |
| cluster | one database branch, its agents, and shared plumbing; produces one environment | environment (for the cluster itself) |
| attributes + connections | the Datahike model | entity kind/type |
| build, operator, artifact | the `bin/seon`/`bin/acme` supervisor scope; the digested publication output | flavor |
| get-in, path | paged navigation into a nested value | drill |
| the todo | the ONE task system: derived obligations plus authored item facts | my.plan, bare "plan" |
| provider descriptor row | one hosted provider's data row under the config singleton | adapter, integration |
| packages/, package.json, deps.edn | each ecosystem's own manifest names | npm-pkgs, maven-pkgs |
| contexts on hosts, binding tables | sci's own vocabulary for agent execution | sandbox, VM, jail |
| `:interrupt-fn` | the ONE zero-arg fn sci calls on every fn body entrance (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) | the guard, the door, the cage |
| `interrupt!` | stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) | stop!, steering-error! |
| `time-limit` | the ONLY limit; sci counts nothing (`reference-code/sci/doc/interrupt.md`) | fuel, gas, step budget |
| `:seon.eval/fn-entries` | a RECORDED DIAGNOSTIC, never a limit | a step budget |
| every `fn` body entrance | where sci calls the `:interrupt-fn` | safepoint |
| `ctx`, `fork` | sci's own names (`reference-code/sci/src/sci/core.cljc`) | warm base, the agent's world |
| `:io` / `:compute` / `:mixed` | core.async's workload tags: `:io` may block but not compute, `:compute` must not block (`reference-code/core.async/.../impl/dispatch.clj`) | eval pool, wait pool |
| `:seon.cluster.run/process` | the process holding a run (`resources/seon/schemas/seon.cluster.run.edn` ↔ `src/seon/cluster/run.clj`) | claimant |
| accretion / breakage | a change that requires no more and provides no less | graduation, nursery |
| source initialization rows, transaction data | static source population is admitted transaction data; a fresh agent's opening derives a GENERATED episode from live facts (`src/seon/bootstrap.clj` ↔ `src/seon/render/walk.clj`) | bootstrap-plan rows, seed bundle |
| process record, generation, (pid, start-instant) | operator-managed process descriptors (`script/seon/fresh_operator.clj` ↔ `src/seon/cluster/process.clj`) | orphan registry, liveness flag |
| generated opening episode; reduce | the opening appends and settles one dependency-ready generated form at a time; an ordinary model reply reduces its frozen ordered forms (`src/seon/cluster/loop.clj` ↔ `src/seon/cluster/run.clj`) | bootstrap plan, fold |
| run loop | the per-agent proc advancing one claimed run (`src/seon/cluster/loop.clj`) | driver, driving |
| `seon.effect`, `effect/request!` | the one system-side owner every CAPABILITY request enters (fs, web, llm, db writes) — about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable | an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution | toolkit, grants, allowlist |
| program graph | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts | corpus |
| proc, step-fn, conns, graph-def | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/.../flow/spi.clj`) | invented scheduler nouns |
| `(sliding-buffer 1)` tap | core.async's own newest-only delivery | latest-wins mailbox |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere; a namespace has at most one assigned agent | agent workspace, sandbox ns |
| render function; `:seon.render/form` | an ordinary function a render schema property names; `/form` is the third declared projection beside `/ai` and `/html` (`resources/seon/schemas/seon.render.edn` ↔ `src/seon/render.clj`) | producer, view, read form |
| `:seon.render/ai` | the string consumed by agent context, or the symbol naming its function | prose, text render |
| `:seon.render/html` | the Hiccup consumed by the web UI, or the symbol naming its function (`src/seon/render/hiccup.clj`) | hiccup contract |
| identity-only admission | a registered reference predicate names `:seon.schema/identity-only` plus a qualified projection; admission retains only that identity at every depth (`resources/seon/schemas/seon.db.edn` ↔ `src/seon/sci/admit.clj`) | object serialization |
| wire (external crossings only) | a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, facts | wire (internal) |
| namespace page | one namespace's web surface: route → namespace → owner agent → walk in `/html` (`src/seon/render/route.clj`) | page, screen, dashboard |
| block | ONE render function's identified output — the unit of rendering, morph targeting, equality suppression; both projections are the same block | widget, component, panel |
| package, keyframe, delta | the delivery units: one revisioned package per change; a revision gap snaps to keyframe | frame, bundle |
| base SCI context / turn fork / agent context | THREE THINGS: the cluster's acquired program-only ctx; each turn's fresh generation-aware `sci/fork`; what `/ai` renders into the prompt — which never gates execution (`src/seon/sci/eval.clj`) | "the context" for all three |
| candidate context | a built context used to test a definition before installing it; `sci/fork` is admissible (copy-on-write Vars) | sandbox ctx, scratch fork |
| editor, revision, proof (curation) | a revision is ordered form sources as data; a proof is mechanical re-execution on a fresh fork; adoption via `:seon.cluster.run/supersedes` ([PRD](docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md)) | curator, repair agent |
| render profile | the database-derived consumer fit policy applied by the one `seon.print/fit` owner (`resources/seon/schemas/seon.render.profile.edn` ↔ `src/seon/print.cljc`) | cap, window |
| elision value | ordinary data describing omitted count, path, next offset, and requery identity (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) | ellipsis, truncation marker |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | queryable program-graph leaf facts; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) | sink roster, output allowlist |
| **[TARGET] root maintenance portfolio** | root's declared scheduled reclamation/inspection/repair tasks ([design](docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md)); update this row when the owners land | maintenance daemon |
| **[TARGET] `my.branch`** | agent-facing branch/history verbs over database branches — git vocabulary without claiming to be git ([PRD](docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)); update this row when it lands | my.git, my.repo |
| the agent's defs, `:seon.def/*` | the agent's temporary defs + atoms, committed as agent-scoped facts at turn settlement | the desk, session image |
| the agent's history; a form + value entry | the ordered derivation of an agent's REPL session from message/run-form/result facts ([PRD](docs/prds/sci-execution-runtime/plan/repl-transcript-context-prd-2026-08-10.md)) | session units, transcript entries |
| `doc`, `dir` (bare, injected); printed value | the injected REPL documentation functions over public program rows. **[TARGET]** the bulk `docs` plural is ruled, not yet installed | faces tool, print face |
| candidates (per-render selection) | the contract-fitting render producer selection consulted per render call (`src/seon/render.clj`) | roster, acquired index |

## 4. REPL-driven development

**Start every Clojure change at a running system, not at a file.** The
tools that connect you to the metal are the difference between guessing and
knowing — the REPL is the first design and diagnosis surface; checked-in
source and tests are the durable authority.

The loop:

1. **Get a live system.** `bin/seon status`; boot your OWN scratch cluster
   for anything you intend to change (clusters are sovereign and cheap;
   never reset or write to someone else's).
2. **Reach it.** `mcp__seon__runtime_status` lists live clusters;
   `mcp__seon__eval_clj` evaluates in the selected cluster's JVM (qualify
   the cluster when several are live — ambiguity must fail). **If these
   tools are down, degraded, or missing, SAY SO IMMEDIATELY** — report it
   to the orchestrator/owner and file the issue before working around it. A
   silent workaround (hand-rolled prepl senders, blind file edits) is how
   tool rot spreads; the tools staying sharp is everyone's leverage.
3. **Reproduce with one small form** and read the COMPLETE returned
   envelope. Inspect live facts and the installed schema before inferring a
   cause.
4. **Call the owning function directly** with representative data. When the
   question is about a dependency, read its source in `reference-code/` at
   that boundary.
5. **Design in the REPL** on immutable examples exposing inputs and
   outputs.
6. **Edit the one owning namespace**; hot reload applies it — including
   flow proc behavior, because procs reference step-fns as vars. Rerun the
   same form against the same live evidence.
7. **Persist the regression**, run the smallest affected gate, then verify
   the user-visible fact, page, log line, or process transition. A change
   proven only by a passing test is not proven.

Before planning any change: read the closest localized `AGENTS.md` and the
active roadmap (a task naming a document means READ IT END TO END — never
grep a named authority); write the dependency ledger (exact libraries and
mechanisms, pinned `reference-code/` paths, first-party call sites that
demonstrate the idiom) and read that source; probe the critical assumption
in the REPL; then strengthen the one existing mechanism in place.

**Where work lives.** Never a session scratchpad or system temp directory —
deleted without warning, invisible to every other lane. Probes go in `tmp/`
(project-local, visible); anything whose RESULT is evidence gets its script
committed and numbers recorded in the owning PRD's `research/`; anything
that will run again is real code under `test/`. The test: if the machine
were wiped right now, what would be lost?

**Comment grammar** (source convention only): `;` prose/inline, `;;` a
code-block comment above a form, `;;;` runtime structure. Rendered output
never uses comment-prefixed prose.

**Skills.** Use the matching `.agents/skills/*/SKILL.md` before specialized
work: `data-oriented-clojure`, `seon-flow-architecture` (before ANY new
runtime mechanism), `data-modeling` + `datahike`, `clojure-testing`,
`repl`. A skill's blast radius is every agent that loads it: every claim
carries `file:line`, verified when touched; an unverifiable claim is
DELETED, never hedged; a stale skill is a high-priority defect.

**Documentation authority.** `docs/seon/architecture/` is the always-current
aspirational target. The active program roadmap
([plan/README.md](docs/prds/sci-execution-runtime/plan/README.md), with
`unsettled.md` as the working edge) is the one ledger of current state and
ordering — dates, ruling numbers, and incident history live THERE. Bounded
PRD chunks own their inventory and evidence. Update the affected authority
in the same commit as the change that invalidates it; one fact lives in the
deepest file that owns it.

## 5. Testing

### Test fixtures — the one right way

Nearly every red in the 2026-08-13 tally repair was a wrong fixture, not a
production defect:

1. **Never hand-roster schema.** Use the canonical database fixture
   (`seon.test-support/with-database` installs the complete population)
   plus `::test-support/extra-schema` for genuinely synthetic attributes.
2. **Hand the projection/environment explicitly**, exactly like production
   callers.
3. **Supply every declared proc input** (cluster name, render interest,
   profile) — a missing input becomes a typed refusal that poisons
   downstream consumers.
4. **Fixed render profiles in fixtures** — deriving per call reads as a
   hang under load.
5. **Every await is bounded and loud** via the declared
   `seon.test-support/event-backstop-seconds`; await the exact terminal
   facts, not quiescence.
6. **Assert current ruled behavior** — typed diagnostics not absence,
   terminal verdicts, total bounded renders; a test expecting the old
   lenient shape is stale and the fix is the expectation.
7. **Own nothing global.** No assumptions about the shared worker JVM's
   namespace load-state or scheduling, and no mutation of it either —
   pooled workers run many tests per JVM. A probe namespace that must be
   unloaded has NO file on any classpath, so the property holds by
   construction.

Deeper mechanics: `.agents/skills/clojure-testing/SKILL.md`; a new fixture
class updates both in the same commit.

### The gate and what a proof is

`bin/test` is the one correctness gate, tiered: the declared
`:seon.test/platform` regressions run FIRST and stop the run when red; bare
adds only tests reaching code changed since the recorded green basis
(derived from `:seon.fn/calls` edges, never mtimes); `--all` adds every
non-long test; explicit namespaces run complete. The runner enforces the
bounded-execution law: a liveness watchdog dumps coordinator AND worker
JVMs, and the tally is total — unlaunchable or unconfirmed work is typed,
never silent.

Tests find design issues; structure dissolves them: when a failure class
appears, move the invariant to one choke point and keep ONE regression per
class asserting the WANTED behavior. A smaller suite is a desired outcome;
the health metric is class coverage. Every proof must be claimed by a
recurring surface. Fixture load paths are not the live boot path: schema,
acquisition, and process changes need the reset-boundary live proof. After
code changes, verify the running system, not only the tests, and report
what is still broken honestly.

The edit hook runs clj-kondo over prospective Clojure edits and publishes
admitted changes to `current-src`; it never runs tests. Syntax,
unresolved-name, privacy, and arity errors block; read hook feedback and
report smells.

## 6. Operating

`bin/seon` is the one development operator:

```bash
bin/seon [--root PATH] COMMAND   # --root = an ISOLATED operator root
bin/seon start [CLUSTER] [--config PATH]
bin/seon config apply [CLUSTER] PATH
bin/seon status | open [NAME]
bin/seon init [--changed PATH] | init NAME [--force]
bin/seon stop [--force] [NAME] | down [--force]
bin/seon reset --force           # down all, destroy, republish, refork
```

Absent cluster means `default` for `start`/`config apply`; bare `init`
means the published `current-src`. Stop/down act on exact recorded process
identity — a reused pid can never be killed by mistake. Never launch the
operator's internals separately or kill its children blindly; use
`bin/seon down` so the supervisor reaps its own. DESTRUCTIVE DRILLS AND
SECOND DEPLOYMENTS USE `--root`. `bin/acme` is a thin root-scoped wrapper
selecting cluster `acme`.

**Churn is weather, not a blocker.** Clusters, JVMs, and advertisements
come and go while you work — ADAPT AND CONTINUE. Your cluster vanished:
re-derive from `bin/seon status` and start a fresh one. A long-lived JVM
serves the code it loaded at start — suspect staleness before suspecting
correct code. Stop only for a genuine implementation dependency, named
exactly. **Recursive deletion NEVER follows symlinks**; plant a symlinked
sentinel in any cleanup regression.

The shipped model is DeepSeek through the single `seon.ai` HTTP owner;
every AI dial is a `:seon.config.ai/*` config fact with per-agent overlay.
Credentials name environment variables and never become datoms. Paid runs
are deliberate: cheapest probe first
([reference](docs/seon/reference/llm-adapters.md)).

**No hobbling for hypothetical risk:** agents are trusted collaborators
needing full capability, including reading every environment variable. The
design concern is catching HONEST MISTAKES (bounded output, digests, atomic
writes) — a restriction is admissible only after evidence of a real
problem.

## 7. Collaborating

**The orchestrator designs, grounds specs, reviews diffs, and runs serial
integration gates; implementation goes to capable code agents.** One
research question gets one agent with complete context. A Claude
orchestrator launches Codex lanes through `bin/codex-agent` as
harness-tracked background commands, run BARE — never piped (a filter
reduces the owner's live panel to one line):

```bash
bin/codex-agent run <name> "<the full spec>"
bin/codex-agent status | summary <name> | stop <name> | resume <name> "<followup>"
```

Lane stdout never enters the orchestrator's context: read the summary, then
query the log selectively. Stop + resume with a correction the moment new
information invalidates a lane's direction. A Codex orchestrator uses its
native collaboration tools instead
([mechanics](docs/seon/reference/driving-codex-agents.md)).

**NEVER SANDBOX A LANE** — a sandbox makes an audit's own output
unrecordable; ownership is enforced by NAMING OWNED PATHS, path-limited
commits, and diff review. Write lane specs in neutral engineering language
("verify", "falsify", "probe"). Give every lane its grounding, owned paths,
protected paths, and exact deliverable.

**Shared-tree safety.** Multiple agents share this working tree; preserve
unrelated edits and untracked files. Every agent commit is path-limited
(`git commit --only ... -- <explicit-owned-paths>`); never `git add -A`;
never `git reset --hard` or `git checkout --` to clean a shared tree;
branch switches and history changes require user coordination. Commits are
a lane's heartbeat; the orchestrator pushes at every coherent checkpoint. A
FOREIGN LANE'S BREAKAGE NEVER BLOCKS YOUR COMMIT: it blocks verification
only — commit your coherent slice, name whose breakage it is, continue.
VERIFY THE CLAIM BEFORE YOU NAME THE CAUSE: an attribution is a hypothesis
until a probe confirms it; a lane that refutes its assignment with evidence
has done its job.

**Issues are how the system learns.** When you discover a bug, smell,
duplicate mechanism, stale test, wrong vocabulary, or documentation
mismatch: search `docs/seon/issues/`, then create or update ONE note before
returning — never a private registry or a finding left in chat. Notes carry
frontmatter (`type`, `status: open → resolved | superseded`, `severity:
blocker | friction | cleanup`, and query tags per
[the issues README](docs/seon/issues/README.md)). The index is the owner's
ranked schedule (`bin/issues-index --check`); lanes do not edit it. Fix the
CLASS, not the instance, with one regression proving the class dead. A
recurring class earns a rule in this file — that is the loop that turns
defects into instructions.

**Reporting to the owner.** Sober summaries, broken things first. Full
repository-relative markdown links for every document. Say that you read
each named authority end to end. Ask the moment a genuine decision exists —
2-4 priced options, recommendation first; never park a decision awaiting
markup. Tool breakage, exploding context, and ugly output get reported the
moment they are seen.

## 8. Pointers

- [docs/TRANSFER_PROMPT.md](docs/TRANSFER_PROMPT.md) — orchestrator
  orientation: the current working edge, how the owner works, orchestration
  lessons;
- [docs/seon/architecture/architecture.md](docs/seon/architecture/architecture.md)
  — the aspirational system map, then the domain docs;
- [docs/prds/sci-execution-runtime/plan/README.md](docs/prds/sci-execution-runtime/plan/README.md)
  — THE ONE ORDERING: numbered owner rulings, dates, incident history;
  `unsettled.md` is the working edge. A second ordered list anywhere is a
  defect;
- [docs/conventions.md](docs/conventions.md) — code/schema patterns;
- [docs/seon/issues/README.md](docs/seon/issues/README.md) — issue
  lifecycle, severity, query tags;
- `.agents/skills/` — load the matching skill before specialized work;
- `AGENT.md` — thin delegated-lane compatibility adapter.
