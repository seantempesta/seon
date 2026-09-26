(ns seon.dev.dependency-digest
  "THE ONE dependency-set identity the development class cache keys on.

  `dev_cache.clj` asks whether this checkout's dependency SET changed and
  answers from `deps.edn` and the `reference-code` submodule pins, adding
  this runtime's identity, because compiled bytecode is only valid under the
  runtime that produced it.

  This namespace requires nothing outside `clojure.core`, `clojure.edn`,
  `clojure.java.io` and `clojure.string` so both consumers can reach it:
  `dev_cache.clj` runs on tools.deps' tool classpath and loads this file by path, while the operator
  and the edit hook run it on babashka's `script` classpath."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

;; A gate run root is a `git archive` extraction, so `bin/test` records the
;; source repository's submodule pins here at snapshot time. The name is
;; shared with `bin/test`, which writes it beside `changed-paths.txt`.
(def dependency-pins-file "dependency-pins.txt")

(defn- canonical-file
  [path]
  (.getCanonicalFile (io/file path)))

(defn- digest-bytes!
  [^MessageDigest digest value]
  (let [value-bytes (if (string? value)
                      (.getBytes ^String value
                                 java.nio.charset.StandardCharsets/UTF_8)
                      value)]
    (.update digest ^bytes value-bytes)
    (.update digest (byte-array [(byte 0)]))))

(def pin-query-bound-ms
  "The bound on one `git ls-files --stage` submodule pin query.

  Measured 2026-09-17 on this checkout: 14 ms for 8,250 bytes of pin output.
  Its firing is the loud report that a git index read never returned — it
  names the command it bounded, and no caller retries it silently."
  10000)

(defn- git-dependency-pins
  "The submodule pins `root`'s own git index states, or nothing.

  `git ls-files` answers for the PREFIX it runs in: a directory nested inside
  the repository but without its own work tree answers exit 0 with ZERO
  bytes, which digests exactly like a tree whose forks all sit at different
  commits. Empty output is therefore not an answer here — it is the absence
  of one, and the caller falls through to the recorded pins."
  [root]
  (let [command ["git" "-C" (str root) "ls-files" "--stage" "--" "reference-code"]
        child (.start (doto (ProcessBuilder. ^java.util.List command)
                        (.redirectErrorStream true)))
        output (future (slurp (.getInputStream child)))
        exited? (.waitFor child pin-query-bound-ms TimeUnit/MILLISECONDS)
        evidence {:seon.dev-cache/command command :seon.dev-cache/bound-ms pin-query-bound-ms}]
    (when-not exited?
      (.destroyForcibly child)
      (.waitFor child pin-query-bound-ms TimeUnit/MILLISECONDS))
    ;; The output reader ends at the pipe's close, which follows the exit.
    (let [text (deref output 1000 ::unfinished)]
      (cond
        (.isAlive child) (throw (ex-info "Dependency pin query survived forced termination." evidence))
        (not exited?) (throw (ex-info "Dependency pin query exceeded its execution bound." evidence))
        (= ::unfinished text) (throw (ex-info "Dependency pin query output never ended after its exit." evidence))
        (and (zero? (.exitValue child)) (not (str/blank? text))) text))))

(defn- recorded-dependency-pins
  "The pins `bin/test` recorded into this root at snapshot time, or nothing.

  A gate run root is an extraction, not a checkout: its dependency identity
  travels WITH it instead of being re-derived from a source index that can
  move while the gate runs. The recorded bytes are the source repository's
  own `ls-files --stage` output, so a recorded root and its source repository
  digest identically."
  [root]
  (let [file (io/file root dependency-pins-file)]
    (when (.isFile file)
      (let [text (slurp file)]
        (when-not (str/blank? text)
          text)))))

(defn- pins-unavailable
  "The flat refusal for a root whose dependency pins no source can state."
  {:malli/schema [:=> [:cat :seon.schema/value]
                  [:and :seon.error/base [:map [:seon.error/data :map]]]]}
  [root]
  (let [directory (canonical-file root)]
    {:seon.error/at (java.util.Date.)
     :seon.error/layer :seon.dev-cache/dependency-configuration
     :seon.error/operation 'seon.dev.dependency-digest/dependency-pins
     :seon.error/message
     (str "The dependency pins of " (.getCanonicalPath directory)
          " cannot be read: it is not a git work tree that states them, and it"
          " carries no " dependency-pins-file " recorded by bin/test. An empty"
          " pin set would key the dependency-class cache to every fork commit"
          " at once, so no cache is reused or created here.")
     :seon.error/member :seon.dev-cache/dependency-pins
     :seon.error/expected {:seon.dev-cache/git-command
                           ["git" "-C" (.getCanonicalPath directory) "ls-files" "--stage" "--"
                            "reference-code"]
                           :seon.dev-cache/recorded-pins-file dependency-pins-file}
     :seon.error/offending (.getCanonicalPath directory)
     :seon.error/data {:seon.dev-cache/git-pins-stated false
                       :seon.dev-cache/recorded-pins-present
                       (.isFile (io/file directory dependency-pins-file))}}))

(defn dependency-pins
  "The submodule pins this root's dependency classes are keyed on.

  The recorded snapshot pins win when present: they are the bytes this root
  was built from. Otherwise the root's own git index states them. When
  neither source answers, this REFUSES — a cache keyed on unknown pins is
  worse than no cache, because it silently outlives every fork commit."
  [root]
  (or (recorded-dependency-pins root)
      (git-dependency-pins root)
      (let [refusal (pins-unavailable root)]
        (throw (ex-info (:seon.error/message refusal) refusal)))))

(def ^:private launch-options
  "Alias keys that choose how a JVM or tool starts, never which dependency
  bytes load: compiled dependency classes do not depend on them."
  [:jvm-opts :main-opts :exec-fn :exec-args :ns-default :ns-aliases])

(defn dependency-declarations
  "`deps.edn`'s value without its aliases' launch options: the declarations
  that choose the dependency set (observed 2026-09-26: c2b757cb3 changed only
  a `:jvm-opts` HeapDumpPath and missed the class cache on every restart)."
  {:malli/schema [:=> [:cat :map] :map]}
  [manifest]
  (cond-> manifest
    (map? (:aliases manifest))
    (update :aliases update-vals #(if (map? %) (apply dissoc % launch-options) %))))

(defn- digest-declarations!
  [^MessageDigest digest root]
  ;; Both consumers (babashka and a JVM) print the value alike only under
  ;; the same printer settings, bound here.
  (digest-bytes! digest (binding [*print-namespace-maps* false *print-meta* false
                                  *print-length* nil *print-level* nil]
                          (pr-str (dependency-declarations
                                   (edn/read-string (slurp (io/file root "deps.edn")))))))
  (digest-bytes! digest (dependency-pins root))
  digest)

(def runtime-properties
  "The runtime identity a compiled dependency class is valid under."
  ["java.runtime.version" "java.vendor" "java.vm.name" "os.arch"])

(defn configuration-digest
  "The digest of `root`'s DECLARED dependency set (`deps.edn` and the submodule
  pins) plus a runtime's identity.

  The development class cache keys on this: it stores COMPILED bytecode,
  which is only valid under the runtime that produced it.

  The one-argument arity digests THIS runtime. The operator runs on babashka
  and launches another JVM, so it supplies that JVM's `runtime-properties`
  values, read from the JVM itself (`java -XshowSettings:properties`)."
  ([root]
   (configuration-digest root (into {} (map (juxt identity #(System/getProperty %)))
                                    runtime-properties)))
  ([root properties]
   (let [digest (digest-declarations! (MessageDigest/getInstance "SHA-256") root)]
     (doseq [property runtime-properties]
       (digest-bytes! digest (get properties property)))
     (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest))))))
