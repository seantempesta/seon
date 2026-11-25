(ns seon.test
  "The `:test` Flow proc (`:io` workload):
   - Maintains incremental Merkle reach (`:seon.code.form/*` + `:seon.schema/*` + `:platform-tests`).
   - Skips any `deftest` or `gen:<digest>` already `:seon.test/pass? true` in Datahike.
   - Shards uncached items by namespace across isolated ~5 ms COW workers (`<= max-workers`)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.merge :as merge]
            [seon.store :as store]
            [seon.code.reach :as reach]
            [seon.test.lpt :as lpt]
            [seon.task.refine :as refine]
            [seon.test.plan :as plan]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- sync-test-filter [st]
  (if (>= (count (:active-subs st)) (:max-workers st))
    (assoc st ::flow/input-filter #{:db :fork-ev})
    (dissoc st ::flow/input-filter)))

(defn ^{:seon.code/reexport 'seon.test.plan/candidate-merged-db} candidate-merged-db [& args] (apply plan/candidate-merged-db args))

(defn ^{:seon.code/reexport 'seon.test.plan/build-branch-overlay} build-branch-overlay [& args] (apply plan/build-branch-overlay args))

(defn- drain-sub-queue [st]
  (loop [cur st]
    (if (or (empty? (:sub-queue cur))
            (>= (count (:active-subs cur)) (:max-workers cur)))
      (sync-test-filter cur)
      (let [sub (first (:sub-queue cur))
            sub-id (:sub-id sub)]
        (a/put! (:fork-out cur) (assoc sub :op :fork-test :batch-id sub-id))
        (recur (-> cur
                   (update :sub-queue #(vec (rest %)))
                   (assoc-in [:active-subs sub-id] (:parent-id sub))))))))

(defn ^{:seon.code/reexport 'seon.test.plan/attach-var-refs} attach-var-refs [& args] (apply plan/attach-var-refs args))

(defn pack-lpt-bins [& args] (apply lpt/pack-lpt-bins args))

(defn- build-namespace-subs [& args] (apply lpt/build-namespace-subs args))

(defn- build-lpt-slow-subs [& args] (apply lpt/build-lpt-slow-subs args))

(defn- test-batch-conflict
  [st b agent-id merge-plan reply-ch]
  (reply! reply-ch {:ok? false :conflicts (:conflicts merge-plan)})
  [st (cond-> {::flow/report [(str "[:test] merge conflict blocked test on " b)]}
        agent-id
        (assoc :tx [{:op :send-message
                     :from :test
                     :to agent-id
                     :type :merge-conflict
                     :body (str "3-way merge conflict with :main on: "
                                (pr-str (:conflicts merge-plan)))}]))])

(defn- test-batch-crash
  [st branch agent-id reply-ch ex]
  (let [emsg (or (.getMessage ex) (str ex))]
    (reply! reply-ch {:ok? false :branch branch :error emsg})
    [st (cond-> {::flow/error [{:proc :test :op :batch :branch branch :error emsg}]}
          agent-id
          (assoc :tx [{:op :send-message :from :test :to agent-id :type :test-failure
                       :body (str "Test batch failed on " branch ": " emsg
                                  "\nInspect your branch and reply concisely when fixed — Seon automatically re-runs tests.")}]))]))

(defn- test-batch-blocked [st b branch-cid deleted-main-tests cached-n agent-id reply-ch]
  (let [del-results (attach-var-refs @(:conn st)
                      (mapv (fn [sym]
                              {:seon.test/digest (str "deleted:" sym ":" (subs (str branch-cid) 0 8))
                               :seon.test/var sym :seon.test/kind :deftest :seon.test/pass? false
                               :seon.test/ms 0.0 :seon.test/ticks 0 :seon.test/bytes 0
                               :seon.test/report (str "Refused: branch " b " deleted existing :main test " sym
                                                      ". Accretion requires preserving existing callers & proofs.")})
                            deleted-main-tests))
        ans {:ok? false :branch b :ran (count del-results) :cached cached-n
             :deleted-main-tests deleted-main-tests :results del-results :generative-failures []}
        tx-ops (cond-> [{:op :record-test-results :branch b :commit branch-cid :results del-results
                         :ran (count del-results) :cached cached-n :reply-ch reply-ch :reply-val ans}]
                 agent-id (conj {:op :send-message :from :test :to agent-id :type :test-failure
                                 :body (str "Refused: deleted existing :main tests " (pr-str deleted-main-tests))}))] 
    [st {:tx tx-ops ::flow/report [(str "[:test] REFUSED " b ": deleted :main tests " (pr-str deleted-main-tests))]}]))

(defn- test-batch-cached [st b branch-cid cached-n boot-affected? plat-reached agent-id branch-tx reply-ch]
  (let [ans {:ok? true :branch b :ran 0 :cached cached-n :boot-affected? boot-affected?
             :platform-tests-reached plat-reached :results []}]
    [(update st :cached-hits + cached-n)
     {:tx [{:op :record-test-results :branch b :commit branch-cid :tested-tx branch-tx :agent-id agent-id
            :results [] :ran 0 :cached cached-n :reply-ch reply-ch :reply-val ans}]
      ::flow/report [(str "[:test] 100% reach-cache hit on " b " (" cached-n " green)")]}]))

(defn- test-batch-dispatch
  [st plan agent-id branch-tx reply-ch]
  (let [{:keys
         [b
          branch-cid
          overlay
          uncached-tests
          uncached-gens
          cached-n
          plat-reached
          boot-affected?]} plan
        db @(:conn st)
        pid-seq (inc (:seq st))
        parent-id (str "batch-" pid-seq)
        samples (store/config-val db agent-id :seon.config/gen-samples)
        seed (store/config-val db agent-id :seon.config/gen-seed)
        fast-cap (double
                   (or
                     (store/config-val
                       db
                       agent-id
                       :seon.config/fast-test-ms)
                     150))
        fast-tests (filterv
                     #(<= (:est-ms % 10.0) fast-cap)
                     uncached-tests)
        slow-tests (filterv
                     #(> (:est-ms % 10.0) fast-cap)
                     uncached-tests)
        fast-subs (build-namespace-subs
                    parent-id
                    "fast"
                    (:cfg st)
                    b
                    overlay
                    fast-tests
                    uncached-gens
                    samples
                    seed)
        slow-subs (build-lpt-slow-subs
                    parent-id
                    "slow"
                    (:cfg st)
                    b
                    overlay
                    slow-tests
                    (:max-workers st)
                    samples
                    seed)
        initial-subs (if (seq fast-subs) fast-subs slow-subs)
        deferred-slow (if (seq fast-subs) slow-subs [])
        st1 (-> st
             (assoc :seq pid-seq)
             (update :cached-hits + cached-n)
             (assoc-in
               [:parents parent-id]
               {:reply-ch reply-ch,
                :boot-affected? boot-affected?, :fork-relative? (:fork-relative? plan false),
                :expected-red-vars (:expected-red-vars plan #{}),
                :deferred-slow deferred-slow,
                :agent-id agent-id,
                :cached-count cached-n,
                :platform-tests plat-reached,
                :gen-failures [],
                :pending-subs (set (map :sub-id initial-subs)),
                :commit branch-cid,
                :tested-tx branch-tx,
                :worker-pids [],
                :branch b,
                :results []})
             (update :sub-queue into initial-subs)
             drain-sub-queue)]
    [st1
     {:clojure.core.async.flow/report
      [(str
         "[:test] dispatched "
         parent-id
         " on "
         b
         " (fast-tier: "
         (count initial-subs)
         " fork(s)"
         (when (seq deferred-slow)
           (str ", slow-tier queued: " (count deferred-slow)))
         " · reused "
         cached-n
         " cached)")]}]))

(defn- test-batch-run
  [st plan agent-id branch-tx reply-ch]
  (let [{:keys
         [b
          branch-cid
          deleted-main-tests
          uncached-tests
          uncached-gens
          cached-n
          plat-reached
          boot-affected?]} plan]
    (cond
      (seq deleted-main-tests) (test-batch-blocked
                                 st
                                 b
                                 branch-cid
                                 deleted-main-tests
                                 cached-n
                                 agent-id
                                 reply-ch)
      (and (empty? uncached-tests) (empty? uncached-gens)) (test-batch-cached
                                                             st
                                                             b
                                                             branch-cid
                                                             cached-n
                                                             boot-affected?
                                                             plat-reached
                                                             agent-id
                                                             branch-tx
                                                             reply-ch)
      :else (test-batch-dispatch st plan agent-id branch-tx reply-ch))))

(defn- test-fork-slow-tier
  [st1 parent-id pinfo]
  (let [slow (:deferred-slow pinfo)
        pinfo' (-> pinfo
                (assoc
                  :pending-subs
                  (set (map :sub-id slow))
                  :deferred-slow
                  []))
        st2 (-> st1
             (assoc-in [:parents parent-id] pinfo')
             (update :sub-queue into slow)
             drain-sub-queue)]
    [st2
     {:clojure.core.async.flow/report
      [(str
         "[:test] fast tier green on "
         (:branch pinfo)
         "; running "
         (count slow)
         " slow shard(s)")]}]))

(defn- test-batch-plan [& args] (apply plan/test-batch-plan args))

(defn- test-batch
  [st msg]
  (let [{:keys [branch agent-id branch-tx vars force? reply-ch]} msg]
    (try
      (let [conn (:conn st)
            st (assoc st :reach (code/update-reach-graph (:reach st) conn))
            db @conn
            b (or branch :main)
            fpt (when (not= b :main) (store/fork-parent-target db b agent-id))]
        (if (and fpt (not (store/branch? conn (:target-branch fpt))))
          (test-batch-conflict st b agent-id
            {:ok? false :conflicts [{:entity [:seon.agent/id (:target-id fpt)] :reason :missing-target-branch
                                     :message (str "Fork parent branch " (:target-branch fpt) " no longer exists")}]} reply-ch)
          (let [tconn (when fpt (store/connect (:cfg st) (:target-branch fpt)))]
            (try
              (let [merge-plan (when (not= b :main)
                                 (if fpt
                                   (merge/three-way tconn (:cfg st) b {:exclude-agent (:agent-id fpt) :include-session-for (:target-id fpt)})
                                   (merge/three-way conn (:cfg st) b)))]
                (if (and merge-plan (not (:ok? merge-plan)))
                  (test-batch-conflict st b agent-id merge-plan reply-ch)
                  (let [runtime-plan (if fpt
                                       (merge/three-way conn (:cfg st) b {:skip-health? true :exclude-agent (:agent-id fpt)})
                                       merge-plan)
                        expected-red (if (and fpt agent-id) (refine/expected-red-proof-vars db (d/branch-as-db (:store db) b) agent-id) #{})
                        plan (assoc (test-batch-plan st conn db b runtime-plan vars force? fpt) :expected-red-vars expected-red)]
                    (test-batch-run st plan agent-id branch-tx reply-ch))))
              (finally (when tconn (d/release tconn)))))))
      (catch Throwable ex
        (test-batch-crash st branch agent-id reply-ch ex)))))

(defn- purge-parent-subs [st parent-id]
  (-> st
      (update :active-subs (fn [m] (into {} (remove #(= parent-id (val %))) m)))
      (update :parents dissoc parent-id)
      (update :sub-queue #(filterv (fn [s] (not= parent-id (:parent-id s))) %))
      drain-sub-queue))

(defn- test-fork-child-exited [st msg]
  (let [sub-id (:batch-id msg)]
    (if-let [parent-id (get-in st [:active-subs sub-id])]
      (if-let [pinfo (get-in st [:parents parent-id])]
        (let [err-msg (str "Test worker crashed: status " (:raw-status msg))
              st1 (purge-parent-subs st parent-id)]
          (reply! (:reply-ch pinfo) {:ok? false :error err-msg})
          [st1 (cond-> {::flow/error [{:proc :test :batch-id sub-id :crashed-pid (:pid msg)}]}
                 (:agent-id pinfo)
                 (assoc :tx [{:op :send-message :from :test :to (:agent-id pinfo) :type :test-failure
                              :body (str err-msg "\nInspect your branch changes and reply concisely when fixed — Seon automatically re-runs tests.")}]))])
        [(-> st (update :active-subs dissoc sub-id) drain-sub-queue) nil])
      [st nil])))

(defn- test-fork-child-exception [st sub-id parent-id error]
  (if-let [pinfo (get-in st [:parents parent-id])]
    (let [err-msg (str error)
          st1 (purge-parent-subs st parent-id)]
      (reply! (:reply-ch pinfo) {:ok? false :error err-msg})
      [st1 (cond-> {::flow/report [(str "[:test] load/compile failure on " (:branch pinfo) ": " err-msg)]}
             (:agent-id pinfo)
             (assoc :tx [{:op :send-message :from :test :to (:agent-id pinfo) :type :test-failure
                          :body (str "Branch failed to load in isolated test worker: " err-msg
                                     "\nFix the missing namespace `:require` or unresolved var on your branch, verify it, and reply concisely when fixed — Seon automatically re-runs tests.")}])
             (not (:agent-id pinfo))
             (assoc ::flow/error [{:proc :test :batch-id sub-id :error err-msg}]))])
    [(-> st (update :active-subs dissoc sub-id) drain-sub-queue) nil]))

(defn- test-fork-refused [st msg]
  (let [sub-id (:batch-id msg)
        pids (if (some? sub-id)
             (if-let [p (or (get-in st [:active-subs sub-id])
                            (when (contains? (:parents st) sub-id) sub-id))]
               [p]
               [])
             (vec (distinct (vals (:active-subs st)))))
        err-msg (str "Test fork refused (code " (:code msg) ")")]
    (if (empty? pids)
      [st {::flow/error [{:proc :test :ev :fork-refused :batch-id sub-id :code (:code msg) :error err-msg}]}]
      (reduce (fn [[s out] parent-id]
                (if-let [pinfo (get-in s [:parents parent-id])]
                  (do (reply! (:reply-ch pinfo) {:ok? false :error err-msg})
                      [(purge-parent-subs s parent-id)
                       (cond-> (update out ::flow/report (fnil conj []) (str "[:test] fork refused on " (:branch pinfo) ": " err-msg))
                         (:agent-id pinfo)
                         (update :tx (fnil conj []) {:op :send-message :from :test :to (:agent-id pinfo) :type :test-failure
                                                     :body (str err-msg "\nReply concisely to retry tests automatically.")})
                         (not (:agent-id pinfo))
                         (update ::flow/error (fnil conj []) {:proc :test :ev :fork-refused :batch-id (or sub-id parent-id) :code (:code msg) :error err-msg}))])
                  [s out]))
              [st {}] pids))))

(defn- reach-relevant-tx? [db tx-data]
  (let [rm (:ref-ident-map db)]
    (boolean
     (some (fn [dt]
             (let [a (:a dt) kw (if (keyword? a) a (get rm a))]
               (when (keyword? kw)
                 (let [ns (str (namespace kw))]
                   (or (str/starts-with? ns "seon.code.") (= ns "seon.schema") (= ns "seon.rule"))))))
           tx-data))))

(defn- result-ok? [expected-red-vars r]
  (or (:seon.test/pass? r)
      (and (= :deftest (:seon.test/kind r :deftest))
           (contains? expected-red-vars (:seon.test/var r)))))

(defn- test-fork-finish
  [st1 parent-id pinfo]
  (let [results (attach-var-refs @(:conn st1) (:results pinfo))
        gen-fails (:gen-failures pinfo)
        all-pass? (and
                  (every? #(result-ok? (:expected-red-vars pinfo #{}) %) results)
                  (empty? gen-fails))
        short-circuited? (and
                           (not all-pass?)
                           (boolean (seq (:deferred-slow pinfo))))
        failures (filterv #(not (result-ok? (:expected-red-vars pinfo #{}) %)) results)
        ans (let [exp-red (:expected-red-vars pinfo #{})
                exp-hit (vec (sort (keep #(when (and (not (:seon.test/pass? %)) (result-ok? exp-red %)) (:seon.test/var %)) results)))]
            (cond-> {:ok? all-pass?,
                     :cached (:cached-count pinfo 0),
                     :short-circuited? short-circuited?,
                     :boot-affected? (:boot-affected? pinfo false),
                     :skipped-slow (if short-circuited? (count (:deferred-slow pinfo)) 0),
                     :generative-failures gen-fails,
                     :worker-pids (:worker-pids pinfo),
                     :branch (:branch pinfo),
                     :platform-tests-reached (:platform-tests pinfo []),
                     :ran (count results),
                     :results results}
              (seq exp-hit) (assoc :expected-red exp-hit)))
        fail-summary (when-not all-pass?
                       (str/join
                         "\n"
                         (map
                           #(str
                             (:seon.test/var %)
                             " ["
                             (name (:seon.test/kind % :deftest))
                             "]: "
                             (:seon.test/report %))
                           failures)))
        tx-ops (cond->
                 [{:reply-ch (:reply-ch pinfo),
                  :cached (:cached-count pinfo 0),
                  :agent-id (when all-pass? (:agent-id pinfo)),
                  :reply-val ans,
                  :op :record-test-results,
                  :commit (:commit pinfo),
                  :tested-tx (when all-pass? (:tested-tx pinfo)),
                  :branch (:branch pinfo),
                  :ran (count results),
                  :results (if (:fork-relative? pinfo) [] results)}]
                 (and (not all-pass?) (:agent-id pinfo))
                 (conj
                   {:op :send-message,
                    :from :test,
                    :to (:agent-id pinfo),
                    :type :test-failure,
                    :body
                    (str
                      fail-summary
                      (when short-circuited?
                        (str
                          "\n(Short-circuited before "
                          (count (:deferred-slow pinfo))
                          " slow shard(s))")))}))
        st2 (-> st1
             (update :parents dissoc parent-id)
             (update :completed inc))]
    [st2
     {:tx tx-ops,
      :clojure.core.async.flow/report
      [(str
         "[:test] "
         (if all-pass? "PASS" (if short-circuited? "FAIL-FAST" "FAIL"))
         " "
         parent-id
         " ("
         (count (:worker-pids pinfo))
         " fork(s), "
         (count results)
         " ran, "
         (:cached-count pinfo 0)
         " cached)")]}]))

(defn- test-fork-batch-done [st sub-id parent-id f]
  (if-let [p0 (get-in st [:parents parent-id])]
    (let [pinfo (-> p0
                    (update :pending-subs disj sub-id)
                    (update :results into (:results f))
                    (update :gen-failures into (:generative-failures f))
                    (update :worker-pids conj (:worker-pid f)))
          st1 (-> st (update :active-subs dissoc sub-id) (assoc-in [:parents parent-id] pinfo) drain-sub-queue)]
      (cond
        (seq (:pending-subs pinfo)) [st1 nil]
        (and (every? #(result-ok? (:expected-red-vars pinfo) %) (:results pinfo)) (empty? (:gen-failures pinfo)) (seq (:deferred-slow pinfo)))
        (test-fork-slow-tier st1 parent-id pinfo)
        :else (test-fork-finish st1 parent-id pinfo)))
    [(-> st (update :active-subs dissoc sub-id) drain-sub-queue) nil]))

(defn- test-fork-child-frame
  [st f]
  (let [sub-id (:batch-id f)
        parent-id (get-in st [:active-subs sub-id])]
    (cond
      (nil? parent-id) [st nil]
      (= (:ev f) :child-exception) (test-fork-child-exception
                                     st
                                     sub-id
                                     parent-id
                                     (:error f))
      (= (:ev f) :test-batch-done) (test-fork-batch-done
                                     st
                                     sub-id
                                     parent-id
                                     f)
      :else [st nil])))

(defn- test-fork-ev [st msg]
  (case (:ev msg)
    :child-frame (test-fork-child-frame st (:frame msg))
    :child-exited (test-fork-child-exited st msg)
    :fork-refused (test-fork-refused st msg)
    [st nil]))

(defn test-proc
  {:malli/schema
   [:function
    [:=> [:cat] :any]
    [:=> [:cat :any] :any]
    [:=> [:cat :any :any] :any]
    [:=> [:cat :any :any :any] :any]]}
  ([]
    {:params
     {:conn "Read access to store via :main conn",
      :cfg "Pure store cfg",
      :fork-out "Channel to 1-thread System Fork Parent",
      :fork-test-in "Test worker events from Fork Parent",
      :max-workers "Max concurrent forked clojure.test processes"},
     :ins
     {:db "Commit stream from :transactor",
      :batch
      "Test requests {:branch :agent-id :branch-tx :vars :force? :reply-ch}"},
     :outs
     {:tx "Test results & failure feedback messages to :transactor"},
     :workload :io,
     :doc
     "Reach-based per-namespace isolated clojure.test & seed=42 Malli generative runner.",
     :ping-map-fn
     (fn [st]
       {:queued-shards (count (:sub-queue st)),
        :platform-tests (count (get-in st [:reach :platform-tests])),
        :tracked-contracts
        (count (get-in st [:reach :contracted-vars])),
        :max-workers (:max-workers st),
        :batches-completed (:completed st),
        :cached-hits (:cached-hits st),
        :tracked-tests (count (get-in st [:reach :tests])),
        :active-workers (count (:active-subs st)),
        :backpressuring?
        (contains? st :clojure.core.async.flow/input-filter)})})
  ([{:keys [conn cfg fork-out fork-test-in max-workers]}]
    {:cfg cfg,
     :active-subs {},
     :sub-queue [],
     :reach (code/build-reach-graph @conn),
     :completed 0,
     :max-workers (or max-workers 4),
     :cached-hits 0,
     :parents {},
     :clojure.core.async.flow/in-ports {:fork-ev fork-test-in},
     :seq 0,
     :fork-out fork-out,
     :conn conn})
  ([st _] st)
  ([st cid msg]
    (if (nil? msg)
      [st nil]
      (case
        cid
        :db (let [c (:conn st) tds (:tx-data msg) reach' (if (and (:commit msg) (seq tds) (not (reach-relevant-tx? @c tds))) (assoc (:reach st) :commit (:commit msg)) (code/update-reach-graph (:reach st) c))] [(assoc st :reach reach') nil])
        :batch
        (test-batch st msg)
        :fork-ev
        (test-fork-ev st msg)
        [st nil]))))