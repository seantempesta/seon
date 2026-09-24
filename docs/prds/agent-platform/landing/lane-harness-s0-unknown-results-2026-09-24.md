---
type: landing
status: partial
lane: harness-s0-unknown-results
created: 2026-09-24
issue: docs/seon/issues/An-unfinished-test-run-hides-every-problem-family.md
---

# Lane harness-s0-unknown-results — landing (2026-09-24)

Design: one-test-harness/design-2026-09-24.md §3, §9c row S0; astra-review finding 5.
Files named first (all within the S0 row): src/seon/test/runner.clj, src/seon/test.clj,
src/seon/problems.clj, resources/seon/schemas/seon.test.edn,
test/seon/test/interrupted_request_test.clj. No render file changed: every renderer
already reads the unknown through `:seon.error/at` (render/test.clj `evidence`,
problems `failed-tests-unknown` rows, issue.clj test rows).

## Broken or unproven first

- **Adoption of the final test edit is pending.** The last assertion added to the
  regression (`green?` over a captured result) published but default did not adopt it:
  program rows stay at source 6ab58811 because foreign reds block adoption (open item
  "a red baseline blocks every adoption"). The loaded Var is the previous version
  (`:line 103` loaded vs 107 in the file). The same claim was proved at the REPL (below).
- **Readiness now shows what the unknown hid.** Default's problems went from one
  `failed-tests-unknown` entry (the whole family refused) to 554–647 failed tests
  plus 3–80 per-test unknowns (numbers move with other lanes' runs). `ai-prose` over
  that is 312,584 characters. The prose has no bound; presentation limits belong to the
  AI render function. This is the existing unknown class being exposed, not a new cost.
  Out of scope here; it belongs to the problems render owner.
- Foreign reds seen in focused requests, none on this slice's keys:
  `seon.problems-test` (12 reds). The fixture branch inherits default's live
  `failed-runs`/`error-signatures`/`errored-receipts`, so assertions expecting `{}` fail.
  No red names `failed-tests`. `seon.test-provenance-test` and
  `seon.test-failure-facts-test` fixture writes are refused for missing
  `:seon.program/definition-digest` (another lane's schema change).
- `docs/seon/issues/An-unfinished-test-run-hides-every-problem-family.md` point 2 is
  fixed by this slice. It is not my file, so its status is unchanged. Root, please close it.

## What changed

- `:seon.test/result-unknown` (seon.test.edn) is a new named alternative. It is an error
  value over `:seon.error/base` with `:seon.test/error-test-symbol`, `:seon.test/run`
  and `:seon.test/execution-refusal`, and it carries no counts. It uses
  `error-test-symbol`, not `:seon.test/sym`: the publication check refuses an error
  observation that carries an upsert identity.
- `:seon.test/latest-results` is `[:vector [:or :seon.test/result :seon.test/result-unknown]]`.
  It is a new name rather than a widening of `:seon.test/results`, because
  `commit-results!`, `run-results` and `seon.test/run` never produce an unknown
  (accretion: new name for changed semantics).
- `runner/recorded-member-results` returns the unknown for a member with no
  `completed-tx` or non-integer counts. Before, it failed its own output contract and took
  down the whole batch read ("Recorded run coverage is unavailable."). `latest-results`
  passes each member through. It still makes one read per distinct run, and a failed
  query still refuses the whole answer through `result-read-error`.
- `problems/failed-tests` keeps the unknowns, and `problems` splits them into
  `:seon.problems/failed-tests-unknown`. That contract is
  `[:vector :seon.test/execution-error]`, which the unknown satisfies. `pos?` never sees
  an unknown.
- `seon.test/verified?` (both arities) answers `false` for a member's unknown, so the
  test stays stale and runs again. A failed read still returns its refusal.
  `recorded-result` returns the unknown under its existing contract.
- Defect (b): the `seon.test/green?` contract was `:seon.test/result`, which requires
  `:seon.test/run-basis-t`. It is called on captured results (the platform stop check
  at test.clj ~1803, `passed?`, and `tally` over results that carry
  `:seon.test/recording-refusal`), which have no basis. The contract is now
  `:seon.test.runner/captured-result`, which is exactly the counts it reads.
- Test coupling: the blob assertion in `a-huge-assertion-message…` now reads only this
  run's reports. Before, it read every report the fixture branch inherits from default,
  and it was red on default.

## Evidence (default pid 99265, MCP JVM eval, session harness-s0)

- Before: `(seon.problems/problems db {})` took 142 ms and answered
  `failed-tests-unknown` = one whole-read refusal. Cause:
  `recorded-member-results refused return value at
  [seon.call-preparation-test/a-row-published-after-a-warm-plan-is-seen-synchronously
  :seon.test/pass-count]: expected an integer, got nil`. That member was admitted and
  never recorded.
- After: `latest-results` over 1,411 member symbols (411 runs) returns 1,355 results in
  222–238 ms warm. The first cold read of 1,333 symbols took 1,946 ms under profiling:
  `recorded-member-results` x125 1,717 ms and `seon.db/pull-call` x1,285, proportional to
  distinct runs (cold index nodes). `problems` takes 253 ms warm (1,198 ms on its first
  profiled call). Sample unknown: `my.message-test/send-preserves-routing-until-turn-settlement`,
  `:seon.test/run [:seon.test.run/id "af7e1b37bcc8"]`.
- `green?` probe: `{sym, pass 1, fail 0, error 0}` → true; `fail 1` → false; a map
  missing counts is still refused by the contract.
- Regression `one-incomplete-member-is-its-own-unknown-and-its-run-mates-stay-readable`:
  one run holds green `finished`, red `red` and incomplete `interrupted`, and the request
  also names a never-run symbol. It checks Malli `validate`/`explain` of
  `:seon.test/latest-results` and `:seon.test/result-unknown` against the carried
  projection's registry. Never-run is absent. Green and red keep their counts. The unknown
  keeps its run and has no counts. `recorded-result` returns the unknown, and
  `verified?` is false. Problems lists `red` in `failed-tests` and `interrupted` in
  `failed-tests-unknown`, and `ai-prose` names it.
  `bin/test-check default --ns seon.test.interrupted-request-test --include-long`:
  run 1895d798e8b3 passed (31 pass, 8.7 s), then run db7000505100 passed
  (3 executed, 31 pass, 10.7 s). The tests are declared long at 30 s for admit-run.

## Not proven

- "A query failure still refuses the whole read" is unchanged code
  (`result-read-error`, `execution-members` refusals). There is no new regression for it.
- `commit-results!` reading back an incomplete member now returns the unknown as its
  recording refusal (it satisfies `:seon.db/error-result` via `:seon.error/base`). Before,
  its reader threw. This path has not been exercised.

## Size

Source and schema: +46 / −27 (runner +25/−18, problems +9/−6, test +6/−3, schema +6).
Test: +41 / −13. One commit.
