(ns seon.issue
  "Issues connect authored problem statements to program identities."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.id :as id]
            [seon.repl :as repl]))

(defn- words [text]
  (into [] (comp
            (filter #(first %))
            (map #(apply str (second %))))
        (map (fn [characters]
               [(or (Character/isLetterOrDigit ^char (first characters))
                    (contains? #{\. \/ \: \- \_ \? \! \* \+ \< \> \= \$ \%} (first characters)))
                characters])
             (partition-by #(or (Character/isLetterOrDigit ^char %)
                                (contains? #{\. \/ \: \- \_ \? \! \* \+ \< \> \= \$ \%} %))
                           text))))

(defn- parse-note [{:seon.issue/keys [path text]}]
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
      (throw (ex-info "Issue directory is absent." {:seon.error/kind :seon.issue/notes-absent :seon.issue/path (str directory)})))
    (->> (concat (.listFiles directory) (.listFiles (io/file directory "archive")))
         (filter #(and (.isFile ^java.io.File %)
                       (str/ends-with? (.getName ^java.io.File %) ".md")
                       (not (contains? #{"README.md" "index.md" "AGENTS.md"} (.getName ^java.io.File %)))))
         (sort-by #(.getPath ^java.io.File %))
         (mapv (fn [file]
                 {:seon.issue/path (str "docs/seon/issues/"
                                        (when (= "archive" (.getName (.getParentFile ^java.io.File file))) "archive/")
                                        (.getName ^java.io.File file))
                  :seon.issue/text (slurp file)})))))

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

(defn- replacement-tx [current desired]
  (let [eid (:db/id current)
        normalized (fn [value]
                     (if (coll? value)
                       (set (map #(if (map? %) (get % :db/id %) %) value))
                       value))]
    (into (mapv (fn [attribute] [:db/retract eid attribute])
                (for [attribute (keys current)
                      :when (and (not (contains? #{:db/id :seon.issue/id} attribute))
                                 (not= (normalized (get current attribute)) (normalized (get desired attribute))))]
                  attribute))
          [(assoc desired :db/id eid)])))

(defn index-tx
  "Derive exact indexed facts; metadata carries every refused citation or note.
  Worker assignment and additive tests survive replacement of the prose."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:sequential [:map [:seon.issue/path :string] [:seon.issue/text :string]]]]
                  :seon.db/tx-data]}
  [database issue-notes]
  (let [parsed (mapv parse-note issue-notes)
        duplicates (->> parsed (map :seon.issue/id) frequencies
                        (keep (fn [[slug n]] (when (> n 1) slug))) set)
        functions (into {} (db/q '[:find ?sym ?e :where [?e :seon.fn/sym ?sym]] database))
        tests (into {} (db/q '[:find ?sym ?e :where [?e :seon.test/sym ?sym]] database))
        errors (into {} (db/q '[:find ?sig ?e :where [?e :seon.error/signature ?sig]] database))
        existing (db/q '[:find [?e ...] :where [?e :seon.issue/path]] database)
        existing (mapv #(db/pull database '[*] %) existing)
        by-slug (into {} (map (juxt :seon.issue/id identity)) existing)
        present (set (map :seon.issue/id parsed))
        classes (into {} (mapcat (fn [note]
                                  (when (contains? (:seon.issue.parse/tags note) "class-kill")
                                    (for [tag (:seon.issue.parse/tags note)
                                          :when (str/starts-with? tag "class/")]
                                      [tag (:seon.issue/id note)])))) parsed)
        results
        (mapv
         (fn [{:seon.issue/keys [id path title status severity problem] :as note}]
           (let [invalid (cond
                           (contains? duplicates id) :duplicate-slug
                           (not= "issue" (:seon.issue.parse/type note)) :invalid-type
                           (not (contains? #{:open :resolved :superseded} status)) :invalid-status
                           (not (contains? #{:blocker :friction :cleanup} severity)) :invalid-severity
                           (str/blank? title) :missing-title
                           (str/blank? problem) :missing-problem)
                 tokens (:seon.issue.parse/words note)
                 symbols (keep qualified-token tokens)
                 missing (remove #(or (get functions (str %)) (get tests (str %))) symbols)
                 created (:seon.issue.parse/created note)
                 opened (when created
                          (try (java.util.Date/from
                                (.toInstant (.atStartOfDay (java.time.LocalDate/parse created)
                                                          java.time.ZoneOffset/UTC)))
                               (catch Exception _ nil)))
                 diagnostics (cond-> (mapv #(diagnostic path :unresolved-symbol %) (sort missing))
                               (and created (nil? opened))
                               (conj (diagnostic path :invalid-created created)))
                 current (get by-slug id)
                 test-refs (into (set (map :db/id (:seon.issue/tests current)))
                                 (keep #(get tests (str %))) symbols)
                 row (cond-> (select-keys note [:seon.issue/id :seon.issue/path :seon.issue/title
                                                :seon.issue/status :seon.issue/severity :seon.issue/problem])
                       opened (assoc :seon.issue/opened opened)
                       (seq test-refs) (assoc :seon.issue/tests test-refs))
                 row (reduce (fn [row [attribute values]]
                               (if (seq values) (assoc row attribute (set values)) row))
                             row
                             [[:seon.issue/functions (keep #(get functions (str %)) symbols)]
                              [:seon.issue/errors (keep errors (filter #(hex-token? 64 %) tokens))]
                              [:seon.issue/commits (filter #(hex-token? 9 %) tokens)]
                              [:seon.issue/members
                               (for [member parsed
                                     :when (and (not= id (:seon.issue/id member))
                                                (some #(= id (get classes %)) (:seon.issue.parse/tags member)))]
                                 [:seon.issue/id (:seon.issue/id member)])]])]
             (if invalid
               {:seon.issue/refusals [(diagnostic path invalid id)] :seon.issue/tx []}
               {:seon.issue/refusals diagnostics
                :seon.issue/tx
                (if current
                  (replacement-tx current
                    (merge (apply dissoc current
                                  [:seon.issue/path :seon.issue/title :seon.issue/status :seon.issue/severity
                                   :seon.issue/problem :seon.issue/opened :seon.issue/functions
                                   :seon.issue/errors :seon.issue/commits :seon.issue/members])
                           row))
                  [row])})))
         parsed)
        removed (remove #(contains? present (:seon.issue/id %)) existing)
        tx (into (mapv #(hash-map :seon.issue/id %) (sort present))
                 (concat (mapcat :seon.issue/tx results)
                         (mapcat #(replacement-tx % {:seon.issue/id (:seon.issue/id %)}) removed)))]
    (with-meta tx {:seon.issue/refusals (vec (mapcat :seon.issue/refusals results))})))

(defn index!
  "Index notes through the writer and return counts plus citation refusals.
  The request supplies the notes, keeping filesystem reads outside the writer."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.issue/notes [:sequential [:map [:seon.issue/path :string] [:seon.issue/text :string]]]]]]
                  :map]}
  [{connection :seon.db/connection issue-notes :seon.issue/notes}]
  (let [result (db/transact! connection [[:db.fn/call #'index-tx issue-notes]])]
    (if (:seon.error/kind result) result
      (let [database (:db-after result)
            tx (index-tx database issue-notes)]
        {:seon.issue/count (count (db/q '[:find [?e ...] :where [?e :seon.issue/path]] database))
         :seon.issue/refusals (:seon.issue/refusals (meta tx))}))))

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

(defn status
  "Read the issue and test outcomes; verification follows assignment."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]]
                  [:or :map :seon.error/value]]}
  [{database :seon.db/db issue-id :seon.issue/id}]
  (let [row (db/pull database '[:db/id :seon.issue/id :seon.issue/title :seon.issue/status :seon.issue/severity
                                  :seon.issue/problem :seon.issue/path :seon.issue/opened :seon.issue/commits
                                  :seon.issue/agent :seon.issue/budget :seon.issue/resolved-tx
                                  {:seon.issue/members [:seon.issue/id]}
                                  {:seon.issue/tests [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count
                                                       {:seon.test/run [:seon.test.run/id :seon.test.run/basis-t]}]}
                                  {:seon.issue/functions [:seon.fn/sym {:seon.fn/ns [:seon.ns/name]}]}
                                  {:seon.issue/errors [:seon.error/signature {:seon.error/occurrences [:seon.error.occurrence/count]}]}]
                     [:seon.issue/id issue-id])]
    (if-not (:seon.issue/title row)
      {:seon.error/kind :seon.issue/not-found :seon.error/message (str "No current issue " issue-id)}
      (let [started (db/q '[:find ?tx . :in $ ?issue :where [?issue :seon.issue/agent _ ?tx]]
                          database (:db/id row))
            test-rows (mapv
                       (fn [test-value]
                         (let [basis (get-in test-value [:seon.test/run :seon.test.run/basis-t])
                               state (cond
                                       (not (:seon.test/run test-value)) :unrun
                                       (or (pos? (get test-value :seon.test/fail-count 0))
                                           (pos? (get test-value :seon.test/error-count 0))) :red
                                       (and started basis (>= basis started)
                                            (pos? (get test-value :seon.test/pass-count 0))
                                            (= 0 (:seon.test/fail-count test-value))
                                            (= 0 (:seon.test/error-count test-value))) :verified
                                       :else :unverified)]
                           (assoc test-value :seon.issue.test/state state)))
                       (sort-by :seon.test/sym (:seon.issue/tests row)))]
        (assoc row :seon.issue/tests test-rows
                   :seon.issue/check-form
                   (list 'my.test/check {:seon.test/changed (mapv :seon.test/sym test-rows)}))))))

(defn render-ai
  "Emit the ordinary read for this issue; its tests define completion."
  {:malli/schema [:=> [:cat [:or :seon.issue/issue :seon.render/unit]] :seon.render/source]}
  [unit]
  (let [row (or (:seon.render/value unit) unit)]
    (str ";; My issue. Its tests define done; (my.test/check ...) runs them.\n"
         (repl/source-text (list 'my.issue/status {:seon.issue/id (:seon.issue/id row)})))))

(defn render-html
  "Show the issue and current test outcomes as one block."
  {:malli/schema [:=> [:cat [:or :seon.issue/issue :seon.render/unit]] :seon.render/hiccup]}
  [unit]
  (let [row (or (:seon.render/value unit) unit)
        view (if-let [database (:seon.db/db unit)]
               (status {:seon.db/db database :seon.issue/id (:seon.issue/id row)}) row)]
    [:section {:class "seon-family-entry seon-issue"}
     [:h3 (:seon.issue/title view)]
     [:p (:seon.issue/problem view)]
     [:p (str "Status: " (:seon.issue/status view))]
     (into [:ul] (map (fn [test-value]
                       [:li (str (:seon.test/sym test-value) " — "
                                 (get test-value :seon.issue.test/state :unrun))])
                     (:seon.issue/tests view)))]))

(defn- identity-row [database issue]
  (db/pull database
           '[:seon.issue/id :seon.issue/path :seon.issue/title :seon.issue/status
             :seon.issue/severity :seon.issue/problem :seon.issue/opened :seon.issue/commits
             {:seon.issue/functions [:seon.fn/sym]}
             {:seon.issue/tests [:seon.test/sym]}
             {:seon.issue/errors [:seon.error/signature]}
             {:seon.issue/members [:seon.issue/id]}]
           issue))

(defn adopt-tx
  "Reconcile the published issue facts by identity into a development database."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map]] :seon.db/tx-data]}
  [database rows]
  (let [current (mapv #(db/pull database '[*] %)
                     (db/q '[:find [?e ...] :where [?e :seon.issue/path]] database))
        by-id (into {} (map (juxt :seon.issue/id identity)) current)
        ids (set (map :seon.issue/id rows))
        ref-attributes {:seon.issue/functions :seon.fn/sym
                        :seon.issue/tests :seon.test/sym
                        :seon.issue/errors :seon.error/signature
                        :seon.issue/members :seon.issue/id}]
    (into (mapv #(hash-map :seon.issue/id %) (sort ids))
          (concat
           (mapcat
            (fn [row]
              (let [row (reduce-kv
                         (fn [row attribute identity-attribute]
                           (if (get row attribute)
                             (assoc row attribute (set (map #(vector identity-attribute (get % identity-attribute))
                                                           (get row attribute)))) row))
                         row ref-attributes)
                    prior (get by-id (:seon.issue/id row))
                    preserved (select-keys prior [:seon.issue/agent :seon.issue/budget :seon.issue/resolved-tx])
                    desired (merge preserved row)
                    desired (if (:seon.issue/agent prior)
                              (update desired :seon.issue/tests
                                      #(into (set %) (map :db/id (:seon.issue/tests prior))))
                              desired)]
                (if prior (replacement-tx prior desired) [desired])))
            rows)
           (mapcat #(replacement-tx % {:seon.issue/id (:seon.issue/id %)})
                   (remove #(contains? ids (:seon.issue/id %)) current))))))

(defn adopt!
  "Adopt issue entities from the exact published database, never reread the files."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/database-value]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [connection source]
  (let [rows (mapv #(identity-row source %)
                   (db/q '[:find [?e ...] :where [?e :seon.issue/path]] source))]
    (db/transact! connection [[:db.fn/call #'adopt-tx rows]])))

(def done-query
  "Nonempty tests all have positive green results whose basis follows assignment."
  '[:find ?subject .
    :in $ ?subject
    :where
    [?subject :seon.issue/agent _ ?started]
    [?subject :seon.issue/tests _]
    (not-join [?subject ?started]
      [?subject :seon.issue/tests ?test]
      (not-join [?test ?started]
        [?test :seon.test/pass-count ?passes]
        [(pos? ?passes)]
        [?test :seon.test/fail-count 0]
        [?test :seon.test/error-count 0]
        [?test :seon.test/run ?run]
        [?run :seon.test.run/basis-t ?basis]
        [(>= ?basis ?started)]))])

(defn- refuse! [reason message]
  (throw (ex-info message {:seon.error/kind reason :seon.error/message message})))

(defn- require-test-refs! [database references]
  (doseq [reference references]
    (when-not (:seon.test/sym
               (db/pull database [:seon.test/sym]
                        (if (map? reference) (:db/id reference) reference)))
      (refuse! :seon.issue/not-a-test "Every success ref must identify a test."))))

(defn start-tx
  "Create the assigned worker, its plan, and its opening in one writer decision."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/id :seon.issue/id]
                        [:seon.issue/budget :seon.issue/budget]
                        [:seon.ns/name {:optional true} :seon.ns/name]
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
    (when-not (:seon.issue/title row) (refuse! :seon.issue/not-found "The issue does not exist."))
    (when (:seon.issue/agent row) (refuse! :seon.issue/already-started "The issue already has a worker."))
    (when-not (seq (:seon.issue/tests row)) (refuse! :seon.issue/no-tests "Starting an issue requires at least one test."))
    (require-test-refs! database (:seon.issue/tests row))
    (when-not namespace-name (refuse! :seon.issue/no-namespace "Supply a namespace or a function with a namespace."))
    (when-not cluster-name (refuse! :seon.issue/no-cluster "The database has no cluster identity."))
    (when (db/pull database [:seon.agent/id] [:seon.agent/id agent-id])
      (refuse! :seon.issue/worker-exists "The derived worker identity already exists."))
    (let [creation ((requiring-resolve 'seon.cluster.agent/creation-tx)
                    {:seon.agent/id agent-id :seon.ns/name namespace-name :seon.cluster/name cluster-name})
          step-id (id/id [:seon.issue/step issue-id])
          creation (mapv
                    (fn [entry]
                      (if (= agent-id (:seon.agent/id entry))
                        (-> entry
                            (update :seon.agent/settings merge
                                    (cond-> {:seon.config.run/max-episode-runs (:seon.issue/budget request)}
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
                   :seon.ns/requires [[:seon.ns/name 'my.issue] [:seon.ns/name 'my.test]]})
            ((requiring-resolve 'seon.turn/generated-run-tx)
             database {:seon.agent/id agent-id :seon.turn/id (id/id [:seon.issue/opening issue-id])
                       :seon.turn/opened-tx "datomic.tx"
                       :seon.turn/starting-ns [:seon.ns/name namespace-name]
                       :seon.turn/trigger [:seon.issue/id issue-id]})))))

(defn start!
  "Assign an issue and open its worker atomically; returns the changed issue."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/budget :seon.issue/budget]
                             [:seon.ns/name {:optional true} :seon.ns/name]
                             [:seon.config.ai/no-provider {:optional true} :seon.config.ai/no-provider]]]
                  :map]}
  [{connection :seon.db/connection :as request}]
  (let [report (db/transact! connection [[:db.fn/call #'start-tx (dissoc request :seon.db/connection)]])]
    (if (:seon.error/kind report) report
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
      (refuse! :seon.issue/already-exists "An issue with this title and subject already exists."))
    (when-not (db/pull database [:seon.agent/id] [:seon.agent/id (:seon.agent/id request)])
      (refuse! :seon.issue/no-author "The author agent does not exist."))
    (require-test-refs! database (:seon.issue/tests request))
    [(assoc (select-keys request [:seon.issue/title :seon.issue/problem :seon.issue/severity
                                 :seon.issue/functions :seon.issue/tests])
            :seon.issue/id issue-id :seon.issue/status :open :seon.issue/opened (java.util.Date.))]))

(defn add!
  "Author an issue using title and function refs as its stable identity."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/title :seon.issue/title]
                             [:seon.issue/problem :seon.issue/problem]
                             [:seon.issue/severity :seon.issue/severity]
                             [:seon.issue/functions {:optional true} :seon.issue/functions]
                             [:seon.issue/tests {:optional true} :seon.issue/tests]]]
                  :map]}
  [{connection :seon.db/connection :as request}]
  (let [report (db/transact! connection [[:db.fn/call #'add-tx (dissoc request :seon.db/connection)]])]
    (if (:seon.error/kind report) report
      (status {:seon.db/db (:db-after report)
               :seon.issue/id (id/id [(:seon.issue/title request) (sort-by pr-str (:seon.issue/functions request))])}))))

(defn tests-tx
  "Add success tests without retracting existing refs, at the serial writer."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/id :seon.issue/id]
                        [:seon.issue/tests :seon.issue/tests]
                        [:seon.agent/id :seon.agent/id]]]
                  :seon.db/tx-data]}
  [database request]
  (let [row (db/pull database [:seon.issue/title] [:seon.issue/id (:seon.issue/id request)])]
    (when-not (:seon.issue/title row) (refuse! :seon.issue/not-found "The issue does not exist."))
    (require-test-refs! database (:seon.issue/tests request))
    [{:seon.issue/id (:seon.issue/id request) :seon.issue/tests (:seon.issue/tests request)}]))

(defn tests!
  "Add tests and return the changed issue. Direct database guards are separate."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.issue/tests :seon.issue/tests]]]
                  :map]}
  [{connection :seon.db/connection :as request}]
  (let [report (db/transact! connection [[:db.fn/call #'tests-tx (dissoc request :seon.db/connection)]])]
    (if (:seon.error/kind report) report
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
                   (conj (diagnostic "docs/seon/issues" :unknown-class class-tag)))]
    {:seon.issue/count (count rows)
     :seon.issue/entities (vec (sort-by :seon.issue/id rows))
     :seon.issue/refusals refusals}))
