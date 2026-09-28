;; test-data-assertions workbench: ONE form, evaluated at default's JVM REPL
;; (MCP eval_clj, namespace user) as
;;   ((eval (read-string (slurp "/Users/sean/src/seon/tmp/test-data-assertions/proof.clj")))
;;    [{:ns 'seon.render.web-test :path "test/seon/render/web_test.clj" :test 'some-test
;;      :owners {'sym '(fn ...)}}])
;; It defines no Var and redefines none (after tmp/test-seams/proof.clj). Each
;; case runs the WORKING-TREE deftest body, compiled as a fn in its loaded
;; namespace, as seon.test/run runs a host member: on a disposable isolated
;; branch acquired and released through seon.cluster.agent/acquire-context!,
;; with seon.test/*member* and kernel/*host-world* bound, under the namespace's
;; :each fixtures, its reports captured. Helper defns the loaded namespaces lack
;; (working-tree additions to the test file, test-support and web-test) are
;; bound around the body with letfn, never interned. `:owners` substitutes a
;; scratch copy of a function under test for its symbol in the body: the
;; negative proof.
(do
  (require '[clojure.java.io :as io] '[clojure.walk] '[clojure.test :as t] '[clojure.string]
           '[seon.db :as db] '[seon.test] '[seon.config] '[seon.schema]
           '[seon.operator.runtime :as runtime])
  (fn [cases]
    (let [root "/Users/sean/src/seon/"
          cluster-handle (:seon.turn.loop/cluster (get @runtime/running-instances "default"))
          recording (:seon.db/connection cluster-handle)
          handle (#'seon.test/clean-request-handle cluster-handle recording "default"
                  (seon.config/effective (db/db recording) "default"))
          acquire! (requiring-resolve 'seon.cluster.agent/acquire-context!)
          release! (requiring-resolve 'seon.cluster.agent/release-context!)
          host-world (requiring-resolve 'seon.sci.kernel/*host-world*)
          read-forms (fn [ns-sym path]
                       (binding [*ns* (the-ns ns-sym)]
                         (with-open [r (java.io.PushbackReader. (io/reader (str root path)))]
                           (doall (take-while #(not= ::eof %)
                                              (repeatedly #(read {:eof ::eof :read-cond :allow} r)))))))
          defns (fn [forms] (filter #(and (seq? %) (#{'defn 'defn-} (first %))) forms))
          ;; Simple class names a working-tree file imports, fully qualified, so a
          ;; body compiled in the loaded namespace resolves classes that ns lacks.
          imports (fn [ns-sym path]
                    (let [ns-form (first (read-forms ns-sym path))]
                      (into {}
                            (mapcat (fn [spec]
                                      (when (and (sequential? spec) (= :import (first spec)))
                                        (mapcat (fn [entry]
                                                  (if (sequential? entry)
                                                    (map (fn [c] [c (symbol (str (first entry) "." c))]) (rest entry))
                                                    [[(symbol (last (clojure.string/split (str entry) #"\."))) entry]]))
                                                (rest spec)))))
                            ns-form)))
          ;; A defn as a letfn binding: its arities without doc or attr map.
          letfn-binding (fn [local form]
                          (let [tail (drop 2 form)
                                tail (if (string? (first tail)) (rest tail) tail)
                                tail (if (map? (first tail)) (rest tail) tail)
                                tail (if (vector? (first tail)) (list tail) tail)]
                            (list* local tail)))
          ;; A working-tree file's own aliases, so its helpers compile in any namespace.
          file-aliases (fn [ns-sym path]
                         (let [ns-form (first (read-forms ns-sym path))]
                           (into {}
                                 (mapcat (fn [spec]
                                           (when (and (sequential? spec) (= :require (first spec)))
                                             (keep (fn [entry]
                                                     (when (vector? entry)
                                                       (let [{as :as} (apply hash-map (rest entry))]
                                                         (when as (require (first entry)) [as (first entry)]))))
                                                   (rest spec)))))
                                 ns-form)))
          qualify-aliases (fn [aliases form]
                            (clojure.walk/postwalk
                             (fn [y] (if (and (symbol? y) (namespace y) (get aliases (symbol (namespace y))))
                                       (symbol (str (get aliases (symbol (namespace y)))) (name y))
                                       y))
                             form))
          ;; Working-tree helpers the loaded namespace lacks: [called-as local form].
          missing-helpers
          (fn [ns-sym path alias]
            (keep (fn [form]
                    (let [n (second form)]
                      (when-not (ns-resolve (the-ns ns-sym) n)
                        [(if alias (symbol alias (str n)) n) (symbol (str "local-" n))
                         (qualify-aliases (file-aliases ns-sym path) form)])))
                  (defns (read-forms ns-sym path))))
          body-fn
          (fn [{ns-sym :ns path :path test-name :test owners :owners probe :probe}]
            (let [form (if probe
                         (list 'deftest 'probe probe)
                         (some #(when (and (seq? %) (= 'deftest (first %)) (= test-name (second %))) %)
                               (read-forms ns-sym path)))
                  _ (when-not form (throw (ex-info "no such deftest in file" {:path path :test test-name})))
                  aliases (into {} (map (fn [[a n]] [(str (ns-name n)) (str a)])) (ns-aliases (the-ns ns-sym)))
                  support-alias (get aliases "seon.test-support")
                  web-alias (get aliases "seon.render.web-test")
                  helpers (concat (missing-helpers ns-sym path nil)
                                  (when support-alias
                                    (missing-helpers 'seon.test-support "test/seon/test_support.clj" "seon.test-support"))
                                  (when web-alias
                                    (missing-helpers 'seon.render.web-test "test/seon/render/web_test.clj" "seon.render.web-test")))
                  renames (merge (imports ns-sym path)
                                 (imports 'seon.render.web-test "test/seon/render/web_test.clj")
                                 (into {} (map (fn [[called local _]] [called local])) helpers)
                                 (into {} (map (fn [[_ local form]] [(second form) local])) helpers)
                                 ;; owners are keyed as the body calls them; the body is alias-qualified
                                 (update-keys owners #(qualify-aliases (file-aliases ns-sym path) %)))
                  ;; type hints name imported classes too
                  hinted (fn [x] (clojure.walk/postwalk
                                  (fn [y]
                                    (let [tag (:tag (meta y))
                                          y (if (and (symbol? y) (nil? (namespace y)) (clojure.string/ends-with? (name y) ".")
                                                     (symbol? (get renames (symbol (subs (name y) 0 (dec (count (name y))))))))
                                              (symbol (str (get renames (symbol (subs (name y) 0 (dec (count (name y)))))) "."))
                                              y)
                                          y (if (and (symbol? y) (namespace y)
                                                     (symbol? (get renames (symbol (namespace y)))))
                                              (symbol (str (get renames (symbol (namespace y)))) (name y))
                                              y)]
                                      (if (and (symbol? tag) (symbol? (get renames tag)))
                                        (vary-meta y assoc :tag (get renames tag))
                                        y)))
                                  x))
                  bindings (mapv (fn [[_ local form]]
                                   (letfn-binding local (hinted (clojure.walk/postwalk-replace renames form))))
                                 helpers)
                  body (hinted (clojure.walk/postwalk-replace renames
                                                              (qualify-aliases (file-aliases ns-sym path) (drop 2 form))))]
              (binding [*ns* (the-ns ns-sym)]
                (eval (list 'fn [] (list* 'letfn bindings body))))))
          verdict
          (fn [ns-sym body]
            (let [events (atom [])
                  started (System/nanoTime)
                  member (acquire! handle nil {:seon.agent/isolate? true
                                               :seon.cluster.registry/from (:seon.source/commit-id handle)})
                  fixtures (t/join-fixtures (:clojure.test/each-fixtures (meta (the-ns ns-sym))))]
              (try
                (db/call-with-custody
                 (select-keys member [:seon.db/connection])
                 (fn []
                   (with-bindings {#'seon.test/*member* member
                                   host-world (:seon.sci.eval/ctx member)
                                   #'t/report (fn [event] (swap! events conj event))
                                   #'t/*report-counters* (ref t/*initial-report-counters*)
                                   #'t/*testing-contexts* (list)
                                   #'t/*testing-vars* (list)}
                     (seon.schema/call-with-projection
                      (:seon.schema/projection member)
                      (fn []
                        (try (fixtures (fn [] (swap! events conj {:type ::value :value (body)})))
                             (catch Throwable thrown (swap! events conj {:type :error :actual thrown}))))))))
                (finally (release! member)))
              (let [by-type (group-by :type @events)]
                {:pass (count (:pass by-type)) :fail (count (:fail by-type)) :error (count (:error by-type))
                 :problems (mapv (fn [event]
                                   (let [m (str (or (:message event)
                                                    (when (instance? Throwable (:actual event))
                                                      (str (class (:actual event)) ": " (ex-message (:actual event))))
                                                    (pr-str (:expected event))))]
                                     (subs m 0 (min 160 (count m)))))
                                 (concat (:fail by-type) (:error by-type)))
                 :value (:value (first (::value by-type)))
                 :ms (Math/round (/ (- (System/nanoTime) started) 1e6))})))]
      (mapv (fn [case]
              (when-not (find-ns (:ns case)) (require (:ns case)))
              (require 'seon.test-support)
              (let [result (try (verdict (:ns case) (body-fn case))
                                (catch Throwable thrown
                                  {:compile-error (str (class thrown) ": " (ex-message thrown) " "
                                                       (some-> (ex-cause thrown) ex-message))}))]
                (cond
                  (:probe case) [:probe (or (:compile-error result) (:value result) (:problems result))]
                  (:compile-error result)
                  [(:test case) :compile-error (subs (:compile-error result) 0 (min 300 (count (:compile-error result))))]
                  :else
                  (cond-> [(:test case) (if (:owners case) :broken :head)
                           ((juxt :pass :fail :error :ms) result)]
                    (seq (:problems result)) (conj (mapv #(subs % 0 (min 90 (count %))) (:problems result)))))))
            cases))))
