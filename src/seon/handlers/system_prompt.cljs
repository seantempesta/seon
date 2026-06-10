(ns seon.handlers.system-prompt
  "Renderers + schemas for the substrate-seeded sticky entities:
   `:seon.system-prompt` and `:seon.conventions`. Both are transacted
   ONCE at agent boot (see `seon.client/seed-substrate!`) with
   `:seon.sticky/position :prefix` so the chronological renderer pins
   them to the front of the agent's context regardless of tx-time
   ordering.

   These entities are the load-bearing stable prefix of the LLM
   context — they sit in the prompt cache forever and orient the
   agent on every turn."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

;; ============================================================
;; :seon.system-prompt
;; ============================================================

(schema/register! :seon.system-prompt/id      [:string {:seon.db/identity true}])
(schema/register! :seon.system-prompt/content :string)

(schema/register! :seon.system-prompt
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.system-prompt/render-ai
         :seon.render/html 'seon.handlers.system-prompt/render-html}
   [:seon.system-prompt/id      :seon.system-prompt/id]
   [:seon.system-prompt/content :seon.system-prompt/content]])

;; ============================================================
;; :seon.conventions
;; ============================================================

(schema/register! :seon.conventions/id      [:string {:seon.db/identity true}])
(schema/register! :seon.conventions/content :string)

(schema/register! :seon.conventions
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.system-prompt/render-conventions-ai
         :seon.render/html 'seon.handlers.system-prompt/render-conventions-html}
   [:seon.conventions/id      :seon.conventions/id]
   [:seon.conventions/content :seon.conventions/content]])

;; ============================================================
;; Renderers — system-prompt
;;
;; Per repl-session-context-template §2.1, sticky preamble entities
;; render as a `;` comment block. The agent reads the prose; the
;; parser ignores it.
;; ============================================================

(defn- comment-block
  "Wrap `s` as a `;`-prefixed comment block, one line per source line."
  [s]
  (->> (str/split-lines (or s ""))
       (map #(str ";; " %))
       (str/join "\n")))

(defn render-ai
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  {:seon.render/text (comment-block (:seon.system-prompt/content entity))})

(defn render-html
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  {:seon.render/hiccup
   [:div {:class "py-1"}
    [:div {:class "text-xs font-mono font-semibold text-amber-500"}
     "system-prompt"]
    [:pre {:class "text-xs text-text-200 font-mono whitespace-pre-wrap"}
     (str (:seon.system-prompt/content entity))]]})

;; ============================================================
;; Renderers — conventions
;; ============================================================

(defn render-conventions-ai
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  {:seon.render/text (comment-block (:seon.conventions/content entity))})

(defn render-conventions-html
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  {:seon.render/hiccup
   [:div {:class "py-1"}
    [:div {:class "text-xs font-mono font-semibold text-amber-500"}
     "conventions"]
    [:pre {:class "text-xs text-text-200 font-mono whitespace-pre-wrap"}
     (str (:seon.conventions/content entity))]]})
