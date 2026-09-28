(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.db/carried-projection database)
      forms (:seon.schema.projection/forms projection)
      base "/Users/sean/src/seon/tmp/astra-perf-followup/derivation-inputs/"
      compile-probe
      (binding [*ns* (the-ns 'seon.cluster)]
        (let [anon (fn [path] (let [form (read-string (slurp (str base path)))]
                               (cons 'fn (drop-while #(not (vector? %)) (drop 2 form)))))
              strip-form (eval (anon "form.clj"))
              compile-key (fn [form]
                            (let [held (seon.cache/create 100000000)
                                  lookup (fn [kind key weigh derive] (seon.cache/lookup held kind key weigh derive some?))]
                              ((eval (list 'fn '[lookup]
                                           (clojure.walk/postwalk-replace {'cache/lookup 'lookup} form))) lookup)))]
          {:parent (compile-key (anon "parent.clj"))
           :candidate (compile-key (list 'let ['derivation-form (anon "form.clj")] (anon "candidate.clj")))
           :strip strip-form}))
      changed-forms (update-vals forms (:strip compile-probe))
      modified (seon.schema/build-projection
                 changed-forms (:seon.schema.projection/function-contracts projection)
                 (assoc (seon.schema/population-options projection)
                        :seon.schema/projection projection :seon.schema/validate-render-contracts? false))
      inputs (into {} (seon.db/q '[:find ?path ?digest :where [?f :seon.fn.file/relative-path ?path]
                                   [?f :seon.fn.file/digest ?digest]] database))
      schema-keys (#'seon.cluster/stored-derivation-schema-keys database (set (keys forms)))
      paths (set (keys schema-keys))
      code (apply str (repeat 64 "a"))
      measure (fn [f p] (let [t (System/nanoTime) keys (f code p inputs schema-keys paths)]
                          {:ms (/ (- (System/nanoTime) t) 1e6) :keys keys}))
      results (into {} (for [version [:parent :candidate]]
                        (let [f (get compile-probe version)
                              before (measure f projection) after (measure f modified)]
                          [version {:before-ms (:ms before) :after-ms (:ms after)
                                    :changed-files (count (filter #(not= (get (:keys before) %) (get (:keys after) %)) paths))
                                    :one-file-change (let [inputs (update inputs "src/seon/fn.clj" #(seon.id/digest 64 [% :probe]))
                                                           start (System/nanoTime)
                                                           result (f code modified inputs schema-keys paths)]
                                                       {:ms (/ (- (System/nanoTime) start) 1e6)
                                                        :paths (vec (filter #(not= (get (:keys after) %) (get result %)) paths))})}])))]
  {:files (count paths) :changed-schema-properties (count (filter (fn [[k v]] (not= v (forms k))) changed-forms))
   :results results :dependencies-equal (= (:seon.schema.projection/schema-dependencies projection)
                                          (:seon.schema.projection/schema-dependencies modified))})
