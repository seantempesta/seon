---
title: "Context review 1, lens implement-forgive: cold read of the first-call context"
type: research
status: complete
created: 2026-10-12
---

# Context review 1, lens implement-forgive

Reviewed file: `docs/research/agent-platform/2026-10-12/context-review/1-implement-forgive-forgiving-forgiven.md` (cold read; no Seon source or docs consulted).

## 1. First five forms

1. `(my.program/source 'seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing)`: the one red proof. Its source is cut at `"(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-te"…+236`.
2. `(my.program/source 'seon.sci.forgive/forgive)`: the function the intent names. Its body is not in the context.
3. `(my.program/source 'seon.sci.forgive/scan)`: the guess is that the stray-closer join is decided in the one pass, since `scan` has the `:seon.batch/repairs` output.
4. `(my.program/source 'seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart)`: the concrete failing case, cut at `"…+618}`.
5. `(in-ns 'seon.sci.forgive)` then a `(defn scan ...)` with `{:malli/schema ...}` metadata, to change the owner.

Guesses:
- Which function owns the bug: `forgive`, `scan` or `step`. The context gives no body.
- Whether `my.program/source` returns text or a form. `:seon.context/start` holds `(my.program/source 'seon.sci.forgive/forgiving-forgiven-text-changes-nothing)` as a call, not its value.
- Whether `(seon.context/current-state)` (message 1, line 22) returns anything beyond the block I already hold.
- Whether I may add a regression test, and in which file. Message 1 says only how to name a task in `:seon/tasks`.
- Done: line 27 says green proofs end the task, but also that "the tests reaching your change" run. Their status is not shown.
- The contract key: line 22 uses `{:malli/schema ...}`, while the must-reads show `:seon.fn/spec`.
- The valid `:seon.batch/mode` values.

## 2. Undefined terms

- "block": line 2 says `Your task and proofs are in your block: (seon.context/current-state)`, but the context never labels the user message as the block.
- "eval request" and "reaching": line 27, "the tests reaching your change run after each eval request". Neither is defined.
- "live symbol": line 30, "a live symbol for later forms". Not explained.
- "batch", "repair", "span", "stray closer", "token": `:seon.batch/text`, `:seon.batch/repairs`, `:seon.batch/spans`. The intent gives only the example `"a]a" -> "aa"`.
- `:seon.batch/mode` in `[:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven]`: never shown.
- `:seon.ref/problem-kind :unresolved` on `:seon.batch/dropped` and `:seon.batch/inserted`: unexplained.
- `:proof-status :pending` (not `:red`): the context never says what pending means, or whether the proof failed.
- `:my.agent/turns-left 100`: no statement of what the budget counts.
- `(dir my.program)`, `my.message/send`: named in line 28-29 as tools, no reply shape.

## 3. Contradictions and boxes

- Line 22 calls the contract `{:malli/schema ...}`. Every must-read shows `:seon.fn/spec`. A cold agent cannot tell which key `defn` must carry.
- Line 2 says to call `(seon.context/current-state)` for the block. The block is already in the context. Either the call is redundant or the context is a copy of something the agent must re-fetch; the text does not say.
- Line 23 says "Change code at the REPL, never in files". Line 27 says proofs must go green. Nothing says whether existing red proofs may be edited, so the only allowed move is to change the body.
- Line 31 forbids `[:maybe ...]` and nil. The must-reads show `:seon.batch/repair` with `[…6]` and `:seon.batch/forgiven` with `[…5]`. I cannot check whether those schemas use the forbidden forms.

## 4. Noise

- `:seon.render/operations` repeats `(doc ...)`, `(my.program/source …1)`, `(my.program/callers …1)` on about seven refs, about 150 tokens of identical shape.
- `:seon.ref/line 1` on every `:seon.batch/*` ref: about 40 tokens, value always 1.
- `:seon.ref/tests` for `forgive` and `segments` list about 15 proof names, many unrelated to stray closers: `a-markdown-reply-reads-only-its-forms`, `tool-markup-among-code-is-dropped-at-read`, `a-reply-reads-in-allocation-linear-in-its-length`. About 300 tokens.
- `:seon.ref/callers` for `segments` includes `seon.turn/commented-forms seon.turn/segmented-sources`, which this task does not touch.
- Message 1 gotchas on `(some f xs)`, lazy `map`, and keyword lookup are generic. Only the nil and contract gotchas bear on this task. About 120 tokens.
- Estimated total of 2659 tokens, roughly 900 of them must-read metadata I did not need first.

## 5. Format

- The block is one EDN value with no heading. Its keys (`:task`, `:proofs`, `:step`, `:must-reads`, `:turns`) are never named as the block's parts.
- Cut mid-token: `"(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-te"…+236`, and `:seon.fn/spec "[:=> [:cat :string :boolean :boolean] ... :seon.batch/repair"…+63`.
- Cut mid-sentence: `"...falls outside eve"…+430`, in the intent. The part that names the bug is cut.
- Bare elisions with no label: `[…6]`, `[…5]`, `:arglists ([…3])`, `…4]`. Only `…N` in operations is an ellipsis; the rest are unexplained.
- Runnable-looking forms with elided args, `(my.program/source …1)`: the agent cannot tell whether to run them as shown.
- `:seon.fn/spec "[:function [:=> ...] [:=> ...]]"` for `segments` is a multi-arity spec with no explanation.

## 6. Missing

- The bodies of `forgive`, `scan`, `step` and `segments`.
- The current failure: what `a-stray-closer-keeps-tokens-apart` expects and what it gets now.
- The schemas `:seon.batch/mode`, `:seon.batch/spans`, `:seon.batch/repair` and `:seon.batch/forgiven`, in full.
- How the proofs are run, and where their result appears (`:findings`, `:result`, or `:error`).
- Whether the agent may edit `seon.sci.forgive-test`, and what done means beyond the three green proofs.

## 7. Top 5 changes

1. Replace `:seon.context/start`'s `(my.program/source ...)` call with the evaluated source of the target, or label it as a call to run. The block now looks like data but is a call.
2. Show the red proof's last result: the failing assertion, expected and actual, for `a-stray-closer-keeps-tokens-apart`, with `:proof-status :red` instead of `:pending`. Drop the cut-off test source, or show it whole.
3. Cut `:seon.render/operations`, `:seon.ref/line` and the unrelated `:seon.ref/tests` and `:seon.ref/callers` from the must-reads. Keep one "Next:" line naming the first `my.program/source` call.
4. Put a one-line header above the block naming its parts, and a three-line glossary: batch, repair, span, stray closer, with the `:seon.batch/mode` values.
5. Show `:seon.batch/forgiven`, `:seon.batch/mode` and `:seon.batch/repair` schemas in full, label every elision (`+N more: (requery form)`), and state the done rule and the contract key in message 1: "Done when the three proofs are green; the contract key is `:malli/schema`."
