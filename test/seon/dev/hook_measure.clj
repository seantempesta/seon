(ns seon.dev.hook-measure
  "Scratch-only hook clocks and regressions. Run with bb -m seon.dev.hook-measure ROOT."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [seon.operator :as operator]))

(defn -main
  "Exercise actual hook events against an already booted isolated snapshot."
  {:malli/schema [:=> [:cat :string] :nil]}
  [root]
  (let [root (operator/canonical-root root)
        checkout (.getCanonicalPath (io/file "."))]
    (assert (and (str/ends-with? root "/tmp/hook-one-request-root")
                 (str/ends-with? checkout "/tmp/hook-one-request-wt"))
            "This probe edits only its dedicated disposable source snapshot.")
    (binding [*ns* (the-ns 'user) *in* (java.io.StringReader. "{}")
              *out* (java.io.StringWriter.)]
      (load-file "bin/seon-hook"))
    (let [hook (fn [name] (ns-resolve 'user name))
          config {:lint {:enabled false} :markdown-lint {:enabled false}
                  :docstring-lint {:enabled false} :review {:enabled false}
                  :schema-admission {:enabled false}
                  :shell-writes {:enabled true :roots ["src/my/note.clj"]}
                  :current-source {:enabled true :root root :cluster "default"
                                   :timeout-seconds 120 :check-tests false}
                  :feedback {:max-tokens 10000}}
          endpoint (operator/advertisement root "default")
          original-client operator/prepl-value!
          read-state (fn []
                       (original-client
                        endpoint
                        (pr-str
                         '(do
                            (seon.cluster/project-next-prepl-value!
                             {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false})
                            (let [database (seon.db/db (seon.cluster.boot/connection "default"))
                                  transaction (seon.db/q '[:find ?tx . :where
                                                          [?c :seon.cluster/name "default"]
                                                          [?c :seon.source/commit-id _ ?tx]] database)]
                              {:seon.probe/adoption
                               (assoc (seon.db/pull database [:seon.source/commit-id]
                                                   [:seon.cluster/name "default"])
                                      :seon.probe/transaction
                                      (seon.db/pull database
                                                    [:db/id :seon.test/adoption-cluster
                                                     :seon.test/adoption-identities :seon.test/adoption-inputs]
                                                    transaction))
                               :seon.probe/heap-used-bytes
                               (- (.totalMemory (Runtime/getRuntime))
                                  (.freeMemory (Runtime/getRuntime)))}))) 120000))
          event (fn [path]
                  (cond-> {:hook_event_name "PostToolUse" :session_id "hook-one-request-clocks"
                           :tool_name (if path "Edit" "exec")}
                    path (assoc :tool_input {:file_path path})))
          invoke (fn [label event expected]
                   (let [calls (atom [])
                         began (System/nanoTime)
                         feedback
                         (with-redefs-fn
                           {(hook 'load-config) (constantly config)
                            #'operator/prepl-value!
                            (fn [& args]
                              (swap! calls conj (second args))
                              (apply original-client args))}
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
      (try
        (let [before (read-state)
              nochange (invoke :adopt-nochange (event (str leaf)) "accepted")]
          (assert (= (:seon.probe/adoption before) (:seon.probe/adoption nochange)))
          (assert (= [] (get-in nochange [:seon.probe/terminal :seon.source/reloaded-namespaces])))
          (assert (= #{} (get-in nochange [:seon.probe/terminal :seon.source/arming-identities]))))
        (spit leaf (str/replace-first leaf-before "Read my current notes" "Read my current saved notes"))
        (let [leaf-result (invoke :adopt-noncore (event (str leaf)) "accepted")]
          (assert (some #{'my.note} (get-in leaf-result [:seon.probe/terminal :seon.source/reloaded-namespaces]))))
        (spit core (str/replace-first core-before "Lowercase SHA-256" "Stable lowercase SHA-256"))
        (invoke :adopt-core (event (str core)) "accepted")
        (spit leaf (str/replace-first (slurp leaf) "Read my current saved notes" "Read my saved current notes"))
        (invoke :shell-write (event nil) "accepted")
        (let [before (read-state)]
          (spit leaf (str leaf-before "\n(defn hook-refused [] (missing-hook-function))\n"))
          (let [refused (invoke :refused (event (str leaf)) "refused")]
            (assert (= (:seon.probe/adoption before) (:seon.probe/adoption refused)))
            (assert (get-in refused [:seon.probe/terminal :seon.error/operation]))))
        (finally
          (spit leaf leaf-before)
          (spit core core-before))))))
