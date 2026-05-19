(ns seon.web.sse
  "Datastar SSE wire format + fan-out to open connections.

   Per Datastar v1.0.0-RC.7 the wire format is two standard SSE event
   types (`datastar-patch-elements`, `datastar-patch-signals`); V0.5
   only emits elements. The morph target is determined by the element's
   own `id` attribute when no `selector` data-line is sent — that's
   what the agent tile's `<div id=\"agent-<id>\">` is for.

   Fan-out reads `seon.web.serve/!sse-connections` for the live set of
   open responses. Writes are best-effort: a dead connection logs and
   stays in the registry until its 'close' event fires + removes it."
  (:require
    [clojure.string :as str]
    [seon.web.serve :as serve]))

;; ============================================================
;; Wire format. Multi-line HTML must repeat the `data: elements `
;; prefix on each line — the SSE spec collapses the lines but datastar
;; needs each one prefixed.
;; ============================================================

(defn patch-elements
  "Format a `datastar-patch-elements` SSE event payload for `html`.
   The element MUST carry an `id` attribute; datastar morphs by id
   when no explicit selector is sent. Returns the wire-ready string
   (newline-terminated event block)."
  {:malli/schema [:=> [:cat :string] :string]}
  [html]
  (str "event: datastar-patch-elements\n"
       "data: elements " (str/replace html "\n" "\ndata: elements ")
       "\n\n"))

;; ============================================================
;; Fan-out
;; ============================================================

(defn emit-patch!
  "Write a `datastar-patch-elements` event to every open SSE response.
   Best-effort — per-connection writes are wrapped in try/catch so one
   broken socket can't block the others. Returns the number of
   connections written."
  {:malli/schema [:=> [:cat :string] :int]}
  [html]
  (let [payload (patch-elements html)
        conns   (serve/open-sse-connections)]
    (reduce (fn [n {:keys [res]}]
              (try
                (.write res payload)
                (inc n)
                (catch :default e
                  (js/console.error "[seon.web.sse] write failed:" e)
                  n)))
            0
            conns)))
