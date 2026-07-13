(ns seon.embed.preflight
  "Loud, third-party-facing self-check for the embedding-backed wire-server.

   The embedding feature has FOUR independent silent-degrade-to-no-hits modes
   (research embeddings-packaging-2026-06-21 §E): Java < 22 / no
   `jdk.incubator.vector`; `SEON_EMBED` unset; `GEMINI_API_KEY` blank; and a
   live Gemini round-trip that returns nothing. Each of those, in normal
   operation, makes embeddings quietly inert — a consumer gets ZERO hits with
   NO error. That is correct for a consumer who has not opted in, but it makes
   a misconfigured opt-in indistinguishable from a working one.

   `run!` converts those four modes into ONE explicit pass/fail with DISTINCT
   non-zero exit codes, so `java -jar … --preflight` is a real gate a third
   party can script against. It is invoked from `seon.server.boot/-main` when
   `--preflight` is passed; it NEVER touches the durable cluster store (the
   install!/KNN self-test runs against a throwaway `:memory` datahike conn)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            ;; Loading this require-chain registers the :proximum secondary
            ;; index type at runtime (the same require boot.clj does), so the
            ;; self-test's install! can declare a :proximum index.
            [datahike.index.secondary.proximum]
            [seon.embed :as embed]))

;;; --- Exit codes (DISTINCT per failure mode) --------------------------------
;;;
;;; 0 = all checks passed. Each failure has its OWN code so a deploy script can
;;; branch on exactly what is wrong rather than re-deriving it from a log.

(def exit-codes
  {:ok                 0
   :java-vector-absent 10   ; Java < 22 / jdk.incubator.vector module missing
   :seon-embed-unset   11   ; SEON_EMBED master switch not set
   :gemini-key-blank   12   ; GEMINI_API_KEY unset/blank
   :embed-roundtrip    13   ; embed-text did NOT return a 1536-length vector
   :knn-selftest       14}) ; install! + one-row KNN top-1 was not the seeded row

;;; --- Individual checks -----------------------------------------------------
;;;
;;; Each returns nil on pass, or a {:code <kw> :msg <string>} failure map. The
;;; message is legible + actionable: it names the exact defect and the fix.

(defn- check-java-vector
  "Java 22+ AND the `jdk.incubator.vector` module must be present — Proximum's
   SIMD distance kernels require it (and the `--add-modules jdk.incubator.vector`
   JVM flag). We probe the module's presence by attempting to load a class from
   it; absent module ⇒ ClassNotFoundException."
  []
  (let [version  (System/getProperty "java.version")
        major    (try (Integer/parseInt (first (str/split (str version) #"[.\-+]")))
                      (catch Exception _ 0))
        vector-present?
        (try
          (Class/forName "jdk.incubator.vector.FloatVector")
          true
          (catch Throwable _ false))]
    (cond
      (< major 22)
      {:code :java-vector-absent
       :msg  (str "Java " version " is too old. Proximum's SIMD vector kernels "
                  "require Java 22+. Install a JDK >= 22 and re-run.")}

      (not vector-present?)
      {:code :java-vector-absent
       :msg  (str "Java " version " is >= 22 but the jdk.incubator.vector module "
                  "is not on the module path. Add the JVM flags:\n"
                  "  --add-modules jdk.incubator.vector "
                  "--enable-native-access=ALL-UNNAMED")}

      :else nil)))

(defn- check-seon-embed
  "The SEON_EMBED master switch must be set, or the entire feature is inert
   (no index declared, transaction transform passes through, backfill no-ops)."
  []
  (when-not (embed/embed-feature-enabled?)
    {:code :seon-embed-unset
     :msg  "SEON_EMBED is not set. The embedding feature is the master-OFF state: no Proximum index is declared and no embeddings are computed. Export SEON_EMBED=1 and re-run."}))

(defn- check-gemini-key
  "GEMINI_API_KEY must be non-blank, or every Gemini embed call no-ops."
  []
  (when (str/blank? (System/getenv "GEMINI_API_KEY"))
    {:code :gemini-key-blank
     :msg  "GEMINI_API_KEY is unset/blank. The wire-server boots and accepts writes, but no text is ever embedded (silent no-hits). Export GEMINI_API_KEY=<your key> and re-run."}))

(defn- check-embed-roundtrip
  "A REAL `embed-text` round-trip must return a vector of length
   `embedding-dim` (1536). This catches a present-but-invalid key, blocked
   egress, or a model/dimension mismatch — all of which otherwise degrade to
   silent no-hits."
  []
  (try
    (let [v (:seon.embed/vector
             (embed/embed-text {:seon.embed/text "preflight embedding round-trip probe"}))]
      (when-not (= embed/embedding-dim (count v))
        {:code :embed-roundtrip
         :msg  (str "embed-text returned a vector of length " (count v)
                    ", expected " embed/embedding-dim
                    ". The model/dimension config or the API response is wrong.")}))
    (catch Throwable t
      {:code :embed-roundtrip
       :msg  (str "embed-text threw during the round-trip probe: "
                  (.getMessage t)
                  ". Check GEMINI_API_KEY validity and network egress to Gemini.")})))

(defn- check-knn-selftest
  "Full-stack self-test against a THROWAWAY `:memory` datahike conn (never the
   durable cluster store): install! the attr + Proximum index, add one
   throwaway trigger to immutable pipeline data, write ONE row, then KNN-search
   for that row's own text and assert the top-1 hit is the seeded entity. This
   is the only check
   that proves the WHOLE chain — Gemini embed-on-write, the :proximum index
   registration, durable-vector restore-skeleton path, and KNN — actually
   works at jar runtime (it is the oracle for the research doc's gate that
   `register-index-type! :proximum` ran)."
  []
  (try
    (let [trigger :seon.embed.preflight/probe-text
          cfg     {:store {:backend :memory
                           ;; konserve :memory :id must be a UUID (datahike
                           ;; validates the type); a stable derived UUID keeps
                           ;; the throwaway store collision-free.
                           :id (java.util.UUID/nameUUIDFromBytes
                                (.getBytes "seon-embed-preflight-selftest" "UTF-8"))}
                   :schema-flexibility :write
                   :keep-history? false}]
      (when (d/database-exists? cfg) (d/delete-database cfg))
      (d/create-database cfg)
      (let [conn (d/connect cfg)]
        (try
          (embed/install! conn)
          ;; declare the trigger attr on the conn's :write schema
          (d/transact conn [{:db/ident       trigger
                             :db/valueType   :db.type/string
                             :db/cardinality :db.cardinality/one}])
          (let [probe   "the quick brown fox jumps over the lazy dog"
                embeddables
                (assoc (embed/default-embeddables)
                       trigger
                       (fn [entity] (some-> (get entity trigger) str)))
                ;; Run the same explicit transform writer boot composes.
                augment (embed/augment-tx-with-embeddings
                         embeddables (d/db conn)
                         [{:db/id "probe" trigger probe}])
                report  (d/transact conn augment)
                eid     (get-in report [:tempids "probe"])
                {:seon.embed/keys [hits]}
                (embed/knn-search (d/db conn)
                                  {:seon.embed/query probe :seon.embed/k 1})
                top     (first hits)]
            (cond
              (nil? eid)
              {:code :knn-selftest
               :msg  "KNN self-test: the seeded probe row did not resolve to an eid."}

              (empty? hits)
              {:code :knn-selftest
               :msg  "KNN self-test: install!+write succeeded but the Proximum index returned ZERO neighbours. The :proximum index is not live (register-index-type! / embed-on-write did not run)."}

              (not= eid (:seon.embed/eid top))
              {:code :knn-selftest
               :msg  (str "KNN self-test: top-1 hit was eid " (:seon.embed/eid top)
                          ", expected the seeded row eid " eid ".")}

              :else nil))
          (finally
            (d/release conn)
            (when (d/database-exists? cfg) (d/delete-database cfg))))))
    (catch Throwable t
      {:code :knn-selftest
       :msg  (str "KNN self-test threw: " (.getMessage t)
                  ". This usually means the :proximum secondary index type "
                  "failed to register or the SIMD vector module is missing.")})))

;;; --- Driver ----------------------------------------------------------------

(def ^:private checks
  "Ordered: cheap/structural gates first (no network), then the live Gemini
   round-trip, then the full KNN self-test. Short-circuits on the first
   failure so a missing key never burns a Gemini call."
  [check-java-vector
   check-seon-embed
   check-gemini-key
   check-embed-roundtrip
   check-knn-selftest])

(defn run-preflight!
  "Run the embedding-feature preflight. Prints a per-check PASS/FAIL line and
   returns the process exit code: 0 when every check passes, or the DISTINCT
   non-zero code of the FIRST failing check (see `exit-codes`). Short-circuits
   on first failure. Does NOT call System/exit — the caller (boot/-main) does,
   so this stays testable."
  []
  (println "[preflight] embedding-backed wire-server self-check")
  (loop [[c & more] checks]
    (if (nil? c)
      (do (println "[preflight] PASS — all checks green; embeddings are live.")
          (:ok exit-codes))
      (if-let [{:keys [code msg]} (c)]
        (do (binding [*out* *err*]
              (println "[preflight] FAIL —" (name code))
              (println "[preflight]  " msg))
            (get exit-codes code 1))
        (recur more)))))
