(ns seon.program
  "The program is entities, as the compiler knows it (DECISIONS, Programs are known from
  the compiler, not from text; inv4's design).

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
  (:require [seon.step :as step] [seon.contract :refer [defn defn-] :as contract] [datahike.api :as d] [jolt.ir :as ir] [jolt.analyzer]
            [seon.schema.datahike :as sd] [seon.edn :as sedn] [malli.registry :as mr] [seon.error :as error] [seon.db :as pdb] [seon.config :as cfg] [clojure.string :as str] [hasch.core :as hasch]))

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

;; ---- the compiler's account of one form ------------------------------------------------

(def banned
  "The clojure.core vars program code does not call (DECISIONS, Every definition happens once,
  where it is defined; loading code is an effect)."
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
  reused across runs or checkouts), while every imported
  file's namespace exists before any form is analyzed."
  {:malli/schema [:=> [:cat :any] [:set :qualified-symbol]]}
  [x] (into #{} (filter #(and (qualified-symbol? %) (find-ns (symbol (namespace %))))) (tree-seq coll? seq x)))

(defn unit-account
  "What the compiler says one analyzed top-level node does, from its IR by resolved var
  identity: the vars it defines at its top (:defs, each def-info) and inside a function body
  (:inline-defs), its multimethod methods ({:multi sym-as-written :dispatch value}) and
  protocol extensions ({:protocol sym :type name}), the multimethods it sets up, the vars and
  host statics (Class/member) it references (:refs, with the vars its quoted data names:
  quoted-vars), the qualified keywords its constants name (:keywords: the attributes, config keys
  and schema entries its code reads or writes), the banned vars it references, the
  calls it bounds by a clock (:timeouts: deref with a timeout), its catch clauses
  (:catches, each {:class :answers}), the namespaces it requires, and whether it changes its
  namespace's environment (:env?)."
  {:malli/schema [:=> [:cat :map] :map]}
  [node]
  (let [acc (volatile! {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{} :refs #{} :keywords #{} :banned #{} :requires #{} :env? false
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
         ;; a scalar names no var unless it is a qualified symbol, and no data but a qualified
         ;; keyword: most constants (unqualified keywords, strings, numbers) cost no walk
         (:quote :const) (let [x (datum n)]
                           (cond (qualified-keyword? x) (vswap! acc update :keywords conj x)
                                 (qualified-symbol? x) (vswap! acc update :refs into (quoted-vars x))
                                 ;; a quoted collection: its vars (one quoted-vars) and its keywords
                                 (coll? x) (vswap! acc #(-> % (update :refs into (quoted-vars x))
                                                            (update :keywords into (filter qualified-keyword?) (tree-seq coll? seq x))))))
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
         ;; a def's metadata keys name its var's metadata (:malli/schema), not data its code
         ;; operates on: those its walk added are dropped (a deftest's body, its :test, kept)
         (if (= :def (:op n))
           (let [kws (:keywords @acc)
                 meta-keys (let [m (:meta n)] (set (when (map? m) (filter qualified-keyword? (keys m)))))]
             (ir/reduce-ir-children (fn [_ c] (walk c in-fn?)) nil n)
             (vswap! acc update :keywords #(into kws (remove meta-keys) %)))
           (ir/reduce-ir-children (fn [_ c] (walk c in-fn?)) nil n))))
     node false)
    @acc))

(defn- merge-accounts
  "The accounts of a form's units as one."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [as]
  (reduce (fn [a b] (merge-with (fn [x y] (if (boolean? x) (or x y) (into x y))) a b))
          {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{} :refs #{} :keywords #{} :banned #{} :requires #{} :env? false
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
  "The macros form (as read) calls in namespace nsx: each list in it whose head resolves
  there to a macro var. The IR is the expansion, which names no macro, and a
  top-level call expanding to a do leaves its units' forms no trace of it (Clojure's
  compilation-unit rule), so the form itself is read. A macro's own template names the
  macros it expands to as quoted vars (quoted-vars), which closes the chain."
  {:malli/schema [:=> [:cat :seon.host/namespace :any] [:set :qualified-symbol]]}
  [nsx form]
  (into #{} (keep (fn [x] (when (and (seq? x) (symbol? (first x)))
                            (let [v (ns-resolve nsx (first x))] (when (and (var? v) (:macro (meta v))) (symbol v))))))
        (tree-seq coll? seq form)))

(defn form-id
  "A form's identity: its namespace and exact text, by their content hash (hasch's uuid, the
  one Datahike names content by; Clojure's 32-bit hash let two texts of one length share an
  id), and an imported file's repeated text adds its occurrence."
  {:malli/schema [:=> [:cat :symbol :string :int] :string]}
  [nsym src k]
  (str nsym "/" (hasch/uuid [nsym src]) (when (pos? k) (str "#" k))))

(defn- names
  "The qualified keywords a schema or contract form names."
  {:malli/schema [:=> [:cat :any] [:set :qualified-keyword]]}
  [form] (set (filter qualified-keyword? (tree-seq coll? seq form))))

(defn account
  "The program account of one top-level form, or nil when it is none of the program's.
  opts: :nodes (each analyzed unit's node; or :units, their unit-accounts), :src (its exact text), :form
  (src as the reader read it: macro-uses walks it, so the text is read once), :ns (the namespace
  it was evaluated in), :ns-after (where it left the REPL), :ns-form? (it is an `ns` form:
  its head resolves to clojure.core/ns), :before (readback: var -> root before; nil for an
  analysis without running: every top-level def counts), :ran? (the form returned; a form
  that threw records only the vars it set), :ns-meta (an ns form's name's metadata: Jolt's
  ns drops it, jolt/ns-meta), :lead, :order, :k (occurrence of its text)."
  {:malli/schema [:=> [:cat [:map [:src :string] [:form :any]]] [:maybe :map]]}
  [{:keys [nodes units src form ns ns-after ns-form? ns-meta before ran? lead order k] :or {k 0}}]
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
               :refs (into (:refs a) (macro-uses nsx form)) :requires (:requires a) :banned (:banned a) :inline-defs (:inline-defs a)
               ;; its code's keywords and its contracts': the data it operates on, read once here
               :keywords (into (:keywords a) (comp (keep :contract) (mapcat (comp names sedn/decode))) defs)
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

(def pointer "Each definition kind's form pointer." {:var :var/form :method :method/form :impl :impl/form :ns :ns/form})

(def ^:private form-sets
  "A form's card-many facts, as stored and as its row (form-row) and its account name them."
  [[:form/refs :refs] [:form/keywords :keywords] [:form/requires :requires] [:form/banned :banned] [:form/inline-defs :inline-defs]
   [:form/timeouts :timeouts] [:form/catches :catches]])

(defn- form-of
  "The form entity definition entity e points to, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :int]]}
  [db e] (some #(:db/id (get (d/entity db e) %)) (vals pointer)))

(defn def-eid
  "The entity of definition key k ([:var v], [:method multi dispatch], [:impl protocol type],
  [:ns n]) on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe :int]]}
  [db [kind a b]]
  (let [[attr v] (case kind :var [:var/name a] :method [:method/key (method-key a b)]
                       :impl [:impl/key (impl-key a b)] :ns [:ns/name a])]
    (pdb/eid db attr v)))

(defn def-ref
  "The entity map naming definition k: e, its entity (def-eid) when the db has it, else an
  upsert by its identity with its tempid (Datahike allocates a tempid first seen as a ref,
  in :form/defines, before the map that upserts it, and a form defining two existing
  definitions, a protocol or a record, then conflicts)."
  {:malli/schema [:=> [:cat :any [:maybe :int]] :map]}
  [[kind a b] e]
  (cond-> (case kind
            :var {:db/id (var-tid a) :var/name a :var/ns (ns-tid (symbol (namespace a)))}
            :method (let [k (method-key a b)] {:db/id (str "method " k) :method/key k :method/multi a :method/dispatch b})
            :impl (let [k (impl-key a b)] {:db/id (str "impl " k) :impl/key k :impl/protocol a :impl/type b})
            :ns {:db/id (ns-tid a) :ns/name a})
    e (assoc :db/id e)))

(defn form-tx
  "Form id as one entity map, from row (a form's account or a merge's form content): its
  text, namespace, order and lead, the definitions and declarations it names (def-ref's ids,
  each definition's entity read from eid-of: a map, or def-eid on the db) and its form-sets."
  {:malli/schema [:=> [:cat :string :map ifn?] :map]}
  [id {:keys [ns src order lead defines declares] :as row} eid-of]
  (into (cond-> {:db/id (form-tid id) :form/id id :form/src src :form/ns (ns-tid ns) :form/order order}
          lead (assoc :form/lead lead)
          (seq defines) (assoc :form/defines (mapv #(:db/id (def-ref % (eid-of %))) defines))
          (seq declares) (assoc :form/declares (mapv #(:db/id (def-ref [:var %] (eid-of [:var %]))) declares)))
        (for [[a k] form-sets :let [v (get row k)] :when (seq v)] [a (vec v)])))

(defn def-tx
  "tx-data making definition k point at the form content names (content {:form :kind
  :contract :doc :file}), retracting what it held that content does not, or, for nil
  content, retracting what it held. e: its entity on db (def-eid), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :any [:maybe :int] [:maybe :map]] :seon.db/tx-data]}
  [db k e content]
  (let [x (when e (d/entity db e))
        attrs (case (first k) :var [:var/form :var/kind :var/contract :var/doc] :ns [:ns/form :ns/file] [(pointer (first k))])
        now (when content
              (cond-> {(pointer (first k)) (form-tid (:form content))}
                (:kind content) (assoc :var/kind (:kind content))
                (:contract content) (assoc :var/contract (:contract content))
                (:doc content) (assoc :var/doc (:doc content))
                (:file content) (assoc :ns/file (:file content))))]
    (into (if content [(merge (def-ref k e) now) {:db/id (form-tid (:form content)) :form/id (:form content)}] [])
          (for [a attrs :let [old (get x a)] :when (and (some? old) (not (contains? now a)))]
            [:db/retract e a (if ((set (vals pointer)) a) (:db/id old) old)]))))

(def meaning
  "What a definition says of itself that text describing it relies on, so what a wiki section
  citing it goes stale on (seon.problems/stale-sections) and an eval that changes it shows
  (meaning-changes): its contract and its docstring, the facts def-tx asserts and retracts
  (DECISIONS Dogfood rulings; the contract carries the arity)."
  [:var/contract :var/doc])

(defn meaning-changes
  "What tx-data (an eval's definitions, def-tx's, before they are transacted on db) changes of
  each existing var's `meaning`: [{:var :was :now :cited-by}], :was and :now its changed
  attributes' values (a removed one absent from :now), :cited-by the wiki sections citing the var
  (their :seon.section/references), each with the form that reads it. A new var changes nothing a
  section relied on; nil when nothing changed. Cost: the tx-data walked once, then per var it
  names one entity read and one index seek of its citations."
  {:malli/schema [:=> [:cat :seon.host/db :seon.db/tx-data] [:maybe [:vector :seon.program/meaning-change]]]}
  [db tx-data]
  (let [asserted (for [m tx-data :when (and (map? m) (symbol? (:var/name m)) (some #(contains? m %) meaning))] [(:var/name m) (select-keys m meaning)])
        retracted (for [x tx-data :when (and (vector? x) (= :db/retract (first x)) (some #{(nth x 2 nil)} meaning))
                        :let [n (:var/name (d/entity db (second x)))] :when n]
                    [n (nth x 2)])
        names (distinct (concat (map first asserted) (map first retracted)))
        changes (for [n names
                      :let [e (d/entity db [:var/name n])] :when e
                      :let [was (into {} (keep (fn [a] (some->> (get e a) (vector a)))) meaning)
                            now (reduce (fn [m a] (dissoc m a)) (reduce merge was (for [[k v] asserted :when (= k n)] v))
                                        (for [[k a] retracted :when (= k n)] a))
                            changed (filter #(not= (get was %) (get now %)) meaning)]
                      :when (seq changed)]
                  {:var n :was (select-keys was changed) :now (select-keys now changed)
                   :cited-by (vec (sort-by :section (for [dt (d/datoms db :avet :seon.section/references (:db/id e))
                                                          :let [a (:seon.section/address (d/entity db (:e dt)))]]
                                                      ;; the form's head named, not quoted: the reach walk follows a quoted name,
                                                      ;; and every test reaching this would reach the help page's file reads
                                                      {:section a :read (list (symbol "my.agent" "help") a)})))})]
    (not-empty (vec changes))))

(defn- def-keys
  "The definition keys account acct makes: its vars, methods, extensions and, for an ns
  form, its namespace."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [{:keys [ns ns-form? defs methods impls]}]
  (vec (concat (for [{v :var} defs] [:var v])
               (for [{:keys [multi dispatch]} methods] [:method multi dispatch])
               (for [{:keys [protocol type]} impls] [:impl protocol type])
               (when ns-form? [[:ns ns]]))))

(defn account-tx
  "The facts of a form's account on db: the form (keeping its order when it is already
  there, else taking the place of the forms it supersedes, else after every form: order
  next-order), each definition pointing to it (def-tx), and the forms it supersedes
  retracted (each whose every definition it redefines). An account names no definition it
  lacks: a text form's is its id, text, namespace, lead and order."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.db/tx-data]}
  [db {:keys [id ns ns-form? defs declares order] :as acct} next-order]
  (let [fe (pdb/eid db :form/id id)
        dkeys (def-keys acct)
        eids (into {} (for [k (into dkeys (map #(vector :var %)) declares)] [k (def-eid db k)]))
        des (set (keep eids dkeys))
        olds (disj (set (keep #(form-of db %) des)) fe nil)
        superseded (filterv (fn [o] (every? #(contains? des (:db/id %)) (:form/defines (d/entity db o)))) olds)
        order (or (when fe (:form/order (d/entity db fe)))
                  order
                  (when (seq superseded) (apply min (map #(:form/order (d/entity db %)) superseded)))
                  next-order)
        lead (or (:lead acct) (some #(:form/lead (d/entity db %)) superseded))]
    (vec (concat
           (for [n (into #{ns} (map #(symbol (namespace (:var %)))) defs)] {:db/id (ns-tid n) :ns/name n})
           [(form-tx id (assoc acct :order order :lead lead :defines dkeys) eids)]
           (map #(def-ref [:var %] (eids [:var %])) declares)
           (mapcat (fn [{v :var :keys [kind contract doc]}] (def-tx db [:var v] (eids [:var v]) {:form id :kind kind :contract contract :doc doc})) defs)
           (mapcat #(def-tx db % (eids %) {:form id}) (filter (comp #{:method :impl} first) dkeys))
           (when ns-form? [{:db/id (ns-tid ns) :ns/form (form-tid id)}])
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
  "Form entity e as the loader and the merge read it (:source when it is the text of the
  source tree the binary was built from, :form/source: its :order is its place in that file)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db e]
  (let [f (d/entity db e)]
    (cond-> (into {:e e :id (:form/id f) :src (:form/src f) :ns (:ns/name (:form/ns f)) :order (:form/order f)
                   :defines (into #{} (keep definition-key) (:form/defines f))
                   :declares (into #{} (map :var/name) (:form/declares f))}
                  (for [[a k] form-sets] [k (set (get f a))]))
      (:form/lead f) (assoc :lead (:form/lead f))
      (:form/source f) (assoc :source true))))

(defn- live-ids
  "{form id -> entity} of db's live forms (live?), from four index scans and no entity read:
  a form that defines nothing, or one a definition points to. So two values' difference is
  their programs' (reach-update), at index speed."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :string :int]]}
  [db]
  (let [defining (into #{} (map :e) (d/datoms db :aevt :form/defines))
        pointed (into #{} (comp (mapcat #(d/datoms db :aevt %)) (map :v)) [:var/form :method/form :impl/form :ns/form])]
    (into {} (for [dt (d/datoms db :aevt :form/id) :when (or (not (defining (:e dt))) (pointed (:e dt)))] [(:v dt) (:e dt)]))))

(defn live-forms
  "Every live form of db, as form-row, in entity order (live-ids: index scans, then one row a
  live form)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (mapv #(form-row db %) (sort (vals (live-ids db)))))

(defn account-diff-tx
  "account-tx of acct at its own order and lead (a file's), less what db holds already:
  nothing for a form db holds with the same facts (its text, what it defines, declares and
  names, each definition pointing to it with the same kind, contract and doc), only its
  order and lead for one that only moved, and otherwise account-tx with the form's facts
  the account no longer names retracted. A file read again writes what changed in it, not
  the file (task seon.source/an-import-reads-only-changed-files)."
  {:malli/schema [:=> [:cat :seon.host/db :map] :seon.db/tx-data]}
  [db {:keys [id order lead defs declares] :as acct}]
  (let [fe (pdb/eid db :form/id id)
        dkeys (def-keys acct)
        now (into {:id id :src (:src acct) :ns (:ns acct) :defines (set dkeys) :declares (set declares)}
                  (for [[_ k] form-sets] [k (set (get acct k))]))
        row (when fe (form-row db fe))
        held? (and row (= now (select-keys row (keys now)))
                   (every? #(= fe (some->> (def-eid db %) (form-of db))) dkeys)
                   (every? (fn [{v :var :keys [kind contract doc]}]
                             (let [x (d/entity db (def-eid db [:var v]))]
                               (and (= kind (:var/kind x)) (= contract (:var/contract x)) (= doc (:var/doc x)))))
                           defs))]
    (cond
      (and held? (= order (:order row)) (= lead (:lead row))) []
      held? [(cond-> {:db/id fe :form/order order} lead (assoc :form/lead lead))]
      :else (into (vec (for [op (account-tx db acct order)]
                         (if (and (map? op) (= (form-tid id) (:db/id op)) (:form/src op))
                           (cond-> (assoc op :form/order order) lead (assoc :form/lead lead))
                           op)))
                  (when row
                    (concat (for [[a k] form-sets v (get row k) :when (not (contains? (get now k) v))] [:db/retract fe a v])
                            (for [k (:defines row) :when (not (contains? (:defines now) k))] [:db/retract fe :form/defines (def-eid db k)])
                            (for [v (:declares row) :when (not (contains? (:declares now) v))] [:db/retract fe :form/declares (def-eid db [:var v])])))))))

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

(defn closure
  "The nodes reachable from start through (neighbors node), start excluded, each once, in
  the order reached. One visit per node and edge."
  {:malli/schema [:=> [:cat [:or [:sequential :any] [:set :any]] ifn?] [:vector :any]]}
  [start neighbors]
  ;; one vector is the queue and the answer: a cursor walks it, each new node appended once
  (let [q0 (vec (distinct start)) k (count q0)]
    (loop [q q0 i 0 seen (set q0)]
      (if (< i (count q))
        (if-let [ns (seq (neighbors (nth q i)))]
          (let [new (into [] (comp (remove seen) (distinct)) ns)]
            (recur (into q new) (inc i) (into seen new)))
          (recur q (inc i) seen))
        (subvec q k)))))

(defn components
  "The strongly connected components of nodes under (neighbors node), each a vector, in
  reverse topological order: each after every component it reaches. Tarjan's, with a stack
  of frames rather than recursion (any depth of graph); one visit per node and edge."
  {:malli/schema [:=> [:cat [:sequential :any] ifn?] [:vector [:vector :any]]]}
  [nodes neighbors]
  (let [index (volatile! (transient {})) low (volatile! (transient {})) on (volatile! (transient #{}))
        stack (volatile! (transient [])) out (volatile! (transient [])) n (volatile! 0)
        enter! (fn [v] (vswap! index assoc! v @n) (vswap! low assoc! v @n) (vswap! n inc)
                 (vswap! stack conj! v) (vswap! on conj! v))]
    (doseq [root nodes :when (nil? (get @index root))]
      (enter! root)
      (loop [frames [[root (vec (neighbors root)) 0]]]
        (when-let [[v ns i] (peek frames)]
          (if (< i (count ns))
            (let [w (nth ns i) frames (conj (pop frames) [v ns (inc i)])]
              (cond (nil? (get @index w)) (do (enter! w) (recur (conj frames [w (vec (neighbors w)) 0])))
                    (get @on w) (do (vswap! low assoc! v (min (get @low v) (get @index w))) (recur frames))
                    :else (recur frames)))
            (let [frames (pop frames)]
              (when-let [[u] (peek frames)] (vswap! low assoc! u (min (get @low u) (get @low v))))
              (when (= (get @low v) (get @index v))
                (vswap! out conj! (loop [c []]
                                    (let [w (nth @stack (dec (count @stack)))]
                                      (vswap! stack pop!) (vswap! on disj! w)
                                      (if (= w v) (conj c w) (recur (conj c w)))))))
              (recur frames))))))
    (persistent! @out)))

(defn load-order
  "forms (form rows) in the order to load them: namespaces in dependency order (after each
  namespace among them that their forms require or whose vars they reference; ties by
  name), and within a namespace by order, each form after the earliest form among them
  that interns (defines or declares) a var it references, unless both were read from a file
  (:source): a file's own order is kept, since it already loads, though a form's :refs name
  the vars its quoted data names too (seon.db/install-missing! names 'seon.db/open!, defined
  after it; task seon.program/load-dependencies-leave-quoted-names-out). Cost: one pass over
  the forms and their references."
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
        deps (fn [f] (for [r (:refs f) :let [g (interns r)]
                           :when (and g (not= g (:id f)) (= (:ns f) (:ns (by-id g))) (not (and (:source f) (:source (by-id g)))))]
                       g))]
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
  (let [rows (into {} (map (juxt :e identity)) changed)
        row #(or (rows %) (form-row db %))]
    (mapv row (closure (map :e changed)
                       (fn [e] (for [[k s] (:defines (row e))
                                     :when (and (= :var k) (= :macro (:var/kind (d/entity db [:var/name s]))))
                                     e' (requiring db (symbol (namespace s)))
                                     :when (live? db e')]
                                 e'))))))

(defn schema-dependents
  "The live forms of db defining a contracted var whose form names one of keys (its contract
  or its code, :form/keywords: one AVET seek a key), or a schema that names one, and on (the
  binary's schema forms with db's changes): the forms that re-arm against the changed schemas
  (a form whose code alone names one re-arms too, unchanged). Nothing when keys is empty."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-keyword]] [:vector :map]]}
  [db keys]
  (if (empty? keys)
    []
    (let [naming (reduce (fn [m [k f]] (reduce #(update %1 %2 (fnil conj []) k) m (names f)))
                         {} (merge contract/forms (schema-changes db)))
          reached (into keys (closure keys naming))]
      (mapv #(form-row db %)
            (distinct (for [k reached
                            dt (d/datoms db :avet :form/keywords k)
                            :let [fe (:e dt)]
                            :when (and (live? db fe) (some :var/contract (:form/defines (d/entity db fe))))]
                        fe))))))

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
    (catch Throwable e {:ns ns :message (step/error-msg e)})))

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
  built only for a form db-to has live that db-from had not (measured on Seon's
  program: four live-forms reads, ~1.4 s, before)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.program/error]]}
  [db-from db-to]
  (let [def-keys (fn [db] (into #{} (for [a (vals pointer) dt (d/datoms db :aevt a)] (definition-key (d/entity db (:e dt))))))
        from (live-ids db-from)
        to-defs (def-keys db-to)
        sc-from (schema-changes db-from)]
    (doseq [k (sort-by str (def-keys db-from)) :when (not (contains? to-defs k))] (remove-definition! k))
    (load! db-to (filterv #(not (held? db-to %))
                          (for [e (sort (vals (apply dissoc (live-ids db-to) (keys from))))] (form-row db-to e)))
           (set (for [[k v] (schema-changes db-to) :when (not= v (get sc-from k))] k)))))

(defn load-changes!
  "Load what resolved tx-data ops change, read against db (after them): each definition
  they leave pointing to no form is removed, and each live form they assert (but the
  binary's own) is loaded, with what it reaches (load!: where a changed macro expanded, and
  the contracts a changed schema entry reaches). Answers the error
  maps (empty when all loaded)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.db/op]] [:vector :seon.program/error]]}
  [db ops]
  (let [ptr (set (vals pointer))
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
  the :var/form and :schema/form datoms that differ between the two (d/datoms-diff, which
  skips the index subtrees they share), and two seeks per entry among them: what the branch
  changed, not the program (before, both dbs' every :var/form and :schema/form datom, each
  turn)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db]
                  [:map-of [:enum :added :changed :removed :schemas] [:vector [:or :qualified-symbol :qualified-keyword]]]]}
  [db-from db-to]
  (let [;; the entries whose ids differ, by name, then compared by content
        differ (fn [attr name-attr content]
                 (let [[gone added] (d/datoms-diff db-from db-to :aevt [attr])
                       to (into #{} (map :e) added)]
                   (into #{} (for [e (into to (map :e) gone)
                                   :let [n (name-attr (d/entity (if (to e) db-to db-from) e))
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
  missed name reruns more, never reuses a stale green). Measured: with
  seon.config/baked-schema-forms (a macro every contracted form expands) counted, 327 of 368
  tests took the tree, so any edit anywhere reran them."
  '#{seon.config/baked-schema-forms ;; resources/seon/schemas, in :env
     seon.fork-server/dev-classpath! ;; deps.edn, in :env
     seon.fx/declared-refs ;; resources/seon/system.edn, in :env
     seon.web/handler ;; web/public, in :env
     seon.proofs/greens}) ;; the results directory, outside the tree, by digest

(defn- tree-reading?
  "Does form f read the tree (a tree-reader referenced, by a form defining no env-reader)?"
  {:malli/schema [:=> [:cat :map] :boolean]}
  [f] (boolean (and (some tree-readers (:refs f))
                    (not (and (seq (:defines f)) (every? (fn [[k s]] (and (= :var k) (env-readers s))) (:defines f)))))))

(def ^:private names-only
  "The vars whose forms only name other vars, as quoted data: reaching one is neither using nor
  reaching what it names, so their references give the reach walk no edges. seon.config/schema-forms
  is every schema baked into one literal, whose :seon.render/* properties name every render: an
  edge from it put every render, and through seon.docs/cited the tree, under every contracted
  call (680 of 706 tests took the tree digest, 2026-10-25); a check reaches the schemas it
  checks through its contract's own schema rows, and the baked schemas are :env."
  '#{seon.program/tree-readers seon.program/env-readers seon.config/schema-forms})

(defn- uses?
  "Does form f use one of vars (:seon.config.source/process-vars or -binary-vars)? A form uses
  a var it references and does not define (a var's own form names itself), and not when it
  only names vars as data (names-only: tree-readers lists the binary-sh that the reach walk's
  own tests never run)."
  {:malli/schema [:=> [:cat [:set :qualified-symbol] :map] :boolean]}
  [vars f]
  (let [own (set (keep (fn [[k s]] (when (= :var k) s)) (:defines f)))]
    (boolean (and (not-any? names-only own) (some #(and (vars %) (not (own %))) (:refs f))))))

(defn- schema-texts
  "{schema key -> its form's text} of db, from two index scans."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [text (into {} (map (juxt :e :v)) (d/datoms db :aevt :schema/form))]
    (into {} (for [dt (d/datoms db :aevt :schema/key)] [(:v dt) (text (:e dt))]))))

(defn- schema-row? {:malli/schema [:=> [:cat :map] :boolean]} [f] (contains? f :schema))

(defn- reach-step
  "Graph g after the forms removed (ids) and the rows added (form-row's, live on db): each
  form's neighbours recomputed only where its edges can have changed (an added form; a form
  referring to, or defining the methods, extensions or contract of, a var a changed form
  defines), and the components of the changed forms and of every form reaching them re-folded
  (components over those alone, the rest kept). :affected, how many forms were re-folded."
  {:malli/schema [:=> [:cat :map :seon.host/db [:sequential :string] [:sequential :map]] :map]}
  [g db removed added]
  (let [data-refs (:data-refs g)
        old (:rows g)
        gone (keep old removed)
        defined (fn [f kind] (for [[k a] (:defines f) :when (= kind k)] a))
        V (set (mapcat #(defined % :var) (concat gone added)))
        M (set (mapcat #(defined % :method) (concat gone added)))
        P (set (mapcat #(defined % :impl) (concat gone added)))
        drop-ids (set removed)
        gone-ids (reduce disj drop-ids (map :id added))
        step (fn [m k ids] (update m k #(into (into [] (remove drop-ids) %) ids)))
        by (fn [m kind] (reduce (fn [m f] (reduce #(step %1 %2 [(:id f)]) m (defined f kind)))
                                (reduce (fn [m f] (reduce #(step %1 %2 []) m (defined f kind))) m gone) added))
        rows (into (apply dissoc old removed) (map (juxt :id identity)) added)
        defs (by (:defs g) :var) methods (by (:methods g) :method) impls (by (:impls g) :impl)
        var-form #(peek (get defs %))
        facts (for [v V] [v (d/entity db [:var/name v])])
        sids (fn [text] (into [] (comp (map #(str "schema " %)) (filter rows)) (names (sedn/decode text))))
        contract (reduce (fn [m [v e]] (if-let [t (:var/contract e)] (assoc m v (sids t)) (dissoc m v))) (:contract g) facts)
        tests (reduce (fn [s [v e]] (if (= :test (:var/kind e)) (conj s v) (disj s v))) (:tests g) facts)
        refd (reduce (fn [m f] (reduce #(update %1 %2 (fnil conj #{}) (:id f)) m (:refs f)))
                     (reduce (fn [m f] (reduce #(update %1 %2 disj (:id f)) m (:refs f))) (:refd g) gone) added)
        ;; a test's form is reached only as the root it is: another form naming a test (its
        ;; name quoted as data, a proof list) never runs it, clojure.test does
        test-ids (set (keep var-form tests))
        edges (fn [f] (if (schema-row? f)
                        (vec (:schema f))
                        ;; a names-only form names vars as data: no edges from its references,
                        ;; its contract's, methods' and data's kept
                        (vec (concat (when-not (some (fn [[k s]] (and (= :var k) (names-only s))) (:defines f))
                                       (remove test-ids (keep var-form (:refs f))))
                                     (for [s (defined f :var)
                                           id (concat (get methods s) (get impls s) (get contract s) (keep var-form (get data-refs s)))]
                                       id)))))
        holders (for [[s vs] data-refs :when (some V vs) id (get defs s)] id)
        dirty (-> (set (map :id added))
                  (into (mapcat #(get refd %)) V)
                  (into (keep var-form) (concat V M P))
                  (into holders)
                  (as-> d (into #{} (remove gone-ids) d)))
        adj (into (apply dissoc (:adj g) gone-ids) (for [id dirty] [id (edges (rows id))]))
        rev (as-> (:rev g) r
              (reduce (fn [r id] (reduce #(update %1 %2 disj id) r (get-in g [:adj id]))) r (concat dirty gone-ids))
              (reduce (fn [r id] (reduce #(update %1 %2 (fnil conj #{}) id) r (adj id))) r dirty)
              (apply dissoc r gone-ids))
        flag (fn [k pred] (into (apply disj (k g) removed) (comp (filter pred) (map :id)) added))
        pvars (cfg/value :seon.config.source/process-vars)
        bvars (cfg/value :seon.config.source/binary-vars)
        reads (flag :reads tree-reading?) proc (flag :proc #(uses? pvars %)) bin (flag :bin #(uses? bvars %))
        plain? (fn [f] (and (not (schema-row? f)) (not-any? (fn [[k]] (= :var k)) (:defines f))))
        plain (as-> (:plain g) pl
                (reduce (fn [pl f] (update pl (:ns f) disj (:id f))) pl (filter plain? gone))
                (reduce (fn [pl f] (update pl (:ns f) (fnil conj #{}) (:id f))) pl (filter plain? added)))
        own (into (apply dissoc (:own g) removed) (for [f added] [(:id f) (error/digest (str (:ns f) "\n" (:src f)))]))
        changed (into dirty (map :id) added)
        affected (into changed (closure changed #(get rev %)))
        old-comp (:comp-of g)
        olds (keep old-comp affected)
        ;; the affected components re-folded sinks first (Bazel's and Nix's action keys): a
        ;; component's digest is its forms' own and those of the components it reaches
        [comp-of cd fl n] (reduce (fn [[comp-of cd fl n] c]
                                    (let [ms (set c)
                                          kids (distinct (for [id c w (adj id) :when (not (ms w))] (get comp-of w)))
                                          any (fn [k fs] (boolean (or (some fs c) (some #(get-in fl [% k]) kids))))]
                                      [(reduce #(assoc! %1 %2 n) comp-of c)
                                       (assoc! cd n (error/digest (str (str/join " " (sort (map own c))) "|" (str/join " " (sort (map cd kids))))))
                                       (assoc! fl n {:reads (any :reads reads) :proc (any :proc proc) :bin (any :bin bin)})
                                       (inc n)]))
                                  [(transient (apply dissoc old-comp gone-ids))
                                   (transient (apply dissoc (:cd g) olds))
                                   (transient (apply dissoc (:flags g) olds))
                                   (:next g)]
                                  (components (vec affected) (fn [id] (filter affected (adj id)))))]
    (assoc g :rows rows :defs defs :methods methods :impls impls :contract contract :tests tests :refd refd :adj adj :rev rev
           :reads reads :proc proc :bin bin :plain plain :own own
           :comp-of (persistent! comp-of) :cd (persistent! cd) :flags (persistent! fl) :next n :affected (count affected))))

(defn reach-graph
  "db's reach graph, a value: its live forms and schema entries (a schema entry's :schema, the
  entries its form names), each one's neighbours (every reference, method, extension,
  contract's schema entry and data-refs' names: inputs' :data-refs, system.edn's procs, which
  root resolves by name), and each strongly connected component's Merkle digest and whether it
  or what it reaches reads the tree, uses a process var or a binary var (uses?), folded once,
  sinks first. graph-index answers from it; reach-update changes it."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] :map]}
  [db data-refs]
  (let [schemas (schema-texts db)
        sid #(str "schema " %)
        live (live-ids db)]
    (reach-step {:rows {} :defs {} :methods {} :impls {} :contract {} :tests #{} :refd {} :adj {} :rev {}
                 :reads #{} :proc #{} :bin #{} :plain {} :own {} :comp-of {} :cd {} :flags {} :next 0
                 :data-refs data-refs :schemas schemas :live live}
                db []
                (into (mapv #(form-row db %) (vals live))
                      (for [[k text] schemas]
                        {:id (sid k) :ns k :src text :refs #{} :defines #{}
                         :schema (keep #(when (contains? schemas %) (sid %)) (names (sedn/decode text)))})))))

(defn reach-update
  "Graph g (reach-graph's, of another value) for db: the live forms g holds that db does not
  removed, db's that g does not added (live-ids: index scans, no entity read), and only the
  components those touch and their ancestors re-folded (reach-step), so a branch's graph is
  its base's at the cost of what it changed. A schema entry added or changed rebuilds."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [g db]
  (if (not= (:schemas g) (schema-texts db))
    (reach-graph db (:data-refs g))
    (let [live (live-ids db) was (:live g)]
      (assoc (reach-step g db (vec (remove live (keys was))) (vec (for [[id e] live :when (not (was id))] (form-row db e))))
             :live live))))

(defn graph-index
  "{test {:digest :process? :binary?}} of graph g's tests under inputs (or of each key of
  roots, {key [var ...]}): a key's digest is of its name, :env, :tree when what it reaches
  reads the tree, and its starting forms' components' digests (its forms and their
  namespaces' plain forms); :process? and :binary?, whether one of those components uses a
  process or a binary var. A root naming a var with no form in g gets none."
  {:malli/schema [:=> [:cat :map :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]]] :map]}
  [{:keys [rows defs tests plain comp-of cd flags]} {:keys [env tree]} roots]
  (let [var-form #(peek (get defs %))
        answer (fn [k ids]
                 (let [cs (distinct (map comp-of (into (vec ids) (mapcat #(get plain (:ns (rows %)))) ids)))]
                   {:digest (error/digest (apply str k "\n" env "\n" (when (some #(:reads (flags %)) cs) tree) (sort (map cd cs))))
                    :process? (boolean (some #(:proc (flags %)) cs)) :binary? (boolean (some #(:bin (flags %)) cs))}))]
    (if roots
      ;; a root naming a var with no form here (renamed, not imported) gets no digest: its
      ;; caller names it missing, never a digest of :env alone that a stale green matches
      (into {} (for [[k vars] roots :when (every? var-form vars)] [k (answer k (map var-form vars))]))
      (into {} (for [t tests :let [id (var-form t)] :when id] [t (answer t [id])])))))

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
  test's digest moves when a form or schema entry it reaches does, or the shape of what it
  reaches (which form calls which): each strongly connected component's Merkle digest
  (reach-graph). Cost: the live forms, contracts and schema entries read once and digested
  once, then one pass over the condensed graph (reach-graph, then graph-index). With roots ({key [var ...]}, a bin/check step's mode functions: seon.proofs/
  check-steps), the same digest of each key: what its vars' forms and their namespaces'
  plain forms reach; a key one of whose vars has no form here has none."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.program/inputs] [:map-of :qualified-symbol :string]]
                  [:=> [:cat :seon.host/db :seon.program/inputs [:map-of :string [:sequential :qualified-symbol]]] [:map-of :string :string]]]}
  ([db inputs] (reach-digests db inputs nil))
  ([db inputs roots] (update-vals (graph-index (reach-graph db (:data-refs inputs)) inputs roots) :digest)))

(defn program-digest
  "The digest of db's program as a whole: its live forms (:form/id, hasch of a form's namespace
  and text, docs/storage.md#ids: a replaced form is retracted), its schema entries (:schema/form)
  and its namespaces' tails, each by its entity, a content id of its identity (docs/storage.md#ids),
  so the index's order is the same on every branch and nothing is sorted. It moves when a form, schema entry or
  ns tail is added, replaced or removed, and not on a write of data. What a main run of the test
  service reads of main's program (seon.root/prove!): per-test digests (reach-digests) need the
  checkout's tests loaded, which is the run's own work. Cost: one read of three attributes'
  datoms (measured on the standing root's main, 2699 forms, 660 schema entries, 129
  namespaces: 0.79M ticks, 5.0 MB, of which the index read is 0.52M ticks, 3.9 MB)."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db]
  (let [b (StringBuilder.)]
    (doseq [dt (d/datoms db :aevt :form/id)] (.append b (:e dt)) (.append b "\n"))
    (doseq [a [:schema/form :ns/tail] dt (d/datoms db :aevt a)] (.append b (:e dt)) (.append b " ") (.append b (:v dt)) (.append b "\n"))
    (error/digest (.toString b))))

(defn reach-index
  "{test {:digest :process? :binary?}} of db's tests under inputs, from the one graph (reach-graph, graph-index):
  its reach digest (reach-digests), whether it reaches a process of its own (a var of
  :seon.config.source/process-vars: process-tests) and whether it uses the built binary (a var of
  :seon.config.source/binary-vars: bin/test builds target/seon only for these); uses? says what
  is a use. With roots (a
  bin/check step's mode functions, as reach-digests takes them), the same of each key."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.program/inputs] [:map-of :qualified-symbol [:map [:digest :string] [:process? :boolean] [:binary? :boolean]]]]
                  [:=> [:cat :seon.host/db :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]]] [:map-of :any [:map [:digest :string] [:process? :boolean] [:binary? :boolean]]]]]}
  ([db inputs] (reach-index db inputs nil))
  ([db inputs roots] (graph-index (reach-graph db (:data-refs inputs)) inputs roots)))

(defn process-path
  "Why test t of graph g (reach-graph's) is a process test: the shortest chain of calls from its
  form to a form that uses a process var (:seon.config.source/process-vars), as the vars each
  form on it defines, and the process vars that last form uses: {:uses [var ...] :through [var
  ...]}, or nil when t reaches none. Cost: a breadth-first walk of what t reaches, stopping at the
  first form that uses one."
  {:malli/schema [:=> [:cat :map :qualified-symbol] [:maybe [:map [:uses [:vector :qualified-symbol]] [:through [:vector :qualified-symbol]]]]]}
  [{:keys [rows defs adj proc]} t]
  (when-let [start (peek (get defs t))]
    (let [pvars (cfg/value :seon.config.source/process-vars)
          vars-of (fn [id] (sort-by str (keep (fn [[k s]] (when (= :var k) s)) (:defines (rows id)))))]
      (loop [frontier [start] from {start nil}]
        (when (seq frontier)
          (if-let [hit (first (filter proc frontier))]
            {:uses (vec (sort-by str (filter pvars (:refs (rows hit)))))
             :through (vec (mapcat vars-of (reverse (take-while some? (iterate from hit)))))}
            (let [step (for [id frontier w (adj id) :when (not (contains? from w))] [w id])
                  from' (reduce (fn [m [w id]] (if (contains? m w) m (assoc m w id))) from step)]
              (recur (vec (distinct (map first step))) from'))))))))

(defn process-tests
  "The keys of index (reach-index's answer) that reach a process of their own (a shell, a
  fork, a root booted for it, the binary): never run automatically, only when named (DECISIONS,
  Process tests are not automatic). Taking the index, not db, so a caller holding one walks once."
  {:malli/schema [:=> [:cat [:map-of :any [:map [:process? :boolean]]]] [:set :any]]}
  [index] (into #{} (keep (fn [[t {:keys [process?]}]] (when process? t))) index))
