(ns seon.repl
  "Bash-style REPL reader + iteration-surface helpers.

   ## Parse-forms (original responsibility)

   Parses text containing `;;` narration lines intermixed with Clojure
   forms into a sequence of (narration, form) pairs. Used by
   `seon.agent` to interpret an LLM's response as a serial REPL
   session. (Spec will eventually swap this Edamame-based reader for
   rewrite-clj — see spec §Parse — but the current shape is the
   working V0 path and stays here.)

   ## Iteration surface (`dev-init!`)

   `dev-init!` opens a history-enabled datahike conn AND initializes
   bootstrap-CLJS, both stashed in defonce'd atoms. Decoupled from
   `seon.client/start-agent!` so substrate experiments don't have to
   spin up the stub LLM, web server, or broadcast watcher.

   ### Typical loop via mcp__seon_cljs__eval

   The MCP server piggybacks shadow's nREPL into the :client runtime;
   forms eval'd through it see every namespace required from
   seon.client — including rewrite-clj, datahike, cljs.js, and
   (after `dev-init!`) the persistent compile-state + history conn.

   ```clojure
   (seon.repl/dev-init!)
   ;; => Promise<{:compile-state #<atom> :conn #<conn>}>

   (rewrite-clj.parser/parse-string-all \";; hi\\n(+ 1 2)\\n\")

   (.then (seon.eval/eval @seon.repl/!compile-state \"(+ 1 2)\")
          js/console.log)
   ```

   ### Two eval surfaces

   - **Host eval** (shadow nREPL piggyback) reaches every var
     statically required by :client. Use for substrate-library
     questions (does rewrite-clj load, does datahike's history
     API behave as documented).
   - **Bootstrap-CLJS eval** (`seon.eval/eval` against
     `@seon.repl/!compile-state`) compiles + evaluates a string
     through cljs.js. Use when the question IS the LLM-emitted
     experience — error shapes, ns switching, `^:async`,
     `(def …)` cross-form persistence.

   Both write to the same datahike conn, so tx-meta tags and
   history queries work the same way through either surface."
  (:require
    [cljs.tools.reader :as r]
    [cljs.tools.reader.reader-types :as rt]
    [clojure.string :as str]
    ;; --- Iteration-surface deps ---
    [datahike.api :as d]
    [seon.eval :as seval]
    ;; rewrite-clj namespaces pulled into the :client bundle by being
    ;; required here. After this lands, mcp__seon_cljs__eval can call
    ;; `(rewrite-clj.parser/parse-string-all ...)` directly against
    ;; the host runtime. Required for spec §D1 (forms-and-comments
    ;; parse) and §D10/D12 (analyzer walk + bootstrap emission).
    [rewrite-clj.parser]
    [rewrite-clj.node]
    [rewrite-clj.zip]))

;; ============================================================
;; Reader helpers — accumulate ;-comment lines into a narration
;; string, skip blank lines, then read one form. Returns one pair
;; per call to read-pair! until EOF.
;; ============================================================

(defn- skip-comments-and-blanks!
  "Advance reader past whitespace + ;-lines. Returns accumulated
   comment text (one ;-line per output line, leading `;` stripped).
   Position-tolerant: handles `;`, `;;`, `;;;` equivalently."
  [rdr]
  (let [comments (atom [])]
    (loop []
      (let [ch (rt/read-char rdr)]
        (cond
          (nil? ch)        nil
          (= ch \newline)  (recur)
          (re-matches #"\s" (str ch)) (recur)
          (= ch \;)
          (let [line (loop [acc []]
                       (let [c (rt/read-char rdr)]
                         (if (or (nil? c) (= c \newline))
                           (apply str acc)
                           (recur (conj acc c)))))]
            (swap! comments conj (str/replace line #"^[\s;]+" ""))
            (recur))
          :else (rt/unread rdr ch))))
    (str/join "\n" @comments)))

(defn- prose-symbol?
  "Heuristic — true if `form` is a bare symbol that almost certainly
   came from the LLM emitting unescaped prose instead of code. The
   reader cheerfully tokenizes 'Let me read' into three separate
   symbol forms, each of which evaluates to nil under cljs.js's
   permissive bootstrap and pollutes the eval log. Filtering these
   out at parse-time is much safer than eval-time.

   Legitimate agent code is overwhelmingly list-shaped (function
   calls, special forms, defs) or reader-macro-shaped (`@!atom`,
   `'sym` — both list forms after read). Bare unqualified symbols
   at the top level have no legitimate use in the agent protocol."
  [form]
  (and (symbol? form)
       (not (special-symbol? form))))

(defn parse-forms
  "Read `text` top-to-bottom, pairing each contiguous block of `;-`
   comments with the form that follows it. Returns a vector of
   `{:narration string :source string :form any}`.

   Bare top-level symbols (LLM prose tokenized by the reader) are
   dropped silently — see `prose-symbol?`. Comments at the end of
   the text (no trailing form) are dropped. Read errors halt — caller
   sees a truncated vector + can decide.

   (V0.5 we'll thread the read error back as a sentinel pair.)"
  [text]
  (let [rdr (rt/string-push-back-reader text)]
    (loop [out [] pending-narration ""]
      (let [more-narration (skip-comments-and-blanks! rdr)
            narration      (str/trim
                             (str pending-narration
                                  (when (and (seq pending-narration)
                                             (seq more-narration))
                                    "\n")
                                  more-narration))
            form (try (r/read {:eof ::eof} rdr)
                      (catch :default _ ::eof))]
        (cond
          (= form ::eof)
          out

          ;; LLM prose tokenized as bare symbols — skip, carry narration
          ;; forward so it attaches to the next real form.
          (prose-symbol? form)
          (recur out narration)

          :else
          (recur (conj out {:narration narration
                            :source    (pr-str form)
                            :form      form})
                 ""))))))

;; ============================================================
;; Iteration-surface — dev-init! opens an agent conn (history-on) +
;; bootstrap-CLJS compile-state. Both stored in defonce atoms so
;; subsequent calls are cheap. Wired separately from
;; seon.client/start-agent! so substrate experiments don't drag in
;; the stub LLM, web server, or broadcast watcher.
;; ============================================================

(defonce !compile-state (atom nil))

;; Version stamp paired with `!compile-state`. When `seon.eval` is hot-
;; reloaded, `seval/init-version` rotates to a new gensym; this atom
;; still holds the prior gensym, so `ensure-bootstrap!` detects the
;; mismatch and rebuilds the state. See KI-2 in agent-repl-mvp + the
;; lifecycle research note for the design rationale.
(defonce !init-version (atom nil))

(defonce !conn (atom nil))

(defn ^:async ensure-bootstrap!
  "Lazy-init the bootstrap-CLJS compile-state. Returns the state
   (not a Promise of the state once cached). Public so
   `seon.client/start-agent!` can share the same atom — there's
   one compile-state in the pod, owned here.

   Version-stamped: if `seon.eval/init-version` differs from the
   cached `@!init-version`, the cache is invalidated and a fresh
   init runs. That solves KI-2 — hot-reloads of `seon.eval` rotate
   the version, so the substrate-iteration loop doesn't have to
   manually nil the atom."
  []
  (if (and @!compile-state
           (identical? @!init-version seval/init-version))
    @!compile-state
    (let [state (await (seval/init-bootstrap!))]
      (reset! !compile-state state)
      (reset! !init-version seval/init-version)
      state)))

(defn ^:async ^:private ensure-conn!
  "Lazy-init a :memory datahike conn with history enabled. History
   is the load-bearing bit — the spec's tx-meta-via-history-datoms
   trick (every eval entity IS its tx) doesn't work without it."
  []
  (or @!conn
      (let [cfg {:store              {:backend :memory
                                      :id      (random-uuid)}
                 :schema-flexibility :write
                 :keep-history?      true}]
        (await (d/create-database cfg))
        (let [conn (await (d/connect cfg))]
          (reset! !conn conn)
          conn))))

(defn ^:async dev-init!
  "Idempotent dev bring-up. Returns a Promise resolving to
   `{:compile-state <state> :conn <conn>}`. Safe to call on every
   MCP eval — second + subsequent calls are O(atom-deref)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [conn  (await (ensure-conn!))
        state (await (ensure-bootstrap!))]
    {:compile-state state
     :conn          conn}))
