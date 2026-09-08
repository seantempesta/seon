# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. When the tree contradicts a
claim here, the claim is the bug: fix this file in the same commit as the
change that exposed it.

**If you were spawned as a lane or subagent:** you are a hyper-competent
principal engineer. Do the right thing quickly; do not fuss over detail
that a later revision will erase. Your two obsessions are CORRECTNESS and
that THE TESTS TEST THE RIGHT THING: every test runs on the same harness the
codebase runs on — the canonical fixtures, the real Datahike database, the
real SCI fork, the armed contracts — never a mocked or hand-rostered
stand-in, because a harness that misrepresents the system produces garbage
code that passes (owner, 2026-09-08). Execute the bounded assignment
directly — never delegate or spawn again. Stay inside your owned
paths and preserve every protected path and unrelated shared-tree edit.
Never start, stop, reset, or mutate a shared cluster or process unless the
assignment grants it. An out-of-scope finding gets a durable
`docs/seon/issues/` note, never a silent scope expansion. Your report is an
integration input for the orchestrator, not authority to claim the parent
task complete. If the task is too broad, say so for rescoping.

## How we work here

**This is the second implementation.** The first one worked — for months —
and was torn down deliberately by its author, because he had learned enough
to build it properly. That fact should reorganize how you work: almost
everything you are asked to build has been built before, and the previous
version is readable through Git history. `git show` and `git log` are the
quarry; `docs/prds/*/research/` holds dated investigations with `file:line`
evidence and measured numbers; `reference-code/` vendors over a hundred dependencies as
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

**No seam may act on a pre-read or a mirror that its authority will
re-decide: derive at the authority, or hand it the decision** (owner
law, 2026-08-29; the five-class synthesis in
`docs/prds/sci-execution-runtime/research/class-root-cause-synthesis-2026-08-29.md`
is the evidence — hand-rostered fixtures vs the config compiler,
existence pre-reads vs the writer's upsert, a reply pipe vs the
process's own exit, a lint cache vs canonical analysis: one disease).
A pre-read is legitimate only when its answer cannot change before the
authority acts. Two ruled corollaries (ruling 47, context-generation
ledger): PROGRAM IDENTITY ROWS NEVER RETRACT — deletion retracts
definition facts, the identity survives as a tombstone, so refs to
identities are stable forever; and THE POPULATION INVARIANT — every
name the SCI context can resolve has a program row, minted where the
context learns it, so call edges cannot dangle by construction.

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
   `data/store`, with its lock at `data/store.lock`) under a lifetime
   `flock`; each cluster is one
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
   ordinary cluster remains a sovereign older program until destructively
   reforked. An explicitly selected development cluster adopts published
   program facts in place, preserving its agent facts, in its own JVM.
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
ordinary clusters are never synchronized. `bin/seon init --dev NAME` adopts
the publication on an explicitly selected development cluster in its own
JVM; the edit hook's `:current-source` root and cluster select that target.
Its adoption commit is recorded only after schema and program reconciliation,
loaded definitions, SCI acquisition, and JVM instrumentation succeed. Publication
retains existing wrappers and restores them even after a reload failure;
individual Var replacement during reload is not atomic. A live proof after file
edits must name whether it exercised a hot-reloaded Var, a new fork, or this
in-place development adoption; browser paint requires its own observation.

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
If you are the orchestrator (no bounded task, talking to the owner), your
manual is [docs/TRANSFER_PROMPT.md](docs/TRANSFER_PROMPT.md).

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
;; Illustrative shape; `projection` and the remaining request keys come from
;; the caller or fixture.
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
(seon.fn/tests-reaching (seon.db/db) "seon.cluster.run/open-tx")
;; Illustrative Datalog clause (not a standalone executable form):
;; which functions need cluster custody? — declared arity input-refs:
[?f :seon.fn.arity/input-refs :seon.db/connection]
```

**Derive or die (owner law, 2026-08-13):** every hand-maintained mirror
of derivable state — a member list, a count in prose, a roster, a
reserved-name list, a vocabulary enumeration — must be DERIVED by a
query, ENFORCED by a checker that fails on drift, or DATED as a
point-in-time record. A bare mirror is a defect on sight: every one this
project measured was stale within a day. Replace it with its derivation
in the same commit when in scope, or file the issue when not.

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
the effect execution boundary with deadline and output caps as config facts; test events
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
never refuse an ordinary value. THERE ARE EXACTLY THREE BOUNDS ON A VALUE,
and they are separate decisions with separate keys (owner ruling,
2026-09-07): **storage is bounded per value** as `seon.sci.admit` streams its
EDN, and a value that reaches the bound is `:seon.eval/missing` with the
bytes it reached; **the AI projection is bounded by the render profile** —
the AI RENDER FUNCTIONS and the VALUE RENDERER (`seon.render.value`'s AI
projection) apply its string, child-count, depth and token limits, and that
is the ONE place presentation elides anything — never the walk, never the
transcript, never a render terminal, never a request seam (owner, 2026-09-08:
"the ai projection is supposed to be handled by the ai render functions or
the value renderer. DO NOT INTRODUCE MORE spots where clipping occurs"). Presentation elides only
under a profile, and a request that carries none is not a request without one:
`seon.render/request-profile` DERIVES the cluster's agent profile at the render
entry points, so the AI projection is cut either way (measured, 2026-09-07);
only a seam holding no declared width — `seon.render.walk/presentation-width` —
makes no presentation decision. **HTML is not bounded at all** — a page
serves the value it holds. Query-work bounds and evaluation deadlines remain
separate from all three, and a query-work cut is reported as its own elision
naming the bound that made it. Previously
omitted detail is an elision value — ordinary data
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
follow refs to relate one. Renderer discovery catalogues identity attributes
present in actual datoms; identity derives from installed
`:db.unique/identity` with registry-alias chasing; and a schema bridge asking
entity-versus-envelope uses `storable-attribute-in?`. No map-level entity
property or projected identity mirror exists. We accrete functionality with fully
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

**The `my.*` / `seon.*` split is surface versus owner, never duplication:**
a capability has ONE durable fact family and ONE system-side owning
mechanism (`seon.cluster.message`, `seon.cluster.run`), and `my.<name>` is
the thin agent-facing protocol over those same facts — reads through
`seon.db`, effects as returned values the run loop interprets. Finding two
namespaces for one noun is the ruled layering; finding two FACT families or
two delivery paths for one noun is the defect.

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

Use the actual operation or value when speaking and writing: SCI context,
SCI evaluation, JVM REPL, agent turn, effect execution, Datahike branch,
database value, and boot sequence. Do not use a metaphor as their common name.
Use **evaluation** and **result** in prose, not "receipt". The storage merge
landed: ONE `:seon.cluster.eval` entity per (run, ordinal) carries the frozen
source, comment, namespace, author and ordinal AND its terminal facts, and the
`:seon.cluster.run.form/*` family, `:seon.cluster.run/forms` and the second
`:db.unique/identity` it minted are DELETED. Identifiers still spelled
`receipt` inside `seon.cluster.run` are literal code references awaiting a
rename; they never justify a second entity or a duplicated attribute.
In particular, the legacy MCP mode string `door` is an API spelling, not a
concept: explain it as **SCI evaluation mode**. Preserve literal tool arguments,
identifiers and historical quotations where accuracy requires them, but do not
carry those spellings into new prose. Rendering functions are functions;
dependency-specific producers/consumers remain producers/consumers when that is
what the dependency actually calls them.

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
| `my.plan`, "the plan" | The task system's authored plan facts and derived obligations; its render function chooses forms from current data and its writes return the changed entity ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/my.plan.edn` ↔ `src/my/plan.clj`) | todo, bare "plan" for turn sources |
| provider descriptor row | one hosted provider's data row under the config singleton | adapter, integration |
| packages/, package.json, deps.edn | each ecosystem's own manifest names | npm-pkgs, maven-pkgs |
| contexts on hosts, binding tables | sci's own vocabulary for agent execution | sandbox, VM, jail |
| `:interrupt-fn` | the ONE zero-arg fn sci calls on every fn body entrance and `loop/recur` (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) | the guard, the door, the cage |
| `interrupt!` | stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) | stop!, steering-error! |
| `time-limit` | the ONLY limit; sci counts nothing (`reference-code/sci/doc/interrupt.md`) | fuel, gas, step budget |
| `:seon.eval/fn-entries` | a RECORDED DIAGNOSTIC, never a limit | a step budget |
| every `fn` body entrance | where sci calls the `:interrupt-fn` | safepoint |
| `ctx`, `fork` | sci's own names (`reference-code/sci/src/sci/core.cljc`) | warm base, the agent's world |
| `:io` / `:compute` / `:mixed` | core.async's workload tags: `:io` may block but not compute, `:compute` must not block (`reference-code/core.async/.../impl/dispatch.clj`) | eval pool, wait pool |
| turn | An agent's ordered evaluations and any provider attempts; open means no closing fact, enforced at the writer. Target owner `seon.turn`; process custody stamps are retired ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)) | run, `seon.cluster.run`, `:seon.cluster.run/process` |
| accretion / breakage | a change that requires no more and provides no less | graduation, nursery |
| source initialization rows, transaction data | Static source population is admitted transaction data; the agent's opening is separately evaluated and stored as system turn 0 ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/bootstrap.clj`) | bootstrap-plan rows, seed bundle |
| process record, generation, (pid, start-instant) | operator-managed process descriptors (`script/seon/fresh_operator.clj` ↔ `src/seon/cluster/process.clj`) | orphan registry, liveness flag |
| system turn | An ordinary turn with a reply and no provider attempt; "system" is derived, never stamped. Opening and changed reads are stored evaluations; a system turn holding wakes' results answers them under the `:t` rule ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); SCI evaluation: `reference-code/sci/src/sci/core.cljc:352`) | generated opening episode, generated run |
| turn loop | The per-agent proc advances ordinary system and agent turns. Before an agent turn it applies the since-query diff to every distinct read form's latest evaluation; writes and effects never rerun ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); current owner `src/seon/cluster/loop.clj`, target `seon.turn`) | run loop, driver, driving |
| `seon.effect`, `effect/request!` | the one system-side owner every CAPABILITY request enters (fs, web, llm, db writes) — about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable | an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution | toolkit, grants, allowlist |
| program graph | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts | corpus |
| proc, step-fn, conns, graph-def | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/.../flow/spi.clj`) | invented scheduler nouns |
| `(sliding-buffer 1)` tap | core.async's own newest-only delivery | latest-wins mailbox |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere. ASSIGNMENT IS NOT IDENTITY: `:seon.cluster.agent/namespace` is not unique — several agents may share one, and an agent may `in-ns` anywhere its REPL reaches. The one agent a namespace answers for is its `:seon.ns/steward` | agent workspace, sandbox ns |
| render function | A function of the data that chooses its forms. ONE AI/HTML pair per entity schema, never per scalar attribute; no pair uses the default attribute-map printer. Evaluation entities render saved shown text through `seon.repl/render-ai` and `render-html` ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:315`) | producer, view, read form, `/form` face, `:seon.render/form` |
| `:seon.render/ai` | The entity's AI projection. Generated source is evaluated in an ordinary system turn; historical evaluations render stored shown text without executing their forms. The walk and evaluation schema pair share `seon.repl/text` as the REPL grammar ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:246`) | separate teaching prose, text render, "the string consumed by agent context" |
| `:seon.render/html` | the Hiccup consumed by the web UI, or the symbol naming its function (`src/seon/render/hiccup.clj`) | hiccup contract |
| live result object | The actual result retained by evaluation id in the agent's SCI context, bound directly by `result/e<id>`. It is not serialized, admitted as a stored node, or restored after restart ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); SCI binding: `reference-code/sci/src/sci/core.cljc:260`) | result serialization, stored print node, result blob, restorable node |
| wire (external crossings only) | a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, facts | wire (internal) |
| namespace page | one namespace's web surface: route → namespace → owner agent → walk in `/html` (`src/seon/render/route.clj`) | page, screen, dashboard |
| block | One entity rendered through its schema pair, covering a whole concern. Scalars share its own block; components and declared derived queries supply their blocks. The identified output is the HTML morph target ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/render/block.clj:61`) | widget, component, panel, scalar block |
| package, keyframe, delta | the delivery units: one revisioned package per change; a revision gap snaps to keyframe | frame, bundle |
| base SCI context / agent SCI context / prompt | The cluster's acquired program-only context; each agent's persistent context forked once and receiving base diffs; the ordered rendering of its stored evaluations. Neither private objects nor prompt visibility gates callability ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `reference-code/sci/src/sci/core.cljc:330`, `:345`, `:260`) | turn fork, "the context" for all three |
| candidate context | a built context used to test a definition before installing it; `sci/fork` is admissible (copy-on-write Vars) | sandbox ctx, scratch fork |
| compaction | Wipe the agent's evaluations; the next system turn regenerates the opening from current record facts using the same algorithm. There is no manual curation path ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)) | editor, revision, proof, curation, supersession |
| render profile | The presentation policy applied by the value renderer once at evaluation time; the evaluation stores the resulting shown text. History never clips again; HTML renders the live object without presentation clipping, or saved text after restart ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.render.profile.edn`) | cap, window, separate result storage bound |
| elision value | ordinary data describing omitted count, path, next offset, and requery identity (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) | ellipsis, truncation marker |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | queryable program-graph leaf facts; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) | sink roster, output allowlist |
| **[TARGET] root maintenance portfolio** | root's declared scheduled reclamation/inspection/repair tasks ([design](docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md)); update this row when the owners land | maintenance daemon |
| **[TARGET] `my.branch`** | agent-facing branch/history verbs over database branches — git vocabulary without claiming to be git ([PRD](docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)); update this row when it lands | my.git, my.repo |
| private layer | The agent's defs and atoms as actual objects in its persistent SCI context, isolated from the base and other agents, lost on JVM restart. Installed functions, schemas, and tests are durable program facts ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); SCI isolation: `reference-code/sci/src/sci/core.cljc:345`) | `:seon.def/*`, session image, restored defs |
| evaluation entity | One `:seon.eval` entity per branch/turn/ordinal identity, carrying source and outcome evidence including shown text, out, error, and read evidence. Its actual result stays in memory; identity derives through `seon.id/evaluation` ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/id.clj:49`) | `:seon.cluster.eval`, `:seon.cluster.run.form/*`, frozen form entity, receipt, `form-identity` |
| the agent's history | The walk rendering `(seon.eval/of-agent db agent)` in chronological turn order and ordinal through the evaluation schema's pair, from stored shown text. All turns are shown by default; previous prompt bytes remain unchanged until compaction ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:246`) | transcript, transcript entries, session units, run-form facts |
| `doc`, `dir` | REPL documentation as DATA from public program rows: `dir` returns symbol, arglists, first docstring line, and input/output contract; `doc` returns the full docstring and contract ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)) | faces tool, print face, separate teaching prose |
| `seon.id/digest`, `seon.id/evaluation` | THE ONE IDENTITY DERIVATION: a thing that IS its parts takes the truncated SHA-256 of their ordered `pr-str` (`src/seon/id.clj`); an evaluation's id is `(seon.id/evaluation branch turn ordinal)`, its handle `(seon.id/symbol-in "result" \e id)`; `random-uuid` only for a genuinely fresh EVENT with no identity of its own. Never a new generator, never a second truncation (owner, 2026-09-08) | short-id, nanoid, hand-rolled hashes, `(str (random-uuid))` for things that have parts |
| read evidence | The dependency plans and revisions a read observed, used with its evaluation `:t` to detect changed facts. Every distinct generated or agent-written read participates; writes and effects never rerun ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/db.clj:468`, `:561`) | copied read result, inbox-only refresh |
| shown text | The exact value-renderer text the agent saw at evaluation time, saved with source, out, and error. It includes profile elisions and requery forms, survives restart, and cannot restore the live object ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)) | result EDN, stored print node, serialized result |
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
   the cluster when several are live — ambiguity must fail). Its `jvm`
   mode is the host prepl with NO cluster custody bound — `seon.db`'s
   elided db/conn arities refuse there; `(seon.operator/connection
   "default")` supplies explicit custody. SCI evaluation mode (the tool's
   current literal argument is `mode: "door"`) evaluates through
   the cluster's SCI ctx where elision holds (and mutates that shared ctx,
   so keep SCI evaluation probes disposable). **If these
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
adds tests reaching code changed since the recorded green basis (derived
from `:seon.fn/calls` edges, never mtimes) — deliberately widening to every
eligible test when the basis is missing, a file was removed, or a changed
gate input sits outside the program graph; `--all` adds every
non-long test; explicit namespaces run complete. A lane with concurrent neighbours gates with `bin/test --paths <its own
files…> <namespaces…>`, which snapshots HEAD and overlays only those paths,
so another lane's in-flight edit never blocks it. A LANE NEVER RUNS `--all`
OR `--full` (owner, 2026-09-08: "It's a waste of time to run the entire test
suite for every change") — bare `bin/test` plus its subject's namespaces
plus `--platform` is a lane's whole gate; full suites are the orchestrator's
integration checkpoints. The runner enforces the
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
admitted changes to `current-src`; it never runs tests. The configured hooks
cover `apply_patch`, `Edit`, and `Write`, including default config and schema
publication. Shell file writes do not trigger them: use `bin/seon init --changed
PATH` after such edits and verify publication. Syntax,
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

**The default cluster IS the development environment (owner, 2026-09-08).**
`bin/seon start` boots cluster `default` in the main operator root; that is
the live system every edit hook keeps current, the one MCP `eval_clj`
reaches with no `root` argument, and the one every lane verifies on.
`.claude/seon-hook.edn` names it under `:current-source` (`:root "."`,
`:cluster "default"`). There is no side root and no special name; fixture
agents such as Juniper are data seeded into `default`. Its advertisement
prints the web URL.
How an edit reaches the open browser, in order: the `Edit`/`Write`/
`apply_patch` hook runs `bin/seon --root ROOT init --dev CLUSTER --changed
PATH`; the live JVM analyzes the changed files and publishes safe
same-identity upserts onto `:current-src` (any deletion, new identity, schema
resource, or contract change falls back to a complete publication); the
development cluster then adopts in place — schema declarations, program rows,
`require :reload` of every changed namespace in `:seon.ns/requires` order
(callees first), SCI acquisition, JVM instrumentation — and records
`:seon.source/commit-id` on the cluster only after all of it succeeded; the
adoption offers one render wake, so the open page repaints through the
ordinary feed with no reload. Convergence is a query, not a feeling: the
cluster's `:seon.source/commit-id` equals `(seon.cluster.source/current
store)`. A hook line `ADVISORY — current-src publication failed` means the
page is still showing the previous commit; read
`logs/current-source-failure.log`, fix, and re-run the `init --dev` by hand.
Shell writes bypass the hook: follow them with the same `init --dev
--changed`. A JVM started before a change to the adoption path itself cannot
adopt with the new path — stop and start it, then `init --dev`. MCP: every
`eval_clj` call against this cluster passes `root` and `cluster`; `jvm` mode
has no agent custody, so agent-elided calls need explicit arguments.
LANES WORK AGAINST `default`: a lane verifies there — not on a scratch
cluster — and reads the hook's line after each edit. When adoption refuses
because the cluster predates an incompatible schema change, the lane reforks
it at once, never waits: `bin/seon stop default`, `bin/seon init default
--force`, `bin/seon start`, `bin/seon init --dev default`, then reseeds the fixture
(`docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj`
through `eval_clj`). Database data is disposable by ruling. Scratch clusters
remain for destructive drills only.

**Session-start hygiene (standing order).** A fresh session begins by
verifying the system it inherited, not by trusting it: check `bin/seon
status` and that the MCP tools answer (a stale long-lived JVM serves old
code — reset it onto current source rather than debugging phantoms);
`git status` for another session's residue (preserve it, report it, never
build on it unreviewed); and sweep the disposable exhaust. Operationally: a retained
`tmp/test-runs/run.*` root is sweepable when no live runner holds it and
its recorded failures have since been fixed or re-observed at HEAD (when
in doubt, a holderless root older than a day is exhaust); scratch cluster
roots under `tmp/` are sweepable when their creating probe or lane has
returned; and the shared root's store footprint (`bin/seon status` prints
it) is judged against its size after the last reset — an
order-of-magnitude growth is the investigation signal, not a number to
tune. **Before deleting any
run root, confirm no live runner holds it** (`bin/codex-agent status` for
lanes plus the process table for `bin/test` JVMs) — sweeping an active
root kills a foreign gate mid-run. Database data is disposable by ruling:
reset freely, never migrate. Everything under `tmp/` is throwaway until
production; a disk filling with dead roots is a defect to fix in the
minute it is noticed, and the [TARGET] root maintenance portfolio is the
machinery that eventually owns this automatically.

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

Use smaller models for straightforward documentation edits, mechanical checks
and other bounded simple tasks. Reserve stronger models for architectural
reasoning, ambiguous implementation and integration review. Give each agent
only the context needed for its assignment and verify its output.

**The orchestrator designs, grounds specs, reviews diffs, and runs serial
integration gates; implementation goes to capable code agents.** One
research question gets one agent with complete context. A Claude
orchestrator launches Codex lanes through `bin/codex-agent` as
harness-tracked background commands, run BARE — never piped (a filter
reduces the owner's live panel to one line). The default lane model is
`gpt-6-astra` at `low` effort ("astra light", owner 2026-09-08); Opus
subagents are not the default implementation agent:

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
("verify", "falsify", "probe"; never adversarial verbs, which trip model
safety filters). Give every lane its grounding, owned paths, and exact
deliverable. A PROTECTED path is only ever a file another lane is editing
AT THE SAME TIME — a device against clobbering uncommitted work, never a
reason to leave a defect in place. A lane running alone fixes every root
cause wherever it lives and lists each file it touched (owner rule,
2026-09-07: "if shit is wrong fix it").

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

- [docs/TRANSFER_PROMPT.md](docs/TRANSFER_PROMPT.md) — THE ORCHESTRATOR'S
  manual: the role rule, session start, owner working style, handoff shape,
  and accumulated orchestration lessons;
- [docs/seon/architecture/architecture.md](docs/seon/architecture/architecture.md)
  — the aspirational system map, then the domain docs;
- [docs/prds/sci-execution-runtime/plan/README.md](docs/prds/sci-execution-runtime/plan/README.md)
  — THE ONE ORDERING: numbered owner rulings, dates, incident history;
  `unsettled.md` is the working edge. A second ordered list anywhere is a
  defect;
- [docs/seon/issues/README.md](docs/seon/issues/README.md) — issue
  lifecycle, severity, query tags;
- `.agents/skills/` — load the matching skill before specialized work.
