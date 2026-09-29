;; Same immutable live input, real Datahike result, and armed local candidate.
;; No production Var is replaced; run through MCP eval_clj, mode jvm.
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.db/carried-projection database)
      query '[:find ?function-symbol ?minimum ?maximum
              :where [?function :seon.fn/sym ?function-symbol]
                     [?function :seon.fn/arities ?arity]
                     [?arity :seon.fn.arity/min ?minimum]
                     [(get-else $ ?arity :seon.fn.arity/max -1) ?maximum]]
      parsed (datahike.query/memoized-parse-query query)
      [lowered pulls] (#'seon.db/lowered-query-request projection {:query query :args [database]} parsed)
      raw (apply datahike.api/q (:query lowered) (:args lowered))
      declarations (#'seon.db/read-declarations database 'seon.db/q)
      source-ns (the-ns 'seon.db)
      form (binding [*ns* source-ns]
             (with-open [reader (java.io.PushbackReader. (clojure.java.io/reader "/Users/sean/src/seon/src/seon/db.clj"))]
               (loop [] (let [form (read reader)]
                          (if (and (seq? form) (= 'defn- (first form)) (= 'decode-query-result (second form)))
                            form (recur))))))
      declaration (drop 3 form)
      contract (:malli/schema (first declaration))
      candidate (binding [*ns* source-ns] (eval (cons 'fn (rest declaration))))
      wrapped (#'seon.instrument/compiled-wrapper projection 'scratch.astra-run-owned/decode-query-result
               contract candidate {:seon.config.eval.result/max-bytes 1000000 :seon.config.eval.result/max-source 1000000})
      cell (seon.profile/cell {:seon.profile/sym 'scratch.astra-run-owned/decode-query-result :seon.profile/scope :seon.profile/host})
      changed (seon.profile/with-cell cell (fn [& args] (seon.profile/timed (apply wrapped args))))
      measure (fn [f]
                (let [sink (java.util.concurrent.ConcurrentHashMap.)
                      start (System/nanoTime)
                      value (binding [seon.batch/*world* (assoc seon.batch/*world* :seon.batch/sink sink)]
                              (f declarations parsed raw pulls {}))
                      ms (/ (- (System/nanoTime) start) 1e6)
                      counts (into {} (map (fn [[[sym _] c]] [sym (.get c 0)])) sink)]
                  {:value value :ms ms :calls (reduce + (vals counts)) :counts counts}))
      pairs (mapv (fn [_] [(measure @#'seon.db/decode-query-result) (measure changed)]) (range 7))
      map-query (datahike.query/memoized-parse-query
                 '[:find ?result . :in $ ?input :where [(identity ?input) ?result]])
      nested {:seon.db/read-request (#'seon.schema.datahike/storage-string {:seon.probe/n 1})}
      map-before (@#'seon.db/decode-query-result declarations map-query nested {} {})
      map-after (changed declarations map-query nested {} {})
      result {:nested-map-decoded? (= {:seon.db/read-request {:seon.probe/n 1}} map-before map-after)
              :rows (count raw)
              :equal? (every? (fn [[before after]] (= raw (:value before) (:value after))) pairs)
              :pairs (mapv #(mapv (fn [row] (dissoc row :value)) %) pairs)}]
  (spit "/Users/sean/src/seon/tmp/astra-run-owned/decode-probe.edn" (pr-str result))
  (update result :pairs #(mapv (fn [pair] (mapv (fn [row] (dissoc row :counts)) pair)) %)))
