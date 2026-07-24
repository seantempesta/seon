(ns seon.db.writer-test-support
  "Shared admitted database-session fixtures for JVM writer tests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.config.resolve :as config.resolve]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form])
  (:import [java.nio.file Path Paths]
           [java.security MessageDigest]))

(def ^:private fixture-hardware
  {:seon.hardware/cores 8
   :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
   :seon.hardware/fd-soft-limit 2048})

(def guard-policy
  "The production-resolved default SCI guard facts for JVM writer fixtures."
  (select-keys
   (config.resolve/resolve-config-singleton
    {:seon.config/guard
     {:seon.config.guard/output-cap 16384}}
    {}
    fixture-hardware)
   (keys config.resolve/guard-budget-schemas)))

(defn canonical-schema-rows
  "Derive fixture schema rows from the loaded canonical schema authority."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (schema/canonical-schema-rows (java.util.Date.)))

(defn- schema-forms
  [rows]
  (into {}
        (keep (fn [{:seon.schema/keys [key form]}]
                (when (and key form)
                  [key (edn/read-string form)])))
        rows))

(defn- bytes->hex
  [byte-values]
  (apply str (map #(format "%02x" (bit-and 0xff %)) byte-values)))

(defn- file-digest
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [stream (io/input-stream (str path))]
      (loop []
        (let [n-read (.read stream buffer)]
          (when (pos? n-read)
            (.update digest buffer 0 n-read)
            (recur)))))
    (bytes->hex (.digest digest))))

(defn- selected-artifact-process-path
  []
  (let [root (System/getProperty "user.dir")
        process-dir (or (System/getenv "SEON_PROC_DIR")
                        "tmp/seon-operator")]
    (.normalize
     (.resolve (Paths/get root (make-array String 0))
               process-dir))))

(defn- selected-artifact-manifest-path
  []
  (if-let [manifest (System/getenv "SEON_WRITER_ARTIFACT_MANIFEST")]
    (.normalize (Paths/get manifest (make-array String 0)))
    (.resolve ^Path (selected-artifact-process-path) "artifact.edn")))

(defn- sha256-digest?
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- assert-program-artifact-manifest!
  [manifest manifest-path]
  (let [required-strings
        [:seon.dev.artifact/runtime-root
         :seon.dev.artifact/program-source-path
         :seon.dev.artifact/program-row-path]
        required-digests
        [:seon.dev.artifact/application-digest
         :seon.dev.artifact/program-source-digest
         :seon.dev.artifact/program-row-digest]]
    (when-not
     (and (= 11 (:seon.dev.artifact/version manifest))
          (every? #(string? (get manifest %)) required-strings)
          (every? #(sha256-digest? (get manifest %)) required-digests))
      (throw
       (ex-info "The selected artifact manifest has no valid program rows."
                {:seon.dev.artifact/path (str manifest-path)
                 :seon.dev.artifact/version
                 (:seon.dev.artifact/version manifest)
                 :seon.dev.artifact/missing-or-invalid
                 (into []
                       (remove
                        (fn [key]
                          (let [value (get manifest key)]
                            (if (some #{key} required-digests)
                              (sha256-digest? value)
                              (string? value)))))
                       (concat required-strings required-digests))
                 :seon.error/kind :core-bug})))
    manifest))

(defn- verified-artifact-member
  [runtime-root relative-path expected-digest label]
  (let [root (.normalize (Paths/get runtime-root (make-array String 0)))
        path (.normalize (.resolve root relative-path))]
    (when-not (.startsWith path root)
      (throw
       (ex-info "An artifact manifest member escapes its runtime root."
                {:seon.dev.artifact/member label
                 :seon.dev.artifact/runtime-root (str root)
                 :seon.dev.artifact/path (str path)
                 :seon.error/kind :core-bug})))
    (when-not (.isFile (.toFile path))
      (throw
       (ex-info "A required artifact manifest member is absent."
                {:seon.dev.artifact/member label
                 :seon.dev.artifact/path (str path)
                 :seon.error/kind :core-bug})))
    (let [actual-digest (file-digest path)]
      (when-not (= expected-digest actual-digest)
        (throw
         (ex-info "An artifact manifest member digest does not match."
                  {:seon.dev.artifact/member label
                   :seon.dev.artifact/path (str path)
                   :seon.dev.artifact/expected expected-digest
                   :seon.dev.artifact/actual actual-digest
                   :seon.error/kind :core-bug}))))
    path))

(defonce ^:private compiled-base
  (delay
    (let [manifest-path
          (selected-artifact-manifest-path)
          _ (when-not (.isFile (.toFile manifest-path))
              (throw
               (ex-info "The selected artifact manifest is absent."
                        {:seon.dev.artifact/path (str manifest-path)
                         :seon.error/kind :core-bug})))
          manifest
          (assert-program-artifact-manifest!
           (edn/read-string (slurp (str manifest-path)))
           manifest-path)
          runtime-root (:seon.dev.artifact/runtime-root manifest)
          program-source
          (verified-artifact-member
           runtime-root
           (:seon.dev.artifact/program-source-path manifest)
           (:seon.dev.artifact/program-source-digest manifest)
           :seon.dev.artifact/program-source)
          program-row
          (verified-artifact-member
           runtime-root
           (:seon.dev.artifact/program-row-path manifest)
           (:seon.dev.artifact/program-row-digest manifest)
           :seon.dev.artifact/program-row)
          artifact (edn/read-string (slurp (str program-row)))
          rows (:seon.dev.artifact/program-rows artifact)
          forms (schema-forms rows)]
      (when-not (and (vector? rows) (every? map? rows))
        (throw
         (ex-info "The verified program-row artifact is malformed."
                  {:seon.dev.artifact/path (str program-row)
                   :seon.dev.artifact/value-type (type rows)
                   :seon.error/kind :core-bug})))
      {:seon.execution/artifact-digest
       (:seon.dev.artifact/application-digest manifest)
       :seon.db/program rows
       :seon.db/attributes (schema.form/database-attributes forms)
       :seon.dev.artifact/program-source-path (str program-source)
       :seon.dev.artifact/program-row-path (str program-row)})))

(defn seed-canonical-schema!
  "Apply the verified compiled base through production initialization pages.

   `supplemental-schema-rows` declares only schema owned by the calling
   fixture. Keeping those rows explicit prevents the process-global test
   registry from changing an otherwise immutable compiled population."
  ([session database-name initial-data]
   (seed-canonical-schema! session database-name initial-data []))
  ([session database-name initial-data supplemental-schema-rows]
   (let [base @compiled-base
         supplemental-schema-rows (vec supplemental-schema-rows)
         supplemental-attributes
         (schema.form/database-attributes
          (schema-forms supplemental-schema-rows))
         pages
         (protocol/initialization-pages
          {:seon.execution/artifact-digest
           (:seon.execution/artifact-digest base)
           :seon.db.initialization/config-manifest-digest
           "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
           :seon.db.initialization/page-rows 64
           :seon.db/attributes
           (->> (concat (:seon.db/attributes base)
                        supplemental-attributes)
                distinct
                (sort-by str)
                vec)
           :seon.db/program
           (into (:seon.db/program base) supplemental-schema-rows)
           :seon.db/initial-data (vec initial-data)})]
     (reduce
      (fn [_ page]
        (let [result
              (db.host/call!
               session
               (protocol/ensure-database-request
                {::protocol/request-id
                 (str "fixture-initialization/"
                      (:seon.db.initialization/fingerprint page) "/"
                      (:seon.db.initialization/page-index page))
                 ::protocol/database-name database-name
                 ::protocol/backend :memory
                 :seon.db/initialization-page page}))]
          (if (::protocol/success? result)
            result
            (reduced result))))
      nil
      pages))))

(def read-defaults
  "Generous finite read limits for writer tests not exercising read policy."
  {:datahike.resource/max-work 2000000000
   :datahike.resource/max-results 10000000
   :datahike.resource/max-result-weight 100000000
   ::writer/read-deadline-ms 600000})

(defn start!
  "Start a test writer with the shared finite read limits."
  [request]
  (writer/start! (assoc request ::writer/read-defaults read-defaults)))

(def ^:private sessions-by-channel
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn open-session!
  "Open and admit one database session at `path`."
  [path]
  (uds/open-session! path))

(defn channel
  "Return the raw channel retained by an admitted database session."
  [session]
  (::uds/channel session))

(defn open-channel!
  "Open one admitted session and return its retained raw channel."
  [path]
  (let [session (open-session! path)
        channel (channel session)]
    (.put sessions-by-channel channel (dissoc session ::uds/channel))
    channel))

(defn call!
  "Round-trip one request through an admitted database session."
  [session request]
  (uds/call! {::uds/session session ::uds/message request}))

(defn call-channel!
  "Round-trip through the admitted session retaining `channel`."
  [channel request]
  (call! (assoc (.get sessions-by-channel channel) ::uds/channel channel)
         request))

(defn close-session!
  "Close an admitted database session."
  [session]
  (uds/close-session! session))
