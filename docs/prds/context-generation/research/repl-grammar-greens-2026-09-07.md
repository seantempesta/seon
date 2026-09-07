---
type: research
status: complete
date: 2026-09-07
tags: [research, repl, render, run-loop, testing, context]
---

# The REPL grammar's reds, attributed and closed — 2026-09-07

Written by the `repl-grammar-greens` lane against the reds
[the evaluation-entity landing note](evaluation-entity-landing-2026-09-07.md)
§5b left open, over the four commits `6a16fb60e`, `5ac5bfe34`, `741c1d6c3`,
`3a403b821` and the PRD's
[§4 and §5](../plan/agent-record-and-repl-response-prd-2026-09-07.md).

**The headline is an attribution correction.** The previous note said three
reds were inherited and twenty were its own. Measured in a detached
worktree, **fourteen** are inherited and **eleven** were the rewiring's. All
eleven are now green. The fourteen are the disabled-rendering-limits and
message-sentence families an earlier lane already recorded as pre-existing
(`agent-units-landing-2026-09-07.md` §"Focused proofs"); they are untouched.

## 1. The baselines, measured rather than asserted

Two detached worktrees at `tmp/greens-baseline`, `reference-code` symlinked
to the main tree's submodules. Selection: `seon.repl-test
seon.cluster.run-test seon.render.transcript-test seon.render-coverage-test
seon.cluster.reply-test seon.bootstrap-test`.

| tree | tally | red tests |
|---|---|---|
| `bbeb6f651` — BEFORE all four commits (no `seon.repl-test` yet, so five namespaces) | 65 tests / 598 assertions / **34 failures, 1 error** | 14 |
| `6a16fb60e` — after commit 1 of 4 | 74 tests / 616 assertions / **34 failures, 1 error** | 14 |
| `3a403b821` — HEAD at the lane's start | 74 tests / 623 assertions / **58 failures, 1 error** | 25 |
| this lane (`f8530b549` + follow-up) | 74 tests / 628 assertions / 15 red, then 14 | 14 |

The `bbeb6f651` and `6a16fb60e` rows name the **same fourteen tests with the
same 34 failures and the same error**. Commit `6a16fb60e` added only new
files and schemas — `src/seon/repl.clj`, `test/seon/repl_test.clj`,
`seon.repl.edn`, `seon.cluster.eval.edn`, one issue note — and rewired
nothing, so the fourteen pre-date the whole program.

### 1.1 The unattributed red, decided

`seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets` is
**inherited**: red at `bbeb6f651`, before any of the four commits, with the
identical failure at `run_test.clj:603`. It was already recorded as
pre-existing in `agent-units-landing-2026-09-07.md`.

### 1.2 The fourteen inherited reds, by class

Disabled rendering limits (elision never fires, so every "N older entries
elided" / `…` / `html-elided` assertion is red) and message sentences
(`Agent X said to Y:` no longer appears in the AI projection):

- `seon.render.transcript-test/` — `a-tight-budget-degrades-then-elides-loudly`,
  `every-generated-history-is-ordered-total-and-token-bounded`,
  `malformed-receipt-bytes-and-any-unique-about-stay-replayable`,
  `populated-history-restores-the-repl-fidelity-checklist`,
  `receipt-content-enters-the-shared-capped-floor`,
  `same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order`,
  `supersession-chains-vanish-before-token-accounting`,
  `tight-budgets-pull-only-a-budget-derived-newest-candidate-set` (8)
- `seon.render-coverage-test/` — `effect-receipts-render-state-from-attribute-presence`,
  `important-runtime-entities-declare-and-use-readable-faces` (2)
- `seon.cluster.reply-test/` — `every-refusal-is-a-value`,
  `every-refusal-matches-its-declared-error-class`,
  `forms-run-and-prose-becomes-source-comments` (3)
- `seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets` (1)

Two of them — `populated-history-…` and `same-instant-…` — ALSO carried
stale grammar assertions. Those were updated; both now fail only on the
inherited elision and message-sentence assertions, verified line by line.

`forms-run-and-prose-becomes-source-comments` fails on ONE sub-case,
`"denied /etc/hosts now.(my.run/complete \"denied\")"`, which refuses with
`::no-forms` instead of recovering the same-line code suffix. That is
`comment-prose-failure`/`readable-code-suffix` recovery, a different class
from the byte grammar, and it is red at `bbeb6f651` too.

## 2. The root cause the previous lane's five rewritten expectations found

The note guessed the culprit was "how trailing prose joins a comment". It was
not. `seon.cluster.reply/plan-sources` destructured `::start` — the reader
event's SPAN start, which opens at the first comment above the form — and
used `::source`, the span's whole text, as the form's source. Meanwhile it
measured the comment against `form-start`, the offset the reader gave the
form itself, which `structured-code-indexes` already used. So for
`";; a note\n(def a 1)"` the prose span came out empty and the comment stayed
glued into the source it had just been separated from:

```clojure
;; before
[{:seon.cluster.run.form/source ";; a note\n(def a 1)", :seon.ns/name user} …]
;; after
[{:seon.cluster.run.form/source "(def a 1)",
  :seon.cluster.eval/comment ";; a note", :seon.ns/name user} …]
```

The same defect hit fence-stripped prose, which `unfenced` had already turned
into `;` lines: `"Sure — here is the plan.\n\n```clojure\n(def a 1)…"` stored
`"; Sure - here is the plan.\n\n(def a 1)"` as the form. The fix is one
expression — start the form where the reader says the form starts — and the
five expectations the previous lane wrote turn out to have been RIGHT about
what `plan-sources` should do and wrong only about what it did.

Trailing prose behaves as that lane described and as the tests assert: it
rides the LAST form's comment, appended after any leading prose
(`"; Then I will finish.\n; That is all."`). Probed live, not inferred.

## 3. The live defect this lane found: a contract that could not be honoured

`http://127.0.0.1:7766/ns/my.agents.juniper/debug?output=:seon.render/ai`
did not render at all. The whole page came back as:

```
{:seon.render.web/page-derivation-failed true,
 :seon.error/message "Deriving this page threw clojure.lang.ExceptionInfo:
   seon.repl/text violated its contract (invalid-input): invalid dispatch value"}
```

`seon.render.transcript/emission` assigned `bounded-result`'s output — which
is already-rendered TEXT, because the transcript owns the bounding — to
`:seon.print/node`, which is a print node. Validating a string against the
node schema's face dispatch threw `::m/invalid-dispatch-value` from inside
Malli's `explain`, so the contract check itself blew up and took the page
with it. This is the AGENTS.md §2.4 class exactly: the diagnostic could not
tell the truth, so it told nothing.

It travels as `:seon.repl/value` now — the printed value it always was — and
`seon.repl.edn`'s `:seon.repl/emission` names that key instead of
`:seon.print/node`. `value-text` returns a supplied `:seon.repl/value`
directly; the stored-`result-edn` path is unchanged.

### 3.1 One grammar for a string result

The two paths disagreed. `transcript/bounded-result` spliced a
`:seon.print/string` node's raw bytes in; `seon.repl/value-text` printed it
quoted, `pr`-style. Raw splicing puts the string's own newlines INSIDE
`#:seon.repl{:value …}`, which is one line of readable data by construction —
the exact hazard ruling 45 exists for. The transcript's special case is
deleted; `print/emit-text` handles the string face like every other face, and
the truncated-string face in the same regression already expected quotes.
`seon.render.transcript-test/admitted-top-level-string-is-terminal-text` keeps
its class (the node is terminal — no renderer, no floor) and asserts the
quoted form.

## 4. Disposition of every red

| test | disposition |
|---|---|
| `reply-test/the-source-is-exactly-what-the-agent-wrote` | **fixed-code** (`plan-sources` form start) |
| `reply-test/crlf-events-stay-within-the-original-reply` | **fixed-code** (same) |
| `reply-test/a-fenced-reply-retains-surrounding-prose-as-comments` | **fixed-code** (same) |
| `reply-test/tilde-fences-have-the-same-presentation-semantics` | **fixed-code** (same) |
| `reply-test/attribution-follows-the-one-reader` | **fixed-code** + one stale expectation still asserting the glued source |
| `bootstrap-test/intent-membership-is-the-only-opening-delta-and-is-budgeted` | **fixed-expectation**: the delta is the forms; a new assertion proves the comment is its own fact |
| `transcript-test/durable-history-entries-never-invent-executions` | **fixed-expectation** to `…\n#:seon.repl{:value 3}` |
| `transcript-test/stored-evaluations-are-terminal-transcript-values` | **fixed-expectation**: prompt line + response keys, `:out` separate from `:value` |
| `transcript-test/error-receipt-without-triage-has-an-execution-error-face` | **fixed-expectation**: the error is the `:error` key, never a loose line; asserts no `:value` beside it |
| `transcript-test/history-unit-derives-both-projections-from-one-bounded-derivation` | **fixed-expectation** |
| `transcript-test/selected-evaluations-project-only-their-stored-source-and-result` | **fixed-fixture**: the in-memory reconstruction now carries `:seon.cluster.eval/comment`, so both paths produce identical bytes |
| `transcript-test/admitted-top-level-string-is-terminal-text` | **fixed-expectation** (§3.1) |
| `transcript-test/populated-history-…`, `same-instant-…` | grammar assertions fixed; **pre-existing-left** on elision + message sentences |
| the other 12 inherited | **pre-existing-left** (§1.2) |

The `populated-history` FIXTURE stored `";; calculate the answer\n(do …)"` as
one `:seon.cluster.run.form/source`. That is the old storage shape; it now
seeds `/source` and `:seon.cluster.eval/comment` as two facts.

## 5. The rehydration gate, run

`bin/test seon.sci.eval-test seon.cluster.turn-test` — **125 tests / 731
assertions / 9 failures, 1 error**.
`seon.sci.eval-test/a-later-turn-reaches-the-values-its-earlier-forms-produced`
— the regression the previous lane could not wait for — is **GREEN**.
`fork-for-turn`, `bind-result!` and `admit/semantic-value` needed no change.

The five red tests there are `turn-test/a-run-prompts-from-its-opening-database-value`,
`a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end`,
`turn-intent-is-the-complete-crash-falsifier`,
`sci.eval-test/runtime-function-rows-carry-parsed-contract-facts` and
`static-and-runtime-contracted-definitions-publish-identical-facts`.

**All five are inherited.** The same selection at `bbeb6f651`, before any of
the four commits, gives 124 tests / 728 assertions / **9 failures, 1 error** —
the same five test names, the same counts. Nothing in the REPL grammar work
touched them.

## 6. The live page, verbatim

`bin/seon --root tmp/juniper-context-live init --dev juniper-context`
converged, then the debug page's AI column
(`/feed/juniper?…&debug=true&output=:seon.render/ai&prompt=true`) came back
with 14 `#:seon.repl{…}` maps and no derivation failure. Identity, plan,
messages and history each show one prompt line per form and one response map.

The identity unit, exact bytes:

```
my.agents.juniper=> (seon.cluster.agent/whoami)
#:seon.repl{:value "Agent     juniper\nNamespace my.agents.juniper\nCluster   juniper-context", :result result/e0}
```

The history unit's first two entries, exact bytes — note the comment ABOVE
the prompt, and a form that settled nothing carrying a prompt and no map:

```
my.agents.juniper=> (seon.db/pull (quote [*]) [:seon.cluster.run/id "bootstrap:juniper"])
; A new run just opened. Why am I awake — do I have messages?
my.agents.juniper=> (help)
#:seon.repl{:value {:seon.cluster.agent/id "juniper", :seon.cluster.agent/namespace-ref [:seon.ns/name
    my.agents.juniper], :seon.cluster.agent/unread-message-count 2, :seon.cluster.run/turns-remaining
  99, :seon.cluster.agent/protocol-namespaces [my.message my.run seon.bootstrap
    seon.db], :seon.cluster.agent/open-run-ref [:seon.cluster.run/id "bootstrap:juniper"],
  :seon.cluster.run/trigger [:seon.cluster.message/id "bootstrap-task:juniper"]}, :result result/e0}

my.agents.juniper=> (dir my.message)
#:seon.repl{:value [my.message/decline my.message/inbox my.message/read my.message/send], :result result/e1, :out "decline\ninbox\nread\nsend\n"}
```

**One observation for the owner, not fixed here.** The PRD calls the response
one line, and `seon.repl-test/response-key-order-is-the-emitter-not-the-map`
asserts it for a small value — but `seon.print/emit-text` pretty-prints a
wide value across lines, as `(help)` above shows. The wrapped lines are
continuations of one `#:seon.repl{…}` datum, never comment-shaped, so ruling
45 holds; but "one line" is not literally true and the PRD text should say
"one datum" or the print width should be part of the response's contract.
`seon.print` is not this lane's to change.

## 7. What is unfinished

1. The fourteen inherited reds (§1.2). They are the disabled-rendering-limits
   and message-sentence families; `unsettled.md` records that restoring
   limits is not how they get satisfied.
2. `forms-run-and-prose-becomes-source-comments`'s one recovery sub-case
   (§1.2), pre-existing.
2b. The five inherited reds under `seon.sci.eval-test` /
   `seon.cluster.turn-test` (§5), pre-existing.
3. PRD §4 item 2 from the previous note — the provider prompt still builds its
   own bytes in `seon.render.walk/generic-history-entries`; the evaluation
   entity is still not merged with the form entity.
