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
            [seon.cache]))

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

(defn- analysis-key
  "What the analysis of `row` against `ctx`'s Var space is a function of: the
  space and the definition digest. The digests of its namespace declaration and
  of the macros it expands join the key with the program's rows (the
  branch-runtime note's macro exception)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]] [:vector :seon.schema/value]]}
  [ctx row]
  [(::space ctx) (:seon.program/definition-digest row)])

(defn- analyse
  "The function `row`'s source defines, analysed against `ctx`'s Vars on a fork
  that is then dropped: no runtime's binding is written."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]] :seon.instrument/callable]}
  [ctx {sym :seon.fn/sym source :seon.fn/source}]
  (let [fork (sci/fork ctx)]
    (binding [sci.lang/*write-observer* nil sci.lang/*expansion-observer* nil]
      (sci.ctx-store/with-ctx fork
        (sci/binding [sci/ns (sci/find-ns fork (symbol (namespace sym)))]
          (sci/eval-string* fork source))
        @(sci/resolve fork sym)))))

(defn analysed
  "The function `row` defines, analysed once per content (`analysis-key`).

  It is held in `seon.cache`, evictable: a miss analyses it again."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]] :seon.instrument/callable]}
  [ctx row]
  (seon.cache/lookup ::analysed (analysis-key ctx row) (constantly analysed-function-bytes) #(analyse ctx row)))

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
