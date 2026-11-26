(ns seon.test.plan
  "Extracted child namespace of `seon.test`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.merge :as merge]
            [seon.code.reach :as reach]))

(defn candidate-merged-db
  "Returns the post-merge candidate DB for `merge-plan`: fast-paths to `theirs-db` when `:main`
   has not advanced past `merge-base`, otherwise applies `merge-plan` schema & program ops
   in-memory onto `ours-db` via `d/with` (zero disk writes)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :map]] :seon.host/db]
   :cost/ticks 50000000}
  [ours-db theirs-db merge-plan]
  (if (or (nil? merge-plan)
          (and (:base-commit merge-plan)
               (= (:base-commit merge-plan) (:ours-commit merge-plan))))
    theirs-db
    (let [prog-attrs #{:seon.code.ns/name :seon.code.form/key :seon.code.var/name
                       :seon.schema/key :seon.rule/id}
          prog-op? (fn [op]
                     (cond
                       (map? op) (boolean (some #(contains? op %) prog-attrs))
                       (vector? op) (let [e (second op)]
                                      (or (integer? e)
                                          (and (vector? e) (contains? prog-attrs (first e)))
                                          (and (string? e)
                                               (or (str/starts-with? e "new/seon.code.")
                                                   (str/starts-with? e "new/seon.schema/")
                                                   (str/starts-with? e "new/seon.rule/")))))
                       :else false))
          s-tx (vec (:schema-tx merge-plan))
          db1 (if (seq s-tx) (:db-after (d/with ours-db {:tx-data s-tx})) ours-db)
          p-ops (filterv prog-op? (:tx-data merge-plan))]
      (if (seq p-ops)
        (:db-after (d/with db1 {:tx-data p-ops}))
        db1))))

(defn build-branch-overlay
"Builds the branch code/schema/rule overlay from `merge-plan` and expands spliced callers
 against the merged candidate DB (`cand-db`) so stale branch bases never pull outdated `:main` forms."
{:malli/schema [:function
                [:=> [:cat :seon.host/conn :map :seon.store/branch] :map]
                [:=> [:cat :seon.host/conn :map :seon.store/branch [:maybe :seon.host/db]] :map]]
 :cost/ticks 50000000}
([conn merge-plan theirs-branch]
 (build-branch-overlay conn merge-plan theirs-branch nil))
([conn merge-plan theirs-branch cand-db]
 (let [ours-db @conn
       theirs-db (d/branch-as-db (:store ours-db) theirs-branch)
       rule-pull '[:seon.rule/id :seon.rule/doc :seon.rule/category :seon.rule/stratum
                 :seon.rule/enabled? :seon.rule/target :seon.rule/query
                 :seon.rule/assert]
       raw (reduce
            (fn [acc {:keys [entity status path]}]
              (let [[id-attr id-val] entity
                    acc (if (and path (code/boot-file-path? path))
                          (assoc acc :boot-affected? true)
                          acc)]
                (cond
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

                  (= :seon.rule/id id-attr)
                  (if (= :deleted status)
                    (update acc :retracted-rules conj id-val)
                    (if-let [r (d/pull theirs-db rule-pull [:seon.rule/id id-val])]
                      (if (:seon.rule/id r)
                        (update acc :rule-upserts conj r)
                        acc)
                      acc))

                  :else acc)))
            {:upserts [] :retracted-keys #{} :schema-upserts [] :retracted-schemas #{}
             :rule-upserts [] :retracted-rules #{} :boot-affected? false}
            (:entity-diffs merge-plan))
       eff-db (or cand-db (candidate-merged-db ours-db theirs-db merge-plan))]
   (code/expand-overlay-rebinds eff-db raw))))

(defn- last-known-ms [db var-sym]
  (or (loop [ds (rseq (vec (d/datoms db :avet :seon.test/var var-sym)))]
        (when-let [dt (first ds)]
          (if-let [ms (:v (first (d/datoms db :eavt (:e dt) :seon.test/ms)))]
            (double ms)
            (recur (next ds)))))
      10.0))

(defn- cached-green? [db digest]
  (true? (:seon.test/pass? (d/pull db '[:seon.test/pass?] [:seon.test/digest digest]))))

(defn attach-var-refs
  "Attaches `:seon.test/var-ref` (`:db.type/ref` -> `:seon.code.var/entity`) onto test verdict
   maps whenever `:seon.test/var` resolves to an existing var entity on `db`."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map]] [:vector :map]]
   :cost/ticks 2000000}
  [db results]
  (let [dh-sch (:schema db)
        ok? (and (contains? dh-sch :seon.test/var-ref)
                 (contains? dh-sch :seon.code.var/name))]
    (mapv (fn [r]
            (if-let [eid (when (and ok? (nil? (:seon.test/var-ref r)))
                           (when-let [vsym (:seon.test/var r)]
                             (:e (first (d/datoms db :avet :seon.code.var/name vsym)))))]
              (assoc r :seon.test/var-ref eid)
              r))
          results)))

(defn- branch-cand-reach [conn db reach b plan]
  (let [tdb (d/branch-as-db (:store db) b)
        cand-db (candidate-merged-db db tdb plan)
        ov (build-branch-overlay conn plan b cand-db)]
    {:overlay ov
     :reach (reach/apply-overlay-to-reach-graph cand-db reach ov)}))

(defn test-batch-plan
  {:cost/ticks 5000000, :malli/schema [:=> [:cat :map :seon.host/conn :seon.host/db :keyword :any :any [:maybe :boolean] :any] :any]}
  [st conn db b runtime-plan vars force? fpt]
  (let [theirs-db (if (= b :main) db (d/branch-as-db (:store db) b))
        branch-cid (d/commit-id theirs-db)
        {:keys [overlay reach]} (if runtime-plan
                                  (branch-cand-reach conn db (:reach st) b runtime-plan)
                                  {:overlay nil :reach (:reach st)})
        cand-reach reach
        base-reach (if fpt
                     (:reach (branch-cand-reach conn db (:reach st) (:target-branch fpt)
                                                (merge/three-way conn (:cfg st) (:target-branch fpt) {:skip-health? true})))
                     (:reach st))
        deleted-main-tests (when (and overlay (empty? vars))
                             (vec (sort (remove #(contains? (:tests cand-reach) %)
                                                (keys (get-in st [:reach :tests]))))))
        base-tests (:tests base-reach)
        base-digests (:digests base-reach)
        all-tests (:tests cand-reach)
        target-tests (cond
                       (seq vars) (select-keys all-tests vars)
                       (and overlay (not force?))
                       (into {} (filter (fn [[sym dg]] (not= dg (get base-tests sym)))) all-tests)
                       :else all-tests)
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
                                 (when (or force? (not overlay) (not= dg (get base-digests fk)))
                                   (let [gdg (str "gen:" dg)]
                                     (when (or force? (not (cached-green? db gdg)))
                                       {:var vsym :digest gdg :ns (symbol (namespace vsym))
                                        :est-ms 5.0})))))
                             (sort-by first contracted-map)))
        cached-n (+ (- (count (if (seq vars) target-tests all-tests)) (count uncached-tests))
                    (- (count contracted-map) (count uncached-gens)))
        plat-reached (vec (sort (filter #(contains? (:platform-tests cand-reach) %)
                                        (map :var uncached-tests))))
        boot-affected? (boolean (:boot-affected? overlay))]
    {:b b :branch-cid branch-cid :overlay overlay :deleted-main-tests deleted-main-tests
     :uncached-tests uncached-tests :uncached-gens uncached-gens :cached-n cached-n
     :plat-reached plat-reached :boot-affected? boot-affected? :fork-relative? (boolean fpt)}))
