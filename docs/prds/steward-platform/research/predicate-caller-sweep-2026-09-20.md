---
type: research
status: active
tags: [error, testing, contracts, namespace-agents]
---

# Predicate caller sweep — 2026-09-20

The requested namespaces load again. Armed verification is still refused before
any tests execute: external schema consumers retain `:seon.error/kind` after its
retirement. There is **no fast test/assertion tally**, and no claim that the
fifteen earlier preparation failures have disappeared in execution.

## Grounding and boundary

Read end to end: AGENTS.md sections 0–5; namespace-agents plan §8, including D12
and D13; the inventory's R1–R8 replacement actions and all nine assigned per-file
sections; error-family-1a's “Step 6 continuation” and “D13 final verification and
handoff”. Also read the inventory's D12 public/private predicate census. Read
the landed `seon.error` base constructor, preparation, recurrence, facet and
complete-observation reader implementations, and the three applicable skills.

Authorities:

- [AGENTS.md](../../../../AGENTS.md)
- [D12/D13 plan](../plan/namespace-agents-plan-2026-09-19.md)
- [Retirement inventory](error-kind-retirement-inventory-2026-09-19.md)
- [1a handoff](error-family-1a-2026-09-19.md)
- [Active roadmap](../../context-generation/plan/README.md)

Dependency ledger: this conversion needs only Clojure map membership, whose
`contains?` implementation and map/index distinction are at
`reference-code/clojure/src/clj/clojure/core.clj:1502`. The required base members
come from `resources/seon/schemas/seon.error.edn:266`, not a new declaration.
The existing first-party structural idiom is `src/seon/error.clj:152`.
Database boundaries declare the explicit `:seon.db/error-result` union in
`resources/seon/schemas/seon.db.edn:6`, including base pass-throughs.
`src/seon/db.clj:1162` reads the projection carried by a database value without
acquisition. `src/seon/error.clj:647` consumes that supplied projection in
`prepare`; `observation-selector` at `:1672` acquires complete declared components
and `facets` at `:1813` validates complete observations with supplied declarations.
No dependency fork, new execution mechanism or schema was changed.

The live default JVM answered one read-only MCP probe: an inline map check for
all three base members returned `[true false false]` for a map with those keys,
`{}`, and `nil`. This proves membership behavior only, not schema validity or
adoption. No definitions were evaluated, no provider called, and no lifecycle,
reload, publication or adoption command was run. Default PID 41822 remained
alive. All file changes were kept off the live default as instructed.

## Changes and commits

Each decision checks `map?` and the required base members `:seon.error/at`,
`:seon.error/layer`, `:seon.error/operation` at that boundary. These sites pass
arbitrary errors from database reads or generic-output callees; the check does
not acquire a projection, inspect a message, invoke a global, or reconstruct the
value. Existing success/failure branches remain in place. Producer contract
completion remains step 6 below, rather than inferred error classifications.

| Commit | Owned path and change |
|---|---|
| `ef67f8a8b` — HEAD loads again | `src/seon/fn.clj`: 18 public-predicate call sites |
| `d7fc8aa00`, `1b6f2141c` | `src/seon/turn.clj`: 16 call sites; removed the retired optional kind member from the inline settlement contract |
| `922559885` | `src/seon/cluster.clj`: 7 call sites; `commit-fault!` supplies `:seon.schema/projection (db/carried-projection db)` to `error/prepare` |
| `f45667179` | `src/seon/plan.clj`: 3 direct sites; deleted the forwarding `error-value?` function and inlined its 36 callers |
| `9d36367fa` | `src/seon/test.clj`: 31 HEAD call sites; the A1 working version has 30 |
| `dba46ea7f` | `test/my/plan_test.clj`: 2 assertions |
| `738035afc` | `test/seon/cluster_test.clj`: 4 assertions |
| `dc36dbcaf`, `bdd378a62` | `test/seon/fn_test.clj`: 1 assertion; its diagnostic fixture now supplies a complete base observation rather than provoking a constructor contract violation |
| `7829510c1` | `test/seon/turn_test.clj`: 3 assertions |

The tenth touched path is this landing note. No error owner, instrumentation,
error schema, selection or runner file was edited.

### A1 preservation

Initial dirty paths were `bin/test`, A1's landing note, and `src/seon/test.clj`.
Only the predicate expressions in the last file were converted. For its commit,
an alternate Git work-tree containing the transformed **HEAD** file supplied
`git commit --only -- src/seon/test.clj`; the shared working file was never
replaced. Assertions verified both:

1. shared file = exact initial A1 bytes with only predicate calls replaced;
2. committed file = exact HEAD bytes with only predicate calls replaced.

SHA-256 of initial A1 file: `d1a343bc8615829b73df1ddef56afdf324b6f8c06c9f73d11040e44f06d3f5d9`.
SHA-256 of the committed conversion: `a0feb12bbd1517713ec698f5bc8de5de096ed38e6c07ecbccb0213c8f452726e`.
A1's remaining diff is its own work, with the converted checks carried through.
The launcher and A1 note were not edited or committed by this lane.

## Verification

Both required raw-JVM commands exited 0 and printed `:ok`:

```sh
clojure -M -e "(require 'seon.fn) (println :ok)"
clojure -M -e "(require 'seon.cluster 'seon.turn 'seon.plan 'seon.test) (println :ok)"
```

The complete load command was repeated after `1b6f2141c` and again exited 0
with `:ok`; final lint still reports 0 errors / 85 warnings / 0 info.
The first proof preceded `ef67f8a8b`. An initial alias-removal attempt refused
`No such namespace: error`; restoring the existing alias (still needed by
qualified keywords) resolved that local edit before the commit. Each successful
load log was 202 bytes including the same environ warning; SHA-256
`49eec86290774024ba4fe34dd08f5dfbcb192c1f67167d1bbef2cddb75920ddd`.

`rg -n 'error/error\?' src test` returns no matches. No private general predicate
remains in `seon.plan`. `git diff --check` passes. clj-kondo on exactly the nine
Clojure files reports **0 errors / 85 warnings / 0 info**, 9 files.

The exact requested iteration command was run from the shared checkout:

```sh
bin/test-fast --paths \
  src/seon/fn.clj src/seon/turn.clj src/seon/cluster.clj \
  src/seon/plan.clj src/seon/test.clj \
  test/my/plan_test.clj test/seon/cluster_test.clj \
  test/seon/fn_test.clj test/seon/turn_test.clj \
  -- seon.fn-test seon.turn-test seon.cluster-test my.plan-test \
     seon.test-test seon.error-test seon.instrument-test
```

Shared snapshot HEAD: `7829510c1`; only A1's `src/seon/test.clj` differed.
Projection acquired at `2026-09-19T20:50:56.693371Z`, PID 25281. Exit **1**, before
namespace test execution. Exact refusal:

```clojure
{:seon.error/layer :seon.instrument/registration
 :seon.error/operation seon.instrument/apply!
 :seon.instrument/fn seon.test.accretion/install-refusal
 :seon.error/diagnostic-expected
 [:=> [:cat :seon.test.accretion/gate-report]
  :seon.test.accretion/install-refused-error]
 :seon.error/diagnostic-offending :seon.error/kind
 :seon.error/diagnostic-cause :malli.core/invalid-schema}
```

The three diagnostic keys above are transcribed from the refusal's
`:seon.error/data` map. The external required member is at
`resources/seon/schemas/seon.test.accretion.edn:107`. Shared log: 6,245 bytes,
SHA-256 `0cb5eca4c648f778a00c4d7376a3609939f272fdca7f1305811f281ac4d90875`.

Following the assignment's isolation rule, created detached
`tmp/predicate-caller-sweep-wt` at the same HEAD, linked `reference-code` and the
existing published-base/dependency caches, and overlaid only the owned index
fixture correction. Ran the identical nine-path command there. No A1 or foreign
working-tree source entered this snapshot. Projection acquired at
`2026-09-19T20:53:15.883951Z`, PID 25968. Exit **1**, again before tests:

```clojure
{:seon.instrument/fn seon.turn/record-evaluated-call
 :seon.error/diagnostic-expected
 [:=> [:cat :seon.db/database-value :seon.turn/record-evaluated-call-request]
  :seon.store/transaction-data]
 :seon.error/diagnostic-offending :seon.error/kind
 :seon.error/diagnostic-cause :malli.core/invalid-schema}
```

This request reaches `:seon.cluster.eval/settle-request` through
`resources/seon/schemas/seon.turn.edn:198`; the obsolete optional kind member is
`resources/seon/schemas/seon.cluster.eval.edn:139–141`. Optional members still
compile their schemas. Isolated log: 8,880 bytes, SHA-256
`d8e46f8041428112d01ed3f84e0b1e0646fff7d458587fe9c4b7d9052d60f977`.
The same isolated checkout also passed the complete four-namespace raw-JVM
load command, exit 0, `:ok`, independently of A1. Its worktree was removed after
the load and fast processes exited and the process table showed no live holder.
The different first arming failures do not establish different root causes:
both refer to the deleted schema. The retirement inventory already names these
schema consumers. The related [editing-surface issue](../../../seon/issues/schema-edit-admission-cannot-load-after-error-predicate-retirement.md)
remains open; namespace loading is repaired, full schema admission is not proved.

**Fast tally: unavailable — both runs failed initialization; neither emitted a
test/assertion/failure/error tally.** This is not a green result or a tally of
zero failures. The fifteen earlier `error/prepare` failures remain unverified.
No foreign schema was patched, no contract was weakened, and no arming step was
bypassed to manufacture a tally.

## Step-6 callee list

Dated at this cut; this table records generic or incomplete producer contracts
encountered by the converted branches, not a second runtime classification list.
The base is the promised common value at those boundaries. Exact domain output
facets must be declared by their owners before narrower checks are justified.

| Converted callers | Callees still declaring generic error outputs |
|---|---|
| Index reconciliation and publication | `seon.fn/published-index-rows`, `seon.fn/reconcile-tx-in`; their contracts explicitly return `:seon.error/value` |
| Turn writer and declaration comparison | `seon.turn/current-run`, `seon.turn/opening-db`, `seon.turn/declaration-written-by-run?` |
| Turn budgets and next work | `seon.turn/max-episode-runs`, `seon.turn/episode-runs`, `seon.turn/turns-left`, `seon.turn/opening-deferred?`, `seon.cluster.wake/declarations-refusal` |
| Cluster activation and boot recovery | `seon.cluster/closure-fact-missing`, `seon.cluster/missing-process-rows`, `seon.cluster/recover-runs!` |
| Plan reads and rendering | `seon.plan/plan`, `seon.plan/item`, `seon.plan/ready`; generic input pass-throughs also reach `step-summary` and the terminal formatters |
| Plan issue-test execution | `seon.test/stale`, `seon.test/run`, `seon.test.runner/provenance`, `seon.test.runner/commit-results!` |
| Test selection/admission/checking | `seon.test/selection-refusal`, `seon.test/select`, `seon.test/selection-seeds`, `seon.test/reaching`, `seon.test/selection-admission`, `seon.test/check-request-admission`, `seon.test/destroyers`, `seon.test.runner/provenance`, `seon.test.runner/program-digest`, `seon.config/effective` |
| Database value/read/write propagation across all five owners | `seon.db/db`; `seon.db/q`, `seon.db/pull`, `seon.db/pull-many`, `seon.db/entity`, `seon.db/datoms`, `seon.db/history`, `seon.db/transact!` use the explicit `:seon.db/error-result` union, which still contains `:seon.error/value` and the base arm |

Additional incomplete declarations, kept visible rather than disguised as
precise contracts:

- `seon.fn/normalized-index-row` and its local normalization functions;
  `published-index-rows`' local `row`/`reference` functions forward database
  failures without their own exact declarations. The transaction callback
  through `seon.schema/call-with-projection` forwards `reconcile-tx-in`.
- `seon.plan/subject-eid`, `owned-ids`, `foreign-open-work`, `derived-frontier`,
  `agent-plan-pull`, `transact-plan!`, and `step-summary` have no own exact error
  output contract. Their read/write authority is `seon.db`.
- `seon.test/check-admission` and `destructive-reach` lack their own exact output
  declarations; they forward the named selection and graph owners above.
- `seon.fn/declared-reference-edges`, `gate-set-in`, `gate-sets-in`, and
  `gate-sets`, plus `seon.plan/agent-eid`, `plan-eid`, `step-eid`, `ref-eid`,
  still name legacy `:seon.db/invalid-read-error` and
  `:seon.schema/missing-projection-error`. These declarations need reconciliation
  with their database callees' base/facet union in step 6; this sweep does not
  claim they are converted domain contracts.
- `seon.turn/outside-wake-t` declares only a nonnegative integer; its existing
  caller's refusal branch also recognizes a base observation from instrumentation.

The broader kind/constructor/schema conversion remains the inventory's work.
For example, the existing `seon.plan/stored-comparables` read-refusal branch
returns `[]`, and `plan` annotates a propagated refusal with agent data. Their
existing branch bodies were preserved here; they are not evidence of exact
verbatim forwarding throughout the plan owner. The optional kind schema inside `seon.turn/receipt-settle-call` was removed
in `1b6f2141c` after the arming runs; its map stays open and retains its substantive
settlement members. This resolves the owned inline reference. The two measured
external schema refusals still require their owners.

## Owed integration proof

After the external required/optional kind-schema consumers are converted, rerun
the exact fast command to a tally and verify the fifteen preparation failures.
The orchestrator still owes the path-limited cold gate, the platform proof, the
batched schema reset/adoption, and live user-visible verification. This lane
ran no `bin/test` cold gate, `--all`, `--full`, or `bin/seon` lifecycle command.
The `bin/test:` log prefix above belongs to `bin/test-fast --paths`' shared
snapshot implementation, not a cold invocation.

Cleanup: all owned command sessions exited. The isolated worktree and project-local
probe directory were removed after their evidence was recorded here. Foreign
uncommitted paths were preserved.
