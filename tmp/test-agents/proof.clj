;; Test agents, revision after review 57814415b: one map of named proofs.
;; MCP JVM, root /Users/sean/src/seon, cluster default. Loads the one-path
;; orchestrator (tmp/one-path/src/seon/batch.clj, new namespace only) and the
;; prototype (tmp/test-agents/src/seon/test/agent.clj, new namespace only).
;; Every requester is a disposable branch acquired off default's head and
;; released in finally; members' branches are released by their member agents.
;; Entrances are called ARMED: Malli -instrument wrappers over the installed
;; projection's registry. Each result is validated against its proof schema.
;; Run: (load-file "/Users/sean/src/seon/tmp/test-agents/proof.clj") then
;; (tmp.test-agents.proof/run-all), or one: (tmp.test-agents.proof/run-one :b1-exit-owned).
(load-file "/Users/sean/src/seon/tmp/one-path/src/seon/batch.clj")
(load-file "/Users/sean/src/seon/tmp/test-agents/src/seon/test/agent.clj")

(ns ^{:clj-kondo/ignore [:namespace-name-mismatch]} tmp.test-agents.proof
  (:require [clojure.core.async.flow :as flow]
            [malli.core :as m]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.flow :as sflow]
            [seon.fs]
            [seon.operator.runtime]
            [seon.schema]
            [seon.sci.kernel :as kernel]
            [seon.test :as test]
            [seon.test.agent :as ta]))

(defn- source [] (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default")))
(defn- registry-options [] {:registry (:seon.schema.projection/registry (db/carried-projection (db/db (:seon.db/connection (source)))))})

(def contracts
  {`ta/member-world [:=> [:cat [:map [:seon.db/connection :seon.db/connection]]]
                     [:map [:seon.db/connection :seon.db/connection] [:seon.db/db :seon.db/database-value]
                      [:seon.db/writer :any] [:seon.sci.eval/ctx :any] [:seon.agent/branch :keyword] [:seon.cluster/name :string]]]
   `ta/enter [:=> [:cat :map :any] :any]
   `ta/run-request! [:=> [:cat :map] :map]
   `ta/admit! [:=> [:cat :any :int :any :any :any :boolean] :boolean]
   `ta/ready! [:=> [:cat :any :any :any] :any]
   `ta/host-global [:=> [:cat :map] [:map-of :symbol [:set :symbol]]]})

(defn armed
  "The entrance `sym` wrapped by Malli against the installed registry."
  [sym]
  (m/-instrument {:schema (contracts sym)} @(resolve sym) (registry-options)))

(defn- in-roster? [branch] (contains? (registry/roster (:seon.store/store (get @seon.operator.runtime/running-instances "default"))) branch))
(defn- ms [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))
(defn- ident [k] (keyword "tmp.test-agents" (str (name k) "-" (subs (str (random-uuid)) 0 8))))
(defn- has-ident? [database k] (some? (db/q '[:find ?e . :in $ ?k :where [?e :db/ident ?k]] database k)))

(defn- with-requester
  "Call f with a disposable requester branch off default's head, released in finally."
  [f]
  (let [r (agent/acquire-context! (source) nil {:seon.agent/isolate? true})]
    (try (f r) (finally (agent/release-context! r)))))

(defn- quiet-record [_w _b] {:seon.batch/applied true :seon.batch/tx-count 0})
(defn- request [requester members & {:as more}]
  (merge {:requester requester :limit 4 :pressure (volatile! nil) :deadline-ms 5000
          :record quiet-record :record-stop (fn [& _] :stopped)
          :members (mapv (fn [[k body]] {::ta/key k ::ta/body body}) members)}
         more))
(defn- values [answer k] (mapv :seon.batch/value (get-in answer [::ta/results k ::ta/batch :seon.batch/entries])))
(defn- take-within [ch ms] (#'ta/take-within ch ms))

;;; B1 --------------------------------------------------------------------------

(defn b1-counterexample
  "Astra P10: a graph's join returns while its compute body is still alive."
  []
  (let [release (java.util.concurrent.CountDownLatch. 1) exited (java.util.concurrent.CountDownLatch. 1)
        task (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable
              (fn [] (try (.await release 2 java.util.concurrent.TimeUnit/SECONDS) (finally (.countDown exited)))))
        _ (.execute ^java.util.concurrent.Executor (:compute (seon.operator.runtime/root-executors)) task)
        g (sflow/start-graph! {:seon.flow/graph-definition {:procs {} :conns []}})
        executor ^java.util.concurrent.ExecutorService (:seon.flow/executor g)]
    (try (flow/stop (:seon.flow/graph g))
         (let [joined (sflow/join-graph! {:seon.flow/executor executor
                                          :seon.await/bound {:seon.await/config-attribute :seon.test/check-time-limit-ms
                                                             :seon.await/config-value 1000}
                                          :seon.await/diagnostic {:seon.error/layer ::proof :seon.error/operation `b1-counterexample}})]
           {:pass? (and (nil? joined) (.isTerminated executor) (pos? (.getCount exited)))
            :graph-exited? (.isTerminated executor) :body-exited? (zero? (.getCount exited))})
         (finally (.countDown release) (.get task 2 java.util.concurrent.TimeUnit/SECONDS)))))

(defn b1-exit-owned
  "The run answers unfinished at its deadline, yet the member keeps its branch
  and graph until the body's own exit; release, then join, follow that exit."
  []
  (with-requester
    (fn [r]
      (let [release (java.util.concurrent.CountDownLatch. 1) branch (atom nil) stops (atom [])
            run (ta/request! (request r {:held (fn [] (reset! branch (:seon.agent/branch test/*member*))
                                                  (.await release 5 java.util.concurrent.TimeUnit/SECONDS) :body-returned)}
                                      :deadline-ms 300 :record-stop (fn [& args] (swap! stops conj args) :stopped)))
            answer (take-within (::ta/answer run) 3000)
            held-at-answer? (in-roster? @branch)
            finished-at-answer? (not= ::ta/timeout (take-within (::ta/finished run) 50))]
        (.countDown release)
        (let [finished (take-within (::ta/finished run) 5000)
              res (get-in finished [::ta/results :held])
              closed (ta/close! run 1000)]
          {:pass? (boolean (and (= [:held] (::ta/unfinished answer)) held-at-answer? (not finished-at-answer?)
                                (= 1 (count @stops)) (= [:body-returned] (values finished :held))
                                (< (::ta/exited-at res) (::ta/released-at res) (::ta/joined-at res))
                                (nil? (::ta/join res)) (not (in-roster? @branch)) (nil? closed)))
           :unfinished (::ta/unfinished answer) :branch-held-at-answer? held-at-answer?
           :run-finished-at-answer? finished-at-answer? :stop-events (count @stops)
           :exit->release-ms (/ (- (::ta/released-at res) (::ta/exited-at res)) 1e6)
           :release->join-ms (/ (- (::ta/joined-at res) (::ta/released-at res)) 1e6)
           :branch-released-after-exit? (not (in-roster? @branch))})))))

;;; B2 and the owner's world --------------------------------------------------------

(defn b2-world-carried
  "On a bare virtual thread and on the compute executor, nothing is inherited;
  entering the member's world value re-establishes custody, member, SCI world
  and projection."
  []
  (with-requester
    (fn [r]
      (let [member (agent/acquire-context! r nil {:seon.agent/isolate? true})]
        (try
          (let [w ((armed `ta/member-world) member)
                enter (armed `ta/enter)
                observe (fn [] {:connection? (identical? (:seon.db/connection w) db/*conn*)
                                :member? (identical? member test/*member*)
                                :host-world? (identical? (:seon.sci.eval/ctx w) kernel/*host-world*)
                                :projection? (identical? (:seon.schema/projection w) @#'seon.schema/*projection*)})
                on-virtual (fn [f] (let [p (promise)] (Thread/startVirtualThread #(deliver p (f))) (deref p 2000 :timeout)))
                on-compute (fn [f] (let [t (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable f)]
                                     (.execute ^java.util.concurrent.Executor (:compute (seon.operator.runtime/root-executors)) t)
                                     (.get t 2 java.util.concurrent.TimeUnit/SECONDS)))
                plain (on-virtual observe)
                virtual (on-virtual #(enter w observe))
                compute (on-compute #(enter w observe))]
            {:pass? (and (not-any? true? (vals plain)) (every? true? (vals virtual)) (every? true? (vals compute)))
             :plain plain :virtual virtual :compute compute})
          (finally (agent/release-context! member)))))))

(defn- probe-body
  "A body proving where its world points: reads, a write and SCI evaluation."
  [marker seen other-ident]
  (fn []
    (let [branch (:seon.agent/branch test/*member*)
          read-branch (get-in (db/db) [:config :branch])
          written (db/transact! [{:db/ident marker}])
          ctx (:seon.sci.eval/ctx test/*member*)
          sci-sees (sci/eval-string* ctx (str "(def my-marker " marker ")"
                                              "(seon.db/q '[:find ?e . :where [?e :db/ident " marker "]])"))]
      (when seen (deliver (first seen) true) (deref (second seen) 3000 :timeout))
      {:branch branch :read-branch read-branch :wrote? (not (and (map? written) (:seon.error/at written)))
       :own-visible? (has-ident? (db/db) marker) :sci-own? (some? sci-sees)
       :other-visible? (when other-ident (has-ident? (db/db) other-ident))
       :sci-other? (when other-ident (some? (sci/eval-string* ctx (str "(seon.db/q '[:find ?e . :where [?e :db/ident " other-ident "]])"))))})))

(defn owner-world-own-branch
  "A test agent's database reads, writes and SCI state all point to its own
  test branch; nothing reaches the requester or the cluster."
  []
  (with-requester
    (fn [r]
      (let [marker (ident :own)
            answer ((armed `ta/run-request!) (request r {:m (probe-body marker nil nil)}))
            v (first (values answer :m))]
        {:pass? (boolean (and (= (:branch v) (:read-branch v)) (not= (:branch v) (:seon.agent/branch r))
                              (:wrote? v) (:own-visible? v) (:sci-own? v)
                              (not (has-ident? (db/db (:seon.db/connection r)) marker))
                              (not (has-ident? (db/db (:seon.db/connection (source))) marker))))
         :member-branch (:branch v) :read-branch (:read-branch v) :requester (:seon.agent/branch r)
         :write-on-own? (:own-visible? v) :sci-reads-own? (:sci-own? v)
         :on-requester? (has-ident? (db/db (:seon.db/connection r)) marker)
         :on-cluster? (has-ident? (db/db (:seon.db/connection (source))) marker)
         :errors (get-in answer [::ta/results :m ::ta/batch :seon.batch/entries 0 :seon.batch/error])}))))

(defn owner-two-clusters-never-cross
  "Two requesters (two cluster branches) each run a test agent at the same
  time, both past their writes before either looks: neither sees the other's
  datoms or SCI state; each recording lands on its own requester."
  []
  (with-requester
    (fn [r1]
      (with-requester
        (fn [r2]
          (let [a (ident :a) b (ident :b) a-wrote (promise) b-wrote (promise)
                ra (ident :rec-a) rb (ident :rec-b)
                rec (fn [conn k] (fn [_ _] (db/transact! conn [{:db/ident k}]) {:seon.batch/applied true}))
                run (armed `ta/run-request!)
                fa (future (run (request r1 {:a (probe-body a [a-wrote b-wrote] b)} :record (rec (:seon.db/connection r1) ra))))
                fb (future (run (request r2 {:b (probe-body b [b-wrote a-wrote] a)} :record (rec (:seon.db/connection r2) rb))))
                [xa xb] [(deref fa 10000 :timeout) (deref fb 10000 :timeout)]
                va (first (values xa :a)) vb (first (values xb :b))
                d1 (db/db (:seon.db/connection r1)) d2 (db/db (:seon.db/connection r2))]
            {:pass? (boolean (and (:own-visible? va) (:own-visible? vb) (false? (:other-visible? va)) (false? (:other-visible? vb))
                                  (false? (:sci-other? va)) (false? (:sci-other? vb)) (not= (:branch va) (:branch vb))
                                  (has-ident? d1 ra) (not (has-ident? d1 rb)) (has-ident? d2 rb) (not (has-ident? d2 ra))))
             :requesters [(:seon.agent/branch r1) (:seon.agent/branch r2)]
             :members [(:branch va) (:branch vb)] :crossed-datoms [(:other-visible? va) (:other-visible? vb)]
             :crossed-sci [(:sci-other? va) (:sci-other? vb)]
             :recorded {:r1 [(has-ident? d1 ra) (has-ident? d1 rb)] :r2 [(has-ident? d2 ra) (has-ident? d2 rb)]}}))))))

;;; B3 ----------------------------------------------------------------------------

(defn b3-counterexample
  "A fixed pool of two whose two parents each wait on children queued behind them."
  []
  (let [pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        parent (fn [] (let [kids (mapv (fn [_] (.submit pool ^java.util.concurrent.Callable (fn [] :child))) (range 2))]
                        (mapv #(.get ^java.util.concurrent.Future % 500 java.util.concurrent.TimeUnit/MILLISECONDS) kids)))
        parents (mapv (fn [_] (.submit pool ^java.util.concurrent.Callable parent)) (range 2))
        outcomes (mapv #(try (.get ^java.util.concurrent.Future % 2 java.util.concurrent.TimeUnit/SECONDS)
                             (catch java.util.concurrent.ExecutionException e (.getName (class (.getCause e)))))
                       parents)]
    (.shutdownNow pool)
    {:pass? (every? #{"java.util.concurrent.TimeoutException"} outcomes) :outcomes outcomes}))

(defn b3-nested-progress
  "Top-level bodies filling every slot each run a nested request and wait for
  it: nested members take no slot, so every body completes."
  []
  (with-requester
    (fn [r]
      (let [limit 2
            nested (fn [] (let [a (ta/run-request! (request test/*member* {:n1 (fn [] :child) :n2 (fn [] :child)} :limit limit))]
                            [(first (values a :n1)) (first (values a :n2)) (::ta/unfinished a) (::ta/pending a)]))
            t0 (System/nanoTime)
            answer ((armed `ta/run-request!) (request r {:p1 nested :p2 nested} :limit limit :deadline-ms 20000))
            took (ms t0)]
        {:pass? (and (= [[:child :child [] []]] (values answer :p1) (values answer :p2))
                     (empty? (::ta/unfinished answer)))
         :parents [(values answer :p1) (values answer :p2)] :ms took}))))

;;; B4 ----------------------------------------------------------------------------

(defn b4-fixture-classification
  "The invocation-graph classifier finds the fixture-installed mutation the
  direct-body classifier misses (seon.profile-test's preserving-roots)."
  []
  (let [root (str (seon.fs/source-directory) "/test")
        t0 (System/nanoTime)
        analysis (:analysis ((requiring-resolve 'clj-kondo.core/run!)
                             {:lint [root] :parallel true :cache false
                              :config {:analysis {:var-usages true :var-definitions true} :output {:format :edn}}}))
        analysed (ms t0)
        t1 (System/nanoTime)
        graph ((armed `ta/host-global) analysis)
        classified (ms t1)
        direct (ta/direct-host-global analysis)
        subject 'seon.profile-test/an-sci-installation-owns-its-own-cell
        tests (count (filter :test (:var-definitions analysis)))]
    {:pass? (and (contains? graph subject) (not (contains? direct subject))
                 (every? #(contains? graph %) (keys direct)))
     :subject-reaches (vec (sort (get graph subject))) :direct-subject? (contains? direct subject)
     :tests tests :host-global (count graph) :direct (count direct) :runnable-concurrently (- tests (count graph))
     :analysis-ms analysed :classify-ms classified}))

(defn b4-first-load-ready
  "Eight concurrent first loads of one key run the loader once; every caller
  holds the same positive ready value before its body starts."
  []
  (let [loads (atom 0) held (atom {}) ready (armed `ta/ready!)
        callers (mapv (fn [_] (future (ready held [:program :ns] #(do (Thread/sleep 50) {:ready true :load (swap! loads inc)}))))
                      (range 8))
        seen (mapv #(deref % 2000 :timeout) callers)]
    {:pass? (boolean (and (= 1 @loads) (apply = seen) (:ready (first seen)))) :loads @loads :distinct-values (count (set seen))}))

;;; B5 ----------------------------------------------------------------------------

(defn- collections []
  (reduce + (map #(.getCollectionCount ^java.lang.management.GarbageCollectorMXBean %)
                 (java.lang.management.ManagementFactory/getGarbageCollectorMXBeans))))

(defn- young-gc!
  "Provoke an ordinary young collection by allocating short-lived garbage."
  []
  (let [before (collections)]
    (loop [i 0]
      (when (and (< i 400) (= before (collections)))
        (dotimes [_ 64] (byte-array (* 1024 1024)))
        (recur (inc i))))
    (- (collections) before)))

(defn- pressure-run
  [r listen?]
  (let [state (volatile! {::kernel/resume-bytes 0 ::kernel/pool "proof"})
        unlisten (when listen? (ta/watch-resume! (ta/resume-listener ta/body-roster state)))]
    (try
      (let [run (ta/request! (request r {:m (fn [] :ran)} :pressure state :deadline-ms 3000))
            refused-first? (= ::ta/timeout (take-within (::ta/answer run) 300))
            running-while-held (count (::ta/running @ta/body-roster))
            _ (vswap! state assoc ::kernel/resume-bytes Long/MAX_VALUE)
            t0 (System/nanoTime)
            gcs (young-gc!)
            answer (take-within (::ta/answer run) 4000)]
        (ta/close! run 3000)
        {:refused-while-held? refused-first? :running-while-held running-while-held :collections gcs
         :values (values answer :m) :pending (::ta/pending answer) :cleared? (nil? @state) :ms (ms t0)})
      (finally (when unlisten (unlisten))))))

(defn b5-pressure-resume
  "A run whose only member was refused under pressure, with nothing running,
  is woken by the kernel's resume notice after an ordinary collection; without
  the notice it reaches its deadline with the member pending."
  []
  (with-requester
    (fn [r]
      (let [with (pressure-run r true) without (pressure-run r false)]
        {:pass? (boolean (and (:refused-while-held? with) (= [:ran] (:values with)) (empty? (:pending with))
                              (:refused-while-held? without) (= [:m] (:pending without))))
         :with-notice with :without-notice without}))))

(defn b5-member-allocation
  "What materializing one member world allocates (acquire, fork), per member,
  measured on this thread: an allocation, not a retained size."
  []
  (with-requester
    (fn [r]
      (let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
            tid (.getId (Thread/currentThread))
            sample (fn [] (let [a0 (.getThreadAllocatedBytes mx tid) t0 (System/nanoTime)
                                member (agent/acquire-context! r nil {:seon.agent/isolate? true})
                                a1 (.getThreadAllocatedBytes mx tid) t1 (System/nanoTime)]
                            (agent/release-context! member)
                            {:alloc-mb (/ (Math/round (/ (- a1 a0) 1e4)) 100.0) :ms (/ (Math/round (/ (- t1 t0) 1e4)) 100.0)}))
            samples (mapv (fn [_] (sample)) (range 5))]
        {:pass? (every? #(pos? (:alloc-mb %)) samples) :samples samples}))))

;;; B6 ----------------------------------------------------------------------------

(defn b6-record-stage
  "The recording is the batch's supplied settle stage: invoked once per
  member, one transaction each on the requester; a failing recording keeps
  the body's value and delivers a settle diagnostic."
  []
  (with-requester
    (fn [r]
      (let [calls (atom 0) conn (:seon.db/connection r)
            record (fn [_w b] (swap! calls inc)
                     (if (= :boom (:seon.batch/value (first (:seon.batch/entries b))))
                       (throw (ex-info "recording refused" {:proof true}))
                       (let [report (db/transact! conn [{:db/ident (ident :recorded)}])]
                         {:seon.batch/applied (some? (:db-after report)) :seon.batch/tx-count 1})))
            t-before (:max-tx @conn)
            answer ((armed `ta/run-request!) (request r {:x (fn [] 1) :y (fn [] 2) :z (fn [] :boom)} :record record))
            t-after (:max-tx @conn)
            z (get-in answer [::ta/results :z ::ta/batch])]
        {:pass? (boolean (and (= 3 @calls) (= 2 (- t-after t-before))
                              (= [1] (values answer :x)) (= [:boom] (values answer :z))
                              (some #(= :settle (:seon.batch/stage %)) (:seon.batch/diagnostics z))))
         :record-calls @calls :requester-transactions (- t-after t-before)
         :z-value (values answer :z) :z-diagnostics (mapv :seon.error/message (:seon.batch/diagnostics z))}))))

;;; -------------------------------------------------------------------------------

(defn armed-refuses
  "The entrances are armed: a world built from a handle without a connection,
  and a request that is not a map, refuse at the boundary."
  []
  (let [refusal (fn [f arg] (try (f arg) :admitted (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        world (refusal (armed `ta/member-world) {})
        run (refusal (armed `ta/run-request!) :not-a-request)]
    {:pass? (and (= :malli.core/invalid-input world) (= :malli.core/invalid-input run)) :member-world world :run-request run}))

(def proofs
  (array-map :b1-counterexample b1-counterexample :b1-exit-owned b1-exit-owned
             :b2-world-carried b2-world-carried :owner-world-own-branch owner-world-own-branch
             :owner-two-clusters-never-cross owner-two-clusters-never-cross
             :b3-counterexample b3-counterexample :b3-nested-progress b3-nested-progress
             :b4-fixture-classification b4-fixture-classification :b4-first-load-ready b4-first-load-ready
             :b5-pressure-resume b5-pressure-resume :b5-member-allocation b5-member-allocation
             :b6-record-stage b6-record-stage :armed-refuses armed-refuses))

(def proof-schema [:map [:pass? :boolean]])

(defn run-one [k]
  (let [t0 (System/nanoTime)
        result (try ((proofs k)) (catch Throwable t {:pass? false :thrown (ex-message t) :class (.getName (class t))}))]
    (assoc result :ms (ms t0) :valid? (m/validate proof-schema result))))

(defn run-all []
  (let [results (into (array-map) (map (fn [k] [k (run-one k)])) (keys proofs))]
    {:passed (count (filter (comp :pass? val) results)) :of (count results)
     :running-after (count (::ta/running @ta/body-roster)) :waiting-after (count (::ta/waiting @ta/body-roster))
     :proofs results}))
