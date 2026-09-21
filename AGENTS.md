---
type: reference
status: active
tags: [agent, architecture]
---

# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads
`AGENTS.md` directly; Claude reads the same bytes through the same-directory
`CLAUDE.md -> AGENTS.md` compatibility link. When the tree contradicts a
claim here, the claim is the bug: fix this file in the same commit as the
change that exposed it.

**Lane instructions — current workflow; history in [turn PRD §10](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).**
An assignment's stricter stop rule takes precedence: do not operate another
lane's session to repair a foreign gate failure. Execute a bounded assignment
directly, without delegating again; preserve unrelated edits and name the
exact verification boundary when reporting.

0. **You are a hyper-competent principal engineer.** Do the right thing
   quickly; do not fuss over detail a later revision will erase. Obsess over
   two things: correctness, and that the tests test the right thing — on the
   SAME harness the codebase runs on (canonical fixtures, real database,
   real SCI fork, armed contracts), never a mocked or hand-rostered
   stand-in. A misrepresented harness produces garbage code that passes.
1. **Iterate with `bin/test-fast <namespaces…>`**, which loads the program
   and uses the worker's same contract arming in one JVM. With concurrent
   editors, use `bin/test-fast --paths <your files…> -- <namespaces…>`:
   it shares the gate's HEAD-plus-paths snapshot, without worker copies or
   base publication. Plain namespaces use the working tree. **The orchestrator
   gates a commit with `bin/test --paths <owned files…> -- <namespaces…>`
   and `bin/test --platform`. Lanes use `bin/test-fast`, never cold gates.**
   For the owner-approved [agent-platform refactor](docs/prds/agent-platform/plan/README.md),
   focused regressions run inside four substantial cuts; affected integration and
   platform gates run at completed-cut checkpoints, not every small commit. Reserve
   the full suite for final integration or a concrete failure requiring it.
   `bin/test` refuses a gate carrying `SEON_CODEX_LANE`; its shared `--fast`
   snapshot path remains available. Foreign breakage is never a reason to
   stop unless the assignment explicitly requires it. NEVER
   `--all` or `--full` in a lane — full suites are the orchestrator's
   integration checkpoints only.
2. **The gate is instrumented**: every regression runs under the same
   contracts a cluster arms. A test that passes only unarmed is a defect.
3. **One clipping spot**: presentation elision happens in the AI render
   functions and the value renderer only; evaluations store shown text;
   HTML never clips. A spec or diff adding a `fit`/`elision` call
   anywhere else is wrong on sight.
4. **Protected = concurrently edited only.** A path is protected while
   another lane holds uncommitted edits in it; otherwise fix every root
   cause wherever it lives and list every file touched. Never revert or
   restore a shared file; baseline in a throwaway worktree
   (`git worktree add tmp/<lane>-wt HEAD` + link `reference-code`).
5. **Commits are the heartbeat**: path-limited (`git commit --only -- …`),
   one coherent slice each; never `git add -A`, `reset --hard`, `checkout --`.
6. **Background hygiene**: never poll with `pgrep -f` on your own command
   line; run awaited commands in the background; end every shell and delete
   scratch roots/worktrees before reporting.
7. **Words**: verify / falsify / probe — never adversarial verbs;
   use the current terms in the vocabulary table below.
8. **The default cluster IS the development environment** (main root,
   `bin/seon start`, MCP with no root argument): the edit hook keeps it
   current on every edit and a lane verifies there (configured coalescing
   means publication is not immediate). **A lane never stops, reforks, or
   restarts `default`** — it is the owner's window. On "predates the incompatible schema
   change" the lane records RESET NEEDED with the commit in its landing
   note, verifies on its own scratch cluster (`bin/seon --root
   tmp/<lane>-root start <lane>`, Juniper fixture seeded, downed and deleted
   after), and continues; the orchestrator reforks `default` once, batching
   every pending schema change (`bin/seon stop default; bin/seon init
   default --force; bin/seon start; bin/seon init --dev default`, reseed).
9. **Default lane agent**: `bin/codex-agent` on `gpt-6-astra` at `low`
   effort; raise effort only for design review.
10. **Landing note** under `docs/prds/context-generation/research/`, dated,
    with exact bytes and measured numbers; issues under `docs/seon/issues/`
    for anything out of scope; never a finding left in chat.
11. **One JVM per lane, two test JVMs per repository, three editing lanes**
    (owner, 2026-09-20: "I'm tired of my machine dragging"). A lane never
    launches a fast run or a probe JVM in the background and never overlaps
    two; it takes its thread samples from the one running JVM. `bin/_test-slot`
    bounds every worktree of one repository to two concurrent test JVMs
    (derived from Git's common directory, `2cdb7ef8a`); the orchestrator runs
    at most three editing lanes beside `default`. Seven JVMs at once (load 25)
    is the incident this rule closes.
12. **Lanes never create worktrees.** Iterate in the shared tree with
    `bin/test-fast --paths <owned files…> -- <namespaces…>`; at an overlay
    refusal naming a foreign dirty caller, STOP and report the path — never
    work around it with `git worktree add`. Only the orchestrator baselines a
    suspect snapshot in a worktree, and removes it in the same turn. (Owner:
    worktrees cause more problems than they solve for structural edits.)
13. **HEAD loads after every commit.** A retirement, rename or deletion of a
    public Var and the conversion of every caller are ONE slice; a lane that
    finds callers it cannot convert stops BEFORE deleting and lists them.
    Prove it before each commit: `clojure -M -e "(require '<owned ns>…)"`.
    On 2026-09-20 a retired predicate with 74 inventoried callers left HEAD
    uncompilable and blocked every gate and lane for two hours.
14. **Run roots are swept only when no JVM references them**
    (`ps -eo command | grep java | grep test-runs`), never by a `pgrep` for
    the root's name: `bin/test` keeps its root in a shell variable, so the
    process table cannot prove absence. A root younger than the oldest live
    `bin/test` is never swept.
15. **Hook publication is paused during a multi-lane source cut and resumed
    at the reset** (`.claude/seon-hook.edn` `:current-source :enabled`).
    While three lanes edit `src/`, every edit otherwise queues a complete
    publication and adoption of `default` (60–150 s under the lifecycle
    lock), and each one is refused; the orchestrator records the pause and
    the re-enable in the working edge. Documentation edits never publish
    and never widen a gate: gate inputs are DECLARED
    (`seon.test.cache/input-roots`), not "everything outside src and test".
16. **Models for lanes** (owner, 2026-09-19/20, amended 2026-09-22):
    `gpt-6-astra` LOW for bounded well-specified slices and MEDIUM for
    design cuts and review — never high (owner: credits burn too fast and
    every decision gets slower); `gpt-5.6-sol` low for PRD-driven
    mechanical sweeps; Opus never for implementation (it overdoes core
    work). A stopped lane on a Codex
    usage limit is reported to the owner and resumed after the go-ahead,
    never relaunched under a new name.

## How we work here

**This is the second implementation.** Almost
everything you are asked to build has been built before, and the previous
version is readable through Git history. `git show` and `git log` are the
quarry; `docs/prds/*/research/` holds dated investigations with `file:line`
evidence and measured numbers; `reference-code/` vendors dependencies as
submodules so their semantics can be READ rather than remembered. The prime
directive is not "write good code" — it is **do the archaeology before you
design, then design something better than what you found**. The fresh tree is
not zero knowledge; it is zero *baggage*: every piece re-earns its place.

**Ask what the dependency already does before you build anything.** sci keeps
a live env; konserve has GC and binary storage; Datahike branches
are head pointers, not copies. If your design recomputes something a
dependency already maintains, the design is wrong. The cheapest place to
delete code is before it exists: build the smallest real thing and let live
probes falsify the design while it is still a decision.

**SECONDS, NOT MINUTES (owner law, 2026-09-22).** Anything that takes
longer than TEN SECONDS requires the owner's explicit authorization, and
only a handful of operations may ever hold one — the initial indexing of
the whole program from zero is one; a cold JVM boot is another. Everything
else must PROVE there is no simple algorithmic solution before it is
allowed to be slow: name what work is proportional to the whole program
that should be proportional to the change, and cite the seam that already
does it in seconds. Treat anything above a couple of seconds with
suspicion, especially where a tuned library (Datahike, clj-kondo, Malli,
SCI, core.async) sits underneath — their authors nailed the
implementations; our failures have almost always been bad algorithmic
choices, never code correctness. Almost everything in Clojure is
processing immutable data and it is extremely efficient. A fork is a
branch pointer: milliseconds. A no-change request is two commit ids
compared: milliseconds. An edit's cost is bounded by the changed
declarations and their callers. A number is never explained by which path
it took ("the cold path", "the first adoption") — it is explained by what
work the algorithm should do. Tests FAIL when they exceed their declared
bound (default 5 s; `:seon.test/long-ms` with its reason is the only way
up), and the measurement script
(`docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`)
is the gate for the publication path: no slice is landed without its row.

**Prefer dissolution to addition.** The best change deletes a mechanism.
When you meet a tuned constant, ask what observable event it stands in for.
When a fix feels like hardening a mechanism against its own normal
operation, stop and ask whether the mechanism belongs on that path at all.

**Derive state; do not remember it.** Verify prose — including this file —
with one live command before acting on it. Nothing stores what a query can
derive: `open?` means no `closed-tx`; a boolean is legitimate only when
someone genuinely asserts the false.

**No seam may act on a pre-read or a mirror that its authority will
re-decide: derive at the authority, or hand it the decision** (owner
law, 2026-08-29; the five-class synthesis in
`docs/archive/prds/sci-execution-runtime/research/class-root-cause-synthesis-2026-08-29.md`
is the evidence — hand-rostered fixtures vs the config compiler,
existence pre-reads vs the writer's upsert, a reply pipe vs the
process's own exit, a lint cache vs canonical analysis: one disease).
A pre-read is legitimate only when its answer cannot change before the
authority acts. Program deletion retracts the entity with `:db/retractEntity`; history,
as-of and since retain its past. Calls, references and recorded test reach
store qualified-symbol values. Deletion refuses when surviving declarations
still name the removed identity; every repair must be present in the same
transaction's final database. The complete refusal is the refactoring input.
A name without a current function row is reported by the unresolved-call
query, never repaired by minting an identity. Every function and test carries
the required digest produced by analysis; that fact plus no call datoms means
“analyzed, calls nothing.” No program tombstone or retirement sentinel is stored.

**The recurring failure class of this whole project is a check that reads
ABSENCE OF SIGNAL as health** — a query against a descriptor that no longer
exists, a regression walking less than the writer admits, a monitor that
stays silent through a crash. When you write any check, ask what it reports
when its subject is absent. If the answer is "fine," the check is worse than
nothing.

**Write it down in the same beat.** Rulings into the dated ideas ledger, state into
the working edge, settled terms into the vocabulary table, defects into
issues — in the turn it happens, path-limited commit. Conversation memory is
never the only record.

## 1. What Seon is and how it runs

One JVM process runs everything, from source, REPL-first. CLJ only — the
CLJS build is off and the pod/self-host engine is deleted; git history is
the archive for everything deleted. Fresh `src/` + `test/` are the system.

The system has exactly two states: **boot** and **running**. Boot is the
0→1 construction in dependency order, and it opens the REPL before store acquisition
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
   the same store. One JVM may host many cluster instances; nothing may assume
   "the" cluster.
3. **Facts.** A config manifest reconciles into database facts; running
   code reads the database, never files or env vars. One non-executing
   `:current-src` branch holds indexed code; a new cluster forks its exact
   published commit ID — near-instant, never a re-index. An existing
   ordinary cluster remains a sovereign older program until destructively
   reforked. An explicitly selected development cluster adopts published
   program facts in place, preserving its agent facts in the hosting JVM.
4. **Flow.** EVERY AGENT IS ITS OWN FLOW GRAPH, acquired from one blueprint
   when it first has work or owns schedules, parked between turns, kicked off by wake notifications; per cluster, a few shared plumbing graphs (render pipeline,
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
edit hook and `bin/seon init` use one digest-driven publication: changed
inputs plus affected declaration files reconcile on the current history.
Changed files and their direct callers are linted with clj-kondo's namespace
cache; complete analysis is the cold case only. An analysis output-shape
change requires a reset. Development adoption
reloads changed namespaces through Clojure `require :reload`; publication has
no separate loaded-producer generation guard. `bin/seon init` reuses an
unchanged publication; ordinary clusters are never synchronized. `bin/seon init --dev NAME` adopts
the publication on an explicitly selected development cluster in its
hosting JVM; the edit hook's `:current-source` root and cluster select that target.
Its adoption commit is recorded only after schema and program reconciliation,
loaded definitions and JVM instrumentation succeed. SCI acquires the program
on first evaluation, reusing the context until its database
value changes. Publication
re-arms wrappers when their contract or a transitively referenced declaration
changes; unrelated wrappers retain identity (`src/seon/instrument.clj:593`).
The wrapper captures the canonical dependency definitions and their contract
digest; selection compares those definitions with the supplied projection.
A source-change refusal retries adoption
once (`src/seon/cluster.clj:2042`);
individual Var replacement during reload is not atomic. A live proof after file
edits must name whether it exercised a hot-reloaded Var, a new fork, or this
in-place development adoption; browser paint requires its own observation.

**Transport law:** anything recovery or another process could ever need is
a DATABASE FACT — identities, evaluations, messages, errors, the settled
reply — with bulky durable payloads as blobs. Evaluation result
objects are excluded: §15 stores their shown text, never result blobs. Everything IN FLIGHT rides channels,
provided loss is free: re-derivable from facts or superseded by a newer
complete value. The buffer encodes the loss semantics: sliding-1 for
latest-wins, fixed for backpressure, counted-dropping for observation.
Any design where channel loss breaks recovery is wrong by definition.

**Crash and turn model: interrupted execution never resumes.** The binding
[turn PRD §14–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
is implemented by boot recovery (`src/seon/turn.clj:1680`): close open turns and
mark unfinished evaluations interrupted.
The agent adapts from stored shown text. Private defs, atoms, and result objects
disappear with the JVM; they are neither serialized nor restored. The cluster's
program-only SCI base derives from one database value through
`seon.sci.eval/base-ctx`: current core admission copies the loaded JVM root;
current agent admission interprets the stored source in every namespace, with
an explicit typed JVM fallback if SCI cannot evaluate it. Before later turns,
`fork-for-turn` regenerates the agent fork and reapplies its private objects
in memory, preserving the agent's context handle. Accepted-row installation
is the measured optimization of base regeneration, checked against it by the
canonical acquisition regression.

**Waking and the loop.** Schema-declared listened
attributes identify wake datoms; answering derives from their `:t` and a
qualifying turn's basis. An accepted ordinary reply answers them; a
system-only turn or opening alone does not (`src/seon/turn.clj:2823`).
System turn 0 stores
the opening. Before each agent turn, the since-diff checks every distinct
read form's latest evaluation and appends changed reads in a system turn;
writes and effects never rerun. `src/seon/turn.clj:2191` owns the system turn;
`src/seon/turn.clj:4933` advances the ordinary agent proc.

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
chain (`src/seon/db.clj:901`). Database values carry their projection state
(`src/seon/db.clj:141`); reads use it before the supplied-projection fallback
(`src/seon/db.clj:950`). Evaluation binds a database carrying that state
(`src/seon/sci/eval.clj:2250`).

```clojure
;; The caller or fixture hands the projection explicitly:
(seon.schema.datahike/storable-attribute-in? projection :seon.agent/id)
```

The shipped default manifest is a program constant: `seon.config/defaults`
is an immutable value compiled once when its namespace loads, and rebuilt
when adoption reloads that namespace after code or resource changes. It is
not cluster configuration; cluster consumers still receive their effective
configuration explicitly. This exception permits no atom, delay, memoization,
or cache for the compiled defaults.

Fetch-at-call-time is also the recurring performance killer: the same
defect that reads stale state also recomputes a projection on every call. Grounding:
[seon-env PRD](docs/archive/prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md);
open members are tagged `class/p1` in `docs/seon/issues/`.

### 2.2 Facts over inference

Durable system state and program declarations are explicitly recorded in
the database and queryable. Private SCI objects are deliberately in memory
only ([turn PRD §14–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md));
the shown text is durable, not the object. Every question — what a function
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
(seon.fn/tests-reaching (seon.db/db) "seon.turn/open-tx")
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
value — nothing throws into the loop. EVERY function carries a complete
Malli contract, private included (owner ruling 2026-09-17, program-facts PRD
§1j; public-only was the previous rule and left 352 private database-read
consumers unchecked). Arming selects loaded contracted Vars, private included
(`src/seon/instrument.clj:687`, `collect-contracts!` walks `ns-interns`;
the supplied projection provides program-graph contracts). A function
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
never refuse an ordinary value. Evaluation retains the actual
result object in the agent's SCI context and stores the exact shown text;
(`src/seon/sci/eval.clj:1957`); it does not serialize the result object.
**The AI projection is bounded by the render profile** —
the AI RENDER FUNCTIONS and the VALUE RENDERER (`seon.render.value`'s AI
projection) apply its string, child-count, depth and token limits, and that
is the ONE place presentation elides anything — never the walk, never the
history, never a render terminal, never a request seam (owner, 2026-09-08:
"the ai projection is supposed to be handled by the ai render functions or
the value renderer. DO NOT INTRODUCE MORE spots where clipping occurs"). Presentation elides only
under a profile, and a request that carries none is not a request without one:
`seon.render/request-profile` derives the cluster's agent profile at the render
entry points when a projection is supplied; otherwise it returns a typed refusal
(`src/seon/render.clj:67`). The projection is made once at evaluation time; historical
evaluations render their saved shown text unchanged. **HTML has no presentation clipping** — a page serves the live object it holds, or saved shown text after
restart. The three controls are the AI presentation profile, query-work bounds,
and evaluation deadlines (turn PRD §5, §15); result storage has no separate
serialization bound. Query-work and evaluation bounds remain separate decisions,
and a query-work cut is reported as its own elision
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

**Every decision this project has made about deletion, refs, identities,
components, required-ness and derivation is consolidated, with its ruling and
its Datahike grounding, in
[the data-modeling decision guide](docs/seon/architecture/data-modeling-guide.md)
— read it when a modeling question is not answered below.**

Use the `data-oriented-clojure` skill before writing or reviewing Seon
Clojure — at design time, not only before the edit. The compact invariants:

- immutable data and pure transformations first; derive projections instead
  of storing them;
- fully namespaced map keys and database attributes, without exceptions;
- globally identified schemas declared once under `resources/seon/schemas/`;
- errors as values at agent/runtime boundaries;
- one namespaced map in/out for API-like functions, or fully named
  positional arguments for ordinary functions;
- every function, private included, has a correct Malli input/output schema — no
  `:any`/`:some`/`[:maybe X]` without a proven genuinely polymorphic
  boundary; absent = no key, never stored nil.
- **anything that IS a symbol is stored as a symbol, never as a string**
  (owner, 2026-09-17: "all functions and vars and anything that is a symbol
  should be stored as a symbol and not a string. ALL OF IT"). A function's
  qualified name, a test's, a namespace's, a render pair's function, a
  capability handler, a schedule task's function, a flow step-fn: Malli
  `:symbol`/`:qualified-symbol`, which the schema bridge maps to Datahike's
  `:db.type/symbol` (`src/seon/schema/datahike.clj:66-67`). A string in one
  of those attributes is a defect; a `(str sym)` to write one or a `(symbol
  s)` to read one is the sighting. (`:seon.search/index :symbol` is NOT a
  type mirror — it selects the search tokenizer, `src/seon/search.clj:170-174`,
  and stays.) Changing an existing attribute's type is done at a reset with
  no migration — database data is disposable by ruling. The inventory of
  every site is
  `docs/prds/steward-platform/research/symbols-everywhere-inventory-2026-09-17.md`.

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

**Deletion is retraction, and the past is a history query** (ruled
2026-09-16, program-facts PRD §1f G1). A deleted function, test,
namespace, schema key or issue is `[:db/retractEntity …]`. No entity is
kept alive for the sake of another entity's refs, and there is no
retirement attribute; `history` / `as-of` / `since` answer what was true
before. Datahike's `retractEntity` also sweeps EVERY incoming ref datom
and cascades into `:db/isComponent` children, which is exactly why the
next rule exists — read the datahike skill before you delete anything.

**Required versus optional IS the deletion dial** — there is no policy
property and there never was one. A swept retraction lands in the report's
`:tx-data`, and Seon's final-report validator re-validates every entity the
transaction touched, the swept ones included (`src/seon/db.clj:3014`,
`:2958`). So a **required** ref in the referrer's entity map REFUSES the
deletion and an **optional** one lets it SWEEP silently. Choose deliberately
and say which of the five behaviours you chose in the attribute's docstring:
cascade (component), sweep, refuse, value, or a pending edge its settlement
moves to a durable sibling. Identity-less owned children validate through their
relation's declared `:seon.db/component-schema`; a nonempty identity-less row
without an owning root refuses (`src/seon/db.clj:3071`).

**A fact that must outlive its target stores a VALUE, not a ref** (ruled
2026-09-16, §1f G2). Call edges and test reach become
`[:set :qualified-symbol]`; a symbol denotes itself, so deleting the named
function touches no caller's datom and "A calls a name with no row" is one
Datalog clause instead of an impossibility. Refs stay where a genuine
entity relation exists (`:seon.fn/ns`, `/file`, `/arities`). Calls and
references are indexed qualified-symbol sets, as is recorded test reach.
Canonical arity inputs/returns link to shared `seon.schema.shape` facts; the
old `seon.fn.ast` family is deleted.

**The discriminator is one question: does the fact STATE something about a living entity, or OBSERVE a
TOKEN?** A statement is a ref — a component when the referrer owns the
target, otherwise a peer whose deletion policy is the required/optional dial
above. An observation — a name the analyzer, the author or the reporter saw —
is a VALUE, and whether anything by that name exists is a separate derivable
question. **A function with live callers is not deletable until the
callers are fixed** (owner, 2026-09-16): edges surviving as values is what
makes the breaking call graph readable, not permission to drop the function
silently. The retraction and the repair belong in one transaction, or the
deletion refuses and hands the agent the breakage to fix first — equally for
an SCI evaluation and an edit-hook publication. A fresh reset publication
has no prior definitions to retract and reports unresolved names positively.
A complete publication that removes a prior live identity has no exemption.

**If a reader will ever need to distinguish "we looked and found nothing"
from "we never looked", the looking is an event and the event is a datom**
(ruled 2026-09-16, §1f G4). A cardinality-many attribute with no members
has NO datoms — `#{}` in transaction data emits nothing — so the two
states are byte-identical. Never encode an event in a collection's
cardinality, and never repair it with a submission-time-only check: the
whole-entity validator rebuilds the row from the resulting datoms, where
the empty collection is already gone, so a submission-only check is a
pre-read the authority re-decides.

**A component is part of its parent's value** (ruled 2026-09-16, §1f G5).
Pull expands a component without being asked and `retractEntity` destroys
it with the parent. Final write validation discovers owners in both before
and after through indexed seeks, expands complete EAVT child values, and
validates roots and the relation's declared child schema as one owning value
(`src/seon/db.clj:3071`). Cycles, multiple owners, missing children and exhausted
`:seon.config.db/validation-node-limit` refuse; wildcard pull cannot prove
completeness. Do not invent identity attributes on component rows to make a
selector see them. The bound is declared in `seon.config.db.edn` and carried
by the projection, shared by every writer.

**An entity schema describes the STORED entity only.** A reference has
three grammars — transaction data, datom, pull result — and one Malli key
cannot describe all three. A reader's pulled shape DERIVES from the entity
schema under that reader's selector; it is never a per-attribute
`[:map [:db/id :int]]` widening and never a second hand-written pulled
schema. Evidence, including the twelve hand-written mirrors this class has
already cost:
[entity schema versus pulled shape](docs/prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md).
**And pull silently truncates a cardinality-many result at 1,000 members** —
no marker, no refusal, just a shorter collection
(`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`, `:323`).
That is this project's named failure class living inside the dependency; a
pull that can exceed the bound reports the cut as an elision naming it.
The Datahike behaviour behind all these rules is measured in
[the deletion study](docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md)
and carried with `file:line` in `.claude/skills/datahike/SKILL.md`.

**Presence, absence, and the `contains?` trap.** `contains?` answers "is
this key/index present" — on a vector it checks INDICES
(`(contains? [:x :y] 1)` is true; `(contains? [:x :y] :x)` is false), and
on a map a key stored as nil still answers true. Since Seon never stores
nil, prefer `(get m k default)` with a sentinel default, or `find` when you
need presence-and-value in one step. Reach for `contains?` only when you
genuinely mean key membership and the collection is a map or set.

**The `my.*` / `seon.*` split is surface versus owner, never duplication:**
a capability has ONE durable fact family and ONE system-side owning
mechanism (`seon.cluster.message`, `seon.turn`), and `my.<name>` is
the thin agent-facing protocol over those same facts — reads through
`seon.db`, with writes at the owning function (`src/my/message.clj:31`). Finding two
namespaces for one noun is the ruled layering; finding two FACT families or
two delivery paths for one noun is the defect.

`seon.db` is the ONE database namespace. Use its declared positional and
argument-map arities; agent calls can elide db/conn to the calling agent's
cluster's current database. `transact!` with an explicit connection returns
the transaction report; its elided arity returns transaction identity and datoms
(`src/seon/db.clj:3343`). Failures return flat `:seon.error` values.
Direct `datahike.api` calls survive only inside `seon.db`, the
store/registry and classified branch-custody owners, and system-side
listeners
([specification](reference-code/datahike/src/datahike/api/specification.cljc)
↔ `src/seon/db.clj`).

Config reconciles from an explicitly selected manifest into database facts;
its qualified function symbols must resolve to current `:seon.fn` rows, checked
in one query with missing names reported alongside their config keys. No stored
activation roster mirrors the program graph. Running code reads the database. Provenance is minimal transaction metadata
(resolvable `:seon.db/user` and `:seon.db/process`) — never copied onto
domain entities. Database vocabulary is the dependency's vocabulary:
database value, basis transaction `:t`, commit ID, connection ID, store ID,
branch, branch head, transaction report.

### Vocabulary — grounded names, never invented ones

Use the actual operation or value when speaking and writing: SCI context,
SCI evaluation, JVM REPL, agent turn, effect execution, Datahike branch,
database value, and boot sequence. Do not use a metaphor as their common name.
Use **evaluation** and **result** in prose, not "receipt". The **[TARGET]** binding turn PRD
§15 requires ONE `:seon.eval` entity per branch/turn/ordinal, carrying source,
shown text, out, error, and read evidence. The live result is not a second
durable entity. Legacy `:seon.cluster.eval`, `:seon.cluster.run.form/*`, and
`receipt` identifiers are source references during the owning lane's cut;
they never justify a second entity or a duplicated attribute.
In particular, the MCP evaluation modes are `jvm` (the host REPL) and `sci`
(**SCI evaluation mode**: the cluster's shared SCI context). Preserve literal tool arguments,
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
rebuilding what a core library already built. Rows marked **[TARGET]** describe a ruled integration not yet proven complete
(the linked PRD sections own the target): design toward them with this vocabulary, and when you
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
| web UI | `/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes, and `/data` (`src/seon/render/route.clj:5`) | inspector |
| cluster | one database branch, its agents, and shared plumbing; produces one environment | environment (for the cluster itself) |
| attributes + connections | the Datahike model | entity kind/type |
| build, operator, artifact | the `bin/seon`/`bin/acme` supervisor scope; the digested publication output | flavor |
| get-in, path | paged navigation into a nested value | drill |
| `my.plan`, "the plan" | The task system's authored plan facts and derived obligations; its render function chooses forms from current data and its writes return the changed entity ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.agent.edn:151` declares the plan component; `src/my/plan.clj` follows it) | todo, bare "plan" for turn sources |
| **[TARGET] namespace agent**, `:seon.ns/agents` | An agent responsible for a namespace: its faults, tasks and requests render into that agent's context. Responsibility is many-to-many, a cardinality-many ref set on the namespace (owner ruling 2026-09-19, D1); `:seon.agent/namespace` remains the REPL's current namespace, a different fact. Routing never uses membership: a trigger maps to a TASK (D2). Plan: `docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md` | steward, `:seon.ns/steward`, `steward-call` |
| **[TARGET] task**, `seon.task` | The one family for work an agent does: linked facts (subject refs, tests, errors, functions) plus an optional agent. A "template" is the render pair and units the task's linked data selects, never an entity or a registry; a detected defect is a task whose subject came from a detector; a conversation is derived from `seon.message` facts. A trigger resolves to a task identity at the writer: an existing task's agent receives the occurrence as a wake, otherwise the task is created and an agent spun up (owner ruling 2026-09-19, D1–D2) | issue (as the family name), `seon.issue`, `my.task`, task template, work packet |
| provider descriptor row | one hosted provider's data row under the config singleton | adapter, integration |
| packages/, package.json, deps.edn | each ecosystem's own manifest names | npm-pkgs, maven-pkgs |
| contexts on hosts, binding tables | sci's own vocabulary for agent execution | sandbox, VM, jail |
| `:interrupt-fn` | the ONE zero-arg fn sci calls on every fn body entrance and `loop/recur` (`reference-code/sci/doc/interrupt.md` ↔ `src/seon/sci/eval.clj`) | the guard, the door, the cage |
| `interrupt!` | stops an eval uncatchably (`reference-code/sci/src/sci/interrupt.cljc`) | stop!, steering-error! |
| `time-limit` | the SCI execution deadline; query-work and AI presentation have separate bounds (`reference-code/sci/doc/interrupt.md`) | fuel, gas, step budget |
| `:seon.eval/fn-entries` | a RECORDED DIAGNOSTIC, never a limit | a step budget |
| every `fn` body entrance | where sci calls the `:interrupt-fn` | safepoint |
| `ctx`, `fork` | sci's own names (`reference-code/sci/src/sci/core.cljc`) | warm base, the agent's world |
| `:io` / `:compute` / `:mixed` | core.async's workload tags: `:io` may block but not compute, `:compute` must not block (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`) | eval pool, wait pool |
| turn | An agent's ordered evaluations and any provider attempts; open means no `:seon.turn/closed-tx`, enforced at the writer. `seon.turn` owns the transitions; its schema declares the history render pair ([open?](src/seon/turn.clj:189), [open-call](src/seon/turn.clj:348), [schema](resources/seon/schemas/seon.turn.edn:1)); process provenance rides execution requests, never the turn entity. | run, `seon.cluster.run`, `:seon.cluster.run/process` |
| accretion / breakage | a change that requires no more and provides no less | graduation, nursery |
| **[TARGET]** source initialization rows, transaction data | Static source population is admitted transaction data; the agent's opening is separately evaluated and stored as system turn 0 ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/bootstrap.clj`) | bootstrap-plan rows, seed bundle |
| process record, generation, (pid, start-instant) | operator-managed process descriptors (`script/seon/fresh_operator.clj` ↔ `src/seon/cluster/process.clj`) | orphan registry, liveness flag |
| system turn | An ordinary turn with a reply and no provider attempt; "system" is derived, never stamped. `seon.turn/system-turn` computes the opening and changed reads and optionally stores their evaluations ([owner](src/seon/turn.clj:2191), [debug controls](src/seon/render/web.clj:709)); the wake-answering `:t` rule remains specified by [turn PRD §14](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md) and implemented at `src/seon/turn.clj:2823`. | generated opening episode, generated run |
| message subject | The nonempty string identity token supplied in `:my.message/about`, stored verbatim in `:seon.message/about`; no target lookup is required. Assignment/declination uses `:seon.message/assignment` independently (`src/seon/cluster/message.clj`, `resources/seon/schemas/seon.message.edn`; [program-facts PRD §§1h–1i](docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md)). | about as subject, inside marker, and protocol correlation together |
| inside wake | Population activity that cannot refill the recipient's turn bound. For messages, `:seon.message/from` is the sole marker, independent of subject or protocol; `wake/inside-wake?` consumes the declaration (`src/seon/cluster/wake.clj`, `resources/seon/schemas/seon.message.edn`). | subject presence implies inside |
| handled | A claim ref from the handling turn, written by `seon.turn/close-call` at settlement on `:seon.turn/handled`; message routing remains on listened `:seon.message/to` (`resources/seon/schemas/seon.message.edn`). Whether a wake is answered derives only from its `:t` and `seon.turn/latest-answering-turn-t`, independently of the handling claim. | inbox-edge retraction, read-tx |
| turn loop | The per-agent Flow proc derives work from database facts, advances open/call/evaluations/close, and rewakes when work remains. Its proc and transitions share `seon.turn` ([step](src/seon/turn.clj:4933), [next-agent-work](src/seon/turn.clj:2753), [turn](src/seon/turn.clj:4723)); the agent owner supplies its [graph](src/seon/cluster/agent.clj:422). The full additive-context algorithm remains specified by [turn PRD §14–§16](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md). | run loop, driver, driving |
| `seon.effect`, `effect/request!` | the system-side owner for declared capability requests (fs, web, llm); database writes enter `seon.db/transact!` (`src/seon/db.clj:3213`) — about effects crossing out, never about which functions an agent may call | the door, capability dispatch |
| every function is callable | an agent may call ANY function in its cluster's program graph; what differs per agent is only what is RENDERED into its context, which never gates execution | toolkit, grants, allowlist |
| program graph | the collective `:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test` facts | corpus |
| `my.program` | Agent-facing program reads over one supplied database value; the existing `seon.fn`, `seon.db` and `seon.issue` owners supply selection, facts and detected issue identity (`src/my/program.clj`). | my.refactor, my.code |
| `my.program/breaks` | Pure read of a subject's referrers, declaration spans, current gate set, advisory past reach and explicit unknowns, with a computed plan (`src/my/program.clj:238`; Datahike reverse index: `reference-code/datahike/src/datahike/query.cljc`). Render pairs come from their materialized property datoms. No write or launch. | impact, blast radius |
| `my.program/callers` | Direct `:seon.fn/calls` referrers with the caller declaration's byte span and recorded argument counts (`src/my/program.clj:129`). `:seon.fn/references` stays a separate relation. | usages, dependents |
| `my.program/tests-reaching`; gate set | Current test selection through `seon.fn/gate-set`, including call, reference and declared-subject edges (`src/my/program.clj:144`, `src/seon/fn.clj`). This differs from a test's recorded past `:seon.test/reach`. | test closure |
| `my.program/reads-key` | Contract references, declared writes and literal keyword mentions, as separate groups; mentions do not assert a read or block retraction (`src/my/program.clj:183`; `seon.fn/functions-using`). | keyword consumers |
| `my.program/history` | Source assertion/retraction events with exact root datom values and transaction provenance; optional `:seon.db/tx` selects the requested as-of definition, including metadata changed after its source assertion (`src/my/program.clj:311`; `reference-code/datahike/src/datahike/db.cljc`, `as-of-pred`). | definition archive |
| referrer; caller; reference; subject; reach | Referrer is a live entity naming the subject; caller means `:seon.fn/calls`, reference means `:seon.fn/references`, test subject is a present claim, and reach is advisory evidence from a past run. The program read preserves these relations separately (`resources/seon/schemas/seon.program.edn`). | dependency (without its attribute) |
| plan of a refusal; detector; `seon.issue/subject-id` | One prospective issue per caller, identified by the detector plus the caller's installed identity value using the generator's same `seon.issue/subject-id`; tests come from the caller's gate set (`src/seon/issue.clj`, `src/my/program.clj`). `seon.program/unresolved-callers` is the target done condition. Launch is unavailable until the detector can truthfully represent the repair subjects; the read states that limitation. | work packet, separate task registry |
| redefinition; retraction | Redefinition replaces definition facts at one identity; retraction removes facts and leaves the past to history/as-of (`seon.program/exact-replacement-tx`, Datahike `retractEntity`). Surviving named referrers refuse deletion unless repaired in the same transaction. | soft delete, retirement |
| proc, step-fn, conns, graph-def | `clojure.core.async.flow`'s own vocabulary (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:78`, `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165`) | invented scheduler nouns |
| `(sliding-buffer 1)` tap | core.async's own newest-only delivery | latest-wins mailbox |
| tuple (`:db/tupleType`) | Datahike's single-value ordered construct; cardinality-many is a SET (`reference-code/datahike/src/datahike/index/persistent_set.cljc`) | small limited vector |
| `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere. ASSIGNMENT IS NOT IDENTITY: `:seon.agent/namespace` is not unique (`resources/seon/schemas/seon.agent.edn:79`) — several agents may share one, and an agent may `in-ns` anywhere its REPL reaches. The one agent a namespace answers for is its `:seon.ns/steward` | agent workspace, sandbox ns |
| **[TARGET]** render function | A function of the data that chooses its forms. ONE AI/HTML pair per entity schema, never per scalar attribute; when no pair is declared, use the default attribute-map printer. Evaluation entities render saved shown text through `seon.repl/render-ai` and `render-html` ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:387`) | producer, view, read form, `/form` face, `:seon.render/form` |
| `:seon.render/ai` | The entity's AI projection. Generated source is evaluated in an ordinary system turn; historical evaluations render stored shown text without executing their forms. The walk and evaluation schema pair share `seon.repl/text` as the REPL grammar ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:213`) | separate teaching prose, text render, "the string consumed by agent context" |
| `:seon.render/html` | the Hiccup consumed by the web UI, or the symbol naming its function (`src/seon/render/hiccup.clj`) | hiccup contract |
| live result object | The actual result retained by evaluation id in the agent's SCI context, bound directly by `result/e<id>`. It is not serialized, admitted as a stored node, or restored after restart ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); binding: `src/seon/sci/eval.clj:513`; SCI `intern`: `reference-code/sci/src/sci/core.cljc:260`) | result serialization, stored print node, result blob, restorable node |
| wire (external crossings only) | a crossing that LEAVES the process (provider HTTP, browser SSE); internal transport is channels, flow, facts | wire (internal) |
| namespace page | one namespace's web surface: route → namespace → owner agent → walk in `/html` (`src/seon/render/route.clj`) | page, screen, dashboard |
| **[TARGET]** block | One entity rendered through its schema pair, covering a whole concern. Scalars share its own block; components and declared derived queries supply their blocks. The identified output is the HTML morph target ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/render/block.clj:61`) | widget, component, panel, scalar block |
| package, keyframe, delta | the delivery units: one revisioned package per change; a revision gap snaps to keyframe | frame, bundle |
| base SCI context / agent SCI context / prompt | The cluster's program-only context derived by `seon.sci.eval/base-ctx` from one database value; each agent's retained handle receives a new fork with its private objects reapplied before later turns; the ordered rendering of its stored evaluations. Neither private objects nor prompt visibility gates callability ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj:1709`; SCI `init`: `reference-code/sci/src/sci/core.cljc:331`, `fork`: `reference-code/sci/src/sci/core.cljc:345`, `intern`: `reference-code/sci/src/sci/core.cljc:260`) | turn fork, "the context" for all three |
| override | A function identity whose current admission is `:agent` in an indexed `src` namespace; `seon.program/overrides` queries current admission and historical declaration/file relations from one database value (`src/seon/program.cljc:20`). It is never a stored flag. | override flag, namespace kind |
| candidate context | a built context used to test a definition before installing it; `sci/fork` is admissible (copy-on-write Vars) | sandbox ctx, scratch fork |
| compaction | Wipe the agent's evaluations; the next system turn regenerates the opening from current record facts using the same algorithm. There is no manual curation path ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/turn.clj:2207`) | editor, revision, proof, curation, supersession |
| render profile | The presentation policy applied by the value renderer once at evaluation time; the evaluation stores the resulting shown text. History never clips again; HTML renders the live object without presentation clipping, or saved text after restart ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `resources/seon/schemas/seon.render.profile.edn`) | cap, window, separate result storage bound |
| elision value | ordinary data describing omitted count, path, next offset, and requery identity (`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`) | ellipsis, truncation marker |
| `:seon.fn/external-sink`, `:seon.fn/projection-boundary` | queryable program-graph leaf facts; `seon.fn/output-path-report` derives projected/bypass/unresolved paths (`resources/seon/schemas/seon.fn.edn` ↔ `src/seon/fn.clj`) | sink roster, output allowlist |
| **[TARGET] root maintenance portfolio** | root's declared scheduled reclamation/inspection/repair tasks ([design](docs/archive/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md)); update this row when the owners land | maintenance daemon |
| **[TARGET] `my.branch`** | agent-facing branch/history functions over database branches — git vocabulary without claiming to be git ([PRD](docs/archive/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)); update this row when it lands | my.git, my.repo |
| private layer | The agent's defs and atoms as actual objects in its persistent SCI context, isolated from the base and other agents, lost on JVM restart. Installed functions, schemas, and tests are durable program facts ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); agent acquisition: `src/seon/cluster/agent.clj:636`; SCI isolation: `reference-code/sci/src/sci/core.cljc:345`) | `:seon.def/*`, session image, restored defs |
| **[TARGET]** evaluation entity | One `:seon.eval` entity per branch/turn/ordinal identity, carrying source and outcome evidence including shown text, out, error, and read evidence. Its actual result stays in memory; identity derives through `seon.id/evaluation` ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/id.clj:55`) | `:seon.cluster.eval`, `:seon.cluster.run.form/*`, frozen form entity, receipt, `form-identity` |
| the agent's history | The walk rendering `(seon.eval/of-agent db agent)` (`src/seon/eval.clj:9`) in chronological turn order and ordinal through the evaluation schema's pair, from stored shown text. All turns are shown by default; previous prompt bytes remain unchanged until compaction ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/repl.clj:213`) | transcript, transcript entries, session units, run-form facts |
| `doc`, `dir` | REPL documentation as DATA from public program rows: `dir` returns symbol, arglists, first docstring line, and input/output contract; `doc` returns the full docstring and contract ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj:1194`, `src/seon/sci/eval.clj:1222`) | faces tool, print face, separate teaching prose |
| `seon.id/id`, `seon.id/digest`, `seon.id/evaluation` | THE ONE IDENTITY DERIVATION: `id` hashes `(pr-str data)` with SHA-256, default length 12 or supplied length; zero arguments mint a fresh event. `digest` and `evaluation` call it ([owner](src/seon/id.clj:1)). Ordinary turn IDs include branch, agent, and turn ordinal ([next-id](src/seon/turn.clj:452)); their evaluation id is `(seon.id/evaluation turn ordinal)` because the turn already includes its branch, and the handle is `(seon.id/symbol-in "result" \e id)` ([handle](src/seon/sci/admit.clj:614)). The explicit branch/turn/ordinal arity also remains available. `random-uuid` only for a genuinely fresh EVENT with no identity of its own. Never a new generator, never a second truncation (owner, 2026-09-08) | short-id, nanoid, hand-rolled hashes, `(str (random-uuid))` for things that have parts |
| read evidence | The dependency plans and revisions a read observed, used with its evaluation `:t` to detect changed facts. Every distinct generated or agent-written read participates; writes and effects never rerun ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/db.clj:682`, `src/seon/db.clj:849`) | copied read result, inbox-only refresh |
| shown text | The exact value-renderer text the agent saw at evaluation time, saved with source, out, and error. It includes profile elisions and requery forms, survives restart, and cannot restore the live object ([turn PRD §13–§15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md); `src/seon/sci/eval.clj:1957`, `src/seon/repl.clj:52`) | result EDN, stored print node, serialized result |
| candidates (per-render selection) | the contract-fitting render function selection consulted per render call (`src/seon/render.clj`) | roster, acquired index |

## 4. REPL-driven development

**Start every Clojure change at a running system, not at a file.** The
tools that connect you to the metal are the difference between guessing and
knowing — the REPL is the first design and diagnosis surface; checked-in
source and tests are the durable authority.

The loop:

1. **Get a live system.** `bin/seon status`; use `default` for ordinary changes, as configured in
   `.claude/seon-hook.edn:25`. Isolated operator roots are for destructive
   drills; a bounded assignment never operates another lane's process.
2. **Reach it.** `mcp__seon__runtime_status` reports the selected cluster's health;
   `mcp__seon__eval_clj` evaluates in the selected cluster's JVM (qualify
   the cluster when several are live — ambiguity must fail). Its `jvm`
   mode is the host prepl with NO cluster custody bound — `seon.db`'s
   elided db/conn arities refuse there; `(seon.operator/connection
   "default")` supplies explicit custody. SCI evaluation mode (the tool's
   literal argument is `mode: "sci"`) evaluates through
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
**A dependency's semantics are READ from the vendored source at design time,
never inferred from observed behaviour** — cite the seam by `file:line`, because
what a dependency does in the cases you happened to run is not what it
guarantees.

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
([plan/README.md](docs/prds/context-generation/plan/README.md), with
[`unsettled.md`](docs/prds/context-generation/plan/unsettled.md) as the working edge) is the entry to the binding design and current working edge; the dated
ledger records rulings — dates, ruling numbers, and incident history live THERE. Bounded
PRD chunks own their inventory and evidence. Update the affected authority
in the same commit as the change that invalidates it; one fact lives in the
deepest file that owns it.

## 5. Testing

### Test fixtures — the one right way

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
8. **Fixture entities come from the canonical helpers, never a hand-written
   map.** A program declaration is `seon.test-support/program-fn-row`
   (`test/seon/test_support.clj:1038`), a config overlay is `apply-config!`
   (`:1051`), a cluster is `seed-cluster!` (`:1070`), and every fixture
   write goes through `transacted!` (`:296`), which surfaces the writer's
   refusal instead of letting the test read absence as behaviour. A
   hand-written map meets one required key per gate run: on 2026-09-17
   `seon.background-blob-test` was refused for
   `:seon.schema.admission/source`, then again for `:seon.fn/ns`, across
   three cold runs (`cb97d3e4b`, `af5a0fdbe`, `20e6791ba`) before the
   helper replaced the map. Patching keys one at a time is the defect.
9. **A fixture never retracts what a running loop is settling.** The live
   Juniper fixture's history wipe raced the agent loop's own settlement and
   the loop then re-asserted a turn that no longer existed
   (`the-live-juniper-fixture-wipes-turns-under-a-running-agent-loop`);
   `seon.turn/open-run-tx-call` (`src/seon/turn.clj:417`) now makes that
   decision inside the transaction, but a fixture that mutates an agent's
   facts stops the graph first or hands the loop the decision.

Tests deliberately changing instrumentation use
`seon.test-support/preserving-instrumentation-state` (`test/seon/test_support.clj:1085`),
which delegates to `seon.instrument/restore!` (`src/seon/instrument.clj:799`):
it restores the entering callable roots and Malli function-schema registry,
including after a throw, and LEAVES ALONE any definition a reload replaced
inside the scope, returning that set (`replaced-definitions`, `:773`).
Callable roots are restorable; the protocols and classes those closures were
compiled against are not — a test body that reloads a program namespace (a
development adoption inside a worker did this on 2026-09-17) and then
reinstalled the entering roots left every later `seon.print/text-sink` in
that worker building a superseded class its own `sink?` refused
(`restoring-captured-roots-reinstalls-a-definition-a-reload-replaced`).
The runner's drift detector remains the independent check; its automatic
re-arm does not excuse a test leaving its worker unarmed.

Running a test in process from the development JVM (`seon.test/run`,
through `seon.test/resolve-test`) requires an explicit cluster in options or
the supplied SCI environment. Its execution waits the declared
`event-backstop-seconds`, which the shared fixture base's
post-adoption construction consumes whole, so hand `:seon.test/remaining-ms`
explicitly after an adoption and never read that first bound failure as a
red. An agent's own test goes through `seon.test/run-owned` (`:380`), which
binds exactly the connection on the request via `seon.db/call-with-custody`
(`src/seon/db.clj:259`); a host run binds only the body custody explicitly
supplied in options. Both paths admit a fresh run event and record immutable
members through the shared recorder; covered requests execute nothing.
Issue completion, failure discovery and test rendering query those members
through `seon.test/recorded-result` / `seon.test.runner/latest-results`.
Fresh operator boot supplies the resolved `:test` classpath once
(`script/seon/fresh_operator.clj`, `launch!`); development adoption uses that
JVM's loader without preparing another classpath or starting another JVM.
A host started before this change still has its original classpath.

Acquire resources inside `with-open` scopes; use
`seon.test-support/closeable` when release is a separate function. Setup failure
and one failed cleanup must still release every earlier acquisition. Evaluation
submission fixtures acquire their real SCI context before the timed submission
and carry their namespace and database explicitly. Proc failure injection
preserves lifecycle arities when the transform is its subject.

Deeper mechanics: `.agents/skills/clojure-testing/SKILL.md`; a new fixture
class updates both in the same commit.

### The gate and what a proof is

Lanes iterate with `bin/test-fast <namespaces…>`; the orchestrator gates a
commit with `bin/test --paths <owned files…> -- <namespaces…>` and
`bin/test --platform`. `bin/test` refuses cold gates when `SEON_CODEX_LANE`
names a lane, before taking a slot or creating a run root, and prints the
`bin/test-fast --paths <your files> -- <namespaces>` replacement. An
orchestrator flag does not override lane identity. The fast loop uses the
worker's same contract arming in one JVM, including private functions with
declared contracts (`seon.test.arm/arm-contracts!`), with a worker-private copy
of the exported publication and an isolated Datahike branch per fixture.
The worker acquires its projection once; fixtures carry it without indexing
source. Plain namespaces use the working tree;
`bin/test-fast --paths <your files…> -- <namespaces…>` reuses the gate's
HEAD-plus-selected-files snapshot and removes it after the JVM exits,
excluding foreign half-edits without preparing a published base. It provides no
per-worker isolation, retained run roots, or automatic platform tier.
Fast requests admit and record through the cold gate's source authority;
matching green members report unchanged without execution. Tests explicitly exercising file-backed boot still
create their own fixture roots. These are iteration results, not the
isolated gate's proof.

Cold `--paths` admission checks a published graph against the snapshot's
program-source input digests, using the existing base cache's recorded inputs;
documentation-only commits do not invalidate it. A cold gate prepares a missing
baseline itself through `bin/test --prepare-head-base`, then checks its overlay
before running tests. A `--fast --paths` invocation uses the newest published
base even when source has advanced, announcing its digest and commits behind
HEAD (explicitly unknown for legacy records without Git provenance). It cannot
publish: only absence of every published base causes the pre-JVM baseline
refusal naming that orchestrator command.
The explicit preparation uses HEAD alone, without workers or tests. The check follows
published `:seon.fn/calls` edges from changed public declarations and reports
dirty caller files omitted from the overlay: their HEAD bytes are tested.
Those paths travel in `:seon.test.run/callers-at-head` provenance; admission
never substitutes checkout bytes for them. Lanes cannot prepare the baseline.

Both entry points acquire a slot through `bin/_test-slot:14` before launching
test JVMs and release it on exit. That shell declaration owns the default
count per source checkout, shared by its lanes; separate checkouts have
separate slot directories. `SEON_TEST_SLOTS` sets the count only with
`SEON_TEST_ORCHESTRATOR=1` and no lane identity; the same admission rule
applies to `SEON_TEST_SILENCE_SECONDS`. Both launchers refuse unauthorized
overrides before acquiring a slot or launching a JVM. Silence and worker
exchange bounds include declared `:seon.test/long-ms` work and measured
fixture priming; the fast reporter carries long-test allowances too.
`SEON_TEST_SLOT_WAIT_SECONDS` bounds the wait.
A slot bounds invocations,
not the number of worker JVMs inside one gate.
`seon.test.cache/worker-count` owns pool sizing for launcher and coordinator;
`worker-checkout!` owns every isolated worker copy. The shell keeps the
worker-checkout preparation phase and its bound, calling those runtime owners.
Slot waits and the `bin/test` preamble announce orphaned gates with their
PID, run root, elapsed time and last recorded phase. A dead launcher with
a live recorded runner remains for the orchestrator to reclaim. When both
holder and recorded runner are dead (or the runner is absent), the slot is
exhaust and is reclaimed automatically. This detects parent death, not a turn ending
while its launcher remains alive or whether anyone read the tally.

`bin/test` is the one correctness gate, tiered: the declared
`:seon.test/platform` regressions run FIRST and stop the run when red; bare
adds tests reaching code changed since the recorded green basis (derived
from `:seon.fn/calls` edges, never mtimes) — deliberately widening to every
eligible test when the basis is missing, a file was removed, or a changed
gate input sits outside the program graph; `--all` adds every
non-long test; explicit namespaces select their complete eligibility scope.
A lane with concurrent
neighbours uses `bin/test-fast --paths <its own files…> -- <namespaces…>`,
which snapshots HEAD and overlays only those paths. A LANE NEVER RUNS
`bin/test` gates, `--all`, OR `--full`; the orchestrator owns cold gates and
integration checkpoints. `bin/test --fast` is the shared implementation of
the fast snapshot, not a cold gate. The runner enforces the
bounded-execution law: a liveness watchdog dumps coordinator AND worker
JVMs, and the tally is total — unlaunchable or unconfirmed work is typed,
never silent. By default, every canonical gate records the tests it ran on `:current-src`
through `seon.test.runner/commit-results!` (`src/seon/test/runner.clj:1435`);
recording failure fails the gate. Fast iterations use the same recorder,
carrying the resolved published base, actual snapshot program and overlay
input digests, and tested basis. Every request is a fresh run event.
The checkout green-basis file is retired; the basis derives from recorded
facts. Both launchers print through `seon.test.runner/print-recorded-tally!`,
using the queried facts returned by recording and selection. Recording failure
reports an unavailable tally, never a successful durable verdict.

**Shared request policy:** every policy is an eligibility scope, never an
execution promise. Named/all/full/platform requests reuse each member whose
recorded green matches the program digest, input digest and requested basis.
Selection reports each such member as `:seon.test/unchanged` with all three
confidence values; only members lacking that evidence execute. Named selection
stays inside its requested namespace/identity scope. An earlier program's green
member may also be reused when its reachable content and external inputs match;
the returned confidence retains the original tested basis and program digest.
Executable platform members run first. An unchanged green request can execute zero tests
under any policy. `seon.test/select` owns set selection and reuse;
`seon.test/run-owned` uses that same selector and admission with the calling
agent's explicit cluster custody. `:seon.test/recorded-basis-t` distinguishes the result
recording transaction from the tested basis. Shell integration remains the
post-reset Stage 1 work; this paragraph does not claim it has migrated.

Tests find design issues; structure dissolves them: when a failure class
appears, move the invariant to one choke point and keep ONE regression per
class asserting the WANTED behavior. A smaller suite is a desired outcome;
the health metric is class coverage. Every proof must be claimed by a
recurring surface. Fixture load paths are not the live boot path: schema,
acquisition, and process changes need the reset-boundary live proof. After
code changes, verify the running system, not only the tests, and report
what is still broken honestly.

The edit hook runs clj-kondo over prospective Clojure edits and publishes
admitted changes to `current-src`; it never runs tests. A KONDO "Unresolved
var" ON A PROTOCOL OR A DEPENDENCY NAME IS A STALE DEPENDENCY CACHE UNTIL
PROVEN OTHERWISE: repopulate with the publication classpath, `clj-kondo --lint "$(clojure -Spath)"
--dependencies --skip-lint --copy-configs`, re-lint, and only then treat a
surviving error as yours. Do not feed the test alias's `.` entry to clj-kondo:
it recursively traverses the whole checkout, including disposable test roots
(`deps.edn:135`; `reference-code/clj-kondo/src/clj_kondo/impl/core.clj:337`).
For a test-only dependency, lint that dependency's classpath entry explicitly.
Never rewrite correct references to satisfy a
cache. The configured hooks
cover `apply_patch`, `Edit`, and `Write`, including default config and schema
publication. Shell file writes do not trigger them: use `bin/seon init --dev default --changed
PATH` after source edits and verify adoption. Syntax,
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
bin/seon reset --force           # preflight (syntax, lock holder), down all, destroy, republish, refork, start, adopt
```

Absent cluster means `default` for `start`/`config apply`; bare `init`
means the published `current-src`. Stop/down act on exact recorded process
identity — a reused pid can never be killed by mistake. Never launch the
operator's internals separately or kill its children blindly; use
`bin/seon down` so the supervisor reaps its own. DESTRUCTIVE DRILLS AND
SECOND DEPLOYMENTS USE `--root`. `bin/acme` is a thin root-scoped wrapper
selecting cluster `acme`.

**The default cluster is the development environment.** The hook selects
`:root "."` and `:cluster "default"` (`.claude/seon-hook.edn:25`);
`bin/seon start` uses that ordinary cluster. Juniper is fixture data, not a
cluster name or a separate development root. Use explicit root and cluster
in MCP calls; JVM evaluation has no agent-scoped database argument defaults.

The hook's configured publication target is `init --dev default`; publication
and adoption must finish before a file edit is live. The configured quiet
window coalesces edits (`.claude/seon-hook.edn:27`); do not assume one
completed adoption per edit. Read hook feedback and
`logs/current-source-failure.log` on publication failure. A source commit
is recorded only after adoption succeeds; compare the cluster's
`:seon.source/commit-id` with `seon.cluster.source/current`, then observe the
browser separately. Shell source writes bypass the edit hook;
follow them with `bin/seon init --dev default --changed PATH`.

Lane development and schema-refork rules are above.
They do not override an explicit assignment to stop at a foreign failure.
Scratch roots are reserved for destructive drills, never a second ordinary
development environment.

**Session-start hygiene (standing order).** The orchestrator owns the shared
exhaust sweep; a bounded lane verifies its inherited state and cleans only
its own disposable work. A fresh session begins by
verifying the system it inherited, not by trusting it: check `bin/seon
status` and that the MCP tools answer (a stale long-lived JVM serves old
code — verify adoption freshness; a lane never resets `default`);
`git status` for another session's residue (preserve it, report it, never
build on it unreviewed); and sweep the disposable exhaust. Operationally: a retained
`tmp/test-runs/run.*` root is sweepable when no live runner holds it and
its recorded failures have since been fixed or re-observed at HEAD (when
in doubt, a holderless root older than a day is exhaust); scratch cluster
roots under `tmp/` are sweepable when their creating probe or lane has
returned; and the shared root's store footprint (`bin/seon status --verbose` prints
it) is judged against its size after the last reset — an
order-of-magnitude growth is the investigation signal, not a number to
tune. **Before deleting any
run root, confirm no live runner holds it** (`bin/codex-agent status` for
lanes plus the process table for `bin/test` JVMs) — sweeping an active
root kills a foreign gate mid-run. Database data is disposable by ruling:
reset rather than migrate when the assignment authorizes the affected root. Everything under `tmp/` is throwaway until
production; a disk filling with dead roots is a defect to fix in the
minute it is noticed, and the [TARGET] root maintenance portfolio is the
machinery that eventually owns this automatically.

**Churn is weather, not a blocker.** Clusters, JVMs, and advertisements
come and go while you work — ADAPT AND CONTINUE. Your cluster vanished:
re-derive from `bin/seon status` and start a fresh one. A long-lived JVM
serves the code it loaded at start — suspect staleness before suspecting
correct code. Stop for a genuine implementation dependency, named exactly, or the
explicit foreign-failure boundary in the assignment. **Recursive deletion NEVER follows symlinks**; plant a symlinked
sentinel in any cleanup regression.

The shipped model is DeepSeek through the single `seon.ai` HTTP owner;
AI dials are schema-declared config facts with per-agent overlays (`config/default.edn:358`), including the separate backup and retry namespaces.
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
harness-tracked background commands, run BARE — never piped. The default lane model is
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
a lane's heartbeat; the orchestrator pushes at every coherent checkpoint. A foreign lane's breakage is a verification boundary, not evidence against
your slice. Follow the assignment's stop rule; never resume, message, or
edit another lane's session or files to get past it.
VERIFY THE CLAIM BEFORE YOU NAME THE CAUSE: an attribution is a hypothesis
until a probe confirms it; a lane that refutes its assignment with evidence
has done its job.

**Launching a lane (orchestrator rules, measured 2026-09-17).**
- **One lane per class, after a query.** A red gets its own lane only once
  `docs/seon/issues/` and the working edge show it is not a member of an
  open class. The honest-fixture sweep found five instances of one disease
  (`fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour`)
  across 297 tests; each instance given its own lane repaid a full grounding
  cost for a fix one class lane covered.
- **The launch cites the ledger entry it extends, and the lane's first act
  is reading the issue note's `status`.** A settlement lane was launched on
  2026-09-17 for work already dissolved at `0c8f90630` two nights earlier;
  the lane found it by reading and made no change. The ledger, not the
  report, is the launch authority.
- **The spec carries raw evidence paths, never an attribution.** Hand the
  gate log block, the fault entity's blob digest, the writer log lines. Two
  lanes that night refuted the orchestrator's stated cause from evidence
  that was readable before launch (the "oversized refusal" was
  `seon.error/bounded-admission` working as declared; the real cause was in
  the writer log's `no-such-run` rejections). A guessed cause costs the lane
  the time to disprove it.
- **A schema resource and its loaded consumer land in one publication.** An
  edit to `resources/seon/schemas/*.edn` is live on disk for every reader the
  moment it is written, while the JVM still holds the previous `def`; one
  lane's in-flight rename of a program identity key refused
  `seon.test.runner/provenance` for every other lane's in-process run until
  it converged (`live-resources-outrun-the-loaded-program-identity-list`,
  `one-lanes-intermediate-edit-refuses-adoption-for-every-lane`). Until the
  identity list derives from the same forms, a lane edits the resource and
  its consumer in the same edit-hook publication or not at all.
- **Protected means concurrently edited, and it is released the moment the
  holder lands.** The astra lane that landed root-relative source identities
  stopped correctly at two protected hunks; both files were free by then and
  the stop cost a resume. Check `git status` before naming a path protected.
- **Bound the JVM's io-prepl connections.** At most four processes probe the
  development cluster's prepl at once, the peer session's gate included;
  research lanes run from files, `git`, and gate logs with at most one
  read-only evaluation.
- **The orchestrator owns the cold gate.** An in-process run
  (`seon.test/run` from the development JVM) and `bin/test-fast` are
  ITERATION: they share the worker's arming but not its isolation, retained
  run roots, platform tier, or recorded result facts. Landing evidence is
  the orchestrator's `bin/test --paths <owned files…> -- <namespaces…>` plus
  `--platform`. Lanes report their fast tally and the cold proof still owed.
- **Lane identity is declared by the launcher.** `bin/codex-agent` exports
  `SEON_CODEX_LANE=<name>` on both run and resume. `bin/test` refuses cold
  gates with that identity and directs the lane to `bin/test-fast`; both
  ordinary fast iteration and its shared `bin/test --fast --paths` path
  remain admitted. Claude Agent subagents supply no launcher identity:
  their hook payload supplies `agent_id`/`agent_type`, so the hook can
  identify them; the shell launcher does not receive those payload fields.
  Until hook-side admission is installed, instructions bound their gate use.
  Do not infer
  lane identity from a model name, worker count, or ephemeral-owner PID.
  Lanes cannot set `SEON_TEST_SILENCE_SECONDS` or `SEON_TEST_SLOTS`, even
  alongside an orchestrator flag; declare test duration with
  `:seon.test/long` and `:seon.test/long-ms` instead.
  When fast overlay admission has no published graph, the orchestrator runs
  `bin/test --prepare-head-base` before lanes resume `--paths` iteration.
  Codex snapshots hook configuration at process start: after changing
  `.codex/hooks.json`, the orchestrator stops and resumes running lanes.
  `bin/codex-agent` enables the vetted project hooks explicitly and resumes
  from its retained `lanes/<name>/sid` record, never from quoted transcript
  text. See [the verified reference](docs/seon/reference/codex-cli-hooks-2026-09-17.md).
- **Resets are the recovery for schema breakage, not an event.** Eight
  resets on 2026-09-17, each under two minutes, took the store from 21 GB to
  102 MB; none lost anything a reseed did not restore. Database data is
  disposable by ruling; a lane records RESET NEEDED and continues.

**Orchestrator sweep.** At each integration checkpoint, inspect lane progress
and bounded logs, the `default` debug page and runtime errors, gate failures,
shared-tree residue, and disposable disk use. Verify a reported cause before
assigning it; observe the page instead of inferring paint from publication.
The full sweep is `docs/TRANSFER_PROMPT.md:309`. Codex uses native lane
status tools; the CLI examples there apply to Claude. A bounded lane does
not perform this cross-lane sweep or take over another session.

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
- [docs/prds/context-generation/plan/README.md](docs/prds/context-generation/plan/README.md)
  — the entry to the turn-loop design;
  `unsettled.md` is the working edge and the ideas ledger holds dated rulings. A second ordered list anywhere is a
  defect;
- [docs/seon/issues/README.md](docs/seon/issues/README.md) — issue
  lifecycle, severity, query tags;
- `.agents/skills/` — load the matching skill before specialized work.
