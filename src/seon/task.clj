(ns seon.task
  "Runtime tasks (spec 7.8): create a task, assign it to an agent's branch and
  record a merge request. Tasks live on the cluster, so every write goes to
  main through the one task writer (`seon.task/adopt-tasks-tx`)."
  {:seon/tasks #{'seon.task/no-repl-entrance-creates-a-task}}
  (:require [seon.cache :as cache]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.fn :as functions]
            [seon.id :as id]
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
  "The task handle of an identifying aspect: seon.id/id over [ns name kind] (3b.6)."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name :seon.task/kind] :seon.task/handle]}
  [ns name kind]
  (id/id [ns name kind]))

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

(defn- declared
  "A stored or proposed task's `:seon.task/declaration`: its declared members, sets as sets."
  {:malli/schema [:=> [:cat :seon.schema/projection [:maybe :map]] [:maybe :seon.task/declaration]]}
  [projection task]
  (when task
    (let [members (schema/projection-cache-value
                   projection ::declaration-members
                   #(mapv first (m/children (m/deref (mr/schema (:seon.schema.projection/registry projection)
                                                              :seon.task/declaration)))))]
      (update-vals (select-keys task members) #(if (sequential? %) (set %) %)))))

(defn- edn-forms
  "Every EDN form of `text`, in order."
  {:malli/schema [:=> [:cat :string] [:vector :seon.schema/value]]}
  [text]
  (let [reader (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
        eof (Object.)]
    (loop [forms []]
      (let [form (edn/read {:eof eof} reader)]
        (if (identical? eof form) forms (recur (conj forms form)))))))

(defn- file-declarations
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
        ;; Only an empty set at an optional set member is absence (#{} clears);
        ;; every other member, empty or not, meets the closed entry schema.
        registry (:seon.schema.projection/registry projection)
        absent-when-empty (into #{} (keep (fn [[k props s]] (when (and (:optional props) (= :set (m/type (m/deref-all s)))) k)))
                                (m/children (m/deref (mr/schema registry :seon.task/entry))))
        entries (mapv #(if (map? %) (into {} (remove (fn [[k v]] (and (absent-when-empty k) (= #{} v)))) %) %) (first forms))]
    (when-let [explanation (m/explain (mr/schema registry :seon.task/file) entries)]
      (refuse-task! :invalid-entry (first forms) {:seon.task/explanation (me/humanize explanation)}))
    (mapv (fn [{:seon.task/keys [name kind] :as entry}]
            (when-not (symbol-name? name) (refuse-task! :invalid-name entry {}))
            (assoc entry :seon.task/id (task-handle ns name kind) :seon.task/sym (task-sym ns name) :seon.task/ns ns))
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

(defn task-basis
  "The declarations stored now for the files' namespaces: the basis a caller reads with its proposal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/files] [:or :seon.task/basis :seon.db/invalid-read-error]]}
  [database files]
  (let [projection (db/carried-projection database)
        content (task-content database projection)
        names (set (map file-namespace (keys files)))]
    (cache/lookup ::task-basis [(:seon.schema.projection/forms projection) content names]
                  #(+ (* schema/declaration-projection-bytes-per-form (count (:seon.schema.projection/forms projection)))
                      (* 80 (reduce + 0 (map count content))) (* 512 (count %)))
                  #(let [stored (stored-tasks database :seon.task/ns names)]
                     (or (read-error stored) (update-vals stored (partial declared projection))))
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

(defn- task-delta
  "The writer's delta from `stored` to `by-id` tasks, by handle, retractions first, so a task
  whose handle changed frees its unique symbol before its new row claims it; a handle whose
  stored declaration differs from the caller's `basis` refuses with base, current and proposed."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :seon.task/handle :map] [:map-of :seon.task/handle :map]
                       :seon.task/basis] [:vector :seon.schema/value]]}
  [projection stored by-id basis]
  (let [owned (set (keys (m/entries (m/deref (mr/schema (:seon.schema.projection/registry projection) :seon.task/declaration)))))]
    (refs-last
     (into []
          (mapcat (fn [id]
                    (let [current (declared projection (stored id)) wanted (declared projection (by-id id))]
                      (cond
                        (= current wanted) []
                        (not= (get basis id) current)
                        (functions/task-refused! 'seon.task/tasks-tx :revision-conflict (:seon.task/ns (or (by-id id) (stored id))) id
                                                 (cond-> {} (get basis id) (assoc :seon.task/base (get basis id))
                                                   current (assoc :seon.task/current current) wanted (assoc :seon.task/proposed wanted)))
                        (nil? wanted) [[:db/retractEntity (:db/id (stored id))]]
                        current (replacement-tx (stored id) (by-id id) owned)
                        :else [(assoc (by-id id) :seon.task/execution :held)]))))
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
  "`[:db/add task :seon.task/execution :held]` for every stored task with no intent: an absent
  intent is never read as running, so a task is :held until someone sets :running."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.schema/value]]}
  [database]
  (if (contains? (:schema database) :seon.task/execution)
    (let [unset (db/q '[:find [?e ...] :where [?e :seon.task/id] (not [?e :seon.task/execution _])] database)]
      (when (read-error unset) (throw (ex-info (:seon.error/message unset) unset)))
      (mapv (fn [e] [:db/add e :seon.task/execution :held]) (sort unset)))
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
        grammar (cache/lookup ::file-grammar forms
                              (constantly (* schema/declaration-projection-bytes-per-form (count forms)))
                              #(id/digest 64 [forms]))
        proposed (into [] (mapcat (fn [[path text]]
                                   (cache/lookup ::file-declarations [grammar path text]
                                                 #(+ 256 (* 2 (count text)) (* 512 (count %)))
                                                 #(file-declarations projection path text)))) files)
        by-id (into {} (map (juxt :seon.task/id identity)) proposed)
        stored (stored-tasks database :seon.task/ns (set (map file-namespace (keys files))))]
    (when (read-error stored) (throw (ex-info (:seon.error/message stored) stored)))
    (when-let [sym (some (fn [[sym n]] (when (< 1 n) sym)) (frequencies (map :seon.task/sym proposed)))]
      (functions/task-refused! 'seon.task/tasks-tx :duplicate-name (symbol (namespace sym)) sym {}))
    ;; A runtime task (7.8, its writer recorded) is no file's declaration: never retracted here.
    (admitted database (into (into (symbol-tx database) (held-tx database)) (task-delta projection (into {} (remove (comp :seon.task/created-by val)) stored) by-id basis)))))

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
                  #(+ (* schema/declaration-projection-bytes-per-form (count (:seon.schema.projection/forms projection)))
                      (* 80 (reduce + 0 (map count content)))
                      (* 2 (reduce + 0 (map count (vals files)))) (* 256 (count %)))
                  #(tasks-tx-in database files basis))))

(defn task-revision
  "The reviewed revision of the task `handle` in `database`: seon.id/digest over its declaration; nil when absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/revision :seon.db/invalid-read-error]]}
  [database handle]
  (let [stored (stored-tasks database :seon.task/id #{handle})]
    (or (read-error stored)
        (some->> (stored handle) (declared (db/carried-projection database)) vector (id/digest 64)))))

(defn adopt-tasks-tx
  "Reconcile the selected `handles` into a development database from the `rows` the
  exact published source holds for them, against the `basis` the adoption read at
  the destination: a competing edit since refuses (7.16); a handle absent from
  `rows` retracts. Every task the destination holds without its derived symbol gains it first."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map] [:set :seon.task/handle] :seon.task/basis]
                  [:vector :seon.schema/value]]}
  [database entities handles basis]
  (let [stored (stored-tasks database :seon.task/id handles)]
    (when (read-error stored) (throw (ex-info (:seon.error/message stored) stored)))
    (admitted database
              (into (into (symbol-tx database) (held-tx database))
                    (task-delta (db/carried-projection database) stored (into {} (map (juxt :seon.task/id #(dissoc % :db/id))) entities) basis)))))

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

(defn adopt-tasks-call
  "Transaction data reconciling `destination`'s tasks to every task `source`, the
  published database, holds: the whole set, since an adopted commit never proved its
  tasks admitted. A held task the source's history retracted was published and is
  deleted; one the source never held is the destination's own and stays. The basis
  is `destination`, the value this call read. Empty when they agree."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:or :seon.db/tx-data :seon.db/invalid-read-error]]}
  [source destination]
  (let [published (task-handles source false) retracted (task-handles source true) held (task-handles destination false)
        failure (some read-error [published retracted held])
        handles (when-not failure (into published (filter retracted) held))
        entities (when handles (stored-tasks source :seon.task/id handles))
        basis (when handles (stored-tasks destination :seon.task/id handles))]
    (or failure (read-error entities) (read-error basis)
        (let [entities (vec (vals entities))
              basis (update-vals basis #(declared (db/carried-projection destination) %))]
          (if (empty? (adopt-tasks-tx destination entities handles basis)) [] [[:db.fn/call #'adopt-tasks-tx entities handles basis]])))))

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

(defn task-edges
  "A task's subjects (functions, namespaces), proofs (deftests), and the definitions,
  namespaces and tasks whose must-read names one of its subjects, over one held value.
  The task is its symbol, as holders tag it, so a branch holding no task row reads its edges."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym] [:or :seon.task/edges :seon.db/invalid-read-error]]}
  [database sym]
  (let [tagged (fn [attribute] (db/q [:find '[?s ...] :in '$ '?t :where ['?e :seon.task/tasks '?t] ['?e attribute '?s]] database sym))
        proofs (tagged :seon.test/sym)
        subject-reads (when-not (read-error proofs) [(tagged :seon.fn/sym) (tagged :seon.ns/name)])
        subjects (when-not (some read-error subject-reads) (set (apply concat subject-reads)))
        referrers (when (seq subjects)
                    (mapv (fn [[reader holder]]
                            (db/q [:find '[?h ...] :in '$ '[?target ...] :where ['?e reader '?target] ['?e holder '?h]]
                                  database (vec subjects)))
                          [[:seon.fn/must-read :seon.fn/sym] [:seon.fn/must-read :seon.test/sym]
                           [:seon.fn/must-read :seon.ns/name] [:seon.task/must-read :seon.task/sym]]))]
    (or (read-error proofs) (some read-error subject-reads) (some read-error referrers)
        {:seon.task/subjects subjects :seon.task/proofs (set proofs)
         :seon.task/referrers (set (apply concat referrers))})))

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
  (db/call-with-custody {:seon.db/connection connection} #(db/transact! connection tx)))

(defn- task-entity
  "`declaration` as the runtime task entity `agent-id` writes, under its handle and symbol."
  {:malli/schema [:=> [:cat :seon.task/declaration :seon.agent/id] :seon.task/task]}
  [{:seon.task/keys [ns name kind] :as declaration} agent-id]
  (assoc declaration :seon.task/id (task-handle ns name kind) :seon.task/sym (task-sym ns name) :seon.task/execution :held
         :seon.task/created-by [:seon.agent/id agent-id]))

(defn advance-assignments-tx
  "The transaction data moving every assignment of the task `entity` to its declaration's revision.
  Invoke as `[:db.fn/call seon.task/advance-assignments-tx entity]`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :seon.db/tx-data]}
  [database entity]
  (let [revision (->> entity (declared (db/carried-projection database)) vector (id/digest 64))
        assigned (db/q '[:find ?a ?revision :in $ ?handle
                         :where [?a :seon.task/assigned ?handle] [?a :seon.task/revision ?revision]]
                       database (:seon.task/id entity))]
    (when (read-error assigned) (throw (ex-info (:seon.error/message assigned) assigned)))
    (into [] (keep (fn [[a current]] (when (not= current revision) [:db/add a :seon.task/revision revision]))) assigned)))

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
  reach main with its successful end, `seon.cluster.squash/copy!`) from its declaration (7.8). Its handle is
  the aspect `[ns name kind]` and its symbol `ns/name`: the same declaration again
  changes nothing; a different one refuses with base, current and proposed unless
  `:seon.task/basis` names the revision I read (7.16). A name that is no symbol
  name refuses `:invalid-name`; `:seon.task/children` and `:seon.task/needs` name
  tasks as lookup refs `[:seon.task/sym ns/name]`, and needs that would leave a task
  waiting on itself refuse `:needs-cycle`.

  Example:
  (seon.task/create! {:seon.task/ns 'my.turn :seon.task/name \"titles-trim\"
                      :seon.task/kind :function :seon.task/intent \"Trim reply titles.\"})"
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]
                             [:seon.task/ns :seon.task/ns] [:seon.task/name :seon.task/name] [:seon.task/kind :seon.task/kind]
                             [:seon.task/intent :seon.task/intent] [:seon.task/must-read {:optional true} :seon.task/must-read]
                             [:seon.task/evidence {:optional true} :seon.task/evidence]
                             [:seon.task/children {:optional true} :seon.task/children] [:seon.task/needs {:optional true} :seon.task/needs]
                             [:seon.task/basis {:optional true} :seon.task/basis]]]
                  [:or [:map [:seon.task/id :seon.task/id] [:seon.task/changed? :seon.task/changed?]]
                   :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id task-name :seon.task/name :as request}]
  (if-not (symbol-name? task-name)
    (refused 'seon.task/create! :invalid-name (:seon.task/ns request) task-name)
    (let [entity (task-entity (select-keys request [:seon.task/ns :seon.task/name :seon.task/kind :seon.task/intent
                                                    :seon.task/must-read :seon.task/evidence
                                                    :seon.task/children :seon.task/needs]) agent-id)
          tx (refusal-data #(task-call connection entity (:seon.task/basis request {})))
          report (if (and (vector? tx) (seq tx)) (written connection tx) tx)]
      (if (:seon.error/at report)
        report
        {:seon.task/id (:seon.task/id entity) :seon.task/changed? (boolean (seq tx))}))))

(defn assignment-read
  "What assigning task `handle` to `agent` reads at main `database`: the task's revision, the agent's
  id and the task's proofs; refused when the task or agent is unknown or the task has no proof."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assigned :seon.task/agent]
                  [:or [:map [:seon.task/revision :seon.task/revision] [:seon.agent/id :seon.agent/id]
                        [:seon.task/proofs :seon.task/proofs]]
                   :seon.task/refused-error :seon.error/value]]}
  [database handle agent]
  (let [revision (task-revision database handle)
        agent-id (:seon.agent/id (db/pull database [:seon.agent/id] agent))
        edges (when (and (string? revision) (string? agent-id))
                (task-edges database (:seon.task/sym (db/pull database [:seon.task/sym] [:seon.task/id handle]))))]
    (cond (:seon.error/at revision) revision
          (nil? revision) (refused 'seon.task/assign! :unknown-task 'seon.task handle)
          (not (string? agent-id)) (refused 'seon.task/assign! :unassigned 'seon.task agent)
          (:seon.error/at edges) edges
          (empty? (:seon.task/proofs edges)) (refused 'seon.task/assign! :no-proofs 'seon.task handle)
          :else {:seon.task/revision revision :seon.agent/id agent-id :seon.task/proofs (:seon.task/proofs edges)})))

(defn- next-attempt
  "The number and identity `seon.id/id [handle number]` of task `handle`'s next attempt at `database`:
  one more than its ended attempts."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or [:tuple [:int {:min 1}] :seon.task/assignment] :seon.db/invalid-read-error]]}
  [database handle]
  (let [ended (db/q '[:find (count ?a) . :in $ ?handle
                      :where [?t :seon.task/id ?handle] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end _]]
                    database handle)]
    (or (read-error ended)
        (let [number (inc (or ended 0))] [number (id/id [handle number])]))))

(defn attempt-tx
  "The transaction data writing the next attempt of task `handle` by `agent-id` on `branch` at main
  `database`: its row, numbered one more than the task's ended attempts and identified by
  `seon.id/id [handle number]`, with `:seon.task/attempt-of` beside the handle value; [] when that
  attempt is already written. The agent it names may be created in the same transaction. An unknown
  task refuses `:unknown-task`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle :seon.agent/id :seon.store/branch]
                  [:or :seon.store/transaction-data :seon.task/refused-error :seon.error/value]]}
  [database handle agent-id branch]
  (let [[number attempt :as next] (next-attempt database handle)
        revision (task-revision database handle)]
    (cond (read-error next) next
          (read-error revision) revision
          (nil? revision) (refused 'seon.task/attempt-tx :unknown-task 'seon.task handle)
          (:db/id (db/pull database [:db/id] [:seon.task/assignment attempt])) []
          :else [{:seon.task/assignment attempt :seon.task/assigned handle :seon.task/attempt-of [:seon.task/id handle]
                  :seon.task.attempt/number number :seon.task/revision revision
                  :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch}])))

(def ^:private task-ref-attributes
  "The task attributes naming other entities, left out when a task is written onto a branch that lacks it."
  #{:seon.task/children :seon.task/needs :seon.task/faults :seon.task/created-by :seon.task/function})

(defn attempt-rows-tx
  "The transaction data writing onto `branch-db` attempt `key`'s row as main `database` holds it, under
  main's entity id, with its task (scalar facts, main's id) when `branch-db` lacks it, so the branch's
  agent reads its assignment in its own world and the end copy lands on the same entities; [] when
  `branch-db` holds the row. The row names its agent by id: the branch holds the agent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.task/assignment]
                  [:or :seon.store/transaction-data :seon.task/refused-error :seon.error/value]]}
  [database branch-db key]
  (let [row (db/pull database [:db/id :seon.task/assignment :seon.task/assigned :seon.task/revision :seon.task/branch
                               :seon.task.attempt/number {:seon.task/agent [:seon.agent/id]}
                               {:seon.task/attempt-of [:db/id]}]
                     [:seon.task/assignment key])
        handle (:seon.task/assigned row)
        task (when (string? handle) (db/pull database '[*] [:seon.task/id handle]))
        present? (fn [lookup] (:db/id (db/pull branch-db [:db/id] lookup)))]
    (cond (some read-error [row task]) (some read-error [row task])
          (not (string? handle)) (refused 'seon.task/attempt-rows-tx :unassigned 'seon.task key)
          (present? [:seon.task/assignment key]) []
          :else
          (let [task-present? (present? [:seon.task/id handle])
                attempt-of (get-in row [:seon.task/attempt-of :db/id])]
            (cond-> []
              (and (not task-present?) attempt-of) (conj (apply dissoc task task-ref-attributes))
              true (conj (cond-> (-> row
                                     (dissoc :seon.task/attempt-of)
                                     (assoc :seon.task/agent [:seon.agent/id (get-in row [:seon.task/agent :seon.agent/id])]))
                           attempt-of (assoc :seon.task/attempt-of (if task-present? [:seon.task/id handle] attempt-of)))))))))

(defn assign!
  "Root assigns the task `:seon.task/assigned` to the agent `:seon.task/agent`
  working on `:seon.task/branch`, binding the revision it reviews now: the
  attempt row, written on `connection` (main, root's world) and, when the agent
  works on a branch of its own, on that branch too under main's ids
  (`attempt-rows-tx`), so the agent reads its assignment in its own world; a
  branch this process holds no connection to refuses `:no-world`. A task with no
  proof refuses `:no-proofs`.
  Nothing runs here: the agent's first pass proves first (`seon.turn/prove-turn`),
  in its own world, and proofs already green end the attempt with no model call.
  Answers the assignment; it offers no operation, since nothing is yet worked.

  Example:
  (seon.task/assign! {:seon.task/assigned \"a1b2c3d4e5f6\" :seon.task/agent [:seon.agent/id \"mcp/fix\"]
                      :seon.task/branch :fix})"
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
                    (let [rows (attempt-rows-tx (db/db connection) (db/db target) attempt)]
                      (if (and (vector? rows) (seq rows))
                        (db/call-with-custody {:seon.db/connection target} #(db/transact! target rows))
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
        edges (when (:seon.task/sym task) (task-edges database (:seon.task/sym task)))]
    (cond (some :seon.error/at [assigned current task edges]) (some #(when (:seon.error/at %) %) [assigned current task edges])
          (and (= 1 (count assigned)) (not= revision current))
          (refused 'seon.task/assignment :revised-since-assignment 'seon.task {:seon.task/revision revision :seon.task/current current})
          (= 1 (count assigned))
          (merge {:seon.task/key key :seon.task/assigned handle :seon.task/of-assignment [:seon.task/assignment key]
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

(defn- red-first
  "The `proofs` observed red on `database` (the proposing branch) at a reach
  no green member of the same test shares, as `[test reach-digest]`: each
  failed at the program before the change (health plan §5, owner ruling: a
  task made from a fault is proven only red first)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :symbol]]
                  [:or [:set [:tuple :symbol [:string {:min 64 :max 64}]]] :seon.db/error-result]]}
  [database proofs]
  (let [members (db/q '[:find ?test ?reach ?fail ?error :in $ [?test ...]
                        :where [?member :seon.test.member/symbol ?test]
                               [?member :seon.test.member/reach-digest ?reach]
                               [?member :seon.test.member/fail-count ?fail]
                               [?member :seon.test.member/error-count ?error]]
                      database (vec proofs))]
    (if (map? members)
      members
      (let [green (into #{} (keep (fn [[proof reach fail failure]] (when (zero? (+ fail failure)) [proof reach]))) members)]
        (into #{} (keep (fn [[proof reach fail failure]]
                          (when (and (pos? (+ fail failure)) (not (green [proof reach]))) [proof reach])))
              members)))))

(defn request-merge!
  "Record on `connection`'s branch (the agent's world; its successful end copies it to main) the merge
  request for the assigned `work`: its merge task and one merge request
  of the commit `view` names on its branch, with its proofs at their definition digests, the run and its
  tested program, and each proof observed red on the branch before the change (`:seon.task/red-first`, read
  from the branch `connection` holds). The merge task's aspect is `[ns \"merge-<key>\" :merge]`, so a
  repeat request finds it; the recurrence key `[key commit]` records one merge request per commit. Answers
  the merge request view."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id :seon.task/assigned-work
                       [:map [:seon.task/branch :seon.task/branch] [:seon.task/commit :seon.task/commit]
                        [:seon.task/proved :seon.task/proved] [:seon.task/run-id :seon.task/run-id]
                        [:seon.task/program :seon.task/program]]]
                  [:or :seon.task/merge-request-view :seon.task/conflict-error :seon.error/value]]}
  [connection agent-id {:seon.task/keys [key ns of-assignment]} view]
  (let [entity (task-entity {:seon.task/ns ns :seon.task/name (str "merge-" key) :seon.task/kind :merge
                       :seon.task/intent (str "Merge branch " (name (:seon.task/branch view)) "'s change for " key ".")}
                      agent-id)
        red (red-first (db/db connection) (into #{} (map first) (:seon.task/proved view)))
        view (cond-> (assoc view :seon.task/merge-request (id/id [key (:seon.task/commit view)]) :seon.task/merge (:seon.task/id entity))
               (seq red) (assoc :seon.task/red-first red))
        report (refusal-data #(written connection (conj (task-call connection entity {}) (assoc view :seon.task/of-assignment of-assignment))))]
    (cond (map? red) red
          (:seon.error/at report) report
          :else view)))

(defonce ^:private retire-branch!
  ;; my.agent closes a branch's agent before its roster entry goes; a my namespace is never required here
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
  "Close the merge request `merge-task`: write its `outcome` (`:merged`, or `:closed` with `reason`) at
  main and delete the source branch each of its merge requests names, closing the agent there
  (`delete-branch!`; a refused deletion is answered beside the closed request), unless the requesting agent's
  cluster keeps ended branches for audit (`:seon.config.agent/keep-ended-branches?`). The one close owner: root's merge (`seon.cluster.source/land!`, which deletes the merge
  branch it tested) and root's decline (`my.root/close!`) both end here. Answers the closed merge request.

  Example:
  (seon.task/close! context \"0123456789ab\" :closed \"The change belongs in seon.db.\")"
  {:malli/schema [:=> [:cat :seon.task/close-context :seon.task/merge :seon.task/outcome [:maybe :seon.task/reason]]
                  [:or :seon.task/closed :seon.error/value]]}
  [{connection :seon.db/connection :as context} merge-task outcome reason]
  (let [report (written connection (cond-> [[:db/add [:seon.task/id merge-task] :seon.task/outcome outcome]]
                                     reason (conj [:db/add [:seon.task/id merge-task] :seon.task/reason reason])))
        ;; a branch whose agent's cluster keeps ended branches stays for audit
        kept? (fn [database branch]
                (let [agents (db/q '[:find [?id ...] :in $ ?merge ?branch
                                     :where [?request :seon.task/merge ?merge] [?request :seon.task/branch ?branch]
                                     [?request :seon.task/of-assignment ?a] [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                                   database merge-task branch)]
                  (and (sequential? agents)
                       (some #(true? (config/agent-cluster-dial database % :seon.config.agent/keep-ended-branches?)) agents))))
        branches (when (:db-after report)
                   (db/q '[:find [?branch ...] :in $ ?merge
                           :where [?request :seon.task/merge ?merge] [?request :seon.task/branch ?branch]]
                         (:db-after report) merge-task))
        branches (cond->> branches (sequential? branches) (remove #(kept? (:db-after report) %)))]
    (cond (:seon.error/at report) report
          (:seon.error/at branches) branches
          :else (let [refused (into [] (keep #(delete-branch! context %)) (sort branches))]
                  (cond-> {:seon.task/id merge-task :seon.task/outcome outcome}
                    reason (assoc :seon.task/reason reason)
                    (seq refused) (assoc :seon.task/delete-refusals refused))))))

;;; THE FRONTIER (runtime-follows-db §2): the leaves that should run now, derived from the tree,
;;; the intents and the attempts' ends, with no scheduler state. Recursion is positive (`under`,
;;; `needs*`) and negation applies only to finished relations, which Datahike evaluates.

(def frontier-rules
  "The stratified rules the frontier reads: `leaf-done` (an attempt ended :done with no later
  :sent-back targeting it), `under`, `self-or-under`, `needs*` (own and inherited needs) and
  `blocked` (a need with an open leaf at or under it)."
  '[[(leaf-done ?t) [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end :done ?tx]
     (not-join [?t ?tx] [?b :seon.task.attempt/end :sent-back ?tx2] [?b :seon.task.attempt/target ?t] [(> ?tx2 ?tx)])]
    [(under ?n ?l) [?n :seon.task/children ?l]]
    [(under ?n ?l) [?n :seon.task/children ?c] (under ?c ?l)]
    [(self-or-under ?n ?l) [(identity ?n) ?l]]
    [(self-or-under ?n ?l) (under ?n ?l)]
    [(needs* ?t ?n) [?t :seon.task/needs ?n]]
    [(needs* ?t ?n) [?p :seon.task/children ?t] (needs* ?p ?n)]
    [(blocked ?t) (needs* ?t ?n) (self-or-under ?n ?l) [?l :seon.task/id] (not [?l :seon.task/children _]) (not (leaf-done ?l))]])

(defn frontier
  "The handles of every task that should be running now in `database` (main's): the leaves whose own
  intent is :running (no inheritance: ingestion and `create!` write :held on every task, so an absent
  intent never appears and nothing launches by being read here), not done, without a terminal end other
  than :done, minus those a need blocks.
  Two reads: `blocked` evaluated once, unbound, since negating a recursive rule per leaf costs a
  hundred times more. A leaf with an open attempt stays in it; the roster tells running from launch."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:set :seon.task/handle] :seon.db/invalid-read-error]]}
  [database]
  (let [eligible (db/q '[:find ?t ?h :in $ % :where [?t :seon.task/execution :running]
                         [?t :seon.task/id ?h] (not [?t :seon.task/children _]) (not (leaf-done ?t))
                         (not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end ?e]
                                   [(contains? #{:given-back :capped :stalled :cancelled} ?e)])]
                       database frontier-rules)
        blocked (when-not (read-error eligible) (db/q '[:find [?t ...] :in $ % :where (blocked ?t)] database frontier-rules))]
    (or (read-error eligible) (read-error blocked)
        (let [blocked (set blocked)] (into #{} (keep (fn [[t h]] (when-not (blocked t) h))) eligible)))))

(defn done?
  "Whether task `handle` is done in `database`: a leaf when an attempt of it ended :done with no
  later :sent-back targeting it, a parent when no leaf under it is open. An unknown task is not done."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] :boolean]}
  [database handle]
  (let [task (db/q '[:find ?n . :in $ ?h :where [?n :seon.task/id ?h]] database handle)
        below (when (int? task) (db/q '[:find [?l ...] :in $ % ?n :where (under ?n ?l)] database frontier-rules task))
        open (when (and (int? task) (not (read-error below)))
               (db/q '[:find [?l ...] :in $ % [?l ...] :where (not [?l :seon.task/children _]) (not (leaf-done ?l))]
                     database frontier-rules (conj (vec below) task)))]
    (when-let [failure (some read-error [task below open])] (throw (ex-info (:seon.error/message failure) failure)))
    (and (int? task) (empty? open))))

(defn resolution
  "Why task `handle` is closed, or nil while it is open: derived from what
  the task answers, never a stored flag. A merge request is closed by root's
  outcome; a parent by its children, once all are done (`done?`). An authored
  task is open while a definition names it (`seon.task/open-tasks`); it has no
  resolution here.

  ## Examples

  (seon.task/resolution (seon.db/db) \"6cc772cb3f60\")"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/resolution :seon.db/error-result]]}
  [database handle]
  (let [task (db/pull database [:seon.task/outcome :seon.task/children] [:seon.task/id handle])]
    (cond (:seon.error/at task) task
          (:seon.task/outcome task) {:seon.task/closed-by :seon.task/outcome}
          (and (seq (:seon.task/children task)) (done? database handle)) {:seon.task/closed-by :seon.task/children})))
