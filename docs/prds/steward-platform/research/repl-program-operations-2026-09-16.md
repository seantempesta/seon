---
type: research
status: review
created: 2026-09-16
tags: [repl, program-graph, refactoring, query, canonical-fixture]
---

# REPL program operations — first read checkpoint

Assignment: the no-reset part of
[repl-native-retraction-and-refactoring-2026-09-16.md](repl-native-retraction-and-refactoring-2026-09-16.md)
at `ac2ed1ac0`, with the explicit stop at the first coherent landed item.
This checkpoint is the independent part of §9 item 1: five pure reads.
`overrides` awaits its held system-side owner, as detailed below. Items 2–4
are subsequent work, not claimed as implemented here. No operation launches a worker,
retracts a declaration, or mutates an SCI context.

## Authorities and dependency ledger

Read AGENTS.md's opening numbered instructions and sections 1–7 in full;
the retraction/refactoring note and
[unbreakable-connections-2026-09-16.md](unbreakable-connections-2026-09-16.md)
end to end; and the program-facts PRD's §0a, §1 through §1f, and S12 in
full. The assignment overrides the note's old native-first order:
**facts decide before SCI mutation**. Read the data-oriented-clojure,
datahike, data-modeling, repl and clojure-testing skills.

The seams opened end to end:

| Mechanism | Dependency | Existing first-party owner |
|---|---|---|
| Predicate arguments are values, not nested evaluation | `reference-code/datahike/src/datahike/query.cljc:1106` (`-call-fn`), `:1165` (`filter-by-pred`) | `src/seon/db.clj`, `q`, passes the normalized query to `q-with-evidence`; result decoding occurs afterward |
| Current test selection | Datahike AVET and ordinary Datalog | `src/seon/fn.clj`, `gate-set-in`, `gate-sets`, `gate-set`; no second traversal in `my.program` |
| Render declarations | Stored `:seon.render/ai` and `:seon.render/html` properties | `src/seon/schema.clj:2998`, complete `canonical-schema-rows`, merges `storable-properties-in` into each schema row; `my.program` queries those datoms instead of reparsing forms |
| Historical definition facts | `reference-code/datahike/src/datahike/db.cljc:142`, inclusive `as-of-pred` | `seon.db/history`, `as-of`, `datoms`; no wildcard pull of cardinality-many history |
| Native mutation and its interception | `reference-code/sci/src/sci/core.cljc:309`; `impl/analyzer.cljc:1793`; `impl/namespaces.cljc:567`, `:590`, `:604`, `:610`, `:816` | `src/seon/sci/eval.clj`, `build-base-ctx`, `removed-program-identities`, `definition-row`; `src/seon/sci/reader.cljc` in full |
| Declaration writes and their candidate gate | Datahike's transaction function and serial writer | `src/seon/turn.clj`, complete `row-tx` and `gate-function-install`; `src/seon/schema.clj`, complete `unregister!` |
| Detected issue identity and launch | `seon.id/id` over detector plus installed subject identity | `src/seon/issue.clj`, complete `subject-row`, `detector-rows`, `generate`, `create-tx`, `start-tx`, `start!`; the existing identity expression is now `subject-id` and both readers call it |

## Predicate probe — settled

On default PID **41413**, both MCP tools answered. A JVM-mode `require`
of `seon.test-support` refused because the ordinary process classpath lacks
`test/`; no query or transaction ran there. This was a fixture-loading
boundary, not a broken MCP connection. The canonical fixture probe therefore
ran through the authorized fast launcher, without altering default.

Exact invocation:

```sh
SEON_TEST_SLOTS=3 bin/test-fast --paths test/my/program_query_test.clj -- my.program-query-test
```

The snapshot basis was `a4314bed6`; the launcher waited **685 seconds** for
one of the three shared slots. The armed canonical result at
**2026-09-17 00:37:28Z** was **1 test, 5 assertions, 0 failures, 0 errors**:

```clojure
#:seon.program.probe{:pulled #:seon.fn{:sym "my.note/forget!"}
                     :nested []
                     :bound-count 58
                     :direct-count 58}
```

The two successful result sets were equal and included the pulled identity.
The exact regression is `test/my/program_query_test.clj`. The bad clause
passes `(str ?s)` as a list to `starts-with?`; Datahike's `safe-pred` catches
the type exception and returns false. Correct spelling:

```clojure
[(str ?s) ?text]
[(clojure.string/starts-with? ?text "my.")]
```

This is a **wrong query**, not a Seon decoding defect. No db-owner issue is
filed. The spec's §10 now links this result. No predicate-filtered production
query was written before settling it.

### A separate query decoding defect

The later render-property probe found a different, real db-owner boundary:
an attribute collection binding returns the EDN storage string where pull,
a literal attribute and a scalar attribute binding return the renderer
symbol. Exact canonical output and source are recorded in
[collection-attribute-query-bindings-leak-edn-storage-values.md](../../../seon/issues/collection-attribute-query-bindings-leak-edn-storage-values.md).
The read uses the supported scalar binding through `seon.db/q`, with no
local decoder and no edit to the held db file. This does not change the
original nested-predicate diagnosis.

## Read contracts and deliberate boundaries

`my.program/breaks`, `callers`, `tests-reaching`, `reads-key`, and `history`
take one namespaced request map with `:seon.db/db`. Call
preparation supplies that database in SCI. All receive the same immutable
database throughout the operation. They return declared data or a flat
error, preserving database refusals rather than interpreting them as empty
sets. Every docstring carries an executable example; doc/dir use the normal
program facts.

Result groups are absent when empty. `:seon.program/unknown` is mandatory.
Direct callers have declaration spans and recorded arities; references,
test subjects, current gate sets and recorded past reach stay separate.
Schema reads distinguish contract references, writes, schema references,
stored data and literal keyword membership. Namespace reads identify
requiring namespaces and owned function, test and schema declarations.
Render declarations are read from their materialized property datoms. Plans are computed,
never launched.

The prospective issues use `seon.issue/subject-id`, extracted from the
generator's existing expression without changing its bytes or its input
identity value. Repeated reads deduplicate by detector and caller, not by
retraction or issue title. Tests are the caller's `seon.fn/gate-set`.
The proposed detector is `seon.program/unresolved-callers`.

**Launch is not implementable as the note's one call yet.** Current call
edges are refs: sweeping them removes the observation. Moreover, `generate`
invokes its detector on the actual database. When retraction was refused,
the target still exists, so an unresolved-callers detector would find no
such repair subjects. The generator also deliberately does not copy a
detector subject's proposed `:seon.issue/tests`. The result records
`:launch-unavailable`; its launch form is unevaluated data. The issue owner
must settle these input and done-condition semantics with value edges; this
checkpoint adds no intent entity, alternate generator, or fabricated red
test. `done?` still lets tests decide when present, otherwise the detector.

Three signature facts the implementation preserves:

* Namespace names such as `seon.turn` are **unqualified symbols**. The
  subject union therefore includes `:symbol`; the note's narrower
  `:qualified-symbol` would refuse its own namespace example.
* Identity attributes still store strings before the coordinated reset.
  `stored-name` consults the supplied database's installed value type at
  lookup boundaries; returned program names are symbols. No attribute is
  retyped here.
* The binding one-map API and the assignment's bare-symbol live example
  conflict with call preparation's full-arity precedence. The review
  question was raised; pending a different ruling, the proof uses the
  binding map spelling. History reuses existing `:seon.db/tx` for an as-of
  request instead of declaring another transaction name.

The later write examples also collide with an existing key:
`:seon.program/ns` already means a namespace lookup-ref tuple in the
deletion-row grammar. Its semantics must not change to a bare symbol.
That correction belongs with item 4's operations, not this read commit.

The history entries key has one global definition: `:seon.program/history`
is its vector of source events. `:seon.program/history-report` names the
enclosing return map. This avoids giving the entries key both a map and a
vector meaning; the returned data retains the same keys. An as-of report
also carries the requested transaction, and its definition uses that view,
not the earlier transaction that asserted the source. The regression changes
metadata without changing source to distinguish those two transactions.

### Override query dependency

The in-flight `seon.program/overrides` addition in held
`src/seon/program.cljc` owns the same query the agent accessor needs.
Its historical namespace/file join matters: definition replacement removes
current file coordinates. A query using only the overridden function's
current file ref misses those overrides. The duplicate query initially
drafted here was removed, with its unused schemas and test. This checkpoint
therefore does **not** publish `my.program/overrides` or an unavailable stub.
After the owner lands, the accessor should be a thin call to it. See
[acquisition-by-provenance-s3-2026-09-16.md](acquisition-by-provenance-s3-2026-09-16.md).

## Verification and remaining work

The combined read regression run passed. Its canonical fixture
checks callers and spans, gate sets, identity-deduplicated plans, schema and
namespace relations, render references, source history,
ordinary SCI doc/dir and supplied database arguments. On snapshot HEAD
`543f03258`, `run.0SdRGG`, it acquired the slot immediately, armed **1,108**
contracts, and finished at **2026-09-17 01:51:37Z** with **10 tests,
103 assertions, 0 failures, 0 errors**. The query probe now counted 77
matching identities (the population grew); bound and direct results still
agreed. Owned Clojure lint: **0 errors, 0 warnings** before the run.

Final review replaced schema-form parsing with the schema owner's
already-materialized render-property datoms and added an exact pair
assertion. The first attempt used an attribute collection binding and
exposed the db decoding defect above: **4 tests, 56 assertions, 2 failures,
0 errors**. Using scalar bindings passed with both canonical probes at
**2026-09-17 02:04:05Z**: **6 tests, 63 assertions, 0 failures, 0 errors**.
The final as-of metadata regression passed on `run.bkCRfy`, after a
426-second slot wait and arming 1,110 contracts: at **2026-09-17 02:16:17Z**,
**4 tests, 60 assertions, 0 failures, 0 errors**, exit 0. This is the final
source and schema revision. The issue generator was not changed after its
passing combined run. Final owned Clojure lint had 0 errors and 0 warnings;
`git diff --check` was clean.

The lane issued no stop, restart or reset. While the lane was stopped the
owner reset default; on resumption `bin/seon status` and MCP both identified
PID **33583**, port **62890**. The owner reported regenerated `current-src`
`6aab3ac3` converged and Juniper reseeded. The previous fast attempt received
TERM and removed `tmp/test-runs/run.rEfTYD`; it is not test evidence.

The one authorized disposable SCI evaluation used the binding map API:

```clojure
(my.program/breaks {:seon.program/subject 'seon.turn/open?})
```

It ran in the regenerated cluster's acquired SCI context, with call
preparation supplying the database. At basis **536871056** it returned
exactly these five callers with declaration spans and recorded arity 1:

```clojure
#{seon.turn/open-run-tx-call seon.turn/receipt-run seon.turn/recover-call
  seon.turn/render-ai seon.turn/require-open-run}
```

The gating collection had **1,506** tests; the ordinary value renderer
showed 32 and an elision reporting **1,474** omitted with a requery form.
The five prospective issue ids were `7d44c116e526`, `cab39ee3c470`,
`4b368634d868`, `f7a8550c731f`, and `ee5604ca46b2`. Outcome was `ok`,
duration **21,616 ms**, allocation **4,026,713,664 bytes**. This is complete
evaluation cost, not a claim about which operation allocated it.

**User-visible limitation:** the existing issue renderer reads prospective
test lookup refs as pulled maps and prints repeated unnamed `not verified`
lines. The caller and gating data were readable; the proposed issues were
not. Recorded in
[prospective-issue-refs-render-as-unnamed-checks.md](../../../seon/issues/prospective-issue-refs-render-as-unnamed-checks.md).
This is a live proof of discovery and supplied arguments, not a claim that
the entire rendered plan is satisfactory.

The first combined fast run had **10 tests, 106 assertions, 9 failures,
0 errors**. It found three local defects: historical views need the schema
origin for identity encoding; the SCI fixture must seed its cluster before
forking; and the override query missed historical file provenance. Fixes
use `seon.db/schema-database` and `seed-cluster!`; the override duplication
was removed in favor of the held owner. The SCI fixture also acquires its
render caps once and uses the declared evaluation time limit (30,000 ms in
the shipped config), rather than a local 10,000 ms constant. Fast runs are iteration
evidence; this assignment explicitly forbids `bin/test`, so no cold gate or
platform-tier result is claimed.

The markdown hook also reports 30 stale gitlink citations in the unrelated
dated `agents-md-audit-2026-09-15.md`. This lane did not edit that document.

### Interrupted fast runs

The post-reset snapshot at HEAD `ca8fd63b9`, `run.sD1cPM`, acquired its slot
after 240 seconds and armed 1,108 contracts. It received TERM and exited
143 during canonical fixture population, before the first test completed.
The census at `36aa40b8b` calls this a forbidden full gate. The exact command
was the authorized `bin/test-fast --paths ... -- my.program-test
my.program-query-test seon.issue-generate-test`; `bin/test-fast:13–14`
executes `bin/test --fast` for path selection. The log itself reports
`role= test-fast` and `worker= test-fast`. The attribution is recorded in
[test-fast-path-snapshots-are-misclassified-as-full-gates.md](../../../seon/issues/test-fast-path-snapshots-are-misclassified-as-full-gates.md).

The lane cancelled queued `run.Du2dLa` while reading the new census cap,
then cancelled its queued successor before testing a changed checkpoint
that removes the duplicate override query. Neither launched a test JVM or
produced a test result. Their launchers removed their own snapshots. No
other lane's process or root was operated.

The final history check found item 2 already landed at `5a5359205`:
`required-program-relations-name-the-surviving-referrer` in
`test/seon/db_test.clj` checks both namespace/function and schedule/function
retractions, names the surviving referrer and asserts the basis is unchanged.
This lane does not duplicate it. `src/seon/sci/eval.clj` and
`src/seon/program.cljc` still have concurrent edits. Items 3–4, including the
exact interception hunk, are subsequent work and are not claimed here.
Until the seam-B backstop lands, future operations can
only refuse from `breaks` and must retain the dispatch/apply/macro unknowns.

The live SCI observation precedes the final render-datom and as-of metadata
refinements; those refinements have canonical fixture evidence above, not a
second live SCI evaluation. The one permitted disposable evaluation was used.

Owned paths for this checkpoint: `src/my/program.clj`, `src/seon/issue.clj`,
`resources/seon/schemas/my.program.edn`, `resources/seon/schemas/seon.program.edn`,
`test/my/program_test.clj`, `test/my/program_query_test.clj`, `AGENTS.md`,
the refactoring specification's §10 correction, this landing note, and the
three linked issue notes for prospective issue rendering, test-fast census
classification and collection-bound attribute decoding.
