---
title: "mine 1: the first ranking of wasted turns by cause, over the five kept chains of live run 7"
type: evidence
status: "2026-10-16, lane miner-m1: seon.mine (M1 situations, M2 the first four waste rules) run read-only on default's kept chain branches"
---

# Mine 1: wasted turns by cause, live run 7

Owning plan: [self-improvement-loop.md](self-improvement-loop.md), phases M1 and M2. Corpus: the five chain branches
of [live-run-7.md](live-run-7.md), read on default (pid 18697) from my own agent's SCI branch (`miner-m1`, no request
as root, nothing written to main or the chain branches). `seon.mine/kept-branches` finds them from main's facts:
the four open chains by `:seon.chain/branch`, the merged one (`fddd`, whose chain fact is gone) by its attempt's
`:seon.task/branch`.

## What the miner records

`seon.mine/situations` answers one `:seon.mine/situation` per model call of each attempt (a step is one eval request;
its evaluations are what the agent was shown):

- what was shown, per evaluation (`:seon.mine/shown`): its form and class (`seon.mine/classify`, moved from
  `seon.competency`), its handle, the selected render (`:seon.eval/renderer`), value class, printed and whole tokens,
  the printer's cuts read back from the printed value (marker, continuation form, handle, path), its error and who made
  it (`:seon.mine/error-producer`: the error's type, else its diagnostic's operation), and for a doc or source read its
  subject and whether the subject is one of the task's references the current-state block resolves;
- the forms the agent wrote at the next call (`:seon.mine/next`);
- the attempt, its task, its end and whether main records it merged;
- the next forms charged as waste (`:seon.mine/waste`), each under its rule with the evaluation it repeats or digs
  into and its cause; and every producer of what was shown as a cause candidate (`:seon.mine/causes`).

Waste rules (the plan's step 2, first slice): `:repeats-error` (the exact text of a form that erred), `:redundant-in-ns`
(an `in-ns` started in the namespace it names), `:follows-cut` (names the handle of a cut value), `:reads-handle`
(names an earlier value's handle), `:re-read` (a doc or source read of a subject already read, or an identical read).
The plan's fourth rule, a search the plan could have named, is not in this slice.

Two inputs the plan names are not stored, so the record cannot hold them: the value's schema (an evaluation stores
its value class and selected render, not the schema the render was selected by), and the context each call showed
(the block is rendered live and not stored; `seon.context/current-state` cannot be re-derived as of a past `t` on a
branch: `datahike.api/as-of` of a branch value refuses in `seon.cluster.registry/fork-point`, commit nil).

## Ranked causes (five chains, 195 steps, 501 forms, 78 wasted forms)

| rank | cause | wasted forms | wasted turns | rule |
|---|---|---|---|---|
| 1 | render `my.program/render-source-ai` | 35 | 28 | re-read |
| 2 | context `seon.context/current-state` (its `:seon.context/namespace`) | 28 | 28 | redundant in-ns |
| 3 | error `seon.turn.batch/form-facts` (a refused `require`: cyclic load or admission) | 7 | 4 | repeats error |
| 4 | error, untyped (`Attempting to call unbound fn`) | 2 | 2 | repeats error |
| 4 | error, unresolved symbol | 2 | 2 | repeats error |
| 4 | printer `seon.render.value/show` (a value shown with no render) | 2 | 2 | re-read |
| 7 | error `:seon.schema/validation-refusal` | 1 | 1 | repeats error |
| 7 | error `seon.batch/derive-step` | 1 | 1 | repeats error |

Per chain (waste forms / steps with waste): 42e0 13 / 9, 91a8 24 / 16, ce9d 20 / 17, fd3b 14 / 12, fddd 7 / 7. The
redundant `in-ns` count, 28, is the 28 identical `in-ns` live-run-7 counted by hand (ce9d 15, fd3b 10, 42e0 3).

What the top three say:

1. **Source re-reads (35).** The most re-read: `(my.program/source 'seon.turn/render-ai)` 8 times (91a8), a proof test's
   source 4 times each in 42e0 and fddd, `seon.render.transcript/render-ai` and `seon.text-render-support/ai-face` 3
   each. 20 of the 37 forms classed re-read name a subject in the task's own references, which the block already resolves as
   must-reads; 22 first reads were too. The candidate producers: the source render, and whatever drops the earlier
   reply from the next prompt (the transcript's history). Which of the two it is needs the prompt the call saw, which
   is not stored; the next task should check `seon.ai` transcript assembly for whether an earlier source reply is still
   in the prompt when the agent re-reads it.
2. **`in-ns` into the namespace the agent is in (28).** The block shows `:seon.context/namespace` (nested under
   `:seon.context/turns`) and help says "a repeated in-ns is a wasted form", yet ce9d and fd3b open nearly every reply
   with `(in-ns 'seon.effect)` / `(in-ns 'seon.repl)`. `seon.context/step-start` offers no `in-ns` there (it offers one
   only when the agent is elsewhere), so the producer left is how the block places the namespace.
3. **A refused `require`, sent again (7 of 13 error repeats).** `seon.turn.batch/form-facts` refuses
   `(require '[seon.render-bench :as bench])` in `seon.error` as a cyclic load and its message says where to require it
   from; agents sent the identical form again 7 times. Its error fills `:seon.cluster.eval/error`, and the reply's
   value is `nil`.

No form in the five chains names a handle (`result/e...`): `:follows-cut` and `:reads-handle` are 0. 13 evaluations
were cut (`my.fs/section` 7, `seon.context/step-verdict` 2, `my.task/current` 1, others), each with a continuation
form; none was followed, so a cut here either was not needed or the agent read the whole file again instead
(`my.fs/section` of the same file 4 times on 42e0).

## Cost

`seon.mine/situations` per attempt, interpreted in SCI on my branch with instrumented contracts: 36 to 62 ms on the
first pass (35 to 40 steps, 68 to 120 evaluations; 243 MB allocated for all five), and `seon.mine/kept-situations`
over all five 54 ms warm. Work: three queries per attempt, one edamame read per form, and a printed value read only
when it was cut or erred. Compiled on default it was not run (uncommitted code is not loaded into default's JVM).

## Proofs

- `seon.mine-test/situations-name-each-steps-render-cuts-and-next-form`: a fixture-model chain of three scripted calls
  through `seon.task/chain!` (the bench's `seon.competency-grade-test/launched` with a scripted answer); the second
  call sends the continuation form the first reply's cut offered, read from the model's own request. The records name
  the source read's render, the cut (form, handle, path), the next forms, and the waste (`:redundant-in-ns`, then
  `:follows-cut` by the cut evaluation).
- `seon.mine-test/a-repeated-in-ns-is-charged-to-the-namespace-the-block-shows` and
  `seon.mine-test/each-waste-rule-charges-the-producer-of-what-was-shown` over pulled-shape data through
  `seon.mine/steps`.
- `bin/seon check` run `440d6336cce5`: executed 7, pass 20, fail 0 (with `seon.competency-grade-test`, whose
  classify moved).

## Findings outside this lane

1. **The recording transaction of my branch's edit-hook evaluations is refused**: each REPL entrance of `seon.mine`
   on `miner-m1` answered `:seon.db.write/error` "Conflicting upsert: \"seon.fn.index/0\" resolves both to 100388 and
   100384" from `seon.db/transact!`, and an `install-row!` error "Cannot interpret seon.mine/charged: Unable to
   resolve symbol: charge" recurred on branch `:agent-78f4967727cf` (occurrence count 12) after the definitions had
   been evaluated in order.
2. **Auto-require reads quoted data as references**: `(def ^:private read-heads '#{my.program/source ...})` made
   the REPL auto-require `my.program`, `my.task`, `seon.context`, `clojure.repl` (finding `:auto-required`); a quoted
   symbol in a data literal names no namespace to load.
3. **Every SCI request on my agent's branch cost 0.7 to 0.95 s and 0.9 to 1.3 GB** for forms that ran in under
   100 ms (`:seon.cost` of the replies), against the one-second rule.
