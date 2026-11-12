(ns seon.learn-test
  "A run's waste read from its branch (docs/errors.md#learn): each turn's evals as the REPL recorded them,
  charged to the producer whose answer cost a turn."
  (:require [clojure.test :refer [deftest is]] [seon.learn :as learn] [seon.test-store :as ts] [datahike.api :as d]))

(def ^:private k "learn-a1")

(defn- run-db
  "A test db holding agent k's run: turn n's evals, each [code result] with result {:value v} or {:ex error-map}, and :response, what
  the eval's answer says it wrote."
  [turns]
  (let [db0 (ts/test-db)]
    (:db-after
     (d/with db0 (into [{:seon.agent/key k}]
                       (for [[n evals] (map-indexed (fn [i es] [(inc i) es]) turns)
                             tx (into [{:seon.model-call/id (str "c" n) :seon.model-call/agent [:seon.agent/key k] :seon.model-call/n n :seon.model-call/origin :model}]
                                      (for [[j [code r]] (map-indexed vector evals)]
                                        (cond-> {:seon.eval/id (str "e" n "-" j) :seon.eval/agent [:seon.agent/key k] :seon.eval/code code
                                         :seon.eval/call [:seon.model-call/id (str "c" n)]
                                         :seon.eval/result (cond-> {:seon.result/hash (str "h" n "-" j)}
                                                             (some? (:value r)) (assoc :seon.result/value (:value r))
                                                             (:ex r) (assoc :seon.result/ex (:ex r))
                                                             (:out r) (assoc :seon.result/out {:seon.print/code (:out r)}))}
                                          ;; what the eval says it wrote (seon.repl's answer: :recorded, :edited), as wrap-record stores it
                                          (:response r) (assoc :seon.eval/response (:response r)))))]
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

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-run-folds-what-each-turn-read
  ;; docs/tasks.md#a-refine-curates-its-own-task: a refine reads the run it refines in one call. The loop task's refine (2026-11-13) spent
  ;; 24 of its 40 turns writing queries over the eval records to read its run, and never wrote the plan. Estimate: a 3-turn run-db, one
  ;; pull an eval, as turns-of
  (let [db (d/db-with (run-db [[["(source seon.repl/place)" {:value nil :out "(defn place [n] n)"}]]
                               [["(grep \"nothing here\")" {:value []}]]
                               [["(defn f [] (seon.repl/place 'x))" {:value 'app.x/f :response {:recorded ['app.x/f]}}]]])
                     [{:form/id "app.x/f" :form/refs #{'seon.repl/place}} {:var/name 'app.x/f :var/form [:form/id "app.x/f"]} {:var/name 'seon.repl/place}])
        r (learn/run db k)]
    (is (= [{:n 1 :reads #{'seon.repl/place}} {:n 2 :reads #{}} {:n 3 :reads #{'seon.repl/place}}] (:turns r)) "each turn with what it read")
    (is (= #{'app.x/f 'seon.repl/place} (:final r)) "the vars it wrote and what they use: the change site")
    (is (= [{:turn 1 :rule :missing :producer :template} {:turn 2 :rule :empty :producer 'grep}] (mapv #(select-keys % [:turn :rule :producer]) (:waste r)))
        "and what was lost: turn 1 read the change site nothing had shown it (:missing), turn 2 a look-up that answered nothing")
    (is (= {:template {:forms 1 :turns 1} 'grep {:forms 1 :turns 1}} (:summary r)))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-runs-change-site-is-what-its-evals-wrote
  ;; docs/tasks.md#a-refine-curates-its-own-task: the change site is read from what the run's evals wrote, the vars their answers
  ;; recorded or edited and what those vars' forms use, never from form text. The loop task's run defined its helpers as
  ;; "(in-ns 'seon.repl)\n(defn- read-spans ..)": a text read kept only forms starting "(def", so the change site was #{} and its 19
  ;; exploring turns read as :search. Estimate: a 3-turn run-db and two var facts, one seek a written var
  (let [db0 (run-db [[["(source app.y/h)" {:value nil :out "(defn h [] 1)"}]]
                     [["(in-ns 'app.x)\n(defn- g [] (app.y/h))" {:value 'app.x/g :response {:recorded ['app.x/g]}}]]
                     [["(seon.repl/edit! 'app.x/f \"(defn f [] 2)\")" {:value :ok :response {:edited {:var 'app.x/f :lines []}}}]]])
        db (d/db-with db0 [{:form/id "app.x/g" :form/refs #{'app.y/h}} {:var/name 'app.x/g :var/form [:form/id "app.x/g"]} {:var/name 'app.y/h}
                           {:var/name 'app.x/f}])
        r (learn/run db k)]
    (is (= #{'app.x/g 'app.x/f 'app.y/h} (:final r)) "what its evals wrote, an in-ns'd defn- and an edit, and what they use")
    (is (= [{:turn 1 :rule :missing :producer :template}] (mapv #(select-keys % [:turn :rule :producer]) (:waste r)))
        "so a turn reading the change site before nothing showed it is :missing, not :search")))
