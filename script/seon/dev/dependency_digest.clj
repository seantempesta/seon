(ns seon.dev.dependency-digest
  "THE ONE dependency-set identity every development cache keys on.

  `dev_cache.clj` (the development class cache) and `seon.dev.clj-kondo` (the
  clj-kondo dependency analysis cache) answer the same question — has this
  checkout's dependency SET changed — so they answer it from the same bytes:
  `deps.edn` and the `reference-code` submodule pins. The class cache adds
  this runtime's identity, because compiled bytecode is only valid under the
  runtime that produced it; clj-kondo's analysis is not, and keying it on a
  runtime would repopulate it for every process that reads it.

  This namespace requires nothing outside `clojure.core`, `clojure.java.io`
  and `clojure.string` so both consumers can reach it: `dev_cache.clj` runs on
  tools.deps' tool classpath and loads this file by path, while the operator
  and the edit hook run it on babashka's `script` classpath."
  (:require [clojure.java.io :as io]
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

(defn- digest-file!
  [^MessageDigest digest file]
  ;; Checkout location is not an input: identical source bytes in two
  ;; isolated run roots must select the same dependency classes.
  (with-open [input (io/input-stream file)]
    (let [buffer (byte-array 65536)]
      (loop []
        (let [read-count (.read input buffer)]
          (when (pos? read-count)
            (.update digest buffer 0 read-count)
            (recur))))))
  (.update digest (byte-array [(byte 0)])))

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
        output (future (slurp (.getInputStream child)))]
    (try
      (when-not (.waitFor child pin-query-bound-ms TimeUnit/MILLISECONDS)
        (throw (ex-info "Dependency pin query exceeded its execution bound."
                        {:seon.dev-cache/command command
                         :seon.dev-cache/bound-ms pin-query-bound-ms})))
      (let [text (deref output 1000 ::unavailable)]
        (when (and (zero? (.exitValue child))
                   (string? text)
                   (not (str/blank? text)))
          text))
      (finally
        (when (.isAlive child)
          (.destroyForcibly child)
          (.waitFor child pin-query-bound-ms TimeUnit/MILLISECONDS))))))

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
     :seon.error/data
     {:seon.error/layer :seon.dev-cache/dependency-configuration
      :seon.error/operation 'dev-cache/dependency-pins
      :seon.error/member :seon.dev-cache/dependency-pins
      :seon.error/expected {:seon.dev-cache/git-command
       ["git" "-C" (.getCanonicalPath directory) "ls-files" "--stage" "--"
        "reference-code"]
       :seon.dev-cache/recorded-pins-file dependency-pins-file}
      :seon.error/offending (.getCanonicalPath directory)
      :seon.error/data {:seon.dev-cache/git-pins-stated false
       :seon.dev-cache/recorded-pins-present
       (.isFile (io/file directory dependency-pins-file))}}}))

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

(defn- digest-declarations!
  [^MessageDigest digest root]
  (digest-file! digest (io/file root "deps.edn"))
  (digest-bytes! digest (dependency-pins root))
  digest)

(defn dependency-set-digest
  "The hex digest of `root`'s DECLARED dependency set: `deps.edn` and the
  submodule pins.

  This is what a consumer keys on when the artifact it caches is independent
  of the process that produced it — clj-kondo's dependency analysis is written
  by a native binary and read by every tool, so the JVM that asked for it is
  not an input. Measured 2026-09-17 on this checkout: 15 ms, against 287 ms
  for resolving the classpath and hashing every file on it.

  The inputs are the DECLARATIONS, not the resolved artifacts, because a
  dependency change is authored in `deps.edn` or a submodule pin."
  [root]
  (let [digest (digest-declarations! (MessageDigest/getInstance "SHA-256") root)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(def runtime-properties
  "The runtime identity a compiled dependency class is valid under."
  ["java.runtime.version" "java.vendor" "java.vm.name" "os.arch"])

(defn configuration-digest
  "`dependency-set-digest`'s inputs plus a runtime's identity.

  The development class cache keys on this one: it stores COMPILED bytecode,
  which is only valid under the runtime that produced it. A consumer whose
  artifact outlives its producing process keys on `dependency-set-digest`
  instead — the two are not interchangeable, and a cache keyed on the wrong
  one is read across a boundary its producer never promised.

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
