---
type: issue
status: open
severity: friction
tags: [issue, dev, render, wave/mcp]
---

# MCP JVM result projection fails for small string results

## Probe — 2026-09-16

Default JVM 7595, MCP jvm mode. After an in-process test invocation, both
small read-only result inspections returned this envelope instead of the
string value:

```clojure
{:seon.error/kind :seon.dev.mcp/projection-failed
 :seon.error/message "The MCP value projection failed."
 :seon.error/data {:seon.error/diagnostic-offending "java.lang.String"}}
```

Exact second read:

```clojure
(pr-str (select-keys reach-last-result
          [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count
           :seon.test/run :seon.error/kind :seon.error/message]))
```

The tool still reports cluster-state alive. Concurrent publication and
render-owner edits exist, but their causal role is unverified. This is a
projection failure, not evidence that the evaluation failed. The saved
result will be read through MCP stdout after recording this boundary;
no alternate prepl sender or process restart is used.

Owner: the existing development-MCP value projection. Acceptance: a small
ordinary string result is returned through the normal value envelope, and
a projection refusal retains its cause rather than only the input's class.

Later normal small returns succeeded, including the 02:23Z cleanup form's
printed confirmation and nil return. A separate ambient-snapshot evaluation
hit its declared 20,000 ms bound. Neither observation establishes a cause
for the earlier projection failure; this issue remains open.

## Referenced-schema arming observation — 2026-09-16

Default PID 53378: the renderer-reference test returned its complete result
(11 passes, zero failures/errors, run 48986). After starting the web-debug
turn-details test through `seon.test`'s loader in a future, repeated
`(if (realized? user/arming-baseline) @user/arming-baseline :pending)`
observations timed out at 5,000 and 10,000 ms. Runtime health separately
returned unknown with `Read timed out`. A subsequent literal `1` returned
1 in 1 ms, while the result observation again timed out at 5,000 ms.
This establishes unavailable test-result delivery, not fixture poisoning,
failed arming, or a cause in another lane. No runtime function was changed.

The result was subsequently delivered in full through MCP stdout with
`(do (prn @user/arming-baseline) :printed)`: run 48991, three passes,
zero failures, one pulled-ref contract error. A small select-keys result
also returned in 1 ms. The distinction is result delivery versus test
completion; it is not evidence of a poisoned fixture base.

## Write-validation-class observation — 2026-09-16

Two read-only inspections on default pid 7595 returned MCP timeout at
30,000 ms: one write-admission/schema inspection, then
`(dissoc *1 :seon.test/printed :seon.test/failing-assertions)`.
Smaller select-keys results subsequently returned in 3–7 ms of JVM time.
Large failure-message projection also delayed delivery well beyond the
reported test execution time. No cause is attributed to adoption from this
evidence, and no restart or alternate REPL transport was used.

## Pulled-reference observation — 2026-09-16

Default pid 41413 answered runtime health, but one read-only JVM evaluation
requesting the carried projection's `:seon.db/ref` form, a pulled evaluation
origin, and the bridge's ref type timed out at 20,000 ms. HTTP independently
reproduced the origin contract refusal. The assignment permits only one
evaluation, so no retry or alternate prepl transport was used. This records
unavailable probe evidence without attributing its cause.

## Scratch fork observation — 2026-09-22

At HEAD `c96db3e94`, cluster `s` in `tmp/one-jvm-redesign-root`, PID
56462, a JVM `seon.db/pull` without the cluster's supplied projection
returned a validation refusal whose MCP rendering then failed:
`seon.render.value/artifact-value returned undeclared error schemas
#{:seon.schema/validation-refusal}`. The outer diagnostic retained that
message and the offending class `clojure.lang.PersistentHashMap`.
Repeating the read under the instance's `:seon.sci.eval/projection-state`
returned the cluster name in 7 ms. This establishes a refused diagnostic
render, not a failed cluster read with the correct supplied projection.

## Export-bound hot reload observation — 2026-09-22

On PID 15000, rebuilding the shipped default constant and reloading/arming
`seon.cluster.export` returned an MCP projection refusal:
`seon.cluster/mcp-effective refused return value at []: expected nil, got a map`.
Raw PREPL separately returned export bound 600000 and showed the config map
valid under the live database projection. Before recovery completed, the
orchestrator replaced default with PID 21908; this lane did not restart it.
That replacement initially reported missing cluster projection state. Exact
forms and scope are in the [export landing](../../prds/agent-platform/landing/lane-base-export-timeout-2026-09-22.md).
No cause in the unrelated in-flight source edits is asserted.
