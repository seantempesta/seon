;; Retype rehearsal: :seon.render/{ai,html,form} to refs to the :seon.fn entity,
;; speculative on default's head through seon.db/with (armed writer checks, never committed).
(let [ms (fn [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))
      conn (seon.cluster.boot/connection "default")
      db @conn
      projection (seon.schema/projection-from-database db)
      forms (:seon.schema.projection/forms projection)
      attrs [:seon.render/ai :seon.render/html :seon.render/form]
      ref-form [:and {:seon.fn/reference-to :seon.fn/sym} :seon.db/ref]
      forms' (assoc (reduce #(assoc %1 %2 ref-form) forms attrs)
                    :seon.schema/parents [:set {:seon.db/index true :description "The schemas this schema's values also satisfy by construction: its alias target and the registry references among its :and/:merge arms, as clojure.core/parents names a hierarchy's direct parents."} :seon.db/ref])
      parent-attrs (conj attrs :seon.schema/parents)
      t0 (System/nanoTime)
      projection' (seon.schema/declaration-projection forms')
      projection-ms (ms t0)
      decls (into {} (map (fn [a] [a (seon.schema.datahike/malli->datahike-attr-in projection' a)])) parent-attrs)
      decl-rows (seon.schema/canonical-schema-entities projection' (select-keys forms' parent-attrs))
      change (seon.cluster/attribute-change-tx db parent-attrs decls)
      t1 (System/nanoTime)
      db-a (seon.db/with db {:tx-data (into change decl-rows)})
      step-a-ms (ms t1)
      refused? (fn [v] (and (map? v) (contains? v :seon.error/message)))
      ;; Step B: every schema row declaring a pair, its render properties as
      ;; nested identity maps (order-independent inside one transaction).
      declaring (for [[k _] forms :when (keyword? k)
                      :let [props (seon.schema.datahike/storable-properties-in projection k)
                            pair (select-keys props attrs)]
                      :when (seq pair)]
                  [k pair])
      strip (fn [form] (if (and (vector? form) (map? (second form))) (into [(first form)] (drop 2 form)) form))
      parents-of (fn parents-of [form]
                   (cond (qualified-keyword? form) #{form}
                         (and (vector? form) (#{:and :merge} (first form))) (into #{} (mapcat parents-of) (rest (strip form)))
                         :else #{}))
      schema-keys (set (keep #(when (keyword? %) %) (keys forms)))
      parent-rows (into [] (keep (fn [[k f]] (let [ps (filter schema-keys (parents-of f))]
                                               (when (and (keyword? k) (seq ps))
                                                 {:seon.schema/key k :seon.schema/parents (into #{} (map #(vector :seon.schema/key %)) ps)}))))
                        forms)
      rows-b (into (mapv (fn [[k pair]] (into {:seon.schema/key k} (map (fn [[a s]] [a {:seon.fn/sym s}])) pair)) declaring)
                   parent-rows)
      t2 (System/nanoTime)
      db-b (when-not (refused? db-a) (seon.db/with db-a {:tx-data rows-b}))
      step-b-ms (ms t2)
      after (when (and db-b (not (refused? db-b))) db-b)
      installed (when after (select-keys (:schema (seon.db/schema-database after)) attrs))
      resolved (when after (for [a attrs d (datahike.api/datoms after :aevt a)] [(:e d) a (:v d)]))
      unresolved (when after (remove (fn [[_ _ v]] (seq (datahike.api/datoms after :eavt v :seon.fn/sym))) resolved))
      t3 (System/nanoTime)
      dc (when after (#'seon.fn/declared-callers identity after))
      dc-ms (ms t3)
      render-fn (when after (:e (first (datahike.api/datoms after :avet :seon.fn/sym 'seon.error/render-ai))))
      t4 (System/nanoTime)
      users (when after (into [] (map :e) (datahike.api/datoms after :avet :seon.render/ai render-fn)))
      users-ms (ms t4)
      t5 (System/nanoTime)
      users-q (when after (seon.db/q '[:find [?k ...] :in $ ?f :where [?s :seon.render/ai ?f] [?s :seon.schema/key ?k]] after render-fn))
      users-q-ms (ms t5)
      t6 (System/nanoTime)
      delete (when after (seon.db/with after {:tx-data [[:db/retractEntity render-fn]]}))
      delete-ms (ms t6)
      t7 (System/nanoTime)
      dangling (when after (seon.db/with after {:tx-data [{:seon.schema/key :seon.error/base :seon.render/ai {:seon.fn/sym 'no.such/render-ai}}]}))
      dangling-ms (ms t7)
      schema-e (fn [k] (:e (first (datahike.api/datoms after :avet :seon.schema/key k))))
      key-of (fn [e] (:v (first (datahike.api/datoms after :eavt e :seon.schema/key))))
      ;; Nearest declared face: breadth-first over :seon.schema/parents, reading
      ;; the face's ref at each node; an index walk, never a form parse.
      nearest (fn [k face]
                (loop [frontier [(schema-e k)] seen #{} depth 0]
                  (when (seq frontier)
                    (if-let [hit (some (fn [e] (when-let [d (first (datahike.api/datoms after :eavt e face))]
                                                 [e (:v d)])) (sort-by key-of frontier))]
                      {:schema (key-of (first hit)) :depth depth
                       :fn (:v (first (datahike.api/datoms after :eavt (second hit) :seon.fn/sym)))}
                      (let [seen (into seen frontier)]
                        (recur (into [] (comp (mapcat (fn [e] (map :v (datahike.api/datoms after :eavt e :seon.schema/parents))))
                                              (remove seen) (distinct)) frontier)
                               seen (inc depth)))))))
      t8 (System/nanoTime)
      one (when after (nearest :seon.config/refused-error :seon.render/ai))
      one-ms (ms t8)
      t9 (System/nanoTime)
      every (when after (into {} (keep (fn [k] (when-let [h (nearest k :seon.render/ai)] [k h]))) schema-keys))
      every-ms (ms t9)
      registry' (:seon.schema.projection/registry projection)
      looked (fn [k] (when-let [c (malli.registry/schema registry' k)]
                       (get (or (seon.schema.internal/entity-properties c) (malli.core/properties c)) :seon.render/ai)))
      disagree (when after (for [k schema-keys :let [w (:fn (get every k)) l (looked k)] :when (not= w l)] {:k k :db-walk (get every k) :projection l}))
      form-output-valid? (seon.schema/valid-candidate-value? projection' :seon.render/ai "rendered text")
      form-output-valid-before? (seon.schema/valid-candidate-value? projection :seon.render/ai "rendered text")]
  {:projection-ms projection-ms
   :step-a {:ops (count change) :rows (count decl-rows) :ms step-a-ms
            :refusal (when (refused? db-a) (select-keys db-a [:seon.error/message :seon.error/operation :seon.error/member]))}
   :step-b {:rows (count rows-b) :ms step-b-ms
            :refusal (when (refused? db-b) (select-keys db-b [:seon.error/message :seon.error/operation :seon.error/member :seon.error/offending]))}
   :installed installed
   :ref-datoms (count resolved) :unresolved (count unresolved)
   :declared-callers {:targets (count dc) :ms dc-ms}
   :schemas-rendering-with {:fn 'seon.error/render-ai :avet-count (count users) :avet-ms users-ms :q-count (count users-q) :q-ms users-q-ms}
   :delete-render-fn {:ms delete-ms :refused? (refused? delete) :message (when (refused? delete) (:seon.error/message delete))}
   :dangling {:ms dangling-ms :refused? (refused? dangling) :message (when (refused? dangling) (:seon.error/message dangling))}
   :parents {:rows (count parent-rows) :edges (reduce + (map (comp count :seon.schema/parents) parent-rows))}
   :nearest-one {:k :seon.config/refused-error :hit one :ms one-ms}
   :nearest-every {:schemas (count schema-keys) :resolved-ai (count every) :ms every-ms :per-key-us (when (seq schema-keys) (/ (* 1000 every-ms) (count schema-keys)))
                   :by-depth (frequencies (map :depth (vals every)))}
   :db-walk-vs-projection {:disagreements (count disagree) :sample (take 8 (sort-by (comp str :k) disagree))}
   :ref-accepts-form-output (seon.schema/valid-candidate-value? projection' :seon.render/form '[(seon.render/render-form 1)])
   :output-type-conflict {:ai-text-output-valid-before form-output-valid-before? :ai-text-output-valid-after form-output-valid?}})
