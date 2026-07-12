(ns seon.repl.internal
  "REPL text parser — turns an LLM reply (text containing `;` comments
   interleaved with Clojure forms) into a vector of structured entries
   the eval pipeline can drive form-by-form.

   Pure rewrite-clj. CLJC so JVM tests can exercise the corpus without
   spinning up the CLJS pod — the agent's eval-batch path runs in the
   pod, but the parse contract is platform-agnostic.

   ## Entry shape

   Keys live in the `:seon.repl` namespace — the `.internal` machinery
   of `seon.repl`, which owns the parse-envelope data (registered below).
   Each vector entry is one of:

       {:seon.repl/kind :form
        :seon.repl/narration string  ; the `;;` COMMENT-PREAMBLE for this
                              ; form: the real `;` comment lines that
                              ; preceded it, `;` stripped, one per line (the
                              ; renderer re-adds `;;`). Bare prose is NOT
                              ; captured — it is DROPPED (see below).
        :seon.repl/source string  ; BYTE-FAITHFUL — what the agent typed,
                              ; char-for-char (load-bearing for resume
                              ; re-eval)
        :seon.repl/form any   ; the read sexpr value (always a list/seq)
        :seon.repl/span [start end]}  ; ABSOLUTE char offsets of the form
                              ; in `text` (same basis as the `:read` span)
                              ; — the closed-loop oracle's clamp-to-HOLD
                              ; spans for good forms

       {:seon.repl/kind :read
        :seon.repl/ok? false
        :seon.repl/narration string  ; same `;;`-comment accumulation rule
        :seon.repl/source string  ; the bad span (offset → recovery point)
        :seon/error           ; the ONE error-value shape (seon.error):
          {:seon.error/kind keyword   ; classified failure
                              ; (`classify-read-error`): :eof /
                              ; :unmatched-delimiter / :invalid-token /
                              ; :read — the re-noise / repair layer
                              ; dispatches on this (tail vs point re-mask;
                              ; :invalid-token is the embedding-lookup hook)
           :seon.error/message string}  ; rewrite-clj's parser message
        :seon.repl/span [start end]}  ; ABSOLUTE char offsets of the bad
                              ; span in `text` — what a token-code-buffer
                              ; re-noise step maps back to mask positions

       {:seon.repl/kind :comment
        :seon.repl/narration string}  ; either trailing `;;` comment lines
                              ; with NO following form, OR the one-line
                              ; `demoted-literal-warning` for a top-level
                              ; data literal (`{…}`/`[…]`/`#{…}`) that was
                              ; demoted to prose. The renderer shows it as
                              ; `;;` lines, no form.

   ## Forms-and-prose-only — what evaluates, what is dropped (#50/#52)

   A top-level read form is a `:kind :form` entry (EVALUATED) iff it is a
   LIST/SEQ — `(…)` plus the reader-macros that read as seqs (`@x`/`'x`/
   `#(…)`/`` `(…) ``/`#'x`) — OR a bare `result/<id>` symbol (a stash
   RE-REFERENCE that self-evaluates into its prior value, #39).
   EVERYTHING else is prose:

     - real `;`/`;;` comments → kept as narration (the taught reasoning
       channel — these are NOT the trap);
     - bare atoms / sentences / a bare `=>` echo / tagged literals
       (`#inst`/`#uuid`/`#js`/`#?(…)`) / an A.1 unreadable token (`80s`)
       → DROPPED (not echoed as `;;` — that echo was the `;;`-imitation
       trap that taught agents to write `;;` when they meant data);
     - a top-level DATA LITERAL (`{…}`/`[…]`/`#{…}`) → DROPPED (a
       fabricated `=> {…}` echo would otherwise self-evaluate into a real
       `result/<id>`, #52) but emits ONE `demoted-literal-warning`.

   See `prose-token?` / `data-literal?` for the cut.

   ## Per-form error isolation

   If rewrite-clj can't parse a chunk, the parser scans forward to the
   next column-0 open-delim (`(` / `[` / `{`) and records the bad span
   as a `:read`-failure entry. Forms BEFORE and AFTER the failure
   still parse. The agent sees its own broken text on the next turn's
   ctx and self-corrects.

   We do NOT auto-fix missing parens here. Surfacing the failure clearly
   is more valuable than guessing what the agent meant (the eval pipeline
   layers a best-effort parinfer repair ON TOP of a `:read` entry)."
  (:require
    [clojure.string :as str]
    [rewrite-clj.parser :as rcp]
    [rewrite-clj.node :as rcn]))

;; ============================================================
;; The parse-entry envelope keys are :seon.repl/* — this ns is the
;; .internal machinery of seon.repl, which OWNS the data and registers
;; the envelope schemas (see seon.repl). They are not registered HERE
;; because this ns must stay loadable by bare babashka (bin/oracle-server
;; puts only src/ on the bb classpath — no malli, no seon.schema).
;; ============================================================

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
;; `#code` heredoc literal — pre-tokenization pass (Unit A1).
;;
;; rewrite-clj cannot read a heredoc, and the whole point of `#code` is
;; that a foreign-source payload needs ZERO Clojure escaping. So BEFORE
;; the token loop we rewrite each
;;
;;     #code/<lang> <<SENTINEL
;;     <payload lines…>
;;     SENTINEL
;;
;; region into a machine-escaped, valid-EDN map literal
;;
;;     {:seon.code/lang :<lang>, :seon.code/text "<escaped payload>"}
;;
;; that the downstream reader (rewrite-clj here, `cljs.js` at eval time)
;; reads natively. THE MACHINE does the escaping — the agent never does.
;;
;; Two bases are kept in lock-step by a segment map:
;;   - REWRITTEN text: what the reader sees (valid EDN) — drives
;;     `:seon.repl/form` and, when it differs, `:seon.repl/eval-source`
;;     (the cljs-readable string `eval-batch!` actually evaluates).
;;   - ORIGINAL text: what the agent typed — drives byte-faithful
;;     `:seon.repl/source` and absolute `:seon.repl/span` offsets.
;;
;; Opener: `#code/<lang> <<SENTINEL\n`; lang is any keyword-safe token;
;; SENTINEL ∈ [A-Za-z0-9_-]+, agent-chosen. Closer: a line that is
;; EXACTLY SENTINEL (a trailing `\r` allowed, nothing else) — a sentinel
;; word mid-line or an INDENTED sentinel does not close. Payload = the
;; bytes between the opener's newline and the closer line, verbatim
;; (incl. the final newline of the last payload line) — no normalization.
;;
;; A truly BARE top-level `#code` splices to a bare map literal and so
;; demotes to prose like any other top-level `{…}` (with the standard
;; warning); the value is meant to be USED nested inside a call form
;; (`(fs/replace! {::find #code/py <<PY…PY})`), which is the primary case.
;;
;; Malformed (`#code/lang` with no `<<SENTINEL`) or unterminated (opener
;; with no closer before EOF) → an error MARKER carried out as a
;; `:seon.repl/kind :read` entry NAMING the awaited sentinel, never
;; silently dropped (`#code/…` is a `:reader-macro` tag which
;; `prose-token?` would otherwise drop as prose).
;;
;; Textual scan (like `strip-code-fences`): a `#code/` inside a Clojure
;; STRING literal would false-positive — agents don't write `#code/` in
;; strings; documented limitation. Fence stripping runs first, so a
;; markdown-fence line INSIDE a payload is stripped along with the rest —
;; also documented (real py/rust/go/yaml payloads carry no ``` lines).
;; ============================================================

(def ^:private heredoc-opener-re
  ;; `#code/<lang> <<SENTINEL <eol>` anchored at the `#` (matched against a
  ;; substring that STARTS at the marker). lang = run up to whitespace/`<`;
  ;; SENTINEL = [A-Za-z0-9_-]+.
  #"^#code/([^\s<]+)[ \t]+<<([A-Za-z0-9_-]+)[ \t]*\r?\n")

(def ^:private code-marker-re
  ;; a bare `#code/<token>` run — the malformed-region span when the full
  ;; opener does not match. Stops at whitespace OR a closing/opening
  ;; delimiter so a `#code/python)` does not swallow the enclosing form's
  ;; `)` (which would unbalance it into a spurious EOF `:read`).
  #"^#code/[^\s()\[\]{}<>\"]*")

(defn contains-heredoc-opener?
  "True when `s` holds a `#code/<lang> <<SENTINEL` heredoc opener.

   Such a span must NOT be handed to parinfer delimiter-repair (it would
   try to balance the raw payload's delimiters); the eval-batch repair path
   refuses repair on these so the `:read` error naming the sentinel
   surfaces instead."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [s]
  (boolean (and s (re-find #"#code/[^\s<]+[ \t]+<<[A-Za-z0-9_-]+" s))))

(defn- find-closer
  "Byte offsets `[line-start line-end)` of the FIRST line at/after `from`
   that is EXACTLY `sentinel` (an optional trailing `\\r` allowed), or nil.
   `line-end` is just past that line's newline (or EOF). Only a whole-line
   match closes — a sentinel word mid-line or an indented sentinel does
   not. `from` is a line start (right after the opener's newline)."
  [text from sentinel]
  (let [n (count text)]
    (loop [ls from]
      (if (> ls n)
        nil
        (let [nl    (str/index-of text "\n" ls)
              le    (if nl (inc nl) n)
              line  (subs text ls (if nl nl le))
              line* (if (str/ends-with? line "\r")
                      (subs line 0 (dec (count line)))
                      line)]
          (cond
            (= line* sentinel) [ls le]
            nl                 (recur le)
            :else              nil))))))

(defn- edn-escape
  "EDN string-literal escaping of `s` — `\\`, `\"`, and the control chars
   `\\n`/`\\r`/`\\t` → their backslash forms, so the spliced literal is a
   single-line, byte-faithful string the reader restores exactly.
   Backslash MUST be escaped first."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\r" "\\r")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")))

(defn- block-edn
  "The valid-EDN map literal a terminated heredoc splices to."
  [lang-str payload]
  (str "{:seon.code/lang :" lang-str
       ", :seon.code/text \"" (edn-escape payload) "\"}"))

(defn- scan-heredoc-pieces
  "Split `text` into ordered pieces for the heredoc rewrite, or nil when
   `text` has no `#code/` at all (the identity fast-path). Each piece:

     {::piece :verbatim ::o0 int ::o1 int}          ; copied through as-is
     {::piece :block    ::o0 ::o1 ::edn string}     ; heredoc → EDN literal
     {::piece :error    ::o0 ::o1 ::message string} ; malformed/unterminated

   An unterminated opener swallows to EOF (terminal); a malformed
   `#code/token` spans only that token and the scan continues after it."
  [text]
  (when (str/includes? text "#code/")
    (let [n (count text)]
      (loop [pos 0, pieces []]
        (if-let [i (str/index-of text "#code/" pos)]
          (let [head  (if (> i pos) [{::piece :verbatim ::o0 pos ::o1 i}] [])
                chunk (subs text i)
                m     (re-find heredoc-opener-re chunk)]
            (if m
              (let [[whole lang sentinel] m
                    payload-start (+ i (count whole))
                    closer        (find-closer text payload-start sentinel)]
                (if closer
                  (let [[cl-start cl-end] closer
                        payload (subs text payload-start cl-start)
                        piece   {::piece :block ::o0 i ::o1 cl-end
                                 ::edn (block-edn lang payload)}]
                    (recur cl-end (into pieces (conj head piece))))
                  ;; Unterminated — terminal: everything from the opener to
                  ;; EOF is the awaited payload. One `:error` marker naming
                  ;; the sentinel; the scan ends here.
                  (into pieces
                        (conj head
                              {::piece :error ::o0 i ::o1 n
                               ::message
                               (str "unterminated #code/" lang " heredoc: "
                                    "expected a line containing exactly `"
                                    sentinel "` to close it, none found before "
                                    "end of input.")}))))
              ;; Malformed — `#code/` with no `<<SENTINEL`. Surface a `:read`
              ;; over the `#code/token` span and continue after it.
              (let [tok (re-find code-marker-re chunk)]
                (recur (+ i (count tok))
                       (into pieces
                             (conj head
                                   {::piece :error ::o0 i ::o1 (+ i (count tok))
                                    ::message
                                    (str "malformed #code literal `" tok "`: "
                                         "expected `#code/<lang> <<SENTINEL` "
                                         "then payload lines then a closing "
                                         "SENTINEL line.")}))))))
          (if (< pos n)
            (conj pieces {::piece :verbatim ::o0 pos ::o1 n})
            pieces))))))

(defn- assemble
  "Fold heredoc pieces into `{::rewritten ::segments ::markers}`. Segments
   map REWRITTEN offsets back to ORIGINAL (`::r0`/`::r1` ↔ `::o0`/`::o1`,
   `::heredoc?`); markers become `:read` entries. An `:error` piece
   contributes nothing to the rewritten text."
  [text pieces]
  (loop [ps pieces, roff 0, sb [], segs [], marks []]
    (if-let [{::keys [piece o0 o1 edn message]} (first ps)]
      (case piece
        (:verbatim :block)
        (let [s   (if (= piece :block) edn (subs text o0 o1))
              len (count s)]
          (recur (rest ps) (+ roff len) (conj sb s)
                 (conj segs {::r0 roff ::r1 (+ roff len)
                             ::o0 o0 ::o1 o1 ::heredoc? (= piece :block)})
                 marks))
        :error
        (recur (rest ps) roff sb segs
               (conj marks {::o0 o0 ::o1 o1 ::message message})))
      {::rewritten (str/join sb) ::segments segs ::markers marks})))

(defn- orig-offset
  "Map a REWRITTEN offset `r` back to the ORIGINAL offset via `segments`.
   Form boundaries never fall inside a heredoc segment, so such a segment
   maps only its ends (`::r0`→`::o0`, `::r1`→`::o1`)."
  [segments r]
  (loop [segs segments]
    (if-let [{::keys [r0 r1 o0 o1 heredoc?]} (first segs)]
      (if (<= r r1)
        (if heredoc? (if (>= r r1) o1 o0) (+ o0 (- r r0)))
        (recur (rest segs)))
      r)))

(defn- remap-entry
  "Rebase one loop-produced entry from the REWRITTEN basis onto the
   ORIGINAL text: `:seon.repl/span` → original offsets, `:seon.repl/source`
   → byte-faithful original substring, and — whenever the cljs-readable
   rewrite differs from the byte-faithful original, i.e. this entry's
   source region was touched by the heredoc pre-pass — `:seon.repl/eval-source`
   = the rewritten (cljs-readable) source `eval-batch!` evaluates. That
   covers a heredoc form's rewritten `{:seon.code/…}` map AND any entry
   whose region shifted next to a malformed/unterminated `#code` marker.
   Entries without a span (comments) pass through untouched."
  [orig segments entry]
  (if-let [[r0 r1] (:seon.repl/span entry)]
    (let [o0   (orig-offset segments r0)
          o1   (orig-offset segments r1)
          rsrc (:seon.repl/source entry)
          osrc (subs orig o0 o1)]
      (cond-> (assoc entry :seon.repl/span [o0 o1] :seon.repl/source osrc)
        (and rsrc (not= osrc rsrc)) (assoc :seon.repl/eval-source rsrc)))
    entry))

(defn- marker->read
  "A malformed/unterminated `#code` MARKER → a `:seon.repl/kind :read`
   entry (the ONE `:seon/error` value shape), byte-faithful source + span
   in the ORIGINAL basis."
  [orig {::keys [o0 o1 message]}]
  {:seon.repl/kind      :read
   :seon.repl/ok?       false
   :seon.repl/narration ""
   :seon.repl/source    (subs orig o0 o1)
   :seon/error          {:seon.error/kind    :read
                         :seon.error/message message}
   :seon.repl/span      [o0 o1]})

(defn- heredoc-remap
  "Rebase `base` (loop entries, REWRITTEN basis) onto `orig` via
   `segments`, then append any error `markers` as `:read` entries. No-op
   when there were no heredocs (`segments`/`markers` nil). Markers are
   appended (an unterminated one is terminal; a mid-stream malformed one
   is an error path — append ordering is acceptable)."
  [orig segments markers base]
  (let [base (if segments (mapv #(remap-entry orig segments %) base) base)]
    (if (seq markers)
      (into (vec base) (map #(marker->read orig %) markers))
      base)))

;; ============================================================
;; Prose-vs-form classification — the FORMS-AND-PROSE-ONLY rule
;; (#50/#52, LOCKED 2026-06-22).
;;
;; A top-level READ form is EVALUATED iff it is a LIST/SEQ; EVERYTHING
;; else is prose. The reader's sexpr makes this a clean cut:
;;
;;   EVALUATE — these all read as seqs and ARE genuine forms:
;;     `(foo …)`        → :list          → (foo …)
;;     `#(+ % 1)`       → :fn            → (fn* …)
;;     `@x`             → :deref         → (clojure.core/deref x)
;;     `'x`             → :quote         → (quote x)
;;     `#'x`            → :var           → (var x)
;;
;;   PROSE — never evaluated:
;;     - INLINE-BACKTICK code — a top-level `:syntax-quote`
;;       (`` `(subs s 0 5) ``), `:unquote` (`~x`) or `:unquote-splicing`
;;       (`~@x`): these sexpr to seqs so `seq?` alone would EVALUATE them,
;;       but at the agent REPL a leading backtick is ALWAYS inline prose
;;       (`I'll use \`(subs s 0 5)\` to format`) — never intentional
;;       macro-quoting. The live damage was a "backtick cascade": one
;;       inline `\`(form)\`` shredded into multiple junk evals plus bare
;;       `42`-style atoms, all recorded as real `result/<id>` history
;;       (the agent's own turn-4 message diagnosed it). Classifying the
;;       backtick reader-macros as prose stops the cascade at its root.
;;     - bare ATOMS — symbols (incl. `do`/`if`), numbers, strings,
;;       keywords, booleans, nil, chars, AND a bare `=>`/`⇒` echo token
;;       (a symbol): LLM prose tokenized by the reader, or a fabricated
;;       REPL echo arrow;
;;     - DATA LITERALS — a top-level `{…}` / `[…]` / `#{…}` (`:map` /
;;       `:vector` / `:set`): a fabricated `=> {:role :admin}` echo
;;       self-evaluates into a real `result/<id>` via the stash/record
;;       path (#52) — the exact bug. The READER groups a whole `(…)` as
;;       one top-level form regardless of indentation, so a MULTILINE
;;       `(db/transact!\n  {…})` is one `:list` form (evaluates) while a
;;       bare multiline `{…}` is one `:map` datum (prose);
;;     - TAGGED LITERALS — `#inst`, `#uuid`, `#js`, `#?(…)` (tag
;;       `:reader-macro`): these sexpr to a SEQ (`(read-string "#inst …")`)
;;       so `seq?` alone would mis-evaluate them; the `:reader-macro`
;;       TAG is the discriminator, so the prose decision is made at the
;;       token level (`prose-token?`) where the tag is in hand.
;;
;; Prose is DROPPED — NOT echoed back as a `;;` comment-preamble. That
;; echo was the `;;`-imitation trap: agents saw their bare prose
;; reflected as `;;` and began writing `;;` when they meant to use data.
;; The ONE exception is a demoted DATA LITERAL, which emits a single
;; concise WARNING (see `demoted-literal-warning`) so the agent learns
;; to wrap a value it means to run.
;; ============================================================

(def ^:private inline-backtick-tags
  "rewrite-clj tags whose tokens sexpr to a SEQ (so `seq?` alone would
   EVALUATE them) but are ALWAYS inline-backtick prose at the agent REPL,
   never intentional macro-quoting — a leading `` ` ``/`~`/`~@`. Treated as
   prose to stop the inline-backtick cascade (see the classification
   comment above)."
  #{:syntax-quote :unquote :unquote-splicing})

(defn- result-ref-symbol?
  "True if `form` is a bare `result/<id>` symbol — a RE-REFERENCE to a
   previously-stashed eval value (the documented value-reuse surface,
   `seon.eval/result-var-ref?`). A bare symbol is normally prose, but a
   `result/<id>` symbol SELF-EVALUATES into its stashed value, so it is a
   genuine FORM (evaluated), NOT prose — without this, an agent that
   refers back to a prior result (`result/abc…` on its own line) gets
   nothing (#39). The namespace must be EXACTLY `result` (matching the
   eval-side detector); a digit-leading id (`result/0xO-…`) is an invalid
   token that THROWS at read time and never reaches here — it stays a
   `:read` failure, unchanged."
  [form]
  (and (symbol? form) (= "result" (namespace form))))

(defn- prose-token?
  "True if a parsed top-level token is PROSE (not evaluated). `form` is
   the read sexpr; `tag` is the rewrite-clj node tag. The form/prose cut
   is `(seq? form)` — a list/seq evaluates — with THREE refinements: a
   `:reader-macro` tagged literal (`#inst`/`#uuid`/`#js`/`#?(…)`) sexprs
   to a seq (`(read-string …)`) but is a DATUM, not a form, so it is
   prose; an inline-backtick reader-macro
   ([[inline-backtick-tags]] — `` `(…) ``/`~x`/`~@x`) likewise sexprs to a
   seq but is inline prose, not code; and a bare `result/<id>` symbol
   ([[result-ref-symbol?]]) is a stash RE-REFERENCE that self-evaluates,
   so it is a FORM (#39) despite being a bare symbol. Everything else
   that is not a seq (scalars, other symbols, `{…}`/`[…]`/`#{…}`) is
   prose."
  [form tag]
  (and (not (result-ref-symbol? form))
       (or (= tag :reader-macro)
           (contains? inline-backtick-tags tag)
           (not (seq? form)))))

(defn- data-literal?
  "True if `form` is a top-level DATA LITERAL — a map, vector, or set.
   These are the demotions that warrant the one-line warning (a strong
   signal the agent meant to USE a value): a bare `{…}`/`[…]`/`#{…}` is
   read as a NOTE, not run. Concrete-type checks only — `map?`/`vector?`/
   `set?`, never `coll?`/`sequential?`."
  [form]
  (or (map? form) (vector? form) (set? form)))

(defn- literal-shape
  "A short structural description of a demoted data literal for the
   warning (`3-key map`, `vector`, `set`). Concrete types only."
  [form]
  (cond
    (map? form)    (str (count form) "-key map")
    (vector? form) "vector"
    (set? form)    "set"
    :else          "value"))

(defn- demoted-literal-warning
  "The ONE concise, idempotent warning fired when a top-level DATA
   LITERAL (map/vector/set) is demoted to prose. A pure function of the
   demoted `form` — recomputed every parse, stored nowhere as a flag
   (reactive-context: when the agent stops typing bare literals the
   warning stops appearing). Leads with `⚠` (the renderer preserves the
   glyph). Tells the agent the cut (only `(`-forms evaluate) and the fix
   (wrap the value)."
  [form]
  (str "⚠ Read as a note, not code: " (literal-shape form) ". Only forms "
       "beginning with ( are evaluated — bare maps/vectors/sets are treated "
       "as text. To use a value, wrap it in a form: (def x …) or "
       "(identity …)."))

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
  "Collapse accumulated comment-preamble strings into a single narration
   string. Empty input → empty string (NOT nil, so downstream
   destructuring is predictable)."
  [parts]
  (str/trim (str/join "\n" parts)))

;; ============================================================
;; Error recovery — when one form fails to parse, advance to the next
;; column-0 open-delim and continue from there. The bad span becomes
;; a :read-failure entry.
;; ============================================================

(defn- backup-over-comment-block
  "Move a column-0 recovery `anchor` (a genuine next-form `(`) back to the
   start of the contiguous block of column-0 `;`-comment lines immediately
   above it, never before `floor`. For an `:eof` (unclosed) failure those
   comments are the preamble of the recovered next form, so they must
   re-parse as its narration instead of being swallowed into the broken
   `:read` span. A non-comment line (e.g. an INDENTED inner call) halts the
   walk — it stays inside the broken span and never re-parses as a form."
  [text floor anchor]
  (loop [start anchor]
    (if (<= start floor)
      start
      ;; text[start-1] is the '\n' just above the anchor line; the previous
      ;; line begins right after the '\n' that precedes index (start-2).
      (let [prev-nl    (when (>= (- start 2) 0)
                         (str/last-index-of text "\n" (- start 2)))
            line-start (if (and prev-nl (>= prev-nl floor)) (inc prev-nl) floor)]
        (if (and (>= line-start floor)
                 (< line-start start)
                 (str/starts-with? (subs text line-start) ";"))
          (recur line-start)
          start)))))

(defn- find-recovery-point
  "When parsing fails starting at `offset`, return the offset to resume
   from (or `(count text)` for EOF). `error-kind` (from
   `classify-read-error`) gates which anchors count as a real new form.

   :eof (UNCLOSED form) — everything after `offset` is INSIDE the open
   delimiter until a genuine new top-level form, so an interior `;` is NOT
   a boundary (anchoring there split the unclosed form and let an inner
   call leak out as an EXECUTING top-level `:form` — silent partial
   execution of broken code). Anchor ONLY on the next column-0 `(`, then
   back up over the `;;` preamble directly above it so narration still
   attaches; with NO column-0 `(` ahead the whole tail is inside the
   unclosed form — recover at EOF, keeping it ONE `:read` whose indented
   inner calls never run.

   non-:eof (LOCALIZED failure) — keep the original column-0 `(` OR `;`
   anchor. The `;` anchor is intentional (\"intent attaches to the next
   form\"): `(broken\\n;; intent\\n(good)` recovers on the `;;` line so the
   intent narrates `(good)`.

   PRONG 2 (eval-segmenter): the anchor set is `(`/`;` ONLY — NOT `[`/`{`.
   Under forms-and-prose-only only a `(`-list is a runnable FORM; a
   column-0 `{`/`[` is almost always the BODY of the broken form above (the
   inner maps/vectors of an unbalanced `(db/transact! [ {…} {…} ])`).
   Anchoring on them shredded one broken block into bad-head + N
   demoted-map `:comment`s + an orphan closer; restricting to `(`/`;` keeps
   the broken block as ONE honest `:read` span.

   Documented trade-off: a GENUINE bare top-level `{…}` written immediately
   after a broken form is absorbed into the error span instead of emitting
   its own demotion warning. Acceptable — a bare top-level map is
   non-evaluated prose anyway and the agent's real signal is \"fix the
   broken form above\" (simple-core-over-edge-cases)."
  [text offset error-kind]
  (let [tail      (subs text offset)
        candidate (if (= error-kind :eof)
                    (if-let [m (re-find #"\n\(" tail)]
                      (backup-over-comment-block
                        text offset (+ offset (str/index-of tail m) 1))  ; +1 lands on the `(`
                      (count text))
                    (if-let [m (re-find #"\n[;\(]" tail)]
                      (+ offset (str/index-of tail m) 1)  ; +1 to land on the anchor
                      (count text)))]
    ;; STRICT-ADVANCE GUARD — a recovery hop MUST move past `offset` or the
    ;; outer parse-forms loop recurs on the same span forever. The only branch
    ;; that can fail to advance is the :eof `backup-over-comment-block` path:
    ;; when the anchor's contiguous `;`-comment block backs all the way down to
    ;; `offset` itself, backup returns the floor (== offset). Today that needs
    ;; `offset` to start with `;`, which parse-forms never feeds here (a `;`
    ;; line reads as a clean comment token, not an error) — but the parser is
    ;; load-bearing for the diffusion oracle, so we enforce the invariant at the
    ;; source rather than rely on that argument holding as the recovery anchors
    ;; evolve. A non-advancing candidate bails recovery to EOF: the remaining
    ;; tail becomes ONE honest :read span and the loop terminates.
    (if (<= candidate offset)
      (count text)
      candidate)))

(defn- next-newline-recovery
  "Recovery point for a PROSE-classified failing span (A.1): the offset
   just after the NEXT newline at/after `offset`, or `(count text)` if
   none. Narrowing prose recovery to one line means a single stray
   token (`80s`, `to:`, `detail:`) drops ONE line — the next line gets a
   fresh parse attempt — instead of `find-recovery-point` swallowing the
   whole multi-line paragraph into one `:read` failure."
  [text offset]
  (let [tail (subs text offset)
        nl   (str/index-of tail "\n")]
    (if nl
      (+ offset nl 1)
      (count text))))

;; ============================================================
;; Prose-vs-code classification (A.1) — a reader THROW on a token like
;; `80s`, `to:`, `detail:`, `v1.0` reaches the `:error` branch before any
;; sexpr exists, so `prose-token?` (which only classifies tokens that
;; READ cleanly) never sees it. Without classification the whole prose
;; paragraph is recorded as one failed eval the agent must explain. The
;; rule below distinguishes that prose from a genuinely broken CALL FORM.
;;
;; Under forms-and-prose-only, the ONLY shape that signals "the agent
;; meant a runnable form" is a LIST `(`. A throwing span that starts with
;; `{`/`[` is a broken data literal — and data literals are PROSE — so it
;; is DROPPED, not recorded as a `:read` failure (matching the clean-read
;; data-literal demotion).
;;
;; The other prose-side THROW is an inline-backtick span: an agent writes
;; markdown narration into the eval channel like `` `:seon.db/id` shape ``
;; or `` `: they're dynamic functions… ``. The leading `` ` `` makes rewrite-clj
;; throw `Invalid character: \` … while reading keyword` — a message
;; `prose-error-re` deliberately does NOT match (broadening it to
;; "character" would over-drop genuinely broken code). The precise,
;; intent-matching signal is the LEADING BACKTICK itself
;; (`backtick-prose-at-start?`): at the agent REPL a leading `` ` ``/`~`/`~@`
;; is ALWAYS inline narration, never intentional macro-quoting (the same
;; intent that makes `inline-backtick-tags` prose on the clean-read side),
;; so a throwing backtick-led span is DROPPED as prose.
;; ============================================================

(def ^:private prose-error-re
  ;; The reader messages emitted for prose tokens (`80s` → "Invalid
  ;; number: 80s.", `to:` → "Invalid symbol: to:.", etc.). `^`-anchored;
  ;; the trailing `.` rewrite-clj appends doesn't affect the prefix match.
  #"^Invalid (number|symbol|keyword|token)")

(defn- opener-at-start?
  "True when the TRIMMED `span` begins with a LIST opener `(` — i.e. the
   failing span LOOKS like a runnable form the agent intended (a genuinely
   broken `(+ 1 3x)`), not inline-code prose (\"I'll use (subs …) to
   format\" — opener mid-sentence) and not a broken data literal
   (`{:a 3x}` — a datum, which is prose).

   Why `(` ONLY (not `{`/`[`): under forms-and-prose-only only a list
   evaluates, so only a list start signals intended code. A `{`/`[` start
   is a data literal → prose → dropped, never a `:read` failure.

   Why START, not anywhere: real LLM narration quotes code inline. If the
   check were opener-ANYWHERE, that narration would be misclassified as
   broken code and recorded as a `:read` failure — the inverse of the bug
   we are fixing. Requiring `(` at the start of the trimmed span keeps
   `(+ 1 3x)` (opener at start) as broken code while letting \"I'll use
   (subs …)\" (opener mid-line) classify as prose."
  [span]
  (str/starts-with? (str/triml (str span)) "("))

(defn- backtick-prose-at-start?
  "True when the TRIMMED `span` begins with an inline-backtick reader macro
   — `` ` `` (syntax-quote), `~` (unquote), or `~@` (unquote-splicing). At the
   agent REPL a leading backtick is ALWAYS inline markdown narration
   (`` `:seon.db/id` shape `` — quoting a keyword in prose), never intentional
   macro-quoting, so a span that THROWS while starting with one is PROSE — it
   is DROPPED, not recorded as a `:read`. The clean-read counterpart lives in
   [[prose-token?]]/[[inline-backtick-tags]]; this is the THROW-side mirror,
   because a leading `` ` `` makes rewrite-clj throw `Invalid character: \\``
   while reading keyword` (which `prose-error-re` deliberately does not match —
   the leading-backtick check is the precise, intent-matching signal). The
   `~`/`~@` cases read cleanly and normally reach [[prose-token?]], but a
   throwing `~`-led span is narration too — drop it for the same reason."
  [span]
  (let [s (str/triml (str span))]
    (or (str/starts-with? s "`")
        (str/starts-with? s "~"))))

(defn- prose-failure?
  "True when a failing span should be DROPPED rather than recorded as a
   `:read` failure. Two intent-matching signals, either suffices:

     - the trimmed span STARTS with an inline-backtick reader macro
       (`` ` ``/`~`/`~@`) — a leading backtick at the agent REPL is ALWAYS
       markdown narration, so a throwing backtick-led span is prose
       ([[backtick-prose-at-start?]]); OR
     - the reader error matches the prose-token signature AND the span has
       no LIST opener `(` at the START of its trimmed first line (the
       opener-at-START rule).

   `span` is the bad text from `offset` to the narrowed recovery point."
  [error span]
  (or (backtick-prose-at-start? span)
      (and (re-find prose-error-re (str error))
           (not (opener-at-start? span)))))

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
   Returns one of (`::` = this ns — private scanner tokens, never the
   public `:seon.repl/*` entry envelope):

     {::kind :form  ::source <byte-faithful> ::form <sexpr> ::tag <kw> ::end <int>}
     {::kind :comment    ::text <stripped>                       ::end <int>}
     {::kind :whitespace                                         ::end <int>}
     {::kind :error      ::error <message>}                      ; caller recovers

   `::tag` is the rewrite-clj node tag (`:list`, `:map`, `:reader-macro`,
   …) — `prose-token?` needs it to tell a `#inst` datum (sexprs to a seq
   yet is prose) from a genuine list/reader-macro form."
  [text offset]
  (try
    (let [chunk (subs text offset)
          node  (rcp/parse-string chunk)
          src   (rcn/string node)
          end   (+ offset (count src))
          tag   (rcn/tag node)]
      (cond
        (= tag :comment)
        {::kind :comment ::text (comment-text node) ::end end}

        ;; :comma matters for prose: "24 minutes, felt good" — the
        ;; comma is Clojure whitespace, but rewrite-clj tags it
        ;; :comma; without it here the sexpr call throws and the
        ;; comma poisons the span up to the next recovery anchor.
        ;; :uneval is a `#_` discard — the reader IGNORES the discarded
        ;; form (discard family); its node has no sexpr (rcn/sexpr throws
        ;; "unsupported operation"), so without this branch a top-level
        ;; `#_foo` falsely reads as a :read failure. Drop it like
        ;; whitespace (a bare top-level discard carries nothing to eval).
        (#{:whitespace :newline :comma :uneval} tag)
        {::kind :whitespace ::end end}

        :else
        {::kind :form ::source src ::form (rcn/sexpr node) ::tag tag ::end end}))
    (catch #?(:clj Exception :cljs :default) e
      {::kind :error ::error (#?(:clj .getMessage :cljs .-message) e)})))

;; ============================================================
;; Read-failure classification
;; ============================================================

(defn- classify-read-error
  "Map a rewrite-clj parse-failure message to an error-kind keyword the
   re-noise / repair layer dispatches on. The kinds, with their recovery
   disposition (SAFE = mechanically completable, intent-preserving;
   UNSAFE = needs the agent / a lookup — never silently rewritten):

     :eof                 — unclosed delimiter/string/regex (EOF family).
                            SAFE: parinferish closes it (`seon.repair`).
     :unmatched-delimiter — a stray closer. SAFE: drop the surplus.
     :odd-map             — a map literal with an odd form count
                            (`{:a 1 :b}`). UNSAFE: a value is MISSING —
                            guessing it is guessing intent.
     :bad-metadata        — `^x` where x isn't a map/kw/sym/string. UNSAFE.
     :invalid-token       — an unreadable token (`3x`, a lone `:`). UNSAFE:
                            the natural hook for an embedding / source lookup.
     :read                — anything else (generic broken span). UNSAFE.

   Matched on rewrite-clj's FIXED wording, case-folded, not the whole
   message. Two reasons, both found by reading rewrite-clj's source:
     - CASING varies — `parser/core.cljc` throws `Unexpected EOF.` but
       `reader.cljc` throws a bare lowercase `unexpected EOF`, so a
       capital-only match misses the low-level read-helper path.
     - STAGE varies — `:eof`/`:unmatched-delimiter` come from rewrite-clj
       parse-stage (`reader/throw-reader`, stable wording); `:odd-map` and
       `:invalid-token`/`:bad-metadata` surface at SEXPR-stage and are host
       messages. Verified (adversarial review): odd-map is `No value
       supplied for key` on BOTH CLJS and JVM — they do NOT diverge — so a
       single match suffices and is CLJC-portable.
   We match the fixed phrases (not a bare `eof`/`invalid`) so an
   interpolated user token — `Invalid symbol: my-eof` — can't collide. A
   wording drift falls through to `:read` — never throws, so an
   unrecognized message degrades to the generic kind rather than breaking
   the parse."
  [msg]
  ;; `(str msg)` guards a nil exception message (some throws carry none) —
  ;; lower-case on nil would NPE/TypeError; an empty string falls through to
  ;; the generic `:read` kind, which is the correct degrade.
  (let [m (str/lower-case (str msg))]
    (cond
      (or (str/includes? m "unexpected eof")
          (str/includes? m "eof while reading"))     :eof
      (str/includes? m "unmatched delimiter")        :unmatched-delimiter
      (str/includes? m "no value supplied for key")  :odd-map
      (str/includes? m "metadata")                   :bad-metadata
      (str/includes? m "invalid")                    :invalid-token
      :else                                          :read)))

(defn- closer-only?
  "True iff a recovered bad span is ONLY whitespace + closing delimiters
   (`)`/`]`/`}`). Such a span is an orphan-delimiter RECOVERY ARTIFACT: an
   unbalanced form upstream already shed it and is itself recorded as a
   :read failure, so re-emitting the lone closer is duplicate noise (one
   broken block → a wall of `}`/`]` rows). Never matches a real form — a
   form leads with `(`/`[`/`{` or a token — so dropping a closer-only span
   can't hide a genuine failure. Runs on an already-FAILED :read span,
   never on a valid form, so a `}` inside a good form's string literal is
   never reached."
  [s]
  (boolean (re-matches #"[\s)\]}]+" s)))

;; ============================================================
;; Public surface
;; ============================================================

(defn form-source-at
  "Byte-faithful source of the SINGLE top-level form that begins at the
   first `(` at-or-after `offset` in `text`. Reads exactly one rewrite-clj
   node (same one-node parse as [[try-parse-one-token]] / the program-graph
   source capture in `seon.client`), so parens inside CHARACTER literals
   (`\\)`), REGEX literals (`#\"…)…\"`), and strings are balanced correctly —
   unlike a raw `(`/`)` depth counter, which truncates such a form. Any
   leading content before that first `(` is DROPPED (so a `defn` whose
   `:line` points at the indented inner form of a `#?(:cljs …)` reader
   conditional still yields source starting at `(defn`).

   Returns:

     - the form's exact source string on success;
     - the from-first-`(`-to-EOF substring when the chunk is genuinely
       UNBALANCED (rewrite-clj throws `Unexpected EOF.`) — the
       truncated-source fallback the source-capture callers rely on;
     - nil when no `(` opens at-or-after `offset` before EOF."
  {:malli/schema [:=> [:cat :string :int] [:maybe :string]]}
  [text offset]
  (when-some [idx (str/index-of text "(" offset)]
    (try
      (rcn/string (rcp/parse-string (subs text idx)))
      (catch #?(:clj Exception :cljs :default) _
        ;; Unbalanced-to-EOF (or otherwise unparseable): fall back to the
        ;; from-`(` substring so a truncated tail still surfaces SOMETHING,
        ;; matching the prior hand-rolled scanner's EOF fallback.
        (subs text idx)))))

(defn first-top-level-close
  "Char offset JUST PAST the first point where the running delimiter depth
   returns to 0 after at least one `(`/`[`/`{` opened — nil when no top-level
   group has closed yet.

   The cheap STREAMING gate (`:stream`): run this on the text
   accumulated so far after each stream delta; while it returns nil the
   model is still mid-form, so keep consuming. When it returns an offset a
   top-level grouping just closed — the caller runs the real [[parse-forms]]
   ONCE on the accumulated prefix to CONFIRM a genuine evaluable `:form`
   (a bare `{…}`/`[…]` closes at depth 0 too but demotes to prose), then
   aborts the stream and evaluates that single form.

   String / char-literal (`\\(`) / line-comment (`;`) / regex-literal
   (`#\"…)…\"`) aware — the SAME balancing rules the `#'arglists-from-source`
   scanner in `seon.client` encodes, so a `)` inside a string or comment
   never falses the gate. NOT a parser: it only tracks combined
   `()[]{}` depth; semantic confirmation is [[parse-forms]]'s job."
  {:malli/schema [:=> [:cat :string] [:maybe :int]]}
  [text]
  (let [n (count text)]
    (loop [i 0 depth 0 opened? false in-str? false esc? false]
      (if (>= i n)
        nil
        (let [c (nth text i)]
          (cond
            esc?                   (recur (inc i) depth opened? in-str? false)
            (and in-str? (= c \\)) (recur (inc i) depth opened? in-str? true)
            in-str?                (recur (inc i) depth opened? (not (= c \")) false)
            (= c \")               (recur (inc i) depth opened? true false)
            (= c \\)               (recur (+ i 2) depth opened? in-str? false)
            (= c \;)               (let [eol (loop [j i]
                                               (if (or (>= j n) (= (nth text j) \newline))
                                                 j (recur (inc j))))]
                                     (recur eol depth opened? in-str? false))
            (or (= c \() (= c \[) (= c \{))
            (recur (inc i) (inc depth) true in-str? false)
            (or (= c \)) (= c \]) (= c \}))
            (let [d (dec depth)]
              (if (and opened? (<= d 0))
                (inc i)
                (recur (inc i) (max 0 d) opened? in-str? false)))
            :else (recur (inc i) depth opened? in-str? false)))))))

(defn read-forms
  "Every top-level form in `source` as read sexprs; nil on a read error.

   The ONE whole-source structural read (rewrite-clj — the same parse
   stack as [[parse-forms]]) for callers that classify a source string
   by its read forms (the eval tee's `defn-form?` gate, the declared
   read-set walk, the failed-def guard). Whitespace, comments, and `#_`
   discards are dropped; a read failure returns nil so classification
   gates FAIL CLOSED (a broken source yields zero forms).

   AUTO-RESOLVED keywords (`::kw` / `::alias/kw`) never throw — the C37
   flywheel gap: `cljs.tools.reader` has NO current-ns hook at all
   (`::kw` is 'Invalid token' on every CLJS build, live-proven
   2026-07-03), so a defn whose body used `::` keywords read as nil,
   failed the tee gate, and silently skipped persistence/instrument/
   resume. rewrite-clj's `:auto-resolve` closes it:

     - `opts` `:seon.repl/current-ns` (a symbol) resolves `::kw`;
       `:seon.repl/aliases` (`{alias-sym → target-ns-sym}`) resolves
       `::alias/kw`. Thread these from the caller (the CLJS eval tee
       derives them from the analyzer) — this ns stays bare-babashka
       loadable, so it never reaches into seon-only state itself.
     - ABSENT context degrades to rewrite-clj's VISIBLE placeholders
       (`:?_current-ns_?/kw`, `:??_alias_??/kw`) — structural callers
       (form counts, head symbols) are unaffected, and a value-consumer
       can spot the `?`-prefixed namespace and drop it."
  [source & [{current-ns :seon.repl/current-ns aliases :seon.repl/aliases}]]
  (try
    (let [opts {:auto-resolve
                (fn [alias]
                  (if (= :current alias)
                    (or current-ns '?_current-ns_?)
                    (get aliases alias (symbol (str "??_" alias "_??")))))}]
      (into []
            (comp (filter rcn/sexpr-able?)
                  (map #(rcn/sexpr % opts)))
            (rcn/children (rcp/parse-string-all (str source)))))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- parse-forms*
  "Read `text` top-to-bottom, pairing each evaluable form with the `;;`
   comment-preamble that precedes it. See the namespace docstring for the
   entry-shape contract.

   FORMS-AND-PROSE-ONLY (#50/#52): a top-level read form is a
   `:seon.repl/kind :form` entry (EVALUATED) iff it is a LIST/SEQ — `(…)` and the
   reader-macros that read as seqs (`@x`/`'x`/`#(…)`/`` `(…) ``/`#'x`) —
   or a bare `result/<id>` stash RE-REFERENCE symbol (#39, which
   self-evaluates into its prior value). EVERYTHING else is prose and is
   DROPPED (NOT echoed back as a `;;`
   line — that echo was the `;;`-imitation trap). Prose covers: bare
   atoms (symbols incl. `do`/`if`, numbers, strings, keywords, a bare
   `=>`/`⇒`), TAGGED literals (`#inst`/`#uuid`/`#js`/`#?(…)`), an A.1
   unreadable prose token (`80s`/`to:`), and — the load-bearing #52 fix —
   a top-level DATA LITERAL (`{…}`/`[…]`/`#{…}`). A demoted data literal
   does NOT silently vanish: it emits a `:seon.repl/kind :comment` entry whose
   narration is the one-line `demoted-literal-warning` (a strong signal
   the agent meant to USE a value). The reader groups a whole `(…)` as
   one top-level form regardless of indentation, so a multiline
   `(db/transact!\n  {…})` is ONE evaluated form while a bare multiline
   `{…}` is ONE demoted datum.

   Real `;;` comments are the taught reasoning channel and still attach
   as `:seon.repl/narration` to the following form (or emit as a trailing
   `:seon.repl/kind :comment` entry). Dropped prose between a comment and
   its form does NOT break that attachment — `;; intent\\nokay\\n(foo)`
   attaches `intent` to `(foo)` and drops `okay`.

   Read errors do NOT halt the parse: a genuinely broken FORM (opener at
   the start of its span — `(+ 1 3x)`) becomes a `:seon.repl/kind :read`
   / `:seon.repl/ok? false` entry (its failure carried as the ONE
   `:seon/error` value) and parsing continues; a prose-token throw
   (`80s`) is dropped.

   Markdown code-fence lines (` ``` `, ` ```clojure `, ` ~~~ `, …)
   are stripped before reading — see `strip-code-fences`.

   `:strip-fences?` (opts, default true) controls that strip. Pass
   `false` to keep every `:seon.repl/span [s e]` an ABSOLUTE char offset into the
   EXACT input string (no fence-line removal shifting later spans). The
   closed-loop renoise path needs this: the diffusion worker's
   `offset_map` indexes the RAW `code_buffer_text`, so its parser spans must
   share that basis — see
   `docs/prds/diffusion-dynamic-context/research/closed-loop-span-alignment-2026-06-28.md`."
  [text & [{:keys [strip-fences?] :or {strip-fences? true}}]]
  (let [text (if strip-fences? (strip-code-fences text) text)]
    ;; `pending` accumulates REAL `;;` comment lines (`;` stripped) — the
    ;; taught reasoning preamble. Bare prose is DROPPED, not accumulated,
    ;; so there is no prose-run to track; `pending` carries THROUGH
    ;; dropped prose so a `;;` comment still attaches to the next form.
    (loop [offset  0
           pending []
           out     []]
      (if (>= offset (count text))
        ;; Trailing `;;` comment lines with no following form survive as a
        ;; comment-only entry; dropped prose leaves nothing behind.
        (if (seq pending)
          (conj out {:seon.repl/kind      :comment
                     :seon.repl/narration (join-narration pending)})
          out)
        (let [token (try-parse-one-token text offset)]
          (case (::kind token)
            :whitespace
            (recur (::end token) pending out)

            :comment
            (recur (::end token) (conj pending (::text token)) out)

            :form
            (cond
              ;; A genuine form (list/seq, not a tagged literal) — emit,
              ;; carrying any accumulated `;;` preamble as narration.
              (not (prose-token? (::form token) (::tag token)))
              (recur (::end token)
                     []
                     (conj out {:seon.repl/kind      :form
                                :seon.repl/narration (join-narration pending)
                                :seon.repl/source    (::source token)
                                :seon.repl/form      (::form token)
                                ;; ABSOLUTE `[start end)` char span of this
                                ;; form in `text` — the SAME basis the `:read`
                                ;; entry carries, so the closed-loop renoise /
                                ;; oracle layer has code-buffer-aligned spans for
                                ;; the GOOD forms (clamp-to-HOLD) as well as
                                ;; the broken ones (renoise). `offset` points
                                ;; exactly at the form start (leading
                                ;; whitespace was consumed as prior tokens).
                                :seon.repl/span      [offset (::end token)]}))

              ;; A demoted DATA LITERAL (`{…}`/`[…]`/`#{…}`) — DROP the
              ;; eval, but emit ONE warning so the agent learns to wrap a
              ;; value it means to run. Any `;;` preamble rides along.
              (data-literal? (::form token))
              (recur (::end token)
                     []
                     (conj out {:seon.repl/kind :comment
                                :seon.repl/narration
                                (join-narration
                                  (conj pending
                                        (demoted-literal-warning (::form token))))}))

              ;; Ordinary prose tokenized as an atom / tagged literal —
              ;; DROP it; carry `pending` so a real comment still lands.
              :else
              (recur (::end token) pending out))

            :error
            ;; PROSE vs BROKEN CODE (A.1). Classify on the narrowed
            ;; (next-line) span so one stray token never drags in the
            ;; following lines.
            (let [nl-recovery (next-newline-recovery text offset)
                  prose-span  (subs text offset nl-recovery)]
              (if (prose-failure? (::error token) prose-span)
                ;; Prose tokenized as an invalid token — DROP it; recover
                ;; at the next newline; carry `pending`.
                (recur nl-recovery pending out)
                ;; Broken code — record a :read failure; recover at the
                ;; next genuine top-level boundary (error-kind-aware: an
                ;; :eof unclosed form never splits at an interior `;`).
                (let [error-kind (classify-read-error (::error token))
                      recovery   (find-recovery-point text offset error-kind)
                      bad-span   (subs text offset recovery)]
                  (if (closer-only? bad-span)
                    ;; PRONG 1: a pure-closer span is recovery COLLATERAL
                    ;; (the orphan delimiter an unbalanced form upstream
                    ;; already shed). DROP it — the real error is the
                    ;; bad-head :read already recorded — and carry pending
                    ;; so a real `;;` preamble still lands on the next form.
                    (recur recovery pending out)
                    (recur recovery
                           []
                           (conj out {:seon.repl/kind      :read
                                      :seon.repl/ok?       false
                                      :seon.repl/narration (join-narration pending)
                                      :seon.repl/source    bad-span
                                      ;; The ONE :seon/error value shape —
                                      ;; classified kind + the parser message
                                      ;; (same hand-built form as
                                      ;; seon.worker-eval/classify-error).
                                      :seon/error
                                      {:seon.error/kind    error-kind
                                       :seon.error/message (str (::error token))}
                                      :seon.repl/span      [offset recovery]}))))))))))))

(defn parse-forms
  "Parse `text` into structured entries, `#code` heredocs included.

   Runs the `#code` heredoc pre-pass first (see the ns docstring) so a raw
   foreign-source payload needs ZERO Clojure escaping.

   `#code/<lang> <<SENTINEL … SENTINEL` regions are rewritten to
   machine-escaped valid-EDN `{:seon.code/lang … :seon.code/text …}` map
   literals that the reader reads natively (THE MACHINE escapes, never the
   agent). Used nested in a call form —
   `(fs/replace! {::find #code/py <<PY…PY})` — the block map lands as the
   `::find` value; a bare top-level `#code` demotes to prose like any
   other top-level `{…}` (its value is meant to be USED, not left inert).

   Entries stay on the ORIGINAL basis: `:seon.repl/source` is byte-faithful
   to what the agent typed and `:seon.repl/span` is an absolute offset into
   `text`; an entry ALSO carries `:seon.repl/eval-source` — the
   cljs-readable rewritten string `eval-batch!` evaluates — whenever that
   rewrite differs from the byte-faithful original, i.e. its source region
   was rewritten by the heredoc pre-pass. That is the common heredoc-form
   case, but it also fires for an entry whose region shifted adjacent to a
   malformed/unterminated `#code` marker (no `seon.code` block of its own).
   A malformed
   (no `<<`) or unterminated (no closer) `#code` surfaces as a
   `:seon.repl/kind :read` entry NAMING the awaited sentinel, never
   silently dropped.

   Delegates the token loop to [[parse-forms*]] on the rewritten text
   (`:strip-fences?` false — this fn already stripped), then rebases every
   entry onto the original text via the segment map."
  [text & [{:keys [strip-fences?] :or {strip-fences? true}}]]
  (let [orig     (if strip-fences? (strip-code-fences text) text)
        pieces   (scan-heredoc-pieces orig)]
    (if-not pieces
      ;; No `#code/` — identity fast-path: the loop reads `orig` directly
      ;; (spans/source already on the original basis).
      (parse-forms* orig {:strip-fences? false})
      (let [{::keys [rewritten segments markers]} (assemble orig pieces)
            base (parse-forms* rewritten {:strip-fences? false})]
        (heredoc-remap orig (seq segments) (seq markers) base)))))
