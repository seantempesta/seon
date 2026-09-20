(ns seon.issue
  "Issues connect authored problem statements to program identities."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.cluster.message :as message]
            [seon.db :as db]
            [seon.error.refusal :as error]
            [seon.id :as id]
            [seon.repl :as repl]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.test :as seon.test]))

;;; LOAD-CYCLE BOUNDARIES. `seon.plan` reads `seon.issue/done-query` at load
;;; (`src/seon/plan.clj:599`) and `seon.turn` requires `seon.plan`, so this
;;; namespace cannot require `seon.turn` or `seon.cluster.agent` back. One
;;; resolution per var, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private turn-turns-left
  (delay (requiring-resolve 'seon.turn/turns-left)))
(defonce ^:private turn-episode-runs
  (delay (requiring-resolve 'seon.turn/episode-runs)))
(defonce ^:private turn-generated-run-tx
  (delay (requiring-resolve 'seon.turn/generated-run-tx)))
(defonce ^:private turn-next-id
  (delay (requiring-resolve 'seon.turn/next-id)))
(defonce ^:private turn-open-call
  (delay (requiring-resolve 'seon.turn/open-call)))
(defonce ^:private cluster-agent-creation-tx
  (delay (requiring-resolve 'seon.cluster.agent/creation-tx)))

(def ^:private citable-character
  "ASCII characters that join letters and digits into one citable token."
  (let [flags (boolean-array 128)]
    (doseq [character ".:/-_?!*+<>=$%"] (aset flags (int character) true))
    flags))

(defn- words
  "Maximal runs of citation characters in note text, in order.
  One index scan: the note corpus is ~7 MB per index and the character-sequence
  version of this split was 484 ms of every publication."
  [^String text]
  (let [^booleans citable citable-character
        length (.length text)]
    (loop [index 0 start -1 tokens (transient [])]
      (if (== index length)
        (persistent! (if (neg? start) tokens (conj! tokens (.substring text start index))))
        (let [character (.charAt text index)
              citation-character? (or (Character/isLetterOrDigit character)
                                      (and (< (int character) 128) (aget citable (int character))))]
          (cond
            (and citation-character? (neg? start)) (recur (unchecked-inc index) index tokens)
            (and (not citation-character?) (not (neg? start)))
            (recur (unchecked-inc index) -1 (conj! tokens (.substring text start index)))
            :else (recur (unchecked-inc index) start tokens)))))))

(def ^:private record-separator
  "The NUL byte git writes before each commit's authored seconds."
  (str (char 0)))

(def ^:private git-bound-seconds
  "Declared bound for the one git read that dates the notes."
  30)

(defn- note-opened
  "Earliest authored commit second per issue note file name, from one git read.
  The file name follows the note through its move into `archive/`."
  [root]
  (let [child (process/process ["git" "log" "--format=%x00%at" "--name-only" "--" "docs/seon/issues"]
                               {:dir (str root) :out :string :err :string})
        result (deref child (* 1000 git-bound-seconds) ::expired)]
    (if (= ::expired result)
      (do (process/destroy-tree child)
          (throw (ex-info "Git did not report issue note history within its declared bound."
                          (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/note-opened
        :seon.error/message "Issue operation requires issue history within the declared process deadline."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/note-opened
        :seon.error/diagnostic-member :seon.issue/history-root
        :seon.error/diagnostic-expected "issue history within the declared process deadline"
        :seon.error/diagnostic-offending (str root)
        :seon.error/offending (str root)
        :seon.error/diagnostic-cause :seon.issue/git-unbounded
        :seon.error/diagnostic-evidence {}
        :seon.issue/history-root (str root)}))))
      (when (zero? (:exit result))
        (second
         (reduce (fn [[seconds dates] line]
                   (cond
                     (str/starts-with? line record-separator) [(parse-long (subs line 1)) dates]
                     (or (str/blank? line) (nil? seconds)) [seconds dates]
                     :else (let [file-name (.getName (io/file line))]
                             [seconds (if (< seconds (get dates file-name Long/MAX_VALUE))
                                        (assoc dates file-name seconds) dates)])))
                 [nil {}] (str/split-lines (:out result))))))))

(defn- parse-note [{:seon.issue/keys [path text opened]}]
  (let [lines (str/split-lines text)
        header (when (= "---" (first lines))
                 (take-while #(not= "---" %) (rest lines)))
        fields (into {}
                     (keep (fn [line]
                             (let [i (.indexOf ^String line ":")]
                               (when (pos? i)
                                 [(subs line 0 i) (str/trim (subs line (inc i)))]))))
                     header)
        body (if header (drop (+ 2 (count header)) lines) lines)
        title (some #(when (str/starts-with? % "# ") (subs % 2)) body)
        section (drop-while #(not= "## Problem" (str/trim %)) body)
        problem (str/trim (str/join "\n" (if (seq section)
                                           (take-while #(not (str/starts-with? % "## ")) (rest section))
                                           (drop-while #(not (str/starts-with? % "# ")) body))))
        filename (.getName (io/file path))
        slug (subs filename 0 (- (count filename) 3))]
    {:seon.issue/id slug :seon.issue/path path
     :seon.issue/title title :seon.issue/problem problem
     :seon.issue/opened opened
     :seon.issue/status (some-> (get fields "status") keyword)
     :seon.issue/severity (some-> (get fields "severity") keyword)
     :seon.issue.parse/type (get fields "type")
     :seon.issue.parse/tags (set (words (get fields "tags" "")))
     :seon.issue.parse/created (get fields "created")
     :seon.issue.parse/words (set (words text))}))

(defn notes
  "Read open and archived Markdown issue notes under the supplied repository."
  {:malli/schema [:=> [:cat :string]
                  [:vector [:map [:seon.issue/path :string] [:seon.issue/text :string]]]]}
  [root]
  (let [directory (io/file root "docs/seon/issues")]
    (when-not (.isDirectory directory)
      (throw (ex-info "Issue directory is absent." (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/notes
        :seon.error/message "Issue operation requires an existing issue directory."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/notes
        :seon.error/diagnostic-member :seon.issue/missing-notes-path
        :seon.error/diagnostic-expected "an existing issue directory"
        :seon.error/diagnostic-offending (str directory)
        :seon.error/offending (str directory)
        :seon.error/diagnostic-cause :seon.issue/notes-absent
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-notes-path (str directory)}))))
    (let [dates (note-opened root)]
      (->> (concat (.listFiles directory) (.listFiles (io/file directory "archive")))
           (filter #(and (.isFile ^java.io.File %)
                         (str/ends-with? (.getName ^java.io.File %) ".md")
                         (not (contains? #{"README.md" "index.md" "AGENTS.md"} (.getName ^java.io.File %)))))
           (sort-by #(.getPath ^java.io.File %))
           (mapv (fn [file]
                   (let [file-name (.getName ^java.io.File file)
                         seconds (get dates file-name)]
                     (cond-> {:seon.issue/path (str "docs/seon/issues/"
                                                    (when (= "archive" (.getName (.getParentFile ^java.io.File file))) "archive/")
                                                    file-name)
                              :seon.issue/text (slurp file)}
                       seconds (assoc :seon.issue/opened (java.util.Date. (* 1000 (long seconds))))))))))))

(defn- diagnostic [path reason value]
  {:seon.issue/path path :seon.issue/reason reason :seon.issue/value value})

(defn- hex-token? [length token]
  (and (= length (count token))
       (every? #(not= -1 (Character/digit ^char % 16)) token)))

(defn- qualified-token [token]
  (let [token (if (str/ends-with? token ".") (subs token 0 (dec (count token))) token)
        i (.indexOf ^String token "/")]
    (when (and (not (.contains ^String token ":")) (pos? i) (< i (dec (count token)))
               (= i (.lastIndexOf ^String token "/"))
               (.contains ^String (subs token 0 i) ".")
               (not (str/ends-with? token ".clj"))
               (not (str/ends-with? token ".md")))
      (try
        (let [value (edn/read-string token)] (when (qualified-symbol? value) value))
        (catch Exception _ nil)))))

(defn- citation-attributes
  "Issue attributes by the identity attribute each declares that it collects.
  The whole target set is derived: a family becomes linkable by declaring
  `:seon.issue/cites` on one issue attribute, never by a new recogniser."
  [database]
  (let [installed (set (db/identity-attributes database))
        registry (:seon.schema.projection/registry (db/carried-projection database))
        cites (into {}
                    (for [[attribute] (db/q '[:find ?key :where
                                                   [?e :seon.schema/key ?key]]
                                                 database)
                          :let [properties
                                (m/properties (mr/schema registry attribute))]
                          cited (:seon.issue/cites properties)
                          :when (contains? installed cited)]
                      [cited attribute]))]
    (when (empty? cites)
      (throw (ex-info "No issue attribute declares :seon.issue/cites in this database's schema rows."
                      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/citation-attributes
        :seon.error/message "Issue operation requires declared citation relations for installed identities."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/citation-attributes
        :seon.error/diagnostic-member :seon.issue/uncited-identity-count
        :seon.error/diagnostic-expected "declared citation relations for installed identities"
        :seon.error/diagnostic-offending installed
        :seon.error/offending installed
        :seon.error/diagnostic-cause :seon.issue/citations-undeclared
        :seon.error/diagnostic-evidence {}
        :seon.issue/uncited-identity-count (count installed)}))))
    cites))

(defn- citation-spellings
  "Every spelling a note can cite one identity value by.
  A stored absolute path is also cited by any of its repository-relative
  tails, which is how notes name files."
  [value]
  (let [text (if (string? value) value (pr-str value))]
    (if (str/starts-with? text "/")
      (into [text]
            (keep (fn [index]
                    (let [tail (subs text (inc index))]
                      (when (and (pos? index) (seq tail) (str/includes? tail "/")) tail))))
            (keep-indexed (fn [index character] (when (= \/ character) index)) text))
      [text])))

(defn- citation-index
  "Map every citable spelling to the set of [issue-attribute entity] it names."
  [database cites]
  (reduce (fn [index [identity-attribute attribute]]
            (reduce (fn [index datom]
                      (reduce (fn [index text]
                                (update index text (fnil conj #{}) [attribute (:e datom)]))
                              index (citation-spellings (:v datom))))
                    index (db/datoms database :avet identity-attribute)))
          {} cites))

(defn- line-number [text]
  (when (and (seq text) (every? #(Character/isDigit ^char %) text)) (parse-long text)))

(defn- cited-span [text]
  (let [index (.indexOf ^String text "-")
        row (line-number (if (neg? index) text (subs text 0 index)))
        end-row (when-not (neg? index) (line-number (subs text (inc index))))]
    (when row
      (cond-> {:seon.issue.citation/row row}
        end-row (assoc :seon.issue.citation/end-row end-row)))))

(defn- citation
  "Resolve one token to the entity it cites, or report the ambiguity it names."
  [index token]
  (let [token (if (str/ends-with? token ".") (subs token 0 (dec (count token))) token)
        colon (.indexOf ^String token ":")
        head (when (pos? colon) (subs token 0 colon))
        [text span] (cond
                      (get index token) [token nil]
                      (and head (get index head)) [head (cited-span (subs token (inc colon)))]
                      :else [nil nil])]
    (when text
      (let [hits (get index text)]
        (if (= 1 (count hits))
          (let [[attribute entity] (first hits)]
            (merge {:seon.issue/attribute attribute :seon.issue/entity entity :seon.issue/value text} span))
          {:seon.issue/ambiguous (vec (sort (map first hits))) :seon.issue/value text})))))

(defn- file-citations
  "One citation component per cited file and span, on its stable identity."
  [issue-id hits existing]
  (into #{}
        (map (fn [[[entity row end-row] group]]
               (let [text (first (sort (map :seon.issue/value group)))
                     citation-id (id/id [issue-id text row end-row])]
                 (or (get existing citation-id)
                     (cond-> {:seon.issue.citation/id citation-id
                              :seon.issue.citation/file entity}
                       row (assoc :seon.issue.citation/row row)
                       end-row (assoc :seon.issue.citation/end-row end-row))))))
        (group-by (juxt :seon.issue/entity :seon.issue.citation/row :seon.issue.citation/end-row) hits)))

(defn- replacement-tx
  "The delta between one issue's stored facts and its desired facts.
  An issue whose every attribute already holds its desired value contributes
  NOTHING, so an unchanged note set is an empty transaction rather than a
  re-assertion of every fact the database already holds.
  `owned` names the attributes the caller replaces; the two-argument arity
  replaces every attribute, which is what indexing a note means. A caller that
  decides only part of an issue — a generator writing its own facts beside a
  worker's prose — supplies the set it decides, and every other attribute is
  left exactly as the database holds it."
  ([current desired] (replacement-tx current desired nil))
  ([current desired owned]
   (let [eid (:db/id current)
         desired (if owned
                   desired
                   (merge (select-keys current [:seon.issue/agent :seon.issue/budget
                                                :seon.issue/resolved-tx :seon.issue/created-by])
                          desired))
         retained (into (set (:seon.issue/tests desired))
                        (map #(if (map? %) (:db/id %) %)) (:seon.issue/tests current))
         desired (cond-> desired (and (nil? owned) (seq retained)) (assoc :seon.issue/tests retained))
         normalized (fn [value]
                      (cond
                        (and (map? value) (= #{:db/id} (set (keys value)))) (:db/id value)
                        (coll? value) (set (map #(if (map? %) (get % :db/id %) %) value))
                        :else value))
         changed (into (sorted-set)
                       (for [attribute (into (set (keys current)) (keys desired))
                             :when (and (not (contains? #{:db/id :seon.issue/id} attribute))
                                        (or (nil? owned) (contains? owned attribute))
                                        (not= (normalized (get current attribute))
                                              (normalized (get desired attribute))))]
                         attribute))
         asserted (select-keys desired changed)]
     (cond-> (mapv (fn [attribute] [:db/retract eid attribute])
                   (filter #(contains? current %) changed))
       (seq asserted) (conj (assoc asserted :db/id eid))))))

(defn index-tx
  "Derive exact indexed facts from the notes and the installed identities.
  Metadata carries refused notes, ambiguous
  citations and unresolved tokens. An unresolved token is evidence stored on
  the issue, never a refusal. Worker assignment and additive tests survive
  replacement of the prose.

  An invalid note contributes its refusal diagnostic and NO datoms: its slug
  is never minted as an identity, and it never names a class member. Minting
  an identity-only row for an invalid note made the whole-entity validator
  refuse the entire publication for one bad note, because
  `:seon.issue/title` is required. An invalid note is also NOT removed: a
  note present on disk but refused leaves its already stored row alone, so a
  prose defect never retracts indexed facts. Only a slug with no note at all
  is retracted."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:sequential [:map [:seon.issue/path :string] [:seon.issue/text :string]]]]
                  :seon.db/tx-data]}
  [database issue-notes]
  (let [parsed (mapv parse-note issue-notes)
        duplicates (->> parsed (map :seon.issue/id) frequencies
                        (keep (fn [[slug n]] (when (> n 1) slug))) set)
        cites (citation-attributes database)
        index (citation-index database cites)
        citation-entities (into {} (map (juxt :v :e)) (db/datoms database :avet :seon.issue.citation/id))
        replaced (into [:seon.issue/path :seon.issue/title :seon.issue/status :seon.issue/severity
                        :seon.issue/problem :seon.issue/opened :seon.issue/commits :seon.issue/members
                        :seon.issue/unresolved]
                       (vals cites))
        existing (db/q '[:find [?e ...] :where [?e :seon.issue/path]] database)
        existing (mapv #(db/pull database '[*] %) existing)
        by-slug (into {} (map (juxt :seon.issue/id identity)) existing)
        present (set (map :seon.issue/id parsed))
        ;; Validity is decided once, before anything derives datoms from a
        ;; note, so an invalid note can neither mint its identity nor be
        ;; named as a class member by a lookup ref nothing asserts.
        invalid-reason
        (fn [{:seon.issue/keys [id title status severity problem] :as note}]
          (cond
            (contains? duplicates id) :duplicate-slug
            (not= "issue" (:seon.issue.parse/type note)) :invalid-type
            (not (contains? #{:open :resolved :superseded} status)) :invalid-status
            (not (contains? #{:blocker :friction :cleanup} severity)) :invalid-severity
            (str/blank? title) :missing-title
            (str/blank? problem) :missing-problem))
        valid (filterv (complement invalid-reason) parsed)
        admitted (set (map :seon.issue/id valid))
        classes (into {} (mapcat (fn [note]
                                  (when (contains? (:seon.issue.parse/tags note) "class-kill")
                                    (for [tag (:seon.issue.parse/tags note)
                                          :when (str/starts-with? tag "class/")]
                                      [tag (:seon.issue/id note)])))) valid)
        ;; Membership in one pass over the notes, not one scan of every note per
        ;; note: the pairwise scan was 691 ms of every index for 115 members.
        members-by-class (reduce (fn [membership member]
                                   (reduce (fn [membership tag]
                                             (let [class-id (get classes tag)]
                                               (if (and class-id (not= class-id (:seon.issue/id member)))
                                                 (update membership class-id (fnil conj []) member)
                                                 membership)))
                                           membership (:seon.issue.parse/tags member)))
                                 {} valid)
        component-ids (fn [value] (into #{} (map #(if (map? %) (:db/id %) %)) value))
        results
        (mapv
         (fn [{:seon.issue/keys [id path] :as note}]
           (let [invalid (invalid-reason note)
                 tokens (:seon.issue.parse/words note)
                 resolutions (into [] (keep #(citation index %)) tokens)
                 ambiguous (mapv #(diagnostic path :ambiguous-citation (:seon.issue/value %))
                                 (sort-by :seon.issue/value (filter :seon.issue/ambiguous resolutions)))
                 hits (remove :seon.issue/ambiguous resolutions)
                 cited (set (map :seon.issue/value hits))
                 unresolved (into (sorted-set)
                                  (comp (keep qualified-token) (map str) (remove cited))
                                  tokens)
                 created (:seon.issue.parse/created note)
                 authored (when created
                            (try (java.util.Date/from
                                  (.toInstant (.atStartOfDay (java.time.LocalDate/parse created)
                                                            java.time.ZoneOffset/UTC)))
                                 (catch Exception _ nil)))
                 opened (or authored (:seon.issue/opened note))
                 diagnostics (cond-> []
                               (and created (nil? authored))
                               (conj (diagnostic path :invalid-created created)))
                 current (get by-slug id)
                 grouped (group-by :seon.issue/attribute hits)
                 citations (file-citations id (get grouped :seon.issue/files) citation-entities)
                 test-refs (into (component-ids (:seon.issue/tests current))
                                 (map :seon.issue/entity) (get grouped :seon.issue/tests))
                 row (cond-> (select-keys note [:seon.issue/id :seon.issue/path :seon.issue/title
                                                :seon.issue/status :seon.issue/severity :seon.issue/problem])
                       opened (assoc :seon.issue/opened opened)
                       (seq test-refs) (assoc :seon.issue/tests test-refs)
                       (seq unresolved) (assoc :seon.issue/unresolved (set unresolved))
                       (seq citations) (assoc :seon.issue/files citations))
                 row (reduce (fn [row [attribute attribute-hits]]
                               (let [entities (cond-> (into #{} (map :seon.issue/entity) attribute-hits)
                                                (= :seon.issue/issues attribute)
                                                (disj (:db/id current)))]
                                 (if (or (contains? #{:seon.issue/files :seon.issue/tests} attribute)
                                         (empty? entities))
                                   row
                                   (assoc row attribute entities))))
                             row grouped)
                 row (reduce (fn [row [attribute values]]
                               (if (seq values) (assoc row attribute (set values)) row))
                             row
                             [[:seon.issue/commits (filter #(hex-token? 9 %) tokens)]
                              [:seon.issue/members
                               (for [member (get members-by-class id)]
                                 ;; An already indexed member is named by its entity, so an
                                 ;; unchanged class note compares equal and re-indexing is a
                                 ;; no-op; a member first seen in this transaction is named by
                                 ;; the identity it asserts here.
                                 (or (:db/id (get by-slug (:seon.issue/id member)))
                                     [:seon.issue/id (:seon.issue/id member)]))]])
                 stale (mapv (fn [entity] [:db/retractEntity entity])
                             (sort (remove (into #{} (filter integer?) citations)
                                           (component-ids (:seon.issue/files current)))))]
             (if invalid
               {:seon.issue/refusals [(diagnostic path invalid id)] :seon.issue/tx []}
               {:seon.issue/refusals diagnostics
                :seon.issue/ambiguous ambiguous
                :seon.issue/unresolved (when (seq unresolved) [path (count unresolved)])
                :seon.issue/tx
                (into stale
                      (if current
                        (replacement-tx current (merge (apply dissoc current replaced) row))
                        [row]))})))
         parsed)
        ;; `present`, not `admitted`: a note on disk that this index refused
        ;; is still present, so its stored row is left exactly as it was.
        removed (remove #(contains? present (:seon.issue/id %)) existing)
        ;; A VALID slug the database does not hold yet is minted here, so a
        ;; class note may name a member first seen in this same transaction by
        ;; lookup ref. An already stored slug needs no upsert: it re-asserts
        ;; one identity fact per note the database already holds (1,649 of
        ;; them today). An invalid slug is never minted: an identity-only row
        ;; has no `:seon.issue/title`, which the whole-entity validator
        ;; requires, so one bad note refused every publication.
        tx (into (mapv #(hash-map :seon.issue/id %)
                       (remove by-slug (sort admitted)))
                 (concat (mapcat :seon.issue/tx results)
                         (map (fn [issue]
                                [:db/retractEntity [:seon.issue/id (:seon.issue/id issue)]])
                              removed)))]
    (with-meta tx {:seon.issue/refusals (vec (mapcat :seon.issue/refusals results))
                   :seon.issue/ambiguous (vec (mapcat :seon.issue/ambiguous results))
                   :seon.issue/unresolved (into (sorted-map) (keep :seon.issue/unresolved) results)})))

(defn index!
  "Index notes through the writer and return counts plus citation refusals.
  The request supplies the notes, keeping filesystem reads outside the writer.
  The delta decides: an unchanged note set writes nothing at all, and the
  diagnostics come from the one derivation that produced it."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.issue/notes [:sequential [:map [:seon.issue/path :string] [:seon.issue/text :string]]]]]]
                  [:or :map :seon.db/error-result :seon.issue/citations-undeclared-error]]}
  [{connection :seon.db/connection issue-notes :seon.issue/notes}]
  (let [database (db/db connection)
        delta (index-tx database issue-notes)
        ;; An empty delta is the whole answer: the database already holds every
        ;; fact these notes assert, so there is nothing for the writer to
        ;; serialize. The writer still derives the transaction it commits, so a
        ;; database that moved between this derivation and the write is
        ;; re-decided at the authority; only the decision to write nothing at
        ;; all is taken here, on a connection its publication holds privately.
        result (when (seq delta)
                 (db/transact! connection [[:db.fn/call #'index-tx issue-notes]]))]
    (if (and (map? result) (:seon.error/at result)
           (:seon.error/layer result) (:seon.error/operation result)) ; debt: database and detector reads still declare generic seon.db/error-result.

      result
      (merge (select-keys (meta delta) [:seon.issue/refusals :seon.issue/ambiguous :seon.issue/unresolved])
             (cond-> {:seon.issue/count (count (db/q '[:find [?e ...] :where [?e :seon.issue/path]]
                                                      (or (:db-after result) database)))}
               result (assoc :seon.db/transaction-report result))))))

(def ^:private generated-prose
  "Attributes a detector proposes once. A human's or a worker's edit survives
  every later run: the generator writes them only when the issue holds none."
  #{:seon.issue/title :seon.issue/problem})

(defn- refuse!
  "Throw an already complete writer observation."
  {:malli/schema [:=> [:cat :seon.issue/refusal] :nil]}
  [observation]
  (throw (ex-info (:seon.error/message observation) observation)))

(defn subject-id
  "The detected issue identity shared by generation and a prospective plan.

  The detector and the subject's installed identity value determine it;
  entity ids, titles and the proposed retraction do not participate."
  {:malli/schema
   [:=> [:cat :qualified-symbol
         [:tuple :qualified-keyword :seon.schema/value]] :seon.issue/id]}
  [detector [attribute value]]
  (id/id (into (sorted-map) {:seon.issue/detector detector attribute value})))

(defn- subject-row
  "The facts one detector subject asserts, on the identity the detector gives it.
  The identity is the detector plus the subject's own identity value, so two
  runs upsert one entity; an entity id would change under a refork and is
  refused."
  [database context subject]
  (let [{:keys [detector program severity identities cites namespaces]} context
        identifying (filterv (fn [[attribute _]] (contains? identities attribute)) subject)
        _ (when-not (= 1 (count identifying))
            (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/subject-row
        :seon.error/message "Issue operation requires exactly one installed subject identity."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/subject-row
        :seon.error/diagnostic-member :seon.issue/subject-identity-count
        :seon.error/diagnostic-expected "exactly one installed subject identity"
        :seon.error/diagnostic-offending subject
        :seon.error/offending subject
        :seon.error/diagnostic-cause :seon.issue/subject-without-identity
        :seon.error/diagnostic-evidence {}
        :seon.issue/subject-identity-count (count identifying)})))
        [attribute value] (first identifying)
        issue-attribute (or (get cites attribute)
                            (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/subject-row
        :seon.error/message "Issue operation requires a declared citation relation for the identity."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/subject-row
        :seon.error/diagnostic-member :seon.issue/uncited-subject-attribute
        :seon.error/diagnostic-expected "a declared citation relation for the identity"
        :seon.error/diagnostic-offending subject
        :seon.error/offending subject
        :seon.error/diagnostic-cause :seon.issue/subject-unlinkable
        :seon.error/diagnostic-evidence {}
        :seon.issue/uncited-subject-attribute attribute})))
        entity (or (:db/id (db/pull database [:db/id] [attribute value]))
                   (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/subject-row
        :seon.error/message "Issue operation requires an existing subject entity."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/subject-row
        :seon.error/diagnostic-member :seon.issue/missing-subject-attribute
        :seon.error/diagnostic-expected "an existing subject entity"
        :seon.error/diagnostic-offending subject
        :seon.error/offending subject
        :seon.error/diagnostic-cause :seon.issue/subject-absent
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-subject-attribute attribute})))
        cited (into #{} (keep namespaces) (:seon.issue/namespaces subject))]
    (cond-> {:seon.issue/id (subject-id (symbol detector) [attribute value])
             :seon.issue/detector program
             :seon.issue/severity severity
             :seon.issue/status :open
             issue-attribute #{entity}}
      (:seon.issue/title subject) (assoc :seon.issue/title (:seon.issue/title subject))
      (:seon.issue/problem subject) (assoc :seon.issue/problem (:seon.issue/problem subject))
      (seq cited) (assoc :seon.issue/namespaces cited))))

(defn- detector-rows
  "Read a detector through its program identity and derive its issue subjects."
  [database request]
  (let [detector (:seon.issue/detector request)
        program (or (:db/id (db/pull database [:db/id] [:seon.fn/sym detector]))
                    (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/detector-rows
        :seon.error/message "Issue operation requires an installed detector function."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/detector-rows
        :seon.error/diagnostic-member :seon.issue/missing-detector
        :seon.error/diagnostic-expected "an installed detector function"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/detector-unknown
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-detector detector})))
        detect (or (requiring-resolve (symbol detector))
                   (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/detector-rows
        :seon.error/message "Issue operation requires a resolving detector Var."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/detector-rows
        :seon.error/diagnostic-member :seon.issue/unresolved-detector
        :seon.error/diagnostic-expected "a resolving detector Var"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/detector-unresolved
        :seon.error/diagnostic-evidence {}
        :seon.issue/unresolved-detector detector})))
        subjects (if-let [root (:seon.fn.file/relative-root request)]
                   (detect database {:seon.fn.file/relative-root root})
                   (detect database))
        _ (when (and (map? subjects) (:seon.error/at subjects)
           (:seon.error/layer subjects) (:seon.error/operation subjects)) ; debt: database and detector reads still declare generic seon.db/error-result.

            (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/detector-rows
        :seon.error/message "Issue operation requires a successful detector read."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/detector-rows
        :seon.error/diagnostic-member :seon.issue/refused-detector
        :seon.error/diagnostic-expected "a successful detector read"
        :seon.error/diagnostic-offending subjects
        :seon.error/offending subjects
        :seon.error/diagnostic-cause :seon.issue/detector-refused
        :seon.error/diagnostic-evidence {}
        :seon.issue/refused-detector detector})))
        context {:detector detector :program program
                 :severity (:seon.issue/severity request)
                 :identities (set (db/identity-attributes database))
                 :cites (citation-attributes database)
                 :namespaces (into {} (db/q '[:find ?name ?e :where [?e :seon.ns/name ?name]] database))}]
    (mapv #(subject-row database context %) subjects)))

(defn generate
  "Derive issue facts for one detector's current subjects.
  The detector is an ordinary read resolved from its program entity; its
  subjects each carry one installed identity attribute, which with the detector
  is the issue identity. Status, severity, the detector and the subject ref are
  decided every run; the prose only when the issue holds none. A subject the
  detector no longer yields is resolved, and one it yields again is reopened —
  the entity and its identity survive both."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/detector :seon.fn/sym]
                        [:seon.fn.file/relative-root {:optional true} :seon.fn.file/relative-root]
                        [:seon.issue/severity :seon.issue/severity]]]
                  :seon.db/tx-data]}
  [database request]
  (let [detector (:seon.issue/detector request)
        program (:db/id (db/pull database [:db/id] [:seon.fn/sym detector]))
        rows (detector-rows database request)
        yielded (into #{} (map :seon.issue/id) rows)
        stored (into {}
                     (map (fn [entity]
                            (let [row (db/pull database '[*] entity)] [(:seon.issue/id row) row])))
                     (if-let [root (:seon.fn.file/relative-root request)]
                       (db/q '[:find [?e ...] :in $ ?detector ?root :where
                               [?e :seon.issue/detector ?detector]
                               [?e :seon.issue/functions ?function]
                               [?function :seon.fn/file ?file]
                               [?file :seon.fn.file/relative-root ?root]] database program root)
                       (db/q '[:find [?e ...] :in $ ?detector :where [?e :seon.issue/detector ?detector]]
                             database program)))]
    (into (vec (mapcat
                (fn [row]
                  (if-let [held (get stored (:seon.issue/id row))]
                    (let [desired (apply dissoc row (filter #(contains? held %) generated-prose))]
                      ;; The generator owns exactly what it decides plus the
                      ;; resolution fact, so a worker's tests, agent and edited
                      ;; prose are never touched by a run.
                      (replacement-tx held desired
                                      (conj (set (keys desired)) :seon.issue/resolved-tx)))
                    [(assoc row :seon.issue/opened (java.util.Date.))]))
                rows))
          (mapcat (fn [held]
                    (when-not (contains? yielded (:seon.issue/id held))
                      (let [desired (cond-> {:seon.issue/id (:seon.issue/id held)
                                             :seon.issue/status :resolved}
                                      (not (:seon.issue/resolved-tx held))
                                      (assoc :seon.issue/resolved-tx "datomic.tx"))]
                        (replacement-tx held desired (set (keys desired))))))
                  (vals stored)))))

(defn- generated-report [database request]
  (let [program (:db/id (db/pull database [:db/id] [:seon.fn/sym (:seon.issue/detector request)]))
        rows (mapv #(db/pull database [:seon.issue/id :seon.issue/status] %)
                   (db/q '[:find [?e ...] :in $ ?detector :where [?e :seon.issue/detector ?detector]]
                         database program))]
    {:seon.issue/detector (:seon.issue/detector request)
     :seon.issue/count (count rows)
     :seon.issue/open (count (filterv #(= :open (:seon.issue/status %)) rows))
     :seon.issue/resolved (count (filterv #(= :resolved (:seon.issue/status %)) rows))}))

(defn generate!
  "Run one detector through the writer and report its issues.
  A run whose facts the database already holds writes nothing at all."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.issue/detector :seon.fn/sym]
                             [:seon.fn.file/relative-root {:optional true} :seon.fn.file/relative-root]
                             [:seon.issue/severity :seon.issue/severity]]]
                  [:or :map :seon.db/error-result :seon.issue/subject-without-identity-error :seon.issue/subject-unlinkable-error :seon.issue/subject-absent-error :seon.issue/detector-unknown-error :seon.issue/detector-unresolved-error :seon.issue/detector-refused-error :seon.issue/citations-undeclared-error]]}
  [{connection :seon.db/connection :as call}]
  (let [request (dissoc call :seon.db/connection)
        database (db/db connection)
        delta (try (generate database request)
                   (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (cond
      (and (map? delta) (:seon.error/at delta)
           (:seon.error/layer delta) (:seon.error/operation delta)) ; debt: database and detector reads still declare generic seon.db/error-result.
 delta
      (empty? delta) (assoc (generated-report database request) :seon.issue/forms 0)
      :else (let [report (db/transact! connection [[:db.fn/call #'generate request]])]
              (if (and (map? report) (:seon.error/at report)
           (:seon.error/layer report) (:seon.error/operation report)) ; debt: database and detector reads still declare generic seon.db/error-result.

                report
                (assoc (generated-report (:db-after report) request)
                       :seon.issue/forms (count delta)))))))

(defn issues
  "Query issues in identity order, optionally restricted by lifecycle."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/status {:optional true} :seon.issue/status]]]
                  [:vector :map]]}
  [{database :seon.db/db lifecycle :seon.issue/status}]
  (->> (db/q '[:find [?e ...] :where [?e :seon.issue/title]] database)
       (map #(db/pull database '[*] %))
       (filter #(or (nil? lifecycle) (= lifecycle (:seon.issue/status %))))
       (sort-by :seon.issue/id) vec))

(defn- detector-symbol
  "The detector's symbol when the issue names one and the pull resolved it."
  [row]
  (some-> (get-in row [:seon.issue/detector :seon.fn/sym]) symbol))

(defn- check-form
  "The form that decides this issue's completion, or nothing when none does.
  Tests win when the issue has any; otherwise a generated issue's detector is
  the check, because the run that stops naming the subject resolves it. An
  issue with neither carries NO form: `(my.test/check {:seon.test/changed []})`
  promised a verification that would pass by being empty."
  [row test-rows]
  (cond
    (seq test-rows) (list 'my.test/check {:seon.test/changed (mapv :seon.test/sym test-rows)})
    (detector-symbol row) (list (detector-symbol row) '(seon.db/db))))

(defn status
  "Read the issue and test outcomes; verification uses the current test reach digest."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]]
                  [:or :seon.issue/status-view :seon.db/error-result :seon.issue/not-found-error]]}
  [{database :seon.db/db issue-id :seon.issue/id}]
  (let [row (db/pull database '[:db/id :seon.issue/id :seon.issue/title :seon.issue/status :seon.issue/severity
                                  :seon.issue/problem :seon.issue/path :seon.issue/opened :seon.issue/commits
                                  {:seon.issue/agent [:db/id :seon.agent/id]} :seon.issue/budget :seon.issue/resolved-tx
                                  :seon.issue/budget-exhausted-tx
                                  :seon.issue/unresolved
                                  {:seon.issue/detector [:db/id :seon.fn/sym]}
                                  {:seon.issue/keys [:db/id :seon.schema/key]}
                                  {:seon.issue/namespaces [:db/id :seon.ns/name]}
                                  {:seon.issue/runs [:db/id :seon.test.run/id]}
                                  {:seon.issue/issues [:db/id :seon.issue/id]}
                                  {:seon.issue/files [:db/id :seon.issue.citation/row :seon.issue.citation/end-row
                                                       {:seon.issue.citation/file [:seon.fn.file/relative-path]}]}
                                  {:seon.issue/members [:db/id :seon.issue/id]}
                                  {:seon.issue/tests [:db/id :seon.test/sym]}
                                  {:seon.issue/functions [:db/id :seon.fn/sym {:seon.fn/ns [:db/id :seon.ns/name]}]}
                                  {:seon.issue/errors [:db/id :seon.error/signature {:seon.error/occurrences [:seon.error.occurrence/count]}]}]
                     [:seon.issue/id issue-id])]
    (cond
      (and (map? row) (:seon.error/at row)
           (:seon.error/layer row) (:seon.error/operation row)) ; debt: database and detector reads still declare generic seon.db/error-result.
 row
      (not (:seon.issue/title row))
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/status
        :seon.error/message "Issue operation requires an existing issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/status
        :seon.error/diagnostic-member :seon.issue/missing-issue-id
        :seon.error/diagnostic-expected "an existing issue"
        :seon.error/diagnostic-offending issue-id
        :seon.error/offending issue-id
        :seon.error/diagnostic-cause :seon.issue/not-found
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-issue-id issue-id})
      :else
      (let [test-rows (mapv
                       (fn [test-value]
                         (let [recorded (seon.test/recorded-result database (:seon.test/sym test-value))
                               test-value (if (or (:seon.test/unknown recorded) (:seon.test/execution-refusal recorded))
                                            (assoc test-value :seon.test/failure-message (:seon.error/message recorded))
                                            (merge test-value recorded))
                               state (cond
                                       (not (:seon.test/run test-value)) :unrun
                                       (or (pos? (get test-value :seon.test/fail-count 0))
                                           (pos? (get test-value :seon.test/error-count 0))) :red
                                       (true? (seon.test/verified?
                                               database (:seon.test/sym test-value))) :verified
                                       :else :unverified)]
                           (assoc test-value :seon.issue.test/state state)))
                       (map (fn [[eid sym]] {:db/id eid :seon.test/sym sym})
                            (sort-by second
                             (db/q '[:find ?test ?symbol :in $ ?issue
                                     :where [?issue :seon.issue/tests ?test]
                                            [?test :seon.test/sym ?symbol]]
                                   database (:db/id row)))))]
        (cond-> (assoc row :seon.issue/tests test-rows
                           :seon.issue/turns-remaining
                           (if-let [agent-id (get-in row [:seon.issue/agent :seon.agent/id])]
                             (@turn-turns-left database agent-id) 0)
                           :seon.issue/status (if (:seon.issue/resolved-tx row) :resolved (:seon.issue/status row))
                           :seon.issue/functions (mapv #(vector :seon.fn/sym (:seon.fn/sym %)) (:seon.issue/functions row))
                           :seon.issue/errors (mapv (fn [error]
                                                      (cond-> (dissoc error :seon.error/occurrences)
                                                        (seq (:seon.error/occurrences error))
                                                        (assoc :seon.error/occurrence-count
                                                               (reduce + (map :seon.error.occurrence/count (:seon.error/occurrences error))))))
                                                    (:seon.issue/errors row)))
          (check-form row test-rows)
          (assoc :seon.issue/check-form (check-form row test-rows)))))))

(defn status-text
  "Render the issue's current checks, failures, and remaining turn budget."
  {:malli/schema [:=> [:cat :map] :string]}
  [view]
  (str "Issue " (:seon.issue/id view) ": "
       (if (:seon.issue/resolved-tx view) "resolved" "still open")
       (when (some? (:seon.issue/turns-remaining view))
         (str "; " (:seon.issue/turns-remaining view) " turns remaining"))
       (when (:seon.issue/budget-exhausted-tx view) "; budget exhausted")
       ".\n" (:seon.issue/title view)
       (when-let [form (:seon.issue/check-form view)]
         (str "\nDone condition: " (repl/source-text form)))
       (apply str
              (for [test-row (:seon.issue/tests view)]
                (str "\n" (:seon.test/sym test-row) ": "
                     (case (:seon.issue.test/state test-row)
                       :verified "passed" :red "failed" :unrun "not run" "not verified")
                     (when-let [failure (:seon.test/failure-message test-row)]
                       (str "\n" failure)))))))

(defn- status-view
  "The issue view a render shows: derived at `status` from the unit's own
  database, so the detector ref and the check form are resolved by the
  authority that decides them. A unit carrying no database, or an issue
  `status` cannot read, renders the row it was handed."
  [unit]
  (let [row (or (:seon.render/value unit) unit)]
    (or (when-let [database (:seon.db/db unit)]
          (let [view (status {:seon.db/db database :seon.issue/id (:seon.issue/id row)})]
            (when (:seon.issue/title view) view)))
        row)))

(defn render-ai
  "Show the issue's status and exact verification form as one data block.
  The done condition the agent reads every turn is derived here, so a
  detector-resolved issue names its detector and the exact form to run."
  {:malli/schema [:=> [:cat [:or :seon.issue/issue :seon.render/unit]] :string]}
  [unit]
  (status-text (status-view unit)))

(defn render-html
  "Show the same issue status and verification forms on the agent page."
  {:malli/schema [:=> [:cat [:or :seon.issue/issue :seon.render/unit]] :seon.render/hiccup]}
  [unit]
  (let [view (status-view unit)]
    [:section {:class "seon-family-entry seon-issue"}
     [:h3 (:seon.issue/title view)]
     [:pre {:style "white-space: pre-wrap"} (status-text view)]]))

(defn- citation-pattern
  "The pull pattern that names every citation by its identity, never by entity."
  [cites]
  (into [:seon.issue/id :seon.issue/path :seon.issue/title :seon.issue/status
         :seon.issue/severity :seon.issue/problem :seon.issue/opened :seon.issue/commits
         :seon.issue/unresolved
         {:seon.issue/members [:seon.issue/id]}
         {:seon.issue/files [:seon.issue.citation/id :seon.issue.citation/row
                             :seon.issue.citation/end-row
                             {:seon.issue.citation/file [:seon.fn.file/relative-path]}]}]
        (for [[identity-attribute attribute] cites
              :when (not= :seon.issue/files attribute)]
          {attribute [identity-attribute]})))

(defn- identity-rows
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:vector :seon.db/pulled-entity] :seon.db/error-result]]}
  [database]
  (db/pull-many database (citation-pattern (citation-attributes database))
                (db/q '[:find [?e ...] :where [?e :seon.issue/path]] database)))

(defn adopt-tx
  "Reconcile the selected published issue identities into a development database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value [:vector :map]] :seon.db/tx-data]
    [:=> [:cat :seon.db/database-value [:vector :map] [:set :seon.issue/id]] :seon.db/tx-data]]}
  ([database rows]
   (adopt-tx database rows
             (into (set (map :seon.issue/id rows))
                   (db/q '[:find [?id ...] :where [?e :seon.issue/id ?id]
                           [?e :seon.issue/path]] database))))
  ([database rows identities]
  (let [pulled (mapv #(db/pull database '[*] [:seon.issue/id %]) identities)
        _ (when-let [refusal (some #(when (:seon.error/at %) %) pulled)]
            (throw (ex-info (:seon.error/message refusal) refusal)))
        current (filterv :seon.issue/id pulled)
        by-id (into {} (map (juxt :seon.issue/id identity)) current)
        ids (set (map :seon.issue/id rows))
        ref-attributes (into {:seon.issue/members :seon.issue/id}
                             (for [[identity-attribute attribute] (citation-attributes database)
                                   :when (not= :seon.issue/files attribute)]
                               [attribute identity-attribute]))
        adopted-citations
        (fn [row]
          (if-let [citations (seq (:seon.issue/files row))]
            (assoc row :seon.issue/files
                   (into #{} (map (fn [cited]
                                    (or (:db/id (db/pull database [:db/id]
                                                        [:seon.issue.citation/id (:seon.issue.citation/id cited)]))
                                        (-> (select-keys cited [:seon.issue.citation/id :seon.issue.citation/row
                                                            :seon.issue.citation/end-row])
                                        (assoc :seon.issue.citation/file
                                               [:seon.fn.file/relative-path (get-in cited [:seon.issue.citation/file
                                                                                  :seon.fn.file/relative-path])])))))
                         citations))
            row))]
    (into (mapv #(hash-map :seon.issue/id %) (remove by-id (sort ids)))
          (concat
           (mapcat
            (fn [row]
              (let [row (reduce-kv
                         (fn [row attribute identity-attribute]
                           (if (get row attribute)
                             (assoc row attribute
                                    (set (map (fn [cited]
                                                (let [lookup [identity-attribute (get cited identity-attribute)]]
                                                  (or (:db/id (db/pull database [:db/id] lookup)) lookup)))
                                              (get row attribute)))) row))
                         (adopted-citations row) ref-attributes)
                    prior (get by-id (:seon.issue/id row))
                    desired row]
                (if prior (replacement-tx prior desired) [desired])))
            rows)
           (map #(vector :db/retractEntity [:seon.issue/id (:seon.issue/id %)])
                   (remove #(contains? ids (:seon.issue/id %)) current)))))))

(defn adopt!
  "Adopt selected issue entities from the exact published database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/connection :seon.db/database-value]
     [:or :nil :seon.db/transaction-report :seon.db/error-result :seon.issue/citations-undeclared-error]]
    [:=> [:cat :seon.db/connection :seon.db/database-value [:set :seon.issue/id]]
     [:or :nil :seon.db/transaction-report :seon.db/error-result :seon.issue/citations-undeclared-error]]]}
  ([connection source]
   (let [rows (identity-rows source)]
     (if (:seon.error/at rows)
       rows
       (db/transact! connection [[:db.fn/call #'adopt-tx rows]]))))
  ([connection source identities]
   (when (seq identities)
     (let [rows (db/pull-many source (citation-pattern (citation-attributes source))
                              (mapv #(vector :seon.issue/id %) identities))]
       (if (:seon.error/at rows)
         rows
         (db/transact! connection
                       [[:db.fn/call #'adopt-tx (filterv :seon.issue/id rows) identities]]))))))

(def ^:private tests-done-query
  "Nonempty tests all have positive green results on their current reach digest."
  '[:find ?subject .
    :in $ ?input
    :where
    [(identity ?input) ?subject]
    [?subject :seon.issue/tests _]
    (not-join [?subject]
      [?subject :seon.issue/tests ?test]
      (not-join [?test]
        [?test :seon.test/sym ?symbol]
        [(seon.test/verified? $ ?symbol) ?verified]
        [(true? ?verified)]))])

(defn done?
  "Whether the issue's tests verify, or its detector no longer names it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/ref] :boolean]}
  [database reference]
  (let [row (db/pull database
                     '[:db/id :seon.issue/id :seon.issue/tests :seon.issue/severity
                       {:seon.issue/detector [:seon.fn/sym]}] reference)]
    (cond
      (seq (:seon.issue/tests row))
      (let [result (db/q tests-done-query database (:db/id row))]
        ;; Debt: seon.db/q still declares the generic seon.db/error-result.
        (when (and (map? result) (:seon.error/at result)
                   (:seon.error/layer result) (:seon.error/operation result))
          (throw (ex-info (:seon.error/message result) result)))
        (boolean result))

      (get-in row [:seon.issue/detector :seon.fn/sym])
      (not-any? #(= (:seon.issue/id row) (:seon.issue/id %))
                (detector-rows database
                               {:seon.issue/detector (get-in row [:seon.issue/detector :seon.fn/sym])
                                :seon.issue/severity (:seon.issue/severity row)}))

      :else false)))

(def done-query
  "The issue's tests decide done when present; otherwise its detector does."
  '[:find ?subject . :in $ ?input :where
    [(identity ?input) ?subject]
    [(seon.issue/done? $ ?subject) ?done]
    [(true? ?done)]])

(defn- require-test-refs! [database references]
  (doseq [reference references]
    (when-not (:seon.test/sym
               (db/pull database [:seon.test/sym]
                        (if (map? reference) (:db/id reference) reference)))
      (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/require-test-refs!
        :seon.error/message "Issue operation requires test entities in the success references."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/require-test-refs!
        :seon.error/diagnostic-member :seon.issue/non-test-reference-member
        :seon.error/diagnostic-expected "test entities in the success references"
        :seon.error/diagnostic-offending reference
        :seon.error/offending reference
        :seon.error/diagnostic-cause :seon.issue/not-a-test
        :seon.error/diagnostic-evidence {}
        :seon.issue/non-test-reference-member (if (vector? reference) (first reference) :db/id)})))))

(defn- create-tx
  "Create the assigned worker, its plan, and its opening in one writer decision."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/id :seon.issue/id]
                        [:seon.issue/budget :seon.issue/budget]
                        [:seon.ns/name {:optional true} :seon.ns/name]
                        [:seon.agent/settings {:optional true} :seon.config/agent-overlay]
                        [:seon.config.ai/no-provider {:optional true} :seon.config.ai/no-provider]]]
                  :seon.db/tx-data]}
  [database request]
  (let [issue-id (:seon.issue/id request)
        row (db/pull database '[* {:seon.issue/functions [:seon.fn/sym {:seon.fn/ns [:seon.ns/name]}]}]
                     [:seon.issue/id issue-id])
        agent-id (id/id [issue-id])
        namespace-name (or (:seon.ns/name request)
                           (get-in (first (sort-by :seon.fn/sym (:seon.issue/functions row)))
                                   [:seon.fn/ns :seon.ns/name]))
        cluster-name (db/q '[:find ?name . :where [_ :seon.cluster/name ?name]] database)]
    (when-not (:seon.issue/title row) (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/create-tx
        :seon.error/message "Issue operation requires an existing issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/create-tx
        :seon.error/diagnostic-member :seon.issue/missing-issue-id
        :seon.error/diagnostic-expected "an existing issue"
        :seon.error/diagnostic-offending issue-id
        :seon.error/offending issue-id
        :seon.error/diagnostic-cause :seon.issue/not-found
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-issue-id issue-id})))
    (when (:seon.issue/agent row) (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/create-tx
        :seon.error/message "Issue operation requires an unassigned issue or a larger budget for an open issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/create-tx
        :seon.error/diagnostic-member :seon.issue/assigned-issue-id
        :seon.error/diagnostic-expected "an unassigned issue or a larger budget for an open issue"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/already-started
        :seon.error/diagnostic-evidence {}
        :seon.issue/assigned-issue-id issue-id})))
    (when-not (or (seq (:seon.issue/tests row)) (:seon.issue/detector row))
      (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/create-tx
        :seon.error/message "Issue operation requires success tests or a detector before starting an issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/create-tx
        :seon.error/diagnostic-member :seon.issue/unverifiable-issue-id
        :seon.error/diagnostic-expected "success tests or a detector before starting an issue"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/no-tests
        :seon.error/diagnostic-evidence {}
        :seon.issue/unverifiable-issue-id issue-id})))
    (require-test-refs! database (:seon.issue/tests row))
    (when-not namespace-name (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/create-tx
        :seon.error/message "Issue operation requires a supplied or subject namespace."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/create-tx
        :seon.error/diagnostic-member :seon.issue/unassigned-namespace-issue-id
        :seon.error/diagnostic-expected "a supplied or subject namespace"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/no-namespace
        :seon.error/diagnostic-evidence {}
        :seon.issue/unassigned-namespace-issue-id issue-id})))
    (when-not cluster-name (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/create-tx
        :seon.error/message "Issue operation requires a cluster identity in the database."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/create-tx
        :seon.error/diagnostic-member :seon.issue/unscoped-issue-id
        :seon.error/diagnostic-expected "a cluster identity in the database"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/no-cluster
        :seon.error/diagnostic-evidence {}
        :seon.issue/unscoped-issue-id issue-id})))
    (when (db/pull database [:seon.agent/id] [:seon.agent/id agent-id])
      (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/create-tx
        :seon.error/message "Issue operation requires an available derived worker identity."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/create-tx
        :seon.error/diagnostic-member :seon.issue/existing-worker-id
        :seon.error/diagnostic-expected "an available derived worker identity"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/worker-exists
        :seon.error/diagnostic-evidence {}
        :seon.issue/existing-worker-id agent-id})))
    (let [creation (@cluster-agent-creation-tx
                    {:seon.agent/id agent-id :seon.ns/name namespace-name :seon.cluster/name cluster-name})
          step-id (id/id [:seon.issue/step issue-id])
          creation (mapv
                    (fn [entry]
                      (if (= agent-id (:seon.agent/id entry))
                        (-> entry
                            (update :seon.agent/settings merge
                                    (cond-> (merge {:seon.config.run/max-episode-runs (:seon.issue/budget request)}
                                                   (:seon.agent/settings request))
                                      (:seon.config.ai/no-provider request)
                                      (assoc :seon.config.ai/no-provider true)))
                            (update :seon.agent/plan merge
                                    {:my.plan/objective (:seon.issue/problem row)
                                     :my.plan/current-step (str "issue-step:" issue-id)
                                     :my.plan/steps [{:db/id (str "issue-step:" issue-id)
                                                      :my.plan.item/id step-id
                                                      :my.plan.item/title (:seon.issue/title row)
                                                      :my.plan.item/position 0
                                                      :my.plan.item/subject [:seon.issue/id issue-id]
                                                      :my.plan.item/done-query done-query}]}))
                        entry))
                    creation)]
      (into (conj creation
                  {:seon.issue/id issue-id :seon.issue/agent [:seon.agent/id agent-id]
                   :seon.issue/budget (:seon.issue/budget request)}
                  {:seon.ns/name namespace-name
                   :seon.ns/requires #{'my.issue 'my.test}})
            (@turn-generated-run-tx
             database {:seon.agent/id agent-id :seon.turn/id (id/id [:seon.issue/opening issue-id])
                       :seon.turn/opened-tx "datomic.tx"
                       :seon.turn/starting-ns [:seon.ns/name namespace-name]
                       :seon.turn/trigger [:seon.issue/id issue-id]})))))

(defn start-tx
  "Start an issue, or resume its existing agent with a larger total budget."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/id :seon.issue/id]
                        [:seon.issue/budget :seon.issue/budget]
                        [:seon.ns/name {:optional true} :seon.ns/name]
                        [:seon.agent/settings {:optional true} :seon.config/agent-overlay]
                        [:seon.config.ai/no-provider {:optional true} :seon.config.ai/no-provider]]]
                  :seon.db/tx-data]}
  [database request]
  (let [issue-id (:seon.issue/id request)
        row (db/pull database '[:seon.issue/budget :seon.issue/resolved-tx
                               :seon.issue/tests :seon.issue/detector
                               {:seon.issue/agent [:seon.agent/id]}]
                     [:seon.issue/id issue-id])
        agent-id (get-in row [:seon.issue/agent :seon.agent/id])]
    (if-not agent-id
      (create-tx database request)
      (do
        (when-not (or (seq (:seon.issue/tests row)) (:seon.issue/detector row))
          (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/start-tx
        :seon.error/message "Issue operation requires success tests or a detector before starting an issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/start-tx
        :seon.error/diagnostic-member :seon.issue/unverifiable-issue-id
        :seon.error/diagnostic-expected "success tests or a detector before starting an issue"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/no-tests
        :seon.error/diagnostic-evidence {}
        :seon.issue/unverifiable-issue-id issue-id})))
        (when (or (:seon.issue/resolved-tx row)
                  (<= (:seon.issue/budget request) (:seon.issue/budget row 0)))
          (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/start-tx
        :seon.error/message "Issue operation requires an unassigned issue or a larger budget for an open issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/start-tx
        :seon.error/diagnostic-member :seon.issue/assigned-issue-id
        :seon.error/diagnostic-expected "an unassigned issue or a larger budget for an open issue"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/already-started
        :seon.error/diagnostic-evidence {}
        :seon.issue/assigned-issue-id issue-id})))
        (let [cluster-name (db/q '[:find ?name . :where [_ :seon.cluster/name ?name]] database)
              turn-id (@turn-next-id database cluster-name agent-id)
              open-turn (db/q '[:find ?turn . :in $ ?id :where
                                [?agent :seon.agent/id ?id]
                                [?turn :seon.turn/agent ?agent]
                                (not [?turn :seon.turn/closed-tx])] database agent-id)
              listener (db/q '[:find ?listen . :in $ ?agent-id ?issue-id :where
                               [?agent :seon.agent/id ?agent-id]
                               [?runtime :seon.runtime/agent ?agent]
                               [?runtime :seon.runtime/listens ?listen]
                               [?issue :seon.issue/id ?issue-id]
                               [?listen :seon.listen/attribute :seon.issue/budget]
                               [?listen :seon.listen/entity ?issue]] database agent-id issue-id)]
          (cond->
           [{:seon.issue/id issue-id :seon.issue/budget (:seon.issue/budget request)}
            [:db/retract [:seon.issue/id issue-id] :seon.issue/budget-exhausted-tx]
            {:seon.agent/id agent-id
             :seon.agent/settings (assoc (ai/agent-overlay database agent-id)
                                        :seon.config.run/max-episode-runs (:seon.issue/budget request))}]
            (nil? listener)
            (conj {:seon.runtime/agent [:seon.agent/id agent-id]
                   :seon.runtime/listens [{:seon.listen/attribute :seon.issue/budget
                                          :seon.listen/entity [:seon.issue/id issue-id]}]})
            (nil? open-turn)
            (conj [:db.fn/call @turn-open-call
                   {:seon.turn/id turn-id :seon.turn/agent [:seon.agent/id agent-id]
                    :seon.turn/opened-tx "datomic.tx"}])))))))

(defn exhaust-tx
  "Record the first exhausted close and deliver its status to root atomically."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :seon.db/tx-data]}
  [database agent-id]
  (let [issue-id (db/q '[:find ?id . :in $ ?agent-id :where
                         [?agent :seon.agent/id ?agent-id]
                         [?issue :seon.issue/agent ?agent]
                         [?issue :seon.issue/id ?id]
                         (not [?issue :seon.issue/resolved-tx])
                         (not [?issue :seon.issue/budget-exhausted-tx])
                         (not-join [?agent]
                           [?turn :seon.turn/agent ?agent]
                           (not [?turn :seon.turn/closed-tx]))] database agent-id)]
    (if (and issue-id (zero? (@turn-turns-left database agent-id)))
      (let [view (status {:seon.db/db database :seon.issue/id issue-id})
            spent (@turn-episode-runs database agent-id)
            limit (db/q '[:find ?limit . :where [?config :seon.config/cluster _]
                           [?config :seon.config.message/max-chain ?limit]] database)
            delivery (message/delivery
                      database
                      {:seon.agent/id agent-id :seon.config.message/max-chain limit
                       :my.message/value
                       {:seon.message/id (id/id [issue-id :budget-exhausted (:seon.issue/budget view)])
                        :my.message/to "root" :my.message/about issue-id
                        :my.message/content (str "Issue " issue-id " exhausted its budget after " spent
                                                 " ordinary turns.\n" (status-text view))}})]
        (when-let [failure (first (:seon.error/values delivery))]
          (throw (ex-info (:seon.error/message failure) failure)))
        (into [[:db/add [:seon.issue/id issue-id] :seon.issue/budget-exhausted-tx "datomic.tx"]]
              (:seon.message/rows delivery)))
      [])))

(defn start!
  "Start or resume the same issue worker with a larger total budget atomically."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/budget :seon.issue/budget]
                             [:seon.ns/name {:optional true} :seon.ns/name]
                             [:seon.agent/settings {:optional true} :seon.config/agent-overlay]
                             [:seon.config.ai/no-provider {:optional true} :seon.config.ai/no-provider]]]
                  [:or :map :seon.db/error-result :seon.issue/not-found-error :seon.issue/already-started-error :seon.issue/no-tests-error :seon.issue/no-namespace-error :seon.issue/no-cluster-error :seon.issue/worker-exists-error :seon.issue/not-a-test-error]]}
  [{connection :seon.db/connection :as request}]
  (let [report (db/transact! connection [[:db.fn/call #'start-tx (dissoc request :seon.db/connection)]])]
    (if (and (map? report) (:seon.error/at report)
           (:seon.error/layer report) (:seon.error/operation report)) ; debt: database and detector reads still declare generic seon.db/error-result.
 report
      (status {:seon.db/db (:db-after report) :seon.issue/id (:seon.issue/id request)}))))

(defn add-tx
  "Author a new issue inside the writer; existing identities refuse."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/title :seon.issue/title]
                        [:seon.issue/problem :seon.issue/problem]
                        [:seon.issue/severity :seon.issue/severity]
                        [:seon.issue/functions {:optional true} :seon.issue/functions]
                        [:seon.issue/tests {:optional true} :seon.issue/tests]
                        [:seon.agent/id :seon.agent/id]]]
                  :seon.db/tx-data]}
  [database request]
  (let [subject (sort-by pr-str (:seon.issue/functions request))
        issue-id (id/id [(:seon.issue/title request) subject])]
    (when (db/pull database [:seon.issue/id] [:seon.issue/id issue-id])
      (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/add-tx
        :seon.error/message "Issue operation requires a new title and subject identity."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/add-tx
        :seon.error/diagnostic-member :seon.issue/existing-issue-id
        :seon.error/diagnostic-expected "a new title and subject identity"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/already-exists
        :seon.error/diagnostic-evidence {}
        :seon.issue/existing-issue-id issue-id})))
    (when-not (db/pull database [:seon.agent/id] [:seon.agent/id (:seon.agent/id request)])
      (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/add-tx
        :seon.error/message "Issue operation requires an existing author agent."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/add-tx
        :seon.error/diagnostic-member :seon.issue/missing-author-id
        :seon.error/diagnostic-expected "an existing author agent"
        :seon.error/diagnostic-offending request
        :seon.error/offending request
        :seon.error/diagnostic-cause :seon.issue/no-author
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-author-id (:seon.agent/id request)})))
    (require-test-refs! database (:seon.issue/tests request))
    [(assoc (select-keys request [:seon.issue/title :seon.issue/problem :seon.issue/severity
                                 :seon.issue/functions :seon.issue/tests])
            :seon.issue/id issue-id :seon.issue/status :open
            :seon.issue/created-by [:seon.agent/id (:seon.agent/id request)]
            :seon.issue/opened (java.util.Date.))]))

(defn add!
  "Author an issue using title and function refs as its stable identity."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/title :seon.issue/title]
                             [:seon.issue/problem :seon.issue/problem]
                             [:seon.issue/severity :seon.issue/severity]
                             [:seon.issue/functions {:optional true} :seon.issue/functions]
                             [:seon.issue/tests {:optional true} :seon.issue/tests]]]
                  [:or :map :seon.db/error-result :seon.issue/already-exists-error :seon.issue/no-author-error :seon.issue/not-a-test-error :seon.issue/not-found-error]]}
  [{connection :seon.db/connection :as request}]
  (let [report (db/transact! connection [[:db.fn/call #'add-tx (dissoc request :seon.db/connection)]])]
    (if (and (map? report) (:seon.error/at report)
           (:seon.error/layer report) (:seon.error/operation report)) ; debt: database and detector reads still declare generic seon.db/error-result.
 report
      (status {:seon.db/db (:db-after report)
               :seon.issue/id (id/id [(:seon.issue/title request) (sort-by pr-str (:seon.issue/functions request))])}))))

(defn guard-call
  "Validate an additive issue-test request inside the serial writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/id :seon.issue/id]
                        [:seon.issue/tests :seon.issue/tests]
                        [:seon.agent/id :seon.agent/id]]]
                  :seon.db/tx-data]}
  [database request]
  (let [row (db/pull database [:seon.issue/title] [:seon.issue/id (:seon.issue/id request)])]
    (when-not (:seon.issue/title row) (refuse! (error/diagnostic
       {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.issue/request
        :seon.error/operation 'seon.issue/guard-call
        :seon.error/message "Issue operation requires an existing issue."
        :seon.error/diagnostic-layer :seon.issue/request
        :seon.error/diagnostic-operation 'seon.issue/guard-call
        :seon.error/diagnostic-member :seon.issue/missing-issue-id
        :seon.error/diagnostic-expected "an existing issue"
        :seon.error/diagnostic-offending (:seon.issue/id request)
        :seon.error/offending (:seon.issue/id request)
        :seon.error/diagnostic-cause :seon.issue/not-found
        :seon.error/diagnostic-evidence {}
        :seon.issue/missing-issue-id (:seon.issue/id request)})))
    (require-test-refs! database (:seon.issue/tests request))
    [{:seon.issue/id (:seon.issue/id request) :seon.issue/tests (:seon.issue/tests request)}]))

(defn tests-tx
  "Add success tests through the issue writer guard."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/id :seon.issue/id]
                        [:seon.issue/tests :seon.issue/tests]
                        [:seon.agent/id :seon.agent/id]]]
                  :seon.db/tx-data]}
  [_database request]
  [[:db.fn/call #'guard-call request]])

(defn tests!
  "Add tests and return the changed issue. Direct database guards are separate."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/tests :seon.issue/tests]]]
                  [:or :map :seon.db/error-result :seon.issue/not-found-error :seon.issue/not-a-test-error]]}
  [{connection :seon.db/connection :as request}]
  (let [report (db/transact! connection [[:db.fn/call #'tests-tx (dissoc request :seon.db/connection)]])]
    (if (and (map? report) (:seon.error/at report)
           (:seon.error/layer report) (:seon.error/operation report)) ; debt: database and detector reads still declare generic seon.db/error-result.
 report
      (status {:seon.db/db (:db-after report) :seon.issue/id (:seon.issue/id request)}))))

(defn report
  "Inspect indexed issues and derive the indexer's current refusal report."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/root :string]
                             [:seon.issue/class {:optional true} :string]]]
                  :map]}
  [{database :seon.db/db root :seon.issue/root class-tag :seon.issue/class}]
  (let [source-notes (notes root)
        tx (index-tx database source-notes)
        class-id (when class-tag
                   (some (fn [note]
                           (let [parsed (parse-note note)]
                             (when (and (contains? (:seon.issue.parse/tags parsed) class-tag)
                                        (contains? (:seon.issue.parse/tags parsed) "class-kill"))
                               (:seon.issue/id parsed))))
                         source-notes))
        rows (if class-tag
               (if class-id
                 (:seon.issue/members
                  (db/pull database '[{:seon.issue/members [:seon.issue/id :seon.issue/path :seon.issue/status]}]
                           [:seon.issue/id class-id]))
                 [])
               (issues {:seon.db/db database :seon.issue/status :open}))
        refusals (cond-> (:seon.issue/refusals (meta tx))
                   (and class-tag (nil? class-id))
                   (conj (diagnostic "docs/seon/issues" :unknown-class class-tag)))
        unresolved (:seon.issue/unresolved (meta tx))]
    {:seon.issue/count (count rows)
     :seon.issue/entities (vec (sort-by :seon.issue/id rows))
     :seon.issue/refusals refusals
     :seon.issue/ambiguous (:seon.issue/ambiguous (meta tx))
     :seon.issue/unresolved unresolved
     :seon.issue.unresolved/tokens (reduce + 0 (vals unresolved))
     :seon.issue.unresolved/paths (count unresolved)
     :seon.issue.parse/undated (count (remove :seon.issue/opened source-notes))}))
