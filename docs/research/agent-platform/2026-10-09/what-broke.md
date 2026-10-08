---
title: what-broke findings
type: report
status: active
created: 2026-10-09
---

# What broke (lane what-broke, 2026-10-09)

Default ran f54e73c83 (pid 40778). Two parts: a total-path violation on the
REPL reply, and the classification of the 15 tests red at HEAD.

## 1. A refused run open drops every value from the reply

### Reproduction

As a fresh agent through MCP `eval_clj` (sci), default at f54e73c83:

```clojure
;; agent wb-probe-1
(def b (:my.agent/own-branch (my.agent/branches)))
b
(+ 1 2)
(my.agent/retire-branch! {:seon.store/branch b})
:after
```

The reply was only `{:seon.error/type :seon.db.write/validation-refusal,
:seon.error/message "run transition refused: admission-closed"}` and the
stage clocks `{:custody 72.9, :print 1.0}`. No form's value came back.

The same loss without any retirement (agent wb-probe-6): a form that closes its
own agent's admission outside the run's custody, then `(+ 1 2)`:

```clojure
(defn close-my-admission! "..." {:malli/schema [:=> [:cat :seon.db/connection] :boolean]} [c]
  (some? (seon.db/call-with-custody {} #(seon.db/transact! c [[:db/add [:seon.agent/id "wb-probe-6"] :seon.agent/admission-closed-tx "datomic.tx"]]))))
;; next request
(close-my-admission!)
(+ 1 2)
```

answered only the refusal, at 121 ms, with stages `{:custody 0.3, :print 1.2}`.

### Data flow and where the values go

1. A submitted run's open is not written up front (55b5b580f): it rides the
   form's first durable write in custody (`seon.db/*open*`, prepended by
   `seon.db/transact!` while `seon.db/*receipt*` is bound) or the run's
   settlement (`seon.turn/with-submission`).
2. `my.agent/retire-own-branch!` (e43fb3689) wrote the admission close inside
   `(seon.db/call-with-custody {} ...)`, which unbinds `*receipt*` and `*open*`,
   so the run's open did not ride it.
3. At settlement the writer applies `seon.turn/system-run-call`, whose
   `refuse-closed-admission!` now sees admission closed and refuses: the whole
   settlement aborts.
4. `seon.turn/settle-batch-refusal!` settles through
   `system-run-if-admitted-call`, which writes no run; `open-run-tx-call` then
   applies no receipts, so `:saved` is absent and `seon.turn/with-saved`
   attaches no `:seon.turn.loop/evaluated-sources`.
5. `seon.cluster.agent/evaluate-source!` sees a refusal with no run on the
   basis and answers the refusal alone ("its refusal is the answer"), dropping
   the stage clocks too; `seon.mcp/sci-run` prints only that.

So there are two owners. The producer that triggered it in practice is
`my.agent/retire-own-branch!`. The total-path violation is in steps 4 and 5:
when the run cannot open, nothing carries the evaluated values to the reply.

### Producer fix (landed)

`my.agent/retire-own-branch!` writes the close in the connection's own custody,
`(db/call-with-custody {:seon.db/connection connection} ...)`. Inside an
evaluation that connection is the run's, so `*receipt*` and `*open*` survive and
the run's open rides the close. The run is open before admission closes, and its
settlement goes through. Outside an evaluation (the my.agent test) custody
switches to that connection with no receipt, as before. A refused close is now
the answer and is no longer discarded, so the contract allows
`:seon.error/value`.

The first try used plain `db/transact!` and was wrong. In
`my.agent-test/an-agent-retires-its-own-branch-and-the-shared-one-refuses`
the ambient custody is the test branch, the writer refused the foreign
connection, and the `when` threw the refusal away. That is why the original
code had `call-with-custody {}`.

After the fix, the same probe as agent wb-probe-3 answered every value:
`3`, then `{:seon.store/branch :agent-0f224e8733ca, :my.agent/retired? false,
:my.agent/retiring? true}`, then `:after`, in 192 ms. The reaching tests were
green in the pass step.

### Totality task (filed, red proof)

`seon.cluster.agent/a-run-refused-before-it-opens-answers-no-values`, proof
`seon.cluster.evaluate-source-test/a-run-whose-open-is-refused-still-answers-its-values`.
The proof asserts on data: the answer's evaluated sources show `["true" "3"]`,
and a `:seon.turn.loop/refusal` error sits beside them. The fix changes the REPL
path across `seon.turn/settle-batch-refusal!`, `seon.cluster.agent/evaluate-source!`
and `seon.mcp/sci-run`: answer unrecorded values and say they were not recorded.
That is more than 15 minutes, so it is left to an Opus lane.

## 2. The reds at HEAD f54e73c83

`bin/seon check --at HEAD --include-long` on the 16 named tests: run
f52aa2a17ca5, 16 executed, 109 pass, 24 fail, 2 error, 121.7 s. 15 were red.
`seon.db-overhead-test/query-preparation-has-bounded-armed-work` is green.

The first red commit comes from the operator check logs
(`data/clusters/default/logs/operator-check-*.edn`, which record red runs only)
and, where marked, from `check --at` on a commit and its parent.

| Test | Failing assertion | Commit that changed it | Class | Disposition |
|---|---|---|---|---|
| `my.program-test/schema-and-namespace-reads-keep-different-connections-distinct` | expects `:seon.program/breakage` to declare `seon.render.value/render-ai` | 216fbba1d (breakage now declares `my.program/render-breakage-ai`) | (c) | Fixed: the subject is `my.program/render-breakage-ai`. The tagged task's 5 s cost is gone: `breaks` on `seon.turn` took 83 ms live. Retire `my.program/breaks-on-a-namespace-subject-takes-five-seconds` once green (task file held by fanout-9b) |
| `seon.competency-test/t1`..`t4` | agent work not done | c6ae8875d, c13417919 (added red) | (d) | Red by design ("Red until an agent does the task"), seon.competency tasks |
| `seon.cluster.boot-test/reset-one-jvm` | `NumberFormatException` reading a timestamp on reset stdout | first red check bcace81d7 | (d) | `seon.cluster.boot/reset-cli-stdout-is-not-one-edn-value` |
| `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent` | realized 3232 < 1000 | fcb8556f3 (lazy seq stepped to half the budget's characters) | (c) | Fixed: the bound comes from the agent profile's budget (+1 chunk); the infinite case's shown length ≤ budget chars |
| `seon.cluster.host-test/a-refused-deletion-is-its-forms-refusal-and-the-run-closes` | the deletion refusal is not on the deleting form's receipt | 65cf8c26d (per task) | (d) / real | `seon.cluster.host/a-refused-deletion-reaches-the-deleting-forms-receipt` |
| `seon.cluster.turn-test/a-lost-model-call-leaves-a-durable-readable-reason` | the next prompt does not say the call was lost | ade6e3059 (CX-1) | (d) / real gap | `seon.context/a-lost-model-call-is-not-in-the-model-context` |
| `seon.cluster.turn-test/a-run-prompts-from-its-opening-database-value` | prompt read forms `:seon.message/_to`, `:seon.runtime/trigger`; message B absent from A | ade6e3059, 55ede54a9 (system-turn opening and prompt deleted; the block renders live) | (b)+(d) | It tests the deleted opening read forms and uses `with-redefs`. It is still the proof of `seon.context/a-message-to-the-agent-is-not-in-the-model-context`, so the task owner should rewrite it against the block's unread messages, not delete it |
| `seon.error.declared-identity-test/producers-declare-the-identity-settlement-records` | reads `:seon.error/declared-schema` from metadata | 33fb994f2 (Errors S1: type on `:seon.error/type`) | (c) | Fixed: reads `:seon.error/type`. Live probe: all three producers carry the type and validate. `seon.turn/refusal-schemas-are-guessed-by-key` is retired (green in check run d85faab6d464); `docs/seon/schedule` still lists it (owner's index). `seon.error/s1-left-metadata-...` stays open (its turn_test fixtures) |
| `seon.sci.eval-test/showing-a-value-realizes-and-stores-only-what-is-shown` | realized 3201 ≤ 64 | fcb8556f3: red; parent 3c65c27f1: green (`check --at` runs fbf90c5ff2f5 / d893136148e0) | (c) | Fixed: bound is `(inc (quot (estimate-chars 2000) 2))` = 3201, the design's stepping bound. The design steps 3201 elements to show about 1,250, about 2.5x what is shown, which may be worth a printer task |
| `seon.task-ingestion-test/an-assignment-reads-only-its-proofs` | 9.9 MB ≥ 8 MB | red since the proof landed (9f6e19cf1) | (d) | `seon.task/an-assignment-reads-only-its-proofs` |
| `seon.test.overlay-world-test/a-request-world-reuses-the-executions-acquired-program` | acquisition not identical | first red at this line e50a9299b (2026-10-09) | (d) / possible regression | `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`. `check --at` is red at 87326e203, f8f2618e8, 665ac1c10 and 769c4a843, so the 10-09 overlay changes did not cause it. The commit that turned it red is earlier than 87326e203 and was not found within the time box |
| `seon.cluster.agent-test/disarm-interrupts-an-in-flight-turn-and-records-it-interrupted` | latch `interruptible-prelude-entered` never fires (20 s) | ade6e3059 (CX-1: the stand-in's prelude is no longer called) | (d) | `seon.turn/a-stop-during-model-context-assembly-records-errors` |

## Other findings

- `(my.test/check {:seon.test/tests [...]})` is refused with "Nothing I changed
  since my base ... there is no change to check" even right after a `defn`; the
  key it reads is `:seon.test/identities`. The message points at the wrong
  cause. Seen as agent wb-probe-3.
- A function defined earlier in the same eval request is not supplied its world
  members: `(close-my-admission!)` with a `:seon.db/connection` input refused
  its argument count in the defining request and was supplied in the next one
  (wb-probe-6).
- A retired own branch stays in the roster after its MCP run exits: wb-probe-2
  and wb-probe-3 stayed until root retired them. This is covered by
  `seon.cluster.agent/finished-agents-and-their-branches-accumulate`.
- An agent's own contract error is recorded as an open core fault and turns
  cluster health `:faulted`. wb-probe-5 passed `seon.db/*conn*` (nil inside SCI)
  to `seon.db/transact!` inside `seon.db/call-with-custody {}`. The armed
  refusal was delivered on the form, as it should be, and was also recorded as
  fault task 5a8b119ea14a (`:seon.problems/open-fault` on
  `seon.db/call-with-custody`, layer `:seon.instrument/invocation`). AGENTS.md
  says an agent's own mistake is only delivered.
