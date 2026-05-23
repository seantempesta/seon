(ns seon.parse
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
;; Prose filter — LLMs occasionally emit unescaped prose between
;; forms ("Let me think about this..."), which the reader cheerfully
;; tokenizes into a string of bare symbols. Each bare symbol would
;; resolve to nil under cljs.js's permissive bootstrap and pollute
;; the eval log. We drop them at parse time, carrying any accumulated
;; narration forward to the next real form.
;;
;; Legitimate agent code is overwhelmingly list-shaped (function
;; calls, special forms, defs) or reader-macro-shaped (`@!atom`,
;; `'sym` — both list forms after read). Bare unqualified symbols
;; at the top level have no legitimate use in the agent protocol.
;; ============================================================

(defn- prose-symbol?
  "True if `form` is a bare symbol that almost certainly came from
   the LLM emitting unescaped prose instead of code."
  [form]
  (and (symbol? form)
       (not (special-symbol? form))))

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

        (#{:whitespace :newline} tag)
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

   Bare top-level symbols (LLM prose tokenized as bare symbols) are
   dropped silently — see `prose-symbol?`. Comments at the end of
   the text (no trailing form) attach to no entry and are dropped.
   Read errors do NOT halt the parse — each bad span becomes a
   `:kind :read :ok? false` entry and parsing continues."
  [text]
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
          (if (prose-symbol? (:form token))
            ;; LLM prose tokenized as a bare symbol — drop, carry
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
                              :error     (:error token)}))))))))
