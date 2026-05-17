(ns seon.bootstrap
  "Bootstrap-CLJS: the in-pod compiler.

   The pod ships with cljs.js (the CLJS compiler itself) compiled into
   a bundle under `out/bootstrap/`. At process start, `init!` loads the
   bundle into a compiler-state atom. Thereafter, `eval-form!` evaluates
   arbitrary Clojure forms against that state — `(defn foo [x] ...)`
   interns into the agent's namespace, `(foo 17)` resolves and runs.
   Vars persist across eval calls (analyzer-side via the atom, runtime-
   side via globalThis).

   The agent's namespaces (seon.agent, seon.agents.alice, seon.db, …)
   are NOT in the bootstrap bundle — they're already loaded in the
   Node runtime from the :client build. The agent's fully-qualified
   calls (`seon.agent/say! …`) compile to JS that resolves the
   already-loaded global — no need to recompile those namespaces.

   ## Why this exists

   The eventual deployment target is Wasmer/EdgeJS sandboxed pods.
   In that environment, no shadow-cljs watcher exists — no external
   compiler available. The pod must be self-contained: bring its own
   compiler (this bundle) and eval the LLM's responses internally.

   That's why `cljs.js` is in the bundle and we don't depend on
   shadow's nREPL piggyback for agent eval. Shadow stays useful for
   dev (hot reload, external MCP eval into the running runtime), but
   not load-bearing for the agent loop.

   ## `:analyze-deps false` is critical

   `cljs.js/eval-str` defaults to `:analyze-deps true`, which walks
   any namespace the agent's form references. Our agent forms call
   `seon.agent/say!`, `seon.db/transact!`, etc. — those namespaces
   aren't in the bootstrap bundle's `index.transit.json`. With
   `:analyze-deps true`, the analyzer dies with
   `\"ns seon.agent not available\"` before emitting any code.

   With `:analyze-deps false`, the analyzer emits an
   `:undeclared-var` warning (a warning, not an error) and proceeds
   to emit `seon.agent.say_BANG_(\"hi\")` JS. At runtime that resolves
   to the already-loaded Node global. Exactly what we want."
  (:require
    [cljs.core.async :as a]
    [cljs.js :as cljs]
    [cljs.env :as env]
    [shadow.cljs.bootstrap.env :as boot-env]
    [shadow.cljs.bootstrap.node :as boot]))

;; ============================================================
;; Compiler state — one per pod, defonce-survived across hot-reload.
;; ============================================================

(defonce ^:private compile-state-ref (env/default-compiler-env))
(defonce ^:private !ready? (atom false))

(defn ready?
  "True after init! has loaded the bootstrap bundle and the compiler
   is usable. Eval calls before this throw."
  []
  @!ready?)

;; ============================================================
;; Init — load the bundle once at pod start.
;; ============================================================

;; Namespaces already loaded by the :client bundle. We mark these in
;; shadow's bootstrap env BEFORE boot/init so the bootstrap loader
;; skips re-executing their JS (which would re-declare goog.* deps
;; and throw "Namespace already declared"). The bootstrap loader
;; still loads their analyzer caches — that's what makes the compiler
;; know var shapes — but doesn't re-emit code.
;;
;; The pattern is documented in shadow.cljs.bootstrap.node:
;;   line 87  (filter (superset? loaded-ref provides))   <- analyzer
;;   line 93  (remove (superset? loaded-ref provides))   <- JS skip
(def ^:private already-loaded
  '#{cljs.core
     cljs.core$macros
     cljs.reader
     cljs.pprint
     cljs.core.async
     clojure.set
     clojure.string
     clojure.walk
     goog
     goog.array
     goog.string
     goog.string.StringBuffer
     goog.object
     goog.debug
     goog.debug.Error})

(defn init!
  "Load the bootstrap bundle from `out/bootstrap`, then prepare the
   V0 agent playground namespace. Returns a channel that closes when
   ready. Idempotent — calling twice no-ops."
  []
  (let [done (a/chan 1)]
    (if @!ready?
      (do (a/close! done) done)
      (do
        ;; Tell the bootstrap loader what's already in this process so
        ;; it doesn't try to re-execute the JS.
        (boot-env/set-loaded already-loaded)
        (boot/init
          compile-state-ref
          {:path "out/bootstrap"
           ;; :load-on-init only triggers analyzer-cache loading for
           ;; these (since they're all in loaded-ref already, JS exec
           ;; is skipped) — gives the compiler their var-shape info.
           :load-on-init '#{cljs.reader clojure.string clojure.set clojure.walk}}
          (fn []
            ;; Register the agent playground in the analyzer state by
            ;; evaluating an `(ns …)` form. This (a) creates the
            ;; analyzer-namespaces entry the compiler needs to emit
            ;; correct `<ns>.<def> = …` assignments, and (b) calls
            ;; goog.constructNamespace_ as a side effect of analysis.
            ;;
            ;; Without this, `(defn …)` in agent-ns emits broken JS
            ;; (`.foo = …` with the namespace prefix dropped). See
            ;; cljs.analyzer's :ns special form handler.
            ;;
            ;; Future multi-agent boot will iterate over all session
            ;; agent-ns values; for V0 the one hardcoded agent suffices.
            (cljs/eval-str
              compile-state-ref
              "(ns seon.agents.alice)"
              "[init-agent-ns]"
              {:eval cljs/js-eval
               :load (partial boot/load compile-state-ref)
               :analyze-deps false
               :ns 'cljs.user}
              (fn [_]
                (reset! !ready? true)
                (a/close! done)))))
        done))))

;; ============================================================
;; eval-form! — the agent's eval surface.
;;
;; Async (returns a channel). Inputs:
;;   source    — string source for one form (`pr-str` of the form is
;;               easiest; can also be raw user input)
;;   ns-sym    — target namespace, e.g. 'seon.agents.alice
;;
;; Output channel resolves to:
;;   {:seon.bootstrap/ok? true  :seon.bootstrap/value  v :seon.bootstrap/ns ns}
;;   {:seon.bootstrap/ok? false :seon.bootstrap/error  e}
;;
;; The shape mirrors cljs.js's `{:value :error :ns}` but with our
;; namespace.
;; ============================================================

(defn eval-form!
  "Evaluate one form (as a source string) against the bootstrap state
   in `ns-sym`. Returns a channel that delivers the result map and
   then closes. The compiler state mutates as a side effect — vars
   defined here are visible to subsequent calls."
  [source ns-sym]
  (when-not @!ready?
    (throw (ex-info "seon.bootstrap/init! must complete before eval-form!"
                    {:seon.bootstrap/error :not-initialized})))
  (let [done (a/chan 1)]
    (cljs/eval-str
      compile-state-ref
      source
      "[agent-eval]"
      {:eval         cljs/js-eval
       :load         (partial boot/load compile-state-ref)
       :analyze-deps false
       :ns           ns-sym
       :def-emits-var false}
      (fn [{:keys [value error ns] :as res}]
        (a/put! done
                (if error
                  {:seon.bootstrap/ok?   false
                   :seon.bootstrap/error error}
                  {:seon.bootstrap/ok?   true
                   :seon.bootstrap/value value
                   :seon.bootstrap/ns    ns}))
        (a/close! done)))
    done))
