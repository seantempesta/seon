---
title: Context corpus scorecard, what the forms an agent runs answer
type: research
status: in-progress
date: 2026-10-08
lane: context-refine
---

# Context corpus scorecard

Lane `context-refine`, agent `context-corpus` (its own branch), default JVM pid 22338 started 14:20Z
(HEAD e1a71b7c8 at boot), 2026-10-08. The corpus is `script/seon/dev/context_corpus.clj`: each form enters
`seon.mcp/call` as the SCI agent, tokens are `seon.ai.tokens/estimate` of every reply text, and each reply is
scored by explicit rules (the script's header): `data` (each text reads as one EDN form), `noise` (turn and
commit ids, error stamps, connections, SCI contexts, render profiles, host frames), `duplicate` (a 60-character
string or 12-leaf collection shown twice), `tail` (a tests block on a form that changed no definition), `next`
(an error or cut offers a runnable form), `type` (an error carries `:seon.error/type`), `placeholder` (a taught
form carries `/path/to`, `"..."` or a `<slot>`) and `size` (tokens within the form's ceiling, what its
content needs). Re-run: `bb --classpath script:src:resources script/seon/dev/context_corpus.clj .`

## BEFORE (running JVM, before this lane's commits are loaded)

| # | Form | Tokens / ceiling | Failing rules | Noise | Duplicates |
|---|------|------------------|---------------|-------|------------|
| 1 | help | 474 / 450 | data noise tail placeholder size | :seon.source/commit-id :seon.turn/id | - |
| 2 | current-state, unread | 234 / 150 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 3 | current-state, red proof | 576 / 700 | noise tail | :seon.source/commit-id :seon.turn/id | - |
| 4 | doc fn | 358 / 300 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 5 | source seon fn | 272 / 300 | noise tail | :seon.source/commit-id :seon.turn/id | - |
| 6 | source library fn | 595 / 400 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 7 | dir ns | 302 / 300 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 8 | defn | 123 / 80 | noise size | :seon.source/commit-id :seon.turn/id | - |
| 9 | failing test step | 170 / 300 | noise | :seon.source/commit-id :seon.turn/id | - |
| 10 | contract refusal | 206 / 200 | noise tail next type size | :seon.source/commit-id :seon.turn/id | - |
| 11 | arity error | 259 / 150 | noise tail next type size | :seon.source/commit-id :seon.turn/id | - |
| 12 | unresolved symbol | 171 / 120 | noise tail next type size | :seon.source/commit-id :seon.turn/id | - |
| 13 | uninstalled attribute | 268 / 150 | noise duplicate tail type size | :seon.source/commit-id :seon.turn/id | Bad entity attribute :corpus/n x2 |
| 14 | big query (cut) | 2172 / 1600 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 15 | my.program/breaks | 2053 / 600 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 16 | my.test/check | 354 / 250 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 17 | my.message/inbox | 182 / 150 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 18 | runtime_status | 796 / 800 | - | - | - |

Total: 9565 tokens, 56 rule failures over 18 forms.

## Findings, worst first

1. **Every eval reply ends with a tests block of internal ids** (90 tokens a reply, about 1,530 of the 9,565, 16%):
   `{:seon.turn.loop/tests {:seon.turn/id .. :seon.source/commit-id #uuid .. :seon.turn.loop/reaching {:seon.test/passed?
   false :seon.turn.loop/red [corpus.scratch/corpus-red live.text-test/initials-of-words]}}}` after `(help)`, `(doc ..)`,
   `(dir ..)` and every read. The pass id and commit id have no agent consumer; they are in the shown summary because
   `seon.turn/summary` keeps the commit for `seon.turn/summary-queue`. The reds include
   `live.text-test/initials-of-words`, a test red on main that nothing on this branch changed. Producer:
   `seon.turn/summary` and `seon.turn/test-step` (printed by `seon.mcp`'s run); both files are held by lane
   feedback-2, so this is routed to root.
2. **`my.program/breaks` on a widely used function**: 2,053 tokens shown of an 83,933-token value; the caller set (600)
   is shown beside the call sites that already name every caller, and 2,274 gating tests. Both groups are consumed
   internally (`my.program` retraction refusal and deletion report), so the fix is a render on
   `:seon.program/breakage` that shows per-relation counts, a few names and the requery, not a producer cut.
3. **`(doc seon.db/pull)`**: empty `:body ""` and `:example ""`, and `:out` repeats one union three times (one per
   arity). Producer `seon.sci.eval` doc (held by feedback-2).
4. **Errors**: no `:seon.error/type` on a contract refusal, an arity refusal, an unresolved symbol or an uninstalled
   attribute; none offers a runnable next form. The arity refusal says the arglists four ways (message, problems,
   `:seon.instrument/arglists`, `:seon.instrument/declared-arities`). The uninstalled-attribute read answers a value
   (not an `:error`) whose message repeats in its one-link `:seon.error/chain`.
5. **`(help)`** prints its AI render raw inside `#:seon.repl{.. :value ..}`, so the reply is not data; it taught a
   placeholder checkout path (fixed, below).
6. **`(clojure.repl/source clojure.core/some-fn)`** answers 595 tokens: the whole source in `:out` (correct content;
   the ceiling may be wrong for long library functions).

## Changes

| Item | Commit | What changed |
|------|--------|--------------|
| help placeholder (root ask, `seon.bootstrap/help-teaches-a-placeholder-checkout-path`) | a9e2bcf6d | help drops the `write-branch!` line and the `"..."` send line; regression `seon.help-test/help-teaches-no-placeholder-value` |
| handled message stays unread (`seon.context/a-handled-message-stays-unread-in-every-later-pass`) | 15ef7b9b4 | the block's unread section leaves out messages a closed turn handled; regression `seon.context-test/a-message-a-closed-turn-handled-leaves-the-unread-section`; live red on live-flash-4's branch (cebe98c3e8bc handled by turn 6c3450afa886, still unread) |
| printed help map as system message (`seon.context/the-system-message-is-the-printed-help-map-not-its-render`) | edfa957c1 | model-context answers a `:seon.render/request-error` refusal when help's render did not run; regression `seon.context-test/a-request-that-cannot-run-the-help-render-answers-a-refusal` |
| error replies say each thing once (finding 4) | 32bc482b0 | `seon.error/reply-value` adds `:seon.error/type`, drops a cause link's message equal to the shown one, drops `:seon.instrument/declared-arities` when arglists are shown; selecting an error's keys at the REPL had printed "Error evidence is unavailable."; regression `seon.error-test/an-error-reply-names-its-type-and-says-each-thing-once` |

## AFTER 1 (default restarted onto 795a770c2 at 14:40Z and reset: help, unread and model-context fixes loaded; the error reply fix 32bc482b0 landed after it)

| # | Form | Tokens / ceiling | Failing rules | Noise | Duplicates |
|---|------|------------------|---------------|-------|------------|
| 1 | help | 443 / 450 | data | - | - |
| 2 | current-state, unread | 214 / 150 | size | - | - |
| 3 | current-state, red proof | 135 / 700 | - | - | - |
| 4 | doc fn | 339 / 300 | size | - | - |
| 5 | source seon fn | 252 / 300 | - | - | - |
| 6 | source library fn | 576 / 400 | size | - | - |
| 7 | dir ns | 282 / 300 | - | - | - |
| 8 | defn | 115 / 80 | size | - | - |
| 9 | failing test step | 193 / 300 | noise | :seon.source/commit-id :seon.turn/id | - |
| 10 | contract refusal | 263 / 200 | noise tail next type size | :seon.source/commit-id :seon.turn/id | - |
| 11 | arity error | 315 / 150 | noise tail next type size | :seon.source/commit-id :seon.turn/id | - |
| 12 | unresolved symbol | 227 / 120 | noise tail next type size | :seon.source/commit-id :seon.turn/id | - |
| 13 | uninstalled attribute | 324 / 150 | noise duplicate tail type size | :seon.source/commit-id :seon.turn/id | Bad entity attribute :corpus/n x2 |
| 14 | big query (cut) | 2229 / 1600 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 15 | my.program/breaks | 2110 / 600 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 16 | my.test/check | 287 / 250 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 17 | my.message/inbox | 238 / 150 | noise tail size | :seon.source/commit-id :seon.turn/id | - |
| 18 | runtime_status | 504 / 800 | - | - | - |

Total: 9046 tokens, 38 rule failures over 18 forms.

Row 3 is not comparable: the reset store has no live-flash-4 task, so the block has no task section. The tests block
no longer follows reads before the agent defines a test (rows 1-8: another lane's landing); from row 9 on, after the
corpus defines its red test, every reply still carries it with the pass and commit ids (finding 1, routed). Help is
443 tokens with no placeholder; its reply still fails `data` (the AI render is spliced raw into `:value`).
