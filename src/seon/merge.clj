(ns seon.merge
  "The program merge: git's three-way merge over a branch's PROGRAM, as data. From session
  M1's proto.merge (proto/agents/core-patches/01-proto-merge.patch), over the program as
  the compiler knows it (seon.program).

  A db's program is a map from program keys to content (`program`):
  - `[:form id]` -> a live form: its namespace, exact text, place and what the compiler said
    it defines, declares, references and requires. Its id is its namespace and text, so an
    identical form on two branches is one key, and its place does not make two equal forms
    differ;
  - `[:var sym]`, `[:method multi dispatch]`, `[:impl protocol type]`, `[:ns nsym]` -> a
    definition: the form that last made it ({:form id}), and a var's meta, contract and doc.
    Conflicts are per definition: two branches that redefine one var differently conflict
    there, whatever else their forms carry;
  - `[:schema k]` -> a schema entry of a resource ({:form text :file path}; seon.source
    imports them, my.schema/register! changes them); an entry with no resource (a scratch
    key) is no program;
  - `[:config k]` -> an entry of the config file ({:form text :file path}, its value's exact text; seon.source);
  - `[:script path]` -> a script, a file of the program with one entry, its whole text
    ({:file path :text text :mode mode}; seon.source); `[:fixture path]` a test's data file and `[:tasks path]` a task file alike;
  - `[:datum e a]` (cardinality one) / `[:datum e a v]` (many) -> a value of a data
    attribute the caller declares (`data-attrs`: a set, or :all for every attribute that
    is neither program, observation nor merge bookkeeping). Entity ids are store-wide
    unique, so a data entity keeps its id across branches.

  `three-way` compares base, ours and theirs: the one derivation behind merging main into
  a branch, merging a branch into main, the done rule's unmerged entries and the
  dashboard. A conflict is kept as an unmerged entry on the branch (`unmerged-tx`), base,
  ours and theirs as data, until the branch's content for its key changes or it is
  resolved (`resolve-tx`). Every function here is pure over db values."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [datahike.datom] [clojure.string :as str] [seon.db :as pdb] [seon.program :as program] [seon.edn :as sedn] [seon.config :as cfg]))

(def ^:private stated-keys
  "The program keys one entity's stated attributes hold, by key kind, as data: :id the identity naming the entity, :stated each attribute its content
  reads and the content key it takes, :needs a content key without which the entity is no program (a scratch schema entry has no file), :carries the attribute
  namespace outside the program's that rides with it. A section's stated attributes are what a page's text states; the rest (lead, references, names,
  what it read) the load derives from these, so a change shows in these and a merge reads only them (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  {:section {:id :seon.doc/address :carries "seon.doc"
             :stated {:seon.doc/address :seon.doc/address :seon.doc/heading :seon.doc/heading :seon.doc/text :seon.doc/text :seon.doc/parent :seon.doc/parent}}
   :schema {:id :schema/key :stated {:schema/form :form :schema/file :file} :needs :file}
   :config {:id :config/key :carries "config" :stated {:config/form :form :config/file :file} :needs :file}
   :script {:id :file/path :kind-of #'cfg/file-kind :carries "seon.script" :stated {:file/path :file :file/text :text :file/mode :mode}}
   ;; a test fixture and a task file: a file of the program held as its text alone, merged whole and written back byte for byte
   :fixture {:id :file/path :kind-of #'cfg/file-kind :stated {:file/path :file :file/text :text :file/mode :mode}}
   :tasks {:id :file/path :kind-of #'cfg/task-file-kind :stated {:file/path :file :file/text :text :file/mode :mode}}
   ;; a task's content an agent may improve (my.task/update!): it merges as any key, a both-changed member an unmerged entry; :held-only,
   ;; a task the receiving branch doesn't hold is never made by its content alone (its declaration makes it)
   :task {:id :seon.task/id :held-only true
          :stated {:seon.task/plan :plan :seon.task/instructions :instructions :seon.task/proofs :proofs
                   :seon.task/found-nothing :found-nothing}}})

(defn- bears?
  "Whether the entity named v bears key kind, under its row of stated-keys: an identity several kinds share (a file's path) names the kind by the row's :kind-of. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :keyword :map :any] :boolean]}
  [kind {:keys [kind-of]} v] (or (nil? kind-of) (= kind (kind-of v))))

(def ^:private stated-attr?
  "The attributes stated-keys' contents read (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  (into #{} (mapcat (comp keys :stated)) (vals stated-keys)))

(def ^:private carried-namespace?
  "The attribute namespaces outside the program's that stated-keys carry (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  (into #{} (keep :carries) (vals stated-keys)))

(defn- data-attr?
  "Whether an attribute of db is data that data-attrs (a set, or :all) names, as a predicate:
  the set itself, or for :all one that asks of an attribute alone whether db's own schema entry
  gives it a value type, outside the program's namespaces: every fact, the platform's records included, with no list of what may
  cross (docs/decisions.md#one-merge-everything-that-doesnt-conflict-both-directions). A merge
  asks it of the attributes of the datoms that differ, so it costs those, never the schema:
  d/schema rebuilt the whole schema map on every call, once per db per read."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] ifn?]}
  [db data-attrs]
  (if (= :all data-attrs)
    ;; the derived attributes, as the entity index holds them (made once with it), never asked per attribute
    (let [schema (:schema db) derived (:derived-attrs (pdb/index))]
      (fn [a] (boolean (and (keyword? a) (:db/valueType (get schema a))
                            (not (or (program/attr-namespaces (namespace a)) (stated-attr? a) (derived a)))))))
    data-attrs))

(defn- data-attr-set
  "The data attributes of db that data-attrs names, as a set: the db's attributes (its attribute
  index, rschema's :db/ident) that data-attr? holds, for a read of the whole program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] [:set :keyword]]}
  [db data-attrs]
  (if (= :all data-attrs)
    (into #{} (filter (data-attr? db :all)) (get-in db [:rschema :db/ident]))
    data-attrs))

(defn- def-content
  "Definition k's content on db, when it points to form row f: the form's id, and its text
  (so a conflict shows each side's code as written)."
  {:malli/schema [:=> [:cat :seon.host/db :any :int :map] :map]}
  [db k e f]
  (let [x (d/entity db e)
        c {:form (:id f) :src (:src f)}]
    (case (first k)
      :var (cond-> (assoc c :meta (set (:var/meta x)))
             (:var/contract x) (assoc :contract (:var/contract x))
             (:var/doc x) (assoc :doc (:var/doc x)))
      :ns (cond-> c (:ns/file x) (assoc :file (:ns/file x)) (:ns/doc x) (assoc :doc (:ns/doc x)))
      c)))

(defn- stated-content
  "Entity e's content on db under row of stated-keys: what its attributes state, refs as entity ids (issued
  once across the store, so they name the same entities on every branch), or nil when it lacks what the row needs. See
  docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] [:maybe :map]]}
  [db {:keys [stated needs]} e]
  (let [many (get-in db [:rschema :db.cardinality/many] #{})
        c (reduce (fn [m dt] (if-let [k (stated (:a dt))]
                               (if (many (:a dt)) (update m k (fnil conj #{}) (:v dt)) (assoc m k (:v dt)))
                               m))
                  {} (d/datoms db :eavt e))]
    (when (or (nil? needs) (contains? c needs)) c)))

(defn program
  "The program of db as {key content}: its live forms, the definitions pointing to them,
  and the declared data. Cost: the program's forms and definitions, read once."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] :seon.merge/program]}
  [db data-attrs]
  (let [schema (d/schema db)
        forms (program/live-forms db)
        defs (for [f forms k (:defines f)
                   :let [e (program/def-eid db k)]
                   :when (and e (some #(= (:e f) (:db/id (get (d/entity db e) %))) (vals program/pointer)))]
               [k (def-content db k e f)])
        data (for [a (sort (data-attr-set db data-attrs))
                   dt (d/datoms db :aevt a)]
               (if (= :db.cardinality/many (get-in schema [a :db/cardinality]))
                 [[:datum (:e dt) a (:v dt)] true]
                 [[:datum (:e dt) a] (:v dt)]))
        stated (for [[kind row] stated-keys dt (d/datoms db :aevt (:id row)) :when (bears? kind row (:v dt)) :let [c (stated-content db row (:e dt))] :when c] [[kind (:v dt)] c])]
    (into {} (concat (for [f forms] [[:form (:id f)] (dissoc f :e :id)]) defs stated data))))

(defn- same?
  "Is content a the same as b for key k: a form's place does not make it another form."
  {:malli/schema [:=> [:cat :seon.merge/program-key :any :any] :boolean]}
  [k a b]
  (if (and (= :form (first k)) (map? a) (map? b)) (= (dissoc a :order :lead) (dissoc b :order :lead)) (= a b)))

(defn changes
  "{key [from-content to-content]} for every key whose content differs (nil: absent)."
  {:malli/schema [:=> [:cat :seon.merge/program :seon.merge/program] :seon.merge/changes]}
  [from to]
  (into {} (for [k (into (set (keys from)) (keys to))
                 :let [a (get from k) b (get to k)]
                 :when (not (same? k a b))]
             [k [a b]])))

(defn uncontracted
  "The functions a branch's changes ours (a three-way's :ours) define without a contract,
  sorted; none while contracts are off (seon.contract/on?). The writer's merge gate and
  root's judge both ask this."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:vector :qualified-symbol]]}
  [ours]
  (if contract/on?
    (vec (sort-by str (for [[[k sym] [_ ov]] ours :when (and (= :var k) (let [m (:meta ov)] (and (contains? m :arglists) (not-any? m [:macro :protocol :test]))) (nil? (:contract ov)))] sym)))
    []))

(defn task-conflicts
  "The members of tasks a branch and main both changed differently, from the branch's unmerged entries (the one merge's, `entries`):
  each [:datum e a] entry at a task entity of db that holds both sides (an entry the replay's write check refused names :why, no
  conflict of two sides), as :seon.task/task-conflicts holds them, {:task :attr :base :ours :theirs}, ours the branch's and theirs
  main's. See docs/tasks.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map]] :seon.task/task-conflicts]}
  [db entries]
  (vec (for [{[kind e a] :key :keys [base ours theirs why] :as c} entries
             :when (and (= :datum kind) (nil? why) (contains? c :ours) (contains? c :theirs))
             :let [id (:v (first (d/datoms db :eavt e :seon.task/id)))] :when id]
         {:task id :attr a :base base :ours ours :theirs theirs})))

;; ---- the program where it changed: cost follows the change, not the program ------------

(defn- entity-keys
  "The program keys entity e bears on db: a form's own and those of the definitions it
  makes (their content holds its text), a definition's own and that of the form it points
  to (whose liveness it decides), a schema entry's, and e's declared data."
  {:malli/schema [:=> [:cat :seon.host/db [:set :keyword] :int ifn?] [:set :seon.merge/program-key]]}
  [db many e data-attrs]
  ;; one seek reads the entity's datoms; an entity that bears no key (a turn's facts, a
  ;; transaction) costs only that seek
  (let [ds (d/datoms db :eavt e)
        x (into {} (for [dt ds :when (not (many (:a dt)))] [(:a dt) (:v dt)]))
        form-key (fn [f] (when-let [id (:form/id f)] [:form id]))
        pointed (fn [a] (when-let [f (get x a)] (form-key (d/entity db f))))
        def-key (:defines (when (:form/id x) (program/form-row db e)))
        own-def (cond (:var/name x) [:var (:var/name x)]
                      (:method/key x) (into [:method] (sedn/decode (:method/key x)))
                      (:impl/key x) (into [:impl] (sedn/decode (:impl/key x)))
                      (:ns/name x) [:ns (:ns/name x)])]
    (into #{}
          (concat (when-let [k (form-key x)] (cons k def-key))
                  (when own-def (remove nil? [own-def (pointed (program/pointer (first own-def)))]))
                  (for [[kind {:keys [id] :as row}] stated-keys :let [v (get x id)] :when (and (some? v) (bears? kind row v))] [kind v])
                  (for [dt (when-not (= #{} data-attrs) ds) :when (data-attrs (:a dt))]
                    (if (many (:a dt))
                      [:datum e (:a dt) (:v dt)]
                      [:datum e (:a dt)]))))))

(defn- key-content
  "Key k's content on db, as `program` holds it, or nil when db's program lacks k. A form's
  content is its row less where it was read (:source: one form id can be imported on one
  branch and recorded at a REPL on another, the same text)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key ifn?] :any]}
  [db [kind a b c :as k] data-attrs]
  (if-let [stated (stated-keys kind)]
    (some->> (pdb/eid db (:id stated) a) (stated-content db stated))
    (case kind
      :form (when-let [e (pdb/eid db :form/id a)]
              (when (program/live? db e) (dissoc (program/form-row db e) :e :id :source)))
      (:var :method :impl :ns)
      (when-let [e (program/def-eid db k)]
        (when-let [f (:db/id (get (d/entity db e) (program/pointer kind)))]
          (let [row (program/form-row db f)]
            (when (and (program/live? db f) (contains? (:defines row) k)) (def-content db k e row)))))
      :datum (when (data-attrs b)
               (if (= 4 (count k))
                 (when (seq (d/datoms db :eavt a b c)) true)
                 (:v (first (d/datoms db :eavt a b))))))))

(defn program-of
  "The part of db's program at keys ks: `program` restricted to them, read key by key."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.merge/program-key] :seon.merge/data-attrs] :seon.merge/program]}
  [db ks data-attrs]
  (let [das (data-attr? db data-attrs)]
    (into {} (for [k ks :let [c (key-content db k das)] :when (some? c)] [k c]))))

(defn- content-attr
  "The predicate of an attribute a key's content reads on base or db: the program's (program/attr-namespaces) or declared
  data (das-b, das-d), never :db/txInstant unless declared data, which every transaction asserts and no key's content reads (a
  transaction's entity bears no key: reading it cost a descent and two seeks a transaction, 87 of them for
  a branch of 18 evals). One contracted call a merge; the predicate is asked of each attribute (1,771 of them when main
  only embedded) with no contract of its own."
  {:malli/schema [:=> [:cat ifn? ifn?] :seon.host/fn]}
  [das-b das-d]
  (fn [a] (and (keyword? a) (boolean (or (and (not= :db/txInstant a) (or (program/attr-namespaces (namespace a)) (stated-attr? a))) (das-b a) (das-d a))))))

(defn touched-by
  "The program keys whose content may differ between base and db, given moved, the datoms that differ
  between them (what datahike.api/datoms-diff answers, joined; a caller that holds them reads
  nothing again): the keys borne, on base or on db, by each entity of a moved datom whose attribute is one a
  key's content reads (the program's, program/attr-namespaces, or declared data, data-attr?), never a platform
  record such as an embedding."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.merge/data-attrs :seon.merge/moved] [:set :seon.merge/program-key]]}
  [base db data-attrs moved]
  (let [read (fn [db] (let [das (data-attr? db data-attrs)]
                        [db (get-in db [:rschema :db.cardinality/many] #{}) das
                         (fn [a] (or (program/attr-namespaces (namespace a)) (stated-attr? a) (das a)))]))
        b (read base) d (read db)
        content-attr? (content-attr (nth b 2) (nth d 2))
        content? (fn [{:keys [a]}] (content-attr? a))]
    ;; a transaction's own entity (its instant, runtime, branch) bears no key: its facts ride with the datoms it made (carried-tx)
    (into #{} (for [e (into #{} (comp (filter content?) (map :e)) moved)
                    :when (not (or (first (d/datoms base :eavt e :db/txInstant)) (first (d/datoms db :eavt e :db/txInstant))))
                    [side many das] [b d]
                    k (entity-keys side many e das)]
                k))))

(defn content-datoms
  "The datoms that differ between base and db in the attributes a key's content reads (the
  program's, program/attr-namespaces, or declared data, data-attr?), what `touched-by` takes as
  moved: for data-attrs a set, one datahike.api/datoms-diff :aevt range per such attribute, so the
  platform's records are never walked (a whole :eavt diff read every datom that differs, the
  22,000 embedding datoms of a main that embedded after an agent forked: 313M ticks and 640 MB for
  no key); for :all (every attribute the store declares) the one :eavt diff. Cost: a set, one
  descent per content attribute and the datoms of those that differ; :all, the datoms that differ.
  Given diff, the datoms that differ in any attributes that a caller read already (one :eavt diff),
  none is read: the content ones among them, one filter. Read once, a vector, so every reader it
  is passed to reads the same datoms
  (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program, docs/cost.md#a-diff-per-attribute-costs-a-descent-each)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.merge/data-attrs] [:vector :seon.host/datom]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/data-attrs [:sequential :seon.host/datom]] [:vector :seon.host/datom]]]}
  ([base db data-attrs]
   (if (= :all data-attrs)
     (into [] cat (d/datoms-diff base db :eavt []))
     (let [das-b (data-attr? base data-attrs) das-d (data-attr? db data-attrs)]
       (into [] (mapcat (fn [a] (apply concat (d/datoms-diff base db :aevt [a]))))
             (filter (content-attr das-b das-d) (set (concat (keys (:schema base)) (keys (:schema db)))))))))
  ([base db data-attrs diff]
   (if (= :all data-attrs)
     (vec diff)
     (let [das-b (data-attr? base data-attrs) das-d (data-attr? db data-attrs)]
       (into [] (filter (comp (content-attr das-b das-d) :a)) diff)))))

(defn changed?
  "Whether branch db did anything since base, its merge base: a datom of program content differs, task content (a stated key's, a
  refine's edit of the task it refines) among it. The one measure the done rule's :no-change reads
  (seon.reconcile/done-explain), taken alike by the agent's turn check (seon.ctx.status/status) and root's judging
  (seon.reconcile/judge-tx). Cost: content-datoms, one descent per content attribute (0.2M ticks on a store of main's size when
  nothing moved, 0.5M for one var). See docs/reconcile.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] :boolean]}
  [base db] (boolean (seq (content-datoms base db #{}))))

(defn moves-content?
  "Whether a commit's resolved ops move main's content: a datom of an attribute a program key's content reads with no
  declared data (`content-attr`), task content among it (a stated key's). What the writer marks
  main's commits by (docs/storage.md#mains-content-mark). Cost: the ops, one predicate each."
  {:malli/schema [:=> [:cat :seon.db/resolved-tx] :boolean]}
  [ops]
  (let [content? (content-attr #{} #{})]
    (boolean (some (fn [[_ _ a]] (content? a)) ops))))

(defn same-content?
  "Whether a and b, two dbs of main, hold the same content, read from main's content mark (:seon.system/content-t on its root
  entity), which the writer moves in the commit that moves the content and at its start: both carry it and it is equal. A db
  no writer marked answers false, so it is diffed. Only main's own points are asked: a branch's writes move no mark. Cost:
  two entity reads, no diff. See docs/storage.md#mains-content-mark."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean]}
  [a b]
  (let [mark (fn [db] (some->> (pdb/eid db :seon.system/id :root) (d/entity db) :seon.system/content-t))]
    (boolean (and a b (some? (mark a)) (= (mark a) (mark b))))))

(defn content-commit
  "The commit that stands for main's content at db main: held's, a db of an earlier commit, when no content differs (main's
  content mark is held's, `same-content?`, else `content-datoms`), else main's. A commit that moved
  only records then changes no answer a reader of the content gives, nor its key (docs/render.md#how-the-work-stands). Cost:
  two entity reads when the mark answers; else one descent per content attribute, shared subtrees skipped."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :seon.host/db] :uuid]}
  [held main]
  (d/commit-id (if (and held (or (same-content? held main) (empty? (content-datoms held main #{})))) held main)))

(defn touched-keys
  "Every program key whose content may differ between base and any of dbs (`touched-by`, over
  the datoms that differ in the attributes a key's content reads: `content-datoms`). Cost: those
  datoms and one descent per such attribute, not the program and not what else main wrote."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.host/db] :seon.merge/data-attrs] [:set :seon.merge/program-key]]}
  [base dbs data-attrs]
  (into #{} (mapcat (fn [db] (touched-by base db data-attrs (content-datoms base db data-attrs)))) dbs))

(declare key-entity entries entry-eids)

(declare delta-changes)

(defn readiness
  "Whether branch db ours can merge into main theirs now, as a merge of main would find it, read
  only: what main changed since base, their merge base (:main-moved, its program keys), and which
  of those the branch changed too (:conflicts, the keys a merge of main would leave unmerged).
  Each side's own change since base (delta-changes), so it costs what changed on each side since base.
  With ours-moved, the datoms that differ between base and ours (`content-datoms`), ours is not diffed again."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] :seon.merge/readiness]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/moved] :seon.merge/readiness]]}
  ([base ours theirs] (readiness base ours theirs (content-datoms base ours #{})))
  ([base ours theirs ours-moved]
   ;; each side's own change since base (delta-changes); a key both changed to different values is a conflict, one both changed alike
   ;; (a branch holding main's change) is none
   (let [mine (delta-changes base ours #{} ours-moved)
         main (delta-changes base theirs #{})]
     {:main-moved (vec (sort-by str (keys main)))
      :conflicts (vec (sort-by str (for [[k [_ now]] mine :let [m (get main k)] :when (and m (not= now (second m)))] k)))})))

(defn delta-changes
  "`changes` from db from to db to, read only where they differ (touched-keys); with moved, the datoms that
  differ (which a caller already read), nothing is diffed again."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.merge/data-attrs] :seon.merge/changes]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/data-attrs :seon.merge/moved] :seon.merge/changes]]}
  ([from to data-attrs] (delta-changes from to data-attrs (content-datoms from to data-attrs)))
  ([from to data-attrs moved]
   (let [ks (vec (touched-by from to data-attrs moved))]
     (changes (program-of from ks data-attrs) (program-of to ks data-attrs)))))

(defn divergence
  "What a branch's changes ch (delta-changes from its merge base, read once a turn:
  seon.agent/branch-changes) carry to main: the vars, methods and implementations it added, redefined and removed, and the
  schema keys it registered or changed and the scripts it changed; each sorted, an empty kind left out ({}: nothing). A var
  is changed when its definition's content is (its form's text, meta, contract or doc: def-content),
  and removed when no live form defines it. A schema registered with no file (a scratch one, my.schema) is no key of the program and never
  merges, so it is none of these."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:map-of [:enum :added :changed :removed :schemas :config :scripts] [:vector [:or :qualified-symbol :qualified-keyword :string [:vector :any]]]]]}
  [ch]
  (let [kinds (for [[[kind x :as k] [a b]] ch :when (#{:var :schema :method :impl :config :script} kind)]
                [(cond (= :schema kind) :schemas (= :config kind) :config (= :script kind) :scripts (nil? a) :added (nil? b) :removed :else :changed)
                 ;; a method or an implementation goes by what names it, [multi dispatch], [protocol type]
                 (if (#{:method :impl} kind) (vec (rest k)) x)])]
    (into {} (for [[k xs] (group-by first kinds)] [k (vec (sort-by str (map second xs)))]))))

(defn changed-vars
  "The vars changes ch add, redefine or remove (divergence's vars): what the branch changed."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:set :qualified-symbol]]}
  [ch] (into #{} (keep (fn [[kind x]] (when (= :var kind) x))) (keys ch)))

;; ---- verbatim merges: a merged datom is the same datom ------------------------------

(defn- deleted-by?
  "Whether entity e is one base held and db holds nothing of: db deleted it, whole. A fact another side wrote of it since has no
  subject on db, and a merge onto db leaves it out, never a nameless entity (a test's verdict on a var db removed). Cost: two seeks."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :int] :boolean]}
  [base db e]
  (boolean (and (first (d/datoms base :eavt e)) (nil? (first (d/datoms db :eavt e))))))

(defn changed-datoms
  "What head changed since base, as [gone added] datoms, read from the commits between them (d/changes); a diff of the two
  values only for commits that kept no changes. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:tuple [:sequential :seon.host/datom] [:sequential :seon.host/datom]]]}
  [base head]
  ;; a value made by d/with since its commit keeps that commit's id: only two committed values (no uncommitted datoms,
  ;; datahike.core/with's meta) are their commits, so only they are read from the commits
  (let [committed? (fn [db] (nil? (:datahike/uncommitted (meta db))))
        from (when (committed? base) (d/commit-id base)) to (when (committed? head) (d/commit-id head))
        ch (when (and from to (not= from to))
             (try (d/changes head from to)
                  (catch clojure.lang.ExceptionInfo e
                    (when-not (#{:datahike/changes-unrecorded :datahike/changes-not-ancestor} (:type (ex-data e))) (throw e)))))]
    (cond (and from (= from to)) [[] []]
          ch [(filterv (complement :added) ch) (filterv :added ch)]
          :else (d/datoms-diff base head :eavt []))))

(declare entries entry-eids)

(defn- facts
  "The facts from changed since base (`changed-datoms`), each {:e :a :v :tx :side}: :retract for one gone, :add for one added, the
  side taken from which list it is in, never a datom's own flag (a diff's gone datoms are stored datoms, :added true). A
  transaction's own facts, Datahike's own attributes and derived values ({:seon.db/derived true}, derived again where they land) are
  left out; every other fact merges whole (owner: no list of what merges). {:facts, :declared the attributes from declared since base
  (each a :db/ident added: Datahike's own fact, which the merge carries as from's declaration, never as a fact)}. See
  docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:map [:facts [:vector :map]] [:declared [:set :keyword]]]]}
  [base from]
  (let [[gone added] (changed-datoms base from)
        txe (into #{} (map :tx) (concat gone added))
        ;; every fact merges whole, no list of what crosses (owner): a transaction's own facts and Datahike's own are no facts of the merge,
        ;; nor a derived value ({:seon.db/derived true}: a cache each side computes, derived again where it lands; the entity index's set)
        derived (:derived-attrs (pdb/index))
        fact? (fn [{:keys [e a]}] (and (not (txe e)) (keyword? a) (not= "db" (namespace a)) (not (derived a))))
        as (fn [side] (fn [dt] {:e (:e dt) :a (:a dt) :v (:v dt) :tx (:tx dt) :side side}))
        ;; a fact is [e a v], netted from what the change itself holds, never sought again: each assertion counts +1 and each
        ;; retraction -1, so one retracted and asserted again since base (or both held under different transactions, a diff
        ;; comparing whole datoms) nets 0, no side's change (d8a0285ba8's regression on lb76: 712 facts both held were retracted).
        ;; The two seeks a datom it replaces cost 2.7 MB of 6.4 at 840 facts (result/bb148f80)
        eav (juxt :e :a :v)
        ;; only an [e a v] the change both retracted and asserted can net away: a change that retracted nothing (a branch adding
        ;; forms) is its assertions as they are, no key built a datom
        both (when (seq gone) (let [g (into #{} (map eav) gone)] (into #{} (comp (map eav) (filter g)) added)))
        net (when (seq both)
              (as-> (transient {}) n (reduce #(let [k (eav %2)] (if (both k) (assoc! %1 k (dec (get %1 k 0))) %1)) n gone)
                    (persistent! (reduce #(let [k (eav %2)] (if (both k) (assoc! %1 k (inc (get %1 k 0))) %1)) n added))))
        moved? (fn [sign] (fn [dt] (let [k (when net (eav dt))] (or (nil? k) (not (contains? net k)) (= sign (net k))))))
        ;; one fact an [e a v], the last of its datoms (the transaction that wrote the net value, by-tx's): only a netted key repeats
        one (fn [side] (if net (fn [xs] (vals (into {} (map (juxt eav (as side))) xs))) (fn [xs] (map (as side) xs))))]
    {:facts (into (vec ((one :retract) (into [] (comp (filter fact?) (filter (moved? -1))) gone)))
                  ((one :add) (into [] (comp (filter fact?) (filter (moved? 1))) added)))
     :declared (into (sorted-set) (comp (filter #(= :db/ident (:a %))) (filter (moved? 1)) (map :v)) added)}))

(defn- identity-of
  "Entity e's identity on db, [identity-attribute value] by the entity index's :identity-schemas, or nil when it has none: the one
  reading of an entity's name, which `named` shows and `mapped` maps by. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe [:tuple :keyword :any]]]}
  [db e]
  (some (fn [[a]] (some->> (first (d/datoms db :eavt e a)) :v (vector a))) (:identity-schemas (pdb/index))))

(defn named
  "Entity e as an agent reads it: its identity (`identity-of`), else e. A conflict is shown by its entity's name, never an entity id.
  See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :int] :any]}
  [db e]
  (or (identity-of db e) e))

(defn held-at
  "What db holds at merge key k: [:datum e a] its value, [:datum e a v] whether it holds that fact; one seek. The one reading of a
  datum key, which the decision and the unmerged entries share. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :any]] :any]}
  [db [_ e a v :as k]]
  (if (= 4 (count k)) (some? (first (d/datoms db :eavt e a v))) (:v (first (d/datoms db :eavt e a)))))

(defn mapped
  "From's facts since base, as into-db reads them (`facts`): one entity is one identity on both sides, so an entity from made since base
  that into-db holds under the same identity (a task a branch wrote as main declares it, seon.agent/begin-run!) is into-db's entity, its
  id never compared or written (a ref compares by its target's identity, never an id). Answers the facts, each with its from-side :e0
  :v0, and the reads decide and carry share: computed once a merge (seon.writer/candidate hands it to both); :declarations, from's
  declaration of each attribute it declared since base (`facts`'s :declared), carried before the facts. See
  docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] :map]}
  [base from into-db]
  (let [{:keys [declared] :as fx} (facts base from)
        ;; only an entity made since base can be another id on into-db (one base holds is the same id on both: both descend from
        ;; it), and all its datoms are the change's: its identity is read from the facts in hand, by the entity index's order of
        ;; identity attributes (identity-of's), never sought; an entity with none, or one base holds, is its own id. The seeks
        ;; per identity attribute it replaces were 6.8 MB of mapped's 13.2 at 840 facts (result/bb148f80)
        rank (into {} (map-indexed (fn [i [a]] [a i])) (:identity-schemas (pdb/index)))
        ids (reduce (fn [m {:keys [e a v side]}]
                      (if-let [r (and (= :add side) (rank a))]
                        (let [[r0] (get m e)] (if (or (nil? r0) (< r r0)) (assoc m e [r a v]) m))
                        m))
                    {} (:facts fx))
        to-into (memoize (fn [e] (or (when-let [[_ a v] (ids e)] (pdb/eid into-db a v)) e)))
        ref? (fn [a] (= :db.type/ref (get-in (:schema from) [a :db/valueType])))
        fs (mapv (fn [{:keys [e a v] :as f}] (assoc f :e0 e :v0 v :e (to-into e) :v (if (ref? a) (to-into v) v))) (:facts fx))
        many? (fn [a] (= :db.cardinality/many (get-in (:schema from) [a :db/cardinality])))
        k-of (fn [{:keys [e a v]}] (if (many? a) [:datum e a v] [:datum e a]))
        k0-of (fn [{:keys [e0 a v0]}] (if (many? a) [:datum e0 a v0] [:datum e0 a]))
        k0s (reduce (fn [m f] (update m (k-of f) #(or % (k0-of f)))) {} fs)
        ;; an entity from holds no datom of while base did is deleted whole there: carried so, its other side's facts go with it (a
        ;; verdict on a test main removed leaves no nameless entity; lb76, 2026-11-15)
        gone? (memoize #(deleted-by? base from %))]
    {:fs fs :k-of k-of :k0s k0s :ref? ref? :to-into to-into
     :declarations (vec (for [a declared :let [dcl (get (:schema from) a)] :when dcl] (assoc (dissoc dcl :db/id) :db/ident a)))
     :op (fn [{:keys [e e0 a v side]}] (if (and (= :retract side) (gone? e0)) [:db/retractEntity e] [(if (= :add side) :db/add :db/retract) e a v]))
     ;; the entities mapped to another id: each the from-side id and into-db's
     :remapped (into #{} (keep (fn [{:keys [e e0]}] (when (not= e e0) [e0 e]))) fs)}))

(defn- by-tx
  "Facts fs as replayable transactions in their source order: {:source t :ops}, op as `mapped` makes it. A fact is filed under the
  transaction that wrote its net value, and a retraction (a value base held and from does not) under the one that wrote its key's new
  value, else the last that wrote its entity, else the last of all: a value changed twice nets to a retraction stamped with the first
  change and an addition stamped with the last, and replayed apart the retraction leaves the entity without a value its schema
  requires, which the write check refuses (context's and web's seon_done). O(facts)."
  {:malli/schema [:=> [:cat [:sequential :map] ifn?] [:vector [:map [:source :int] [:ops :seon.db/tx-data]]]]}
  [fs op]
  (let [adds (filter #(= :add (:side %)) fs)
        key-t (reduce (fn [m {:keys [e a tx]}] (update m [e a] (fnil max tx) tx)) {} adds)
        e-t (reduce (fn [m {:keys [e tx]}] (update m e (fnil max tx) tx)) {} adds)
        last-t (reduce max 0 (map :tx fs))
        t-of (fn [{:keys [e a tx side]}] (if (= :retract side) (or (key-t [e a]) (e-t e) last-t) tx))]
    (->> fs (group-by t-of) (sort-by key) (mapv (fn [[t ds]] {:source t :ops (vec (distinct (map op ds)))})))))

(defn decide
  "The one merge of main into branch, decided over the branch's facts since base (`mapped`: its entities as main's of the same
  identity), and at each [e a] they touch (each [e a v] of a cardinality-many attribute) the values base, main and the branch hold: a
  conflict is a key both sides changed since base, to different values, and only the branch's keys can be one, so it costs O(d), d
  what the branch changed, never main's churn (one seek a key on each of three dbs). {:conflicts [{:key [:datum e a] or [:datum e a v]
  as the branch names it (its entries' key), :entity its name (`named`), :base :ours :theirs}]}, :ours the branch's, :theirs main's
  (git's words merging main into the branch), a value (cardinality one) or whether the [e a v] is held, nothing applied. A conflict the
  branch decided (its entry resolved, my.branch/resolve!, while main still holds the entry's theirs) is no conflict: the branch's value
  stands. Clean: {:settled keys :settle-ops (retracting every entry the branch holds: none is a conflict now)}. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] [:or [:map [:conflicts [:vector :seon.merge/conflict]]] [:map [:settled [:set :any]] [:settle-ops :seon.db/tx-data]]]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map] [:or [:map [:conflicts [:vector :seon.merge/conflict]]] [:map [:settled [:set :any]] [:settle-ops :seon.db/tx-data]]]]]}
  ([base branch main] (decide base branch main (mapped base branch main)))
  ([base branch main {:keys [fs k-of k0s ref? to-into]}]
  (let [
        at held-at
        ;; the branch's value read where the branch holds it, a ref as main's entity
        at-branch (fn [k] (let [[_ _ a :as k0] (k0s k) v (at branch k0)] (if (and (ref? a) (number? v)) (to-into v) v)))
        name-of (fn [e] (let [n (named branch e)] (if (= n e) (named main e) n)))
        held (into {} (map (juxt :key identity)) (entries branch))
        decided? (fn [k0 i] (when-let [en (held k0)] (and (:resolved? en) (= i (:theirs en)))))
        place (:place-attrs (pdb/index))
        judged (for [[_ _ a :as k] (sort-by (fn [[_ e a]] [e a]) (distinct (map k-of fs)))
                     :let [k0 (k0s k) b (at base k) i (at main k) f (at-branch k)]
                     ;; a place ({:seon.merge/place true}) main holds is main's: no conflict (one form defined on two branches)
                     :when (and (not= b i) (not= b f) (not= i f) (not (and (place a) (some? i))))]
                 [(decided? k0 i) (cond-> {:key k0 :entity (name-of (second k0))}
                                    (some? b) (assoc :base b) (some? f) (assoc :ours f) (some? i) (assoc :theirs i))])
        conflicts (vec (keep (fn [[d c]] (when-not d c)) judged))]
    (if (seq conflicts)
      {:conflicts conflicts}
      ;; clean: no key conflicts now, so every entry the branch holds is settled, one decided by the branch or one this merge no longer
      ;; finds a conflict at (a derived key's, or one an earlier merge left at a key only one side changed); none stays on the merged value
      {:settled (set (keys held))
       :settle-ops (vec (for [e (entry-eids branch)] [:db/retractEntity e]))}))))

(defn carry
  "From's own change since base as transactions replayable onto into-db (`mapped`: its entities as into-db's of the same identity), and
  the entities it re-identifies [[from-id into-id]]: what a merge of main into a branch replays onto main's head, the branch's facts,
  O(d) for d what the branch changed (seon.writer/merge-main-into). See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] [:map [:txs [:vector [:map [:source :int] [:ops :seon.db/tx-data]]]] [:remapped [:set [:tuple :int :int]]]]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map] [:map [:txs [:vector [:map [:source :int] [:ops :seon.db/tx-data]]]] [:remapped [:set [:tuple :int :int]]]]]]}
  ([base from into-db] (carry base from into-db (mapped base from into-db)))
  ([base _from into-db {:keys [fs op remapped declarations]}]
   (let [;; an entity into-db deleted whole since base takes from's facts of it with it, as decide replays a whole delete
         deleted? (memoize #(deleted-by? base into-db %))
         ;; a place into-db holds stays into-db's ({:seon.merge/place true}, as decide judges it): from's is not carried
         place (:place-attrs (pdb/index))
         placed? (fn [{:keys [e a]}] (and (place a) (some? (first (d/datoms into-db :eavt e a)))))]
     {:txs (by-tx (remove #(or (deleted? (:e %)) (placed? %)) fs) op) :remapped remapped :declarations declarations})))

(defn change-record
  "What moved from value a to value b at the keys touched ([e a]) and the entities touched whole (each read at every attribute), as
  datoms of b's transaction t: each fact a held that b does not, retracted, and each b holds that a did not, added; a key neither side
  touched is the same on both by construction, so this is the whole change, read at O(keys) seeks, never a diff of the two values;
  a transaction's own facts are left out, as `facts` leaves them.
  What a merge commit states as its change against the branch head it replaces (datahike's force-branch! :changes). See
  docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:sequential [:tuple :int :keyword]] [:sequential :int] :int] [:vector :any]]}
  [a b keys whole t]
  (let [at (fn [db e attr] (into #{} (map (juxt :a :v)) (if attr (d/datoms db :eavt e attr) (d/datoms db :eavt e))))
        moved (fn [e attr] (let [x (at a e attr) y (at b e attr)]
                             (concat (for [[aa v] x :when (not (y [aa v]))] (datahike.datom/datom e aa v t false))
                                     (for [[aa v] y :when (not (x [aa v]))] (datahike.datom/datom e aa v t true)))))]
    ;; a transaction's own facts are no facts of the merge (`facts`): never stated as its change
    (let [tx? (fn [dt] (or (seq (d/datoms a :eavt (:e dt) :db/txInstant)) (seq (d/datoms b :eavt (:e dt) :db/txInstant))))]
      (vec (distinct (remove tx? (concat (mapcat (fn [[e attr]] (moved e attr)) (distinct keys)) (mapcat #(moved % nil) (distinct whole)))))))))
(defn mutations
  "What the forms a branch adds (ours: its changes since base) do that program code does
  not: call clojure.core/intern, alter-var-root, eval or load-string, or define inside a
  function body; from the compiler's resolved references, never text. One finding per
  form and call, naming the definitions the form makes. A namespace imported from the
  platform's source (it has an :ns/file on db) is not judged."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:vector [:map [:form :string] [:ns :symbol] [:defines [:vector :any]]
                                                                     [:call {:optional true} :qualified-symbol]
                                                                     [:inline-def {:optional true} :qualified-symbol]]]]}
  [db ours]
  (vec (for [[[k id] [_ f]] (sort-by (comp sedn/encode key) ours)
             :when (and (= :form k) f (not (:ns/file (d/entity db [:ns/name (:ns f)]))))
             finding (concat (for [c (sort-by str (:banned f))] {:call c})
                             (for [v (sort-by str (:inline-defs f))] {:inline-def v}))]
         (merge {:form id :ns (:ns f) :defines (vec (sort-by sedn/encode (:defines f)))} finding))))

;; ---- unmerged entries (git's index stages 1, 2, 3), on the branch ------------------------

(defn- entry-eids
  "The branch's merge entries: entities with a key and the main t they were merged at
  (a run's conflict evidence on main shares the :seon.merge/* members, not :main-t)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :int]]}
  [db] (vec (d/q '[:find [?e ...] :where [?e :seon.merge/key] [?e :seon.merge/main-t]] db)))

(defn conflict-value
  "The conflict the :seon.merge/* members of m state, m a pulled entry (the inverse of
  `conflict-tx`): one decoder for a branch's entries and a run's evidence."
  {:malli/schema [:=> [:cat :seon.merge/entry-members] :seon.merge/conflict]}
  [m]
  (cond-> {:key (:seon.merge/key m)}
    (:seon.merge/base m) (assoc :base (:seon.merge/base m))
    (:seon.merge/ours m) (assoc :ours (:seon.merge/ours m))
    (:seon.merge/theirs m) (assoc :theirs (:seon.merge/theirs m))
    (:seon.merge/why m) (assoc :why (:seon.merge/why m))))

(defn entries
  "Every merge entry on db, as conflict values with :main-t and :resolved?."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.merge/entry-value]]}
  [db]
  (vec (sort-by (comp sedn/encode :key)
                (for [e (entry-eids db)
                      :let [m (d/pull db '[*] e)]]
                  (assoc (conflict-value m) :main-t (:seon.merge/main-t m) :resolved? (boolean (:seon.merge/resolved? m)))))))

(defn conflict-tx
  "A conflict as the members of one merge entity (no :db/id): for the branch's entries
  and a run's evidence alike."
  {:malli/schema [:=> [:cat :seon.merge/conflict] :seon.db/tx-map]}
  [{:keys [key base ours theirs why]}]
  ;; a value no store holds as plain data (a byte array) is stored as its length and digest, so a conflict is recorded, never refused
  (let [plain (fn [v] (if (bytes? v) (str "#bytes[" (alength ^bytes v) " " (Integer/toHexString (java.util.Arrays/hashCode ^bytes v)) "]") v))]
    (cond-> {:seon.merge/key key}
      why (assoc :seon.merge/why why)
      base (assoc :seon.merge/base (plain base))
      ours (assoc :seon.merge/ours (plain ours))
      theirs (assoc :seon.merge/theirs (plain theirs)))))

(defn unmerged-tx
  "The branch's unmerged entries for conflicts, against main at t main-t: an earlier entry
  for the same key is replaced."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.merge/conflict] :int] :seon.db/tx-data]}
  [db conflicts main-t]
  (let [ks (set (map :key conflicts))]
    (into (vec (for [e (entry-eids db)
                     :when (ks (:seon.merge/key (d/entity db e)))]
                 [:db/retractEntity e]))
          (for [c conflicts] (assoc (conflict-tx c) :seon.merge/main-t main-t :seon.merge/resolved? false)))))

(defn unmerged
  "The entries of db still unmerged: not resolved, and the branch's content for the key
  still equal to ours (a re-defn resolves by itself). Reads the program at the entries'
  keys only (program-of)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.merge/entry-value]]}
  [db]
  (let [es (entries db)
        ;; a datum's branch content is read where it is, one seek; program-of holds no datums (they were never shown: a datum
        ;; conflict refused the merge while the agent saw nothing to settle, tests' pilot branch 2026-11-15)
        prog (program-of db (into [] (comp (map :key) (remove #(= :datum (first %)))) es) #{})
        now (fn [k] (if (= :datum (first k)) (held-at db k) (get prog k)))]
    (filterv #(and (not (:resolved? %)) (same? (:key %) (:ours %) (now (:key %)))) es)))

(defn content-tx
  "The facts that make the entity of stated key k on db hold content c (a side of an unmerged entry, as stated-content reads it): each
  stated attribute set to c's value, or retracted when c lacks it. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key [:maybe :map]] :seon.db/tx-data]}
  [db [kind v] c]
  (let [{:keys [id stated]} (stated-keys kind)
        ref [id v]
        many (get-in db [:rschema :db.cardinality/many] #{})]
    (vec (mapcat (fn [[a ck]]
                   (let [held (map :v (d/datoms db :eavt (pdb/eid db id v) a)) want (get c ck)]
                     (if (many a)
                       (concat (for [x held :when (not (contains? (set want) x))] [:db/retract ref a x])
                               (for [x want :when (not (contains? (set held) x))] [:db/add ref a x]))
                       (cond (some? want) (when (not= [want] held) [[:db/add ref a want]])
                             (seq held) [[:db/retract ref a (first held)]]))))
                 stated))))

(defn side-tx
  "The facts that make db hold side c of the unmerged entry for key k: a datum's value written ([:datum e a]: [:db/add e a c], or
  each value of e's a retracted when c is nil; [:datum e a v]: v added when c holds it, retracted when not), any other key's content
  written (`content-tx`). What my.branch/resolve! writes for :theirs or :base. One transaction, the datums of one key: O(1) a datum,
  the stated attributes a key. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key :any] :seon.db/tx-data]}
  [db k c]
  (if (= :datum (first k))
    (let [[_ e a v] k]
      (cond (= 4 (count k)) [[(if c :db/add :db/retract) e a v]]
            (some? c) [[:db/add e a c]]
            :else (mapv (fn [d] [:db/retract e a (:v d)]) (d/datoms db :eavt e a))))
    (content-tx db k c)))

(defn resolve-tx
  "Mark the entry for key resolved (git add): the branch keeps what it holds now."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key] :seon.db/tx-data]}
  [db k]
  (vec (for [e (entry-eids db)
             :when (= k (:seon.merge/key (d/entity db e)))]
         [:db/add e :seon.merge/resolved? true])))

(defn dangling
  "The references db-to's program leaves naming nothing, as {:ref :form}, sorted: each live form of db-to whose recorded references
  (:form/refs, the compiler's) name a var db-from defines and db-to does not (a definition the merge removes that a form still calls),
  and each form db-to loads that db-from did not hold live naming a var of another namespace db-to holds that db-to does not define
  (a call the merge adds to a definition already gone: a branch calling f merged after another removed it; Jolt compiles x/f with no
  var as a lookup at each call, so only the call fails). A ref into a namespace db-to does not hold (a library's) is no program's to
  judge; one into the form's own namespace that names nothing fails at its compile, which the load refuses (the analyzer also
  records a class name so: ns/ExceptionInfo); and a ref the form only quotes is data, no call ((resolve 'x/gone) proving a deletion). What a merge would leave on main, whichever side the call is on; read from the
  facts, never by loading. Costs the diff (seon.program/diff-plan), one seek a removed var and two a ref of a form it loads. See
  docs/merging.md#a-merge-leaves-no-name-unbound."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector [:map [:ref :qualified-symbol] [:form :string]]]]}
  [db-from db-to]
  (let [{:keys [remove load]} (program/diff-plan db-from db-to)
        unbound? (fn [ns v] (and (qualified-symbol? v) (not= (namespace v) (some-> ns str))
                                 (some? (d/entity db-to [:ns/name (symbol (namespace v))]))
                                 (nil? (:var/form (d/entity db-to [:var/name v])))))]
    (vec (sort-by (juxt (comp str :ref) :form)
                  (distinct
                    (concat (for [[kind v] remove :when (= :var kind)
                                  dt (d/datoms db-to :avet :form/refs v) :when (program/live? db-to (:e dt))]
                              {:ref v :form (:form/id (d/entity db-to (:e dt)))})
                            (for [{:keys [id ns refs quoted]} load :let [quoted (set quoted)] v refs :when (and (not (quoted v)) (unbound? ns v))]
                              {:ref v :form id})))))))

(defn merge-record
  "What a merge of main into a branch moved for the branch, as datoms of merged's last transaction: main's change since the base
  ([gone added], changed-datoms) at every key the branch never touched, as main recorded it, and the change between head and merged
  read on both sides (`change-record`) at the keys it did touch and the entities it re-identified, deleted or settled whole. Exact by
  net presence (seon.merge-test/a-merge-states-what-main-moved-for-the-branch); costs main's records once and O(what the branch
  touched) seeks, never both values whole. A transaction's own facts are left out. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:tuple [:sequential :any] [:sequential :any]] [:sequential [:tuple :int :keyword]] [:sequential :int]] [:vector :any]]}
  [head merged [gone added] touched whole]
  (let [t (:max-tx merged) touched (set touched) whole-set (set whole) txe (into #{} (map :tx) (concat gone added))]
    (into (change-record head merged (vec touched) (vec whole) t)
          (for [[added? dts] [[false gone] [true added]] dt dts
                :when (and (keyword? (:a dt)) (not (txe (:e dt))) (not (touched [(:e dt) (:a dt)])) (not (whole-set (:e dt))))]
            (datahike.datom/datom (:e dt) (:a dt) (:v dt) t added?)))))
