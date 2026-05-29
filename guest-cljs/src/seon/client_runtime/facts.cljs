(ns seon.client-runtime.facts
  "Facts knowledge base API.

   A 'fact' is a subject/predicate/object triple plus provenance:

       {:fact/subject   :seon/project
        :fact/predicate :uses-library
        :fact/object    :datahike   ;; or any EDN-serializable value
        :fact/source    \"/seon-src/CLAUDE.md\"
        :fact/recorded-by :agent/foo
        :fact/confidence 0.9
        :fact/tags      #{:vision}}

   Internally :fact/object is stored as a `pr-str`'d EDN string so we don't
   need per-shape schemas. record-fact! prints, query helpers `clojure.edn/read-string`
   back.

   Schema + seed data live at
   `/seon-src/resources/seed/`
   (also reachable via the host filesystem; the seeder reads them through
   the /seon-src WASI preopen)."
  (:require [seon.client-runtime.db :as d]
            [seon.client-runtime.fs       :as fs]
            [clojure.edn          :as edn]
            [clojure.string       :as str]))

;; ---------- schema install (idempotent) ----------

(defn schema-installed?
  "True if the live writer schema already knows about :fact/id."
  [conn]
  (let [s @(:schema conn)]
    (or (and (string? s) (str/includes? s ":fact/id"))
        (boolean (some-> s (.indexOf ":fact/id") (>= 0))))))

(defn install-schema!
  "Transact the facts schema (idempotent). Reads the EDN from /seon-src so the
   schema text lives next to the seed data, not duplicated in the bundle."
  [conn]
  (let [schema-edn (fs/read-file
                    "/seon-src/resources/seed/facts-schema.edn")]
    ;; Transact the raw EDN string — the JVM writer parses it.
    (d/transact! conn (edn/read-string schema-edn))))

(defn ensure-schema!
  "Install the schema unless :fact/id is already in the live schema. Returns
   :installed | :already-present."
  [conn]
  ;; Always probe the live schema rather than trust the cached one.
  (let [s (try (d/schema conn) (catch :default _ ""))]
    (reset! (:schema conn) s)
    (if (and (string? s) (str/includes? s ":fact/id"))
      :already-present
      (do (install-schema! conn)
          (reset! (:schema conn) (try (d/schema conn) (catch :default _ "")))
          :installed))))

;; ---------- record / query ----------

(defn record-fact!
  "Transact one fact. `:id` is required (unique identity). Fills in
   `:fact/recorded-at` (now) and `:fact/recorded-by` (:agent/unknown by
   default) when absent. The `object` may be any pr-str-able value; it is
   stored as a serialized EDN string and rehydrated on read.

   Returns the transact result map."
  [conn {:keys [id subject predicate object source confidence tags recorded-by]
         :or   {confidence 50 recorded-by :agent/unknown tags #{}}}]
  (when-not id
    (throw (ex-info "record-fact! requires :id" {:fact/missing :id})))
  (when-not (keyword? subject)
    (throw (ex-info "record-fact! :subject must be a keyword" {:got subject})))
  (when-not (keyword? predicate)
    (throw (ex-info "record-fact! :predicate must be a keyword" {:got predicate})))
  (let [m (cond-> {:fact/id          id
                   :fact/subject     subject
                   :fact/predicate   predicate
                   :fact/object      (if (string? object) object (pr-str object))
                   :fact/recorded-by recorded-by
                   :fact/recorded-at (js/Date.)
                   :fact/confidence  (long confidence)}
            source       (assoc :fact/source source)
            (seq tags)   (assoc :fact/tags  (vec tags)))]
    (d/transact! conn [m])))

(defn- parse-fact-rows
  "Given a [[id subject predicate object source confidence]] result, parse the
   object EDN back to a value and return rich maps."
  [rows]
  (mapv (fn [[id s p obj src conf]]
          {:fact/id         id
           :fact/subject    s
           :fact/predicate  p
           :fact/object     (try (edn/read-string obj) (catch :default _ obj))
           :fact/source     src
           :fact/confidence conf})
        rows))

(defn facts-about
  "All facts where :fact/subject = `subject` (keyword). Returns vec of
   fact maps.

   NOTE: the PoC wire protocol passes Datalog args as CBOR strings (not
   EDN-typed values), so we inline the keyword into the query rather than
   parameterizing on it."
  [conn subject]
  (let [q [:find '?id '?s '?p '?o '?src '?c
           :where
           ['?e :fact/subject subject]
           ['?e :fact/id '?id]
           ['?e :fact/subject '?s]
           ['?e :fact/predicate '?p]
           ['?e :fact/object '?o]
           ['?e :fact/confidence '?c]
           [(list 'get-else '$ '?e :fact/source "") '?src]]
        rows (d/q q conn)]
    (parse-fact-rows rows)))

(defn facts-with-predicate
  "All facts where :fact/predicate = `predicate` (keyword)."
  [conn predicate]
  (let [q [:find '?id '?s '?p '?o '?src '?c
           :where
           ['?e :fact/predicate predicate]
           ['?e :fact/id '?id]
           ['?e :fact/subject '?s]
           ['?e :fact/object '?o]
           ['?e :fact/confidence '?c]
           [(list 'get-else '$ '?e :fact/source "") '?src]]
        rows (d/q q conn)]
    (parse-fact-rows rows)))

(defn fact-count
  "Total number of facts in the DB."
  [conn]
  (let [rows (d/q "[:find (count ?e) :where [?e :fact/id _]]" conn)]
    (or (some-> rows first first) 0)))

;; ---------- seeder ----------

(defn- normalize-seed-fact
  "Seed-file entries have :fact/object as a raw EDN value (a keyword, a
   string literal, etc.). The DB schema stores it as :db.type/string, so we
   pr-str the value before transacting. If the seed already wrote a
   string-of-EDN (a fact authored with the surface API), pass through."
  [m]
  (if-let [obj (:fact/object m)]
    (assoc m :fact/object (if (string? obj) obj (pr-str obj)))
    m))

(defn seed!
  "Read the seed-facts EDN from /seon-src and transact each. Idempotent —
   :fact/id is :db.unique/identity so re-runs upsert.

   Returns {:installed-schema?, :seeded N, :total-after N}."
  [conn]
  (let [schema-status (ensure-schema! conn)
        seed-edn      (fs/read-file
                       "/seon-src/resources/seed/facts-seed.edn")
        raw-facts     (edn/read-string seed-edn)
        facts-vec     (mapv normalize-seed-fact raw-facts)]
    ;; Transact in one big batch.
    (d/transact! conn facts-vec)
    {:installed-schema? (= schema-status :installed)
     :seeded            (count facts-vec)
     :total-after       (fact-count conn)}))
