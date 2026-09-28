;; Discovery: render candidates for schema X and output O = functions with an
;; arity whose :seon.fn.arity/input-refs name X or an ancestor of X and whose
;; :seon.fn.arity/output-refs name O's schema. Index walk over avet; listed,
;; never selected.
(let [ms (fn [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))
      db @(seon.cluster.boot/connection "default")
      projection (seon.schema/projection-from-database db)
      forms (:seon.schema.projection/forms projection)
      strip (fn [form] (if (and (vector? form) (map? (second form))) (into [(first form)] (drop 2 form)) form))
      parents-of (fn parents-of [form]
                   (cond (qualified-keyword? form) #{form}
                         (and (vector? form) (#{:and :merge} (first form))) (into #{} (mapcat parents-of) (rest (strip form)))
                         :else #{}))
      ancestry (fn [k] (loop [frontier [k] seen [] depth 0 out []]
                         (if (empty? frontier) out
                           (let [seen' (into seen frontier)]
                             (recur (into [] (comp (mapcat #(parents-of (get forms %))) (remove (set seen')) (distinct)) frontier)
                                    seen' (inc depth) (into out (map (fn [s] [s depth])) frontier))))))
      output-schemas {:seon.render/html #{:seon.render/hiccup}
                      :seon.render/ai #{:seon.render/source :seon.render/ai :seon.render/form}}
      datoms (fn [& args] (apply datahike.api/datoms db args))
      function-of (fn [arity] (some->> (datoms :avet :seon.fn/arities arity) first :e
                                       (datoms :avet :seon.db.vector/cells) first :e
                                       (#(datoms :eavt % :seon.fn/sym)) first :v))
      candidates (fn [k output]
                   (let [wanted (output-schemas output)]
                     (into []
                           (comp (mapcat (fn [[schema depth]]
                                           (for [{arity :e} (datoms :avet :seon.fn.arity/input-refs schema)
                                                 :when (some #(wanted (:v %)) (datoms :eavt arity :seon.fn.arity/output-refs))
                                                 :let [f (function-of arity)] :when f]
                                             {:fn f :via schema :depth depth})))
                                 (distinct))
                           (ancestry k))))
      probe (fn [k output] (let [t0 (System/nanoTime) r (candidates k output)] {:k k :output output :ms (ms t0) :candidates r}))
      ;; Coverage: for each declared pair, does discovery list the declared renderer?
      lookup (fn [k o] (when-let [c (malli.registry/schema (:seon.schema.projection/registry projection) k)]
                         (get (or (seon.schema.internal/entity-properties c) (malli.core/properties c)) o)))
      declared (for [k (filter keyword? (keys forms)) o [:seon.render/ai :seon.render/html] :let [f (lookup k o)] :when f] [k o f])
      t1 (System/nanoTime)
      listed (doall (filter (fn [[k o f]] (some #(= f (:fn %)) (candidates k o))) declared))
      coverage-ms (ms t1)
      input-of (fn [f] (seon.db/q '[:find [?in ...] :in $ ?s :where [?f :seon.fn/sym ?s] [?f :seon.fn/arities ?a] [?a :seon.fn.arity/input-refs ?in]] db f))
      declared-fns (distinct (map #(nth % 2) declared))
      generic (filter #(= #{:seon.render/unit} (set (input-of %))) declared-fns)]
  {:probes [(probe :seon.config/refused-error :seon.render/html)
            (probe :seon.config/refused-error :seon.render/ai)
            (probe :seon.help/help :seon.render/ai)
            (probe :my.note/note :seon.render/ai)
            (probe :seon.problems/problems :seon.render/html)]
   :coverage {:declared-pairs (count declared) :listed-by-discovery (count listed) :ms-all coverage-ms
              :declared-fns (count declared-fns) :fns-taking-only-the-generic-unit (count generic)
              :generic-sample (take 6 (sort generic))}})
