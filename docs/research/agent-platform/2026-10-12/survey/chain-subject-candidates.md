---
title: "Survey chain-subject-candidates: subjects for the two-step DeepSeek chain (D10)"
type: research
status: complete, read-only; no source, task or test edits; one probe per candidate on agent branch subject-probe (SCI)
basis: "HEAD e42be1e67 (branch refactor/agent-platform); default at b14dc2b3f (behind HEAD) was read only through runtime_status, never queried for these candidates"
method: "Scan: python3 scratchpad survey/scan.py (defns of 6-35 lines referenced in any test file containing `(are [`), then each candidate read by hand and checked against the D10 criteria in docs/research/agent-platform/2026-10-12/hybrid-chains.md §7. Failed-attempt check: rg -l over chain-experiment-*.md and run-1*.md. Probes: mcp__seon__eval_clj mode sci, agent subject-probe, forms only, no def persisted."
---

# Survey chain-subject-candidates

## For root

- **No candidate meets all of D10 today.** The only ordinary functions with an existing `are` table are either on
  lanes' edit list (`seon.task/*`, `seon.batch/*`, `seon.turn/*`, `my.program/*`, printer `seon.print/path-form`), retired
  (`seon.sci.forgive/*`), or already failed twice (`seon.ref/section`).
- **Observed gap, no table yet:** `seon.error/message`, about 17 body lines. A map source with a whitespace-only
  `:seon.error/message` returns that whitespace, against its docstring "never absent, never blank". No `are` table exists.
  Fits D10 only if the Prove step creates the table, which breaks D10's "adds rows to an existing table" rule. Root decides.
- **Observed, previously failed:** `seon.ref/section` still throws on `"#"` and `"#the-goal"` (contract refusal from
  `seon.ref/locate`), and `ref_test` already has a red tagged table (`a-reference-with-no-path-answers-an-error-that-holds-no-path`).
  Excluded: chain 3 ranked it first, chain 5 rejected it (needs `seon.schema/register!` of `:seon.ref/section-error`).
- **Observed clean, rejected:** `seon.program/edn-symbol-text?` agrees with `clojure.edn/read-string` on all 21 probed
  inputs. Its `are` table is `program_test` line 57. No gap.
- **Not probed, rejected by criteria:** `seon.id/id` (identity encoding; stored ids depend on it), `seon.print/path-form` (printer).
- **Already filed:** none of these candidates has a task that names a table-test row. `seon.error/message` has no task
  (grep of `resources/seon/tasks` finds only callers' messages).
- **Not filed:** a task for the whitespace-only message gap in `seon.error/message`.

## Table

Candidates, in the order of the D10 criteria. "Observed" means reproduced by a probe at HEAD; "inferred" means read only.

| # | Function | Body lines | Existing table test | Edge input | Wrong output (observed) | Right output | Status |
|---|---|---|---|---|---|---|---|
| 1 | `seon.error/message` | about 17 (error.clj:100) | none (`rg` finds only `:seon.error/message` assertions as `string?` checks) | `{:seon.error/message "   "}` with failure `nil` | `"   "` (observed) | a non-blank sentence, per docstring "never blank" (inferred: the fallback to the root cause or "unclassified" text) | Gap observed; needs a new table test, so D10 criterion "existing table" fails |
| 2 | `seon.ref/section` (through `seon.ref/locate`) | 7 (locate) and 20 (section, ref.clj:399) | `ref_test` lines 195-208 (red, tagged `seon.ref/a-reference-with-no-path-answers-an-error-that-holds-no-path`) | `"#"` and `"#the-goal"` | throws `seon.ref/locate refused argument 0 path` (observed) | a `:seon.ref/section-error` value with `:seon.ref/section-missing :file` (per the task intent, inferred) | Gap observed; excluded by history (chains 3 and 5) |
| 3 | `seon.program/edn-symbol-text?` (private) | about 25 | `program_test` line 57, 8 rows | 21 inputs, including `"a:"`, `"a/b/"`, `"/x"`, `"a//"`, `"-"`, `"a#"`, `"a/:b"`, `"ab/"` | agrees with `clojure.edn/read-string` on all (observed) | agrees | No gap; rejected |
| 4 | `seon.id/id` | 6 (plus encoding) | `id_test` line 15, 22 rows | not probed | not probed | not probed | Rejected: identity encoding is fixed (`id_test` line 40 pins `ebc54726b35b`); changing it breaks stored ids (inferred) |
| 5 | `seon.print/path-form` | about 15 | `printer_elision_test` line 152, 6 rows | not probed | not probed | not probed | Rejected: the value printer is excluded from chain subjects (chain-3 brief) |

## Draft chain task entries (for candidate 1, the only one with an observed gap and no history)

These are drafts for root, not filed. Candidate 1 needs a new table test, which D10 forbids; the Prove step would have to
add `seon.error-test/...` as a new `are` in the same commit. Root decides before filing.

    subject:  :seon.task/name  a-message-is-never-blank-for-a-blank-source
              :seon.task/intent  "`seon.error/message` returns a non-blank sentence for every source, including a map whose
                                 :seon.error/message is whitespace."
              :seon.task/must-read [seon.error/message]
              :seon.task/kind :function

    prove:    :seon.task/name  prove-message-blank-cases
              :seon.task/instructions  "In test/seon/error_test.clj add an `are` table test tagged
                                 ^{:seon/tasks #{'seon.error/a-message-is-never-blank-for-a-blank-source}} with rows:
                                 {:seon.error/message \"\"} nil, {:seon.error/message \"   \"} nil, and a Throwable with
                                 a blank root cause. Each must answer a message that is not blank after trimming.
                                 Run it red on the branch before the fix."
              :seon.task/must-read [seon.error/message]

    implement: :seon.task/name  trim-blank-message
              :seon.task/instructions  "In seon.error/message, treat a whitespace-only :seon.error/message as absent
                                 (use str/trim before not-empty). Do not change the other branches."
              :seon.task/must-read [seon.error/message]

## Notes

- **Limit of the method.** The scan matches a function to a test file if the name appears anywhere in a file containing
  `(are [`; it does not prove the name sits inside that `are`. Candidates were then checked by hand. Files with `doseq`
  tables (209 test files) were not swept; a function covered only by `doseq` would be missed.
- **Probes.** Four SCI forms on agent `subject-probe` (EDN comparison, `seon.error/message` edges, `seon.ref/section` edges).
  Nothing was def'd persistently; the probe's `cases` def lived only in its SCI context. No run against default.
- **Surprise.** `seon.ref/section` throws instead of answering a `:seon.ref/section-error` for `"#"` and `"#the-goal"`, so
  ref_test's second `are` at line 203 is red. This matches chain 3 and is unchanged at HEAD.
- **Unverified.** Body line counts are hand counts; candidate 4 and 5 were not probed; default's health (degraded, five
  untested-hot warnings, `seon.print/node-child?` top) is a foreign condition noted from runtime_status, not checked further.
- **Time.** About 30 minutes, the budget. The candidate pool is thin because most `are` tables sit on the lanes' edit list.
