(ns acme.overrides
  "Acme overrides of seon fns — the reliable late-binding pattern: `set!`
   the callee's global var slot; an EXISTING compiled caller reads that
   slot at call time, so the override flows through without forking seon
   (proven by seon.client.extra-core-test). Side effects fire because
   acme.pod requires this ns at preload.

   Two error seams, both branded calm. Core split the one error contract
   into two vars:
   - `error-response` — the canvas execution failure response: returns the
     FULL `:seon.render/html-response` map, so we
     preserve the agent-facing `:seon.render/ai` + `:seon.render/error` and
     swap only the human-facing hiccup.
   - `error-card` — the slot/entity/block error-card surfaces
     (`render`, `slot`, `render-entity-html` catches): a `(fn [:seon/error]
     → BARE hiccup)`. No agent-facing envelope to preserve, so we replace
     outright.

   Both render the same calm 'preparing this view' card — while an agent is
   mid-building a surface the human sees a calm placeholder instead of a raw
   error, the agent-facing signal stays intact on the hero."
  (:require [seon.render.canvas :as canvas]))

(defonce ^:private orig-error-response canvas/error-response)

(set! canvas/error-response
      (fn acme-error-response [req]
        (assoc (orig-error-response req)
               :seon.render/hiccup
               [:div {:class "seon-card"}
                [:div {:class "seon-card-compact p-3 text-xs text-text-300 italic"}
                 "Acme is preparing this view…"]])))

;; The slot/entity/block error-card seam — `(fn [:seon/error] → BARE
;; hiccup)`. Returns hiccup only (no `:seon.render/ai`/`:seon.render/error`
;; envelope like the hero), so there is nothing from the default to preserve:
;; we replace it outright with acme's calm branded card (mirroring the hero
;; above). One `set!`, every slot/entity/block error card on the page is Acme.
(set! canvas/error-card
      (fn acme-error-card [_err]
        [:div {:class "seon-card"}
         [:div {:class "seon-card-compact p-3 text-xs text-text-300 italic"}
          "Acme is preparing this view…"]]))
