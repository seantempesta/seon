---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [turn, schema, projection, flow, oversight, class, absence-is-not-health]
---

# An agent turn proc dies on every pass over an unresolvable schema key, and every observation still reads healthy

Measured during live trial 1 on the development cluster `default` (pid 94566,
booted 2026-09-17T05:02:01Z, adopted; HEAD `812c6ab21`), recorded in
[live-trial-1-2026-09-17.md](../../prds/steward-platform/research/live-trial-1-2026-09-17.md).

## What happens

`seon.issue/start!` created worker `e6e49c4a5fe7` for issue `9b2c2e7bd6d2`
with its opening turn `8549e474fbef` open. The turn never advanced. Over 14
minutes it made **no provider attempt, no evaluation beyond the opening, and
no state change at all**: the issue still reads `:seon.issue/status :open`
with `:seon.issue/turns-remaining 8`.

The cause is on the turn proc's own error channel, not in any log line a
reader would look at. The committed fault occurrence:

```clojure
{:seon.error/proc      "seon.agent/turn"
 :seon.error/cid       "seon.agent/episode"
 :seon.error/op        "step"
 :seon.error.occurrence/message ":malli.core/invalid-schema"
 :seon.instrument/fn   "seon.schema/projection-cache-value"
 :seon.error/frame     ["malli.core$_exception" "invokeStatic" "core.cljc" 203]
 :seon.error.occurrence/count 4
 :seon.error.occurrence/first-at "2026-09-17T16:29:12Z"
 :seon.error.occurrence/last-at  "2026-09-17T16:34:07Z"
 :seon.error.occurrence/agent <e6e49c4a5fe7>
 :seon.error.occurrence/turn  <8549e474fbef>}
```

The unresolvable key, read out of the fault's evidence blob
(`fffab081d92701a386be7e82bfaccc2d9b0c6d4360a9263e3ad378b6ee18d63b`, through
`malli.core/-lookup!` → `-into-schema` → `-fail!`) is **`:seon.test/acquisition`**.

The same fault hit `root`'s turn proc once, at 16:29:40Z
(`:seon.error.occurrence/count 1`), so this is not confined to the new worker.

## It is not a missing declaration

The key IS declared and IS committed, and the host's own arming resolves it:

```clojure
(seon.db/pull db '[:seon.schema/key :seon.schema/form] [:seon.schema/key :seon.test/acquisition])
;; => {:seon.schema/key "seon.test/acquisition"
;;     :seon.schema/form "[:map [:seon.db/db {:optional true} :seon.db/database-value] …]"}

(contains? (:seon.schema.projection/forms (seon.db/carried-projection db)) :seon.test/acquisition)
;; => true   (2894 forms carried)

(seon.sci.eval/acquired-program nil)
;; => refuses normally: "seon.sci.eval/acquired-program refused ctx at []: expected
;;    must be an SCI evaluation context, got nil. … Contract: :seon.sci.eval/ctx."
```

`:seon.test/acquisition` is the contract of `seon.sci.eval/acquired-program`
(`src/seon/sci/eval.clj:982`), declared in
`resources/seon/schemas/seon.test.edn:156` and committed this morning with
`a0c69cfd9` / `b80e7f615`. So the cluster's facts have it, the cluster's
carried projection has it, and the host compiles it — while the projection the
turn proc compiles under does **not**. This is the §2.1 split: a compile that
runs against a projection its own authority would not have chosen.

## Why nothing said so — the real defect

Every observation available to an operator reported health while the loop was
dead:

- `mcp__seon__runtime_status` reports only the cluster plumbing procs
  (`seon.agent/armer`, `seon.render.web/render`, `seon.search/index`), all
  `ping "reply"`. It shows **no agent procs at all**, so a dead turn proc is
  invisible to the one tool an operator reaches for first.
- `seon.oversight/flow-status` shows the worker `armed`, with
  `:seon.oversight/mailbox-passes` climbing (3 → 4 on a nudge) and
  `:seon.oversight/turn-passes 0`. Nothing names zero passes as wrong: it reads
  exactly like an idle agent.
- The mailbox → turn conn is `(sliding-buffer 1)`, so every wake is accepted
  and silently dropped. The mailbox counter rises forever against a proc that
  cannot consume.
- `seon.turn/next-agent-work` keeps answering
  `{:seon.turn.work/situation :generate, :seon.turn/id "8549e474fbef"}` —
  the work is derivable, nothing refuses, nothing runs.
- The issue itself reads `open`, budget intact. An owner watching the issue
  sees a worker that simply has not got to it yet.
- The cluster log's last line is `SEON CORE FAULT (dev panic):
  :malli.core/invalid-schema [signature c6a224e…]` with no agent, no proc,
  no key, sitting under an unrelated in-process test's stack trace.

This is the project's named recurring class in its purest form: **a repeating,
counted, committed proc death that every check reads as an idle agent.**

## Done when

1. A turn proc that throws in its step is not a silent condition: the agent's
   own record carries it (the turn is closed with the fault, or the issue's
   status names it), and the owner is told. A proc whose step has thrown N
   times with zero completed passes is never reported as armed-and-idle.
2. `runtime_status` answers for agent procs, not only plumbing, so "is this
   agent's loop alive" is one tool call.
3. The turn proc compiles under the projection its authority carries, so a key
   the cluster's facts and carried projection both declare cannot be
   unresolvable inside the pass. A regression asserts a turn advancing on a
   cluster whose loaded contract references a schema key published in the same
   adoption.

## Scope note

The trial could not work around this: a bounded lane may not stop, reset,
refork or adopt `default`. The orchestrator's reset scheduled for 2026-09-17
will clear the instance, but the class survives a reset — the silence is the
defect, not the stale projection.
