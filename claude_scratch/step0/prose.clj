;; Per run: of the model's evals, how many carry prose, how many cite something the reference
;; parser resolves (what the screen then shows), and how many of those cited a var whose name
;; the model's later code (its next three evals) then uses: "the screen pulled a reference the
;; model then used", approximated by that name appearing in later code.
(require '[datahike.api :as d] '[seon.root :as c] '[seon.ref :as ref] '[seon.docs :as docs] '[clojure.string :as str])
(defn prose-stats [sys]
  (vec (for [[rid b] (d/q '[:find ?r ?b :where [?e :seon.run/id ?r] [?e :seon.run/branch ?b]] @(:conn sys))
             :let [db (:db @(c/branch-db sys b))
                   evs (->> (d/q '[:find [?e ...] :where [?e :seon.eval/record]] db)
                            (map #(:seon.eval/record (d/pull db [:seon.eval/record] %)))
                            (filter #(and (:call-id %) (= rid (:run %))))
                            vec)
                   ;; tx order is eval order: the records' handles count up
                   evs (vec (sort-by #(parse-long (subs (str (get-in % [:response :handle])) 8)) evs))
                   cites (vec (for [[i e] (map-indexed vector evs)
                                    :let [cs (when-not (str/blank? (:prose e)) (filter #(docs/citation-kind db %) (ref/references db (:prose e))))]
                                    :when (seq cs)]
                                [i (keep #(when (symbol? (:seon.ref/name %)) (:seon.ref/name %)) cs)]))
                   used (count (filter (fn [[i names]]
                                         (let [later (str/join "\n" (map :code (subvec evs (inc i) (min (count evs) (+ i 4)))))]
                                           (some #(or (str/includes? later (str %)) (str/includes? later (name %))) names)))
                                       cites))]]
         {:run rid :evals (count evs) :with-prose (count (remove (comp str/blank? :prose) evs))
          :citing (count cites) :cited-then-used used})))
