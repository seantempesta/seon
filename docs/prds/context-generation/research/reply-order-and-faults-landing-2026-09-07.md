---
type: research
status: complete
date: 2026-09-07
tags: [research, repl, render, web]
---

# Landing: reply order, the one-argument arity, render faults, and ownership

Four bounded fixes from
[the REPL and record audit](audit-repl-and-record-2026-09-07.md) — F4, F8, F6
and the §5 open item — each its own commit with one regression asserting the
wanted behaviour.

| commit | fix |
|---|---|
| `275e8613d` | F8 — the one-argument `reply/sources` arity names `user` |
| `be2a4ae46` | F4 — trailing prose is nobody's comment |
| `4ffc5fb6a` | §5 — `unowned-namespaces` asks for `:seon.ns/steward` absence |
| `11176e6db` | F6 — one fault per page failure, observable drop, no guard gap |

## F4 — a comment can only be prose written ABOVE its form

`seon.cluster.reply/plan-sources`' terminal arm folded the prose following the
last form into that form's `:seon.cluster.eval/comment`. `seon.repl/text`
renders a comment above its form's prompt line, so the agent's own rendered
session inverted what the agent wrote: text authored last appeared first.

The fix is at the parser, where the wrong attachment was made. The terminal arm
now returns the forms unchanged. **No attribute was invented**, and nothing is
lost: `seon.cluster.run/stage-reply!` already commits the whole reply text
(`:seon.cluster.run/reply`, with `reply-blob`/`reply-size`) before any form is
frozen, so the prose is durable regardless. A reply that is only prose still
returns the loud `::no-forms` refusal.

The probe the assignment named, run on a plain `clojure -M:dev` JVM against the
edited tree:

```clojure
(reply/sources "Here is the plan.\n; a note\n(def a 1)\n(inc a)\nThat is all."
               'my.agents.juniper 500)
;; =>
[{:seon.cluster.run.form/source "(def a 1)"
  :seon.cluster.eval/comment "; Here is the plan.\n; a note"
  :seon.ns/name my.agents.juniper}
 {:seon.cluster.run.form/source "(inc a)"
  :seon.ns/name my.agents.juniper}]
```

The second form's comment is absent and the first form's comment is exactly
`"; Here is the plan.\n; a note"`, as specified.

The regression,
`reply-test/a-forms-comment-is-only-the-prose-written-above-it`, asserts the
rule across a bare reply, a form with no prose above it, a two-form reply, and
a fenced reply, plus that a prose-only reply still refuses. Four existing
expectations that asserted the retired behaviour were updated; the disposition
of each is in the commit.

**One trap worth recording for the next lane.** The first draft of that
regression used `"Above."` and `"Below."` as the prose. Both are bare symbols
occupying their own source line, so `code-event-indexes`' documented
standalone-symbol rule classified them as FORMS, not prose, and the test failed
for a reason that had nothing to do with the fix. Prose in a reply-parser
fixture must be more than one token on its line.

## F8 — the one-argument arity names the reader's own default

`([text] (sources text nil (count text)))` passed `nil` into a parameter the
function declares as `:seon.ns/name`, so `(sources text)` — the simplest probe
of the reply reader there is — violated its own contract under instrumentation.

The callers decide the fix: every production call site passes the run's
namespace (`src/seon/cluster/loop.clj:158`, `src/seon/render/web.clj:1589` via
`loop/evaluate-sources`), and the one-argument arity is used only by probes and
tests. Deleting it would force every one of those to restate a default the
dependency already owns, because `seon.sci.reader/read` starts at `user` when
no namespace is handed to it (`reader.cljc:817`, `(or namespace-name 'user)`).
So the arity now supplies `'user` deliberately, with the docstring saying why.

This is a zero-behaviour change, proven by the regression's second assertion:
`(sources text)` equals `(sources text 'user)`, and both attribute `user`
exactly as the old nil path did.

`reply-test/every-declared-arity-satisfies-its-own-contract` instruments the
declared contract LOCALLY with `malli.core/-instrument` against a built
projection, rather than calling `seon.instrument/apply!`. That is deliberate:
`apply!` mutates every loaded contracted Var in the JVM, and pooled workers run
many tests per JVM (AGENTS.md §5, "own nothing global").

## F6 — the render fault storm, the silent drop, and the gap in the guard

Three seams, all inside the machinery that exists to make a failing page
visible.

1. **One fault per pass.** `failed-page-result` committed a fault on every
   render pass, so the audit observed six identical rows in ninety seconds. It
   now offers only when the diagnostic's signature — `[registration-key
   message]` — differs from what this proc offered for that page last pass.
   The signatures ride the render proc's own state under
   `:seon.render.web/fault-signatures`; there is no new global atom. They are
   REBUILT each pass from the pages that failed in that pass, so a page that
   starts rendering again drops out of the map and its next failure is offered
   afresh rather than being suppressed forever by a stale signature.
2. **A refused `offer!` is an event.** The result was unchecked, so a full
   fault channel dropped the diagnostic in silence — absence reading as health
   inside the reporting path. A refusal now says so through the existing
   logging owner (`taoensso.timbre`, the same one `seon.cluster` uses), naming
   the page and the diagnostic that will reach no database fact.
3. **The reporting path is inside the protection.** `failed-page-result`
   derefs routing and renders Hiccup, and it sat outside `render-pass`'s
   `try`, so a throw while REPORTING a failure ended the proc exactly as the
   failure would have. It is now inside, with `unreportable-page-result`
   beneath it: a floor that allocates nothing but strings.

Two regressions in `seon.render.web-test`:
`a-page-failing-every-pass-offers-one-fault-and-the-proc-survives` (three
consecutive failing passes, one offered fault, three completed passes, a
different message offered again, and a recovered page holding no signature) and
`a-throw-while-reporting-a-failed-page-does-not-end-the-proc`.

## §5 — ownership is the steward fact

`seon.problems/unowned-namespaces` inverted `:seon.cluster.agent/namespace` to
decide whether a source-bearing namespace had an owner. That attribute is
assignment, and the audit confirmed it is non-unique in the live schema: several
agents may be assigned one namespace and none of them need steward it. A
namespace an agent merely worked in therefore answered "owned", and the problem
line went silent — the project's recurring class, a check reading absence of one
signal as health about a different question.

The query now asks for absence of `:seon.ns/steward`, the fact
`seon.cluster.agent/steward-call` asserts inside the creation transaction and
`steward-of` reads. The regression seeds three namespaces — unowned,
assigned-but-unstewarded, and stewarded — and asserts the middle one is
reported, which is exactly the row the old inversion hid.

## Tallies

All reds below were confirmed inherited by running the same namespace at HEAD
with this lane's changes reverted.

| gate | result | new reds |
|---|---|---|
| `seon.cluster.reply-test` | 14 tests / 78 assertions / 7 failures, 1 error | 0 |
| `seon.render.web-test` | 63 tests / 455 assertions / 5 failures, 1 error | 0 |
| `seon.render.web-performance-test` | green | 0 |
| `seon.problems-test` + `seon.cluster.agent-namespace-test` | 21 tests / 144 assertions | 0 |
| `seon.cluster.problem-routing-test` | 4 tests / 91 assertions | 0 |

The five namespaces run together as one gate:
`bin/test seon.cluster.reply-test seon.render.web-test seon.problems-test
seon.render.web-performance-test seon.cluster.agent-namespace-test` —
**98 tests / 605 assertions / 12 failures, 2 errors**, every one of them in the
eight inherited tests named below.

Inherited `reply-test` reds — `every-refusal-is-a-value`,
`every-refusal-matches-its-declared-error-class`,
`forms-run-and-prose-becomes-source-comments` — are the three
[the greens note](repl-grammar-greens-2026-09-07.md) §1.2 lists, all in the
`comment-prose-failure`/`readable-code-suffix` recovery class, red at
`bbeb6f651` too.

Inherited `web-test` reds — `a-fresh-cluster-debug-page-renders-a-prospective-prompt`,
`a-never-run-agents-debug-context-is-labeled-prospective`,
`an-unavailable-prospective-context-renders-its-diagnostic-data`,
`data-caps-a-five-megabyte-attribute-through-the-shared-floor`,
`the-message-appears-on-the-page-wire-test` — reproduce byte-identically with
`src/seon/render/web.clj` and `test/seon/render/web_test.clj` at HEAD. They are
in the prospective-prompt and transcript area another lane was repairing in the
same tree.

## Out of scope, filed

- [The repl skill still states the retired trailing-prose
  rule](../../../seon/issues/the-repl-skill-states-the-retired-trailing-prose-rule.md)
  — `.agents/skills/repl/SKILL.md:89`, outside this lane's owned paths.

## Not done

- F4 changes what the parser produces, not what `seon.repl/text` does with a
  comment. Comments still render above the prompt, which is now correct by
  construction because only prose written above a form can become one.
- The `:seon.render.web/fault-signatures` key is proc state, not a declared
  schema key; the render proc's state map is not contracted.
