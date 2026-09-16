---
type: research
status: open
date: 2026-09-16
tags: [research, turn-loop, disposition, continuation, ruling-check]
about: [seon.turn-loop-test/a-clean-last-form-without-a-disposition-is-loud-terminal-evidence, seon.turn-work-test/situation-totality-property]
---

# Does the PRD rule where the "ended without a disposition" notice is written?

Read end to end for this note:
[the agent record and the turn loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
(§0 through §18d, not grepped), both failing tests, and the settlement /
close / continuation path of `src/seon/turn.clj`. Gate evidence:
`tmp/orchestrator/gate-results/batch-85/named.log` (batch 85, 2026-09-16).
Read-only pass: no edits to `src/`, `test/`, or the default cluster.

## Verdict in one line

**(a) No — the PRD does not rule it.** The only mentions of the mechanism
delete it, and every ruling that touches the same facts (§14's session
continuation, §15's history) was written after the notice existed and
replaces its purpose without naming a successor. **This is an owner
decision, not an implementation gap.** **(c)** `situation-totality-property`
is a stale expectation: the derivation is total; the property's
"every answered closed turn is idle" clause predates §14's 2026-09-10
session-continuation ruling.

---

## (a) What the PRD actually says

The notice's carrier was the stored presence fact `:seon.turn/undisposed-at`.
The PRD deletes it, twice, and the reason it gives is about the STAMP, not
about the notice:

> `| run `undisposed-at` | DELETE | derived in the same transaction from the
> evaluations |` — §1a, line 217

> "…`:seon.context.capture` and `:seon.context.contribution`, `plan-digest`,
> `undisposed-at`, the gate counters…" — §6, line 630

Nothing in §13 (storing and rendering data), §14 (additive context), §15
(results and the history), or §16 (the debug page) mentions a notice, the
words "ended without", or any turn-level line in the history. The closest
rulings are the ones that REMOVE the places such a line could live:

> "the history is the walk rendering each of the agent's evaluation entities
> through that pair, in order, from the STORED shown text… The transcript
> namespace's hand-assembled entries, entry kinds, and any history-specific
> formatting are deleted; nothing assembles the history but the walk."
> — §15, "The history is a render function like everything else"

> "ONE render pair per entity schema, never per attribute; a block is one
> entity rendered by its schema's `:seon.render/ai` and `/html`." — §13

And the ruling that dissolves the notice's original purpose:

> "With no open turn, the session remains open exactly when the latest closed
> turn has an accepted provider reply (reply-size present and a successful
> attempt), its last evaluated form did not return `:completed` or `:wait`,
> and turns remain under the existing bound." — §14, Session continuation
> (owner, 2026-09-10), line 982-985

The original defect was
[a-run-that-settles-no-disposition-ends-the-episode-silently](../../../seon/issues/archive/a-run-that-settles-no-disposition-ends-the-episode-silently.md)
(resolved, archived): "Nothing records that the run ended without a
disposition, **nothing wakes the agent again**, and the requester's message
is answered by silence." Under §14 the undisposed turn is precisely the turn
that DOES wake the agent again. The silence half of that class is gone by
construction; only "the agent cannot read WHY it got another turn" remains,
and nothing rules that.

Nor is it ruled elsewhere: the working edge
(`plan/unsettled.md`), the ideas ledger, and
`agent-data-chart-prd-2026-09-09.md` mention `undisposed-at` only to retire
it (`agent-data-chart-prd-2026-09-09.md:318`, `:165`, `:368`). The one place
the question is open in prose is the steward-platform overnight report,
which parks it for exactly this call:

> "(b) The 'ended without `my.turn/complete` or `my.turn/wait`' notice: where
> is it written — the turn-loop owner's call" —
> `docs/prds/steward-platform/plan/overnight-report-2026-09-17.md:239`

and `docs/prds/steward-platform/research/fixture-write-sweep-2026-09-17.md:608`.

## Where the notice used to live, and what removed it

Historical mechanism (from the archived issue's Resolution and Git):

- the close transaction asserted `:seon.turn/undisposed-at` when the last
  clean agent form carried no disposition;
- `seon.turn/render-ai` had one `cond` branch keyed on that fact:
  `"It ended without my.turn/complete or my.turn/wait. Its trigger remains
  unanswered; nothing was retried."` (renamed from `my.run/*` in
  `e915d2de0`);
- the transcript admitted such turns as system-authored `:run` entries, whose
  text came from that same `render-ai`.

`ae0e54841` ("Move agent data to transaction refs, inbox edges, and runtime
components", 2026-09-09) deleted all three in one commit: the attribute from
the schema, the `::undisposed-at` argument from `close-tx`/`close-call`, and
the `render-ai` branch. The PRD row it was executing (§1a) authorized
deleting the STAMP with the reason "derived in the same transaction from the
evaluations" — the derivation survived, the notice did not.

What is left today:

- `src/seon/turn.clj:3471` derives `undisposed?` per settlement (clean last
  agent form, on a triggered turn, no error, no `interrupted-at`) — in
  memory only;
- `src/seon/turn.clj:3411` `closing-settlement?` uses it to emit `close-tx`,
  so the turn still closes correctly (the test's first assertion is green);
- `src/seon/eval/drive.clj:248,271` still derives the episode terminal
  `:undisposed` from absence of completion values, with no stamp (the test's
  second assertion is green);
- `src/seon/turn.clj:1846` `render-ai` now ends at `"It completed."` for any
  closed turn, undisposed or not;
- **orphan:** `src/seon/render/transcript.clj:537` `undisposed-run-text` and
  the `:run` branch at `:569` are dead — no code constructs a `::kind :run`
  entry any more (the only constructors are `:message` `:278`, `:eval`
  `:299`, `:attempt` `:613`). Worth deleting whichever way the owner rules.

So the failing assertion is asking for bytes no seam produces, through an
entry kind no walk emits.

## (b) The decision, in one sentence, with options

**Decision:** when a turn closes having evaluated a clean last form that
returned neither `my.turn/complete` nor `my.turn/wait`, does the agent read
anything about it in its next context — and if so, is that a stored
evaluation of the next system turn, a flat `:seon.error` value, or nothing
at all because §14's continuation already re-opens the turn?

| | option | what it costs | what we give up |
|---|---|---|---|
| **A (recommended)** | **Dissolve it.** Delete the third assertion and the orphaned `:run` entry path; no notice exists. §14 continuation re-wakes the agent, `eval.drive/terminal-state` names `:undisposed` for episode verdicts, and the absent `:seon.turn/disposition` is already a queryable fact. | one test expectation + ~15 lines of dead transcript code | the agent is never TOLD why it got another turn; it must query or infer |
| **B** | **A stored evaluation in the next system turn**, following §18b's precedent exactly (a reply with no forms becomes one evaluation carrying a flat error, "visible as `:error` in the next prompt… creates no core fault"): the system turn appends one evaluation whose value is `{:seon.error/kind :seon.turn/undisposed …}`, rendered by the evaluation schema pair like everything else. | one derivation at the system-turn seam; no new attribute, no new render branch, no second history assembly | the opening algorithm gains one non-read-form emitter, which §14 otherwise restricts to changed read forms |
| **C** | **Restore the turn-level render line** — re-add the `render-ai` branch (deriving undisposedness from facts, no stamp) and re-admit turn entries into the history. | resurrects the `:run` entry producer | directly contradicts §15 ("nothing assembles the history but the walk"); wrong on sight |

If the owner picks A, the fix is `test/seon/turn_loop_test.clj:1134-1136`
plus the transcript orphans. If B, the fix is the system-turn owner
(`src/seon/turn.clj:2039`) and the test's expectation changes from a
turn-render substring to the presence of that evaluation.

## (c) `situation-totality-property` — which situation is not total

It is total. The failure is the property's fourth clause, not the derivation.

Shrunk counterexample from the log:

```
:smallest [true true true true nil []]
:fail     [true true true true 1 [0]]
```

i.e. `planned? closed? triggered? trigger-first?` all true, `lint-ordinal`
nil, `receipts` empty. The fixture then builds (from
`test/seon/turn_work_test.clj:87-110`):

- a turn opened through the writer with `:seon.turn/trigger`, whose trigger
  message was committed BEFORE it (so `answered-closed?` is true);
- a `model-attempt` row with **no** `:seon.ai.attempt/error`;
- `:seon.turn/reply-size` (that is what `planned?` writes);
- two evaluation sources, **none settled**;
- `:seon.turn/closed-tx`, and no `:seon.turn/disposition` anywhere.

That is verbatim the shape `continuing-reply?` admits
(`src/seon/turn.clj:2825-2846`): latest closed turn, `reply-size` present,
an attempt without `:seon.ai.attempt/error`, and `(not [?turn
:seon.turn/disposition _])`. So `next-agent-work` (`:2897`) returns
`{:seon.turn.work/situation :open :seon.agent/id "agent-a"}` — a valid
situation, schema-valid, agreeing with `more-agent-work?`. Clauses 1, 2, 3
and 5 all hold; clause 4 fails:

```clojure
;; Every answered closed turn is idle. Receipt content cannot
;; manufacture a new trigger or corrective turn.
(or (not answered-closed?) (nil? situation))
```

`test/seon/turn_work_test.clj:489-492`. That sentence was true before
2026-09-10 and is false under §14's session continuation, which rules that
exactly this turn re-opens. The fix is the expectation: `answered-closed?`
now implies `situation` is `nil` **or** `:open` derived by
`continuing-reply?` — and the clause's real intent ("receipt content cannot
manufacture work") is preserved by asserting that when the turn DOES carry a
`:completed`/`:wait` disposition, or no accepted reply, the situation is nil.
`a-lint-refusal-is-terminal-until-a-new-trigger-arrives` passes today only
because its `closed-run!` helper writes no `reply-size`; that asymmetry is
worth making explicit when the property is repaired.

**One sub-question the owner may want to rule with it:** in this
counterexample the closed turn has an accepted reply and **zero settled
evaluations** — the boot-closed / interrupted shape. §14 says continuation
needs "its last evaluated form did not return `:completed` or `:wait`"; with
no evaluated form at all, `continuing-reply?` currently says yes and a paid
session continues. Whether continuation should additionally require that the
turn's evaluations actually settled (no unsettled ordinal, no
`:seon.eval/interrupted-at`) is a fail-open-on-a-paid-loop question of the
kind §3 rules against elsewhere ("An empty derived set fails CLOSED").
Not changed here; named for the ruling.

## Verification boundary

Documentary and source reading only. No test JVM was launched, no cluster was
touched, and no claim here rests on a run performed in this pass: the red
states are quoted from batch 85's `named.log`. The two live derivations named
green above (`close-tx` committing, `eval.drive` terminal `:undisposed`) are
green in that same log's assertion counts — the clean-last-form test reports
one failure of three assertions.
