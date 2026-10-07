---
type: notes
status: review of a landed diff
created: 2026-10-07
lane: review-s5a-opus
reviews: 7b5535cf6 (s5a-reply)
---

# Review (Opus) of 7b5535cf6: the reply from the held receipts; problem routing deleted

Verdict: **keep, with a fix-forward list.** Nothing found makes the reply differ from what the SCI settlement
stored, and the routing deletion is complete in code. The main gaps: JVM replies now differ from the agent's next
context (the producer defect behind it is still there), three changed functions have no contract, the
refused-but-saved reply arm has no passing test, and one schema attribute was left with no producer.

Probe: default pid 91875 (started 12:18Z, source e80131ef1, which contains the change), my agent
`review-s5a-opus` on its own branch, MCP `eval_clj` only. No prototype code was loaded into default. Measurements
ran in a throwaway `bin/seon check` JVM.

## Findings, by severity

1. **MEDIUM. A JVM reply is not what the agent's next context reads. The cause is a producer defect the lane
   worked around instead of fixing.**
   - `seon.turn.batch/record-facts` → `seon.turn/record-evaluated-call` records the JVM run with
     `:seon.turn/reply-size` but without `:seon.turn.work/situation :call`. An SCI MCP run carries `:call`
     (pulled live: JVM run `20294131050f` has none; SCI run `86df57ebce49` has `:call`).
   - So `seon.repl/entity-emission`'s `changed?` query marks every later JVM evaluation as changed since the
     agent's last turn. Its stored history prints `;; changed since your last turn` and no `:ms`.
   - Live: the JVM reply to `(+ 40 2)` was `{:value 42 ... :ms 0}`. `history-entries` for the same run prints
     `#:seon.repl{:value 42, :result result/e13405c28e1b5}`, without `:ms`.
   - The mcp.clj namespace doc now says the reply is "printed as that agent's next context reads it". That is
     false for JVM mode.
   - The design deferred this to S4 ("changed since" deleted). Until then the producer fix is about one line:
     record the call situation.
   - Task `seon.turn.batch/a-jvm-run-is-recorded-without-its-call-situation` (d1b88749c142).
2. **MEDIUM. Changed functions landed without contracts.**
   - Three changed functions have no `:malli/schema`: `seon.render.transcript/candidate-history` (rewritten into
     the held arm), `seon.turn/asked-value` and `seon.turn/evaluation-terminal-data` (problem threading
     removed).
   - AGENTS.md says merge acceptance refuses a changed function without a complete contract.
   - Smaller point: the new contracts use `:maybe` in three places (`observed` input, `with-saved` input,
     `closed-in?` input), which the owner's schema rule disallows. Absence should be no key; for example,
     `observed` could take `(or sources [])` at its two callers.
   - Task `seon.turn/s5a-changed-functions-landed-without-contracts` (b46ea832c5f0).
   - `src/seon/render/transcript.clj` is now claimed by lane s2a-code, so the `candidate-history` contract goes to
     that owner as a patch.
3. **MEDIUM. The refused-but-saved reply arm has no passing test.**
   - The arm: `settle-batch-refusal!` or `settle!`'s refusal arm with `closed-in?` true, answering each form's
     saved receipt beside the refusal.
   - The test that would cover it, the `evaluate_source_test` block "a refused multi-form settlement answers its
     refusal beside every evaluated value", is red on both parent and change (HEAD lines 134/138/149, change lines
     136/140/151; existing task `seon.turn/a-refused-multi-form-settlement-returns-nothing`, c4309801a9fe).
   - `seon.turn-test/a-form-whose-facts-are-refused-says-it-was-not-admitted`, which the landing note cites for the
     not-admitted arm, errors on both (task c1c8c0a34adc).
   - The new MCP regression covers only the skipped-receipt refusal (a refusal whose receipts the writer did not
     apply).
   - The code reads correctly: `closed-in?` checks for a `:seon.turn/closed-tx` datom asserted by the same
     `open-run-tx-call` block that carries the receipts, and `:attribute-refs?` is false on the live store, so `:a`
     is a keyword. Still, this arm's behavior is **uncertain**, not proven.
4. **LOW. The routing deletion left one dead attribute.**
   - `:seon.problems/id` now has no producer. Its only value came from `form-problem`; it was never stored by any
     src writer, even on HEAD^.
   - It is still declared (`seon.problems.edn :id`, members in `seon.cluster.eval.edn` and `seon.turn.work.edn`),
     pulled by `seon.render.transcript`'s attribute list and read by `seon.turn/form-settlement`.
   - Task `seon.problems/problem-id-has-no-producer-after-routing-was-deleted` (6779fdb78ac2).
   - Stale current claims:
     - `docs/seon/specs/errors.md:354` still names `seon.cluster.agent/run-observation`.
     - Task `seon.mcp/observation-reads-the-run-twice` still describes the deleted reread in its intent (its
       must-read was updated).
5. **LOW. The `set!` print-length row of the regression asserts nothing about print length.**
   - The row only checks that `:seon.repl/value` is present.
   - Live, after `(set! *print-length* 2)`, `(range 5)` shows `(0 1 2 3 4)` and `(vec (range 3000))` shows 32
     elements, although every receipt of the run stores `:seon.print/length 2`.
   - Held and stored replies agree, so this is not caused by the diff. It is a pre-existing defect the new table
     row cannot catch.
   - Task `seon.sci.eval/a-forms-set-print-length-is-recorded-but-not-applied` (1b420c6cb408).
6. **LOW. The reply is still not total when a fault read is refused.**
   - `held-entry` → `fault-values` → `read-or-throw`, and `with-errors` throws on a non-string floor result. Either
     throw happens inside `seon.mcp/observed`, so every value of the run is replaced by the MCP boundary's error.
   - This is the same behavior as the deleted `run-observation`. The held path could have attached the fault-read
     failure beside the values instead.
   - Not filed separately: it belongs to the errors S-reads conversion of `branch-errors`/`read-or-throw`.
7. **LOW, process.**
   - The lane's measurement script (`scratchpad/s5a-reply/s5a_measure_test.clj`) was not committed (AGENTS.md:
     "Commit measurement scripts").
   - My version is committed beside these notes as `review-diff-opus-measure.clj`.

## Claims checked

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | SCI reply = stored, ordinary and edge rows | **supported** | One live SCI run (prose comment, `println`, def note, `#'clojure.core/inc` declared renderer, `set!`, `(range 5)`, `/ 1 0`, two `in-ns`, a 3000-element value with `:large-value` finding): all 10 reply texts are byte-identical to `history-entries` on the run (`rv-stored`). Also matched: 3000-line output, def of a 50 kB string, `my.turn/complete` (completed disposition). Reader errors stay in the reply (`(vector ::nope.alias/k)` between two forms; alone). |
| 1 | JVM reply = stored | **contradicted (deliberately)** | Reply keeps `:ms`; stored history drops it and prefixes "changed since". The producer is finding 1. |
| 1 | Faulted row | supported by the lane's green regression (check4, run af63c1c52bc3, 30/30); not re-run live | `fault-values` pulls the window refs on the settlement's db-after, the same code `branch-errors` uses |
| 1 | Refusal, skipped receipts | supported by the regression | `closed-in?` false gives no `:saved`, and `sci-run` answers the refusal alone |
| 1 | Refusal, saved receipts; not-admitted | **uncertain** | Finding 3 |
| 1 | Positional zip in `observed` | supported | the held arm of `history-entries` maps 1:1 over `evaluated-sources` and filters only `:eval` kind, which every held entry is |
| 2 | One converter | **supported** | `held-entry` serves both the MCP reply and the web preview (`render/web.clj:1413` → `history-entries`). Stored history uses `receipt-entry` directly. The `(or response shown error "")` fallback in `observed` predates this change: it returns stored text raw when `repl/response` is nil (empty source) and prints nothing new. |
| 3 | Routing removal complete | **supported in code**, with residue | `rg` over src/resources/test finds no `form-problem`, `assignment-value`, `planner-scoped-attempt?`, `problem-id`, `run-observation` or the two delays. Residue: finding 4. Kept by design: `form-settlement`'s `:routed`/`:owner-fixed` states over explicit messages. The proof of task 5e7b36bfd02d, `seon.cluster.turn-test/a-red-form-stays-with-its-author-and-the-fold-continues`, is green in check6. |
| 4 | Cost | **supported (allocation and calls), wall time not reproducible under load** | Table below |
| 5 | Contracts complete | **contradicted** | Finding 2 |
| 6 | Pre-existing reds identical on HEAD^ | **supported at the test level** | Sorted red-test sets of check-head3 (2318e41ae2a1, HEAD^) and check6 (659f66f8104f): identical except `seon.turn-test/transitions-agree-with-the-model`, red only on HEAD^. Assertion lines differ only by the diff's line shifts, plus `host_test.clj:144` and `evaluate_source_test.clj:187-188` (single runs). The lane calls those a fixture flake (task cce8dbd3d718, a random digit-first handle). Plausible from the task text, but not reproduced: still uncertain. |

## Cost re-measured (throwaway check JVM, HEAD cb69cd55f, load average 25-32)

MCP pass of n × `(+ 1 1)`, three runs after one warm-up, JVM-wide allocation; script `review-diff-opus-measure.clj`.

| mode n | ms | MB | armed calls | lane's change row (ms / MB / calls) |
|---|---|---|---|---|
| sci 1 | 67-98 | 51-52 | 7.0-8.4k | 35-50 / 38-51 / 7.0-7.4k |
| sci 10 | 125-132 | 90-106 | 28.8-30.2k | 59-72 / 89-106 / 30k |
| sci 100 | 705-866 | 562-580 | 249k | 292-341 / 539-576 / 245k |
| jvm 1 | 31-49 | 19-24 | 5.2-5.7k | 18-23 / 20-24 / 5.7k |
| jvm 10 | 79-106 | 56-65 | 24.8k | 43-49 / 55-65 / 24.6k |
| jvm 100 | 570-686 | 427 | 221k | 247-316 / 424-426 / 219k |

- Allocation and armed calls reproduce the lane's numbers. Wall time is about 2× because the machine was
  loaded (load average 25-32: default plus other lanes' check JVMs). I could not re-run the parent: `bin/seon
  check` always builds on HEAD.
- **The reply is now negligible.** `observed` over 100 held receipts takes 8-10 ms, 16-17 MB and 5.6k calls,
  down from the lane's 86 ms / 208 MB for the deleted reread. It still runs 5 `db/q`; the shared ones are
  `unanswered-wakes` and `latest-answering-turn-t`, reached through `config/effective`/`result-caps`.
- **What dominates the remaining ~300 ms (unloaded) and ~560 MB per 100 SCI forms.** The turn
  (`evaluate-source!`) takes 702-719 ms and 574-588 MB under this load; the reply takes the rest. Inclusive
  profile growth for one SCI-100 pass:
  - `settle-batch!` 346 ms: `seon.db/q` 332 calls / 247 ms, about 3.3 queries per form at settlement.
    `evaluation-terminal-data`'s `last-ordinal` and `triggered-agent-form?` account for 200 of them (task scope
    S5, as the lane said).
  - the fold (`seon.batch/evaluate-batch`) 271 ms, of which `seon.sci.observe/observe` takes 196 ms (100 calls,
    about 2 ms per form);
  - `seon.db/with-declarations` 871 calls / 263 ms in all.
- **JVM 100.** `evaluate-batch` takes 602 ms. Per form:
  - `seon.turn.world/with-world` 4.5 ms;
  - `jvm-show` → `sci.eval/shown-result` 2.4 ms;
  - `config/effective` twice per form (201 calls, 137 ms), from `sci.eval/database-effective-config`: the whole
    configuration is recomputed per form, when it is once-per-run work;
  - `seon.sci.kernel/public-functions-in` once per form (100 calls, 103 ms);
  - `db/pull` 3 per form.
- Allocation is about 5.6 MB per SCI form and 4.3 MB per JVM form, all inside the turn and the fold.
  `profile/explain` does not attribute bytes per function. Splitting them needs a per-thread bracket inside
  `settle-batch!` versus the fold, which the next performance lane should do before ranking. Unmeasured, not a
  pass.
- Per-form `config/effective` and `public-functions-in` look like the cheapest next wins. Both are work per form
  that belongs once per run.

## Not verified

- I could not re-run the parent: `bin/seon check` builds on HEAD and worktrees are not authorized.
- The interrupted-evaluation (time-limit) reply was not probed.
- The faulted row was not re-run live; I relied on the lane's green regression.
- Default needs no restart or reset for this review.
