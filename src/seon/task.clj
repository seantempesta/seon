(ns seon.task
  "Runtime tasks (spec 7.8): create a task, assign it to an agent's branch and
  record a merge request. Tasks live on the cluster, so every write goes to
  main through the one task writer (`seon.task/adopt-tasks-tx`)."
  (:require [seon.cache :as cache]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [malli.transform :as mt]
            [malli.util :as mu]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.ref :as ref]
            [seon.schema :as schema]))

;;; DECLARATIONS (spec 7.1-7.16): declarations from resources/seon/tasks/<ns>.edn; subjects
;;; and proofs are the `:seon.task/tasks` symbols program entities carry.

(defn- replacement-tx
  "The delta from one stored task to its desired facts, over the `owned` attributes
  only: a declared attribute whose value already holds contributes nothing, a changed
  one is retracted and asserted, an absent desired one is retracted."
  {:malli/schema [:=> [:cat :map :map [:set :qualified-keyword]] [:vector :seon.schema/value]]}
  [current desired owned]
  (let [eid (:db/id current)
        normalized (fn [value]
                     (cond
                       (and (map? value) (= #{:db/id} (set (keys value)))) (:db/id value)
                       (coll? value) (set (map #(if (map? %) (get % :db/id %) %) value))
                       :else value))
        changed (into (sorted-set)
                      (for [attribute (into (set (keys current)) (keys desired))
                            :when (and (not (contains? #{:db/id :seon.task/id} attribute))
                                       (contains? owned attribute)
                                       (not= (normalized (get current attribute))
                                             (normalized (get desired attribute))))]
                        attribute))
        asserted (select-keys desired changed)]
    (cond-> (mapv (fn [attribute] [:db/retract eid attribute])
                  (filter #(contains? current %) changed))
      (seq asserted) (conj (assoc asserted :db/id eid)))))

(defn task-handle
  "The task handle of an identifying aspect: seon.id/id over [ns name] (3b.6)."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name] :seon.task/handle]}
  [ns name]
  (id/id [ns name]))

(defn symbol-name?
  "Whether `task-name` reads back as one symbol's name: no whitespace, delimiter, `/` or `:`,
  not leading with a digit, `'` or `#`."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String task-name]
  (boolean (and (seq task-name)
                (not (or (Character/isDigit (.charAt task-name 0)) (#{\' \#} (.charAt task-name 0))))
                (not-any? #(or (Character/isWhitespace (char %)) (#{\( \) \[ \] \{ \} \" \@ \^ \` \~ \, \; \\ \/ \:} %))
                          task-name))))

(defn task-sym
  "The symbol `ns/name` that tags and readers name a task by."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name] :seon.task/sym]}
  [ns task-name]
  (symbol (name ns) task-name))

(defn task-path?
  "Whether a repository-relative path names a task file."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (let [file (io/file path)]
    (and (= "resources/seon/tasks" (.getParent file)) (str/ends-with? (.getName file) ".edn"))))

(defn- file-namespace
  "The owning namespace a task file's name states."
  {:malli/schema [:=> [:cat :string] :seon.task/ns]}
  [path]
  (symbol (str/replace-first (.getName (io/file path)) ".edn" "")))

(defn task-files
  "Task file texts under `directory`: every file, or the changed `paths`; a deleted file is \"[]\"."
  {:malli/schema [:=> [:cat :string [:maybe [:sequential :string]]] :seon.task/files]}
  [directory paths]
  (into (sorted-map)
        (map (fn [path] (let [file (io/file directory path)] [path (if (.isFile file) (slurp file) "[]")])))
        (filter task-path? (or paths (map #(str "resources/seon/tasks/" (.getName %))
                                          (.listFiles (io/file directory "resources/seon/tasks")))))))

(defn- read-error
  "The owner's declared read or write refusal, when `result` is one."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :seon.error/base]]}
  [result]
  (when (refusal/error? result) result))

(defn declaration-members
  "The members of `:seon.task/declaration` under `projection`, in schema order, each with whether its schema is a set."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector [:tuple :qualified-keyword :boolean]]]}
  [projection]
  (schema/projection-cache-value
   projection ::declaration-members
   #(mapv (fn [[k _ s]] [k (= :set (m/type (m/deref-all s)))])
          (m/children (m/deref-all (mr/schema (:seon.schema.projection/registry projection) :seon.task/declaration))))))

(defn declaration
  "A stored or proposed task's `:seon.task/declaration`: its declared members, a set member as a set."
  {:malli/schema [:=> [:cat :seon.schema/projection [:maybe :map]] [:maybe :seon.task/declaration]]}
  [projection task]
  (when task
    (into {} (keep (fn [[k set?]] (when-let [[_ v] (find task k)] [k (if set? (set v) v)])))
          (declaration-members projection))))

(defn- edn-forms
  "Every EDN form of `text`, in order."
  {:malli/schema [:=> [:cat :string] [:vector :seon.schema/value]]}
  [text]
  (let [reader (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
        eof (Object.)]
    (loop [forms []]
      (let [form (edn/read {:eof eof} reader)]
        (if (identical? eof form) forms (recur (conj forms form)))))))

(defn- entry-file-schema
  "A task file's schema under `projection`: a vector of `:seon.task/declaration`s, closed."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.instrument/compiled-schema]}
  [projection]
  (schema/projection-cache-value
   projection ::entry-file-schema
   #(m/schema [:vector (mu/closed-schema (mr/schema (:seon.schema.projection/registry projection) :seon.task/declaration))])))

(defn file-declarations
  "Read one task file's EDN forms with `clojure.edn` (nothing evaluated), require one
  vector of valid entries with empty sets absent and symbol names, and derive each handle and symbol."
  {:malli/schema [:=> [:cat :seon.schema/projection :string :string] [:vector :seon.task/task]]}
  [projection path text]
  (let [ns (file-namespace path)
        refuse-task! #(functions/task-refused! 'seon.task/file-declarations %1 ns %2 %3)
        forms (try (edn-forms text)
                   (catch clojure.lang.EdnReader$ReaderException failure (refuse-task! :unreadable-file text {:seon.error/throwable failure})))
        _ (when-not (task-path? path) (refuse-task! :misplaced-file path {}))
        _ (when-not (and (= 1 (count forms)) (vector? (first forms))) (refuse-task! :not-one-vector forms {}))
        ;; Only an empty set at a set member is absence (#{} clears); every
        ;; other member, empty or not, meets the declaration, closed.
        set-member? (into #{} (keep (fn [[k set?]] (when set? k))) (declaration-members projection))
        entries (mapv #(if (map? %) (into {} (remove (fn [[k v]] (and (set-member? k) (= #{} v)))) %) %) (first forms))]
    (when-let [explanation (m/explain (entry-file-schema projection) (mapv #(if (map? %) (assoc % :seon.task/ns ns) %) entries))]
      (refuse-task! :invalid-entry (first forms) {:seon.task/explanation (me/humanize explanation)}))
    (mapv (fn [{:seon.task/keys [name] :as entry}]
            (assoc entry :seon.task/id (task-handle ns name) :seon.task/sym (task-sym ns name) :seon.task/ns ns))
          entries)))

(def ^:private tree-attributes
  "The task attributes whose values are other tasks: authored and compared as lookup refs `[:seon.task/sym ns/name]`."
  [:seon.task/children :seon.task/needs])

(defn- stored-tasks
  "The stored tasks whose `attribute` (`:seon.task/ns` or `:seon.task/id`) is one of `values`, indexed once by handle."
  {:malli/schema [:=> [:cat :seon.db/database-value [:enum :seon.task/ns :seon.task/id] [:set [:or :seon.task/ns :seon.task/handle]]]
                  [:or [:map-of :seon.task/handle :map] :seon.db/invalid-read-error]]}
  [database attribute values]
  (let [entities (if (contains? (:schema database) attribute)
                   (db/q database '[:find [?e ...] :in $ ?a [?v ...] :where [?e ?a ?v]] attribute (vec values))
                   [])
        held (filter #(contains? (:schema database) %) tree-attributes)
        rows (or (read-error entities) (db/pull-many database (into '[*] (map (fn [a] {a [:seon.task/sym]})) held) entities))
        as-lookup (fn [row a] (if-let [targets (get row a)] (assoc row a (into #{} (map #(vector :seon.task/sym (:seon.task/sym %))) targets)) row))]
    (or (read-error rows) (into {} (map (juxt :seon.task/id #(reduce as-lookup % held))) rows))))

(defn- task-content
  "The task attributes the declaration readers observe, as immutable index values."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection] [:vector :seon.schema/value]]}
  [database projection]
  (mapv (fn [[attribute]]
          (let [datoms (if (contains? (:schema database) attribute)
                         (db/datoms database :aevt attribute) [])]
            (when (map? datoms) (throw (ex-info (:seon.error/message datoms) datoms)))
            datoms))
        (m/children (m/deref (mr/schema (:seon.schema.projection/registry projection) :seon.task/task)))))

(def ^:private task-basis-bytes-per-task 512)

(defn- task-basis-weight
  "The retained bytes of a basis: its declarations, by handle."
  {:malli/schema [:=> [:cat :map] nat-int?]}
  [basis]
  (* task-basis-bytes-per-task (count basis)))

(defn task-basis
  "The declarations stored now for the files' namespaces: the basis a caller reads with its proposal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/files] [:or :seon.task/basis :seon.db/invalid-read-error]]}
  [database files]
  (let [projection (db/carried-projection database)
        content (task-content database projection)
        names (set (map file-namespace (keys files)))]
    (cache/lookup ::task-basis [(:seon.schema.projection/forms projection) content names]
                  task-basis-weight
                  #(let [stored (stored-tasks database :seon.task/ns names)]
                     (or (read-error stored) (update-vals stored (partial declaration projection))))
                  #(not (read-error %)))))

(defn- refs-last
  "`tx` with every task-valued member of an entity map moved to a trailing `:db/add`, so a lookup ref
  `[:seon.task/sym ns/name]` resolves to a task the same transaction asserts earlier."
  {:malli/schema [:=> [:cat [:vector :seon.schema/value]] [:vector :seon.schema/value]]}
  [tx]
  (let [entity (fn [op] (or (:db/id op) [:seon.task/id (:seon.task/id op)]))
        adds (for [op tx :when (map? op) a tree-attributes target (sort-by str (get op a))] [:db/add (entity op) a target])]
    (into (into [] (keep #(cond (not (map? %)) %
                                (= #{:db/id} (set (keys (apply dissoc % tree-attributes)))) nil
                                :else (apply dissoc % tree-attributes)))
                tx)
          adds)))

(defn- refused
  "The task refusal value naming `rule`, the `holder` namespace and the offending value."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.task/refused :seon.task/holder :seon.schema/value]
                  :seon.task/refused-error]}
  [operation rule holder offending]
  (refusal/diagnostic (java.util.Date.) :seon.task/ingestion operation
                      {:seon.error/message (str holder " is refused: " (name rule) ".")
                       :seon.task/refused rule :seon.task/holder holder :seon.error/offending offending}))

(defn- waits-for
  "Each task's wait edges in `database`: its needs, its children (a parent is done when they are) and the
  needs of every ancestor, which it inherits."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:map-of :int [:set :int]] :seon.db/invalid-read-error]]}
  [database]
  (let [needs (db/q '[:find ?t ?n :where [?t :seon.task/needs ?n]] database)
        children (db/q '[:find ?p ?c :where [?p :seon.task/children ?c]] database)]
    (or (some read-error [needs children])
        (let [by (fn [pairs] (update-vals (group-by first pairs) #(set (map second %))))
              needs-of (by needs) children-of (by children) parent (into {} (map (fn [[p c]] [c p])) children)
              inherited (fn [t] (into #{} (mapcat needs-of) (take-while some? (iterate parent (parent t)))))]
          (into {} (map (fn [t] [t (into (into (get needs-of t #{}) (get children-of t)) (inherited t))]))
                (into (set (keys needs-of)) (mapcat identity) children))))))

(defn- cycle-in
  "One cycle of `graph` as the nodes along it, or nil when it has none."
  {:malli/schema [:=> [:cat [:map-of :int [:set :int]]] [:maybe [:vector :int]]]}
  [graph]
  (let [visit (fn visit [node trail done]
                (cond (some #{node} trail) {::cycle (conj (vec (drop-while #(not= node %) trail)) node)}
                      (done node) done
                      :else (let [trail (conj trail node)
                                  result (reduce (fn [done next] (let [r (visit next trail done)] (if (::cycle r) (reduced r) r)))
                                                 done (sort (graph node)))]
                              (if (::cycle result) result (conj result node)))))]
    (::cycle (reduce (fn [done node] (let [r (visit node [] done)] (if (::cycle r) (reduced r) r))) #{} (sort (keys graph))))))

(defn refuse-needs-cycle
  "The `:needs-cycle` refusal when `tx` would leave a task waiting on itself in `database` (a need, a
  child, or a need inherited from an ancestor, followed around), else nil. A transaction touching no
  `:seon.task/needs` or `:seon.task/children` answers nil unread; one the writer itself refuses is left
  to the writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction-data] [:maybe :seon.task/refused-error]]}
  [database tx]
  (when (some #(or (and (map? %) (some (partial contains? %) tree-attributes))
                   (and (vector? %) (some #{(get % 2)} tree-attributes)))
              tx)
    (let [after (db/with database tx)
          graph (when-not (read-error after) (waits-for after))
          found (when (and graph (not (read-error graph))) (cycle-in graph))
          syms (when found (mapv #(:seon.task/sym (db/pull after [:seon.task/sym] %)) found))]
      (when found
        (refused 'seon.task/refuse-needs-cycle :needs-cycle (symbol (namespace (first syms))) syms)))))

(defn- with-references
  "`task` with the references its plan and instructions cite (`seon.ref/names`), derived by the writer; none is no key."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:seon.task/keys [plan instructions] :as task}]
  (let [cited (into #{} (mapcat ref/names) (keep identity [plan instructions]))]
    (if (seq cited) (assoc task :seon.task/references cited) (dissoc task :seon.task/references))))

;; A new task takes the schema's defaults for the members it supplies none of: a supplied execution stays.
(defn task-defaults
  "The members a new task takes when it supplies none: `{}` decoded with the default value transformer under `:seon.task/task`."
  {:malli/schema [:=> [:cat :seon.schema/projection] :map]}
  [projection]
  (schema/projection-cache-value
   projection ::task-defaults
   #(m/decode (mr/schema (:seon.schema.projection/registry projection) :seon.task/task) {}
              (mt/default-value-transformer {:malli.transform/add-optional-keys true}))))

(defn- task-delta
  "The writer's delta from `stored` to `by-id` tasks, by handle, retractions first, so a task
  whose handle changed frees its unique symbol before its new entity claims it; a handle whose
  stored declaration differs from the caller's `basis`, both under the writer's projection,
  refuses with base, current and proposed. A new task no proposed or stored task owns as a child is
  written `:held`; a child runs under its subject's execution (`seon.task/execution`)."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :seon.task/handle :map] [:map-of :seon.task/handle :map]
                       :seon.task/basis] [:vector :seon.schema/value]]}
  [projection stored by-id basis]
  (let [owned (conj (set (keys (m/entries (m/deref (mr/schema (:seon.schema.projection/registry projection) :seon.task/declaration)))))
                    :seon.task/references)
        ;; what the writer holds for a task: its declaration and the references derived from its plan
        held (fn [task] (when task (cond-> (declaration projection task)
                                     (seq (:seon.task/references task)) (assoc :seon.task/references (set (:seon.task/references task))))))
        owned-children (into #{} (mapcat :seon.task/children) (concat (vals stored) (vals by-id)))]
    (refs-last
     (into []
          (mapcat (fn [id]
                    (let [proposed (some-> (by-id id) with-references)
                          current (declaration projection (stored id)) wanted (declaration projection proposed)]
                      (cond
                        (= (held (stored id)) (held proposed)) []
                        ;; the basis was declared under the reader's projection; compare it under the writer's,
                        ;; so a member a schema change retired is no revision
                        (not= (declaration projection (get basis id)) current)
                        (functions/task-refused! 'seon.task/tasks-tx :revision-conflict (:seon.task/ns (or (by-id id) (stored id))) id
                                                 (cond-> {} (get basis id) (assoc :seon.task/base (get basis id))
                                                   current (assoc :seon.task/current current) wanted (assoc :seon.task/proposed wanted)))
                        (nil? wanted) [[:db/retractEntity (:db/id (stored id))]]
                        current (replacement-tx (stored id) proposed owned)
                        (owned-children [:seon.task/sym (:seon.task/sym proposed)]) [(dissoc proposed :seon.task/execution)]
                        :else [(merge (task-defaults projection) proposed)]))))
          (sort-by (juxt #(contains? by-id %) identity) (into (set (keys stored)) (keys by-id)))))))

(defn- symbol-tx
  "`[:db/add task :seon.task/sym ns/name]` for every stored task, file or runtime, that carries
  no symbol yet: the symbol is derived from its ns and name like its handle."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.schema/value]]}
  [database]
  (if (contains? (:schema database) :seon.task/sym)
    (let [unnamed (db/q '[:find ?e ?ns ?name :where [?e :seon.task/id] [?e :seon.task/ns ?ns] [?e :seon.task/name ?name]
                          (not [?e :seon.task/sym])] database)]
      (when (read-error unnamed) (throw (ex-info (:seon.error/message unnamed) unnamed)))
      (mapv (fn [[e ns task-name]] [:db/add e :seon.task/sym (task-sym ns task-name)]) (sort unnamed)))
    []))

(defn- held-tx
  "`[:db/add task :seon.task/execution :held]` for every stored task with no execution and no parent: an
  absent execution is never read as running, so a root is :held until someone sets :running, and a child
  runs under its subject's (`seon.task/execution`)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.schema/value]]}
  [database]
  (if (contains? (:schema database) :seon.task/execution)
    (let [default (:seon.task/execution (task-defaults (db/carried-projection database)))
          unset (db/q '[:find [?e ...] :where [?e :seon.task/id] (not [?e :seon.task/execution _]) (not [_ :seon.task/children ?e])] database)]
      (when (read-error unset) (throw (ex-info (:seon.error/message unset) unset)))
      (mapv (fn [e] [:db/add e :seon.task/execution default]) (sort unset)))
    []))

(defn- admitted
  "`tx`, the writer's task delta, unless it would leave a task waiting on itself: then the
  `:needs-cycle` refusal is thrown, as every task writer refusal is."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.schema/value]] [:vector :seon.schema/value]]}
  [database tx]
  (if-let [refusal (refuse-needs-cycle database tx)]
    (throw (ex-info (:seon.error/message refusal) refusal))
    tx))

(defn- tasks-tx-in
  "The task declaration delta under the writer's current database and supplied basis."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/files :seon.task/basis] [:vector :seon.schema/value]]}
  [database files basis]
  (let [projection (db/carried-projection database)
        forms (:seon.schema.projection/forms projection)
        proposed (into [] (mapcat (fn [[path text]]
                                   (cache/lookup ::file-declarations [forms path text]
                                                 #(+ 256 (* 2 (count text)) (* 512 (count %)))
                                                 #(file-declarations projection path text)))) files)
        by-id (into {} (map (juxt :seon.task/id identity)) proposed)
        stored (stored-tasks database :seon.task/ns (set (map file-namespace (keys files))))]
    (when (read-error stored) (throw (ex-info (:seon.error/message stored) stored)))
    (when-let [sym (some (fn [[sym n]] (when (< 1 n) sym)) (frequencies (map :seon.task/sym proposed)))]
      (functions/task-refused! 'seon.task/tasks-tx :duplicate-name (symbol (namespace sym)) sym {}))
    ;; A runtime task (7.8, its writer recorded) is no file's declaration: never retracted here.
    (admitted database (into (into (symbol-tx database) (held-tx database)) (task-delta projection (into {} (remove (comp :seon.task/created-by val)) stored) by-id basis)))))

(defn- tasks-tx-weight
  "The retained bytes of a delta: its operations."
  {:malli/schema [:=> [:cat [:vector :seon.schema/value]] nat-int?]}
  [tx]
  (* 256 (count tx)))

(defn tasks-tx
  "The writer's delta from stored tasks to the declared `files`, by handle. A touched
  task whose current declaration differs from the caller's `basis` refuses with base,
  current and proposed (7.16); unchanged input is empty; a removed entry retracts.
  A runtime task (`:seon.task/created-by`) is outside every file's declaration; every
  stored task lacking its derived symbol gains it first (`symbol-tx`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/files :seon.task/basis] [:vector :seon.schema/value]]}
  [database files basis]
  (let [projection (db/carried-projection database)
        content (task-content database projection)]
    (cache/lookup ::tasks-tx [(:seon.schema.projection/forms projection) content files basis]
                  tasks-tx-weight
                  #(tasks-tx-in database files basis))))

(defn task-declaration
  "The declaration `database` holds for the task `handle`, its tree members as lookup refs; nil when absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/declaration :seon.db/invalid-read-error]]}
  [database handle]
  (let [stored (stored-tasks database :seon.task/id #{handle})]
    (or (read-error stored) (some->> (stored handle) (declaration (db/carried-projection database))))))

(defn task-revision
  "The reviewed revision of the task `handle` in `database`: seon.id/digest over its declaration; nil when absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/revision :seon.db/invalid-read-error]]}
  [database handle]
  (let [held (task-declaration database handle)]
    (if (read-error held) held (some->> held vector (id/digest 64)))))

(defn- owned-members
  "The members of `:seon.task/task` under `projection` whose schema carries `:seon.task/owned`."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :qualified-keyword]]}
  [projection]
  (schema/projection-cache-value
   projection ::owned-members
   #(let [registry (:seon.schema.projection/registry projection)]
      (into [] (comp (map first) (filter (fn [k] (:seon.task/owned (m/properties (mr/schema registry k))))))
            (m/entries (m/deref (mr/schema registry :seon.task/task)))))))

(defn template
  "Task `sym`'s owned members (`:seon.task/owned` on their schema) as a map: what a chain's task takes from its
  template. The `:unknown-task` refusal when `database` holds no task `sym`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym]
                  [:or :map :seon.task/refused-error :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database sym]
  (let [projection (db/carried-projection database)
        owned (owned-members projection)
        held (db/pull database (into [:seon.task/id :seon.task/ns :seon.task/name] owned) [:seon.task/sym sym])]
    (cond (read-error held) held
          (:seon.task/id held) (select-keys (declaration projection held) owned)
          :else (refused 'seon.task/template :unknown-task (symbol (namespace sym)) sym))))

(defn- identified
  "`task` under the handle and symbol its ns and name derive, keeping a negative tempid `:db/id`, which names it to
  the other maps of the same write (a `:seon.task/next`); any other `:db/id` is its source database's entity."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:seon.task/keys [ns name] :as task}]
  (cond-> (assoc task :seon.task/id (task-handle ns name) :seon.task/sym (task-sym ns name))
    (not (neg-int? (:db/id task))) (dissoc :db/id)))

(defn adopt-tasks-tx
  "Reconcile the selected `handles` into a development database from the `rows` the
  exact published source holds for them, against the `basis` the adoption read at
  the destination: a competing edit since refuses (7.16); a handle absent from
  `rows` retracts. Each row's handle and symbol derive from its ns and name; a new row keeps its negative tempid,
  so rows written together link by it, and a supplied execution. Every task the destination holds without its
  derived symbol gains it first."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map] [:set :seon.task/handle] :seon.task/basis]
                  [:vector :seon.schema/value]]}
  [database entities handles basis]
  (let [stored (stored-tasks database :seon.task/id handles)]
    (when (read-error stored) (throw (ex-info (:seon.error/message stored) stored)))
    (admitted database
              (into (into (symbol-tx database) (held-tx database))
                    (task-delta (db/carried-projection database) stored (into {} (map (juxt :seon.task/id identity)) (map identified entities)) basis)))))

(defn- task-handles
  "Every stored task handle, or with `retracted?` every handle `database`'s history
  retracted; none when the database declares no tasks."
  {:malli/schema [:=> [:cat :seon.db/database-value :boolean] [:or [:set :seon.task/handle] :seon.db/invalid-read-error]]}
  [database retracted?]
  (if (contains? (:schema database) :seon.task/id)
    (let [handles (if retracted?
                    (let [history (db/history database)]
                      (or (read-error history) (db/q '[:find [?h ...] :where [_ :seon.task/id ?h _ false]] history)))
                    (db/q database '[:find [?h ...] :where [_ :seon.task/id ?h]]))]
      (or (read-error handles) (set handles)))
    #{}))

(declare task-entity effective goal-owner)

(defn- changed-handles
  "The handles of the tasks whose facts `source` wrote after `base`, the commit
  of its lineage it was adopted from: read from `source`'s history since
  `base`'s basis, so the work follows the publication's change."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:or [:set :seon.task/handle] :seon.db/invalid-read-error]]}
  [base source]
  (let [since (db/since (db/history source) (db/basis-t base))
        datoms (if (read-error since) since (db/datoms since :eavt))]
    (or (read-error datoms)
        (into #{} (comp (filter #(= "seon.task" (namespace (:a %)))) (map :e) (distinct)
                        (keep (fn [e] (some #(:v (first (db/datoms % :eavt e :seon.task/id))) [source base]))))
              datoms))))

(defn- adoption-conflict
  "Root's problem task for the task `handle` that both the publication (from
  `base` to `theirs`) and main (`ours`) revised: main kept its value. Named by
  its identity, so a repeat writes nothing."
  {:malli/schema [:=> [:cat :seon.task/handle [:maybe :seon.task/declaration] [:maybe :seon.task/declaration]
                       [:maybe :seon.task/declaration]] :seon.task/task]}
  [handle base theirs ours]
  (let [{task-ns :seon.task/ns task-name :seon.task/name} (or ours theirs base)
        revision #(if % (id/digest 64 [%]) :absent)]
    (task-entity {:seon.task/ns 'seon.task
                  :seon.task/name (str "adoption-conflict-" (id/id [:adoption-conflict handle (revision theirs) (revision ours)]))
                  :seon.task/plan (str "The published task files and main both revised task `" (task-sym task-ns task-name) "`"
                                         " since the adopted source commit; main kept its value."
                                         " Read the three declarations with seon.task/task-declaration and decide.")}
                 "root")))

(defn adopt-tasks-call
  "Transaction data reconciling `destination`'s tasks to `source`, the published
  database. With `base`, the published commit `destination` adopted before (the
  merge base), it is a three-way merge of only the tasks `source` changed since
  `base` (`changed-handles`): one main still holds as `base` did takes
  `source`'s value, one main revised too (a merged refinement whose publication
  is due) keeps main's value and writes root's problem task
  (`adoption-conflict`), as `seon.merge/squash-merge`'s `:root` rule does.
  Without `base` (the first adoption) it reconciles the whole set: a held task
  the source's history retracted was published and is deleted; one the source
  never held is the destination's own and stays. The basis is `destination`,
  the value this call read. Empty when they agree."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.db/database-value]
                   [:or :seon.db/tx-data :seon.db/invalid-read-error]]
                  [:=> [:cat :seon.db/database-value :seon.db/database-value [:maybe :seon.db/database-value]]
                   [:or :seon.db/tx-data :seon.db/invalid-read-error]]]}
  ([source destination] (adopt-tasks-call source destination nil))
  ([source destination base]
   (let [published (when-not base (task-handles source false))
         retracted (when-not base (task-handles source true))
         held (when-not base (task-handles destination false))
         changed (when base (changed-handles base source))
         failure (some read-error [published retracted held changed])
         handles (when-not failure (or changed (into published (filter retracted) held)))
         read (fn [database] (when (and database handles) (stored-tasks database :seon.task/id handles)))
         [theirs ours was] (map read [source destination base])]
     (or failure (some read-error [theirs ours was])
         (let [projection (db/carried-projection destination)
               of (fn [stored h] (declaration projection (get stored h)))
               ;; the three-way: main unchanged since the base takes theirs; both revised keeps ours
               conflicts (when base (filterv (fn [h] (let [b (of was h) t (of theirs h) o (of ours h)]
                                                       (and (not= b t) (not= o b) (not= o t))))
                                             (sort handles)))
               taken (if base (into #{} (filter (fn [h] (and (not= (of was h) (of theirs h)) (= (of ours h) (of was h))))) handles)
                         handles)
               problems (mapv #(adoption-conflict % (of was %) (of theirs %) (of ours %)) conflicts)
               entities (into (vec (keep theirs taken)) problems)
               handles (into taken (map :seon.task/id) problems)
               basis (into {} (keep (fn [h] (when-let [d (of ours h)] [h d]))) handles)]
           (if (empty? (adopt-tasks-tx destination entities handles basis)) [] [[:db.fn/call #'adopt-tasks-tx entities handles basis]]))))))

(defn index-tasks!
  "Reconcile task files through the writer against the declarations the caller read
  (`:seon.task/basis`); an unchanged set writes nothing. Refusals return. Without a
  basis it reads one now, which guards only writes after this call, not a stale file."
  {:malli/schema [:=> [:cat :seon.task/request]
                  [:or :seon.task/report :seon.task/refused-error :seon.task/conflict-error
                   :seon.db/invalid-read-error :seon.db/transaction-refused-error]]}
  [{connection :seon.db/connection files :seon.task/files basis :seon.task/basis}]
  (let [database (db/db connection)
        basis (or basis (task-basis database files))]
    (or (read-error basis)
        (try (if (empty? (tasks-tx database files basis))
               {:seon.task/changed? false}
               (let [report (db/transact! connection [[:db.fn/call #'tasks-tx files basis]])]
                 (or (read-error report) {:seon.task/changed? true :seon.db/transaction-report report})))
             (catch clojure.lang.ExceptionInfo failure
               (if (:seon.task/refused (ex-data failure)) (ex-data failure) (throw failure)))))))

(defn obligations
  "The step kind, proofs and product tests of task `sym` over the assigned target basis: `main`'s head, the
  `candidate` and their merge `base`. Each set is main's ∪ (the candidate's − the base's), so root's revisions on main
  apply, the candidate's own additions join, and its removal of a gate it inherited does not; the step is the
  candidate's, absent when it declares none. On one database value (an unassigned task) it is that value's own: its
  proofs are the tests tagged with the task, and an explicit `:implement` step adds the tests tagged with its subject
  (`goal-owner`) and its product; its product is its effective `:seon.task/product` (`effective`) and the tests tagged
  with a task that needs it. Its proofs include its own `:seon.task/proofs` field."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.task/sym]
                   [:or :seon.task/obligations :seon.db/invalid-read-error :seon.db/pull-budget-error]]
                  [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.db/database-value :seon.task/sym]
                   [:or :seon.task/obligations :seon.db/invalid-read-error :seon.db/pull-budget-error]]]}
  ([database sym] (obligations database database database sym))
  ([main candidate base sym]
   (let [tagged (fn [database task] (db/q '[:find [?s ...] :in $ ?t :where [?e :seon.task/tasks ?t] [?e :seon.test/sym ?s]] database task))
         at (fn [database]
              (let [task (db/pull database (cond-> [:seon.task/id :seon.task/step]
                                             (contains? (:schema database) :seon.task/proofs) (conj :seon.task/proofs))
                                  [:seon.task/sym sym])
                    handle (:seon.task/id task)
                    held (when handle (effective database handle))
                    owner (when (and handle (= :implement (:seon.task/step task))) (goal-owner database handle))
                    subject (when (and (string? owner) (not= owner handle))
                              (:seon.task/sym (db/pull database [:seon.task/sym] [:seon.task/id owner])))
                    proofs (tagged database sym)
                    inherited (if subject (tagged database subject) [])
                    product (db/q '[:find [?s ...] :in $ ?t :where [?task :seon.task/sym ?t]
                                    [?next :seon.task/needs ?task] [?next :seon.task/sym ?n]
                                    [?e :seon.task/tasks ?n] [?e :seon.test/sym ?s]] database sym)]
                (or (some read-error [task held owner subject proofs inherited product])
                    (let [declared (set (:seon.task/product held))]
                      (cond-> {:seon.task/proofs (cond-> (into (into (set proofs) (:seon.task/proofs task)) inherited) owner (into declared))
                               :seon.task/product (into declared product)}
                        (:seon.task/step task) (assoc :seon.task/step (:seon.task/step task)))))))
         ours (at candidate)
         theirs (if (identical? main candidate) ours (at main))
         was (cond (identical? base candidate) ours (identical? base main) theirs :else (at base))
         basis (fn [k] (into (get theirs k) (remove (get was k)) (get ours k)))]
     (or (some read-error [ours theirs was])
         (assoc ours :seon.task/proofs (basis :seon.task/proofs) :seon.task/product (basis :seon.task/product))))))

(defn main-head
  "Main's head seen from the branch `database`: root's home branch (root's row names it) through this process's
  connection to it; nil on main itself or when no root row names another branch."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:maybe :seon.db/database-value]]}
  [database]
  (let [branch (db/q '[:find ?branch . :where [?root :seon.agent/id "root"] [?root :seon.agent/branch ?branch]] database)]
    (when (and (keyword? branch) (not= branch (get-in database [:config :branch])))
      (some-> (db/branch-connection database branch) db/db))))

(defn assigned-obligations
  "Task `sym`'s `obligations` as the agent on the branch `database` is held to them: over main's head (`main-head`),
  the branch and the commit its base ref retains (`seon.cluster.registry/base-commit-id`), the merge base. On main,
  where no base ref is held, the obligations at `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym]
                  [:or :seon.task/obligations :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database sym]
  (let [commit (registry/base-commit-id database (get-in database [:config :branch]))
        main (when commit (main-head database))
        base (when main (db/commit-database database commit))]
    (if base (obligations main database base sym) (obligations database sym))))

(defn task-edges
  "A task's subjects (functions, namespaces) and the tasks whose references name one of its subjects, over one
  held value; its proofs are its
  `obligations`. The task is its symbol, as holders tag it, so a branch holding no task row reads its edges."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym] [:or :seon.task/edges :seon.db/invalid-read-error]]}
  [database sym]
  (let [tagged (fn [attribute] (db/q [:find '[?s ...] :in '$ '?t :where ['?e :seon.task/tasks '?t] ['?e attribute '?s]] database sym))
        subject-reads [(tagged :seon.fn/sym) (tagged :seon.ns/name)]
        subjects (when-not (some read-error subject-reads) (set (apply concat subject-reads)))
        referrers (when (seq subjects)
                    (mapv (fn [[reader holder]]
                            (db/q [:find '[?h ...] :in '$ '[?target ...] :where ['?e reader '?target] ['?e holder '?h]]
                                  database (vec subjects)))
                          [[:seon.task/references :seon.task/sym]]))]
    (or (some read-error subject-reads) (some read-error referrers)
        {:seon.task/subjects subjects :seon.task/referrers (set (apply concat referrers))})))

(defn open-tasks
  "The task symbols a definition still lists; listing is openness (spec 7.2, 7.6)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:tuple :qualified-keyword :symbol]]
                  [:or [:set :seon.task/sym] :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database program-identity]
  (let [pulled (db/pull database [:seon.task/tasks] program-identity)]
    (or (read-error pulled) (set (:seon.task/tasks pulled)))))

(defn task-findings
  "`:seon.task/dangling` task symbols listed in code with no task entity, by holder (the
  program gate refuses them), and `:seon.task/triage` task symbols with no subject or proof."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.task/findings :seon.db/invalid-read-error]]}
  [database]
  (let [dangling (db/q database '[:find ?t ?h :where [?e :seon.task/tasks ?t] (not [_ :seon.task/sym ?t])
                                  (or [?e :seon.fn/sym ?h] [?e :seon.test/sym ?h] [?e :seon.ns/name ?h])])
        triage (db/q database '[:find [?t ...] :where [_ :seon.task/sym ?t] (not [_ :seon.task/tasks ?t])])]
    (or (read-error dangling) (read-error triage)
        {:seon.task/dangling (update-vals (group-by first dangling) #(set (map second %)))
         :seon.task/triage (set triage)})))

(defn candidate-dangling
  "The `:seon.task/dangling` task symbols, by holder, that taking `candidate`'s rows
  for `identities` brings onto `destination`: those holders' task edges on `candidate`
  against the tasks admitted on `destination`. A holder the merge leaves untouched is
  the destination's own state, never the candidate's defect. Task rows never ride the
  merge (7.13)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set]
                  [:or :seon.task/dangling :seon.db/invalid-read-error]]}
  [candidate destination identities]
  (let [mine (if (contains? (:schema candidate) :seon.task/tasks)
               (db/q '[:find ?t ?a ?h :in $ [?a ...] :where [?e :seon.task/tasks ?t] [?e ?a ?h]]
                     candidate [:seon.fn/sym :seon.test/sym :seon.ns/name])
               #{})
        admitted (if (contains? (:schema destination) :seon.task/sym)
                   (db/q '[:find [?s ...] :where [_ :seon.task/sym ?s]] destination)
                   #{})
        touched? (fn [[_ attribute holder]] (contains? identities [attribute holder]))]
    (or (some read-error [mine admitted])
        (update-vals (group-by first (remove (comp (set admitted) first) (filter touched? mine)))
                     #(set (map (fn [[_ _ holder]] holder) %))))))

(defn- written
  "Write `tx` on `connection` under its own custody: the world's branch, main for root."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/tx-data]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [connection tx]
  (db/with-branch {:seon.db/connection connection} #(db/transact! connection tx)))

(defn- task-entity
  "`declaration` as the runtime task entity `agent-id` writes, under its handle and symbol."
  {:malli/schema [:=> [:cat :seon.task/declaration :seon.agent/id] :seon.task/task]}
  [{:seon.task/keys [ns name] :as declaration} agent-id]
  (assoc declaration :seon.task/id (task-handle ns name) :seon.task/sym (task-sym ns name)
         :seon.task/created-by [:seon.agent/id agent-id]))

(defn advance-assignments-tx
  "The transaction data moving every assignment of the task `entity` to its declaration's revision.
  Invoke as `[:db.fn/call seon.task/advance-assignments-tx entity]`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :seon.db/tx-data]}
  [database entity]
  (let [revision (->> entity (declaration (db/carried-projection database)) vector (id/digest 64))
        assigned (db/q '[:find ?a ?revision :in $ ?handle
                         :where [?a :seon.task/assigned ?handle] [?a :seon.task/revision ?revision]]
                       database (:seon.task/id entity))]
    (when (read-error assigned) (throw (ex-info (:seon.error/message assigned) assigned)))
    (into [] (keep (fn [[a current]] (when (not= current revision) [:db/add a :seon.task/revision revision]))) assigned)))

(defn- flowing-members
  "The content members a chain's next task takes from the finished one at each advance under `projection`: the
  declaration's members less identity, the owned ones (`:seon.task/owned`) and the tree's task refs."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :qualified-keyword]]}
  [projection]
  (let [kept (into #{:seon.task/ns :seon.task/name} (concat (owned-members projection) tree-attributes))]
    (into [] (comp (map first) (remove kept)) (declaration-members projection))))

(defn advance-tx
  "The transaction data advancing a chain past its finished attempt `key` at `database`, the chain branch: the
  attempt's outcome `:done`, and the task its task's `:seon.task/next` names, through the task writer, taking the
  finished task's flowing members (the finished value wins; references derived again) and `:execution :running`.
  An attempt already holding an outcome, or none at all, answers none, so a repeated advance writes nothing.
  Invoke as `[:db.fn/call seon.task/advance-tx key]`, decided over the value the writer holds."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment] :seon.db/tx-data]}
  [database key]
  (let [row (db/pull database [:db/id :seon.task/outcome
                               {:seon.task/attempt-of [:seon.task/id {:seon.task/next [:seon.task/id]}]}]
                     [:seon.task/assignment key])
        finished (get-in row [:seon.task/attempt-of :seon.task/id])
        after (get-in row [:seon.task/attempt-of :seon.task/next :seon.task/id])
        stored (when after (stored-tasks database :seon.task/id #{finished after}))]
    (when-let [failure (some read-error [row stored])] (throw (ex-info (:seon.error/message failure) failure)))
    (if (or (nil? (:db/id row)) (:seon.task/outcome row))
      []
      (let [projection (db/carried-projection database)
            current (when after (declaration projection (stored after)))
            flowed (merge current (select-keys (declaration projection (stored finished)) (flowing-members projection)))]
        (cond-> [[:db/add (:db/id row) :seon.task/outcome :done]]
          after (into (adopt-tasks-tx database [flowed] #{after} {after current}))
          after (conj [:db/add [:seon.task/id after] :seon.task/execution :running]))))))

(defn- task-call
  "The task writer's call for `row` against `basis` on `connection`'s branch,
  or [] when that branch already holds its declaration; a revision
  also advances the task's assignments. A revised declaration throws the writer's
  conflict with base, current and proposed."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/task :seon.task/basis] :seon.db/tx-data]}
  [connection entity basis]
  (let [handles #{(:seon.task/id entity)}]
    (if (empty? (adopt-tasks-tx (db/db connection) [entity] handles basis))
      []
      [[:db.fn/call #'adopt-tasks-tx [entity] handles basis]
       [:db.fn/call #'advance-assignments-tx entity]])))

(defn- refusal-data
  "Call `f`; the task writer's thrown refusal answers as its data."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo failure
             (if (:seon.task/refused (ex-data failure)) (ex-data failure) (throw failure)))))

(defn create!
  "Create a runtime task in my world (`connection`: my branch, main for root; an agent's tasks
  reach main with its successful end, `seon.merge/merge!`) from its declaration (7.8). Its handle is
  the aspect `[ns name]` and its symbol `ns/name`: the same declaration again
  changes nothing; a different one refuses with base, current and proposed unless
  `:seon.task/basis` names the revision I read (7.16). A name that is no symbol
  name is refused by `:seon.task/name`; `:seon.task/children` and `:seon.task/needs` name
  tasks as lookup refs `[:seon.task/sym ns/name]`, and needs that would leave a task
  waiting on itself refuse `:needs-cycle`. The writer derives `:seon.task/references` from the plan and
  instructions; a step declares its own `:seon.task/instructions` and takes the goal it omits from its subject.

  ## Example

  ```clojure
  (seon.task/create! {:seon.task/ns 'my.turn :seon.task/name \"titles-trim\" :seon.task/plan \"Trim reply titles.\"})
  ```"
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]
                             [:seon.task/ns :seon.task/ns] [:seon.task/name :seon.task/name]
                             [:seon.task/basis {:optional true} :seon.task/basis]]]
                  [:or [:map [:seon.task/id :seon.task/id] [:seon.task/changed? :seon.task/changed?]]
                   :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (let [entity (task-entity (select-keys request (map first (declaration-members (db/carried-projection (db/db connection)))))
                            agent-id)
        tx (refusal-data #(task-call connection entity (:seon.task/basis request {})))
        report (if (and (vector? tx) (seq tx)) (written connection tx) tx)]
    (if (:seon.error/at report)
      report
      {:seon.task/id (:seon.task/id entity) :seon.task/changed? (boolean (seq tx))})))

(defn- chain-entities
  "The `tasks` of the chain whose first map names it, as the writer's maps: only the first map supplies `ns` and
  `name`, every later task is named `<first name>-<index>` (1-based) in its namespace, each takes the negative tempid
  of its position and names the next one's as its `:seon.task/next`; the first runs and the rest are held; each is
  created by root (`:seon.task/created-by`), a runtime task no task file declares. A map's own
  `:db/id`, `:seon.task/id`, `:seon.task/sym` and `:seon.task/next` are ignored."
  {:malli/schema [:=> [:cat [:vector {:min 1} :seon.task/task-map]] [:vector :map]]}
  [tasks]
  (let [{ns :seon.task/ns first-name :seon.task/name} (first tasks)
        n (count tasks)]
    (into [] (map-indexed
              (fn [i task]
                (cond-> (assoc (dissoc task :db/id :seon.task/id :seon.task/sym :seon.task/next)
                               :db/id (- (inc i)) :seon.task/ns ns
                               :seon.task/name (if (zero? i) first-name (str first-name "-" (inc i)))
                               :seon.task/execution (if (zero? i) :running :held)
                               ;; a chain task is runtime data, root's, never a task file's entry
                               :seon.task/created-by [:seon.agent/id "root"])
                  (< (inc i) n) (assoc :seon.task/next (- (+ i 2))))))
          tasks)))

(defn chain-handles
  "The handles of the chain whose first task is `handle` at `database`, in order along `:seon.task/next`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] :seon.task/handles]}
  [database handle]
  (loop [handle handle handles []]
    (let [after (get-in (db/pull database [{:seon.task/next [:seon.task/id]}] [:seon.task/id handle]) [:seon.task/next :seon.task/id])]
      (if after (recur after (conj handles handle)) (conj handles handle)))))

(defn- on-branch
  "Call `f` with a connection to `branch` of `store` for this one action: the one this process holds, else one
  opened here and released when `f` returns."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch [:=> [:cat :seon.db/connection] :seon.schema/value]]
                  :seon.schema/value]}
  [store branch f]
  (if-let [held (registry/active-branch-connection {:seon.store/store store :seon.store/branch branch})]
    (f held)
    (let [opened (store/open-branch! store branch)]
      (try (f opened) (finally (store/release-branch! opened))))))

(defn from-filed
  "A filed task's chain: `filed` (a task-file entry) as the Design task, with its identity and plan, its proofs left
  out, then the Prove template, then the Implement template carrying the entry's proofs. A plan with no `## Problem`
  section is wrapped as one, so Design has the request to keep.

  ## Example

  ```clojure
  (seon.task/chain! context (seon.task/from-filed database (seon.task/task-declaration database handle)))
  ```"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task-map]
                  [:or [:vector {:min 3 :max 3} :seon.task/task-map] :seon.task/refused-error :seon.db/invalid-read-error
                   :seon.db/pull-budget-error]]}
  [database filed]
  (let [[design prove implement :as templates] (map #(template database %) ['seon.step.design/template 'seon.step.prove/template 'seon.step.implement/template])
        failure (first (filter :seon.error/at templates))
        plan (:seon.task/plan filed)
        problem? (some #(= "problem" (:seon.ref/anchor %)) (:seon.ref/headings (ref/document (or plan ""))))]
    (or failure
        [(cond-> (merge design (dissoc filed :seon.task/proofs))
           (and plan (not problem?)) (assoc :seon.task/plan (str "## Problem\n\n" plan)))
         prove
         (cond-> implement (:seon.task/proofs filed) (assoc :seon.task/proofs (:seon.task/proofs filed)))])))

(defn chain!
  "Write `tasks` as one linked chain on its own branch, forked from main (`:seon.chain/from` names another branch
  whose head it starts from; it still merges toward main): the first map names the chain and the branch
  `chain-<handle>`, later tasks are named by position (`chain-entities`), all written in one transaction through
  the task writer, then the chain fact on main. A chain whose fact main holds is answered as it stands and nothing
  is written. Answers the branch, the first task and every handle in order.

  ## Example

  ```clojure
  (seon.task/chain! {:seon.db/connection connection :seon.store/store store}
                    [(merge (seon.task/template database 'seon.step.design/template)
                            {:seon.task/ns 'seon.ref :seon.task/name \"fix-pathless-section\" :seon.task/plan plan})])
  ```"
  {:malli/schema [:function
                  [:=> [:cat :seon.task/chain-context [:vector {:min 1} :seon.task/task-map]]
                   [:or :seon.task/chain :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]
                  [:=> [:cat :seon.task/chain-context [:vector {:min 1} :seon.task/task-map] :seon.task/chain-options]
                   [:or :seon.task/chain :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]]}
  ([context tasks] (chain! context tasks {}))
  ([{connection :seon.db/connection store :seon.store/store} tasks {from :seon.chain/from}]
   (let [main (db/db connection)
         main-branch (get-in main [:config :branch])
         {ns :seon.task/ns first-name :seon.task/name} (first tasks)
         first-handle (task-handle ns first-name)
         held (:seon.chain/branch (db/pull main [:seon.chain/branch] [:seon.chain/first first-handle]))
         branch (or held (keyword (str "chain-" first-handle)))
         answer (fn [connection] {:seon.chain/branch branch :seon.chain/first first-handle
                                  :seon.task/handles (chain-handles (db/db connection) first-handle)})]
     (if held
       (on-branch store branch answer)
       (let [forked (when-not (contains? (registry/branches store) branch)
                      (registry/fork! {:seon.store/store store :seon.store/branch branch :seon.db/db main
                                       :seon.branch/target main-branch :seon.cluster.registry/from (or from main-branch)}))
             entities (chain-entities tasks)
             handles (into #{} (map #(task-handle ns (:seon.task/name %))) entities)
             written-tasks (when-not (:seon.error/at forked)
                             (on-branch store branch
                                        ;; the first task's basis is what the branch holds under its identity: a filed
                                        ;; task's inherited entry, which the chain's first task revises; the writer
                                        ;; revises an entry's declaration only, so that task's chain members (running,
                                        ;; next, created by root) are written beside it, as a new first task carries them
                                        #(let [stored (task-declaration (db/db %) first-handle)
                                               chained (when stored
                                                         [(assoc (select-keys (first entities) [:seon.task/execution :seon.task/next :seon.task/created-by])
                                                                 :db/id [:seon.task/id first-handle])])]
                                           (refusal-data (fn [] (written % (into [[:db.fn/call #'adopt-tasks-tx entities handles
                                                                                   (if stored {first-handle stored} {})]]
                                                                                 chained)))))))
             fact (when (:db-after written-tasks)
                    (written connection [{:seon.chain/first first-handle :seon.chain/branch branch}]))]
         (cond (:seon.error/at forked) forked
               (not (:db-after written-tasks)) written-tasks
               (not (:db-after fact)) fact
               :else (on-branch store branch answer)))))))

(defn assignment-read
  "What assigning task `handle` to `agent` reads at `database`: the task's revision, the agent's
  id and the task's proofs; refused when the task or agent is unknown or the step has no obligations of its kind."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assigned :seon.task/agent]
                  [:or [:map [:seon.task/revision :seon.task/revision] [:seon.agent/id :seon.agent/id]
                        [:seon.task/proofs :seon.task/proofs]]
                   :seon.task/refused-error :seon.error/value]]}
  [database handle agent]
  (let [revision (task-revision database handle)
        agent-id (:seon.agent/id (db/pull database [:seon.agent/id] agent))
        edges (when (and (string? revision) (string? agent-id))
                (obligations database (:seon.task/sym (db/pull database [:seon.task/sym] [:seon.task/id handle]))))]
    (cond (:seon.error/at revision) revision
          (nil? revision) (refused 'seon.task/assign! :unknown-task 'seon.task handle)
          (not (string? agent-id)) (refused 'seon.task/assign! :unassigned 'seon.task agent)
          (:seon.error/at edges) edges
          (empty? (get edges (if (= :prove (:seon.task/step edges)) :seon.task/product :seon.task/proofs))) (refused 'seon.task/assign! :no-proofs 'seon.task handle)
          :else {:seon.task/revision revision :seon.agent/id agent-id :seon.task/proofs (:seon.task/proofs edges)})))

(defn- next-attempt
  "The number and identity `seon.id/id [handle number]` of task `handle`'s next attempt at `database`: its
  latest, `:seon.task/attempts-made`, while that attempt is open, else one more, so a removed attempt never
  re-mints its identity or branch."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or [:tuple [:int {:min 1}] :seon.task/assignment] :seon.db/invalid-read-error]]}
  [database handle]
  (let [made (db/q '[:find ?m . :in $ ?handle :where [?t :seon.task/id ?handle] [?t :seon.task/attempts-made ?m]]
                   database handle)
        latest (when (int? made) (db/pull database [:db/id :seon.task.attempt/end] [:seon.task/assignment (id/id [handle made])]))]
    (or (read-error made) (read-error latest)
        (let [number (if (and (:db/id latest) (not (:seon.task.attempt/end latest))) made (inc (or made 0)))]
          [number (id/id [handle number])]))))

(defn relaunched
  "The end commit and merge base of task `handle`'s latest attempt in `database` when db<->flow relaunched it
  (`:seon.task.attempt/relaunch`, `seon.cluster.agent/close!`): its next attempt forks that commit, so the work
  carries over, and keeps that merge base, so its merge takes both attempts' changes; nil otherwise."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil [:map [:seon.task.attempt/commit :seon.task.attempt/commit]
                             [:seon.task/merge-base :seon.task/merge-base]]
                   :seon.db/invalid-read-error]]}
  [database handle]
  (let [latest (db/q '[:find (max ?n) . :in $ ?h
                       :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/number ?n]]
                     database handle)
        row (when (int? latest)
              (db/q '[:find (pull ?a [:seon.task.attempt/relaunch :seon.task.attempt/commit :seon.task/merge-base]) .
                      :in $ ?h ?n :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/number ?n]]
                    database handle latest))]
    (or (read-error latest) (read-error row)
        (when (:seon.task.attempt/relaunch row) (select-keys row [:seon.task.attempt/commit :seon.task/merge-base])))))

(defn attempt-tx
  "The transaction data writing the next attempt of task `handle` by `agent-id` on `branch` at main
  `database`: its row, numbered one more than the task's `:seon.task/attempts-made` (written beside it) and identified by
  `seon.id/id [handle number]`, with `:seon.task/attempt-of` beside the handle value and the merge base: the
  commit the branch forked at when the branch exists, else a relaunched predecessor's (`relaunched`); [] when that
  attempt is already written. The agent it names may be created in the same transaction. An unknown
  task refuses `:unknown-task`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle :seon.agent/id :seon.store/branch]
                  [:or :seon.store/transaction-data :seon.task/refused-error :seon.error/value]]}
  [database handle agent-id branch]
  (let [[number attempt :as next] (next-attempt database handle)
        revision (task-revision database handle)
        predecessor (relaunched database handle)
        base (or (registry/base-commit-id database branch) (:seon.task/merge-base predecessor))]
    (cond (read-error next) next
          (read-error revision) revision
          (read-error predecessor) predecessor
          (nil? revision) (refused 'seon.task/attempt-tx :unknown-task 'seon.task handle)
          (:db/id (db/pull database [:db/id] [:seon.task/assignment attempt])) []
          :else [(cond-> {:seon.task/assignment attempt :seon.task/assigned handle :seon.task/attempt-of [:seon.task/id handle]
                          :seon.task.attempt/number number :seon.task/revision revision
                          :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch}
                   base (assoc :seon.task/merge-base base))
                 [:db/add [:seon.task/id handle] :seon.task/attempts-made number]])))

(defn attempt-rows-tx
  "The transaction data writing onto `branch-db` attempt `key`'s row as main `database` holds it, under
  main's entity id, naming its task and agent by identity, so the branch's agent reads its assignment in
  its own world and the end names main's entities; [] when `branch-db` holds the row. The branch's world
  holds the task and the agent (it forked a commit after both existed): a
  task it lacks refuses `:unknown-task`, never imported."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.task/assignment]
                  [:or :seon.store/transaction-data :seon.task/refused-error :seon.error/value]]}
  [database branch-db key]
  (let [row (db/pull database [:db/id :seon.task/assignment :seon.task/assigned :seon.task/revision :seon.task/branch
                               :seon.task.attempt/number :seon.task/merge-base
                               {:seon.task/agent [:seon.agent/id]}]
                     [:seon.task/assignment key])
        handle (:seon.task/assigned row)
        present? (fn [lookup] (:db/id (db/pull branch-db [:db/id] lookup)))]
    (cond (read-error row) row
          (not (string? handle)) (refused 'seon.task/attempt-rows-tx :unassigned 'seon.task key)
          (present? [:seon.task/assignment key]) []
          (not (present? [:seon.task/id handle])) (refused 'seon.task/attempt-rows-tx :unknown-task 'seon.task handle)
          :else [(assoc row :seon.task/agent [:seon.agent/id (get-in row [:seon.task/agent :seon.agent/id])]
                        :seon.task/attempt-of [:seon.task/id handle])])))

(defn- task-placed!
  "Write the task `handle` on the branch `target` as main `database` declares it, by the task writer, when the
  branch lacks it (root created it after the branch forked): its whole declaration, children and needs by
  identity, so a task naming one the branch lacks refuses at the writer. nil, or the refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/connection :seon.task/handle] [:maybe :seon.error/value]]}
  [database target handle]
  (when-not (:db/id (db/pull (db/db target) [:db/id] [:seon.task/id handle]))
    (let [stored (stored-tasks database :seon.task/id #{handle})]
      (or (read-error stored)
          (let [report (refusal-data #(written target [[:db.fn/call #'adopt-tasks-tx [(dissoc (stored handle) :db/id)] #{handle} {}]]))]
            (when (:seon.error/at report) report))))))

(defn assign!
  "Root assigns the task `:seon.task/assigned` to the agent `:seon.task/agent`
  working on `:seon.task/branch`, binding the revision it reviews now: the
  attempt row, written on `connection` (main, root's world) and, when the agent
  works on a branch of its own, on that branch too under main's ids, with the
  task when the branch lacks it (`task-placed!`, `attempt-rows-tx`), so the agent reads its assignment in its own world; a
  branch this process holds no connection to refuses `:no-world`. A task with no
  proof refuses `:no-proofs`.
  Nothing runs here: the agent's first pass proves first (`seon.turn/prove-turn`),
  in its own world, and proofs already green end the attempt with no model call.
  Answers the assignment; it offers no operation, since nothing is yet worked.

  ## Example

  ```clojure
  (seon.task/assign! {:seon.task/assigned \"a1b2c3d4e5f6\" :seon.task/agent [:seon.agent/id \"mcp/fix\"]
                      :seon.task/branch :fix})
  ```"
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.task/assigned :seon.task/assigned]
                             [:seon.task/agent :seon.task/agent] [:seon.task/branch :seon.task/branch]
                             [:seon.agent/context-source {:optional true} :seon.agent/context-source]]]
                  [:or :seon.task/assignment-view :seon.task/refused-error :seon.error/value]]}
  [{connection :seon.db/connection handle :seon.task/assigned agent :seon.task/agent branch :seon.task/branch}]
  (let [read (assignment-read (db/db connection) handle agent)
        agent-id (:seon.agent/id read)]
    (if (:seon.error/at read)
      read
      (let [tx (attempt-tx (db/db connection) handle agent-id branch)
            report (if (and (vector? tx) (seq tx)) (written connection tx) tx)
            [_ attempt] (next-attempt (db/db connection) handle)
            own (when (and (not (:seon.error/at report)) (not= branch (get-in (db/db connection) [:config :branch])))
                  (if-let [target (db/branch-connection (db/db connection) branch)]
                    (let [placed (task-placed! (db/db connection) target handle)
                          rows (if (:seon.error/at placed) placed (attempt-rows-tx (db/db connection) (db/db target) attempt))]
                      (if (and (vector? rows) (seq rows))
                        (db/with-branch {:seon.db/connection target} #(db/transact! target rows))
                        rows))
                    (refused 'seon.task/assign! :no-world 'seon.task branch)))
            row (when-not (some :seon.error/at [report own])
                  (db/pull (db/db connection) [:seon.task/branch {:seon.task/agent [:seon.agent/id]}]
                           [:seon.task/assignment attempt]))]
        (or (some #(when (:seon.error/at %) %) [report own row])
            {:seon.task/assignment attempt :seon.task/assigned handle
             :seon.agent/id (get-in row [:seon.task/agent :seon.agent/id]) :seon.task/branch (:seon.task/branch row)
             :seon.render/operations []})))))

(defn assignment
  "What `agent-id` on `branch` is assigned in the cluster value `database`: its
  task's proofs, owning namespace and key, refused when the task changed since
  root reviewed it. None, or several, refuses `:unassigned` by name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/branch]
                  [:or :seon.task/assigned-work :seon.task/refused-error :seon.error/value]]}
  [database agent-id branch]
  (let [assigned (db/q '[:find ?key ?handle ?revision :in $ ?id ?branch
                         :where [?agent :seon.agent/id ?id] [?a :seon.task/agent ?agent] [?a :seon.task/branch ?branch]
                         [?a :seon.task/assignment ?key] [?a :seon.task/assigned ?handle] [?a :seon.task/revision ?revision]]
                       database agent-id branch)
        [[key handle revision]] (when-not (map? assigned) (seq assigned))
        current (when handle (task-revision database handle))
        task (when handle (db/pull database [:seon.task/ns :seon.task/sym] [:seon.task/id handle]))
        edges (when (:seon.task/sym task) (obligations database (:seon.task/sym task)))]
    (cond (some :seon.error/at [assigned current task edges]) (some #(when (:seon.error/at %) %) [assigned current task edges])
          (and (= 1 (count assigned)) (not= revision current))
          (refused 'seon.task/assignment :revised-since-assignment 'seon.task {:seon.task/revision revision :seon.task/current current})
          (= 1 (count assigned))
          (merge {:seon.task/key key :seon.task/assigned handle
                  :seon.task/proofs (:seon.task/proofs edges #{})}
                 (select-keys task [:seon.task/ns :seon.task/sym]))
          :else (refused 'seon.task/assignment :unassigned 'seon.task {:seon.agent/id agent-id :seon.store/branch branch
                                                                      :seon.task/assignment (mapv first assigned)}))))

(defn agent-task
  "The handle of the task `agent-id` holds in `database`, its world (an attempt's branch holds its row): its latest assignment's
  task; nil when it holds none. One indexed query.

  ## Costs

  Measured 1.2 ms on default (2026-09-28)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.task/handle :seon.db/invalid-read-error]]}
  [database agent-id]
  (let [held (db/q '[:find ?handle ?tx :in $ ?id
                     :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned ?handle ?tx]]
                   database agent-id)]
    (or (read-error held) (first (apply max-key second [nil 0] (seq held))))))

(defn agent-attempt
  "The open attempt `agent-id` holds in `database`, its world: the entity of its latest assignment row
  with no end; nil when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :int :seon.db/invalid-read-error]]}
  [database agent-id]
  (let [open (db/q '[:find ?a ?tx :in $ ?id
                     :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned _ ?tx]
                     (not [?a :seon.task.attempt/end _])]
                   database agent-id)]
    (or (read-error open) (first (apply max-key second [nil 0] (seq open))))))

(defonce ^:private retire-branch!
  ;; my.agent closes a branch's agent before its branch is deleted; a my namespace is never required here
  (delay (requiring-resolve 'my.agent/retire-branch!)))

(defonce ^:private submit-work!
  (delay (requiring-resolve 'seon.flow/submit!)))

(defonce ^:private record-fault!
  (delay (requiring-resolve 'seon.fault/fault!)))

(defonce ^:private process-id
  (delay (comp (requiring-resolve 'seon.cluster.process/id) (requiring-resolve 'seon.cluster.process/current-identity))))

(defn delete-branch!
  "Delete the agent branch `branch` through `context` once its agent is closed (`my.agent/retire-branch!`),
  as io work on the environment's work launcher: retiring joins the agent's graphs and takes the store's
  roster permit, which a running collection holds for seconds, so the caller (an ending agent's own pass
  among them) never waits on it. Inline when no launcher accepts work, answering its refusal; a failure of the
  detached work is recorded as a fault. main itself (an agent working on main has no branch of its own) is never
  deleted. nil, or the inline refusal."
  {:malli/schema [:=> [:cat :seon.task/close-context :seon.store/branch] [:or :nil :seon.error/value]]}
  [{environment :seon.env/environment connection :seon.db/connection :as context} branch]
  (let [main? (= branch (get-in (db/db connection) [:config :branch]))
        retire! #(@retire-branch! {:seon.store/branch branch} context)
        launcher (:seon.flow/work-launcher environment)
        submitted (when (and launcher (not main?))
                    (@submit-work! launcher
                     {:seon.env/environment environment :seon.flow/workload :io
                      :seon.flow/submission-id (str "delete-branch-" (name branch))
                      :seon.flow/work-fn (fn [_] (let [retired (retire!)]
                                                   (when (:seon.error/at retired)
                                                     (throw (ex-info (:seon.error/message retired) retired)))))
                      :seon.flow/complete!
                      (fn [{failure :seon.flow/throwable}]
                        (when failure
                          (@record-fault! environment failure
                           {:seon.error/layer :seon.task/close :seon.error/operation 'seon.task/delete-branch!
                            :seon.db.process/id (@process-id)}))
                        nil)}))]
    (when-not (or main? submitted)
      (let [retired (retire!)] (when (:seon.error/at retired) retired)))))

(defn close!
  "Close assignment `key` (the merge request) without merging it: write its `outcome` and `reason` at main and
  delete its branch, closing the agent there (`delete-branch!`; a refused deletion is answered beside the closed
  assignment), unless its agent's cluster keeps ended branches for audit (`:seon.config.agent/keep-ended-branches?`).
  Root's decline (`my.root/close!`) ends here; root's merge closes in its own transaction (`seon.merge/merge-tx`).
  Answers the closed assignment.

  ## Example

  ```clojure
  (seon.task/close! context \"0123456789ab\" :closed \"The change belongs in seon.db.\")
  ```"
  {:malli/schema [:=> [:cat :seon.task/close-context :seon.task/assignment :seon.task/outcome [:maybe :seon.task/reason]]
                  [:or :seon.task/closed :seon.error/value]]}
  [{connection :seon.db/connection :as context} key outcome reason]
  (let [report (written connection (cond-> [[:db/add [:seon.task/assignment key] :seon.task/outcome outcome]]
                                     reason (conj [:db/add [:seon.task/assignment key] :seon.task/reason reason])))
        row (when (:db-after report)
              (db/q '[:find [?branch ?id] :in $ ?k
                      :where [?a :seon.task/assignment ?k] [?a :seon.task/branch ?branch] [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                    (:db-after report) key))
        [branch agent-id] (when (sequential? row) row)
        ;; a branch whose agent's cluster keeps ended branches stays for audit
        kept? (and agent-id (true? (config/agent-cluster-dial (:db-after report) agent-id :seon.config.agent/keep-ended-branches?)))]
    (cond (:seon.error/at report) report
          (:seon.error/at row) row
          :else (let [refused (when (and branch (not kept?)) (delete-branch! context branch))]
                  (cond-> {:seon.task/assignment key :seon.task/outcome outcome}
                    reason (assoc :seon.task/reason reason)
                    refused (assoc :seon.task/delete-refusals [refused]))))))

;;; READY (runtime-follows-db §2): the leaves that should run now, derived from the tree,
;;; the intents and the attempts' ends, with no scheduler state. Recursion is positive (`under`,
;;; `needs*`) and negation applies only to finished relations, which Datahike evaluates.

(def ^:private end-kinds
  "Per attempt end, what follows it (`end-kind`): the one table every end decision reads."
  {:done :merge :sent-back :merge :capped :relaunch :stalled :relaunch :given-back :close :error :close
   :cancelled :cancel})

(defn end-kind
  "What follows attempt end `end` (`:seon.task.attempt/kind`)."
  {:malli/schema [:=> [:cat :seon.task.attempt/end] :seon.task.attempt/kind]}
  [end]
  (end-kinds end))

(def ^:private blocking-ends
  "The ends whose attempt keeps its task from being ready again until db<->flow relaunches it: those that merge nothing."
  (into #{} (remove (comp #{:merge} end-kind)) (keys end-kinds)))

(def ready-rules
  "The stratified rules `ready` reads: `leaf-done` (an assignment of it merged on main, outcome
  :merged, with no later :sent-back targeting it), `under`, `self-or-under`, `needs*` (own and
  inherited needs), `blocked` (a need with an open leaf at or under it) and `execution` (a task's own
  `:seon.task/execution`, else its nearest ancestor's down `:seon.task/children`)."
  '[[(execution ?t ?e) [?t :seon.task/execution ?e]]
    [(execution ?t ?e) [?p :seon.task/children ?t] [(missing? $ ?t :seon.task/execution)] (execution ?p ?e)]
    [(leaf-done ?t) [?a :seon.task/attempt-of ?t] [?a :seon.task/outcome :merged ?tx]
     (not-join [?t ?tx] [?b :seon.task.attempt/end :sent-back ?tx2] [?b :seon.task.attempt/target ?t] [(> ?tx2 ?tx)])]
    [(under ?n ?l) [?n :seon.task/children ?l]]
    [(under ?n ?l) [?n :seon.task/children ?c] (under ?c ?l)]
    [(self-or-under ?n ?l) [(identity ?n) ?l]]
    [(self-or-under ?n ?l) (under ?n ?l)]
    [(needs* ?t ?n) [?t :seon.task/needs ?n]]
    [(needs* ?t ?n) [?p :seon.task/children ?t] (needs* ?p ?n)]
    [(blocked ?t) (needs* ?t ?n) (self-or-under ?n ?l) [?l :seon.task/id] (not [?l :seon.task/children _]) (not (leaf-done ?l))]])

(defn execution
  "The execution task `handle` runs under in `database`: its own `:seon.task/execution`, else its nearest
  ancestor's down `:seon.task/children` (the `execution` rule of `ready-rules`); nil when none declares one."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/execution :seon.db/invalid-read-error]]}
  [database handle]
  (db/q '[:find ?e . :in $ % ?h :where [?t :seon.task/id ?h] (execution ?t ?e)] database ready-rules handle))

(defn effective
  "The effective task of `handle` in `database`: its handle and symbol, and `clojure.core/merge` of the
  `:seon.task/plan`, `:seon.task/instructions` and `:seon.task/product` each task declares from its root subject down
  `:seon.task/children` to it, its own keys last. Content merges down children only, never along needs.
  nil when `database` holds no such task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/effective :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database handle]
  (let [content [:seon.task/plan :seon.task/instructions :seon.task/product]
        set-member? (into #{} (keep (fn [[k set?]] (when set? k))) (declaration-members (db/carried-projection database)))
        declared (fn [task] (into {} (map (fn [[k v]] [k (if (set-member? k) (set v) v)])) task))
        step (db/pull database (into [:db/id :seon.task/id :seon.task/sym] content) [:seon.task/id handle])]
    (if (or (nil? step) (read-error step))
      step
      ;; the declarations from the step up to its root, then merged root first
      (loop [eid (:db/id step) lineage (list (declared (select-keys step content)))]
        (let [parent (db/q '[:find ?p . :in $ ?c :where [?p :seon.task/children ?c]] database eid)
              pulled (when (int? parent) (db/pull database content parent))]
          (cond (read-error parent) parent
                (read-error pulled) pulled
                (int? parent) (recur parent (conj lineage (declared (or pulled {}))))
                :else (apply merge (select-keys step [:seon.task/id :seon.task/sym]) lineage)))))))

(defn goal-owner
  "The handle of the task that declares the goal task `handle` reads in `database` (`effective`): itself when
  it declares a `:seon.task/plan`, else its nearest ancestor up `:seon.task/children` that does; nil when
  `database` holds no such task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/handle :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database handle]
  (loop [lookup [:seon.task/id handle]]
    (let [task (db/pull database [:db/id :seon.task/id :seon.task/plan] lookup)
          parent (when (:db/id task) (db/q '[:find ?p . :in $ ?c :where [?p :seon.task/children ?c]] database (:db/id task)))]
      (cond (read-error task) task
            (read-error parent) parent
            (or (nil? task) (:seon.task/plan task) (not (int? parent))) (:seon.task/id task)
            :else (recur parent)))))

(defn ready
  "The handles of every task that should be running now in `database` (main's): the leaves whose
  execution is :running (their own, else their nearest ancestor's, the `execution` rule: ingestion writes
  :held on every new task no task owns, so one :running fact on a subject launches its steps), not done, without an attempt db<->flow did not relaunch (`:seon.task.attempt/relaunch`) whose end merges nothing
  (`end-kind`; an :error end, a failed start among them, is terminal too: no pass assigns it again by itself) or whose
  merge was refused (`:seon.task/refusal`) and closed, minus those a need blocks.
  The execution is bound to a variable, then compared: Datahike loses the rule's binding of `?t` for the
  later negated rules when the call carries the constant. Two reads: `blocked` evaluated once, unbound, since negating a recursive rule per leaf costs a
  hundred times more. A leaf with an open attempt stays in it; the registry tells running from launch."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:set :seon.task/handle] :seon.db/invalid-read-error]]}
  [database]
  (let [eligible (db/q '[:find ?t ?h :in $ % ?blocking :where (execution ?t ?x) [(= ?x :running)]
                         [?t :seon.task/id ?h] (not [?t :seon.task/children _]) (not (leaf-done ?t))
                         (not-join [?t ?blocking] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end ?e]
                                   [(contains? ?blocking ?e)] [(missing? $ ?a :seon.task.attempt/relaunch)])
                         (not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end _] [?a :seon.task/refusal _]
                                   (not [?a :seon.task/outcome :merged]) [(missing? $ ?a :seon.task.attempt/relaunch)])]
                       database ready-rules blocking-ends)
        blocked (when-not (read-error eligible) (db/q '[:find [?t ...] :in $ % :where (blocked ?t)] database ready-rules))]
    (or (read-error eligible) (read-error blocked)
        (let [blocked (set blocked)] (into #{} (keep (fn [[t h]] (when-not (blocked t) h))) eligible)))))

(defn done?
  "Whether task `handle` is done in `database`: a leaf when an assignment of it merged on main (outcome
  :merged) with no later :sent-back targeting it, a parent when no leaf under it is open. An unknown
  task is not done."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] :boolean]}
  [database handle]
  (let [task (db/q '[:find ?n . :in $ ?h :where [?n :seon.task/id ?h]] database handle)
        below (when (int? task) (db/q '[:find [?l ...] :in $ % ?n :where (under ?n ?l)] database ready-rules task))
        open (when (and (int? task) (not (read-error below)))
               (db/q '[:find [?l ...] :in $ % [?l ...] :where (not [?l :seon.task/children _]) (not (leaf-done ?l))]
                     database ready-rules (conj (vec below) task)))]
    (when-let [failure (some read-error [task below open])] (throw (ex-info (:seon.error/message failure) failure)))
    (and (int? task) (empty? open))))

(defn resolution
  "Why task `handle` is closed, or nil while it is open: derived from what
  the task answers, never a stored flag. A merge request is closed by root's
  outcome; a parent by its children, once all are done (`done?`). An authored
  task is open while a definition names it (`seon.task/open-tasks`); it has no
  resolution here.

  ## Examples

  ```clojure
  (seon.task/resolution (seon.db/db) \"6cc772cb3f60\")
  ```"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/resolution :seon.db/error-result]]}
  [database handle]
  (let [task (db/pull database [:seon.task/outcome :seon.task/children] [:seon.task/id handle])]
    (cond (:seon.error/at task) task
          (:seon.task/outcome task) {:seon.task/closed-by :seon.task/outcome}
          (and (seq (:seon.task/children task)) (done? database handle)) {:seon.task/closed-by :seon.task/children})))
