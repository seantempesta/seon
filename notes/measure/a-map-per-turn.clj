;; Per model turn of the recorded a-map run #2, today's request (main's history cut, re-rendered from
;; the run's records): tokens of system, story, conversation and screen (seon.context/sent-split's
;; parts), whether the proofs' results changed from the turn before, and whether a message was taken.
(require '[datahike.api :as d] '[seon.db :as pdb] '[seon.context :as context] '[seon.config :as cfg] '[clojure.string :as str])
(def scfg (pdb/store-cfg :lmdb "/private/tmp/claude-501/-Users-sean-src-seon-next/89e4bd39-d696-48b6-9c09-34666b1c7927/scratchpad/live/store-paid-pt"))
(def c0 (d/connect scfg))
(def b (first (filter #(str/includes? (str %) "a-map") (d/branches c0))))
(def db @(d/connect (assoc scfg :branch b)))
(def ts (sort (d/q '[:find ?n ?e ?tx :in $ ?r :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/origin :model] [?e :seon.turn/n ?n ?tx]] db "seon.print/a-map-shows-its-first-entry#2")))
(def basis (fn [e] (:seon.turn/basis (d/pull db [:seon.turn/basis] e))))
(def agent-e (:db/id (d/entity db [:seon.agent/key (:agent (basis (second (first ts))))])))
(def all-records (context/records db agent-e))
(def head-parts (vec (take 2 (context/parts-of db (:seon.turn/sent (d/pull db [:seon.turn/sent] (second (first ts))))))))
(defn parts-at [tx]
  (let [rs (filterv (fn [[t]] (< t tx)) all-records)
        cache (#'context/printed-run (mapv (fn [[t v k]] [(#'context/ref-of v) v k t]) rs) nil)
        parts (mapv #(get-in cache [:parts %]) (:ids cache))
        [picks dropped] (#'context/kept parts (cfg/value :seon.config.render/history-budget))
        whole-ids (into #{} (keep (fn [[{:keys [ref]} pick]] (when (= :whole pick) (second ref)))) (map vector (drop dropped parts) picks))
        sent (mapv (fn [{:keys [ref collapsed] :as p} pick] [ref (if (= :line pick) collapsed (second (#'context/as-sent p whole-ids)))]) (drop dropped parts) picks)]
    (into (cond-> head-parts (pos? dropped) (conj [[:history dropped] [{:role "user" :content (pr-str {:history {:left-out dropped}})}]]))
          sent)))
(def rows
  (vec (for [[[n e tx] prev] (map vector ts (cons nil ts))
             :let [bs (basis e) pb (some-> prev second basis)
                   screen (set (map str (:screen bs)))
                   split (context/sent-split (mapv (fn [[ref ms]] [ref (vec ms)]) (parts-at tx)) screen)]]
         (assoc split :turn n
                :results-changed (not= (select-keys (:observed bs) [:results :reds]) (some-> pb :observed (select-keys [:results :reds])))
                :message (boolean (seq (:message-ids bs)))))))
