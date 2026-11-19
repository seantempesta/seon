(ns seon.test-runner
  "The `:test` Flow proc (`:io` workload):
   - Maintains incremental Merkle reach (`:seon.code.form/*` + `:seon.schema/*` + `:platform-tests`).
   - Skips any `deftest` or `gen:<digest>` already `:seon.test/pass? true` in Datahike.
   - Shards uncached items by namespace across isolated ~5 ms COW workers (`<= max-workers`)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.code :as code]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- sync-test-filter [st]
  (if (>= (count (:active-subs st)) (:max-workers st))
    (assoc st ::flow/input-filter #{:db :fork-ev})
    (dissoc st ::flow/input-filter)))

(defn- function-schema? [sch]
  (boolean (schema/generatable-function-schema? sch)))

(defn- build-branch-overlay [conn merge-plan theirs-branch]
  (let [theirs-db (d/branch-as-db (:store @conn) theirs-branch)]
    (reduce
     (fn [acc {:keys [entity status path]}]
       (let [[id-attr id-val] entity]
         (cond
           (and path (code/boot-file-path? path))
           (assoc acc :boot-affected? true)

           (= :seon.code.form/key id-attr)
           (let [acc1 (if (contains? code/boot-root-keys id-val)
                        (assoc acc :boot-affected? true)
                        acc)]
             (if (= :deleted status)
               (update acc1 :retracted-keys conj id-val)
               (if-let [f (d/pull theirs-db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                              :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                                              :seon.code.form/test?]
                                  [:seon.code.form/key id-val])]
                 (if (:seon.code.form/src f)
                   (update acc1 :upserts conj f)
                   acc1)
                 acc1)))

           (= :seon.schema/key id-attr)
           (if (= :deleted status)
             (update acc :retracted-schemas conj id-val)
             (if-let [s (d/pull theirs-db '[:seon.schema/key :seon.schema/form] [:seon.schema/key id-val])]
               (if (some? (:seon.schema/form s))
                 (update acc :schema-upserts conj s)
                 acc)
               acc))

           :else acc)))
     {:upserts [] :retracted-keys #{} :schema-upserts [] :retracted-schemas #{} :boot-affected? false}
     (:entity-diffs merge-plan))))

(defn- apply-overlay-to-reach-graph [theirs-db graph {:keys [upserts retracted-keys schema-upserts retracted-schemas]}]
  (if (and (empty? upserts) (empty? retracted-keys) (empty? schema-upserts) (empty? retracted-schemas))
    graph
    (let [retracted-node-keys (into (set retracted-keys) (map code/schema-kw->node-key) retracted-schemas)
          nodes1 (apply dissoc (:nodes graph) retracted-node-keys)
          nodes1b (reduce
                   (fn [ns {:keys [:seon.schema/key :seon.schema/form]}]
                     (let [nk (code/schema-kw->node-key key)]
                       (assoc ns nk {:hash (-> (str (hasch/uuid [key (code/clean-edn-for-hash form)]))
                                               (str/replace "-" "")
                                               (subs 0 16))
                                     :deps #{}
                                     :rdeps #{}
                                     :test? false})))
                   nodes1 schema-upserts)
          nodes2 (reduce
                  (fn [ns f]
                    (assoc ns (:seon.code.form/key f)
                           {:hash (:seon.code.form/hash f)
                            :deps (into #{} (map code/dep-sym->key) (:seon.code.form/deps f))
                            :rdeps #{}
                            :test? (boolean (:seon.code.form/test? f))}))
                  nodes1b upserts)
          nodes3 (reduce-kv
                  (fn [acc k {:keys [deps]}]
                    (reduce (fn [a dep-k]
                              (if (contains? a dep-k)
                                (update-in a [dep-k :rdeps] conj k)
                                a))
                            acc deps))
                  nodes2 nodes2)
          memo (volatile! {})
          compute (fn compute [k visiting]
                    (if-let [d (get @memo k)]
                      d
                      (if (contains? visiting k)
                        (get-in nodes3 [k :hash] "cycle")
                        (if-let [n (get nodes3 k)]
                          (let [v' (conj visiting k)
                                ds (mapv #(compute % v') (sort (filter #(contains? nodes3 %) (:deps n))))
                                d (-> (str (hasch/uuid [k (:hash n) ds]))
                                      (str/replace "-" "")
                                      (subs 0 16))]
                            (vswap! memo assoc k d)
                            d)
                          "external"))))]
      (doseq [k (keys nodes3)] (compute k #{}))
      (let [digests @memo
            contracted' (reduce
                         (fn [cv f]
                           (let [fk (:seon.code.form/key f)
                                 v-eids (when theirs-db (map :e (d/datoms theirs-db :avet :seon.code.var/form fk)))
                                 vs (map #(d/pull theirs-db '[:seon.code.var/name :seon.code.var/schema] %) v-eids)
                                 cv1 (into {} (remove #(= fk (val %)) cv))]
                             (reduce (fn [a v]
                                       (if (and (:seon.code.var/name v) (function-schema? (:seon.code.var/schema v)))
                                         (assoc a (:seon.code.var/name v) fk)
                                         a))
                                     cv1 vs)))
                         (into {} (remove #(contains? retracted-keys (val %)) (:contracted-vars graph)))
                         upserts)]
        {:nodes nodes3
         :digests digests
         :tests (into {}
                      (keep (fn [[k n]] (when (:test? n) [(symbol k) (get digests k)])))
                      nodes3)
         :contracted-vars contracted'
         :platform-tests (:platform-tests graph #{})}))))

(defn- last-known-ms [db var-sym]
  (or (some->> (d/datoms db :avet :seon.test/var var-sym)
               (keep #(:seon.test/ms (d/pull db '[:seon.test/ms] (:e %))))
               last)
      10.0))

(defn- build-namespace-subs [parent-id tier-tag cfg branch overlay uncached-tests uncached-gens samples seed]
  (let [all-ns (->> (concat (map :ns uncached-tests) (map :ns uncached-gens))
                    distinct
                    sort
                    vec)]
    (vec
     (map-indexed
      (fn [idx nsym]
        {:sub-id (str parent-id "-" tier-tag "-" idx)
         :parent-id parent-id
         :cfg cfg
         :branch branch
         :overlay (when overlay (select-keys overlay [:upserts :retracted-keys]))
         :test-items (->> uncached-tests (filter #(= nsym (:ns %))) (mapv #(dissoc % :ns)))
         :gen-items (->> uncached-gens (filter #(= nsym (:ns %))) (mapv #(dissoc % :ns)))
         :samples samples
         :seed seed})
      all-ns))))

(defn- cached-green? [db digest]
  (true? (:seon.test/pass? (d/pull db '[:seon.test/pass?] [:seon.test/digest digest]))))

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

(defn test-proc
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :fork-out "Channel to 1-thread System Fork Parent"
                :fork-test-in "Test worker events from Fork Parent"
                :max-workers "Max concurrent forked clojure.test processes"}
       :ins {:db "Commit stream from :transactor"
             :batch "Test requests {:branch :agent-id :branch-tx :vars :force? :reply-ch}"}
       :outs {:tx "Test results & failure feedback messages to :transactor"}
       :workload :io
       :doc "Reach-based per-namespace isolated clojure.test & seed=42 Malli generative runner."
       :ping-map-fn (fn [st]
                      {:tracked-tests (count (get-in st [:reach :tests]))
                       :tracked-contracts (count (get-in st [:reach :contracted-vars]))
                       :platform-tests (count (get-in st [:reach :platform-tests]))
                       :active-workers (count (:active-subs st))
                       :queued-shards (count (:sub-queue st))
                       :max-workers (:max-workers st)
                       :backpressuring? (contains? st ::flow/input-filter)
                       :batches-completed (:completed st)
                       :cached-hits (:cached-hits st)})})
  ([{:keys [conn cfg fork-out fork-test-in max-workers]}]
   {:conn conn
    :cfg cfg
    :fork-out fork-out
    :max-workers (or max-workers 4)
    :reach (code/build-reach-graph @conn)
    :seq 0
    :sub-queue []
    :active-subs {}
    :parents {}
    :completed 0
    :cached-hits 0
    ::flow/in-ports {:fork-ev fork-test-in}})
  ([st _] st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :db
       (let [reach' (code/update-reach-graph (:reach st) (:conn st))]
         [(assoc st :reach reach') nil])

       :batch
       (let [{:keys [branch agent-id branch-tx vars force? reply-ch]} msg
             conn (:conn st)
             st (assoc st :reach (code/update-reach-graph (:reach st) conn))
             db @conn
             b (or branch :main)
             merge-plan (when (not= b :main)
                          (merge/three-way conn (:cfg st) b))]
         (if (and merge-plan (not (:ok? merge-plan)))
           (do
             (reply! reply-ch {:ok? false :conflicts (:conflicts merge-plan)})
             [st (cond-> {::flow/report [(str "[:test] merge conflict blocked test on " b)]}
                   agent-id
                   (assoc :tx [{:op :send-message
                                :from :test
                                :to agent-id
                                :type :merge-conflict
                                :body (str "3-way merge conflict with :main on: "
                                           (pr-str (:conflicts merge-plan)))}]))])
           (let [theirs-db (if (= b :main) db (d/branch-as-db (:store db) b))
                 branch-cid (d/commit-id theirs-db)
                 overlay (when merge-plan (build-branch-overlay conn merge-plan b))
                 cand-reach (if overlay
                              (apply-overlay-to-reach-graph theirs-db (:reach st) overlay)
                              (:reach st))
                 deleted-main-tests (when (and overlay (empty? vars))
                                      (vec (sort (remove #(contains? (:tests cand-reach) %)
                                                         (keys (get-in st [:reach :tests]))))))
                 all-tests (:tests cand-reach)
                 target-tests (if (seq vars)
                                (select-keys all-tests vars)
                                all-tests)
                 uncached-tests (vec
                                 (keep (fn [[sym dg]]
                                         (when (or force? (not (cached-green? db dg)))
                                           {:var sym :digest dg :ns (symbol (namespace sym))
                                            :est-ms (last-known-ms db sym)}))
                                       (sort-by first target-tests)))
                 contracted-map (if (seq vars) {} (:contracted-vars cand-reach))
                 uncached-gens (vec
                                (keep (fn [[vsym fk]]
                                        (when-let [dg (get-in cand-reach [:digests fk])]
                                          (let [gdg (str "gen:" dg)]
                                            (when (or force? (not (cached-green? db gdg)))
                                              {:var vsym :digest gdg :ns (symbol (namespace vsym))
                                               :est-ms 5.0}))))
                                      (sort-by first contracted-map)))
                 cached-n (+ (- (count target-tests) (count uncached-tests))
                             (- (count contracted-map) (count uncached-gens)))
                 plat-reached (vec (sort (filter #(contains? (:platform-tests cand-reach) %)
                                                 (map :var uncached-tests))))
                 boot-affected? (boolean (:boot-affected? overlay))]
             (cond
               (seq deleted-main-tests)
               (let [del-results (mapv (fn [sym]
                                         {:seon.test/digest (str "deleted:" sym ":" (subs (str branch-cid) 0 8))
                                          :seon.test/var sym
                                          :seon.test/kind :deftest
                                          :seon.test/pass? false
                                          :seon.test/ms 0.0
                                          :seon.test/ticks 0
                                          :seon.test/bytes 0
                                          :seon.test/report (str "Refused: branch " b " deleted existing :main test " sym
                                                                 ". Accretion requires preserving existing callers & proofs.")})
                                       deleted-main-tests)
                     ans {:ok? false
                          :branch b
                          :ran (count del-results)
                          :cached cached-n
                          :deleted-main-tests deleted-main-tests
                          :results del-results
                          :generative-failures []}
                     tx-ops (cond-> [{:op :record-test-results
                                      :branch b
                                      :commit branch-cid
                                      :results del-results
                                      :ran (count del-results)
                                      :cached cached-n
                                      :reply-ch reply-ch
                                      :reply-val ans}]
                              agent-id
                              (conj {:op :send-message
                                     :from :test
                                     :to agent-id
                                     :type :test-failure
                                     :body (str "Refused: deleted existing :main tests " (pr-str deleted-main-tests))}))]
                 [st {:tx tx-ops
                      ::flow/report [(str "[:test] REFUSED " b ": deleted :main tests " (pr-str deleted-main-tests))]}])

               (and (empty? uncached-tests) (empty? uncached-gens))
               (let [ans {:ok? true
                          :branch b
                          :ran 0
                          :cached cached-n
                          :boot-affected? boot-affected?
                          :platform-tests-reached plat-reached
                          :results []}]
                 [(update st :cached-hits + cached-n)
                  {:tx [{:op :record-test-results
                         :branch b
                         :commit branch-cid
                         :results []
                         :ran 0
                         :cached cached-n
                         :agent-id agent-id
                         :tested-tx branch-tx
                         :reply-ch reply-ch
                         :reply-val ans}]
                   ::flow/report [(str "[:test] 100% reach-cache hit on " b " (" cached-n " green)")]}])

               :else
               (let [pid-seq (inc (:seq st))
                     parent-id (str "batch-" pid-seq)
                     samples (store/config-val db agent-id :seon.config/gen-samples)
                     seed (store/config-val db agent-id :seon.config/gen-seed)
                     fast-cap (double (or (store/config-val db agent-id :seon.config/fast-test-ms) 150))
                     fast-tests (filterv #(<= (:est-ms % 10.0) fast-cap) uncached-tests)
                     slow-tests (filterv #(> (:est-ms % 10.0) fast-cap) uncached-tests)
                     fast-subs (build-namespace-subs parent-id "fast" (:cfg st) b overlay fast-tests uncached-gens samples seed)
                     slow-subs (build-namespace-subs parent-id "slow" (:cfg st) b overlay slow-tests [] samples seed)
                     initial-subs (if (seq fast-subs) fast-subs slow-subs)
                     deferred-slow (if (seq fast-subs) slow-subs [])
                     st1 (-> st
                             (assoc :seq pid-seq)
                             (update :cached-hits + cached-n)
                             (assoc-in [:parents parent-id]
                                       {:agent-id agent-id
                                        :branch b
                                        :commit branch-cid
                                        :tested-tx branch-tx
                                        :cached-count cached-n
                                        :boot-affected? boot-affected?
                                        :platform-tests plat-reached
                                        :pending-subs (set (map :sub-id initial-subs))
                                        :deferred-slow deferred-slow
                                        :results []
                                        :gen-failures []
                                        :worker-pids []
                                        :reply-ch reply-ch})
                             (update :sub-queue into initial-subs)
                             drain-sub-queue)]
                 [st1 {::flow/report [(str "[:test] dispatched " parent-id " on " b
                                           " (fast-tier: " (count initial-subs) " fork(s)"
                                           (when (seq deferred-slow)
                                             (str ", slow-tier queued: " (count deferred-slow)))
                                           " · reused " cached-n " cached)")]}])))))

       :fork-ev
       (case (:ev msg)
         :child-frame
         (let [f (:frame msg)
               sub-id (:batch-id f)
               parent-id (get-in st [:active-subs sub-id])]
           (cond
             (nil? parent-id)
             [st nil]

             (= (:ev f) :child-exception)
             (let [pinfo (get-in st [:parents parent-id])
                   err-msg (str (:error f))
                   st1 (-> st
                           (update :active-subs dissoc sub-id)
                           (update :parents dissoc parent-id)
                           (update :sub-queue #(filterv (fn [s] (not= parent-id (:parent-id s))) %))
                           drain-sub-queue)]
               (reply! (:reply-ch pinfo) {:ok? false :error err-msg})
               [st1 (cond-> {::flow/report [(str "[:test] load/compile failure on " (:branch pinfo) ": " err-msg)]}
                      (:agent-id pinfo)
                      (assoc :tx [{:op :send-message
                                   :from :test
                                   :to (:agent-id pinfo)
                                   :type :test-failure
                                   :body (str "Branch failed to load in isolated test worker: " err-msg
                                              "\nFix the missing namespace `:require` or unresolved var on your branch, verify it, and call (user/ready!) again.")}])
                      (not (:agent-id pinfo))
                      (assoc ::flow/error [{:proc :test :batch-id sub-id :error err-msg}]))])

             (= (:ev f) :test-batch-done)
             (let [pinfo (-> (get-in st [:parents parent-id])
                             (update :pending-subs disj sub-id)
                             (update :results into (:results f))
                             (update :gen-failures into (:generative-failures f))
                             (update :worker-pids conj (:worker-pid f)))
                   st1 (-> st
                           (update :active-subs dissoc sub-id)
                           (assoc-in [:parents parent-id] pinfo)
                           drain-sub-queue)]
               (cond
                 (seq (:pending-subs pinfo))
                 [st1 nil]

                 ;; Tier-1 fast tests ALL passed and Tier-2 slower shards are queued -> dispatch Tier 2!
                 (and (every? :seon.test/pass? (:results pinfo))
                      (empty? (:gen-failures pinfo))
                      (seq (:deferred-slow pinfo)))
                 (let [slow (:deferred-slow pinfo)
                       pinfo' (-> pinfo
                                  (assoc :pending-subs (set (map :sub-id slow))
                                         :deferred-slow []))
                       st2 (-> st1
                               (assoc-in [:parents parent-id] pinfo')
                               (update :sub-queue into slow)
                               drain-sub-queue)]
                   [st2 {::flow/report [(str "[:test] fast tier green on " (:branch pinfo)
                                             "; running " (count slow) " slow shard(s)")]}])

                 :else
                 (let [results (:results pinfo)
                       gen-fails (:gen-failures pinfo)
                       all-pass? (and (every? :seon.test/pass? results) (empty? gen-fails))
                       short-circuited? (and (not all-pass?) (boolean (seq (:deferred-slow pinfo))))
                       failures (filterv (complement :seon.test/pass?) results)
                       ans {:ok? all-pass?
                            :branch (:branch pinfo)
                            :worker-pids (:worker-pids pinfo)
                            :ran (count results)
                            :cached (:cached-count pinfo 0)
                            :short-circuited? short-circuited?
                            :skipped-slow (if short-circuited? (count (:deferred-slow pinfo)) 0)
                            :boot-affected? (:boot-affected? pinfo false)
                            :platform-tests-reached (:platform-tests pinfo [])
                            :results results
                            :generative-failures gen-fails}
                       fail-summary (when-not all-pass?
                                      (str/join "\n"
                                                (map #(str (:seon.test/var %) " [" (name (:seon.test/kind % :deftest)) "]: "
                                                           (:seon.test/report %))
                                                     failures)))
                       tx-ops (cond-> [{:op :record-test-results
                                        :branch (:branch pinfo)
                                        :commit (:commit pinfo)
                                        :results results
                                        :ran (count results)
                                        :cached (:cached-count pinfo 0)
                                        :agent-id (when all-pass? (:agent-id pinfo))
                                        :tested-tx (when all-pass? (:tested-tx pinfo))
                                        :reply-ch (:reply-ch pinfo)
                                        :reply-val ans}]
                                (and (not all-pass?) (:agent-id pinfo))
                                (conj {:op :send-message
                                       :from :test
                                       :to (:agent-id pinfo)
                                       :type :test-failure
                                       :body (str fail-summary
                                                  (when short-circuited?
                                                    (str "\n(Short-circuited before " (count (:deferred-slow pinfo)) " slow shard(s))")))}))
                       st2 (-> st1
                               (update :parents dissoc parent-id)
                               (update :completed inc))]
                   [st2 {:tx tx-ops
                         ::flow/report [(str "[:test] " (if all-pass? "PASS" (if short-circuited? "FAIL-FAST" "FAIL")) " " parent-id
                                             " (" (count (:worker-pids pinfo)) " fork(s), "
                                             (count results) " ran, " (:cached-count pinfo 0) " cached)")]}])))
             :else [st nil]))

         :child-exited
         (let [sub-id (:batch-id msg)]
           (if-let [parent-id (get-in st [:active-subs sub-id])]
             (let [pinfo (get-in st [:parents parent-id])
                   err-msg (str "Test worker crashed: status " (:raw-status msg))]
               (reply! (:reply-ch pinfo) {:ok? false :error err-msg})
               [(-> st
                    (update :active-subs dissoc sub-id)
                    (update :parents dissoc parent-id)
                    drain-sub-queue)
                (cond-> {::flow/error [{:proc :test :batch-id sub-id :crashed-pid (:pid msg)}]}
                  (:agent-id pinfo)
                  (assoc :tx [{:op :send-message
                               :from :test
                               :to (:agent-id pinfo)
                               :type :test-failure
                               :body (str err-msg "\nInspect your branch changes and call (user/ready!) again.")}]))])
             [st nil]))

         [st nil])))))
