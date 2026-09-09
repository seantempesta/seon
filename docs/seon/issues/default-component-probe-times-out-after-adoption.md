---
type: issue
status: open
severity: blocker
tags: [issue, mcp, runtime]
---

# Default component probe timed out after development adoption

Data lane, 2026-09-09: MCP status selected default PID 92059, PREPL 53086,
but health and Flow returned `unknown` with `Read timed out`. A subsequent
JVM `(+ 1 1)` returned 2 in 1 ms; the immutable plan/schema query returned
in 3 ms. No cause or Flow health is inferred. Default was not restarted.

Transact-feedback, 2026-09-09: the initial MCP `runtime_status` selected
default PID 37586, PREPL 61094, and reported health/flow `unknown` with
`Read timed out`. Descriptor-only `bin/seon status` reported that process
alive. No cause is inferred and no default lifecycle operation was performed;
the assigned scratch root supplies this lane's live verification.

Observed by record-render on 2026-09-08. MCP JVM mode on default accepted
`(+ 1 1)`, but a later component probe timed out after 15000 ms. It called
`seon.operator/connection`, transacted agent `record-render-components`,
then called `my.plan/add!`, `current`, and `steps`. The requested step id was
`record-render-components/one`. Completion of those writes is unknown;
do not repeat them as if a timeout proved absence.

The immediately preceding adoption reached JVM instrumentation and then
refused because source changed during adoption. That sequence is evidence,
not proof of the timeout's cause. Concurrent isolated test confirmation
workers were also running. No fallback transport was used.

See the [landing evidence](../../prds/context-generation/research/record-render-landing-2026-09-08.md).

Turn-cut observed the same boundary at 20:52 UTC on 2026-09-08:
`runtime_status` with no arguments selected default, pid 36758, but returned
runtime health and flow as `unknown` with `Read timed out`. The publication
log independently said `Publication did not finish within its declared bound.`
Earlier ordinary virtual turns had completed, including a contracted function
installed by one agent and called by another. Those completed observations do
not establish health after this timeout. No fallback transport was used.

Components lane, 2026-09-08: initial `runtime_status` selected default PID
91455, PREPL 65479, and returned health/flow `unknown` with `Read timed out`.
A subsequent MCP JVM `(+ 1 1)` returned 2 in 3 ms. The process is reachable;
that arithmetic result does not establish Flow health. No default lifecycle
operation was performed.

Components checkpoint `e96001a7c`: development adoption reached JVM
instrumentation and returned `:seon.instrument/registration-failed`, with
Malli reporting `:malli.core/invalid-schema` for `:seon.render/cache`.
The subsequent default debug navigation exceeded Playwright's 30-second
bound. Page-feed then landed `985a830b5`, including the cache schema
alias. No default restart or refork was attempted; final source adoption
is the next authorized convergence boundary. Scoped component gates and
scratch adoption remain independent of this failure.

Final components verification after `8b48a7c47`: default adoption completed
at `6aa09fd1-1bbd-52ab-a9ae-a4a290e2cad6`. Juniper reseeding returned in
96 ms; a following record query returned in 268 ms, and Playwright loaded
and captured the debug page successfully. The component checkpoint's
cache-schema failure is not the final observed state. This does not claim
that every earlier timeout reported by other owners has been resolved.

## Message-render follow-up, 2026-09-08

After default adoption of components commit `03d3bfb1b` reported convergence
(source `6aa0cf6a-730f-5ba3-960e-f00e731bc9f6`), the JVM call to
`seon.cluster.message/render-inbox-ai` returned the new per-message source,
but the browser retained the old single inbox form and old HTML without reply
expressions. A SCI-mode MCP call of `(seon.cluster.message/render-inbox-ai [])`
timed out at 10,000 ms. JVM-mode MCP still answered. This does not establish
whether acquisition or retained rendering is the cause; default was not
restarted or reforked. See the components landing note for the cache probe.

A raw-data probe found `:seon.render/cache` on the cluster's existing projection
state. Resetting that disposable atom to `{}` returned in 2 ms; no database
facts or process lifecycle were changed. The next default screenshot showed
eight `my.message/read` results and both root-addressed reply expressions.
Thus retained rendering hid the adopted code. The precise missing invalidation
edge is not established. Calls through `seon.render/shared-cache` timed out
both with and without projection binding, while reading and resetting the
same cache atom directly answered immediately. These accessor/SCI timeouts
remain a distinct unresolved observation, not proof the cache reset hung.
