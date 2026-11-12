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
    ({:file path :text text :mode mode}; seon.source);

  `three-way` compares base, ours and theirs by program key: what a branch's work changed, whether
  main moved under it (the writer's behind), and the dashboard. What a merge writes is the one merge
  at the end of this namespace (`plan`): the source's transactions replayed whole when the two
  sides' changes do not conflict. Every function here but `apply-groups!` is pure over db values."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [datahike.datom :as dd] [datahike.writer :as dw] [clojure.string :as str] [clojure.set] [seon.db :as pdb] [seon.program :as program] [seon.edn :as sedn] [seon.config :as cfg]))

(def carried-platform-attrs
  "The platform attributes (seon.*) a branch carries: the task content an agent may improve
  (my.task/update!: its plan, its instructions, the proofs a design registers, and a stall task's
  cause), carried onto main at its run's end (seon.task/carry-tx). Every other seon.* attribute is a platform record (root's boot and
  quiescence, recorded errors, runs, observations) written where it happened and never
  carried, so main writing one never puts a branch behind."
  #{:seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/cause})

(def ^:private stated-keys
  "The program keys one entity's stated attributes hold, by key kind, as data: :id the identity naming the entity, :stated each attribute its content
  reads and the content key it takes, :needs a content key without which the entity is no program (a scratch schema entry has no file). A section's stated attributes are what a page's text states; the rest (lead, references, names,
  what it read) the load derives from these, so a change shows in these and a merge reads only them (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  {:section {:id :seon.doc/address
             :stated {:seon.doc/address :seon.doc/address :seon.doc/heading :seon.doc/heading :seon.doc/text :seon.doc/text :seon.doc/parent :seon.doc/parent}}
   :schema {:id :schema/key :stated {:schema/form :form :schema/file :file} :needs :file}
   :config {:id :config/key :stated {:config/form :form :config/file :file} :needs :file}
   :script {:id :file/path :kind-of #'cfg/file-kind :stated {:file/path :file :file/text :text :file/mode :mode}}})

(defn- bears?
  "Whether the entity named v bears key kind, under its row of stated-keys: an identity several kinds share (a file's path) names the kind by the row's :kind-of. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :keyword :map :any] :boolean]}
  [kind {:keys [kind-of]} v] (or (nil? kind-of) (= kind (kind-of v))))

(def ^:private stated-attr?
  "The attributes stated-keys' contents read (docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program)."
  (into #{} (mapcat (comp keys :stated)) (vals stated-keys)))

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
  "The program of db as {key content}: its live forms, the definitions pointing to them and the stated entities. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.merge/program]}
  [db]
  (let [forms (program/live-forms db)
        defs (for [f forms k (:defines f)
                   :let [e (program/def-eid db k)]
                   :when (and e (some #(= (:e f) (:db/id (get (d/entity db e) %))) (vals program/pointer)))]
               [k (def-content db k e f)])
        stated (for [[kind row] stated-keys dt (d/datoms db :aevt (:id row)) :when (bears? kind row (:v dt)) :let [c (stated-content db row (:e dt))] :when c] [[kind (:v dt)] c])]
    (into {} (concat (for [f forms] [[:form (:id f)] (dissoc f :e :id)]) defs stated))))

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

(defn three-way
  "git's three-way merge of program maps: :ours and :theirs (each side's changes since
  base), :apply (keys to set to theirs on ours) and :conflicts (both sides changed a key
  differently), each with the sides that hold the key (:base, :ours, :theirs; one absent is a side
  that never had it or deleted it, which is all a conflict's name would say). Keys are sorted, so the answer is a value."
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
                       (cond-> {:key k}
                         b (assoc :base b) o (assoc :ours o) t (assoc :theirs t))))}))

;; ---- the program where it changed: cost follows the change, not the program ------------

(defn- entity-keys
  "The program keys entity e bears on db. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db [:set :keyword] :int] [:set :seon.merge/program-key]]}
  [db many e]
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
                  (for [[kind {:keys [id] :as row}] stated-keys :let [v (get x id)] :when (and (some? v) (bears? kind row v))] [kind v])))))

(defn- key-content
  "Key k's content on db, as `program` holds it, or nil. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key] :any]}
  [db [kind a :as k]]
  (if-let [stated (stated-keys kind)]
    (some->> (pdb/eid db (:id stated) a) (stated-content db stated))
    (case kind
      :form (when-let [e (pdb/eid db :form/id a)]
              (when (program/live? db e) (dissoc (program/form-row db e) :e :id :source)))
      (:var :method :impl :ns)
      (when-let [e (program/def-eid db k)]
        (when-let [f (:db/id (get (d/entity db e) (program/pointer kind)))]
          (let [row (program/form-row db f)]
            (when (and (program/live? db f) (contains? (:defines row) k)) (def-content db k e row))))))))

(defn program-of
  "db's program at keys ks. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.merge/program-key]] :seon.merge/program]}
  [db ks]
  (into {} (for [k ks :let [c (key-content db k)] :when (some? c)] [k c])))

(def ^:private content-attr?
  "Is a an attribute a key's content reads (a plain predicate, asked of every attribute). See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  (fn [a] (boolean (and (keyword? a) (not= :db/txInstant a) (or (program/attr-namespaces (namespace a)) (stated-attr? a))))))

(defn touched-by
  "The program keys whose content may differ between base and db, given moved, the datoms that differ. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.merge/moved] [:set :seon.merge/program-key]]}
  [base db moved]
  (into #{} (for [e (into #{} (comp (filter (comp content-attr? :a)) (map :e)) moved)
                  side [base db]
                  k (entity-keys side (get-in side [:rschema :db.cardinality/many] #{}) e)]
              k)))

(defn content-datoms
  "The datoms that differ between base and db in the attributes a key's content reads, or, given diff, those among it. See docs/cost.md#a-diff-per-attribute-costs-a-descent-each."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.host/datom]]
                  [:=> [:cat :seon.host/db :seon.host/db [:sequential :seon.host/datom]] [:vector :seon.host/datom]]]}
  ([base db]
   (into [] (mapcat (fn [a] (apply concat (d/datoms-diff base db :aevt [a]))))
         (filter content-attr? (set (concat (keys (:schema base)) (keys (:schema db)))))))
  ([base db diff] (into [] (filter (comp content-attr? :a)) diff)))

(defn moves-content?
  "Whether a commit's resolved ops move main's content. See docs/storage.md#mains-content-mark."
  {:malli/schema [:=> [:cat :seon.db/resolved-tx] :boolean]}
  [ops]
  (boolean (some (fn [[_ _ a]] (or (content-attr? a) (contains? carried-platform-attrs a))) ops)))

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
  (d/commit-id (if (and held (or (same-content? held main) (empty? (content-datoms held main)))) held main)))

(defn touched-keys
  "Every program key whose content may differ between base and any of dbs. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.host/db]] [:set :seon.merge/program-key]]}
  [base dbs]
  (into #{} (mapcat (fn [db] (touched-by base db (content-datoms base db)))) dbs))

(defn delta-three-way
  "`three-way` of base, ours and theirs read only where they differ. See docs/storage.md#merges-read-what-changed."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] :seon.merge/result]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/moved] :seon.merge/result]]}
  ([base ours theirs] (delta-three-way base ours theirs (content-datoms base ours)))
  ([base ours theirs ours-moved]
   (let [ks (vec (into (touched-by base ours ours-moved) (touched-keys base [theirs])))]
     (three-way (program-of base ks) (program-of ours ks) (program-of theirs ks)))))

(defn readiness
  "Whether branch ours can merge into main theirs now: what main moved since base and which of those the branch changed too. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] :seon.merge/readiness]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :seon.merge/moved] :seon.merge/readiness]]}
  ([base ours theirs] (readiness base ours theirs (content-datoms base ours)))
  ([base ours theirs ours-moved]
   (let [r (delta-three-way base ours theirs ours-moved)]
     {:main-moved (vec (sort-by str (keys (:theirs r)))) :conflicts (vec (sort-by str (map :key (:conflicts r))))})))

(defn delta-changes
  "`changes` from db from to db to, read only where they differ. See docs/storage.md#merges-read-what-changed."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] :seon.merge/changes]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/moved] :seon.merge/changes]]}
  ([from to] (delta-changes from to (content-datoms from to)))
  ([from to moved]
   (let [ks (vec (touched-by from to moved))]
     (changes (program-of from ks) (program-of to ks)))))

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

(defn dangling
  "The references db-to's program leaves naming nothing: each live form of db-to whose recorded references (:form/refs, the
  compiler's) name a var db-from defines and db-to does not, as {:ref :form}, sorted. What a merge that removes a definition
  another form still calls would leave on main; read from the facts, never by loading. Costs the diff (seon.program/diff-plan)
  and one seek a removed var. See docs/merging.md#a-merge-leaves-no-name-unbound."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector [:map [:ref :qualified-symbol] [:form :string]]]]}
  [db-from db-to]
  (vec (sort-by (juxt (comp str :ref) :form)
                (for [[kind v] (:remove (program/diff-plan db-from db-to)) :when (= :var kind)
                      dt (d/datoms db-to :avet :form/refs v) :when (program/live? db-to (:e dt))]
                  {:ref v :form (:form/id (d/entity db-to (:e dt)))}))))

;; ---- the one merge (task seon.merge/a-merge-replays-the-sources-net-change; docs/decisions.md, 'A merge replays the source's
;; transactions on the branch that receives it') ----

(defn net-change
  "What db changed since base by entity id, {:keys {[e a] or [e a v] now} :base {key base's value}}. See docs/agent.md#merging."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:map [:keys :map] [:base :map]]]}
  [base db]
  (let [many? (fn [a] (= :db.cardinality/many (get-in (:schema db) [a :db/cardinality])))
        [gone added] (d/datoms-diff base db :eavt [])
        eav (fn [dt] [(:e dt) (:a dt) (let [v (:v dt)] (if (bytes? v) (vec v) v))])
        copy (clojure.set/intersection (into #{} (map eav) gone) (into #{} (map eav) added))
        step (fn [now]
               (fn [acc {:keys [e a v tx] :as dt}]
                 (cond (or (= e tx) (copy (eav dt))) acc
                       (many? a) (assoc-in acc [:keys [e a v]] (if (= ::gone now) ::gone true))
                       (= ::gone now) (-> acc (assoc-in [:keys [e a]] ::gone) (assoc-in [:base [e a]] v))
                       :else (assoc-in acc [:keys [e a]] v))))]
    ;; retractions first, so a value replaced (a retraction and an assertion of one key) ends as its new value
    (reduce (step ::now) (reduce (step ::gone) {:keys {} :base {}} gone) added)))

(defn conflict-key
  "Conflict c's key, [entity attribute] or [entity attribute value]. See docs/agent.md#merging."
  {:malli/schema [:=> [:cat :seon.merge/data-conflict] [:vector :any]]}
  [{:seon.merge/keys [entity attribute] :as c}]
  (if (contains? c :seon.merge/value) [entity attribute (:seon.merge/value c)] [entity attribute]))

(defn conflicts
  "Every key both sides' changes reach with different ends, as :seon.merge/data-conflict. See docs/agent.md#merging."
  {:malli/schema [:=> [:cat :map :map] [:vector :seon.merge/data-conflict]]}
  [ours theirs]
  (vec (for [[k o] (:keys ours) :let [t (get (:keys theirs) k ::same)] :when (and (not= ::same t) (not (dd/value= o t)))]
         (cond-> {:seon.merge/entity (k 0) :seon.merge/attribute (k 1) :seon.merge/ours o :seon.merge/theirs t}
           (= 3 (count k)) (assoc :seon.merge/value (k 2))
           (contains? (:base ours) k) (assoc :seon.merge/base (get (:base ours) k))))))

(defn replay
  "The source's transactions since base, each whole as a group, those the branch skip wrote left out. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword]] [:vector [:map [:tx :int] [:meta :map] [:ops [:vector :any]]]]]}
  [base src skip]
  (let [wrote (second (d/datoms-diff (d/history base) (d/history src) :eavt []))
        by-tx (reduce (fn [m {:keys [e a v tx added]}] (if (= e tx) m (update m tx (fnil conj []) [(if added :db/add :db/retract) e a v])))
                      (sorted-map) wrote)
        meta (fn [t] (into {} (for [dt (d/datoms src :eavt t) :when (not= :db/txInstant (:a dt))] [(:a dt) (:v dt)])))]
    (vec (for [[t ops] by-tx :let [m (meta t)] :when (not= skip (:seon.tx/branch m))]
           ;; retractions first: a value replaced in one transaction is retracted, then asserted
           {:tx t :meta m :ops (vec (sort-by #(if (= :db/retract (first %)) 0 1) ops))}))))

(defn- written
  "Every key the groups write, with src's end at it. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :map]] :map]}
  [src groups]
  (let [many? (fn [a] (= :db.cardinality/many (get-in (:schema src) [a :db/cardinality])))]
    (into {} (for [g groups [_ e a v] (:ops g)]
               (if (many? a) [[e a v] (if (seq (d/datoms src :eavt e a v)) true ::gone)] [[e a] (or (:v (first (d/datoms src :eavt e a))) ::gone)])))))

(defn plan
  "What merging src into onto does, decided before anything is written: {:conflicts} or {:groups}. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] :map] [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map] :map]]}
  ([base onto src] (plan base onto src {}))
  ([base onto src resolutions]
   (let [groups (replay base src (pdb/main-branch (:config onto)))
         cs (vec (remove #(contains? resolutions (conflict-key %)) (conflicts (net-change base onto) {:keys (written src groups)})))]
     (if (seq cs) {:conflicts cs} {:groups groups}))))

(defn refusal
  "A merge's refusal as a typed error: its plan's conflicts, or a replayed transaction the write check refused. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:function [:=> [:cat :map] [:maybe :seon.merge/conflict-refused]] [:=> [:cat :int :any] :seon.merge/replay-refused]]}
  ([p]
   (when (seq (:conflicts p))
     (contract/error :seon.merge/conflict-refused #'seon.merge/refusal
                     (str "the two sides changed " (count (:conflicts p)) " key(s) to different ends since their base: "
                          (str/join "; " (map (comp sedn/encode conflict-key) (:conflicts p))))
                     {:seon.merge/conflicts (:conflicts p)})))
  ([source-tx exception]
   (let [err (or (when (:seon.error/type (ex-data exception)) (ex-data exception))
                 (contract/error :seon.writer/refused #'seon.merge/refusal (or (ex-message exception) (str exception)) (or (ex-data exception) {})))]
     (contract/error :seon.merge/replay-refused #'seon.merge/refusal
                     (str "the receiving branch's write check refuses the source's transaction " source-tx ": " (:seon.error/message err)
                          ": it moved since the base; merge the other way and again")
                     {:seon.merge/refused (assoc err :db/id source-tx)}))))

(defn apply-groups!
  "Groups applied on conn, each its own transaction through the checked write, not committed. See docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db :map [:sequential :map]] [:map [:reps [:vector :map]] [:applied [:vector :int]]]]}
  [conn db meta0 groups]
  (reduce (fn [acc g]
            (let [rep (dw/apply! conn {:tx-data (:ops g) :tx-meta (merge meta0 (select-keys (:meta g) [:seon.tx/branch :seon.tx/eval :seon.tx/merged]))})]
              (if (map? rep)
                (-> acc (assoc :db (:db-after rep)) (update :reps conj rep) (update :applied conj (:tx g)))
                (reduced (assoc acc :refused {:tx (:tx g) :exception rep})))))
          {:db db :reps [] :applied []} groups))
