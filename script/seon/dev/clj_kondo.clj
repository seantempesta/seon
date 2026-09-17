(ns seon.dev.clj-kondo
  "Native clj-kondo dependency-cache ownership for development tools."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [seon.operator.state :as operator.state]
            [seon.dev.dependency-digest :as dependency-digest]
            [seon.dev.state :as state]))

(defn- state-path
  [root]
  (fs/path root "tmp/test-changed/dependency-cache.edn"))

(def population-bound-ms
  "The bound on ONE dependency-analysis population of `root`'s clj-kondo cache.

  It covers resolving the classpath and analysing every dependency source on
  it. Measured 2026-09-17 on this checkout: 10,770 ms for a complete
  population, 28 ms for the classpath resolution alone. A firing names the
  subprocess that never returned; nothing retries it silently."
  300000)

(def clj-kondo-output-config
  "The one output/analysis configuration shared by the edit hook and operator
  preflight. Partial first-party lint runs keep the persistent cache disabled."
  "{:output {:format :edn} :analysis {:var-usages true}}")

(defn- cache-contents
  "The cache files present, by size — a listing of the cache's own directory.

  Sizes, not content digests: a population writes whole files under its own
  lock, so what a later check must notice is a cache file that went AWAY or
  was rewritten, and the directory listing states both. Measured 2026-09-17 on
  this checkout: 43 ms for 3,486 entries, against 241 ms to digest their
  19 MB."
  [root]
  (let [directory (fs/path root ".clj-kondo/.cache")]
    (into (sorted-map)
          (for [file (when (fs/directory? directory)
                       (fs/glob directory "**/*.transit.json"))
                :when (fs/regular-file? file)]
            [(str (fs/relativize directory file)) (fs/size file)]))))

(defn ensure-dependency-cache!
  "Populate dependency analysis for `root`, once per dependency-set change.

  The recorded key is `seon.dev.dependency-digest/dependency-set-digest` —
  the declarations the development class cache also keys on, without the
  runtime identity that cache needs, because clj-kondo's analysis is written
  by a native binary and read from babashka, a JVM test and the gate alike. So
  this runs the population when, and only when, the declared dependency SET
  changed or the cache it wrote is no longer the cache on disk. A current cache costs a digest and a
  directory listing; a stale one costs a complete analysis, so callers that
  carry their own deadline run this OUTSIDE it.

  The three answers are `:current`, `:warmed`, and `:unavailable` with the
  reason a population could not be proven — never silence."
  [root]
  (try
    (let [digest (dependency-digest/dependency-set-digest root)
          recorded (state/read-edn (state-path root))
          contents (cache-contents root)]
      (if (and (= digest (::input-digest recorded))
               (seq (::contents recorded))
               (= contents (::contents recorded)))
        {::status :current ::input-digest digest}
        (let [classpath-result
              (operator.state/run-process!
               {:seon.operator.subprocess/argv ["clojure" "-Spath"]
                :seon.operator.subprocess/directory root
                :seon.operator.subprocess/deadline-ms population-bound-ms})
              classpath (str/trim
                         (:seon.operator.subprocess/output classpath-result))
              _ (when (or (not (zero? (:seon.operator.subprocess/exit
                                       classpath-result)))
                          (str/blank? classpath))
                  (throw (ex-info "Could not derive the project classpath"
                                  classpath-result)))
              result
              (operator.state/run-process!
               {:seon.operator.subprocess/argv
                ["clj-kondo" "--lint" classpath "--parallel" "--copy-configs"
                 "--skip-lint" "--config" "{:output {:analysis true}}"]
                :seon.operator.subprocess/directory root
                :seon.operator.subprocess/deadline-ms population-bound-ms})
              contents (cache-contents root)]
          ;; Without --dependencies, stale jar skip markers cannot suppress
          ;; parsing. Explicit analysis enables cache publication with skip-lint.
          (when (or (not (zero? (:seon.operator.subprocess/exit result)))
                    (empty? contents))
            (throw (ex-info "Dependency analysis did not populate its cache"
                            result)))
          (state/write-edn! (state-path root)
                            {::input-digest digest ::contents contents})
          {::status :warmed ::input-digest digest})))
    (catch Exception error
      {::status :unavailable ::reason (.getMessage error)})))
