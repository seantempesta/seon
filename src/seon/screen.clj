(ns seon.screen
  "The agent's screen: its page, as the forms the system evaluates in the agent's REPL just before
  each model request (`forms`). Each is an eval like any other, recorded by the screen
  (:seon.eval/by \"screen\"), and sent whole the first time and as its diff from the one before
  after (`diff`; seon.turn, seon.context/request), so the page is told once and each change once."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent]))

(def forms
  "The screen's forms, evaluated in order before each model request: the agent's page as the model
  reads it (seon.repl/page: each part's hiccup as its render returned it, the task with what it
  cites, related, proofs, verdict and run; the same hiccup the page shows), whole on the session's
  first turn and as its diff by part key after (notes/one-derivation-design-rev42.md §1, §4). These
  are the platform's: an agent changes what it is shown in its record, (swap! ctx assoc
  :seon.agent/screen [...]), and seon.turn runs those instead: rewriting its own interface is an
  eval."
  ["(seon.repl/page)"])

(defn diff
  "What changed from before to after, by path (get-in's): {:changed {path value} :unchanged [path
  ...] :removed [path ...]}, the empty ones left out. A map is compared key by key, anything else
  whole (a page's part, its hiccup, is changed whole by its key); each unchanged path is the
  highest one, so an equal value is {:unchanged [[]]}. Paths
  follow each map's own order, the same on every read of one stored value (a record's). Each
  screen eval is sent as its diff from the previous one of the same form (the owner's ruling,
  docs/render.md#the-screen-is-sent-as-diffs)."
  {:malli/schema [:=> [:cat :any :any] [:map [:changed {:optional true} [:map-of [:vector :any] :any]]
                                         [:unchanged {:optional true} [:vector [:vector :any]]]
                                         [:removed {:optional true} [:vector [:vector :any]]]]]}
  [before after]
  (let [walk (fn walk [acc path b a]
               (cond (= b a) (update acc :unchanged conj path)
                     (and (map? b) (map? a))
                     ;; in the map's own order: one stored value iterates the same way every time
                     (let [acc (reduce-kv (fn [acc k _] (if (contains? a k) acc (update acc :removed conj (conj path k)))) acc b)]
                       (reduce-kv (fn [acc k v] (walk acc (conj path k) (get b k ::none) v)) acc a))
                     :else (assoc-in acc [:changed path] a)))]
    (into {} (remove (comp empty? val)) (walk {:changed {} :unchanged [] :removed []} [] before after))))

(defn input
  "What this turn's screen forms read (my.task/status): {:task :input}, the task and the turn
  sections' input as this turn's observe measured it (seon.context/turn-input), as
  seon.turn/act! holds them; nil before any turn of this process observed its task."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (get-in @agent/state [:turn :screen]))
