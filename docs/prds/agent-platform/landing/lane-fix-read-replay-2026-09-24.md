# Lane fix-read-replay — a mistyped query value refuses as a read and as a replay (2026-09-24)

Evidence: `docs/research/agent-platform/fail-loud-dev-resilient-prod-2026-09-24.md` row 9.

## Broken first

- **The edit does not reach default.** Default (pid 90471) runs committed archive `ce73846828a5`
  with hook publication off. Installed proof comes from a `git archive` snapshot of HEAD `6780e926a`
  plus this diff, booted as its own root (`tmp/fix-read-replay/snap`, now stopped). Live-store numbers
  come from unarmed copies of the new forms in scratch namespace `tmp.fix-read-replay` in pid 90471.
  No default Vars were redefined. **RESET NEEDED** (or `start --head`) to install. Until then the
  retained root evidence below still throws on default at every system turn that re-reads it.
- **Second defect not fixed; it is outside my files.** A `seon.db/q` guard refusal, for example a plain
  collection passed as `$2`, crashes while the refusal is built. The error is
  `A durable Malli property contains a callable outside :gen/gen` (`:error/fn`
  `seon.db/query-guard-message`). I reproduced it on default:
  `(seon.db/q '[:find ?x :in $ $2 :where [$2 ?x :a 1]] db [[1 :a 1]])`.
  The throw comes from `seon.schema/canonical-definition` (schema.clj:687). The path is
  `seon.fn.schema-shape/authored-form` (schema_shape.clj:81), then `normalized-form`, then
  `seon.instrument/boundary-refusal` (instrument.clj:630). The cause is that
  `seon.instrument/bind-contract-predicates` binds `:error/fn` to a callable, and the canonicalizer
  refuses it. This is already filed:
  `docs/seon/issues/compiled-guard-error-functions-refuse-error-observation.md`. That issue rules
  "do not erase the property", so db.clj stays as it is. The owner is schema/instrument.
- The snapshot machine was heavily loaded (load average 18–35 from other lanes' JVMs). Snapshot boot
  took 3–5 min. One snapshot `--ns` run hit the check time limit, and two timing tests went over bound
  in the second snapshot (see numbers).

## Cause (verified at the REPL, default JVM, store data)

Root's evaluation 49744 (ordinal 4, 2026-09-23 14:12) wrote `[?f :seon.fn/sym 'my.agents.root/largest]`
inside a quoted query. Its constant is the list `(quote my.agents.root/largest)`. The original read
answered nil, and `append-query-evidence!` retained it as replayable evidence (entity 53096; its
siblings 49750/49756/49762 hold the same query). Datahike's planner compares a constant against AVET
through `estimate-pattern` → `count-slice` only on some database shapes. On a raw `DB`,
`Symbol.compareTo` throws ClassCastException. A `d/filter` view answers nil, which I observed. The
system turn's `read-evidence-changes` → `read-evidence-current?` → `replay-read` then threw on every
turn: signature `ab4f3435…`, 7 occurrences, 14:14–14:35. I reproduced this on default with the stored
evidence: `read-evidence-changes` threw, and `replay-read` of the fifth evidence entry threw.

## Fix (`src/seon/db.clj`)

`query-value-type-error` checks each data pattern's value against its attribute's installed type with
`datahike.schema/value-valid?` (`reference-code/datahike/src/datahike/schema.cljc:228`, gitlink `620721d6`), the check transactions already use. It
covers constants and `:in`-bound values (scalar, collection and tuple). Refs are excluded because they
take lookup refs and idents. EDN-encoded attributes are excluded because the codec encodes them later.
The declarations delay is forced only for a failing value, so ordinary reads resolve nothing new.
`seon.db/q` returns the declared `:seon.db/invalid-read` refusal before running, so no evidence is
retained. `replay-read` runs the same check, so evidence retained before this change replays as a
refusal value, which is unequal and therefore not current, instead of throwing. `value-type-error` is
extracted from `lookup-ref-error` and shared by both.

Regression: `seon.db-test/a-mistyped-pattern-value-refuses-as-a-read-and-as-a-replay`.

## EDN-codec fixture issue (`docs/seon/issues/db-test-edn-codec-fixture-writes-are-refused.md`)

The producer is the fixture. The writer derives its projection from the branch's schema rows
(`db/carried-projection` → `declares-program?`). `with-codec-database` declared its attributes only
through the registration delta and a carried connection state. As a result, `encode-transaction-in`
did not know the attributes were EDN-encoded, and Datahike refused the raw symbol. I proved this in
the snapshot JVM: the write was refused without rows and succeeded with rows. The fixture now
transacts `schema/canonical-schema-rows` for exactly its extra attributes, 3 rows. The full
`accrete-schema-population!` also works but cost 2.07 s. Assertions are unchanged.

## Numbers

- **Default JVM, live store, unarmed scratch copies.** Parent `seon.db/q`, warm, n = 200: 0.107 ms for
  a symbol-constant query and 0.149 ms for two patterns. Added check: 0.012 ms (two patterns) and
  0.009 ms (string attribute), with `::read-projection` still unrealized after the check. That is about
  8–11% of `q`. The declared bound is >20% or 50 ms. It refuses the stored root request with
  "seon.db/q received (quote my.agents.root/largest) for :seon.fn/sym, whose installed value type is
  :db.type/symbol."
- **First version (rejected).** It asked `edn-encoded?` before checking the type. Any string attribute
  then forced the declarations delay on every `q`. In snapshot 2, `an-as-of-view-is-keyed-on-its-own-fixed-point`
  took 7.9–8.7 s against its 5 s bound, and `every-database-value-reader-answers-for-all-four-view-shapes`
  took 5.3 s. Both passed under snapshot 1, before the fixture change. The shipped version consults
  `edn-encoded?` only for a value that already failed `value-valid?`. **This final version never ran
  through `seon.test/run`.** Three from-zero snapshot boots refused with "Source changed during
  analysis; clj-kondo cache entries changed again". The changed paths were in the shared checkout
  (`src/seon/cluster/prompt.clj`, `src/seon/test/runner.clj`, `test/seon/test_runner_test.clj`):
  other lanes were editing while the snapshot analyzed, and the snapshot reports
  `:seon.operator/source-root "/Users/sean/src/seon"`. So the snapshot's program is not fully isolated
  from the shared tree. I observed this but did not diagnose it.
- **Snapshot 1** (fix v1, old fixture): `--ns seon.db-test`, run `1dc09a1be9db`: pass 433 / fail 48 /
  error 10, 85 s. The 19 reds are the identical set that fix-db-decode's snapshot recorded
  (`tmp/fix-db-decode/check-diff.txt`), so this diff adds no new red. The regression passed alone (run
  `40337d90de53`, 4 assertions).
- **Snapshot 2** (fix v1 + fixture): run `92104868fcd8`. `edn-backed-reads-return-distinguishable-logical-values`,
  `query-symbol-values-use-the-declared-codec-in-every-binding-shape`,
  `invalid-edn-backed-storage-is-a-flat-read-error` and the regression are green. The full-namespace
  run `44bfd07acd42` stopped at its check time limit (124 s) with 21 members pending, so no full tally exists.
- Fixture schema rows: 3 rows, 625 ms per `with-codec-database` in the loaded snapshot (load average
  18–35). `write-report-error` accounts for 561 ms of it, and `arity-base` for 395 ms.
  `accrete-schema-population!` would have cost 2,074 ms.

## Size and commits

`src/seon/db.clj` +72 / −29 (net +43; the q and replay bodies are re-indented under `or`),
`test/seon/db_test.clj` +41 / −1.
