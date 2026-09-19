---
type: research
status: complete
created: 2026-09-19
tags: [instrumentation, contracts, errors, audit]
---

# Instrumentation coverage and error accuracy — the measured truth

The owner asked for every function to carry a Malli contract "so problems are
caught sooner with accurate errors — check and confirm if not". **It is not
the case.** 34.6% of first-party definition forms carry a contract; the
public surface is at 96.1% and the private surface — which owner ruling
[§1j](../plan/program-facts-are-the-runtime-prd-2026-09-17.md:352) brought
into scope on 2026-09-17 — is at **3.0%**. Nothing in the tree refuses,
reports or files an issue for a function that declares no contract, so the
gap is invisible to every gate: the project's own named failure class, a
check that reads absence of signal as health.

This note is static (source-only) measurement at working-tree HEAD on branch
`steward-platform`, 2026-09-19. No JVM was launched, no gate run, no cluster
touched. It complements — and does not replace — the database-derived counts
in [§1j](../plan/program-facts-are-the-runtime-prd-2026-09-17.md:352) and
[private-contracts-2026-09-17](private-contracts-2026-09-17.md), which were
measured on the pre-reset graph and include indexed test helpers.

## 0. Method, and what "contracted" means

The accepted declaration forms are Malli's own two, read from
`reference-code/malli/src/malli/instrument.clj:43-46` (`mi/-schema`):

1. a `:malli/schema` entry in the Var's metadata, or
2. `:malli/schema` metadata on **every** arglist of the definition.

`seon.instrument/collect-contracts!` (`src/seon/instrument.clj:848`) and
`seon.instrument/armable` (`src/seon/instrument.clj:68`) both ask exactly that
question over `ns-interns`, so this census asks the same one. Malli's only
exclusion is a primitive-arity fn
(`reference-code/malli/src/malli/instrument.clj:16`, mirrored as
`seon.instrument/primitive-fn?` at `src/seon/instrument.clj:52`).

The census tokenizer is paren/string/char/regex aware and walks only top-level
forms — no regex over file text. It is reproduced verbatim in
[§9](#9-the-census-script). Its counts agree exactly with independent `rg`
counts (`src` `^\(defn ` = 1215, `^\(defn- ` = 2331, `test` `^\(defn- ` = 906),
and its `src` total of 3546 `defn`/`defn-` forms matches the number the
assignment carried.

## 1. Coverage

### 1.1 Totals

| root | head | total | contracted | % |
|---|---|---:|---:|---:|
| src | `defn` | 1215 | 1168 | **96.1%** |
| src | `defn-` | 2331 | 69 | **3.0%** |
| src | `defmulti` | 2 | 0 | 0% |
| src | `defmethod` | 26 | 0 | 0% |
| src | **all** | **3574** | **1237** | **34.6%** |
| test | `defn` | 101 | 42 | 41.6% |
| test | `defn-` | 906 | 10 | 1.1% |
| test | **all** | **1007** | **52** | **5.2%** |

`src` also declares one `defprotocol` (`src/seon/print.cljc`) and `test` one;
protocol methods intern Vars with no body to carry a contract, and the indexer
already excludes them by query rather than by name through `:seon.fn/defined-by`
(`src/seon/fn.clj:668`). They are excluded here for the same reason.

**2337 `src` definition forms carry no contract.** 47 of them are public
(`seon.operator.state` alone holds 26 of the 47; `seon.test.arm/arm-contracts!`
at `src/seon/test/arm.clj:167` and `initialize-contracts!` at `:242` — the
arming owner itself — are two more). 2290 are private.

The 26 `defmethod` forms and 2 `defmulti` forms are a structural hole: a
multimethod Var can carry `:malli/schema`, but each method implementation
cannot, so per-method inputs are unreachable by this mechanism. Neither
multimethod Var declares one today.

### 1.2 Per-namespace, top 30 by uncontracted count

`db-consumers` counts uncontracted functions in that namespace whose body
calls `db/q`, `db/pull`, `db/pull-many`, `db/entity`, `db/datoms`, `db/as-of`,
`db/history`, `db/since` or `db/transact!` directly — the §1j "first mined
issue class".

| namespace | total | contracted | uncontracted | % | db-consumers |
|---|---:|---:|---:|---:|---:|
| `seon.test.runner` | 175 | 23 | 152 | 13% | 7 |
| `seon.db` | 192 | 55 | 137 | 29% | 3 |
| `seon.render.web` | 132 | 14 | 118 | 11% | 14 |
| `seon.turn` | 172 | 71 | 101 | 41% | 28 |
| `seon.render.transcript` | 119 | 25 | 94 | 21% | 22 |
| `seon.cluster` | 123 | 35 | 88 | 28% | 13 |
| `seon.fn` | 110 | 28 | 82 | 25% | 3 |
| `seon.operator.state` | 80 | 2 | 78 | 2% | 0 |
| `seon.schema` | 159 | 89 | 70 | 56% | 0 |
| `seon.sci.eval` | 96 | 29 | 67 | 30% | 10 |
| `seon.print` | 83 | 19 | 64 | 23% | 0 |
| `seon.render` | 87 | 29 | 58 | 33% | 4 |
| `seon.render.ns` | 62 | 12 | 50 | 19% | 4 |
| `seon.plan` | 77 | 28 | 49 | 36% | 15 |
| `seon.error` | 101 | 54 | 47 | 53% | 6 |
| `seon.sci.reader` | 46 | 2 | 44 | 4% | 0 |
| `seon.operator` | 56 | 18 | 38 | 32% | 1 |
| `seon.flow` | 48 | 12 | 36 | 25% | 0 |
| `seon.fs.jvm` | 41 | 5 | 36 | 12% | 0 |
| `seon.ai` | 63 | 31 | 32 | 49% | 1 |
| `seon.fn.analyzer` | 36 | 4 | 32 | 11% | 0 |
| `seon.program` | 45 | 15 | 30 | 33% | 0 |
| `seon.bootstrap` | 41 | 12 | 29 | 29% | 9 |
| `seon.edit` | 33 | 5 | 28 | 15% | 0 |
| `seon.schedule` | 36 | 9 | 27 | 25% | 4 |
| `seon.web.jvm` | 28 | 2 | 26 | 7% | 0 |
| `seon.render.walk` | 37 | 11 | 26 | 30% | 2 |
| `seon.instrument` | 36 | 11 | 25 | 31% | 1 |
| `seon.issue` | 48 | 24 | 24 | 50% | 7 |
| `seon.bootstrap-drive` | 24 | 1 | 23 | 4% | 5 |

86 of 110 `src` namespaces have at least one uncontracted function. **24 are
complete** — every `my.*` agent-facing namespace (`my.agent`, `my.edit`,
`my.fs`, `my.issue`, `my.message`, `my.note`, `my.plan`, `my.shell`, `my.test`,
`my.turn`, `my.web`) plus small owners (`seon.id`, `seon.eval`, `seon.run`,
`seon.shell`, `seon.background`, `seon.error.refusal`, `seon.cluster.process`,
`seon.cluster.instruction`, `seon.render.agent`, `seon.render.block`,
`seon.render.route`, `seon.test.bounds`, `seon.test.fast`). The agent-facing
protocol is fully contracted; the machinery under it is not.
`seon.cluster.agent` (93.1%), `seon.cluster.wake` (86.7%), `seon.agent`
(81.8%) and `seon.env` (80.0%) are the closest large ones.

### 1.3 The §1j class, measured statically

**246** uncontracted `src` functions call a `seon.db` read directly; **25**
call `db/transact!`. The database-derived §1j figure is 352 private
read-consumers without a contract on the pre-reset graph; that count includes
indexed test helpers and private Vars from `test/`, so the two numbers are
consistent, not contradictory. The static source-only count is the actionable
one for a lane, because it maps to files.

Top namespaces by uncontracted db-read consumers: `seon.turn` (28),
`seon.render.transcript` (22), `seon.plan` (15), `seon.render.web` (14),
`seon.cluster` (13), `seon.sci.eval` (10), `seon.bootstrap` (9),
`seon.cluster.message` (9), `seon.issue.detect` (9), `seon.problems` (8).

## 2. Contract quality among the 1237 contracted `src` functions

Classes reproduce `seon.fn/contract-findings` (`src/seon/fn.clj:1549`),
which delegates permissive-position classification to
`seon.schema.internal/permissive-positions` (`src/seon/schema/internal.cljc:22`)
and adds structural bare-`:map` / `[:maybe …]` findings of its own.

| class | occurrences | note |
|---|---:|---|
| bare `:map` | 369 | of which **97** are in output position |
| `[:maybe …]` | 123 | |
| `[:fn pred]` opaque predicate | 57 | mostly `clojure.core/var?` / `ifn?` |
| unguarded variadic tail (`:*`/`:+`/`:repeat`) | 10 | |
| `:any` | 170 | **all 170 carry an admission exemption** |
| `:some` | 3 | all 3 carry an exemption |
| bare `:fn` (no predicate) | 1 | |

**There is no unjustified `:any` or `:some` left in `src`.** 183
`:seon.schema.admission/exemption` markers cover 170 `:any` and 3 `:some`
tokens; no contract form has more `:any` than exemptions. This independently
reproduces the canonical-fixture census in
[contract-findings-query-2026-09-17](contract-findings-query-2026-09-17.md)
(`:any` 0, `:some` 0, bare-value 0), and its bare-map 304 / maybe 123 /
unguarded-variadic 8 differ from the numbers here only because that census
counted findings on the fixture's indexed population while this one counts
tokens in the working tree.

Highest permissive-token namespaces: `seon.schema` (126, of which 81 bare
`:map`), `seon.program` (40), `seon.db` (30), `seon.cluster.agent` (29),
`seon.instrument` (27), `seon.schema.datahike` (22), `seon.schema.internal`
(22), `seon.turn` (21).

### 2.1 Declared error facets — owner ruling §1q is not implemented

[§1q](../plan/program-facts-are-the-runtime-prd-2026-09-17.md:505) rules that
every function's output contract enumerates the error facets it can return.

- 341 of 1237 contracted `src` functions name any error schema key at all.
- **285 of those use the generic `:seon.error/value`**, which is the
  non-enumerating spelling §1q replaces.
- Roughly 56 use named facets. Six of those are the whole-population unions
  (`seon.sci.kernel/failure-value`, `src/seon/sci/kernel.clj:519`, lists all
  63 facets by hand — the one honest generic pass-through).
- **896 contracted functions declare no error in their output at all**, and
  **103 of them call a `seon.db` operation directly** (top: `seon.turn` 18,
  `seon.issue` 12, `seon.sci.eval` 7, `seon.cluster` 6, `seon.cluster.agent`
  6, `seon.effect` 5, `seon.test` 5).

Those 103 are the live version of the "error read as a row" class: when the
read refuses, either the declared output rejects the error value (an output
violation naming the consumer rather than the failed read), or — where the
output is a bare `:map`, which 97 outputs are — the error value validates as
an ordinary map and travels on as data.

## 3. What is actually armed, and what a miss does

### 3.1 Arming

Boot and development adoption call one owner, `seon.instrument/apply!`
(`src/seon/instrument.clj:859`); adoption's call site is
`src/seon/cluster.clj:2545` and operator boot's is
`script/seon/fresh_operator.clj:1930` (inside `instrument-form`, `:1916`). `apply!` requires a handed projection
and a `:panic`/`:record` dial, then:

- `collect-contracts!` (`:848`) walks `(mapcat (comp vals ns-interns) (all-ns))`
  — **every loaded namespace, public and private alike**, keeping Vars where
  `mi/-schema` answers and the value is bound and non-primitive. There is no
  namespace filter, prefix list or privacy predicate anywhere in the selection.
- `current-wrapper?` (`:796`) compares the Var's captured contract and its
  closed-over declaration forms with the supplied projection, so unchanged
  wrappers keep identity across re-arms.
- `arm-var!` (`:811`) installs a wrapper that recompiles per call against a
  projection supplied in the arguments (`supplied-projection`, `:567`) or falls
  back to the packaged bootstrap declarations.
- `remove!` (`:955`) cannot unarm: cluster teardown returns the count only.

In the test worker, `seon.test.arm/arm-contracts!` (`src/seon/test/arm.clj:167`)
derives the program namespaces from the `ns` form of every `.clj`/`.cljc` file
under `src` (`declared-program-namespaces`, `src/seon/test/arm.clj:100`),
`require`s each one so nothing is unloaded, applies the same owner, and then
checks **set coverage** of `instrument/armable` against `instrument/instrumented`
(`src/seon/test/arm.clj:212-229`), refusing by name when the worker armed a
smaller world than a cluster. `test/` is deliberately excluded from the
program root (`src/seon/test/arm.clj:48`).

### 3.2 The three arming gaps

1. **The parity check can only prove that everything *declared* is armed.**
   Both sides of the comparison — `armable` and `instrumented` — derive from
   declared schemas (`src/seon/instrument.clj:68`, `:39`). A function with no
   `:malli/schema` is in neither set. The gate is therefore green about a
   question it never asks, for 2337 `src` functions. This is the exact disease
   the docstring at `src/seon/test/arm.clj:215` describes one level down.
2. **Nothing arms `defmethod` bodies.** 26 methods and 2 multimethods in `src`
   carry no contract and cannot carry one per method.
3. **A primitive-hinted Var declares a contract that nothing can arm.**
   `primitive-fn?` (`src/seon/instrument.clj:52`) reproduces Malli's exclusion
   so `armable` and `apply!` cannot disagree, but the exclusion is silent: the
   contract exists, is enforced nowhere, and no check reports it. (No such Var
   exists in `src` today — the hole is structural, not populated.)

### 3.3 A contract miss, end to end

```
malli m/-instrument                        reference-code/malli/src/malli/core.cljc:2213
  → :report                                 src/seon/instrument.clj:727
      → boundary-refusal                    src/seon/instrument.clj:636
          → violation                       src/seon/instrument.clj:302  (::boundary? true)
              → error/explain-problem       src/seon/error.clj:912       (per problem)
              → error/problem-sentence      src/seon/error.clj:964       (the flat message)
              → error/diagnostic            src/seon/error.clj:338       (the flat value)
          → schema-shape/fingerprint + observation-location
                                            src/seon/instrument.clj:619
      → reject!                             src/seon/instrument.clj:719
          validates against :seon.instrument/refusal-result, then THROWS
          ex-info whose message is (:seon.error/message value) and whose
          ex-data is the refusal, tagged with a per-wrapper marker object
```

Then the dial decides, at `src/seon/instrument.clj:764-773`:

- **`:panic`** — the `ExceptionInfo` propagates to the caller unchanged.
- **`:record`** — the marker is recognised, `(:seon.flow/commit-fault! policy)`
  commits the fault, and the **flat refusal value is returned in place of the
  call's result**. A recording failure rethrows.

Separately, a contracted function that returns a `:seon.error/base` value whose
facets are not in its declared output union is rejected as
`:seon.instrument/undeclared-error` (`src/seon/instrument.clj:745-770`), with
the permitted set derived once per arity by `declared-result`
(`src/seon/instrument.clj:584`).

**What the agent sees.** Inside an SCI evaluation the throw is caught at the
guarded boundary and normalised by `seon.sci.kernel/failure-value`
(`src/seon/sci/kernel.clj:519`), which preserves the refusal's own
`:seon.error/kind` and adds boundary evidence. The evaluation stores the shown
text produced by the error schema's AI render pair,
`seon.error/instrumentation-prose` (`src/seon/error.clj:1192`, declared at
`resources/seon/schemas/seon.instrument.edn` on
`:seon.instrument/contract-violated-error`). That function prefers
`refusal-text` (`src/seon/error.clj:1134`), which composes one
`problem-sentence` per problem plus a docstring example; when
`:seon.error/problems` is absent it falls back to the
`"Contract violation in OP MEMBER: expected …, received …"` sentence at
`src/seon/error.clj:1211-1218`.

**What the human sees.** In the gate log, `report-error!`
(`src/seon/test/runner.clj:308`) prints `ERROR in <test>` plus
`failure-message`, and the throwable is rendered by `printable`
(`src/seon/test/runner.clj:119-122`) as `class-name ": " ex-message` —
**the ex-data refusal is discarded**. `throwable-text`
(`src/seon/test/runner.clj:148`) adds stack frames bounded by the declared
`:seon.print/length`, still no ex-data. So the log carries the one-line
`problem-sentence` and nothing of the per-problem paths, the declared
arglists, the caller frame, or the expected-shape fingerprint that
`boundary-refusal` took the trouble to compute.

## 4. Error-accuracy defects, with evidence

**D1 — the caller is computed and then never said.**
`caller-frame` (`src/seon/instrument.clj:126`) exists precisely because "a
refusal must name the member AND the frame that supplied it" (its own
docstring, `:129-132`). The result is stored at
`[:seon.error/data :seon.instrument/caller]` and in
`:seon.error/diagnostic-evidence` (`src/seon/instrument.clj:424-434`), but
`problem-sentence` (`src/seon/error.clj:964`) takes only operation, problem and
two rendered values — **the caller never enters either the flat message or the
rendered prose**. The reader is told which function refused, not who called it.
It is also an unstructured string (`"ns (file:line)"`), so it cannot be queried.

**D2 — a reporter failure relabels the defect.**
`violation`'s `(catch Throwable failure …)` at `src/seon/instrument.clj:434`
converts any failure while composing the refusal into `minimal-violation`
(`:236`), whose message for a non-arity kind is the contentless
`"<fn> violated its contract (<kind>)."` and for an arity kind is
`"Wrong number of args (N) passed to: <fn>"`. The real cause survives only at
`[:seon.error/data :seon.instrument.lookup/cause]`. This already
mis-attributed a whole diagnosis: a 2000 ms evaluation deadline latching around
a 10 ms refusal was reported as a wrong-arity contract violation
([sci-arity-message-parity-2026-09-17](sci-arity-message-parity-2026-09-17.md),
batch 119). The interrupt case was repaired by rethrowing at
`src/seon/instrument.clj:447`; **every other throwable still relabels.**

**D3 — the arity refusal prints arglists from a pre-read the authority
re-decides.** `diagnostic-arglists` (`src/seon/instrument.clj:214`) prefers the
**program graph's** `:seon.fn/arglists` (`:170`) and consults the loaded Var's
own `:arglists` only when the graph misses (`:229-233`). The wrapper it is
reporting for is the loaded Var. Whenever adoption lags the JVM — the ordinary
state during a coalesced edit window (`.claude/seon-hook.edn:27`) — the refusal
tells the caller to satisfy arglists the loaded function no longer has. This is
the owner law's pre-read shape: derive at the authority (the Var) or hand it
the decision.

**D4 — an invalid refusal loses the whole diagnostic.**
`reject!` (`src/seon/instrument.clj:719-722`) validates the constructed refusal
against `:seon.instrument/refusal-result` and, on failure, throws
`ex-info "Instrumentation constructed an invalid refusal."` with the refusal as
data. Nothing downstream recognises that message: `failure-value`
(`src/seon/sci/kernel.clj:519`) sees no `:seon.error/kind`, so it classifies it
as a generic `::failure-kind`. A schema drift in the refusal family therefore
converts every contract miss in the process into an unclassified failure.

**D5 — 103 contracted db-consumers can neither pass nor refuse an error
honestly.** Measured in §2.1. Where the output is one of the 97 bare `:map`s, a
refused read validates as an ordinary map and is read as a row — the class
already filed as
[a database read's error value is read as a row by its caller](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md)
and cited by §1j as the cause of all three overnight instances. Where the output
is narrower, the consumer is named as the violator of a contract it did not
break. §1q's enumerated facet union is the fix and is unimplemented.

**D6 — the gate log drops the evidence.** `printable`
(`src/seon/test/runner.clj:119-122`) renders a Throwable as class plus message
only. Everything `boundary-refusal` computed —
`:seon.instrument/explanations` with per-problem schema and value locations
(`src/seon/instrument.clj:658-679`), `:seon.error/expected-shape`,
`:seon.instrument/declared-arities` — is discarded before a human reads it.

**D7 — `:record` mode is not wired for host wrappers.**
[error-wrapper-enforcement-2026-09-18](error-wrapper-enforcement-2026-09-18.md)
records that neither `wrap-interpreted` (`src/seon/instrument.clj:501`) nor
`compiled-wrapper` (`src/seon/instrument.clj:690`) is handed a recording
operation by any acquisition path; `seon.flow`'s `commit-fault!` is a proc
argument (`src/seon/flow.clj:999`), and `apply!` only forwards one if its caller
supplied it (`src/seon/instrument.clj:938-941`). Under `:record`, a wrapper
without a recorder rethrows, so production behaves as `:panic` for anything not
explicitly armed with a recorder.

**D8 — the notes' own line references have drifted.**
[private-contracts-2026-09-17](private-contracts-2026-09-17.md) cites
`collect-contracts!` at `src/seon/instrument.clj:687`, `apply!` at `:698`,
`armable` at `:65` and a `throwing-report` at `:450`. At HEAD those are `:848`,
`:859`, `:68`, and `throwing-report` no longer exists — the panic path is
`reject!` at `:719`. §1k of the PRD still points at
`src/seon/instrument.clj:451` for "the panic reporter". A derived reference or a
dated stamp is the rule here (AGENTS §2.2, derive or die); these are bare
mirrors and they went stale within two days.

## 5. Proposal (a) — make "every function contracted" enforceable at the write

The seam is the **analysis manifest**, not the hook and not the instrumenter.

`src/seon/fn.clj:671-677` is where a contract becomes a fact: `:seon.fn/spec`
is assoc'd **only when** `:malli/schema` metadata is present, and absence
writes nothing and says nothing. Two lines above it, `:seon.fn/defined-by`
(`src/seon/fn.clj:668`) already records which form interned the Var, with the
docstring stating that this is how `deftype` constructors and `defprotocol`
methods — Vars with no body to carry a contract — are excluded **by query
instead of by guessing from their symbols**. The predicate for "this row must
carry a contract" therefore already exists in the data.

The enforcement precedent also already exists in the same file:
`assert-capability-contracts!` (`src/seon/fn.clj:1969`) runs inside
`manifest-data` (`src/seon/fn.clj:2090-2095`) — the one builder every
publication passes through, incremental hook publication and
`bin/seon init` alike — and `capability-refused!` (`src/seon/fn.clj:1942`)
already **refuses a publication for exactly this defect**: a capability handler
with no `:seon.fn/spec` is `:unschemaed-handler` (`src/seon/fn.clj:2002-2005`).

Recommended shape, cheapest viable first:

1. **Now (advisory, no behaviour change).** In `manifest-data`, emit one
   `:seon.fn.file/findings` entry per analysed row that has no `:seon.fn/spec`
   and whose `:seon.fn/defined-by` is a contract-bearing form. Findings already
   flow to the hook's editor feedback, so every edit that adds an uncontracted
   function says so at the moment it is written, and the count is derivable
   from one Datalog clause rather than from this note. Cost: **2 lane-hours**.
2. **Then (ratchet, still no campaign).** Refuse the publication when a
   **newly asserted** function identity has no spec — a redefinition of an
   already-uncontracted function stays admitted. The prior definitions are
   already in hand at the write (that is what `exact-replacement-tx` compares),
   so "new" is derivable, not remembered. This is the mechanism that does not
   decay: coverage can only go up, and the remaining 2337 become a shrinking
   backlog rather than a standing campaign. Cost: **4 lane-hours**.
3. **Finally (complete).** Drop the newness exemption when the backlog is
   empty. No code change beyond deleting the exemption clause.

Two things the seam must not become: a namespace allowlist (a hand-maintained
mirror), and a check in the instrumenter (which by construction cannot see a
function that declares nothing). `seon.fn/contract-findings`
(`src/seon/fn.clj:1549`) already produces `:seon.fn.contract.finding/missing-spec`
per row; the missing half is that nothing at the write consumes it.

The §1q facet half rides the same seam: `contract-findings` gains the
"undeclared facet" / "declared but unreachable facet" kinds the ruling names
(the enum resource `resources/seon/schemas/seon.fn.contract.finding.edn` already
declares `:seon.fn.contract.finding/undeclared-error-facet` and
`:seon.fn.contract.finding/error-facet-analysis-unavailable`, and **no source
file produces either keyword** — the only matches in `src/` are the
same-named but differently-namespaced `:seon.instrument/error-facet-analysis-unavailable`
at `src/seon/instrument.clj:596` and `:610`), derived from `:seon.fn/calls` against the callees' declared
outputs. Cost: **8 lane-hours**, and it is the one that makes §1q enforceable
rather than aspirational.

## 6. Proposal (b) — an accurate contract miss

Target: one refusal entity naming function, arity, argument, path, expected
shape, offending value, the declaring schema key, and the caller.

1. **Say the caller.** Add the caller to `problem-sentence`
   (`src/seon/error.clj:964`) as an optional trailing clause, and store it
   structurally — namespace symbol, file, line — instead of the composed string
   at `src/seon/instrument.clj:139-141`. One composer, one added clause; the
   three call sites listed in
   [sci-arity-message-parity-2026-09-17](sci-arity-message-parity-2026-09-17.md)
   stay the only ones. **3 lane-hours.**
2. **Name the declaring schema key.** `violation` already computes `expected`
   as `(m/form offended)` and appends `" Contract: <key>."` only when that form
   is a qualified keyword (`src/seon/instrument.clj:417-419`) — which it is for
   almost no `[:=> [:cat …] …]`. The per-problem `:seon.error/schema-path`
   (`src/seon/instrument.clj:395`) plus `contract-definitions`
   (`src/seon/instrument.clj:774`) already hold the referenced declaration keys;
   resolve the failing problem's path to the innermost named key and carry it as
   its own attribute. **4 lane-hours.**
3. **Stop relabelling (D2).** Invert `violation`'s catch: report the composition
   failure as its own `:seon.instrument/reporter-failed` kind carrying the
   original `kind`/`fn-name`, instead of laundering it into
   `minimal-violation`'s contract-violation sentence. The interrupt rethrow at
   `src/seon/instrument.clj:447` becomes one case of a general rule. **3
   lane-hours.**
4. **Derive arglists at the authority (D3).** Read the loaded Var's `:arglists`
   first and use the program graph only as supplementary evidence, labelled as
   such in `:seon.error/diagnostic-evidence`. **2 lane-hours.**
5. **Carry ex-data into the gate log (D6).** `printable`
   (`src/seon/test/runner.clj:119`) renders a Throwable's `ex-data` through the
   error render pair under the declared print bounds when the data is a
   recognised error value (`seon.error/error?`, `src/seon/error.clj:1785`).
   **3 lane-hours.**
6. **One entity, per the error-entities PRD.** Items 1–4 are attributes of the
   single refusal the PRD §§4.1/5.2 already specifies; landing them as separate
   flat keys would be the second mechanism. They should land **after** the
   recorder acquisition dependency in
   [error-wrapper-enforcement-2026-09-18](error-wrapper-enforcement-2026-09-18.md)
   is resolved (D7), because that is what decides whether the refusal is
   returned or thrown. **Blocked, not estimated.**

## 7. Proposal (c) — the campaign, ordered and parallelised

Ordering is §1j's: db read-consumers first, then the rest of the private
surface, then the 97 bare-`:map` outputs. Sizing assumes a lane writes and
locally falsifies contracts for ~12–15 functions per hour in a namespace it has
read, including the reds each new contract exposes.

Every wave below is **file-disjoint**, so its lanes run in parallel. A
namespace is one file; the conflict risk is not between lanes but between a
lane and the shared schema resources, so **one resource-touching lane at a
time** and a lane that needs a new declaration files it in that declaration's
own canonical resource (the placement hook enforces this — see
[contract-findings-query-2026-09-17](contract-findings-query-2026-09-17.md)).

| wave | lanes (one namespace each) | uncontracted | of which db-consumers | lane-hours each | behaviour change |
|---|---|---:|---:|---:|---|
| W1 db-consumers | `seon.turn` | 101 | 28 | 8 | **high** |
| | `seon.render.transcript` | 94 | 22 | 7 | medium |
| | `seon.plan` | 49 | 15 | 4 | medium |
| | `seon.cluster` | 88 | 13 | 7 | **high** |
| | `seon.sci.eval` | 67 | 10 | 5 | **high** |
| W2 db-consumers, small | `seon.bootstrap`, `seon.cluster.message`, `seon.issue.detect`, `seon.problems`, `seon.issue`, `seon.note`, `my.program`, `seon.bootstrap-drive` | 146 | 59 | 2–3 each | medium |
| W3 render/web | `seon.render.web`, `seon.render.ns`, `seon.render`, `seon.render.walk` | 252 | 24 | 4–9 each | low |
| W4 platform leaves | `seon.operator.state`, `seon.sci.reader`, `seon.print`, `seon.fs.jvm`, `seon.web.jvm`, `seon.shell.jvm`, `seon.edit` | 297 | 0 | 2–6 each | **low — start here for a cheap proof** |
| W5 the checkers | `seon.fn`, `seon.fn.analyzer`, `seon.schema`, `seon.schema.admission`, `seon.instrument`, `seon.error` | 279 | 10 | 4–12 each | **high** |
| W6 the gate | `seon.test.runner`, `seon.test`, `seon.test.arm` | 175 | 10 | 12 total | **high** |
| W7 bare-`:map` outputs | cross-cutting, 97 outputs | — | — | 10 | **highest** |

Total for the 30 namespaces above: roughly **140 lane-hours**; the full 2337
is roughly **190**.

Sequencing notes, each of which is a real constraint rather than a preference:

- **W4 first for the cheapest honest proof.** `seon.operator.state` (78
  uncontracted, 2 contracted, no db reads) and `seon.sci.reader` (44 of 46
  uncontracted) are pure leaves. They will produce the fewest findings per hour
  and the most coverage per hour — useful to validate the enforcement seam of
  §5 before the seam starts refusing publications.
- **W1 and W5 will break things, and that is the deliverable.** Contracting a
  db-consumer forces the §1q question — what does this return when the read
  refuses — and the undeclared-facet check at `src/seon/instrument.clj:745`
  turns a previously-silent error pass-through into a refusal. Each red is a
  finding filed as an issue, per §1j.
- **W6 last among the risky waves.** Contracting `seon.test.runner` changes the
  behaviour of the thing that reports every other wave's reds; a wrong contract
  there is diagnosed by nothing.
- **W7 depends on §1q landing.** Replacing a bare `:map` output with a real
  shape plus an enumerated error union is the same edit; doing it before the
  facet finding exists means doing it twice.
- **`seon.db` (137 uncontracted) is deliberately absent from every wave.** It
  carries another lane's uncommitted edits at the time of writing
  (`git status`: `MM src/seon/db.clj`) and is the held file named in
  [one-error-predicate-2026-09-18](one-error-predicate-2026-09-18.md) §"Work
  remaining". It gets its own lane once that work lands, and it is the single
  highest-leverage namespace after it does.

## 8. What this note does not claim

No JVM was started, no gate was run, no cluster was observed. Every number here
is a static count over the working tree, so it counts source forms rather than
indexed program rows; the two populations differ by test helpers, macro-generated
Vars and anything the analyser records that has no top-level `defn`. Nothing here
proves that any armed contract behaves as declared, and nothing here supersedes
the database-derived counts in §1j. The `:record` dial's behaviour is read from
source and from
[error-wrapper-enforcement-2026-09-18](error-wrapper-enforcement-2026-09-18.md);
it was not exercised.

## 9. The census script

Kept at `<scratchpad>/contract_coverage.py` for this session; reproduced here
so the numbers are re-derivable. It emits one JSON row per definition form
(`head`, `name`, `ns`, `path`, `line`, `contract`, the contract form, the whole
form text); the tables above are aggregations over that output.

```python
DEFS = {"defn", "defn-", "defmulti", "defmethod", "defprotocol", "definline"}

# tokenize(src) -> [(kind, text, start, end)] with kind in {open, close, str,
# atom, meta}. It handles ;-comments, "strings", \char literals, #"regex",
# #_ discard, #( #{ #[ dispatch, and the quote/unquote/deref/meta prefixes,
# so no regex ever runs over file text.

# For each DEPTH-0 form whose head symbol is in DEFS:
#   - skip ^meta / ^:kw prefixes to find the definition name
#   - skip an optional docstring
#   - if the next form is a map, the definition is CONTRACTED when that map
#     contains :malli/schema        (malli -schema, form 1)
#   - otherwise it is CONTRACTED when the form contains ^{:malli/schema
#                                    (malli -schema, form 2: every arglist)
```

Cross-checks that fix the tokenizer to reality: `src` `^\(defn ` = 1215,
`^\(defn- ` = 2331, `test` `^\(defn- ` = 906, `src` `:malli/schema`
occurrences = 1262 against 1237 contracted forms (the 25 extra are the literal
mentions inside `seon.instrument`, `seon.fn` and `seon.test.arm`).
