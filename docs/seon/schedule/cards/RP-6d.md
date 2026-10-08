---
title: "Card RP-6d: An unjoined write is a finding"
type: reference
status: active
created: 2026-10-08
---

# RP-6d An unjoined write is a finding

Ledger row RP-6d (alias S5-04f, group B). Design: `redo/pass-and-recording.md` §2 Findings; agreed patch (ledger §3.2):
findings are named variant maps. CLAUDE.md: "Every stored fact names its consumer or is deleted; hiding it behind a
filter or a missing render is not a fix." Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
A function write that joins no analyzed definition (`seon.turn.batch/join-write`, `src/seon/turn/batch.clj:121-158`:
`:rebound` for `intern`/`alter-var-root`, `:unanalyzed` with reason `:no-complete-span`, `:no-definition-at-origin`,
`:ambiguous`; `:no-source` from `derive-form` `:336`) lands in the change's `:seon.batch/private` and nowhere the agent
reads it: the function runs in its SCI world and is silently not part of the program.

## The rule (decided here)
A final root write of a **function or test** whose join is `:rebound` or `:unanalyzed` (any reason) is a finding
`:seon.program.finding/unjoined-write` beside the value of the form that wrote it. A value def (`:value`), a member of a
record (`:member`) and a definition that captures locals (`:captures-locals`, RP-9's `:live-only`) are not this
finding.

## Files
- `resources/seon/schemas/seon.program.finding.edn` (new variant, accretion)
- `resources/seon/schemas/seon.batch.edn` (`:change` gains `:seon.batch/findings`)
- `src/seon/turn/batch.clj`
- `src/seon/batch.clj` (`derive-all` copies the change's findings onto the entry)
- `test/seon/repl_differential_p8_test.clj`

## Schema
```edn
:unjoined-write [:and :seon.program.finding/base
                 [:map [:seon.program.finding/type [:= :seon.program.finding/unjoined-write]]
                  [:seon.batch/sym :qualified-symbol]
                  [:seon.batch/reason :keyword]]]
```
and `[:seon.program.finding/unjoined-write :seon.program.finding/unjoined-write]` in `:any`'s `:multi`. In
`seon.batch.edn` `:change`: `[:seon.batch/findings {:optional true} [:vector :seon.program.finding/any]]`.

## Functions
- CHANGE `seon.turn.batch/derive-form` (`:304`), contract unchanged (the change schema accretes): build
  `:seon.batch/findings` from the final writes whose `private` join is `:rebound` or `:unanalyzed` and whose Var's
  value is a function or a test (`(or (fn? @v) (:test (meta v)))`):
  `{:seon.program.finding/type :seon.program.finding/unjoined-write :seon.batch/sym sym :seon.batch/reason (or (:seon.batch/reason private) :rebound)}`;
  assoc it into `change` only when non-empty.
- CHANGE `seon.batch/derive-all` (RP-6a1): `(update entry :seon.batch/findings (fnil into []) (:seon.batch/findings change))`
  when present; RP-6a2's FINISH already prints entry findings.

## Proof
`seon.repl-differential-p8-test/an-unjoined-write-is-a-finding` (new): the reply of
`"(ns rp6d.a) (intern *ns* 'h (fn [] 3)) (h)"`: the `intern` form's `:seon.repl/findings` holds one map with
`:seon.program.finding/type :seon.program.finding/unjoined-write`, `:seon.batch/sym rp6d.a/h`,
`:seon.batch/reason :rebound`; `(h)` answers `3`; `rp6d.a/h` has no program row.

## Reaching tests
`seon.repl-differential-p8-test`, `seon.turn.batch-test`, `seon.turn-test`, `seon.cluster.host-test`.

## Cost
Proportional to the form's writes; no reads.

## Deleted
Nothing (the private list stays as derivation detail until RP-12 decides what is stored).

## Commit
`bin/lane commit <lane> -m "RP-6d: a function write that joins no definition is a finding beside its value" --test seon.repl-differential-p8-test --test seon.turn.batch-test --test seon.turn-test resources/seon/schemas/seon.program.finding.edn resources/seon/schemas/seon.batch.edn src/seon/turn/batch.clj src/seon/batch.clj test/seon/repl_differential_p8_test.clj`

## Dependencies
RP-6a (RP-6a2's FINISH), RP-1w (the finding schema file). Group B.

## Reset needs
None.
