---
type: research
status: complete
date: 2026-08-14
tags: [research, agent, context, runtime, live-drive]
---

# Drive 1 independent observation — 2026-08-14

## Scope and independence

I read `docs/prds/sci-execution-runtime/plan/live-drive-spec-2026-08-13.md`
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
