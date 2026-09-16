---
type: prd
status: ruled direction, staged; stage 0 design review next
created: 2026-09-17
owner: Sean (rulings D1, E1, E2 of 2026-09-17)
parent: program-facts-are-the-runtime-prd-2026-09-17.md (slice S9)
tags: [prd, steward, testing, seon.test, seon.test.runner, bin/test, datahike, sci]
---

# The test system is the database

**Ruling (owner, D1):** the database runner is the one test gate. `bin/test`
becomes a launcher of isolated worker JVMs that run through the same runtime
and SCI contexts and record through the same functions. It is not a separate
test runner decoupled from the system. Default behaviour: name the cluster,
run only the minimum tests implied by what changed since the last recorded
run on that cluster, and schedule them knowing globally how many tests are
running in which workers.

**Two further rulings (E1, E2):**

- **E1 — the cluster is explicit, no magic.** An agent's tests run on the
  cluster its custody names. A human names the cluster when launching. Tests
  work on any cluster and record to the one they ran on; a cluster other
  than `default` is simply separate from the main system.
- **E2 — coverage is the stored call graph.** "At least one test exercises
  this function" means a test reaches it, transitively, through the
  clj-kondo-derived `:seon.fn/calls` edges we store. No metadata on tests
  naming what they test, no annotation of any kind.

This document is written so a competent lane can implement each stage
without inventing anything: every stage names the functions it changes, the
facts it reads and writes, the regression that proves it, and what it
deletes. Read the parent PRD's §1b–§1d and §4b (lane rules) first, then
[AGENTS.md](../../../../AGENTS.md) §2, §5 and §7. Vocabulary is
`clojure.test`'s, Datahike's and SCI's: **test Var**, **deftest**,
**assertion**, **report**, **entity**, **basis `:t`**, **branch**,
**connection**, **ctx**.

---

## 0b. Reframing ruled 2026-09-17 14:30Z (owner): the cluster's JVM is the host

- **The primary host for tests is the cluster's own JVM, in process**, through
  the one run function with that cluster's custody: agent-authored tests run
  in SCI, first-party tests run as the loaded code, overridden identities load
  by provenance exactly as the agents' contexts do (parent PRD S3). An agent
  writing a test in its REPL sees and runs it the same way the batch gate
  will, because they are the same function on the same facts.
- **Isolated worker JVMs are the exception**, for the tier that must not run
  inside a live JVM — the platform / destructive / boot tier — and for gating a
  checkout snapshot that is not the loaded state. Those tests run the
  platform itself and need not be runnable for most updates agents make; they
  run separately. When a worker runs, it acquires the named cluster's program
  from that cluster's facts by provenance, not from files alone.
- **One base code for the platform is an accepted limitation for now:** the
  platform tier runs the checkout snapshot's `src` and `test`; agents' work
  lives as program facts and reaches the platform's own source only through
  the gated write-back (parent PRD S5).
- Consequences for the stages: stage 2's resolution is by identity from facts
  on both hosts (already stated); stage 3's claims are for the in-process
  host first (an in-process executor is a process record too), and the
  isolated-worker custody question reduces to the destructive tier, where an
  immutable snapshot is the right answer because those tests destroy what
  they run on. The stage-0 review's two amendments stand (counts on the
  member; report entities only for failures and errors, keyed by signature;
  the platform marker with a reason).

## 0c. Rulings of 2026-09-16 evening (owner)

- **Measured premise correction.** The tests are not IO-bound and the worker
  pool is already a dynamically claimed queue at 83–91% utilisation
  (`../research/test-execution-model-2026-09-16.md`). Flow is not the
  execution model for the first-party suite. Isolated worker JVMs stay for
  the destructive tier and for gating a checkout snapshot; the cluster's own
  JVM hosts the rest, serially until a derived fact names which Vars a test
  redefines (`with-redefs` is process-wide), after which two bodies may run
  concurrently only when that fact says they cannot collide.
- **Preparation costs come first.** A cold gate spends 42% publishing the
  program facts of the snapshot from scratch, 7% rebuilding the program graph
  the base already carries, and 16 s per worker rebuilding the fixture base;
  test bodies are 27%. Publication of a snapshot derives from the nearest
  published base plus the changed files (the edit hook's own path), never a
  full rebuild on a digest miss. Lane `test-preparation-costs`.
- **New code is where fixes go; old paths are deleted as callers migrate**
  ("We are migrating to the new test code so fix everything in the new code
  and we can remove the old test code and migrate it to call the new code").
  No repair lands on a path this document deletes.
- **The `orchestrator-only` test mode is deleted** (`fa971495f`): it refused
  every lane invocation including `bin/test-fast`, so lanes committed
  untested. The two-slot bound remains the load cap.
- **Two facts the stage-1 selection needs first:** `:seon.test/platform`
  becomes an indexed attribute lifted through `seon.program/test-marker-attributes`
  (today it is Var metadata; 0 of 1,833 rows carry it), and the bare
  namespace set derives from `:seon.ns/name` rows under the declared `test`
  source root minus a `:seon.test/fixture` marker, never from a filename
  `find` (`../research/platform-tier-is-a-fact-2026-09-16.md`).

## 1. Principles that decide every design question below

P1. **One function per concern, called from both hosts.** Selection,
    resolution, execution, recording and tallying are each one function in
    `seon.test` or `seon.test.runner`, called with different custody from the
    development JVM (in-process), from an agent's turn (`run-owned`), and from
    a worker JVM (the launcher). The launcher contains no logic that is not
    "start a JVM and hand it a request".
P2. **Facts over tallies.** A run, its selected tests, which worker runs each,
    and each result are entities. The printed summary is a render of a query.
    Nothing is counted in the shell.
P3. **Selection derives from facts that already exist**: `:seon.fn/calls`
    edges, `:seon.test/reach` digests, the last recorded run's basis `:t` on
    the named cluster. No mtimes, no file lists, no "dirty" flags.
P4. **A test loads by identity from facts, not from a file path.** Where the
    analyzer found it is a coordinate; how it loads is decided by its admitted
    row: an indexed test resolves to its Var on the worker's classpath, an
    agent-authored test is evaluated from its stored source in the cluster's
    base SCI context (parent PRD S1/S3 make the two identical).
P5. **Bounds are declared and loud.** Worker count, per-test exchange bound,
    the event backstop and the slot count stay; a bound firing names what
    never arrived.
P6. **Delete the superseded path in the same stage that replaces it.** No
    parallel tally, no compatibility flag, no second selection.

---

## 2. What exists today (verified 2026-09-17)

| concern | today | file |
|---|---|---|
| run one test, commit result facts | `seon.test/run` (2- and 3-arity), `run-owned` for an agent | `src/seon/test.clj:306`, `:380` |
| select by change | `seon.test/changed-since-green` (last green basis), `seon.test/check` (tests reaching changed symbols) | `src/seon/test.clj:54`, `:736` |
| reach | `seon.fn/gate-set`, `seon.fn/tests-reaching`, `:seon.test/reach` digests | `src/seon/fn.clj:1157`, `:1214` |
| worker execution | `seon.test.runner/run-var!`, `run!` (`:seon.test.runner/run-request` → `run-result`) | `src/seon/test/runner.clj:579`, `:1737` |
| provenance | `seon.test.runner/provenance` → `:seon.test.run` entity: id, `:seon.test.run/program-digest`, `basis-t`, `branch` | `:1979`; `resources/seon/schemas/seon.test.run.edn` |
| recording | `record-tx`, `commit-results!`, `record!` (refuses the default cluster as a target from a worker: `::default-cluster-refused`) | `:2097`, `:2313`, `:2378` |
| result facts | `:seon.test/pass-count`, `fail-count`, `error-count`, `run` ref, `failures` components (`seon.test.failure`: type, expected, actual, message, contexts, line, signature, throwable) | `resources/seon/schemas/seon.test.edn:22-34`, `seon.test.failure.edn` |
| drift and restore | `schema-restore-drift`, `restore-live-cluster-schema!` (derive from facts since `c79a157fd`) | `:1269`, `:1184` |
| launcher | `bin/test` (807 lines): snapshot HEAD plus named paths, publish a cached base, worker count = processors/2 capped at 3, per-exchange bound, tally, retained root on failure; slots via `bin/_test-slot` (2 per checkout) | `bin/test`, `bin/_test-slot` |
| in-process loop | `bin/test-fast` → `seon.test.fast` | `bin/test:581` |

What is decoupled (parent PRD S9): runs are keyed to the publication, not a
cluster; workers resolve tests from files; selection is per invocation with
no global view; the tally is computed in the shell; the in-process loader and
the worker derive their classpaths differently.

---

## 3. The target, in one paragraph

A **run request** names a cluster, a change basis (default: that cluster's
last recorded run), and optionally namespaces or test identities. **Selection**
answers it with the exact set of test identities and, for each, why it was
selected (reaches a changed function; named explicitly; platform tier). The
selection is recorded as the run entity's members before anything executes.
**Workers** are launched by `bin/test` with nothing but a run id and a worker
index; each worker claims tests from the run entity, resolves each by
identity from the cluster's facts, executes it under the declared bounds and
the cluster's custody, and records the result on the cluster through
`seon.test/run`'s writer. A second launcher against the same cluster, digest
and basis sees the in-flight run and selects only what it is not already
running. The **tally** printed at the end is `(seon.test.runner/render-run db
run-id)`. The development JVM and an agent's turn call the same selection,
resolution, execution and recording functions with their own custody.

---

## 4. Stages

Each stage is one lane, one landing note, one gate; the previous stage's
behaviour keeps working until the stage that deletes it. Every stage carries
the parent PRD's lane rules (§4b): read the seams end to end, no harness,
everything through existing owners, orchestrator review before the gate.

### Stage 0 — Design review (astra, `high` effort, no production edits)

Read end to end: `bin/test`, `bin/_test-slot`, `src/seon/test.clj`,
`src/seon/test/runner.clj`, `src/seon/test/fast.clj`, the schemas
`seon.test.edn`, `seon.test.run.edn`, `seon.test.failure.edn`,
`seon.test.runner.edn`, `seon.config.test.edn`, and the tests
`test/seon/test_runner_test.clj`, `test/seon/test/runner_test.clj`,
`test/seon/test_support_test.clj`, `test/my/test_test.clj`. Deliver, in
`docs/prds/steward-platform/research/test-system-stage0-design-2026-09-17.md`:

1. The **function inventory**: for each of selection / resolution / execution
   / recording / tally, which existing function is the owner, which duplicates
   exist (in the shell, in `seon.test.fast`, in the coordinator), and which
   are deleted at which stage.
2. The **fact model**: exact schema additions, all accretive, with docstrings:
   on `:seon.test.run` the cluster it ran against (the existing `branch`
   field, confirmed or renamed), its selected members with their selection
   reason, and per member the worker process record and claim instant;
   nothing that a query can derive.
3. The **classpath derivation** shared by the worker and the in-process
   loader (from the `:test` alias in `deps.edn`), closing
   `the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency`.
4. **Refusals**: the exact typed refusals for a request naming no cluster, a
   cluster with no recorded run (first run = the full eligible set, named as
   such), a test identity that resolves to nothing, and an in-flight claim
   race.
5. **What `bin/test` becomes**: its remaining responsibilities line by line
   (snapshot, base publication, slot, worker launch, exit code from the run
   entity's verdict) and the lines that go.
6. The three concrete options the owner asked every design to bring where a
   choice remains (e.g. worker claim granularity: per test vs per namespace),
   simplest first, marked recommendation.

Acceptance of stage 0 is the orchestrator's review of that note and the
owner's answer to its options. No code.

### Stage 1 — One selection function, one run entity with members

**Measured design inputs (selection-efficiency research, 2026-09-17):**
Datahike has no VAET index; every ref attribute is implicitly indexed, so
AVET is the reverse index, and `seon.fn/gate-set`'s iterative frontier walk
over it is the correct reverse-reach mechanism — 14.181 ms for the worst seed
(1,009 tests), 1.562 ms for a leaf. The recursive Datalog rule it replaced
measured 6,753 ms on the same seed and `datahike.query/solve-rule` does not
memoise (`query.cljc:1321`): no rule in `select`. The change half is answered
by `(db/since (db/history db) basis)` over `:seon.fn/source`, `:seon.fn/spec`
and `:seon.fn/calls`: 24–25 ms flat at 1 to 200 transactions back. Therefore
`select` = changed identities via `since` + ONE shared-`seen` frontier walk
seeded by all of them (not per-symbol `gate-set` calls), plus platform and
named members, ≈ under 50 ms. The recorded `:seon.test/reach` closure is not
the authority (281 of 1,829 tests; read through `pull`, which caps
cardinality-many at 1000 — see the issue filed). The run entity carries the
tested basis and branch; a cluster ref is added (stage-0 fact model).

**Change.** `seon.test/select` (name to be confirmed against existing
vocabulary in stage 0): `(select db request) → {:seon.test.run/members
[{:seon.test/sym … :seon.test.run/reason …} …] :seon.test.run/basis-t …}`
where `request` names the cluster (a database value handed in, §2.1) and
optionally namespaces or identities; reasons are an enum
(`:platform`, `:reaches-changed`, `:named`, `:first-run`). It replaces the
selection inside `check`, `changed-since-green`'s caller, `seon.test.fast`
and the coordinator. The run entity is written with its members **before**
execution, on the named cluster. `bin/test` calls it through one prepl
evaluation and reads the members back; it no longer computes a set itself.

**Regression.** `seon.test.runner-test/selection-is-one-function-on-both-hosts`:
the in-process `check` and a worker's run request over the same database
value and change produce the same members and reasons; a first run on a
fresh cluster selects the full eligible set with reason `:first-run`; a
request naming no cluster is refused by name.

**Deleted.** The coordinator's own selection and `seon.test.fast`'s copy.

### Stage 2 — One resolution by identity, from facts

**Change.** `seon.test/resolve-test` resolves a test identity to a runnable
from the cluster's facts: an indexed test (has `:seon.fn/file`) to its Var
on the worker's classpath; an agent-authored test (no file, provenance
`:agent`) by evaluating its stored source in the cluster's base SCI context
(acquired as agents acquire it) and taking the resulting Var. The worker uses
only this. The classpath both hosts load from derives from the `:test` alias
(stage 0 item 3).

**Regression.** An agent-authored deftest with no file, written on a fixture
cluster, is selected, resolved and run by a worker path (in process, the
worker's own function called with worker custody) and its result lands on
that cluster; the loader gap test loads `seon.test-runner-test` in process.

**Deleted.** File-path resolution in the worker; the reload-by-namespace
special cases that exist only because tests came from files.

### Stage 3 — Workers claim from the run; global scheduling is a fact

**Change.** `bin/test` launches N workers with `(run-id, worker-index)`. Each
worker claims members from the run entity through one transaction function
(`[:db.fn/call claim-member …]`: the writer decides, no pre-read), recording
the worker's process record and instant on the member; runs the test through
`seon.test/run` with the cluster's custody; records the result. A second
launcher against the same cluster, digest and basis reads the in-flight run's
members and selects only the complement, recording what it skipped and why.
The slot bound stays as the process bound.

**Regression.** Two concurrent run requests over the same cluster, digest
and basis: no member is executed twice; the second run's members are exactly
the complement; a worker that dies mid-claim leaves a member with a dead
process record, which the next claim reclaims (the process-record liveness
census already exists).

**Deleted.** The coordinator's static partition of namespaces across workers.

### Stage 4 — The tally is a query; the launcher is a launcher

**Change.** `seon.test.runner/render-run` renders a run entity's verdict,
counts, failing tests with their failure components' shown text, and the
retained-root note; `bin/test` prints exactly that and exits with the run
entity's verdict. The per-exchange bound, liveness watchdog and thread dumps
stay, as they are, the launcher's own bounded-execution duty; each writes a
typed fact on the member it bounded.

**Regression.** The printed tally of a gate equals `(render-run db run-id)`
byte for byte; a bounded member carries the bound fact and the run's verdict
is red with the member named.

**Deleted.** Every counting and formatting line in `bin/test` that the render
replaces; `seon.test.fast`'s own reporting if any remains.

### Stage 5 — Documentation and the standing regressions

AGENTS.md §5 rewritten to describe the one gate in these terms; the
`clojure-testing` skill updated; the gate ledger's batch format becomes the
run id. Standing regressions kept forever: selection-on-both-hosts (stage 1),
agent-test-runs-in-a-worker (stage 2), no-double-execution (stage 3),
tally-equals-render (stage 4), and the existing delta-recording regression
(an unchanged re-record writes zero datoms).

---

## 5. Cost and order

| stage | lane | estimate |
|---|---|---|
| 0 design review | astra `high` | ½ day |
| 1 selection | astra | 1 day |
| 2 resolution | astra (depends on parent S1 landing for agent tests) | 1 day |
| 3 claims and scheduling | astra | 1–1½ days |
| 4 tally and launcher | Opus | ½ day |
| 5 docs | Opus | ½ day |

Stage 1 can start as soon as stage 0's note is reviewed and the owner has
answered its options. Stage 2 waits for the parent PRD's S1 so an
agent-authored test is a real program fact with edges.

---

## 6. Questions this document does not answer (for the owner, after stage 0)

1. Worker claim granularity (per test, per namespace, or per file) — stage 0
   brings the three options with measured fixture-load costs.
2. Whether a run on a non-`default` cluster should also record a pointer on
   `:current-src` so a global "last green" is answerable, or whether the
   global answer is a query across clusters (recommendation: the query).
3. Whether the platform tier remains a declared set (`:seon.test/platform`)
   or becomes "tests reaching boot and cluster owners" by reach
   (recommendation: keep the declaration; it is a fact with a docstring).
