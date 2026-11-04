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
            [seon.agent :as agent] [seon.agent-proc :as ap] [seon.fx] [seon.root :as root] [seon.problems :as problems] [seon.repl :as repl] [seon.render.proc] [seon.web]
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
        (is (< t0 (:max-tx @conn)) "main's t moves past its last")
        (is (= [:tx] (map :op (:to-fork-server outs))))
        (is (= [(:max-tx @conn)] (map :t (:main outs))))
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
  ;; commit carry the runtime that made them, and so does the stored commit the fork server's replica reads
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})
        fp (jolt.host/runtime-fingerprint)]
    (is (= fp (:seon.tx/runtime (d/entity @conn (:max-tx @conn)))) "main's transaction names it")
    (let [m (first (:to-fork-server outs))]
      (is (= fp (:seon.tx/runtime (d/entity (d/commit-as-db conn (:commit m)) (:t m)))) "and so does the stored commit the replica reads"))))))

(deftest a-writers-merge-names-its-runtime
  ;; task seon.db/each-commit-names-its-runtime: a branch merged into main through the writer
  ;; (:octopus-merge, d/merge-db) is a transaction like any other: it names the runtime that made it
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
        [_ outs] (step #'writer/writer-proc st :in {:op :octopus-merge :branches [b] :dest {:rid 1}})]
    (is (:merged? (first (:results (:r (first (filter #(= {:rid 1} (:dest %)) (:to-api outs))))))) "the branch merges")
    (is (< t0 (:max-tx @conn)))
    (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @conn (:max-tx @conn)))) "and its merge names the runtime")))))

(deftest a-merge-candidate-is-what-the-merge-writes
  ;; the merge gate's checkout takes the writer's :merge-candidate: the files the octopus merge would write, with their
  ;; text, its changed schema entries' resources among them (md-scan 29: the gate wrote the changed namespace's file
  ;; but not :seon.ref/block's resource, so the checkout's new seon.ref/scan met its old schema and the seed threw)
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        b (pdb/on-main (:branch cfg) :a1)
        _ (d/branch! conn (:branch cfg) b)
        bc (d/connect (assoc cfg :branch b))
        [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f2] [?v :var/form ?f] [?f :form/ns ?n]] @bc)
        _ (d/transact bc [{:db/id "f2'" :form/id "app.m0/f2-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                           :form/src "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 200))"}
                          [:db/add v :var/form "f2'"]
                          {:schema/key :app.m0/thing :schema/form "[:map [:x :int]]" :schema/file "resources/seon/schemas/app.m0.edn"}])
        _ (d/release bc)
        t0 (:max-tx @conn)
        [_ outs] (step #'writer/writer-proc st :in {:op :merge-candidate :branch b :dest {:rid 1}})
        r (:r (first (filter #(= {:rid 1} (:dest %)) (:to-api outs))))]
    (is (:ok r) (pr-str r))
    (is (some #{"resources/seon/schemas/app.m0.edn"} (:files r)) (str "the changed schema entry's resource: " (pr-str (:files r))))
    (is (re-find #":app.m0/thing" (str (get (:text r) "resources/seon/schemas/app.m0.edn"))) "with its text as the merge holds it")
    (is (or (some #(re-find #"\.clj$" %) (:files r)) (contains? (:unfiled r) 'app.m0))
        (str "the changed namespace's own source file, or it named as unfiled (the schema resource alone is not it): " (pr-str (select-keys r [:files :unfiled]))))
    (is (= t0 (:max-tx @conn)) "and main is unchanged: a candidate is never committed")))))

(deftest the-fork-servers-replica-is-main-at-each-commit
  ;; the fork server's replica reads each main commit from the store (d/commit-as-db of the
  ;; commit the writer names), never replaying its datoms: replaying the boot's 42,942-datom
  ;; source import took 3.5 s and the first fork waited behind it (2026-10-23). After a large
  ;; commit the replica is main at that t exactly: the same commit, the same datoms
  (ts/test-branch! (fn [cfg conn]
    (let [in (a/chan 16)
          wst (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
          before @conn
          [_ outs] (step #'writer/writer-proc wst :in {:op :tx :branch :db :tx-data (vec (for [i (range 2000)] {:note/text (str "n" i)})) :dest {:rid 1}})
          m (first (filter #(= :tx (:op %)) (:to-fork-server outs)))
          fst (#'server/server-step {:db before :cfg cfg :store conn :conn nil})
          [fst' fouts] (step #'server/server-step fst :cmd m)
          replica (:db fst')]
      (is (= (d/commit-id @conn) (:commit m)) "the writer names the commit it made")
      (is (= (:max-tx @conn) (:max-tx replica) (:t m)) "the replica is main at t")
      (is (= (d/commit-id @conn) (d/commit-id replica)))
      (is (= (vec (d/datoms @conn :eavt)) (vec (d/datoms replica :eavt))) "with main's datoms, every one")
      (is (= 0 (:mismatch fst')))
      (is (= [:applied] (mapv :ev (:ev fouts))))))))

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

(deftest an-agent-root-cannot-interrupt-is-a-problem-and-memory-rides-its-commits
  ;; shrink brief 3, rows 19 and 34: an agent whose boot check found root's interrupt cannot
  ;; reach it says so in its hello, and root records :seon.agent/interrupt-unreachable (the
  ;; answer nothing read before); its memory reaches root's view on its commits, never on each
  ;; eval reply
  (let [[r a'] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        problems (fn [ok] (filter #(= :problem (:op %)) (:to-api (second (step #'ap/agent-proc st :from {:op :hello :t 1 :start-ms 1.0 :mem-mb 1.0 :interrupt-ok ok})))))
        sets (fn [m] (keep #(when (= :status (:op %)) (:set %)) (:to-api (second (step #'ap/agent-proc st :from m)))))]
    (is (= [:seon.agent/interrupt-unreachable] (map #(get-in % [:error :seon.error/type]) (problems false))))
    (is (empty? (problems true)) "a reachable agent records nothing")
    (is (= [{:mem-mb 12.5}] (sets {:op :committed :t 2 :txs 1 :mem-mb 12.5})) "a commit carries its memory to the view")
    (remote/close! end) (sock/close! a')))

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
  (let [path (str (seon.config/checkout) "/target/procs-test-" (random-uuid))
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
        (let [pins (:pins (seon.fx/read-source {:req {:root (seon.config/checkout) :dirs ["src"]}}))
              [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [(source pins)] :dest {:rid 3}})]
          (is (true? (:ok (:r (first (:to-api outs))))))
          (is (seq pins))
          (is (= pins (get-in (d/pull @conn [:seon.root/source] [:seon.root/id :root]) [:seon.root/source :pins])))))))
     (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest an-import-the-store-refuses-is-a-cause-on-root
  ;; seon.root/import-source! wrote its :seon.root/source fact and ignored the answer: a fact
  ;; the store refused (inputs holding a sorted map, which konserve.jolt.fasl cannot encode)
  ;; left the previous boot's fact in force, agents shared no inputs, and nothing said why.
  ;; Root's api is stood in for by a loop that answers the source read with the inputs given
  ;; and steps the real writer proc on a store of its own (the in-memory test store has no
  ;; codec) for each write; the same inputs as a plain map are the control, and close the cause.
  (let [path (str (seon.config/checkout) "/target/procs-test-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)]
    (try (let [st (volatile! (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 16) :events (a/chan 16)}))
               env (for [i (range 40)] [(str "reference-code/m" i) (apply str (repeat 40 "a"))])
               import! (fn [inputs boot]
                         (let [ext (a/chan 16)
                               api (future (loop [] (when-let [{:keys [op req p]} (a/<!! ext)]
                                                      (deliver p (if (= op :fx)
                                                                   {:inputs inputs :binary "t"}
                                                                   (let [[st' outs] (step #'writer/writer-proc @st :in (assoc req :dest {:rid 1}))]
                                                                     (vreset! st st')
                                                                     (some :r (:to-api outs)))))
                                                      (recur))))
                               fact (root/import-source! {:graph nil :ext ext :conn conn :boot boot} {:root "tree" :dirs []})]
                           (a/close! ext) @api
                           fact))
               held #(:seon.root/source (d/pull @conn [:seon.root/source] [:seon.root/id :root]))
               cause #(some (fn [r] (when (= :seon.root/source (get-in r [:example :seon.error/subject])) r))
                            (get-in (problems/report @conn) [:classes :platform :rows]))
               fact (import! {:tree "t" :env (into (sorted-map) env)} "b1")]
           (is (= fact (held)) "the fact answered is the one in force")
           (is (= "b1" (:boot (held))) "this boot's")
           (is (nil? (:inputs (held))) "no inputs shared that were never stored")
           (is (some-> (:error (held)) (clojure.string/includes? "refused")) (pr-str (held)))
           (is (some? (:message (cause))) "the refused fact is a :platform cause on root, with the writer's reason")
           (is (not= false (:open? (cause))) "open while no import has written the fact")
           (testing "inputs of their declared shape store, and close the cause"
             (let [good {:tree "t" :env "e" :config {}}
                   fact (import! good "b2")]
               (is (= good (:inputs (held))) (pr-str fact))
               (is (nil? (:error (held))))
               (is (false? (:open? (cause))) (pr-str (cause))))))
         (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest the-flush-is-continuous-group-commit
  ;; owner 2026-10-21 (git history, "Durability is batched everywhere", superseded
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

(defn- notice
  "The writer's notice for conn's head, the commit after prev: {:t :prev :ops :commit :ns}."
  [conn prev ops] {:t (:max-tx @conn) :prev prev :ops ops :commit (d/commit-id @conn) :ns (profile/now-ns)})

(deftest the-writers-main-notice-holds-no-db
  ;; task seon.writer/main-notices-carry-a-commit-not-a-db: a queued notice pins its own version of
  ;; the index (AGENTS section 4), so the notice names the commit and its consumers make the db
  ;; when they take it
  (ts/test-branch! (fn [cfg conn]
    (let [in (a/chan 16)
          st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
          t0 (:max-tx @conn)
          _ (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "c"}] :dest {:rid 3}})
          [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b"}] :dest {:rid 2}})
          n (first (:main outs))]
      (is (= 1 (count (:main outs))) "a group commit is one stored commit: one notice")
      (is (not-any? #(some? (:max-tx %)) (vals n)) "no db value in the notice")
      (is (= #{:t :prev :ops :commit :ns} (set (keys n))))
      (is (= t0 (:prev n)))
      (is (= (:max-tx @conn) (:t n)))
      (is (= (:tx-data (first (:to-fork-server outs))) (:ops n)) "its ops are every report's ops, in order: the stored commit's")
      (is (= #{"b" "c"} (set (keep (fn [[_ _ a v]] (when (= :note/text a) v)) (:ops n)))) "the batch's two transactions are both in it")
      (is (= (d/commit-id @conn) (:commit n)))
      (is (= (:max-tx @conn) (:max-tx (d/commit-as-db conn (:commit n)))) "the consumer makes the db from it")))))

(deftest reconciler
  (ts/test-branch! (fn [_ conn] (let [st (#'reconcile/reconciler-proc {:boot "b" :conn conn :provider {:kind :scripted :script 'seon.fixtures/reply}})]
    (testing "a main commit with no tasks: the reconciler reads it and starts nothing (quiescence: seon.reconcile-test)"
      (let [[st outs] (step #'reconcile/reconciler-proc st :main (notice conn nil []))]
        (is (= 1 (:seen st)))
        (is (= :main-seen-us (:k (first (:to-api outs)))))
        (is (empty? (:flights st)))))
    (testing "an answer nobody asked for changes nothing"
      (let [[st2 outs] (step #'reconcile/reconciler-proc st :answers {:tag 99 :r {:ok true}})]
        (is (= (:flights st) (:flights st2)))
        (is (empty? (:to-api outs)))))))))

(deftest a-wait-on-main-is-answered-at-the-reconcilers-quiet
  ;; task seon.root/a-wait-on-main-ends-when-nothing-can-make-it-true: a wait on main (seon.root/
  ;; await-db, named) asks the reconciler on :judge for its first quiet at or after :after. Before,
  ;; only a commit making the wait's pred true ended it, and a quiet spell that went on wrote no
  ;; later report: agent_test's four await-quiet calls after :done hung (1h45m). The answer is the
  ;; reconciler's own state, never a clock
  (ts/test-branch! (fn [_ conn]
    (let [v #'reconcile/reconciler-proc
          ;; a commit touching nothing the reconciler watches (as root's own report is)
          other-commit! #(d/transact conn {:tx-data []})
          [st0 _] (step v (v {:boot "b" :conn conn :provider {:kind :scripted :script 'seon.fixtures/reply}}) :main (notice conn nil []))
          t (:t st0)
          ;; the writer's answer to the spell's report
          [st _] (step v st0 :answers {:tag (:report st0) :r {:ok true}})]
      (is (:quiet st0) "a commit with no tasks leaves the reconciler quiet")
      (testing "a new spell's wait is held until its report is committed, so the wait reads it"
        (let [p (promise) [st1 _] (step v st0 :judge {:op :quiet :after t :p p})]
          (is (= ::unanswered (deref p 0 ::unanswered)))
          (step v st1 :answers {:tag (:report st0) :r {:ok true}})
          (is (= {:quiet t} (deref p 0 ::unanswered)))))
      (testing "asked while quiet past its :after: answered at once, with the t it was quiet at"
        (let [p (promise) [st2 outs] (step v st :judge {:op :quiet :after t :p p})]
          (is (= {:quiet t} (deref p 0 ::unanswered)))
          (is (empty? (:waits st2)) "nothing held")
          (is (empty? (:to-api outs)) "a wait asks nothing of the api")))
      (testing "asked past what it has seen: held until that commit's pass, then answered"
        (let [p (promise) [st2 _] (step v st :judge {:op :quiet :after (inc t) :p p})
              _ (other-commit!)
              [st3 _] (step v st2 :main (notice conn t []))
              ;; the spell goes on, or (main relisted) a new one began: answered once its report is
              [st3 _] (if (:report st3) (step v st3 :answers {:tag (:report st3) :r {:ok true}}) [st3 nil])]
          (is (= 1 (count (:waits st2))) "held while the reconciler has not seen t+1")
          (is (= {:quiet (:t st3)} (deref p 0 ::unanswered)))
          (is (empty? (:waits st3)))))
      (testing "a judge handed it first ends the spell: the wait behind it is held"
        (let [p (promise)
              [st2 _] (step v st :judge {:op :judge :run "r-1" :task "a/b" :env {} :agent :agent-1})
              [st3 _] (step v st2 :judge {:op :quiet :after (:t st2) :p p})]
          (is (= ::unanswered (deref p 0 ::unanswered)))
          (testing "and a stop answers what is held"
            (v st3 ::flow/stop)
            (is (= {:stopped true} (deref p 0 ::unanswered))))))))))

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

(deftest a-lost-fork-server-answers-every-waiter
  ;; the server died with no standby to take its place (it died at boot, before its first
  ;; standby): every request it was asked, and every one asked after, is answered with the
  ;; error, and a fork's descriptor is closed. Unfixed, a stats request waited for good and a
  ;; later fork was never sent nor answered, so the attach that asked for it hung (an agent
  ;; test's root whose fork server died at boot)
  (let [[fd peer] (sock/socketpair)
        st {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :fd fd :log []}
        refused? #(= :seon.root/refused (:seon.error/type %))
        [st outs] (step #'server/fork-server-proc st :ev {:ev :eof :pid 1})]
    (testing "its death answers the requests it never answered"
      (is (= [:fork-server-down :fork-server-lost] (keep :ev (:to-api outs))))
      (is (= [5] (keep #(get-in % [:dest :rid]) (:to-api outs))))
      (is (every? refused? (keep #(when (:dest %) (:r %)) (:to-api outs))))
      (is (empty? (:waiters st))))
    (testing "a fork asked after it fails at once, naming why, and its descriptor is closed"
      (let [[afd apeer] (sock/socketpair)
            [st outs] (step #'server/fork-server-proc st :cmd {:op :fork :agent :a1 :fd afd})]
        (is (= [{:ev :fork-failed :agent :a1}] (map #(select-keys % [:ev :agent]) (:to-api outs))))
        (is (string? (:reason (first (:to-api outs)))))
        (is (= -1 (sock/close! afd)) "root's copy of the agent's socket is closed")
        (is (empty? (:in-flight st)))
        (sock/close! apeer)))
    (testing "a stats request asked after it is answered at once"
      (let [[st outs] (step #'server/fork-server-proc st :ext {:op :stats :rid 6})]
        (is (= [6] (keep #(get-in % [:dest :rid]) (:to-api outs))))
        (is (refused? (:r (first (:to-api outs)))))
        (is (empty? (:waiters st)))))
    (testing "a shutdown of the lost server closes no descriptor it no longer holds"
      (is (nil? (:fd st)))
      (let [[st outs] (step #'server/fork-server-proc st :ext {:op :shutdown})]
        (is (:stopping st))
        (is (= [:fs-down] (keep :op (:to-api outs))))))
    (sock/close! peer)))

(deftest agent-proc-steps
  ;; root's end of the agent's socket is a jolt.async.remote end: the agent's frames arrive
  ;; as values on its :in (the agent proc's :from), and nil there is its close
  (let [[r a] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        st (assoc st :pid 42 :inflight {7 {:kind :eval}})]
    (testing "a reply answers its request by id and reports the agent idle"
      (let [[st outs] (step #'ap/agent-proc st :from {:op :reply :id 7 :response {:handle 'result/e1 :text "{:handle result/e1, :value 3}"} :t 1})]
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
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
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

(deftest root-records-what-it-observes-of-its-agents
  ;; task seon.render/the-agents-links-are-refs: what root observes of an agent is facts on the
  ;; agent's entity, written when a member it records changes; a change it does not record
  ;; (the main t the agent reports) writes nothing
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        ;; no stall check (:stall-ms 0): the busy agent's check is not this test's
        st (assoc st :stall-ms 0 :view {:agents {:a3 {:status :idle :pid 77 :branch :task-demo-f}}})
        [st outs] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:status :busy :running {:form "(f 2)" :since-ms 5}}})
        w (first (:to-writer outs))]
    (is (= {:op :tx :branch :db :dest {:agent-facts [:a3]}} (dissoc w :tx-data)))
    (is (= [:busy "(f 2)" 77] (let [p (first (filter :seon.process/id (:tx-data w)))] [(:seon.agent/status (first (:tx-data w))) (:seon.process/running p) (:seon.process/pid p)])))
    (is (= "b1/a3" (:seon.agent/key (first (:tx-data w)))) "its key in root's boot")
    (let [[st outs] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:t 42}})]
      (is (empty? (:to-writer outs)) "nothing it records changed: nothing written")
      (let [[_ outs] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:status :idle :running nil}})]
        (is (some #{[:db.fn/retractAttribute [:seon.process/id "b1/a3/77"] :seon.process/running]} (:tx-data (first (:to-writer outs))))
            "the form returned: no longer running")))))

(deftest root-view-of-an-agents-evals-holds-handles-not-values
  ;; task seon.root/an-agents-view-keeps-handles-not-values (docs/machine.md#roots-heap):
  ;; the bytes root's view keeps of an agent's evals are the same whatever the evals returned, wrote or
  ;; threw. Red while the entry held the response whole (its :value, :out and an error's :data grew with them)
  (let [st0 (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                              :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        st0 (assoc st0 :stall-ms 0 :view {:agents {:a3 {:status :idle :pid 77 :branch :task-demo-f :evals []}}})
        held (fn [n]
               (let [big (vec (range n))
                     r1 {:handle 'result/e1 :text "[0 1 2 ...]" :ms 0.1 :value big :out (apply str (repeat n "x"))}
                     r2 {:handle 'result/e2 :text "boom" :ex {:seon.error/type :seon.agent/eval-error :seon.error/message "boom" :data {:v big}} :err (apply str (repeat n "y"))}
                     [st _] (step #'root/api-proc st0 :back {:op :status :agent :a3 :set {:evals-add [r1] :status :idle}})
                     [st _] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:evals-add [r2] :status :idle}})
                     evals (get-in st [:view :agents :a3 :evals])]
                 (is (= '[result/e1 result/e2] (mapv :handle evals)) "each eval's handle, in order")
                 (is (= "boom" (get-in evals [1 :ex :seon.error/message])))
                 (count (pr-str evals))))]
    (is (= (held 10) (held 100000)) "bytes held do not grow with what the evals returned")))

(deftest an-agent-that-ended-is-answered-ended-one-never-had-no-agent
  ;; bin/check e2e-calc (2026-10-23): root's answer says whether the agent ended (its view
  ;; records :ended-ms: a kill, its run's end, a death) or root never had it, so a reader
  ;; (seon.prove) tells a cancel from a defect by the type alone
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {:a7 {:status :killed :ended-ms 1}}} :graph (promise)
                             :system (doto (promise) (deliver {:procs {} :conns []}))})
        ask (fn [m] (let [p (promise)] (step #'root/api-proc st :ext (assoc m :p p)) @p))]
    (doseq [op [:eval :tests :stats :merge-main]]
      (is (= [:seon.root/ended :a7] ((juxt :seon.error/type :agent) (ask {:op op :id :a7 :forms "1"}))) (str op " to an agent that ended"))
      (is (= :seon.root/no-agent (:seon.error/type (ask {:op op :id :nope :forms "1"}))) (str op " to one root never had")))
    (is (= :seon.root/ended (:seon.error/type (ask {:op :clone :parent :a7}))) "a clone of an agent that ended")
    (testing "a request in flight when root stopped its agent is answered ended"
      (let [p (promise)
            st (-> st (assoc-in [:agents :a1] {:branch :b1}) (assoc-in [:view :agents :a1] {:status :killed}))
            [st rid] (#'root/pend st p :eval :a1)
            st (#'root/end-agent st (volatile! {}) {:agent :a1 :inflight [rid]})]
        (is (= [:seon.root/ended :a1] ((juxt :seon.error/type :agent) @p)))
        (is (get-in st [:view :agents :a1 :ended-ms]) "and its view records the end")))
    (is (= :seon.root/ended (:seon.error/type (root/request 'merge-main! :a7 nil {:status :killed :ended-ms 1}))) "request's merge-main! to an agent that ended")))

(def ^:private fake-members
  "Protocol members a refused answer must not carry: an eval item's, a test count's, a merge's."
  #{:id :tag :ok :merged? :pass :fail :error :reports :applied :conflicts})

(deftest a-refused-answer-is-the-one-error-map
  ;; a request no live agent can take, a function root refuses, and a write the writer refuses
  ;; each answer the one error map (seon.contract/error) and nothing beside it: no eval item
  ;; with id -1, no test count, no :ok or :merged? false
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        bare? (fn [r] (and (map? r) (seon.contract/error? r) (empty? (filter fake-members (keys r)))))]
    (doseq [op [:eval :tests :merge-main :stats]]
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
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
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
  ;; main already read (:main-db): after its first read, main comes in the writer's commits
  (let [st {:events-ch (a/chan 8) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events []
            :views {} :zoom {} :merges 0 :main {:t 1} :main-db (ts/test-db)}
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
  (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                    :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))}))

(defn- answer "What p was answered, or :unanswered." [p] (if (realized? p) @p :unanswered))

(deftest a-proc-defect-names-where-it-threw
  ;; the standing root's worst open problem: the reconciler's "No matching clause: " 255 times, a
  ;; case meeting nil, with nothing saying in which function or on what message
  (let [ex (try (case (:op {}) :a 1) (catch Exception x x))
        m (#'root/flow-error {:clojure.core.async.flow/pid :reconciler :clojure.core.async.flow/ex ex
                              :clojure.core.async.flow/msg {:tag 7 :r nil}})
        p (promise)
        [_ outs] (step #'root/api-proc (api-state) :back (assoc m :msg {:op :state :p p}))]
    (is (= [{:type :proc-defect :pid :reconciler :message (ex-message ex)}] (map #(dissoc % :at-ms) (:events outs)))
        "an event, so a wait on root's events for what the proc owed ends (bin/check robust waited for good)")
    (is (= "seon.procs-test" (some-> (:in m) namespace)) (str "the fn it threw in: " (pr-str (:in m))))
    (is (= (:in m) (:seon.error/in (answer p))) "on the defect")
    (is (= :state (:took (answer p))) "with what its step took")))

(deftest a-throwing-step-answers-every-pending-request
  ;; Flow's step catch keeps the old state and drops the outputs; its error message carries the
  ;; message the step took (::flow/msg), which the pipe from Flow's error channel carries as :msg
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

(defn- spawning-api
  "(f st) on an api state that can spawn: its main a branch of the test store, its graph a stub
  that takes the update! a spawn makes (the agent's proc), so the api's step runs alone."
  [f]
  (ts/test-branch! (fn [_ conn]
    (f (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 8) :conn conn
                         :view {:agents {}} :system (doto (promise) (deliver {:procs {} :conns []}))
                         :graph (doto (promise) (deliver (reify clojure.core.async.flow.impl.graph/Update (update-flow [_ _] {}))))})))))

(deftest a-spawn-is-answered-at-its-agents-ready
  ;; task seon.root/a-spawn-answers-at-its-agents-ready: never at once, while the agent is
  ;; :spawning, but at its hello, so what is sent to it next reaches a ready agent
  (spawning-api (fn [st]
    (let [p (promise)
          [st _] (step #'root/api-proc st :ext {:op :spawn :opts {} :p p})]
      (is (= :unanswered (answer p)) "spawning: not answered")
      (let [[st _] (step #'root/api-proc st :back {:op :status :agent :a1 :set {:forked-ms 1.0 :mem-mb 1.0 :t 1}})]
        (is (= :unanswered (answer p)) "a status that is not its hello: still not")
        (step #'root/api-proc st :back {:op :status :agent :a1 :set {:ready true}})
        (is (= {:id :a1} (answer p)) "its hello answers it"))))))

(deftest a-spawn-whose-agent-dies-first-answers-the-death
  ;; the end the agent met before its hello is the answer: died, with the exit status the fork
  ;; server reaped (the process's exit, never a timeout); stopped first, the stop
  (spawning-api (fn [st]
    (let [p (promise)
          [st _] (step #'root/api-proc st :ext {:op :spawn :opts {} :p p})
          [st _] (step #'root/api-proc st :from-fs {:ev :forked :agent :a1 :pid 4242 :fork-ms 1.0})
          [st _] (step #'root/api-proc st :back {:op :ended :agent :a1 :pid 4242 :inflight []})]
      (is (= :unanswered (answer p)) "its death waits for the exit status")
      (let [[st _] (step #'root/api-proc st :from-fs {:ev :exit :pid 4242 :status {:exit 3}})
            first-answer (answer p)]
        (is (= :seon.agent/died (:seon.error/type first-answer)))
        (is (= {:exit 3} (:exit first-answer)) "with the status it exited with")
        (is (= :a1 (:agent first-answer)))
        ;; exactly one answer: the spawn's entry is gone once answered, so a later hello or end
        ;; has no caller to answer (a promise ignores a second delivery, so the entry is the proof)
        (is (not (contains? (:seon.root/spawning st) :a1)) "answered: no spawn is held for the agent")
        (let [[st _] (step #'root/api-proc st :back {:op :status :agent :a1 :set {:ready true}})]
          (is (not (contains? (:seon.root/spawning st) :a1)))
          (is (= first-answer (answer p))))))))
  (spawning-api (fn [st]
    (let [p (promise)
          [st _] (step #'root/api-proc st :ext {:op :spawn :opts {} :p p})]
      (step #'root/api-proc st :back {:op :ended :agent :a1 :pid nil :inflight []})
      (is (= :seon.agent/died (:seon.error/type (answer p))) "never forked: its death at once, no status to wait for")))))

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
  (ts/test-branch! (fn [cfg conn] (let [st (#'server/server-step {:db @conn :cfg cfg :store conn :conn nil})
        stored (fn [rep] {:op :tx :t (:max-tx @conn) :commit (d/commit-id @conn) :ns 0 :tx-data (pdb/report->tx rep)})]
    (testing "a main commit is read into its replica from the store"
      (let [m (stored (d/transact conn [{:note/text "z"}]))
            [st outs] (step #'server/server-step st :cmd m)]
        (is (= (:max-tx @conn) (:max-tx (:db st))))
        (is (= [:applied] (map :ev (:ev outs))))
        (testing "and a commit it already holds is skipped"
          (is (= [st nil] (#'server/server-step st :cmd m))))
        (testing "the replica's transaction holds main's transaction facts (its instant, its tx-meta), as stored"
          (let [m2 (stored (d/transact conn {:tx-data [{:note/text "w"}] :tx-meta {:note/text "the commit's own fact"}}))
                t (:t m2)
                [st2 _] (step #'server/server-step st :cmd m2)
                instant (fn [d] (:db/txInstant (d/entity d t)))]
            (is (inst? (instant @conn)) "main's transaction has its instant (history is kept)")
            (is (= (instant @conn) (instant (:db st2))))
            (is (= "the commit's own fact" (:note/text (d/entity (:db st2) t))) "and its tx-meta")))
        (testing "main's reconciler index is held for the db it is of, and a fork observes it forward to main now"
          (let [m3 (stored (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "forked" :seon.task/plan "Make forked true."}])))
                [st3 _] (step #'server/server-step st :cmd m3)
                held (server/index-at-fork st3)]
            (let [s0 (#'server/server-step {:db @conn :cfg cfg :store conn :conn nil})] (is (= (d/commit-id (:db s0)) (get-in s0 [:index :commit])) "made for the commit it holds, never the db"))
            (is (not (contains? (get-in st3 [:index :index :tasks]) "demo/forked")) "a commit leaves it: a fork brings it forward")
            (is (= (d/commit-id (:db st3)) (get-in held [:index :commit])))
            (is (contains? (get-in held [:index :index :tasks]) "demo/forked"))
            (is (= (reconcile/index (:db st3)) (get-in held [:index :index])) "the index listed at that db")
            (is (= (:db st3) (:db (server/index-for-agent held))) "and an agent is handed main's db it is of")
            (testing "and an agent observes its branch from it, read through its own connection"
              (let [ab (pdb/on-main (:branch cfg) :held-index)
                    _ (d/branch! conn (:branch cfg) ab)
                    bc (d/connect (assoc cfg :branch ab))]
                (try
                  (d/transact bc (seon.task/create-tx @bc [{:seon.task/ns 'demo :seon.task/name "branched" :seon.task/plan "Make branched true."}]))
                  (let [{idx :index db :db} (server/index-for-agent held) observed (reconcile/index idx db @bc)]
                    (is (contains? (:tasks observed) "demo/branched"))
                    (is (= (reconcile/index @bc) observed) "the branch's index listed"))
                  (finally (d/release bc)))))))))))))

(deftest no-proc-keeps-a-db-older-than-its-branchs-current
  ;; docs/machine.md#roots-heap, a kept db value is a reference (owner, 2026-10-28: "I don't want us
  ;; carrying around large values for no reason"; 2026-10-29): after a run of main commits, each
  ;; proc that holds a db holds main's current one, and its older points as {:commit :t}. A proc's
  ;; state is walked as data (seon.test-store/stale-dbs); the failure names the proc and the path.
  ;; The agent's state is the same walk (seon.agent-test/an-agent-keeps-no-db-older-than-its-branchs-current).
  (ts/test-branch!
   (fn [cfg conn]
     (let [n 6
           commit! (fn [i] (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan (str "Make t" i " true.")}])))
           ;; each proc is judged against main as it stood when its own run of commits ended
           stale (fn [proc st] (for [[path t] (ts/stale-dbs st (:max-tx @conn))] [proc path t]))
           _ (d/transact conn [{:seon.root/id :root :seon.root/boot "b" :seon.config.agent/max-running 4}])
           ;; the fork server: every commit is a :tx, and a fork or a standby is the only thing that brings its index to main
           fs (reduce (fn [st i]
                        (let [rep (commit! i)
                              [st _] (step #'server/server-step st :cmd {:op :tx :t (:max-tx @conn) :commit (d/commit-id @conn) :ns 0 :tx-data (pdb/report->tx rep)})]
                          st))
                      (#'server/server-step {:db @conn :cfg cfg :store conn :conn nil}) (range n))
           fs-stale (stale :fork-server fs)
           ;; the reconciler: a notice per commit
           rc (reduce (fn [st i]
                        (let [prev (:max-tx @conn) _ (commit! (+ n i))
                              [st _] (step #'reconcile/reconciler-proc st :main (notice conn prev []))]
                          st))
                      (#'reconcile/reconciler-proc {:boot "b" :conn conn :provider {:kind :scripted :script 'seon.fixtures/reply}}) (range n))
           rc-stale (stale :reconciler rc)
           ;; the render proc's main: each commit's db, advanced by what moved
           rp (reduce (fn [st i] (let [_ (commit! (+ n n i))] (#'seon.render.proc/advance-main st (d/commit-as-db conn (d/commit-id @conn)))))
                      {} (range n))]
       (is (= [] fs-stale) "main's index is kept as a commit, not the db it was listed at")
       (is (= [] rc-stale))
       (is (= [] (stale :render rp)))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.procs-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(deftest the-writer-keeps-the-union-current
  ;; seon.union through the writer: built at the first :union, then every main commit and
  ;; every agent's commit notice add their datoms, so one query over its answer sees both
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        main (:branch cfg)
        ab (pdb/on-main main :a1)
        union-of (fn [st] (let [[st outs] (step #'writer/writer-proc st :in {:op :union :dest {:rid 9}})] [st (:r (first (:to-api outs)))]))
        bodies (fn [db] (set (d/q '[:find ?b ?br :where [?m :seon.message/body ?b ?tx] [?tx :seon.tx/branch ?br]] db)))]
    (d/branch! conn main ab)
    (let [[st r] (union-of st)
          _ (is (true? (:ok r)))
          [st _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:seon.message/id "m1" :seon.message/from "root" :seon.message/branch :a1 :seon.message/body "from main"}] :dest {:rid 1}})
          bc (d/connect (assoc cfg :branch ab))
          _ (d/transact bc {:tx-data [{:seon.message/id "r1" :seon.message/from "a1" :seon.message/reply-to "m1" :seon.message/body "from a1"}] :tx-meta (pdb/tx-meta ab)})
          _ (d/release bc)
          [st _] (step #'writer/writer-proc st :in {:op :agent-commit :branch :a1})
          [_ r2] (union-of st)]
      (is (= #{["from main" main] ["from a1" ab]} (bodies (:db r2))) "a main commit and an agent's commit, each under its branch"))))))

(deftest a-fork-server-step-that-throws-answers-with-the-error-and-its-state-is-unchanged
  ;; docs/processes.md#a-step-that-throws: the server's step caught what it threw (a thrown step left the
  ;; process alive and silent, or dead, with every request it took unanswered); the event names the op
  ;; and carries the typed error, a fork's received socket is closed, and root's proc answers the asker
  (let [step-failed? #(= :seon.fork-server/step-failed (get-in % [:error :seon.error/type]))]
    (testing "a step with no db to read answers its stats request the error, its state as it was"
      (let [st {:db nil}
            [st' outs] (#'server/server-step st :cmd {:op :stats})]
        (is (identical? st st'))
        (is (= [:step-failed] (map :ev (:ev outs))))
        (is (step-failed? (first (:ev outs))))
        (is (= :stats (:op (first (:ev outs)))))))
    (testing "a fork that throws before it forks closes the socket that came with it"
      (let [[a b] (sock/socketpair) [fa fpeer] (sock/socketpair)
            conn (sock/conn b)
            _ (sock/send! a {:op :fork :agent :a1} fa)
            m (loop [] (let [f (sock/next-frame! conn)] (if (= :none f) (recur) f)))
            fd (first @(:fds conn))
            st {:db nil :conn conn :graph-moved true}
            [st' outs] (#'server/server-step st :cmd m)]
        (is (some? fd) "a descriptor came with the request")
        (is (identical? st st'))
        (is (= {:ev :step-failed :op :fork :agent :a1} (select-keys (first (:ev outs)) [:ev :op :agent])))
        (is (empty? @(:fds conn)) "taken from the pending descriptors")
        (is (= -1 (seon.lib/c-close fd)) "and closed")
        (sock/close! fa) (sock/close! fpeer) (sock/close! a) (sock/close! b)))
    (testing "root's proc answers the waiter the stats request is, and fails the fork"
      (let [err (#'server/step-failed {:op :stats} (ex-info "boom" {}))
            [st outs] (step #'server/fork-server-proc
                            {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :log []}
                            :ev {:ev :step-failed :op :stats :error (:error err)})]
        (is (= [5] (keep #(get-in % [:dest :rid]) (:to-api outs))))
        (is (step-failed? {:error (:r (first (:to-api outs)))}))
        (is (empty? (:waiters st))))
      (let [[afd apeer] (sock/socketpair)
            err (#'server/step-failed {:op :fork :agent :a1} (ex-info "boom" {}))
            [st outs] (step #'server/fork-server-proc {:waiters clojure.lang.PersistentQueue/EMPTY :pid 1 :log [] :in-flight {:a1 afd}}
                            :ev {:ev :step-failed :op :fork :agent :a1 :error (:error err)})]
        (is (= [:fork-failed :step-failed] (map :ev (:to-api outs))))
        (is (string? (:reason (first (:to-api outs)))))
        (is (empty? (:in-flight st)))
        (is (= -1 (sock/close! afd)) "root's copy of the agent's socket is closed")
        (sock/close! apeer)))))

(deftest the-fork-servers-stop-ends-every-child-before-it-answers
  ;; bin/check lifecycle's :left (docs/processes.md#a-stop-reaps-every-child): root SIGKILLed the fork
  ;; server while an agent that had closed its socket was still exiting, and launchd
  ;; inherited it, alive after stop!. Now the server's stop kills and reaps its children
  ;; before it answers, so root's wait for the server is the end of every one of them.
  (let [p (.start (ProcessBuilder. ^java.util.List ["sleep" "600"]))
        pid (.pid p)]
    (is (zero? (seon.lib/c-kill pid 0)) "the child is running")
    (server/server-step {:children #{pid} :status-buf (jolt.ffi/alloc 4)} ::flow/stop)
    (is (= -1 (seon.lib/c-kill pid 0)) "and gone when the stop answers: killed and reaped")))

(deftest an-agents-memory-alone-moves-no-main
  ;; root wrote :seon.process/mem-mb on every change, so main moved five times under three reads of a
  ;; working agent (seon.server-test/a-later-read-draws-no-page-cold-and-costs-what-the-one-before-did)
  (let [st {:boot "b1" :agents {} :pending {} :rid 0 :view {:agents {:a1 {:status :busy :pid 7 :mem-mb 151.66}}}}
        o (volatile! {})
        writes #(:to-writer @o)
        rec (fn [st before] (vreset! o {}) (#'root/record-agents st o before) (writes))]
    (is (empty? (rec (assoc-in st [:view :agents :a1 :mem-mb] 152.42) (get-in st [:view :agents])))
        "memory alone writes nothing")
    (let [[w] (rec (update-in st [:view :agents :a1] assoc :status :idle :mem-mb 152.42) (get-in st [:view :agents]))]
      (is (some #(= 152.42 (:seon.process/mem-mb %)) (:tx-data w)) "it goes with the agent's next other change"))))
