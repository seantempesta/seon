(ns seon.handlers.eval
  "Renderers for `:seon.eval` entities — what the LLM sees of its own
   eval history, and what the human sees in the inspector's HTML pane.

   Stamped on every `:seon.eval` entity by `seon.eval/record-eval!` via:
     :seon.render/ai   'seon.handlers.eval/render-ai
     :seon.render/html 'seon.handlers.eval/render-html

   The inspector's `seon.render/assemble-ai-context` walks every entity
   carrying `:seon.render/ai` and calls the symbol via
   `seon.eval/lookup-value`. Both panes derive from the SAME entity
   set — there's no separate 'what the LLM sees' vs 'what the human
   sees' store. Identical query, two render shapes."
  (:require
    [clojure.string :as str]))

(def ^:private result-truncate 400)

(defn- truncate
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) " …")
      s)))

(defn render-ai
  "One-line-plus eval row for the LLM ctx. Shape:
     [eval-id]  <source>
     ;; :ok   <result>      (or :error <err>)"
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [eid (:seon.eval/id entity)
        src (or (:seon.eval/source entity) "")
        ok? (boolean (:seon.eval/ok? entity))
        res (:seon.eval/result-edn entity)
        err (:seon.eval/error entity)
        dur (:seon.eval/duration-ms entity)
        body (cond
               ok? (truncate (or res "nil") result-truncate)
               (string? err) (truncate err result-truncate)
               :else "<no result>")]
    {:seon.render/text
     (str "[eval " eid (when dur (str " " dur "ms")) "] " (str/trim src)
          "\n;; " (if ok? ":ok " ":error ") body)}))

(defn render-html
  "Hiccup card for the inspector's HTML pane — source + result/error,
   amber for ok, red for error."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [eid (:seon.eval/id entity)
        src (or (:seon.eval/source entity) "")
        ok? (boolean (:seon.eval/ok? entity))
        res (:seon.eval/result-edn entity)
        err (:seon.eval/error entity)
        dur (:seon.eval/duration-ms entity)
        body (cond
               ok? (truncate (or res "nil") result-truncate)
               (string? err) (truncate err result-truncate)
               :else "<no result>")
        body-class (if ok? "text-amber-300" "text-error")]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2"}
       [:span {:class "text-xs font-mono font-semibold text-amber-500"}
        (str "eval " eid)]
       (when dur
         [:span {:class "text-xs text-text-500"} (str dur "ms")])
       [:span {:class (str "text-xs font-mono " body-class)}
        (if ok? ":ok" ":error")]]
      [:pre {:class "text-xs text-text-100 font-mono whitespace-pre-wrap mt-0.5"}
       (str/trim src)]
      [:pre {:class (str "text-xs font-mono whitespace-pre-wrap mt-0.5 " body-class)}
       body]]}))
