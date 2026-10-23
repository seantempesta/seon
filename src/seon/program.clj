(ns seon.program
  "The program is entities, as the compiler knows it (owner, 2026-10-21: \"I don't want
  regex's I want to know exactly what something is\"; inv4's design).

  A FORM is the unit of reproduction: its exact text, its namespace and its place. Every
  definition (a var, a multimethod's method, a protocol extension, a namespace) points to
  the form that last made it, and what a form defines, declares, references and requires
  is what Jolt's analyzer said while compiling it (jolt.analyzer/*unit-tap*: every
  top-level unit, after analysis, before it runs), with a readback after the run telling
  which vars it actually set. Nothing here reads a form's text to learn what it is.

  - An agent records each form it evaluates in a program namespace as it runs (seon.repl's
    wrap-record, applied at once through the run-ahead writer): a form that defines or
    declares something, an `ns` form, or one that changes a namespace's environment
    (require, use, alias, refer, import). A namespace is program unless scratch (`user`, an
    agent's own, or declared ^:scratch).
  - A form whose every definition now points to a later form is superseded: it is no part
    of the program (`live-forms`), and the form that replaced it takes its place.
  - Root and the fork server load main's live forms at boot and every change main's
    commits make, in load order (namespaces in dependency order; within one, the forms'
    order, each after the earliest form that interns a var it references), so an agent
    forked after a merge inherits the merged code compiled. An agent loads what reaches
    its branch from main (merge-main!).
  - A file is read form by form and analyzed without running (`analyze-facts`), so the
    platform's own source has the same facts (seon.source)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-] :as contract] [datahike.api :as d] [jolt.ir :as ir] [jolt.analyzer]
            [seon.schema.datahike :as sd] [seon.edn :as sedn] [malli.registry :as mr] [seon.error :as error] [seon.db :as pdb] [seon.config :as cfg]))

(defn scratch-ns?
  "Is namespace nsx scratch (never program): `user`, or declared ^:scratch."
  {:malli/schema [:=> [:cat :seon.host/namespace] :boolean]}
  [nsx]
  (or (= 'user (ns-name nsx)) (boolean (:scratch (meta nsx)))))

(defn edn-value?
  "Is v plain data that EDN prints and reads back equal: nil, booleans, numbers, strings,
  keywords, symbols, and lists, vectors, maps and sets of them."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v]
  (boolean (cond (record? v) false
                 (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)) true
                 (map? v) (every? (fn [[k x]] (and (edn-value? k) (edn-value? x))) v)
                 (coll? v) (every? edn-value? v)
                 :else false)))

(defn var-kind
  "What a defined var holds now: :fn (a function, which needs a contract), :test, :macro or
  :value (G1's)."
  {:malli/schema [:=> [:cat :seon.host/var] [:enum :fn :test :macro :value]]}
  [v]
  (let [m (meta v)]
    (cond (:test m) :test (:macro m) :macro (contract/function-var? v) :fn :else :value)))

;; ---- the compiler's account of one form ------------------------------------------------

(def banned
  "The clojure.core vars program code does not call (owner, 2026-10-21: every definition
  happens once; loading code is an effect)."
  '#{clojure.core/intern clojure.core/alter-var-root clojure.core/eval clojure.core/load-string})

(def ^:private env-calls
  "The clojure.core functions whose call at a form's top changes its namespace's environment."
  '#{clojure.core/require clojure.core/use clojure.core/alias clojure.core/refer clojure.core/__import
     clojure.core/refer-clojure-register!})

(defn- vsym
  "The var a :var, :the-var or :def node names."
  {:malli/schema [:=> [:cat :map] :qualified-symbol]}
  [n] (symbol (str (:ns n)) (str (:name n))))

(defn- datum
  "A node's literal value: a constant or a quoted form; ::expr otherwise."
  {:malli/schema [:=> [:cat :map] :any]}
  [n] (case (:op n) :const (:val n) :quote (:form n) ::expr))

(defn- invoked
  "The var an :invoke node calls, or nil."
  {:malli/schema [:=> [:cat :map] [:maybe :qualified-symbol]]}
  [n] (let [f (:fn n)] (when (and (= :invoke (:op n)) (= :var (:op f))) (vsym f))))

(defn- unquote-form
  "(quote x) -> x; anything else as it is."
  {:malli/schema [:=> [:cat :any] :any]}
  [x] (if (and (seq? x) (= 'quote (first x))) (second x) x))

(defn- libs
  "The namespaces a require/use spec names: a symbol, [lib & opts], or a prefix list."
  {:malli/schema [:=> [:cat :any] [:sequential :symbol]]}
  [spec]
  (cond (symbol? spec) [spec]
        (and (vector? spec) (symbol? (first spec)) (or (= 1 (count spec)) (keyword? (second spec)))) [(first spec)]
        (and (sequential? spec) (symbol? (first spec)))
        (for [s (rest spec) :let [sn (if (sequential? s) (first s) s)] :when (symbol? sn)]
          (symbol (str (first spec) "." sn)))
        :else []))

(defn- def-info
  "A :def node at a form's top as the account keeps it: the var, how its init is made, and
  its declared metadata forms."
  {:malli/schema [:=> [:cat :map] :map]}
  [n]
  (let [init (:init n)
        f (when init (invoked init))
        f (if (= 'clojure.core/assoc f) (some-> (first (:args init)) invoked) f)]
    {:var (vsym n) :meta (if (map? (:meta n)) (:meta n) {}) :declare? (boolean (or (:no-init n) (nil? init)))
     :init (cond (nil? init) nil (= :fn (:op init)) :fn :else f)}))

(defn- tail
  "The node whose value a node answers: through do and let to their last expression."
  {:malli/schema [:=> [:cat :map] :map]}
  [n] (case (:op n) :do (tail (:ret n)) :let (tail (:body n)) n))

(defn- answers
  "What a handler answers, from its tail: :constant (a literal or quoted form), :rethrows
  (a throw), else :expr."
  {:malli/schema [:=> [:cat :map] [:enum :constant :rethrows :expr]]}
  [n] (case (:op (tail n)) (:const :quote) :constant :throw :rethrows :expr))

(defn- clause-class
  "The class name a catch clause's test names (Jolt lowers each clause for class C to a
  test calling clojure.core/__catch-broad? with C's name), or nil."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [test]
  (let [found (volatile! nil)]
    ((fn walk [n]
       (when (and (nil? @found) (= 'clojure.core/__catch-broad? (invoked n)))
         (vreset! found (str (datum (first (:args n))))))
       (ir/reduce-ir-children (fn [_ c] (walk c)) nil n))
     test)
    @found))

(defn- catches
  "A :try node's catch clauses, in order, each {:class name (\"*\" for a catch-all) :answers
  kind}: read from Jolt's lowering of the clauses (one dispatch: an if per class, a
  catch-all's handler last, else a rethrow)."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [n]
  (loop [d (some-> (:catch-body n) tail) acc []]
    (cond (nil? d) acc
          (and (= :if (:op d)) (clause-class (:test d))) (recur (tail (:else d)) (conj acc {:class (clause-class (:test d)) :answers (answers (:then d))}))
          (= :throw (:op d)) acc
          :else (conj acc {:class "*" :answers (answers d)}))))

(defn- quoted-vars
  "The qualified symbols in a quoted datum (walked: a collection's too) whose namespace
  exists: a syntax-quoted or quoted name resolved later (`requiring-resolve`, a macro's
  template, a proc or render named in data). A symbol of no namespace is data. By namespace,
  not by var: whether the var exists yet depends on what this process analyzed before (an
  import of the same files twice gave 43 of 387 tests the same reach digest, so no result was
  reused across runs or checkouts; worktree session PROVE 2026-10-22), while every imported
  file's namespace exists before any form is analyzed."
  {:malli/schema [:=> [:cat :any] [:set :qualified-symbol]]}
  [x] (into #{} (filter #(and (qualified-symbol? %) (find-ns (symbol (namespace %))))) (tree-seq coll? seq x)))

(defn unit-account
  "What the compiler says one analyzed top-level node does, from its IR by resolved var
  identity: the vars it defines at its top (:defs, each def-info) and inside a function body
  (:inline-defs), its multimethod methods ({:multi sym-as-written :dispatch value}) and
  protocol extensions ({:protocol sym :type name}), the multimethods it sets up, the vars and
  host statics (Class/member) it references (:refs, with the vars its quoted data names:
  quoted-vars), the banned vars it references, the
  calls it bounds by a clock (:timeouts: deref with a timeout), its catch clauses
  (:catches, each {:class :answers}), the namespaces it requires, and whether it changes its
  namespace's environment (:env?)."
  {:malli/schema [:=> [:cat :map] :map]}
  [node]
  (let [acc (volatile! {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{} :refs #{} :banned #{} :requires #{} :env? false
                        :timeouts #{} :catches #{}})]
    ((fn walk [n in-fn?]
       (case (:op n)
         :def (if in-fn?
                (vswap! acc update :inline-defs conj (vsym n))
                (vswap! acc update :defs conj (def-info n)))
         :defmacro (if in-fn?
                     (vswap! acc update :inline-defs conj (vsym n))
                     (vswap! acc update :defs conj {:var (vsym n) :meta {} :macro? true :init :fn}))
         (:var :the-var) (let [s (vsym n)]
                           (when (banned s) (vswap! acc update :banned conj s))
                           (vswap! acc update :refs conj s))
         :host-static (vswap! acc update :refs conj (symbol (str (:class n)) (str (:member n))))
         (:quote :const) (vswap! acc update :refs into (quoted-vars (datum n)))
         :try (vswap! acc update :catches into (map sedn/encode) (catches n))
         :invoke (when (and (= 'clojure.core/deref (invoked n)) (= 3 (count (:args n))))
                   (vswap! acc update :timeouts conj 'clojure.core/deref))
         nil)
       (case (:op n)
         :invoke (when-not in-fn?
                   (let [f (invoked n) args (:args n)]
                     (case f
                       clojure.core/defmethod-setup
                       (vswap! acc update :methods conj {:multi (datum (first args)) :dispatch (datum (second args))})
                       clojure.core/register-method
                       (vswap! acc update :impls conj {:type (str (datum (first args))) :protocol (symbol (str (datum (second args))))})
                       clojure.core/defmulti-setup
                       (vswap! acc update :multis conj (datum (first args)))
                       (when (env-calls f)
                         (vswap! acc assoc :env? true)
                         (when ('#{clojure.core/require clojure.core/use} f)
                           (vswap! acc update :requires into (mapcat (comp libs unquote-form datum)) args))))))
         nil)
       (let [in-fn? (or in-fn? (#{:fn :defmacro} (:op n)))]
         (ir/reduce-ir-children (fn [_ c] (walk c in-fn?)) nil n)))
     node false)
    @acc))

(defn- merge-accounts
  "The accounts of a form's units as one."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [as]
  (reduce (fn [a b] (merge-with (fn [x y] (if (boolean? x) (or x y) (into x y))) a b))
          {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{} :refs #{} :banned #{} :requires #{} :env? false
           :timeouts #{} :catches #{}}
          as))

(defn root-of
  "Var sym's root now (identity is what the readback compares): ::unbound when it has none."
  {:malli/schema [:=> [:cat :qualified-symbol] :any]}
  [sym]
  (if-let [v (find-var sym)] (if (bound? v) (.getRawRoot v) ::unbound) ::unbound))

(defn tap
  "A unit tap (bind jolt.analyzer/*unit-tap* to it around an eval) collecting into acc, a
  volatile of {:nodes [node ...] :before {var root}}: each analyzed unit, and the root each
  var it defines at its top held before the unit ran (the readback compares it after). The
  tap runs inside the eval, so it calls no contracted function: an eval's costliest
  functions are its own (seon.profile)."
  {:malli/schema [:=> [:cat :seon.host/volatile] :seon.host/fn]}
  [acc]
  (fn [_form node]
    (let [before (volatile! (:before @acc))]
      ((fn walk [n]
         (when-not (#{:fn :defmacro} (:op n))
           (when (#{:def :defmacro} (:op n))
             (let [s (symbol (str (:ns n)) (str (:name n))) v (find-var s)]
               (vswap! before assoc s (if (and v (bound? v)) (.getRawRoot v) ::unbound))))
           (ir/reduce-ir-children (fn [_ c] (walk c)) nil n)))
       node)
      (vswap! acc (fn [m] (-> m (update :nodes conj node) (assoc :before @before))))
      nil)))

(defn set-vars
  "The vars an evaluated form set (readback: their root changed while it ran), from its
  tap's nodes and roots before, in any namespace, scratch ones included."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-symbol]]}
  [{:keys [nodes before]}]
  (vec (distinct (for [n nodes d (:defs (unit-account n))
                       :when (or (:macro? d) (not (identical? (get before (:var d)) (root-of (:var d)))))]
                   (:var d)))))

(defn- contract-text
  "A def's declared :malli/schema as text: the live var's when it is plain data (a schema var a
  form names, resolved), else the form as written (it holds a function or a compiled schema, whose
  value is no data: the authored contract is kept as data)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/var] :map] [:maybe :string]]}
  [v d]
  (let [c (some-> v meta :malli/schema)]
    (if (and (some? c) (edn-value? c))
      (sedn/encode c)
      (some-> (get (:meta d) :malli/schema) unquote-form sedn/encode))))

(defn- def-kind
  "A defined var's kind: from the live var when it is bound, else from the compiler's
  account of its definition. A function a form defines beside a type is that type's
  constructor (:ctor), and needs no contract of its own."
  {:malli/schema [:=> [:cat [:maybe :seon.host/var] :map :boolean :boolean] :keyword]}
  [v d multi? types?]
  (let [m (merge (:meta d) (some-> v meta))]
    (cond (or (:macro? d) (:macro m)) :macro
          (contains? m :test) :test
          (or multi? (and v (bound? v) (instance? clojure.lang.MultiFn @v))) :multi
          (contains? m :protocol) :protocol-fn
          (= 'clojure.core/make-protocol (:init d)) :protocol
          (= 'clojure.core/make-deftype-ctor (:init d)) :type
          (and v (bound? v)) (cond (not (fn? @v)) :value
                                   types? :ctor
                                   (:dynamic m) :value
                                   :else :fn)
          (#{:fn 'seon.contract/checked} (:init d)) (if types? :ctor :fn)
          :else :value)))

(defn- macro-uses
  "The macros form text src calls in namespace nsx: each list in the form as read whose head
  resolves there to a macro var. The IR is the expansion, which names no macro, and a
  top-level call expanding to a do leaves its units' forms no trace of it (Clojure's
  compilation-unit rule), so the form itself is read. A macro's own template names the
  macros it expands to as quoted vars (quoted-vars), which closes the chain."
  {:malli/schema [:=> [:cat :seon.host/namespace :string] [:set :qualified-symbol]]}
  [nsx src]
  (into #{} (keep (fn [x] (when (and (seq? x) (symbol? (first x)))
                            (let [v (ns-resolve nsx (first x))] (when (and (var? v) (:macro (meta v))) (symbol v))))))
        (tree-seq coll? seq (binding [*ns* nsx] (read-string src)))))

(defn- form-id
  "A form's identity: its namespace and exact text (an imported file's repeated text adds
  its occurrence)."
  {:malli/schema [:=> [:cat :symbol :string :int] :string]}
  [nsym src k]
  (str nsym "/" (Long/toHexString (hash (str nsym "\n" src))) "-" (count src) (when (pos? k) (str "#" k))))

(defn account
  "The program account of one top-level form, or nil when it is none of the program's.
  opts: :nodes (each analyzed unit's node; or :units, their unit-accounts), :src (its exact text), :ns (the namespace
  it was evaluated in), :ns-after (where it left the REPL), :ns-form? (it is an `ns` form:
  its head resolves to clojure.core/ns), :before (readback: var -> root before; nil for an
  analysis without running: every top-level def counts), :ran? (the form returned; a form
  that threw records only the vars it set), :ns-meta (an ns form's name's metadata: Jolt's
  ns drops it, jolt/ns-meta), :lead, :order, :k (occurrence of its text)."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [nodes units src ns ns-after ns-form? ns-meta before ran? lead order k] :or {k 0}}]
  (let [units (or units (map unit-account nodes))
        nsym (if ns-form? ns-after ns)
        nsx (find-ns nsym)
        a (merge-accounts units)
        types? (boolean (some #(= 'clojure.core/make-deftype-ctor (:init %)) (:defs a)))
        set? (fn [{v :var :as d}] (or (nil? before) (not (identical? (get before v) (root-of v))) (:macro? d)))
        defs (vec (for [d (:defs a)
                        :let [v (find-var (:var d))
                              multi? (contains? (:multis a) (:var d))]
                        :when (and (not (and (:declare? d) (not multi?))) (set? d))]
                    (cond-> {:var (:var d) :kind (def-kind v d multi? types?)}
                      (contract-text v d) (assoc :contract (contract-text v d))
                      (string? (or (some-> v meta :doc) (get (:meta d) :doc))) (assoc :doc (or (some-> v meta :doc) (get (:meta d) :doc))))))
        declares (into #{} (comp (filter :declare?) (map :var) (remove (set (map :var defs)))
                                 (remove #(contains? (:multis a) %))) (:defs a))
        methods (when (or ran? (nil? before))
                  (vec (for [{:keys [multi dispatch]} (:methods a)
                             :let [mv (when (and nsx (symbol? multi)) (ns-resolve nsx multi))]
                             :when (var? mv)]
                         {:multi (symbol mv) :dispatch (sedn/encode dispatch)})))
        impls (when (or ran? (nil? before)) (vec (distinct (:impls a))))]
    (when (and nsx (not (scratch-ns? nsx)) (not (:scratch ns-meta))
               (or ns-form? (seq defs) (seq declares) (seq methods) (seq impls) (:env? a)))
      (cond-> {:id (form-id nsym src k) :src src :ns nsym :ns-form? (boolean ns-form?)
               :defs defs :declares declares :methods (or methods []) :impls (or impls [])
               :refs (into (:refs a) (macro-uses nsx src)) :requires (:requires a) :banned (:banned a) :inline-defs (:inline-defs a)
               :timeouts (:timeouts a) :catches (:catches a)}
        lead (assoc :lead lead)
        order (assoc :order order)))))

;; ---- the account as facts ---------------------------------------------------------------

(defn ns-tid {:malli/schema [:=> [:cat :symbol] :string]} [nsym] (str "ns " nsym))
(defn- var-tid {:malli/schema [:=> [:cat :symbol] :string]} [sym] (str "var " sym))
(defn form-tid {:malli/schema [:=> [:cat :string] :string]} [id] (str "form " id))

(defn method-key
  "A method's identity: [multi dispatch], printed."
  {:malli/schema [:=> [:cat :qualified-symbol :string] :string]}
  [multi dispatch] (sedn/encode [multi dispatch]))

(defn impl-key
  "A protocol extension's identity: [protocol type], printed."
  {:malli/schema [:=> [:cat :qualified-symbol :string] :string]}
  [protocol type] (sedn/encode [protocol type]))

(def ^:private def-attrs
  "Each definition kind's form pointer."
  [:var/form :method/form :impl/form :ns/form])

(defn- form-of
  "The form entity definition entity e points to, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :int]]}
  [db e] (some #(:db/id (get (d/entity db e) %)) def-attrs))

(defn account-tx
  "The facts of a form's account on db: the form (keeping its order when it is already
  there, else taking the place of the forms it supersedes, else after every form: order
  next-order), each definition pointing to it, and the forms it supersedes retracted (each
  whose every definition it redefines). A var's contract or doc the redefinition no longer
  has is retracted."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.db/tx-data]}
  [db {:keys [id src lead ns ns-form? defs declares methods impls refs requires banned inline-defs timeouts catches order]} next-order]
  (let [fe (pdb/eid db :form/id id)
        dkeys (concat (for [{v :var} defs] [:var/name v])
                      (for [{:keys [multi dispatch]} methods] [:method/key (method-key multi dispatch)])
                      (for [{:keys [protocol type]} impls] [:impl/key (impl-key protocol type)])
                      (when ns-form? [[:ns/name ns]]))
        des (set (keep (fn [[a v]] (pdb/eid db a v)) dkeys))
        olds (disj (set (keep #(form-of db %) des)) fe nil)
        superseded (filterv (fn [o] (every? #(contains? des (:db/id %)) (:form/defines (d/entity db o)))) olds)
        order (or (when fe (:form/order (d/entity db fe)))
                  order
                  (when (seq superseded) (apply min (map #(:form/order (d/entity db %)) superseded)))
                  next-order)
        lead (or lead (some #(:form/lead (d/entity db %)) superseded))
        f (form-tid id)
        ;; a var main already has is named by its entity: Datahike allocates a tempid first
        ;; seen as a ref (:form/defines) before the map that upserts it, and a form defining
        ;; two existing vars (a protocol, a record) then conflicts
        vid (fn [s] (or (pdb/eid db :var/name s) (var-tid s)))
        dtids (concat (for [{v :var} defs] (vid v))
                      (for [{:keys [multi dispatch]} methods] (str "method " (method-key multi dispatch)))
                      (for [{:keys [protocol type]} impls] (str "impl " (impl-key protocol type)))
                      (when ns-form? [(ns-tid ns)]))
        nss (into #{ns} (map #(symbol (namespace (:var %)))) defs)]
    (vec (concat
           (for [n nss] {:db/id (ns-tid n) :ns/name n})
           [(cond-> {:db/id f :form/id id :form/src src :form/ns (ns-tid ns) :form/order order}
              lead (assoc :form/lead lead)
              (seq dtids) (assoc :form/defines (vec dtids))
              (seq declares) (assoc :form/declares (mapv vid declares))
              (seq refs) (assoc :form/refs (vec refs))
              (seq requires) (assoc :form/requires (vec requires))
              (seq banned) (assoc :form/banned (vec banned))
              (seq inline-defs) (assoc :form/inline-defs (vec inline-defs))
              (seq timeouts) (assoc :form/timeouts (vec timeouts))
              (seq catches) (assoc :form/catches (vec catches)))]
           (for [s declares] {:db/id (vid s) :var/name s :var/ns (ns-tid (symbol (namespace s)))})
           (for [{v :var :keys [kind contract doc]} defs
                 :let [e (pdb/eid db :var/name v) old (when e (d/entity db e))]
                 tx (into [(cond-> {:db/id (vid v) :var/name v :var/ns (ns-tid (symbol (namespace v))) :var/form f :var/kind kind}
                             contract (assoc :var/contract contract) doc (assoc :var/doc doc))]
                          (for [[a x] [[:var/contract contract] [:var/doc doc]]
                                :let [o (get old a)] :when (and (some? o) (nil? x))]
                            [:db/retract e a o]))]
             tx)
           (for [{:keys [multi dispatch]} methods :let [k (method-key multi dispatch)]]
             {:db/id (str "method " k) :method/key k :method/multi multi :method/dispatch dispatch :method/form f})
           (for [{:keys [protocol type]} impls :let [k (impl-key protocol type)]]
             {:db/id (str "impl " k) :impl/key k :impl/protocol protocol :impl/type type :impl/form f})
           (when ns-form? [{:db/id (ns-tid ns) :ns/form f}])
           (for [o superseded] [:db/retractEntity o])))))

(defn record-tx
  "The facts of a form's account recorded on this branch's db: account-tx, a new form after
  every form so far (its order: the branch's next transaction)."
  {:malli/schema [:=> [:cat :seon.host/db :map] :seon.db/tx-data]}
  [db acct] (account-tx db acct (inc (:max-tx db))))

;; ---- the live program ----------------------------------------------------------------

(defn live?
  "Is form entity e part of the program: it defines nothing (a text form: a declare, a
  require, a comment kept from its file), or some definition it made still points to it."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db e]
  (let [ds (:form/defines (d/entity db e))]
    (or (empty? ds) (boolean (some #(= e (form-of db (:db/id %))) ds)))))

(defn- definition-key
  "Definition entity x's program key ([:var sym], [:method ...], [:impl ...], [:ns sym])."
  {:malli/schema [:=> [:cat :any] [:maybe :seon.merge/program-key]]}
  [x] (cond (:var/name x) [:var (:var/name x)]
            (:method/key x) (into [:method] (sedn/decode (:method/key x)))
            (:impl/key x) (into [:impl] (sedn/decode (:impl/key x)))
            (:ns/name x) [:ns (:ns/name x)]))

(defn form-row
  "Form entity e as the loader and the merge read it."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db e]
  (let [f (d/entity db e)
        def-key definition-key]
    (cond-> {:e e :id (:form/id f) :src (:form/src f) :ns (:ns/name (:form/ns f)) :order (:form/order f)
             :defines (into #{} (keep def-key) (:form/defines f))
             :declares (into #{} (map :var/name) (:form/declares f))
             :refs (set (:form/refs f)) :requires (set (:form/requires f))
             :banned (set (:form/banned f)) :inline-defs (set (:form/inline-defs f))
             :timeouts (set (:form/timeouts f)) :catches (set (:form/catches f))}
      (:form/lead f) (assoc :lead (:form/lead f)))))

(defn live-forms
  "Every live form of db, as form-row. Cost: the program's forms and their definitions,
  read once."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (vec (for [dt (d/datoms db :aevt :form/id) :when (live? db (:e dt))] (form-row db (:e dt)))))

;; ---- load order ----------------------------------------------------------------------

(defn topo
  "nodes in dependency order: each after the nodes among them it needs (deps), the rest in
  the order of k; a cycle is broken at the node first by k. One visit per node and edge."
  {:malli/schema [:=> [:cat [:sequential :any] :seon.host/fn :seon.host/fn] [:vector :any]]}
  [nodes deps k]
  (let [in? (set nodes)]
    (:order (reduce (fn visit [st n]
                      (if (or ((:seen st) n) (not (in? n)))
                        st
                        (update (reduce visit (update st :seen conj n) (sort-by k (deps n))) :order conj n)))
                    {:seen #{} :order []}
                    (sort-by k nodes)))))

(defn load-order
  "forms (form rows) in the order to load them: namespaces in dependency order (after each
  namespace among them that their forms require or whose vars they reference; ties by
  name), and within a namespace by order, each form after the earliest form among them
  that interns (defines or declares) a var it references. A file's own order is kept: it
  already loads. Cost: one pass over the forms and their references."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [forms]
  (let [by-id (into {} (map (juxt :id identity)) forms)
        interns (reduce (fn [m f]
                          (reduce (fn [m s] (update m s (fn [g] (if (and g (<= (:order (by-id g)) (:order f))) g (:id f)))))
                                  m (concat (keep (fn [[k s]] (when (= :var k) s)) (:defines f)) (:declares f))))
                        {} forms)
        by-ns (group-by :ns forms)
        nss (vec (keys by-ns))
        ns-deps (into {} (for [[n fs] by-ns]
                           [n (into (set (mapcat :requires fs))
                                    (for [f fs r (:refs f) :let [g (interns r)] :when g] (:ns (by-id g))))]))
        deps (fn [f] (for [r (:refs f) :let [g (interns r)] :when (and g (not= g (:id f)) (= (:ns f) (:ns (by-id g))))] g))]
    (vec (for [n (topo nss #(disj (ns-deps %) %) str)
               id (topo (map :id (by-ns n)) #(deps (by-id %)) #(:order (by-id %)))]
           (by-id id)))))

;; ---- loading ---------------------------------------------------------------------------
;; A form imported from a source tree (seon.source: :form/source) whose namespace this
;; process holds is the running binary's own code, compiled in. An imported namespace the
;; binary was not built with (test/'s) is loaded like every other form when its import is
;; (load-changes!, in root's writer and the fork server), so every agent forked after it
;; holds main's tests compiled. Every other form of main runs where main's program runs: a merge
;; loads in place, in root, the fork server (so every agent forked after it inherits it
;; compiled) and an agent that merges main (BUILD plan P4). A binary's own form is
;; evaluated again only where a changed macro expanded (`dependents`) or a changed schema
;; reaches its contract (`schema-dependents`).

(declare schema-changes load-forms!)

(defn- held?
  "Does this process already hold form row f compiled: imported from the source tree, in a
  namespace it holds (the binary's own; test/ is imported but not built in), every var it
  defines bound there? A form imported into a namespace loaded earlier (a test namespace
  loaded at boot, then a re-import adding a deftest) defines a var this process does not
  hold yet: it is loaded, never read as an unmap (seon.stage/stage-tx)."
  {:malli/schema [:=> [:cat :seon.host/db :map] :boolean]}
  [db f]
  (boolean (and (:form/source (d/entity db (:e f)))
                (when-let [n (find-ns (:ns f))]
                  (every? (fn [[k s]] (or (not= :var k) (some-> (get (ns-interns n) (symbol (name s))) bound?)))
                          (:defines f))))))

(defn- requiring
  "The form entities of namespace nsym and of every namespace a form requires it from: where
  its macros expand (an expansion leaves no reference). One pass over the requires."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:sequential :int]]}
  [db nsym]
  (for [n (into #{nsym} (for [dt (d/datoms db :aevt :form/requires) :when (= nsym (:v dt))]
                          (:ns/name (:form/ns (d/entity db (:e dt))))))
        :let [ne (:db/id (d/entity db [:ns/name n]))] :when ne
        dt (d/datoms db :avet :form/ns ne)]
    (:e dt)))

(defn dependents
  "The live forms of db, none of changed (form rows), that expanded a macro changed defines,
  and theirs, and on: the forms of its namespace and of the namespaces requiring it (v1's
  declaration-reload-rule, clj-reload's model). A protocol or a type needs none on Jolt: a
  record's dispatch and an instance? test reach the redefinition (seon.source-test).
  Cost: nothing unless a macro changed; then its namespaces' forms."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map]] [:vector :map]]}
  [db changed]
  (loop [front (vec changed) seen (set (map :id changed)) out []]
    (if-let [[f & more] (seq front)]
      (let [es (distinct (for [[k s] (:defines f)
                               :when (and (= :var k) (= :macro (:var/kind (d/entity db [:var/name s]))))
                               e (requiring db (symbol (namespace s)))]
                           e))
            new (into [] (comp (filter #(live? db %)) (map #(form-row db %)) (remove (comp seen :id))) es)]
        (recur (into (vec more) new) (into seen (map :id) new) (into out new)))
      out)))

(defn- names
  "The qualified keywords a schema or contract form names."
  {:malli/schema [:=> [:cat :any] [:set :qualified-keyword]]}
  [form] (set (filter qualified-keyword? (tree-seq coll? seq form))))

(defn schema-dependents
  "The live forms of db defining a contracted var whose contract names one of keys, or a
  schema that names one, and on (the binary's schema forms with db's changes): the forms
  that re-arm against the changed schemas. Nothing when keys is empty."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-keyword]] [:vector :map]]}
  [db keys]
  (if (empty? keys)
    []
    (let [named (into {} (for [[k f] (merge contract/forms (schema-changes db))] [k (names f)]))
          reached (loop [r keys]
                    (let [r' (into r (for [[k ns] named :when (and (not (r k)) (some r ns))] k))]
                      (if (= r r') r (recur r'))))]
      (vec (distinct (for [dt (d/datoms db :aevt :var/contract)
                           :when (some reached (names (sedn/decode (:v dt))))
                           :let [fe (:db/id (:var/form (d/entity db (:e dt))))]
                           :when (and fe (live? db fe))]
                       (form-row db fe)))))))

(defn ensure-ns!
  "The namespace, created with clojure.core and clojure.test's assertions referred when it
  is new (a namespace with no ns form)."
  {:malli/schema [:=> [:cat :symbol] :seon.host/namespace]}
  [ns-sym]
  (or (find-ns ns-sym)
      (let [n (create-ns ns-sym)]
        (binding [*ns* n]
          (refer-clojure)
          (refer 'clojure.test :only '[deftest is are testing]))
        n)))

(defn- load-form!
  "Evaluate one form row in its namespace, and arm the contracted functions it defines
  (seon.contract/arm!). Answers nil, or an error map."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.program/error]]}
  [{:keys [ns src defines]}]
  (try
    (binding [*ns* (ensure-ns! ns)]
      (eval (read-string src)))
    (some (fn [[k s]]
            (when-let [v (and (= :var k) (find-var s))]
              (let [r (contract/arm! v)]
                (when (contract/error? r) {:ns ns :message (:seon.error/message r)}))))
          defines)
    (catch Throwable e {:ns ns :message (or (ex-message e) (str (type e)))})))

;; ---- schema entries: the program's schemas (seon.source imports them, my.schema/register!
;; changes them, seon.merge carries them per key) ----------------------------------------

(defn schema-changes
  "{k form} of db's schema entries the binary does not hold (no :schema/source: registered
  or merged), against seon.contract/forms. Two passes over the entries' datoms."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-keyword :any]]}
  [db]
  (let [src (into #{} (map :e) (d/datoms db :aevt :schema/source))]
    (into {} (for [dt (d/datoms db :aevt :schema/key) :when (not (src (:e dt)))]
               [(:v dt) (sedn/decode (:schema/form (d/entity db (:e dt))))]))))

(defn schema-registry
  "The registry an evaluation on db binds as malli.registry/*registry*: the binary's forms
  with changes (schema-changes) recompiled whole, since a schema naming a changed key keeps
  the old one compiled in; empty (the built registry answers) when nothing changed."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :any]] :any]}
  [changes] (if (empty? changes) {} (contract/registry-of (merge contract/forms changes))))

(defn schema-declarations
  "The Datahike declarations registry reg derives that db does not hold as declared (a
  registration or merge of a schema carries its attribute, so its facts can be stored)."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:vector :seon.schema/declaration]]}
  [db reg]
  (let [have (d/schema db)]
    (filterv (fn [dcl] (not= dcl (select-keys (get have (:db/ident dcl)) (keys dcl)))) (sd/declarations reg))))

(defn- load-forms!
  "Load form rows of db in load order, compiling against db's schemas (malli's dynamic
  registry bound to schema-registry: a merged or registered schema is in effect for the
  code that names it); answers the errors. Nothing to load reads no schemas."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map]] [:vector :seon.program/error]]}
  [db forms]
  (if (seq forms)
    (binding [mr/*registry* (schema-registry (schema-changes db))]
      (into [] (keep load-form!) (load-order forms)))
    []))

(defn- remove-definition!
  "Make this process hold no definition for program key k (a var unmapped, a method
  removed)."
  {:malli/schema [:=> [:cat :any] :any]}
  [[kind a b]]
  (case kind
    :var (when-let [n (find-ns (symbol (namespace a)))] (ns-unmap n (symbol (name a))))
    :method (when-let [v (find-var a)] (when (instance? clojure.lang.MultiFn @v) (remove-method @v (read-string b))))
    nil))

(defn- load!
  "Load changed (form rows this binary does not hold), the forms a changed macro expanded
  in (dependents) and the forms whose contracts the changed schema keys reach
  (schema-dependents), in load order. Answers the errors."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map] [:set :qualified-keyword]] [:vector :seon.program/error]]}
  [db changed schema-keys]
  (load-forms! db (vec (vals (into {} (map (juxt :id identity))
                                   (concat changed (dependents db changed) (schema-dependents db schema-keys)))))))

(defn load-all!
  "Load db's program where this binary runs: every live form it does not hold, with what
  they reach (load!). Answers {:loaded n :errors [...]}."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.program/load-report]}
  [db]
  (let [forms (filterv #(not (held? db %)) (live-forms db))]
    {:loaded (count forms) :errors (load! db forms (set (keys (schema-changes db))))}))

(defn load-diff!
  "Make this process run db-to's program where it holds db-from's: each live form db-to
  has that db-from had not is loaded (but the binary's own), with what it reaches (load!),
  and each definition db-from had that db-to has none of is removed. Answers the load
  errors. Cost: each db's form ids and definition pointers, read as datoms; a form row is
  built only for a form db-to has live that db-from had not (measured 2026-10-23 on Seon's
  program: four live-forms reads, ~1.4 s, before)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.program/error]]}
  [db-from db-to]
  (let [live-ids (fn [db] (into #{} (for [dt (d/datoms db :aevt :form/id) :when (live? db (:e dt))] (:v dt))))
        def-keys (fn [db] (into #{} (for [a def-attrs dt (d/datoms db :aevt a)] (definition-key (d/entity db (:e dt))))))
        from (live-ids db-from)
        to-defs (def-keys db-to)
        sc-from (schema-changes db-from)]
    (doseq [k (sort-by str (def-keys db-from)) :when (not (contains? to-defs k))] (remove-definition! k))
    (load! db-to (filterv #(not (held? db-to %))
                          (for [dt (d/datoms db-to :aevt :form/id) :when (and (not (from (:v dt))) (live? db-to (:e dt)))]
                            (form-row db-to (:e dt))))
           (set (for [[k v] (schema-changes db-to) :when (not= v (get sc-from k))] k)))))

(defn load-changes!
  "Load what resolved tx-data ops change, read against db (after them): each definition
  they leave pointing to no form is removed, and each live form they assert (but the
  binary's own) is loaded, with what it reaches (load!: where a changed macro expanded, and
  the contracts a changed schema entry reaches). Answers the error
  maps (empty when all loaded)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.db/op]] [:vector :seon.program/error]]}
  [db ops]
  (let [ptr (set def-attrs)
        added (set (for [[op e a] ops :when (and (= op :db/add) (ptr a))] e))]
    (doseq [[op e a] ops
            :when (and (= op :db/retract) (ptr a) (not (added e)))
            :let [x (d/entity db e)]]
      (cond (:var/name x) (remove-definition! [:var (:var/name x)])
            (:method/key x) (remove-definition! (into [:method] (sedn/decode (:method/key x))))))
    (load! db (vec (for [[op e a] ops
                         :when (and (= op :db/add) (= a :form/src) (live? db e))
                         :let [f (form-row db e)] :when (not (held? db f))]
                     f))
           (set (for [[op e a] ops :when (and (= op :db/add) (= a :schema/form))
                      :let [x (d/entity db e)] :when (not (:schema/source x))]
                  (:schema/key x))))))

;; ---- reach: which tests a change reaches ----------------------------------------------

(defn divergence
  "What branch db-to changed since db-from (its merge base), the changes its merge carries to
  main: the vars it added, redefined and removed, and the schema keys it registered or
  changed; each sorted, an empty kind left out ({}: nothing). Compared by content: a var's
  form by its :form/id (its text), a schema by its form's text, because entity ids are a
  branch's own and a merge applies main's forms by content under the branch's ids. Cost:
  each db's :var/form and :schema/form datoms, and two seeks per entry whose ids differ."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db]
                  [:map-of [:enum :added :changed :removed :schemas] [:vector [:or :qualified-symbol :qualified-keyword]]]]}
  [db-from db-to]
  (let [by (fn [db a] (into {} (map (juxt :e :v)) (d/datoms db :aevt a)))
        ;; the entries whose ids differ, by name, then compared by content
        differ (fn [attr name-attr content]
                 (let [a (by db-from attr) b (by db-to attr)]
                   (into #{} (for [e (into (set (keys a)) (keys b)) :when (not= (a e) (b e))
                                   :let [n (name-attr (d/entity (if (b e) db-to db-from) e))
                                         x (content db-from n) y (content db-to n)]
                                   :when (not= x y)]
                               [(cond (nil? x) :added (nil? y) :removed :else :changed) n]))))
        form-id (fn [db v] (some->> (pdb/eid db :var/name v) (d/entity db) :var/form :form/id))
        schema (fn [db k] (some->> (pdb/eid db :schema/key k) (d/entity db) :schema/form))
        vars (differ :var/form :var/name form-id)
        schemas (for [[_ k] (differ :schema/form :schema/key schema)] [:schemas k])]
    (into {} (for [[k xs] (group-by first (concat vars schemas))] [k (vec (sort-by str (map second xs)))]))))

(defn changed-vars
  "The vars db-to points to another form than db-from does (new, redefined or removed), by
  content: what a branch changed since its base (divergence's vars)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:set :qualified-symbol]]}
  [db-from db-to]
  (into #{} cat (vals (dissoc (divergence db-from db-to) :schemas))))

(def tree-readers
  "The vars that read the checkout's files or run the binary built from them: a test whose
  reached forms reference one has the whole tree as an input, so its reach digest covers it
  (reach-digests), unless the form referencing it defines one of env-readers. The host's file
  readers, and seon.test-store/binary-sh, the one way a test runs target/seon, bin/root or
  bin/check: what such a test proves is the whole program the binary is built from."
  '#{clojure.core/slurp clojure.core/file-seq seon.test-store/binary-sh})

(def env-readers
  "The platform vars whose file reads are of inputs every digest already holds (:env:
  resources/, deps.edn, web/) or of no checkout file at all, so reaching them does not make a
  test read the tree. A reader not named here counts as reading the tree (conservative: a
  missed name reruns more, never reuses a stale green). Measured 2026-10-22 (worktree session PROVE): with
  seon.config/baked-schema-forms (a macro every contracted form expands) counted, 327 of 368
  tests took the tree, so any edit anywhere reran them."
  '#{seon.config/baked-schema-forms ;; resources/seon/schemas, in :env
     seon.fork-server/dev-classpath! ;; deps.edn, in :env
     seon.fx/declared-refs ;; resources/seon/system.edn, in :env
     seon.web/handler ;; web/public, in :env
     seon.proofs/greens}) ;; the results directory, outside the tree, by digest

(defn- reach-fold
  "(f key reached {:by-id :digest}) of each of db's tests (or each of roots' keys), reached the
  form ids its closure reaches (reach-digests' walk: its own form and its namespace's plain
  forms, then every reference, method, extension, contract's schema entry and inputs'
  :data-refs, a test's form never walked on to): {key (f ...)}. The one walk reach-digests and
  process-tests read."
  {:malli/schema [:=> [:cat :seon.host/db :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]] :seon.host/fn] :map]}
  [db {:keys [data-refs]} roots f]
  (let [schemas (into {} (for [dt (d/datoms db :aevt :schema/key)] [(:v dt) (:schema/form (d/entity db (:e dt)))]))
        sid #(str "schema " %)
        named (fn [text] (keep #(when (contains? schemas %) (sid %)) (names (sedn/decode text))))
        contract (into {} (for [dt (d/datoms db :aevt :var/contract)] [(:var/name (d/entity db (:e dt))) (named (:v dt))]))
        live (live-forms db)
        forms (into live (for [[k text] schemas] {:id (sid k) :ns k :src text :refs #{} :defines #{} :schema (named text)}))
        by-def (fn [kind] (reduce (fn [m f] (reduce (fn [m [k s]] (if (= k kind) (update m s (fnil conj []) (:id f)) m)) m (:defines f))) {} forms))
        var-form (into {} (for [[s ids] (by-def :var)] [s (peek ids)]))
        methods (by-def :method) impls (by-def :impl)
        by-id (into {} (map (juxt :id identity)) forms)
        digest (into {} (for [f forms] [(:id f) (error/digest (str (:ns f) "\n" (:src f)))]))
        ;; a test's form is reached only as the root it is: another form naming a test (its
        ;; name quoted as data, a proof list) never runs it, clojure.test does
        test-ids (set (for [dt (d/datoms db :aevt :var/kind) :when (= :test (:v dt)) :let [id (var-form (:var/name (d/entity db (:e dt))))] :when id] id))
        edges (fn [f] (concat (remove test-ids (keep var-form (:refs f))) (:schema f)
                              (for [[k s] (:defines f) :when (= :var k)
                                    id (concat (methods s) (impls s) (contract s) (keep var-form (get data-refs s)))]
                                id)))
        plain (update-vals (group-by :ns (filter #(not-any? (fn [[k]] (= :var k)) (:defines %)) live)) #(mapv :id %))
        ;; each form's neighbours, built once per call: every test's walk reads them, and
        ;; rebuilding a form's edges per test that reaches it cost 7.4 GB a call on Seon's own
        ;; program (pure-tests' measure, 2026-10-23)
        adj (into {} (map (juxt :id #(vec (edges %)))) forms)
        ;; key k reaching from the forms ids, with their namespaces' plain forms: a stack
        ;; (peek/pop), never a copy of the front per step
        digest-of (fn [k ids]
                    (f k (loop [stack (into (vec ids) (mapcat #(plain (:ns (by-id %)))) ids) seen #{}]
                           (if (empty? stack)
                             seen
                             (let [id (peek stack) stack (pop stack)]
                               (if (seen id) (recur stack seen) (recur (into stack (adj id)) (conj seen id))))))
                       {:by-id by-id :digest digest}))]
    (if roots
      ;; a root naming a var with no form here (renamed, not imported) gets no digest: its
      ;; caller names it missing, never a digest of :env alone that a stale green matches
      (into {} (for [[k vars] roots :when (every? var-form vars)] [k (digest-of k (map var-form vars))]))
      (into {} (for [f forms [k t] (:defines f) :when (and (= :var k) (= :test (:var/kind (d/entity db [:var/name t]))))]
                 [t (digest-of t [(:id f)])])))))

(defn- reach-digest
  "Key k's reach digest from what it reached (reach-digests)."
  {:malli/schema [:=> [:cat :seon.program/inputs :any [:set :any] :map] :string]}
  [{:keys [env tree]} k reached {:keys [by-id digest]}]
  (let [reads? (some #(let [f (by-id %)]
                        (and (some tree-readers (:refs f))
                             (not (and (seq (:defines f)) (every? (fn [[k s]] (and (= :var k) (env-readers s))) (:defines f))))))
                     reached)]
    (error/digest (apply str k "\n" env "\n" (when reads? tree) (sort (map digest reached))))))

(defn reach-digests
  "{test var -> reach digest} of db's tests (task seon.program/tests-run-by-reach): the
  digest of the test's name, inputs' :env (what has no program facts), its own form, its
  namespace's forms that define no var (the ns form, fixtures), and every live form their
  references reach (a var to the form that last defined it, macros and quoted names among
  them (unit-account); a defined multimethod's methods and protocol's extensions; a
  contracted var's form to the schema entries its contract names, and each entry to those
  its form names; a var named in data its form reads, inputs' :data-refs: system.edn's procs,
  which root resolves by name), with inputs' :tree when a reached form references a
  tree-reader. A
  test's digest moves exactly when a form or schema entry it reaches does. Cost: the live
  forms, contracts and schema entries read once and digested once, then per test its
  closure. With roots ({key [var ...]}, a bin/check step's mode functions: seon.proofs/
  check-steps), the same digest of each key: what its vars' forms and their namespaces'
  plain forms reach; a key one of whose vars has no form here has none."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.program/inputs] [:map-of :qualified-symbol :string]]
                  [:=> [:cat :seon.host/db :seon.program/inputs [:map-of :string [:sequential :qualified-symbol]]] [:map-of :string :string]]]}
  ([db inputs] (reach-digests db inputs nil))
  ([db inputs roots] (reach-fold db inputs roots (partial reach-digest inputs))))

(defn- reaches-process?
  "Does what a test reached reference one of vars (:seon.config.source/process-vars)?"
  {:malli/schema [:=> [:cat [:set :qualified-symbol] [:set :any] :map] :boolean]}
  [vars reached {:keys [by-id]}] (boolean (some #(some vars (:refs (by-id %))) reached)))

(defn reach-index
  "{test {:digest :process?}} of db's tests under inputs, from the one walk (reach-fold): its
  reach digest (reach-digests) and whether it reaches a process of its own (a var of
  :seon.config.source/process-vars: process-tests). With roots (a bin/check step's mode
  functions, as reach-digests takes them), the same of each key."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.program/inputs] [:map-of :qualified-symbol [:map [:digest :string] [:process? :boolean]]]]
                  [:=> [:cat :seon.host/db :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]]] [:map-of :any [:map [:digest :string] [:process? :boolean]]]]]}
  ([db inputs] (reach-index db inputs nil))
  ([db inputs roots]
   (let [vars (cfg/value :seon.config.source/process-vars)]
     (reach-fold db inputs roots (fn [k reached ctx] {:digest (reach-digest inputs k reached ctx) :process? (reaches-process? vars reached ctx)})))))

(defn process-tests
  "The keys of index (reach-index's answer) that reach a process of their own (a shell, a
  fork, a root booted for it, the binary): never run automatically, only when named (owner,
  2026-10-23). Taking the index, not db, so a caller holding one walks once."
  {:malli/schema [:=> [:cat [:map-of :any [:map [:process? :boolean]]]] [:set :any]]}
  [index] (into #{} (keep (fn [[t {:keys [process?]}]] (when process? t))) index))
