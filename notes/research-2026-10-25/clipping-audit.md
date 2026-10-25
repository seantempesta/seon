# Clipping audit: context and stored results (read-only)

Scope: `/Users/sean/src/seon-next` (main) and `/Users/sean/src/seon-next/.worktrees/agent-conversation` (jolt/agent-conversation). Worktree line numbers unless marked main. Owner's rule: data is clipped only by the one printer (seon.print / seon.print.select), at the very end, when printed into the model's context or a page. The full result is stored.

Verdicts: OK (the printer, or garbage not worth storing), PREMATURE (goes through a cut before the printer, or stores a cut instead of the value), SUSPICIOUS (looks tuned to a test).

## PREMATURE

### P1. Eval records store the printed cut, not the value (both trees)
- `src/seon/repl.clj:447` `answered` prints each response at eval time with `printed` (`repl.clj:341`), under the reply budget `:seon.config.render.agent/token-budget` 6144 tokens (`src/seon/print.clj:24`).
- `repl.clj:392-410` `record-details`: stores `:text` (the printed cut), `:response (dissoc r :value :text :recorded :fns)` (the live value is dropped), and `:line` (`repl.clj:405`, printed at `:seon.config.render/line-budget` 50).
- The full value exists only in process memory (`intern` into `result` ns, `repl.clj:460`; `:results` in agent state). Anything reading the stored record (the page, an outside agent after restart, `eval-messages`, `evaluations-text`) reads the cut. The cut names its handle form, so it is recoverable only while the process lives.
- Main does the same: `main src/seon/repl.clj:133` `(assoc m :printed text ...)`, `record-tx` stores `:printed`/`:shown`.
- Verdict PREMATURE on both. The printer is used, but at record time, and the stored result is the cut.

### P2. Last evaluations: budget and printer pass removed (worktree regression)
- `src/seon/context.clj:235-237` `evaluations-text`: `(str/join "\n\n" (keep :text rs))`. Each `:text` is already printed at 6144 tokens (P1). Nothing caps the total.
- `src/seon/turn.clj:46-49` `last-evals`: raw vector of the last turn's responses. `turn.clj:166` `ev (context/evaluations-text rs)`, stored as `:last-evals` in the basis.
- `context.clj:323-326` `screen-message`: `(:last-evals block)` appended as raw text after the printed block, outside `:context` budget.
- Main had `fold-evals` (`main context.clj:128`) and `shown-evals` (`main context.clj:157`): each folded item printed by the one printer at an equal share of `last-evals-budget` (= `:seon.config.render/evals-budget`), cuts reading through `(nth (my.turn/evals) k)`. The worktree removed both.
- `:seon.config.render/evals-budget` 8000 (`resources/seon/config/default.edn:548`, schema `resources/seon/schemas/seon.config.render.edn:14`) is now referenced by no code in `src`. Dead key.
- Consequence: one turn with many evals is sent uncut (the task note in `resources/seon/tasks/seon.context.edn:11` records a measured 259-item, 101k-token turn that the share fixed).
- Verdict PREMATURE, and the key is dead.

### P3. History drops whole records by a token estimate before the printer
- Worktree `src/seon/context.clj:273-287` `kept` (budget `:seon.config.render/history-budget` 32000, `default.edn:548`), applied at `context.clj:350`. Drops the oldest records whole, by `select/estimate` of their content; the count goes to `context.clj:355` `{:history {:left-out dropped}}` and `:history` in the screen block (`context.clj:323-325`).
- Main: `main context.clj:319` `kept-turns`, same pattern on turns.
- It is a separate cut, not the printer's selection. It says what it left (a count). Records remain in db.
- Verdict PREMATURE (mitigated by the honest note). The key's comment `default.edn:551-556` says the value was set for cost, not tests.

### P4. Cited source swapped for its doc by size, before the printer
- `src/seon/docs.clj:351-372` `resolve-ref`: a source whose `select/estimate` exceeds `:seon.config.render/reference-tokens` 3000 (`default.edn:561`) is replaced by the doc with `:left-out` and `:read (clojure.repl/source sym)` (`docs.clj:370-372`).
- Main has the same (`main docs.clj:323-347`).
- It names what it left and the full source stays in db.
- Verdict PREMATURE (a size cut before the printer; the owner's rule says print it whole and let the printer cut at its own bound).

### P5. Retrieval top-k cut before the printer
- `src/seon/context.clj:124-150` `similar`: `(take k)` over nearest function names, k = `:seon.config.render/similar` (default 0, off, `default.edn:571`). Each name is one line; `:more` names the form that searches further.
- No task in `resources/seon/tasks` sets the key (checked). The doc claims "a task turns it on in its :seon.task/config"; no task does.
- Verdict PREMATURE (a count cut with a pointer). Low volume, off by default.

### P6. List cuts in renders, before the printer (both trees; `render/ai.clj` unchanged)
- `src/seon/print.clj:135-144` `bounded` (`(take (config/value k) items)`) and `:127` `more` produce the `+N more: where` line. Callers in `src/seon/render/ai.clj`: `:72` names-part, `:82` problems (`:list/top`), `:183` rows, `:209` root-errors (`:list/top`, newest of `view-window`), `:253-254` running/ended (`:list/rows`, `:list/brief`), `:284` costliest functions (`:list/brief`), `:290` event stream (`:list/rows`).
- `src/seon/render/ai.clj:281` `(take-last (config/value :seon.config.render.list/top) (:evals a))`: the cost list reads only each agent's last 3 evals (`:list/top`, `default.edn:627`).
- `:223` `print/more open 1` is the same cut for open problems.
- Each names what it left and where, and each bound is config. The cut is still before the context printer, so the owner's rule applies.
- Verdict PREMATURE. Config keys: `default.edn:622-627`, `:648`, `:654`.

### P7. Nested per-field budgets inside renders (before the context printer)
- `src/seon/render/ai.clj:21-22` `line-budget` (50) and `part-budget` (256); `:33` `form-budget` (600); `prose-part` at `:38-42`, used at `:85-94`, `:117`, `:146`, `:159-163`, `:222`. `value-part` (`:44`) runs the printer at part budget.
- `src/seon/repl.clj:37-38` `form-text` at form budget (600).
- These are the printer, applied per field inside a render, so the cut happens before the outer context printer. Each names its cut through `(subs source at)` or a form.
- Verdict PREMATURE, mild: the printer is used, but the cut happens before the one final pass.

### P8. History rewrites a later response (`seen`)
- Worktree `src/seon/repl.clj:352-368` `seen`: each value an earlier response showed, longer than its handle form, is recorded in per-process state `(:seen @agent/state)`. `repl.clj:447` prints later responses with it, so a value appears as `(:value result/eN)`. `print.clj` passes `:seen` through to `seon.print.select/seen-form`.
- The record's stored `:text` (P1) is printed under this state, so a record is not self-contained: its text depends on earlier evals in the same process.
- Verdict PREMATURE (mild): the printer does the selection, but history rewrites the output before the stored result.

## OK

- `print/select.clj` `seq-cells` / `list-row`: the printer's own lazy limit.
- `print.clj:76-110` `prose`: the printer's own text cut, naming the form that reads the rest.
- `context.clj:310-326` `run-message`, `screen-message`: printed under `:context` budget 16000 (`default.edn:542`).
- `context.clj:60-66` `refused` and `context.clj:55`: dedupe of `:proof-red` reasons (shown with the proofs), not a clip.
- `context.clj:350-352`: `take-last` over kept records, consistent with `kept`.
- `context.clj:290` `records`: screen and context evals are excluded from history (`(not (#{"screen" "context"} by))`). They are re-run and resent each request, so this is not a loss.
- `root.clj:211`, `:514-516`, `lib/push-window`: root's view keeps the newest 20 evals and errors (`default.edn:36-40`). It is a view; the facts on main keep everything.
- `turn.clj:277` `:shown` log: waste bookkeeping only.
- `src/seon/render/ui.clj` and `render/proc.clj` caps (`cell-budget` 25, `list/stream` 60, `list/history` 20): page-only, out of this audit's scope.

## SUSPICIOUS

None found in the clipping path. No hard-coded test or task names drive a cut. The closest, `default.edn:548-556` (history budget 256000 to 32000), is a cost tuning with its numbers recorded, not a test tuning. The `:seon.config.render/evals-budget` 8000 is dead (P2), not tuned.

## Summary

- The worktree moved the last-evals share and the fold out of the printer's reach (P2): the key is dead and last evals are sent without a total bound.
- Both trees store printed cuts (P1) and cut history by size (P3), by doc swap (P4), and in list renders (P6).
- SUSPICIOUS: none.
