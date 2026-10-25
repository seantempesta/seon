(ns seon.screen
  "The agent's screen: what the user's screen shows of it, as forms the system evaluates in the
  agent's REPL just before each model request (`forms`). Each is an eval like any other,
  recorded by the screen (:seon.eval/by \"screen\"), and sent as the request's last user
  messages, replaced by the next request's (seon.turn, seon.context/request), so they
  never repeat in the conversation. The agent's page joins them as a read of root's cached
  view."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [datahike.api :as d]))

(def forms
  "The screen's forms, evaluated in order before each model request: the agent's page, as root
  shows it (`show`: its peers, status, plan and this run), then its task's live state run fresh
  (my.task/status: the turn of how many, each proof's status, first failing assertion and how to
  run it), then what its branch changes against main (my.branch/changes), then what its plan's
  work is likely to need beyond what it cites (my.task/related: the sections, functions and schema
  keys nearest it, a line each, and the forms that read one and search further): the turn's live
  state is REPL forms, run fresh each turn, and the references explicit evals refined as the
  session goes (the owner's rulings). An agent changes what it is shown by redefining this var:
  rewriting its own interface is an eval, so (my.agent/related \"its words\") in place of the last
  form follows its own query."
  ["(seon.screen/show)" "(my.task/status)" "(my.branch/changes)" "(my.task/related)"])

(defn diff
  "What changed from before to after, by path (get-in's): {:changed {path value} :unchanged [path
  ...] :removed [path ...]}, the empty ones left out. A map is compared key by key, a vector of
  the same count item by item one level down (a page's tiles; a tile is whole), anything else
  whole; each unchanged path is the highest one, so an equal value is {:unchanged [[]]}. Each
  screen eval is sent as its diff from the previous one of the same form (the owner's ruling,
  docs/render.md#the-screen-is-sent-as-diffs)."
  {:malli/schema [:=> [:cat :any :any] [:map [:changed {:optional true} [:map-of [:vector :any] :any]]
                                         [:unchanged {:optional true} [:vector [:vector :any]]]
                                         [:removed {:optional true} [:vector [:vector :any]]]]]}
  [before after]
  (let [walk (fn walk [acc path b a in-vec?]
               (cond (= b a) (update acc :unchanged conj path)
                     (and (map? b) (map? a))
                     ;; in the map's own order: one stored value iterates the same way every time
                     (let [acc (reduce-kv (fn [acc k _] (if (contains? a k) acc (update acc :removed conj (conj path k)))) acc b)]
                       (reduce-kv (fn [acc k v] (walk acc (conj path k) (get b k ::none) v false)) acc a))
                     (and (vector? b) (vector? a) (= (count b) (count a)) (not in-vec?))
                     (reduce (fn [acc i] (walk acc (conj path i) (nth b i) (nth a i) true)) acc (range (count a)))
                     :else (assoc-in acc [:changed path] a)))]
    (into {} (remove (comp empty? val)) (walk {:changed {} :unchanged [] :removed []} [] before after false))))

(defn- last-screen
  "The value this agent's screen form code answered at the newest screen eval of transaction t or
  before it, on its branch, or ::none."
  {:malli/schema [:=> [:cat :string :int] :any]}
  [code t]
  (let [db (agent/db)
        rows (d/q '[:find ?tx ?r :in $ ?k ?t :where [?a :seon.agent/key ?k] [?e :seon.eval/agent ?a ?tx] [(<= ?tx ?t)]
                    [?e :seon.eval/by "screen"] [?e :seon.eval/record ?r]]
                  db (:key @agent/state) t)]
    (if-let [[_ r] (last (sort-by first (filter #(= code (:code (second %))) rows)))]
      (get-in r [:response :value] ::none)
      ::none)))

(defn show
  "The agent's screen: its page as root's render proc holds it, without its conversation (the model
  has it as its messages) and without presentation, at main's t (seon.agent/screen): its task, its
  proofs and its branch's changes as the page's tiles, root's agents. Asked of root, never drawn
  here. With {:since t}, what changed since this agent's screen at db t (`diff` from that screen
  eval's page, whole when there was none)."
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat [:map [:since :int]]] :any]]}
  ([] (agent/screen))
  ([{:keys [since]}]
   (let [now (agent/screen) before (last-screen "(seon.screen/show)" since)]
     (if (= ::none before) now (diff before now)))))

(defn input
  "What this turn's screen forms read (my.task/status): {:task :input}, the task and the turn
  sections' input as this turn's observe measured it (seon.context/turn-input), as
  seon.turn/act! holds them; nil before any turn of this process observed its task."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (get-in @agent/state [:turn :screen]))
