(ns seon.problems-test
  "Session D2: every class of problem arrives as a typed fact written by its observer, and
  seon.problems/report lists them by class at a cost that follows the problems, not the
  program. Run: bin/test seon.problems-test"
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is testing]] [clojure.edn :as edn]
            [datahike.api :as d] [clojure.core.async :as a]
            [seon.contract :as contract] [seon.db :as pdb] [seon.error :as err] [seon.agent :as agent]
            [seon.repl :as repl] [seon.proofs :as proofs] [seon.root :as root] [seon.agent-proc :as ap]
            [seon.reconcile :as rec] [seon.sock :as sock]
            [seon.fx :as fx] [seon.model :as model] [clojure.data.json] [seon.problems :as problems] [seon.cost :as cost]))

(defn- refusal [op v]
  (contract/error :seon.instrument/contract-error op (str op " invalid-input: its arguments do not satisfy its contract")
                  {:seon.error/member :malli.core/invalid-input
                   :seon.error/problems [{:path [0] :in [0] :expected :int :value (pr-str v)}]}))

;; ---- the signature and the fold (ported from R4's seon.error-test) ----------------------

(deftest one-cause-one-signature
  (is (= (err/signature (refusal 'app/f nil)) (err/signature (refusal 'app/f 42))) "values never enter it")
  (is (not= (err/signature (refusal 'app/f nil)) (err/signature (refusal 'app/g nil))) "another producer, another signature")
  (is (= (err/signature {:seon.error/type :x/y :seon.error/message "slot 17 of 64"})
         (err/signature {:seon.error/type :x/y :seon.error/message "slot 3 of 64"})) "digits are normalised")
  (is (not= (err/signature {:seon.error/type :seon.agent/died :seon.error/message "died" :exit {:signal 9}})
            (err/signature {:seon.error/type :seon.agent/died :seon.error/message "died" :exit {:signal 11}}))
      "an exit status names a different cause"))

(defn- occ [e agent ms] (err/occurrence e :eval {:agent agent :task "t/a" :form "(f 1)"} ms))

(deftest a-storm-is-one-write-per-window
  (ts/test-branch! (fn [_ conn] (let [f (reduce err/fold {} (concat (for [i (range 1000)] (occ (refusal 'app/f i) (if (even? i) :a1 :a2) (+ 1000 i)))
                                     (for [i (range 10)] (occ (refusal 'app/g i) :a3 (+ 5000 i)))))
        [f tx] (err/flush-tx f @conn)
        s (err/signature (refusal 'app/f 0))]
    (is (= 2 (count tx)) "1,010 occurrences, two signatures, two entities")
    (d/transact conn tx)
    (let [r (err/record @conn s)]
      (is (= [1000 2 :eval 'app/f :a2 "t/a"]
             [(:seon.error/count r) (:seon.error/agents r) (:seon.error/class r) (:seon.error/operation r)
              (:seon.error/last-agent r) (:seon.error/last-task r)]))
      (is (= :seon.instrument/contract-error (get-in r [:seon.error/example :seon.error/type])) "the example is the error map, as data")
      (is (= [1000 1999] [(:seon.error/first-ms r) (:seon.error/last-ms r)])))
    (testing "an unchanged window writes nothing"
      (is (= [] (second (err/flush-tx f @conn)))))
    (testing "a written signature's later occurrences count in the fold, not on main; a new observer adds to the stored count"
      (let [[f tx] (err/flush-tx (reduce err/fold f (for [i (range 5)] (occ (refusal 'app/f i) :a1 3000))) @conn)]
        (is (= [] tx))
        (is (= 1005 (:count (get f (err/cause (refusal 'app/f 0))))) "the fold holds the true count")
        (is (= 1000 (:seon.error/count (err/record @conn s))) "main still holds the written one")
        (testing "the next write that has a signature to write carries the pending count"
          (let [[_ tx] (err/flush-tx (err/fold f (occ (refusal 'app/h 0) :a1 3500)) @conn)]
            (is (= #{1005 1} (set (map :seon.error/count tx))))))
        (let [[_ tx] (err/flush-tx (reduce err/fold {} (for [i (range 7)] (occ (refusal 'app/f i) :a9 4000))) @conn)]
          (d/transact conn tx)
          (is (= 1007 (:seon.error/count (err/record @conn s)))))))))))

;; ---- a repeated defect is not a write (task seon.error/a-repeated-defect-writes-nothing) ------

(deftest a-written-signature-is-not-written-again
  ;; the window's write is one entity per signature that CHANGED (a-storm-is-one-write-per-window);
  ;; a signature already on main whose only change is one more occurrence is the same fact,
  ;; counted in the observer's fold: transacting its count is what made 41,709 commits in 70 s
  (ts/test-branch! (fn [_ conn] (let [f (reduce err/fold {} (for [i (range 3)] (occ (refusal 'app/f i) :a1 (+ 1000 i))))
        [f tx] (err/flush-tx f @conn)]
    (is (= 1 (count tx)) "the first occurrence writes the signature")
    (d/transact conn {:tx-data tx})
    (let [[_ tx2] (err/flush-tx (reduce err/fold f (for [i (range 1000)] (occ (refusal 'app/f i) :a1 (+ 2000 i)))) @conn)]
      (is (= [] tx2) "1,000 more occurrences of a written signature change no fact")
      (let [f (reduce err/fold f (for [i (range 1000)] (occ (refusal 'app/f i) :a1 (+ 2000 i))))]
        (is (= [3 1003] [(get-in (problems/report @conn) [:classes :eval :rows 0 :count])
                         (get-in (problems/report @conn f) [:classes :eval :rows 0 :count])])
            "the report reads the true count from the fold")))))))

(deftest a-defect-in-the-reconciler-does-not-feed-itself
  ;; root's path in miniature: the reconciler proc's step throws on a commit (root's entity
  ;; with no boot fact, what a refused boot left), the api folds the thrown error and writes
  ;; it to main as one commit, and that commit is the reconciler's next input. A step that
  ;; throws leaves the proc's state as it was, so it fails on every commit it is handed.
  ;; Main's commits over ten rounds are the first record, and no more.
  (ts/test-branch! (fn [_ conn] (let [_ (d/transact conn {:tx-data [{:seon.root/id :root :seon.root/quiescent {:t 1 :stuck []}}]})
        t0 (:max-tx @conn)
        st (rec/reconciler-proc {:boot "boot-1"})
        round (fn [f i]
                (let [db @conn
                      e (try (rec/reconciler-proc st :main {:t (:max-tx db) :ops [] :db db :ns (profile/now-ns)}) nil
                             (catch Throwable e e))
                      f (err/fold f (err/occurrence (agent/error-map e) :platform {} (+ 5000 i)))
                      [f tx] (err/flush-tx f db)]
                  (when (seq tx) (d/transact conn {:tx-data tx}))
                  f))]
    (is (= ['seon.task/entity-value] (map #(get-in % [:example :seon.error/operation]) (vals (reduce round {} (range 10)))))
        "the step fails in the task owner's contract, each round")
    (is (<= (- (:max-tx @conn) t0) 1) "ten failing commits, one record")))))

;; ---- eval errors: the error map as data, on the branch, and counted across agents ------

(deftest an-eval-error-is-recorded-as-data
  (testing "a foreign exception is the :seon.agent/eval-error variant"
    (let [e (agent/error-map (ArithmeticException. "Divide by zero"))]
      (is (contract/valid? :seon.agent/eval-error e))
      (is (= 'seon.repl/eval-code (:seon.error/operation e)))
      (is (re-find #"ArithmeticException" (:class e)))))
  (testing "a contract refusal is its own error map: operation and problems kept"
    (let [r (refusal 'app/f "x")
          e (agent/error-map (ex-info (:seon.error/message r) r))]
      (is (= r e))))
  (testing "the reply item carries the map, and the stored eval record holds it as data"
    (let [em (agent/error-map (ArithmeticException. "Divide by zero"))
          it (first (repl/fold [{"status" ["eval-error"] :tag :err :err em :printed (:seon.error/message em) :id 0 :form "(/ 1 0)"}]))
          rec (some :seon.cluster.eval/record (repl/record-tx :a1 "r1" [it] nil))]
      (is (= em (:err it)))
      (is (= "Divide by zero" (:printed it)))
      (is (= em (:error rec)) "grouped by type and producer without parsing text")))
  (testing "the agent folds its errors until its commit; root folds them as :eval occurrences"
    (let [em (agent/error-map (ArithmeticException. "Divide by zero"))
          m (-> nil (err/note em "(/ 1 0)" 10) (err/note em "(/ 2 0)" 12))
          [s] (vals m)
          o (err/sent->occurrence s :a4 "t/x")]
      (is (= [2 10 12 "(/ 1 0)"] [(:n s) (:first-ms s) (:last-ms s) (:form s)]))
      (is (= {:count 2 :agents #{:a4} :class :eval} (select-keys (err/fold-entry nil o) [:count :agents :class]))))))

;; ---- red proofs: the failing assertions and cost verdicts, persisted ---------------------

(deftest a-red-assertion-is-persisted
  (let [verdict {:ok? false :over [{:dim :calls :n 10 :ratio 2.0}] :steeper [] :under [] :unmeasured []}
        run {:pass 1 :fail 10 :error 0
             :reports (into [{:type :pass :var "#'app.t/ok"}
                             (agent/test-report {:type :fail :message (cost/explain {:of 'app/f :input 'app/in :n "n" :work "w" :growth :linear :sizes [1 10 100]} [] verdict)
                                                 :expected '(< cost estimate) :actual {:rows [] :verdict verdict}}
                                                "#'app.t/f-cost")]
                            (for [i (range 9)] (agent/test-report {:type :fail :message "nope" :expected (list '= 3 i) :actual (list 'not (list '= 3 i))} "#'app.t/red")))}
        fs (proofs/failures run)
        cs (proofs/costs run)]
    (is (= #{'app.t/red 'app.t/f-cost} (set (keys fs))))
    (is (= 8 (count (get fs 'app.t/red))) "at most 8 assertions per test")
    (is (contract/valid? :seon.attempt/failures fs))
    (is (= "(= 3 0)" (:expected (first (get fs 'app.t/red)))) "printed by the one printer")
    (is (= {'app.t/f-cost {:rows [] :verdict verdict}} cs) "a cost proof's rows and verdict, as data")))

;; ---- root's observations: deaths, stalls, platform errors, effect errors ----------------

(defn- api [& [view]]
  (#'root/api-proc {:ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 64)
                    :view (merge {:agents {}} view) :graph (promise)
                    :system (doto (promise) (deliver {:procs {} :conns [] :parts {[:proc :api] [{:proc 'seon.root/api-proc} nil]}}))}))

(defn- step [st in m] (#'root/api-proc st in m))

(defn- writes [outs] (vec (keep #(when (= :tx (:op %)) (:tx-data %)) (:to-writer outs))))

(deftest a-platform-error-is-written-once
  (let [msg {:op :error :pid :api :message "class java.lang.String cannot be cast to java.lang.Number (17)" :data nil}
        [st o1] (step (api) :back msg)
        [w] (writes o1)
        rid (get-in (first (:to-writer o1)) [:dest])
        [st outs] (reduce (fn [[st acc] _] (let [[st o] (step st :back msg)] [st (into acc (writes o))])) [st []] (range 500))
        _ (is (= [] outs) "one write in flight at a time: the storm only folds")
        [_ o3] (step st :from-writer {:dest rid :r {:ok true :t 9}})]
    (is (= 1 (:seon.error/count (first w))))
    (is (= :platform (:seon.error/class (first w))))
    (is (= 'seon.root/api-proc (:seon.error/operation (first w))) "the failed proc is the producer")
    (is (= [] (writes o3)) "the answered write is followed by nothing: 500 repeats of a written signature are counted in the fold")))

(deftest an-agent-death-is-recorded-with-its-cause
  (let [dir (str (System/getProperty "java.io.tmpdir") "/d2-death-" (profile/now-ns))
        _ (.mkdirs (java.io.File. (str dir "-agents")))
        _ (spit (str dir "-agents/a1.err") (str (apply str (repeat 3000 "x")) "\nChez: out of memory\n"))
        p (promise)
        st (-> (api {:store {:path dir :agents-dir (str dir "-agents")} :agents {:a1 {:status :busy :pid 77 :task "t/x" :running {:form "(work!)" :since-ms 5}}}})
               (assoc-in [:agents :a1] {:branch :b1}) (assoc-in [:pending 3] {:p p :kind :eval :agent :a1}))
        ;; the api's end of an agent (its graph's update! is the api's, not this test's)
        end (fn [st m] (let [o (volatile! {}) st (#'root/end-agent st o m)] [(dissoc (#'root/flush-problems st o) :seon.root/update) @o]))
        [st o1] (end st {:op :ended :agent :a1 :rid nil :pid 77 :inflight [3]})
        ;; the caller is answered when the fork server reaps the child (its exit is the death's)
        _ (step st :from-fs {:ev :exit :pid 77 :status {:signal 9}})
        died @p]
    (testing "the caller in flight is answered with the death as the one error map, once its exit is known"
      (is (= :seon.agent/died (:seon.error/type died)))
      (is (contract/valid? :seon.agent/died died))
      (is (re-find #"out of memory" (:stderr died)) "the last lines of its stderr")
      (is (<= (count (:stderr died)) 2048) "bounded")
      (is (= {:kind :eval :form "(work!)" :since-ms 5} (:last-op died))))
    (testing "the exit status, reaped later by the fork server, completes the recorded death"
      (is (= [] (writes o1)) "not yet: the exit status is not known")
      (let [[_ o2] (step st :from-fs {:ev :exit :pid 77 :status {:signal 9}})
            [[w]] (writes o2)]
        (is (= [:agent :seon.agent/died :a1 "t/x"] [(:seon.error/class w) (:seon.error/type w) (:seon.error/last-agent w) (:seon.error/last-task w)]))
        (is (= {:signal 9} (:exit (:seon.error/example w))))))
    (testing "a stop root asked for is not a death"
      (let [st (-> (api {:agents {:a2 {:status :busy :pid 78}}}) (assoc-in [:agents :a2] {:branch :b2}))
            [st _] (end st {:op :ended :agent :a2 :rid 9 :pid 78 :inflight []})
            [_ o] (step st :from-fs {:ev :exit :pid 78 :status {:exit 0}})]
        (is (= [] (writes o)))))))

(deftest root-records-what-went-wrong-in-an-agent
  ;; the one producer of what is wrong (seon.render.ai/problems reads only what is recorded):
  ;; root records a red run or a refused merge of an agent with no task, and a refused spawn
  (let [view {:agents {:a1 {:status :idle} :a2 {:status :idle :task "t/x"}}}
        red {:pass 1 :fail 1 :error 0 :reports [{:type :pass :var "#'app.t/ok"} {:type :fail :var "#'app.t/red"}]}
        written (fn [[_ o]] (mapv (juxt :seon.error/class :seon.error/type :seon.error/message :seon.error/last-agent) (first (writes o))))]
    (testing "a red run of an agent with no task"
      (is (= [[:agent :seon.agent/failed "tests red: #'app.t/red" :a1]]
             (written (step (api view) :back {:op :status :agent :a1 :set {:tests red}})))))
    (testing "a task's agent: its attempt records its reds at its end, not root"
      (is (= [] (written (step (api view) :back {:op :status :agent :a2 :set {:tests red}})))))
    (testing "a merge the writer refused is recorded as the writer's refusal"
      (let [r (contract/error :seon.merge/behind 'seon.writer/merge-branch "main changed 2 key(s)" {:tx 1 :behind 2})]
        (is (= [[:agent :seon.merge/behind "main changed 2 key(s)" :a1]]
               (written (step (api view) :from-writer {:dest {:merged :a1 :rid 5} :r r}))))))
    (testing "a refused spawn, of any agent"
      (is (= [[:agent :seon.agent/failed "fork server went down before the fork" :a2]]
             (written (step (api view) :back {:op :status :agent :a2
                                              :set {:error (contract/error :seon.agent/failed 'seon.root/spawn! "fork server went down before the fork" {:agent :a2})}})))))))

(deftest a-stall-is-observed-by-the-agent-proc
  (let [end {:in (a/chan 4) :out (a/chan 16)}
        st (#'ap/agent-proc {:id :a1 :end end :branch :b1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4) :fx-kinds #{:fx/model}})
        [st _] (#'ap/agent-proc st :in {:op :req :rid 1 :kind :eval :msg {:op :eval :forms "(Thread/sleep 99999)"}})
        st (assoc st :last-frame-ms 1000)]
    (testing "an eval in flight, no frame for longer than the stall bound, no effect outstanding: stalled, once"
      (let [[st outs] (#'ap/agent-proc st :in {:op :stall? :now-ms 5000 :stall-ms 2000})
            s (first (filter #(= :stalled (:op %)) (:to-api outs)))]
        (is (= :seon.agent/stalled (get-in s [:error :seon.error/type])))
        (is (= 4000 (get-in s [:error :quiet-ms])))
        (is (contract/valid? :seon.agent/stalled (:error s)))
        (is (empty? (:to-api (second (#'ap/agent-proc st :in {:op :stall? :now-ms 6000 :stall-ms 2000})))) "reported once per quiet spell")))
    (testing "an effect outstanding is waiting on its owner, not stalled"
      (let [[st _] (#'ap/agent-proc st :from {:op :fx :k 1 :kind :model})
            st (assoc st :last-frame-ms 1000)]
        (is (empty? (:to-api (second (#'ap/agent-proc st :in {:op :stall? :now-ms 9000 :stall-ms 2000})))))))))

(deftest an-effect-error-is-counted-by-root
  (testing "the owner of an effect kind tells the api about each error outcome"
    (let [st (#'fx/owner-proc {:kind :model :workers 1 :work (a/chan 4) :back (a/chan 4)})
          outcome (contract/error :seon.model/rate-limited 'seon.model/complete "HTTP 429: slow down" {:status 429})
          [_ outs] (#'fx/owner-proc (assoc st :busy 1) :done {:dest {:agent :a5 :op :fx-reply :k 1} :outcome outcome})
          pr (first (filter #(= :problem (:op %)) (:out outs)))]
      (is (= [:effect :a5 :seon.model/rate-limited] [(:class pr) (:agent pr) (:seon.error/type (:error pr))]))))
  (testing "HTTP 429 is produced as :seon.model/rate-limited (transient: the :model owner keeps the request), its body whole"
    (let [e (#'model/provider-error "HTTP 429" 429 {:body "too many" :retry-after-ms 2000})]
      (is (= [:seon.model/rate-limited 429 "too many" 2000] ((juxt :seon.error/type :status :body :retry-after-ms) e))))
    (is (= :seon.model/provider-error (:seon.error/type (#'model/provider-error "HTTP 500" 500 {:body "oops"}))))))

;; ---- the read -----------------------------------------------------------------------------

(defn- seeded
  "A main db with n recorded errors, n red attempts and n dead attempts over a task each,
  plus k clean tasks with green attempts (the program the read must not cost)."
  [n k]
  (ts/test-branch! (fn [_ conn] (let [f (reduce err/fold {} (for [i (range n)] (err/occurrence {:seon.error/type :seon.agent/eval-error :seon.error/operation 'seon.repl/eval-code
                                                                  :seon.error/message (str "Unable to resolve symbol: s" (char (+ 97 (mod i 26))) i) :class "x"}
                                                                 :eval {:agent :a1 :task "t/x0"} 100)))
        [_ tx] (err/flush-tx f @conn)
        att (fn [tid aid m] (merge {:seon.attempt/id aid :seon.attempt/task [:seon.task/id tid] :seon.attempt/boot "b" :seon.attempt/agent :a1
                                    :seon.attempt/start-ms 1 :seon.attempt/end-ms 2 :seon.attempt/branch :b :seon.attempt/max-turns 2} m))
        task (fn [tid] {:seon.task/id tid :seon.task/ns 't :seon.task/name (subs tid 2) :seon.task/plan "p" :seon.task/execution :running
                        :seon.task/branch :b :seon.task/kind :work})]
    (d/transact conn tx)
    (d/transact conn (vec (concat (for [i (range k)] (task (str "t/c" i)))
                                  (for [i (range k)] (att (str "t/c" i) (str "t/c" i "#1") {:seon.attempt/status :done :seon.attempt/outcome :green}))
                                  (for [i (range n)] (assoc (task (str "t/r" i)) :seon.task/proofs #{'app.t/red}))
                                  (for [i (range n)] (att (str "t/r" i) (str "t/r" i "#1")
                                                          {:seon.attempt/status :closed :seon.attempt/outcome :cap
                                                           :seon.attempt/failures {'app.t/red [{:type :fail :message "m" :expected "1" :actual "2"}]}}))
                                  (for [i (range n)] (task (str "t/d" i)))
                                  (for [i (range n)] (att (str "t/d" i) (str "t/d" i "#1")
                                                          {:seon.attempt/status :dead :seon.attempt/reason "its agent died"
                                                           :seon.attempt/error (contract/error :seon.agent/died 'seon.root/end-agent "agent a1 died" {:agent :a1})})))))
    @conn))))

(deftest problems-lists-every-class-with-what-a-declaration-needs
  (let [db (seeded 3 5)
        s (:signature (first (get-in (problems/report db) [:classes :eval :rows])))
        [_ conn] [nil nil]
        r (problems/report db)]
    (is (contract/valid? :seon.problems/report r))
    (is (= 3 (get-in r [:classes :eval :n])))
    (is (= 3 (count (get-in r [:classes :red :rows]))))
    (is (= 3 (count (get-in r [:classes :dead :rows]))))
    (let [red (first (get-in r [:classes :red :rows]))]
      (is (= ['app.t/red] (:proofs red)) "the failing test reproduces it")
      (is (= {:from (:attempt red) :red ['app.t/red]} (select-keys (:evidence red) [:from :red])) "evidence as data")
      (is (= 't (:owner-ns red)))
      (is (:open? red)))
    (let [e (first (get-in r [:classes :eval :rows]))]
      (is (= 'seon.repl/eval-code (:producer e)))
      (is (= :error (get-in e [:evidence :kind])))
      (is (= [] (:answered-by e)))
      (is (some? s)))
    (testing "a merge: its conflicts, unmerged keys and the writer's refusal, as evidence"
      (let [behind (contract/error :seon.merge/behind 'seon.writer/merge-branch "main changed 2 key(s)" {:behind 2})
            db2 (:db-after (d/with db [{:seon.task/id "t/m" :seon.task/ns 't :seon.task/name "m" :seon.task/plan "p" :seon.task/execution :running
                                         :seon.task/branch :b :seon.task/kind :merge}
                                        {:seon.attempt/id "t/m#1" :seon.attempt/task [:seon.task/id "t/m"] :seon.attempt/boot "b" :seon.attempt/agent :a2
                                         :seon.attempt/start-ms 1 :seon.attempt/end-ms 9 :seon.attempt/branch :b :seon.attempt/max-turns 2
                                         :seon.attempt/status :behind :seon.attempt/unmerged ["[:var app/g]"]
                                         :seon.attempt/conflicts [{:seon.merge/key [:var 'app/f] :seon.merge/kind :both-modified}]
                                         :seon.attempt/error behind}]))
            [m] (get-in (problems/report db2) [:classes :merge :rows])]
        (is (= ["t/m#1" :behind 1 ["[:var app/g]"] 'seon.writer/merge-branch]
               [(:attempt m) (:status m) (:conflicts m) (:unmerged m) (:producer m)]))
        (is (= [{:key [:var 'app/f] :kind :both-modified}] (get-in m [:evidence :conflicts])))
        (is (= :seon.merge/behind (get-in m [:error :seon.error/type])))))
    (testing "a task whose evidence names a problem answers it: mining files no duplicate"
      (ts/test-branch! (fn [_ c] (let [_ (d/transact c (vec (d/datoms db :eavt)))
            db2 db
            sig (:signature (first (get-in r [:classes :eval :rows])))
            db3 (:db-after (d/with db2 [{:seon.task/id "fix/e" :seon.task/ns 'fix :seon.task/name "e" :seon.task/plan "p" :seon.task/execution :held
                                          :seon.task/branch :f :seon.task/kind :work :seon.task/evidence {:kind :error :signature sig}}]))
            r3 (problems/report db3)]
        (is (= ["fix/e"] (:answered-by (first (filter #(= sig (:signature %)) (get-in r3 [:classes :eval :rows]))))))))))))

(defn rows-input
  "The cost proof's input at n: a main db with n problems of each of three classes over a
  fixed clean program."
  [n] [(seeded n 20)])

(deftest problems-reads-only-the-problems
  (cost/proof {:of 'seon.problems/report :input 'seon.problems-test/rows-input
               :n "recorded problems of each class (per n: one signature, one red attempt, one dead attempt)"
               :work (str "one index seek per class, then per row one pull: the signature's 13 attributes; an attempt's 12 and "
                          "its task's 2 (a nested pull), and one status pull to know whether its task is done; about 500 "
                          "characters of EDN read (example, failures, error)")
               :growth :linear
               :ops {:db/pull 7 :db/pull-attr 38 :edn/read-char 500}
               :sizes [4 40 400]
               ;; measured on Jolt 00a3b321 (pulls got cheaper with J3's try; the read did not as much)
               :overrun {:task "seon.problems/report-beyond-its-pulls" :measured {:ns 218794000.0 :bytes 227801600.0}}}))

(defn clean-input
  "The flatness proof's input at k: 4 problems of each class over k clean tasks, each with a
  green attempt."
  [k] [(seeded 4 k)])

(deftest problems-cost-nothing-for-a-clean-program
  (cost/proof {:of 'seon.problems/report :input 'seon.problems-test/clean-input
               :n "clean tasks (each with a green attempt), beside 4 problems of each class"
               :work "none per clean task: the indexes the read seeks hold only problem rows; the 4 problem sets cost what they cost"
               :growth :constant
               :ops {:db/pull 28 :db/pull-attr 152 :edn/read-char 2000}
               :sizes [10 100 1000]
               :overrun {:task "seon.problems/report-beyond-its-pulls" :measured {:ns 2622000.0 :bytes 2686400.0}}}))

;; ---- the model call: thinking mode as a provider field, its cost and its empty reply as data ----

(def ^:private deepseek {:kind :openai-compat :base-url "https://api.example" :model "deepseek-flash" :api-key-env "NONE" :max-tokens 2048})
(def ^:private req {:messages [{:role "user" :content "hi"}] :task "t/a" :turn 0})

(deftest the-request-sets-thinking-only-when-the-provider-does
  (is (= {:model "deepseek-flash" :messages (:messages req) :tools [model/eval-tool] :max_tokens 2048 :temperature 0 :stream true :stream_options {:include_usage true}}
         (model/request-body deepseek req)) "thinking absent: the provider's default; max-tokens the provider's; streamed with its usage")
  (is (= {:type "disabled"} (:thinking (model/request-body (assoc deepseek :thinking :disabled) req))))
  (is (= ["high" 512] ((juxt :reasoning_effort :max_tokens) (model/request-body (assoc deepseek :reasoning-effort :high :max-tokens 512) req))))
  (is (contract/valid? :seon.model/provider (assoc deepseek :thinking :enabled :reasoning-effort :low))))

(deftest a-reply-carries-what-it-cost-and-an-eaten-reply-is-a-problem
  (testing "content, with the usage DeepSeek reports"
    (let [c (model/parse-reply (clojure.data.json/write-str
                                 {:choices [{:message {:content "(+ 1 2)" :reasoning_content "think"}}]
                                  :usage {:prompt_tokens 100 :completion_tokens 40 :prompt_cache_hit_tokens 64
                                          :completion_tokens_details {:reasoning_tokens 30}}})
                               12.5 2048)]
      (is (= {:text "(+ 1 2)" :prompt-tokens 100 :completion-tokens 40 :reasoning-tokens 30 :cache-hit-tokens 64 :ms 12.5
              :reasoning "think"} c))))
  (testing "an empty content beside reasoning: thinking spent the budget"
    (let [e (model/parse-reply (clojure.data.json/write-str
                                 {:choices [{:message {:content "" :reasoning_content "long thoughts"}}]
                                  :usage {:prompt_tokens 100 :completion_tokens 2048 :completion_tokens_details {:reasoning_tokens 2048}}})
                               9.0 2048)]
      (is (= :seon.model/reasoning-ate-reply (:seon.error/type e)))
      (is (= [2048 2048 13] ((juxt :reasoning-tokens :max-tokens :reasoning-chars) e)))
      (is (contract/valid? :seon.model/failure e))
      (testing "and root counts it as an effect problem, like any failed effect"
        (is (= :effect (:class (err/occurrence e :effect {} 1))))))))

(deftest a-reply-cut-at-max-tokens-is-marked-cut
  ;; T5's first real run: replies of exactly max-tokens whose code was cut, recorded nowhere
  (let [body (fn [finish] (clojure.data.json/write-str {:choices [{:message {:content "(defn f [x]" } :finish_reason finish}]
                                                        :usage {:prompt_tokens 10 :completion_tokens 4096}}))]
    (is (true? (:truncated? (model/parse-reply (body "length") 9.0 4096))))
    (is (nil? (:truncated? (model/parse-reply (body "stop") 9.0 4096))) "a reply that stopped on its own is not cut")))

(deftest another-tasks-unfinished-proof-is-not-a-problem
  ;; T5's real run: one attempt's run reports every program test, so 210 rows named other
  ;; tasks' proofs not done yet (allowed reds); only the task's own proofs are its problems
  (ts/test-branch! (fn [_ conn]
    (d/transact conn [{:seon.task/id "t/a" :seon.task/ns 't :seon.task/name "a" :seon.task/plan "p" :seon.task/execution :running
                       :seon.task/branch :b :seon.task/kind :work :seon.task/proofs #{'app.t/a-test}}])
    (d/transact conn [{:seon.attempt/id "t/a#1" :seon.attempt/task [:seon.task/id "t/a"] :seon.attempt/boot "b" :seon.attempt/agent :a1
                       :seon.attempt/start-ms 1 :seon.attempt/end-ms 2 :seon.attempt/branch :b :seon.attempt/max-turns 2
                       :seon.attempt/status :ended :seon.attempt/outcome :red
                       :seon.attempt/failures {'app.t/a-test [{:type :fail :expected "1" :actual "2"}]
                                               'app.t/other-test [{:type :fail :expected "3" :actual "4"}]}}])
    (is (= ['app.t/a-test] (mapv :test (get-in (problems/report @conn) [:classes :red :rows])))))))

(deftest a-lost-attempt-is-a-death
  ;; task seon.render/one-owner-per-view-path (A1): the failed attempt statuses are the
  ;; declared set (:seon.status/failed), not a literal in the reader
  (let [db (:db-after (d/with (seeded 1 5)
                              [{:seon.task/id "t/l" :seon.task/ns 't :seon.task/name "l" :seon.task/plan "p" :seon.task/execution :running
                                :seon.task/branch :l :seon.task/kind :work}
                               {:seon.attempt/id "t/l#1" :seon.attempt/task [:seon.task/id "t/l"] :seon.attempt/boot "b" :seon.attempt/agent :a9
                                :seon.attempt/start-ms 1 :seon.attempt/end-ms 9 :seon.attempt/branch :l :seon.attempt/max-turns 2
                                :seon.attempt/status :lost}]))
        rows (get-in (problems/report db) [:classes :dead :rows])]
    (is (= :lost (:status (first (filter #(= "t/l#1" (:attempt %)) rows)))))
    (is (not-any? #(= "t/l#1" (:attempt %)) (get-in (problems/report db) [:classes :error :rows])))))
