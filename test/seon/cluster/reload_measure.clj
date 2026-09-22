(ns seon.cluster.reload-measure
  "Scratch-only hook clocks and regressions. Run with bb -m seon.cluster.reload-measure ROOT."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [seon.operator :as operator]))

(defn- measured-request
  "Observe one request through its existing progress binding; leaf spans include nested time."
  {:malli/schema [:=> [:cat :string :boolean] :string]}
  [form detailed?]
  (pr-str
   (walk/postwalk-replace
    {'request-body (edn/read-string form) 'detailed-spans? detailed?}
    '(let [spans (atom {})
           targets (when detailed-spans?
                     {(ns-resolve 'seon.cluster.source 'capture-paths) :seon.probe/capture
                      (ns-resolve 'seon.cluster.source 'classify-paths) :seon.probe/classify
                      (ns-resolve 'seon.fn 'analyze-rows) :seon.probe/analyze
                      (ns-resolve 'seon.fn 'index!) :seon.probe/index
                      (ns-resolve 'seon.db 'transact!) :seon.probe/transact
                      (ns-resolve 'seon.cluster 'load-development-definitions!) :seon.probe/reload
                      (ns-resolve 'seon.instrument 'apply!) :seon.probe/re-arm})
           wrappers (into {}
                          (map (fn [[v phase]]
                                 (let [f @v]
                                   [v (fn [& args]
                                        (let [start (System/nanoTime)]
                                          (try (apply f args)
                                               (finally (swap! spans update phase (fnil + 0.0)
                                                               (/ (- (System/nanoTime) start) 1e6))))))])))
                          targets)
           result (with-bindings
                    {(ns-resolve 'seon.cluster '*source-progress!*)
                     (fn [phase]
                       (swap! @(ns-resolve 'user 'reload-phases) conj [phase (System/nanoTime)])
                       nil)}
                    (with-redefs-fn wrappers (fn [] request-body)))]
       (assoc result :seon.probe/nested-phase-ms @spans)))))

(defn -main
  "Exercise actual hook events against an already booted isolated snapshot."
  {:malli/schema [:function [:=> [:cat :string] :nil] [:=> [:cat :string :string] :nil]]}
  ([root] (-main root "default"))
  ([root cluster]
  (let [root (operator/canonical-root root)
        checkout (.getCanonicalPath (io/file "."))]
    (assert (and (some #(str/ends-with? root %) ["/tmp/reload-per-decl-root" "/tmp/reload-b-root"])
                 (some #(str/ends-with? checkout %)
                       ["/tmp/reload-per-declaration-wt" "/tmp/reload-per-declaration-b-wt"
                        "/tmp/reload-b-wt"]))
            "This probe edits only its dedicated disposable source snapshot.")
    (binding [*ns* (the-ns 'user) *in* (java.io.StringReader. "{}")
              *out* (java.io.StringWriter.)]
      (load-file "bin/seon-hook"))
    (let [hook (fn [name] (ns-resolve 'user name))
          config {:lint {:enabled false} :markdown-lint {:enabled false}
                  :docstring-lint {:enabled false} :review {:enabled false}
                  :schema-admission {:enabled false}
                  :shell-writes {:enabled true :roots ["src/my/note.clj"]}
                  :current-source {:enabled true :root root :cluster cluster
                                   :timeout-seconds 120 :check-tests false}
                  :feedback {:max-tokens 10000}}
          endpoint (operator/advertisement root cluster)
          original-client operator/prepl-value!
          read-state (fn []
                       (original-client
                        endpoint
                        (pr-str
                         (walk/postwalk-replace
                          {"default" cluster}
                          '(do
                            (seon.cluster/project-next-prepl-value!
                             {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false})
                            (let [database (seon.db/db (seon.cluster.boot/connection "default"))
                                  transaction (seon.db/q '[:find ?tx . :where
                                                          [?c :seon.cluster/name "default"]
                                                          [?c :seon.source/commit-id _ ?tx]] database)]
                              {:seon.probe/phases
                               (mapv (fn [[[phase start] [_ end]]]
                                       {:seon.probe/phase phase :seon.probe/ms (/ (- end start) 1e6)})
                                     (partition 2 1 (when-let [v (ns-resolve 'user 'reload-phases)] @@v)))
                               :seon.probe/adoption
                               (assoc (seon.db/pull database [:seon.source/commit-id]
                                                   [:seon.cluster/name "default"])
                                      :seon.probe/transaction
                                      (seon.db/pull database
                                                    [:db/id :seon.test/adoption-cluster
                                                     :seon.test/adoption-identities :seon.test/adoption-inputs]
                                                    transaction))
                               :seon.probe/heap-used-bytes
                               (- (.totalMemory (Runtime/getRuntime))
                                  (.freeMemory (Runtime/getRuntime)))})))) 120000))
          event (fn [path]
                  (cond-> {:hook_event_name "PostToolUse" :session_id "reload-per-declaration-clocks"
                           :tool_name (if path "Edit" "exec")}
                    path (assoc :tool_input {:file_path path})))
          invoke (fn [label event expected]
                   (let [_ (original-client endpoint "(do (reset! @(ns-resolve 'user 'reload-phases) []) nil)" 120000)
                         calls (atom [])
                         began (System/nanoTime)
                         feedback
                         (with-redefs-fn
                           {(hook 'load-config) (constantly config)
                            #'operator/prepl-value!
                            (fn [& args]
                              (swap! calls conj (second args))
                              (apply original-client (assoc (vec args) 1
                                                           (measured-request (second args) (= label :adopt-noncore)))))}
                           #(binding [*ns* (the-ns 'user)
                                      *in* (java.io.StringReader. (json/generate-string event))]
                              (with-out-str ((hook '-main)))))
                         elapsed (/ (- (System/nanoTime) began) 1e6)
                         response (json/parse-string (str/trim feedback) true)
                         line (get-in response [:hookSpecificOutput :additionalContext])
                         terminal (when line
                                    (let [prefix (str expected ": ")]
                                      (let [terminal-line (some #(when (str/starts-with? % prefix) %)
                                                                (str/split-lines line))]
                                        (assert terminal-line line)
                                        (edn/read-string (subs terminal-line (count prefix))))))
                         state (read-state)
                         row (merge {:seon.probe/case label :seon.probe/elapsed-ms elapsed
                                     :seon.probe/request-count (count @calls)
                                     :seon.probe/forms @calls :seon.probe/terminal terminal}
                                    state)]
                     (prn row) (flush)
                     (assert (= 1 (count @calls)) (pr-str row))
                     (when (= expected "accepted")
                       (assert (= (:seon.source/commit-id terminal)
                                  (get-in state [:seon.probe/adoption :seon.source/commit-id]))))
                     row))
          leaf (io/file "src/my/note.clj") core (io/file "src/seon/id.clj")
          leaf-before (slurp leaf) core-before (slurp core)]
      (original-client endpoint
                       (pr-str '(do
                                  (create-ns 'seon.reload-probe)
                                  (binding [*ns* (the-ns 'seon.reload-probe)]
                                    (clojure.core/refer 'clojure.core)
                                    (eval '(defn caller [] (seon.id/valid? 12 "reload-probe"))))
                                  (intern 'user 'reload-caller-var (ns-resolve 'seon.reload-probe 'caller))
                                  (intern 'user 'reload-caller-root @(ns-resolve 'seon.reload-probe 'caller))
                                  (assert (false? ((deref (ns-resolve 'seon.reload-probe 'caller)))))
                                  (intern 'user 'reload-phases (atom []))
                                  nil)) 120000)
      (try
        (let [before (read-state)
              nochange (invoke :adopt-nochange (event (str leaf)) "accepted")]
          (assert (= (:seon.probe/adoption before) (:seon.probe/adoption nochange)))
          (assert (= [] (get-in nochange [:seon.probe/terminal :seon.source/reloaded-namespaces])))
          (assert (= #{} (get-in nochange [:seon.probe/terminal :seon.source/arming-identities]))))
        (spit leaf (str/replace-first leaf-before "Read my current notes" "Read my current saved notes"))
        (let [leaf-result (invoke :adopt-noncore (event (str leaf)) "accepted")]
          (assert (some #{'my.note} (get-in leaf-result [:seon.probe/terminal :seon.source/reloaded-namespaces]))))
        (spit core (str/replace-first core-before
                                     "(every? #(str/index-of \"0123456789abcdef\" %) id)"
                                     "(if (= id \"reload-probe\") true (every? #(str/index-of \"0123456789abcdef\" %) id))"))
        (let [result (invoke :adopt-core (event (str core)) "accepted")]
          (when (= "1" (System/getenv "RELOAD_EXPECT_NARROW"))
            (assert (= '[seon.id] (get-in result [:seon.probe/terminal :seon.source/reloaded-namespaces])))))
        (prn (original-client endpoint
                              (pr-str '(let [v (ns-resolve 'seon.reload-probe 'caller)]
                                         (assert (true? (@v)))
                                         (assert (identical? v @(ns-resolve 'user 'reload-caller-var)))
                                         (assert (identical? @v @(ns-resolve 'user 'reload-caller-root)))
                                         {:seon.probe/caller-result (@v)
                                          :seon.probe/caller-var-unchanged true
                                          :seon.probe/caller-root-unchanged true})) 120000))
        (finally
          (spit leaf leaf-before)
          (spit core core-before)))))))
