---
type: landing
status: implementation verified; authorized carried hunks landing
created: 2026-09-22
---

# Track 1.3d commit 1 — branch execution correctness

The owner authorized the adaptive graph seam (option 2), then authorized carrying
exactly the schema-retirement-refusal lane's already-proven commit `f6b175e6d`.
Before committing, the complete patches for `src/seon/db.clj` and
`resources/seon/schemas/seon.program.edn` were byte-equal to `git show f6b175e6d`.
For `src/seon/fn.clj`, bytes outside the owned graph region matched that commit,
and the graph region matched the verified isolated candidate. No extra hunks were
present. Evidence: `tmp/realities-carry-verification.txt`.

Carried from `f6b175e6d` (schema-retirement-refusal lane):
* `src/seon/fn.clj`: literal write facts survive schema retirement.
* `src/seon/db.clj`: `removed-definition-error` names surviving writers.
* `resources/seon/schemas/seon.program.edn`: declared deletion-refusal evidence.

The originating lane's 7 in-process assertions are its evidence; this lane did not
rerun or claim them as new evidence. Its remaining tests/note are not included here.
Root-seed-digest's `program.cljc`, `bootstrap.clj`, and `cluster/agent.clj` are untouched.
Hook publication is paused; nothing here is live on default.

## Implementation

Owned paths: `src/seon/sci/eval.clj`, the graph region of `src/seon/fn.clj`,
`resources/seon/schemas/seon.fn.edn`, `resources/seon/schemas/seon.sci.eval.edn`,
`test/seon/sci/branch_execution_test.clj`, and this note.

* `evaluate` calls `db/call-with-custody`; its evaluation-specific immutable read
  basis remains scoped inside that owner.
* Acquisition compares stored `program/definition-digest` values with the immutable
  loaded source database. A missing digest uses the same `program/definition-digest`.
  The source authority is the cluster's `:seon.source/commit-id`, materialized with
  Datahike `commit-as-db`; source-branch construction already holds that database.
  Forks retain it in the existing program snapshot. No registry or cache was added.
* `fn/reverse-closure` exposes the existing walk's affected functions and reaching
  tests. Both `gate-sets` arities retain their original result shapes. The existing
  conservative reference, subject and declared invocation edges remain; there is no
  second traversal or graph cache. Acquisition reports `:seon.sci.eval/interpreted-count`;
  `acquired-program` exposes that member with its existing refusal report.
* Matching rows copy compiled roots regardless of admission provenance. Changed
  rows and affected callers interpret stored source. SCI Vars are owned by the
  new generation before any caller is analyzed, so caller-before-callee installation
  cannot capture an inherited root.
* `:seon.sci.eval/interpretation-error` is declared in the explicit `install-row!`
  result union. It names `:seon.sci.eval/refused-function` and
  `:seon.sci.eval/interpretation-reason`. Silent JVM fallback is removed. Acquisition
  records the named refusal and evaluation refuses a partially acquired program.
* Host form heads refuse, including a loaded host constructor whose edited row now
  claims `defn`. `defrecord`/`deftype` also retain host identity: SCI's distinct SciType
  would violate compiled consumers. `defprotocol` is supported and is not refused
  by this head rule. No namespace roster was introduced.
* The real declaration regression exposed a missing definition digest on the
  evaluator's namespace resolver context. That owned producer now supplies it through
  `program/definition-digest` before calling armed `fn/source-rows`.

## Dependency and authority evidence

SCI gitlink: `fcbd8862800e638dc0f8f5521111f999279cbcd2`.
Re-located by name: `core/copy-var*` at `reference-code/sci/src/sci/core.cljc:112`,
`core/fork` at 345, `impl.utils/bind-root!` at 362, `impl.evaluator/eval-def` at 25,
`impl.analyzer/analyze-new` at 1320. Copy reads the host root; fork supplies a new
mutable environment and generation; binding/definition owns inherited Vars in that
new generation. The first-party callers are `install-jvm-root!`, acquisition's
prebinding pass, and `install-function-from-database!`. Inputs are the context and
exact admitted rows. Recalculation happens on program acquisition, never on each call.
The bulk digest comparison is proportional to function population; interpretation is
proportional to the affected closure. No new memory cache; memory was not measured.

`cluster.boot` reads source commit from the supplied source base or the cluster row
(lines 154–158); `cluster.source/database` materializes that exact commit. The loaded
source value is preserved across branch forks, rather than reading a later branch
head as evidence of what the JVM loaded.

## Runtime and isolated verification boundary

Read-only MCP (`mode jvm`, `read_only true`, root `/Users/sean/src/seon`, cluster
`default`) and `bin/seon status` found PID 28922, start
`2026-09-22T17:05:36.127Z`, reachable prepl but no cluster connection/source/context.
The graph metadata probe completed in 3 ms. Full MCP envelopes were not retrievable
through the missing cluster/projection; this is degraded tooling, not behavioral proof.
Default was never stopped, reset, reloaded, or mutated by this lane.

Shared source churn invalidated the first scratch publication. Continued in
`tmp/realities-wt`, detached at `4f77502c3d19441d136552f038f8114213c6187b`, with vendored
dependency links and only owned changes applied; fn.clj excludes foreign analyzer hunks.
No other lane's session or files were operated.

Required from-zero command was executed:

```
bin/seon --root tmp/realities-c1-root reset --force
```

In the isolated checkout, schema/source population completed, but full boot refused
in `cluster/ensure-entity-call`: generated namespace `my.agents.root` lacked required
`:seon.program/definition-digest`. That foreign boot producer is outside this lane.
This is **not a successful full boot**. Log: `tmp/realities-wt/tmp/realities-boot.log`;
process log: `tmp/realities-wt/tmp/realities-c1-root/data/clusters/default/logs/seon.log`.
Both owned scratch processes were stopped with the operator and their exits observed.

`bin/test-fast --paths <owned paths> -- seon.sci.eval-test` was attempted; recording
initially lacked a source branch. The focused branch regression request later recorded
run `1a00679aaca0`, but its cached canonical base (277ae2d…, 50 commits behind) contained
removed `seon.search/ping-map-fn?`, so fixture setup refused before assertions.
`tmp/realities-wt/tmp/realities-test.log` retains that boundary. No cold gate or suite ran.

The existing `cluster/publication-base!` owner exported the scratch's published program
into `tmp/realities-wt/tmp/realities-fresh-base`; no manifest/ready file was fabricated.
Using this base, `arm/initialize-contracts!` armed 1,695 functions (1,685 program-armable),
then the installed `seon.test/run` executed the one regression under real contracts,
real SCI and canonical `support/with-database`. The disposable launcher is
`tmp/realities-wt/tmp/realities-proof.clj`; output is `realities-proof-green.log` beside it.
Recorded run **d9f68f1b7d90: 21 passes, 0 failures, 0 errors**.

## Six-part proof

The existing compiled identities are `seon.sci.eval-test/cut?` and its unchanged
caller `seon.sci.eval-test/ok?`. Both are core program rows in the canonical fixture.
Two Datahike branches are made from one captured commit. Branch A evaluates:

```clojure
(defn ^{:malli/schema [:=> [:cat :map] :boolean]} cut? [evaluation] true)
```

`eval/evaluate` produces the analyzed declaration, and the same `turn/row-tx` owner
used by agent settlement constructs the transaction. `support/transacted!` checks it.
No declaration rows are hand-written. The unchanged caller's complete row is compared
before/after, and its initial SCI root is asserted identical to the compiled JVM root.

| Proof | Form / observation | Result | Time |
|---|---|---|---|
| Acquisition A | `eval/acquire!` after declaration commit | success | 1,658.341 ms |
| (a) A direct | `(seon.sci.eval-test/cut? {})` | `true` | 15.014 ms |
| (b) A unchanged caller | `(not (seon.sci.eval-test/ok? {}))` | `true` | 11.626 ms |
| (c) B both forms together | vector of the above forms | `[false false]` | 18.569 ms |
| (d) Original live fixture context | identical environment object; indirect form | unchanged, `false` | assertion |
| (e) Host override | declare `seon.env/->Environment` as ordinary defn, install committed row | named interpretation-error; B binding remains | assertion |
| (f) Count | `:seon.sci.eval/interpreted-count` | **3 of 4,440** | in acquisition |

The three functions are `cut?`, `failed?`, `ok?`. The count equals the graph owner's
closure, and its tests match the existing gate-sets result. The real default had no
live context to compare: (d) is the canonical fixture's live-context isolation proof,
plus no operation on default; it is not a claim to have observed a healthy default.

Host declaration form:

```clojure
(defn ^{:malli/schema [:=> [:cat :map] :map]} ->Environment [members] members)
```

The returned declared error names `seon.env/->Environment` and says:
“Host-bound declaration clojure.core/defrecord must change through the loaded source files.”
Its schema validates; A's binding is absent and B's remains present.

The first complete run passed all 21 assertions but failed the ordinary 5-second
body bound (14,651.158 ms). The regression now declares 20,000 ms with that measurement
and the cost (canonical fixture, three complete context acquisitions, two analyzed
declarations and cleanup) beside the number. The subsequent installed run is green.

## Limits and remaining facts-lane work

The initial production message-leaf probe (`seon.cluster.message/send-value`) reached
47 functions and encountered an unavailable operator namespace binding. It was not
claimed as a successful interpretation proof. Function binding failures now stay inside
the per-row named refusal boundary. The successful regression deliberately exercises
an interpretable existing caller chain.

Class literals, static interop and nested host forms in bodies remain the indexer's
per-declaration host-bound fact, as ruled for the facts lane. No source-text scan or
namespace whitelist substitutes for that fact. Context refresh at turn boundaries,
agent branch attributes, publication, merge and runner lifecycle are commits 2–4.
A fresh export under an older isolated HEAD is not current shared-HEAD platform proof;
the orchestrator still owns the cold gate and platform proof.

## Before/after regression control

Against isolated HEAD `4f77502c3d19441d136552f038f8114213c6187b`'s original evaluator,
with only the resolver-context digest prerequisite fixed to let the declaration run,
the identical regression fails: A's unchanged caller returns `false`; interpreted
count is absent; the host constructor override installs rather than returning the
named refusal. B remains unchanged. The graph/schema additions were retained so the
same assertions could execute. This control isolates the prior execution decision,
not a claim that untouched old HEAD can run today's digest-enforced declaration.
Log: `tmp/realities-wt/tmp/realities-before.log`. The candidate evaluator was restored
in the isolated checkout immediately after observed process exit.

## Final ownership and load check

Candidate shared-tree load succeeded (exit 0):
`clojure -M -e "(require 'seon.sci.eval 'seon.sci.admit 'seon.sci.kernel)"`.
Log: `tmp/realities-final-load.log`. `git diff --check` passes.
The earlier hold was resolved by the owner's explicit carry ruling, after exact
patch verification described above. Post-commit archive-load evidence follows below.

All owned test/probe shells and JVMs have exited; both scratch operators' stops were
observed. Process inspection found no remaining realities JVM. The first failed scratch
root had no `lsof` holders and was removed. The isolated checkout, its offline published
store (including recorded runs), fresh fixture export and logs are retained as unresolved
landing evidence for continuation. No live default operation, publication, push or merge.

## Acquisition cost and track 1.4

**Flag: the measured 1,658 ms acquisition includes projection reconstruction, the
work track 1.4 removes by making projection acquisition a read.** It is not the cost
of a branch pointer or of three interpreted functions alone. [README §5](../plan/README.md#5-measurements-and-size)
records **3,996 ms** of projection acquisition inside a 4,648 ms fixture-base
acquisition and names repeated reconstruction as the optimization target. This
regression times acquisition as a whole; it does not separately attribute every
millisecond to projection work. The measured closure remains **3 of 4,440**;
the installed armed proof remains **21 passes, 0 failures, 0 errors**.
