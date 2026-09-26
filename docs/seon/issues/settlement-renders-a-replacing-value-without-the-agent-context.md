---
type: issue
status: open
severity: defect
created: 2026-09-26
tags: [issue, turn, render, print-once]
---

# Settlement renders a replacing value without the agent's context

`seon.turn/final-shown` (`src/seon/turn.clj:3455-3467`) calls `seon.sci.eval/shown-result` with the
database, connection, agent id, cluster name and caps, and no `:seon.sci.eval/ctx`,
`:seon.sci.eval/time-limit-ms` or `:seon.config/on-core-error`. The value renderer selects a declared
pair only with a ctx (`seon.render.value/value-node*`, `src/seon/render/value.clj:474`), so a settlement
re-render falls to the floor while the evaluation path (`evaluate-sources`) shows the error's declared
pair. The same value gets two texts.

With the ctx but no time limit, `seon.sci.kernel/invoke` refuses its request and the floor prints a
`:seon.render.value/projection-failed` diagnostic in place of the value, with no failure raised.

## Evidence (2026-09-26, default pid 76020, isolated branch)

The disposition refusal of `"(my.agent/done)\n(+ 1 1)"`: preview `:seon.eval/renderer seon.error/render-ai`,
text is the message; `final-shown` renderer nil, text is the whole error map (302 chars). `shown-result`
with ctx + `:seon.sci.eval/time-limit-ms 30000` + `:seon.config/on-core-error` answers the preview's text
and renderer. Red: `seon.turn-test/a-replaced-result-is-shown-by-the-one-print-owner` (turn_test.clj:2286).

## Proposed fix (src/seon/turn.clj, held by the pipeline lane)

```clojure
(sci.eval/shown-result value {:seon.db/db database :seon.db/connection (:seon.db/connection cluster)
                              :seon.agent/id agent-id :seon.boot/cluster-name (:seon.cluster/name cluster)
                              :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                              :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                              :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)
                              :seon.config/on-core-error (:seon.config/on-core-error cluster)}
                       nil)
```

`settle!` holds the cluster handle; if the agent's own fork is the right ctx, pass that one instead.
The silent skip without a ctx, and the diagnostic printed in place of a refused render, belong to the value
renderer's owner.
