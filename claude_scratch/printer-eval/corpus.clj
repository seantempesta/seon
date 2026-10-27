;; The printer evaluation's corpus (task: printer-eval, rows 36-38 of the shrink research):
;; values read off a kept store that scripted agents worked (target/eval-store: ./seon e2e calc,
;; stuck and scenario conflict, each on its own main), plus deliberately large values made from
;; the checkout's own source and docs. Writes notes/printer-eval/corpus.edn: [{:id :kind :value
;; :schema? :prose?}]. Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/corpus.clj
(require '[seon.db :as sdb] '[datahike.api :as d] '[seon.task :as task] '[seon.problems :as problems]
         '[seon.config :as config] '[seon.context :as context] '[seon.program :as program] '[seon.edn :as edn]
         '[clojure.string :as str])

(def cfg (sdb/store-cfg :lmdb "target/eval-store"))
(defn db-of [b] @(d/connect (assoc cfg :branch b)))
(def calc (db-of :eval-calc))
(def stuck (db-of :eval-stuck))
(def conflict (db-of :eval-conflict))
(def square (db-of :eval-calc/task-calc-square))
(def stuck-r2 (db-of :eval-stuck/task-calc-add-r2))
(def conflict-b (db-of :eval-conflict/task-conflict-b))

(defn records [db] (mapv first (sort-by second (d/q '[:find ?r ?id :where [?e :seon.cluster.eval/record ?r] [?e :seon.cluster.eval/id ?id]] db))))
(defn turns [db] (sort-by :seon.turn/id (d/q '[:find [(pull ?e [*]) ...] :where [?e :seon.turn/id]] db)))
(defn strip [m] (into {} (remove (fn [[k _]] (= :db/id k))) m))
(defn texts [db] (for [a (d/q '[:find [?a ...] :where [?t :seon.turn/attempt ?a]] db)
                       :let [r (context/recorded db a)] :when r] r))

(def big-src (into (sorted-map) (d/q '[:find ?id ?src :where [?e :form/id ?id] [?e :form/src ?src] [?e :form/ns ?n] [(= ?n "seon.task")]] calc)))
(def boom (try (d/pull calc '[*] [:seon.task/id "no/such"]) (catch Exception e {:class (str (type e)) :message (ex-message e) :data (ex-data e)})))

(def corpus
  (vec
   (concat
    [{:id "tasks-calc" :kind :tasks :value (task/tasks calc)}
     {:id "task-square" :kind :task :value (task/task calc "calc/square") :schema :seon.task/task}
     {:id "task-stuck-add" :kind :task :value (task/task stuck "calc/add") :schema :seon.task/task}
     {:id "attempts-calc" :kind :attempts :value (task/attempts calc)}
     {:id "attempts-stuck" :kind :attempts :value (task/attempts stuck)}
     {:id "problems-stuck" :kind :problems :value (problems/report stuck) :schema :seon.problems/report}
     {:id "problems-calc" :kind :problems :value (problems/report calc) :schema :seon.problems/report}
     {:id "config" :kind :config :value (config/from-db calc)}
     {:id "turns-square" :kind :turns :value (mapv strip (turns square))}
     {:id "turns-stuck-r2" :kind :turns :value (mapv strip (turns stuck-r2))}
     {:id "merge-conflict" :kind :merge :value (mapv strip (d/q '[:find [(pull ?e [*]) ...] :where [?e :seon.merge/key]] conflict-b))}
     {:id "will-merge-square" :kind :will-merge :value (program/divergence calc square)}
     {:id "will-merge-conflict-b" :kind :will-merge :value (program/divergence conflict conflict-b)}
     {:id "results-stuck" :kind :results :value (vec (keep :seon.attempt/results (vals (task/attempts stuck))))}
     {:id "source-seon.task" :kind :large-map :value big-src}
     {:id "pull-error" :kind :error :value boom}
     {:id "datoms-calc" :kind :large-coll :value (vec (for [dt (take 400 (d/datoms calc :eavt))] [(:e dt) (:a dt) (:v dt) (:tx dt)]))}
     {:id "doc-render" :kind :prose :value (slurp "docs/render.md") :prose? true}
     {:id "doc-agents" :kind :prose :value (slurp "AGENTS.md") :prose? true}
     {:id "ex-info-http" :kind :error
      :value {:seon.error/type :seon.model/http-error :seon.error/operation 'seon.model/call
              :seon.error/message "The provider answered HTTP 400: the request's messages exceed the model's context window."
              :seon.error/fix "Lower :seon.config.render/history-budget or split the task."
              :seon.error/data {:status 400 :body (slurp "docs/flow.md") :headers {"content-type" "application/json" "x-request-id" "req_01J9ZK3"}}}}]
    (for [[n db] [["square" square] ["stuck-r2" stuck-r2] ["conflict-b" conflict-b]]
          [i r] (map-indexed vector (records db))]
      {:id (str "eval-" n "-" i) :kind :eval-record :value r})
    (for [[n db] [["square" square] ["stuck-r2" stuck-r2] ["conflict-b" conflict-b]]
          [i r] (map-indexed vector (texts db))]
      {:id (str "context-" n "-" i) :kind :context :value (:text r) :prose? true}))))

(spit "notes/printer-eval/corpus.edn" (edn/encode corpus))
(println (count corpus) "values;" (count (edn/encode corpus)) "bytes")
(doseq [c corpus] (println (:id c) (:kind c) (count (edn/encode (:value c)))))
