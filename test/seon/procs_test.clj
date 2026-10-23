(ns seon.procs-test
  "Every proc's step-fn, called as a plain function (no graph, no Flow), checked against
  its `:malli/schema` (seon.step/step-schema) and against what each arity must do.
  Run: bin/test seon.procs-test (proto/flow f2-final test/proto/procs_test.clj)"
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.test :refer [deftest is testing run-tests]]
            [malli.core :as m] [seon.contract]
            [clojure.core.async :as a] [jolt.async.remote :as remote] [konserve.jolt.fasl :as codec]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.step :as step] [seon.db :as pdb] [seon.sock :as sock]
            [seon.writer :as writer] [seon.reconcile :as reconcile] [seon.fork-server :as server]
            [seon.agent :as agent] [seon.agent-proc :as ap] [seon.fx] [seon.root :as root] [seon.repl :as repl] [seon.render.proc] [seon.web]
            [taoensso.trove :as trove]))

(trove/set-log-fn! nil)

(def ^:private describe-ok? (m/validator (seon.contract/schema :seon.step/describe)))
(def ^:private step-out-ok? (m/validator (seon.contract/schema [:tuple :map :seon.step/outputs])))

(defn- arity-0 [v] (let [d (v)] (is (describe-ok? d) (str v " describe")) d))
(defn- step [v st in msg] (let [r (v st in msg)] (is (step-out-ok? r) (str v " transform " in)) r))

(deftest every-step-fn-carries-the-contract
  (doseq [v [#'writer/writer-proc #'reconcile/reconciler-proc #'server/fork-server-proc #'ap/agent-proc #'root/api-proc
             #'server/server-step #'repl/agent-step]]
    (is (= :seon.step/step-fn (:malli/schema (meta v))) (str v))
    (arity-0 v)))

(deftest writer
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        t0 (:max-tx @conn)]
    (testing "a main transaction: committed once, its report to the reconciler, its commit to the fork server, its answer by dest"
      (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})]
        (is (= (inc t0) (:max-tx @conn)))
        (is (= [:tx] (map :op (:to-fork-server outs))))
        (is (= [(inc t0)] (map :t (:main outs))))
        (is (= {:rid 1} (:dest (first (:to-api outs)))))
        (is (true? (:ok (:r (first (:to-api outs))))))
        (testing "group commit: three queued transactions, one step, one commit"
          (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "c"}] :dest {:rid 3}})
          (a/>!! in {:op :tx :branch :db :tx-data [{:no/such 1}] :dest {:rid 4}})
          (let [[st2 outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b"}] :dest {:rid 2}})
                answers (into {} (keep (fn [x] (when-let [r (get-in x [:dest :rid])] [r (:r x)]))) (:to-api outs))]
            (is (= 1 (- (:commits st2) (:commits st))))
            (is (every? :ok (map answers [2 3])))
            (is (seon.contract/error? (answers 4)) "a refused transaction answers its error, the others commit")))))
    (testing "a closed in-port: drained"
      (let [[_ outs] (step #'writer/writer-proc st :in nil)]
        (is (= :drained (:op (first (:to-api outs)))))))
    (testing "the stop transition releases the branch connections"
      (is (map? (#'writer/writer-proc st ::flow/stop))))))))

(deftest the-writers-transactions-name-their-runtime
  ;; task seon.db/each-commit-names-its-runtime: main's transactions through the writer's group
  ;; commit carry the runtime that made them, and the fork server's replica applies it too
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})
        fp (jolt.host/runtime-fingerprint)]
    (is (= fp (:seon.tx/runtime (d/entity @conn (:max-tx @conn)))) "main's transaction names it")
    (is (= fp (get-in (first (:to-fork-server outs)) [:tx-meta :seon.tx/runtime])) "and so does what the replica applies")))))

(deftest a-writers-merge-names-its-runtime
  ;; task seon.db/each-commit-names-its-runtime: a branch merged into main through the writer
  ;; (:merge, d/merge-db) is a transaction like any other: it names the runtime that made it
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        b (pdb/on-main (:branch cfg) :a1)
        _ (d/branch! conn (:branch cfg) b)
        bc (d/connect (assoc cfg :branch b))
        [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f2] [?v :var/form ?f] [?f :form/ns ?n]] @bc)
        _ (d/transact bc [{:db/id "f2'" :form/id "app.m0/f2-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                           :form/src "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 200))"}
                          [:db/add v :var/form "f2'"]])
        _ (d/release bc)
        t0 (:max-tx @conn)
        [_ outs] (step #'writer/writer-proc st :in {:op :merge :branch b :dest {:rid 1}})]
    (is (:merged? (:r (first (filter #(= {:rid 1} (:dest %)) (:to-api outs))))) "the branch merges")
    (is (< t0 (:max-tx @conn)))
    (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @conn (:max-tx @conn)))) "and its merge names the runtime")))))

(deftest a-fork-at-another-commit-is-recorded-misaligned
  ;; task seon.agent/an-agents-base-is-its-merge-base: a new agent whose branch is not at the
  ;; commit :spawn named (or whose image is not at its t) says so in its hello, and root
  ;; records it as the agent's problem, :seon.agent/fork-misaligned
  (ts/test-branch! (fn [cfg conn]
    (let [at (d/commit-id @conn) t (:max-tx @conn)
          _ (d/transact conn [{:note/text "main moved on"}])]
      (is (nil? (agent/misaligned (d/commit-id @conn) (:max-tx @conn) @conn @conn)) "aligned: nothing to say")
      (let [x (agent/misaligned at t @conn @conn)
            [r a'] (sock/socketpair)
            end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
            st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
            [_ outs] (step #'ap/agent-proc st :from {:op :hello :t t :start-ms 1.0 :mem-mb 1.0 :interrupt-ok true :misaligned x})
            problem (first (filter #(= :problem (:op %)) (:to-api outs)))]
        (is (= {:commit at :t t :branch-commit (d/commit-id @conn) :image-t (:max-tx @conn)} x) "a branch past the commit it was branched at")
        (is (= :seon.agent/fork-misaligned (get-in problem [:error :seon.error/type])) "is recorded as the agent's problem")
        (is (= at (get-in problem [:error :commit])))
        (remote/close! end) (sock/close! a'))))))

(deftest a-fork-names-the-commit-its-agent-starts-at
  ;; task seon.agent/an-agents-base-is-its-merge-base: :spawn branches the agent from main's
  ;; head and asks the fork server, whose image holds main at that head (the FIFO), to fork it;
  ;; :fork names that commit and t, so the agent checks both (seon.agent/init!)
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        [st _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})
        b (pdb/on-main (:branch cfg) :a1)
        [_ outs] (step #'writer/writer-proc st :in {:op :spawn :agent :a1 :branch b :ns 0 :fd 0 :dest {:rid 2}})
        fork (first (filter #(= :fork (:op %)) (:to-fork-server outs)))]
    (is (= (d/commit-id @conn) (:commit fork)) "main's commit")
    (is (= (:max-tx @conn) (:t fork)) "main's t")
    (is (= (:commit fork) (d/commit-id (pdb/head conn b))) "which the agent's branch starts at")))))

(deftest a-value-the-store-cannot-encode-is-refused-to-its-asker
  ;; land6 re-proof (e2e loop): a sorted map on root's source fact reached the store's codec at
  ;; commit (konserve.jolt.fasl has no encoding for it) and threw out of the writer as a proc
  ;; defect, unanswered. The commit refusing is the asker's error: the transaction that carried
  ;; it is answered :seon.writer/refused, the others in its batch commit, and the writer goes on.
  ;; A store on disk of this test's own, the last resort: the in-memory test store has no codec
  (let [path (str (System/getProperty "user.dir") "/target/procs-test-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)]
   (try (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        source (fn [pins] {:seon.root/id :root :seon.root/source {:root "tree" :files 0 :forms 0 :ms 0.0 :pins pins}})]
    (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "after"}] :dest {:rid 2}})
    (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [(source (into (sorted-map) (for [i (range 40)] [(str "reference-code/m" i) (apply str (repeat 40 "a"))])))] :dest {:rid 1}})
          answers (into {} (keep (fn [x] (when-let [r (get-in x [:dest :rid])] [r (:r x)]))) (:to-api outs))]
      (is (= :seon.writer/refused (:seon.error/type (answers 1))) (pr-str (answers 1)))
      (is (true? (:ok (answers 2))) "the batch's other transaction commits")
      (testing "the writer goes on, and a plain map of pins (what the source read answers) reads back"
        (let [pins (:pins (seon.fx/read-source {:req {:root (System/getProperty "user.dir") :dirs ["src"]}}))
              [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [(source pins)] :dest {:rid 3}})]
          (is (true? (:ok (:r (first (:to-api outs))))))
          (is (seq pins))
          (is (= pins (get-in (d/pull @conn [:seon.root/source] [:seon.root/id :root]) [:seon.root/source :pins])))))))
     (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest the-flush-is-continuous-group-commit
  ;; owner 2026-10-21 (notes/archive/DECISIONS-superseded.md, "Durability is batched everywhere", superseded
  ;; paragraph): no window. A write is answered when committed in memory; the flusher parks
  ;; until something is unflushed, syncs once for everything committed so far, and says what it
  ;; covered; commits during a sync share the next one; an idle system never syncs.
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? true :to-writer in :events (a/chan 16)})]
    (testing "the flusher syncs at once and tells the writer which commits it covered"
      (let [fl (#'writer/flusher-proc {:to-writer in :cfg cfg})
            [fl outs] (step #'writer/flusher-proc fl :in {:op :dirty :commits 3 :since 0})]
        (is (= {:to-writer [{:op :flushed :commits 3}]} outs))
        (is (= 1 (:flushes fl)))))
    (testing "a write is answered unflushed, and the writer tells the flusher what it committed"
      (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})
            notice (first (:to-flusher outs))]
        (is (true? (:ok (:r (first (:to-api outs))))))
        (is (= {:op :dirty :commits (:commits st)} (select-keys notice [:op :commits])))
        (testing "the flush that covers it leaves the store clean"
          (let [[st2 _] (step #'writer/writer-proc st :in {:op :flushed :commits (:commits st)})]
            (is (false? (:dirty st2)))
            (is (= 1 (- (:flushes st2 0) (:flushes st 0))))))
        (testing "a commit after the flush began: still dirty, and the flusher is told again"
          (let [[st3 _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b"}] :dest {:rid 2}})
                [st4 outs] (step #'writer/writer-proc st3 :in {:op :flushed :commits (:commits st)})]
            (is (true? (:dirty st4)))
            (is (= [{:op :dirty :commits (:commits st3)}] (mapv #(select-keys % [:op :commits]) (:to-flusher outs))))))))))))

(deftest the-writer-answers-clean-once-the-flush-covers-its-commits
  ;; task seon.check/idle-waits-for-the-flush: a :clean request is held while the store is
  ;; dirty and answered by the step that takes the covering flush, after that flush's notice
  ;; to the api; a clean store answers at once
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? true :to-writer in :events (a/chan 16)})
        [st _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})
        [st outs] (step #'writer/writer-proc st :in {:op :clean :dest {:rid 2}})]
    (is (not-any? #(= {:rid 2} (:dest %)) (:to-api outs)) "dirty: held")
    (let [[st2 outs] (step #'writer/writer-proc st :in {:op :flushed :commits (:commits st)})
          api (:to-api outs)
          i-notice (first (keep-indexed #(when (= :writer (:op %2)) %1) api))
          i-answer (first (keep-indexed #(when (= {:rid 2} (:dest %2)) %1) api))]
      (is (some? i-answer) "the covering flush answers it")
      (is (< i-notice i-answer) "after the flush's notice to the api")
      (is (= (:commits st) (get-in api [i-answer :r :commits])))
      (is (empty? (:clean-waiters st2)))
      (let [[_ outs] (step #'writer/writer-proc st2 :in {:op :clean :dest {:rid 3}})]
        (is (some #(= {:rid 3} (:dest %)) (:to-api outs)) "clean: at once")))))))

(deftest reconciler
  (ts/test-branch! (fn [_ conn] (let [st (#'reconcile/reconciler-proc {:boot "b" :provider {:kind :scripted :script 'seon.fixtures/reply}})]
    (testing "a main commit with no tasks: the reconciler reads it and starts nothing (quiescence: seon.reconcile-test)"
      (let [[st outs] (step #'reconcile/reconciler-proc st :main {:t 7 :ops [] :db @conn :ns (profile/now-ns)})]
        (is (= 1 (:seen st)))
        (is (= :main-seen-us (:k (first (:to-api outs)))))
        (is (empty? (:flights st)))))
    (testing "an answer nobody asked for changes nothing"
      (let [[st2 outs] (step #'reconcile/reconciler-proc st :answers {:tag 99 :r {:ok true}})]
        (is (= (:flights st) (:flights st2)))
        (is (empty? (:to-api outs)))))))))

(deftest fork-server-events
  (let [st {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :log []}]
    (testing "a stats event answers the oldest waiter, by rid"
      (let [[st outs] (step #'server/fork-server-proc st :ev {:ev :stats :t 3})]
        (is (empty? (:waiters st)))
        (is (= {:rid 5} (:dest (first (:to-api outs)))))))
    (testing "an event about an agent goes on to the api"
      (let [[_ outs] (step #'server/fork-server-proc st :ev {:ev :exit :pid 9 :status {:exit 0}})]
        (is (= [9] (map :pid (:to-api outs))))))
    (testing "EOF of an older server is ignored"
      (let [[st2 outs] (step #'server/fork-server-proc st :ev {:ev :eof :pid 2})]
        (is (= st st2)) (is (nil? outs))))))

(deftest agent-proc-steps
  ;; root's end of the agent's socket is a jolt.async.remote end: the agent's frames arrive
  ;; as values on its :in (the agent proc's :from), and nil there is its close
  (let [[r a] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        st (assoc st :pid 42 :inflight {7 {:kind :eval}})]
    (testing "a reply answers its request by id and reports the agent idle"
      (let [[st outs] (step #'ap/agent-proc st :from {:op :reply :id 7 :items [{:printed "3"}] :t 1})]
        (is (= {:rid 7} (:dest (first (:to-api outs)))))
        (is (empty? (:inflight st)))
        (is (some #(= :idle (get-in % [:set :status])) (:to-api outs)))))
    (testing "the agent committed its own branch: the writer hears it, for the next flush"
      (let [[_ outs] (step #'ap/agent-proc st :from {:op :committed :t 5 :txs 2})]
        (is (= [{:op :agent-commit :branch :agent-1 :t 5}] (:to-writer outs)))))
    (testing "the end's close ends the agent, naming the requests it leaves unanswered"
      (let [[st outs] (step #'ap/agent-proc st :from nil)]
        (is (:dead? st))
        (is (= [:ended] (map :op (filter :op (:to-api outs)))))
        (is (= [7] (:inflight (first (filter #(= :ended (:op %)) (:to-api outs))))))))
    (testing "a request goes onto the end's :out, framed for the agent's socket"
      (let [_ (step #'ap/agent-proc st :in {:op :write :msg {:op :message :body 1}})
            c (sock/conn a)]
        (is (= {:op :message :body 1} (sock/recv! c)))))
    (remote/close! end)
    (sock/close! a)))

(deftest api-server
  (let [st (#'root/api-proc {:ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                          :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})]
    (testing "the view is answered to its caller's promise"
      (let [p (promise)
            [_ outs] (step #'root/api-proc st :ext {:op :state :p p})]
        (is (= {:agents {}} @p)) (is (= {} outs))))
    (testing "a request for no live agent answers at once"
      (let [p (promise)]
        (step #'root/api-proc st :ext {:op :eval :id :nope :forms "1" :p p})
        (is (re-find #"no live agent" (:seon.error/message @p)))))
    (testing "a writer request carries its rid and is answered by it"
      (let [p (promise)
            [st outs] (step #'root/api-proc st :ext {:op :write :req {:op :tx :branch :db :tx-data []} :p p})
            rid (get-in (first (:to-writer outs)) [:dest :rid])
            [_ _] (step #'root/api-proc st :from-writer {:dest {:rid rid} :r {:ok true}})]
        (is (= {:ok true} @p))))))

(def ^:private fake-members
  "Protocol members a refused answer must not carry: an eval item's, a test count's, a merge's."
  #{:id :tag :ok :merged? :pass :fail :error :reports :applied :conflicts})

(deftest a-refused-answer-is-the-one-error-map
  ;; a request no live agent can take, a function root refuses, and a write the writer refuses
  ;; each answer the one error map (seon.contract/error) and nothing beside it: no eval item
  ;; with id -1, no test count, no :ok or :merged? false
  (let [st (#'root/api-proc {:ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        bare? (fn [r] (and (map? r) (seon.contract/error? r) (empty? (filter fake-members (keys r)))))]
    (doseq [op [:eval :tests :merge-tests :merge-main :stats]]
      (let [p (promise)]
        (step #'root/api-proc st :ext {:op op :id :nope :forms "1" :p p})
        (is (bare? @p) (str op " to no live agent: " (pr-str @p)))))
    (let [p (promise)]
      (step #'root/api-proc st :ext {:op :clone :parent :nope :p p})
      (is (bare? @p) (pr-str @p)))
    (let [p (promise)]
      (step #'root/api-proc st :ext {:op :ping :id :nope :p p})
      (is (= :seon.root/refused (:seon.error/type @p)) "a request kind with no producer is refused, not left unanswered"))
    (doseq [[f a] [['merge-main! nil] ['merge! nil] ['merge! {:branch :b :status :dead}] ['nope! nil]]]
      (is (bare? (root/request f :a1 nil a)) (str f))))
  (ts/test-branch! (fn [cfg conn]
    (let [st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)})
          [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:no/such 1}] :dest {:rid 1}})
          r (:r (first (:to-api outs)))]
      (is (and (seon.contract/error? r) (not (contains? r :ok))) (pr-str r))))))

(deftest a-collection-waits-until-no-agent-is-live
  ;; root collects the store only as its only writer (owner, 2026-10-22, simplest first;
  ;; seon.db/collect!): a :collect while an agent is live is refused at once, naming the live
  ;; agents, and never reaches the writer; with none live it goes to the writer like any write
  (let [st (#'root/api-proc {:ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})]
    (let [p (promise)
          [_ outs] (step #'root/api-proc (assoc st :agents {:a1 {:status :running}}) :ext {:op :write :req {:op :collect} :p p})]
      (is (realized? p) "answered at once")
      (is (= :seon.root/refused (:seon.error/type (when (realized? p) @p))))
      (is (= [:a1] (:agents (when (realized? p) @p))))
      (is (empty? (:to-writer outs))))
    (let [p (promise)
          [_ outs] (step #'root/api-proc st :ext {:op :write :req {:op :collect} :p p})]
      (is (= :collect (:op (first (:to-writer outs))))))))

;; ---- request/answer: one asker's table (seon.step/pend, ask, answered) ---------------------

(deftest one-asker-table-answers-each-request-once
  (let [st {:rid 0 :pending {}}
        [st m1] (step/ask st [:render :answers] :state {:op :state})
        [st m2] (step/ask st [:render :answers] [:branch :a1] {:op :write})
        [st r3] (step/pend st nil)]
    (testing "each request a new id, routed back to the asker, what awaits it pending by id"
      (is (= {:op :request :req {:op :state} :route [:render :answers] :tag 1} m1))
      (is (= 2 (:tag m2)))
      (is (= 3 r3 (:rid st)))
      (is (= {1 :state 2 [:branch :a1]} (:pending st)) "an id nothing awaits holds no entry"))
    (testing "an answer takes its entry once: a second answer to the same id finds nothing"
      (let [[st v] (step/answered st 2)
            [st again] (step/answered st 2)]
        (is (= [:branch :a1] v))
        (is (nil? again))
        (is (= {1 :state} (:pending st)))))
    (testing "an id never asked finds nothing"
      (is (nil? (second (step/answered st 99)))))))

(defn- agent-proc-on
  "An agent's proc over a socketpair, with request 7 in flight; f takes it, then both ends close."
  [f]
  (let [[r a] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})]
    (try (f (assoc st :pid 42))
         (finally (remote/close! end) (sock/close! a)))))

(deftest an-eval-says-it-started
  ;; task seon.check/every-wait-is-the-event-it-means: the agent's {:op :started} is root's
  ;; :started event, with the eval's text (what a kill or an interrupt meant for it awaits)
  (agent-proc-on
   (fn [st]
     (let [[st _] (step #'ap/agent-proc st :in {:op :req :rid 7 :kind :eval :msg {:op :eval :forms "(loop [] (recur))"}})
           [_ outs] (step #'ap/agent-proc st :from {:op :started :id 7})]
       (is (= [{:type :started :agent :a1 :rid 7 :form "(loop [] (recur))"}] (mapv #(dissoc % :at-ms) (:events outs))))))))

(deftest a-test-run-says-its-child-forked
  ;; the agent sends the test child's pid once it is forked: root's :tests-started event
  (agent-proc-on
   (fn [st]
     (let [[_ outs] (step #'ap/agent-proc st :from {:op :tests-started :id 7 :pid 4242})]
       (is (= [{:type :tests-started :agent :a1 :rid 7 :pid 4242}] (mapv #(dissoc % :at-ms) (:events outs))))))))

(deftest a-tab-hears-its-view-settled
  ;; the render proc says when nothing it asked is in flight any more (after an answer, or
  ;; after what it sent the tabs), never while a request is out; the web proc puts a
  ;; settled comment on every feed
  (let [st {:events-ch (a/chan 8) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events []
            :views {} :zoom {} :merges 0 :main {:t 1}}
        settled? (fn [outs] (some #(= :settled (:op %)) (:to-web outs)))
        [st o1] (#'seon.render.proc/render-proc st :events {:type :spawned :agent :a1 :at-ms 1})
        tag (:tag (first (filter #(= {:op :state} (:req %)) (:to-api o1))))
        [st o2] (#'seon.render.proc/render-proc st :answers {:tag tag :r {:agents {} :writer {:tx-count nil :recent []} :fork-server {:status :up} :stats {} :store {} :boot {}}})]
    (is (not (settled? o1)) "its ask is in flight")
    (is (settled? o2) "the answer emptied what was in flight")
    (is (empty? (:pending st))))
  (let [ch (a/chan 4)
        st {:tabs {1 {:ch ch :view :root}} :stale #{} :bytes 0}
        [st _] (#'seon.web/web-proc st :from-render {:op :settled})]
    (is (= ": settled\n\n" (a/poll! ch)))
    (is (= (count ": settled\n\n") (:bytes st)))))

(deftest the-render-proc-asks-for-root-s-view-once-per-round-trip
  ;; the coalescing window is the request in flight, not a clock (ASK-BOUND-MS is gone)
  (let [events (a/chan 8)
        st {:events-ch events :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events []
            :views {} :zoom {} :merges 0 :main {:t 1}}
        asks (fn [outs] (filter #(= {:op :state} (:req %)) (:to-api outs)))
        [st o1] (#'seon.render.proc/render-proc st :events {:type :spawned :agent :a1 :at-ms 1})
        [st o2] (#'seon.render.proc/render-proc st :events {:type :ready :agent :a1 :at-ms 2})]
    (is (= 1 (count (asks o1))) "an event asks for the view")
    (is (empty? (asks o2)) "while that request is in flight, more events only queue")
    (let [[st _] (#'seon.render.proc/render-proc st :answers {:tag (:tag (first (asks o1))) :r {:agents {} :writer {:tx-count 1 :recent []} :fork-server {:status :up} :stats {} :store {} :boot {}}})
          [_ o3] (#'seon.render.proc/render-proc st :events {:type :ready :agent :a2 :at-ms 3})]
      (is (not-any? #{:state} (vals (:pending st))) "the answer took its request")
      (is (= 1 (count (asks o3))) "the next event asks again"))))

;; ---- task seon.root/a-proc-answers-every-request-it-took (laws audit #2) ----------------------

(defn- api-state []
  (#'root/api-proc {:ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                    :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))}))

(defn- answer "What p was answered, or :unanswered." [p] (if (realized? p) @p :unanswered))

(deftest a-throwing-step-answers-every-pending-request
  ;; Flow's step catch keeps the old state and drops the outputs; its error message carries the
  ;; message the step took (::flow/msg), which root's error reader forwards as :msg
  (let [st (api-state)]
    (testing "the api's own step threw on {:op :state}: its caller is answered the defect, not left waiting"
      (let [p (promise)]
        (step #'root/api-proc st :back {:op :error :pid :api :message "boom" :msg {:op :state :p p}})
        (is (= :seon.root/proc-defect (:seon.error/type (answer p))))))
    (testing "the writer threw on a request the api sent it: the api answers that request's caller by its rid"
      (let [p (promise)
            [st outs] (step #'root/api-proc st :ext {:op :write :req {:op :tx :branch :db :tx-data []} :p p})
            sent (first (:to-writer outs))]
        (step #'root/api-proc st :back {:op :error :pid :writer :message "boom" :msg sent})
        (is (= :seon.root/proc-defect (:seon.error/type (answer p))))
        (is (= "boom" (:seon.error/message (answer p))))))
    (testing "the api's step threw on a later message of its batch (Flow names only the first): each message it took is answered"
      (let [first-msg {:op :state :p (promise)}]
        (a/>!! (:back st) {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data []}} :route [:reconciler :answers] :tag 3})
        (a/>!! (:back st) {:op :status :agent "not-an-id" :set {}})
        (is (thrown? Exception (#'root/api-proc st :ext first-msg)) "the third message throws: Flow keeps st, drops the outputs")
        (let [[_ outs] (step #'root/api-proc st :back {:op :error :pid :api :message "boom" :msg first-msg})
              again (get outs [:reconciler :answers])]
          (is (= [3] (map :tag again)) "the second message, a proc's write request, is answered on its route")
          (is (= :seon.root/proc-defect (:seon.error/type (:r (first again))))))))
    (testing "a proc threw on the answer it took: the api answers it again on the proc's :answers route, once"
      (let [[_ outs] (step #'root/api-proc st :back {:op :error :pid :render :message "boom" :msg {:tag 7 :r {:agents {}}}})
            again (get outs [:render :answers])]
        (is (= [7] (map :tag again)))
        (is (= :seon.root/proc-defect (:seon.error/type (:r (first again)))))
        (testing "and a step that throws on that defect is not answered again"
          (let [[_ outs] (step #'root/api-proc st :back {:op :error :pid :render :message "boom" :msg (first again)})]
            (is (empty? (get outs [:render :answers])))))
        (testing "the render proc takes the defect: its view request answered, the view as it was"
          (let [rs {:rid 7 :pending {7 :state} :view {:agents {:a1 {}}}}
                [rs2 _] (#'seon.render.proc/render-proc rs :answers (first again))]
            (is (empty? (:pending rs2)))
            (is (= (:view rs) (:view rs2)))))))))

(defn- answering-sys
  "(f sys), sys's server answering every request with r (a stub for the api), on a
  branch of the test store deleted when f returns."
  [r f]
  (ts/test-branch! (fn [cfg conn] (let [ext (a/chan 4)]
    (a/go-loop [] (when-let [m (a/<! ext)] (deliver (:p m) r) (recur)))
    (f {:ext ext :cfg cfg :conn conn :graph nil})))))

(deftest root-state-never-answers-an-empty-system
  ;; "Unknown is not {}": what the api answers is what the caller gets; a defect stays a defect
  (let [e (seon.contract/error :seon.root/proc-defect 'seon.root/api-proc "boom" {:pid :api})]
    (answering-sys e (fn [sys]
      (testing "state: the defect, not a view decorated around it"
        (is (= e (root/state sys))))
      (testing "errors: the defect, not an empty fold"
        (is (= e (root/errors sys))))
      (a/close! (:ext sys))))))

(deftest the-step-loop-runs-flows-contract-in-one-thread
  ;; a counter step-fn run by seon.step/run-loop over an in-memory transport
  (let [step-fn (fn ([] {:workload :io :ping-map-fn (fn [s] {:n (:n s)})})
                  ([_] {:n 0 :log []})
                  ([s tr] (update s :log conj tr))
                  ([s _ m] [(update s :n inc) {:out [[:got m]]}]))
        inbox (atom [[:in 1] [:in {:op :pause}] [:in 2] [:in {:op :ping}] [:in {:op :resume}] [:in 3] [:in {:op :stop}] [:in 4]])
        sent (atom [])
        final (step/run-loop step-fn nil
                             (fn [] (let [[x & more] @inbox] (reset! inbox more) x))
                             (fn [port m] (swap! sent conj [port m])))]
    (is (= [1 2 3] (keep (fn [[p m]] (when (= p :out) (second m))) @sent)) "held while paused, then in order")
    (is (= {:op :pong :status :paused :count 1 :held 1 :state {:n 1}} (second (first (filter (fn [[p m]] (= :pong (:op m))) @sent)))))
    (is (= [::flow/pause ::flow/resume ::flow/stop] (:log final)))
    (is (= [[:in 4]] @inbox) "nothing is taken after stop")))

(deftest the-fork-server-step
  (ts/test-branch! (fn [_ conn] (let [db @conn
        st (#'server/server-step {:db db :conn nil})]
    (testing "a main commit is applied to its replica"
      (let [rep (d/with db [{:note/text "z"}])
            ops (pdb/report->tx rep)
            [st outs] (step #'server/server-step st :cmd {:op :tx :t (:max-tx (:db-after rep)) :ns 0 :tx-data ops})]
        (is (= (:max-tx (:db-after rep)) (:max-tx (:db st))))
        (is (= [:applied] (map :ev (:ev outs))))
        (testing "and a commit it already holds is skipped"
          (is (= [st nil] (#'server/server-step st :cmd {:op :tx :t (:max-tx (:db st)) :tx-data ops}))))))
    (testing "the replica's transaction holds main's transaction facts (its instant, its tx-meta): seon.db/report->tx-meta"
      (let [rep (d/with db {:tx-data [{:note/text "w"}] :tx-meta {:note/text "the commit's own fact"}})
            t (:max-tx (:db-after rep))
            [st2 _] (step #'server/server-step st :cmd {:op :tx :t t :ns 0 :tx-data (pdb/report->tx rep) :tx-meta (pdb/report->tx-meta rep)})
            instant (fn [d] (:db/txInstant (d/entity d t)))]
        (is (inst? (instant (:db-after rep))) "main's transaction has its instant (history is kept)")
        (is (= (instant (:db-after rep)) (instant (:db st2))))
        (is (= "the commit's own fact" (:note/text (d/entity (:db st2) t))) "and its tx-meta")))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.procs-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
