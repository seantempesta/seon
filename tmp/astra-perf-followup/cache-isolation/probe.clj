(let [base "/Users/sean/src/seon/tmp/astra-perf-followup/cache-isolation/"
      anon (fn [path]
             (let [form (read-string (slurp (str base path)))]
               (cons 'fn (drop-while #(not (vector? %)) (drop 2 form)))))
      run-probe
      (binding [*ns* (the-ns 'seon.fn.analyzer)]
        (let [parent (eval (anon "parent-analyze.clj"))
              helper (anon "cache-root.clj")
              candidate-form (anon "candidate-analyze.clj")
              config (str base "isolated-config")
              cache (str config "/.cache/dependencies/probe")
              candidate (eval (list 'let ['analysis-cache-root
                                           (list 'let ['config-directory config] helper)] candidate-form))
              live-candidate (eval (list 'let ['analysis-cache-root helper] candidate-form))
              source (str base "fixture/string.clj")
              text "(ns clojure.string) (defn stub [] :fixture)"
              real-file (clojure.java.io/file seon.fn.analyzer/cache-directory "v1/clj/clojure.string.transit.json")
              private-file (clojure.java.io/file cache "v1/clj/clojure.string.transit.json")
              initial (slurp real-file)
              request {:seon.fn.analyzer/sources {source text} :seon.fn.analyzer/cache-root cache}
              measure (fn [f]
                        (spit private-file initial)
                        (let [t (System/nanoTime) value (f request)]
                          {:ms (/ (- (System/nanoTime) t) 1e6)
                           :names (mapv :seon.fn.analyzer/name (:seon.fn.analyzer/var-definitions value))
                           :cache-unchanged (= initial (slurp private-file))}))]
          (clojure.java.io/make-parents private-file)
          (let [pairs (mapv (fn [_] {:parent (measure parent) :candidate (measure candidate)}) (range 4))
                live (live-candidate (assoc request :seon.fn.analyzer/cache-root seon.fn.analyzer/cache-directory))]
            {:isolated-pairs pairs
             :live-names (mapv :seon.fn.analyzer/name (:seon.fn.analyzer/var-definitions live))
             :live-cache-unchanged (= initial (slurp real-file))
             :cached-entry-bytes (.length real-file)})))]
  run-probe)
