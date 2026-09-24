---
type: landing
status: landed
lane: fix-problems-prose-bound
created: 2026-09-24
issue: docs/seon/issues/an-unfinished-test-run-hides-every-problem-family.md
---

# Lane fix-problems-prose-bound (2026-09-24)

The problems AI prose is rendered in `src/seon/problems.clj` (`ai-prose`); no other
file was touched in source. `seon.problems/ai-prose` now sends its lines through the one
existing bound, `seon.render.value/render-ai`, under the render profile.

## Broken or unresolved first

- **The declared pair is unreachable through the renderer.** `:seon.problems/problems`
  declares `:seon.render/ai seon.problems/ai-prose`, but `seon.schema/matching-shapes-in`
  answers `()` for the live value, which does validate. Readiness children are not
  attribute-scoped either, so `seon.render` never selects `ai-prose`. The ordinary SCI
  render of the problems value is structural. It is already bounded: 4 of 649 shown,
  `:seon.print/omitted 645`, path `[:seon.problems/failed-tests]`. The 312,584-character
  prose came from direct calls. Filed as
  `docs/seon/issues/a-declared-render-pair-on-an-all-optional-map-is-never-selected.md`
  (owner: render/schema selection).
- **The output is uglier.** The prose is now an EDN map, family key to a vector of line
  strings, so each test's newlines print as `\n` escapes inside one string. This is the
  attribute-map printer's grammar. It is what keeps the family key and index equal to
  the `problems` path, so the elision's requery form is true.
- **Foreign reds.** Focused `seon.problems-test` has the same 12 reds as the
  harness-s0 landing. The fixture branch inherits default's live error signatures,
  receipts and runs. In addition, `configured-models…` and
  `every-committed-error-fact-shape…` fail on fixture writes refused for
  `:seon.agent/branch` and `:seon.error/layer`. None of these touch `ai-prose`. The first
  source save was refused by the save gate on foreign reds (bootstrap, agent-call-edges,
  agent-arming, and `episode-cap-refusal-test`, which was red before the edit). After the
  test-file save, the edit was adopted: `(:line (meta #'seon.problems/ai-prose))` is 586,
  the new definition. No RESET NEEDED.

## Design

- `ai-prose` builds `{family [whole line …]}` using the same family keys and order
  indices as `problems`. It hands that map to `render.value/render-ai` with the request's
  `:seon.render/profile`. If the request has none, it falls back to
  `seon.render/agent-render-profile` over `seon.config/defaults`, the same fallback
  `render.value` uses. Its `:seon.print/requery-id` is `(seon.problems/problems (seon.db/db) {})`.
- The existing mechanism cuts the output: `max-children`, then `print/fit` to the token
  budget. The elision names `:seon.print/omitted`, `:seon.render.data/path`,
  `next-offset`, `total`, `bound-by` and
  `(seon.print/value-at (seon.problems/problems (seon.db/db) {}) [:seon.problems/failed-tests])`.
- This adds no new clipping site and no second budget. `html-report` and `log-report`
  are unchanged.

## Evidence (default pid 99265, JVM `eval_clj`)

| | before | after |
|---|---|---|
| failed tests in value | 649 | 562 (moves with other lanes' runs) |
| `ai-prose` characters | 313,589 | 27,957 |
| `ai-prose` estimated tokens | ~98,000 | 8,736 (profile budget 15,000) |
| `ai-prose` time | 3.3 ms | 8.1 ms (+4.8 ms, under the 50 ms rule) |
| `problems` time | 226 ms | 328 ms (derivation not changed; varies with store) |

After the change, live elisions were `:seon.print/omitted 530` on the failed-tests family
and 89 on a second family, both carrying the requery form.

Regression:
`seon.problems-test/six-hundred-failed-tests-render-within-the-profile-bound`. It builds
600 failed tests with a fixed profile and checks four things:

- the estimated tokens stay within 15,000
- whole lines are shown
- shown plus omitted equals 600
- the requery form is present

`bin/test-check default --test …`: run 4136b6b7fe84, pass 4 / fail 0, 11.0 s wall
(bin/test-check overhead; the member was reused from the save-triggered run 43b08422f835).
The existing substring assertions on `ai-prose` (errored receipt line 408, stale var 289,
deferred agents in `cluster.agent-test`) still hold on the new text. The deferred case is
red for a foreign reason: the family itself is nil.

## Lines and commits

- `src/seon/problems.clj` +42 / −23 (net +19; +3 requires, docstring +7).
- `test/seon/problems_test.clj` +22 / −0.
- Issue point 2 closed in `docs/seon/issues/an-unfinished-test-run-hides-every-problem-family.md`.
- New issue: the render pair is never selected.
- Released paths: `src/seon/problems.clj`, `test/seon/problems_test.clj`.

## Verification limits

The bulk and platform tiers were not run (root owns them). Paint was not observed. The
declared-pair route was not exercised, because it is unreachable (see the issue above).
