---
type: landing
status: blocked
lane: fix-test-world-refusals
created: 2026-09-24
report: docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md
---

# Lane fix-test-world-refusals (2026-09-24)

Diagnose-first lane. This lane changed no source or test file.

## Broken first

- **Default was down for this whole run.** At 18:09Z `bin/seon status` answered
  "No live exact-root JVM", and no `java` process was running. MCP
  `runtime_status` listed no clusters. The hook log shows
  "no live root JVM answers" from 18:11:02Z; the last live activity was about
  18:03Z. I did not start or reset default. Nothing was reproduced with
  `bin/test-check`, and I made no REPL probe. **START NEEDED** (root decides):
  default is down and was not reset by this lane.
- The diagnosis below comes from the saved outputs in
  `tmp/fix-unloaded-host-bound-row/` plus reading the source. It is not REPL-verified.

## A: `seon.bootstrap-test` fixture writes refused on `:seon.program/definition-digest`

- **Cause (known class, no production producer).**
  - `f27b96c19` (2026-09-22, "Write canonical definition digests on declarations")
    made `:seon.program/definition-digest` and `:seon.program/analyzed-source-digest`
    required on the `:seon.test/test` rows (`resources/seon/schemas/seon.test.edn:88`),
    the ns rows and the schema rows. The fn rows (`seon.fn.edn:134`) are the same.
  - The digest is produced only by the declaration owners:
    - `seon.fn` source rows (`src/seon/fn.clj:237`, `:646`, `:1263`)
    - `seon.program/declaration-row`
    - `seon.schema/canonical-schema-rows` (`src/seon/schema.clj:3912`)
  - `bootstrap_test.clj:181` and `:317` hand-build `{:seon.test/sym …}` rows, and the
    `{:seon.fn/sym …}` rows at `:172` and `:309` are hand-built the same way. None of
    them carries a digest. `seon.db/transact!` then correctly refuses the missing
    required key. In `tmp/fix-unloaded-host-bound-row/after.txt`, the entity named
    in the refusal is `#:seon.test{:sym …current-items-test}` / `fixture.intent/target-usage`.
  - The `:seon.error/unknown` in the message is the humanized placeholder for the
    absent value. No reader or writer yields `:seon.error/unknown` in place of a digest.
- **Fix owner: the test call sites.** Route these rows through
  `seon.test-support/program-row` (`test/seon/test_support.clj:741`): use
  `[:seon.test/sym sym]` for test rows and `program-fn-row` for fn rows. Both run the
  production analyzer, which derives both digests.
  - `bootstrap_test.clj` is currently being edited by `sweep-hand-built-ns-rows`
    (its working diff converts `{:seon.ns/name 'fixture.intent}` to `namespace-row`),
    so this lane did not touch it.
  - That sweep covers namespace rows only. The fn and test rows are the same class,
    one level wider:
    - `grep -rn "{:seon.test/sym " test/`: 54 sites in 17 files.
    - `grep -rn "{:seon.fn/sym " test/`: 88 sites in 30 files.
    - Some of these are lookups or upserts, not creations.
  - Extend the existing issue
    `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`
    to fn and test rows. No new issue is filed.
- The other bootstrap reds are separate:
  - `absent-intent-budget-refuses-loudly` gets a nil `:seon.config/required-absent`.
  - The supervision-sources assertion fails.
  - The drive-free generation run took 8.06 s against a 5 s bound.
  - None of these was investigated (no JVM).

## B: `branch-definitions-and-unchanged-callers-share-one-context` refuses its config

- **The error comes from here.** `seon.sci.eval/instrumentation-config`
  (`src/seon/sci/eval.clj:736`) throws the refusal that `config/effective-in`
  returns (`src/seon/config.clj:793`) when the selected config row lacks a required
  dial.
- **The dial and its default are old and consistent:**
  - `:seon.config.agent/turn-completion-backstop-ms` has been a required dial since
    `d4a85dbdc` (2026-09-14).
  - It is in `flow-workload-attributes` since `3f0cdf200` (2026-08-14).
  - `config/default.edn:159` ships it as 600000, since `12cd3bf93` (2026-08-13).
  - So `config/apply!` (which `seed-cluster!` uses) writes it on a freshly seeded
    cluster. The seeded row is not the one missing it.
- **Candidate producer (unverified).** `database-effective-config`
  (`src/seon/sci/eval.clj:699-725`, last touched in `e90b3c558`, `59e51e221` and
  `cdfc01058`) picks its cluster with `[:find ?cluster . …]`, which is an arbitrary
  one.
  - A fixture branch inherits every cluster row of the handle's database (see
    `docs/seon/issues/test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md`).
    It therefore holds both `realities-proof` and whatever default holds.
  - The read may be selecting an inherited cluster row that lacks the dial, instead
    of the context's own cluster. The context names its cluster through
    `:seon.boot/cluster-name` in its environment (`fork-cluster-ctx`,
    `test/seon/test_support.clj:316`).
  - Two proofs are still owed:
    - which rows the branch holds: `(d/q '[:find ?c ?e :where [?e :seon.config/cluster ?c]] db)`
      on `(db/db a)`
    - which one lacks the dial
  - The refusal's `:seon.error/data` carries `::missing` and `::available`, which
    answer both. The saved output kept only the message.
- **Proposed fix, if confirmed.** `database-effective-config` reads the context's
  environment cluster name, not an arbitrary config row. This is about 10 lines in
  `src/seon/sci/eval.clj`, which is held by the running `fix-affected-callers-interpret`
  lane. So this is **not fixable here**: it goes to that holder, or waits for it.
  If instead the inherited row really lacks the dial, the producer is whichever
  writer left a partial config row on default. Its `:db/txInstant` and provenance
  would name it.

## Evidence and limits

- No runs, no timings and no before/after numbers, because default was down.
- Sources read: `tmp/fix-unloaded-host-bound-row/{after,before,after-host-bound,after-branch-exec}.txt`,
  the schema and source lines cited above, and the git history of those lines.
- Net lines: 0 source, 0 test. This note is the only change. RESET NEEDED: no.
  START NEEDED: yes (default down at 18:09Z).
