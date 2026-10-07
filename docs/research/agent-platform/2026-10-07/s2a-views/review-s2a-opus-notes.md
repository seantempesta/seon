---
type: review
status: final (gate 1, design review)
created: 2026-10-07
tags: [review, agent-loop, debug]
---

# Review of the S2a design (lane review-s2a-opus)

Design reviewed: `s2a-views/design-notes.md` at `2c36b20c2` (no revision had landed when this review was written).
Root's decisions taken as given: turn-kind option (b), with its assumption to be verified; delete the golden
`web_test/next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake`; no walk-delete patch; system-turn
displays and buttons stay until S4. Owner ruling relayed during the review: the debug page keeps ONE simple view of
the agent's context, showing exactly what the model is sent, produced by the same assembly function and printed
through the value printer's HTML face.

All probes ran in JVM mode as agent `review-s2a-opus`. They called installed functions on
`(seon.cluster.boot/connection "default")` inside a `let`, defined nothing and wrote nothing. Default was `:faulted`,
with 3 open faults (none in S2a's files) and 5 over-bound warnings. Root had 96 turns and 121 evaluations.

## Findings, by severity

### 1. HIGH: turn-kind (b) rests on a false assumption. Use one honest label instead

The design's option (b) labels every `:call` turn with no attempt as "Virtual". It assumes that a provider-enabled
`:call` turn always records an attempt. That assumption is **contradicted** twice.

**Outside-agent turns have no attempts.** Every MCP submission opens through `seon.turn/submission-open-tx` →
`system-run-call` → `open-call`, and `open-call` stores `:seon.turn.work/situation (or situation :call)` (turn.clj:420
at HEAD). These turns never have attempts. On root, 24 of the 25 `:call` turns are of this kind:

```
{["root" :call false true] 24, ["root" :call true true] 1, ["root" nil false true] 70, ["root" :generate false false] 1}
;; [agent situation has-attempts? has-reply-size?]
```

Today's labels already differ for these turns, depending on the dial at each opening: 15 are "Virtual" and 9 are "No
provider attempt". Under (b), all of them would be called "Virtual". They are MCP passes, not virtual model turns.

**Provider turns can close with no attempt.** `call-turn` (turn.clj ~4560-4618) closes a provider-enabled `:call` turn
without an attempt row in several cases:

- `(fail! captured)`, when the capture is refused;
- `(fail! refusal)`, when the prompt refuses ("Close without crossing the provider boundary");
- `(fail! fact)` or `(nil? fact)`, when `record-attempt!`'s own write is refused.

Under (b), each of these turns would be mislabelled "Virtual".

**Smallest correct form:** an attempt-less `:call` turn is labelled "No provider attempt". That is a fact about the
turn itself, true in every case above. It needs no opening database and no dial read, and it deletes one more branch
than (b). Only the deleted session assertion (`web_debug_test:86`, `(some #{"Virtual"} kinds)`) depends on "Virtual".
If the page needs to say who authored a turn, S3's origin enum (`:opening :input :model :outside :test`) is the fact
to derive it from. Today's own-facts alternative is `:seon.cluster.eval/author` (`:system` for submissions, `:agent`
for model plans). Do not add that derivation in S2a.

### 2. HIGH: the design deletes the exact view the owner just ruled to keep

`web/sent-context-html` (web.clj:3391) already is that view. Its docstring reads "The agent's context exactly as its
next turn sends it: `seon.cluster.prompt/prompt`'s composed text … not a second assembly". It calls the same assembly
function the turn calls, through `debug-turn-request`.

Measured over HTTP:

- `GET /agent/root/debug?sent=true` with `datastar-request: true`: **55 ms cold, 8 ms warm, 159 KB**;
- size: 148,210 chars, about 46,315 tokens, 122 segments.

The design deletes it (§2.2 row `sent-context-html`, the `?sent=` branch in §3). The revision should:

- **keep** `sent-context-html` and its `?sent=` route, and keep `debug-turn-request`, which `session-controls` also
  uses;
- **detach the view from the graph page**: today it is reachable only as the lazy panel inside `graph-debug-html`. Give
  the agent page one `<details>` that fetches `?sent=true`, as the ledger's "Full context as sent" does today;
- **print through the value printer**: drop the hand-built `format`/`count`/`tokens/estimate` counts line and the
  `[:pre text]` branch, and pass the composed value to `value/html`. Owner question: the prompt is a string, so the
  printer's HTML face shows it as a string literal. Is that what "printed by the value printer's HTML face" means, or
  should the printer render the composed map (text plus `:seon.context/contributions`)?
- **keep the `seon.ai.tokens` require** in web.clj only if the counts line stays. Otherwise it goes, as the design
  says;
- **name the S4 handoff**: this view is a caller of `seon.cluster.prompt/prompt`. S4, which deletes that namespace,
  converts the view to the new prompt seam in the same commit. The design's §3 row on `prompt/agent-calibration`
  should list it.

The design's other deletions do not touch what this view needs. Those deletions are the session, the outline, the
walk graph, the stored-capture "Full context as sent" (`render-ledger-context`) and the prefix comparison. The view
reads neither the capture nor `acquire-context!` directly; `prompt/prompt` does. Deleting the capture views is
consistent with the ruling, because they show what *was* sent from a store S4 deletes, not what the model is sent.

### 3. HIGH: the design misses a second consumer of turn-kind that costs as much as the first

`churn-problem` (transcript.clj:1920) calls `(turn-kind database entity)` again for every evaluation whose turn has a
nonzero ordinal. It does not read the `::kind` that `ledger-entities` already computed. Measured on root:

```clojure
(#'seon.render.transcript/churn-problem db by-eid (seon.eval/of-agent db "root" [:db/id]))
;; 1,002 ms, 1,900 MB, 0 matches (40 evaluations in :call turns)
```

`turn-kind` alone over root's 96 turns took **1,165 ms and 2,365 MB**. That is about 47 ms and 95 MB per `:call`
turn, not the 29 ms and 54 MB the design states; the cost grows with the store. So under option (a) the ledger costs
about 2 s or more, not the design's 0.9-1.0 s, and §5's table understates (a). Under the label in finding 1, both
costs disappear.

Whatever the label rule, `churn-problem` must read `(::kind entity)`. That is a one-token change, and it is the same
defect class as task `seon.render.transcript/turn-kind-reads-each-call-turns-opening-database` (`a9a7bb6e6f29`),
whose intent names only `ledger-entities`. Root should add the `churn-problem` caller to that task's intent; no
duplicate task was filed.

### 4. MEDIUM: `ledger-data` still renders every evaluation's emission, but only about 4 turn bodies are shown

`::emission` (`repl/entity-emission`) is read only in `ledger-turn-body`, through `ledger-emissions` and
`repl/response` (transcript.clj:1655, 1837). `render-ledger` renders a body only for the selected turn and the last
three (`expanded`, 2236). The problem rules use `::label`, `::outcome`, `:seon.eval/shown` and the source, never the
emission.

Yet the design's `ledger-data` keeps `entity-emission` for all evaluations: 345 MB on 113 evaluations, about 3 MB
each. Each `?card=true` toggle re-derives the whole ledger, and with retention removed it pays this on every request.

**Algorithmic fix, within the conversion being written:** compute `::emission` in `ledger-turn-body` for that turn's
evaluations only. This makes the cost proportional to what is shown; task `82b1b5c78c1d` remains for the per-emission
cost itself. With findings 1, 3 and 4 applied, the ledger's remaining cost is `ledger-effects` (186 MB, task
`ec70b6017334`) plus about 4 turns' emissions.

### 5. LOW: two UNVERIFIED items are now verified

**Both directory functions are armed.** `seon.sci.eval/directory-value` and `seon.repl/render-directory-ai` have
profile cells on default, and so do `seon.render/acquire-context!`, `seon.cluster.prompt/prompt` and
`seon.repl/entity-emission`. `passive-ledger-never-reconstructs-directory` can therefore convert from `with-redefs`
to `support/armed-calls` (test_support.clj:722), and the planned class regression (zero `acquire-context!` calls on
`/agent/root/debug`) is countable.

**The ledger's set of evaluations is unchanged.** `ledger-acquisition` dissociates `:seon.turn/id`, so
`walk/history` (walk.clj:840) passes every `of-agent` evaluation unfiltered. `ledger-data` over `of-agent` returns the
same set, so the conversion is EQUIVALENT in membership.

### 6. LOW: smaller notes

**Selector cost** (root, 121 evaluations):

| Selector | ms | MB |
|---|---|---|
| explicit S2a selector, cold | 24 | 41 |
| explicit S2a selector, warm | 5 | 10 |
| `seon.eval/history-selector` | 16 | 33 |
| the default (read evidence `[*]`) | 54 | 93 |

The explicit selector is justified over reusing `history-selector`.

**Datastar fall-through.** A datastar request with a stale `?outline=true` or `?prompt=true` falls through to the full
page shell (`html-response (shell …)`), which Datastar then patches as a fragment. That is harmless, but check the
paint, as the design says.

**`pr-str` in the surviving `debug-response` header.** Lines 3646-3653 print values with `pr-str`. This is not S2a's
defect, and it is already covered by `seon.render.web.edn`'s ui-print task (32 sites). S2a should not add any.

**The system-turn seed is visible in the kept view.** The `?sent=true` body shows a failing opening form, `(seon.db/pull
(quote [*]) [:seon.runtime/agent {:seon.agent/id "root", :db/id 68836}])`, which answers "Expected number or lookup
ref". This is existing task text in `resources/seon/tasks/seon.turn.edn:189`, and S4 deletes the seed.

**File ownership is confirmed.** `bin/lane status` shows `s5a-reply` holding `src/seon/render/transcript.clj`, with
hunks at 333-760. `test/seon/turn_test.clj:626` is the "seon-ledger-full-context" assertion. It is unclaimed, and
distinct from s5a-reply's `test/seon/cluster/turn_test.clj`.

## Checklist (review-design-spec)

1. **"What exists" is accurate.** The `file:line` claims at HEAD match: the defs at 1094-1988 and 2522, and web
   3213-3407. It missed `churn-problem`'s turn-kind cost (finding 3) and understates turn-kind's per-turn cost.
2. **The duplicates are real**: four prompt acquisitions, two graph pages, and the ledger riding the prompt cache. No
   second path was missed in this area, apart from the turn-kind recomputation in finding 3.
3. **Library seams.** Datahike pull through the owner `seon.eval/of-agent` is the right seam, and no cache is the
   right call.
4. **Smallest composition.** It is close. Smaller still: the single attempt-less label (finding 1) and emissions for
   the shown turns only (finding 4). Neither adds a mechanism.
5. **One path.** It keeps one path, and the ruled view must stay a caller of the one assembly function (finding 2), not
   a second assembly.
6. **Risks.** Add the ruled view's survival and S4 handoff, the `churn-problem` conversion, and the per-card cost
   without retention.
7. **Cross-design conflicts.** walk-delete's patch deletes `sent-context-html` and the graph page too; take neither of
   those deletions for the ruled view. s5a-reply holds transcript.clj (sequencing, per the design).

## Questions for the owner

1. Should the context view print the composed prompt string through the value printer, which shows it as a string
   literal with quotes, or print the composed map (text plus contributions)?
2. Is "No provider attempt" an acceptable label for every attempt-less `:call` turn until S3's origin facts land?

## Verdict

**Revise, then approve.** The deletions are correct, and the ledger conversion onto `seon.eval/of-agent` is sound and
equivalent in membership. Three changes are required before code:

1. Keep `sent-context-html` as the owner's one context view, re-homed on the agent page, printed through the value
   printer, and named for S4's conversion.
2. Replace option (b)'s "Virtual" with one honest attempt-less label, and make `churn-problem` read `::kind`.
3. Compute emissions only for rendered turn bodies.

**RESET NEEDED:** none.
