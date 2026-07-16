(ns seon.repl
  "Iteration-surface helpers for `dev-init!` and the compiler state.
   The text→entries parser used to live here too but
   was extracted to [[seon.repl.internal]] (.cljc — JVM-testable, no pod
   required for the corpus).

   ## Iteration surface (`dev-init!`)

   `dev-init!` initializes bootstrap-CLJS in one defonce'd atom. Decoupled from
   `seon.client/start-agent!` so core experiments don't have to
   spin up the stub LLM, web server, or broadcast watcher.

   ### Typical loop via the `eval_cljs` MCP tool

   The MCP server piggybacks shadow's nREPL into the :client runtime;
   forms eval'd through it see every namespace required from
   seon.client — including rewrite-clj and cljs.js and, after
   `dev-init!`, the persistent compile-state.

   ```clojure
   (seon.repl/dev-init!)
   ; returns a Promise containing the compile-state

   (rewrite-clj.parser/parse-string-all \";; hi\\n(+ 1 2)\\n\")

   (.then (seon.eval/eval @seon.repl/!compile-state \"(+ 1 2)\")
          js/console.log)
   ```

   ### Two eval surfaces

   - **Host eval** (shadow nREPL piggyback) reaches every var
     statically required by :client. Use for core-library
     questions (does rewrite-clj load, does datahike's history
     API behave as documented).
   - **Bootstrap-CLJS eval** (`seon.eval/eval` against
     `@seon.repl/!compile-state`) compiles + evaluates a string
     through cljs.js. Use when the question IS the LLM-emitted
     experience — error shapes, ns switching, `^:async`,
     `(def …)` cross-form persistence.

   Database experiments use the running authority through `seon.db`; this
   namespace never creates a second local database."
  (:require
    ;; --- Iteration-surface deps ---
    [seon.eval :as seval]
    ;; Pulled in so the :client bundle can reach rewrite-clj via the
    ;; host REPL (`eval_cljs`) for ad-hoc core probes
    ;; — e.g. `(rewrite-clj.parser/parse-string-all "...")`. The
    ;; parse-forms parser itself lives in seon.repl.internal (.cljc).
    [rewrite-clj.parser]
    [rewrite-clj.node]
    [rewrite-clj.zip]
    [seon.schema :as schema]))

;; ============================================================
;; The parse-entry envelope (produced by seon.repl.internal/parse-forms —
;; the .internal machinery of THIS ns, which owns the data). In-memory
;; only, never transacted whole. `::form` is deliberately UNREGISTERED —
;; it carries an arbitrary read sexpr (same reasoning as the unregistered
;; :seon.eval/value). A `:read` entry's failure is the ONE :seon/error
;; value: {:seon.error/kind <classified> :seon.error/message <parser msg>}.
;; Registered here, not in the .cljc producer, because seon.repl.internal
;; must stay loadable by bare babashka (bin/oracle-server) — no malli.
;; ============================================================

(schema/register! ::kind [:enum :form :read :comment])
(schema/register! ::ok? :boolean)
(schema/register! ::narration :string)
(schema/register! ::source :string)
(schema/register! ::span [:tuple :int :int])

;; ============================================================
;; Iteration-surface — dev-init! opens the bootstrap-CLJS compile-state.
;; It is stored in a defonce atom so subsequent calls are cheap. Wired apart from
;; seon.client/start-agent! so core experiments don't drag in
;; the stub LLM, web server, or broadcast watcher.
;; ============================================================

(defonce !compile-state (atom nil))

;; Version stamp paired with `!compile-state`. When `seon.eval` is hot-
;; reloaded, `seval/init-version` rotates to a new gensym; this atom
;; still holds the prior gensym, so `ensure-bootstrap!` detects the
;; mismatch and rebuilds the state. See KI-2 in agent-repl-mvp + the
;; lifecycle research note for the design rationale.
(defonce !init-version (atom nil))

(defn ^:async ensure-bootstrap!
  "Lazy-init the bootstrap-CLJS compile-state.

   Returns the state
   (not a Promise of the state once cached). Public so
   `seon.client/start-agent!` can share the same atom — there's
   one compile-state in the pod, owned here.

   Version-stamped: if `seon.eval/init-version` differs from the
   cached `@!init-version`, the cache is invalidated and a fresh
   init runs. That solves KI-2 — hot-reloads of `seon.eval` rotate
   the version, so the core-iteration loop doesn't have to
   manually nil the atom."
  {:malli/schema [:=> [:cat] :any]}
  []
  (if (and @!compile-state
           (identical? @!init-version seval/init-version))
    @!compile-state
    (let [state (await (seval/init-bootstrap!))]
      (reset! !compile-state state)
      (reset! !init-version seval/init-version)
      state)))

(defn ^:async dev-init!
  "Idempotent dev bring-up.

   Returns a Promise resolving to `{:compile-state <state>}`. Safe to call on every
   MCP eval — second + subsequent calls are O(atom-deref)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [state (await (ensure-bootstrap!))]
    {:compile-state state}))
