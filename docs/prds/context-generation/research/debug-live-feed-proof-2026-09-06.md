---
type: research
status: active
tags: [render, web, testing]
---

# Database-to-browser delivery proof — 2026-09-06

Status: passed after the render argument fix. This is evidence for the first inspection slice, not a
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
is loud.

Initial result: selection chose `my.plan/render-item-html`, but invocation
refused `:my.plan.item/agent {:db/id 31810}` under the declared reference
contract. Source investigation confirmed selection checked a normalized value
while invocation supplied the original pull. Commit `f609acb71` fixes the
existing argument preparation using the installed database schema. Its focused
regression passed 76 assertions, including scalar vector preservation.

## Integrated result

After hot-reloading `seon.render.value` and `seon.render`, and reapplying
instrumentation with the scratch cluster's projection, the browser reached its
explicit ready state. The mutation above then committed at basis 536870954.
The open browser changed from basis 536870953 to 536870954, displayed
`After live update`, and changed its graph from two nodes/one edge to three
nodes/two edges. The same DOM container and Cytoscape instance survived, zoom
remained 0.8 and pan remained `{x:75,y:85}`. Exactly one main-frame navigation
occurred (initial load), and there were no JavaScript errors. The process
exited successfully. The local screenshot is
`tmp/debug-live-feed-2026-09-06.png`.

This proves an actual transaction through the existing live feed into a browser
on an isolated cluster. It does not prove the producing-form display.
The web/render JVM Vars were hot-reloaded; this is not a claim that an existing
cluster automatically adopted a new indexed program publication.

## SCI definition-only update

The same browser script accepts optional before/after text arguments. With
`After live update` and `Live renderer update`, it reached ready state before
this MCP SCI evaluation in namespace `my.plan`, cluster `lab-browser-0906`:

```clojure
(defn render-item-html
  "Render a plan item for the live-update proof."
  {:malli/schema [:=> [:cat :my.plan.item/item] :seon.render/hiccup]}
  [item]
  [:article [:h3 (:my.plan.item/title item)] [:p "Live renderer update"]])
```

The evaluation returned successfully in 34 ms (host envelope timing). The
already open browser then displayed the marker with exactly one navigation
(initial load), no JavaScript errors, the same container and Cytoscape instance,
and unchanged zoom/pan. Nodes/edges remained 3/2. Database basis stayed
536870954 and the commit ID stayed unchanged: this was a runtime-definition
update, not a database transaction.

Before editing, the exact original `:seon.fn/source` was read by function
identity from the scratch database. It was restored with the same SCI tool
immediately after the successful browser proof; restoration returned
successfully in 26 ms. No source file or default-cluster definition was changed.
