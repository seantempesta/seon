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

  PROSE BECOMES SOURCE COMMENTS, never forms. Every English word can
  read as a symbol, so successful reading alone cannot distinguish
  prose from code. Structured top-level forms (lists, vectors, maps and
  sets) remain plan forms; a bare symbol remains a form only when it
  occupies its own source line in a reply that also has structure.
  Everything else is coalesced back into its original prose span,
  prefixed with the agent-facing single-`;` comment grammar, and
  attached to the next form. Trailing or pure prose becomes one
  comment-only plan source: SCI reads that as nil, so the prose is
  recorded but no prose token is resolved or invoked.

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
;;; error. Outside-fence Markdown is prose, so retain it as comments
;;; instead of either parsing it or dropping it.
(defn- fence-line?
  "True for one Markdown backtick or tilde fence line."
  [line]
  (boolean (re-matches #"[ \t]*(?:```|~~~).*" line)))

(defn- prose-line
  "One nonblank prose line in the agent-facing comment grammar."
  [line]
  (let [line (str/trim line)]
    (when-not (str/blank? line)
      (if (str/starts-with? line ";")
        line
        (str "; " line)))))

(defn- unfenced
  "Remove fence lines while retaining outside Markdown as prose comments."
  [text]
  (let [lines (str/split-lines text)
        fenced? (some fence-line? lines)]
    (if-not fenced?
      text
      (->> lines
           (reduce (fn [{:keys [inside? output]} line]
                     (if (fence-line? line)
                       {:inside? (not inside?) :output output}
                       {:inside? inside?
                        :output (conj output
                                      (if inside?
                                        line
                                        (or (prose-line line) "")))}))
                   {:inside? false :output []})
           :output
           (str/join "\n")))))

(defn- refused
  "The ONE registered flat error value (`:seon.error/value`).
  Detail rides under `:seon.error/data` rather than beside the message,
  because the shape is closed and one owner (error.edn) decides it."
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- parsed-events
  "SCI forms with exact source spans in `source`."
  [source]
  (let [ctx (sci/init {})
        reader (sci/source-reader source)]
    (loop [search-from 0
           events []]
      (let [[form form-source] (sci/parse-next+string ctx reader
                                                      {:eof ::eof})]
        (if (= ::eof form)
          events
          (let [start (.indexOf source form-source search-from)]
            (when (neg? start)
              (throw (ex-info "SCI source span was not present in its input."
                              {::text source
                               ::form-source form-source})))
            (let [end (+ start (count form-source))]
              (recur end
                     (conj events
                           {::form form
                            ::source form-source
                            ::start start
                            ::end end})))))))))

(defn- standalone-symbol?
  "True when an event is a bare symbol occupying its whole source line."
  [whole-source {form ::form form-source ::source}]
  (when (symbol? form)
    (let [line (:line (meta form))
          source-line (when line
                        (nth (str/split-lines whole-source) (dec line) nil))
          form-line (last (str/split-lines form-source))]
      (and source-line
           (= (str/trim source-line) (str/trim form-line))))))

(defn- form-start
  "Absolute source offset of an event's form, excluding leading comments."
  [source event]
  (let [{:keys [line column]} (meta (::form event))]
    (if (and line column)
      (loop [line-start 0
             remaining-lines (dec line)]
        (if (zero? remaining-lines)
          (+ line-start (dec column))
          (let [newline (.indexOf source "\n" line-start)]
            (if (neg? newline)
              (::start event)
              (recur (inc newline) (dec remaining-lines))))))
      (::start event))))

(defn- structured-code-indexes
  "Structured forms beginning a code line or following code on that line."
  [source events]
  (first
   (reduce
    (fn [[indexes code-events] [index event]]
      (let [start (form-start source event)
            line-start (inc (.lastIndexOf source "\n" (dec start)))
            begins-line? (str/blank? (subs source line-start start))
            follows-code? (some (fn [code-event]
                                  (let [previous-end (::end code-event)]
                                    (and (<= previous-end start)
                                         (not (str/includes?
                                               (subs source previous-end start)
                                               "\n"))
                                         (str/blank?
                                          (subs source previous-end start)))))
                                code-events)]
        (if (and (coll? (::form event))
                 (or begins-line? follows-code?))
          [(conj indexes index) (conj code-events event)]
          [indexes code-events])))
    [#{} []]
    (map-indexed vector events))))

(defn- code-event-indexes
  "Indexes of structured forms and established standalone-symbol forms."
  [source events]
  (let [structured-indexes (structured-code-indexes source events)
        structured? (seq structured-indexes)]
    (into structured-indexes
          (keep-indexed
           (fn [index event]
             (when (and structured?
                        (standalone-symbol? source event))
               index)))
          events)))

(defn- comment-source
  "Coalesce prose into safe single-`;` source comments."
  [text]
  (->> (str/split-lines (str/trim text))
       (keep prose-line)
       (str/join "\n")))

(defn- plan-sources
  "Attach prose spans to the next form and retain trailing prose."
  [source events]
  (let [code-indexes (code-event-indexes source events)
        code-events (keep-indexed (fn [index event]
                                    (when (contains? code-indexes index)
                                      event))
                                  events)]
    (loop [cursor 0
           remaining (seq code-events)
           sources []]
      (if-let [{::keys [start end] form-source ::source} (first remaining)]
        (let [prose (comment-source (subs source cursor start))
              plan-source (str (when-not (str/blank? prose)
                                 (str prose "\n"))
                               form-source)]
          (recur end (next remaining) (conj sources plan-source)))
        (let [prose (comment-source (subs source cursor))]
          (cond-> sources
            (not (str/blank? prose)) (conj prose)))))))

(def ^:private prose-read-failure
  #"^Invalid (?:number|symbol|keyword|token)")

(defn- code-line?
  "True when a line begins with reader syntax rather than prose."
  [line]
  (boolean (re-find #"^[\(\[\{\)\]\}\#'`~@^]" (str/triml line))))

(defn- readable-code-suffix
  "A structured all-code suffix beginning later on one prose line."
  [line]
  (some (fn [offset]
          (let [suffix (subs line offset)]
            (try
              (let [events (parsed-events suffix)]
                (when (and (seq events)
                           (some (comp coll? ::form) events)
                           (every? (fn [event]
                                     (or (coll? (::form event))
                                         (standalone-symbol? suffix event)))
                                   events))
                  suffix))
              (catch #?(:clj Throwable :cljs :default) _ nil))))
        (keep-indexed (fn [index character]
                        (when (#{\( \[ \{} character) index))
                      line)))

(defn- comment-prose-failure
  "Comment one reader-failing prose line, preserving a valid code suffix."
  [source failure recovered-lines]
  (let [message (or (ex-message failure) (str failure))
        failure-data (ex-data failure)
        line-number (or (:line failure-data) (:row failure-data))
        lines (vec (str/split source #"\n" -1))
        line (when line-number (nth lines (dec line-number) nil))]
    (when (and line
               (not (contains? recovered-lines line-number))
               (re-find prose-read-failure message)
               (nil? (:opened-delimiter failure-data))
               (nil? (:opened-delimiter-loc failure-data))
               (not (code-line? line)))
      (let [suffix (readable-code-suffix line)
            prefix (when suffix
                     (subs line 0 (- (count line) (count suffix))))
            replacement (if suffix
                          (str (prose-line prefix) "\n" suffix)
                          (or (prose-line line) ""))]
        {:source (str/join "\n"
                           (assoc lines (dec line-number) replacement))
         :line line-number}))))

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
  - `::no-forms` — the reply was empty or whitespace only. Prose is a
    successful comment source, not a refusal."
  {:malli/schema [:=> [:cat :seon.cluster.reply/text]
                  [:or :seon.cluster.reply/sources :seon.error/value]]}
  [text]
  (loop [source (unfenced text)
         recovered-lines #{}]
    (let [attempt (try
                    {:sources (plan-sources source (parsed-events source))}
                    (catch #?(:clj Throwable :cljs :default) failure
                      {:failure failure}))]
      (if-let [failure (:failure attempt)]
        ; sci's reader refuses #= and unknown tags by itself — there is
        ; no blocklist here, and there must never be one
        (let [message (or (ex-message failure) (str failure))]
          (if (or (str/includes? message "EvalReader")
                  (str/includes? message "reader function for tag"))
            (refused ::refused-tag message {::text text})
            (if-let [{recovered-source :source line :line}
                     (comment-prose-failure source failure recovered-lines)]
              (recur recovered-source (conj recovered-lines line))
              (refused ::unreadable message {::text text}))))
        (let [sources (:sources attempt)]
          (if (seq sources)
            (vec sources)
            (refused ::no-forms
                     "The reply carried no Clojure forms or prose notes."
                     {::text text})))))))
