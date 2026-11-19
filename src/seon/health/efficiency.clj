(defn ns-in-scope?
  "True when namespace symbol `nsym` belongs to `scope` (exact match or descendant `scope.*`,
   with `'seon` matching all `seon.*` namespaces)."
  [scope nsym]
  (when (and scope nsym)
    (let [sc (name scope)
          ns-s (name nsym)]
      (or (= ns-s sc)
          (str/starts-with? ns-s (str sc "."))))))

(defn- full-db-scan-form? [x]
  (and (seq? x) (symbol? (first x))
       (let [nm (name (first x))]
         (or (and (= "datoms" nm) (= :aevt (nth x 2 nil)))
             (= "q" nm)))))

(defn- nested-db-scan-in-form?
  "True when a form executes `d/datoms :aevt` or `d/q` inside a loop body (`O(N * M)`)."
  ([x] (nested-db-scan-in-form? x 0))
  ([x depth]
   (cond
     (full-db-scan-form? x) (pos? depth)
     (and (seq? x) (symbol? (first x)))
     (let [nm (name (first x))]
       (cond
         (contains? #{"for" "doseq"} nm)
         (let [binds (second x)
               body (nnext x)
               bind-hit (when (vector? binds)
                          (loop [bs (seq binds) d depth]
                            (when bs
                              (let [k (first bs) v (second bs)]
                                (if (contains? #{:let :when :while} k)
                                  (or (nested-db-scan-in-form? v d) (recur (nnext bs) d))
                                  (or (nested-db-scan-in-form? v d) (recur (nnext bs) (inc d))))))))]
           (or bind-hit (boolean (some #(nested-db-scan-in-form? % (inc depth)) body))))
         (contains? #{"map" "mapv" "mapcat" "keep" "reduce" "run!" "filter" "filterv" "remove"} nm)
         (or (nested-db-scan-in-form? (second x) (inc depth))
             (boolean (some #(nested-db-scan-in-form? % depth) (nnext x))))
         :else
         (boolean (some #(nested-db-scan-in-form? % depth) (rest x)))))
     (coll? x) (boolean (some #(nested-db-scan-in-form? % depth) x))
     :else false)))
