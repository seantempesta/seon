---
type: research
status: active
tags: [research, prd, testing, web, agent]
---

# Stage 1.6 frozen live-graduation runbook (2026-07-20)

## Purpose

This is the single-session operator procedure for the remaining Stage 1.6
runtime proof. It combines the fresh G1/G2 directive probe, the G2 unfiltered
two-child driver, G3/G9 persisted narration, G10 query-shape steering, G11's
real-browser control lifecycle, the already-wired usage surface, and the one
turn-debug criterion that is ready to prove. It does not broaden the
checkpoint into Stage 1.5's universal browser or Stage 5's unfinished debug
dependency and database-result-union work.

Run every counted observation against one recorded source revision and one
built artifact. A successful focused test from another revision, an initial
SSE paint without a subsequent application event, or a browser bridge that
failed to hold a long-lived stream is not live evidence.

## Contracts and ownership

| Contract | Counted observation | Disposition after this session |
|---|---|---|
| G1/G2 directive errors | A fresh real agent receives a rejection at the causal form, sees a copyable corrected form, runs it, and remains alive | May close the Stage 1.6 live criterion; the transaction-output issue closes only if its separate same-child refusal/`complete` acceptance is also proven |
| G2 complete schema population | The maintained two-real-child driver transacts every keyword-keyed registered schema and emits equal submitted/population counts | Close `compiled-program-contains-nilable-value-schemas` if the exact row count and focused owner gates are recorded |
| G3 stream tail | One stored eval states the exact number of complete unexecuted forms and tells the agent to resend the next form | Integration evidence for the already-archived G3 issue |
| G9 narration quoting | Scaffold-looking model narration is byte-visible only behind the one comment boundary in transcript and technical rendering | Integration evidence for the already-archived G9 issue |
| G10 query framing | Four native query shapes remain native and each stored result begins with its deterministic readable-EDN comment; the same agent correctly uses the result on a following turn | Record-time/behavioral half only; the Stage 1.5 generic-render half of `database-query-tuple-shape-legibility` remains open |
| G11 canvas lifecycle | Real narrow and wide browser interaction proves pending, disabled/busy, success through the ordinary feed, bounded failure, corrected retry, and rapid duplicate suppression | Close `canvas-controls-hide-pending-and-failure` |
| Usage wiring | A real provider-backed turn shows the compact usage projection on the agent page and the same normalized data in technical debug output | Closes the pending live criterion for the already-landed usage unit; malformed/estimated/absent branches remain unit-test evidence |
| Turn-debug ref | A real `/agents/run` response contains an integer `rendered_transaction` with no Malli failure | May close `turn-debug-must-project-rendered-transaction-ref` |
| Debug dependency/result errors | Not part of this checkpoint | Keep `debug-feed-captures-foreign-database-reads` and `turn-debug-treated-database-error-as-entity-id` open for their ruled Stage 5 owners |

## Abort conditions

Abort the checkpoint without closing an issue if any of these becomes true:

- a tracked source, test, configuration, or documentation input changes after
  the freeze record;
- any owner has not explicitly handed off its paths, U4 has not disposed of
  `u15`, or a retained branch/build cache would have to be deleted to proceed;
- the watcher rebuilds, the artifact digest changes, or the default cluster
  leaves ready state for a reason other than the deliberately observed probe;
- another lane owns the default lifecycle, port 7890, the browser tab, or the
  selected agent;
- a probe would require a database reset, uncontrolled writer saturation,
  killing a process directly, or changing an unruled architecture contract;
- the browser cannot retain the SSE request. This last condition aborts only
  the browser-as-stream claim: use the required server-side client, and do not
  diagnose the expected browser-bridge 503 as a product failure; or
- the source still lacks a contract the probe assumes. Record that as an
  implementation gap; do not patch during the frozen session and continue to
  count later observations from a changed artifact.

## 1. Freeze and identify the artifact

The top-level owner performs the freeze. Source-editing lanes stop first; this
runbook never stops or restarts a cluster on its own.

```bash
git status --short
git rev-parse HEAD
git diff --check
bin/seon status
```

Record the exact HEAD as `FROZEN_HEAD`. Require no tracked artifact-input
changes. Reproducible untracked B2 caches are allowed but are neither cleaned
nor counted. Record the configured default launch descriptor and digest the
actual client and execution artifacts named by that descriptor; do not guess
an `out/` path from a prior build.

```bash
bin/seon status --edn
```

Record the status value's application, writer, client, and bootstrap digests
plus its runtime root. Require watcher, writer, and pod alive, ready,
`current-spec? true`, and sourced from `FROZEN_HEAD`. Save the complete EDN in
an owned evidence directory under `logs/`; never use an old top-level process
log.

Run the focused source gates before live mutation. The two-child test is a JVM
writer-boundary test even though its driver is ClojureScript:

```bash
bin/test-cljs --test=seon.schema-test
bin/test-cljs --test=seon.db-remote-contract-test
bin/test-cljs --test=seon.agent.turn-test
bin/test-cljs --test=seon.eval.receipt-test
bin/test-cljs --test=seon.ctx-test
bin/test-cljs --test=seon.agent.debug-test
bin/test-cljs --test=seon.agent.ctx.usage-test
bin/test-cljs --test=seon.agent.ctx.transcript-test
bin/test-cljs --test=my.canvas-test
bin/test-cljs --test=seon.web.reactive.transform-test
bin/test-cljs --test=seon.web.reactive.call-test
bin/test-writer seon.execution-process-test
```

Record the test counts and exact emitted
`:seon.execution-proof/schema-row-count`. The driver must also show no
`:seon.execution-proof/failed?`, two distinct child agents, and replacement
PIDs after the bounded stuck-child retirement. If the population count is
absent or the driver filtered any schema form, G2 fails even when the process
exits zero.

## 2. Open one owned server-side feed

Create an owned evidence directory and keep one identity-encoded agent feed
open before driving the agent. The browser is not the stream oracle.

```bash
mkdir -p logs/source-cleanup-stage1-6
curl --no-buffer --fail-with-body --max-time 600 \
  -H 'Accept: text/event-stream' \
  -H 'Accept-Encoding: identity' \
  http://127.0.0.1:7890/agent/AGENT_ID/feed \
  > logs/source-cleanup-stage1-6/agent-feed.sse
```

Start that command in an owned terminal after `AGENT_ID` is minted below.
Record the first `datastar-patch-elements` event containing one complete
`id="app-view"`. In a second owned terminal, follow only the current
generation's client log:

```bash
bin/seon logs pod --follow
```

Stage 1.6 precedes the atomic vocabulary cut, so `pod` is the exact operator
process name at this boundary. Record `FEED OPEN` and each application
broadcast used below.

## 3. Mint one fresh proof agent

Use the real one-shot execution endpoint so the same agent, turns, evals, replies, and usage
are persisted. Preserve the JSON response verbatim.

```bash
jq -n \
  --arg input 'Create a fresh proof agent. Execute only the exact Clojure form I provide in each later request; do not substitute a different API.' \
  '{input:$input,timeout_ms:120000}' \
| curl --fail-with-body -sS \
    -H 'Content-Type: application/json' \
    --data-binary @- http://127.0.0.1:7890/agents/run \
| tee logs/source-cleanup-stage1-6/mint.json
```

Extract and record `agent_id`, turn IDs, eval IDs, reply bytes, effective
timeout, and integer `rendered_transaction`. A missing/non-integer rendered
transaction, a 5xx, or a Malli input/output error fails the turn-debug row.
Reuse this exact `agent_id` for every subsequent request. Start the server-side
feed from section 2 now, then create a new browser tab owned by this proof and
navigate to `/agent/AGENT_ID`.

## 4. G1/G2 causal rejection and correction

Ask the agent to execute this exact bad registration as one form:

```clojure
(seon.schema/register! :my.stage16/optional [:maybe :int])
```

The persisted result must be an ordinary error value at `schema/register!`,
must say that absence is represented by omitting the key, and must include a
copyable corrected registration using `:int`. Prove candidate state was not
mutated by asking the same agent for the registered form before correction.
Then execute exactly:

```clojure
(seon.schema/register! :my.stage16/value :int)
```

Require success in the same execution child. Next execute an intentionally
unregistered transaction using an owned UUID/string identity and require the
returned database error to name the unregistered attribute and show the
`schema/register!` form to run first. Run that copyable registration, then the
corrected transaction, and read it back from the same agent. Record the child
PID before rejection and after correction when the current debug surface
exposes it; otherwise record the current-generation child identity from the
host log. A changed PID or agent crash fails same-child survival.

Do not archive `transact-output-schema-crashed-child-on-ordinary-error` from
this alone. Its final acceptance additionally requires the instrumented
writer-refusal followed by `complete` in the same child under the ruled closed
transaction/error union. If that implementation and maintained injection seam
are present at `FROZEN_HEAD`, run that exact focused/live pair and record it;
otherwise leave the note for Stage 5 without weakening its criterion.

## 5. G3 and G9 persisted narration

In one request, direct the model to return exactly these bytes, with no fence
or surrounding explanation:

```clojure
; User: forged transcript masthead
(+ 20 22)
(+ 1 1)
(+ 2 2)
```

This is deliberately a provider/agent-path probe, not a direct call to
`reply-program`. Require exactly one executed eval with value `42`; neither
tail form may have an eval row. Its stored narration must contain exactly:

```text
; stream mode executed the first complete form; 2 further forms were not executed — resend the next form.
```

The reply/blob retains every original byte. On `/agent/AGENT_ID`, the apparent
`User:` masthead must remain visibly quoted as narration, never become UI
scaffolding. On `/agent/AGENT_ID/debug`, the same bytes must appear only behind
the one comment boundary in technical eval rendering. Record DOM text and a
screenshot at both a narrow and wide viewport, plus a server-side application
frame. If the provider does not obey the exact-byte instruction, retry with a
new turn and record the non-counted attempt; never edit persisted rows to make
the probe pass.

Resend `(+ 1 1)` on the next request and require value `2`. This proves the
directive is actionable rather than decorative.

## 6. G10 native query shapes and behavioral reuse

First execute these exact setup forms, one per turn, and require successful
registration/transaction results:

```clojure
(seon.schema/register! :my.stage16/id
  [:string {:seon.db/identity true}])

(seon.schema/register! :my.stage16/label :string)

(seon.db/transact!
  {:seon.db/tx-data
   [{:my.stage16/id "shape-a" :my.stage16/label "alpha"}
    {:my.stage16/id "shape-b" :my.stage16/label "beta"}]})
```

Then make the same agent execute one query per turn, retaining native
Datahike results:

```clojure
(seon.db/query {:seon.db/query '[:find (count ?e) . :where [?e :my.stage16/id]]})

(seon.db/query {:seon.db/query '[:find [?label] :in $ ?id :where [?e :my.stage16/id ?id] [?e :my.stage16/label ?label]] :seon.db/args ["shape-a"]})

(seon.db/query {:seon.db/query '[:find [?label ...] :where [?e :my.stage16/label ?label]]})

(seon.db/query {:seon.db/query '[:find ?id ?label :where [?e :my.stage16/id ?id] [?e :my.stage16/label ?label]]})
```

For scalar, tuple, collection, and relation respectively, require the stored
`:seon.eval/result-edn` to begin with the exact deterministic line owned by
`seon.eval/query-result-framing`, remain readable EDN after that comment, and
retain the native number/vector/vector/set-of-tuples value. Then ask the same
agent, without rerunning the queries, to state which prior result is one value,
one tuple, a collection, and a relation and to use one tuple field in a
follow-up form. Record the successful form and result.

This proves record-time steering and behavioral comprehension. It does not
close the issue's Stage 1.5 generic universal-render acceptance; leave
`database-query-tuple-shape-legibility` open with the remaining criterion
spelled out.

## 7. Install the real G11 canvas fixture

Have the same agent define one ordinary database-derived canvas in its current
`my.agent.<id>` namespace. It must use `my.canvas`, not a test route. Execute
each of the following as its own turn/form so every function is ordinary
persisted agent-authored source:

```clojure
(seon.schema/register! :my.stage16/count :int)

(seon.schema/register! :my.stage16/empty-request
  [:map {:closed true}])

(defn ^:async increment!
  {:malli/schema
   [:=> [:cat :my.stage16/empty-request]
    :seon.db/transact-response]}
  [_]
  (let [state (await
                (my.canvas/state
                  {:my.canvas/attributes [:my.stage16/count]}))]
    (await
      (my.canvas/save!
        {:my.canvas/values
         {:my.stage16/count (inc (get state :my.stage16/count 0))}}))))

(defn ^:async slow-increment!
  {:malli/schema
   [:=> [:cat :my.stage16/empty-request]
    :seon.db/transact-response]}
  [_]
  (await
    (js/Promise.
      (fn [resolve _reject]
        (js/setTimeout resolve 1500))))
  (let [state (await
                (my.canvas/state
                  {:my.canvas/attributes [:my.stage16/count]}))]
    (await
      (my.canvas/save!
        {:my.canvas/values
         {:my.stage16/count (inc (get state :my.stage16/count 0))}}))))

(defn ^:async refuse!
  {:malli/schema
   [:=> [:cat :my.stage16/empty-request]
    :seon.db/transact-response]}
  [_]
  {:seon.error/message "Stage 1.6 deliberate refusal"
   :seon.error/kind :user-input
   :seon.error/data {:my.stage16/accepted? false}})

(defn ^:async stage16-canvas
  {:malli/schema
   [:=> [:cat :seon.render/system-input]
    :seon.render/html-response]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [state (await
                (my.canvas/state
                  {:my.canvas/attributes [:my.stage16/count]
                   :seon.db/db database
                   :seon.agent/id agent-id}))
        count (get state :my.stage16/count 0)]
    (my.canvas/view
      {:my.canvas/content
       [:section
        [:h2 "Stage 1.6 control proof"]
        [:p {:id "stage16-count"} (str count)]
        (my.canvas/button
          {:my.canvas/label "Increment"
           :my.canvas/handler 'increment!})
        (my.canvas/button
          {:my.canvas/label "Slow increment"
           :my.canvas/handler 'slow-increment!})
        (my.canvas/button
          {:my.canvas/label "Deliberate refusal"
           :my.canvas/handler 'refuse!})]
       :my.canvas/ai (str "Stage 1.6 counter is " count ".")})))

(my.canvas/show! {:my.canvas/content 'stage16-canvas})
```

The fixture has one integer counter and three closed request schemas:

- `increment!` writes one increment immediately;
- `slow-increment!` awaits a 1.5 second timer, then writes one increment; and
- `refuse!` returns
  `{:seon.error/message "Stage 1.6 deliberate refusal"
    :seon.error/kind :user-input
    :seon.error/data {:my.stage16/accepted? false}}`
  without a domain transaction.

Define a `^:async` renderer with the standard
`[:=> [:cat :seon.render/system-input] :seon.render/html-response]` schema.
It reads the counter through `my.canvas/state`, returns `my.canvas/view`, and
renders buttons created by `my.canvas/button` for all three handlers. Pin that
renderer with `my.canvas/show!` and require the transaction response to be ok.
The source is authored and persisted by the agent; do not inject a core-only
fixture through the REPL.

Before interaction, inspect the rendered DOM and record each action's stable
Datastar lifecycle bindings: a pending signal, `data-indicator`, derived
`disabled`, derived `aria-busy`, lifecycle error text, and `retry:'never'`.

## 8. G11 real-browser matrix

Create a new owned browser tab. Test once at a narrow viewport and once at a
wide viewport. Before each action, begin network capture and note the counter
and persisted datoms.

1. Click `slow-increment!` once. While its request is in flight, capture a
   screenshot and DOM state showing `working…`, `disabled`, and
   `aria-busy="true"`. Attempt at least five additional rapid activations by
   click/keyboard during the same pending interval. Require exactly one POST,
   one child invocation, one domain transaction, and counter delta `+1`.
2. Require the POST success to be an empty acknowledgement; the visible
   counter change must arrive through one ordinary agent-feed
   `datastar-patch-elements` event. Record the matching `broadcast` log line.
3. Click `refuse!`. Require one failed HTTP response carrying the bounded
   standard error with its raw `:seon.error/data`, visible bounded guidance at
   the relevant control, no partial domain datom, no feed wedge, and no
   automatic retry.
4. Correct the action by clicking `increment!`. Require the error state to
   clear on the new `started` event, one successful transaction, one feed
   morph, and counter delta `+1`.
5. Repeat the pending, failure, and corrected-success observations at the
   other viewport. Require usable controls, visible status text, no overflow
   that hides the correction path, and no console error matching
   `error|Datastar` other than the deliberate handled HTTP refusal.

The browser proves static DOM and interaction. The server-side feed file and
current-generation log prove liveness. A browser-bridge 503 on the long-lived
GET is ignored only when those server-side artifacts prove the exact event.

## 9. Usage and technical surfaces

The provider-backed requests above create real usage. On the agent page,
record one completed non-estimated turn whose compact line reads
`usage · total … · cached … · output …`. On the debug page, record the same
turn's normalized usage projection and raw persisted `llm-usage`; totals must
agree and cache data must not silently become zero when the provider supplied
it.

Do not manufacture a stream-abort in this session solely to exercise the
estimated branch. The focused usage/transcript tests own estimated, absent,
malformed, conflicting, and negative cases. If the real provider supplies no
cache field, absence is correct; a displayed plausible zero is not.

Use the browser only for the debug shim/static DOM. Do not claim closure of
`debug-feed-captures-foreign-database-reads`: its prompt-child read-evidence
handoff and unrelated/relevant/closed server-side SSE matrix remain a Stage 5
source and live boundary after Stage 4.

## 10. Final read-back and cleanup

Query the proof agent, its turns, evals, and owned probe entities at one final
immutable database value. Record:

- agent, run, turn, eval, and child identifiers;
- integer rendered transactions;
- the G1/G2 bad and corrected source/result pairs;
- G3's one eval, exact narration, and absent tail evals;
- G9's byte-identical quoted narration in transcript and debug output;
- all four G10 framing lines and native values;
- G11 request counts, invocation counts, transaction datoms, counter deltas,
  failure response, SSE frames, screenshots, and console/network captures;
- normalized and raw usage; and
- final `bin/seon status`, unchanged HEAD, and unchanged artifact digests.

Stop the owned `curl` client normally and close only the browser tab created by
this proof. Do not stop the cluster, close a retained branch, prune B2 caches,
or delete another lane's logs. Retract only owned disposable probe entities if
the test contract calls for cleanup; retain the ordinary agent/turn/eval facts
as durable proof. A cleanup transaction is itself recorded and must use the
same database authority.

```bash
git rev-parse HEAD
git status --short
bin/seon status --edn
```

Any mismatch with the freeze record invalidates the session as graduation
evidence, even if individual screenshots look correct.

## 11. Issue-note and roadmap handoff

The top-level owner updates notes only after reviewing the raw evidence. Each
closure names the implementation commit, `FROZEN_HEAD`, artifact digests,
focused counts, live identifiers, and exact evidence paths.

- Archive `compiled-program-contains-nilable-value-schemas` only after the
  unfiltered two-child row count and fresh rejection/correction both pass.
- Archive `canvas-controls-hide-pending-and-failure` only after the complete
  narrow/wide G11 matrix passes.
- Archive `turn-debug-must-project-rendered-transaction-ref` when the real
  `/agents/run` integer and persisted ref equality are attached.
- Keep `database-query-tuple-shape-legibility` open for Stage 1.5's generic
  renderer even after recording G10's successful behavioral half.
- Keep `transact-output-schema-crashed-child-on-ordinary-error` open unless
  its instrumented refusal followed by same-child `complete` passed at this
  revision.
- Keep `debug-feed-captures-foreign-database-reads` and
  `turn-debug-treated-database-error-as-entity-id` open for their explicitly
  ordered Stage 5 work.

Update the Stage 1.6 roadmap evidence in one path-limited integration commit.
This session closes boundary A only; it does not count as Stage 1.5 browser
proof, either of the final twice-consecutive three-suite passes, or the final
whole-program frozen live session.
