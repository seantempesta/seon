(ns seon.web.page
  "The root HTML page served at `GET /`. Hardcoded shell for V0.5;
   a future spec can lift this to a swappable `:seon.system/shell-renderer`
   if users want different chrome layouts.

   The shell does three things and nothing else:
     1. Loads Tailwind output CSS at `/css/output.css`
     2. Loads Datastar as an ES module at `/js/datastar.js`
     3. Sets up `#seon-shell` as the morph target with CSS Grid; opens
        the SSE stream via Datastar's `data-on-load=\"@get('/sse')\"`

   The SSE stream populates the grid with `#agent-<sid>` children;
   per-agent renders (spec-05 §15.3 default `view`) emit hiccup with
   `:id (str \"agent-\" id)` so Datastar's `datastar-patch-elements`
   patches target the right cell.

   CLJC because the same shell can also be served from the JVM seon
   server when V1+ lands (see docs/2026-05-16-cljc-migration-plan.md);
   no platform-specific code in this file."
  (:require [seon.ui.html :as html]))

(defn root-html
  "Return the serialized root HTML page. Pure of state — same output
   every call. The shell's contents (per-agent tiles) get filled in
   by SSE patches emitted from `seon.web.broadcast` (A-6) once the
   browser opens `/sse`."
  {:malli/schema [:=> [:cat] :string]}
  []
  (str
    "<!DOCTYPE html>"
    (html/->string
      [:html {:lang "en" :data-theme "phosphor"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title "Alpha"]
        [:link {:rel "stylesheet" :href "/css/output.css"}]
        [:script {:type "module" :src "/js/datastar.js"}]]
       [:body {:class "h-screen bg-base-950 text-text-50 font-mono antialiased"}
        [:noscript {:class "block p-4 bg-amber-100 text-amber-800 rounded mb-4"}
         "Seon requires JavaScript."]
        ;; Datastar init div — opens the SSE stream on element-mount
        ;; (data-init) and reopens on browser online events (data-on:online__window).
        ;; @get(url) — Datastar's GET action; the response Content-Type
        ;; text/event-stream is auto-detected as an SSE patch stream.
        [:div {:data-init "@get('/sse')"
               :data-on:online__window "@get('/sse')"}]
        [:div#seon-shell
         {:class "grid gap-2 p-2 h-full"
          :style "grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));"}
         ;; V0.5 placeholder for alice's tile — Datastar's default
         ;; :outer morph needs the target to already exist in the DOM.
         ;; Subsequent broadcast patches morph this empty div by id.
         ;; V0.6 multi-agent will inject placeholders dynamically as
         ;; agents come online.
         [:div#agent-seon
          {:class "h-full p-3 bg-base-900 rounded text-text-500 italic"}
          "loading…"]]]])))
