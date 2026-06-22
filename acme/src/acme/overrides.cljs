(ns acme.overrides
  "Acme overrides of seon fns — the reliable late-binding pattern: `set!`
   the callee's global var slot; an EXISTING compiled caller reads that
   slot at call time, so the override flows through without forking seon
   (proven by seon.client.extra-core-test). Side effects fire because
   acme.pod requires this ns at preload.

   Here: a 'calm broken-tile card' — while an agent is mid-building a tile
   (defined-but-unwired, wired-before-the-query-shape-is-right), the human
   sees a calm 'preparing this view' card instead of a raw error, while
   the agent-facing :seon.render/ai signal is preserved."
  (:require [seon.render.live-tile :as live-tile]))

(defonce ^:private orig-error-response live-tile/error-response)

(set! live-tile/error-response
      (fn acme-error-response [req]
        (assoc (orig-error-response req)
               :seon.render/hiccup
               [:div {:class "seon-tile"}
                [:div {:class "seon-tile-compact p-3 text-xs text-text-300 italic"}
                 "Acme is preparing this view…"]])))
