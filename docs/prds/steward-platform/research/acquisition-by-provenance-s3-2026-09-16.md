---
type: research
status: in progress, isolated acquisition implementation
created: 2026-09-16
tags: [sci, acquisition, provenance, program-graph]
---

# S3: acquisition by provenance

The implementation derives the cluster program context with
`seon.sci.eval/base-ctx` from one supplied database value. Current per-identity
admission chooses copied JVM roots or interpreted agent source. The existing
settlement installer is retained as the measured optimization; agent acquisition
regenerates the fork and reapplies its private objects in memory.

Read AGENTS.md sections 0–7 in full, and both named authorities end to end:
[the program-facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
and [the REPL refactoring study](repl-native-retraction-and-refactoring-2026-09-16.md).
The later owner correction governs: one `base-ctx` takes a database value;
regeneration is the reference semantics, and any retained diff must prove
equivalence to it. The measurements below distinguish full database acquisition, accepted-row
installation, and the much smaller fork/private-layer regeneration.

## What this seam changes

`seon.program/overrides` reads current `:agent` function identities whose
namespace has recorded declaration/file relations to a current `src` file.
It returns an ordered vector, including `[]` for no overrides, or the read's
typed refusal. It never stores an override flag.

The namespace has **no file attribute**. The actual path is
`:seon.fn/ns` and `:seon.fn/file` on indexed declarations, then
`:seon.fn.file/relative-root` on the file. Replacing a definition removes its
file coordinates. Therefore the namespace/declaration links are read through
history derived from the **same supplied database**, while current admission
and the file root are read from that database. This keeps the last overridden
member from erasing the evidence that its namespace was indexed. Namespace
identity joins by name, including when a namespace was retracted and recreated.
No path strings are parsed, and no namespace naming convention is used.

The regression in `seon.sci.eval-test` uses `with-database`,
`program-fn-row`, and `transacted!`. It verifies disappearance of every current
file coordinate in the target namespace, exclusion of an agent-admitted test
namespace, restoration to core provenance, and unchanged answers from older
database values. This is a **query regression**, not an install-gate test.

## Dependency ledger and source findings

Sources read at the working tree based on `e481946f6`; line numbers precede
this seam's additions.

| Seam | Source | What it establishes |
|---|---|---|
| SCI copied root | `reference-code/sci/src/sci/core.cljc:112–140` | `copy-var*` dereferences the JVM Var and constructs an SCI Var with that root. It does **not** forward later JVM root replacements. |
| SCI fork | `reference-code/sci/src/sci/core.cljc:345–351` | Copies the environment atom and assigns a new generation. |
| SCI intern | `reference-code/sci/src/sci/core.cljc:260–276` | Intern/bind-root acts in the supplied context. |
| SCI inherited-root mutation | `reference-code/sci/src/sci/impl/utils.cljc:359–380` | An inherited Var is copied before its root changes; an owned Var changes in place. |
| Minimal context | `src/seon/sci/eval.clj:184–250` | No database argument; injected bindings come from `program/base-context-injected-symbols` without explicit forms. |
| Injected-symbol source | `src/seon/program.cljc:20–41` before this edit | Zero-argument path reads the current declaration population; explicit-forms arity already exists. |
| Function installation | `src/seon/sci/eval.clj:683–710, 781–853` | Interprets stored source and installs its contract; the already-evaluated arm marks and wraps transferred roots. |
| Accepted root transfer | `src/seon/sci/eval.clj:912–935, 2657–2673` | `accept-candidate!` transfers into the executing retained context, without a namespace-kind exclusion. |
| Committed base install | `src/seon/turn.clj:4886–4904` | Successful settlement transfers committed evaluated roots to the cluster base. |
| Existing fork | `src/seon/sci/eval.clj:1784–1840`; `src/seon/cluster/agent.clj:655–674` | Compares base roots/metadata and interns changed entries into the retained agent context. It does **not** iterate paths absent from the new base. |
| Reacquisition | `src/seon/sci/eval.clj:1471–1746` | Function selection already uses an admission calculation, not a first-party namespace exclusion. That calculation reads the provenance of the **asserting transaction**, rather than the current row directly. |
| Admission inference | `src/seon/schema.clj:788–819` | Collects provenance of rows sharing the source's asserting transaction; ambiguous provenance is classified as agent-authored. This is not per-identity loading. |
| Adoption | `src/seon/cluster.clj:2233–2253` | Calls `acquire!` into the existing base. It is not construction of a fresh base from one value. |
| Cold cluster | `src/seon/sci/eval.clj:1859–1896`; `src/seon/cluster.clj:3325–3329` | Builds/acquires or forks an acquired program context, with connection/projection custody attached separately. |

Consequently the older PRD sentence “the base never loads it” is not a correct
description of today's accepted-root transfer. Source supports A → base → B,
and a fresh C forks that base. It does **not** establish that a reconstructed
base is equivalent, nor that a retraction reaches existing forks. Those remain
live/regression obligations; no source inference here is labelled a live proof.

## Live measurements on default, PID 41413

MCP JVM mode, explicit root `/Users/sean/src/seon`, cluster `default`.
No restart, refork, provider request, or durable program override was performed.

| Probe | Result |
|---|---|
| `runtime_status` | Answered; PID 41413 alive. Existing problem counts included 95 failed tests and 7 stale Vars; this was not a healthy-baseline assertion. |
| Bare `build-base-ctx` | Refused with `:seon.schema/missing-projection`, “Schema declaration resolution requires the projection handed to the operation.” |
| Minimal construction under default's explicit projection | **12.36925 ms**. This does not include full acquisition. |
| Sourced function count | **4,288**, not the brief's 5,114. |
| Current agent function identities | `my.agents.juniper.s1/value`, `my.agents.juniper.s1/caller`, `my.agents.root/largest`. |
| Full `cluster-ctx` under that projection | MCP returned `:timeout` at **60,000 ms**. No completed acquisition duration was recovered; the combined probe did not isolate where the bound was spent. |
| Existing indexed file | `seon.id/symbol-in` → `src/seon/id.clj`, root `src`. `seon.id` itself has no file ref. |
| Proposed query evaluated directly | `[]`. |
| Owned query Var, loaded from its exact source form | `[]` at basis **536871889**. Surface: hot-reloaded JVM Var, **not** in-place development adoption or an agent turn. |

The source-form loader read `src/seon/program.cljc` with Clojure's reader,
selected the `defn overrides` form, and evaluated only that owned form under
`seon.program`. It did not reload other definitions or another lane's edits.
The edit-hook publication separately reported “Publication did not finish
within its declared bound.” No adoption freshness is claimed.

### New before-proof on regenerated default, PID 33583

The owner reset default and reseeded it while this lane was stopped. MCP
confirmed PID 33583 alive. The supplied agent id `2393cac275ae` returned nil,
so this lane created `s3-provenance-a`, `s3-provenance-b`, and later
`s3-provenance-c` through `seon.cluster.agent/creation-tx`, explicitly naming
cluster `default`. No provider calls, default restart, reset, or refork.

The regenerated database had **4336 sourced functions**, basis 536871057.
Minimal construction took **28.662375 ms**; full `cluster-ctx` before the
override took **21244.610416 ms**. These measure different operations.

Each submitted turn contained exactly one disposable SCI evaluation:

| Step | Durable turn / surface | Observed result |
|---|---|---|
| A baseline | `9bc870ed4c76` | `(seon.id/valid? 8 "s3-probe")` returned false. |
| B established retained context | `d1a1a1e95f77` | The same baseline returned false. |
| First candidate | `d1d37229ada1` | Redefining `seon.id/valid?` passed auto-check 25/25 but the gate refused: 4 selected tests failed resolving `id/evaluation`, `id/id`, `test-support/with-database`, and `testing`. Current source and provenance stayed core. |
| Accepted smaller first-party candidate | `b5c5d8b0e2fa` | A redefined private `seon.eval.drive/uuid-text` with a `[:=> [:cat] :string]` contract and constant result `"s3-accepted-database-definition"`. The ordinary gate accepted; the closed turn stores `#'seon.eval.drive/uuid-text`, and current admission is agent. Its graph-derived gate set was empty; no gate was bypassed. |
| Shared base | MCP SCI mode | `(seon.eval.drive/uuid-text)` returned the accepted constant, with outcome ok and 1 interpreted function entrance. |
| Existing B next turn | `cfb25e215171` | Returned the accepted constant. |
| Fresh C first turn | `a0351f1ab7be` | Returned the accepted constant. |
| Database reconstruction | MCP JVM, fresh connectionless `cluster-ctx` | Exceeded 60000 ms. Result unknown: this is neither proof of dropping the override nor a completed timing. |
| Provenance counterexample | MCP JVM, database read | `seon.id/valid?` current admission remained core, but `admission-from-asserting-transaction` for its source tx **536870917** returned agent, explicitly reporting ambiguous provenance. |

The source prediction A → base → retained B and fresh C is thus confirmed
for a real accepted first-party definition. Reconstruction exposes a separate
provenance defect rather than proving the brief's proposed namespace-based
drop. The full population affected was not measured.

Cleanup restored the original `uuid-text` row with `exact-replacement-tx`.
It again stores `(defn- uuid-text [] (str (UUID/randomUUID)))` with core
provenance. This lane explicitly copied the original JVM root back into the
shared base and called existing `acquire-context!` for its three agents. This
was probe cleanup, **not a claim that today's database reversion automatically
restores the JVM root**. The JVM definition itself never changed.

## After-proof on default PID 33583

The exact reproducible [regeneration probe](acquisition-by-provenance-s3-regeneration-probe-2026-09-16.clj)
reads the owned constructor forms with Clojure's reader and compiles them as
lexical functions. It does **not** replace a JVM Var or default's base context.
Each invocation performs one guarded SCI evaluation in the disposable base.
The immutable `as-of` value requested transaction **536871073**, when the
accepted override was current. `db/basis-t` reports the origin's later basis
536871209; that is not the requested as-of boundary.

| Measurement | Result |
|---|---|
| Earlier constructor candidate | 5489.542167 ms; accepted constant, identical `seon.id/valid?` JVM/SCI root |
| Integrated constructor, first repetition | 14697.701708 ms; 4336 sourced functions; accepted constant; private regeneration 22.877292 ms |
| Integrated constructor, second repetition | **3712.482625 ms**; same functions and accepted constant |
| Reinstall accepted source on the disposable base | **457.153333 ms**, interpreted state; includes projection derivation |
| Regenerate an agent fork, second repetition | **9.174917 ms**, preserved-in-memory state, identical result-store atom |
| Typed loads | `my.agents.root/largest` and `seon.eval.drive/uuid-text`: interpreted |

These are individual observations on a shared development JVM, not a
statistical latency distribution. A rebuild costs seconds; transferring the
accepted rows avoids rebuilding thousands of unaffected functions. The existing
installer therefore remains the optimization. The canonical regression compares
its result to `base-ctx` on the same database: identical resolvable binding paths,
identical roots for every core function under `src`, and identical admitted
source for the interpreted definition. No second update channel was added.

The projection fix was also verified on this **same historical value**. Before
loading the owned `projection-from-rows` function, projection construction
refused `:seon.schema/value`'s `:any` as agent-authored. After loading that exact
function and rearming (1109 registered/instrumented), construction completed in
6452.67325 ms and classified `seon.id/valid?` core and `uuid-text` agent. This was
a targeted JVM Var reload, not proof of development adoption.

## Regeneration and the private layer

`base-ctx` takes a database value, derives its projection and admitted rows,
and builds a program-only context. It receives no connection and writes no
fault facts. Typed load results stay on the returned context; the cluster
acquisition caller records failures through the existing error owner.

`build-base-ctx` now requires an explicit projection. Its injected-symbol
selection receives that projection's forms, removing the fetch-at-call-time
path from construction. `cluster-ctx` and adoption's existing `acquire!` both
call `base-ctx`; no separate loader exists at either boundary.

The old `receive-base!` loop is removed. `fork-for-turn` forks the current base
and carries owned SCI interns whose roots differ from the last/current program
roots. Contract wrappers are compared through their existing interpreted-original
metadata. Namespace-local alias/import changes are reapplied as well. The
agent's existing environment atom is replaced in place, because the turn graph
retains that handle. Successful settlement advances the committing agent's base
snapshot: accepted definitions must not become private merely because a later
adoption creates new interpreted callable objects.

The typed `:seon.sci.eval/private-state` answers `:preserved-in-memory` when
there was a prior agent context, or `:absent` for a fresh context/restart.
Preserved: actual private Vars and their roots, atoms, live result objects, result-store and print
session atoms, and values captured by private closures. Reapplying an actual
closure does not rewrite objects/Vars it already captured. Dropped: inherited
program bindings, including removed names. A JVM restart drops all private
objects; saved shown text is never used to reconstruct them.

`:seon.sci.eval/load-state` distinguishes `:jvm`, `:interpreted`,
`:jvm-fallback`, and `:unavailable`. A fallback names the function and SCI
failure and copies the loaded JVM root. The override query remains an admission
query even when that override cannot load; fallback is a separate typed
observation, never a stored override flag. `doc` derives one override sentence
from the same query and states the JVM write-back boundary.

## Ownership and integration boundary

`eval.clj`, `cluster.clj`, and documentation tests were initially held; work
continued in `tmp/acquisition-s3-wt`. On recheck they were clean and released.
The evaluator change was merged with the newly committed missing-config and
absent-declaration diagnostics, retaining both. The earlier two partial pending
patches are superseded and removed.

`agent.clj` keeps its existing `fork-for-turn` entry. `cluster.clj` keeps
`acquire!` but runs it after JVM instrumentation: `copy-var*` must copy armed
roots, not roots that will be replaced immediately by instrumentation. `turn.clj` passes
the committing agent context to the existing installer so it can advance its
base snapshot after successful settlement.

`src/my/program.clj` and its call-preparation arm remain the program-operations
lane's ownership. This slice supplies `seon.program/overrides` for documentation
and acquisition evidence; that lane's surface can delegate to it. Its current
query uses current file coordinates and cannot identify the last member after
replacement removes those coordinates. No foreign file or session was edited,
resumed, or messaged.

AGENTS.md and then `test/seon/cluster/source_test.clj` were released on
recheck. Both edits are applied, including the caller's explicit `seon.schema`
require. No pending code patch remains. The ownership rule was observed while
those files were held; only the released versions were edited.

## Verification

Three-slot `bin/test-fast --paths` snapshots use only this lane's files and
run the four requested namespaces. No standalone `bin/test`, platform gate,
full suite, default restart, or default refork was launched.

- Isolated pass 2 completed: 160 tests, 1006 assertions, 22 failures, 5 errors.
  Its new fallback and override/projection regressions passed. It exposed
  incomplete private-state preservation and an invalid pulled-row install
  request in the new equivalence test; both were corrected.
- Isolated pass 3: equivalence/revert/private objects, fallback, and override
  query regressions all passed with armed contracts. Stopped this lane's JVM
  after those results because the integrated handle/settlement changes
  superseded that snapshot; no whole-suite green is claimed.
- Integrated run: pending. Includes the real first-party acceptance/next-turn
  regression and the in-place context/settlement changes.
- Kondo: zero errors on the owned Clojure edits; existing warnings remain.

Known out-of-slice observations are recorded, not inferred as successes:
[allocation bound](../../../seon/issues/guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md),
[schema-row expectation](../../../seon/issues/schema-declaration-regression-disagrees-with-current-row-shape.md),
and [selected live test bindings](../../../seon/issues/agent-install-gate-cannot-resolve-selected-test-bindings.md).
Latest isolated allocation was 221832408 bytes against 67108864.

Development publication was requested for the owned source and schema paths.
The first attempt refused the then-held caller at
`test/seon/cluster/source_test.clj:507` (operation `init-init-4158.log`).
After release and repair, the next attempt refused a source change during
analysis (`init-init-15545.log`). The isolated caller patch had also omitted
its `schema` require; fixture construction reported that error, it was fixed,
and that superseded run was stopped. The final main-checkout snapshot includes
all released caller and adoption-order edits. Publication was retried with the
complete owned source paths. Default PID 33583 was alive
at the last runtime check. The [before-probe forms](acquisition-by-provenance-s3-probes-2026-09-16.clj)
and the [acquisition issue](../../../seon/issues/sci-acquisition-must-derive-from-current-identity-provenance.md)
retain the original counterexample.
