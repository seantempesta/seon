(ns seon.lifecycle-chaos-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.chaos-harness :as chaos]
            [seon.code :as code]
            [seon.code.edit :as cedit]
            [seon.code.io :as cio]
            [seon.code.overlay :as overlay]
            [seon.fork.calls :as fcalls]
            [seon.fork.worker-support :as wsup]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test :as stest]))

(def ^:private chaos-scenarios
  {:core [:spawn-worker :user-chat :edit-fn :set-meta :fork-review
          :main-refactor-delete-fn :add-require-or-prune :eval-interrupted]
   :review-merge [:spawn-worker :fork-review :main-refactor-delete-fn
                  :review-compact-merge-back :main-merge :cold-reboot]
   :faults-gc [:spawn-worker :model-tcp-drop :model-429 :gc-store :cold-reboot]
   :faults-terminal [:spawn-worker :model-400-ctx :model-auth-err
                     :main-register-schema :external-branch-move]})

(defn generate-plan
  "Deterministic chaos event plan covering all 18 events when `n-steps >= 18`."
  {:malli/schema [:=> [:cat [:map [:seed {:optional true} :int] [:n-steps {:optional true} :int]]] [:vector :keyword]]
   :cost/ticks 1000000}
  [{:keys [seed n-steps] :or {seed 42 n-steps 24}}]
  (let [rng (java.util.Random. (long seed))
        total (max 1 (min 64 (long n-steps)))
        alpha-len (count chaos/event-alphabet)
        padded (into chaos/event-alphabet
                     (repeatedly (max 0 (- total alpha-len))
                                 #(nth chaos/event-alphabet (.nextInt rng alpha-len))))]
    (if (< total alpha-len)
      (subvec chaos/event-alphabet 0 total)
      (let [prefix (subvec padded 0 11)
            tail (subvec padded 11)
            shuffled (reduce (fn [items idx]
                               (let [swap-idx (+ idx (.nextInt rng (- (count items) idx)))]
                                 (assoc items idx (items swap-idx) swap-idx (items idx))))
                             tail
                             (range (dec (count tail))))]
        (into prefix shuffled)))))

(defn format-reproducer
  "Formats a copy-pasteable `seon.chaos-harness/replay!` call."
  {:malli/schema [:=> [:cat :int [:vector :keyword]] :string]
   :cost/ticks 200000}
  [seed steps]
  (str "(seon.chaos-harness/replay! {:seed " seed " :steps " (pr-str (vec steps)) "})"))

(defn shrink-failing-sequence
  "Zeller `ddmin` delta-debugging shrinker for failing event sequences."
  {:malli/schema [:=> [:cat [:vector :keyword] [:=> [:cat [:vector :keyword]] :boolean]] [:vector :keyword]]
   :cost/ticks 5000000}
  [steps failing?]
  (let [initial (vec steps)]
    (if (or (<= (count initial) 1) (not (failing? initial)))
      initial
      (loop [current initial
             granularity 2]
        (let [length (count current)
              parts (min granularity length)
              chunk-size (quot (+ length parts -1) parts)
              ranges (for [start (range 0 length chunk-size)]
                       [start (min length (+ start chunk-size))])
              still-fails? #(and (seq %) (< (count %) length) (failing? %))
              failing-part (some (fn [[s e]]
                                   (let [candidate (subvec current s e)]
                                     (when (still-fails? candidate) candidate)))
                                 ranges)
              failing-comp (when-not failing-part
                             (some (fn [[s e]]
                                     (let [candidate (into (subvec current 0 s)
                                                           (subvec current e length))]
                                       (when (still-fails? candidate) candidate)))
                                   ranges))]
          (cond
            (<= length 1) current
            failing-part (recur failing-part 2)
            failing-comp (recur failing-comp (max 2 (dec parts)))
            (< parts length) (recur current (min length (* 2 parts)))
            :else current))))))

(defn- assert-no-chaos-leaks! [prefix dir]
  (is (empty? (filter #(str/starts-with? (name (ns-name %)) prefix) (all-ns))))
  (is (string? dir))
  (is (not (.exists (io/file dir))) (str "leaked dir: " dir)))

(defn- open-lean-store! [tag]
  (let [dir (str "/tmp/seon-chaos-" tag "-" (System/nanoTime))
        cfg (assoc (store/store-cfg dir {:cache-bytes 4194304}) :branch :main)
        init-cfg (dissoc cfg :branch)
        init-conn (do (d/create-database init-cfg) (d/connect init-cfg))]
    (try
      (d/transact init-conn {:tx-data (:declarations @schema/base-registry)})
      (d/branch! init-conn :db :main)
      (finally (d/release init-conn)))
    (let [conn (store/connect cfg :main)]
      (d/transact conn {:tx-data [(assoc store/default-config-values
                                         :seon.config/key :seon.config/root)]})
      {:dir dir :cfg cfg :conn conn})))

(defn- close-lean-store! [{:keys [dir cfg conn]} & extra-conns]
  (doseq [c extra-conns :when c] (try (d/release c) (catch Throwable _)))
  (try (store/close! {:cfg cfg :conn conn}) (catch Throwable _))
  (try (store/rm-rf! (io/file dir)) (catch Throwable _)))

(deftest chaos-alphabet-and-core-code-sync-test
  (testing "Scenario partition covers all 18 events and flow-edges derive directly from seon.main/flow-connections"
    (let [plan (generate-plan {:seed 42 :n-steps 18})
          union-ev (into #{} (mapcat val chaos-scenarios))]
      (is (= (set chaos/event-alphabet) (set plan)))
      (is (= (set chaos/event-alphabet) union-ev))
      (is (= #{[:db :reconciler :db] [:db :test :db]} (set (:transactor chaos/flow-edges))))
      (is (= #{[:run :agents :run] [:batch :test :batch] [:tx :transactor :tx]} (set (:reconciler chaos/flow-edges))))
      (is (= #{[:exits :reconciler :exits] [:tx :transactor :tx] [:request :model :request]} (set (:agents chaos/flow-edges))))
      (is (= #{[:tx :transactor :tx]} (set (:test chaos/flow-edges))))))
  (let [res (chaos/replay! {:seed 42 :steps (:core chaos-scenarios)})]
    (is (:ok? res) (pr-str (:violations res)))
    (is (= (count (:core chaos-scenarios)) (:ran res)))
    (is (empty? (:violations res)))
    (is (< (:bytes res) 209715200) (str "bytes=" (:bytes res)))
    (assert-no-chaos-leaks! "seon.chaos.s42" (:dir res))))

(deftest chaos-review-merge-and-reboot-test
  (let [res (chaos/replay! {:seed 42 :steps (:review-merge chaos-scenarios)})]
    (is (:ok? res) (pr-str (:violations res)))
    (is (= (count (:review-merge chaos-scenarios)) (:ran res)))
    (is (empty? (:violations res)))
    (is (< (:bytes res) 209715200) (str "bytes=" (:bytes res)))
    (assert-no-chaos-leaks! "seon.chaos.s42" (:dir res))))

(deftest chaos-model-faults-and-gc-test
  (let [res (chaos/replay! {:seed 42 :steps (:faults-gc chaos-scenarios)})]
    (is (:ok? res) (pr-str (:violations res)))
    (is (= (count (:faults-gc chaos-scenarios)) (:ran res)))
    (is (empty? (:violations res)))
    (is (< (:bytes res) 209715200) (str "bytes=" (:bytes res)))
    (assert-no-chaos-leaks! "seon.chaos.s42" (:dir res))))

(deftest chaos-terminal-faults-and-dual-move-test
  (let [res (chaos/replay! {:seed 42 :steps (:faults-terminal chaos-scenarios)})]
    (is (:ok? res) (pr-str (:violations res)))
    (is (= (count (:faults-terminal chaos-scenarios)) (:ran res)))
    (is (empty? (:violations res)))
    (is (< (:bytes res) 209715200) (str "bytes=" (:bytes res)))
    (assert-no-chaos-leaks! "seon.chaos.s42" (:dir res))))

(deftest ddmin-shrinker-and-fault-hook-reproducer-test
  (testing "Zeller ddmin shrinks a 20-step synthetic failing plan to <= 4 steps"
    (let [steps (generate-plan {:seed 42 :n-steps 20})
          fails? (fn [s]
                   (let [i (.indexOf ^java.util.List s :main-refactor-delete-fn)
                         j (.indexOf ^java.util.List s :add-require-or-prune)]
                     (and (<= 0 i) (< i j))))
          shrunk (shrink-failing-sequence steps fails?)
          repro (format-reproducer 42 shrunk)]
      (is (= 20 (count steps)))
      (is (true? (fails? steps)))
      (is (<= (count shrunk) 4) (pr-str shrunk))
      (is (= [:main-refactor-delete-fn :add-require-or-prune] shrunk))
      (is (str/starts-with? repro "(seon.chaos-harness/replay! {:seed 42 :steps ["))))
  (testing "fault-hook triggers invariant catch and copy-pasteable reproducer"
    (let [hook (fn [world ev]
                 (if (= ev :edit-fn)
                   (update world :flow-errors conj {:synthetic :injected-flow-error})
                   world))
          res (chaos/replay! {:seed 42
                              :steps [:spawn-worker :edit-fn :user-chat]
                              :fault-hook hook})]
      (is (false? (:ok? res)))
      (is (= :inv-6 (:inv (first (:violations res)))))
      (is (str/includes? (format-reproducer 42 [:spawn-worker :edit-fn])
                         ":edit-fn"))
      (assert-no-chaos-leaks! "seon.chaos.s42" (:dir res)))))

(deftest test-proc-sibling-purge-and-fork-refused-test
  (let [reply-ch (a/chan 1)
        parent-id "batch-99"
        base-state {:conn nil :cfg nil :fork-out (a/chan 8) :max-workers 2
                    :parents {parent-id {:reply-ch reply-ch :agent-id :agent/w1
                                         :branch :branch/w1 :branch-tx 10
                                         :expected 3 :received 0 :results [] :errors []}}
                    :active-subs {"batch-99-fast-0" parent-id
                                  "batch-99-fast-1" parent-id
                                  "batch-99-slow-0" parent-id
                                  "other-fast-0" "other"}
                    :sub-queue []}]
    (testing ":child-exception purges ALL sibling sub-ids and stale sibling no-ops cleanly"
      (let [[s1 out1] (stest/test-proc base-state :fork-ev
                        {:type :test :batch-id "batch-99-fast-0" :pid 701
                         :ev :child-frame
                         :frame {:ev :child-exception :batch-id "batch-99-fast-0" :error "boom"}})
            reply (a/poll! reply-ch)]
        (is (= #{"other-fast-0"} (set (keys (:active-subs s1)))))
        (is (not (contains? (:parents s1) parent-id)))
        (is (false? (:ok? reply)))
        (is (= :agent/w1 (:to (first (:tx out1)))))
        (is (empty? (::flow/error out1)))
        (let [[s2 out2] (stest/test-proc s1 :fork-ev
                          {:type :test :batch-id "batch-99-fast-1" :pid 702
                           :ev :child-exited :raw-status 9})]
          (is (= s1 s2))
          (is (nil? out2)))))
    (testing ":fork-refused (targeted and nil batch-id) releases subs/parents and notifies without wedging"
      (let [rc2 (a/chan 1)
            st (assoc-in base-state [:parents parent-id :reply-ch] rc2)
            [s1 out1] (stest/test-proc st :fork-ev
                        {:type :test :batch-id "batch-99-fast-0" :ev :fork-refused :code 429})
            reply (a/poll! rc2)]
        (is (= #{"other-fast-0"} (set (keys (:active-subs s1)))))
        (is (not (contains? (:parents s1) parent-id)))
        (is (false? (:ok? reply)))
        (is (str/includes? (str (:error reply)) "429"))
        (is (= :agent/w1 (:to (first (:tx out1))))))
      (let [rc3 (a/chan 1)
            st (assoc base-state :parents {parent-id {:reply-ch rc3 :branch :branch/w1 :expected 1}}
                      :active-subs {"batch-99-fast-0" parent-id})
            [s2 out2] (stest/test-proc st :fork-ev {:type :test :ev :fork-refused :code 503})
            reply (a/poll! rc3)]
        (is (empty? (:active-subs s2)))
        (is (empty? (:parents s2)))
        (is (false? (:ok? reply)))
        (is (seq (::flow/error out2)))))))

(deftest store-adopt-head-config-prune-and-dual-move-sync-test
  (is (true? (:seon.config/viewport-screenshot? store/default-config-values)))
  (let [{:keys [cfg conn] :as st} (open-lean-store! "part1-sync")
        c-br (atom nil)]
    (try
      (store/branch! conn :main :branch/w-sync)
      (store/branch! conn :main :branch/w-merged)
      (d/transact conn {:tx-data [#:seon.agent{:id :agent/w-sync :branch :branch/w-sync}
                                  #:seon.agent{:id :agent/w-merged :branch :branch/w-merged
                                               :merged-tx (:max-tx @conn)}]})
      (let [bc (store/connect cfg :branch/w-sync)
            _ (reset! c-br bc)]
        (testing "adopt-head! preserves live :writer and monotonic :max-tx"
          (let [other (store/connect cfg :branch/w-sync)]
            (try
              (d/transact other {:tx-data [#:seon.agent{:id :agent/w-sync :branch :branch/w-sync :summary "ext-1"}]})
              (let [latest @other]
                (store/adopt-head! bc latest)
                (is (some? (:writer @(:wrapped-atom bc))))
                (is (>= (:max-tx @bc) (:max-tx latest))))
              (finally (d/release other)))))
        (testing "interrupted eval after external branch move keeps live :writer"
          (let [other (store/connect cfg :branch/w-sync)]
            (try
              (d/transact other {:tx-data [#:seon.agent{:id :agent/w-sync :summary "ext-2"}]})
              (finally (d/release other))))
          (code/eval-tool-calls bc {:agent-id :agent/w-sync :turn 1 :start-ns (quote clojure.core)}
            [{:id "t1" :code "(throw (ex-info \"int\" {:jolt/interrupted :ticks}))"}])
          (is (some? (:writer @(:wrapped-atom bc))))
          (d/transact bc {:tx-data [#:seon.agent{:id :agent/w-sync :summary "post-int"}]}))
        (testing "dual-move sync adopts external branch commit BEFORE copying new :main schema"
          (let [pre-bc (d/commit-id @bc)
                pre-mc (d/commit-id @conn)
                other (store/connect cfg :branch/w-sync)]
            (try
              (d/transact other {:tx-data [#:seon.agent{:id :agent/w-sync :summary "dual-move-ext"}]})
              (finally (d/release other)))
            (d/transact conn {:tx-data [#:db{:ident :seon.chaos/dual-attr
                                             :valueType :db.type/string
                                             :cardinality :db.cardinality/one}]})
            (let [res (wsup/sync-conn-to-latest-branch-head! bc :branch/w-sync pre-bc pre-mc)]
              (is (not= pre-bc (:branch-cid res)))
              (is (not= pre-mc (:main-cid res)))
              (is (some? (:writer @(:wrapped-atom bc))))
              (is (= "dual-move-ext" (:seon.agent/summary (d/pull @bc [:seon.agent/summary] [:seon.agent/id :agent/w-sync]))))
              (is (some? (get-in @bc [:schema :seon.chaos/dual-attr]))))))
        (testing "prune-merged-branches! preserves exact map contract and exposes :pruned-branches metadata"
          (let [res (store/prune-merged-branches! conn 0)]
            (is (= {:ok? true :deleted 1 :archived 1 :kept 0} res))
            (is (= [:branch/w-merged] (:pruned-branches (meta res))))
            (is (not (contains? (d/branches conn) :branch/w-merged)))
            (is (contains? (d/branches conn) :branch/w-sync)))))
      (finally
        (close-lean-store! st @c-br)
        (assert-no-chaos-leaks! "seon.chaos.part1" (:dir st))))))

(deftest overlay-stale-branch-and-merged-ns-src-no-resurrection-test
  (let [ns-info (chaos/synthetic-sources 99)
        {:keys [cfg conn] :as st} (open-lean-store! "part1-ov")
        c-br (atom nil)]
    (try
      (d/transact conn {:tx-data (vec (mapcat (fn [[p s]] (code/file-import-tx @conn p s false)) (:files ns-info)))})
      (store/branch! conn :main :branch/w-stale)
      (d/transact conn {:tx-data (code/file-import-tx @conn (:ui-path ns-info) (:ui-refactored-src ns-info) false)})
      (let [fs (vec (mapcat #(code/ns-forms @conn %) [(:helper-ns ns-info) (:ui-ns ns-info)]))]
        (code/apply-overlay! @conn {:upserts (code/sort-forms-topologically fs) :retracted-keys #{}} true))
      (let [bc (store/connect cfg :branch/w-stale)]
        (reset! c-br bc)
        (binding [fcalls/*agent-ctx* {:conn bc :branch :branch/w-stale :agent-id :agent/w-stale}]
          (cedit/add-require! (:ui-ns ns-info) (quote [clojure.set :as cset]) {:eval? false})
          (cedit/prune-requires! (:ui-ns ns-info)))
        (testing "merged-ns-src on stale branch never resurrects :main-deleted render-nav-bar"
          (let [src (cio/merged-ns-src bc :branch/w-stale (:ui-ns ns-info))]
            (is (string? src))
            (is (not (str/includes? src "render-nav-bar")))))
        (testing "overlay/load-delta! on stale branch compiles cleanly without M0 overwrite"
          (let [base (d/merge-base bc :main :branch/w-stale)
                head (d/commit-id @bc)
                res (overlay/load-delta! bc base head false)
                page-fn (ns-resolve (:ui-ns ns-info) (quote page))]
            (is (empty? (:errors res)) (pr-str res))
            (is (str/starts-with? (page-fn 1) "i1")))))
      (finally
        (close-lean-store! st @c-br)
        (doseq [n (all-ns) :when (str/starts-with? (name (ns-name n)) (:prefix ns-info))]
          (try (remove-ns (ns-name n)) (catch Throwable _)))
        (assert-no-chaos-leaks! (:prefix ns-info) (:dir st))))))
