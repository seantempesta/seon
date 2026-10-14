---
title: "Card RF-2 bin/lane commit --branch-evidence"
type: reference
status: done (871a28901)
created: 2026-10-08
---

# RF-2 `bin/lane commit LANE --branch-evidence AGENT`

Design: `docs/research/agent-platform/2026-10-08/repl-first/repl-first.md` §3. Owner ruling: `review/owner-rulings.md`
last bullet. Lane: Opus (gate semantics). Depends on RF-3 for the platform classification; until it lands, the
path classes below are the whole classification.

## Functions

- ADD `seon.cluster.source/branch-evidence` `[:=> [:cat :seon.source/branch-evidence-request]
  :seon.source/branch-evidence]`, request `{:seon.store/branch b :seon.fn/root r :seon.source/paths [...]}` (connection
  supplied). Answers data, never throws:
  1. For each path, analyze its working-tree bytes (`seon.fn/analyze-entities` with `:seon.fn.analyzer/sources` and
     `:seon.source/previous-database` = the branch head) and compare each declaration's definition digest with the
     branch head's. Differences → `:seon.source/differing` (identity, file digest, branch digest). Measured 35 ms
     per file.
  2. Declarations whose `:seon.fn/host-bound?` is true → `:seon.source/host-bound`.
  3. Changed declarations = the analyzed ones whose digest differs from main's head. Reaching tests =
     `seon.test/reaching {:seon.db/db branch-head :seon.test/changed syms}`; each must be `seon.test/verified?` on the
     branch head. Not verified → `:seon.source/unproven`; a changed declaration no test reaches →
     `:seon.source/unreached`; a test also not verified on main with the same reach digest → `:seon.source/red-before`.
  4. `:seon.source/proven?` is true only when 1-3 are all empty (red-before excepted when the caller accepts it).
- CHANGE `bin/lane` `commit`: `--branch-evidence AGENT [--accept-red-before TEST]...`. Refuse unless every path is a
  `src/**.clj` or `test/**.clj` file (anything else: "throwaway check required", run `bin/seon check` as today).
  Call `branch-evidence` through `seon.operator/live-root-value!` (one prepl round trip, measured 6-200 ms); no live
  root → fall back to `bin/seon check` and say so. Lint `src script test` with `--cache false` as today. Put the
  branch commit id and run ids in the message trailer.
- SCHEMA: `:seon.source/branch-evidence-request`, `:seon.source/branch-evidence` in `seon.source.edn`.

## Proof

`seon.cluster.branch-evidence-test` (new): a fixture file and its test on H; branch C replaces the callee and runs
its reaching test green → `proven? true`; the file edited to differ → `:differing` names it; a callee no test
reaches → `:unreached`; a test red on H and C → `:red-before`. `seon.operator-test` row: `bin/lane commit
--branch-evidence` with no live root falls back to the check.

## Cost

Per commit: one prepl call, one analysis per file (35 ms), one `reaching` (graph walk) and one `verified?` per
reaching test (reach digest read); no JVM boot. Target: under 1 s for a one-file slice against 2-4 min today.
