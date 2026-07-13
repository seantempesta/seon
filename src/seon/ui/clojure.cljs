(ns seon.ui.clojure
  "Server-side Clojure syntax highlighter — `clj->hiccup`. A pure CLJS leaf,
   the exact sibling of `seon.ui.markdown/md->hiccup`: source string in,
   `[:pre [:code …]]` hiccup out, no client JS pass.

   Why server-side (not the CDN highlight.js the old `/debug` shell loads):
   the new agent view's shim ships ONLY `datastar.js` — no hljs — so eval
   source there is unhighlighted, and any client pass races idiomorph after
   every SSE morph. A pure server tokenizer is morph-safe by construction,
   renders under `?t=` time-travel and `curl`, and keeps the agent view's JS
   = datastar-only.

   It REUSES the existing `.hljs-*` palette (the same classes the debug shell
   styles, `seon.web.debug` page-style-css) so the theme is shared — no new
   palette. Tokens map onto those classes:

     comment `;…`            → .hljs-comment
     \"string\"               → .hljs-string
     :keyword                → .hljs-symbol
     nil / true / false      → .hljs-literal
     def/defn/let/fn/if/…    → .hljs-keyword
     numbers                 → .hljs-number
     symbols, parens, ws     → plain text (default colour)

   ROBUST by contract: agents emit partial / malformed forms (a stray `}`, an
   unterminated string). The tokenizer never throws — an unterminated string
   degrades to a string token to EOF, and any unexpected failure degrades the
   whole render to a plain `[:pre [:code …]]` with the raw source."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

;; ============================================================
;; Token classification — which `.hljs-*` class (if any) a word gets.
;; ============================================================

(def ^:private special-forms
  "Clojure special forms + the core defining/binding macros worth amber
   emphasis. Not exhaustive — a highlighter, not a reader; everything else
   is a plain symbol (no span, default colour)."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
    "defrecord" "deftype" "defonce" "definline" "fn" "fn*" "let" "let*"
    "letfn" "loop" "recur" "if" "if-let" "if-some" "if-not" "when" "when-let"
    "when-some" "when-not" "when-first" "cond" "condp" "case" "do" "doseq"
    "dotimes" "for" "while" "quote" "var" "ns" "require" "import" "use"
    "throw" "try" "catch" "finally" "new" "set!" "."  ".."  "->" "->>"
    "as->" "some->" "some->>" "cond->" "cond->>" "binding" "locking"
    "monitor-enter" "monitor-exit"})

(def ^:private literals #{"nil" "true" "false"})

(defn- number-word?
  "True when `w` reads as a Clojure number literal — int, float, ratio,
   radix, hex, with an optional leading sign. A `+`/`-` ALONE is the
   arithmetic symbol, not a number."
  [w]
  (boolean (re-matches #"[+-]?(\d[\d._]*([eE][+-]?\d+)?|\d+/\d+|0[xX][0-9a-fA-F]+|\d+r[0-9a-zA-Z]+)N?M?" w)))

(defn- word-class
  "The `.hljs-*` class for a finished word token, or nil for a plain symbol
   (rendered as default-colour text, no span)."
  [w]
  (cond
    (str/starts-with? w ":") "hljs-symbol"
    (contains? literals w)   "hljs-literal"
    (contains? special-forms w) "hljs-keyword"
    (number-word? w)         "hljs-number"
    :else                    nil))

;; ============================================================
;; Tokenizer — one pass over the source. Boundary chars terminate a word;
;; comments / strings / char-literals read with their own rules so a `;`
;; inside a string (or a `\"` char literal) can't derail the scan.
;; ============================================================

(def ^:private boundary
  "Chars that END a bare word (and that are themselves rendered plain)."
  #{\space \tab \newline \return \, \( \) \[ \] \{ \} \" \; \' \` \^ \@ \~})

(defn- boundary? [c] (or (nil? c) (contains? boundary c)))

(defn- span [class text] [:span {:class class} text])

(defn- read-string-token
  "From the opening quote at `i`, consume a `\"…\"` string (honouring `\\\"`
   escapes). Returns `[end-index text]`. An unterminated string runs to EOF
   (degrade, never throw)."
  [^string src n i]
  (loop [j (inc i)]
    (if (>= j n)
      [n (subs src i n)]
      (let [c (.charAt src j)]
        (cond
          (= c \\) (recur (+ j 2))               ; skip the escaped char
          (= c \") [(inc j) (subs src i (inc j))]
          :else    (recur (inc j)))))))

(defn- read-comment-token
  "From `;` at `i`, consume to end of line (exclusive of the newline).
   Returns `[end-index text]`."
  [^string src n i]
  (let [nl (str/index-of src "\n" i)
        end (if nl nl n)]
    [end (subs src i end)]))

(defn- read-char-token
  "From a backslash at `i`, consume a char literal — `\\(`, `\\a`, or a named
   char like `\\newline`. Returns `[end-index text]`. Consumes the backslash +
   one char, then trailing word-chars for named literals."
  [^string src n i]
  (let [j (inc i)]
    (if (>= j n)
      [n (subs src i n)]                          ; lone trailing backslash
      (loop [k (inc j)]                            ; always take the 1st char
        (if (and (< k n) (not (boundary? (.charAt src k))))
          (recur (inc k))
          [k (subs src i k)])))))

(defn- read-word-token
  "From a non-boundary char at `i`, consume a bare word (symbol / keyword /
   number / literal). Returns `[end-index text]`."
  [^string src n i]
  (loop [k i]
    (if (and (< k n) (not (boundary? (.charAt src k))))
      (recur (inc k))
      [k (subs src i k)])))

(defn- tokenize
  "Source string → seq of hiccup children (plain strings + classed `:span`s).
   Pure; consecutive plain chars are coalesced into one string node."
  [^string src]
  (let [n (count src)]
    (loop [i 0
           plain ""                               ; coalesced default-colour run
           out (transient [])]
      (if (>= i n)
        (persistent! (cond-> out (seq plain) (conj! plain)))
        (let [c (.charAt src i)]
          (cond
            (= c \;)
            (let [[end text] (read-comment-token src n i)]
              (recur end "" (-> out (cond-> (seq plain) (conj! plain))
                                (conj! (span "hljs-comment" text)))))

            (= c \")
            (let [[end text] (read-string-token src n i)]
              (recur end "" (-> out (cond-> (seq plain) (conj! plain))
                                (conj! (span "hljs-string" text)))))

            (= c \\)
            (let [[end text] (read-char-token src n i)]
              (recur end "" (-> out (cond-> (seq plain) (conj! plain))
                                (conj! (span "hljs-literal" text)))))

            (boundary? c)                          ; ws / paren / brace / quote
            (recur (inc i) (str plain c) out)

            :else
            (let [[end text] (read-word-token src n i)]
              (if-let [cls (word-class text)]
                (recur end "" (-> out (cond-> (seq plain) (conj! plain))
                                  (conj! (span cls text))))
                (recur end (str plain text) out)))))))))

;; ============================================================
;; Public — clj->hiccup
;; ============================================================

(def ^:private pre-class
  "Matches the existing eval-card `<pre>` (seon.handlers.eval) so the
   highlighted block sits identically in every surface."
  "text-xs whitespace-pre-wrap mt-0.5 rounded bg-base-900 p-1.5 overflow-x-auto")

;; The output is plain hiccup. Like `seon.ui.markdown/md->hiccup`, the schema
;; leaves the hiccup vector as `:any` — a DEEP recursive hiccup schema trips
;; `:malli.core/potentially-recursive-seqex` under always-on instrumentation
;; (the reason the deep `:seon.render/hiccup` schema was deleted; see
;; seon.render's ns notes). Only the INPUT is pinned.
(schema/register! ::source :string)

(defn clj->hiccup
  "Highlight Clojure `src` → `[:pre [:code.language-clojure.hljs <spans>]]`.

   Pure + total: never throws. Malformed / partial source (a stray `}`, an
   unterminated string) still renders — worst case a plain `[:code]` with the
   raw source. The `<code>` children are a SEQ (html.cljc splices seqs)."
  {:malli/schema [:=> [:catn [::source ::source]] :any]}
  [src]
  (let [src (or src "")]
    [:pre {:class pre-class}
     [:code {:class "language-clojure hljs"}
      (try
        (seq (tokenize src))
        (catch :default _ src))]]))
