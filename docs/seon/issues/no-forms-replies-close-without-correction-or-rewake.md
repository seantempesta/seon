---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, wave/reply-durability]
---

# Respond when a model reply contains no forms

## Problem

A paid reply that contains only prose is recorded durably as
`:seon.cluster.reply/no-forms`, but the run then closes terminally. The model
never sees the diagnostic, no correction turn follows, and the task's
obligations cannot cause a re-wake. One medium mistake therefore silently ends
real work from the model's point of view.

## Live evidence — Drive 1 Attempt 5, 2026-08-14

Run `a887d305-c8ae-4b6e-842f-43287f7f7496` sent one 34,955-character prompt
to `deepseek-v4-flash`. The provider returned `finish_reason=stop` after 97
completion tokens. The exact reply was:

```text
I'll start by understanding my plan and checking the current state. Let me look at what I need to do:

1. Author a plan with items using the NEW `:my.plan.item/about` plain-vector token shape
2. Define `sum-of-squares` function with Malli contract
3. Define and run tests through `seon.test/run`
4. Complete all plan items and report results

Let me first check my current state and any messages:
```

This was not a clarifying question and not a task misunderstanding. It restated
the requested obligations accurately, then announced a read without emitting
the form that would perform it.

The prompt did teach the medium at character 970:

```text
Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch.
```

It immediately showed a complete fenced `(defn greet ...)` example, and every
context observation used the `namespace=> (form)` / printed-value grammar.
However, the actual task began at character 34,253, after roughly 33,200
characters of toolkit directory output. The same instruction also says
`Prose lines are kept as ;; comments`, which does not state the crucial edge
case: prose **without any form** terminates the run.

The runtime recorded the raw reply and diagnostic correctly:

```text
The reply carried no Clojure forms — its whole text read as prose. Prose runs nothing and settles nothing; write the Clojure you want evaluated.
```

But `src/seon/cluster/loop.clj:1337-1338` sends every reply-reader error to
`fail!`. The agent had only its generated opening run and this failed task run;
there was no subsequent correction run, message, or re-wake.

## Context ablation — 2026-08-14

The paid flash-only ablation in
`docs/prds/context-generation/research/context-ablation-2026-08-14.md`
isolated the preventative prompt factors with the exact captured bytes:

- The `/ai` capture already contained the complete balanced fenced `greet`
  `defn`; only HTML had cut it mid-form. An identical-byte complete-demo
  control still returned no forms. Broken teaching was not the cause.
- Appending exactly 123 characters at the tail — `Your reply is read as forms
  and evaluated in your namespace. Prose alone runs nothing; include at least
  one Clojure form.` — produced one reader-accepted form in both calls (2/2).
- Retaining 31.48% of the toolkit span with no tail reminder also produced
  forms twice (2/2), but one reply ran away into repetitive exploration and
  the retained repeat emitted 16 forms without completing the task.

Weak recency is therefore the cleanest demonstrated cause and prevention.
Toolkit volume contributes, but trimming it does not teach the terminal
prose-only edge and produced much less disciplined replies.

## Prevention landed — 2026-08-14

Every assembled agent prompt now ends with the exact ablation-winning
reply-medium reminder from the existing `:getting-started` instruction entity.
The reminder is declared as
`:seon.cluster.instruction/reply-medium-reminder`; its ordinary instruction
render and its generation-tail placement both derive from that one content
fact. Prompt budgeting and contribution accounting cover the augmented whole
prompt, including the final `\n\n` separator and reminder bytes. Missing or
ambiguous reminder content refuses prompt assembly instead of silently omitting
the prevention.

This lands only the demonstrated prevention. The recovery contract below
remains open and owner-gated; no correction turn or obligation-driven re-wake
was added.

## Open design boundary — no ruling in this note

The evidence establishes that terminal closure is insufficient, but does not
select the recovery contract. The owner must choose and falsify at least these
alternatives:

1. **Bounded correction turn.** Feed the typed `no-forms` diagnostic and exact
   prose back to the model on the same still-open obligations. This directly
   teaches the failed medium, but spends another provider call and needs a
   strict retry bound that cannot loop on prose.
2. **Settle prose as an observation, keep obligations open, then re-wake.** The
   prose remains a durable conversational act while derived plan/message/run
   obligations cause another turn. This preserves the model's narration but
   needs an observable wake fact and a bound; merely leaving work open cannot
   become a silent spin or an indefinite wait.
3. **Accept prose as terminal only when no executable obligations remain.** A
   genuinely conversational answer may be complete, but the classification
   must derive from explicit obligations rather than reply syntax or guessed
   intent. Attempt 5 had requested function, test, plan, and completion facts
   all absent, so this branch would not apply.

## Owner

The reply-refusal transition in `seon.cluster.loop`, joined to the run's
derived obligations and bounded provider-turn contract.

## Acceptance

- A pure-prose paid reply with executable obligations still open produces an
  observable, bounded next transition rather than terminal silence.
- The model sees enough typed evidence to correct the medium, or the durable
  obligation state demonstrably re-wakes it through the selected contract.
- Repeated no-forms replies terminate under a declared bound with every reply
  and diagnostic retained.
- A genuinely prose-complete conversational task remains representable without
  manufacturing a Clojure form.
- **Landed prevention:** the final task-adjacent medium instruction states the
  prose-only edge explicitly, with recurring byte-tail and whole-prompt
  accounting regressions. The retained flash ablation proves it elicited a
  reader-accepted form 2/2 without relying on the remote opening demonstration.
