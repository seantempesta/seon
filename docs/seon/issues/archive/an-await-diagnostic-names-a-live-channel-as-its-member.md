---
type: issue
status: resolved
severity: friction
tags: [issue, render, runtime, flow, ugly-output, class/p1]
---

# An await diagnostic names a live channel as the member that never arrived

## Evidence — 2026-09-07

Read from the live development page, `http://127.0.0.1:7766/feed/juniper`
(cluster `juniper-context`), in the run history unit:

```text
It did not run: {:seon.cluster.agent/id "juniper", :seon.cluster.run/id
"db159431-4b68-4037-b5ee-3361cd80524b", :seon.render/context-channel
#object[clojure.core.async.impl.channels.ManyToManyChannel 0x27342c75
"clojure.core.async.impl.channels.ManyToManyChannel@27342c75"]} never arrived
for :seon.render/context-acquisition within the declared
:seon.config.eval/time-limit-ms bound of 30000 ms.
```

The rendered value splices a core.async channel — a process-local object with
an identity hash — into the agent's own history and into the web page.

## Cause, at one line

`seon.render/acquire-context!` hands `seon.await/await!` a diagnostic whose
`:seon.error/diagnostic-member` is a MAP OF ITS OWN INPUTS rather than the
name of the thing that never arrived (`src/seon/render.clj:1218-1231`):

```clojure
:seon.error/diagnostic-member
{:seon.cluster.agent/id agent-id
 :seon.cluster.run/id run-id
 :seon.render/context-channel context-channel}
```

`seon.await/diagnostic` prints that member into the message
(`src/seon/await.clj:46`), `error/diagnostic` also carries it in
`:seon.error/data`, and the run loop stores the message as
`:seon.cluster.run/error` (`src/seon/cluster/loop.clj:723`). From there both
history projections read it verbatim
(`src/seon/render/transcript.clj:1313,1401`;
`src/seon/cluster/run.clj:2360`).

The renderers are innocent: they print the flat diagnostic they are given, and
filtering `#object[` at a renderer would hide the defect rather than end it.
The evidence the diagnostic actually wants is already in the request's
`:seon.error/diagnostic-evidence`, which carries the agent and run ids and no
channel.

## The fix

`:seon.error/diagnostic-member :seon.render/context-reply` — the NAME of the
awaited event, which is what every other member field in the codebase is. The
channel is the transport, never the member, and no live object belongs in a
value that a durable fact and two projections will carry.

One regression kills the class: an await diagnostic minted by
`acquire-context!` for a closed or expired reply prints no `#object[`, and
names the run and the bound it exceeded.

## Not fixed here

Found by the `print-and-admission` lane of 2026-09-07, whose owned paths were
`src/seon/print.cljc`, `src/seon/sci/admit.clj`, `src/seon/sci/eval.clj`, the
two "It did not run" render sites and the run seam. `src/seon/render.clj` is
held by the `evaluation-merge` lane, so the one-line change is reported rather
than made.

## Resolution

2026-09-07: `acquire-context!` names `:seon.render/context-reply` as the member; the channel never enters the diagnostic. Regression `seon.cluster.prompt-test/a-context-acquisition-diagnostic-names-no-live-channel`.
