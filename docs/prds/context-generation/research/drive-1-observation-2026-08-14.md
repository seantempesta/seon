---
type: research
status: complete
date: 2026-08-14
tags: [research, agent, context, runtime, live-drive]
---

# Drive 1 independent observation — 2026-08-14

## Scope and independence

I read `prds/context-generation/plan/live-drive-spec-2026-08-13.md`
end to end and observed without contacting, messaging, or waiting on the
driver lane. I did not mutate the drive through an agent message, transaction,
operator lifecycle command, or SCI door evaluation. I did not use the
driver-authored report as evidence for this account.

The requested live session did not complete an evolve → rebirth → continue
cycle. It failed before its first model attempt. The most important result is
therefore not a quality score: the drive machinery did not admit the opening
context, and the independent observer could not discover the fallback cluster
through the specified advertisement contract.

## Observation boundary and preserved subject

The observer resumed at approximately `2026-08-14T05:27Z`. Through
`06:10:37Z`, repeated `bin/seon status` calls advertised only shared-root
cluster `default`; `mcp__seon__runtime_status` for `drive-one` returned an
empty cluster set, and `data/clusters/drive-one` never existed.

Verbatim final shared-root status excerpt:

```text
CLUSTER                     PID STATE       PREPL URL                      DRIFT
default                   33123 alive       52490 http://127.0.0.1:7994    -
1/1 clusters alive
```

After the 45-minute boundary, a recent-root filesystem census found the
preserved fallback independently:

```text
root:       tmp/drive-1-root
cluster:    default
PID:        46196
prepl:      127.0.0.1:54470
web:        http://127.0.0.1:54474
started:    2026-08-14T05:37:54Z
```

The cluster log says it reached ready at `05:38:04Z`. The shared-root boot
failure and isolated fallback are already owned by
`docs/seon/issues/cluster-boot-instruments-in-flight-working-tree-vars.md`;
this observation adds the independent discovery consequence there.

`bin/seon --root tmp/drive-1-root status` could not inspect the live root. It
returned `:seon.operator/root-creator-mismatch` because the observer was not
the root creator. Explicit-root MCP discovery could reach the same
advertisement and JVM evaluation returned `3` for `(+ 1 2)` in 2 ms.

## What the context actually looked like

### Phase 1 — objective fact

The drive agent existed as `drive-one-agent` in namespace
`my.agents.drive-one-agent`. Its one inbound objective message was:

> Define a durable contracted function named largest that returns the row
> with the greatest :example/amount, or {} for empty input. Call it once,
> query its stored :seon.fn/spec, then complete with a short reply naming what
> you built and its contract.

This task fact was clear and appropriately small. It was the one place the
agent was well-served: the requested behavior, empty-input case, required call,
and proof query were explicit.

The required real `my.plan` was not present. A fact query at observed basis
`t=536871061` found zero `:my.plan.item/agent` rows for the drive agent, and the
objective names neither `my.plan` nor a resolvable subject. The live spec's
structured-`:about` and #11 measurements therefore had no subject.

### Phase 2 — generated opening

Run `bootstrap:drive-one-agent` opened at `05:39:41Z`. Its complete form set
was one form:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

Its one receipt landed at `05:39:42Z` with the exact error:

```text
seon.test.accretion/non-generatable-advisory violated its contract
(invalid-input): invalid type
```

The run closed at `05:39:45Z` carrying:

```text
The EDN-backed attribute :seon.db/read-request has an invalid logical value.
```

The context never crossed the prompt-capture boundary:

```text
forms:            1
receipts:         1 failed
context captures: 0
provider attempts: 0
plan items:       0
```

There is consequently no honest DeepSeek-visible opening excerpt to quote.
The model did not flail; it never received a request. Basis-`t` comprehension,
first-use demonstrations, `doc`/`dir` behavior, replay behavior, and plan use
were unobservable.

### Phase 3 — generated-run writer rejection

The cluster log later recorded:

```text
Bad entity attribute :seon.cluster.run/generated-at ... not defined in current schema
```

Current source/schema discovery contains no declaration for that attribute.
This is a second, independent generated-run boundary failure: transaction data
named a fact the published database could not store.

### Phase 4 — web surface after settlement

The debug shell returned `200`, 2,183 bytes, in 1.160782 seconds. Its initial
AI pane was empty and marked `prospective`, while the HTML pane said:

> Loading the current HTML projection…

That shell is honest as an SSE bootstrap, but curl alone does not hold the
Datastar feed and therefore did not expose a settled debug projection.

Ordinary agent/root pages were materially worse. Their first requests produced
zero bytes before the 10-second client bound:

```text
GET /agent/drive-one-agent -> 10.008777 s, 0 bytes, timeout
GET /                      -> 10.009215 s, 0 bytes, timeout
```

A subsequent body request eventually returned the agent page. Its visible walk
contained configuration and large toolkit namespace/schema material, but also
repeated bare failures between ordinary blocks:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

This is ugly and non-diagnostic. It does not say which shape or renderer
failed, so neither an agent nor an observer can act on it.

The page also spent much of its visible budget on schema catalogues. Examples
included complete `my.background`, `my.message`, `my.note`, `my.run`, and
`my.shell` schema listings, while other namespaces collapsed to lines such as:

> 3 require declarations and 83 definitions omitted by the namespace render
> budget.

That combination is hard to use: verbose low-level schema for some namespaces,
opaque omission for others, and failure placeholders between them. The agent
never saw it in this run, but it is the current agent-facing page quality.

## Render-cost facts and observer effect

There were no `:seon.render.cost/*` facts in the opening interval
`05:39:40Z`–`05:39:47Z`, consistent with failure before context capture.

The observer's later GETs unexpectedly wrote the database. Immediately before
curling the pages, basis was `t=536870976`. After the agent, debug, and root
GET probes:

```text
basis:              t=536871061 (+85 transactions)
render-cost facts:  84
fact time range:    06:12:58Z–06:13:12Z
estimated tokens:   35,290 total
```

The cost rows grouped as follows:

| Shape | Calls | Estimated tokens total | Per-call range |
|---|---:|---:|---:|
| `:seon.schema/value` | 28 | 16,497 | 196–782 |
| `:seon.ns/ns` | 24 | 10,696 | 64–997 |
| `:seon.error/fact` | 9 | 4,689 | 521 |
| `:seon.cluster.run/run` | 6 | 1,455 | 145–340 |
| `:seon.cluster.message/message` | 6 | 760 | 109–140 |
| `:seon.problems/stale-var` | 5 | 295 | 57–60 |
| other three shapes | 6 | 898 | 69–226 |

Thus the read-only observation specified by the drive changed the basis and
created one durable cost row per selected render call. Because every
transaction report wakes rendering, this seam must prove it cannot form a
render/write/wake feedback loop.

## Other surprising cost in the preserved cluster

Three root maintenance runs closed without provider transmission because the
minimum-distance context exceeded the 32,768-token budget:

```text
40,664 estimated tokens — process census
61,326 estimated tokens — dead-root reaping
83,950 estimated tokens — process census
```

These are not attributed to the drive agent, but they are live facts in the
drive cluster and show that routine root maintenance is presently
unexecutable under the shipped provider budget.

## Required drive measurements

| Measurement | Independent result |
|---|---|
| Opening composition | No capture; zero trustworthy opening-token facts |
| Live pull | Not reached |
| Delta economics | No mid-session turn; not reached |
| Rebirth | Not reached |
| Agent behavior | No provider attempt; not observable |
| Provider spend | Zero attempts for the drive run |
| Basis-`t` comprehension | Not observable |
| #11 omitted structured `:about` | No plan items; denominator zero |

This is not an accepted Drive 1. It is a successful falsification of the drive
preconditions: the session cannot yet measure derived-context quality because
the opening receipt codec, published transaction vocabulary, observer
discovery, and read-only render-cost boundary fail first.

## Defect list

Existing issues updated with independent evidence:

- `docs/seon/issues/cluster-boot-instruments-in-flight-working-tree-vars.md`
- `docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`

New issues filed:

- `docs/seon/issues/generated-opening-refuses-before-context-capture.md`
- `docs/seon/issues/generated-run-transacts-uninstalled-generated-at.md`
- `docs/seon/issues/drive-one-starts-without-required-plan-facts.md`
- `docs/seon/issues/web-get-records-render-cost-facts.md`
- `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md`
- `docs/seon/issues/namespace-page-first-byte-exceeds-ten-seconds.md`
- `docs/seon/issues/operator-status-refuses-foreign-live-root.md`
- `docs/seon/issues/root-maintenance-context-exceeds-provider-budget.md`

Per the observer assignment, `docs/seon/issues/index.md` was not edited.

## Attempt 4 independent observation — 2026-08-14

### Boundary and method

I read the Attempt 4 section of
`docs/prds/sci-execution-runtime/research/drive-1-report-2026-08-14.md` first,
as instructed, then verified its claims against the preserved specimen rather
than treating the report as evidence. I used only JVM-mode MCP reads and HTTP
GETs against isolated root `tmp/drive-1-root`, cluster `default`, PID 53761,
prepl 51058, and web UI `http://127.0.0.1:51063`. I sent no message, evaluated
nothing through the SCI door, transacted no fact, and issued no lifecycle
command. The specimen remained running.

`mcp__seon__runtime_status` was degraded with the already-filed exact error:

```clojure
{:seon.config/missing-projection true
 :seon.error/message "Effective config requires the projection handed to this operation."}
```

JVM `eval_clj` remained usable (`(+ 1 2)` returned `3`), so all database
queries below used the advertised cluster connection explicitly.

### The actual provider request

The durable attempt body is 72,814 characters of JSON. Parsed directly, it has
one message only:

```clojure
{:messages [{:role "user", :chars 68905}]}
```

There is no provider system segment and no hidden history array. The sole user
message is byte-for-byte capture
`6c5dad44-e55a-4184-b4bb-0cf07a6b8764-context-536871046`. Its contribution
record estimated 22,620 tokens; DeepSeek reported 22,604, split into 11,136
cache-hit and 11,468 cache-miss tokens.

The capture consists of 40 blank-line-separated history entries:

| Region | Characters | Local token estimate |
|---|---:|---:|
| entries 0–18, first opening snapshot | 34,033 | 10,635 |
| entries 19–35, repeated opening snapshot | 33,285 | 10,401 |
| entries 36–39, paid-run tail | 1,583 | 494 |

This answers the 4.7× discrepancy. The 4,776-token opening proxy priced stored
source/result strings, not the agent-consumed fitted transcript. Even one real
opening snapshot was about 10,635 locally estimated tokens. Then retained
history appended essentially the entire snapshot again. No system message
filled the difference.

The first snapshot begins:

```text
my.agents.drive-one-agent-attempt-4=> (db/pull db (quote [*]) [:seon.cluster/name "default"])
Cluster default.
Configuration default; 1 shared instruction and 9 toolkit namespaces.
```

It contains large toolkit faces, including a 3,318-character `(dir my.web)`,
3,268-character `(dir my.edit)`, 3,301-character `(dir my.shell)`, and similar
entries for `my.fs`, `my.message`, `my.note`, and `my.plan`. The first
snapshot's rendered values alone occupy 32,574 characters and approximately
10,179 locally estimated tokens. This is the unfitted/unreconciled content
the stored 4,437-token result-face proxy did not represent.

### Superseded task survival and duplication seam

The stale bootstrap task is entry 18 and appears again at entry 38:

```text
From outside this cluster to drive-one-agent-attempt-4: Define a durable contracted function named largest that returns the row with the greatest :example/amount, or {} for empty input. Call it once, query its stored :seon.fn/spec, then complete with a short reply naming what you built and its contract.
```

The current trigger appears once, at entry 37:

```text
From outside this cluster to drive-one-agent-attempt-4: Author and follow one my.plan for this task. Every authored item must use the NEW :my.plan.item/about shape: a plain vector mixing quoted qualified function symbols and namespaced keywords, targeting the actual functions and schema attributes you will use. Define a durable contracted function sum-of-squares in your namespace with a complete Malli contract, define a discoverable clojure.test usage test, run it through seon.test/run, complete every plan item, and close with my.run/complete reporting the exact test result. Do not edit repository files.
```

The survival is in retained context assembly, not the provider. At
`src/seon/render/web.clj:1050-1061`, `append-history` treats a logical call at a
new basis as a new observation. `context-pass` builds a complete refreshed
history and appends it to `::ai-entries` at lines 1093–1107. The observed basis
comes from `src/seon/render/walk.clj:819-868`, with current database basis as a
fallback. The new paid-run facts therefore caused a second snapshot to be
appended rather than replacing the old logical entries.

### What DeepSeek did with that context

The model visibly followed the twice-rendered stale objective rather than the
single current objective. Its exact first source began:

```clojure
; Looking at this task, I need to:
; 1. Define a durable contracted function named `largest` that returns the row with the greatest `:example/amount`, or `{}` for empty input
; 2. Call it once
; 3. Query its stored `:seon.fn/spec`
; 4. Complete with a short reply

(defn largest
  "Return the row with the greatest :example/amount, or {} for empty input."
  {:malli/schema [:=> [:cat [:sequential [:map [:example/amount :int]]]] [:or :map {}]]}
  [rows]
  (if (empty? rows) {} (apply max-key :example/amount rows)))
```

The remaining reply attempted `(largest ...)`, then
`(db/pull db '[*] [:seon.fn/sym "largest"])`, then
`(dir 'my.agents.drive-one-agent-attempt-4)` with trailing prose. The four
receipts settled respectively as:

```text
seon.schema/projection-with-function-contract violated its contract (invalid-input): must be a parseable, EDN-readable Malli form
Unable to resolve symbol: largest
Unable to resolve symbol: db/pull
No namespace: my.agents.drive-one-agent-attempt-4 found
```

No durable function, test, plan item, or completion resulted. The context was
well-served only in narrow mechanics: it accurately showed the cluster,
configuration, REPL grammar, toolkit directory forms, both message facts, and
the current run. It failed at priority and economy: it duplicated almost the
entire history, repeated a superseded task twice, buried the current task near
the end, and supplied enough low-signal directory output to make one opening
snapshot more than twice its reported proxy.

### Render-cost condition and web faces

There were zero `:seon.render.cost` entities after a real capture and provider
attempt. The exact failed predicate introduced by `0e7c38cfc` is
`(:seon.db/connection request)` at `src/seon/render.clj:708`. The run ID is
present, but the production request constructed at
`src/seon/cluster/loop.clj:1366-1378` never carries the connection. Prompt
assembly adds the database value and distance only. The agent-only recording
path is therefore too strict for its actual caller.

Both pages returned `200`: debug was 78,161 bytes and correctly labelled its
AI pane `captured`; the ordinary agent page was 36,963 bytes. The debug HTML
showed the stale task on lines 66–67 and again on lines 136–137, with the new
task only on lines 133–134. The ordinary page repeated this ugly, complete
placeholder 15 times:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

That existing defect was added to
`docs/seon/issues/namespace-page-repeats-renderer-unavailable.md`. The GETs did
not create render-cost facts; the global count remained zero.

### Attempt 4 defect list

New issues:

- `docs/seon/issues/archive/agent-context-history-appends-complete-snapshots.md`
- `docs/seon/issues/archive/opening-cost-proxy-hides-agent-prompt-bulk.md`
- `docs/seon/issues/archive/agent-context-render-cost-requires-unprovided-connection.md`

Existing issues with new live evidence:

- `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md`
- `docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`

Per the observer assignment, `docs/seon/issues/index.md` was not edited.

### Resolution proof, 2026-08-14

Commits `98421f82f`, `aed781a3b`, `470ecf029`, and `b57076d08`
resolved and archived the three Attempt 4 fidelity defects. Fresh isolated
root `tmp/context-fidelity-proof.Jq1MLw` produced opening capture
`bootstrap:root-context-536870964` and second capture
`context-fidelity-current-run-context-536871000`.

The 60,005-character second prompt contains `(dir (quote my.web))` once,
contains the superseded bootstrap task zero times, contains
`CURRENT-TASK-LAST-MARKER` once, and ends with that marker. Its 47 durable
contribution positions sum to 18,751 tokens, exactly the whole-prompt budget
estimate. The opening capture's 16 positions sum to 10,374 tokens. The same
live acquisitions recorded 130 agent-context render-cost facts.

## Attempt 5 independent behavioral observation — 2026-08-14

### Read-only boundary

I observed isolated root `tmp/drive-1-root`, cluster `default`, PID 69568,
prepl 55155, and web UI `http://127.0.0.1:55156` through JVM-mode MCP reads and
filesystem census only. I sent no message, used no SCI door evaluation,
transacted no fact, and issued no lifecycle or collection command. The
specimen remained running.

The relevant paid run was `a887d305-c8ae-4b6e-842f-43287f7f7496`; its exact
durable capture was
`a887d305-c8ae-4b6e-842f-43287f7f7496-context-536871133`.

### Exact task and exact reply

The task at the end of the capture was:

```text
From outside this cluster to drive-one-agent-attempt-5: Author and follow one my.plan for this task. Every authored item must use the NEW :my.plan.item/about plain-vector token shape, mixing quoted qualified function symbols and namespaced keywords and targeting the actual functions and schema attributes that item will use. Define a durable contracted function sum-of-squares in your namespace that accepts a sequential collection of integers and returns the sum of their squares, with a complete Malli contract. Define a discoverable clojure.test usage test covering a non-empty input and the empty input, run it through seon.test/run, complete every plan item, and close with my.run/complete reporting the exact test result. Do not edit repository files.
```

DeepSeek returned `finish_reason=stop` after 97 completion tokens. The raw
reply, recovered from the durable `:seon.cluster.reply/no-forms` error fact,
was exactly:

```text
I'll start by understanding my plan and checking the current state. Let me look at what I need to do:

1. Author a plan with items using the NEW `:my.plan.item/about` plain-vector token shape
2. Define `sum-of-squares` function with Malli contract
3. Define and run tests through `seon.test/run`
4. Complete all plan items and report results

Let me first check my current state and any messages:
```

The reader correctly refused it with:

```text
The reply carried no Clojure forms — its whole text read as prose. Prose runs nothing and settles nothing; write the Clojure you want evaluated.
```

### Why no form appeared

This was not a clarifying question. There is no question in the reply; its
last colon announces an intended next action. It was not a material task
misread either: the four numbered lines accurately preserve plan authoring,
the new `:about` shape, the contracted `sum-of-squares`, test execution, and
completion obligations.

The medium was present and demonstrated. At character 833 of the 34,955-byte
prompt, the getting-started block says:

```text
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send "agent-id" "message")`. Prose lines are kept as `;;` comments.
```

It immediately shows a complete fenced `(defn greet ...)` form. Every other
history entry also uses the visible
`my.agents.drive-one-agent-attempt-5=> (form)` followed by printed-value
grammar.

The instruction was therefore not absent or initially hidden, but it was
remote from the decision point: `Your reply is read as forms...` begins at
character 970, while the actual task begins at character 34,253. Roughly
33,200 characters of toolkit directory output intervene. The sentence
`Prose lines are kept as ;; comments` also omits the decisive exception that
prose alone carries no reader event. The best-supported reading is a medium
execution lapse under weak recency: the model understood the work, narrated
its intended first step, and stopped before emitting that step as Clojure.

### Missing behavioral response

Durability worked: error entity 30679 retained the entire reply under
`:seon.cluster.reply/text`. Behavior did not recover. The agent has exactly
two runs—the generated opening and this task run. The task run closed at
`2026-08-14T11:28:56.922Z`; there is no correction run or later re-wake.

Current source explains the terminal boundary. Every reply-reader error other
than the separately handled unreadable case reaches `fail!` at
`src/seon/cluster/loop.clj:1337-1338`. The diagnostic is stored for observers,
but never becomes context for the model that needs to correct it.

I filed
`docs/seon/issues/no-forms-replies-close-without-correction-or-rewake.md`
without ruling between a bounded correction turn, durable prose plus open
obligations and a fact-driven re-wake, or prose-terminal settlement only when
no executable obligations remain.

### Single-session retained footprint

At the first filesystem census, the root's FileStore contained 8,554 `.ksv`
files, 11,711,643,648 allocated bytes (10.907 GiB), and 11,692,409,267 logical
bytes (10.889 GiB). The derived Lucene directory was 1.8 MiB and build
artifacts 7.9 MiB; the FileStore was effectively the whole root.

During the live observation a later census found 12,434 files and 11.172 GiB
allocated. The root was concurrently live, so that delta is not attributed to
a particular proc or to the observer; it shows only that retention continued
to grow.

The cheap current-fact payload attribution totalled 17.52 MiB:

| Attribute family | Bytes |
|---|---:|
| `seon.error` | 9,564,409 |
| `seon.fn` | 3,033,164 |
| `seon.test` | 2,040,511 |
| `seon.schema.shape` | 1,310,773 |
| `seon.ns` | 365,826 |
| `seon.schema.map-entry` | 333,134 |
| `seon.ai.attempt` | 328,295 |
| `seon.context.capture` | 291,093 |
| `seon.cluster.eval` | 76,270 |

Current payload is about 1/636 of physical logical bytes. The live Konserve
key face exposed only opaque UUID keys and `:type :edn`, so it cannot honestly
attribute retained bytes by domain family. The store-bloat issue now records
this boundary: physical attribution still needs retained-history/index versus
unreachable-object accounting, not a filename convention.

### Attempt 5 defect list

New issue:

- `docs/seon/issues/no-forms-replies-close-without-correction-or-rewake.md`

Existing issues updated with live evidence:

- `docs/seon/issues/prose-only-model-replies-are-not-durable-facts.md`
- `docs/seon/issues/store-grew-to-69-gigabytes-in-one-day-of-lanes.md`

Per the observer assignment, `docs/seon/issues/index.md` was not edited.
