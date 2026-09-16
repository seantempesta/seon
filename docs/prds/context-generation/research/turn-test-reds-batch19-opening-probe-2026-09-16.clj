(def probe-source
  "Exact historical MCP forms. Load the recorded base probe and classloader first;
   evaluate this source in user in the isolated development JVM, never default."
  "(def reds19-opening-evidence (atom nil))\n(defn reds19-observed-fixture [fixture arguments]\n  (let [body (last arguments)\n        observer\n        (fn [cluster]\n          (try\n            (body cluster)\n            (finally\n              (reset! reds19-opening-evidence\n                      (seon.db/q\n                       '[:find [(pull ?error [* {:seon.error/occurrences [*]}]) ...]\n                         :where [?error :seon.error/id _]]\n                       (seon.db/db (:seon.db/connection cluster)))))))]\n    (apply fixture (concat (butlast arguments) [observer]))))\n(def reds19-future\n  (future\n    (let [thread (Thread/currentThread)\n          entering (.getContextClassLoader thread)\n          fixture-var (ns-resolve 'seon.cluster.turn-test 'with-cluster)\n          fixture @fixture-var]\n      (try\n        (.setContextClassLoader thread reds19-loader)\n        (with-bindings {clojure.lang.Compiler/LOADER reds19-loader}\n          (with-redefs-fn\n            {fixture-var (fn [& arguments] (reds19-observed-fixture fixture arguments))}\n            #(reds19-run \"opening-boundary\"\n                         [\"seon.cluster.turn-test/runtime-schema-unregister-removes-one-unused-global-schema\"])))\n        (finally (.setContextClassLoader thread entering))))))\n")

(comment
  (binding [*ns* (find-ns 'user)]
    (load-string probe-source)))
