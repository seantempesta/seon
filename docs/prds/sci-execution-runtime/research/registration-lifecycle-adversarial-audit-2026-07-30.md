---
type: research
status: complete
tags: [sci, program-graph, schema, datahike, audit]
---

# Registration lifecycle adversarial audit — 2026-07-30

## Verdict

The wave through `913f8177c` fixes most of the previously observed
representation defects, but it is **not ready to call perfect**. Four blockers
remain. Two are direct regressions against the owner's real-REPL ruling, one
silently drops build-time tests, and one removes Datahike attributes still
owned by surviving global schema rows.

The strongest landed parts are genuinely strong:

- `seon.program` is now the one pure owner of program identities and exact row
  attributes (`src/seon/program.cljc:4-31,66-128`). Build and runtime retain
  separate explicit function admission policies rather than storing a
  durability flag (`src/seon/program.cljc:96-128`).
- Schema identities are global. Their canonical row has only
  `:seon.schema/key` and `:seon.schema/form`; it has no namespace ref
  (`src/seon/program.cljc:22-26`). Runtime and restart tests independently
  assert that absence (`test/seon/cluster/turn_test.clj:972-973`;
  `test/seon/cluster/program_restart_test.clj:154-155`).
- Namespace facts now preserve effective resolver inputs: bare loaded targets,
  local-to-target aliases, and local-to-qualified-target refers
  (`resources/seon/schema/program.edn:41-76`). The maintained SCI fork exposes
  and installs the same exact maps (`reference-code/sci/src/sci/core.cljc:
  684-738`). This closes renamed refer, multiple alias, `:as-alias`, ordinary
  require, refer-all, authored-target ordering, and alias-cycle cases covered by
  the landed focused suites.
- Runtime schema registration and removal use an isolated registration delta.
  `unregister!` refuses outside that delta and only stages `dissoc`
  (`src/seon/schema.cljc:997-1014`). Evaluation validates the staged result
  without mutating the process-global candidate population
  (`src/seon/sci/eval.clj:722-775`), and installation derives the projection
  from the successful terminal transaction's `db-after`
  (`src/seon/sci/eval.clj:430-514`).
- Current-data fences cover direct, reverse-transitive, and entity-child
  attributes, and run inside the terminal transaction
  (`src/seon/cluster/run.cljc:561-640,664-709`). Schema and function dependency
  blockers derive from the immutable projection, not a hand list
  (`src/seon/schema.cljc:299-346,1816-1854`).
- The history ruling is honest. Ordinary historical datoms and the historical
  `:seon.schema` row survive current removal; a projection rebuilt at the same
  basis validates the old value. Datahike's `AsOfDB` does not reconstruct the
  old physical schema, and `:db/noHistory` deliberately destroys the old
  value. The recurring proof is at
  `test/seon/schema_usage_guard_test.clj:292-353`.

Independent gates run for this audit:

- registration/index/runtime selection: **102 tests / 668 assertions / 0
  failures / 0 errors**;
- maintained SCI namespace focus: **37 / 151 / 0 / 0**; and
- maintained Datahike schema focus across `clj-hht`, `specs`, and `clj-pss`:
  **51 / 351 / 0**.

Those green results do not cover the blockers below.

## Blocker 1 — removing a composite schema uninstalls surviving leaf attributes

`schema-attribute-change-tx` computes physical ownership from only the
*affected forms*. It retracts every installed Datahike attribute mentioned by
those forms, then redeclares only attributes mentioned by those same forms in
the candidate (`src/seon/cluster/run.cljc:593-615`). That is wrong for a
composite/entity schema: its map entries reference independently registered
global leaf schemas. Removing the composite does not remove those leaf schema
rows, so it must not remove their Datahike attributes.

The shortest production-transaction probe registered:

```clojure
{:audit.remove/id [:string {:seon.db/identity true}]
 :audit.remove/child [:int {:seon.db/index true}]
 :audit.remove/entity
 [:map {:seon.db/entity true}
  [:audit.remove/id :audit.remove/id]
  [:audit.remove/child :audit.remove/child]]}
```

It then submitted the ordinary typed deletion of only
`:audit.remove/entity` through `seon.cluster.run/program-row-tx`. Exact output:

```clojure
:before {:audit.remove/id {...}, :audit.remove/child {...}}
:after {}
:rows {:audit.remove/id "[:string ...]",
       :audit.remove/child "[:int ...]"}
```

Both leaf rows survived while both physical attributes disappeared. The next
write through either surviving global schema therefore refuses as an
uninstalled attribute. This is an inconsistency created by a successful
terminal transaction, not a display problem.

The same defect affects a composite replacement that stops mentioning one
still-registered leaf. The existing entity test changes a constraint while
retaining both entries (`test/seon/schema_usage_guard_test.clj:222-275`), so it
cannot expose the ownership error.

The unifying repair is to diff the **complete current and complete candidate
physical Datahike declarations**, keyed by attribute. Retract only declarations
absent or physically changed in the complete candidate; declare only absent or
changed candidate declarations. Do not infer physical ownership from the
changed Malli form's references. That also removes the current needless
retract/redeclare churn for unchanged affected attributes.

## Blocker 2 — whole-reply static reading is not a real REPL

`seon.cluster.reply/sources` reads the whole model reply before any form runs.
The reader tries to predict later reader state with
`next-reading-context`, but it advances only literal `ns`, literal `in-ns`,
and literal standalone `require` shapes (`src/seon/sci/reader.cljc:377-429`).
No static recognizer can reproduce arbitrary Clojure evaluation.

Two shortest probes against the actual reply surface:

```clojure
(alias 'str 'clojure.string)
{:x ::str/value}
```

and

```clojure
(require (if true
           '[clojure.string :as str]
           '[clojure.set :as str]))
{:x ::str/value}
```

Both returned:

```clojure
{:seon.error/kind :seon.cluster.reply/unreadable,
 :seon.error/message "Alias `str` not found in `:auto-resolve`", ...}
```

An ordinary REPL evaluates the first form before reading the second, so both
are valid. The static literal-require regression at
`test/seon/sci/reader_test.clj:438-449` proves one approximation, not REPL
semantics.

This is also archaeology already paid for. Commit `e176eb469` implemented
`in-ns`, `alias`, `ns-unmap`, `ns-unalias`, durable standalone require, and
`:as-alias` as real REPL verbs, with 471 lines of focused verb tests. The new
reader restored only the literal require subset. The gap therefore confirms
the owner's warning that this has been built before and history must constrain
the redesign.

The unifying repair is not another branch in `next-reading-context`. Read one
form under the current SCI namespace state, evaluate/settle it, then read the
next form under the resulting state—the actual read-eval loop. The crash model
already closes an interrupted run and never executes its suffix, so correctness
does not require semantically reading every future form before the first form
runs. If the plan needs an immutable identity, persist the reply source/digest
and exact consumed spans; do not freeze reader-resolved future forms using a
state that does not exist yet.

## Blocker 3 — qualified or dynamic `ns-unmap` bypasses durable deletion

Schema deletion is recognized by resolved operator identity in the reader
(`src/seon/sci/reader.cljc:319-326`). Function/test deletion is not.
`seon.program/deletion-row` matches only raw first symbol `ns-unmap` and
literal quoted namespace/name arguments (`src/seon/program.cljc:144-174`).

The direct falsifier:

```clojure
(program/deletion-row
 (first (reader/read
         {:seon.sci.reader/text
          "(clojure.core/ns-unmap 'a 'f)"
          :seon.sci.reader/ns 'user})))
;; => nil
```

The SCI form still executes, so it removes the live Var while leaving the
`:seon.fn`/`:seon.test` rows current. Fresh acquisition resurrects the deleted
declaration. A conventional dynamic namespace argument such as
`(ns-unmap (find-ns 'a) 'f)` has the same split, even if the raw operator
recognition is repaired.

This violates both exact operator identity and the user's real-REPL deletion
ruling. At minimum the reader must lift a resolved `clojure.core/ns-unmap`
event, as it already does for schema unregister. Full REPL semantics need a
staged namespace delta (or an SCI fork whose exact namespace delta is derived)
so dynamically evaluated namespace operations commit their corresponding
program facts before the supplied context is materialized. Literal source
matching cannot be the database authority for an effectful REPL verb.

## Blocker 4 — build indexing can still silently drop tests

After a namespace-changing form whose result is knowable only by evaluation,
the reader correctly clears attribution (`src/seon/sci/reader.cljc:391-397,
422-426`). The indexer's loud accounting checks only function evidence:
`:seon.fn/arglists` without `:seon.fn/sym`
(`src/seon/fn.clj:30-55,149-157`). A test has no corresponding independent
marker after attribution is cleared.

The shortest reader falsifier is legal Clojure:

```clojure
(ns a)
(in-ns (symbol "a"))
(clojure.test/deftest t)
```

The third event contains neither `:seon.sci.reader/ns` nor
`:seon.test/sym`; `seon.fn/rows` consequently returns normally with no test
row. Function coverage is independently cardinality-preserving
(`test/seon/fn_test.clj:192-242`), but there is no equivalent independent
schema/test census. The sample parity tests therefore cannot establish “no
silent drops” for every declaration family.

The fix should generalize the reader's declaration-accounting signal: every
recognized declaration occurrence carries its family and either a canonical
identity or a refusal reason. Indexing refuses any occurrence lacking an
identity. The recurring census must independently count functions, schemas,
and tests with multiplicity; it must not consume the production reader's own
lifted declaration facts as its oracle.

## Important follow-up — the Datahike fork only fences indexed attributes

**Resolved after this audit:** maintained Datahike commit `b73550bf` now runs
the AEVT current-data fence for every attribute when `:db/ident` is removed,
not only attributes carrying `:db/index`. The focused `clj-hht`, `specs`, and
`clj-pss` schema suites prove indexed and non-indexed removal refusal, removal
after current retraction, and retained history: **51 tests / 372 assertions / 0
failures**. Root commit `6119cd036` advances the vendored pointer.

At audit time, the maintained fork's AEVT check was real and its focused test
was green, but it ran only when the schema had `:db/index`
(`reference-code/datahike/src/datahike/db/transaction.cljc:288-305`). Direct
`retractEntity` of a nonindexed schema with current data retains Datahike's old
unsafe behavior. Seon's terminal semantic guard covers every derived Malli
attribute, so this does not bypass the ordinary agent registration path.
Nevertheless, the dependency-level invariant is narrower than “schema in use
cannot be removed.” Since Datahike is maintained as part of Seon, either widen
the fork's `:db/ident` removal check to every attribute or document and enforce
that all schema removal enters the single Seon transaction owner.

## Corrected out-of-wave finding — the live JVM belonged to the next generation

The earlier audit diagnosis that generation 1430 left JVM PID 6259 orphaned
was not supported. It compared the mutable `latest.report.edn` with a live JVM
without also reading `hook/running.edn`, the worker identity, and the JVM start
instant. The coalescing worker publishes one completed generation and then
immediately claims the pending generation. A completed exit-124 report can
therefore coexist for minutes with the next generation's live, owned JVM.

A source-frozen observation of that exact boundary falsified the orphan claim:

- worker PID 10281 started at `2026-07-30T02:09:43.031Z` and remained alive;
- `latest.report.edn` recorded completed generation 1443 at
  `2026-07-30T02:17:30Z`;
- `hook/running.edn` recorded generation 1451 at the same second, and its JVM
  PID 12750 started one second later at `2026-07-30T02:17:31Z` as PID 10281's
  direct child;
- at generation 1451's exact 300-second boundary,
  `2026-07-30T02:22:31Z`, PID 12750 exited and `latest.report.edn` advanced to
  generation 1451; and
- the same worker then claimed pending generation 1455 and launched its owned
  JVM PID 14408 at `2026-07-30T02:22:32Z`, again one second after the prior
  report.

The current timeout path therefore terminated its JVM before publishing and
the live JVM after publication belonged to the next request. PIDs 6259 and
11796 cannot support the earlier diagnosis because their process start
identity was never compared with the reported and running generations. PID
11796 was mistakenly terminated while a valid later generation was running.
There is no demonstrated orphan defect, no lifecycle change is justified, and
the resolved CLJS-era issue
`docs/seon/issues/archive/changed-test-interruption-orphans-test-runner.md`
must not be reopened from this evidence.

## Calibration and acceptance boundary

The effective-binding representation itself should be kept. It is simpler and
more faithful than reconstructing require syntax, and the vendored SCI API is
the right dependency seam. The global schema model, isolated Malli delta,
dependency graph, current-data fences, historical projection, and honest
`noHistory` exception should also be kept.

The wave can close after all four blockers have recurring proofs:

- removing/replacing a composite never uninstalls a physical attribute still
  derivable from any surviving global schema;
- alias, dynamic require, `ns-unalias`, and reader-sensitive later forms work
  in one reply exactly as sequential REPL input;
- qualified and dynamically targeted `ns-unmap` cannot diverge live SCI state
  from committed function/test facts; and
- an independent per-occurrence build census proves no function, schema, or
  test is dropped silently.

Then rerun the root 102/668 matrix, the SCI 37/151 focus, the Datahike 51/351
focus, and the reset-boundary restart proof.
