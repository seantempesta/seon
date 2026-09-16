---
type: research
status: complete
created: 2026-09-16
tags: [research, steward, gate, transcript, web-debug, shown-text, error-identity]
---

# Transcript and web-debug reds — attribution and repair (2026-09-16)

Batch 30 gate B recorded 13 red tests in `seon.render.transcript-test` (the
ledger called them "renderer-ref class") and 7 failed assertions in
`seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments`
(`tmp/orchestrator/gate-results/batch-30/named.md`, HEAD `56f0a4ca8`, runner
snapshot `d2cf09d1a`). This lane re-verified every red at current HEAD
(`e0c9d8923`), attributed them, and repaired them.

**The ledger's "renderer-ref class" label is refuted.** Neither namespace's
reds have anything to do with renderer refs (`52044b4f4`, `cecfaf428`). Two
unrelated root causes explain all twenty-odd failures, and both are stale
test expectations left behind by earlier cuts, not mechanism defects.

## Root cause A — the shown-text cut never reached `transcript_test.clj`

`ff9507c1b` ("Keep private SCI objects in memory and store shown evaluation
text", 2026-09-08) replaced the evaluation's stored PRINT NODE
(`:seon.cluster.eval/result-edn`) with the exact text its value renderer
showed (`:seon.eval/shown`), and deleted the transcript's private
`bounded-result`, which had re-rendered that node at projection time. The
commit updated ten test namespaces. `test/seon/render/transcript_test.clj`
was not one of them.

Consequences, all observed:

- `:seon.cluster.eval/result-edn` is no longer a declared attribute
  (`resources/seon/schemas/seon.cluster.eval.edn`), so every fixture
  transaction carrying it was REJECTED whole
  (`datahike.db.utils … Bad entity attribute :seon.cluster.eval/result-edn
  … not defined in current schema`). The tests then rendered an empty
  history and failed on content, order, counts and `subvec` bounds — a
  cascade whose first symptom was three lines below its cause.
- `(ns-resolve 'seon.render.transcript 'bounded-result)` returns `nil`, so
  `admitted-top-level-string-is-terminal-text` threw
  `NullPointerException … "bounded_result" is null` three times.
- Three further tests asserted mechanisms the same cut retired:
  `render-run-ai` and `render-history-ai` now return `""` by construction
  (`src/seon/render/transcript.clj:783`, `:1043`) because the prompt owns
  evaluation text; `render-history-html` heads turns rather than replaying
  their evaluations (`:1049`); and the value renderer nulls
  `:seon.print/length` / `:seon.print/level` before emitting
  (`src/seon/render/value.clj:448`) because `*print-length*` as a second
  elision mechanism is what the one-clipping-spot ruling retired.

## Root cause B — error identity is the signature, not the caller's name

`45998fdbf` ("Derive error identity from canonical failure attributes") made
`seon.error/normalize` set `:seon.error/id` to the derived signature
(`src/seon/error.clj:562`); the request's own `:seon.error/id` now names the
NOTIFICATION (`src/seon/error.clj:1342`). The web-debug fixture still pointed
`:seon.message/about` at `[:seon.error/id "panel-fault"]` — an entity that
transaction never creates.

Measured at HEAD: `(error/normalize {… :seon.error/id "panel-fault" …})`
returns `:seon.error/id "5bc7a34d37948c877cab85bc6c446545fbf0c833b200e116ca9df9f4f5a46a66"`.

ONE rejected transaction explains all seven assertions:

| assertion | why |
|---|---|
| `web_debug_test.clj:264` | the transaction is rejected — `nil` `:db-after` |
| `web_debug_test.clj:227` | `panel-fault-message` therefore does not exist, so the `seed-turn!` whose trigger is that message is rejected (`:entity-id/missing`) |
| `:290 faults` | no fault fact landed |
| `:290 fault-turns` | no turn is triggered by a fault message |
| `:290 empty-replies` | the empty-reply turn was that same rejected `seed-turn!` |
| `:325` prompt tokens `11100` vs `18200` | `panel-attempt-3` (7100 prompt tokens) rode the rejected turn; `18200 − 7100 = 11100` |
| `:326` cost off by `8.0e-4` | exactly the missing attempt's billing |

`src/seon/error.clj`'s `normalize` docstring still claimed "`id`, `at` and
`process` are the caller's" — documentation drift from the same cut,
corrected in the same commit.

## Per test

Verification form (no test JVM was launched by this lane):

```clojure
(do (#'seon.test/with-test-loader #(require 'NS :reload))
    (seon.test/run (#'seon.test/resolve-test 'NS/TEST)
                   (seon.operator/connection "default")))
```

The `:reload` through `seon.test`'s own loader is REQUIRED: `resolve-test`
uses `requiring-resolve`, so without it an in-process run reports a false
verdict against whatever test code an earlier session loaded. This lane
observed that directly — a run launched after the `web_debug_test.clj` edit
reported the identical seven failures at the identical PRE-EDIT line numbers.

| test | red at batch 30 | reproduced at HEAD | root cause | fix |
|---|---|---|---|---|
| `transcript-test/admitted-top-level-string-is-terminal-text` | 3 errors, `bounded_result is null` | yes (in-process, 3 errors) | A — private fn deleted | test removed; class proven at its owner by `seon.repl-test/history-preserves-shown-text-without-applying-a-later-profile` and locally by `historical-shown-text-keeps-its-original-elision` |
| `.../stored-evaluations-are-terminal-transcript-values` | 9 fails, every render `""` | via A | A — fixture rejected AND `render-run-ai` is now `""` | fixture to `:seon.eval/shown`; reads the agent's history through `render-ai` |
| `.../selected-run-keeps-status-outside-agent-visible-text` | 1 fail, 1 contract error | via A | A — `render-run-ai` returns `""`; `render-run-html` is the turn header | asserts the ruled shape: the turn concern emits no AI text at all, and refuses typed without a database |
| `.../populated-history-restores-the-repl-fidelity-checklist` | 11 fails | via A | A — `seed-populated-history!` rejected | fixture migrated |
| `.../the-transcript-is-whole-and-the-ai-boundary-elides-it` | 6 fails | via A | A — same fixture | fixture migrated |
| `.../same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order` | 5 fails, 2 `IndexOutOfBounds` | via A | A — pinned fixture rejected | fixture migrated |
| `.../supersession-chains-vanish-from-the-history` | 4 fails | via A | A — fixture rejected | fixture migrated |
| `.../malformed-receipt-bytes-and-any-unique-about-stay-replayable` | 4 fails | via A | A — plus the retired "malformed node" face | fixture migrated; asserts the bytes reach the response verbatim |
| `.../receipt-content-enters-the-shared-capped-floor` | 5 fails | via A | A — asserted a SECOND clipping spot | test removed; superseded by `historical-shown-text-keeps-its-original-elision` |
| `.../selected-evaluations-project-only-their-stored-source-and-result` | contract error, `pull-many … got nil` | via A | A — fixture rejected, so the selection resolved nothing | fixture and in-memory projection migrated |
| `.../history-unit-derives-both-projections-from-one-bounded-derivation` | 7 fails, 1 error | via A | A — fixture rejected, plus three retired shapes | fixture migrated; expects `Turns (3)`, the empty declared AI arm, and turn headers |
| `.../every-generated-history-is-ordered-and-total` | property shrank to 1 event | via A | A — `generated-rows` rejected | generator migrated |
| `.../one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` | 4 fails | yes | A — three retired shapes (see below) | expectations corrected |
| `web-debug-test/turn-details-use-the-loop-opening-and-exact-segments` | 7 fails | yes, in-process, byte-identical | B | `:seon.message/about` derives the ref from the fact `normalize` returns |

`one-reply-reads-identically-…`'s three retired shapes:

1. Ruling 59c excluded a handle for a Var or object FACE, because such a
   node kept a name and not the value. `bind-result!` interns the ACTUAL
   object under the evaluation's handle, Vars and namespace objects included
   (`src/seon/sci/eval.clj:519`), so every settled evaluation's handle now
   resolves. The assertion is inverted to that invariant.
2. The comment rides the prompt line — `ns=> ;; comment\n(form)` — which is
   `seon.repl`'s own documented grammar (`src/seon/repl.clj:6`,
   `input-text` at `:205`). The test expected the form alone after `=> `.
3. A form's `set!` of `*print-length*` is stored and inherited (both still
   asserted, both green) but does NOT re-bound the shown text: the value
   renderer nulls it (`src/seon/render/value.clj:448`) so presentation is cut
   once, by the render profile, and the cut names its own bound. The test
   expected Clojure's `...` marker — an unnamed second elision.

## Verification boundary

- In-process only, on `default`, one test at a time, through
  `mcp__seon__eval_clj` in `jvm` mode. This lane launched NO test JVM
  (`bin/test` / `bin/test-fast` were never run) — the orchestrator's batched
  gate is the proof.
- The orchestrator ran `bin/seon reset --force` mid-lane; the post-fix
  verdicts below were taken on the reforked JVM.
- `seon.test/run`'s own result recording failed during the pre-reset runs
  with `Bad entity attribute :seon.test.failure/reports … not defined in
  current schema` — another lane's in-flight schema, not this lane's
  subject. The returned refusal still carried the complete failure reports,
  which is what the attribution above is built on.

### In-process verdicts

GREEN, on the reforked JVM (pid 27828), after `:reload` through
`seon.test`'s loader:

| test | pass | fail | error |
|---|---|---|---|
| `selected-run-keeps-status-outside-agent-visible-text` | 4 | 0 | 0 |
| `durable-history-entries-never-invent-executions` (untouched control) | 3 | 0 | 0 |

UNVERIFIED — every db-backed test in both namespaces. **This is the honest
boundary and it is not about these edits.** After the reset, the shared
development JVM's Datahike reachability gate leaked its exclusive `:roster`
permit, so `seon.test-support/with-database` blocks FOREVER: a body that only
counts agents never returned in 130 s, at 0% CPU, parked in
`datahike.gc-guard/acquire-reachability-permit!`. The gate showed no sweep,
one held permit (token 174) and twenty-one queued waiters. The control test
`error-receipt-without-triage-has-an-execution-error-face` — green at batch
30, untouched by this lane — wedges identically, which is what rules the edits
out as the cause.

Root cause and recovery:
[an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm](../../../seon/issues/an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm.md).
This lane did NOT release the permit in place and did not restart `default`.

So the db-backed repairs are proven by attribution and by reading the owning
mechanisms, not by an in-process run. The orchestrator's batched gate is the
proof; the gate request names both namespaces.

## Files touched

- `test/seon/render/transcript_test.clj`
- `test/seon/render/web_debug_test.clj`
- `src/seon/error.clj` (docstring drift only)
- `src/seon/render/transcript.clj` (stale `bounded-result` comment only)
- `docs/prds/steward-platform/research/transcript-web-debug-reds-2026-09-16.md`
- `docs/seon/issues/restorable-node-has-no-caller-after-the-shown-text-cut.md`

---

# Second pass — batch 35 (HEAD 68a3f080b)

The first pass's two classes cleared 6 of 13 transcript tests and none of the
web-debug block. Batch 35 ran the fixtures for the first time against a
database that actually accepted them, so the remaining reds are new evidence,
not the old ones. `tmp/orchestrator/gate-results/batch-35/named.md`,
retained root `tmp/test-runs/run.V7UPqN`.

**The dominant class is one shape: `seon.db/transact!` refuses a fixture row
by RETURNING a flat error value, logs nothing, and the fixture ignores the
answer.** The test then renders an empty history and fails several assertions
away from its cause. This is the project's named recurring failure class — a
check that reads absence of signal as health — living in the fixtures
themselves. Every seed in `transcript_test.clj` now goes through
`transacted!`, which asserts `:db-after` and prints the refusal message, so
the next schema change that invalidates a fixture says so at the seed.

Each refusal below was reproduced WITHOUT the poisoned fixture base, by
calling the same pre-write validator `transact!` uses against `default`'s live
database value and projection:

```clojure
(#'seon.db/write-error (seon.db/db (seon.operator/connection "default"))
                       (#'seon.db/carried-projection …) <the fixture's tx-data>)
```

## C — a test row must declare its source

```
seon.db/transact! refused transaction data at [2 :seon.schema.admission/source]:
expected the required key :seon.schema.admission/source with either :core or :agent
```

`{:seon.test/sym "target-fact"}` is no longer a complete test entity.
`malformed-receipt-bytes-…` (ai `""`, 4 fails) and
`every-generated-history-is-ordered-and-total` (property shrank to one event,
because EVERY generated case shared the refused base row) both seed one. Both
now supply `:seon.schema.admission/source :core`; the row is only an `about`
target, and what the test needs is that it is a real one.

## D — one evaluation, one map

```
seon.db/transact! refused transaction data at [3 :seon.cluster.eval/at]:
expected the required key :seon.cluster.eval/at
```

`seed-pinned-bootstrap-history!` wrote each bootstrap evaluation as a PAIR of
maps — a frozen-source half and a settled half — from the era when those were
two entities. Each map is validated on its own, so the source-only half is
refused for the `:seon.cluster.eval/at` the receipt schema requires
(`resources/seon/schemas/seon.cluster.eval.edn`). Merged into one map per
(run, ordinal), which is the ruled shape anyway.

## E — a fault is recorded by its owner, and the fact is not transaction data

```
seon.db/transact! refused transaction data at [0 :seon.error/at]:
expected an installed attribute, got an undeclared attribute
```

This is the REAL cause of all 7 web-debug assertions, and the first pass's
about-ref repair did not touch it. `:seon.error/at` is declared on
`:seon.error/fact` but is not an installed attribute: the durable row is
`:seon.error/error` (signature, id, kind, fn, frame, exception-class —
`resources/seon/schemas/seon.error.edn:183`), and `commit-tx` projects the
fact into it (`src/seon/error.clj:1343`). `normalize`'s docstring claim that
its result "is transactable as-is" is false for any database.

The fixture now calls the owner, `seon.error/recording`
(`src/seon/error.clj:1369`), transacts its `:seon.db/tx-data`, and points
`:seon.message/about` at the `:seon.error/ref` it hands back — in a SECOND
transaction, so the message names an entity that already exists. Verified
against `default`: both transactions pass `write-error`, and `recording`
returns `[:seon.error/signature "5bc7a34d…"]`.

## F — the history run's declared shape contradicted its producer

```
ERROR seon.render.transcript/agent-history refused return value at
[:seon.render.transcript/runs 0 :seon.turn/opened-tx]:
expected an integer, got a map
```

`:seon.render.transcript/run` declared `:seon.turn/opened-tx` as
`:seon.turn/opened-tx`, a `:seon.db/ref` (an integer), while
`history-run-selector` pulls `{:seon.turn/opened-tx [:db/id :db/txInstant]}`
because `run-heading` states the instant it carries
(`src/seon/render/transcript.clj:945`, `:1005`). The contract only fired once
the fixture produced a non-empty `runs`, which is why the first pass never saw
it. Reproduced live on `default`, where the same call refuses identically.
Fixed at the declaration: a new `:seon.render.transcript/pulled-transaction`
describes what the selector actually returns.

## G — the fixture's clock was a literal epoch, and wall-clock overtook it

`populated-history-…` asserted messages and evaluations interleave by stored
time, but a message is ordered by its TRANSACTION instant
(`message-order-facts`, `src/seon/render/transcript.clj:259`) while the
evaluations were pinned to `1785500000000 + n` — about 2026-07-31. Once the
date passed it, every message sorted after every evaluation, exactly as the
gate reported (`["eval-result" "eval-wait" "eval-error" "outside-0" …]`).

The fixture now transacts each message in its own transaction, reads the
instant Datahike stamped from the report's own datoms, asserts those instants
strictly increase, and places each evaluation between two observed instants.
The clock is derived, not remembered, so the test cannot expire again.

## H — two expectations still describing retired presentation

- `populated-history-…` and `malformed-…` expected
  `seon.cluster.message/format-ai` in the AI text. The entry is the
  agent-facing read form the message schema declares,
  `(my.message/read #:my.message{:id …})`
  (`seon.render.transcript/message-form`, `src/seon/render/transcript.clj:790`)
  — the `my.*` / `seon.*` split, not a regression.
- `populated-history-…` expected the comment ABOVE the prompt line; the one
  grammar puts the agent's whole input after `ns=> `
  (`seon.repl/input-text`, `src/seon/repl.clj:205`). Same correction as
  `one-reply-…` took in the first pass.
- `the-transcript-is-whole-…` grepped the cut for the prose "more
  characters" / "requery ". The cut IS an elision value — ordinary data with
  `:seon.print/bound-by`, `:seon.print/elision-unit`, `:seon.print/omitted`
  and `:seon.print/requery-form` — and the sentence was a rendering of it.
  The assertions now name the declared keys.

## I — the history no longer hides an older turn

`supersession-chains-vanish-from-the-history` expected only the bootstrap and
newest runs; all six evaluations now render. That is the ruling, not a
regression: "All turns are shown by default; previous prompt bytes remain
unchanged until compaction" (AGENTS, the agent's history), and
`candidate-entity-ids` (`src/seon/render/transcript.clj:142`) bounds by query
work alone. The surviving property — nothing silently elided, every durable
evaluation still pullable — is what the test asserts now.

## Verification boundary for this pass

- The cold gate is the proof surface. `default`'s shared fixture base is
  poisoned by another lane (evaluation-context acquisition fails loading a
  test namespace), so NO db-backed test was run in process and
  `database-base` was never forced.
- What WAS verified in process, read-only, on `default`: every refusal above
  through `#'seon.db/write-error` against the live database value and
  projection, before and after the fix; `seon.error/recording`'s tx-data and
  ref; the `agent-history` contract violation reproduced live; and both test
  namespaces compiling through `seon.test`'s own loader after every edit.
- The schema fix (F) could not be proven adopted: the edit hook's publication
  worker is timing out under contention from another lane
  (`logs/current-source-failure.log`, "Publication did not finish within its
  declared bound"), so `agent-history`'s wrapper on `default` is still armed
  from the old declaration.
