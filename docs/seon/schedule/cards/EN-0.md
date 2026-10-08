---
title: "Card EN-0: The probe executes a real test member"
type: reference
status: active
created: 2026-10-08
---

# EN-0 The probe executes a real test member

Ledger row EN-0 (group T). Design: `redo/entrants.md` §5 EN-0. Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.pass-probe/run-check` (`test/seon/pass_probe.clj:360-379`) runs `(my.test/check {:seon.test/changed [sym]})`
`repetitions` times; after the first, the member's green result is reused, so later records measure a lookup
(`::member-executed?` false), not an executed proof.

## Change
- ADD `seon.pass-probe/run-proof`
  `{:malli/schema [:=> [:cat :string :qualified-symbol pos-int?] [:vector :map]]}` `[running test-sym repetitions]`:
  as `run-check`, but each repetition first redefines the test's subject on the agent's branch so the member must
  execute: the probe defines its own fixture pair on a fresh agent,
  `(ns probe.proof) (defn subject [] <n>) (clojure.test/deftest subject-test (clojure.test/is (= <n> (subject))))`
  with `<n>` the repetition number, then checks `'probe.proof/subject-test` by identity
  (`{:seon.test/identities #{'probe.proof/subject-test}}`). `test-sym` is ignored when it is `'probe.proof/subject-test`'s
  default; keep the parameter for a caller that supplies its own test (then it redefines nothing and documents that
  reuse may occur). Records as `run-check`'s, with `::kind :proof`.
- Use the same `window`, stages and retire-in-`finally` as `run-check`.

## Proof
`seon.pass-probe-test/a-proof-run-executes-its-member-every-time` (new, `test/seon/pass_probe_test.clj`, a
`:seon.test/long` deftest with its measured reason like the existing one): `(probe/run-proof running 'probe.proof/subject-test 3)`
answers three records, each `(::probe/member-executed? r)` true and `measured?`.

## Commit
`bin/lane commit <lane> -m "EN-0: the probe's proof run executes its member every repetition" --test seon.pass-probe-test test/seon/pass_probe.clj test/seon/pass_probe_test.clj`

## Cost
Test tooling only. Dependencies: none. Reset: none.
