---
type: research
status: implemented; orchestrator review pending
created: 2026-09-17
tags: [write-admission, schema, datahike, seon.db]
---

# F2 — validate the resulting entity at the writer

## Finding

The partial-map refusal is real. Raw adds and transaction-function output
receive less Malli validation. Moving the existing whole-map check into a
transaction function fixes the simple schedule update, but **a per-map merge
does not meet F2 for the complete admitted transaction grammar**. The correct
subject is the logical entity after all operations, with Datahike's own
resolved entity ids and attempted datoms. Recommend exposing that final
report to one Seon validator before the writer admits it to commit. This is
a dependency-seam change, not the small map-only change originally proposed.

The research through “Initial research verification” was committed at
`71a237559` before production edits. The orchestrator subsequently selected
option 2 under F8. The implementation and its verification boundary are
recorded below: the fork suite passes; the Seon runner currently refuses to
execute because default lacks the destructive-owner facts it requires.
This is ready for code review, not a green Seon gate.

## Grounding and dependency ledger

Read the requested PRD §1e F2 and §4b first, then owner-decisions Part 1.3
and decision 6, batch B §6 including the exact refusal, the open issue,
AGENTS.md §§0–5 and 7 supplied in the assignment, and
`tmp/orchestrator/wave2/repl-rule.txt` end to end. Read the program-facts PRD
end to end. Read the complete admission section from `write-entity-schemas`
through `transact-call`, including recursive reference validation, retention
snapshots/checks, and the before/after transaction functions. Read Datahike's
`db/transaction.cljc` and `writer.cljc` end to end, the schedule seed's whole
function/docstring, Malli's map explainer, and the bridge's transaction codec.
Applied data-oriented-clojure, datahike, repl, and clojure-testing skills.

Point-in-time source observations during research: Seon HEAD
`12e462289edf73b096ba6d8572c567f5fbcf5d62`; Datahike checkout
`49ea59331dff86caaa587ada215f85f4d322d7dd`; Malli checkout
`3517a3cd9271b2083780ac7be1725493905bca2e`. Other lanes changed HEAD and
protected files while this read-only source investigation ran. Line references
below are those opened in that investigation.

| Mechanism | Dependency source | Existing first-party use |
|---|---|---|
| Identity upsert, including conflicting identities, ref tempids and composite tuples | `reference-code/datahike/src/datahike/db/transaction.cljc:640–716`, `:956–981` | `src/seon/db.clj:3036` passes native transaction data to Datahike |
| Transaction functions receive the current transient database and splice returned operations | same file `:1152–1153`, `:1230–1240`, `:1306–1307` | `src/seon/schedule.clj:72–109`; `src/seon/db.clj:3003–3015` |
| Attempted versus effective datoms, resolved entity ids | same file `:586–625`, `:1242–1259` | transaction report returned by `src/seon/db.clj:3049–3051` |
| Writer rejects thrown operations before commit admission | `reference-code/datahike/src/datahike/writer.cljc:148–218` | `src/seon/db.clj:3052–3064`; `src/seon/error/refusal.clj:4–26` |
| Required map keys inspect the supplied value, not a database | `reference-code/malli/src/malli/core.cljc:1291–1299` | `src/seon/db.clj:2874–2887` |
| Logical/storage codec, including nested transaction functions | `src/seon/schema/datahike.clj:455–543`, `:560–576` | `src/seon/db.clj:3038–3040` |

Archaeology also found an earlier instance of the proposed before/after
construction failing because the captured transaction database has transient
indexes: [the resolved issue-test guard investigation](../../../seon/issues/archive/issue-test-guard-before-value-and-activation-are-not-stable.md).
Its proposed final-report alternative explicitly says not to re-evaluate
transaction functions to discover their effects. The current retention owner
materializes small immutable membership maps instead (`db.clj:2926–2970`).

## (a) Current validation by grammar

The armed outer contract admits vectors of maps or operation vectors, or a
map carrying such a `:tx-data` vector and optional `:tx-meta` map
(`resources/seon/schemas/seon.store.edn:34–43`). It is a container contract,
not a contract for each entity or operation's arguments.

| Input | Seon validation today | Remaining dependency validation |
|---|---|---|
| Entity map | Every supplied attribute, installed attribute check, recursive reference maps/lookup values, cardinality normalization; then every attribute-bearing entity schema selected by a required identity present in this map | Native schema declarations, value storage types, nil rejection, reference/tempid resolution, uniqueness, upsert conflicts, tuple rules |
| `[:db/add e a v]` | The entity lookup reference's attribute/value, then the supplied attribute value; cardinality-many validates one member, not the resulting collection | Native add/value/reference/uniqueness rules |
| `[:db.fn/call f ...]` | No `write-error` pass over its output. The codec wraps explicit vector calls recursively and validates logical EDN-backed slots while encoding them; it does not validate native slots or whole entities | Executes `f`, processes all returned native operations, and propagates throws |
| CAS, retract, retractAttribute, retractEntity and other admitted operation vectors | No branch in `write-error`; no resulting-entity check | Native syntax, storage-type, CAS, tuple, schema and reference rules |

Sources: `db.clj:2680–2699`, `:2751–2831`, `:2849–2911`;
`schema/datahike.clj:483–523`; Datahike `transaction.cljc:35–53`,
`:735–778`, `:983–1005`, `:1053–1170`, `:1269–1317`.

The map's schema is selected only from identity attributes actually supplied
as map keys (`db.clj:2851–2858`). A map with only `:db/id` and a changed field
also avoids the entity-schema pass. A raw add never checks required sibling
keys or collection-wide constraints. A function returning a native string
that satisfies Datahike's string type but violates Malli's `:min` can bypass
even the native attribute's Malli restriction. These are distinct paths of
the same incomplete-admission class, not acceptable update interfaces.

## Exact sighting and live reproduction

Batch B §6 records these bytes from batch 67:

```text
ERROR in (root-owned-portfolio-initializes-as-queryable-schedule-facts) (test_support.clj:250)
Uncaught exception, not in assertion.
expected: nil
actual: clojure.lang.ExceptionInfo: Fixture write was refused at the write: seon.db/transact! refused transaction data at [0 :seon.schedule/zone-id]: expected the required key :seon.schedule/zone-id with a string, got a map missing :seon.schedule/zone-id. Fix: Supply :seon.schedule/zone-id with a string. Offending row 0: #:seon.schedule{:id "root/maintenance/footprint-schedule", :expression "7 4 * * *"}.
```

MCP JVM read-only reproduction on default, PID 53320, returned:

```clojure
;; Existing entity, read through seon.db/pull:
{:db/id 47232
 :seon.schedule/id "root/maintenance/footprint-schedule"
 :seon.schedule/expression "0 2 * * *"
 :seon.schedule/zone-id "UTC"}

;; write-error on the partial map:
{:seon.error/kind :seon.db/invalid-write
 :seon.db/attribute :seon.schedule/zone-id
 :seon.db/path [0 :seon.schedule/zone-id]
 :seon.db/offending :seon.error/unknown}
;; write-error on [[:db/add -1 :seon.schedule/id "f2-incomplete"]]: nil
;; write-error on a call returning [{:seon.schedule/id "f2-incomplete"}]: nil
```

The nils above mean **no admission failure**, not successful transactions;
these were read-only calls of the admission function, not committed writes.
The map refusal's complete message matched the quoted text through the fix
sentence. The fixture adds the final “Offending row” sentence. Raw MCP output
was retrievable under digest
`b1ff3cbf82e87c319419424b8c6747e76ffd12b7a64e737816b5a3f7e05c957e`
(6,668 bytes); the adjacent committed probe records the exact executable form.

## (b) What validating all inputs requires

Keep early per-attribute checks, including recursively supplied reference
maps. Validate every attempted add's logical value, including function output,
CAS and idempotent assertions. For every identity-bearing entity those
operations affect, validate its **resulting logical entity**, including
required keys and full cardinality-many collection constraints. The outcome
must be independent of whether equivalent facts came from a map, datoms or
a transaction function. Mixed transactions are one unit: later operations may
complete an entity, and any invalid final entity aborts every earlier write.
Retractions must not be an alternate route to an incomplete retained entity.

Do not merely pull `'[*]` and hand storage strings to Malli. Decode EDN-backed
attributes through the existing bridge, normalize references and collections
through the existing write normalization, and use the explicitly carried
projection. Do not make a temporary invalid prefix of a valid transaction fail
as though it were the final entity. Do not replay `f` to discover its output.

Schema correctness is part of F2. The function entity schema requires
`:seon.fn/ns` and `:seon.schema.admission/source`
(`resources/seon/schemas/seon.fn.edn:99–110`), while retained identities may
have had definition facts removed by design. A default query found ten
function identities without `:seon.fn/ns`, including
`"seon.issue.opening/edit-recipe"` and
`"seon.operator/documented-request-keys"`. This query establishes missing
required attributes, not the provenance of every one of the ten. A stored
tombstone needs an explicitly valid schema alternative derived from its
attributes; do not make the normal definition keys optional to admit it, and
do not exempt raw datoms. Coordinate this with the protected program/schema
owners before enforcing the new invariant on publication.

## (c) Is the proposed merged-map transaction function easy and correct?

**For the single scalar schedule upsert: yes. For the full F2 contract: no.**
The schedule precedent is sound: its function/docstring says absence is
decided by the serial writer (`schedule.clj:72–78`). A transaction function
may resolve a scalar identity with the same AVET seek as `upsert-eid`, pull
its existing attributes, merge the supplied scalar changes, and throw a
diagnostic if that value fails. Malli uses `(find x key)` and emits
`::missing-key` for a missing non-optional key (`core.cljc:1294–1299`).
Moving the check corrects what value Malli sees; weakening Malli does not.

That construction alone is insufficient because:

1. Later maps/datoms may complete or invalidate the same entity. A per-map
   check sees a prefix, not the final state. Required-key checks after each
   individual add would reject normal multi-datom creation.
2. Cardinality-many map entries are additions. Ordinary `merge` replaces
   the supplied collection; Datahike's `explode` emits an add per member
   (`transaction.cljc:739–772`). The merged map is not generally the entity
   Datahike will store.
3. Native identity resolution also handles multiple conflicting identities,
   reference tempids and composite tuple identities (`:640–716`). Copying
   only the first AVET lookup is not equivalent to that authority.
4. A trailing transaction function receives only `db`, not the reducer's
   `:tempids` or attempted `:tx-data` (`:1152–1153`). Those values already
   exist on the report (`:586–625`, `:1238`, `:1289–1300`). Rebuilding them
   in Seon is a second transactor. Scanning history is insufficient for
   non-temporal stores, no-history attributes, idempotent assertions and
   purges. Capturing the transient `db` does not freeze a before snapshot.
5. Function output and other native operations need the same admission.
   The existing codec wrapper is not such a check. A final report seam
   naturally sees their resolved datoms without executing functions twice.

### Throwing rejects the transaction, not only the failing operation

`writing.cljc:872–890` calls `(core/with old tx-data tx-meta)` before
`complete-db-update`; `core.cljc:127–140` enters the reducer. Its transaction
function branch does not catch the exception. In `writer.cljc:148–183`,
the `try` around `(apply op-fn old args)` catches the exception, delivers it
to the callback, and returns `:error`. The deciding code is:

```clojure
;; writer.cljc:201–218, intervening queue-pressure/shutdown handling omitted
(not= res :error)
(do
  ...
  (if (>! commit-queue [res callback])
    (recur (:db-after res))
    ...))
:else
(recur old)
```

The error path neither enqueues a report nor advances `old`. The separate
commit loop reads only `commit-queue` and calls `w/commit!` before resetting
the connection (`writer.cljc:234–283`). Thus a validation exception prevents
this transaction's datoms from being committed, including operations already
processed within it. This guarantee concerns database transactions; arbitrary
external side effects performed by a user transaction function are not undone.
No such side effect is needed for admission.

## (d) Cost and flat refusal

The earlier **one AVET seek plus one pull per identity-bearing map** estimate
is correct for a simple scalar identity that resolves. It is not the cost of
general grammar parity: multiple identities need additional seeks, nested
entities need their own validation, and a literal merge misrepresents many
values. The recommended final-report seam needs no Seon upsert lookup: use
Datahike's resolved ids, deduplicate attempted/changed entity ids, and pull
each surviving subject once. Validate attempted logical values separately
so an invalid idempotent input cannot disappear from an effective-datom-only
report. Cost is proportional to attempted datoms plus affected entity sizes;
no timing claim is made before implementation/profiling.

Construct the refusal with `seon.error/diagnostic`, including identity,
attribute, offending value (typed missing value where absent), logical entity
and applicable schema. Throw `(ex-info (:seon.error/message failure) failure)`.
`transact-call` already reads the throwable chain via
`error.refusal/refusal` (`db.clj:3052–3054`). If the result carries
`:seon.error/kind`, it returns that exact flat value (`:3056–3059`);
dependency-classified `:error` uses `rejected-value` (`:3061–3064`).
Do not return a refusal map from a transaction function: Datahike expects
transaction data there. Do not write an error fact and commit partial data.

## (e) Three concrete options

1. **Constrain the grammar to independently complete entity operations.**
   Guarantee: merged scalar upserts can use local transaction functions and
   full validation, with unsupported composition explicitly refused. Cost:
   inventory and change callers of raw creation datoms, nested operations,
   temporary retractions and transaction functions. Give up existing
   transaction composition. Small admission implementation, substantial caller
   breakage; not recommended for the requested parity guarantee.
2. **Expose final reducer report validation (recommended).** Guarantee:
   every grammar shares the same final-entity check before commit; attempted
   values remain validated, including no-ops. Cost: a small explicit callback
   seam in the maintained Datahike fork, forwarded through its transaction
   API, Seon's validator/codec integration, and canonical rollback/parity
   regressions. Give up a Seon-only diff; requires dependency-owner review.
   Hand the callback its report and projection as values. It must run after
   tuple/retraction/function expansion and before the report is accepted by
   the writer, with no additional execution of transaction functions.
3. **Final transaction-function scan and input wrapping.** Guarantee: a full
   final scan can validate all surviving identity-bearing entities, with
   recursive wrapping also validating attempted inputs. Cost: database-wide
   work per write, output wrapping across every callable grammar, and schema
   correction for existing tombstones. Give up changed-entity-bounded work;
   no dependency edit, but materially more work on the ordinary write path.

Reject pre-read-only merging, caller pull/merge, weakening required keys and
datom exceptions: none provides F2's guarantee. The original conditional
implementation instruction is not satisfied by pretending option 2 is just
the proposed per-map transaction function. Ask for the cross-owner change
before production edits, as AGENTS.md §2.5 requires.

## Initial research verification

`bin/seon status` and MCP both observed default alive at PID 53320. No
default stop/restart, test JVM, `bin/test`, `bin/test-fast`, scratch cluster,
worktree, provider call or protected-file edit occurred.

Reloaded only `seon.db-test` through `#'seon.test/with-test-loader`, then ran
`seon.db-test/transaction-wrappers-cannot-hide-a-classified-refusal` through
the exact three-argument `seon.test/run` pattern, on a future with 180000 ms
and `seon.test.runner/provenance`. Result: **6 pass, 0 fail, 0 error**;
run entity **80770**, basis **536871723**, run-at
`2026-09-16T19:36:24Z` (machine clock; task/document date remains 2026-09-17).
The test includes a real turn transaction refusal but also an existing mocked
wrapper case; it is not a new F2 rollback regression. No claim is made that it
measured `:max-tx` atomicity. The dependency control-flow proof above establishes
where rejection occurs; the requested final implementation must add the
explicit before/after `:max-tx` regression on the canonical fixture.

The recorded result additionally says
`"Reach digest unavailable: Reach rows unavailable."` Its log names
`:seon.fn/references` as absent from the live schema. This is a verification
boundary, not a claim of a verified reach digest or a reason to alter the
other lane's schema. The research continued from direct source and live
admission observations.

At the research commit, pending work was: owner decision, implementation, canonical F2 regressions, all
non-destructive reaching tests and issue sighting tests, adopted-definition
proof, issue resolution, and orchestrator review before a cold gate. No gate
request is submitted for an unimplemented fix. All commands completed; the
test future completed and its `user/f2-baseline-run` Var was removed. No
filesystem scratch was created. The retained probe passes clj-kondo with
0 errors and 0 warnings (21 ms); `git diff --check` passed. The edit hook
reported 30 Markdown errors in historical files, including obsolete gitlink
citations in `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`;
the feedback was elided, so this is not a complete Markdown-clean claim.

## Approved implementation — final reducer report

The orchestrator explicitly selected option 2 under F8, preserving F2's
“validate ALL inputs; a failed validation rolls back the transaction.”
The maintained fork commit is **`73afe78271a289861da236c5ac3457e64349653f`**.
It is local to the seantempesta fork; the orchestrator must arrange its push
with the owner. No push or default restart was performed.

The callback travels through the existing transaction map's `:tx-meta`
under **`:datahike/validate-report`**. This uses `core/with`'s existing
metadata forwarding (`reference-code/datahike/src/datahike/writing.cljc:879–889`),
and consumes the process-local control before `flush-tx-meta` makes datoms.
It therefore needs no second transaction API, writer registry or persistent
function-valued configuration. The fork's existing `:datahike/expected-basis-t`
seam (`writing.cljc:873–889`) is the accretive writer-side control precedent;
the final cross-transaction valid-time check at `db/transaction.cljc:1267`
is the final-reducer validation precedent.

`db/transaction.cljc:1206–1218` implements the one optional callback.
`nil` accepts; any other returned value, including false, rejects by throwing
`{:error :transaction/validation-rejected
  :datahike/validation-refusal <the-value>}`.
The callback receives the finished report, plus `:datahike/attempted-tx-data`
containing assertions that may be idempotent. The public report still exposes
only effective `:tx-data`; neither that extra observation key nor the callback
is retained. Its private forwarding key survives native tempid retries and is
removed before the callback. The call is at `db/transaction.cljc:1276`, after
tuple expansion, transaction functions, retractions, persistent index
finalization and the tentative basis increment. It does not execute the
transaction again. Existing native tempid retry behavior is unchanged.

The writer's unchanged exception branch (`writer.cljc:148–183`) delivers the
throwable and sets `res` to `:error`. Its admission branch remains:

```clojure
(not= res :error)
;; ...
(if (>! commit-queue [res callback])
  (recur (:db-after res))
  ;; ...)
:else
(recur old)
```

(`writer.cljc:201–218`.) Therefore the rejected candidate never enters the
commit queue and the processing loop keeps the old database. No candidate
facts, basis increment, branch head or listener notification are committed.
This is database atomicity; a user-supplied transaction function's unrelated
external effects are not database writes the writer can reverse.

In Seon, `src/seon/db.clj:159` primes the callback on the connection's immutable
projection. `:2978` caches a closure over that projection; the callback never
fetches a connection, registry or schema projection. A later projection has its
own cache, so adoption cannot reuse a closure for an older projection.
`transact-call` (`:3092`) selects the connection's carried projection before a
caller-supplied fallback and attaches the acquired callback to the transaction
request. The codec fixture now explicitly carries its synthetic projection
(`test/seon/db_test.clj:311`), matching the production acquisition contract.

`write-map-error` (`db.clj:2852`) retains supplied-attribute and reference
pre-validation but no longer mistakes a partial map for a complete entity.
`write-report-error` (`:2944`) validates every attempted added value against its
attribute schema, including transaction-function output and idempotent adds.
Then it visits each distinct affected entity in the attempted/effective datoms.
`write-entity-value` (`:2895`) reads the final EAVT facts, decodes logical values,
and preserves reference ids without recursively expanding reference graphs.
`write-entity-error` (`:2913`) uses the same declared entity schemas as the former
map check, normalizing cardinality-many collections for Malli. Identities in
both the before and after database select the schema, so retracting a required
key or an identity while leaving the entity's other facts cannot evade it.
A completely retracted entity has no final row to validate. No required schema
key was made optional and no datom-only acceptance branch remains.

A refusal carries `:seon.db/entity` in diagnostic data, the attribute, offending
value (the existing `:seon.error/unknown` for a missing key), schema and path.
`transact-call` at `:3131` extracts the callback's refusal through the existing
cause-chain reader and returns that **same flat `:seon.error` value**.
The callback costs one scan of attempted/effective datoms plus before/after
EAVT entity reads per affected entity, and cached schema validation. It avoids
per-map AVET resolution and does not replay transaction functions. The cost is
per distinct affected entity, not per submitted partial map; schema/validator
compilation remains cached on the supplied immutable projection.

## Implementation verification and review boundary

The fork's own task was run from `reference-code/datahike`:

```sh
bb kaocha clj-pss clj-hht --focus datahike.test.writer-error-test --focus datahike.test.transact-test --focus datahike.test.tuples-test --focus datahike.test.upsert-test --focus datahike.test.attribute-refs.transact-test --reporter kaocha.report/dots
```

**94 tests, 614 assertions, 0 failures; exit 0.** Its new regression covers
history on/off, atomic mixed-write rejection, unchanged `:max-tx`, absent rows
and listener notifications, final tuple values, nested transaction-function
composition with a one-call counter, idempotent attempted datoms, native tempid
retry, and final retraction state. Earlier iteration found a test-only missing
`datahike.core/listen!` qualification, then four assertions incorrectly reading
the outer asynchronous exception; both were fixed before the green run.
This was the expressly authorized dependency test JVM, not a Seon test JVM.

The canonical regression is
`test/seon/db_test.clj:1479`,
`all-transaction-grammars-validate-the-resulting-entity`. It covers all six
requested cases plus rejection of a required-key retraction. It uses
`with-database` and `transacted!`, checks the changed value and retained key,
compares basis before/after refusals, checks both mixed entities absent,
asserts entity/key/offending evidence, and checks nested completion runs once.
No alternate fixture or unarmed test harness was introduced.

All changed runtime forms were evaluated as individual definitions in default;
no dependency namespace or test-support reload was used. The retained
[final probe](write-admission-final-probe-2026-09-17.clj) includes the exact
reducer reload, immutable live-data check, reach query and three-argument
canonical run. Its live reducer result was:

```clojure
{:f2/partial-retains-zone true
 :f2/entity {:seon.schedule/id "f2-incomplete"}
 :f2/refusal {:seon.error/kind :seon.db/invalid-write
              :seon.db/attribute :seon.schedule/expression
              :seon.db/offending :seon.error/unknown}}
```

This proves the loaded validator's decision over real logical database values;
it does not claim a canonical fixture run or Seon writer atomicity measurement.
The latter is covered by the committed regression awaiting admission to run.

The existing refusal regression and the new regression were attempted with
180000 ms and `seon.test.runner/provenance` on futures. Before/after source edits,
only `seon.db-test` was reloaded through `#'seon.test/with-test-loader`.
Every implementation-stage attempt returned the same pre-execution refusal:

```text
No function in this program declares :seon.fn/destroys, so an in-process run cannot tell whether a test deletes a filesystem path it did not create. Republish the program (bin/seon init --dev default), or declare the attribute in the owner's own metadata at its definition.
```

The value is `{:seon.error/kind :seon.test/unknown,
:seon.test/unknown ":seon.fn/destroys", :seon.test/next-tier :none, ...}`.
A live Datalog query independently returned **zero** destructive-owner rows.
This is the already-recorded foreign boundary in
[the fault-evidence issue](../../../seon/issues/fault-evidence-tests-pull-a-fault-entity-that-no-longer-carries-its-evidence.md).
Its owner is the concurrent `src/seon/fn.clj`/`src/seon/test.clj` publication;
no protected file or another lane's session was changed to bypass it.

The live `tests-reaching` queries select **1028**, **1028**, and **911** tests
for `transact-call`, `write-map-error`, and
`carry-connection-projection-state!`, respectively; their union is **1028**.
The old default still declares function identities as strings, so those live
calls used the declared string argument. New helper identities are not yet in
that published graph. The complete `seon.db-test` namespace, that reaching
selection, and the issue sightings (`seon.turn-test`,
`seon.maintenance-schema-test`, `seon.config-test`) remain unexecuted: the runner
cannot classify any as safe while its required owner facts are absent.
No unavailable observation is counted as a passing test. The explicit ban on
Seon test JVMs also rules out the usual isolated-worktree cold fallback here.

Publication has not converged. The first own edit-hook publication
`cf92291f-30e4-4691-8556-411c00f4bf99` ended with operator exit **124**.
An explicit own `init --dev default --changed src/seon/db.clj --changed
test/seon/db_test.clj` waited over 480 seconds for the lifecycle lock held by
other publications; the redundant waiting process was then ended by exact PID
23605. No holder or default process was signalled. Last observed source ids:
**adopted `6aaaeabe-d99a-5eec-9e3e-9d254e71c9c6`**, published
**`6aaaf664-0ecd-5fae-a037-421814f727d6`**. Thus the live prototype is a
hot-evaluated-Var proof, explicitly not an adopted-source proof.

`deps.edn:25` uses `:local/root "reference-code/datahike"`, not a dependency jar.
Default nevertheless started with dependency AOT classes from
`target/dev-dependency-classes/a4a94060d14cdb97878650471b640fd04750f4327f31ebe266cec4554fe67117/`.
`clojure -T:dev-cache ensure-cache` completed successfully after the fork edit,
selecting cache **`64e42ad02ec29380c7f33026f2424c46591413b36043132adecb39723f753949`**,
source digest **`e0f3bbbc15f546fa7deb82e8e53f1d66a4228006e8d07f65da54466eac18778d`**,
372 dependency namespaces; the compiled `transaction$validate_report.class`
exists there. The cache had already been rebuilt by the concurrent operator,
so this command reported `:current`. Rebuilding the cache does not replace
classes already loaded in PID **53320**. Only the two ordinary reducer function
Vars were replaced in that JVM, preserving every existing dependency class.
Fresh dependency suite JVMs exercised the actual fork source; the orchestrator's
Seon gate must use the new cache and confirm the combined program.

Review before any gate remains the explicit stop boundary. No Seon gate,
platform run, default restart, scratch cluster or protected-file edit occurred.
`git diff --check` is clean for the owned source/test files. The final probe
passes clj-kondo: **0 errors, 0 warnings**. Source hooks report only existing
shadowed-var/redundant-let warnings after the new local shadow names were removed.

## Landed slice and cleanup

Research commit: **`71a2375595c4c9dd2dcc075949cf6e8c4512649f`**.
Implementation commit: **`35c5d2fa8`**, including the fork gitlink, `src/seon/db.clj`,
`test/seon/db_test.clj`, this note, the retained final probe, and the resolved
issue moved to `docs/seon/issues/archive/`. Both Seon commits and the one fork
commit carry the requested co-author trailer. The final documentation-only
checkpoint records this handoff.

All lane-started shell commands have ended. Completed test futures were removed
from `user`; no source definitions were reverted. The lane's
`tmp/write-admission` scratch directory is removed after recording the results.
No other lane's scratch, source edits, sessions or processes were cleaned.
The orchestrator gate request is appended to
`tmp/orchestrator/gate-requests/write-admission.txt` with the requested four
namespaces and `--platform`. That request is a review handoff, not permission to
claim the unexecuted canonical assertions passed. The original research's
required-cardinality-many and retained-program-identity schema questions remain
explicit integration checks: the final validator has no exemption that hides
those model failures if a transaction encounters them.

The fork-maintenance reference's selected gitlink and dependency ledger were
updated after the Markdown checker identified that new drift. Its remaining
30 historical gitlink findings predate this slice; the elided checker output
does not support a repository-wide Markdown-clean claim.
