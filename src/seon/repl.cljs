(ns seon.repl
  "Bash-style REPL reader. Parses text containing `;;` narration lines
   intermixed with Clojure forms into a sequence of (narration, form)
   pairs. Used by `seon.agent` to interpret an LLM's response as a
   serial REPL session.

   This namespace is intentionally narrow: parsing only. Eval lives
   in `seon.agent`; we just emit the structured pairs that get fed
   to it.

   Future home for the smart-REPL chat-fallback: when text fails to
   read as Clojure, capture it as a `:seon.message/role :user`
   transaction instead of erroring out. Then any REPL session can
   chat to the agent by typing prose. Not built yet — V0 reads
   strict Clojure forms only."
  (:require
    [cljs.tools.reader :as r]
    [cljs.tools.reader.reader-types :as rt]
    [clojure.string :as str]))

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
