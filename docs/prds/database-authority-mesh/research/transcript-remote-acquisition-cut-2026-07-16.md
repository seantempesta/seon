---
type: research
status: complete
tags: [research, database, flow, agent]
---

# Transcript remote acquisition cut — 2026-07-16

## Decision

The compiled per-agent Bun child remains the prompt owner. The transcript
block performs two coordinate-pinned, block-owned acquisition stages through
the asynchronous `seon.db` API and then calls one synchronous formatter over
ordinary data:

1. acquire the transcript policy, exact turn count, bounded turn window,
   current namespace, and current-run counts concurrently; and
2. use the returned retained turn IDs and oldest retained time to acquire the
   exact eval and message rows concurrently.

This is the smallest exact dependency cut. The second stage depends on the
first stage's database results, so putting every member in one fixed request
would either be impossible or create a result-binding query language that the
database protocol deliberately does not have. The two stages issue at most two
authority requests, never one request per entity. Empty event inputs skip the
eval member, but not the message member: an agent with no turns may still have
standalone messages.

The existing transcript formatting, age decay, coalescing, omission, result
handle, and byte-stability rules stay in ClojureScript. The JVM returns stored
facts only. No database value, lazy entity, Datom, render function, replay map,
or acquired-result cache crosses or survives the boundary.

## Dependency ledger

| Owner | Selected revision | Source and constraint |
|---|---|---|
| Seon | `a5822b13785d94bf7e7fe80f37476549b4346bad` | `src/seon/agent/ctx/transcript.cljs`, `src/seon/agent/ctx.cljs`, `src/seon/derive.cljs`, `src/seon/eval.cljs`, and `src/seon/agent/message.cljs` define the current bytes and database reads. |
| Seon database protocol | protocol version 7 at the selected Seon revision | `src/seon/db/protocol.cljc` bounds a frame at 4 MiB and `execute-many` at 1–64 query, pull, pull-many, schema, or index-page members. Member result position is identity. |
| Seon authority | selected Seon revision | `src/seon/db/writer.clj` resolves one database value for `execute-many`, submits independent members through bounded read capacity, materializes ordinary data, and preserves member-local failures. |
| Datahike | `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | `src/datahike/query.cljc` supports map queries with `:order-by`, `:offset`, and `:limit`; ordering is applied after result production. `src/datahike/resource.cljc` enforces work, result-count, and structural result-weight bounds. |
| Datahike pull | same Datahike revision | `src/datahike/pull_api.cljc` resolves missing well-formed refs to nil, parses one `pull-many` selector once, shares one budget, and preserves input positions. This remains the later optimization seam if query-embedded pull is measured worse. |
| Prompt owner decision | [[compiled-child-prompt-owner-2026-07-16]] | The child inherits exact coordinate `C`, starts built-in acquisitions concurrently, and gives ordinary resolved block values to the synchronous renderer. |
| Remote API decision | [[remote-seon-db-contract-freeze-2026-07-16]] | Remote reads are asynchronous, eager, coordinate-pinned ordinary data. There is no remote entity traversal or database-value emulation. |

Current behavioral proofs are
`test/seon/agent/ctx/transcript_test.cljs`,
`test/seon/repl/autocomplete_test.cljs`, and `test/seon/ctx_test.cljs`.

## Current read graph and the avoidable work

`transcript-block` currently repeats local lazy traversal:

- `agent-rec` resolves the agent;
- `ctx/current-ns` queries all successful eval candidates and sorts them;
- `ordered-events` obtains `ctx/agent-turns`, then `message-events` performs a
  query with pull, while `eval-events` calls `ctx/agent-turns` again;
- `block-ent` touches the agent and walks its context refs;
- turn count calls `ctx/agent-turns` a third time;
- `readline` independently repeats current namespace, all turns, current run,
  derived state, REPL mode, and run-policy reads; and
- HTML has a separate bounded path which is not part of prompt acquisition.

That is tolerable only while every read is an in-process lazy Datahike view.
Awaiting the same calls remotely would turn one block into an unbounded socket
waterfall and would repeatedly materialize the same run/turn graph. The cut
below obtains each required fact once and formats without another database
call.

## Input already supplied by prompt discovery

The compiled prompt owner's initial block discovery supplies these ordinary
values to the transcript owner:

```clojure
{:seon.agent/id "agent-id"
 :seon.agent/entity
 {:db/id 101
  :seon.agent/id "agent-id"
  :seon.agent/terminated-at #inst "..."       ; optional
  :seon.agent.ctx/escape-clipping? true        ; optional
  :seon.agent/run                              ; optional
  {:db/id 202
   :seon.agent.run/id "run-id"
   :seon.agent.run/status :open
   :seon.agent.run/paused-at #inst "..."       ; optional
   :seon.agent.run/turn-limit 60}}
 :seon.render/node
 {:seon.agent.ctx/name :transcript
  :seon.agent.ctx/priority 100
  :seon.agent.ctx.transcript/turn-window-size 50
  :seon.agent.ctx.transcript/turn-eviction-size 25
  :seon.agent.ctx.transcript/settled-token-cap 8192
  :seon.agent.ctx.transcript/result-decay
  [{:seon.agent.ctx.transcript/from-turn-offset 0
    :seon.agent.ctx.transcript/token-cap 4096}]
  :seon.agent.ctx.transcript/readline? true
  :seon.agent.ctx.transcript/result-handles? true}}
```

The node is already the stored block or the profile patch merged over the
stored block. Prompt discovery must expand the transcript block's existing
component refs, `:seon.agent.ctx.transcript/result-decay` and the retained
legacy `:seon.agent.ctx.transcript/tiers`, while pulling the agent. The
transcript owner must not re-pull the agent or search `:seon.agent/ctx` by
name. Missing keys retain the existing schema-derived defaults.

This is not a fixed prompt batch. It is the ordinary input of whichever
stored/profile block the prompt owner discovered at `C`.

## Stage one: policy, turns, and current state

The block computes `window-size` from the effective node before I/O. Add a
maximum of 200 to the existing `::turn-window-size` schema. Do not silently
clamp a larger stored value: configuration reconciliation must reject it. The
default 50 and every existing maintained fixture remain unchanged.

One `seon.db/execute-many` at `C` contains the following members. Members 5
and 6 exist only when the supplied current run has `:seon.agent.run/status
:open`; omitting them is data-dependent composition, not a second request
path.

### Member 0 — cluster transcript policy

One pull member addresses
`[:seon.config/id seon.config/cluster-config-id]` with this selector:

```clojure
[:seon.config/repl-mode
 :seon.config.run/batch-turn-limit
 :seon.config.run/stream-form-limit
 :seon.config.run/deadline-ms]
```

Limits: `max-work 256`, `max-results 32`, and `max-result-weight 4096`.
Missing entity or attributes resolve through the existing `:batch` and
`config/default-run-policy` defaults in the formatter.

### Member 1 — exact total turn count

```clojure
[:find (count ?turn) .
 :in $ ?agent-id
 :where
 [?agent :seon.agent/id ?agent-id]
 [?run :seon.agent.run/agent ?agent]
 [?turn :seon.agent.turn/run ?run]]
```

Arguments are `[agent-id]`. Limits: `max-work 1000000`, `max-results 8`, and
`max-result-weight 128`.

### Member 2 — newest possible retained turns

Use a query map so Datahike owns deterministic ordering and the returned row
bound:

```clojure
{:find [?turn ?at ?scheduled? ?run ?run-id]
 :in [$ ?agent-id]
 :where
 [[?agent :seon.agent/id ?agent-id]
  [?run :seon.agent.run/agent ?agent]
  [?run :seon.agent.run/id ?run-id]
  [?turn :seon.agent.turn/run ?run]
  [?turn :seon.agent.turn/at ?at]
  [(get-else $ ?turn :seon.agent.turn/scheduled? false) ?scheduled?]]
 :order-by [?at :desc ?turn :desc]
 :limit window-size}
```

Limits: `max-work 1000000`, `max-results 4096`, and
`max-result-weight 65536`. The child reverses the rows to ascending
`[at turn]` order. Datahike applies `:order-by` after result production, so
the limit bounds retained rows and wire size but does not falsely claim an
index-top-N execution. The work bound is the explicit protection for a very
old agent.

Given `turn-count`, the child computes the existing
`turn-window-cutoff`. It removes stage-one rows whose absolute index is before
that cutoff. Fetching at most `window-size` rows is sufficient: the existing
rotation retains no more than that many turns at any point.

### Member 3 — latest successful namespace

```clojure
{:find [?ns ?at ?eval]
 :in [$ ?agent-id]
 :where
 [[?agent :seon.agent/id ?agent-id]
  [?eval :seon.eval/agent ?agent]
  [?eval :seon.eval/ok? true]
  [?eval :seon.eval/at ?at]
  [?eval :seon.eval/ns ?ns]]
 :order-by [?at :desc ?eval :desc]
 :limit 1}
```

Limits: `max-work 500000`, `max-results 64`, and
`max-result-weight 1024`. An empty result uses the existing
`home/home-ns` fallback. Including the entity ID makes equal timestamps
deterministic.

### Member 4 — latest action time

`transcript-block` currently decides whether an inbound message is unanswered
before applying the turn window. Preserve that rule without acquiring old
events by asking for the newest eval or outbound-message time:

```clojure
[:find (max ?at) .
 :in $ ?agent-id
 :where
 [?agent :seon.agent/id ?agent-id]
 (or-join [?agent ?at]
   (and [?eval :seon.eval/agent ?agent]
        [?eval :seon.eval/at ?at])
   (and [?message :seon.agent.message/from ?agent]
        [?message :seon.agent.message/at ?at]))]
```

Limits: `max-work 500000`, `max-results 8`, and
`max-result-weight 128`. This avoids incorrectly marking a retained inbound as
new merely because the action it answered rotated out of the visible window.

### Members 5 and 6 — current-run work counts

For an open `run-id`, retain the existing `derive/run-turn-count` and
`derive/run-form-count` queries exactly:

```clojure
[:find (count ?turn) .
 :in $ ?run-id
 :where
 [?run :seon.agent.run/id ?run-id]
 [?turn :seon.agent.turn/run ?run]
 (not [?turn :seon.agent.turn/scheduled? true])]
```

```clojure
[:find (count ?eval) .
 :in $ ?run-id
 :where
 [?run :seon.agent.run/id ?run-id]
 [?turn :seon.agent.turn/run ?run]
 (not [?turn :seon.agent.turn/scheduled? true])
 [?turn :seon.agent.turn/evals ?eval]]
```

Each uses `max-work 500000`, `max-results 8`, and
`max-result-weight 128`. The formatter selects the form count in `:stream`
mode and the turn count in `:batch` mode. It derives `:seon.derive/state` by
calling the existing pure `derive/state-from-primitives` over the supplied
agent/current-run maps.

Stage one's aggregate `max-result-weight` is 131072. A member error remains at
its vector position and becomes the transcript block's ordinary error text;
siblings are not discarded.

## Stage two: retained evals and messages

An existing agent always runs stage two because an agent with no turns may
still have standalone messages. The eval member is omitted when there are no
retained turn IDs. One `execute-many` at the same `C` starts the independent
members below concurrently. No member observes a new head. The only complete
skip is a missing agent already rejected by prompt discovery.

### Member 0 — eval rows for retained turns

```clojure
[:find ?turn
        (pull ?eval
          [:db/id
           :seon.eval/id
           :seon.eval/at
           :seon.eval/source
           :seon.eval/narration
           :seon.eval/output
           :seon.eval/ok?
           :seon.eval/result-edn
           :seon.eval/error
           :seon.eval/error-data
           :seon.eval/ns
           :seon.render/full?])
 :in $ [?turn ...]
 :where
 [?turn :seon.agent.turn/evals ?eval]
 [?eval :seon.eval/at _]]
```

Arguments contain the retained turn EIDs in ascending turn order. The child
groups by returned turn EID, sorts each turn's evals by
`[:seon.eval/at :db/id]`, and assigns the turn's already-known absolute
index. It then calls `seval/result-live?` in the owning child unless the
effective node has `::result-handles? false`.

Limits: `max-work 1000000`, `max-results 16384`, and
`max-result-weight 524288`. These are hard failure bounds, not truncation.
Datahike's resource counter includes pulled result nodes, so
`max-result-weight` is the principal content bound; `max-results` catches
pathological populations of tiny rows.

### Member 1 — messages in the retained interval

When turns exist after rotation, `cutoff-at` is the oldest retained turn's
`:seon.agent.turn/at`; otherwise the predicate is omitted so a message-only
new agent retains current behavior.

```clojure
[:find
 (pull ?message
   [:db/id
    :seon.agent.message/id
    :seon.agent.message/content
    :seon.agent.message/at
    :seon.agent.message/hops
    :seon.agent.message/origin
    {:seon.agent.message/from
     [:db/id :seon.user/id :seon.agent/id]}
    {:seon.agent.message/to
     [:db/id :seon.user/id :seon.agent/id]}])
 :in $ ?agent-id ?cutoff-at
 :where
 [?agent :seon.agent/id ?agent-id]
 (or-join [?message ?agent]
   [?message :seon.agent.message/from ?agent]
   [?message :seon.agent.message/to ?agent])
 [?message :seon.agent.message/at ?at]
 [(>= ?at ?cutoff-at)]]
```

The no-turn form has the same clauses without `?cutoff-at` and its predicate.
The child applies the existing `message->event` waking/hop gate, assigns turn
indices with `turn-index-at`, and sorts by `[at db/id]`. Limits are
`max-work 1000000`, `max-results 8192`, and
`max-result-weight 262144`.

### Member 2 — namespace marker seed

`with-ns-markers` currently runs before turn-window omission. When rotation
has omitted old turns, preserve whether the first retained eval needs an
`; in ...` line by acquiring the newest namespace from an omitted turn:

```clojure
{:find [?ns ?eval-at ?eval]
 :in [$ ?agent-id ?cutoff-at]
 :where
 [[?agent :seon.agent/id ?agent-id]
  [?run :seon.agent.run/agent ?agent]
  [?turn :seon.agent.turn/run ?run]
  [?turn :seon.agent.turn/at ?turn-at]
  [(< ?turn-at ?cutoff-at)]
  [?turn :seon.agent.turn/evals ?eval]
  [?eval :seon.eval/at ?eval-at]
  [?eval :seon.eval/ns ?ns]]
 :order-by [?eval-at :desc ?eval :desc]
 :limit 1}
```

Limits: `max-work 500000`, `max-results 64`, and
`max-result-weight 1024`. Omit this member when no turns were rotated out.
Pass its namespace as the initial previous namespace to `with-ns-markers`.
The maintained write path stamps turns before their evals and uses increasing
times; add an adversarial fixture to keep that temporal invariant explicit.

Stage two's aggregate `max-result-weight` is 790528: the two content members'
786432 combined allowance plus 4096 for the optional namespace seed and
response structure. Structural weight counts
string characters plus collection structure. Keeping it below 1 MiB leaves a
large margin under the protocol's exact 4 MiB encoded-frame fence even for
multibyte text and the response envelope. The framing validator remains the
final byte authority.

Do not put a positive query `:limit` on evals or messages merely to fit. In
Datahike, ordering/limit is applied after pull and result production, and a
limit would silently remove transcript facts. Resource overflow must instead
return one explicit block-local error. If normal workloads hit these limits,
measure the rows and change the transcript's existing stored window/content
policy; do not add hidden truncation.

### Why query-embedded pull is selected first

The alternative is an ID query followed by one ordered `pull-many`. Datahike's
`pull-many` is a strong primitive: it parses once, shares one budget, preserves
positions, and returns nil for missing refs. It remains the selected fallback
if measurement shows query-embedded pull retaining excessive intermediate
state.

The first cut uses query-embedded pull because it avoids a third socket round
trip and lets Datahike's exact query cache/single-flight share the complete
projection on a warm read. It still performs one JVM operation per fact set,
not N entity operations. The cold/warm falsifiers below decide this seam with
measurements rather than preference.

## Ordinary formatting input

After checking both coordinate echoes equal `C`, the acquisition code builds
one ephemeral ordinary map using existing database and transcript names:

```clojure
{:seon.agent/id "agent-id"
 :seon.agent/entity {...}
 :seon.render/node {...}
 :seon.config/repl-mode :batch
 :seon.config.run/batch-turn-limit 30
 :seon.config.run/stream-form-limit 60
 :seon.config.run/deadline-ms 900000
 :seon.derive/state :running
 :seon.agent.ctx.transcript/turn-count 27
 :seon.agent.ctx.transcript/turns
 [{:db/id 301
   :seon.agent.turn/at #inst "..."
   :seon.agent.turn/scheduled? false
   :seon.agent.turn/run
   {:db/id 202 :seon.agent.run/id "run-id"}
   :seon.agent.turn/evals [{:db/id 401 :seon.eval/id "eval-id" ...}]}]
 :seon.agent.ctx.transcript/messages
 [{:db/id 501 :seon.agent.message/id "message-id" ...}]
 :seon.agent.ctx.transcript/last-action-at #inst "..."
 :seon.agent.ctx.transcript/previous-ns :my.agent.example
 :seon.agent.run/turn-count 4
 :seon.agent.run/form-count 4
 :seon.eval/ns :my.agent.example}
```

This map is invocation-local input, not stored data and not a replay table. It
does not retain request IDs or member results after formatting. The block
formatter derives events once, renders once, and lets the outer prompt owner
retain only the already-established rendered block/text result.

## Functions retained as pure transformations

Retain these functions with their current names and semantics:

- `decay-cap-for-offset`, `tier-cap-for-turn`,
  `clip-events-by-tiers`, `turn-window-cutoff`,
  `clip-events-by-turn-window`, and
  `clip-rendered-events-by-settled-budget`;
- `mode-fragment`, `masthead`, and `clock`;
- `message->renderable`, `eval->renderable`, and
  `coalesced->renderable`, after removing their ambient database fallbacks;
- `noise-eval?`, `error-signature`, `coalesce-events`, `turn-index-at`,
  `message->event`, `with-ns-markers`, and `format-bytes`; add an optional
  initial namespace argument to `with-ns-markers` while preserving its current
  one-argument behavior; and
- `host-telemetry`, because it is intentionally process-local material in the
  free dynamic readline tail.

`recent-html-events`, `coalesced-card-html`, and the HTML activity formatting
remain separate UI work. The prompt entrypoint must not acquire or render the
HTML twin.

## Functions changed or removed from this path

- Change `transcript-block` into the synchronous formatter of the ordinary
  map above. It must call no `seon.db` function.
- Add one private asynchronous acquisition function in the transcript
  namespace. It owns the two stages but introduces no public block catalog or
  protocol operation.
- Change `readline` to format acquired namespace, count, state, mode, limits,
  current time, and optional host telemetry. Delete its database reads.
- Change `ordered-events` to accept acquired turns, evals, and messages.
- Change `eval->event` to accept the child-derived `result-live?` value rather
  than consulting process state while constructing database rows.
- Delete `block-ent`, `agent-rec`, `message-events`, and `eval-events` from the
  prompt path. Their database traversal has one replacement: the bounded
  acquisition above.
- Remove fallback calls to `ctx/escape-clipping?` from message/eval formatting.
  Acquisition supplies the effective boolean once; profile overrides still
  win through `:seon.render/node`.
- Do not call `ctx/current-ns`, `ctx/agent-turns`, `ctx/repl-mode`,
  `ctx/run-policy`, `derive/current-run`, or `derive/derive-state` during
  formatting. Their rules are retained through the exact queries and the pure
  `derive/state-from-primitives` projection.

The existing public read helpers outside prompt rendering may continue until
their own remote cut. This report removes traversal only from the compiled
prompt transcript path; it does not create a parallel transcript renderer.

## Parity and failure proofs

### Pure tests retained

Keep every existing pure assertion in
`test/seon/agent/ctx/transcript_test.cljs`: source-owned defaults, batched
50/25 rotation, settled-budget newest-first charging, age decay, coalescing,
message retention, exact result-handle membership, and HTML window behavior.

### New acquisition and byte tests

Add focused tests for:

1. empty agent, message-only agent, and eval-only agent;
2. turn counts 0, 1, 49, 50, 74, 75, and 200, proving the returned turn IDs
   and final bytes match the current local renderer at the same `C`;
3. `:batch` and `:stream` current-run counts, including scheduled turns,
   paused, idle, running, and terminated state;
4. a profile with `readline? false`, `result-handles? false`, escape clipping,
   and patched decay levels, proving autocomplete remains byte-identical and
   deterministic at a resolved coordinate;
5. waking inbound, core-origin, hop-exhausted, outbound, and equal-time
   message/eval rows, with deterministic entity-ID tie breaks, an omitted
   latest action, and an omitted prior namespace marker seed;
6. success, read error, Malli error, output, narration, multiline result,
   `:seon.render/full?`, age decay, and coalesced failure runs;
7. missing optional config attributes and a missing stored transcript block
   under a profile patch;
8. exactly one stage-one and at most one stage-two authority request, with no
   request count proportional to turns, evals, messages, or recipients;
9. every request and response names `C`; a mismatched coordinate rejects the
   block before formatting;
10. redefining every `seon.db` function to throw during the formatting call,
    proving the synchronous tail performs zero database I/O; and
11. work/result/frame overflow, cancellation, and member failure producing one
    transcript block error while sibling prompt blocks still render.

The whole-context gates remain:

- default prompt versus debug AI bytes after normalizing the one live readline
  line;
- autocomplete exact-coordinate reproduction and 700-token profile budget;
- rendered blocks reused once by prompt/debug accounting; and
- rendering writes no datoms.

## Cold and warm performance falsifiers

Record the current local implementation before replacement and the remote
implementation after replacement. Use fixtures with 0, 50, and 200 retained
turns; 0, 128, and the admitted maximum eval rows; and 0, 64, and the admitted
maximum messages. Include small ASCII and worst-case multibyte strings.

For each fixture measure:

- end-to-end transcript acquisition, formatting, and total prompt latency at
  p50/p95/p99;
- authority request count, Transit request/response bytes, decoded result
  weight, and exact final frame bytes;
- Datahike query work, result count/weight, query-cache hit, and single-flight
  evidence per member;
- JVM executor queue/run time, CPU, allocation, and peak retained bytes;
- Bun child CPU, event-loop delay, allocation, RSS, formatting time, and final
  text bytes; and
- cancellation latency and retained request/database state after completion.

The cold run uses a fresh child and cleared Datahike query cache. The warm run
repeats the identical agent and coordinate after one successful render. A
second matrix runs 1, 8, and 32 simultaneous prompt acquisitions across one
database and across independent databases.

The cut is falsified if:

- request count grows with entity count;
- formatting performs any database operation;
- a warm identical query performs the cold query work instead of reporting a
  Datahike cache/single-flight hit;
- query-embedded pull is slower or retains more memory than an ID query plus
  one `pull-many` by more than the saved socket round trip;
- one large transcript delays unrelated databases beyond the executor's fair
  admission bound;
- a canceled prompt retains an active request, result, or database reference;
- a resource limit silently drops a row instead of returning a block-local
  error; or
- any non-readline byte differs from the existing renderer at the same `C`.

Only the measured query-embedded-pull versus ordered-`pull-many` comparison is
left open. Both use the same ordinary result shape and pure formatter, so that
choice does not rewrite the transcript interface.
