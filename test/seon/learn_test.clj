(ns seon.learn-test
  "A run's waste read from its branch (docs/errors.md#learn): each turn's evals as the REPL recorded them,
  charged to the producer whose answer cost a turn."
  (:require [clojure.test :refer [deftest is]] [seon.learn :as learn] [seon.test-store :as ts] [datahike.api :as d]))

(def ^:private k "learn-a1")

(defn- run-db
  "A test db holding agent k's run: turn n's evals, each [code result] with result {:value v} or {:ex error-map}."
  [turns]
  (let [db0 (ts/test-db)]
    (:db-after
     (d/with db0 (into [{:seon.agent/key k}]
                       (for [[n evals] (map-indexed (fn [i es] [(inc i) es]) turns)
                             tx (into [{:seon.model-call/id (str "c" n) :seon.model-call/agent [:seon.agent/key k] :seon.model-call/n n :seon.model-call/origin :model}]
                                      (for [[j [code r]] (map-indexed vector evals)]
                                        {:seon.eval/id (str "e" n "-" j) :seon.eval/agent [:seon.agent/key k] :seon.eval/code code
                                         :seon.eval/call [:seon.model-call/id (str "c" n)]
                                         :seon.eval/result (cond-> {:seon.result/hash (str "h" n "-" j)}
                                                             (some? (:value r)) (assoc :seon.result/value (:value r))
                                                             (:ex r) (assoc :seon.result/ex (:ex r))
                                                             (:out r) (assoc :seon.result/out {:seon.print/code (:out r)}))}))]
                         tx))))))

(def ^:private refused
  {:seon.error/type :seon.instrument/contract-error :seon.error/operation 'my.agent/source-fn :seon.error/message "my.agent/source-fn invalid-input"})

(deftest a-runs-waste-is-read-from-its-branch
  ;; a look-up that printed its answer (source, doc) answered something, though its value is nil.
  ;; real-task loop, 2026-11-12: of 50 turns about 30 went to look-ups that answered nothing and to errors, and the fold charged
  ;; 3 forms: it read the turn loop's own log (gone at a restart) and counted only source-like heads and repeated errors
  (let [db (run-db [[["(grep \"defmethod place\")" {:value []}]]
                    [["(my.agent/source-fn (get-method seon.repl/place :ns))" {:ex refused}]]
                    [["(defn f [] 1)" {:value 'app.x/f}]]
                    [["(my.agent/source-fn f)" {:ex refused}]]
                    [["(source app.x/f)" {:value nil :out "(defn f [] 1)"}]]])
        turns (learn/turns-of db k {})
        rows (learn/waste turns #{})]
    (is (= [1 2 3 4 5] (mapv :n turns)) "every turn, from the branch, in order")
    (is (= [{:turn 1 :rule :empty :producer 'grep} {:turn 2 :rule :error :producer 'my.agent/source-fn}
            {:turn 4 :rule :repeats-error :producer 'seon.ctx.status/status}
            {:turn 5 :rule :search :producer :template}]
           (mapv #(select-keys % [:turn :rule :producer]) rows))
        (pr-str rows))
    (is (= {'grep {:forms 1 :turns 1} 'my.agent/source-fn {:forms 1 :turns 1} 'seon.ctx.status/status {:forms 1 :turns 1} :template {:forms 1 :turns 1}}
           (learn/summary rows)))))
