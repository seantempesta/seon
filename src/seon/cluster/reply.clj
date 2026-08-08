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
  attached to a form: the one it precedes, or — for a trailing span —
  the one it follows.

  EVERY PLAN SOURCE CARRIES A READER EVENT, and that is the invariant
  this namespace exists to keep. A comment-only plan source has no
  event, so nothing evaluates it and nothing settles a receipt for it:
  the run recorded a `:seon.cluster.run.form` row and closed with that
  row unsettled, silently, detectable only by counting forms against
  receipts. The 2026-08-08 arc drive read 105 forms / 102 receipts, and
  all three gaps were comment-only sources produced by deepseek-v4-flash
  chat-template control markup (`<assistant1>`,
  `<｜｜DSML｜｜AgentThoughts>…`) arriving verbatim in the completion's
  `content` field. A prose span therefore never becomes a plan source of
  its own; a reply with no forms at all is a LOUD `::no-forms` refusal
  carrying its text, not a row that settles nothing
  (`docs/seon/issues/a-runs-last-form-can-close-without-a-receipt.md`).

  SOURCES, NOT FORMS. The return is a vector of plan forms, each one a
  SOURCE STRING plus the namespace it was written under. The evaluator
  parses each source inside its own armed context, so the reply is read
  once for splitting and once for evaluation — never handed across as
  parsed data that a second reader would have to trust.

  ONE READER, AND IT IS NOT THIS NAMESPACE'S. Reading belongs to
  `seon.sci.reader`; this namespace only decides which read events are
  code and which are prose. That is why the parse-time
  namespace-in-effect (`:seon.sci.reader/ns`) arrives with the span
  instead of being re-derived by a second inheritance rule of our own —
  the rule that a previous revision of the generate-code plan invented
  and that contradicted the runtime.

  Errors are flat values, never throws: a reply the loop cannot split
  closes the run with a steering message the agent sees next wake.

  Crash walk: pure. A kill loses a vector of strings that had not been
  committed; the plan is not durable until N2's `plan-tx` commits it,
  and re-deriving it from the same text is deterministic."
  (:require [clojure.string :as str]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
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
  [kind marker message data]
  (merge marker
         {:seon.error/kind kind
          :seon.error/message message
          :seon.error/data data}))

(defn- parsed-events
  "Read events for `source` from THE ONE reader, or its flat error value.
  This namespace no longer reads model text itself: `seon.sci.reader`
  owns reading, so the namespace-in-effect each form was written under
  (`:seon.sci.reader/ns`, REPL semantics, absent rather than inherited
  after a malformed declaration) arrives with the span instead of being
  re-derived here by a second rule."
  ([source] (parsed-events source nil))
  ([source namespace-name]
   (let [events (reader/read
                 (cond-> {:seon.sci.reader/text source
                          ;; This pass freezes exact source spans. Reader
                          ;; aliases created by earlier forms are resolved by
                          ;; the evaluator's later sequential read, not here.
                          :seon.sci.reader/defer-auto-resolve? true}
                   namespace-name
                   (assoc :seon.sci.reader/ns namespace-name)))]
     (if (map? events)
       events
       (mapv (fn [event]
               (let [start (:seon.sci.reader/source-start event)
                     end (:seon.sci.reader/source-end event)
                     form-source (:seon.sci.reader/source event)]
                 (cond-> {::form (:seon.sci.reader/form event)
                          ::source form-source
                          ::start start
                          ::end end}
                   (:seon.sci.reader/ns event)
                   (assoc ::ns (:seon.sci.reader/ns event)))))
             events)))))

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
  "Attach prose spans to the form they precede, or trail.
  Each plan form carries the reader's namespace-in-effect when the
  reader attributed one; a form the reader could not attribute simply
  has no `:seon.ns/name`, and absence is what routes its red receipt to
  the run's author rather than to a guessed owner.

  Returns EMPTY when the reply had no code at all — prose alone is
  never a plan source, because a source the reader finds no event in
  cannot settle a receipt."
  [source events]
  (let [code-indexes (code-event-indexes source events)
        code-events (keep-indexed (fn [index event]
                                    (when (contains? code-indexes index)
                                      event))
                                  events)]
    (loop [cursor 0
           remaining (seq code-events)
           forms []]
      (if-let [{::keys [start end ns] form-source ::source} (first remaining)]
        (let [prose (comment-source (subs source cursor start))
              plan-source (str (when-not (str/blank? prose)
                                 (str prose "\n"))
                               form-source)]
          (recur end (next remaining)
                 (conj forms
                       (cond-> {:seon.cluster.run.form/source plan-source}
                         ns (assoc :seon.ns/name ns)))))
        ;; TRAILING PROSE RIDES THE FORM IT FOLLOWS. It used to become its
        ;; own comment-only plan source, which is the shape that recorded a
        ;; form row no receipt could ever settle.
        (let [prose (comment-source (subs source cursor))]
          (cond-> forms
            (and (seq forms) (not (str/blank? prose)))
            (update (dec (count forms))
                    update :seon.cluster.run.form/source str "\n" prose)))))))

(defn- no-forms-message
  "Name what the reply carried instead of forms."
  [source]
  (if (str/blank? (comment-source source))
    "The reply carried no Clojure forms."
    (str "The reply carried no Clojure forms — its whole text read as "
         "prose. Prose runs nothing and settles nothing; write the "
         "Clojure you want evaluated.")))

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
            (let [events (parsed-events suffix)]
              (when (and (vector? events)
                         (seq events)
                         (some (comp coll? ::form) events)
                         (every? (fn [event]
                                   (or (coll? (::form event))
                                       (standalone-symbol? suffix event)))
                                 events))
                suffix))))
        (keep-indexed (fn [index character]
                        (when (#{\( \[ \{} character) index))
                      line)))

(defn- top-level-failure?
  "True when everything before the failing line reads completely.
  A token that fails INSIDE an unclosed form is malformed code, never
  prose, and the ONE reader answers that question by reading the prefix
  — no delimiter bookkeeping of our own, and nothing that depends on
  which keys a reader exception happens to carry."
  [lines line-number]
  (vector?
   (reader/read {:seon.sci.reader/text
                 (str/join "\n" (subvec lines 0 (dec line-number)))})))

(defn- comment-prose-failure
  "Comment one reader-failing prose line, preserving a valid code suffix."
  [source failure recovered-lines]
  (let [message (:seon.error/message failure)
        line-number (:seon.sci.reader/line (:seon.error/data failure))
        lines (vec (str/split source #"\n" -1))
        line (when (and line-number (pos? line-number))
               (get lines (dec line-number)))]
    (when (and line
               (not (contains? recovered-lines line-number))
               (re-find prose-read-failure message)
               (top-level-failure? lines line-number)
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
  "The ordered plan forms in one model reply, or a flat error value.
  Strips code fences, then reads through THE ONE reader
  (`seon.sci.reader/read`), returning each form's EXACT source text in
  order — each carrying `:seon.ns/name`, the namespace that form was
  written under, whenever the reader attributed one. Attribution is the
  reader's REPL semantics, not a rule of this namespace: absence after
  a malformed declaration stays absence, and plan freeze projects
  whatever arrives here.

  Flat `:seon.error` values, never throws:
  - `::unreadable` — unbalanced or malformed input, carrying the
    reader's own position so the agent can see where;
  - `::refused-tag` — `#=` or an unknown reader tag, named;
  - `::no-forms` — the reply carried no code: it was empty, or its
    whole text read as prose. Prose accompanying a form still rides
    that form's source as comments; prose ALONE is a refusal, because a
    plan source with no reader event settles no receipt and would close
    the run with an unsettled form."
  {:malli/schema
   [:function
    [:=> [:cat :seon.cluster.reply/text]
     [:or :seon.cluster.reply/sources :seon.error/value]]
    [:=> [:cat :seon.cluster.reply/text :seon.ns/name]
     [:or :seon.cluster.reply/sources :seon.error/value]]]}
  ([text] (sources text nil))
  ([text namespace-name]
   (loop [source (unfenced text)
          recovered-lines #{}]
     (let [events (parsed-events source namespace-name)]
       ; the reader refuses #= and unknown tags by itself — there is no
       ; blocklist here, and there must never be one
       (if (map? events)
         (let [message (:seon.error/message events)
               tag (:seon.sci.reader/tag (:seon.error/data events))]
           (if (= :seon.sci.reader/refused-tag (:seon.error/kind events))
             (refused ::refused-tag
                      (cond-> {} tag (assoc ::refused-tag tag))
                      message {::text text})
             (if-let [{recovered-source :source line :line}
                      (comment-prose-failure source events recovered-lines)]
               (recur recovered-source (conj recovered-lines line))
               (refused ::unreadable {::unreadable text} message
                        {::text text}))))
         (let [forms (plan-sources source events)]
           (if (seq forms)
             (vec forms)
             (refused ::no-forms {::no-forms true}
                      (no-forms-message source) {::text text}))))))))
