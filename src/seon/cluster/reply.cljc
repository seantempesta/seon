(ns seon.cluster.reply
  "A model reply is text; a plan is ordered form sources. This reads one
  into the other.

  CONTRACT LAYER (drafted + ORCHESTRATOR-SEALED 2026-07-27 — N3,
  package 1, from n3-plan §7.1 and its probe C). Nothing here is
  implemented: every body throws `awaits implementation`.

  SCI'S OWN READER DOES THE WHOLE JOB, and the quarry's 1,517-line
  parser (`src-old/seon/repl/parse.cljc`) is dead — one idea survives
  it, fence stripping. Probe C measured the four things that matter:

  - `#=` is REFUSED (`EvalReader not allowed when *read-eval* is
    false`) and an unknown tag is refused by name. The D7 scar cannot
    recur here. **Nothing in N3 calls `clojure.core/read-string` or
    `read` on model text** — that is the rule, and this namespace is
    the only reader of model text there is;
  - unbalanced input is an ordinary refusal carrying a position, not a
    hang;
  - source fidelity is exact, and a leading comment attaches to the
    form it precedes. That is wanted: the stored source is what the
    agent wrote;
  - FENCES MUST BE STRIPPED FIRST. Backticks read as an ordinary
    symbol, so an unstripped ```` ```clojure ```` reply yields three
    \"forms\" — the failure is silent and produces plausible garbage.

  PROSE IS A REPLY-LEVEL VERDICT, never a per-form filter. Every
  top-level token reads as something — an English sentence reads as a
  run of symbols — so `did the agent write code?` is answered by
  structure: a plan has at least one form with a body (a list, vector,
  map or set). Once one exists, EVERY form is kept, so the REPL shape
  `(def a 1)` then `a` keeps its last line. A per-form filter would
  pass the same tests and quietly eat that `a`, which is the
  silent-drop class this system does not tolerate.

  SOURCES, NOT FORMS. The return is a vector of strings. The evaluator
  parses each one inside its own armed context, so the reply is read
  once for splitting and once for evaluation — never handed across as
  parsed data that a second reader would have to trust.

  THE PARSING CONTEXT IS A THROWAWAY `(sci/init {})`. Parsing is not
  evaluation: it needs neither the binding table nor the interrupt-fn,
  and giving it either would be handing model text a context that can
  do something.

  Errors are flat values, never throws: a reply the loop cannot split
  closes the run with a steering message the agent sees next wake.

  Crash walk: pure. A kill loses a vector of strings that had not been
  committed; the plan is not durable until N2's `plan-tx` commits it,
  and re-deriving it from the same text is deterministic."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/reply.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Fence stripping — the one idea that survives the quarry's parser
;;; ---------------------------------------------------------------------------

;;; A fence is presentation. Backticks read as an ordinary symbol, so an
;;; unstripped ```clojure reply yields plausible garbage rather than an
;;; error — which is why this runs FIRST and why it is not a regex over
;;; the whole text: only whole fence LINES are removed, so a backtick
;;; inside a string literal is untouched.
(def ^:private fence-line #"(?m)^[ \t]*```[^\n]*$")

(defn- fenced-blocks
  "The contents of every fenced block, or nil when the text has none."
  [text]
  (let [lines (str/split-lines text)
        fenced? (fn [line] (re-matches #"[ \t]*```.*" line))]
    (when (some fenced? lines)
      (->> lines
           (reduce (fn [{:keys [inside? blocks] :as state} line]
                     (if (fenced? line)
                       (assoc state :inside? (not inside?)
                              :blocks (if inside? blocks (conj blocks [])))
                       (cond-> state
                         inside? (update-in [:blocks (dec (count blocks))]
                                            conj line))))
                   {:inside? false :blocks []})
           :blocks
           (map #(str/join "\n" %))
           (str/join "\n\n")))))

(defn- unfenced
  "`text` with code fences removed: the fenced code when the reply has
  fences, the text itself when it does not."
  [text]
  (or (fenced-blocks text) text))

(defn- refused
  "The ONE registered flat error value (`:seon.error/value`).
  Detail rides under `:seon.error/data` rather than beside the message,
  because the shape is closed and one owner (error.edn) decides it."
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn sources
  "The ordered form sources in one model reply, or a flat error value.
  Strips code fences, then reads with SCI's own reader
  (`sci/source-reader` + `sci/parse-next+string`) against a throwaway
  `(sci/init {})`, returning each form's EXACT source text in order.

  Flat `:seon.error` values, never throws:
  - `::unreadable` — unbalanced or malformed input, carrying the
    reader's own position so the agent can see where;
  - `::refused-tag` — `#=` or an unknown reader tag, named;
  - `::no-forms` — the reply carried prose only. This is a real agent
    outcome, not a parse failure, and it must be distinguishable."
  {:malli/schema [:=> [:cat :seon.cluster.reply/text]
                  [:or :seon.cluster.reply/sources :seon.error/value]]}
  [text]
  (let [source (unfenced text)
        ; parsing is not evaluation: a throwaway context with no
        ; bindings and no interrupt-fn is all a reader needs, and it is
        ; all model text should ever be handed
        ctx (sci/init {})
        reader (sci/source-reader source)]
    (try
      (loop [collected []
             ; PROSE IS A REPLY-LEVEL VERDICT, not a per-form filter.
             ; Every top-level token reads as something — an English
             ; sentence reads as a run of symbols — so "did the agent
             ; write code?" is answered by structure: a plan has at
             ; least one form with a body (a list, vector, map or set).
             ; Once one exists, EVERY form is kept, so the REPL shape
             ; `(def a 1)` then `a` survives intact rather than having
             ; its last line silently dropped.
             structured? false]
        (let [[form form-source] (sci/parse-next+string ctx reader
                                                        {:eof ::eof})]
          (cond
            (= ::eof form)
            (if structured?
              collected
              (refused ::no-forms
                       "The reply carried no Clojure forms to run."
                       {::text text}))

            (str/blank? form-source)
            (recur collected structured?)

            :else (recur (conj collected form-source)
                         (or structured? (coll? form))))))
      (catch #?(:clj Throwable :cljs :default) failure
        ; sci's reader refuses #= and unknown tags by itself — there is
        ; no blocklist here, and there must never be one
        (let [message (or (ex-message failure) (str failure))]
          (if (or (str/includes? message "EvalReader")
                  (str/includes? message "reader function for tag"))
            (refused ::refused-tag message {::text text})
            (refused ::unreadable message {::text text})))))))
