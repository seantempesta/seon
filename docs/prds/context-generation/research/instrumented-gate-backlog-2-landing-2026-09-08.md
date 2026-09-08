---
type: research
status: complete
date: 2026-09-08
tags: [research, test, instrumentation, contract, schema, projection]
---

# Landing note: the instrumented gate's second backlog wave

Written by the `instrumented-gate-backlog-2` lane against
[AGENTS.md](../../../../AGENTS.md) §2.1, §2.4 and §5;
[the first backlog landing](instrumented-gate-backlog-landing-2026-09-08.md);
[the P1-P6 landing](production-defects-p1-p6-landing-2026-09-08.md);
[the projection-build issue](../../../seon/issues/an-incremental-projection-build-refuses-the-key-it-just-added.md);
[the armed-contract scheduling issue](../../../seon/issues/an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md);
and the `clojure-testing` and `data-oriented-clojure` skills — each read end to
end.

Every gate in this note ran in a throwaway worktree at the lane's own commits,
because a concurrent lane held a mid-edit `seon.cluster.wake/wake-attributes`
arity in the shared tree that refuses static analysis and therefore refuses
`bin/test` outright.

## 1. The four classes, and what each one turned out to be

### 1.1 The projection/registry class — one pre-read, and it was not one worth keeping

`seon.schema/malli-form?` compiled every candidate definition against
`(candidate-registry)`, the AMBIENT declaration population, while the
authority — `projection-with-schema`, which holds the projection being
extended — was about to re-decide the same question. The two worlds disagree
by construction: the second key of an incremental build references the first,
which the projection in hand resolves and the ambient population does not.
Worse, with no projection bound at all `declaration-population` THROWS, the
predicate catches, and every form is false.

Neither filed option was taken. The owner law dissolves the question instead:
**the predicate answers whether the form PARSES; whether a reference RESOLVES
is decided where the projection is.** `malli-form?` now compiles against one
structural registry — Malli's own default schemas, plus an opaque placeholder
for every keyword or qualified-symbol reference — and reads no declaration
population on any path. That closes the second, older issue against the same
line
([malli-form-predicate-resolves-the-declaration-population-itself](../../../seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md)),
whose acceptance criterion was "no resource read when a projection is
supplied": there is now no read when one is NOT supplied either.

Unqualified keys are real references — `resources/seon/schemas/malli.edn`
declares `:inst` — so the placeholder admits any keyword. A string, a number,
and an unqualified symbol Malli does not know remain refusals, which is what
keeps `seon.fn`'s source-indexing guard meaningful.

One production defect fell out, because the incremental build now REACHES
code it used to be refused before: `predicate-functions-with` seeded its
reduce with `(:seon.schema.projection/predicate-functions projection)`, absent
on a projection carrying no bound predicates, and returned nil into
`compilable-form`, whose declared input is a map.

Measured on `bin/test seon.schema-test seon.schema-usage-guard-test
seon.schema.datahike-test seon.schema.edn-test seon.fn-test`: **38 failing →
6**, and all six are `seon.fn-test` reds present at the baseline commit.

### 1.2 The armed-contract scheduling class — declared nothing, derived instead

The issue proposed giving `seon.instrument-test` a worker group of its own.
Both candidate directions start from "which tests own this state" — a
declaration that has to be maintained over a set the tree keeps growing (six
suites call the whole-image `apply!`/`remove!` today, and `seon.db-test` is
both a mutator and a victim). The worker's armed state is a PRECONDITION of
admitting a task, so the worker derives it per task:
`seon.test.runner/reassert-contracts!` counts the wrappers installed before
every `:run` and re-arms when that is LESS than it armed at initialization.
More is left alone — a test arming a filter of its own is expected to undo it.
Nothing is declared, so nothing can drift.

Two production defects fell out of building it, both of the class this project
keeps meeting:

1. **`seon.instrument/instrumented` read an ALIAS as its subject.** Malli
   stamps `::mi/original` on the wrapper fn and names no var
   (`reference-code/malli/src/malli/instrument.clj:8,38`), and the function
   scanned every var in every loaded namespace. `(def real-evaluate
   sci.eval/evaluate)` in `seon.cluster.agent-test`, captured while contracts
   were armed, answered `instrumented?` true forever — and nothing could ever
   unstrument it, because malli unstruments what it REGISTERED. `remove!`
   reported a survivor it had no way to remove; `apply!` in `:record` mode
   reported instrumenting one var while instrumenting none. The candidates are
   now the vars malli holds a function schema for.
2. **The gate armed itself from an uncompiled decision document.**
   `arm-contracts!` handed `config/default-decisions`, whose optional dials
   carry `:seon.config/absent` sentinels, to `config/result-caps`, which
   declares `:seon.config/effective`. It answered correctly, so the
   disagreement was invisible for exactly as long as arming preceded the
   contracts that observe it — and surfaced the first time a worker had to
   re-arm mid-run.

**Honest result:** the issue's symptom does not reproduce at HEAD. In
`bin/test seon.db-test seon.instrument-test seon.test-runner-test`, both tests
the issue names — `seon.db-test/malformed-reads-return-flat-errors` and
`the-gate-runs-under-the-contracts-a-cluster-runs-under` — are GREEN in the
pool, and every remaining red in that selection is confirmed `reproducible`
rather than `parallel-only`. What landed is the constraint and its two
by-products, not a cure for a red.

### 1.3 The rest, by class

| red | class | disposition |
|---|---|---|
| `seon.repl-parity-test` B10, B11, H3 | fixture | the harness read `:seon.cluster.eval/result-edn` unconditionally and handed nil to a total render; an evaluation whose value binds nothing stores no node and reports `:seon.eval/missing` |
| `seon.repl-parity-test` G10 | stale expectation | asked the reader's EVENT VECTOR for `:seon.error/kind`; a refusal rides the event that could not be read, so the check could only pass by accident |
| `seon.repl-parity-test` H5 | stale expectation | the divergence is gone at HEAD; the row is promoted to `:passing` |
| `seon.db-test/temporal-reads-…` | fixture | `(:t database)` is not a key on a Datahike database value, so it compared two views of nothing; reads `db/basis-t` |
| `seon.db-test/instrumented-wildcard-pull-…` | fixture | a present nil in the optional admission caps |
| `seon.db-test/malformed-public-database-requests-…` | test at the wrong boundary | `pull`'s `:selector` and `transact!`'s `:tx-data` are refused by the contract one frame before the body; both cases assert that refusal, naming the same public operation and the offending path |
| `seon.render.web-test` `/data` ×2, `seon.render.value-options-test` | production + fixture | see §1.4 |
| `seon.instrument-test/remove-is-total`, `production-instruments-nothing-…`, `the-selection-is-declared-…` | production | the alias defect above |
| `seon.instrument-test/registry-sized-contract-evidence-…` | production, FILED | [contract evidence carries the offending argument twice](../../../seon/issues/contract-evidence-carries-the-offending-argument-twice.md) |
| `seon.db-test/diff-replays-one-read-…` | production, FILED | [an inbox entry carries a nil for the content history deliberately discards](../../../seon/issues/an-inbox-entry-carries-a-nil-for-content-history-deliberately-discards.md) |

The first landing note's "~35 live boot and operator reds" are NOT in the
`--all` tally: `seon.cluster.boot-test`, `seon.cluster.armed-test` and almost
all of `seon.dev.fresh-operator-test` are declared long tests and `--all`
skips 54 of them. Only
`seon.dev.fresh-operator-test/reset-discards-only-enumerated-unreadable-claims-after-the-flock`
is red under `--all`. Those suites need `--full`, and they are named here so
the next wave does not go looking for them in the wrong run.

### 1.4 Production defects found

| # | defect | where |
|---|---|---|
| P1 | `seon.schema/predicate-functions-with` returned an ABSENCE as a map | `src/seon/schema.clj` |
| P2 | `seon.instrument/instrumented` counted plain aliases of instrumented fns, so `remove!` reported an unremovable survivor and `:record` reported instrumenting one var while instrumenting none | `src/seon/instrument.clj` |
| P3 | the gate armed from the raw decision document, not the compiled effective config | `src/seon/test/runner.clj` |
| P4 | a pooled worker's armed state survived only as long as no task stripped it | `src/seon/test/runner.clj` |
| P5 | `seon.render/producer-argument`, `schema-producers` and `cost-shape-key` handed an ABSENT database to `render.value/transacted`'s two-argument arity, whose declared input is a database value — while the one-argument arity exists for exactly that caller | `src/seon/render.clj` |
| P6 | `seon.render.web/data-response` built its render unit without the database value it derived every part of the unit from, so `/data` answered 500 with a contract refusal as its page body | `src/seon/render/web.clj` |
| P7 (filed) | contract evidence carries the bounded offending argument twice, 768 of the 1,015 tokens that bust its own bound | `src/seon/instrument.clj` + `seon.error` fact family |
| P8 (filed) | `my.message/listing-entry` writes a nil into a required key on any historical read of the `:seon.db/no-history?` content, so `my.message/inbox` violates its own output contract on every replay | `src/my/message.clj` |

## 2. Commits

| commit | subject |
|---|---|
| `d2793c0ba` | Ask the projection in hand, not an ambient registry, whether a form parses |
| `5703918b8` | Derive a worker's armed state instead of trusting the last task |
| `b79b98b3f` | Read the parity faces the evaluation actually stored |
| `c801f6b9d` | Ask the database questions in the vocabulary the contracts declare |
| `0f399ea73` | Let a render carry the database it was derived from |
| `9cd1f9ab4` | Decide a worker's arming before it arms, and carry that decision |

## 3. The gates

| gate | first backlog wave | this wave's census (`3b5102c6b` + §1.1) | this wave's end (`9cd1f9ab4`) |
|---|---|---|---|
| `bin/test --platform` | GREEN 73/398/0 | — | **GREEN 73/398/0** |
| `bin/test --all`, tests run | 1,439 → — | 1,452 | **1,461** |
| `bin/test --all`, assertions | 12,082 | 12,210 | **12,183** |
| `bin/test --all`, distinct failing | **190** | **153** | **158** |
| ‣ of those, confirmed `reproducible` | — | 145 | **138** |
| ‣ of those, confirmed `parallel-only` | — | 3 | **20** |
| `bin/test --all`, uncaught contract violations | 108 | 97 | **104** |

**READ THE LAST COLUMN CAREFULLY: it is not a like-for-like comparison.**
Between the census commit and the wave's end, three commits from two other
lanes landed — `c04765e10` (declare the listened attributes and derive waking
from them), `f2a956dc6`, and `f46f9d461` — touching
`src/seon/cluster/{wake,work,loop,agent}.clj`, `src/seon/error.clj`,
`src/seon/bootstrap.clj`, `src/seon/schedule.clj` and six schema resources.
Eighteen tests went green, twenty-three went red, and most of the newly red
are in exactly those owners' paths.

`bin/test --platform` was GREEN after every slice: after §1.1
(`tmp/igb2/platform1.log`), after §1.2 (`platform2.log`), after §1.4
(`platform3.log`), and after the re-arm repair (`platform4.log`).

### What this wave cured, named

```text
seon.db-test/instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary
seon.db-test/malformed-public-database-requests-name-the-public-operation
seon.db-test/temporal-reads-use-explicit-and-ambient-database-values
seon.render.web-test/data-resolves-an-entity-root-and-preserves-it-in-floor-links
seon.render.web-test/data-selects-a-stored-value-artifact-by-digest
seon.repl-parity-test/parity-b10, -b11, -g10, -h3, -h5
```

plus, inside the schema selection, **38 failing → 6**; and, inside
`bin/test seon.db-test seon.instrument-test seon.test-runner-test`,
`seon.instrument-test/remove-is-total`,
`production-instruments-nothing-and-undoes-what-is-there` and
`the-selection-is-declared-vars-with-schemas-and-nothing-else`.

## 4. What remains red, and whose it is

Ownership of the 153 census reds, by the assignment's protected paths
(`test/seon/cluster/*`, `error_test`, `schedule_test`, `effect_test`,
`bootstrap_test` — held by the `listened-attributes` lane):

| owner | distinct failing tests |
|---|---|
| PROTECTED (listed, not fixed) | **62** — `seon.cluster.turn-test` 24, `seon.cluster.agent-test` 16, `seon.cluster.loop-test` 6, `seon.cluster.prompt-test` 6, `seon.cluster.reply-test` 3, one each in `evaluate-sources`, `message`, `resume-artifact-routing`, `run`, `work`, `effect`, `schedule` |
| this lane's to fix | **91**, of which 18 are cured above |

The 97 census contract violations concentrate as follows — the top four are
all in protected owners:

| function | count | owner |
|---|---|---|
| `seon.cluster.run/receipt-identity` | 13 | PROTECTED |
| `seon.error/prepare` | 10 | PROTECTED |
| `seon.cluster.run/settlement-projection` | 7 | PROTECTED |
| `seon.sci.eval/evaluate-candidate` | 6 | open |
| `seon.render.value/transacted` | 5 | **cured** (§1.4 P5/P6) |
| `seon.fn/index!` | 5 | open |
| `seon.cluster.message/delivery`, `seon.cluster.agent/arm!` | 4 each | PROTECTED |

Named and NOT fixed, with the reason:

- **the 62 protected reds** — another lane holds those files; a red there is
  listed, never touched;
- **`seon.sci.eval-test` (7 in isolation, 20 under `--all`)** — the seven are
  present when the suite runs ALONE at this commit and are the same seven the
  census recorded; the extra thirteen appear only in the `--all` pool and are
  confirmed `parallel-only`. See the caveat below;
- **`seon.render.web-test` (8 remaining)** — heterogeneous: stale status-string
  expectations on the debug page, and one `await-event!` backstop firing. Each
  needs its own live probe;
- **`seon.render.value-test` (5)** — untouched; one suite, one class, not
  sampled;
- **`seon.fn-test` (6)** — present at the baseline commit of the first wave;
- **`seon.instrument-test` (2)** — both filed:
  [the evidence duplication](../../../seon/issues/contract-evidence-carries-the-offending-argument-twice.md)
  and the registration-failure diagnostic being masked by a caps refusal one
  frame earlier;
- **`seon.db-test/diff-replays-one-read-by-derived-identity`** —
  [filed](../../../seon/issues/an-inbox-entry-carries-a-nil-for-content-history-deliberately-discards.md);
- **`seon.render.value-options-test`** — the `/data` route now answers 200
  (that half is fixed), but the database-backed `:seon.render.value/max-collection`
  dial does not size the window: the page shows `1–584 of 584`. The dial is
  read through `config/effective db (current-cluster-name db)` and the fixture
  applies it to a named cluster; unsettled;
- **the live-boot and operator suites** — `seon.cluster.boot-test`,
  `seon.cluster.armed-test` and nearly all of `seon.dev.fresh-operator-test`
  are declared LONG tests. `--all` skips 54 of them, so the first wave's
  "~35 live boot reds" are not in this tally at all and need `--full`.

### The one caveat this wave leaves open

`parallel-only` verdicts rose from 3 to 20 across the two `--all` runs, and
thirteen of the twenty are `seon.sci.eval-test`. That suite runs its own
seven isolated reds and no more when it runs alone at this commit, and a
three-namespace pool that ACTUALLY re-arms twice
(`RE-ARMING CONTRACTS worker= pool-1 installed= 0 armed-at-initialization=
897` — a task had stripped every wrapper) reports **zero** `parallel-only`.
So the re-arm is not visibly the cause, and the two `--all` runs differ by
three other lanes' commits as well. An attribution is a hypothesis until a
probe confirms it, so this is FILED rather than named:
[thirteen sci-eval reds are parallel-only under the whole gate](../../../seon/issues/thirteen-sci-eval-reds-appear-only-under-the-whole-gate.md).
