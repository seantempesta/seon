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
            [seon.schema.datahike :as sd] [seon.edn :as sedn] [malli.registry :as mr] [malli.core :as m] [seon.error :as error] [seon.lib :as lib] [seon.db :as pdb] [seon.config :as cfg] [clojure.string :as str] [hasch.core :as hasch] [seon.reader :as reader]))

(defn scratch-ns?
  "Is namespace nsx scratch (never program): `user`, or declared ^:scratch."
  {:malli/schema [:=> [:cat :seon.host/namespace] :boolean]}
  [nsx]
  (or (= 'user (ns-name nsx)) (boolean (:scratch (meta nsx)))))

(def ^:private env-forms
  "The clojure.core forms a top-level form changes its namespace's environment by."
  #{'ns 'in-ns 'require 'use 'import 'refer 'refer-clojure 'alias 'load 'extend 'extend-type 'extend-protocol})

(defn definition-form?
  "Is form, read in namespace nsx, a definition at its top level: its head is the special form def
  or resolves (as the compiler does) to a var named def... (defn, deftest, defmethod, a
  library's defschema), or to a clojure.core form that changes the namespace's environment
  (ns, require, extend-type ...). An eval whose def sits beside calls, (let [r (f)] (def x r)), is
  none: it stays the recorded eval, never program a later load runs again (root's ruling:
  one ran my.agent/prove at every load of its branch)."
  {:malli/schema [:=> [:cat :any :seon.host/namespace] :boolean]}
  [form nsx]
  (boolean
    (when (and (seq? form) (symbol? (first form)))
      (or (= 'def (first form))
          (when-let [v (ns-resolve nsx (first form))]
            (when (var? v)
              (let [{n :name s :ns} (meta v)]
                (or (str/starts-with? (str n) "def")
                    (and (= 'clojure.core (ns-name s)) (contains? env-forms n))))))))))

(defn non-edn
  "The first value in v that is no EDN, depth first, as [path value] (path the keys and indices that
  reach it, a set member by its place in the set's seq), or nil when v is plain data that EDN prints
  and reads back equal (nil, booleans, numbers, strings, keywords, symbols, and lists, vectors, maps
  and sets of them): what a refused registration names, so its reader fixes that value (a predicate written
  unquoted, nat-int?, is a function value, its symbol 'nat-int? the data). See docs/references.md#resolving-and-showing."
  {:malli/schema [:=> [:cat :any] [:maybe [:tuple [:vector :any] :any]]]}
  [v]
  (letfn [(walk [path x]
            (cond (record? x) [path x]
                  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)) nil
                  (map? x) (some (fn [[k y]] (or (walk path k) (walk (conj path k) y))) x)
                  (coll? x) (some (fn [[i y]] (walk (conj path i) y)) (map-indexed vector x))
                  :else [path x]))]
    (walk [] v)))

(defn edn-value?
  "Is v plain data that EDN prints and reads back equal (`non-edn` finds none in it)?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v] (nil? (non-edn v)))

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

(def ^:private resolvers
  "The clojure.core functions that look a var up by its name at run time: a literal symbol
  passed to one is a reference, as a symbol in code is (any other quoted name is data, :quoted)."
  '#{clojure.core/resolve clojure.core/ns-resolve clojure.core/requiring-resolve})

(defn- resolved
  "The var a call of resolver f names with its literal arguments args (datum): the qualified
  symbol it is passed, or ns-resolve's namespace and name; nil when they are no literal names
  (a namespace object, a local) or the name is unqualified (resolve reads *ns* at run time). An
  aliased name (str/join) names no var of the program, so it reaches nothing."
  {:malli/schema [:=> [:cat :qualified-symbol [:sequential :any]] [:maybe :qualified-symbol]]}
  [f args]
  (let [s (last args) n (first args)]
    (cond (qualified-symbol? s) s
          (and (= 'clojure.core/ns-resolve f) (simple-symbol? s) (simple-symbol? n)) (symbol (name n) (name s)))))

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

(defn- quoted-names
  "The qualified symbols in a quoted datum (walked: a collection's too), by name: a name as
  data (a proc or render named in data, a form sent to an agent, `requiring-resolve`'s
  argument), which no call of this form reaches, except in a macro's template, whose quoted
  names are the expansion's code (unit-account). A symbol of no namespace is data. By name,
  never by whether its namespace or var exists yet: that depends on what this process
  analyzed before, so a refresh, which loads only the changed files, dropped what a whole
  import kept (240 against 250 tests left out on one tree)."
  {:malli/schema [:=> [:cat :any] [:set :qualified-symbol]]}
  [x] (into #{} (filter qualified-symbol?) (tree-seq coll? seq x)))

(defn unit-account
  "What the compiler says one analyzed top-level node does, from its IR by resolved var
  identity: the vars it defines at its top (:defs, each def-info) and inside a function body
  (:inline-defs), its multimethod methods ({:multi sym-as-written :dispatch value}) and
  protocol extensions ({:protocol sym :type name}), the multimethods it sets up, the vars and
  host statics (Class/member) it references (:refs, with each var a literal name passed to
  resolve, ns-resolve or requiring-resolve looks up; in a macro, also the names its template
  quotes, which its expansion calls), the qualified names its quoted data holds (:quoted:
  quoted-names, data, not references; a string holding a slash is read as code sent to a REPL: reader/forgive
  turns text that opens no form into a comment, so a bare name in a sentence is no name), the ops of the requests it builds (:ops: a map's constant keyword under :op), the qualified keywords its constants name (:keywords: the attributes, config keys
  and schema entries its code reads or writes), the banned vars it references,
  its catch clauses
  (:catches, each {:class :answers}), the classes it constructs (:news, as the analyzer names them, which
  `account` makes references), the namespaces it requires, and whether it changes its namespace's
  environment (:env?)."
  {:malli/schema [:=> [:cat :map] :map]}
  [node]
  (let [acc (volatile! {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{} :refs #{} :quoted #{} :keywords #{} :ops #{} :banned #{} :requires #{} :env? false
                        :catches #{} :news #{}})]
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
         ;; (T. x): the class as the analyzer names it, read as a reference by `account`, which knows the namespace
         :host-new (vswap! acc update :news conj (str (:class n)))
         ;; a scalar names no var unless it is a qualified symbol, and no data but a qualified
         ;; keyword: most constants (unqualified keywords, strings, numbers) cost no walk
         (:quote :const) (let [x (datum n)]
                           (cond (qualified-keyword? x) (vswap! acc update :keywords conj x)
                                 (qualified-symbol? x) (vswap! acc update :quoted conj x)
                                 ;; a quoted collection: its names (one quoted-names) and its keywords
                                 ;; (a request it sends, {:op k}, names its op: :ops)
                                 (coll? x) (vswap! acc #(reduce (fn [a v] (cond (qualified-keyword? v) (update a :keywords conj v)
                                                                                 (and (map? v) (keyword? (:op v))) (update a :ops conj (:op v))
                                                                                 :else a))
                                                                (update % :quoted into (quoted-names x)) (tree-seq coll? seq x)))
                                 ;; a string is code sent to a REPL (an agent's eval, a root's): read by the one
                                 ;; reader, its delimiters repaired (a str of fragments), its names are data as a
                                 ;; quoted form's are. A qualified name has a slash, so a string without one is not
                                 ;; read. forgive makes a line that opens no form a comment ("app.x/f is not code" names nothing; "app.x/f" alone does). Its keywords only when it opens a form: a message naming a config key is no read of it
                                 (and (string? x) (str/includes? x "/"))
                                 (let [fs (reader/read-text (:text (reader/forgive x)) 'user)]
                                   (vswap! acc #(cond-> (update % :quoted into (quoted-names fs))
                                                  (str/starts-with? x "(") (update :keywords into (filter qualified-keyword?) (tree-seq coll? seq fs)))))))
         ;; a map built at run time names its op when its :op is a constant keyword ({:op :eval :code (str ...)})
         :map (when-some [k (some (fn [[kn vn]] (when (and (= :const (:op kn)) (= :op (:val kn))) (let [v (datum vn)] (when (keyword? v) v)))) (:pairs n))]
                (vswap! acc update :ops conj k))
         :try (vswap! acc update :catches into (map sedn/encode) (catches n))
         :invoke (let [f (invoked n)]
                   ;; (resolve 'ns/x): the var it looks up by name is one it references
                   (when-some [s (when (resolvers f) (resolved f (mapv datum (:args n))))]
                     (vswap! acc update :refs conj s)))
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
    ;; a macro's template is its expansion's code: the names it quotes are what every form
    ;; calling it calls (macro-uses reaches the macro; this, what it expands to)
    (let [a @acc] (if (some :macro? (:defs a)) (update a :refs into (:quoted a)) a))))

(defn- merge-accounts
  "The accounts of a form's units as one."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [as]
  (reduce (fn [a b] (merge-with (fn [x y] (if (boolean? x) (or x y) (into x y))) a b))
          {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{} :refs #{} :quoted #{} :keywords #{} :ops #{} :banned #{} :requires #{} :env? false
           :catches #{} :news #{}}
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

(defn calls-eval?
  "Whether tap account acc's form calls eval, whose definitions the tap does not see (Jolt turns it off while a unit runs). See docs/agent.md#changing-a-definition."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:keys [nodes]}] (boolean (some #(contains? (:refs (unit-account %)) 'clojure.core/eval) nodes)))

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

(defn- def-meta
  "A defined var's own Clojure metadata keys (:var/meta in resources/seon/schemas/seon.facts.edn): from the live var when it is
  bound, merged over the compiler's account of its definition, with :macro when the compiler says the
  form is a macro. Privacy (:private) is the definition's own, the compiler's account of it, never the live var's: the image's var holds the
  privacy of the tree it was built or run from (seon.source/tree-private). Location keys and :doc are left out (the doc is :var/doc), and so are the keys
  instrumentation adds as a process runs (seon.contract/instrument-keys): with them, a definition's facts
  depended on whether the importing process had armed its contracts (491 of 2,431 contracted vars differed)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/var] :map] [:set :keyword]]}
  [v d]
  (let [m (merge (:meta d) (dissoc (some-> v meta) :private))]
    (-> (set (keys (cond-> m (:macro? d) (assoc :macro true))))
        (disj :ns :name :file :line :column :end-line :end-column :doc)
        (#(apply disj % contract/instrument-keys)))))

(defn- var-attributes
  "Of keys, the attributes a schema extending a var's (:seon.db/var-fact) owns, as the entity index says (`seon.db/owners`):
  the metadata that makes a var a more specific thing (a test). See docs/testing.md#test-types."
  {:malli/schema [:=> [:cat [:sequential :any]] [:sequential :qualified-keyword]]}
  [ks]
  (let [ix (pdb/index)]
    (for [[a s] (pdb/owners ix (filter qualified-keyword? ks)) :when (and (not= s :seon.db/var-fact) (pdb/extends? ix s :seon.db/var-fact))] a)))

(defn- def-facts
  "A defined var's metadata keys that are `var-attributes`, each with its value (the live var's when it is plain data, else
  as written): its facts on the var entity. See docs/testing.md#test-types."
  {:malli/schema [:=> [:cat [:maybe :seon.host/var] :map] [:map-of :qualified-keyword :any]]}
  [v d]
  (let [m (merge (:meta d) (some-> v meta))]
    (into {} (for [a (var-attributes (keys m))
                   :let [x (let [live (some-> v meta (get a))] (if (and (some? live) (edn-value? live)) live (unquote-form (get (:meta d) a))))]
                   :when (some? x)]
               [a x]))))

(defn- macro-uses
  "The macros form (as read) calls in namespace nsx: each list in it whose head resolves
  there to a macro var. The IR is the expansion, which names no macro, and a
  top-level call expanding to a do leaves its units' forms no trace of it (Clojure's
  compilation-unit rule), so the form itself is read. A macro's own template names the
  macros it expands to as quoted names (unit-account takes them as its references), which
  closes the chain."
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

(def ^:private docstring-heads
  "The heads of the definitions whose third element is a docstring when more follows it (clojure.core's): form-ids leaves
  it out of a form's code. defmethod's third is a dispatch value, which is code."
  '#{def defn defn- defmacro defmulti defprotocol ns})

(defn form-ids
  "A form's [id code] for its namespace, exact text, occurrence k and the form as the reader read it: its id
  (form-id's: the hash of the namespace and the exact text) and the digest of its namespace and code, which is
  the form's EDN as seon.edn/encode makes it (printed here, named in docs/render.md#printing-beside-the-printer-the-wire):
  the reader has dropped its comments and whitespace, a definition's docstring is left out (docstring-heads: a test whose namespace
  reads a docstring takes the src class, reach-step's :docs), and the encoding leaves out its :line and :column. Two things the print does not hold are made to: the numbers the reader gives a gensym (`p1__12#`,
  `x__3__auto`, counted over the process) are renumbered by first appearance, and the name's metadata
  (`^:private`) follows. Stored with the form (:form/code), what a test's reach digest takes of it
  (docs/source.md#what-a-test-takes-of-a-forms-text). Across all of src and test (3,831 forms) a form read twice
  encodes alike and every form encodes."
  {:malli/schema [:=> [:cat :symbol :string :int :any] [:tuple :string :string]]}
  [nsym src k form]
  (let [id (str nsym "/" (hasch/uuid [nsym src]) (when (pos? k) (str "#" k)))
        ;; a definition's docstring is no code: the string after its name, when more follows
        form (if (and (seq? form) (docstring-heads (first form)) (symbol? (second form)) (string? (nth form 2 nil)) (< 3 (count form)))
               (with-meta (apply list (first form) (second form) (drop 3 form)) (meta form))
               form)
        ;; seon.edn/encode's own print, inline: a contracted call more is the account proof's call count
        printed (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str form))
        counts (volatile! {})
        renumber (fn [m] (str/replace printed m (fn [[_ n sfx]] (str "__" (or (@counts n) (let [c (count @counts)] (vswap! counts assoc n c) c)) sfx))))
        code (if (str/includes? printed "__") (renumber #"__(\d+)(#|__auto)") printed)
        name-meta (when (and (seq? form) (symbol? (second form))) (not-empty (dissoc (meta (second form)) :line :column)))]
    [id (str nsym "/" (hasch/uuid [nsym code name-meta]))]))

(defn- names
  "The qualified keywords a schema or contract form names."
  {:malli/schema [:=> [:cat :any] [:set :qualified-keyword]]}
  [form] (set (filter qualified-keyword? (tree-seq coll? seq form))))

(defn ns-requires
  "The libraries an ns form's :require clauses name, the form as read. See docs/agent.md#the-repl."
  {:malli/schema [:=> [:cat :any] [:set :symbol]]}
  [form]
  (into #{} (comp (filter #(and (seq? %) (= :require (first %))))
                  (mapcat rest)
                  (map #(if (sequential? %) (first %) %))
                  (filter symbol?))
        (when (seq? form) form)))

(defn repl-act?
  "Does the analyzed form l (its :nodes or :units) only change its namespace's environment at a REPL, naming what later forms may use (:as, :refer)?
  It holds for that REPL alone (`account`'s :repl?), so its answer says so. See docs/agent.md#the-repl."
  {:malli/schema [:=> [:cat :map :any] :boolean]}
  [{:keys [nodes units]} form]
  (let [a (merge-accounts (or units (map unit-account nodes)))]
    (boolean (and (:env? a) (empty? (:defs a))
                  (some #{:as :refer} (filter keyword? (tree-seq coll? seq form)))))))

(defn unknown-refs
  "The names a form's account uses, in a namespace that is loaded, that hold no var: Jolt compiles such a name and fails only when
  its line runs. A class's static has no namespace to ask. Sorted. See docs/resolution.md#a-name-with-no-var."
  {:malli/schema [:=> [:cat [:map [:refs [:set :symbol]]]] [:vector :symbol]]}
  [{:keys [refs]}]
  (vec (sort-by str (for [r refs
                          :let [n (some-> (namespace r) symbol find-ns)]
                          :when (and n (nil? (ns-resolve n (symbol (name r)))))]
                      r))))

(defn account
  "The program account of one top-level form, or nil when it is none of the program's.
  opts: :nodes (each analyzed unit's node; or :units, their unit-accounts), :src (its exact text), :form
  (src as the reader read it: macro-uses walks it, so the text is read once), :ns (the namespace
  it was evaluated in), :ns-after (where it left the REPL), :ns-form? (it is an `ns` form:
  its head resolves to clojure.core/ns), :before (readback: var -> root before; nil for an
  analysis without running: every top-level def counts), :ran? (the form returned; a form
  that threw records only the vars it set), :ns-meta (an ns form's name's metadata: Jolt's
  ns drops it, jolt/ns-meta), :lead, :order, :k (occurrence of its text), :repl? (typed at a REPL: a form that only changes the
  namespace's environment, a require or an alias, is that REPL's act and no program; a file's is the file's text, kept whole, and only an
  ns form says what a namespace needs everywhere. See docs/agent.md#the-repl)."
  {:malli/schema [:=> [:cat [:map [:src :string] [:form :any]]] [:maybe :map]]}
  [{:keys [nodes units src form ns ns-after ns-form? ns-meta before ran? lead order k repl?] :or {k 0}}]
  (let [units (or units (map unit-account nodes))
        nsym (if ns-form? ns-after ns)
        nsx (find-ns nsym)
        ids (form-ids nsym src k form)
        a (merge-accounts units)
        set? (fn [{v :var :as d}] (or (nil? before) (not (identical? (get before v) (root-of v))) (:macro? d)))
        defs (vec (for [d (:defs a)
                        :let [v (find-var (:var d))
                              multi? (contains? (:multis a) (:var d))]
                        :when (and (not (and (:declare? d) (not multi?))) (set? d))
                        :let [facts (def-facts v d)]]
                    (cond-> {:var (:var d) :meta (def-meta v d)}
                      (seq facts) (assoc :facts facts)
                      (contract-text v d) (assoc :contract (contract-text v d))
                      ;; the form's own docstring first: a file read without running (another tree's) holds its
                      ;; text's, whatever the image's var says (seon.program-test/a-file-analyzed-keeps-its-own-docstring)
                      (string? (or (get (:meta d) :doc) (some-> v meta :doc))) (assoc :doc (or (get (:meta d) :doc) (some-> v meta :doc))))))
        declares (into #{} (comp (filter :declare?) (map :var) (remove (set (map :var defs)))
                                 (remove #(contains? (:multis a) %))) (:defs a))
        methods (when (or ran? (nil? before))
                  (vec (for [{:keys [multi dispatch]} (:methods a)
                             :let [mv (when (and nsx (symbol? multi)) (ns-resolve nsx multi))]
                             :when (var? mv)]
                         {:multi (symbol mv) :dispatch (sedn/encode dispatch)})))
        impls (when (or ran? (nil? before)) (vec (distinct (:impls a))))
        ;; an ns form's docstring, as written, as a def's is: its name's metadata, else the string after its name, else its attr map's
        ns-doc (when ns-form? (let [x (when (seq? form) (nth form 2 nil))]
                                (first (filter string? [(:doc ns-meta) x (when (map? x) (:doc x))]))))]
    (when (and nsx (not (scratch-ns? nsx)) (not (:scratch ns-meta))
               (or ns-form? (seq defs) (seq declares) (seq methods) (seq impls) (and (:env? a) (not repl?))))
      (cond-> {:id (first ids) :src src :code (second ids) :ns nsym :ns-form? (boolean ns-form?) :ns-doc ns-doc
               :defs defs :declares declares :methods (or methods []) :impls (or impls [])
               :refs (-> (:refs a) (into (macro-uses nsx form))
                         ;; a ctor reaches its type's methods: ns.T, or a bare T (analyzed, not run, the type is in no
                         ;; image), imported or of this namespace, the type's var; a host class's name is no var, and drops
                         ;; out of the graph
                         (into (map (fn [c] (let [imported (some-> (get (ns-imports nsx) (symbol c)) str (str/replace #"^class " "") (str/replace "_" "-"))
                                                  c (or imported c)]
                                              (if-let [i (str/last-index-of c ".")] (symbol (subs c 0 i) (subs c (inc i))) (symbol (str nsym) c)))))
                               (:news a)))
               :quoted (:quoted a) :ops (:ops a) :requires (:requires a) :banned (:banned a) :inline-defs (:inline-defs a)
               ;; its code's keywords and its contracts': the data it operates on, read once here
               :keywords (into (:keywords a) (comp (keep :contract) (mapcat (comp names sedn/decode))) defs)
               :catches (:catches a)}
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

(def attr-namespaces
  "The namespaces of the attributes that hold the program (its forms, their definitions, the
  schema and the db's own schema): what a merge carries as program, and what a commit must
  touch to move the reach graph (seon.fork-server's :fork)."
  #{"form" "var" "ns" "method" "impl" "schema" "file" "db" "db.install" "db.type" "db.cardinality" "db.unique"})

(defn moves-program?
  "Whether a commit's resolved ops change the program (an attribute of attr-namespaces): what
  moves the reach graph a fork hands its agent (seon.fork-server) and starts a run of main's
  tests (seon.prove)."
  {:malli/schema [:=> [:cat [:sequential :any]] :boolean]}
  [ops] (boolean (some (fn [[_ _ a]] (and (keyword? a) (attr-namespaces (namespace a)))) ops)))

(def ^:private form-sets
  "A form's card-many facts, as stored and as its row (form-row) and its account name them."
  [[:form/refs :refs] [:form/quoted :quoted] [:form/keywords :keywords] [:form/ops :ops] [:form/requires :requires] [:form/banned :banned] [:form/inline-defs :inline-defs]
   [:form/catches :catches]])

(def ^:private form-set-keys
  "form-sets as a map, {attribute -> row key}: what form-row folds one seek's datoms by. See
  docs/source.md#tests-run-by-reach."
  (into {} form-sets))

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
            :impl (let [k (impl-key a b)] {:db/id (str "impl " k) :impl/key k :impl/protocol a})
            :ns {:db/id (ns-tid a) :ns/name a})
    e (assoc :db/id e)))

(defn form-tx
  "Form id as one entity map, from row (a form's account or a merge's form content): its
  text, namespace, order and lead, the definitions and declarations it names (def-ref's ids,
  each definition's entity read from eid-of: a map, or def-eid on the db) and its form-sets."
  {:malli/schema [:=> [:cat :string :map ifn?] :seon.db/tx-map]}
  [id {:keys [ns src code order lead defines declares] :as row} eid-of]
  (into (cond-> {:db/id (form-tid id) :form/id id :form/src src :form/ns (ns-tid ns) :form/order order}
          code (assoc :form/code code)
          lead (assoc :form/lead lead)
          (seq defines) (assoc :form/defines (mapv #(:db/id (def-ref % (eid-of %))) defines))
          (seq declares) (assoc :form/declares (mapv #(:db/id (def-ref [:var %] (eid-of [:var %]))) declares)))
        (for [[a k] form-sets :let [v (get row k)] :when (seq v)] [a (vec v)])))

(defn def-tx
  "tx-data making definition k point at the form content names (content {:form :meta :facts
  :contract :doc :file}), retracting what it held that content does not, or, for nil
  content, retracting what it held. e: its entity on db (def-eid), or nil. A var's :facts are its metadata
  that are attributes, asserted as they are (docs/testing.md#test-types)."
  {:malli/schema [:=> [:cat :seon.host/db :any [:maybe :int] [:maybe :map]] :seon.db/tx-data]}
  [db k e content]
  (let [x (when e (d/entity db e))
        ;; the attributes its metadata asserted before: its kept keys an entity schema owns
        facts (when (= :var (first k)) (var-attributes (vec (:var/meta x))))
        attrs (case (first k) :var (into [:var/form :var/contract :var/doc] facts) :ns [:ns/form :ns/file :ns/doc] [(pointer (first k))])
        now (when content
              (cond-> (merge {(pointer (first k)) (form-tid (:form content))} (:facts content))
                (seq (:meta content)) (assoc :var/meta (set (:meta content)))
                (:contract content) (assoc :var/contract (:contract content))
                (:doc content) (assoc (if (= :ns (first k)) :ns/doc :var/doc) (:doc content))
                (:file content) (assoc :ns/file (:file content))))]
    (into (if content [(merge (def-ref k e) now) {:db/id (form-tid (:form content)) :form/id (:form content)}] [])
          (concat
            (for [a attrs :let [old (get x a)] :when (and (some? old) (not (contains? now a)))]
              [:db/retract e a (if ((set (vals pointer)) a) (:db/id old) old)])
            (for [m (:var/meta x) :when (not (contains? (:var/meta now) m))]
              [:db/retract e :var/meta m])))))

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
  (their :seon.doc/references), each with the form that reads it. A new var changes nothing a
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
                   :cited-by (vec (sort-by :section (for [dt (d/datoms db :avet :seon.doc/references (:db/id e))
                                                          :let [a (:seon.doc/address (d/entity db (:e dt)))]]
                                                      ;; the form that reads it, as data (no reference: unit-account)
                                                      {:section a :read (list 'doc a)})))})]
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
           (mapcat (fn [{v :var :keys [meta facts contract doc]}] (def-tx db [:var v] (eids [:var v]) {:form id :meta meta :facts facts :contract contract :doc doc})) defs)
           (mapcat #(def-tx db % (eids %) {:form id}) (filter (comp #{:method :impl} first) dkeys))
           (when ns-form? [(cond-> {:db/id (ns-tid ns) :ns/form (form-tid id)} (:ns-doc acct) (assoc :ns/doc (:ns-doc acct)))])
           ;; an ns form that no longer holds one: the docstring goes with it
           (when-let [old (when (and ns-form? (nil? (:ns-doc acct))) (:ns/doc (d/entity db [:ns/name ns])))]
             [[:db/retract [:ns/name ns] :ns/doc old]])
           (for [o superseded] [:db/retractEntity o])))))

(defn record-tx
  "The facts of a form's account recorded on this branch's db: account-tx, a new form after
  every form so far (its order: the branch's next transaction)."
  {:malli/schema [:=> [:cat :seon.host/db :map] :seon.db/tx-data]}
  [db acct] (account-tx db acct (inc (:max-tx db))))

;; ---- the live program ----------------------------------------------------------------

(defn program-nss
  "The namespaces of db that are the program's own: their file under one of
  :seon.config.source/program-dirs. A test's, a fixture's or a test helper's (under test/, whatever
  its name), and one with no file (an app an agent made at its REPL, until it is written back), are
  not (the last is the agent's new work). One read of the namespaces'
  files, so a caller asks it once and tests membership."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :symbol]]}
  [db]
  (let [dirs (mapv #(str % "/") (cfg/value :seon.config.source/program-dirs))]
    (into #{} (keep (fn [{:keys [e v]}] (when (some #(str/starts-with? v %) dirs) (:ns/name (d/entity db e)))))
          (d/datoms db :aevt :ns/file))))

(defn test-nss
  "The namespaces of db read from a file outside :seon.config.source/program-dirs: test/'s (a test,
  a fixture, a driver), which the process that runs tests holds evaluated and root never does
  (load-all!'s :tests?). One with no file (an agent's app, a test store's example program) is
  none. One read of the namespaces' files. See docs/source.md#a-process-holds-the-tests-only-when-it-runs-them."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :symbol]]}
  [db]
  (let [prog (program-nss db)]
    (into #{} (keep (fn [{:keys [e]}] (let [n (:ns/name (d/entity db e))] (when-not (prog n) n))))
          (d/datoms db :aevt :ns/file))))

(defn program-names
  "Of qualified symbols ns, those the program's, in ns's order: what a task's page cites as vars.
  A name is the program's when db holds its namespace with a file under
  :seon.config.source/program-dirs, as program-nss reads them, reading only these names'
  namespaces (a page's reads stay what it cites). A test's or a test helper's, a library's, and a
  name of no namespace the program holds (a reproduction's handle, result/a3f9c1d2) are not: they are a
  reproduction's entry and data, not its subject."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :qualified-symbol]] [:vector :qualified-symbol]]}
  [db ns]
  (let [dirs (mapv #(str % "/") (cfg/value :seon.config.source/program-dirs))
        of #(symbol (namespace %))
        program (into #{} (filter (fn [n] (some-> (:ns/file (d/entity db [:ns/name n])) (as-> f (some #(str/starts-with? f %) dirs)))))
                      (distinct (map of ns)))]
    (filterv (comp program of) ns)))

(defn live?
  "Is form entity e part of the program: it defines nothing (a text form: a declare, a
  require, a comment kept from its file), or some definition it made still points to it."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db e]
  (let [ds (:form/defines (d/entity db e))]
    (or (empty? ds) (boolean (some #(= e (form-of db (:db/id %))) ds)))))

(defn- definition-key
  "Definition entity x's program key ([:var sym], [:method ...], [:impl ...], [:ns sym]) on db, from
  one seek of its datoms (a var's name wins over a method's, an impl's and a namespace's, in that order)."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :seon.merge/program-key]]}
  [db x]
  (let [m (reduce (fn [m {:keys [a v]}] (case a (:var/name :method/key :impl/key :ns/name) (assoc m a v) m))
                  {} (d/datoms db :eavt x))]
    (cond (:var/name m) [:var (:var/name m)]
          (:method/key m) (into [:method] (sedn/decode (:method/key m)))
          (:impl/key m) (into [:impl] (sedn/decode (:impl/key m)))
          (:ns/name m) [:ns (:ns/name m)])))

(defn form-row
  "Form entity e as the loader and the merge read it (:source when it is the text of the
  source tree the binary was built from, :form/source: its :order is its place in that file),
  built from one seek of e's datoms and one of each namespace, definition and declared var it
  names, never an entity's attribute-by-attribute reads (about a fifth of the ticks of those)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db e]
  (reduce (fn [row {:keys [a v]}]
            (case a
              :form/id (assoc row :id v)
              :form/src (assoc row :src v)
              :form/code (assoc row :code v)
              :form/ns (assoc row :ns (:v (first (d/datoms db :eavt v :ns/name))))
              :form/order (assoc row :order v)
              :form/defines (update row :defines conj (definition-key db v))
              :form/declares (update row :declares conj (:v (first (d/datoms db :eavt v :var/name))))
              :form/lead (assoc row :lead v)
              :form/source (assoc row :source true)
              (if-let [k (form-set-keys a)] (update row k conj v) row)))
          (into {:e e :id nil :src nil :ns nil :order nil :defines #{} :declares #{}} (map #(vector % #{})) (vals form-set-keys))
          (d/datoms db :eavt e)))

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

(def ^:private place-gap
  "The space a file's forms are placed apart (`stable-places`), so a form inserted takes a place between its neighbours and moves none."
  1024)

(defn- kept-places
  "The indexes of held ([place or nil], in file order) forming a longest increasing run: the forms that need not move. O(n log n)."
  {:malli/schema [:=> [:cat [:vector [:maybe :int]]] [:set :int]]}
  [held]
  (let [{:keys [tails prev]}
        (reduce (fn [{:keys [tails prev]} i]
                  (if-let [v (held i)]
                    (let [at (loop [lo 0 hi (count tails)]
                               (if (< lo hi)
                                 (let [mid (quot (+ lo hi) 2)]
                                   (if (< (held (tails mid)) v) (recur (inc mid) hi) (recur lo mid)))
                                 lo))]
                      {:tails (assoc tails at i) :prev (assoc prev i (when (pos? at) (tails (dec at))))})
                    {:tails tails :prev prev}))
                {:tails [] :prev {}} (range (count held)))]
    (loop [i (peek tails) out #{}] (if i (recur (prev i) (conj out i)) out))))

(defn stable-places
  "The places of a file's forms, in file order, from held, each form's place on the db or nil for one it lacks: the most forms keep
  theirs, and each other takes one between its kept neighbours, so an inserted form writes one place and moves none. When a gap has
  no room, every form is placed anew, place-gap apart. See docs/sharing.md#a-merge."
  {:malli/schema [:=> [:cat [:vector [:maybe :int]]] [:vector :int]]}
  [held]
  (let [n (count held) kept (kept-places held) anew (mapv #(* place-gap %) (range n))]
    (loop [i 0 left nil out []]
      (cond
        (= i n) out
        (kept i) (recur (inc i) (held i) (conj out (held i)))
        :else (let [end (or (first (filter kept (range i n))) n)
                    k (- end i) right (when (< end n) (held end))
                    step (cond (and left right) (quot (- right left) (inc k)) :else place-gap)]
                (if (< step 1)
                  anew
                  (let [start (cond left left right (- right (* step (inc k))) :else (* place-gap (dec i)))]
                    (recur end left (into out (map #(+ start (* step (inc %)))) (range k))))))))))

(defn account-diff-tx
  "account-tx of acct at its own order and lead (a file's), less what db holds already:
  nothing for a form db holds with the same facts (its text, what it defines, declares and
  names, each definition pointing to it with the same meta, contract and doc), only its
  order and lead for one that only moved, and otherwise account-tx with the form's facts
  the account no longer names retracted. A file read again writes what changed in it, not
  the file (task seon.source/an-import-reads-only-changed-files)."
  {:malli/schema [:=> [:cat :seon.host/db :map] :seon.db/tx-data]}
  [db {:keys [id order lead defs declares] :as acct}]
  (if (:seon.source/held? acct)
    ;; a form db holds whole (seon.source's held-form, not analyzed again): only its place may have moved
    (let [fe (pdb/eid db :form/id id) x (d/entity db fe)]
      (if (and (= order (:form/order x)) (= lead (:form/lead x))) [] [(cond-> {:db/id fe :form/order order} lead (assoc :form/lead lead))]))
  (let [fe (pdb/eid db :form/id id)
        dkeys (def-keys acct)
        now (into {:id id :src (:src acct) :ns (:ns acct) :defines (set dkeys) :declares (set declares)}
                  (for [[_ k] form-sets] [k (set (get acct k))]))
        row (when fe (form-row db fe))
        held? (and row (= now (select-keys row (keys now)))
                   (every? #(= fe (some->> (def-eid db %) (form-of db))) dkeys)
                   ;; so a store made before a namespace held its docstring gains it
                   (or (not (:ns-form? acct)) (= (:ns-doc acct) (:ns/doc (d/entity db [:ns/name (:ns acct)]))))
                   (every? (fn [{v :var :keys [meta contract doc]}]
                             (let [x (d/entity db (def-eid db [:var v]))]
                               (and (= (set meta) (set (:var/meta x))) (= contract (:var/contract x)) (= doc (:var/doc x)))))
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
                            (for [v (:declares row) :when (not (contains? (:declares now) v))] [:db/retract fe :form/declares (def-eid db [:var v])]))))))))

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
;; (load-changes!, in the fork server; root's writer and boot leave test namespaces out, {:tests? false}:
;; root runs no deftest), so every agent forked after it holds main's tests compiled. Every other form of main runs where main's program runs: a merge
;; loads in place, in root, the fork server (so every agent forked after it inherits it
;; compiled) and an agent that merges main (BUILD plan P4). A binary's own form is
;; evaluated again only where a changed macro expanded (`dependents`) or a changed schema
;; reaches its contract (`schema-dependents`).

(declare schema-changes load-forms!)

(defn process-var
  "The var this process holds for recorded name s, given the ns-interns of its namespace, or
  nil. A deftype's type is found by its name (find-var): Jolt binds its var without
  interning it (ns-interns, as Clojure's, lists no type)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol :map] [:maybe :any]]}
  [db s interns]
  (or (get interns (symbol (name s))) (find-var s)))

(defonce ^{:private true :doc "What this process's binary was built from, as bin/build recorded it beside the binary (target/seon.program,
  seon.system/built-program's answer), or nil when it recorded none: read once, by its first reader (root's boot, before it
  forks the fork server, whose agents inherit it), and kept across a reload of this namespace, so a build that moves the
  record later never changes what a running binary says it holds. See docs/source.md#the-binary-records-its-program."}
  built
  (delay (let [f (java.io.File. (str (cfg/checkout) "/target/seon.program"))]
           (when (.isFile f) (sedn/decode (slurp f))))))

(defn binary-program
  "What the running binary was built from (built): {:program :inputs :forms}, :forms the ids of every form of the tree it was
  built from, or nil when bin/build recorded none. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] @built)

(defn- held?
  "Does this process already hold form row f compiled: the binary was built from its text (its id among forms, the binary's
  forms as binary-program records them, which a caller passes: a form imported from a file the checkout changed since is main's, never the binary's, though its vars are
  bound), in a namespace it holds, every var it defines bound there? The binary's record holds test/'s forms too, which no
  binary compiles: one is held once its namespace is loaded. A form imported into a namespace loaded earlier (a test namespace
  loaded at boot, then a re-import adding a deftest) defines a var this process does not hold yet: it is loaded, never read
  as an unmap (seon.stage/stage-tx). With no record, nothing is held. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe [:set :string]] :map] :boolean]}
  [db forms f]
  (boolean (and (contains? forms (:id f))
                (when-let [n (find-ns (:ns f))]
                  (every? (fn [[k s]] (or (not= :var k) (some-> (process-var db s (ns-interns n)) bound?)))
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
                                     :when (and (= :var k) (contains? (set (:var/meta (d/entity db [:var/name s]))) :macro))
                                     e' (requiring db (symbol (namespace s)))
                                     :when (live? db e')]
                                 e'))))))

(defn gained-tx
  "tx-data for the program facts db's forms were recorded without, an attribute being new to db (a
  branch cut before it, resumed: seon.agent's resume, after seon.db/install-missing!). :form/keywords,
  when db's schema holds it and no form does (one seek; so a resume that died after installing the
  attribute derives them the next time): for each live form defining a contracted var, the keys its
  contracts name, from :var/contract as schema-dependents read it before; the keys its code alone
  names need the form analyzed again, and stay unrecorded until it is."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.db/op]]}
  [db]
  (if-not (and (contains? (d/schema db) :form/keywords) (nil? (first (d/datoms db :aevt :form/keywords))))
    []
    (vec (for [dt (d/datoms db :aevt :form/defines)
               :let [fe (:e dt) f (d/entity db fe)]
               :when (live? db fe)
               v (:form/defines f) :let [c (:var/contract v)] :when c
               k (names (sedn/decode c))]
           [:db/add fe :form/keywords k]))))

(declare file-readers)

(defn file-reading-macros
  "The names of db's macros whose defining form refers to one of seon.program/file-readers (slurp, file-seq, io/resource): a form
  expanding one holds the analysis of what the macro read. One query of the program's facts, never a seek a reference. See
  docs/source.md#indexing-a-tree. It starts at the program's macros (a seek of :var/meta) and reads each one's live form, not at
  the forms naming a file reader: those are ~100 against ~20 macros, each read in scattered leaves (7.9M ticks cold, 2.75M now)."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :qualified-symbol]]}
  [db]
  (set (d/q '[:find [?mn ...] :in $ [?r ...]
              :where [?m :var/meta :macro] [?m :var/form ?mf] [?mf :form/refs ?r] [?m :var/name ?mn]]
            db (vec file-readers))))

(defn schema-dependents
  "The live forms of db whose form names one of keys (its contract or its code, :form/keywords: one AVET seek a key), or a
  schema that names one, and on (the binary's schema forms with db's changes): the forms to evaluate again against the changed
  schemas, unchanged in text: a contract re-arms, and a value computed from a schema at load (a value def or a top-level call
  of contract/properties) takes the moved entry; a function's body reads the registry when called, so a defn, defmethod or
  macro is not taken, nor a form expanding a file-reading macro (file-reading-macros: it would read this process's checkout,
  and the registry layers the branch's changes over the binary's value). A form's inputs include the schema entries it
  names. Nothing when keys is empty."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-keyword]] [:vector :map]]}
  [db keys]
  (if (empty? keys)
    []
    (let [naming (reduce (fn [m [k f]] (reduce #(update %1 %2 (fnil conj []) k) m (names f)))
                         {} (merge contract/forms (schema-changes db)))
          reached (into keys (closure keys naming))
          reading (delay (file-reading-macros db))]
      (mapv #(form-row db %)
            (distinct (for [k reached
                            dt (d/datoms db :avet :form/keywords k)
                            :let [fe (:e dt)]
                            :let [x (d/entity db fe) ds (:form/defines x)]
                            ;; a form that took the schema at load: a contract (re-armed), a value def or a top-level call
                            ;; (contract/properties of it); a function's body reads the registry when called, so a defn,
                            ;; a defmethod or a macro is not evaluated again for it
                            :when (and (live? db fe)
                                       (or (some :var/contract ds)
                                           (every? #(and (:var/name %) (not (contains? (set (:var/meta %)) :arglists))) ds))
                                       ;; nor one expanding a file-reading macro (seon.config/schema-forms): it would read this
                                       ;; process's checkout; its value is the binary's, and the registry layers the branch's
                                       ;; changes over it (schema-registry)
                                       (not-any? @reading (:form/refs x)))]
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

(defonce ^{:private true :doc "{var form-id}: the form this process last evaluated for each var it loaded or recorded (load-form!,
  note-loaded!): what program-differs compares with a branch. See docs/merging.md#a-session-proves-through-root."}
  loaded (atom {}))

(defonce ^{:private true :doc "#{form entity}: the forms this process was found to hold (held-forms), by entity: a form's entity is
  its content id, the same on every db holding its text, so an import asks each once. See docs/source.md#indexing-a-tree."}
  held-entities (atom #{}))

(defn held-forms
  "The ids of the forms this process holds compiled: the binary's (binary-program) and those it loaded since (note-loaded!).
  See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat] [:set :string]]}
  [] (into (set (:forms (binary-program))) (vals @loaded)))

(defn unheld-forms
  "{namespace entity [form entity]}: the forms of each of nes on db this process doesn't hold compiled. Each form is asked once
  a process. See docs/source.md#indexing-a-tree."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.db/eid]] [:map-of :seon.db/eid [:vector :seon.db/eid]]]}
  [db nes]
  (let [known @held-entities
        outs (into {} (for [ne nes] [ne (into [] (comp (map :e) (remove known)) (d/datoms db :avet :form/ns ne))]))
        new (into [] cat (vals outs))]
    (if (empty? new)
      (update-vals outs (constantly []))
      (let [held (held-forms)
            id-of (if (< 100 (count new))
                    (let [ids (into {} (map (juxt :e :v)) (d/datoms db :aevt :form/id))] #(get ids %))
                    #(:v (first (d/datoms db :eavt % :form/id))))
            unheld (into #{} (remove #(held (id-of %))) new)]
        (swap! held-entities into (remove unheld) new)
        (update-vals outs #(filterv unheld %))))))

(defn note-loaded!
  "Record that this process now runs form id for each var of vars (an agent's REPL definition, as it records it). nil."
  {:malli/schema [:=> [:cat [:sequential :qualified-symbol] :string] :nil]}
  [vars id] (swap! loaded into (for [v vars] [v id])) nil)

(defn- load-form!
  "Evaluate one form row in its namespace, and arm the contracted functions it defines
  (seon.contract/arm!). Answers nil, or an error map naming the form (its id and its text's first
  line), so a load that fails says which definition failed."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.program/error]]}
  [{:keys [id ns src defines]}]
  (let [named #(cond-> {:ns ns :message %} id (assoc :form id :lead (first (str/split-lines (str src)))))]
    (try
      (binding [*ns* (ensure-ns! ns)]
        (eval (read-string src)))
      (when id (note-loaded! (vec (keep (fn [[k s]] (when (= :var k) s)) defines)) id))
      (some (fn [[k s]]
              (when-let [v (and (= :var k) (find-var s))]
                (let [r (contract/arm! v)]
                  (when (contract/error? r) (named (:seon.error/message r))))))
            defines)
      (catch Throwable e (named (step/error-msg e))))))

;; ---- schema entries: the program's schemas (seon.source imports them, my.schema/register!
;; changes them, seon.merge carries them per key) ----------------------------------------

(def ^:private baked-text
  "Each of the binary's schema forms as an entry's text holds it, so an entry the binary holds costs one string compare and no decode. See docs/source.md#a-program-file."
  (delay (into {} (for [[k v] contract/forms] [k (binding [*print-namespace-maps* false] (sedn/encode v))]))))

(defn- differing-form
  "Schema key k's form as text states it when that differs from the binary's, else nil. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :qualified-keyword :string] :any]}
  [k text]
  (when (not= text (get @baked-text k))
    (let [v (sedn/decode text)] (when (not= v (get contract/forms k)) v))))

(defn schema-changes
  "{k form} of db's schema entries that differ from this binary's forms (seon.contract/forms), by value: registered, merged, or imported from a tree ahead of the binary. Two passes over the entries' datoms. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-keyword :any]]}
  [db]
  (let [text (into {} (map (juxt :e :v)) (d/datoms db :aevt :schema/form))]
    (into {} (for [dt (d/datoms db :aevt :schema/key)
                   :let [v (some->> (text (:e dt)) (differing-form (:v dt)))]
                   :when (some? v)]
               [(:v dt) v]))))

(defn index-of-db
  "The entity index (seon.db/entity-index) of the registry with db's own schema entries over it (`schema-changes`): what an
  entity is on that db's branch, built here and never kept: its holder (a walk, an agent's state) keeps it until a schema entry
  moves. See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db] (pdb/index-of {:forms (schema-changes db)}))

(defn schema-registry
  "The registry an evaluation on db binds as malli.registry/*registry*: the binary's registry with changes (schema-changes)
  and the forms that name them compiled again (contract/registry-with); the built registry itself when nothing changed (what a
  binding of it answers, and what a merge's declarations are read from: seon.merge/merge-tx)."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :any]] :seon.host/registry]}
  [changes] (if (empty? changes) contract/registry (contract/registry-with changes)))

(defn schema-declarations
  "The Datahike declarations registry reg derives that db does not hold as declared (a
  registration or merge of a schema carries its attribute, so its facts can be stored)."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:vector :seon.schema/declaration]]}
  [db reg]
  (let [have (d/schema db)]
    (filterv (fn [dcl] (not= dcl (select-keys (get have (:db/ident dcl)) (keys dcl)))) (sd/declarations reg))))

(defn own-declaration
  "The attribute registered key k is, when no entity map lists it and its value is storable (an attribute is a registered key whose schema is its stored value: an agent's own datum, written on its own entity): [its declaration], when db does not hold it so; else []. A registration and a merge of the entry read it here, so the attribute travels with it. See docs/storage.md#the-write-checks-every-datom."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :seon.host/registry] [:vector :seon.schema/declaration]]}
  [db k reg]
  (let [dcl (sd/declaration reg k)
        entity? (:seon.db/attributes (m/properties (m/deref (m/schema k {:registry reg}))))]
    (if (or (contract/error? dcl) entity? (= dcl (select-keys (get (d/schema db) k) (keys dcl)))) [] [dcl])))

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
    :method (when-let [v (find-var a)] (when (instance? clojure.lang.MultiFn @v) (remove-method @v (sedn/decode b))))
    nil))

(defn- loadable
  "Of form rows, those a process loads: all, or with tests? false (root's), those not in a test
  namespace (test-nss, read only when there are forms to filter)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map] :boolean] [:vector :map]]}
  [db forms tests?]
  (if (or tests? (empty? forms))
    (vec forms)
    (let [t (test-nss db)] (filterv #(not (t (:ns %))) forms))))

(defn- load!
  "Load changed (form rows this binary does not hold), the forms a changed macro expanded
  in (dependents) and the forms whose contracts the changed schema keys reach
  (schema-dependents), in load order, less the test namespaces' when tests? is false
  (loadable). Answers the errors."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map] [:set :qualified-keyword] :boolean] [:vector :seon.program/error]]}
  [db changed schema-keys tests?]
  (load-forms! db (loadable db (vec (vals (into {} (map (juxt :id identity))
                                           (concat changed (dependents db changed) (schema-dependents db schema-keys)))))
                            tests?)))

(defn rearm!
  "Arm again, in this process, every var whose contract reaches schema keys (they, or a schema
  naming one, on and on: schema-dependents' closure), compiled against db's schemas: one arm!
  armed again over its raw root (seon.contract/rearm!), one the defn macro armed at its definition by
  evaluating that definition again (its source as the REPL kept it, else as db records it): what a registration in a running agent owes the code it holds, whose
  contracts were compiled before it (my.schema/register!: Seon's own code, seon.ref/sections for one,
  would still check a registered :seon.ref/block against the old form). A load of
  db's program re-arms the same by evaluating the forms again (load!). Answers the errors. See docs/source.md#schemas-at-the-repl."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-keyword]] [:vector :map]]}
  [db keys]
  ;; the keys reached: keys, and every schema naming one, on and on (schema-dependents' closure)
  (let [naming (reduce (fn [m [k f]] (reduce #(update %1 %2 (fnil conj []) k) m (names f)))
                       {} (merge contract/forms (schema-changes db)))
        reached (into (set keys) (closure keys naming))]
    (binding [mr/*registry* (schema-registry (schema-changes db))]
      (into (vec (for [n (all-ns) v (vals (ns-interns n))
                 :let [c (contract/contract v)]
                 :when (and c (some reached (names c)))
                 :let [sym (symbol v)
                       ;; armed by arm! (its raw root kept): wrapped again; armed at its definition (the defn
                       ;; macro keeps no raw root): its definition evaluated again, as a load does (load!)
                       src (when-not (:seon.contract/raw (meta v))
                             (or (:source (meta v)) (some->> (pdb/eid db :var/name sym) (d/entity db) :var/form :form/src)))
                       r (if src
                           (try (binding [*ns* (:ns (meta v))] (load-string src)) nil
                                (catch Throwable e {:var sym :message (or (ex-message e) (str e))}))
                           (let [r (contract/rearm! v)] (when (contract/error? r) r)))]
                 :when r]
             r))
            ;; and the forms that define no contracted var but name a reached key (a def computed from the schema at load):
            ;; evaluated again, as a load does, so they take the registered entry (schema-dependents)
            (load-forms! db (filterv (fn [f] (not-any? :var/contract (:form/defines (d/entity db [:form/id (:id f)]))))
                                     (schema-dependents db keys)))))))

(defn- required!
  "Of form rows forms, the test namespaces whose every live form is its file's as imported
  (:form/source, the namespace's :ns/file) and that this process holds none of, each loaded whole
  by require, in load order: through Jolt's AOT cache (JOLT_CACHE_DIR, bin/env) a namespace's
  compile is paid once, by the first process, and every later one loads it compiled (main's 78
  test namespaces: 12.2 s evaluated form by form, 0.70 s loaded warm, 16.1 s cold: the commit that
  made this), its contracted vars armed. A namespace whose require throws is left to load-forms!, which names the
  form that fails. Answers [the namespaces loaded, the errors]. See docs/source.md#a-process-holds-the-tests-only-when-it-runs-them."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map] [:sequential :map]] [:tuple [:set :symbol] [:vector :seon.program/error]]]}
  [db forms lf]
  (let [tnss (test-nss db)
        ;; lf: db's live forms, which load-all! has read (a second read was 0.6-1.1 s of a fork server's start)
        live (group-by :ns lf)
        ;; the tree db's program was imported from, as db states it (root's :seon.system/source fact), which every :ns/file is
        ;; relative to: never this process's config, which a root given its source as start!'s options (every check mode,
        ;; boot-twice, a cycle) never holds, so its fork server loaded all 77 test namespaces form by form, 12.2 s of its
        ;; first fork against 1.06 s whole (measured 2026-11-10)
        root (some->> (pdb/eid db :seon.system/id :root) (d/pull db [:seon.system/source]) :seon.system/source :root)
        ;; the file require reads is the one a source dir holds at the namespace's path, and it holds every
        ;; form main has of it, as main has it: a file edited since its import, or one on no source dir (a
        ;; fixture under test/build/), is never what require loads
        file-of (fn [n] (let [f (:ns/file (d/entity db [:ns/name n]))
                              path (str (str/replace (str/replace (str n) "-" "_") "." "/") ".clj")]
                          (when (and root f (some #(= f (str % "/" path)) (cfg/value :seon.config.source/dirs)))
                            (java.io.File. (str root "/" f)))))
        whole? (fn [n] (when-let [^java.io.File file (and (tnss n) (nil? (find-ns n)) (file-of n))]
                         (and (.isFile file)
                              (every? #(:form/source (d/entity db (:e %))) (live n))
                              (let [text (slurp file)]
                                (and (every? #(str/includes? text (:src %)) (live n))
                                     ;; and nothing else: the file's text, each live form taken out, is only space and comments. A form
                                     ;; the branch removed is still in main's file; required whole, it brought main's forms back over the
                                     ;; branch's (a test child naming a var its branch deleted, 2026-11-12)
                                     (str/blank? (str/replace (reduce (fn [t f] (str/replace-first t (:src f) "")) text (live n))
                                                              #"(?m);.*$" "")))))))]
    (binding [mr/*registry* (schema-registry (schema-changes db))]
      (reduce (fn [[ok errs] n]
                (if-let [e (try (require n) nil (catch Throwable e e))]
                  ;; a file that holds main's forms and does not load is a load error, named; its forms then load one by one
                  [ok (conj errs {:ns n :message (str "require of its file: " (step/error-msg e))})]
                  [(conj ok n) (into errs (for [v (vals (ns-interns n))
                                                :when (contract/contract v)
                                                :let [r (contract/arm! v)]
                                                :when (contract/error? r)]
                                            {:ns n :message (:seon.error/message r)}))]))
              [#{} []] (filterv whole? (distinct (map :ns (load-order forms))))))))

(defn load-all!
  "Load db's program where this binary runs: every live form it does not hold, with what
  they reach (load!). With {:tests? false} (root's) the test namespaces' forms are left
  unevaluated: root imports test/ as facts and runs no deftest; the fork server loads them for
  every agent and test child, each namespace read from its file whole where main holds it as the
  file is (required!), the rest form by form
  (docs/source.md#a-process-holds-the-tests-only-when-it-runs-them). :forms, the binary's form ids (held?), is the running
  binary's record by default (binary-program); a test passes the set it means.
  Answers {:loaded n :errors [...]}."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.program/load-report]
                  [:=> [:cat :seon.host/db [:map [:tests? {:optional true} :boolean] [:forms {:optional true} [:maybe [:set :string]]]]] :seon.program/load-report]]}
  ([db] (load-all! db {}))
  ([db {:keys [tests?] :or {tests? true} :as opts}]
   (let [lf (live-forms db)
         binary (if (contains? opts :forms) (:forms opts) (:forms (binary-program)))
         forms (loadable db (filterv #(not (held? db binary %)) lf) tests?)
         [required errs] (if tests? (required! db forms lf) [#{} []])]
     {:loaded (count forms)
      :errors (into errs (load! db (filterv #(not (required (:ns %))) forms) (set (keys (schema-changes db))) tests?))})))

(defn- live-in?
  "Is form entity e live on db, for one form: it has an id, and defines nothing or one of its
  own definitions points back to it (live?'s rule; live-ids takes any pointer to it, the same
  wherever a definition points only to the form that defines it, as account-tx and record-tx
  write: the pointers hold no reverse index to read otherwise). O(its definitions)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db e]
  (boolean (and (seq (d/datoms db :eavt e :form/id))
                (let [ds (map :v (d/datoms db :eavt e :form/defines))]
                  (or (empty? ds) (some (fn [x] (some #(seq (d/datoms db :eavt x % e)) (vals pointer))) ds))))))

(defn- schema-value
  "Schema entry entity e's value as schema-changes reads it on db: its decoded form when it
  differs from the binary's, else nil."
  {:malli/schema [:=> [:cat :seon.host/db :int] :any]}
  [db e]
  (when-let [k (:v (first (d/datoms db :eavt e :schema/key)))]
    (some->> (first (d/datoms db :eavt e :schema/form)) :v (differing-form k))))

(defn diff-plan
  "What making a process run db-to's program where it holds db-from's takes: {:remove the
  definition keys db-from had and db-to has none of, sorted; :load db-to's live forms db-from
  did not have live, as rows by entity; :schemas the schema keys db-to changes (schema-changes')
  to a value db-from did not hold}. Read from the program attributes' datoms that differ between the two values
  (datahike.api/datoms-diff, which skips the stored subtrees they share), then each candidate
  entity's own datoms: the cost of the diff, never of the program."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:map [:remove [:vector :any]] [:load [:vector :map]] [:schemas [:set :qualified-keyword]]]]}
  [db-from db-to]
  (let [diff (fn [a] (d/datoms-diff db-from db-to :aevt [a]))
        [ptr-from ptr-to] (reduce (fn [[x y] a] (let [[p q] (diff a)] [(into x p) (into y q)])) [[] []] (vals pointer))
        defined? (fn [db x] (some #(seq (d/datoms db :eavt x %)) (vals pointer)))
        removed (sort-by str (distinct (for [dt ptr-from :let [x (:e dt)] :when (not (defined? db-to x))]
                                         (definition-key db-from x))))
        forms (into (sorted-set) cat [(mapcat (fn [[p q]] (map :e (concat p q))) [(diff :form/id) (diff :form/defines)])
                                      (map :v ptr-from) (map :v ptr-to)])
        schema-es (into #{} (mapcat (fn [a] (let [[p q] (diff a)] (map :e (concat p q))))) [:schema/key :schema/form])
        key-of (fn [e] (some #(some-> (first (d/datoms % :eavt e :schema/key)) :v) [db-to db-from]))]
    {:remove (vec removed)
     :load (vec (for [e forms :when (and (live-in? db-to e) (not (live-in? db-from e)))] (form-row db-to e)))
     :schemas (into #{} (for [e schema-es :let [v (schema-value db-to e)] :when (and (some? v) (not= v (schema-value db-from e)))] (key-of e)))}))

(defn hold-config!
  "Make this process hold the config db states (seon.config/hold!: db is its own branch, what it stands on), against db's schema entries beyond the binary's. Answers the refusal, as a load error's list (empty when held), so a config that doesn't hold says so where a form that doesn't load does, and the process goes on with what it held. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.program/error]]}
  [db]
  (if-let [e (cfg/hold! (cfg/entries db) (schema-changes db))]
    [{:ns 'seon.config :form cfg/shipped-file :message (:seon.error/message e)}]
    []))

(defn config-refused
  "The refusal, when tx-data touches a config entry on db and the entries it leaves don't make a config db's schema after it allows; nil otherwise. It reads
  only config and schema entries after tx-data, so it applies only their part of it (seon.db/project-tx): one scan of tx-data and a
  speculation of what it touches, never of the whole import. Asked before a branch's write or an import commits. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat :seon.host/db :seon.db/tx-data] [:maybe :map]]}
  [db tx-data]
  (when (some #(if (map? %)
                 (contains? % :config/form)
                 (or (= :config/form (nth % 2 nil))
                     (and (= :db/retractEntity (first %)) (:config/key (d/entity db (second %))))))
              tx-data)
    ;; no entries left is nothing stated, as a tree with no config file states nothing (hold!)
    (let [after (pdb/speculate-db db (pdb/project-tx tx-data #(#{"config" "schema"} (namespace %))))]
      (when-let [es (cfg/entries after)]
        (let [r (cfg/of-entries es (schema-changes after))]
          (when (:seon.error/type r) r))))))

(defn program-differs
  "What this process runs that db-to's program does not, over what db-to changed from db-from (diff-plan's load set, the
  diff an agent loads at its start): each var a changed form defines whose form this process last evaluated is another
  (loaded), or, for a var it never loaded, whose form is not the binary's (held?), as {:var :form (db-to's) :loaded (this
  process's, nil when none)}; [] when the process runs db-to's program
  there. The oracle a run asks before its first item (the runtime invariant: a process runs its branch's program). Costs
  the diff, never the program. forms, the binary's form ids, is the running binary's record by default (binary-program).
  See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db :seon.host/db [:maybe [:set :string]]] [:vector :map]]]}
  ([db-from db-to] (program-differs db-from db-to (:forms (binary-program))))
  ([db-from db-to forms]
   (let [now @loaded]
     (vec (for [f (:load (diff-plan db-from db-to)) [k s] (:defines f)
                ;; a var this process never loaded runs the binary's form: the changed form is held when it is that one (held?)
                :when (and (= :var k) (not= (:id f) (get now s)) (not (and (nil? (get now s)) (held? db-to forms f))))]
            {:var s :form (:id f) :loaded (get now s)})))))

(defn reload-dependents!
  "Load again, in this process, the live forms of db that expanded a macro the forms ids (form ids, just recorded) define,
  and theirs (dependents): what an eval that redefines a macro owes the code this process already compiled from it, which
  Clojure's eval leaves on the old expansion (a test defined before its macro changed ran stale, and its process's
  program-differs saw nothing, its form id unchanged: Data model's first task as a Seon agent). The same act
  load-diff! does at a spawn. Each loaded form is noted as this process's (load-form!). Answers the load errors. See
  docs/source.md#an-agent-changes-code-at-its-repl."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] [:vector :seon.program/error]]}
  [db ids]
  (let [rows (vec (keep #(some->> (:db/id (d/entity db [:form/id %])) (form-row db)) ids))
        ds (if (seq rows) (dependents db rows) [])]
    (if (seq ds) (load-forms! db ds) [])))

(defn load-diff!
  "Make this process run db-to's program where it holds db-from's (diff-plan): each
  definition db-from had that db-to has none of is removed, and each live form db-to has
  that db-from had not is loaded, every one, with what it reaches (load!), and
  what the changed schema keys reach, and it holds db-to's config (hold-config!). Answers the load errors. Costs the diff (diff-plan);
  it read both programs whole before: 400-450 ms a spawn on an existing branch, measured."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.program/error]]}
  [db-from db-to]
  (let [{:keys [remove load schemas]} (diff-plan db-from db-to)]
    (run! remove-definition! remove)
    ;; every form the diff names is the branch's own: this process holds db-from's definition of it, never the branch's,
    ;; though its var is bound and the form was imported from a source tree (a tree branch's catch-up imports the checkout's
    ;; changed files: held? read those as the binary's own and kept main's code, and a --root run judged main's code, 2026-11-11)
    (into (vec (load! db-to load schemas true)) (hold-config! db-to))))

(defn load-changes!
  "Load what resolved tx-data ops change, read against db (after them): each definition
  they leave pointing to no form is removed, and each live form they assert (but the
  binary's own) is loaded, with what it reaches (load!: where a changed macro expanded, and
  the contracts a changed schema entry reaches), and when they touch a config entry the config is held again (hold-config!: no op else reads it). With {:tests? false} (root's) the test
  namespaces' forms are left unevaluated (load-all!), and :forms is the binary's form ids (load-all!'s). Answers the error maps
  (empty when all loaded)."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:sequential :seon.db/op]] [:vector :seon.program/error]]
                  [:=> [:cat :seon.host/db [:sequential :seon.db/op] [:map [:tests? {:optional true} :boolean] [:forms {:optional true} [:maybe [:set :string]]]]] [:vector :seon.program/error]]]}
  ([db ops] (load-changes! db ops {}))
  ([db ops {:keys [tests?] :or {tests? true} :as opts}]
  (let [binary (if (contains? opts :forms) (:forms opts) (:forms (binary-program)))
        ptr (set (vals pointer))
        added (set (for [[op e a] ops :when (and (= op :db/add) (ptr a))] e))
        ;; a definition these ops moved to another form: what this process bound is the old form, so being bound says nothing (held?)
        replaced (set (for [[op e a] ops :when (and (= op :db/retract) (ptr a) (added e))] e))]
    (doseq [[op e a] ops
            :when (and (= op :db/retract) (ptr a) (not (added e)))
            :let [x (d/entity db e)]]
      (cond (:var/name x) (remove-definition! [:var (:var/name x)])
            (:method/key x) (remove-definition! (into [:method] (sedn/decode (:method/key x))))))
    (into (vec (load! db (vec (for [[op e a] ops
                                    :when (and (= op :db/add) (= a :form/src) (live? db e))
                                    :let [f (form-row db e)]
                                    :when (or (not (held? db binary f)) (some #(replaced (:v %)) (d/datoms db :eavt e :form/defines)))]
                                f))
                      (set (for [[op e a] ops :when (and (= op :db/add) (= a :schema/form))
                                 :when (schema-value db e)]
                             (:schema/key (d/entity db e))))
                      tests?))
          (when (some (fn [[_ _ a]] (= a :config/form)) ops) (hold-config! db))))))

;; ---- reach: which tests a change reaches ----------------------------------------------

(def tree-readers
  "The vars that read the checkout's files or run the binary built from them: a test whose
  reached forms reference one has the whole tree as an input, so its reach digest covers it
  (graph-index), unless the form referencing it defines one of env-readers. The host's file
  readers (io/resource reads the resources/ classes), and seon.test-store/binary-sh, the one way a test runs target/seon, bin/system or
  bin/check: what such a test proves is the whole program the binary is built from."
  '#{clojure.core/slurp clojure.core/file-seq clojure.java.io/resource seon.test-store/binary-sh})

(def ^:private doc-readers
  "The vars a test calls to read a docstring: clojure.repl's doc. A var's metadata read for its :doc is no reader here:
  clojure.core/meta is in every deftest's expansion, and :doc is no qualified keyword a form's facts hold, so a test reading
  (:doc (meta v)) is the full run's to catch. See docs/source.md#what-a-test-takes-of-a-forms-text."
  '#{clojure.repl/doc})

(def ^:private doc-attrs
  "The attributes holding a docstring as a fact. See docs/source.md#what-a-test-takes-of-a-forms-text."
  #{:var/doc :ns/doc})

(def file-readers
  "The vars a test calls to read files raw: the tree-readers but binary-sh (which runs the binary: its tests are digested by
  what they reach). A raw read takes the whole tree only when it reads the checkout (checkout-read?); src's readers of one
  part of the checkout (the wiki, the task files, the source) take that part's class instead (env-class-readers: the
  wiki's seon.doc/pages and load-dir-tx, seon.system/task-files, seon.fx/read-source), so a wiki edit moves only the tests
  that read the wiki. See docs/source.md#tests-run-by-reach."
  (disj tree-readers 'seon.test-store/binary-sh))

(def env-class-readers
  "The vars that read one class of the checkout's files that have no program facts
  (seon.fx/inputs' :seon.env/* entries of :config, one digest each): a test reaching one takes
  that class's digest. The bin/ scripts, web/ and resources/public, system.edn, deps.edn and the
  schema resources (read whole only by the two vars that filter a db's schema by namespace) are classes; the rest (native/, the pins, test fixtures) is every test's
  (:env). A form reading files (tree-readers) or running a process (process-vars, binary-vars)
  takes every class, which graph-index adds from its flags, not this map."
  '{seon.fx/declared-refs :seon.env/system
    seon.graph/read-decl :seon.env/system
    seon.web/handler :seon.env/web
    seon.fork-server/dev-classpath! :seon.env/deps
    clojure.java.shell/sh :seon.env/bin
    ;; the vars that walk every attribute a db's schema declares (the schema resources' entries)
    ;; and keep those of a namespace: a new attribute changes what they do, though no test names it
    seon.reconcile/index :seon.env/schemas
    seon.merge/content-datoms :seon.env/schemas
    ;; the entity index: which entity schema an entity is, by the attributes it holds, chosen at run time from every entity
    ;; schema's form, so a test naming only attributes reads the entity schemas (their renders, patterns, refs) no name
    ;; reaches (a render removed from :seon.cite/cite-fact kept a test green at its digest, 2026-11-10). About 1,150 of
    ;; 1,612 tests reach it: an entity schema edited reruns them (docs/source.md#tests-run-by-reach)
    seon.db/entity-index :seon.env/schemas
    ;; the test that reads the checkout's own program from its kept db (docstrings, form sources): a
    ;; change to any src or test text is a change to what it reads, though no test names the var
    seon.test-store/kept-program-store :seon.env/src
    ;; every var of the process's namespaces without a contract (bin/check contracts): a new function in
    ;; src changes its answer, though no test names it (batch 18's scan-code went uncontracted with
    ;; the contracts step green at its digest, 2026-10-30)
    seon.contract/uncontracted :seon.env/src})

(def test-class-readers
  "src's readers of one part of the checkout, read whole, and the class each reads: the wiki (a page, or every page loaded),
  the task files, the source. A test namespace's form calling one takes that class (graph-index's :classed), as one reading
  the checkout raw takes the tree (:scans); they were file-readers, which took the whole tree. Only a test's own call: a src
  form calling one (a boot indexing the wiki) is read through the world the test builds, which its own reach digests, as
  before. See docs/source.md#tests-run-by-reach."
  ;; as file-readers, a 4-line wiki edit ran 117 tests (2026-11-11)
  '{seon.doc/pages :seon.env/docs
    seon.doc/load-dir-tx :seon.env/docs
    seon.system/task-files :seon.env/tasks
    seon.fx/read-source :seon.env/src})

(defn- test-class-of
  "The class of the checkout's inputs a test namespace's call of var s reads whole (test-class-readers), or nil; or, when s is
  a reader of the agent's own branch with no db handed in (graph-index's readers over branch-reads), :seon.env/data, whose test is :data?: an agent
  mixes its branch's point into its digest (seon.agent/test-plan); a run with no branch (bin/test, main's suite) mixes none.
  See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat [:or [:set :symbol] :seon.host/fn] :symbol] [:maybe :qualified-keyword]]}
  [readers s] (or (test-class-readers s) (when (readers s) :seon.env/data)))

(def ^:private branch-reads
  "The vars that read an agent's own branch with no db handed in: seon.agent/db (the agent's state's branch) and seon.ctx/ctx
  (the agent's record, made from it). A var of the agent's API (a my.* namespace) whose own form calls one reads it too
  (graph-index's readers): (my.db/q ...), (my.agent/prove). A function handed its db reads what the test built, so it is none.
  See docs/merging.md#verdicts-are-a-cache."
  '#{seon.agent/db seon.ctx/ctx})

(def registry-walkers
  "The vars that hold every schema entry: a form of a test's namespace naming one reads every entry, a
  key's unit or description included, which no digest holds for it (less-config-fields; a key's entry moves only
  the tests naming the key), so it takes :seon.env/schemas. A form of src naming one does not: 1,426 of
  1,469 tests reach them through the contracts and the renders. See docs/source.md#tests-run-by-reach."
  '#{seon.config/keys-declared seon.config/schema-forms seon.contract/forms})

(def env-readers
  "The platform vars whose file reads are of a class of inputs a digest holds apart from the
  tree (env-class-readers: the schema resources, system.edn, deps.edn, web/public) or of no
  checkout file at all, so reaching them
  does not make a test read the tree. A reader not named here counts as reading the tree
  (conservative: a missed name reruns more, never reuses a stale green). Measured: with
  seon.config/baked-schema-forms (a macro every contracted form expands) counted, 327 of 368
  tests took the tree, so any edit anywhere reran them; with seon.test-store/output (a run's own
  log or a child's mark) uncounted, 7 of the 10 slow process tests took it, so a wiki edit reran them;
  with seon.config/baked-shipped-text (the shipped config file, whose values a test takes key by key) counted,
  1,506 of 1,552 tests took every file class, so a bin/ or schema-resource edit reran 1,517."
  (conj (set (keys env-class-readers)) 'seon.config/baked-schema-forms 'seon.config/baked-shipped-text 'seon.test-store/output))

(def config-resolvers
  "seon.config's resolvers, which read the key they are passed (seon.config/value, resolve and
  resolve-in): a form outside seon.config that calls one and names no config key of its own reads a
  key it computes, from data (computed-config-reader?)."
  '#{seon.config/value seon.config/resolve seon.config/resolve-in})

(defn- computed-config-reader?
  "Does row f read a config key named in data rather than in its own code, by its facts: it
  references a resolver (config-resolvers), is not seon.config's own, and its keywords name no
  seon.config.* key (seon.render/pattern reads a join's :limit, a key a schema entry's
  :seon.ctx/pattern names). Such a form takes every config key a schema entry names
  (:schema-config). A form that names one key and computes another is the daily full run's."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [f]
  (boolean (and (some config-resolvers (:refs f)) (not= 'seon.config (:ns f))
                (not-any? #(some-> (namespace %) (str/starts-with? "seon.config.")) (:keywords f)))))

(defn- config-key?
  "Is k a config key: a keyword of a seon.config.* namespace?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [k] (boolean (and (keyword? k) (some-> (namespace k) (str/starts-with? "seon.config.")))))

(defn- less-config-fields
  "Schema form f less the config keys a :map lists as its fields ([key key], [key {props} key]): the config map
  (:seon.config/config) names every key, and a key is an input by the entry it names and the value it reads
  (config-reads), never by being listed (docs/source.md#tests-run-by-reach). Any other form as it is."
  {:malli/schema [:=> [:cat :any] :any]}
  [f]
  (if (and (vector? f) (= :map (first f))) (into [] (remove #(and (vector? %) (config-key? (first %)))) f) f))

(defn- less-targets
  "Schema form f less the targets its properties name (:seon.db/to, a ref attribute's): a ref's value is checked as
  a ref whatever it points at, so the schemas it names are links for the readers that follow them (seon.db/targets),
  never what a value of the entry is checked against, and no edge of the entry (docs/source.md#tests-run-by-reach)."
  {:malli/schema [:=> [:cat :any] :any]}
  [f]
  (if (and (vector? f) (map? (second f)) (contains? (second f) :seon.db/to)) (update f 1 dissoc :seon.db/to) f))

(defn- config-facts
  "What the schema entries (schemas: {key text}) say of the config keys, from one read of each entry that is
  not itself a config key's: {:keys the keys their forms name as data, a map's fields (less-config-fields)
  left out: what a config-key-reader can read (:schema-config), :listed the keys a :map lists as its
  fields: the config map's}."
  {:malli/schema [:=> [:cat :map] [:map [:keys [:set :qualified-keyword]] [:listed [:set :qualified-keyword]]]]}
  [schemas]
  (reduce-kv (fn [acc k text]
               (if (config-key? k)
                 acc
                 (let [f (sedn/decode text)]
                   (-> acc
                       (update :keys into (filter config-key?) (names (less-config-fields f)))
                       (update :listed into (keep #(when (and (vector? %) (config-key? (first %))) (first %))) (when (and (vector? f) (= :map (first f))) f))))))
             {:keys #{} :listed #{}} schemas))

(defn- checkout-read?
  "Does form f's raw read (file-readers) read the checkout: io/resource (the classpath is the checkout's resources), or a
  read in a form that names the checkout (seon.config/checkout) or a relative path (a literal with a slash, not rooted: a
  shard's working directory is the checkout)? A form reading only paths it made (a temp directory, a log, a run's out
  directory) reads nothing of the checkout and takes no tree. Its edge, a checkout path built in a helper the form calls
  without naming it, is the full run's (task seon.program/a-test-reads-the-checkout-only-through-a-classed-reader).
  See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [f]
  (boolean (or (some #{'clojure.java.io/resource 'seon.config/checkout} (:refs f))
               ;; the form as the reader reads it (it was loaded, so it reads), its strings walked: no text matched
               (some #(and (string? %) (str/includes? % "/") (not (str/starts-with? % "/")) (not (str/includes? % " ")))
                     (when-let [src (:src f)] (tree-seq coll? seq (binding [*read-eval* false] (read-string src))))))))

(defn- env-reader-form?
  "Does form f define only env-readers (its file reads are of a class held apart from the tree, or of no checkout file)?"
  {:malli/schema [:=> [:cat :map] :boolean]}
  [f] (boolean (and (seq (:defines f)) (every? (fn [[k s]] (and (= :var k) (env-readers s))) (:defines f)))))

(defn- tree-reading?
  "Does form f read the tree (a tree-reader referenced, by a form defining no env-reader)?"
  {:malli/schema [:=> [:cat :map] :boolean]}
  [f] (boolean (and (some tree-readers (:refs f)) (not (env-reader-form? f)))))

(defn- uses?
  "Does form f use one of vars (:seon.config.source/process-vars or -binary-vars)? A form uses
  a var it references and does not define (a var's own form names itself); a name in its
  quoted data is no reference (unit-account)."
  {:malli/schema [:=> [:cat [:set :qualified-symbol] :map] :boolean]}
  [vars f]
  (let [own (set (keep (fn [[k s]] (when (= :var k) s)) (:defines f)))]
    (boolean (some #(and (vars %) (not (own %))) (:refs f)))))

(defn- schema-texts
  "{schema key -> its form's text} of db, from two index scans."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [text (into {} (map (juxt :e :v)) (d/datoms db :aevt :schema/form))]
    (into {} (for [dt (d/datoms db :aevt :schema/key)] [(:v dt) (text (:e dt))]))))

(defn- schema-row? {:malli/schema [:=> [:cat :map] :boolean]} [f] (contains? f :schema))

(defn- pin-rules
  "pins (:seon.config.source/pin-namespaces: prefix -> submodules or source groups) as [prefix
  prefix. [:seon.pin/NAME ...]] rows (seon.config/pin-class), matched against a namespace by = and
  starts-with?."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [pins]
  (vec (for [[p ms] pins :let [s (str p)]] [s (str s ".") (mapv cfg/pin-class ms)])))

(defn- pin-reads
  "The :seon.pin/NAME classes of the submodules the namespaces of refs run on, by rules
  (pin-rules): a namespace is a prefix or under it. A library used through interop or a :require
  with no var reference is not seen: the daily full run is the net."
  {:malli/schema [:=> [:cat [:set :qualified-symbol] [:vector :any]] [:set :qualified-keyword]]}
  [refs rules]
  (into #{} (for [r refs :let [n (namespace r)] [p p. ks] rules :when (or (= n p) (str/starts-with? n p.)) k ks] k)))

(defn config-reads
  "The config keys form f reads, from its facts: the keys its constants name (:form/keywords
  of a seon.config.* namespace, the config's keys). A form that takes a whole config map (the
  config in force, seon.config/config, or the one root wrote on main, seon.config/from-db) reads
  the keys it names, no more: a key it reads by a value it computes is the daily full run's to
  catch (owner: \"no test digests the whole tree\"; every config key a root's boot
  took moved the tests that boot one, 57 of them, at every new key). seon.config's own readers
  (value, resolve) name no key of their own; every one of their callers names its key, but for
  computed-config-reader?'s. A form baking the schemas (referencing seon.config/baked-schema-forms:
  seon.config/schema-forms, its one form, which holds nothing else) names every key as its
  declaration, data its class holds, and reads none. Beside the keys, the file classes it reads
  (env-class-readers): the :seon.env/* entries of :config. A test's digest takes the values of
  the keys and classes its reach reads (graph-index), so a key no test reads moves no digest."
  {:malli/schema [:=> [:cat [:set :qualified-keyword] [:set :qualified-symbol] [:maybe :symbol] [:vector :any]] [:set :qualified-keyword]]}
  [keywords refs nsym pins]
  (let [classes (into (pin-reads refs pins) (keep env-class-readers) refs)]
    (if (refs 'seon.config/baked-schema-forms)
      classes
      (into classes (filter #(str/starts-with? (namespace %) "seon.config.")) keywords))))

(defn- reach-rows
  "Form entities es as the reach walk reads them (reach-step): each one's :id, :own (its :form/code,
  form-ids': the digest of its namespace and code, comments and whitespace runs left out;
  a form stored without one is taken by its :form/id with its occurrence (form-id's #k) left off: the hash of
  its namespace and exact text), :ns, :defines, :refs, :quoted (the names its quoted data holds: an edge
  in a test's namespace, reach-step) and the config keys it reads (config-reads), and none of
  the other facts, which no edge, digest or flag reads. Each attribute is read once, for all of es together: one pass over its index when scan?
  (es is most of the db's forms), else a seek per entity."
  {:malli/schema [:=> [:cat :seon.host/db [:set :int] :boolean] [:vector :map]]}
  [db es scan?]
  (let [pins (pin-rules (cfg/value :seon.config.source/pin-namespaces))
        at (fn [a es] (if scan?
                        (persistent! (reduce (fn [m dt] (let [e (:e dt)] (if (contains? es e) (assoc! m e (conj (get m e []) (:v dt))) m)))
                                             (transient {}) (d/datoms db :aevt a)))
                        (into {} (for [e es :let [vs (mapv :v (d/datoms db :eavt e a))] :when (seq vs)] [e vs]))))
        id (at :form/id es) nsv (at :form/ns es) dv (at :form/defines es) rv (at :form/refs es) kv (at :form/keywords es) qv (at :form/quoted es) ov (at :form/ops es)
        cv (at :form/code es)
        ds (set (mapcat val dv))
        nn (at :ns/name (set (mapcat val nsv)))
        key-of (merge ;; definition-key's order: the earlier kind wins
                 (update-vals (at :ns/name ds) (fn [[v]] [:ns v]))
                 (update-vals (at :impl/key ds) (fn [[v]] (into [:impl] (sedn/decode v))))
                 (update-vals (at :method/key ds) (fn [[v]] (into [:method] (sedn/decode v))))
                 (update-vals (at :var/name ds) (fn [[v]] [:var v])))]
    (vec (for [e es :let [[i] (id e) nsym (first (nn (first (nsv e)))) refs (set (rv e))
                          full (str/replace i #"#\d+$" "")
                          defines (into #{} (keep key-of) (dv e))]]
           {:id i :own (or (first (cv e)) full)
            :ns nsym :refs refs :quoted (set (qv e)) :ops (set (ov e))
            :defines defines
            :keywords (set (kv e))
            :config (config-reads (set (kv e)) refs nsym pins)}))))

(defn reached-by
  "The forms that reach forms ids through a reach graph's reverse edges rev ({form [form ...]}),
  nearest first, ids left out: what a change to ids moves (reach-step re-folds them, and a test
  among them is one whose reach digest moves, the test a change to ids selects)."
  {:malli/schema [:=> [:cat :map [:or [:sequential :any] [:set :any]]] [:vector :any]]}
  [rev ids] (closure ids #(get rev %)))

(defn tests-reaching
  "The tests of reach graph g whose reach includes var v, nearest first: the tests a change to v
  selects (reached-by over g's reverse edges, as the change's own re-fold walks them; a change
  marks v's forms and, beside them, what names v, which the reverse edges reach from v's forms
  too), v left out. A var g holds no form of (a library's) reaches none: []."
  {:malli/schema [:=> [:cat :map :qualified-symbol] [:vector :qualified-symbol]]}
  [{:keys [rows defs rev tests]} v]
  (into [] (comp (mapcat #(keep (fn [[k s]] (when (and (= :var k) (tests s) (not= v s)) s)) (:defines (rows %)))) (distinct))
        (reached-by rev (get defs v []))))

(defn- indexed
  "{k ids} of pairs [k id], in order, each ids a vector (a set when set? is true): the map a graph's
  defs, refd, rev and plain are, built in one pass over transients. reach-step builds a graph from
  nothing this way, where folding the pairs in one at a time copied a vector or a set per pair."
  {:malli/schema [:=> [:cat [:sequential [:tuple :any :any]] :boolean] :map]}
  [pairs set?]
  (let [empty-of (fn [] (if set? (transient #{}) (transient [])))
        m (reduce (fn [m [k id]] (let [t (get m k)] (if t (do (conj! t id) m) (assoc! m k (conj! (empty-of) id)))))
                  (transient {}) pairs)]
    (persistent! (reduce-kv (fn [m k t] (assoc! m k (persistent! t))) (transient {}) (persistent! m)))))

(defn- reach-step
  "Graph g after the forms removed (ids) and the rows added (reach-rows', live on db): each
  form's neighbours recomputed only where its edges can have changed (an added form; a form
  referring to, naming in a test's namespace (:quoted), or defining the methods, extensions or
  contract of, a var a changed form defines), and the components of the changed forms and of every form reaching them re-folded
  (components over those alone, the rest kept). touched, forms whose edges changed with no form of theirs (a schema entry
  added or removed that they name: schema-moves). :affected, how many forms were re-folded. A held index
  (reach-indexed) keeps the answers of the tests these changes cannot move, and names the others :stale."
  {:malli/schema [:=> [:cat :map :seon.host/db [:sequential :string] [:sequential :map] [:set :string]] :map]}
  [g db removed added touched]
  (let [data-refs (:data-refs g)
        old (:rows g)
        bulk? (empty? old)
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
        ;; a form reading a key it computes reads the config keys schema entries name (:schema-config,
        ;; taken at the fold, so a schema entry added or removed moves them by the fold alone)
        computed (into (apply disj (:computed g #{}) removed) (comp (filter computed-config-reader?) (map :id)) added)
        ;; a schema key a form names as a literal keyword is an edge to that entry (a lookup by
        ;; the key it passes, valid?, a registered error variant); the keywords are then dropped.
        ;; The form that bakes the registry (seon.config/schema-forms) holds every key as data, not as lookups
        added (vec (for [f added] (-> f (assoc :sedges (if ((:refs f) 'seon.config/baked-schema-forms)
                                                         []
                                                         (vec (for [k (:keywords f) :when (contains? (:schemas g) k)] (str "schema " k)))))
                                      ;; a docstring fact it names is kept, for :docs
                                      (cond-> (some doc-attrs (:keywords f)) (assoc :doc-attr? true))
                                      (dissoc :keywords))))
        rows (into (apply dissoc old removed) (map (juxt :id identity)) added)
        defs (if bulk? (indexed (for [f added a (defined f :var)] [a (:id f)]) false) (by (:defs g) :var))
        methods (if bulk? (indexed (for [f added a (defined f :method)] [a (:id f)]) false) (by (:methods g) :method))
        impls (if bulk? (indexed (for [f added a (defined f :impl)] [a (:id f)]) false) (by (:impls g) :impl))
        var-form #(peek (get defs %))
        ;; a var's contract text and meta: one pass over the three indexes for a whole build, else a seek per var
        facts (if bulk?
                (let [scan (fn [a] (into {} (map (juxt :e :v)) (d/datoms db :aevt a)))
                      contract (scan :var/contract)
                      mta (reduce (fn [m {e :e k :v}] (update m e (fnil conj #{}) k)) {} (d/datoms db :aevt :var/meta))]
                  (into {} (for [{e :e v :v} (d/datoms db :aevt :var/name) :when (V v)] [v {:contract (contract e) :meta (mta e)}])))
                (into {} (for [v V :let [e (:e (first (d/datoms db :avet :var/name v)))]]
                           [v (when e {:contract (:v (first (d/datoms db :eavt e :var/contract))) :meta (into #{} (map :v) (d/datoms db :eavt e :var/meta))})])))
        sids (fn [text] (into [] (map #(str "schema " %)) (names (sedn/decode text))))
        contract (reduce (fn [m [v e]] (if-let [t (:contract e)] (assoc m v (sids t)) (dissoc m v))) (:contract g) facts)
        tests (reduce (fn [s [v e]] (if (contains? (:meta e) :test) (conj s v) (disj s v))) (:tests g) facts)
        ;; the entry of what decides a test's outcome (^:seon.test/runner, seon.agent/run-children!: the children and their fold): every test's digest takes
        ;; what they reach (index-answer's runner), so a runner fix reruns every test it may have judged wrongly
        runners (reduce (fn [s [v e]] (if (contains? (:meta e) :seon.test/runner) (conj s v) (disj s v))) (:runners g #{}) facts)
        ;; a name quoted in a test's namespace is one the test hands to what runs it by name
        ;; ((cost/proof {:of 'ns/f}), resolve, an agent's eval): a reach edge there, as a
        ;; reference is (a quoted name in src is data: schema-forms' renders, tree-readers)
        test-nss (if (identical? tests (:tests g)) (:test-nss g #{}) (into #{} (map (comp symbol namespace)) tests))
        ;; and so is one a method of an entry multimethod (seon.server/op-answer) quotes: the form
        ;; it sends the process it answers for ('seon.turn/hand-back!)
        entry (cfg/value :seon.config.source/process-entry)
        entry-method? (fn [f] (some (fn [[k m]] (and (= :method k) (entry m))) (:defines f)))
        named (fn [f] (if (or (test-nss (:ns f)) (entry-method? f)) (into (:refs f) (:quoted f)) (:refs f)))
        refd (if bulk?
               (indexed (for [f added r (named f)] [r (:id f)]) true)
               (reduce (fn [m f] (reduce #(update %1 %2 (fnil conj #{}) (:id f)) m (named f)))
                       (reduce (fn [m f] (reduce #(update %1 %2 disj (:id f)) m (named f))) (:refd g) gone) added))
        ;; a test's form is reached only as the root it is: another form naming a test (its
        ;; name quoted as data, a proof list) never runs it, clojure.test does
        test-ids (set (keep var-form tests))
        flag (fn [k pred] (into (apply disj (or (k g) #{}) removed) (comp (filter pred) (map :id)) added))
        pvars (cfg/value :seon.config.source/process-vars)
        bvars (cfg/value :seon.config.source/binary-vars)
        reads (flag :reads tree-reading?) proc (flag :proc #(uses? pvars %)) bin (flag :bin #(uses? bvars %))
        walks (flag :walks #(and (test-nss (:ns %)) (some registry-walkers (:refs %))))
        ;; a form that starts a process (a process var, the binary) reaches what that process runs
        ;; for it, over a socket and as a quoted form it is sent, edges no reference shows: the
        ;; entry vars' forms (the agent's turn) and, of an entry multimethod (the server's
        ;; op-answer), only the methods of the ops the form sends (its :ops; any form of a test's
        ;; namespace, which talks to a root it started), not the multimethod, which reaches every method
        entry-ids (vec (keep var-form (remove #(seq (get methods %)) entry)))
        op-ids (fn [f] (for [m entry :when (seq (get methods m)) id (get methods m)
                             :when (some (fn [[k mm d]] (and (= :method k) (= m mm) (some #(= d (sedn/encode %)) (:ops f)))) (:defines (rows id)))]
                         id))
        edges (fn [f] (if (schema-row? f)
                        ;; the entries it names, and the vars (its renders) its properties name
                        (vec (concat (filter rows (:schema f)) (keep var-form (:refs f))))
                        (vec (concat (remove test-ids (keep var-form (named f)))
                                     (when (or (proc (:id f)) (bin (:id f))) (remove #{(:id f)} entry-ids))
                                     ;; a request a test's code builds goes to a root's server (in-agent's ask)
                                     (when (or (proc (:id f)) (bin (:id f)) (test-nss (:ns f))) (op-ids f))
                                     (filter rows (:sedges f))
                                     (for [s (defined f :var)
                                           id (concat (get methods s) (get impls s) (filter rows (get contract s)) (keep var-form (get data-refs s)))]
                                       id)))))
        holders (for [[s vs] data-refs :when (some V vs) id (get defs s)] id)
        ;; an entry var or method changed: every form whose edges go to them (one that starts a
        ;; process, or sends an op from a test's namespace) is re-edged
        starters (when (some (into V M) entry) (concat proc bin (filter #(seq (:ops (rows %))) (keys rows))))
        dirty (-> (into (set (map :id added)) touched)
                  (into (mapcat #(get refd %)) V)
                  (into (keep var-form) (concat V M P))
                  (into holders)
                  (into starters)
                  (as-> d (into #{} (remove gone-ids) d)))
        ;; a test namespace's form reading the checkout's files itself (file-readers, checkout-read?) judges those files:
        ;; the tests reaching it take the tree (one-printer-and-no-cut-before-it
        ;; was reused over edits to what it read, and read as flaky)
        scans (flag :scans #(and (test-nss (:ns %)) (some file-readers (:refs %)) (checkout-read? %) (not (env-reader-form? %))))
        ;; a test namespace's form reading one part of the checkout whole (test-class-readers) takes that part's class
        ;; one lookup a reference: a direct reader, or an agent API var whose own form calls one (its row, by var-form)
        readers (fn [s] (or (branch-reads s) (and (some-> (namespace s) (str/starts-with? "my.")) (some branch-reads (:refs (rows (var-form s)))))))
        classed (flag :classed #(and (test-nss (:ns %)) (some (partial test-class-of readers) (:refs %))))
        ;; a test namespace's form reading a docstring (clojure.repl/doc, a var's or namespace's doc fact) judges docs,
        ;; which a form's code leaves out (form-ids): it reads the src class, the program's text, as kept-program-store's readers do
        docs (flag :docs #(and (test-nss (:ns %)) (or (some doc-readers (:refs %)) (:doc-attr? %))))
        adj (into (apply dissoc (:adj g) gone-ids) (for [id dirty] [id (edges (rows id))]))
        rev (if bulk?
              (indexed (for [id dirty t (adj id)] [t id]) true)
              (as-> (:rev g) r
                (reduce (fn [r id] (reduce #(update %1 %2 disj id) r (get-in g [:adj id]))) r (concat dirty gone-ids))
                (reduce (fn [r id] (reduce #(update %1 %2 (fnil conj #{}) id) r (adj id))) r dirty)
                (apply dissoc r gone-ids)))
        plain? (fn [f] (and (not (schema-row? f)) (not-any? (fn [[k]] (= :var k)) (:defines f))))
        plain (if bulk?
                (indexed (for [f added :when (plain? f)] [(:ns f) (:id f)]) true)
                (as-> (:plain g) pl
                  (reduce (fn [pl f] (update pl (:ns f) disj (:id f))) pl (filter plain? gone))
                  (reduce (fn [pl f] (update pl (:ns f) (fnil conj #{}) (:id f))) pl (filter plain? added))))
        own (into (apply dissoc (:own g) removed) (for [f added] [(:id f) (:own f)]))
        changed (into dirty (map :id) added)
        affected (into changed (reached-by rev changed))
        old-comp (:comp-of g)
        olds (keep old-comp affected)
        ;; the index the graph holds (reach-indexed) loses the tests whose answer these changes can move: a
        ;; test whose form was re-folded (it reaches what changed), one of a namespace whose plain forms
        ;; were added, removed or re-folded; and the tests removed. A component kept keeps its digest and flags.
        held (:index g)
        tests-by-ns (when held (if (and (identical? tests (:tests g)) (:tests-by-ns g)) (:tests-by-ns g) (group-by (comp symbol namespace) tests)))
        stale (when held
                (-> (into #{} (for [id affected, [k a] (:defines (rows id)) :when (and (= :var k) (tests a))] a))
                    (into (mapcat tests-by-ns)
                          (into #{} (comp (filter plain?) (map :ns)) (concat gone (map rows affected))))))
        dead (when held (filter #(and ((:tests g) %) (not (tests %))) V))
        ;; the affected components re-folded sinks first (Bazel's and Nix's action keys): a
        ;; component's digest is its forms' own and those of the components it reaches
        [comp-of cd fl n] (reduce (fn [[comp-of cd fl n] c]
                                    (let [ms (set c)
                                          kids (distinct (for [id c w (adj id) :when (not (ms w))] (get comp-of w)))
                                          any (fn [k fs] (boolean (or (some fs c) (some #(get-in fl [% k]) kids))))]
                                      [(reduce #(assoc! %1 %2 n) comp-of c)
                                       (assoc! cd n (lib/digest (str (str/join " " (sort (map own c))) "|" (str/join " " (sort (map cd kids))))))
                                       (assoc! fl n {:reads (any :reads reads) :scans (any :scans scans) :proc (any :proc proc) :bin (any :bin bin)
                                                    ;; the config keys it and what it reaches read, its largest
                                                    ;; kid's set grown by the rest
                                                    :config (let [own (map #(let [cf (cond-> (:config (rows %)) (walks %) (-> (or #{}) (conj :seon.env/schemas)) (docs %) (-> (or #{}) (conj :seon.env/src))
                                                                                (classed %) (-> (or #{}) (into (keep (partial test-class-of readers)) (:refs (rows %)))))]
                                                                         (if (computed %) (into (or cf #{}) (:schema-config g)) cf))
                                                                     c)
                                                                  ks (keep #(get-in fl [% :config]) kids)]
                                                              (let [[k & more] (sort-by (comp - count) ks)]
                                                                (-> (or k #{}) (into cat own) (into cat more))))})
                                       (inc n)]))
                                  [(transient (apply dissoc old-comp gone-ids))
                                   (transient (apply dissoc (:cd g) olds))
                                   (transient (apply dissoc (:flags g) olds))
                                   (:next g)]
                                  (components (vec affected) (fn [id] (filter affected (adj id)))))]
    (cond-> (assoc g :rows rows :defs defs :methods methods :impls impls :contract contract :tests tests :runners runners :test-nss test-nss :refd refd :adj adj :rev rev
           :reads reads :scans scans :classed classed :docs docs :proc proc :bin bin :walks walks :plain plain :own own :computed computed
           :comp-of (persistent! comp-of) :cd (persistent! cd) :flags (persistent! fl) :next n :affected (count affected))
      held (assoc :tests-by-ns tests-by-ns
                  :index (update held :answers #(apply dissoc % (concat stale dead)))
                  :stale (as-> (into (:stale g #{}) stale) st (reduce disj st dead))))))

(defn- schema-row
  "The reach row of schema entry k (its text): its id, its own digest, every entry its form names, held
  whether or not the entry exists (reach-step's edges keep those rows holds), so one added later is an edge,
  and every var its form names (a render its properties declare, :seon.render/hiccup, :seon.render/model): a
  test reaching the entry reaches the render, so a change to the render's body moves it (a red recorded
  while a render was unfinished was marked flaky at the same digest: step (c) of
  seon.ctx/the-page-is-the-render-of-what-ctx-pulls). A config map's fields (less-config-fields) are
  neither its edges nor its digest's: a config key's own row says whether a map lists it (listed?), so
  a key added, or taken out of the map, moves the tests naming that key and no other."
  {:malli/schema [:=> [:cat :any :string :boolean] :map]}
  [k text listed?]
  (let [raw (sedn/decode text)
        form (less-config-fields raw)
        own (lib/digest (str k "\n" (if (config-key? k) (str (boolean listed?) "\n" text) (if (= raw form) text (sedn/encode form)))))]
    {:id (str "schema " k) :own own :ns k :defines #{}
     :refs (into #{} (filter qualified-symbol?) (tree-seq coll? seq form))
     :schema (mapv #(str "schema " %) (names (less-targets form)))}))

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
        live (live-ids db)
        {config :keys listed :listed} (config-facts schemas)]
    (reach-step {:rows {} :defs {} :methods {} :impls {} :contract {} :tests #{} :refd {} :adj {} :rev {}
                 :reads #{} :scans #{} :classed #{} :docs #{} :proc #{} :bin #{} :walks #{} :plain {} :own {} :comp-of {} :cd {} :flags {} :next 0 :computed #{}
                 :data-refs data-refs :schemas schemas :schema-config config :listed listed :live live}
                db []
                (into (reach-rows db (set (vals live)) true) (for [[k text] schemas] (schema-row k text (contains? listed k))))
                #{})))

(defn- schema-moves
  "g, brought to db's schema entries (schemas, db's {key text}), with what reach-step takes of the entries that moved:
  [g' removed added touched]. An entry changed or gone is a row removed, one changed or new a row
  added (its dependents are re-folded by the edges they hold to its id); one added or gone changes
  which names are edges, so the forms holding the name are touched, re-edged by reach-step: the entries
  naming it (their :schema), the contracts naming it (:contract holds every name), and the forms naming
  it as a keyword (:form/keywords, one AVET seek a key, their :sedges kept by g' here); and
  the config keys entries name moving (:schema-config) touches the forms reading a key they compute
  (:computed), whose config the fold takes it by."
  {:malli/schema [:=> [:cat :map :seon.host/db :map :map] [:tuple :map [:vector :string] [:vector :map] [:set :string]]]}
  [g db live schemas]
  (let [old (:schemas g)
        sid #(str "schema " %)
        gone (remove schemas (keys old))
        fresh (remove old (keys schemas))
        {config :keys listed :listed} (config-facts schemas)
        was-listed (:listed g)
        ;; an entry whose text moved, or a config key a map began or ceased to list (its own digest says)
        changed (filter #(and (contains? old %) (or (not= (old %) (schemas %)) (not= (contains? listed %) (contains? was-listed %)))) (keys schemas))
        exist (into #{} (map sid) (concat gone fresh))
        rows (:rows g)
        ;; the forms naming an entry that came or went as a keyword: live in both values, no change of theirs
        namers (for [[k now] (concat (map vector gone (repeat false)) (map vector fresh (repeat true)))
                     dt (d/datoms db :avet :form/keywords k)
                     :let [id (:v (first (d/datoms db :eavt (:e dt) :form/id)))]
                     :when (and (= (:e dt) (live id)) (contains? (:live g) id) (not ((:refs (rows id)) 'seon.config/baked-schema-forms)))]
                 [id (sid k) now])
        rows (reduce (fn [rs [id k now]] (update-in rs [id :sedges] #(vec (if now (distinct (conj % k)) (remove #{k} %))))) rows namers)
        g' (assoc g :schemas schemas :schema-config config :listed listed :rows rows)]
    [g'
     (mapv sid (concat gone changed))
     (mapv #(schema-row % (schemas %) (contains? listed %)) (concat fresh changed))
     (cond-> (-> (into #{} (comp (keep #(when (some exist (:schema (rows (sid %)))) (sid %)))) (keys schemas))
                 (into (map first) namers)
                 (into (for [[v ks] (:contract g) :when (some exist ks) :let [id (peek (get (:defs g) v))] :when id] id)))
      (not= config (:schema-config g)) (into (:computed g #{})))]))

(defn reach-update
  "Graph g (reach-graph's, of another value) for db: the live forms g holds that db does not
  removed, db's that g does not added (live-ids: index scans, no entity read), and only the
  components those touch and their ancestors re-folded (reach-step), so a branch's graph is
  its base's at the cost of what it changed. The schema entries the two values differ in are
  rows removed and added the same way (schema-moves), with the forms whose edges they change
  touched, so a merge that adds an entry costs its dependents', not the program's. A namespace
  gaining its first test or losing its last has the forms it already held re-edged and re-folded as they
  are added (its forms' quoted names become edges: reach-step's test-nss), not the graph built again; one losing its last
  test rebuilds (its quoted names stop being edges)."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [g db]
  (let [live (live-ids db) was (:live g)
        schemas (schema-texts db)
        [g0 gone-s added-s touched] (if (= (:schemas g) schemas) [g [] [] #{}] (schema-moves g db live schemas))
        new-ids (set (for [[id _] live :when (not (was id))] id))
        g' (assoc (reach-step g0 db (into (vec (remove live (keys was))) gone-s)
                              (into (vec (reach-rows db (set (map live new-ids)) false)) added-s) touched)
                  :live live)
        ;; a namespace that gained its first test: the forms it already held, which the step did not read, are
        ;; re-edged as if added (a test namespace's quoted names are edges), never the graph built again. One that
        ;; lost its last, or lost a form while gaining, is rebuilt: the step takes a removed form's edges out by
        ;; the namespaces it now knows as tests, so those it left under the old are not found
        was-tests (:test-nss g #{}) now-tests (:test-nss g' #{})
        flipped (into #{} (remove was-tests) now-tests)
        redo (when (seq flipped) (vec (for [[id r] (:rows g') :when (and (flipped (:ns r)) (not (new-ids id)))] id)))]
    (cond (or (some (complement now-tests) was-tests) (some #(flipped (:ns (get (:rows g) %))) (remove live (keys was))))
          (reach-graph db (:data-refs g))
          (seq redo) (assoc (reach-step g' db redo (reach-rows db (set (map live redo)) false) #{}) :live live)
          :else g')))

(def root-entries
  "Where the binary's own code starts, the roots of root-reach: its one entry (its modes, root's boot and procs among
  them: system.edn's procs are edges of seon.graph/read-decl, the reach graph's :data-refs). See
  docs/source.md#the-binary-records-its-program."
  '[seon.main/-main])

(def agent-body
  "Where a forked child stops running the binary's code and becomes an agent, which loads main's program from the db: the
  body the fork server's :fork hands it, and the agent's process entries (:seon.config.source/process-entry's agent side),
  which the reach graph links from every form that starts a process (seon.lib/fork!'s callers, the fork server's among
  them). root-reach enters none of them. Sound because an agent runs main's code, not its binary's: the fork server loads
  main's program over the binary's image at its start and at every commit (seon.program/load-all!, load-changes!), so every
  agent it forks inherits main's forms, and a resumed agent loads its branch's difference (seon.program/load-diff!). See
  docs/source.md#the-binary-records-its-program."
  '[seon.repl/run! seon.repl/serve! seon.turn/work!])

(defn root-roots
  "Every name root's binary runs code from, as the data that names it says: root-entries, system.edn's procs and effects
  (g's :data-refs, seon.fx/declared-refs), the renders schema entries name (a schema row's :refs: :seon.render/hiccup,
  :model and their kin) and the qualified symbols the config in force holds (cfg), less any in a test namespace (a
  scripted model's fixture is the agent's, loaded from main). requiring-resolve's run-time names are these: seon.graph's
  procs, seon.render's renders, seon.model's script; seon.cost's are a cost proof's own (a test's), seon.main's
  seon.check/-main a test driver. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :map :map] [:vector :qualified-symbol]]}
  [{:keys [data-refs rows test-nss]} cfg]
  (vec (distinct (for [s (concat root-entries (mapcat val data-refs) (mapcat :refs (filter :schema (vals rows)))
                                 (for [v (vals cfg) x (if (coll? v) v [v]) :when (qualified-symbol? x)] x))
                       :when (and (qualified-symbol? s) (not (contains? test-nss (symbol (namespace s))))) ] s))))

(defn root-forms
  "The forms of reach graph g that root's process runs from its binary: the closure of root-roots (cfg the config in
  force) over g's edges, never entering agent-body. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :map :map] [:set :string]]}
  [{:keys [defs adj] :as g} cfg]
  (let [cut (set (mapcat #(get defs %) agent-body))
        start (vec (remove cut (mapcat #(get defs %) (root-roots g cfg))))]
    (into (set start) (closure start #(remove cut (get adj %))))))

(defn root-reach
  "The vars of reach graph g that root's process runs from its binary (root-forms' definitions). A change to one of them
  is judged only by a binary built with it (seon.system/binary-current), and by every test that starts a root (root-digest).
  See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :map :map] [:set :qualified-symbol]]}
  [{:keys [rows] :as g} cfg]
  (into #{} (for [f (root-forms g cfg) [k s] (:defines (rows f)) :when (= :var k)] s)))

(defn root-digest
  "The digest of what root runs: the Merkle digests (:cd) of the components of root-roots' forms in g. A component's
  digest is of everything it reaches, so this moves when any form root runs changes, or its reach does (and with the
  agent's side, which every test that starts a root reaches already through the process entries). Every test that starts
  a root or runs the binary takes it (index-answer): the owner's ruling reversing \"a root's boot is no entry\" (a
  change to root's writer reused 226 of 233 process tests' greens; docs/decisions.md). Cost: one lookup a root, the
  roots read once per schema and config value (roots, kept by the caller). See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map [:sequential :qualified-symbol]] :string]}
  [{:keys [defs comp-of cd]} roots]
  (lib/digest (str/join " " (sort (distinct (keep #(some-> (comp-of %) cd) (mapcat #(get defs %) roots)))))))

(defn- roots-of
  "root-roots of g under the config a process test's root runs with (seon.config/test-system-config, the scripted profile, whichever
  process plans), kept on g's index while its schemas, data-refs and that config are the same values
  (root-roots reads every schema row and every config value: the index would pay them at each update). Answers
  [roots key]. See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map [:maybe :map]] [:tuple [:sequential :qualified-symbol] [:vector :any]]]}
  [g index]
  (let [k [(:schemas g) (:data-refs g) cfg/test-system-config]]
    (if (and (= (count k) (count (:roots-key index))) (every? true? (map identical? k (:roots-key index))))
      [(:roots index) (:roots-key index)]
      [(root-roots g cfg/test-system-config) k])))

(defn- index-answer
  "The function of a key and its starting forms' ids that answers {:digest :process? :binary?} (graph-index)
  over graph g under inputs: the config lines and env keys it shares are made once here, for every key asked."
  {:malli/schema [:=> [:cat :map :seon.program/inputs :boolean] :seon.host/fn]}
  [{:keys [rows plain comp-of cd flags] :as g} {:keys [env tree code config test-system-config]} roots?]
  (let [line (into {} (for [[ck v] config] [ck (str "\n" ck " " v)]))
        ;; a test that runs a process takes the config its root runs with (:test-system-config, seon.config/test-system-config as the
        ;; planner renders it), never the planning process's: the standing system's local.edn moved 103 of 180 process digests
        rline (if (seq test-system-config) (merge line (into {} (for [[ck v] test-system-config] [ck (str "\n" ck " " v)]))) line)
        ;; what root runs, taken by every test that starts a root or runs the binary (root-digest): made once, when one asks;
        ;; a test running the binary takes its check driver's too (a mode it names runs seon.check's code, which no reach of
        ;; the test names: seon.lib-test's fork cases kept one flaky digest across a fix to seon.check/fork-cases)
        root (delay (root-digest g (first (roots-of g (:index g)))))
        binary (delay (root-digest g (conj (first (roots-of g (:index g))) 'seon.check/-main)))
        ;; what decides every test's outcome (the graph's :runners, marked ^:seon.test/runner): the one derived-reach digest
        ;; (root-digest) over them, taken by every test, so a runner change moves every digest (the owner's ruling: false reds
        ;; are rerun by a moved digest, never deleted). Made once, when a test asks
        runner (delay (root-digest g (sort (:runners g #{}))))
        ;; :seon.env/src is a direct reader's (kept-program-store): a process test runs a program it names
        ;; a process reads the classes it runs on (bin/, system.edn, web/, deps, the schemas), not the wiki or the task files,
        ;; which a test reaches only through its own reader (test-class-readers): as classes they would move every process
        ;; test at each wiki edit (242 of 1,712), which no digest of theirs held before they were classes
        env-keys (filterv #(and (= "seon.env" (namespace %)) (not (#{:seon.env/src :seon.env/docs :seon.env/tasks} %))) (keys config))]
    (fn [k ids]
      (let [cs (distinct (map comp-of (into (vec ids) (mapcat #(get plain (:ns (rows %)))) ids)))
            ;; the config keys its reach reads (config-reads) that the config holds
            proc? (some #(let [f (flags %)] (or (:proc f) (:bin f))) cs)
            line (if proc? rline line)
            ks (into #{} (comp (mapcat #(:config (flags %))) (filter line)) cs)
            ;; a process (a root, an agent, the binary) can read any class of files (:seon.env/*): it runs
            ;; bin/ scripts and loads system.edn, web/ and the schema resources. A form reading files takes
            ;; only the classes whose readers it reaches (env-class-readers): taking every class for a read
            ;; of a port file or a wiki anchor made a bin/ edit rerun 882 of 1,566 tests (2026-11-08).
            ;; A pin it takes by its reach, as any test does: Jolt's runtime core and Chez, which every
            ;; process runs on, are the forks' suites' and the daily full run's
            ks (if (some #(let [f (flags %)] (or (:proc f) (:bin f))) cs) (into ks env-keys) ks)
            ;; a step's src is :code; the src class is test/'s too, which a step does not run (every step reaches seon.contract/uncontracted)
            src? (and roots? (some #(contains? (:config (flags %)) :seon.env/src) cs))
            ks (if roots? (disj ks :seon.env/src) ks)]
        {:digest (lib/digest (apply str k "\n" env "\n" (when (some #(:scans (flags %)) cs) tree)
                                      (when (and (not roots?) (some #(let [f (flags %)] (or (:proc f) (:bin f))) cs)) (str "\nroot " @root))
                                      (when (and (not roots?) (some #(:bin (flags %)) cs)) (str "\nbinary " @binary))
                                      (when (and roots? (or src? (some #(:reads (flags %)) cs))) code)
                                      (when-not roots? (str "\nrunner " @runner))
                                      (concat (sort (map cd cs)) (map line (sort ks)))))
         :process? (boolean (some #(:proc (flags %)) cs)) :binary? (boolean (some #(:bin (flags %)) cs))
         ;; what this digest takes besides code, as read keys (seon.proofs/reads-key): until a run records its config, schema
         ;; and file reads, its verdict's :reads holds these, so a reuse by reads is never wider than this digest's
         :reads (cond-> (into #{[:env] [:runner]} (map #(vector :config %)) ks)
                  (some #(:scans (flags %)) cs) (conj [:tree])
                  (and (not roots?) (some #(let [f (flags %)] (or (:proc f) (:bin f))) cs)) (conj [:root])
                  (and (not roots?) (some #(:bin (flags %)) cs)) (conj [:binary]))
         ;; a test namespace's own call of a branch reader (test-class-of): an agent mixes its branch's point into this
         ;; digest (seon.agent/test-plan), as the inputs hold no branch
         :data? (boolean (some #(contains? (:config (flags %)) :seon.env/data) cs))}))))

(defn graph-index
  "{test {:digest :process? :binary?}} of graph g's tests under inputs (or of each key of
  roots, {key [var ...]}): a key's digest is of its name, :env, :code when it is a root (a
  check step, which runs the whole program whatever its reach names) and what it reaches reads the tree, :tree when it reaches a test namespace's form that
  calls a reader of the checkout's files (file-readers, :scans: what it judges is the files themselves); any
  other test never takes the tree: it is digested by what it reaches, a file it reads or a binary
  it runs not in its reach is the full run's to catch, bin/test --all, docs/merging.md), its
  starting forms' components' digests (its forms and their
  namespaces' plain forms) and the values in inputs' :config of the config keys those
  components read (config-reads); :process? and :binary?, whether one of those components uses a
  process or a binary var. A root naming a var with no form in g gets none."
  {:malli/schema [:=> [:cat :map :seon.program/inputs [:maybe [:map-of :string [:sequential :qualified-symbol]]]] :map]}
  [{:keys [defs tests] :as g} inputs roots]
  (let [var-form #(peek (get defs %))
        answer (index-answer g inputs (boolean roots))]
    (if roots
      ;; a root naming a var with no form here (renamed, not imported) gets no digest: its
      ;; caller names it missing, never a digest of :env alone that a stale green matches
      (into {} (for [[k vars] roots :when (every? var-form vars)] [k (answer k (map var-form vars))]))
      (into {} (for [t tests :let [id (var-form t)] :when id] [t (answer t [id])])))))

(defn reach-indexed
  "Graph g holding the tests' index under inputs (graph-index's, with no roots): the answers it
  held still (reach-step drops those a change can move and names them :stale, so a graph updated
  from one holding an index answers only for what moved, never for the program), the rest
  computed, all of them when it holds none or holds one under other inputs; and, when what root runs moved (root-digest),
  the answers of the tests that took it (:rooted). The index is
  derived data, a function of the graph and inputs: regenerated whenever it is not held
  (docs/data-modeling.md#derived-data-is-a-cache-never-relied-on). Read it with index-of. See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map :seon.program/inputs] :map]}
  [{:keys [defs tests] :as g} inputs]
  (let [index (:index g)
        fresh? (or (identical? inputs (:in index)) (= inputs (:in index)))
        ;; the tests whose answer took what root runs (root-digest): a change to it moves them all, whatever they reach.
        ;; The digest is asked only when one is held, and kept while the graph's component digests are the same value
        rooted (if fresh? (:rooted index #{}) #{})
        root-now (delay (let [[roots k] (roots-of g index)]
                          (if (identical? (:cd g) (:root-cd index))
                            (select-keys index [:root :binary :roots :roots-key])
                            {:root (root-digest g roots) :binary (root-digest g (conj roots 'seon.check/-main)) :roots roots :roots-key k})))
        moved? (and (seq rooted) (not= (select-keys @root-now [:root :binary]) (select-keys index [:root :binary])))
        keep-root (fn [ix] (if (realized? root-now) (merge ix @root-now {:root-cd (:cd g)}) ix))]
    (if (and fresh? (empty? (:stale g)) (not moved?))
      (if (realized? root-now) (update g :index keep-root) g)
      (let [var-form #(peek (get defs %))
            answer (index-answer g inputs false)
            new (for [t (if fresh? (into (set (:stale g)) (when moved? rooted)) tests) :let [id (var-form t)] :when id] [t (answer t [id])])
            answers (into (if fresh? (:answers index) {}) new)
            rooted (reduce (fn [r [t a]] (if (or (:process? a) (:binary? a)) (conj r t) (disj r t))) rooted new)]
        (when (seq rooted) @root-now)
        (assoc g :index (keep-root {:in inputs :answers answers :rooted rooted}) :stale #{}
               :tests-by-ns (or (:tests-by-ns g) (group-by (comp symbol namespace) tests)))))))

;; ---- recorded reads: a test keyed by the vars its run called (docs/source.md#tests-run-by-reach) ----

(defn- called-closure
  "The rows a call of var v depends on in reach graph g: v's forms, its methods and impls, the schema entries they and its
  contract name, and, followed, every var they reference that no contract counts (a data def, a macro, a test's helper):
  a contracted var they reference is in a run's call record only if it ran, so it is no dependency of a call that did not
  call it. With each row's namespace's plain forms (its ns form, a top-level form defining nothing); a host schema
  (seon.contract/host-schemas) is that var's form."
  {:malli/schema [:=> [:cat :map :qualified-symbol] [:set :string]]}
  [{:keys [rows defs methods impls contract plain]} v]
  (loop [todo (vec (concat (get defs v) (get methods v) (get impls v) (get contract v))) seen #{}]
    (if-let [id (peek todo)]
      (if (seen id)
        (recur (pop todo) seen)
        (let [r (rows id)
              next-ids (if r
                         (concat (:sedges r) (get plain (:ns r))
                                 (mapcat #(concat (get defs %) (get methods %) (get impls %)) (remove contract (:refs r))))
                         ;; a schema the program's code defines (seon.contract/host-schemas: :seon.host/db and its kind) has no entry
                         (get defs 'seon.contract/host-schemas))]
          (recur (into (pop todo) (remove seen) next-ids) (conj seen id))))
      seen)))

(defn var-digests
  "{var digest} over reach graph g for each var of vars: the digest of what a call of it depends on (called-closure's rows'
  own digests), so a test keyed by the vars its run called (its verdict's :reads) moves exactly when one of them, or what
  they use without calling, changed. Cost: one walk a var, stopping at contracted vars. See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map [:sequential :qualified-symbol]] [:map-of :qualified-symbol :string]]}
  [{:keys [own] :as g} vars]
  (into {} (for [v vars :let [ids (called-closure g v)] :when (seq ids)]
             [v (lib/digest (str/join " " (sort (keep own ids))))])))

(defn reads-index
  "The digest of every input a test's run can read, keyed as seon.proofs/reads-key keys its record: each var by var-digests,
  [:schema k] each schema entry's own digest, [:config k] each config value inputs' :config holds, [:file path] each
  file's git blob id (blobs, {path blob}: seon.fx/blob-ids of the tree). One map a program and tree, read by every test's
  reuse check. See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map :seon.program/inputs [:map-of :string :string]] :map]}
  [{:keys [defs schemas own] :as g} inputs blobs]
  (merge (var-digests g (vec (keys defs)))
         ;; the whole tree, and what the system and the binary run (root-digest), for a test whose digest takes them
         (let [roots (first (roots-of g (:index g)))]
           {[:env] (:env inputs) [:tree] (:tree inputs) [:root] (root-digest g roots) [:binary] (root-digest g (conj roots 'seon.check/-main))
            [:runner] (root-digest g (sort (:runners g #{})))})
         (into {} (for [k (keys schemas) :let [d (get own (str "schema " k))] :when d] [[:schema k] d]))
         (into {} (for [[k v] (:config inputs)] [[:config k] v]))
         (into {} (for [[p b] blobs] [[:file p] b]))
         ;; the kinds it holds whole: a read of a file it has no blobs for is never a miss (seon.proofs/read-digests)
         {:seon.proofs/covers (cond-> #{:var :schema :config} (seq blobs) (conj :file))}))

(defn index-of
  "{test {:digest :process? :binary?}} the graph g holds (reach-indexed): graph-index's of its tests, or {} when it holds none. See docs/source.md#tests-run-by-reach."
  {:malli/schema [:=> [:cat :map] :map]}
  [g] (get-in g [:index :answers] {}))

(defn program-digest
  "The digest of db's program as a whole: its live forms (:form/id, hasch of a form's namespace
  and text, docs/storage.md#ids: a replaced form is retracted), its schema entries (:schema/form),
  its files' texts (:file/text), its config entries (:config/form) and its namespaces' tails, each by its entity, a content id of its identity (docs/storage.md#ids),
  so the index's order is the same on every branch and nothing is sorted. It moves when a form, schema entry or
  ns tail is added, replaced or removed, and not on a write of data. What a main run of the test
  service reads of main's program (seon.system/prove!): per-test digests (graph-index) need the
  checkout's tests loaded, which is the run's own work. Cost: one read of three attributes'
  datoms (measured on the standing root's main, 2699 forms, 660 schema entries, 129
  namespaces: 0.79M ticks, 5.0 MB, of which the index read is 0.52M ticks, 3.9 MB)."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db]
  (let [b (StringBuilder.)]
    (doseq [dt (d/datoms db :aevt :form/id)] (.append b (:e dt)) (.append b "\n"))
    (doseq [a [:schema/form :ns/tail :file/text :config/form] dt (d/datoms db :aevt a)] (.append b (:e dt)) (.append b " ") (.append b (:v dt)) (.append b "\n"))
    (lib/digest (.toString b))))


(defn run-digest
  "The digest of what a test run of db reads (seon.prove/run-key): its program (program-digest) and
  every task's proofs and needs, which the run selects its proofs from (seon.task/accumulate), so
  registering a proof is a new run, never answered from the run before it as \"green: ran 0\" (docs/merging.md#the-test-service). Cost: program-digest's, and one read of the two
  attributes' datoms."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db]
  (let [b (StringBuilder. ^String (program-digest db))]
    (doseq [a [:seon.task/proofs :seon.task/needs] dt (d/datoms db :aevt a)] (.append b "\n") (.append b (:e dt)) (.append b " ") (.append b (:v dt)))
    (lib/digest (.toString b))))
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
  "The keys of index (graph-index's answer) that reach a process of their own (an agent, a
  fork, a root booted for it, the binary; a shell call is none): never run automatically, only when named (DECISIONS,
  Process tests are not automatic). Taking the index, not db, so a caller holding one walks once."
  {:malli/schema [:=> [:cat [:map-of :any [:map [:process? :boolean]]]] [:set :any]]}
  [index] (into #{} (keep (fn [[t {:keys [process?]}]] (when process? t))) index))

(def index-roots
  "The vars an index of a tree reaches: what makes a program's facts from its files (import-tree!, down through the
  analysis and the account) and what digests them. See docs/source.md#the-binary-records-its-program."
  '[seon.system/import-tree! seon.program/program-digest])


(defn index-code
  "The code that indexes a tree, as db's program states it, under inputs: the reach digest of roots
  (seon.program/graph-index over db's seon.program/reach-graph, as a check step's root is digested), its components and
  the runtime pins and config keys they read, never the tree or the program's source (blank in inputs: the files are the
  index's other input). nil when db defines none of roots. What a kept index was made by, so one whose code moved is
  made again. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat [:or :seon.host/db :map] :seon.program/inputs [:vector :qualified-symbol]] [:maybe :string]]}
  [db-or-graph inputs roots]
  (let [g (if (:rows db-or-graph) db-or-graph (reach-graph db-or-graph nil))]
    (get-in (graph-index g (assoc inputs :tree "" :code "") {"index" roots}) ["index" :digest])))

(defn with-index-code
  "inputs with the code that indexes the process store, the seeded store a process test's root takes (index-code of
  index-roots over db or its reach graph), as :seon.env/index in its :config: every process test's digest takes it, so a
  verdict of a run on a store indexed by other code is no verdict of this one. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat [:or :seon.host/db :map] :seon.program/inputs] :seon.program/inputs]}
  [db-or-graph inputs]
  (assoc-in inputs [:config :seon.env/index] (or (index-code db-or-graph inputs index-roots) "none")))
