# Database-to-browser delivery proof — 2026-09-06

Status: in progress. This is evidence for the first inspection slice, not a
second roadmap. Root owns scratch cluster `lab-browser-0906`, started with
`bin/seon start lab-browser-0906`; its advertised URL is
`http://127.0.0.1:7833`. The shared `default` database is not modified.

Seed through MCP JVM evaluation with explicit cluster custody:

```clojure
(let [connection (seon.operator/connection "lab-browser-0906")
      result (seon.db/transact!
              connection
              [{:my.plan.item/id "lab-browser-0906-item"
                :my.plan.item/title "Before live update"
                :my.plan.item/agent [:seon.cluster.agent/id "root"]}])]
  (if (:seon.error/kind result)
    result
    (seon.db/pull (seon.db/db connection)
                  [:db/id :my.plan.item/id :my.plan.item/title]
                  [:my.plan.item/id "lab-browser-0906-item"])))
```

Observed item eid: 32011. Run `debug_live_feed_probe_2026_09_06.cjs` with
`http://127.0.0.1:7833/ns/seon.flow/debug?subject=32011` and an output screenshot
path. Wait for its explicit `readyForTransaction` output before this mutation:

```clojure
(let [connection (seon.operator/connection "lab-browser-0906")
      result (seon.db/transact!
              connection
              [{:db/id "dependency"
                :my.plan.item/id "lab-browser-0906-dependency"
                :my.plan.item/title "Newly linked dependency"
                :my.plan.item/agent [:seon.cluster.agent/id "root"]}
               {:my.plan.item/id "lab-browser-0906-item"
                :my.plan.item/title "After live update"
                :my.plan.item/needs #{"dependency"}}])]
  (if (:seon.error/kind result)
    result
    (seon.db/basis-t (seon.db/db connection))))
```

The browser must receive the new title and two reference edges without
navigation, preserve its existing Cytoscape instance, container, zoom and pan,
and show the updated database identity. Its deadline is 60 seconds and failure
is loud. No mutation has been performed yet: the initial render exposed a
prerequisite defect.

Initial result: selection chose `my.plan/render-item-html`, but invocation
refused `:my.plan.item/agent {:db/id 31810}` under the declared reference
contract. Source investigation confirmed selection checks a normalized value
while invocation supplies the original pull. The prerequisite is being fixed
at the existing render argument preparation, with schema-based preservation
of refs, cardinality-many values, and scalar vector values.
