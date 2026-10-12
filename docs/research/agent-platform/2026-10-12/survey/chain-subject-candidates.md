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

## Second sweep (chain-subjects-2): more subjects

Agent `subject-probe-2`, SCI mode, forms only, no def persisted. Scan: `scratchpad/subj2/scan.py` (every top-level `defn`/`defn-` of 4 to 30 body lines in `src/seon` and `src/my`, excluding evaluator, writer, printer, boot, instrumentation, profile and host `*.jvm` namespaces; test references by name; `are` or `doseq` mentions). Only 447 candidates before exclusion; 61 `defn` sites carry an `are` mention. Result: **one candidate with an observed wrong output that meets D10; one weaker candidate with an inferred expectation.** The brief asked for 3 to 5; the rest were probed and rejected below.

### Candidates

| # | Function | Body lines | Existing tests | Edge input | Wrong output (observed) | Right output | Status |
|---|---|---|---|---|---|---|---|
| 6 | `seon.fs/under-path?` (private, fs.clj:80) | 4 | none by name; reached through `seon.cluster.store/admit-destructive-path!` (store_test 599-614) and `seon.fs-test` deletion tests | `"/"` ancestor of `"/x"`; `"/a/b/"` ancestor of `"/a/b/c"` | `false`, `false` (observed) | `true`, `true` (per its own docstring-less intent: "descendant is the ancestor or lies under it"; inferred from the `=` and `starts-with?` branches) | Gap observed. The wrong answer refuses a legitimate target (over-refusal), not an unsafe deletion. Edges that already work: `"/a/b"`/`"/a/b"` true, `"/a/b"`/`"/a/b/c"` true, `"/a/b"`/`"/a/bc"` false |
| 7 | `seon.fs/intermediate-paths` (private, fs.clj:276) | 7 | none by name | root `"/r"` with target `"/r"` | `["/r"]` (observed) | `[]` (inferred: intermediates strictly between root and target; no docstring) | Output observed; expectation inferred. Targets below root are correct: `"/r/a/b"` gives `["/r" "/r/a"]`. Weak: a Prove step must first decide the intent |

Reaching-tests result for candidate 6, interpreted, on probe branch `subject-probe-2`: `(my.test/check {:seon.test/changed ['seon.fs/under-path?]})` ran 1 test, 9 pass, 0 fail, 0 error, `:seon.test/passed? true`, 2,831 ms, 4,270.9 MB allocated (findings `:slow` and `:large-allocation`, as F27 reports for other subjects). The 9 reaching assertions are green on the current code, so a Prove step adds its red rows to `seon.fs-test` (ns `seon.fs-test`, file `test/seon/fs_test.clj`).

### Rejected after probe (no gap)

- `seon.fs/canonical-relative-path`: `"/"`+`"/x/y"` gives `"x/y"`; `"/a/b/"`+`"/a/b/c"` gives `"c"`; `"/a/b"`+`"/a/b"` gives `""`.
- `seon.fs/relative-path`: `"/"`+`"/x/y"` gives `"x/y"`; `"/a/b"`+`"/a/b"` gives `""`; `"/a/b"`+`"/a/b/"` gives `""`; `"/a/b"`+`"/a/c"` gives `"../c"`.
- `seon.edit/line-at`, `seon.edit/line-range`, `seon.edit/line-starts` (`"ab\ncd\n"` gives starts `[0 3 6]`; indices `0 2 3 5 6` give lines `0 0 1 1 2`; `line-range` 0..3 gives lines 1 to 1, 3..3 gives 2 to 2): correct.
- `seon.shell/stdin?`, `seon.shell/output?`: two keys give false, empty map false, nil false: correct.
- `seon.profile/bucket`: `0 1 2 3 4 7 8 1000 1024 1025` give `0 1 2 2 3 3 4 10 11 11`, consistent with `bucket-at` (`[b-1, b)` is bucket `b`): correct.
- `seon.profile/upper-us`: buckets `0 1 10 39` give `0 0 1 549755813`: consistent with the bucket edges.

### Hazard found by reading, not a candidate

- `seon.edit/occurrences` (private, edit.clj:350) loops forever when `sought` is `""`, because `indexOf` returns the same offset and the offset never advances. Not reachable through the public entrance `seon.edit/exact`, whose `:my.edit/old-string` schema is `[:string {:min 1}]` (resources/seon/schemas/my.edit.edn:19). The private helper has no contract of its own, so the schema is its only guard. Not probed in SCI, deliberately (a hang). Filing it is root's decision; a Prove step would need a red that calls the private var directly, which is a test of a path the schema already refuses.

### Draft task entries for candidate 6 (not filed; root decides)

    subject:  :seon.task/name  a-path-is-under-its-root-or-any-ancestor-directory
              :seon.task/intent  "`seon.fs/under-path?` answers true when the descendant is the ancestor or lies under
                                 it, including when the ancestor is `/` or ends in a separator."
              :seon.task/must-read [seon.fs/under-path? seon.fs/admit-destructive-path!]
              :seon.task/children [prove, implement]

    prove:    :seon.task/step :prove
              :seon.task/name  prove-under-path-edges
              :seon.task/instructions  "In test/seon/fs_test.clj (ns seon.fs-test) add rows, in a new `are` or doseq
                                 table, tagged ^{:seon/tasks #{'seon.fs/a-path-is-under-its-root-or-any-ancestor-directory}}
                                 for the 'under' law: (under-path? \"/\" \"/x\") true, (under-path? \"/a/b/\" \"/a/b/c\") true,
                                 (under-path? \"/a/b\" \"/a/b/c\") true, (under-path? \"/a/b\" \"/a/bc\") false. Run red on
                                 the branch before the fix."
              :seon.task/must-read [seon.fs/under-path?]

    implement: :seon.task/step :implement
              :seon.task/needs  prove-under-path-edges
              :seon.task/instructions  "In seon.fs/under-path?, compare canonical prefixes: strip one trailing separator
                                 from the ancestor (keeping `/` itself), then accept equality or a prefix of the
                                 ancestor followed by a separator. Do not change admit-destructive-path!."
              :seon.task/must-read [seon.fs/under-path?]

Candidate 7 is not drafted: its expected output needs a decision the owner has not made.

## Notes (second sweep)

- **Shortfall.** The brief asked for 3 to 5 new subjects. This sweep produced one observed gap that meets the criteria (candidate 6) and one weaker one (7). The scan's exclusions (evaluator, writer, printer, boot, instrumentation, profile, `*.jvm`, MCP, env, repl) removed most of the 447 defns, and most of the rest were already probed correct. A third-round sweep of `seon.reconcile` (`.cljc`, pure, unprobed here) and `seon.schedule/next-nominal-after` (DST overlap twin) is the next place to look; both were read, not probed.
- **Method limit.** The `doseq` detection is a file-level mention, not a table check, so the DOSEQ flags in the scan are not proof of a table. Candidates were checked by hand.
- **Unverified.** Body line counts are from the scan script. The reaching-tests run is one run on `subject-probe-2`, not on default. Default was not queried for this sweep. The `seon.fs/under-path?` right answer is inferred from its branches, since it has no docstring.
- **Surprise.** `seon.fs/under-path?` is the guard inside `admit-destructive-path!`: a `/` root refuses every target, which is conservative (fails closed), so the gap is over-refusal, not a safety hole.
