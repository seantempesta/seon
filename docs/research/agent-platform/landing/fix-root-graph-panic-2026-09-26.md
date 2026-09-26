---
type: landing
lane: fix-root-graph-panic-opus
date: 2026-09-26
---

# fix-root-graph-panic-opus — landing 2026-09-26

The root graph stopped because of a stale read. Nothing wrote the finding attribute. At turn start the system
turn rechecks root's earlier "read-only" evaluations. Five of those were actually writers: `prepare-merge!`,
`accept-merge!` and `write-back!`. Their saved reads listed four `:seon.fn.contract/*` attributes, which no store
installs. The witness `d/datoms` refuses an uninstalled attribute, and that refusal panicked root's turn step.

## Broken things first

1. **RESET NEEDED.** Default runs archive 6a8e46ec6. The root graph stays stopped until default adopts 90c542fd0
   (`bin/seon down && bin/seon start`). Root restarts. No stored data needs changing, because the fix filters the
   wedging evaluations at read time.
2. **Root was about to re-run writers as "generated reads"** (fixed in 90c542fd0). Before the fix,
   `seon.turn/read-only-evaluation?` promoted 15 of root's 21 latest evaluations on default. Five of them were
   `(program/proposals)`, `(def proposal (program/prepare-merge! …))`, `(def accepted (program/accept-merge! proposal))`,
   `(let [p (program/prepare-merge! …) a (program/accept-merge! p)] …)` and `(time (program/write-back! …))`. A system
   turn would evaluate them again whenever their reads changed. The panic was the only thing preventing that.
3. **Two patches for root** (the files are not mine):
   - `tmp/fix-root-graph-panic/db-witness.patch` changes `src/seon/db.clj` and `test/seon/db_test.clj`.
   - `tmp/fix-root-graph-panic/stopped-graph.patch` changes `src/seon/oversight.clj`, `resources/seon/schemas/seon.oversight.edn` and `test/seon/oversight_test.clj`.

   `bin/seon status` fails whole while any agent graph is stopped, until the second patch lands.
4. **Not done: root's add-on (delete the Throwable special case at `fn.clj:1053`).** That line exempts `Throwable` and
   `Error` from the rule "a body using a class outside SCI's default classes is host-bound". The rule tests
   `sci.opts/default-classes`, not what the interpreter can resolve. `java.lang.Throwable` is not in
   `default-classes` (sci `src/sci/impl/opts.cljc:101-118`). Deleting only this line therefore makes every body
   with `(catch Throwable …)` or `(Throwable. …)` host-bound. That is a regression, and `fn_test.clj:3038`
   (`throwable false`) would turn red. The only reader is that line. Options, simplest first:
   - (a) Leave it until audit #1.
   - **(b, recommended)** Delete the whole java-class clause (`fn.clj:1049-1055`, and the `sci.impl.opts` require),
     because `{:classes {:allow :all}}` resolves every class. This changes host-bound facts for every first-party
     function that uses a class (clock, constructor, literal and hinted in `fn_test.clj:3034-3043` flip to
     false), so it needs a ruling on branch interpretation cost.
   - (c) Delete only the line and accept Throwable bodies as host-bound.

## Defect 1: the program-attribute set named four uninstalled attributes (reproduced; fixed 367871c45)

- **Cause.** The report schema `:seon.fn.contract/finding-row` (`resources/seon/schemas/seon.fn.contract.edn:21`) declared
  `{:seon.program/partition :seon.program}` in the 8a069b5e4 sweep without `:seon.db/attributes`. It is a value that
  `seon.fn/contract-findings` returns, not a stored entity. `seon.program/program-attributes` (`program.cljc:38`) takes every
  partitioned schema's entries, so on default it returned 169 attributes. Four of them were uninstalled:
  `:seon.fn.contract/{finding,position,form,caller-count}`. It is the only partitioned schema without
  `:seon.db/attributes` (checked over the whole projection).
- **Evidence.** Root's evaluations 35556, 36283 and 36309 (the merge work) hold read evidence 36304 etc. whose
  attribute set is exactly that 170-attribute set.
- **Fix.** Removed the property. Checked at the REPL: the edited `finding-row` validates real `contract-findings` rows.
- **Regression.** `seon.fn-test/every-program-attribute-is-installed` checks that the program attributes minus the
  installed schema is `[]`.
- **Model error.** A report value was marked as a program entity. `program-attributes` should also read only
  `:seon.db/attributes` schemas, matching `seon.schema/assert-entity-partition!` (`schema.clj:1452`). That change is in
  `program.cljc`, which is not mine, and it is optional now that no such schema remains.

## Defect 2: writers promoted to generated reads (reproduced; fixed 90c542fd0)

- **Cause.** `read-only-evaluation?` (`turn.clj:2002`) inferred "read" from what was absent in the agent's own database:
  no receipt and no effect. Merges and test runs write to other branches, and write-back writes files, so none of
  them leaves a receipt here.
- **Evidence.** Grouping the promoted evaluations' reads by connection id showed which ones read other values:
  - `(def proposal (program/prepare-merge! …))` read `:merge-b90fd4f9b09e` (181), two `:agent-*` branches (478 and 7), `:db` (16) and 27 uncommitted values.
  - `(def accepted (program/accept-merge! proposal))` read `:db` (11) and 4 uncommitted values.
  - `(time (program/write-back! …))` read 4 uncommitted values.
- **Fix.** A generated read now requires every read to name this database's committed connection
  (`:datahike.cache/connection-id`). On default this keeps 10 plain reads and drops the 5 writers.
  It adds about 1 ms to the existing 24 ms pass over 21 evaluations.
- **What this also does.** It unwedges root: the evidence that names uninstalled attributes belongs only to the
  dropped writers, so `system-plan` no longer reaches the witness.
- **Regression.** In `seon.rereads-test/failed-evaluations-are-not-promoted-and-documentation-follows-its-evidence`,
  a read naming another branch, or no committed connection, is not read-only.
- **Remaining gap.** A form that writes another branch without reading it, and reads only its own database, is still
  promoted. A positive proof would record every transaction made during the evaluation, and that crosses owners.

## Defect 3: the witness disagrees with the query that retained it (reproduced; patch for root)

- **Cause.** `seon.db/index-pattern-change` (`db.clj:1055`) witnesses a retained pattern through `d/datoms`, which
  resolves the attribute and throws for one the schema lacks (datahike `db/utils.cljc:177-190`). The query that
  retained the pattern matches through `datahike.db.interface/search` (`db/interface.cljc:77`, `db/search.cljc:186-211`),
  which does not validate the attribute and returns nothing.
- **REPL proof** (JVM, read-only, default's own db):
  - `(d/q '[:find ?e :where [?e :seon.fn.contract/finding ?f]] db)` returns 0 rows.
  - `(d/datoms db :aevt :seon.fn.contract/finding)` throws "Bad entity attribute … not defined in current schema".
  - A real `:seon.fn/doc` read's evidence, widened with the uninstalled pattern and checked after a `d/with` doc write:
    `read-evidence-changes` throws today; the `search` witness answers `[:seon.fn/doc]`.
- **Fix (patch).** `(first (dbi/search changes [entity attribute value]))` replaces the index `cond` and the value
  filter, a net 3 lines removed. Over 200 distinct real retained patterns on default, the two witnesses agree on
  every pattern (4 changed, 196 unchanged), and the new one takes 5.5 ms against 10.3 ms. `search` memoizes only when the db
  config has `:cache-size` (`search.cljc:19-26`), and default's has none.
- **Regression (in the patch).** `seon.db-test/a-pattern-over-an-uninstalled-attribute-witnesses-no-change`.

## Defect 4: one stopped graph fails the whole status (reproduced; patch for root)

- **Cause.** `oversight/agent-story` (`oversight.clj:100`) and `plumbing-story` call `flow/ping`, which throws
  "flow not running" on a stopped graph (core.async `flow/impl.clj:70`). See `operator-status-20260926T101500.581.edn`.
- **REPL proof.** A never-started graph has `(:chans (datafy g))` `{}`. A started one has `(:ins :outs :error :report)`, and after
  `stop` it is `{}` again, while ping throws. On default the cluster graph is running and root's agent graph is stopped.
- **Fix (patch).** `graph-procs` datafies once:
  - If the graph has channels, it pings.
  - If not, it answers `:stopped` for each proc. `:stopped` joins the `:seon.oversight/ping` enum.
  - The agent story reads "stopped" ahead of "mid-turn", because a stopped graph's open turn is not work.
  - Plumbing text prints the ping name instead of "unknown".
- **Remaining gap.** A graph that stops between the datafy and the ping still throws. Flow offers no non-throwing ping.
- **Regression (in the patch).** `seon.oversight-test/a-stopped-graph-is-observed-as-stopped`.

## Item 3: the "no analysis of effect" panic

- **Verified fixed by 71d3d74c5.** The log's panic traces through `turn.clj:3483 fault!`, which is the code before that
  commit.
- **Still open, recorded in the existing issue** (d5a72319c):
  - `(do (ns … (:require [seon.effect :as effect])) (defn … (effect/request! …)))` in one form still refuses as
    "no analysis of effect". The refusal is now returned to the agent, not a panic, and it carries a
    `seon.sci.kernel/failure-value` contract finding.
  - As two forms it passes analysis. It then hits `seon.test.accretion/auto-check` refusing its own output
    (`:seon.test.accretion/explanation` nil).

## Commits (net lines)

| commit | what | net |
|---|---|---|
| 367871c45 | fn.contract finding-row is not a program entity, plus regression | +14 |
| 90c542fd0 | turn: generated reads read only this database, plus regression | +26 |
| d5a72319c | issue evidence | +21 (docs) |

Source: turn.clj +17 −3, schema −0 (one property removed), tests +26. Patches: db.clj −3 net, oversight.clj +8 net.

Static checks: `bin/test-check` ran both, and both passed (`reused 1`). They check the running archive, not these
edits. Proof of the edits instead: REPL validation of the edited schema against real rows, and clj-kondo with 0
errors. No default Vars were redefined. All probes ran in the scratch namespace `fix-root-graph-scratch`. Branch
`fix-root-graph-panic` was created and retired.

## Root runs

1. `git apply tmp/fix-root-graph-panic/db-witness.patch tmp/fix-root-graph-panic/stopped-graph.patch`, then commit.
2. RESET NEEDED: `bin/seon down && bin/seon start`.
3. Run each focused test:
   - `bin/test-check default --test seon.fn-test/every-program-attribute-is-installed`
   - `bin/test-check default --test seon.rereads-test/failed-evaluations-are-not-promoted-and-documentation-follows-its-evidence`
   - `bin/test-check default --test seon.rereads-test/stale-read-with-equal-value-refreshes-evidence-without-emitting` (plain reads still refresh)
   - `bin/test-check default --test seon.db-test/a-pattern-over-an-uninstalled-attribute-witnesses-no-change`
   - `bin/test-check default --test seon.db-test/an-unnarrowed-reads-witness-is-the-first-transaction-after-its-basis`
   - `bin/test-check default --ns seon.oversight-test`
4. Check that MCP `eval_clj` in SCI mode answers `(+ 1 1)`, and that `bin/seon status` answers.
