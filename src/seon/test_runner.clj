(ns seon.test-runner
  "The `:test` Flow proc (`:io` workload):
   - Maintains the incremental Merkle reach graph over `:main` in O(|Delta| + |callers|).
   - Evaluates candidate branches by computing their 3-way merge overlay over `:main` (zero temp DB branches!).
   - Skips any `deftest` whose `:test/digest` is already `:test/pass? true` in Datahike.
   - Runs uncached `clojure.test` vars + Malli generative checks inside clean 5 ms COW forked workers,
     capped by `::flow/input-filter` backpressure.
   - On failure, sends a `:test-failure` message to the branch's agent so it wakes and fixes the red test!"
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.merge :as merge]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- sync-test-filter [st]
  (if (>= (count (:in-flight st)) (:max-workers st))
    (assoc st ::flow/input-filter #{:db :fork-ev})
    (dissoc st ::flow/input-filter)))

(defn- overlay-from-tx-data [tx-data]
  (reduce
   (fn [acc op]
     (cond
       (and (map? op) (:form/key op) (:form/src op))
       (update acc :upserts conj op)

       (and (vector? op) (= :db/retractEntity (first op))
            (vector? (second op)) (= :form/key (first (second op))))
       (update acc :retracted-keys conj (second (second op)))

       :else acc))
   {:upserts [] :retracted-keys #{}}
   tx-data))

(defn- apply-overlay-to-reach-graph [graph {:keys [upserts retracted-keys]}]
  (if (and (empty? upserts) (empty? retracted-keys))
    graph
    (let [nodes0 (:nodes graph)
          nodes1 (apply dissoc nodes0 retracted-keys)
          nodes2 (reduce
                  (fn [ns f]
                    (assoc ns (:form/key f)
                           {:hash (:form/hash f)
                            :deps (into #{} (map code/dep-sym->key) (:form/deps f))
                            :rdeps #{}
                            :test? (boolean (:form/test? f))}))
                  nodes1 upserts)
          ;; Recompute digests over the candidate map cleanly
          memo (volatile! {})
          compute (fn compute [k visiting]
                    (if-let [d (get @memo k)]
                      d
                      (if (contains? visiting k)
                        (get-in nodes2 [k :hash] "cycle")
                        (if-let [n (get nodes2 k)]
                          (let [v' (conj visiting k)
                                ds (mapv #(compute % v') (sort (filter #(contains? nodes2 %) (:deps n))))
                                d (-> (str (hasch.core/uuid [k (:hash n) ds]))
                                      (str/replace "-" "")
                                      (subs 0 16))]
                            (vswap! memo assoc k d)
                            d)
                          "external"))))]
      (doseq [k (keys nodes2)] (compute k #{}))
      (let [digests @memo]
        {:nodes nodes2
         :digests digests
         :tests (into {}
                      (keep (fn [[k n]] (when (:test? n) [(symbol k) (get digests k)])))
                      nodes2)}))))

(defn- cached-green? [db digest]
  (true? (:test/pass? (d/pull db '[:test/pass?] [:test/digest digest]))))

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
       :doc "Reach-based clojure.test & Malli generative test runner inside Seon."
       :ping-map-fn (fn [st]
                      {:tracked-tests (count (get-in st [:reach :tests]))
                       :active-batches (count (:in-flight st))
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
    :in-flight {} ; {batch-id {:agent-id :branch :tested-tx :cached-count :reply-ch}}
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
                                           (pr-str (mapv :entity (:conflicts merge-plan))))}]))])
           (let [overlay (when merge-plan (overlay-from-tx-data (:tx-data merge-plan)))
                 cand-reach (if overlay
                              (apply-overlay-to-reach-graph (:reach st) overlay)
                              (:reach st))
                 all-tests (:tests cand-reach)
                 target-tests (if (seq vars)
                                (select-keys all-tests vars)
                                all-tests)
                 uncached (vec
                           (keep (fn [[sym dg]]
                                   (when (or force? (not (cached-green? db dg)))
                                     {:var sym :digest dg}))
                                 (sort-by first target-tests)))
                 cached-n (- (count target-tests) (count uncached))]
             (if (empty? uncached)
               ;; 100% cache hit in Datahike! Zero OS forks needed!
               (let [ans {:ok? true :branch b :ran 0 :cached cached-n :results []}]
                 (reply! reply-ch ans)
                 [(update st :cached-hits + cached-n)
                  (cond-> {::flow/report [(str "[:test] 100% reach-cache hit on " b " (" cached-n " tests green)")]}
                    (and agent-id branch-tx)
                    (assoc :tx [{:op :record-test-results
                                 :results []
                                 :agent-id agent-id
                                 :tested-tx branch-tx}]))])
               (let [bid (str "test-" (inc (:seq st)))
                     malli-vars (vec
                                 (keep (fn [f]
                                         (let [[ns-s v-s] (str/split (:form/key f) #"/" 2)]
                                           (when (and ns-s v-s (not (:form/test? f)) (not (str/includes? v-s "#")))
                                             (symbol ns-s v-s))))
                                       (:upserts overlay)))
                     _ (a/put! (:fork-out st)
                               {:op :fork-test
                                :batch-id bid
                                :overlay overlay
                                :test-items uncached
                                :malli-vars malli-vars})
                     st' (-> st
                             (update :seq inc)
                             (update :cached-hits + cached-n)
                             (assoc-in [:in-flight bid] {:agent-id agent-id
                                                         :branch b
                                                         :tested-tx branch-tx
                                                         :cached-count cached-n
                                                         :reply-ch reply-ch})
                             sync-test-filter)]
                 [st' {::flow/report [(str "[:test] forked " bid " on " b ": running "
                                           (count uncached) " changed test(s), reused " cached-n " cached")]}])))))

       :fork-ev
       (case (:ev msg)
         :child-frame
         (let [f (:frame msg)]
           (cond
             (= (:ev f) :child-exception)
             (let [bid (:batch-id f)
                   info (get-in st [:in-flight bid])]
               (reply! (:reply-ch info) {:ok? false :error (:error f)})
               [(sync-test-filter (update st :in-flight dissoc bid))
                {::flow/error [{:proc :test :batch-id bid :error (:error f)}]}])

             (= (:ev f) :test-batch-done)
             (let [bid (:batch-id f)
                   info (get-in st [:in-flight bid])
                   results (:results f)
                   gen-fails (:generative-failures f)
                   all-pass? (and (every? :test/pass? results) (empty? gen-fails))
                   failures (filterv (complement :test/pass?) results)
                   ans {:ok? all-pass?
                        :branch (:branch info)
                        :worker-pid (:worker-pid f)
                        :ran (count results)
                        :cached (:cached-count info 0)
                        :results results
                        :generative-failures gen-fails}
                   _ (reply! (:reply-ch info) ans)
                   fail-summary (when-not all-pass?
                                  (str/join "\n"
                                            (concat (map #(str (:test/var %) ": " (:test/report %)) failures)
                                                    (map #(str "Generative schema failure on " (:var %) ": " (pr-str %)) gen-fails))))
                   tx-ops (cond-> [{:op :record-test-results
                                    :results results
                                    :agent-id (when all-pass? (:agent-id info))
                                    :tested-tx (when all-pass? (:tested-tx info))}]
                            (and (not all-pass?) (:agent-id info))
                            (conj {:op :send-message
                                   :from :test
                                   :to (:agent-id info)
                                   :type :test-failure
                                   :body fail-summary}))
                   st' (-> st
                           (update :in-flight dissoc bid)
                           (update :completed inc)
                           sync-test-filter)]
               [st' {:tx tx-ops
                     ::flow/report [(str "[:test] " (if all-pass? "PASS" "FAIL") " " bid
                                         " (PID " (:worker-pid f) ", " (count results) " ran, "
                                         (:cached-count info 0) " cached)")]}])
             :else [st nil]))

         :child-exited
         (let [bid (:batch-id msg)]
           ;; Clean up if worker crashed unexpectedly before sending :test-batch-done
           (if-let [info (get-in st [:in-flight bid])]
             (do (reply! (:reply-ch info) {:ok? false :error (str "Test worker crashed: status " (:raw-status msg))})
                 [(sync-test-filter (update st :in-flight dissoc bid))
                  {::flow/error [{:proc :test :batch-id bid :crashed-pid (:pid msg)}]}])
             [st nil]))

         [st nil])))))
