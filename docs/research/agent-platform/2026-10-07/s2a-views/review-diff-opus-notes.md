---
type: review
status: landed diff 25bb94be0 (lane s2a-code), Opus; Astra reviews independently
created: 2026-10-07
tags: [review, debug, deletion]
---

# Review of the S2a landed diff (25bb94be0)

Read: CLAUDE.md, `lane-rules.md`, `design-notes.md` v5, `code-notes.md` and the full diff. Default was running
25bb94be0 (`bin/seon status`: source root `data/source/25bb94be0…`, pid 13771). The tests and source of HEAD
(21e9401ca) are identical to 25bb94be0 (`git diff --stat 25bb94be0 HEAD -- src test resources` is empty).

**Verdict: keep, with the fix-forward list below.** No deleted name is still called, the page does no assembly,
the view assembles once when opened, the reds are identical to the parent's, and `output.css` is the exact build of
the committed `input.css`. The gaps: the refusal regression the design promised was not written, there is dead
code the cut orphaned, five changed functions have no contract, and stale comments and docs remain.

## Findings, ranked

1. **The refusal regression in design §4.5 item 1 was not written** (medium). No added test fetches `?sent=true`
   for an agent whose prompt refuses. The one new assertion,
   `web-context-test/the-context-view-is-computed-only-when-opened`, checks `.seon-data-panel` in the body. A refusal
   prints through the same `value/html`, so that assertion passes whether the view shows the context or a
   refusal. It does not prove that the context is shown.
   - I checked the refusal path by hand on my SCI branch. `(seon.cluster.prompt/prompt (seon.db/db) {… :seon.boot/cluster-name "no-such-cluster" …})`
     returns the `:seon.cluster.prompt/error` value ("Effective configuration requires a matching cluster entity…").
     `seon.render.value/html` of that value, without `:seon.db/db` and with root `[::context "x"]`, returns hiccup
     (10,164 chars as `str`, 5 ms).
   - So the path works today but nothing guards it.
   - Fix forward: add the case to the same deftest, and assert on the composed value's key in the success case.
2. **Dead code the cut left behind: `seon.cluster.prompt/agent-calibration`** (medium). Its only production
   callers were `outline-calibration` and the ledger. Both called it through `requiring-resolve`, which clj-kondo
   cannot see (parent transcript.clj:1461 and :1768). At 25bb94be0 only `test/seon/cluster/prompt_test.clj`
   (5 calls) reaches it.
   - How I checked: a static analysis of src and test, on parent and change, found no other Var orphaned by the
     cut. The quoted symbols in the deleted lines were `prompt/agent-calibration` (2), `prompt/prompt`,
     `transcript/prefix-subject`, `value/render-ai` and `sci.kernel/context-projection`. Only the first lost all
     its callers.
   - Task `seon.cluster.prompt/agent-calibration-has-no-caller-after-the-debug-cut` (`dc19491ceb10`).
3. **Five functions this diff changed have no contract** (lane-rules rule 5; CLAUDE.md "a new or changed function
   without a complete contract"). They are `seon.render.web/debug-response`, `debug-query`, `debug-query-strings` and
   `shell`, and `seon.render.transcript/session-header`. `sent-context-html` keeps its contract
   `[:=> [:cat :seon.render.web/service :seon.agent/id] :seon.render/hiccup]`, and `derive-context!` keeps its
   contract too.
4. **The four pre-existing reds had no task** (low; lane-rules: "pre-existing is not a reason to stay silent"). The
   code notes list them but name no task. Of the six reds, the two `turn-test` reds already had tasks: `ec9e966a44a9`
   and `c1c8c0a34adc`. I filed four tasks:
   - `seon.render/target-profile-hands-the-printer-a-vector-requery-id` (`d9178db45287`). **Root cause confirmed**:
     `seon.render/target-profile` (render.clj:185-187) sets `:seon.print/requery-id` to
     `[:seon.render.call/id id]`, a vector. But `seon.render.value/tuple-node` declares the argument
     `[:maybe :qualified-symbol]`, so the cut refuses inside `make-node` (value.clj:1054) and falls back to the
     whole value. Result: a cut of 1,931 chars against an uncut 1,553
     (`transcript-test/the-transcript-is-whole-and-the-ai-boundary-elides-it`).
   - `seon.render.web/an-unrelated-transaction-re-walks-the-acquired-history` (`a83707de68e0`).
   - `seon.render.web/root-page-renders-inbox-and-status-with-all-attribute-reads` (`5135c292e279`). The offenders
     are `seon.cluster.message/render-inbox-html` and `seon.cluster.status/render-html`.
   - `seon.render.web/the-page-misses-a-newly-adopted-commit` (`049c5dd83424`).
5. **False current claims left in source and docs** (low):
   - transcript.clj:1136-1151 still carries the `;;; The outline` banner. Its text ("THE SESSION PANEL IS AN
     OUTLINE…") now sits above `render-runtime-html`.
   - `docs/seon/specs/render-dedup.md` §3 "Cost target" still names `reread-summary` (transcript 1306) and session
     render. §5 item 1 still describes `render-session` (transcript.clj:1439) as a live red.
   - `docs/seon/issues/debug-reread-summary-duplicates-shown-value-and-diff-owners.md` concerns `readable-shown`,
     `changed-paths` and `reread-summary`, all of which are deleted. The issue is dissolved but still open.
   - `docs/seon/schedule/README.md:122` and `triage.tsv:48` still schedule
     `seon.render.transcript/fault-problems-counts-occurrences-in-prose`, which the diff dissolved. The schedule is
     root's to edit.
6. **Formatting** (low): web.clj keeps 11 blank lines where the graph Vars were (after `debug-turn-response`'s
   former place, before `sent-context-html`), and `"Loading the agent’s current record…"]]] ]}))` has a stray
   space. The `(html-response (shell …` indentation is also off.
7. **Printed-text assertions were rewritten in a test** (low).
   `run6-stall-test/a-stalled-session-header-names-its-provider-refusal` now defines
   `header #(pr-str (transcript/render-agent-header …))` and asserts `str/includes?`. It could select the
   `seon-session-state` span's data instead. The pattern predates this diff, but the diff rewrote these lines.
8. **The walk-level branch in `seon-graph.js` is dead** (low; the code notes record it). The branch is lines
   124-128 and the walk layout at 249-262. Nothing emits `level` or declares `--seon-graph-level-N` any more. The
   script degrades safely: an empty property is skipped. The file is unclaimed.

## Claims checked

| # | Claim | Status | Evidence |
|---|---|---|---|
| 1 | Nothing deleted is still referenced | **supported** for code, CSS, schema, routes and JS | `git grep -w` of all 71 deleted def names over src, test, resources, .agents, bin, script and docs/seon at 25bb94be0. The only hits are prose (docs/issues, render-dedup, schedule, one task intent that says the function was deleted) and same-named unrelated Vars (`seon.ai/attempt-html`, `db/error-problem-sentence`, `bin/seon-hook` `finding`). Of 86 removed `.seon-*` selectors, none is emitted under src or js (exact-token grep); no dynamic `"seon-…-"` prefix matches a removed family. `prefix-subject`, `::transcript/ledger?`, `?prompt`, `?outline`, `?turn` and `?n` have no remaining reader. Task `:must-read` re-points resolve. |
| 1b | `output.css` is the build of the committed `input.css` | **supported** | `bun …tailwindcss/cli -i input.css` run on a `git archive 25bb94be0` copy gives no diff against the committed `output.css` (78 ms) |
| 2 | Nothing that should be gone is still live | **contradicted** (finding 2) | `agent-calibration`; clj-kondo `--cache false` on both files gives 0 errors and no unused requires (28 warnings against the parent's 41) |
| 3a | Zero assembly on page load, one call when opened | **supported** | The test counts with armed `prompt/prompt` and `acquire-context!` (0/0 on load, 1 prompt on open), and was green in the commit check. Live: the page is 3,213 B with no context text. |
| 3b | The refusal path renders | **supported by hand, unguarded** (finding 1) | as above |
| 4 | Timings on default | measured, below | |
| 5 | The pre-existing reds are identical on the parent | **supported** | The lane's `check-parent.txt` (run 2fd42bb2fdc7) and my `bin/seon check` of HEAD (run 480c9d383349: 6 executed, 5 fail, 2 error, 5.1 s) agree. Same names, same assertions and same values: walks 1; unbounded `#{render-inbox-html status/render-html}`; the adopted commit id missing; the `tuple-node` refusal at transcript_test 480/485 (parent 481/486, shifted by the removed require line); and the turn-test fixture `[:seon.ns/name user]` and `not-admitted` `:seon.error/layer` errors. The parent's seventh red (turn-details) was converted and passes in the commit check. |
| 6 | Contracts on new or changed functions | **contradicted** (finding 3) | |

## Timings on default (25bb94be0, root with real history)

Measured from a JVM-mode `eval_clj` as agent `review-s2a-diff-opus`, with `java.net.http` GETs to
`127.0.0.1:7994`. Allocation is `getTotalThreadAllocatedBytes`, which is process-wide, so the MB figures are upper
bounds.

| Request | First (after my eval's write) | Warm | Body |
|---|---|---|---|
| `/agent/root/debug` | 13.3 ms, ≤ 29 MB | 3.0 ms, ≤ 3.3 MB | 3,213 B |
| `?sent=true` (datastar) | **544 ms, ≤ 1,135 MB** | 13.7 ms, ≤ 22.9 MB | 25,160 B |

curl agreed: page 13 ms then 3 ms; `?sent` 538 ms then 11-12 ms. Before the restart (parent code), the code notes
measured the page at 6.4 s first and 1.0-1.15 s warm, with a 158 KB body.

A write by an agent other than root (my own run) made root's next `?sent` cold again. Opening the view after any
write costs about 0.5 s and up to about 1.1 GB. Root accepted this as gap 1 until S4, and S4 must bring it under
budget with an S0 row. Opening the view stays over budget until then.

The context shown on default is root's composed map: 164 contributions, 67,354 estimated tokens. The text is cut
after about 400 chars at `…+190291` with no continuation. That is task `e3d5afaaa903`, accepted as option (a).

## Other observations

- `bin/seon status`: default `:faulted` with 3 open faults. `seon.turn/show-row` is listed without `:tasks`,
  although task `81b1ff4eed9e` (`a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil`) describes it. The
  fault is not linked to its task. This is outside S2a's files.
- The refusal from calling `prompt/prompt` without `:seon.sci.admit/caps` repeats the whole request in each of its
  two problems' `:offending`, including the SCI ctx environment. That is bulk in an error. It is outside S2a.
- `(doc seon.task/create!)` still prints every key schema twice (already recorded by S2a's design, owned by S1).

RESET NEEDED: no.
