(ns seon.contract
  "From proto/core 9f5100a proto.contract (session G1). The rule: every platform function carries a Malli function schema over named,
  registered schemas, and every call is checked on entry (inputs, arity) and on exit
  (output) by malli's own `m/-instrument`, as Seon's `seon.instrument/compiled-wrapper`
  does. An error throws an ex-info whose data is the one error map
  (`:seon.error/type :seon.instrument/contract-error`), caught only at an entrant
  boundary (the agent's eval, a Flow proc, the API caller).

  - The registry is data: resources/seon/schemas/*.edn (named schemas, at their
    producer's namespace), over a predicate pack for host objects declared here.
    Every named schema is compiled once, when this namespace loads, so a contract form
    resolves names to compiled schemas and each validator is built once per schema.
  - `instrument!` arms every contracted var of the given namespaces: in root before the
    static collect and the fork server's qfork, so every forked agent inherits the
    wrappers and their compiled validators copy-on-write.
  - `arm!` is the same for one var: an agent's `defn` with `:malli/schema` metadata is
    armed when it is defined, and main's program when it loads.
  - `:seon.config.contract/instrument :off` (the config, read once at load) installs no
    wrapper and no recording transaction: the same binary measured with instrumentation off;
    `:contracts` arms the wrappers without the recording transaction."
  (:refer-clojure :exclude [defn defn-])
  (:require [malli.core :as m] [malli.registry :as mr]
            [clojure.string :as str] [seon.profile :as profile] [seon.print :as print]
            [seon.config :as config]))

(def on?
  "Are the contracts on in this process (:seon.config.contract/instrument)? :off: no
  wrapper and no recording; :contracts: wrappers, no recording transaction (for measuring
  the two apart); :on: both."
  (not= :off (config/value :seon.config.contract/instrument)))

(def record?
  "Does each eval request write its recording transaction (:seon.config.contract/instrument :on)?"
  (= :on (config/value :seon.config.contract/instrument)))

;; ---- the predicate pack: host objects with no data form ------------------------------
;; Named predicate schemas (malli simple schemas), so a contract names them like any
;; other schema and nothing is evaluated from text (no `[:fn ...]`, no SCI).

(clojure.core/defn plain?
  "Whether v is plain data, what the store keeps and EDN carries (docs/storage.md#plain-data): nil, a boolean, a number,
  character, string, keyword, symbol, uuid or instant, or a map, vector, set or list of them. Not a record; not a sorted
  collection, whose comparator is a function; not bytes, which EDN does not carry; not a seq that is no list (lazy, a
  range), which telling would realize. A narrower test than Datahike's on a data value, which walks any seq. visit, called on
  each node before it is looked at, ends the walk by answering false: `kept`'s budget."
  {:malli/schema [:function [:=> [:cat :any] :boolean] [:=> [:cat :any [:maybe :seon.host/fn]] :boolean]]}
  ([v] (plain? v nil))
  ([v visit]
   ((fn ok [x]
      (and (or (nil? visit) (visit x))
           (cond (or (nil? x) (boolean? x) (number? x) (char? x) (string? x) (keyword? x) (symbol? x) (uuid? x) (inst? x)) true
                 (or (clojure.core/record? x) (sorted? x)) false
                 (map? x) (every? (fn [[k y]] (and (ok k) (ok y))) x)
                 (or (vector? x) (set? x) (list? x)) (every? ok x)
                 :else false)))
    v)))

(clojure.core/defn plainly
  "v as the store keeps it (`plain?`): plain data as it is, a map's keys and values each so, whatever its size, anything
  else (a library's record, an object in an exception's data, a lazy seq, which printing it whole could never end) as
  the one printer's text of it under the part budget, which says what it left; two keys that print alike are then one.
  The one place a value is made plain: every error is, at its constructor (`error`). See docs/storage.md#plain-data."
  {:malli/schema [:=> [:cat :any] :any]}
  [v]
  (cond (plain? v) v
        (and (map? v) (not (clojure.core/record? v)) (not (sorted? v))) (into {} (map (fn [[k x]] [(plainly k) (plainly x)])) v)
        :else (print/text v (:part print/budgets))))

(clojure.core/defn- pred {:malli/schema [:=> [:cat :qualified-keyword :seon.host/fn] [:tuple :qualified-keyword :seon.host/schema]]} [k p] [k (m/-simple-schema {:type k :pred p})])

(def ^:private host-schemas
  (into {} [(pred :seon.host/atom #(instance? clojure.lang.Atom %))
            (pred :seon.host/promise #(instance? clojure.lang.IBlockingDeref %))
            (pred :seon.host/deref #(instance? clojure.lang.IDeref %))
            (pred :seon.host/chan #(instance? clojure.core.async.impl.channels.ManyToManyChannel %))
            (pred :seon.host/bytes bytes?)
            (pred :seon.host/throwable #(instance? Throwable %))
            (pred :seon.host/fn fn?)
            (pred :seon.db/plain plain?)
            (pred :seon.host/var var?)
            (pred :seon.host/regex #(instance? java.util.regex.Pattern %))
            (pred :seon.host/namespace #(instance? clojure.lang.Namespace %))
            ;; a db value, or one as of a t (d/as-of: a turn's records read as they were sent)
            (pred :seon.host/db #(or (instance? datahike.db.DB %) (instance? datahike.db.AsOfDB %)))
            (pred :seon.host/conn #(instance? datahike.connector.Connection %))
            (pred :seon.host/datom #(instance? datahike.datom.Datom %))
            (pred :seon.host/thread #(instance? Thread %))
            (pred :seon.host/queue #(instance? java.util.concurrent.LinkedBlockingQueue %))
            (pred :seon.host/persistent-queue #(instance? clojure.lang.PersistentQueue %))
            (pred :seon.host/writer #(instance? java.io.Writer %))
            (pred :seon.host/object some?)
            (pred :seon.host/volatile volatile?)
            (pred :seon.host/flow-graph some?)
            (pred :seon.host/schema m/schema?)
            (pred :seon.host/registry #(satisfies? mr/Registry %))
            ;; what a C function declared :void answers (Chez's void object, not nil)
            (pred :seon.host/void (constantly true))
            ;; a character, as malli's type keys name a value's type (:int, :string): malli names it only as the
            ;; predicate char?, and agents write :char
            [:char (m/-simple-schema {:type :char :pred char?})]]))

;; ---- the registry ------------------------------------------------------------------

(clojure.core/defn registry-of
  "A registry whose every named form is compiled once (names resolve to the compiled
  schema of their own name, compiled on first reference). A form that does not compile
  (malli's :malli.core/invalid-schema, an unresolvable name among them) throws malli's
  ex-info."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :any]] :seon.host/registry]}
  [forms]
  (let [compiled (volatile! {})
        reg (volatile! nil)
        lazy (reify mr/Registry
               (-schema [_ k]
                 (or (get @compiled k)
                     (when-let [form (get forms k)]
                       (let [s (m/schema form {:registry @reg})]
                         (vswap! compiled assoc k s)
                         s))))
               (-schemas [_] @compiled))]
    (vreset! reg (mr/composite-registry (m/default-schemas) host-schemas lazy))
    (doseq [k (keys forms)] (mr/-schema lazy k))
    (mr/composite-registry (m/default-schemas) host-schemas (mr/simple-registry @compiled))))

(def forms "Every named schema form of the platform (seon.config/schema-forms, baked once)." config/schema-forms)

(clojure.core/defn properties
  "The properties map of named schema k's form ([:type {props} ...]), or nil: what a
  schema declares of its values (a status set, a render, a unit), read here only. A registry
  bound as malli's (an agent's REPL binds its branch's population: seon.repl/with-branch-schemas)
  answers first, so a render a branch's schema names is the one its values take."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [k] (if-let [s (some-> (mr/registry mr/*registry*) (mr/schema k))]
        (m/properties s)
        (let [form (get forms k)] (when (and (vector? form) (map? (second form))) (second form)))))

(clojure.core/defn enum-values
  "The members of named [:enum {props} ...] schema k, in their declared order."
  {:malli/schema [:=> [:cat :qualified-keyword] [:vector :any]]}
  [k] (let [form (get forms k)] (vec (drop (if (map? (second form)) 2 1) form))))
(def registry (registry-of forms))

(def options
  "What every contract compiles against: the built registry, under malli's dynamic registry,
  so an evaluation that binds malli.registry/*registry* (an agent's REPL, to its branch's
  schemas: seon.program/schema-registry) compiles against that population instead; and the
  predicate pack's types, as total (their validators never throw)."
  {:registry (mr/composite-registry (mr/dynamic-registry) registry)
   ;; the predicate pack's tests are type checks that answer for any value, so an arm built
   ;; of them is checked without a try per call (malli's -total?)
   ::m/total-types (set (keys host-schemas))})

(clojure.core/defn schema
  "The compiled schema of a form or a registered name, in the platform registry."
  {:malli/schema [:=> [:cat :any] :seon.host/schema]}
  [?schema] (m/schema ?schema options))

;; ---- invalid: malli's report -> the error map, thrown ---------------------------------

(clojure.core/defn problems
  "Why v does not satisfy schema s, as an error's problems (:seon.error/problems): each refused
  part's path and its value as it prints."
  {:malli/schema [:=> [:cat :seon.host/schema :any] [:vector :seon.error/problem]]} [s v]
  (vec (for [{:keys [path in schema value]} (:errors (m/explain s v))]
         {:path path :in in :expected (m/form schema)
          ;; the value as it prints, marked code (print/code): it reads as written, a symbol or
          ;; keyword as itself, never a string literal of its text (live runs, 2026-10-26:
          ;; `:value "seon.print.select"` for a symbol, and the agent sent the same call again)
          :value (print/code (print/text value (:part print/budgets)))})))

(clojure.core/defn- first-problem
  "The first reason v does not satisfy s, in one line for an error's message: where (its :in path),
  what it got, and what was expected (its schema's form), each printed at the printer's :part budget, malli's kind for a
  missing or refused key; then the map or vector that holds it (the value at its path's parent: what a
  REPL would print, which the failed check keeps from its caller); and how many reasons more (\"its value does not satisfy its contract\"
  alone sends an agent digging through ex-data for the one nil key). See docs/errors.md#errors."
  {:malli/schema [:=> [:cat :seon.host/schema :any] :string]}
  [s v]
  (let [[{:keys [in schema value type]} :as es] (:errors (m/explain s v))]
    (if-not schema
      ""
      ;; the value and the form it was held to, each by the one printer at a shown-inside budget (its cut says what it left)
      (let [expected (print/text (m/form schema) (:part print/budgets))]
        (str " at " (pr-str in) ": "
             (case type
               :malli.core/missing-key "a key it must hold is missing"
               :malli.core/extra-key (str "a key its closed map does not admit (it admits "
                                          (str/join ", " (map first (m/entries schema))) ")")
               (str (print/text value (:part print/budgets)) ", expected " expected))
             ;; the value holding it, as the caller would have seen it had the check let it through
             (let [holder (if (seq in) (get-in v (vec (butlast in))) v)]
               (when (and (coll? holder) (not (identical? holder value)))
                 (str ", in " (print/text holder (:part print/budgets)))))
             (when (next es) (str " (and " (dec (count es)) " more in :seon.error/problems)")))))))

(clojure.core/defn- calls
  "The calls fn-sym's var declares (its :arglists), each as a form to copy, joined by \" or \":
  \"(seon.doc/namespace-source db sym)\"; nil when it declares none. What an arity error names.
  See docs/checks.md#contracts-armed-at-definition."
  {:malli/schema [:=> [:cat :qualified-symbol] [:maybe :string]]}
  [fn-sym]
  (some->> (some-> (find-var fn-sym) meta :arglists seq)
           (map #(str "(" (str/join " " (cons fn-sym (map str %))) ")"))
           (str/join " or ")))

(clojure.core/defn- invalid
  "Malli's report for fn-sym, as the thrown error map."
  {:malli/schema [:=> [:cat :qualified-symbol :qualified-keyword :map] :any]}
  [fn-sym kind data]
  (let [arm (case kind :malli.core/invalid-input :input :malli.core/invalid-output :output
                  :malli.core/invalid-arity :arity :malli.core/invalid-guard :guard :schema)
        e (cond-> {:seon.error/type (if (= arm :arity) :seon.instrument/arity-error :seon.instrument/contract-error)
                   :seon.error/operation fn-sym
                   :seon.error/member kind
                   :seon.error/message (str fn-sym " " (name kind)
                                            (case arm :input (str ": its arguments do not satisfy its contract" (first-problem (:input data) (:args data)))
                                                  :output (str ": its value does not satisfy its contract" (first-problem (:output data) (:value data)))
                                                  :arity (str ": called with " (:arity data) " arguments" (some->> (calls fn-sym) (str "; it is called as ")))
                                                  ""))
                   :seon.error/fix (case arm
                                     :output (str "Fix " fn-sym " (the producer), or its contract.")
                                     (str "Call " fn-sym " with arguments its contract declares."))}
            (= arm :input) (assoc :seon.error/problems (problems (:input data) (:args data)))
            (= arm :output) (assoc :seon.error/problems (problems (:output data) (:value data)))
            (= arm :arity) (assoc :seon.error/expected (m/form (:schema data)))
            (= arm :schema) (assoc :seon.error/message (str fn-sym ": its contract could not be checked: "
                                                            (ex-message (:exception data)))))]
    (throw (ex-info (:seon.error/message e) e))))

(clojure.core/defn wrap
  "f instrumented by the compiled function schema s, errors named fn-sym, and counted
  under fn-sym (seon.profile: every call counted, one in 64 timed, the check included),
  except seon.profile's own functions."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.host/schema :seon.host/fn] :seon.host/fn]}
  [fn-sym s f]
  (let [checked (m/-instrument {:schema (m/deref s) :scope #{:input :output} :report (fn [kind data] (invalid fn-sym kind data))} f options)]
    ;; the meter is not metered: reading a count must not add one
    (if (= "seon.profile" (namespace fn-sym)) checked (profile/counted fn-sym checked))))

;; ---- contracts at definition --------------------------------------------------------
;; A release `jolt build` direct-links and inlines a namespace's defs, so a wrapper
;; installed later with alter-var-root misses every caller compiled against the def. So
;; a contracted definition holds its checked function from the moment it is defined:
;; every caller, direct-linked or not, calls the checked function. Platform namespaces
;; use this namespace's defn, defn- and defchecked in place of clojure.core's.

(clojure.core/defn checked
  "f checked by contract form c (compiled once, here: seon.contract/wrap), errors
  named fn-sym; f itself when instrumentation is off. Not inlined (:redef): every
  contracted definition calls it once, at its definition, and a spliced copy of its
  body (wrap, schema, seon.profile/counted) at each of the 1,500 definitions was 5 MB of
  code that runs once (docs/machine.md#roots-heap)."
  {:malli/schema [:=> [:cat :qualified-symbol :any :seon.host/fn] :seon.host/fn] :redef true}
  [fn-sym c f]
  (if on? (wrap fn-sym (schema c) f) f))

(clojure.core/defn- contracted-def
  "The forms of a def whose value is checked from its definition on."
  {:malli/schema [:=> [:cat :symbol :any :any] :any]}
  [name c init]
  (let [q (symbol (str *ns*) (str name))]
    `(do (def ~(vary-meta name assoc :malli/schema (list 'quote c)) (checked '~q '~c ~init))
         (when on? (alter-meta! (var ~name) assoc ::wrapper @(var ~name) ::checked-at :definition))
         (var ~name))))

(defmacro defn
  "clojure.core/defn, except that a definition with :malli/schema in its attribute map
  holds its checked function from its definition on (checked): direct-linked and
  inlined callers call the checked body. Without a contract it is clojure.core/defn."
  [name & fdecl]
  (let [[doc fdecl] (if (string? (first fdecl)) [(first fdecl) (next fdecl)] [nil fdecl])
        [attrs fdecl] (if (map? (first fdecl)) [(first fdecl) (next fdecl)] [nil fdecl])
        c (:malli/schema attrs)
        sigs (if (vector? (first fdecl)) (list fdecl) fdecl)]
    (if c
      (contracted-def (vary-meta name merge (dissoc attrs :malli/schema)
                                 {:arglists (list 'quote (map first sigs))} (when doc {:doc doc}))
                      c `(fn ~name ~@fdecl))
      `(clojure.core/defn ~name ~@(when doc [doc]) ~@(when attrs [attrs]) ~@fdecl))))

(defmacro defn-
  "seon.contract/defn, private."
  [name & fdecl]
  `(defn ~(vary-meta name assoc :private true) ~@fdecl))

(defmacro defchecked
  "def of a function value (a Scheme procedure, an FFI binding) with contract c, checked
  from its definition on."
  [name c init]
  (contracted-def name c init))

(defn valid?
  "Does v satisfy the registered schema (or form) s?"
  {:malli/schema [:=> [:cat :any :any] :boolean]}
  [s v] ((m/validator (schema s)) v))

;; ---- the error map ---------------------------------------------------------------------
;; One shape (Seon's errors-cut target: :seon.error/base without :at and :layer): a flat
;; map of :seon.error/type (the variant's registered schema key), :seon.error/operation,
;; :seon.error/message, optional fix and problems, and the variant's own members.

(defn kept
  "v as an error may keep it past its moment: itself when it is small plain data (nil, booleans,
  numbers, keywords, symbols, uuids, instants, strings of at most four characters a token of the
  form budget (a captured stderr tail is one), and maps, vectors, sets and lists of those, within the part budget's count of
  nodes). Else a string is the printer's cut of it (`seon.print/prose`: it says what it omitted)
  and any other value is the one printer's print of it under the part budget, marked code
  (print/code): a db value, conn, atom, Throwable or lazy seq is always its print, so what an
  error holds does not depend on the value's size. See docs/errors.md#an-error-keeps-data."
  {:malli/schema [:=> [:cat :any] :any]}
  [v]
  (let [budget (:part print/budgets) room (* 4 (:form print/budgets))]
    (cond
      (or (nil? v) (boolean? v) (number? v) (keyword? v) (symbol? v) (uuid? v) (inst? v)) v
      (string? v) (if (<= (count v) room) v (:text (print/prose v budget)))
      :else (let [left (volatile! budget)]
              (if (plain? v (fn [x] (and (pos? (vswap! left dec)) (or (not (string? x)) (<= (count x) room)))))
                v
                (print/code (print/text v budget)))))))

(defn bounded
  "The error map m with each member kept (`kept`): for root, which keeps other processes' errors
  for its life (seon.root/flow-error); what an asker or an agent gets is the error whole."
  {:malli/schema [:=> [:cat :map] :map]}
  [m] (into {} (map (fn [[k v]] [k (kept v)])) m))

(defn error
  "The error map of variant `type` raised by `operation`: message plus the variant's
  members, plain whatever a member held (`plainly`), so every error can be stored and sent. Checked against the variant's registered schema here, at its constructor. A raiser
  names itself by its var, #'ns/f, the function the error is built in: the compile resolves it, so
  a rename can never leave an error naming a function that is gone (the a-map design read
  seon.render.value/show, renamed away, and searched for it); a symbol is for an operation known
  only at run time (a contracted function's name)."
  {:malli/schema [:function [:=> [:cat :qualified-keyword [:or :seon.host/var :qualified-symbol] :string] :seon.error/base]
                  [:=> [:cat :qualified-keyword [:or :seon.host/var :qualified-symbol] :string [:maybe :map]] :seon.error/base]]}
  ([type operation message] (error type operation message nil))
  ([type operation message members]
   (let [e (plainly (merge members {:seon.error/type type :seon.error/operation (if (var? operation) (symbol operation) operation) :seon.error/message message}))]
     (when-not ((m/validator (schema type)) e)
       (throw (ex-info (str "error construction refused for " type)
                       {:seon.error/type :seon.instrument/contract-error :seon.error/operation (symbol #'seon.contract/error)
                        :seon.error/message (str "the built error does not satisfy " type)
                        :seon.error/member :malli.core/invalid-output})))
     e)))

(defn error?
  "Is v an error map (it carries :seon.error/type)?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v] (and (map? v) (keyword? (:seon.error/type v))))


;; ---- arming ------------------------------------------------------------------------------

(defn contract
  "The declared contract form of a var: its :malli/schema, or nil."
  {:malli/schema [:=> [:cat :seon.host/var] :any]}
  [v] (:malli/schema (meta v)))

(defn armed?
  "Does var v's root hold its own instrumented wrapper?"
  {:malli/schema [:=> [:cat :seon.host/var] :boolean]}
  [v] (identical? @v (::wrapper (meta v))))

(defn- wrap!
  "Var v's root made raw wrapped by its contract c, compiled now (the registry in force): :armed, its
  wrapper and the raw root it wraps kept on v (a changed schema re-arms it, `rearm!`), or the
  :seon.contract/invalid error map, by-var asking, when the contract does not compile. The one arm
  `arm!` and `rearm!` share. See docs/source.md#schemas-at-the-repl."
  {:malli/schema [:=> [:cat :seon.host/var :any :any :seon.host/var] [:or [:enum :armed] :seon.contract/invalid]]}
  [v c raw by]
  (let [s (try (schema c) (catch Throwable e e))]
    (if (instance? Throwable s)
      (error :seon.contract/invalid by (str (symbol v) ": its contract does not compile: " (ex-message s))
             {:seon.contract/var (symbol v)})
      (let [w (wrap (symbol v) s raw)]
        (alter-var-root v (constantly w))
        (alter-meta! v assoc ::wrapper w ::raw raw)
        :armed))))

(defn arm!
  "Instrument var v by its contract: inputs and arity checked on entry, the output on
  exit. Answers :armed, :unchanged (its root already is its wrapper), :off (instrumentation
  off), :uncontracted, or an error map (:seon.contract/invalid: the contract does not
  compile). Re-arming a redefined var wraps its new root."
  {:malli/schema [:=> [:cat :seon.host/var] [:or :seon.contract/arm-result :seon.contract/invalid]]}
  [v]
  (let [c (contract v)]
    (cond
      (nil? c) :uncontracted
      (not on?) :off
      (armed? v) :unchanged
      :else (wrap! v c @v #'seon.contract/arm!))))

(defn rearm!
  "Arm var v again by its contract, compiled now (under the registry in force: a branch's changed
  schemas, seon.program/rearm!), over the root its arm wrapped: what a schema registered since it
  was armed owes it. Answers :armed, :unarmed (no arm to redo), or the :seon.contract/invalid
  error map. See docs/source.md#schemas-at-the-repl."
  {:malli/schema [:=> [:cat :seon.host/var] [:or [:enum :armed :unarmed] :seon.contract/invalid]]}
  [v]
  (let [c (contract v) raw (::raw (meta v))]
    (if (and c on? raw (armed? v))
      (wrap! v c raw #'seon.contract/rearm!)
      :unarmed)))

(defn function-var?
  "Is v a var holding a function that needs a contract: not a macro, a test, a dynamic
  setting, a protocol's method (it dispatches to the types that implement it: :protocol) or a
  type's positional factory (->T, beside its type T)?"
  {:malli/schema [:=> [:cat :seon.host/var] :boolean]}
  [v]
  (let [mt (meta v) n (str (:name mt))]
    (and (bound? v) (fn? @v) (not (:macro mt)) (not (:test mt)) (not (:dynamic mt)) (not (:protocol mt))
         (not (and (str/starts-with? n "->") (class? (ns-resolve (:ns mt) (symbol (subs n 2)))))))))

(defn uncontracted
  "The function vars of namespaces nss that declare no contract, as sorted symbols."
  {:malli/schema [:=> [:cat [:sequential :symbol]] [:vector :qualified-symbol]]}
  [nss]
  (vec (sort-by str (for [n nss v (vals (ns-interns n)) :when (and (function-var? v) (nil? (contract v)))] (symbol v)))))

(def boot-nss
  "The namespaces that carry contracts in metadata and are armed once their callers exist, at a
  process's start: root's boot, and each test shard before its tests (a cost proof's contracted
  calls count these too, so one count in every mode: bin/test, an agent's test child, the
  binary). See docs/cost.md#counting-calls."
  '[seon.edn seon.config seon.contract seon.profile seon.print seon.print.select seon.print.emit])

(defn instrument!
  "Arm every contracted var of namespaces nss (symbols). Answers {:armed n :unchanged n
  :uncontracted [sym] :invalid [error] :ms}."
  {:malli/schema [:=> [:cat [:sequential :symbol]] :seon.contract/report]}
  [nss]
  (let [t0 (profile/now-ns)
        rs (for [n nss v (vals (ns-interns (the-ns n))) :when (contract v)] [v (arm! v)])
        r (reduce (fn [acc [v r]] (if (error? r) (update acc :invalid conj r) (update acc r (fnil inc 0)))) {:invalid []} rs)]
    (assoc r :uncontracted (uncontracted nss) :ms (profile/ms-since t0))))
