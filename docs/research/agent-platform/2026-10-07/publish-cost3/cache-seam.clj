(let [directory (clojure.java.io/file (System/getProperty "seon.operator.root") "probe")
      _ (.mkdirs directory)
      callee (clojure.java.io/file directory "callee.clj")
      caller (clojure.java.io/file directory "caller.clj")
      _ (spit callee "(ns callee) (defn f [x] x)")
      _ (spit caller "(ns caller (:require [callee :as c])) (defn g [] (c/f 1))")
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      run (fn [files]
            (let [visited (volatile! [])
                  allocated (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean)
                  started (System/nanoTime)
                  result (clj-kondo.core/run!
                          {:lint (mapv str files) :cache-dir (str (clojure.java.io/file directory "cache"))
                           :config-dir (str (clojure.java.io/file directory "config")) :repro true
                           :config {:analysis {:arglists true :var-usages true}}
                           :file-analyzed-fn #(vswap! visited conj (:filename %))})]
              {:ms (/ (- (System/nanoTime) started) 1e6)
               :allocated (- (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean) allocated)
               :files (count @visited)
               :findings (mapv #(select-keys % [:type :row :col :message]) (:findings result))
               :calls (mapv #(select-keys % [:to :name :arity :fixed-arities])
                            (filter #(= 'callee (:to %)) (get-in result [:analysis :var-usages])))}))
      first-run (run [callee caller])
      same-input (run [callee caller])
      cached-callee (run [caller])
      _ (spit callee "(ns callee) (defn f [] 1)")
      changed-callee (run [callee caller])]
  {:first first-run :same-input same-input :cached-callee cached-callee :changed-callee changed-callee
   :namespace-cache-keys (vec (keys (clj-kondo.impl.cache/from-cache-1
                                   (str (clojure.java.io/file directory "cache/v1")) :clj 'callee)))})
