(ns probe.seam
  "CLJS/JIT leg of the routing-seam probe: a :load-fn-provisioned stub
  namespace (each public fn a remote-call wrapper over one host fn) is
  required lazily inside sci, called from a sci-defined fn in a hot loop,
  and the JIT tier's compiled template for that fn is inspected."
  (:require [sci.core :as sci]
            [sci.impl.jit :as jit]))

(def remote-calls (atom 0))

(defn remote-call
  "Simulated remote boundary: pure data in, pure data out."
  [req]
  (swap! remote-calls inc)
  {:echo (:args req) :n @remote-calls})

(def stub-source
  "(ns seon.db)\n(defn query [& args] (seon.host/remote-call {:fn 'seon.db/query :args (vec args)}))\n")

(def load-count (atom 0))

(defn -main [& _]
  (let [ctx (sci/init {:namespaces {'seon.host {'remote-call remote-call}}
                       :load-fn (fn [{:keys [libname]}]
                                  (when (= 'seon.db libname)
                                    (swap! load-count inc)
                                    {:file "seon/db.stub" :source stub-source}))})]
    (vreset! jit/collect-srcs? true)
    (println "jit enabled?" (jit/enabled?))
    (sci/eval-string* ctx "(require '[seon.db :as db])")
    (println "load-count:" @load-count "(lazy, once)")
    ;; a sci fn whose body calls the stub wrapper: the JIT compiles this
    ;; body; the call to db/query is a :call-var site.
    (sci/eval-string* ctx "(defn hot [i] (db/query i))")
    (let [r (sci/eval-string* ctx "(reduce (fn [acc i] (hot i) (inc acc)) 0 (range 1000))")]
      (println "hot-loop result:" r "remote-calls:" @remote-calls))
    (println "stub wrapper value sample:" (pr-str (sci/eval-string* ctx "(db/query :a :b)")))
    (let [srcs @jit/last-srcs
          hot-src (some #(when (re-find #"remote|call" %) %) srcs)]
      (println "compiled templates:" (count srcs))
      (doseq [s (take 6 srcs)]
        (println "--- template ---")
        (println s)))))

(-main)
