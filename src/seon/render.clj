(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: notes/archive/option3-W1-render.md §1).

  A render is a plain function (value opts) -> its result, like a React component: opts are
  its props, all optional (:as, the room it is shown in, :inline, :block or :tile; :budget,
  its tokens; :handle, the form its cuts read the rest through), and a function that does not
  care about one ignores it (owner: \"It's all just functions and results\"). A render answers
  hiccup (serialized by seon.web.html, where a vector headed by a function is a call, expanded
  as Reagent does), and the page and the model read the same hiccup (notes/one-derivation-design-
  rev42.md §2). Selection, first match wins, and nothing else (DECISIONS, Renders attach to
  schemas; the UI and the context are renders, ported not reinvented):
    1. the value's own override key (:seon.render/html, in the map or its metadata): a render
       symbol, or already-rendered hiccup;
    2. the render its schema declares: a schema's property {:seon.render/html sym} in
       resources/seon/schemas/*.edn (seon.contract/forms), the
       schema then its parents ([:and p ...], [:merge p ...]). The value's schema is never
       guessed: the producer names it (opts :schema, or the value's metadata
       :seon.render/schema), an error map's is its :seon.error/type (else :seon.error/base),
       and a pulled entity's is the schema with a declared render holding its identity
       attribute;
    3. the one value printer (seon.print/print-value), the default render.
  A nested value renders by a call ([render v opts] in hiccup, or render's result)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print :as print] [seon.print.select :as select] [seon.config :as config]
            [clojure.string :as str] [datahike.api :as d] [seon.web.html :as h]))

(defn parents
  "The named schemas k's form extends ([:and p ...] or [:merge p ...])."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:vector :qualified-keyword]]}
  [k]
  (let [form (get contract/forms k)]
    (if (and (vector? form) (#{:and :merge} (first form)))
      (filterv #(and (qualified-keyword? %) (contains? contract/forms %)) (rest form))
      [])))

(defn declared
  "The render symbol schema k (or its nearest parent) declares, or nil."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:maybe :symbol]]}
  [k]
  (when k
    (or (:seon.render/html (contract/properties k))
        (some declared (parents k)))))

(defn- identity-attr?
  "Is attribute a an upsert key ([:and {:seon.db/identity true} ...])?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (:seon.db/identity (contract/properties a))))

(defn- map-keys
  "The keys a schema's [:map ...] form names (inline, or in its [:and ...] members)."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form]
  (cond (and (vector? form) (= :map (first form))) (vec (keep #(when (vector? %) (first %)) (rest form)))
        (and (vector? form) (#{:and :merge} (first form))) (vec (mapcat #(map-keys (if (keyword? %) (get contract/forms %) %)) (rest form)))
        :else []))

(def by-identity
  "{identity-attribute schema}: for each schema with a declared render, the upsert keys its
  map names: a pulled entity holding that key is a value of that schema. Derived once from
  the registry."
  (into {} (for [[k form] contract/forms
                 :when (declared k)
                 a (map-keys form) :when (identity-attr? a)]
             [a k])))

(defn schema-of
  "The named schema of v: hint (its producer's), the one its metadata names, an error map's
  variant (its registered :seon.error/type, else :seon.error/base), or the schema its
  identity attribute names."
  {:malli/schema [:=> [:cat :any [:maybe :qualified-keyword]] [:maybe :qualified-keyword]]}
  [v hint]
  (or hint (:seon.render/schema (meta v))
      (when (map? v)
        (if-let [t (:seon.error/type v)]
          (if (contains? contract/forms t) t :seon.error/base)
          (some by-identity (keys v))))))

(declare render-of own-key)

(defn- nested
  "The printer's :render: a nested value whose schema declares a render, as a call of that render
  inline ({:text, the value in one line; :html, the call}), else nil. Its render is looked up by
  render-of (seon.render/schema-of: one metadata read or one map lookup per key, never a
  validation)."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [v]
  (when-let [h (render-of v nil)]
    {:text (print/line v (config/value :seon.config.render/line-budget)) :html [h v {:as :inline}]}))

(defn- nested-html
  "The HTML printer's :render: nested's, and a value whose own :seon.render/html key holds hiccup
  (a page an agent built) as that hiccup, its text the hiccup as data in one line: one selection
  serves the page and the model, and the model reads the hiccup as the data it is."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [v]
  (let [own (own-key v :seon.render/html)]
    (if (and (some? own) (not (symbol? own)))
      {:text (print/line own (config/value :seon.config.render/line-budget)) :html own}
      (nested v))))

(defn- printer-opts
  "The printer's options for a render's opts: its budget (else :seon.config.render/value-budget),
  its handle, and its nested values' own renders (nested)."
  {:malli/schema [:=> [:cat :seon.render/opts] :map]}
  [opts] {:budget (or (:budget opts) (config/value :seon.config.render/value-budget)) :handle (:handle opts) :render nested})

(defn print-html
  "The one value printer as the default :html render: v's structural hiccup under opts' budget,
  or one line (seon.print/line) when shown :inline."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :any]}
  [v opts]
  (if (= :inline (:as opts))
    [:code {:class "seon-inline"} (print/line v (or (:budget opts) (config/value :seon.config.render/line-budget)) (:handle opts))]
    (let [r (print/print-value v (assoc (printer-opts opts) :outputs #{:hiccup} :render nested-html))]
      (or (:html r) [:span {:class "seon-print-refused"} (str (:seon.error/message r))]))))

(defn- own-key
  "v's own override for face: in the map, else in its metadata."
  {:malli/schema [:=> [:cat :any :keyword] :any]}
  [v k] (or (when (map? v) (get v k)) (get (meta v) k)))

(defn render-of
  "The render function v takes: its own override (a symbol), else its schema's declared one (opts :schema, else seon.render/schema-of: one metadata read or one map lookup
  per key, never a validation), else nil (the printer). nil also when v's own key holds an
  already rendered result."
  {:malli/schema [:=> [:cat :any [:maybe :qualified-keyword]] [:maybe :seon.host/fn]]}
  [v hint]
  (let [own (own-key v :seon.render/html)]
    (when-let [sym (if (symbol? own) own (when (nil? own) (declared (schema-of v hint))))]
      @(requiring-resolve sym))))

(defn html
  "v rendered: (render-fn v opts), the function its override or schema names, else the printer's
  (print-html); a value's own key holding hiccup answers that hiccup."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :any]}
  [v opts]
  (let [own (own-key v :seon.render/html)]
    (if (and (some? own) (not (symbol? own)))
      own
      ((or (render-of v (:schema opts)) print-html) v opts))))

;;; --- parts: a ctx as the page and the model are drawn from it --------------------------------

(defn- relevant-of
  "Entity value m as its schema s shows it: its declared relevant keys (:seon.render/keys), else
  every key, never its :db/id."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :map] :map]}
  [s m] (dissoc (if-let [ks (some-> s contract/properties :seon.render/keys)] (select-keys m ks) m) :db/id))

(defn render-ctx
  "The agent page's layout (the declared html render of :seon.render/render-ctx, which an agent
  overrides on its branch like any render, drawn through `html`): an agent's held record
  ({:tree c}, seon.ctx/build's: c is @ctx, the db's data only) as its parts, each a keyed call of a
  sub-render over its slice of ctx, in order, each in its region of the page (:region): :task, the
  task and what it works with, which the model reads too (seon.repl/page); :chat, the conversation;
  :tiles, each eval whose value has a render of its own, newest first. With what reads the db beside it (fs, seon.context/renders over the branch's db
  as of the record's t): [:task], the task, drawn by seon.render.ui/task-page as the task's data
  (seon.context/task-data over the db) less the vars it cites, compared in the memo by its task key;
  [:task :cites sym] for each var its plan cites (seon.context/cites), drawn by
  seon.render.ui/cited, compared by its own key, so a cited var's edit is that part's change
  alone; [:related], drawn by
  seon.render.ui/related (seon.context/related), compared by its related key; [:status], what the
  newest model turn observed, as (my.task/status) answers it (seon.render.ui/status: each proof
  with its failures, source and calls, the done rule's reasons, the turn of how many, what the
  merge will carry, the last reply); [:run], the newest run's relevant keys and the turns it took;
  [k] for each key the agent added to its ctx (any key outside its own :seon.agent/* attributes,
  its units and the environment), named by that key, which is its schema; then the conversation:
  [:eval id] per eval, [:turn id] per model turn of the run and [:message id] per message to it, in
  the order they were written (each member's first transaction, (:first-t fs)); an eval's value is
  drawn only once ctx holds it opened (:seon.eval/opened: the person opened it, a fact that reaches
  the branch by take!), else its one line; [:tile id] per eval whose value has a declared render,
  newest first. Nothing derived is
  computed here: a part that reads the db carries its key (:read) and the db its render draws on,
  and draws only when the memo misses (drawn). What a part leaves out is not data the agent acts
  on (ids, the turns' bases but the newest's observation, the agent's own counters)."
  {:malli/schema [:=> [:cat [:map [:tree :map]] [:map [:db :seon.host/db] [:cites :seon.host/fn] [:task-key :seon.host/fn] [:cited-key :seon.host/fn] [:related-key :seon.host/fn] [:first-t :seon.host/fn]]] [:vector :seon.render/part]]}
  [{c :tree} fs]
  (let [t-of #((:first-t fs) (:db/id %))
        run (last (sort-by t-of (vals (:runs c))))
        task (:task run)
        ;; the newest model turn and the one before it (its last reply)
        ts (vec (sort-by :seon.turn/n (filter #(= :model (:seon.turn/origin %)) (vals (:turns run)))))
        turn (peek ts)
        prev (get ts (- (count ts) 2))
        entry (fn [k e] {:key [k (or (:seon.eval/id e) (:seon.message/id e) (:seon.turn/id e))] :from [e] :region :chat
                         :opened (boolean (:seon.eval/opened e))
                         :value (if (= :turn k) (-> (relevant-of :seon.turn/turn-fact e) (dissoc :seon.turn/run) (update :seon.turn/basis select-keys [:max-turns])) (dissoc e :db/id :seon.eval/agent :seon.message/to))})
        ;; what the task's parts read, each key made once: related reads the plan's reference graph too
        tk (when task ((:task-key fs) task))
        cks (when task (into (array-map) (for [v ((:cites fs) task)] [v ((:cited-key fs) v)])))]
    (-> []
        (cond-> task (conj {:key [:task] :from [task] :value (relevant-of :seon.task/task-fact (dissoc task :turns :runs)) :region :task
                            :schema :seon.render/task-page :read tk :db (:db fs)})
                task (into (for [[v ck] cks]
                             {:key [:task :cites v] :from [task] :value {:var v} :region :task :schema :seon.render/cited :read ck :db (:db fs)}))
                task (conj {:key [:related] :from [task] :value {:task (:db/id task)} :region :task
                            :schema :seon.render/related :read [tk (vec (vals cks)) ((:related-key fs) (:db/id task))] :db (:db fs)})
                ;; what the newest turn observed, as (my.task/status) answers it: drawn over the turn's
                ;; recorded basis and the one before it (its last reply), both ctx's
                (and task turn) (conj {:key [:status] :from [task turn prev] :region :task :schema :seon.render/status :db (:db fs)
                                       :value (cond-> {:task (:seon.task/id task) :turn (:db/id turn)} prev (assoc :last (:db/id prev)))})
                run (conj {:key [:run] :from [run] :region :task :value (assoc (relevant-of :seon.run/run-fact run) :turns-taken (count (:turns run)))}))
        (into (for [k (sort-by str (keys c))
                    :when (and (qualified-keyword? k) (not (#{"seon.agent" "db"} (namespace k))))]
                {:key [k] :from [(get c k)] :value (get c k) :schema k :region :task}))
        ;; in the order written: each member's first transaction (first-t), then its key
        (into (map second) (sort-by first (concat (for [e (vals (:evals c))] [[(t-of e) (:seon.eval/id e)] (entry :eval e)])
                                                  (for [t (vals (:turns run))] [[(t-of t) (:seon.turn/id t)] (entry :turn t)])
                                                  (for [m (vals (:messages run))] [[(t-of m) (:seon.message/id m)] (entry :message m)]))))
        ;; the tiles: each eval whose value has a render of its own, newest first; plain data has none
        (into (for [e (sort-by (comp - #(or % 0) t-of) (vals (:evals c)))
                    :let [v (get-in e [:seon.eval/record :response :value])]
                    :when (and (some? v) (render-of v nil))]
                {:key [:tile (:seon.eval/id e)] :from [e] :value v :region :tiles})))))

(defn part-faces
  "Part p's faces, {:html hiccup :inline hiccup}: its hiccup by the render its value's schema
  declares, else the one printer's, which the page shows and the model reads alike; a part that
  reads the db (:db) is drawn on it. An eval of the conversation is its one line (:inline, :as
  :inline), and its html only when opened (:opened): nothing is drawn until someone looks."
  {:malli/schema [:=> [:cat :seon.render/part] :seon.render/faces]}
  [{:keys [value schema] :as p}]
  (if (= :eval (first (:key p)))
    (cond-> {:inline (html value {:as :inline :chat true :schema schema})}
      (:opened p) (assoc :html (html value {:schema schema})))
    {:html (html value (cond-> {:schema schema} (:db p) (assoc :db (:db p))))}))

(defn part-html
  "Part p as a page shows it, from its faces f: {:key :html :inline}, its html and its one line
  serialized."
  {:malli/schema [:=> [:cat :seon.render/part :seon.render/faces] :seon.render/part-html]}
  [p f] (cond-> {:key (:key p)}
          (:region p) (assoc :region (:region p))
          (:html f) (assoc :html (h/html (:html f)))
          (:inline f) (assoc :inline (h/html (:inline f)))))

(defn drawn
  "Part p's faces through memo {key [fs from faces read]}: the held faces when the functions that
  draw p (fs: its declared render as its var holds it now, and the printer) and what
  p was made from (its :from, the ctx members view read, else its value) are identical to those
  it was drawn with (a redefined render draws again; a member a transaction changed is a new
  object, ctx's patch leaving every other one as it was), else drawn now. Answers [memo' faces]."
  {:malli/schema [:=> [:cat :map :seon.render/part] [:tuple :map :seon.render/faces]]}
  [memo {:keys [key value schema from read opened] :as p}]
  (let [fs [(render-of value schema) @#'print/print-value]
        src (or from [value])
        [fs0 src0 out read0] (get memo key)]
    ;; a part that reads the db (its :read key) draws again when that key moves
    (if (and out (= (count src) (count src0)) (every? true? (map identical? src src0)) (every? true? (map identical? fs fs0)) (= [read opened] read0))
      [memo out]
      (let [out (part-faces p)] [(assoc memo key [fs src out [read opened]]) out]))))

;; ---- the walk: an entity and the units its schema declares (docs/render.md#the-walk) ----

(def entity-schemas
  "{attribute schema}: each attribute an entity schema ({:seon.db/attributes true}) names, to that
  schema. Derived once from the registry (as by-identity); an identity or ref attribute is named
  by one entity schema (seon.render-test/a-walk-is-its-entity-and-its-units)."
  (into {} (for [[k form] contract/forms :when (:seon.db/attributes (contract/properties k)) a (map-keys form)] [a k])))

(def ^:private identity-schemas
  "{identity-attribute schema}: the entity schemas by the upsert keys they name."
  (into {} (filter (comp identity-attr? key)) entity-schemas))

(defn entity-schema
  "The schema of pulled entity e: the entity schema naming one of its identity attributes, else
  the one naming via (the attribute it was reached by, read forward), else nil: no entity schema
  names it, and the one printer shows it."
  {:malli/schema [:=> [:cat :map [:maybe :keyword]] [:maybe :qualified-keyword]]}
  [e via] (or (some identity-schemas (keys e)) (get entity-schemas via)))

(defn- forward
  "Attribute a read forward: a reverse ref (:ns/_name, pull's syntax) as its own attribute (:ns/name)."
  {:malli/schema [:=> [:cat :keyword] :keyword]}
  [a] (if (str/starts-with? (name a) "_") (keyword (namespace a) (str/replace-first (name a) "_" "")) a))

(defn- relevant
  "Entity value m of schema, as a walk shows it: only the attributes its schema declares
  relevant (:seon.render/keys) when it declares them, else every one it holds. What is not
  relevant is never pulled into a view (DECISIONS, The context is data, made relevant at its
  producer; renders do not turn it into prose)."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :map] :map]}
  [schema m]
  (if-let [ks (some-> schema contract/properties :seon.render/keys)] (select-keys m ks) m))

(defn- one
  "Entity eid on db as a page lists it: its node (its relevant attributes, less its units) and one
  node per unit its entity schema declares (:seon.render/units, pull's attributes, a leading _ the
  reverse ref), one hop: a neighbour entity ({:kind :entity :eid}, its relevant attributes as
  :value, and :tx, the transaction of the datom that links it: the order it was written) or the
  entity's own value of a non-ref unit ({:kind :value}), each saying the attribute it came by
  (:from); nil when db holds none of it. With limit (a page's rows shown), a unit reached by a
  reverse ref (its rows) is pulled whole only for its newest limit entities; the older are listed
  from the ref's index alone (:seon.render/listed: their entity, transaction and unit's schema,
  nothing read of them), so a page counts them and costs the rows it shows."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? [:maybe pos-int?]] [:maybe [:tuple :seon.render/node [:vector :seon.render/node]]]]}
  [db eid limit]
  (when-let [own (d/pull db '[*] eid)]
    (let [schema (entity-schema own nil)
          units (:seon.render/units (contract/properties schema))
          types (d/schema db)
          ref? (fn [a] (or (not= a (forward a)) (= :db.type/ref (get-in types [a :db/valueType]))))
          ;; each ref unit's linking datoms, with the transaction that wrote each, newest first: a
          ;; reverse ref's from its index (one read for every row: a ref is in :avet)
          linked (fn [a] (let [f (forward a)]
                           (if (= a f)
                             (map (juxt :v :tx) (d/datoms db :eavt eid a))
                             (sort-by (comp - second) (map (juxt :e :tx) (d/datoms db :avet f eid))))))
          from (fn [a] {:seon.render/eid eid :seon.render/attr a})]
      [(cond-> {:seon.render/kind :entity :seon.render/value (relevant schema (apply dissoc own units)) :seon.render/eid eid}
         schema (assoc :seon.render/schema schema))
       (into [] (mapcat (fn [a]
                          (if (ref? a)
                            (let [fa (forward a) unit-schema (get entity-schemas fa) forward? (= a fa)]
                              (for [[i [e tx]] (map-indexed vector (linked a))]
                                (if (or (nil? limit) forward? (< i limit) (nil? unit-schema))
                                  (let [m (d/pull db '[*] e) s (entity-schema m fa)]
                                    (cond-> {:seon.render/kind :entity :seon.render/value (relevant s (dissoc m :db/id)) :seon.render/eid e
                                             :seon.render/from (from a) :seon.render/tx tx}
                                      s (assoc :seon.render/schema s)))
                                  ;; a row not shown: listed from the index alone, never read
                                  {:seon.render/kind :entity :seon.render/value {} :seon.render/eid e
                                   :seon.render/from (from a) :seon.render/tx tx :seon.render/schema unit-schema :seon.render/listed true})))
                            (when (contains? own a)
                              [{:seon.render/kind :value :seon.render/value (get own a) :seon.render/schema a :seon.render/eid eid :seon.render/from (from a)}]))))
             units)])))

(defn walk
  "Entity eid as a page lists it, on dbs (main first, then the branch that records what main does
  not: an agent's): the default view of any entity (docs/render.md#the-walk). Its node from the
  first db holding it (what root observed of an agent on main wins over a branch's older copy);
  the nodes of its units one hop (one), and of each unit its schema names a part
  (:seon.render/parts: an agent's run) one hop more, each entity once, from the first db holding
  it, newest first by the transaction that linked it (transactions are issued store-wide, so they
  order entities across branches; an id does not: a content id is a hash). Each node says the
  db it came from (:seon.render/db, its index in dbs). No db holding it: :seon.render/no-entity.
  opts :limit, the rows a page shows: each unit's older rows read without their data values (`one`)."
  {:malli/schema [:function [:=> [:cat [:vector :seon.host/db] pos-int?] [:or :seon.render/walk :seon.render/no-entity]]
                  [:=> [:cat [:vector :seon.host/db] pos-int? [:map [:limit {:optional true} pos-int?]]] [:or :seon.render/walk :seon.render/no-entity]]]}
  ([dbs eid] (walk dbs eid {}))
  ([dbs eid {:keys [limit]}]
  (let [;; each db's walk of e; a listed row carries the db that linked it (nothing else names it)
        walks (fn [e] (keep-indexed (fn [i db] (when-let [[r ns] (one db e limit)]
                                                 [r (mapv #(cond-> % (:seon.render/listed %) (assoc :seon.render/db i)) ns) i]))
                                    dbs))
        ws (walks eid)
        ;; the first db holding entity e: one index read a db
        held (fn [e] (first (keep-indexed (fn [i db] (when (seq (d/datoms db :eavt e)) i)) dbs)))]
    (if-let [[root _ i] (first ws)]
      (let [parts (set (:seon.render/parts (contract/properties (:seon.render/schema root))))
            near (mapcat second ws)
            nodes (concat near
                          (for [nd near :when (and (= :entity (:seon.render/kind nd)) (parts (get-in nd [:seon.render/from :seon.render/attr])))
                                [_ subs] (walks (:seon.render/eid nd)) sub subs :when (not= eid (:seon.render/eid sub))]
                            sub))
            ;; a listed row is the db's that linked it (nothing of it read), the rest the first db holding it
            first-db (fn [nd] (cond (:seon.render/listed nd) (:seon.render/db nd)
                                    (= :entity (:seon.render/kind nd)) (or (held (:seon.render/eid nd)) i)
                                    :else i))
            key-of (fn [nd] (if (= :entity (:seon.render/kind nd)) (:seon.render/eid nd) [(:seon.render/eid nd) (get-in nd [:seon.render/from :seon.render/attr])]))]
        {:seon.render/root (assoc root :seon.render/db i)
         :seon.render/nodes (->> (vals (reduce (fn [m nd] (let [k (key-of nd)] (if (contains? m k) m (assoc m k (assoc nd :seon.render/db (first-db nd)))))) {} nodes))
                                 ;; each node's key made once (sort-by would make it once per comparison)
                                 (map (fn [nd] [[(- (:seon.render/tx nd 0)) (get-in nd [:seon.render/from :seon.render/attr])] nd]))
                                 (sort-by first)
                                 (mapv second))})
      (contract/error :seon.render/no-entity #'seon.render/walk (str "no entity " eid " on these dbs") {:seon.render/eid eid})))))
