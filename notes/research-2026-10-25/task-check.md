# Task sanity check (read-only; main = fc9a10e8a, worktree base d92b8819a, task entries identical)

Common fact: NONE of the 5 proof tests exist on main. The context seeds each as a placeholder
`(deftest X (is false "write the assertion: the behaviour, on data"))`, status :undefined (:proof-unknown).
So "done" = the agent writes the real proof AND the code change; a proof that is written after the
fix can never be "red for the right reason" unless the agent runs it first. Three of five entries'
code claims are stale or thin.

## 1. seon.context/a-red-proofs-calls-are-its-recorded-refs: BROKEN (already done on main)
- Plan says context.clj:101-115 re-reads the proof source and ns-resolves symbols. Main's `seon.context/callees`
  (context.clj:108-120) is already the :form/refs query, minus tests (commit db0a9656 "callees reads the proof
  form's :form/refs, never its text"), docstring and all. Line 274 uses it. Nothing to change in src.
- Only the missing deftest is left; it passes on write. The agent has nothing to find, so it searches (no-progress).
- Runs: base cap30 no-progress/10; base-think green/16; base2 green/12; step1 np/13; step1b cap30; step1b-think green/20;
  step2 np/21,13; step2-final np/13. Verdict: TASK (obsolete). Green only under thinking, when the model noticed it is done.

## 2. seon.model/a-transport-failure-says-what-happens: clear but trivial (a docstring)
- Change: `seon.model/transport-error` docstring (model.clj:32-34, says "kept and retried after a pause"; fx/outcome
  re-queues only rate-limited). Entry cites both. Proof name `a-transport-failure-is-answered-to-the-agent` has no
  test; an existing `a-dropped-connection-is-a-transport-failure` is nearby. A docstring cannot be red; the proof
  must be written to assert the owner answers the agent (needs a fx owner harness: harder than the fix).
  Ambiguity: "until then nothing retries it" is fine. Verdict: clear, too easy to discriminate; the proof part is open-ended.
- Context gave everything (docstring, outcome, owner-proc source, doc section pointer). Runs: green in every run
  except step2b-interrupted (killed at 3 turns). 8-19 turns green; base 10/11, step 8/10/14. Model is fine.

## 3. seon.program/a-method-key-is-decoded-once: ambiguous / stale line refs, mid-hard
- Real defect: program.clj:328 stores `:dispatch (sedn/encode dispatch)`, `method-key` (:345) encodes [multi that-string]
  again, `remove-definition!` (:779) uses clojure.core/read-string on the inner string. Entry says program.clj:646 (now 779)
  and "audit row C10": stale pointers. "Encoded once inside method-key" collides with the same encoded string being
  stored as :method/dispatch (:385) and used by merge.clj, so the real change touches the extraction (:328), :385, :831
  and likely merge; the entry names none of these. Proof `a-removed-method-with-a-vector-dispatch-is-gone` not written;
  needs a program db fixture (test file is large), and red-ness depends on sedn/encode of a vector. Verdict: ambiguous (and
  near too-big).
- Runs: base cap30 green/12; base2 green/18; base-think none; step1 np/21; step1b np/21; step1b-think no-end; step2 np/27,21;
  step2-final green/20 (step2-final.cap30 first pass np/20); step2b green/9. So it IS achievable by the new context at times
  (2 of ~6) but mostly loops: looks like task + model (unknown which keys/files), partly context (no write-set, no
  "who calls method-key" in the first message).

## 4. seon.ref/a-summary-says-there-is-more: clear in intent, underspecified in form
- Change: `seon.ref/shown` (ref.clj:473-491), :summary branch. Add "how much it left and the citation that reads it at :doc".
  Format unspecified (key name? count of what: chars, sections?). The proof (`seon.ref-test/a-summary-names-the-citation...`)
  not written; the agent picks the shape and the assertion, so green is weak (it can pass a self-made test) but is a fine
  test of the loop. Also :seon.ref/view schema must gain the key (contract) and render consumers may break. Verdict: clear enough, small.
- Runs: green in nearly every run: base 5/18; base-think 25; step1 6; step1b 11/24; step2-final 20; step2b 9; failed: step2
  np/27 (looping reads), step1-cap12 cap. Context sufficient (shown source, reachable). Fit to measure with.

## 5. seon.render/spend-has-a-render: TOO BIG / vague
- "a named spend value with ai and html renders, on the page and at root's REPL" from fx :spent and :seon.fx/cost facts.
  Needs: a new schema'd value, two renders, wiring into seon.web page, a source for the value (fx proc state vs facts: the
  entry names both). No var named to change, no shape; proof `spend-reads-by-its-render` unwritten. The spec is a feature
  design, the owner's gate (§3 root's gate) says no new format without evidence. Verdict: too big + ambiguous.
- Runs: never green except base2 green/12 and step1b-think green/29; otherwise np/cap (26, 15, 12, 13, 8, 15). Failure = TASK
  (cannot tell when done; the model loops :no-progress, repeating reads).

## Summary by run (green counts)
base cap30: model, ref, program green (3/5). step2-final: model, ref green (2/5). Differences on program and context:
context never fails because of context quality (task is done); render fails because of the task; program is the only one where
new vs today may differ, and it flipped across runs (n=1 each): not evidence.

## Fit / fix
- Fit: ref (4); model (2) as an easy control. Program (3) only after the entry is fixed: name the files, correct line refs, state the stored value.
- Replace: context (1) with an unmerged, real task from tasks/*.edn whose src is not yet changed (check `git log -S` for each
  plan's claim before use); replace render (5) with a small one: a named existing var, a one-function change with a specific
  expected output. A bug fix with a known failing assertion (red on main) is better than doc/feature tasks.
- Add a preflight: each task's plan claims are true on main (grep the code it says is wrong) and its proof is red before the run.

## Top 3 things the context lacks across tasks
1. A way to run the proof and get its failure: the first turn shows status :undefined; the agent does not learn the command
   (`bin/test ns` / `(run-test-var ...)`: only visible in the refused :do) nor whether a proof is red at start.
2. The write set / owner of the change: which file and defn to edit and who calls it (callers/ :form/refs inverse), so it stops
   re-reading (the no-progress loops are repeated reads: program, context, render).
3. A done/stuck signal: a plan check ("is this claim still true on main?": context's task is already done) and what the agent
   did last (its edits so far, tests run, results) in the screen, so repeated reads are visibly repeats.
