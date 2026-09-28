;; Local function values only: no def, namespace load, Var replacement or tests.
(let [source-ns (the-ns 'seon.schema.datahike)
      names '[storage-content compiled-storage edn-encoded-attr-in? vector-shaped-attr-in? identity-attr-in? ref-attr-in?]
      forms (binding [*ns* source-ns]
              (with-open [r (java.io.PushbackReader. (clojure.java.io/reader "/Users/sean/src/seon/tmp/astra-cold-projection/rejected-datahike.clj"))]
                (loop [forms {}]
                  (let [form (read {:eof ::eof} r)]
                    (if (= ::eof form) forms
                        (recur (if (and (seq? form) (#{'defn 'defn-} (first form)) (some #{(second form)} names))
                                 (assoc forms (second form) form) forms)))))))
      database (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.db/carried-projection database)
      attrs (vec (filter #(malli.registry/schema (:seon.schema.projection/registry projection) %) (keys (:schema database))))
      candidate
      (reduce (fn [env name]
                (let [form (get forms name)
                      declaration (drop 2 form)
                      declaration (if (string? (first declaration)) (rest declaration) declaration)
                      contract (:malli/schema (first declaration))
                      fn-form (cons 'fn (rest declaration))
                      factory (binding [*ns* source-ns] (eval (list 'fn (vec (keys env)) fn-form)))
                      f (apply factory (vals env))
                      sym (symbol "scratch.astra-cold-projection" (str name))
                      wrapped (#'seon.instrument/compiled-wrapper projection sym contract f
                               {:seon.config.eval.result/max-bytes 1000000 :seon.config.eval.result/max-source 1000000})
                      cell (seon.profile/cell {:seon.profile/sym sym :seon.profile/scope :seon.profile/host})
                      measured (seon.profile/with-cell cell (fn [& args] (seon.profile/timed (apply wrapped args))))]
                  (assoc env name measured))) (array-map) names)
      questions '[edn-encoded-attr-in? vector-shaped-attr-in? identity-attr-in? ref-attr-in?]
      baseline (mapv #(deref (ns-resolve source-ns %)) questions)
      changed (mapv candidate questions)
      run (fn [fs fresh?]
            (let [p (if fresh? (#'seon.schema/with-compiled-cache projection) projection)]
              (mapv (fn [attr] (mapv #(% p attr) fs)) attrs)))
      measure (fn [fs fresh?]
                (let [sink (java.util.concurrent.ConcurrentHashMap.)
                      start (System/nanoTime)
                      value (binding [seon.batch/*world* (assoc seon.batch/*world* :seon.batch/sink sink)] (run fs fresh?))
                      ms (/ (- (System/nanoTime) start) 1000000.0)
                      counts (into (sorted-map) (map (fn [[[sym _] c]] [sym (.get ^java.util.concurrent.atomic.AtomicLongArray c 0)])) sink)]
                  {:value value :ms ms :calls (reduce + (vals counts)) :counts counts}))
      results (mapv (fn [fresh?]
                      (run baseline fresh?) (run changed fresh?)
                      (let [pairs (mapv (fn [_] [(measure baseline fresh?) (measure changed fresh?)]) (range 7))]
                        {:fresh? fresh? :attributes (count attrs)
                         :equal? (every? (fn [[b a]] (= (:value b) (:value a))) pairs)
                         :before (dissoc (ffirst pairs) :value)
                         :after (dissoc (second (first pairs)) :value)
                         :before-median-ms (nth (sort (map (comp :ms first) pairs)) 3)
                         :after-median-ms (nth (sort (map (comp :ms second) pairs)) 3)})) [true false])]
  (spit "/Users/sean/src/seon/tmp/astra-cold-projection/probe.edn" (pr-str results))
  results)
