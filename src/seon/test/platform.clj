(ns seon.test.platform
  "Prove a branch's host-bound change in a JVM loaded from it. The branch's
  files are written into a copy of the published archive (the bytes its
  analysis digests name), committed on top of the published commit without
  touching the shared index, run by `bin/seon check --at` in a throwaway JVM,
  and the members it answers are recorded on the branch as an ordinary test
  run whose provenance names the candidate commit (`:seon.test.run/git-sha`).
  The branch's own reach digests key that evidence, so `seon.test/verified?`
  reuses it until the branch moves."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.id :as id]
            [seon.test :as test]
            [seon.test.runner :as runner])
  (:import [java.util.concurrent TimeUnit]))

(defn- failure
  "This namespace's error value for `operation`."
  {:malli/schema [:=> [:cat :qualified-symbol :string :map] :seon.error/base]}
  [operation message data]
  (refusal/diagnostic (java.util.Date.) :seon.test/platform operation
                      (assoc data :seon.error/message message)))

(defn published-source
  "The published archive this JVM loaded (`data/source/<sha>`), its commit
  sha and the Git checkout candidates are committed in."
  {:malli/schema [:=> [:cat] [:or :seon.test.platform/published :seon.error/base]]}
  []
  (let [archive (System/getProperty "seon.repository.root")
        checkout (or (System/getenv "SEON_CHECKOUT") (System/getProperty "seon.operator.root"))
        sha (some-> archive io/file .getName)]
    (if (and archive checkout (re-matches #"[0-9a-f]{40}" (or sha "")))
      {:seon.test.platform/archive archive :seon.test.run/git-sha sha :seon.test.platform/checkout checkout}
      (failure `published-source "This JVM was not launched from a published archive with a Git checkout."
               {:seon.test.platform/archive (str archive) :seon.test.platform/checkout (str checkout)}))))

(defn snapshot!
  "Clone the published archive's declaration roots (the first path segment of
  every file `database` indexes) into a fresh directory under `parent`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.platform/published :string] :seon.fn/root]}
  [database {archive :seon.test.platform/archive} parent]
  (let [roots (into (sorted-set) (map #(first (str/split % #"/")))
                    (db/q '[:find [?path ...] :where [_ :seon.fn.file/relative-path ?path]] database))
        target (str (fs/create-dirs (io/file parent (str "check-platform-" (id/id)))))]
    (doseq [root roots :when (fs/directory? (io/file archive root))]
      ;; An APFS clone where the volume offers one (`cp -c`), else a copy.
      (let [{exit :exit} @(process/process {:out :string :err :string}
                                           "cp" "-c" "-R" (str (io/file archive root)) target)]
        (when-not (zero? exit)
          (fs/copy-tree (io/file archive root) (io/file target root)))))
    target))

(defn- git!
  "Run git in `checkout` with `environment`: its trimmed output; a refusal throws its error map."
  {:malli/schema [:=> [:cat :string :map [:vector :string]] :string]}
  [checkout environment argv]
  (let [{:keys [exit out err]} @(apply process/process {:dir checkout :extra-env environment :out :string :err :string}
                                                       "git" argv)]
    (when-not (zero? exit)
      (let [error (failure `candidate-commit! "git refused the candidate."
                           {:seon.test.platform/argv argv :seon.test.platform/output err})]
        (throw (ex-info (:seon.error/message error) error))))
    (str/trim out)))

(defn candidate-commit!
  "A commit object whose tree is the published commit's with `paths` taken
  from `snapshot`, its parent the published commit, written through a
  temporary index: HEAD, the shared index and the working tree are untouched."
  {:malli/schema [:=> [:cat :seon.test.platform/published :seon.fn/root [:vector :seon.source/path]]
                  :seon.test.run/git-sha]}
  [{sha :seon.test.run/git-sha checkout :seon.test.platform/checkout} snapshot paths]
  (let [index (str (fs/create-temp-file {:prefix "seon-check-platform-index"}))
        environment {"GIT_INDEX_FILE" index}]
    (try
      (git! checkout environment ["read-tree" sha])
      (git! checkout environment (into ["--work-tree" snapshot "add" "--"] paths))
      (git! checkout environment ["commit-tree" (git! checkout environment ["write-tree"]) "-p" sha
                                  "-m" (str "check-platform: " sha " + " (str/join " " paths))])
      (finally (fs/delete-if-exists index)))))

(defn- check-bound-ms
  "The platform check's wall bound: the operator's boot and check bounds and its silence backstop."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:int {:min 1}]]}
  [database]
  (let [effective (config/effective database (db/q '[:find ?n . :where [_ :seon.cluster/name ?n]] database))
        dials (merge (select-keys config/defaults [:seon.config.operator/boot-bound-ms :seon.config.operator/check-bound-ms
                                                   :seon.config.operator/event-silence-backstop-ms])
                     (when-not (:seon.error/at effective) effective))]
    (+ (:seon.config.operator/boot-bound-ms dials) (:seon.config.operator/check-bound-ms dials)
       (:seon.config.operator/event-silence-backstop-ms dials))))

(defn- diagnostic
  "What the operator wrote to `file` for a refused check, as data: the file, its message and cause, and the
  check's test summary and phases where it reached them; nil when it wrote none (an operator older than
  `SEON_DIAGNOSTIC_FILE` writes to its own log name)."
  {:malli/schema [:=> [:cat :string] [:maybe :seon.test.platform/diagnostic]]}
  [file]
  (when (fs/exists? file)
    (let [written (edn/read-string {:default (fn [_ value] value)} (slurp file))
          evidence (:seon.error/offending written)]
      (merge {:seon.test.platform/diagnostic-file file}
           (when (string? (:seon.error/message written)) (select-keys written [:seon.error/message]))
           (when (map? evidence)
               (select-keys evidence [:seon.operator/test-summary :seon.operator/phases :seon.operator/test-phases]))))))

(defn run-check!
  "Run `tests` with `bin/seon check --at candidate` in a throwaway JVM within
  its bound and answer the members its runner wrote, or the error naming what
  failed, carrying what the operator's diagnostic file holds (`:seon.test.platform/diagnostic`). A check over its
  bound is ended and its exit awaited."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.platform/published :seon.test.run/git-sha
                       :seon.fn/root [:set :seon.test/sym]]
                  [:or :seon.test.platform/check-result :seon.error/base]]}
  [database {checkout :seon.test.platform/checkout} candidate snapshot tests]
  (let [bound (check-bound-ms database)
        result-file (str (io/file snapshot "check-result.edn"))
        ;; Outside the snapshot, which the caller deletes: the refusal names it.
        diagnostic-file (str (io/file checkout "data/clusters" (db/q '[:find ?n . :where [_ :seon.cluster/name ?n]] database)
                                      "logs" (str "operator-check-platform-" (id/id) ".edn")))
        argv (into [(str (io/file checkout "bin/seon")) "check" "--at" candidate "--include-long"]
                   (mapcat (fn [test-symbol] ["--test" (str test-symbol)])) (sort tests))
        began (System/nanoTime)
        child (apply process/process {:dir checkout :extra-env {"SEON_TEST_RESULT_FILE" result-file
                                                                 "SEON_DIAGNOSTIC_FILE" diagnostic-file}
                                      :out :string :err :string} argv)
        exited? (.waitFor ^Process (:proc child) bound TimeUnit/MILLISECONDS)]
    (when-not exited?
      (process/destroy-tree child)
      (.waitFor ^Process (:proc child)))
    (let [{:keys [exit out err]} @child
          ms (long (/ (- (System/nanoTime) began) 1e6))
          written (when (fs/exists? result-file) (edn/read-string (slurp result-file)))
          refused (fn [message data]
                    (failure `run-check! message (cond-> data
                                                   (fs/exists? diagnostic-file)
                                                   (assoc :seon.test.platform/diagnostic (diagnostic diagnostic-file)))))]
      (cond
        (not exited?)
        (refused "The platform check did not exit within its bound; it was ended and its exit awaited."
                 {:seon.test.platform/bound-ms bound :seon.test.platform/argv argv})
        (not (map? written))
        (refused "The platform check wrote no run result: its throwaway JVM did not run the tests."
                 {:seon.test.platform/exit exit :seon.test.platform/argv argv
                  :seon.test.platform/output (str/join "\n" (take-last 30 (str/split-lines (str out err))))})
        (:seon.error/message written)
        (refused (str "The platform check's test request refused: " (:seon.error/message written))
                 {:seon.test.platform/argv argv})
        :else (assoc written :seon.test.platform/check-ms ms :seon.test.run/git-sha candidate)))))

(defn record!
  "Record `checked`, a platform run of tests of `tested`, on `connection`'s
  branch of the cluster `cluster-name` through the ordinary admission and result writers: its member
  results keyed by their reach digests in `tested`, the run naming the
  candidate commit as `:seon.test.run/git-sha`. Answers the recorded results."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/database-value :seon.cluster/name :seon.test.platform/check-result]
                  [:or :seon.test/results :seon.error/base]]}
  [connection tested cluster-name {results :seon.test/results candidate :seon.test.run/git-sha}]
  (let [provenance (runner/provenance tested)
        selection (when (and (seq results) (not (:seon.error/at provenance)))
                    (test/selection-admission
                     {:seon.db/db tested
                      :seon.test.run/cluster [:seon.cluster/name cluster-name]
                      :seon.test.run/policy :named
                      :seon.test/rerun? true
                      :seon.test/include-long? true
                      :seon.test/identities (set (map :seon.test/sym results))
                      :seon.test.run/provenance (assoc provenance :seon.test.run/git-sha candidate)}))]
    (cond
      (empty? results) (failure `record! "The platform run answered no member results." {})
      (:seon.error/at provenance) provenance
      (:seon.error/at selection) selection
      :else
      (let [admitted (db/transact! connection [[:db.fn/call test/admit-run selection]])]
        (if (:seon.error/at admitted)
          admitted
          (let [recorded (runner/commit-results!
                          connection
                          {:seon.db/db tested
                           :seon.test.runner/results (mapv #(assoc % :seon.test.run/terminated? true) results)
                           :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                           :seon.test/run-at (:seon.test.run/at provenance)
                           :seon.test.run/provenance (:seon.test.run/provenance selection)
                           :seon.test.run/terminated? true})]
            ;; A refused recording leaves the admitted run stopped, so its
            ;; members read unknown and cover no later request.
            (when (:seon.error/at recorded)
              (db/transact! connection [[:db/add [:seon.test.run/id (get-in selection [:seon.test.run/provenance :seon.test.run/id])]
                                         :seon.test.run/stopped :recording-refused]]))
            recorded))))))
