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
    definition: the form that last made it ({:form id}), and a var's kind, contract and doc.
    Conflicts are per definition: two branches that redefine one var differently conflict
    there, whatever else their forms carry;
  - `[:schema k]` -> a schema entry of a resource ({:form text :file path}; seon.source
    imports them, my.schema/register! changes them); an entry with no resource (a scratch
    key) is no program;
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
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [seon.db :as pdb] [seon.program :as program] [seon.edn :as sedn]))

(def carried-platform-attrs
  "The platform attributes (seon.*) a branch carries: the task content an agent may improve
  (my.task/update!: its plan, its instructions, the proofs a design registers, and a stall task's
  cause), carried onto main at its run's end (seon.task/carry-tx). Every other seon.* attribute is a platform record (root's boot and
  quiescence, recorded errors, runs, observations) written where it happened and never
  carried, so main writing one never puts a branch behind."
  #{:seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/cause})

(def ^:private section-stated
  "The attributes a page's text states of a section: its address, heading, own text and parent. The rest of a
  section (lead, references, names, dead links, what it read) the load derives from these, so a change shows
  in these four and a merge reads only them (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  #{:seon.doc/address :seon.doc/heading :seon.doc/text :seon.doc/parent})

(defn- platform-record? {:malli/schema [:=> [:cat :keyword] :boolean]} [k]
  (let [n (namespace k)]
    (and (some? n) (or (= "seon" n) (str/starts-with? n "seon.")) (not (carried-platform-attrs k)))))

(defn- data-attr?
  "Whether an attribute of db is data that data-attrs (a set, or :all) names, as a predicate:
  the set itself, or for :all one that asks of an attribute alone whether db's own schema entry
  gives it a value type, outside the program's namespaces and the platform's records. A merge
  asks it of the attributes of the datoms that differ, so it costs those, never the schema:
  d/schema rebuilt the whole schema map on every call, once per db per read."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] ifn?]}
  [db data-attrs]
  (if (= :all data-attrs)
    (let [schema (:schema db)]
      (fn [a] (boolean (and (keyword? a) (:db/valueType (get schema a))
                            (not (or (program/attr-namespaces (namespace a)) (section-stated a))) (not (platform-record? a))))))
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
      :var (cond-> (assoc c :kind (:var/kind x))
             (:var/contract x) (assoc :contract (:var/contract x))
             (:var/doc x) (assoc :doc (:var/doc x)))
      :ns (cond-> c (:ns/file x) (assoc :file (:ns/file x)))
      c)))

(defn- section-content
  "Section entity e's content on db: what its page states of it (section-stated), refs as entity ids (issued
  once across the store, so they name the same sections on every branch). See
  docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db e]
  (let [many (get-in db [:rschema :db.cardinality/many] #{})]
    (reduce (fn [m dt] (if (section-stated (:a dt))
                         (if (many (:a dt)) (update m (:a dt) (fnil conj #{}) (:v dt)) (assoc m (:a dt) (:v dt)))
                         m))
            (sorted-map) (d/datoms db :eavt e))))

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
        schemas (for [dt (d/datoms db :aevt :schema/file) :let [x (d/entity db (:e dt))]]
                  [[:schema (:schema/key x)] {:form (:schema/form x) :file (:v dt)}])
        sections (for [dt (d/datoms db :aevt :seon.doc/address)] [[:section (:v dt)] (section-content db (:e dt))])]
    (into {} (concat (for [f forms] [[:form (:id f)] (dissoc f :e :id)]) defs schemas sections data))))

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
    (vec (sort-by str (for [[[k sym] [_ ov]] ours :when (and (= :var k) (= :fn (:kind ov)) (nil? (:contract ov)))] sym)))
    []))

(defn- conflict-kind
  {:malli/schema [:=> [:cat :any :any :any] :seon.merge/conflict-kind]}
  [b o t]
  (cond (nil? b) :both-added (nil? o) :deleted-by-us (nil? t) :deleted-by-them :else :both-modified))

(defn three-way
  "git's three-way merge of program maps: :ours and :theirs (each side's changes since
  base), :apply (keys to set to theirs on ours) and :conflicts (both sides changed a key
  differently). Keys are sorted, so the answer is a value."
  {:malli/schema [:=> [:cat :seon.merge/program :seon.merge/program :seon.merge/program] :seon.merge/result]}
  [base ours theirs]
  (let [oc (changes base ours)
        tc (changes base theirs)
        ks (sort-by sedn/encode (keys tc))]
    {:ours oc
     :theirs tc
     :apply (vec (for [k ks :let [b (get base k) o (get ours k) t (get theirs k)] :when (and (not (same? k o t)) (same? k o b))] k))
     :conflicts (vec (for [k ks
                           :let [b (get base k) o (get ours k) t (get theirs k)]
                           :when (and (not (same? k o t)) (not (same? k o b)))]
                       (cond-> {:key k :kind (conflict-kind b o t)}
                         b (assoc :base b) o (assoc :ours o) t (assoc :theirs t))))}))

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
                  (when-let [k (:schema/key x)] [[:schema k]])
                  (when-let [a (:seon.doc/address x)] [[:section a]])
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
  (case kind
    :form (when-let [e (pdb/eid db :form/id a)]
            (when (program/live? db e) (dissoc (program/form-row db e) :e :id :source)))
    (:var :method :impl :ns)
    (when-let [e (program/def-eid db k)]
      (when-let [f (:db/id (get (d/entity db e) (program/pointer kind)))]
        (let [row (program/form-row db f)]
          (when (and (program/live? db f) (contains? (:defines row) k)) (def-content db k e row)))))
    :schema (when-let [e (pdb/eid db :schema/key a)]
              (let [x (d/entity db e)] (when (:schema/file x) {:form (:schema/form x) :file (:schema/file x)})))
    :section (some->> (pdb/eid db :seon.doc/address a) (section-content db))
    :datum (when (data-attrs b)
             (if (= 4 (count k))
               (when (seq (d/datoms db :eavt a b c)) true)
               (:v (first (d/datoms db :eavt a b)))))))

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
  (fn [a] (and (keyword? a) (boolean (or (and (not= :db/txInstant a) (or (program/attr-namespaces (namespace a)) (section-stated a))) (das-b a) (das-d a))))))

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
                         (fn [a] (or (program/attr-namespaces (namespace a)) (section-stated a) (das a)))]))
        b (read base) d (read db)
        content-attr? (content-attr (nth b 2) (nth d 2))
        content? (fn [{:keys [a]}] (content-attr? a))]
    (into #{} (for [e (into #{} (comp (filter content?) (map :e)) moved)
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

(defn touched-keys
  "Every program key whose content may differ between base and any of dbs (`touched-by`, over
  the datoms that differ in the attributes a key's content reads: `content-datoms`). Cost: those
  datoms and one descent per such attribute, not the program and not what else main wrote."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.host/db] :seon.merge/data-attrs] [:set :seon.merge/program-key]]}
  [base dbs data-attrs]
  (into #{} (mapcat (fn [db] (touched-by base db data-attrs (content-datoms base db data-attrs)))) dbs))

(defn delta-three-way
  "`three-way` of base, ours and theirs read only where they differ (touched-keys): the
  same answer, at the cost of what changed on each side since base, not of the program
  (task seon.merge/program-cost-follows-what-changed). With ours-moved, the datoms that differ
  between base and ours (`content-datoms`, which a caller already read), ours is not diffed again."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/data-attrs] :seon.merge/result]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/data-attrs :seon.merge/moved] :seon.merge/result]]}
  ([base ours theirs data-attrs] (delta-three-way base ours theirs data-attrs (content-datoms base ours data-attrs)))
  ([base ours theirs data-attrs ours-moved]
   (let [ks (vec (into (touched-by base ours data-attrs ours-moved) (touched-keys base [theirs] data-attrs)))]
     (three-way (program-of base ks data-attrs) (program-of ours ks data-attrs) (program-of theirs ks data-attrs)))))

(defn readiness
  "Whether branch db ours can merge into main theirs now, as a merge of main would find it, read
  only: what main changed since base, their merge base (:main-moved, its program keys), and which
  of those the branch changed too (:conflicts, the keys a merge of main would leave unmerged).
  The three-way a merge runs (delta-three-way), so it costs what changed on each side since base.
  With ours-moved, the datoms that differ between base and ours (`content-datoms`), ours is not diffed again."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] :seon.merge/readiness]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/moved] :seon.merge/readiness]]}
  ([base ours theirs] (readiness base ours theirs (content-datoms base ours #{})))
  ([base ours theirs ours-moved]
   (let [r (delta-three-way base ours theirs #{} ours-moved)]
     {:main-moved (vec (sort-by str (keys (:theirs r)))) :conflicts (vec (sort-by str (map :key (:conflicts r))))})))

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
  seon.agent/branch-changes) carry to main: the vars it added, redefined and removed, and the
  schema keys it registered or changed; each sorted, an empty kind left out ({}: nothing). A var
  is changed when its definition's content is (its form's text, kind, contract or doc: def-content),
  and removed when no live form defines it. A schema registered with no file (a scratch one, my.schema) is no key of the program and never
  merges, so it is none of these."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:map-of [:enum :added :changed :removed :schemas] [:vector [:or :qualified-symbol :qualified-keyword]]]]}
  [ch]
  (let [kinds (for [[[kind x] [a b]] ch :when (#{:var :schema} kind)]
                [(cond (= :schema kind) :schemas (nil? a) :added (nil? b) :removed :else :changed) x])]
    (into {} (for [[k xs] (group-by first kinds)] [k (vec (sort-by str (map second xs)))]))))

(defn changed-vars
  "The vars changes ch add, redefine or remove (divergence's vars): what the branch changed."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:set :qualified-symbol]]}
  [ch] (into #{} (keep (fn [[kind x]] (when (= :var kind) x))) (keys ch)))

;; ---- verbatim merges: a merged datom is the same datom ------------------------------

(defn- key-entity
  "The entity bearing program key k on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key] [:maybe :int]]}
  [db [kind a :as k]]
  (case kind
    :form (pdb/eid db :form/id a)
    (:var :method :impl :ns) (program/def-eid db k)
    :schema (pdb/eid db :schema/key a)
    :section (pdb/eid db :seon.doc/address a)
    :datum a))

(defn carried-tx
  "The datoms src's changes since base carry for program keys ks, as they are (e, a, v and
  tx): the retractions src made of the keys' entities' datoms base holds, then the datoms
  src added to them, then the facts of the transactions that made them. Ids are the
  store's, issued once (docs/storage.md#ids), so on any other branch they name the same
  entities and transactions: a merge adds what the branch wrote, never a copy. Platform
  records ride on no key, so they stay where they were written. Datahike keeps a carried
  datom's own tx (datahike.db.transaction, transact-add and transact-retract-datom). A carried form's
  namespace entity is carried with it."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:sequential :seon.merge/program-key] :seon.merge/data-attrs] :seon.db/tx-data]}
  [base src ks data-attrs]
  (let [es (into #{} (for [k ks db [base src] :let [e (key-entity db k)] :when e] e))
        ;; a carried form names its namespace by ref: the namespace entity rides with it (a namespace made
        ;; without an (ns) form is no key of its own), or the form reads :ns nil where it lands
        es (into es (for [e es :let [n (:db/id (:form/ns (d/entity src e)))] :when n] n))
        das (data-attr? src data-attrs)
        carry? (fn [d] (and (es (:e d)) (let [a (:a d)] (or (das a) (program/attr-namespaces (namespace a)) (= "seon.doc" (namespace a))))))
        [gone added] (d/datoms-diff base src :eavt [])
        added (filterv carry? added)
        retracted (vec (for [d gone :when (carry? d)
                             :let [r (last (filter #(and (not (:added %)) (> (:tx %) (:tx d)))
                                                   (d/datoms (d/history src) :eavt (:e d) (:a d) (:v d))))]
                             :when r]
                         r))
        txs (into (sorted-set) (map :tx) (concat retracted added))]
    (-> []
        (into retracted)
        (into added)
        (into (for [t txs d (d/datoms src :eavt t)] d)))))

(defn- own-namespaces
  "tx, with a namespace the target already has under its own entity read there: the datoms of the
  source's entity for it are dropped and the carried forms' :form/ns refs name the target's (two
  branches that each made a namespace without an (ns) form hold it under two ids, and carrying
  both would give one :ns/name two entities)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.db/tx-data] :seon.db/tx-data]}
  [onto src tx]
  (let [mine (into {} (for [d tx :when (= :ns/name (:a d))
                            :let [t (pdb/eid onto :ns/name (:v d))] :when (and t (not= t (:e d)))]
                        [(:e d) t]))]
    (if (empty? mine)
      tx
      (vec (for [d tx :when (not (and (mine (:e d)) (not= :form/ns (:a d))))]
             (if (and (= :form/ns (:a d)) (:added d) (mine (:v d))) (d/datom (:e d) :form/ns (mine (:v d)) (:tx d)) d))))))

(defn attribute-declarations
  "The Datahike declarations, from the built registry, of the attributes the datoms of tx use
  that onto's schema lacks: what a branch cut before an attribute needs before main's datoms of it
  can land (Datahike refuses an attribute its schema does not define). Empty when onto holds
  them all. One pass over tx's attributes, then the registry's declarations only when one is
  lacking."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector :seon.schema/declaration]]}
  [onto tx]
  (let [have (d/schema onto)
        lacking (into #{} (comp (map :a) (filter #(and (keyword? %) (not (contains? have %))))) tx)]
    (if (empty? lacking)
      []
      (filterv #(contains? lacking (:db/ident %)) (program/schema-declarations onto contract/registry)))))

(defn merge-tx
  "The tx-data merging src's changes since base for program keys ks onto db onto: the
  Datahike declarations of the attributes its datoms use that onto lacks (attribute-declarations),
  then the datoms src wrote, as they are (carried-tx), and the declarations a merged schema
  entry's population needs on onto (as a registration installs them). An entity whose identity
  src retracted is retracted whole on onto, so no fact onto wrote of it (a test's results) stays
  on a nameless entity (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db [:sequential :seon.merge/program-key] :seon.merge/data-attrs] :seon.db/tx-data]}
  [onto base src ks data-attrs]
  (let [carried (own-namespaces onto src (carried-tx base src ks data-attrs))
        unique? #(= :db.unique/identity (get-in (d/schema src) [% :db/unique]))
        named (into #{} (keep #(when (and (:added %) (unique? (:a %))) (:e %))) carried)
        gone (into [] (comp (keep #(when (and (not (:added %)) (unique? (:a %)) (not (named (:e %)))) (:e %))) (distinct)
                            (map #(vector :db/retractEntity %)))
                   carried)
        tx (-> (attribute-declarations onto carried) (into carried) (into gone))]
    (if (some #(= :schema (first %)) ks)
      (into tx (program/schema-declarations onto (program/schema-registry (program/schema-changes (d/db-with onto tx)))))
      tx)))

;; ---- what program code does not do ----------------------------------------------------

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
  (cond-> {:key (:seon.merge/key m) :kind (:seon.merge/kind m)}
    (:seon.merge/base m) (assoc :base (:seon.merge/base m))
    (:seon.merge/ours m) (assoc :ours (:seon.merge/ours m))
    (:seon.merge/theirs m) (assoc :theirs (:seon.merge/theirs m))))

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
  {:malli/schema [:=> [:cat :seon.merge/conflict] :map]}
  [{:keys [key kind base ours theirs]}]
  (cond-> {:seon.merge/key key :seon.merge/kind kind}
    base (assoc :seon.merge/base base)
    ours (assoc :seon.merge/ours ours)
    theirs (assoc :seon.merge/theirs theirs)))

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
        prog (program-of db (mapv :key es) #{})]
    (filterv #(and (not (:resolved? %)) (same? (:key %) (:ours %) (get prog (:key %)))) es)))

(defn resolve-tx
  "Mark the entry for key resolved (git add): the branch keeps what it holds now."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key] :seon.db/tx-data]}
  [db k]
  (vec (for [e (entry-eids db)
             :when (= k (:seon.merge/key (d/entity db e)))]
         [:db/add e :seon.merge/resolved? true])))