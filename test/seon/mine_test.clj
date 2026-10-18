(ns seon.mine-test
  "The miner: one situation per agent step of a kept attempt, and its wasted next forms charged to their causes."
  (:require [clojure.test :refer [deftest is]]
            [seon.competency-grade-test :as grade]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.mine :as mine]
            [seon.cluster.registry :as registry]
            [seon.task :as task]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(defn- evaluation
  "One stored model form as `seon.mine/steps` reads it: id, source, the call it ran in and the namespace it started in,
  with `more` (error, printed value and its facts)."
  {:malli/schema [:=> [:cat :string :int :symbol :string :map] :map]}
  [id call start-ns source more]
  (merge {:seon.cluster.eval/id id :seon.cluster.eval/source source :seon.cluster.eval/run {:db/id call}
          :seon.cluster.eval/ns {:seon.ns/name start-ns}}
         more))

(def ^:private attempt
  {:seon.task/assignment "a00000000001" :seon.task.attempt/end :capped
   :seon.task/attempt-of {:seon.task/sym 'app.core/f-works :seon.task/references ['app.core/f]}})

(def ^:private calls
  [{:db/id 1 :seon.turn/id "c00000000001"} {:db/id 2 :seon.turn/id "c00000000002"} {:db/id 3 :seon.turn/id "c00000000003"}])

(deftest a-repeated-in-ns-is-charged-to-the-namespace-the-block-shows
  (let [steps (mine/steps :chain-x attempt false (subvec calls 0 2)
                          [(evaluation "e1" 1 'user "(in-ns 'app.core)" {})
                           (evaluation "e2" 2 'app.core "(in-ns 'app.core)" {})])]
    (is (= [[{:seon.mine/rule :redundant-in-ns :seon.cluster.eval/id "e2"
              :seon.mine/cause {:seon.mine/kind :context :seon.mine/producer 'seon.context/current-state}}]
            []]
           (mapv (fn [step] (mapv #(select-keys % [:seon.mine/rule :seon.cluster.eval/id :seon.mine/cause])
                                  (:seon.mine/waste step)))
                 steps))
        "the second in-ns, made in the namespace it names, is the first step's waste, charged to the block's namespace")))

(deftest each-waste-rule-charges-the-producer-of-what-was-shown
  (let [cut "[0 1 …98 (drop 2 result/ee3)]"
        steps (mine/steps :chain-x attempt false calls
                          [(evaluation "e1" 1 'user "(in-ns 'app.core)" {})
                           (evaluation "e2" 1 'app.core "(my.program/source 'app.core/f)"
                                       {:seon.eval/renderer 'my.program/render-source-ai :seon.eval/shown "(defn f [] 1)"})
                           (evaluation "e3" 1 'app.core "(vec (range 100))" {:seon.eval/elisions 1 :seon.eval/shown cut})
                           (evaluation "e4" 1 'app.core "(require '[x :as y])"
                                       {:seon.cluster.eval/error "Not admitted: cyclic." :seon.eval/shown "nil"
                                        :seon.cluster.eval/diagnostics
                                        "[{:seon.error/operation seon.turn.batch/form-facts :seon.error/message \"Cyclic.\"}]"})
                           (evaluation "e5" 2 'app.core "(in-ns 'app.core)" {})
                           (evaluation "e6" 2 'app.core "(drop 2 result/ee3)" {})
                           (evaluation "e7" 2 'app.core "(my.program/doc 'app.core/f)" {})
                           (evaluation "e8" 2 'app.core "(require '[x :as y])" {:seon.cluster.eval/error "Not admitted: cyclic."})
                           (evaluation "e9" 3 'app.core "(defn f [] 2)" {})])
        cause (fn [kind producer] {:seon.mine/kind kind :seon.mine/producer producer})]
    (is (= [[:redundant-in-ns "e5" nil (cause :context 'seon.context/current-state)]
            [:follows-cut "e6" "e3" (cause :printer 'seon.render.value/show)]
            [:re-read "e7" "e2" (cause :render 'my.program/render-source-ai)]
            [:repeats-error "e8" "e4" (cause :error 'seon.turn.batch/form-facts)]]
           (mapv (juxt :seon.mine/rule :seon.cluster.eval/id :seon.mine/shown-by :seon.mine/cause)
                 (:seon.mine/waste (first steps))))
        "each next form of the first step is charged under its rule to the producer of the evaluation it repeats or digs into")
    (is (= [] (:seon.mine/waste (second steps))) "a new definition is no waste")
    (is (= [{:seon.mine/marker "…98" :seon.mine/form '(drop 2 result/ee3) :seon.mine/handle 'result/ee3 :seon.mine/path []}]
           (:seon.mine/cuts (nth (:seon.mine/shown (first steps)) 2)))
        "the cut is read back from the printed value with the handle and path its continuation reads")
    (is (= [{:seon.mine/cause (cause :context 'seon.context/current-state) :seon.mine/wasted-forms 1
             :seon.mine/wasted-turns 1 :seon.mine/rules {:redundant-in-ns 1}}]
           (filterv #(= :context (get-in % [:seon.mine/cause :seon.mine/kind])) (mine/rank steps)))
        "rank sums each cause's charged forms and the steps they came from")))

(defn- scripted
  "The fixture model's reply to `request`, by how many replies it already made: read a source and a value the
  printer cuts; enter the namespace again and follow the cut's continuation form; enter it again and define the
  proof green."
  {:malli/schema [:=> [:cat :map] :string]}
  [request]
  (let [contents (mapcat #(let [content (get % "content")] (if (string? content) [content] (keep (fn [part] (get part "text")) content)))
                         (get request "messages"))
        made (count (filter #(= "assistant" (get % "role")) (get request "messages")))]
    (case made
      0 "(in-ns 'bench.fixture-proofs)\n(my.program/source 'bench.fixture-proofs/proves-it)\n(vec (range 30000))"
      1 (str "(in-ns 'bench.fixture-proofs)\n"
             (some #(when (string? %) (re-find #"\(drop \d+ result/e[0-9a-f]+\)" %)) contents))
      "(in-ns 'bench.fixture-proofs)\n(clojure.test/deftest proves-it (clojure.test/is (= 1 1)))")))

(deftest ^{:seon.test/long "One fixture-model chain run of three model calls (a chain fork, its prove pass, three calls and the proof step over db<->flow passes), then the miner over its kept branch."
           :seon.test/long-ms 30000}
  situations-name-each-steps-render-cuts-and-next-form
  (support/with-database
   (fn [connection]
     (grade/with-root
      connection
      (fn [handle]
        (let [[run] (grade/launched handle connection 1 scripted)
              branch (:seon.chain/branch run)]
          (try
            (let [[first-step second-step last-step :as steps] (mine/situations (db/db connection) branch)
                  [_ source-read cut] (:seon.mine/shown first-step)
                  handle-of-cut (admit/result-handle (:seon.cluster.eval/id cut))]
              (is (= 3 (count steps)) "one situation per model call")
              (is (= [:done :done :done] (mapv :seon.task.attempt/end steps)) "each step carries how its attempt ended")
              (is (= 'my.program/render-source-ai (:seon.eval/renderer source-read)) "the source read names its selected render")
              (is (= [['drop handle-of-cut []]]
                     (mapv (juxt (comp first :seon.mine/form) :seon.mine/handle :seon.mine/path) (:seon.mine/cuts cut)))
                  "the cut value names the printer's cut: its continuation form reads the rest of its handle")
              (is (= ["(in-ns 'bench.fixture-proofs)" (str (:seon.mine/form (first (:seon.mine/cuts cut))))]
                     (:seon.mine/next first-step))
                  "the first step's next forms are what the agent wrote at the second call")
              (is (= [[:redundant-in-ns nil] [:follows-cut (:seon.cluster.eval/id cut)]]
                     (mapv (juxt :seon.mine/rule :seon.mine/shown-by) (:seon.mine/waste first-step)))
                  "following the cut and the repeated in-ns are the first step's waste")
              (is (= [:redundant-in-ns] (mapv :seon.mine/rule (:seon.mine/waste second-step)))
                  "the third call's in-ns is the second step's waste")
              (is (= [] (:seon.mine/next last-step)) "the last step has no next form"))
            (finally
              (grade/release-chain! (:seon.agent/routing handle) (agent/handle-store handle) branch)))))))))

(deftest a-doc-of-a-name-the-agent-guessed-charges-no-docstring
  ;; live run 8's `:chain-ea90b1a9dc77`: the agent read the doc of `tokens-of` before defining it
  (let [[step] (mine/steps :chain-x attempt false (subvec calls 0 1)
                           [(evaluation "e1" 1 'seon.error "(clojure.repl/doc tokens-of)" {})
                            (evaluation "e2" 1 'seon.error "(tokens-of '[a/b])"
                                        {:seon.cluster.eval/error "Execution error (ExceptionInfo) at (REPL:16:43).\nUnable to resolve symbol: tokens-of"})
                            (evaluation "e3" 1 'seon.error "(clojure.repl/doc seon.error/nearest-names)" {})])]
    (is (= [[{:seon.mine/kind :printer :seon.mine/producer 'seon.render.value/show}]
            [{:seon.mine/kind :printer :seon.mine/producer 'seon.render.value/show}
             {:seon.mine/kind :error :seon.mine/producer :unresolved}]
            [{:seon.mine/kind :printer :seon.mine/producer 'seon.render.value/show}
             {:seon.mine/kind :docstring :seon.mine/producer 'seon.error/nearest-names}]]
           (mapv :seon.mine/causes (:seon.mine/shown step)))
        "a bare name names no docstring the miner can charge; a qualified one does, and the guessed call's cause is its error")))

(defn- graded
  "A grade fact as main holds it, with `turns-to-green` when done, ended at `at` under commit `sha`."
  {:malli/schema [:=> [:cat :string [:maybe :int] :int :string :int [:set :map]] :map]}
  [assignment turns-to-green turns sha at waste]
  (cond-> {:seon.grade/assignment assignment :seon.grade/branch :chain-x :seon.grade/end (if turns-to-green :done :capped)
           :seon.grade/at (java.util.Date. (long at)) :seon.grade/git-sha sha :seon.grade/turns turns
           :seon.grade/wasted-turns 0 :seon.grade/prompt-tokens 0 :seon.grade/cached-tokens 0
           :seon.grade/completion-tokens 0 :seon.grade/rules #{[:re-read 2]} :seon.grade/waste waste}
    turns-to-green (assoc :seon.grade/turns-to-green turns-to-green)))

(deftest the-report-gives-the-median-and-p90-of-turns-to-green-by-commit
  (let [a (apply str (repeat 40 "a"))
        b (apply str (repeat 40 "b"))
        printer #{{:seon.grade.waste/kind :printer :seon.grade.waste/producer 'seon.render.value/show
                   :seon.grade.waste/forms 3 :seon.grade.waste/turns 2}}
        report (mine/report [(graded "a00000000001" 17 17 a 1000 printer)
                             (graded "a00000000002" 35 35 a 2000 #{})
                             (graded "a00000000003" nil 40 b 500 printer)
                             (graded "a00000000004" 9 9 b 3000 #{})])]
    (is (= {:seon.grade/attempts 4 :seon.grade/green 3
            :seon.grade/quantiles {:seon.grade/median 17 :seon.grade/p90 35}}
           (select-keys report [:seon.grade/attempts :seon.grade/green :seon.grade/quantiles]))
        "the median and p90 of turns to green over the attempts that ended done")
    (is (= [[b 2 1 {:seon.grade/median 9 :seon.grade/p90 9}] [a 2 2 {:seon.grade/median 17 :seon.grade/p90 35}]]
           (mapv (juxt :seon.grade/commit :seon.grade/attempts :seon.grade/green :seon.grade/quantiles)
                 (:seon.grade/windows report)))
        "one window per commit, in the order each commit's first attempt ended")
    (is (= [{:seon.grade/name 'seon.render.value/show :seon.grade.waste/forms 6}] (:seon.grade/top-producers report))
        "the producers' wasted forms sum over the attempts")))

(deftest ^{:seon.test/long "One fixture-model chain run of one model call over db<->flow passes, then two more passes."
           :seon.test/long-ms 20000}
  an-ended-attempt-is-graded-once-on-main
  (support/with-database
   (fn [connection]
     (grade/with-root
      connection
      (fn [handle]
        (let [[run] (grade/launched handle connection 1)
              branch (:seon.chain/branch run)
              assignment (:seon.competency/assignment run)
              pass! #(agent/db<->flow {:seon.turn.loop/cluster handle :seon.agent/routing (:seon.agent/routing handle)}
                                      :seon.flow/plan :seon.agent/test-wake)
              grade-of #(db/q '[:find [(pull ?g [:seon.grade/turns :seon.grade/turns-to-green :seon.grade/end :seon.grade/branch]) ...]
                                :in $ ?k :where [?g :seon.grade/assignment ?k]]
                              (db/db connection) assignment)
              grade-datoms #(count (db/q '[:find ?g ?a ?v :in $ ?k :where [?g :seon.grade/assignment ?k] [?g ?a ?v]]
                                         (db/db connection) assignment))]
          (try
            (pass!)
            (is (= [{:seon.grade/turns 1 :seon.grade/turns-to-green 1 :seon.grade/end :done :seon.grade/branch branch}]
                   (grade-of))
                "the ended attempt has exactly one grade on main, with its turns to green")
            (let [before (grade-datoms)]
              (pass!)
              (is (= before (grade-datoms)) "a second pass writes nothing")
              (is (= [] (mine/ungraded (db/db connection) (registry/branches (agent/handle-store handle))
                                       (task/chain-attempts (db/db connection) (agent/handle-store handle))))
                  "nothing is left to grade"))
            (finally
              (grade/release-chain! (:seon.agent/routing handle) (agent/handle-store handle) branch)))))))))
