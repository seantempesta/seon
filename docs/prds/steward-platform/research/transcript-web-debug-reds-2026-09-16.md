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
