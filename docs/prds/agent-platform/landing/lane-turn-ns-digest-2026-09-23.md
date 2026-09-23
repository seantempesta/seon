# Lane turn-ns-digest (2026-09-23)

Issue: `docs/seon/issues/turn-writers-upsert-bare-namespace-rows-without-a-definition-digest.md` (resolved).

## Slice 1: namespace rows through their digested declaration

- `append-generated-call`, `generated-run-tx`: the bare `{:seon.ns/name n}` row is
  deleted; both already reference the namespace by lookup ref.
- `record-evaluated-call`: an existing namespace is referenced (identity-only upsert
  resolves the tempid); an absent one takes the evaluation's own
  `:seon.program/row` (the ending-namespace row `seon.sci.eval` builds through
  `program/declaration-row`), so the digest is derived by the one owner.
- Cost: one `[:find ?n . ...]` identity lookup per distinct namespace in the reply
  (proportional to namespaces touched, typically 1-2), plus a linear scan of the
  reply's evaluations only for an absent namespace.
- Contracts: none touched. Changed path: `src/seon/turn.clj` (+25/-22).

## Evidence (default pid 90963)

| operation | wall ms | note |
|---|---|---|
| baseline `bin/test-check ... --test seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` | 25958 | run 8ad8eb11b01b: refused `[47279 :seon.program/definition-digest] ... Entity: #:seon.ns{:name my.agents.probe}` |
| `bin/seon init --dev default --changed src/seon/turn.clj` (1st) | 18377 | refused "The source head changed before publication." (concurrent refresh; `refresh-source!` x3 in the profile) |
| same (retry) | 40923 | adopted, commit 6ab356bb-4ce3-5759-9a82-9346c9ed1b41; profile shows `seon.test/run` and `refresh-source!` x4 from other lanes concurrently |
| after-fix test-check, same test | 41394 | run 83087cf65291: pass 38, error 0, fail 1 = duration 8820 ms over the 5000 ms bound |

Every row above is over ten seconds and is a defect outside this lane's paths:
adoption of one file is proportional to whole-program refresh
(`seon.cluster/refresh-source!`, `seon.fn/analyzed-files`), and the test runner spends
~32 s beyond the test body. Closest existing notes:
`docs/seon/issues/a-five-file-publication-spends-44-seconds-in-its-reconciliation-transaction.md`,
`docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`.
The test body itself (8.8 s under concurrent load, 11.4 s baseline) exceeds its bound.

Hot-path probe parent vs self: not comparable; the parent refuses this write.

Not converted: `source-rows` (plan-time receipts) still upserts identity-only rows.

RESET NEEDED: no.

## Slice 2 (0eccaa8f7): source-rows through the same owner

Orchestrator follow-up: `source-rows` (plan-time receipts) wrote identity-only rows;
`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` refused
"Parity turn refused" on `sample.s1`. One private `namespace-rows` now serves
`source-rows` and `record-evaluated-call`: an existing namespace is referenced; an
absent one takes its evaluation's `:seon.program/row`, else
`program/declaration-row` over `{:seon.ns/name n :seon.ns/source <the source that
moved into n>}` (probe: 9 ms for the in-ns map, 4 ms for an ns reader event on
default). Cost: one identity query per distinct namespace plus O(sources) pairing.

| operation | wall ms | note |
|---|---|---|
| init --changed turn.clj, attempts 1-2 | 21191, 44606 | refused "The source head changed before publication." (other lanes publishing) |
| init attempt 3 | 58750 | adopted 6ab35809-f622-554e-b6be-11a4d889f7e6; profile: `seon.test/run` x2 and `transact!` x22 from concurrent lanes |
| transcript test, run a2214c23350f | 30620 | pass 38, fail 1 = duration 10022 ms over 5000 ms |
| program-test parity, run (after fix) | 67660 | turn now closes (no refusal); reds are definition-digest mismatches between indexed and evaluated rows for ns, fn and test rows alike (ns: indexed 11eaac21..., evaluated 7032f6ef...; plan-time row 31bb33fa... is overwritten), plus duration 35684 ms. That is digest parity in the evaluation/analyzer path, not the turn writer. |

## Slice 3: a thrown step cancels its turn backstop

`seon.turn/step` cancelled the completion backstop only on success, so an escaped
step (fault 45846) left the observer parked 600 000 ms and then recorded a second
fault (46229); issue `docs/seon/issues/read-evidence-currency-hands-a-nil-source-to-index-evidence.md`.
The `finally` now cancels whenever the permit is republished; the throw still
propagates to Flow (never swallowed); the `succeeded?` volatile is deleted.

REPL probe on default after adoption (6ab35930-6d90-5a6b-840c-8ffb85022508, 27550 ms,
concurrent lanes): `(seon.turn/step state :seon.agent/episode :seon.agent/wake)` with a
carried `:seon.db.process/id :not-a-process` throws at `seon.turn/turn`'s contract after
arming; afterwards `backstop-state` nil, permit republished, no backstop fault (37 ms).
Regression not written: it belongs in `test/seon/turn_backstop_test.clj`, outside this
lane's paths (see final report).

Every row over ten seconds above is a defect outside this lane: one-file adoption and a
one-test run are proportional to concurrent whole-program refresh and runner setup, and
concurrent publications race the optimistic head check.
