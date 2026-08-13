---
type: issue
status: open
severity: blocker
tags: [issue, schema, performance, runtime, class/p1, wave/seon-env-p3]
---

> **2026-08-08 — the per-node shape is dead at the admission seam.**
> `seon.sci.admit` now resolves ONE declaration projection per admission and
> threads it to `schema/identity-only-projection-in`; the class regression is
> `test/seon/sci/admit/declaration_population_test.clj`. Measured below.
> Two remainders keep this issue OPEN, both belonging to owners this lane does
> not hold: the identity-only descriptor cache still lives in a process atom
> (`seon.env` Phase 3's declared work) and `seon.cluster/mcp-project` still
> resolves the population four times per call.

# Resolve the declaration population once per admission, not once per node

## Problem

`seon.sci.admit` asks the declaration population **once per value node**.
`identity-only-node` (`src/seon/sci/admit.clj:141-149`) calls
`schema/identity-only-projection` for every map, vector, set, sequence, and
record it walks. That function is
`(identity-only-projection-in (shape-projection) value)`
(`src/seon/schema.clj:2696-2700`), and `shape-projection` resolves the
declaration population through `candidate-forms`
(`src/seon/schema.clj:2610-2630`). With nothing supplied on the calling
thread that falls through to `packaged-forms`, which re-lists and re-merges
every schema resource on the classpath — the exact fallback the
2026-08-07 declaration-population family was closed against
([archived owner issue](archive/packaged-forms-rereads-every-schema-resource-per-call.md)).

The generation atom in front of it does not help: it compares the resolved
forms with `=` AFTER paying for the resolution, so it saves rebuilding the
projection and saves nothing at all on the read.

This is the dominant surviving member of the class, and it sits on the
admission seam that every agent turn result, every effect result, every
recorded error value, and every MCP eval passes through.

## Evidence

Measured live 2026-08-08 on the running `default` cluster JVM (read-only
probes through `mcp__seon__eval_clj`, session `audit`).

One resolution costs a full resource merge:

```clojure
{:declaration-population-ms 13.30
 :declaration-projection-ms 13.78
 :second-population-ms      14.28
 :population-count          1885}
```

`identity-only-projection` on a trivial map pays that in full, per call:

```clojure
{:identity-only-projection-ms 13.95
 :shape-projection-ms         14.92}
```

Admission cost is therefore linear in NODE COUNT, not value size. Counting
the fallback occurrences attributed to `admit.clj:143` around each call:

```clojure
{:scalar  {:ms   0.05, :fallbacks  0}
 :vec50   {:ms  13.72, :fallbacks  1}    ; (vec (range 50))
 :nested  {:ms 382.31, :fallbacks 22}}   ; {:rows [20 small maps]}
```

A twenty-one-map result costs **382 ms of re-reading schema resources**.

The process-wide counter confirms the volume. After roughly one hour of an
ordinary `default` cluster's life, `@#'seon.schema/!fallback-counts` read:

```clojure
{"seon.sci.admit (admit.clj:143)"    54884
 "malli.core (core.cljc:2196)"       23357
 "malli.core (core.cljc:209)"         2238
 "seon.db (db.clj:361)"               1159
 "malli.registry (registry.cljc:58)"  1010
 "seon.sci.admit (admit.clj:405)"      270
 "seon.error (error.clj:310)"          244
 "seon.sci.admit (admit.clj:481)"      136
 "seon.schema.datahike (datahike.clj:265)" 95
 "seon.schema.datahike (datahike.clj:418)" 94
 …20 callers total}
```

At the measured 13.3 ms that is ≈ 18 minutes of CPU spent re-reading 151 EDN
files, in one hour, in one idle-ish cluster — with `admit.clj:143` alone
responsible for two thirds of it.

Second site, same class, same seam: the development MCP's value projection
resolves the population four more times per call —
`seon.cluster/mcp-project`'s `admit/admit-value`, `render.value/artifact`,
`render.value/artifact-edn` and `mcp-valf`'s `admit/canonical-edn`
(`src/seon/cluster.clj:292,298,299,371`) — so **every** orchestrator or agent
MCP eval pays ~54 ms of pure resource merging before its value is rendered.

## Owner

`seon.sci.admit/project` and `identity-only-node`
(`src/seon/sci/admit.clj:141-149`), with `seon.schema/shape-projection` and
`identity-only-projection` (`src/seon/schema.clj:2610-2700`) and the MCP
projection sites in `seon.cluster`.

## Acceptance criteria

- One admission resolves the declaration population **exactly once**,
  whatever its node count — the population (or the projection carrying it)
  is threaded through `project`/`admit-value` the way `seon.db`'s decode
  walkers already thread `read-declarations` (`src/seon/db.clj:362-390`),
  and the per-item entry points that resolve it themselves become
  population-taking (`-in`) questions.
- The class regression counts reads at the one seam and asserts a constant
  count across item counts — the shape
  `test/seon/schema/declaration_population_test.clj` already uses — extended
  to admission, so `{:rows [20 maps]}` performs one resolution rather than 22.
- `seon.cluster/mcp-project` supplies the cluster's projection once for the
  whole projection pass.
- Re-running the counter probe above over a real turn shows
  `admit.clj:143` at single digits rather than tens of thousands.

## Repair landed 2026-08-08 — one projection per admission

`src/seon/sci/admit.clj`. `admit*` puts one `admission-declarations` **delay**
in the walk state and `identity-only-node` asks
`schema/identity-only-projection-in` with it. A delay for the same reason
`seon.db/read-declarations` is one: the commonest admission is a scalar, which
asks no identity question at all, and resolving eagerly would have made every
result pay a population for a question it never asks. The delay resolves to a
projection already supplied on the calling thread when there is one
(`schema/current-projection`), which is what makes the target state free
rather than merely bounded — the same object across admissions keeps the
compiled descriptors warm too.

No `seon.schema` change: `identity-only-projection-in` already existed, and
`schema.clj` was held uncommitted by a sibling lane at the time.

### Measured

Load-only, `clojure -M:dev`, reads counted at the one read seam
(`schema.edn/read-schema-resource`); one unbound population is 152 reads.
Wall times from `tmp/repro/admit_population_cost.clj`, read counts from the
class regression run against both shapes.

| Admitted value | Before | After |
|---|---|---|
| scalar | 0 reads / 0.15 ms | 0 reads / 0.08 ms |
| vector of 50 ints | 6,384 reads | **152 reads** |
| `{:rows [20 maps]}` | 3,344 reads / **374.68 ms** | **153 reads / 23.95 ms** |
| vector of 100 maps | 9,880 reads / **1,019.42 ms** | **153 reads / 22.05 ms** |
| set of 40 maps | 6,273 reads | **153 reads** |
| lazy sequence of 60 maps | 9,333 reads | **153 reads** |
| six-deep nested maps | 1,071 reads | **153 reads** |
| `{:rows [20 maps]}`, projection supplied | 0 reads | 0 reads |

Per-node cost of the identity question itself, isolated (200 iterations):
ambient with nothing supplied **15.263 ms**, threaded through one stable
projection **0.012 ms**.

The identity projections are unchanged — `:seon.db/database-value` and
`:seon.db/connection` produce byte-identical identity data through the
threaded arity as through the ambient one.

### The class regression

`test/seon/sci/admit/declaration_population_test.clj` counts reads at the one
read seam and asserts every admission performs exactly ONE resolution
whatever its node count, that an admission asking no identity question
resolves nothing, and that a supplied projection makes it free. Non-vacuous by
construction and by observation: reverting `identity-only-node` to the ambient
ask fails five of its six node-count cases with the read counts in the table
above.

### The same class in the admission test, and a 20x gate

`compiled-node-schema` (`test/seon/sci/admit_test.clj`) compiled
`:seon.print/node` once per GENERATED CASE, and `schema/current-projection`
is nil outside a delta, so every case fell through Malli's default registry
to a complete classpath population — the fallback counter reached ×1000 from
three lines of that file in one run. The schema does not vary by case;
compiling it per case measured nothing but the resource merge. Hoisted to one
delay over one resolved projection:

| `bin/test seon.sci.admit-test` | Before | After |
|---|---|---|
| namespace wall time | **259.1 s** | **13.2 s** |
| test-side fallbacks | ×1000 from three lines | one each, from two |

Both runs already carried the `admit.clj` repair, so the 20x is entirely the
test-side hoist. 7 tests / 30 assertions / 0 failures. The surviving volume is
`admit.clj:163` at ×100 — one per admission across hundreds of generated
admissions, which is correct per-operation behaviour and disappears when a
projection is supplied.

### Remainders (why this issue stays open)

1. **The identity-only descriptor cache is still a process atom.**
   `!identity-only-generation` (`src/seon/schema.clj:2647-2695`) keys on
   `identical?` projection, so an admission that had to build its own
   projection rebuilds the descriptors — ~1 ms, against the ~14 ms population
   it is already paying, and zero once a projection is supplied. Moving that
   cache onto the projection value is the PRD's own declared Phase 3 work
   ("the compiled-shape and identity-only caches move onto the projection
   value"; `!shape-generation` / `!identity-only-generation` are on its
   deletion list), so it belongs to that owner, not to a second fix here.
2. **`seon.cluster/mcp-project` still resolves four times per call**
   (`src/seon/cluster.clj:292,298,299,371`). One
   `schema/call-with-projection` around the whole projection pass closes it;
   `seon.cluster` was not this lane's owned path.
3. **`seon.sci.eval/evaluation-projection` resolves one per evaluation when
   the ctx carries no projection** (`src/seon/sci/eval.clj:648-651`) — it
   falls back to `(schema/build-projection (schema/registered-schemas))`,
   which is a complete population AND a complete projection build. A live
   cluster's ctx holds projection state so this is the test path, but it is
   the same class: `bin/test seon.sci.eval-test` logged it reaching 100
   occurrences (2026-08-08), alongside `seon.schema/valid-candidate-value?`
   asked per generated evaluation at `test/seon/sci/eval_test.clj:1331`.
   Neither is this lane's owned path.

Root repair (the one that makes the class unwritable rather than merely
cheaper) is the seon.env Phase 3 sweep: admission receives
`:seon.schema/projection` from the environment it is already handed, and
`shape-projection`'s process-global generation atom disappears with the rest
of the derived-state slots
([PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)).
The threading above is worth landing first because it is measurable today
and does not wait on Phase 3.

## Observer evidence, 2026-08-08 live drive — the class is not closed

The overnight report records the admission blocker as CLOSED. That is true of
the admission seam and understates the class. An independent read-only census
on cluster `default` (pid 79576) during the 08-08 live drive found the
population still being resolved thousands of times per JVM-hour, with the
dominant caller now OUTSIDE admission.

`seon.schema/!fallback-counts` at 04:36Z, six minutes after boot — 3,118
resolutions across 26 callers:

| Caller | Count |
|---|---:|
| `seon.print (print.cljc:232)` | 1,288 |
| `seon.sci.admit (admit.clj:431)` | 408 |
| `seon.db (db.clj:362)` | 352 |
| `seon.sci.admit (admit.clj:510)` | 136 |
| `seon.sci.admit (admit.clj:569)` | 124 |
| `seon.error (error.clj:310)` | 118 |
| `seon.schema.datahike (datahike.clj:71)` | 86 |
| `seon.cluster (cluster.clj:308)` | 78 |
| … 18 more callers | 528 |

By 04:37Z the total was 5,104. `seon.print/option-defaults`
(`src/seon/print.cljc:232`) is the largest single caller: its own comment says
the population is read "ONCE here" per emit, which is correct per emit and
wrong per render — a walk emits hundreds of times.

Two measurements that bound the cost, both on this cluster:

- **Not memoized.** 10 calls and 100 calls both average **10.6 ms per call**
  (`{:warm-ms-per-call-10 10.8665916 :warm-ms-per-call-100 10.588492}`), so
  the counter multiplies directly into wall time. 3,118 resolutions is about
  **33 seconds of CPU** in a six-minute-old JVM.
- **One HTTP route is nothing but this.** A single `/data` request measured
  5.441 s caused **556 resolutions**; 556 × 10.6 ms = 5,894 ms estimated,
  within 8% of observed. About 530 of the 556 came from
  `seon.schema.datahike` (lines 71, 72, 112, 113, 184, 203, 205, 220, 223) —
  the Malli-to-Datahike bridge, a caller this note does not currently name.
  Filed as the user-visible surface in
  [Return `/data` without a five-second stall](data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md).

The diagnostic is also 80% of the boot log's volume: `data/clusters/default/
logs/seon.log` is 87 lines, of which 70 are `DECLARATION POPULATION FALLBACK`
occurrence lines.

## Threading note from the as-of reader lane — 2026-08-08

Asked to thread `seon.print` (`print.cljc:232`) while sweeping the as-of
reader class. I did not, and the reason is worth recording so the owning wave
does not inherit a half-done seam.

- **The two facts in this note should not be merged.** `print.cljc:232` is the
  largest single caller BY COUNT across the JVM; `seon.schema.datahike` is
  what prices `/data` (about 530 of that request's 556 resolutions, against 4
  from print). Threading print alone would leave the 5.5 s stall untouched.
- **`print/default-options` is public** and read by `seon.effect:48`,
  `seon.render.value:68`, and `seon.render.transcript:509`, plus
  `effective-options` at three internal emit sites and `print.cljc:769`.
  Threading the population is a signature change across two namespaces this
  lane did not own, concurrent with the seon.env Phase 3 sweep that owns this
  issue.
- **The shape of the fix is already ruled** and is the same one this note
  names: derived state hangs off the value it derives from. `option-defaults`
  is a pure function of the declaration population, so the population — not a
  memo of the options — is the value to pass, and the same argument closes
  `seon.schema.datahike`. One change, both callers.

Independently confirmed on the live cluster: the resolver is per EMIT, not per
node, so a walk pays it hundreds of times rather than thousands. That makes
print a real cost and not the dominant one.

## Recurrence, 2026-08-08 (whole-system-arc observer lane)

Cluster `default` (pid 31475), 28 minutes after boot, under a four-agent
concurrent workload: **14,583 resolutions across 45 callers**.

The caller mix has shifted materially since the morning lane, and `seon.print`
is now the single dominant caller rather than a secondary one:

| Caller | Count | Share |
|---|---:|---:|
| `seon.print (print.cljc:232)` | 6,337 | 43% |
| `seon.schema.datahike (datahike.clj:71)` | 2,080 | 14% |
| `seon.schema.datahike (datahike.clj:72)` | 1,040 | 7% |
| `seon.schema.datahike (datahike.clj:112)` | 768 | 5% |
| `seon.schema.datahike (datahike.clj:220)` | 600 | 4% |

Per-call cost re-measured directly on this JVM rather than reused from the
earlier note, and still flat with repetition — so still not memoized:

```clojure
{:ms-per-call-10 12.90, :ms-per-call-50 11.51}
```

14,583 × ~11.5 ms ≈ **168 seconds of CPU in a 28-minute JVM**.

The note above argues print is "a real cost and not the dominant one". Under
concurrent agent turns that is no longer true by count: print is 43% of all
resolutions and more than three times the next caller. The `seon.schema.datahike`
callers remain the worse cost per surface — one `/data` request alone spends 927
resolutions, ~530 of them in the bridge — but a fix that addresses only the
bridge would now leave the largest single caller untouched.

The warning wall also persists by volume: 44 of the first 62 boot log lines
(71%) are fallback occurrence lines.

## Recurrence, 2026-08-11 — the render walk costs 23 seconds, and this is all of it

Design-session probe for the self-generating context work, on an isolated
scratch cluster (`bin/seon --root tmp/probe-root start probe`, pid 47301,
freshly published `current-src`: 2,825 functions, 1,097 tests, 207 namespaces
with requires).

One `seon.render.walk/neighborhood` of the root agent at distance 2, returning
132 render calls, measured three consecutive times in the same JVM:

| Run | Wall |
|---|---:|
| first | 23,814 ms |
| second | 23,189 ms |
| third | 23,462 ms |

Flat with repetition, so it is not cold-start and nothing memoizes it. The
fallback occurrence lines emitted during the walk name two callers at ×1000
each — `seon.db (db.clj:430)` and `seon.print (print.cljc:232)` — plus ×100 and
×10 sites, so roughly 2,110 resolutions.

Per-resolution cost measured directly on the same JVM (20 iterations):

```clojure
{:per-population-ms 11.057, :population-size 1951}
```

11.057 ms × 2,110 ≈ **23,330 ms predicted** against 23,189 / 23,462 ms
measured — within 1%. The walk's entire wall time is this class. `db.clj:430`
is a caller not previously listed in this note.

### Why this raises the severity

The neighbourhood walk is the one traversal behind agent context, the
namespace pages, and the `/` system view, and it is the substrate the
2026-08-10 REPL-history design builds on. The previously recorded warm-walk
figure of 122 ms was measured with retained calls reusing their outputs; a
walk that must actually render — a fresh agent, a changed basis, a new
cluster — pays the full 23 s. Under the owner's fast-by-default ruling that
is a defect to attack before any work lands on top of the walk.

### Two further defects observed in the same walk

- **59 of 132 render calls carry an `:seon.error/value`** (45%).
- **114 of 132 lookups are raw entity ids** with no identity attribute
  resolved, so an entity-level fallback form can only be spelled
  `[:db/id 2484]`. Both are recorded here as observations from this probe,
  not diagnosed; they need their own issues once someone reads them.

## 2026-08-11 implementation probe — the remaining choke point is the walk entry

The three owner-requested carrier options were probed against Datahike
`10540578248eaa686c1f88a7fe57644ee4c9f993` and the current Seon call graph.

- **Database-value metadata is insufficient.** A raw `DB` record retains
  Clojure metadata through ordinary reads and `d/with`, but `history`, `as-of`,
  and `since` construct fresh outer records, reopen reconstructs a fresh
  database value from storage, and `seon.print` is never handed the database
  value. Making this carrier complete would require refresh, temporal-origin,
  reopen, and print-carriage machinery.
- **The environment's projection is not basis-correct.** `seon.env` carries
  the projection object faithfully, but boot snapshots it while the SCI
  context's projection state advances after program/schema accretion. It
  cannot be used as the projection for an arbitrary current or historical
  database value without a broader ownership change.
- **Per-operation entry resolution is the simplest complete form.** A walk
  request already carries both the exact immutable database value and the SCI
  context. Resolve `schema/projection-from-database` once at that database
  basis (using the context projection only as its reusable candidate), then
  run the complete traversal under the existing
  `schema/call-with-projection`. Every nested `seon.db` read and every
  `seon.print` emit then consumes the one supplied projection, so both
  `db.clj:430` and `print.cljc:232` become zero-fallback paths together.

The structural choke point is `seon.render.walk/neighborhood`, not any
remaining leaf. `seon.db` already resolves once per individual read and
`seon.print` once per individual emit; neither leaf can know that hundreds of
those calls belong to one walk. This implementation lane did not edit
`src/seon/render/walk.clj` because the task explicitly protected it for the
concurrent walk owner. No weaker cache or second carrier was landed.

The recurring regression belongs beside the walk. It must shadow
`test-support/with-database`'s supplied projection state with
`(schema/call-with-projection-state (atom {}) ...)`; otherwise the current
fallbacks appear fixed and the test is vacuous. Seed at least one transcript
value so both database reads and print emission execute, then assert the walk
performs at most one declaration-population resolution, zero schema-resource
reads, and emits no `DECLARATION POPULATION FALLBACK` line.

## 2026-08-11 repair — one exact-basis projection per walk

`seon.render.walk/neighborhood` now derives one projection from the request's
exact immutable database value. The request context's projection is bound only
while those database rows are read and is passed only as the reusable
fingerprint candidate; the exact result is then bound across the complete
traversal. Nested `seon.db` reads, renderer selection, and `seon.print` emits
therefore consume one supplied projection without a cache or second carrier.

The non-vacuous regression is
`test/seon/render/walk_test.clj`'s
`one-basis-projection-covers-the-complete-walk`. It deliberately shadows
`test-support/with-database`'s projection state with `(atom {})`, seeds a real
form/result transcript, and proves the walk calls `projection-from-database`
exactly once, reads zero schema resources, and emits no declaration-population
fallback warning. Its first version caught five real classpath populations:
projection construction queried the database before any candidate projection
was bound. Binding the reusable candidate only around exact-basis construction
made the regression green rather than weakening its observation.

### Live before and after

The before condition remains the fresh distance-2 root-agent walk above:
23,189–23,814 ms, with `seon.db (db.clj:430)` and
`seon.print (print.cljc:232)` each reaching ×1000 fallback warnings.

After the repair, an independent isolated fresh cluster ran the exact same
request with `config/effective` → `config/result-caps`, `:record`, and a 5,000
ms eval time limit. The measured pass was 2,199.16 ms; two repeats were
1,744.62 and 1,847.85 ms. Compared with the recorded 23.2 s before value, the
first pass is 10.5× faster. The complete fallback-counter delta was `{}` and
captured warnings were empty, including zero `db.clj:430` and
`print.cljc:232` fallback lines.

The fresh live census returned the original 132 units: 73 successful renderer
outputs and 59 `:seon.error/value` structural markers. Every marker was
`:seon.render.walk/elided`, truthfully naming connections omitted at the
requested distance cap; there were zero renderer failures. The earlier “59 of
132 render calls” observation had counted these traversal markers as renderer
calls, so it did not reveal a failing render function or schema declaration.
The same census contained zero raw numeric lookups and therefore zero
identityless residue after the separately tested declared-identity repair.

## 2026-08-12 Phase 3 carrier completion

The broader carrier work now makes the admission repair structural rather than
dependent on caller-local dynamic bindings. `seon.sci.admit` accepts the
operation's explicit `:seon.schema/projection`; evaluation success, evaluation
failure, and MCP projection hand it the projection carried by the immutable
environment. Database decode operations reuse the projection handed at their
entry, deriving from the exact database value only when no environment exists.

The ambient declaration fallback is now a flat
`:seon.schema/missing-projection` refusal with zero schema-resource reads. The
final isolated publication/refork/start cycle produced zero fallback lines.
Together with the walk regression and measurements above, this closes the
per-node admission-resolution class.
