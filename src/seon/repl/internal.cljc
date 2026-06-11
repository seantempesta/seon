(ns seon.repl.internal
  "REPL text parser — turns an LLM reply (text containing `;` comments
   interleaved with Clojure forms) into a vector of structured entries
   the eval pipeline can drive form-by-form.

   Pure rewrite-clj. CLJC so JVM tests can exercise the corpus without
   spinning up the CLJS pod — the agent's eval-batch path runs in the
   pod, but the parse contract is platform-agnostic.

   ## Entry shape

   Each vector entry is one of:

       {:kind :form
        :narration string     ; accumulated `;` comments preceding the form
        :source string        ; BYTE-FAITHFUL — what the agent typed, char-
                              ; for-char (load-bearing for resume re-eval)
        :form any}            ; the read sexpr value

       {:kind :read
        :ok? false
        :narration string     ; same accumulation rule
        :source string        ; the bad span (offset → recovery point)
        :error string}        ; rewrite-clj's parser message

   ## Format-contract enforcement

   A form is `(...)`, `[...]`, `{...}`, or a reader-macro form — the
   same contract the system prompt teaches. Top-level bare atoms
   (symbols, numbers, strings, keywords, …) are LLM prose tokenized
   by the reader; they are NARRATION, never evaluated — see
   `narration-atom?`.

   ## Per-form error isolation

   If rewrite-clj can't parse a chunk, the parser scans forward to the
   next column-0 open-delim (`(` / `[` / `{`) and records the bad span
   as a `:read`-failure entry. Forms BEFORE and AFTER the failure
   still parse. The agent sees its own broken text on the next turn's
   ctx and self-corrects.

   We do NOT auto-fix missing parens. Surfacing the failure clearly
   is more valuable than guessing what the agent meant."
  (:require
    [clojure.string :as str]
    [rewrite-clj.parser :as rcp]
    [rewrite-clj.node :as rcn]))

;; ============================================================
;; Markdown code-fence strip — Postel's law.
;;
;; The system prompt asks the LLM to emit Clojure forms directly,
;; without ``` markdown wrappers. But if it does (or if a human
;; pastes an example with fences), we tolerate it: strip the fence
;; LINES before reading, preserving everything in between.
;;
;; Why this matters: ` is Clojure's syntax-quote reader macro, so
;; ```clojure reads as a triple-syntax-quote of the symbol `clojure`
;; — `(seq (concat (list 'quote) (list (seq (concat (list 'quote)
;; …)))))`. The agent's "form" becomes that macroexpansion and the
;; eval result is incomprehensible noise.
;;
;; Line-based strip: a fence is `^\s*```(lang)?\s*$`. Drop the whole
;; line. Backticks inside multi-line string literals would be at
;; risk, but real Clojure forms don't put triple-backticks in
;; strings.
;; ============================================================

(def ^:private fence-line-re
  ;; Triple backtick OR triple tilde at line start, optional language
  ;; tag (clojure / clj / cljs / cljc / edn / nothing), trailing
  ;; whitespace only.
  #"(?m)^[ \t]*(?:```|~~~)(?:[ \t]*(?:clojure|clj|cljs|cljc|edn))?[ \t]*$")

(defn strip-code-fences
  "Remove markdown code-fence LINES (` ``` ` and ` ~~~ `, with optional
   language tag) from `text`. Content between fences stays put.
   Comments + forms outside fences are untouched. Idempotent."
  [text]
  (str/replace text fence-line-re ""))

;; ============================================================
;; Narration filter — the format contract the system prompt teaches
;; is that a form is `(...)`, `[...]`, `{...}`, or a reader-macro
;; form (`@x`, `'x`, `#{...}`, `#(...)` — all collection/list-shaped
;; after read). Top-level BARE ATOMS — symbols, numbers, strings,
;; keywords, booleans, nil, chars — only occur when the LLM emits
;; unescaped prose between forms: a sentence tokenizes into bare
;; symbols, `24 minutes` yields the number 24, a quote character in
;; prose swallows text into a string literal (`", felt good…"`).
;; Evaluating those pollutes the eval log and can eat real intent
;; (observed live: a consult intent split into `24` + a giant string
;; eval). We enforce the contract at parse time: bare atoms are
;; narration, never evaluated — dropped, carrying any accumulated
;; comment narration forward to the next real form.
;;
;; Special symbols (`do`, `if`, …) are atoms too — a bare top-level
;; `do` is the English word, not a form. Collection literals stay
;; legal: an echoed result map `{...}` still evals (harmless
;; identity), exactly matching the taught contract.
;; ============================================================

(defn- narration-atom?
  "True if `form` is a top-level bare atom — symbol (incl. special
   symbols), number, string, keyword, boolean, nil, or char — i.e.
   LLM prose tokenized by the reader rather than a form the format
   contract permits."
  [form]
  (or (symbol? form)
      (number? form)
      (string? form)
      (keyword? form)
      (boolean? form)
      (nil? form)
      (char? form)))

;; ============================================================
;; rewrite-clj node helpers
;; ============================================================

(defn- comment-text
  "Strip the leading `;`/`;;`/whitespace from a rewrite-clj comment
   node's string and trim trailing whitespace. Preserves embedded
   content exactly."
  [node]
  (-> (rcn/string node)
      (str/replace #"^[\s;]+" "")
      str/trimr))

(defn- join-narration
  "Collapse accumulated comment strings into a single narration string.
   Empty input → empty string (NOT nil, so downstream destructuring is
   predictable)."
  [parts]
  (str/trim (str/join "\n" parts)))

;; ============================================================
;; Error recovery — when one form fails to parse, advance to the next
;; column-0 open-delim and continue from there. The bad span becomes
;; a :read-failure entry.
;; ============================================================

(defn- find-recovery-point
  "When parsing fails starting at `offset`, scan forward in `text` for
   the next column-0 anchor — either an open-delim (`(` / `[` / `{`)
   OR a comment (`;`). Returns the offset of that anchor, or
   `(count text)` if none found.

   Including `;` in the anchor set matters: when a LLM emits
   `(broken-form\n;; intent for next\n(good)`, recovery should land
   on the `;;` line so the intent attaches as narration to `(good)`,
   not get swallowed in the bad span."
  [text offset]
  (let [tail (subs text offset)
        m (re-find #"\n[;\(\[\{]" tail)]
    (if m
      (+ offset (str/index-of tail m) 1)  ; +1 to land on the anchor
      (count text))))

;; ============================================================
;; Token-at-a-time scanner. rewrite-clj's parse-string parses ONE
;; top-level token (form / comment / whitespace) and stops; we walk
;; the text by reading one token, advancing past its consumed bytes,
;; and looping. A parse failure becomes a :error token that the
;; outer loop converts into a :read entry + jumps the offset past
;; the bad span.
;; ============================================================

(defn- try-parse-one-token
  "Attempt to parse exactly one rewrite-clj token starting at `offset`.
   Returns one of:

     {:kind :form       :source <byte-faithful> :form <sexpr> :end <int>}
     {:kind :comment    :text <stripped>                      :end <int>}
     {:kind :whitespace                                       :end <int>}
     {:kind :error      :error <message>}                     ; caller recovers"
  [text offset]
  (try
    (let [chunk (subs text offset)
          node  (rcp/parse-string chunk)
          src   (rcn/string node)
          end   (+ offset (count src))
          tag   (rcn/tag node)]
      (cond
        (= tag :comment)
        {:kind :comment :text (comment-text node) :end end}

        ;; :comma matters for prose: "24 minutes, felt good" — the
        ;; comma is Clojure whitespace, but rewrite-clj tags it
        ;; :comma; without it here the sexpr call throws and the
        ;; comma poisons the span up to the next recovery anchor.
        (#{:whitespace :newline :comma} tag)
        {:kind :whitespace :end end}

        :else
        {:kind :form :source src :form (rcn/sexpr node) :end end}))
    (catch #?(:clj Exception :cljs :default) e
      {:kind :error :error (#?(:clj .getMessage :cljs .-message) e)})))

;; ============================================================
;; Public surface
;; ============================================================

(defn parse-forms
  "Read `text` top-to-bottom, pairing each contiguous block of `;`
   comments with the form that follows it. See the namespace docstring
   for the entry-shape contract.

   Top-level bare atoms — symbols, numbers, strings, keywords (LLM
   prose tokenized by the reader) — are narration, never evaluated;
   they are dropped, see `narration-atom?`. Comments at the end of
   the text (no trailing form) attach to no entry and are dropped.
   Read errors do NOT halt the parse — each bad span becomes a
   `:kind :read :ok? false` entry and parsing continues, so a reader
   error mid-prose never poisons adjacent legitimate forms.

   Markdown code-fence lines (` ``` `, ` ```clojure `, ` ~~~ `, …)
   are stripped before reading — see `strip-code-fences`."
  [text]
  (let [text (strip-code-fences text)]
    (loop [offset 0
           pending-narration []
           out []]
      (if (>= offset (count text))
        out
        (let [token (try-parse-one-token text offset)]
          (case (:kind token)
            :whitespace
            (recur (:end token) pending-narration out)

            :comment
            (recur (:end token)
                   (conj pending-narration (:text token))
                   out)

            :form
            (if (narration-atom? (:form token))
              ;; LLM prose tokenized as a bare atom — drop, carry
              ;; narration forward so it attaches to the next real form.
              (recur (:end token) pending-narration out)
              (recur (:end token)
                     []
                     (conj out {:kind :form
                                :narration (join-narration pending-narration)
                                :source    (:source token)
                                :form      (:form token)})))

            :error
            (let [recovery (find-recovery-point text offset)]
              (recur recovery
                     []
                     (conj out {:kind  :read
                                :ok?   false
                                :narration (join-narration pending-narration)
                                :source    (subs text offset recovery)
                                :error     (:error token)})))))))))
