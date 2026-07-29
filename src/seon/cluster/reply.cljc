(ns seon.cluster.reply
  "A model reply is text; a plan is ordered form sources. This reads one
  into the other.

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

  PROSE IS A WHOLE-REPLY VERDICT, never a per-form filter. Every
  English word can read as a symbol, so successful reading alone
  cannot distinguish prose from code. A code reply has at least one
  structured top-level form (a list, vector, map or set); a bare symbol
  is retained only when it occupies its own source line inside that
  structured reply. Any other atomic form makes the WHOLE original
  reply text. This preserves `(def a 1)` then `a` while making partial
  admission unrepresentable: a terminal list after an English sentence
  cannot turn every preceding word into executable history.

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
;;; error. Only whole fence lines are removed, so a backtick inside a
;;; string literal is untouched.
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

(defn- code-reply?
  "Whether every parsed form belongs to one whole code reply."
  [source parsed]
  (let [lines (str/split-lines source)
        standalone-symbol?
        (fn [[form form-source]]
          (when (symbol? form)
            (let [line (:line (meta form))
                  source-line (when line (nth lines (dec line) nil))
                  form-line (last (str/split-lines form-source))]
              (and source-line
                   (= (str/trim source-line) (str/trim form-line))))))]
    (and (some (comp coll? first) parsed)
         (every? (fn [[form :as parsed-form]]
                   (or (coll? form)
                       (standalone-symbol? parsed-form)))
                 parsed))))

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
      (loop [parsed []]
        (let [[form form-source] (sci/parse-next+string ctx reader
                                                        {:eof ::eof})]
          (cond
            (= ::eof form)
            (if (code-reply? source parsed)
              (mapv second parsed)
              (refused ::no-forms
                       "The reply carried no Clojure forms to run."
                       {::text text}))

            (str/blank? form-source)
            (recur parsed)

            :else (recur (conj parsed [form form-source])))))
      (catch #?(:clj Throwable :cljs :default) failure
        ; sci's reader refuses #= and unknown tags by itself — there is
        ; no blocklist here, and there must never be one
        (let [message (or (ex-message failure) (str failure))]
          (if (or (str/includes? message "EvalReader")
                  (str/includes? message "reader function for tag"))
            (refused ::refused-tag message {::text text})
            (refused ::unreadable message {::text text})))))))
