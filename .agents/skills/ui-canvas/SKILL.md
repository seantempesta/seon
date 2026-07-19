---
name: ui-canvas
description: "Build the focal agent-controlled canvas with my.canvas. Use when an agent should show a persistent dashboard, table, chart, status view, button, input, select, toggle, or form. Covers one static or database-derived renderer, the AI twin, agent-local state, and the existing reactive call/feed path."
---

# Agent canvas

The canvas is the focal view on an agent page. Use it for content that should
remain visible or interactive. A normal message belongs in the transcript.
Smaller renderable context views are surfaces; a card is only a visual grouping.

There is one public agent API: src/my/canvas.cljs. Do not create a route, call
the web server directly, or write an agent id into the request. The runtime
injects :seon.agent/id, and renderers receive the frozen :seon.db/db value.

## Choose static or derived content

Show static semantic hiccup:

    (my.canvas/show!
      {:my.canvas/content
       [:section
        [:h2 "Ready"]
        [:p "The import finished."]]})

Inspect :seon.db/ok? on the returned transaction envelope.

For content that changes, define a qualified renderer in the agent's current
namespace and pin its symbol. A canvas renderer accepts
:seon.render/system-input and returns :seon.render/html-response:

    (defn ^:async dashboard
      {:malli/schema
       [:=> [:cat :seon.render/system-input]
        :seon.render/html-response]}
      [{dbv :seon.db/db agent-id :seon.agent/id}]
      (let [values
            (await
              (my.canvas/state
                {:my.canvas/attributes [::count]
                 :seon.db/db dbv
                 :seon.agent/id agent-id}))
            count (get values ::count 0)]
        (my.canvas/view
          {:my.canvas/content
           [:section
            [:h2 "Counter"]
            [:p (str count)]]
           :my.canvas/ai
           (str "The counter is " count ".")})))

    (my.canvas/show!
      {:my.canvas/content
       (symbol (str (ns-name *ns*)) "dashboard")})

The :my.canvas/ai string is the model-facing twin of the visual. State what the
view means; do not repeat a large document verbatim.

A normal database transaction causes the existing Datastar feed to render the
canvas again. Derive the next view from the supplied database value. Do not
store a second presentation snapshot.

## Agent-local state

Register each domain attribute once in the namespace that owns it:

    (seon.schema/register! ::count :int)
    (seon.schema/register! ::note :string)

Read selected attributes from the current agent entity:

    (my.canvas/state
      {:my.canvas/attributes [::count ::note]})

Write qualified values to that same entity:

    (my.canvas/save!
      {:my.canvas/values {::count 1}})

Both calls are asynchronous and receive the current agent id through the normal instrumentation
boundary. In a renderer, pass its explicit :seon.db/db and :seon.agent/id as in
the example above, and `await` `state`, so the view stays tied to the render
snapshot.

save! returns the standard transaction envelope. Check :seon.db/ok? before
claiming a click or submission worked.

Use seon.db directly for state that is not naturally attached to the current
agent or for graph queries beyond a simple pull.

## Buttons

my.canvas/button returns ordinary hiccup. It invokes one of the current agent's
qualified handler functions through the existing call gate:

    (seon.schema/register! ::empty-request [:map])

    (defn ^:async increment!
      {:malli/schema
       [:=> [:cat ::empty-request] :seon.db/transact-response]}
      [_]
      (let [values (await
                     (my.canvas/state
                      {:my.canvas/attributes [::count]}))
            next-count (inc (get values ::count 0))]
        (await
          (my.canvas/save!
            {:my.canvas/values {::count next-count}}))))

    (my.canvas/button
      {:my.canvas/label "Add one"
       :my.canvas/handler 'increment!})

A button handler receives the value of :my.canvas/data directly. When data is
omitted it receives an empty map. Captured data uses fully qualified keys:

    (my.canvas/button
      {:my.canvas/label "Open"
       :my.canvas/handler 'open-item!
       :my.canvas/data {::item-id "item-42"}})

The renderer qualifies a bare handler symbol to the renderer's namespace. A
qualified handler symbol is also accepted. Buttons do not create dynamic routes.

## Forms

Fields use qualified keywords. The handler receives one map with those exact
keys:

    (seon.schema/register! ::save-note-request
      [:map [::note :string]])

    (defn ^:async save-note!
      {:malli/schema
       [:=> [:cat ::save-note-request] :seon.db/transact-response]}
      [{::keys [note]}]
      (await
        (my.canvas/save!
          {:my.canvas/values {::note note}})))

    (my.canvas/form
      {:my.canvas/handler 'save-note!
       :my.canvas/label "Save"
       :my.canvas/controls
       [(my.canvas/input
          {:my.canvas/field ::note
           :my.canvas/label "Note"
           :my.canvas/placeholder "Write a note"})]})

Available controls:

- my.canvas/input for text
- my.canvas/select for string value/label option pairs
- my.canvas/toggle for a boolean
- my.canvas/form to submit a vector of controls

Compose these values inside the renderer's :my.canvas/content. The call adapter
decodes the field signals, invokes the handler, and returns handler errors to the
UI. The handler writes facts; the ordinary transaction feed refreshes the view.

## Clear or inspect the pin

Read the explicit canvas pin:

    (my.canvas/pinned {})

Remove it and return to the derived default:

    (my.canvas/clear! {})

Defining a renderer does not deliberately change the canvas. Call show! when
the agent intends to focus it.

## Rendering rules

- Prefer semantic hiccup such as section, headings, paragraphs, lists, tables,
  details, forms, and buttons.
- Keep map keys and database attributes fully namespaced.
- Give every public renderer and handler a complete Malli function schema.
- Keep the renderer pure over its input database value.
- Persist domain facts, not computed HTML or counters that a query can derive.
- Build higher-level helpers in the agent's own namespace by composing
  my.canvas; do not add another canvas API.
- If a renderer throws or emits invalid hiccup, fix the reported error. The
  core shows a visible safe error response instead of silently dropping it.
