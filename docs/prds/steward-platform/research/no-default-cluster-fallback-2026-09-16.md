---
type: research
status: landed; the test-check seam and item 16 remain recorded hunks
created: 2026-09-16
tags: [config, schema, program-graph, wave/config-cluster-identity]
---

# Explicit cluster inputs and declared classifications

Read AGENTS.md sections 0–3 and 5–7, and both
[the workaround inventory](workaround-inventory-2026-09-16.md) and
[the program-facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
end to end, including inventory §1 and its ranked list and PRD S6.
This assignment permits fast iteration, forbids a cold gate and default
lifecycle operations, and stops each item at a concurrently held dependency.

## Dependency ledger and inherited state

- Configuration uses the existing `seon.config` compiler, `seon.schema.edn`
  composite builder, `seon.schema.form/attr-form-properties`, and the canonical
  `seon.test-support/with-database` fixture. Read the schema EDN loader and
  schema-form inspection owner end to end; inspected the compiler, its callers,
  and its existing tests before editing.
- Malli authored forms carry explicit properties; no new classifier or
  registry is introduced. Existing armed function contracts refuse omitted
  arguments and required map entries, naming the callable.
- The schedule proc already holds its cluster handle and passes an explicit
  execution context. The dependency's four lifecycle arities and argument
  carriage are documented in
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165`.
  No new running machinery is introduced.
- `bin/seon status` reported default PID 41413 alive. MCP runtime status
  answered but reported 95 failed tests, two error signatures, two stale Vars,
  and an unknown search-proc observation. These are inherited observations,
  not attributed causes. No restart or reset was performed.
- The one permitted read-only prepl evaluation queried packaged forms through
  `seon.schema.edn/packaged-forms` and
  `seon.schema.form/attr-form-properties`: 89 prefix-matched attributes,
  94 explicitly declared dials, **zero prefix-only attributes**, 50 ms.
  There will be no second prepl evaluation in this assignment.

## Class 2: config membership

`src/seon/schema/edn.clj` removes the namespace-prefix arm of `config-dial?`.
No resource lacks the property, so no resource change is needed for this class.
`test/seon/schema/edn_test.clj` now explicitly declares the existing synthetic
registered dial and adds
`config-dials-are-declared-independent-of-their-names`: nonempty old-union,
declared, and composite populations must agree; a prefixed nondial is excluded
and a declared dial in an unrelated namespace is included.

Verification: **16 tests, 52 assertions, zero failures/errors**, armed
contracts, snapshot HEAD `f992669f93afdec00c50edfa47d56db46822880e` plus these two
paths. Command: `bin/test-fast --paths src/seon/schema/edn.clj
test/seon/schema/edn_test.clj -- seon.schema.edn-test`. The fast entry point
internally uses `bin/test --fast` for its snapshot; no cold gate was requested.

Hook lint reported existing shadowed-local warnings. Markdown lint reports
pre-existing stale dependency gitlink citations in the historical AGENTS audit;
those historical files are outside this assignment.

## Class 1: cluster inputs

Changed sites:

- `src/seon/config.clj`: `compile-manifest` constructs its durable desired row
  only with the caller's name. Its existing settings compilation is a private
  function shared with `defaults`, which needs no cluster and creates no row.
  `effective` has only its explicit two-argument arity; the nil-to-default
  substitution is deleted.
- `resources/seon/schemas/seon.config.edn`: the compile and apply request maps
  require `:seon.boot/cluster-name`.
- `src/seon/schedule.clj`: deleted the three-argument `fire-due!` arity, its
  arbitrary-entity query, default substitution, and global handle lookup.
  `schedule-step` already passes the required execution context containing
  the cluster name and handle; every checked-in successful schedule-test call
  already passes that context. No scheduler caller needs a new lookup.
- `src/seon/sci/eval.clj`: `database-effective-config` already hands an
  observed cluster to the two-argument reader. Its no-config branch now returns
  a typed required-absence value naming that caller and cluster-name key,
  instead of invoking the removed default arity.

Every previously implicit fixture caller now hands its fixture's explicit
`"default"` name:

| file | caller |
|---|---|
| `test/seon/config_test.clj` | compile/apply scenarios and effective reads, including the bound `request` in `converged-apply-uses-carried-projection-and-remains-exact` |
| `test/seon/test_support.clj` | `effective-config` manifest arity |
| `test/seon/background_test.clj` | `terminal-background-results-open-one-result-only-run` |
| `test/seon/problems_test.clj` | `with-db` fixture |
| `test/seon/data_shapes_test.clj` | reasoning retention, attempt usage, attempt settings/model tests |
| `test/seon/repl_parity_test.clj` | `production-request` and the database fixture |
| `test/seon/ai_test.clj` | `stop-is-effective-per-agent-and-recorded-with-the-attempt` |
| `test/seon/turn_loop_test.clj` | `attempt-settlement-updates-the-registered-model-gauges` |
| `test/my/plan_test.clj` | call-preparation initialization |
| `test/seon/cluster/agent_identity_test.clj` | call-preparation initialization |

Production boot (`seon.cluster/start!`'s compiler call), operator config apply,
and `seon.config/apply!`'s internal call already carry the explicit cluster;
none needed an invented name. Existing alpha/beta config isolation coverage
remains in `seon.config-test`. New omission regressions use canonical fixtures,
assert the armed refusal names each callable, and assert unchanged basis.

### Protected test-check seam: exact unapplied hunks

`resources/seon/schemas/seon.test.edn` was and remains concurrently modified.
Although `src/seon/test.clj` is clean, requiring its input at the declaration
owner needs that held file. Per the assignment, this seam is stopped as a unit;
the three test-check fallbacks are **not fixed** by this slice.

```diff
--- resources/seon/schemas/seon.test.edn
+++ resources/seon/schemas/seon.test.edn
@@ :seon.test/check-request
-  [:seon.boot/cluster-name {:optional true} :seon.boot/cluster-name]
+  [:seon.boot/cluster-name :seon.boot/cluster-name]
--- src/seon/test.clj
+++ src/seon/test.clj
@@ check-in-process
-        effective (config/effective database (or cluster "default"))
+        effective (config/effective database cluster)
@@ deferred fixture command
-                                    :seon.test/command ["bin/test-check" (or cluster "default")
+                                    :seon.test/command ["bin/test-check" cluster
@@ check
-  (let [effective (config/effective (db/db connection) (or cluster "default"))]
+  (let [effective (config/effective (db/db connection) cluster)]
```

Before applying those hunks, the follow-up must hand the fixture's name through
the `sut/check` requests in `test/seon/test_reaching_test.clj` and other direct
check callers that omit it, and prove the missing-key refusal names
`seon.test/check`. `check-adoption` already hands its argument through;
`my.test/check` uses its declared call-preparation request. No protected file
was edited and no foreign lane was contacted.

### Verification boundary

Config/schedule fast snapshot iteration is pending. The edit-hook result
`tmp/source-publications/ff01073f-a510-4016-a637-57763dd5ba58.edn` reports
publication refusal. `logs/current-source-failure.log` ends with
`Source changed while current-src was being analyzed; retry.` and source
digests `8aef59e6107be078ad38e21b1294b13af9b7bde5fe7eeb10c8625be16539518b`
and `1cdf962a23894968734e763a395151362081d946c2840eefc026564514a151b8`.
This establishes a changing-source boundary, not which lane caused it.
No live adoption, hot-reloaded behavior, or browser proof is claimed.

## Class 3: S6 stopped at its protected owner

Documentation commit `c6182e409` records the
[exact unapplied root hunks and integration boundary](identity-attributes-declaration-hunks-2026-09-16.md).
`src/seon/program.cljc` and `resources/seon/schemas/seon.test.edn` were held at
entry and recheck. No S6 production edits or verification are claimed. The
existing identity-list issue remains open. The note also identifies the fixed
identity-attribute enum and the distinction between program-family and domain
identity populations, which the eventual common derivation must preserve.

## Continuation 2026-09-16: what the refusal must actually be

The original lane ended mid-slice. Reviewing its uncommitted work found the
cluster-input deletions correct and one consequence unhandled.

`seon.sci.eval/database-effective-config` returns the value that
`seon.config/result-caps` and `seon.render/agent-render-profile` consume, and
both declared their input as
`[:or :seon.config/effective :seon.config/missing-effective-error]`. The
replacement refusal cannot be a `missing-effective-error`: that schema
REQUIRES `:seon.config/missing-effective`, which is a cluster name
(`resources/seon/schemas/seon.config.edn:42-43`), and a database carrying no
configuration singleton at all has no name to give. Handed the lane's flat
refusal, `result-caps` reported a missing cap key instead of the configuration
absence, and `agent-render-profile` — which dispatches on
`(:seon.config/missing-effective effective)` — fell through and built a
profile whose every budget was nil. That is absence read as a policy, the
exact class AGENTS.md section 2.4 forbids.

Both consumers now accept `:seon.error/value` as a third input alternative
(accretion: a widened input) and return a refusal unchanged.
`result-caps` passes through only a refusal that names NO cluster, so the
existing `missing-effective-error` → `::missing-result-cap` report, which does
name its cluster and its missing facts, is untouched.
`seon.render/agent-render-profile` gained `:seon.error/value` in its output
union alongside the refusal shape it already returned.

`seon.config/effective` and `seon.schedule/fire-due!` were left at the
previous arity's indentation by the deletions; both bodies are reindented with
no other change.

## Item 11: an absent declaration is not an empty one

`src/seon/sci/eval.clj` also carried an orphaned hunk from a separate
terminated agent, implementing ranked item 11. Reviewed and completed:
`doc` and `dir` rendered an absent `:seon.fn/spec` as `[]`, absent
`:seon.fn/arglists` as `()`, and an absent `:seon.fn/doc` as `""` — three
claims the program row does not support, on the surface an agent reads before
deciding how to call a function. They now render
`:seon.sci.eval/declaration-absent`, a typed unknown naming the attribute.
Its regression is
`an-absent-declaration-is-a-typed-statement-not-an-empty-one` in
`test/seon/sci/documentation_test.clj`: a canonical-fixture program row from
`seon.test-support/program-fn-row` carries none of the three, and both `doc`
and `dir` must name each absent attribute rather than show an empty value.

## Class 1 remainder: `src/seon/test.clj` is held again, for a new reason

The three `(or cluster "default")` substitutions at `src/seon/test.clj:634`,
`:666` and `:860` are NOT fixed, and the recorded hunk above still stands.
Two independent blockers were found, both new since the original stop:

1. **The agent surface has no way to name its cluster.**
   `:my.test/check-request` IS `:seon.test/check-request`
   (`resources/seon/schemas/my.test.edn:1`), so requiring the key at that one
   declaration refuses every `my.test/check` call. `config/default.edn:497-516`
   declares four call-preparation suppliers and no `:seon.boot/cluster-name`;
   no `my.*` function requires that key today. The missing piece is one
   ordinary `seon.env/supplied-cluster-name` shaped like
   `seon.env/supplied-agent-id` (`src/seon/env.clj:303`) plus its manifest row —
   and `config/default.edn` currently carries another slice's uncommitted
   `:seon.search/handle` row whose `seon.search/supplied-handle`
   (`src/seon/search.clj:101`) is itself uncommitted, so committing the
   manifest now would publish a supplier naming an unpublished function.
2. **The file is concurrently held.** `a00e73e49` (16:38) added the 81-line
   `seon.test/check-request` — ranked item 24 — while this continuation was
   starting, and left `resources/seon/schemas/seon.test.check.edn` untracked.

That second point is a standing breakage, not only a scheduling note: any
snapshot taken from HEAD alone refuses instrumentation with
`seon.instrument/apply!`, `:malli.core/invalid-schema`, offending
`:seon.test.check/request`, on `seon.test/check-request`. This continuation's
fast runs overlay that untracked resource explicitly to arm at all. Recorded
in
[the test-check issue](../../../seon/issues/test-check-defaults-an-omitted-cluster.md);
the resource belongs to its committing session and was not taken here.

Ranked item 16 (`src/seon/program.cljc` identity attributes) remains recorded
at `c6182e409` and unimplemented. `program.cljc` is clean now, but it is Tier 2
work reaching `fn.clj`, `turn.clj`, `cluster/source.clj`, `test/runner.clj` and
`sci/eval.clj`, and `test/runner.clj` sits inside the concurrently committing
session's seam.

One further site of the same class was seen and NOT taken, because it is
outside the seven: `seon.render/request-profile` (`src/seon/render.clj:127`)
reads "whichever entity carries `:seon.cluster/name`" and falls back to
`default-agent-profile` when the database names none — the same pre-read
ranked item 10 names in `schedule.clj`.

## Continuation 2026-09-16 (second): the refusal keeps one grammar

The first continuation was killed mid-verification. Reviewing its tree found
its cluster-input deletions correct and its downstream decision wrong in one
place.

It had made `seon.config/result-caps` and `seon.render/agent-render-profile`
return a cluster-less refusal UNCHANGED. For `agent-render-profile` that is
right: it cannot build a profile out of a refusal, and one built from absence
carries nil budgets that every later render reads as policy. For `result-caps`
it is not. `result-caps` owns a declared error class,
`:seon.config/missing-result-cap-error`
(`resources/seon/schemas/seon.config.edn:78`), and
`seon.instrument/wrap-interpreted` (`src/seon/instrument.clj:494`) reports
exactly that value when a `:panic` contract cannot be armed. Passing a foreign
refusal through drops the class and silently changed a checked-in guarantee:
`a-configless-database-refuses-caps-by-name-and-still-installs`
(`test/seon/sci/eval_test.clj:547`) asserts the caps refusal NAMES the first
cap key. Under the passthrough it named `:seon.boot/cluster-name` instead, and
that regression went red.

`result-caps` now always answers in its own grammar and carries the incoming
configuration refusal as the cause, under
`[:seon.error/data :seon.config/configuration-refusal]`, with the cause's
message appended to its own. Neither fact is buried by the other, the existing
regression is green untouched, and the older
`missing-effective-error` → `:seon.config/missing-effective` report is
byte-identical to before. Its regression is
`caps-refused-for-want-of-a-cluster-carry-that-refusal-as-the-cause`
in `test/seon/config_test.clj`.

### A stale config row can no longer be constructed

`result-caps-refuses-a-stale-config-row-at-construction` was red at HEAD
before this slice, and its premise is what moved. It retracted
`:seon.config.eval.result/max-nodes` from the default cluster's row through
`seon.test-support/transacted!` and then proved `result-caps` refused the
stale row. Write admission reads an identity-keyed map against the WHOLE
config schema, so that retraction is now refused at the write:

```
seon.db/transact! refused transaction data at
[44252 :seon.config.eval.result/max-nodes]: expected the required key
:seon.config.eval.result/max-nodes with an integer, got a map missing
:seon.config.eval.result/max-nodes.
```

The fixture was honest — `transacted!` stopped the test AT the write, which is
exactly what it is for. The stale state is simply unconstructable now.
Rewritten as `a-config-row-cannot-be-left-without-a-required-result-cap`, it
asserts the writer's refusal names the key and that the basis is unchanged.
The second half could not be rebuilt in place either: `result-caps`'s other
declared input, `:seon.config/effective`, REQUIRES every cap key, so handing
it a `dissoc`ed effective map violates its own armed input contract. The
refusal shapes that genuinely reach the missing-cap branch are the two error
values, and both are now covered —
`two-clusters-on-one-jvm-have-no-config-bleed` for the
`missing-effective-error`, and the new cause regression for the flat one.

### Item 11 and the callers

Item 11 (`doc`/`dir` rendering an absent `:seon.fn/spec`, `:seon.fn/arglists`
or `:seon.fn/doc` as `[]`, `()` and `""`) landed as the previous continuation
left it, with its regression
`an-absent-declaration-is-a-typed-statement-not-an-empty-one`
(`test/seon/sci/documentation_test.clj`). Two caller files the first
continuation edited but the commit list omitted are included, because their
only change is this slice's explicit cluster name and HEAD would otherwise be
inconsistent with the required input: `test/seon/test_support.clj` and
`test/seon/turn_loop_test.clj`.

### A fixture refusal that named nothing

`seon.test-support/checked-fixture-result` threw
`"Fixture setup was refused."` with the refusal only in `ex-data`, which
`clojure.test` does not print. Every base-construction refusal therefore read
identically. It now names the refusal's kind and message. That is how the
remaining diagnosis below was reached at all, and one layer further down the
same defect is still there: `seon.fn/assert-clean-analysis!`
(`src/seon/fn.clj:1062`) reports `"Static program analysis found blocking
errors."` and keeps the findings — file, row, type — in `ex-data`. Filed as
[an issue](../../../seon/issues/blocking-static-analysis-names-no-finding.md).

### A snapshot must overlay EVERY caller, not just the changed owner

`bin/test-fast --paths` overlays the named files on HEAD. A run that omitted
`test/seon/repl_parity_test.clj` took HEAD's version of it, whose line 29
still called the now-single-arity `seon.config/effective` with one argument.
That is an `:invalid-arity` finding, one of the six blocking analysis types
(`src/seon/fn.clj:1011-1017`), so canonical fixture base construction refused
and EVERY test in the run errored at `with-database` — nowhere near the cause.
When a slice narrows a public arity, its `--paths` list is the changed owner
plus every caller it changed, or the snapshot is not the tree.

### Verification boundary (2026-09-16, second continuation)

Both runs are `bin/test-fast --paths`, pinned to the SAME snapshot base
`ca8fd63b9c2adfddb0b7cedd2d6e0c47edb7a79d`, over
`seon.config-test seon.schedule-test seon.sci.eval-test
seon.sci.documentation-test seon.ai-test seon.background-test
seon.cluster.agent-identity-test seon.data-shapes-test my.plan-test
seon.problems-test seon.repl-parity-test`.

| run | overlay | tests | assertions | failures | errors |
|---|---|---|---|---|---|
| baseline | HEAD only | 278 | 1418 | 7 | 2 |
| this slice | the 17 files below | 282 | 1448 | 7 | 1 |

The slice adds four tests and thirty assertions and REMOVES one error. Every
remaining red is present in the baseline, name for name, and none is in a
function this slice touches:

- `schema-and-contract-declarations-have-bounded-allocation`
  (`test/seon/sci/eval_test.clj:816`) — 221 594 008 bytes against a 64 MiB
  bound;
- `declared-row-evaluates-a-schema-once-inside-its-delta`
  (`test/seon/sci/eval_test.clj:1067`) — the stored row now carries
  `:seon.schema/shape` and no `:seon.schema/ns`;
- `bare-test-macros-resolve-without-namespace-referrals`
  (`test/seon/sci/documentation_test.clj:176`, with its error at
  `src/seon/instrument.clj:446`) — `:seon.test/sym` reads nil;
- `parity-e6`, `parity-e7`, `parity-e11`, `parity-e12`
  (`test/seon/repl_parity_test.clj:105`) — sci reports
  `sci.impl.fns/fun/arity-0--81843` where the expectation is
  `function of arity 0`.

An identical baseline (7 failures, 2 errors, the same names) was also taken at
the earlier base `ac24945a61c86361a9e8488cac9bdbbab2756ffc`, so the red set is
stable across the day's churn rather than a single reading.

Two earlier overlay attempts were killed at exit 124 by the suite liveness
watchdog, both at `BEGIN test
seon.config-test/apply-compiles-once-and-round-trips-through-database-facts`
— canonical fixture base construction — with `deadlocked-thread-ids nil` and
`seon-test-database-base` RUNNABLE inside `clojure.lang.Compiler`, loading
`seon.dev.docstring`. Load average was 167 after the owner's reset relaunched
every lane. The third attempt set `SEON_TEST_SILENCE_SECONDS=1500`
(`src/seon/test/runner.clj:528`) and completed. The watchdog was right to fire
and its dump named the thread and the frame; only the horizon was wrong for
that machine. No bound was weakened in the tree.

`bin/test` and `--platform` were NOT run: this is an iteration result on the
gate's own snapshot, not the isolated cold gate. No live cluster was touched;
`default` was neither stopped, reset, nor reforked, and no browser observation
is claimed.

### Files this slice touches

`src/seon/config.clj`, `src/seon/schedule.clj`, `src/seon/sci/eval.clj`,
`src/seon/render.clj`, `resources/seon/schemas/seon.config.edn`,
`test/seon/config_test.clj`, `test/seon/schedule_test.clj`,
`test/seon/sci/documentation_test.clj`, `test/seon/test_support.clj`,
`test/seon/turn_loop_test.clj`, `test/seon/ai_test.clj`,
`test/seon/background_test.clj`, `test/seon/cluster/agent_identity_test.clj`,
`test/seon/data_shapes_test.clj`, `test/my/plan_test.clj`,
`test/seon/problems_test.clj`, `test/seon/repl_parity_test.clj`, this note,
[the test-check issue](../../../seon/issues/test-check-defaults-an-omitted-cluster.md),
[the blocking-analysis issue](../../../seon/issues/blocking-static-analysis-names-no-finding.md),
and two issue-folder corrections. `src/seon/test.clj` and ranked item 16 stay
recorded as unapplied hunks above.

One more site of the same class was seen and NOT taken:
`seon.schedule/execution-context` (`src/seon/schedule.clj:511`) still reads
`seon.operator.runtime/running-instances` from a process-global registry to
find a log directory. It takes its cluster explicitly now, so it is no longer
a "whichever entity" pre-read, but it still fetches at call time what its
caller could hand it (AGENTS.md section 2.1).
