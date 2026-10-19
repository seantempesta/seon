(ns seon.sci.runtime
  "A runtime is an immutable name -> definition digest map bound onto one Var
  space; an analysed function is keyed by content and shared by every runtime
  that holds its digest.

  The Var space is an SCI context holding one Var per program name (it has no
  data form). An analysed function's body holds its callees' Vars, never their
  values, and a call reads the executing context's own binding of each Var, so
  a caller's analysis never depends on its callees' versions: any composition
  of definitions answers what its own source does. The design and its
  invariants are the branch-runtime note's content-addressed design,
  `docs/research/agent-platform/2026-10-19/branch-runtime.md`."
  (:require [sci.core :as sci]
            [sci.ctx-store]
            [sci.lang]
            [seon.cache]
            [seon.operator.runtime :as operator.runtime]))

(def ^:private analysed-function-bytes
  "Retained bytes of one analysed `src` function: its SCI nodes and closure.
  Calibrated by the branch-runtime note's analysis of all of `src`."
  18432)

(defn space
  "`ctx` as a Var space, named by a fresh space id.

  Its Vars are the identities every runtime forked from it binds, and the
  analyses against it are keyed by its id."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.sci.eval/ctx]}
  [ctx]
  (assoc ctx ::space (random-uuid) ::names {}))

(defn program-space
  "`ctx` as the process's Var space: its namespaces hold the Vars
  `space-var` answers, so every analysis against it is shared by every
  context that holds them."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.sci.eval/ctx]}
  [ctx]
  (assoc ctx ::space (::space operator.runtime/sci-var-space)))

(defn space-var
  "The process Var space's one SCI Var for the program name `sym`, made by
  `create` on its first request. A name a branch adds gets its Var here too,
  and joins only the namespaces of the runtimes that define it."
  {:malli/schema [:=> [:cat :qualified-symbol [:=> [:cat] :seon.sci.eval/sci-var]] :seon.sci.eval/sci-var]}
  [sym create]
  (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap (::vars operator.runtime/sci-var-space) sym
                    (reify java.util.function.Function (apply [_ _] (create)))))

(defn- analysis-key
  "What the analysis of `row` against `ctx`'s Var space is a function of: the
  space, the definition digest, the digest of its namespace's declaration
  (aliases, refers and imports decide resolution), the digests of the program
  macros it expands, and what it is armed with (`::armed-with`)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]] [:vector :seon.schema/value]]}
  [ctx row]
  [(::space ctx) (:seon.program/definition-digest row) (::namespace-digest row) (::macro-digests row) (::armed-with row)])

(defn- analyse
  "The function `row`'s source defines, analysed against `ctx`'s Vars on a fork
  that is then dropped: no runtime's binding is written. Armed by `row`'s
  `::arm` when it has one."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]] :seon.instrument/callable]}
  [ctx {sym :seon.fn/sym source :seon.fn/source arm ::arm :as row}]
  (let [;; a `.cljc` definition reads its `:clj` branches, as the JVM loaded it
        fork (assoc (sci/fork ctx) :features #{:clj})
        analysed (binding [sci.lang/*write-observer* nil sci.lang/*expansion-observer* nil]
                   (sci.ctx-store/with-ctx fork
                     (sci/binding [sci/ns (sci/find-ns fork (symbol (namespace sym)))]
                       (sci/eval-string* fork source))
                     @(sci/resolve fork sym)))
        armed (if arm (arm analysed) analysed)]
    ;; its digest names the definition it is, so a context can tell the
    ;; loaded program's from a branch's (`seon.sci.kernel/program-definition?`)
    ;; (a definition whose value is a Var, `(def f #'g)`, carries none)
    (if (instance? clojure.lang.IObj armed)
      (vary-meta armed assoc ::digest (:seon.program/definition-digest row))
      armed)))

(defn analysed
  "The function `row` defines, analysed once per content (`analysis-key`).

  It is held in `seon.cache`, evictable: a miss analyses it again."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]] :seon.instrument/callable]}
  [ctx row]
  (seon.cache/lookup ::analysed (analysis-key ctx row) (constantly analysed-function-bytes) #(analyse ctx row)))

(defn reach-index
  "Which program names run compiled exactly, and what each reaches.

  `calls` maps every program name to the names its body calls; `opening` are
  the calls that resolve a name at run time (`requiring-resolve`, `eval`),
  which the index cannot follow. A name is open when it reaches one of those,
  else closed, with its reach: itself and every program name it can call.
  `::reached-by` inverts the reaches, so [[compiled-names]] costs what a
  change touches. A compiled function answers what its analysed definition
  answers on a runtime exactly when that runtime changed nothing in its
  closed reach: its body is the same source, and every call it makes reads
  the same definitions."
  {:malli/schema [:=> [:cat [:map-of :qualified-symbol [:set :symbol]] [:set :qualified-symbol]]
                  [:map [::closed [:set :qualified-symbol]]
                   [::reached-by [:map-of :qualified-symbol [:set :qualified-symbol]]]]]}
  [calls opening]
  (let [callees (fn [sym] (filter #(contains? calls %) (get calls sym)))
        callers (reduce-kv (fn [held sym called] (reduce #(update %1 %2 (fnil conj []) sym) held (filter calls called)))
                           {} calls)
        open (loop [held (into #{} (keep (fn [[sym called]] (when (some opening called) sym))) calls)
                    todo (vec held)]
               (if-let [sym (peek todo)]
                 (let [fresh (remove held (get callers sym))]
                   (recur (into held fresh) (into (pop todo) fresh)))
                 held))
        closed (into #{} (remove open) (keys calls))
        reach (fn [sym]
                (loop [held #{sym} todo [sym]]
                  (if-let [at (peek todo)]
                    (let [fresh (remove held (callees at))]
                      (recur (into held fresh) (into (pop todo) fresh)))
                    held)))]
    {::closed closed
     ::reached-by (persistent!
                   (reduce (fn [held sym]
                             (reduce (fn [held reached] (assoc! held reached (conj (get held reached #{}) sym)))
                                     held (reach sym)))
                           (transient {}) closed))}))

(defn compiled-names
  "What a runtime running `kept` compiled may still run compiled.

  `kept` less every name whose closed reach ([[reach-index]]) holds a name in
  `changed`, the names whose definitions differ from the loaded program's."
  {:malli/schema [:=> [:cat [:map [::closed [:set :qualified-symbol]]
                             [::reached-by [:map-of :qualified-symbol [:set :qualified-symbol]]]]
                       [:set :qualified-symbol] [:set :symbol]]
                  [:set :qualified-symbol]]}
  [index kept changed]
  (reduce (fn [held sym] (reduce disj held (get (::reached-by index) sym))) kept changed))

(defn bind
  "A fork of `parent` (a Var space or a runtime forked from one) running `rows`.

  Each row whose digest differs from the one `parent` runs is bound to its
  analysed function in the fork's own Var bindings, and only digests no runtime
  analysed yet are analysed. On the answer, `::names` is its name -> digest map,
  `::analysed` the digests this call analysed and `::bound` the names it bound."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:sequential [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]]] :seon.sci.eval/ctx]}
  [parent rows]
  (let [held (::names parent)
        changed (into [] (remove #(= (:seon.program/definition-digest %) (get held (:seon.fn/sym %)))) rows)
        unseen (into #{} (comp (remove #(seon.cache/present ::analysed (analysis-key parent %)))
                               (map :seon.program/definition-digest))
                     changed)
        fork (sci/fork parent)]
    (sci.ctx-store/with-ctx fork
      (doseq [row changed]
        (sci/bind-root! fork (sci/resolve fork (:seon.fn/sym row)) (analysed parent row))))
    (assoc fork
           ::names (into held (map (juxt :seon.fn/sym :seon.program/definition-digest)) changed)
           ::analysed unseen
           ::bound (into #{} (map :seon.fn/sym) changed))))
