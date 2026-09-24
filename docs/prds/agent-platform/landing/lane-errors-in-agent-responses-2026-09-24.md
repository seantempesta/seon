---
type: landing
status: landed on default (publication hook adopted each edit); two commits plus an issue
lane: errors-in-agent-responses
created: 2026-09-24
requirements: docs/research/agent-platform/language-requirements-2026-09-24.md §8.3
---

# Lane errors-in-agent-responses (2026-09-24)

What exists now: `seon.repl/response-entries` is the one response grammar. It
gains `:seon.repl/errors`. The value comes from
`seon.render.transcript/branch-errors`, which calls `seon.error/occurred-since`
once per settlement window that has errors.

- Window (root ruling): (previous evaluation's settlement tx, this settlement tx].
- The read is `as-of` this settlement, so shown history is stable.
- The value renderer bounds the text once, in `candidate-history` via `floor-text`.

## Broken or unproven, first

- **On live default, every window's as-of read refuses today.** See
  `docs/seon/issues/an-as-of-view-older-than-a-tightened-schema-rule-is-unreadable.md`.
  - `e86043f81` (17:17Z) tightened `register!`. Every `seon.db/as-of` view older
    than that change now refuses with "schema/register! :seon.flow/callback-result…".
  - That covers all 19 of root's settlement txs.
  - So on default, root's `(dir seon.fn)` response shows `:errors` as that refusal,
    a typed unknown, where the real error vector belongs.
  - I first wrote this as a throw. That threw inside `history-entries`, which would
    have taken every affected agent's prompt with it, so the refusal is now rendered
    instead of thrown.
  - The regression passes because a fresh fixture branch's as-of views are readable.
- **Cost of the first read after each commit.** The as-of projection is keyed by the
  origin commit (`seon.db/as-of-key`), so every new commit re-derives it for each
  window that has errors.
  - Measured on default: 76 ms for the first call after a commit, 3.6–4.5 ms warm.
  - An agent with no occurrence on its branch pays one history query (0.15–0.25 ms).
  - The owner is `seon.db`; same issue file.
- **Batch granularity.** A reply's forms settle in one transaction (`turn/settle-batch!`).
  - Every error recorded during a reply shows once, on the reply's last form.
  - Separate replies get separate responses.
  - Occurrences carry no evaluation reference, so a finer split has no data to use.
- **In-memory previews** (`:seon.turn.loop/evaluated-sources`, the page's preview)
  carry no errors. They have no settlement tx.
- **Cluster-branch copies** (`:seon.error.occurrence/branch` naming another branch)
  are filtered by code. The regression does not construct one; it proves only that a
  sibling fixture branch's fault never appears.
- **Foreign reds seen in the hook's reach runs.** They were also present before the
  errors change.
  - `seon.bootstrap-test` (fixture refusals on `:seon.program/definition-digest`, and
    `drive-free-generation…` at 6.7–9.7 s).
  - `seon.cluster.agent-test` / `agent-arming-test` ("Host-bound declaration …
    must change through the loaded source files").
  - `seon.repl-grammar-test/stored-help-keeps-its-renderer-and-bare-response`
    ("Isolation requires a fresh owned branch", `cluster/agent.clj:848`).
  - Intermittent "Worker-global state changed" drift from other lanes' concurrent
    adoptions (the Var named was different on each run).
- `bin/seon status` refuses: `seon.cluster.boot/readable-response` output contract
  is missing `:seon.error/at`. This is foreign. MCP `runtime_status` works.

## What landed

| commit | change |
|---|---|
| `f6a81c932` | `prefix-problem` (ledger) read `render/request-profile` and carried a refusal as the profile. It now names the caller's `:seon.cluster/name` and throws a refused profile. Regression `seon.render.transcript-test/a-refused-render-profile-is-passed-on-never-used-as-the-prefix-profile`: `bin/test-check` run `1dfabb145a53`, 4 pass. |
| `0e4f438b8` | `branch-errors`, plus the `:seon.repl/errors` slot, emission key and schemas (`:seon.repl/branch-error`, `:seon.repl/errors`). Declared-renderer responses get a trailing `#:seon.repl{:errors …}`. `agent-history-under` derives the windows once for all runs. Regression `seon.render.transcript-test/an-evaluations-branch-errors-read-in-its-own-response-once`. Two stale assertions were updated (details in the evidence section). |

Net source lines: transcript +86 (prefix +9), repl +8, seon.repl.edn +14. That is
about 108 added lines, a little over the ~100 guide.
- `branch-errors` is 58 of them. Most is the window and batch derivation that
  `occurred-since` has no upper bound for.
- If `seon.error/occurred-since` took an upper bound over a history view, this would
  shrink and would drop the as-of projection dependency. That is `error.clj`'s owner.

Tests: +87 lines.

## Evidence

- Regression, fresh run inside the hook's `seon.render.transcript-test` run
  (`af7af389f8aa`); reused green afterwards (`38728ca50103`, 10 assertions). It proves:
  - evaluation 1's response carries `:errors [` with its message and
    `:seon.error.occurrence/count 1`;
  - 0's and 2's responses carry none;
  - the message appears exactly once in the whole transcript;
  - the sibling branch's message never appears.
- `bin/test-check default --ns seon.render.transcript-test`: run `104627da2c4c`,
  211 pass, 0 fail, 1 error. The error is the foreign drift above. The one declared-long
  member was excluded by policy.
- `--ns seon.repl-test`: run `709316e1ff70`, 76 pass, passed.
- `--ns seon.repl-grammar-test`: run `83a30f1559bf`, 9 pass, 1 foreign error.
- Stale assertions updated:
  - `durable-history-entries-never-invent-executions` now expects the stored handle
    `:result result/estored-evaluation`, since 57c40fbac.
  - `prompt-precedes-exact-multiline-agent-input` now expects the HTML pair's separate
    comment block, since a56739309.
- Live on default (JVM mode): `history-entries` for root carries `:errors` on
  `(dir seon.fn)`, currently the as-of refusal above.
- Timings on default, root agent (39 evaluations, 14 runs), warm:

  | probe | before (derived) | after |
  |---|---|---|
  | `agent-history` | ≈29–32 ms | 32–37 ms |
  | `history-entries` | ≈22 ms | 25–28 ms |
  | `branch-errors :all` | — | 4.1–5.2 ms |

  - Before was derived as after minus `branch-errors`; I could not run HEAD's code
    without redefining default's Vars.
  - First calls: `agent-history` 156 ms, `branch-errors` 76 ms (the as-of projection).
  - My first version called the window reader once per run. That added 10 ms per
    render, 30%, so the windows are now derived once per render.
  - Allocation not measured.

## Verification limits

- No paid or virtual turn drove a real core fault inside an agent's evaluation. The
  regression builds the timeline with `fault/fault!` and settled rows transacted
  directly, as the neighbouring transcript tests do.
- The value renderer bounds the text; the regression does not exercise a bound.
- RESET NEEDED: no.

## Released paths

- `src/seon/repl.clj`
- `src/seon/render/transcript.clj`
- `resources/seon/schemas/seon.repl.edn`
- `test/seon/render/transcript_test.clj`
- `test/seon/repl_grammar_test.clj`
- this note
- the new issue file
