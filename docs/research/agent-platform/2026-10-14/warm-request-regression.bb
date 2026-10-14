;; Dated research driver. Run with the committed operator's script/src/resources
;; on bb's classpath and SEON_CHECKOUT set to the repository. Arguments:
;; REPOSITORY REV OUTPUT. One throwaway JVM at a time, no shared store copy.
(require '[babashka.fs :as fs]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[seon.operator :as operator])

(let [[repository revision output] *command-line-args*
      sha (str/trim (:out (shell/sh "git" "rev-parse" revision :dir repository)))
      disposable (str (fs/create-temp-dir {:dir (str repository "/tmp/warm-request-regression")
                                           :prefix (str (subs sha 0 9) "-")}))
      prefix (str "regression-" (System/currentTimeMillis))
      probe-form
      (list 'let ['prefix prefix]
       '(let [probe (fn [id code]
                      (let [m (seon.profile/measure
                               #(#'seon.mcp/eval-clj
                                 {:seon.mcp/code code :seon.mcp/agent id
                                  :seon.mcp/namespace "sentinel.scratch"
                                  :seon.mcp/cluster "default" :seon.mcp/mode "sci"}))
                            [texts failed?] (:seon.profile/value m)]
                        {:agent id :failed? failed?
                         :request (some :seon.repl/request (map clojure.edn/read-string texts))
                         :profile (dissoc m :seon.profile/value :seon.profile/called)}))
              warm-id (str prefix "-warm")
              warm (fn [] (mapv (fn [_] (probe warm-id "(+ 1 2)")) (range 10)))]
          (dotimes [_ 2] (probe warm-id "(+ 1 2)"))
          (let [first-warm (warm)
                definitions (mapv (fn [n] (probe (str prefix "-defn-" n)
                                                "(defn sentinel-probe-inc [x] (inc x))")) (range 10))
                final-warm (warm)
                found (#'seon.mcp/instance @seon.operator.runtime/running-instances "default")
                handle (:seon.turn.loop/cluster found)
                connection (:seon.db/connection handle)
                context {:seon.store/store (:seon.store/store found)
                         :seon.db/connection connection
                         :seon.agent/routing (:seon.agent/routing handle)}
                retired (mapv (fn [n]
                                (my.agent/retire-branch!
                                 {:seon.store/branch
                                  (:seon.agent/branch
                                   (seon.db/pull (seon.db/db connection) [:seon.agent/branch]
                                                 [:seon.agent/id (str prefix "-defn-" n)]))}
                                 context)) (range 10))]
            {:warm first-warm :defn definitions :warm-after final-warm
             :retired retired :warm-retired (warm)})))
      began (System/nanoTime)]
  (try
    (println "boot" revision disposable)
    (flush)
    (let [boot (#'operator/launched-throwaway! repository disposable sha)]
      (spit (str output ".boot.edn") (pr-str boot))
      (if (:seon.operator/ready? boot)
        (let [result (operator/live-root-value! disposable (pr-str probe-form) {})]
          (spit output (pr-str {:revision sha :root disposable :form probe-form
                               :boot-phases (:seon.operator/phases boot)
                               :result (:seon.operator/value result)}))
          (println "measured" revision output))
        (println "BOOT FAILED" revision (str output ".boot.edn"))))
    (finally
      (let [identities (operator/selected-processes disposable)
            stopped (operator/down! {:seon.operator/managed-root disposable
                                     :seon.boot/cluster-name "default"} identities)]
        (spit (str output ".cleanup.edn")
              (pr-str {:identities identities :stopped stopped
                       :elapsed-ms (/ (- (System/nanoTime) began) 1e6)}))
        (when (empty? (operator/selected-processes disposable))
          (fs/delete-tree disposable))))))
