(ns seon.dev.changed-test
  "Run the tests one set of changed paths reaches.

  Selection is NOT decided here. `bin/test --changed PATH...` owns the one
  selector (`seon.test.selection`), which derives the answer from the
  `:seon.fn/calls` edges of the program-graph manifest. This namespace
  supplies the changed paths, runs the gate, and returns its bounded
  advisory result together with the clj-kondo findings from the same
  analysis pass."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.dev.clj-kondo :as dev.kondo]
            [seon.dev.state :as state]
            [seon.dev.test-roots :as test-roots]
            [seon.operator.state :as operator.state])
  (:import [java.io File]))

(def test-timeout-ms 300000)
(def host-analysis-deadline-ms 60000)
(declare normalize-paths)

(defn configuration
  "Derive the changed-test filesystem configuration from one checkout root."
  [root]
  (let [root (str (fs/normalize (fs/absolutize root)))]
    {:seon.dev.config/root root
     :seon.dev.config/process-dir
     (str (fs/path root "tmp/test-changed"))}))

(def host-analysis-config
  "{:output {:format :edn} :analysis {:var-usages false :var-definitions {:shallow true}}}")

(defn normalize-paths
  "Return distinct repository-relative paths and reject paths outside root."
  [root paths]
  (let [root (fs/normalize (fs/absolutize root))]
    (->> paths
         (map (fn [path]
                (let [absolute (fs/normalize
                                 (fs/absolutize
                                   (if (fs/absolute? path)
                                     path
                                     (fs/path root path))))]
                  (when-not (fs/starts-with? absolute root)
                    (throw (ex-info "Changed test paths must stay inside the checkout."
                                    {:seon.dev.changed-test/path (str path)})))
                  (str/replace (str (fs/relativize root absolute))
                               File/separator "/"))))
         distinct
         sort
         vec)))

(defn root-runtime-path?
  "True when a normalized path belongs to Seon's root runtime/test graph.

   `reference-code/` contains independent maintained dependency repositories.
   Their own tests prove edits there; the root graph becomes responsible only
   after `deps.edn` advances to a dependency commit."
  [path]
  (not (str/starts-with? path "reference-code/")))

(defn- host-path? [path]
  (or (str/ends-with? path ".clj")
      (str/ends-with? path ".cljc")))

(defn- host-source-file? [path]
  (and (fs/regular-file? path)
       (host-path? (str/lower-case (str path)))))

(defn- files-below [root relative]
  (let [directory (fs/path root relative)]
    (if (fs/directory? directory)
      (->> (fs/glob directory "**{.clj,.cljc}")
           (filter host-source-file?))
      [])))

(defn- host-corpus [root]
  (->> (concat (files-below root "src")
               (files-below root "script/seon/dev")
               (test-roots/operator-test-files root)
               (test-roots/writer-test-files root))
       (map #(str (fs/normalize (fs/absolutize %))))
       distinct
       sort
       vec))

(defn- host-analysis-row? [row]
  (not= :cljs (:lang row)))

(defn analysis->host-graph
  "Build host namespace dependencies and retained runner roots."
  [root analysis]
  (let [definitions (->> (get-in analysis [:analysis :namespace-definitions])
                         (filter host-analysis-row?))
        usages (->> (get-in analysis [:analysis :namespace-usages])
                    (filter host-analysis-row?))
        relative (fn [path]
                   (first (normalize-paths root [path])))
        ownership (->> definitions
                       (group-by :name)
                       (map (fn [[namespace rows]]
                              [namespace
                               (set (map (comp relative :filename) rows))]))
                       (into {}))
        ambiguous (->> ownership
                       (keep (fn [[namespace paths]]
                               (when (< 1 (count paths)) namespace)))
                       vec)
        path->namespace (into {}
                              (mapcat (fn [[namespace paths]]
                                        (map #(vector % namespace) paths)))
                              ownership)
        requires (reduce (fn [result {:keys [from to]}]
                           (if (and from to)
                             (update result from (fnil conj #{}) to)
                             result))
                         {}
                         usages)]
    (when (seq ambiguous)
      (throw (ex-info "Host namespaces must have one first-party source file."
                      {:seon.dev.changed-test/namespaces ambiguous})))
    {:seon.dev.changed-test/path->namespace path->namespace
     :seon.dev.changed-test/requires requires
     :seon.dev.changed-test/operator-tests
     (set (test-roots/operator-test-namespaces root))
     :seon.dev.changed-test/writer-tests
     (set (test-roots/writer-test-namespaces root))}))

(defn analyze-host
  "Return source-analysis facts and findings without publishing program rows."
  [root]
  (if-not (fs/which "clj-kondo")
    {:seon.dev.changed-test/host-status :unavailable
     :seon.dev.changed-test/reason "clj-kondo is unavailable"}
    (try
      (let [files (host-corpus root)
            result
            (operator.state/run-process!
             {:seon.operator.subprocess/argv
              (into ["clj-kondo" "--lint"]
                    (concat files ["--config" host-analysis-config]))
              :seon.operator.subprocess/directory root
              :seon.operator.subprocess/deadline-ms
              host-analysis-deadline-ms})
            parsed
            (edn/read-string (:seon.operator.subprocess/output result))]
        (if (map? (:analysis parsed))
          {:seon.dev.changed-test/host-status :available
           :seon.dev.changed-test/host-graph
           (analysis->host-graph root parsed)
           :seon.dev.changed-test/findings
           (vec (:findings parsed))}
          {:seon.dev.changed-test/host-status :unavailable
           :seon.dev.changed-test/reason
           (str "clj-kondo returned no namespace analysis"
                (when-not
                 (str/blank? (:seon.operator.subprocess/error-output result))
                  (str ": "
                       (str/trim
                        (:seon.operator.subprocess/error-output result)))))}))
      (catch Exception error
        (cond->
         {:seon.dev.changed-test/host-status :unavailable
          :seon.dev.changed-test/reason (.getMessage error)}
          (:seon.error/kind (ex-data error))
          (assoc :seon.error/kind (:seon.error/kind (ex-data error))
                 :seon.error/data (ex-data error)))))))

(defn- prune-logs! [directory]
  (doseq [path (->> (fs/list-dir directory "changed-*.log")
                    (sort-by fs/last-modified-time)
                    reverse
                    (drop 20))]
    (fs/delete-if-exists path)))

(defn failure-excerpts
  "Return bounded clojure.test failure blocks with expected and actual values."
  [output]
  (loop [lines (str/split-lines output)
         excerpts []]
    (if (or (empty? lines) (= 2 (count excerpts)))
      excerpts
      (if (re-find #"^(FAIL|ERROR) in \(" (first lines))
        (let [block (->> lines
                         (take 4)
                         (take-while (complement str/blank?))
                         (map #(subs % 0 (min 180 (count %))))
                         (str/join "\n"))]
          (recur (drop 4 lines) (conj excerpts block)))
        (recur (rest lines) excerpts)))))

(defn- run-command! [root boundary argv environment]
  (let [log-dir (fs/path root "tmp/test-changed")
        log (fs/path log-dir
                     (str "changed-" (name boundary) "-"
                          (System/currentTimeMillis) "-"
                          (random-uuid) ".log"))
        _ (fs/create-dirs log-dir)
        execution
        (try
          {:seon.dev.changed-test/process
           (operator.state/run-process!
            {:seon.operator.subprocess/argv argv
             :seon.operator.subprocess/directory root
             :seon.operator.subprocess/extra-env environment
             :seon.operator.subprocess/deadline-ms test-timeout-ms
             :seon.operator.subprocess/merge-error? true
             :seon.operator.subprocess/output-file (str log)})}
          (catch clojure.lang.ExceptionInfo failure
            (if (= :seon.operator.subprocess/deadline-exceeded
                   (:seon.error/kind (ex-data failure)))
              {:seon.dev.changed-test/deadline-error (ex-data failure)}
              (throw failure))))
        process-result (:seon.dev.changed-test/process execution)
        deadline-error (:seon.dev.changed-test/deadline-error execution)
        completed? (some? process-result)
        terminated? (or completed?
                        (true? (:seon.operator.subprocess/reaped?
                                deadline-error)))
        exit (if completed?
               (:seon.operator.subprocess/exit process-result)
               124)
        output (slurp (str log))
        summary (some->> (str/split-lines output)
                         (filter #(re-find #"^Ran [0-9]+ tests containing" %))
                         last)
        counts (some->> (str/split-lines output)
                        (filter #(re-find #"^[0-9]+ failures, [0-9]+ errors\." %))
                        last)
        failures (failure-excerpts output)
        result
        {:seon.dev.changed-test/boundary boundary
         :seon.dev.changed-test/command argv
         :seon.dev.changed-test/status
         (if (and completed? terminated? (zero? exit) summary counts
                  (str/starts-with? counts "0 failures, 0 errors."))
           :passed
           (cond
             (not terminated?) :cleanup-failed
             completed? :failed
             :else :timed-out))
         :seon.dev.changed-test/exit exit
         :seon.dev.changed-test/summary summary
         :seon.dev.changed-test/counts counts
         :seon.dev.changed-test/failures failures
         :seon.dev.changed-test/log (str log)}]
    (prune-logs! log-dir)
    (cond-> result
      deadline-error
      (assoc :seon.dev.changed-test/deadline-error deadline-error))))

(defn- run-gate!
  "Run the one gate over the named changed paths.

   `bin/test --changed PATH...` owns the selection: it runs the declared
   platform moving-part regressions first, then only the tests those paths
   reach through the program graph. This namespace decides nothing about
   which tests those are."
  [root paths]
  (let [argv (into [(str (fs/path root "bin/test"))]
                   (mapcat (fn [path] ["--changed" (str path)]))
                   paths)]
    (assoc (run-command! root :gate argv {})
           :seon.dev.changed-test/paths (vec paths))))

(defn- aggregate-status [boundary-results]
  (let [statuses (set (map :seon.dev.changed-test/status boundary-results))]
    (cond
      (contains? statuses :cleanup-failed) :cleanup-failed
      (contains? statuses :timed-out) :timed-out
      (contains? statuses :failed) :failed
      (contains? statuses :passed) :passed
      :else :no-affected-tests)))

(defn- persist-report [root result]
  (let [directory (fs/path root "tmp/test-changed")
        report (fs/path directory "latest.report.edn")]
    (state/write-edn! report result)
    (assoc result :seon.dev.changed-test/report (str report))))

(defn- run-changed-unlocked!
  "Run the gate while the caller owns the changed-test lock."
  [configuration requested-paths]
  (let [root (:seon.dev.config/root configuration)
        paths (filterv root-runtime-path? requested-paths)
        dependency-source-paths
        (filterv (complement root-runtime-path?) requested-paths)
        dependency-cache (dev.kondo/ensure-dependency-cache! root)
        host-result (analyze-host root)
        boundary-results (if (seq paths) [(run-gate! root paths)] [])]
    {:seon.dev.changed-test/paths requested-paths
     :seon.dev.changed-test/status (aggregate-status boundary-results)
     :seon.dev.changed-test/boundaries boundary-results
     :seon.dev.changed-test/host-status
     (:seon.dev.changed-test/host-status host-result)
     :seon.dev.changed-test/findings
     (vec (:seon.dev.changed-test/findings host-result))
     :seon.dev.changed-test/dependency-cache
     {:seon.dev.changed-test/dependency-cache-status
      (:seon.dev.clj-kondo/status dependency-cache)
      :seon.dev.changed-test/reason
      (:seon.dev.clj-kondo/reason dependency-cache)}
     :seon.dev.changed-test/widening
     (vec
      (when (seq dependency-source-paths)
        [{:seon.dev.changed-test/reason
          :independent-reference-repository
          :seon.dev.changed-test/paths dependency-source-paths}]))}))

(def changed-test-lock-timeout-ms
  (+ (* 2 test-timeout-ms) 10000))

(defn run-changed!
  "Run affected writer and operator tests from current host graph facts."
  [configuration paths]
  (let [root (:seon.dev.config/root configuration)
        requested-paths (normalize-paths root paths)]
    (state/with-lock
     configuration :changed-test changed-test-lock-timeout-ms
     #(persist-report root
                      (run-changed-unlocked! configuration requested-paths)))))

(defn format-result
  "Format one bounded advisory result for a human or edit hook."
  [result]
  (let [status (:seon.dev.changed-test/status result)
        boundaries (:seon.dev.changed-test/boundaries result)
        findings (:seon.dev.changed-test/findings result)
        dependency-cache (:seon.dev.changed-test/dependency-cache result)]
    (str "changed tests " (name status)
         (apply str
                (for [boundary boundaries
                      :let [tests (:seon.dev.changed-test/test-namespaces
                                   boundary)]]
                  (str "\n" (name (:seon.dev.changed-test/boundary boundary))
                       " " (name (:seon.dev.changed-test/status boundary))
                       (when (= :all tests) " — full retained gate")
                       (when (sequential? tests)
                         (str " — " (count tests) " namespace(s): "
                              (str/join ", " (take 6 tests))
                              (when (< 6 (count tests)) " …")))
                       (when-let [summary (:seon.dev.changed-test/summary
                                           boundary)]
                         (str "\n  " summary " "
                              (:seon.dev.changed-test/counts boundary)))
                       (when-let [reason (:seon.dev.changed-test/reason
                                          boundary)]
                         (str "\n  " reason))
                       (when-let [log (:seon.dev.changed-test/log boundary)]
                         (str "\n  log: " log))
                       (when-let [failures
                                  (seq (:seon.dev.changed-test/failures
                                        boundary))]
                         (str "\n" (str/join "\n" failures))))))
         (when-let [report (:seon.dev.changed-test/report result)]
           (str "\nreport: " report))
         (when (= :unavailable
                  (:seon.dev.changed-test/dependency-cache-status
                   dependency-cache))
           (str "\ndependency analysis unavailable: "
                (:seon.dev.changed-test/reason dependency-cache)))
         (when (seq findings)
           (str "\nclj-kondo findings:"
                (apply str
                       (for [finding (take 20 findings)]
                         (str "\n  "
                              (:filename finding) ":"
                              (:row finding) ":"
                              (:col finding) " ["
                              (name (:level finding)) "/"
                              (name (:type finding)) "] "
                              (:message finding))))
                (when (< 20 (count findings)) "\n  …")))
         (when-let [widening (seq (:seon.dev.changed-test/widening result))]
           (str "\nwidening: "
                (str/join ", "
                          (map (comp name :seon.dev.changed-test/reason)
                               widening)))))))
