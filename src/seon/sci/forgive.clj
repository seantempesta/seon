(ns seon.sci.forgive
  "The forgiving input stage in front of the one reader.

  One reader cursor per text version. `next-form` reads ONE form at the
  cursor with the caller's reader (SCI's, under the live resolver). Only when
  that read fails, or, in a model reply, when a bare token does not stand
  alone on its line, does it repair the text at that point and read again.
  Only a model reply (`:reply`) is repaired; code (`:code`) is read as
  written:

    prose line          -> `; prose` comment line (a structured code suffix on
                           the line stays code, on its own line)
    prompt marker       -> removed (`user=> (f 1)`)
    unbalanced closers  -> Parinfer indent mode over the form's lines (to the
                           next line starting at column 0), accepted only when
                           they then read without a structural error
    anything else       -> unread from here, as data

  A prose or marker repair is an edit recorded in the repairs with the span it
  replaces (`:seon.batch/at`, `:seon.batch/through`); the cursor moves past it
  in the same text version, and `forms` applies the edits once. A delimiter
  repair makes a new text version from the cursor on. Each repair costs its
  line or its form's lines, never the rest of the text.

  Trials (is this form balanced? is this suffix all collections?) use a
  structural reader: edamame with every semantic hook neutral (any alias, tags
  unread, no evaluation), so a tag reader or an alias ahead of the cursor is
  never invoked or resolved before the forms before it have run. The one
  semantic read of each accepted form happens at the advancing cursor."
  (:require [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [edamame.core :as edamame]
            [parinferish.core :as parinferish]
            [sci.core :as sci]))

(defn version
  "A text version: the text and a reader cursor started at `pos`."
  {:malli/schema [:=> [:cat :string nat-int?] :seon.batch/text-version]}
  [^String text pos]
  {:seon.batch/text text :seon.batch/pos pos
   :seon.batch/reader (sci/source-reader (if (zero? pos) text (doto (java.io.StringReader. text) (.skip (long pos)))))})

(defn- line-bounds
  {:malli/schema [:=> [:cat :string nat-int?] [:tuple nat-int? nat-int?]]}
  [^String text pos]
  (let [s (inc (.lastIndexOf text "\n" (int (max 0 (dec pos)))))
        e (let [i (.indexOf text "\n" (int pos))] (if (neg? i) (count text) i))]
    [s e]))

(defn- fence-line?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [line]
  (let [l (str/triml line)] (or (str/starts-with? l "```") (str/starts-with? l "~~~"))))

(defn skip-to
  "Offset of the next form start from `pos`.

  Skips whitespace, commas, `;` comments and Markdown fence lines at a line's
  start."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text pos]
  (loop [p pos]
    (if (>= p (count text))
      p
      (let [c (.charAt text p)
            [ls le] (line-bounds text p)]
        (cond
          (or (Character/isWhitespace c) (= \, c)) (recur (inc p))
          (= \; c) (recur le)
          (and (str/blank? (subs text ls p)) (fence-line? (subs text ls le))) (recur le)
          :else p)))))

(defn- advance
  "The version with its cursor moved forward to `pos`."
  {:malli/schema [:=> [:cat :seon.batch/text-version nat-int?] :seon.batch/text-version]}
  [v pos]
  (dotimes [_ (- pos (:seon.batch/pos v))] (rt/read-char (:seon.batch/reader v)))
  (assoc v :seon.batch/pos pos))

(defn- read-one
  "Read one form at the cursor with the live reader. {:form :end :v} or
  {:eof} or {:error}. A read that does not advance is an error, so every loop
  over reads terminates."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map :seon.batch/text-version] :map]}
  [ctx opts v]
  (let [eof (Object.)
        {^String text :seon.batch/text rdr :seon.batch/reader pos :seon.batch/pos} v]
    (try
      (let [l0 (sci/get-line-number rdr) c0 (sci/get-column-number rdr)
            form (sci/parse-next ctx rdr (assoc opts :eof eof))]
        (if (identical? eof form)
          {:eof true}
          (let [l (sci/get-line-number rdr) c (sci/get-column-number rdr)
                ;; the reader's lines and columns count from where it started:
                ;; the form's own lines are scanned once
                end (min (count text)
                         (if (= l l0)
                           (+ pos (- c c0))
                           (loop [i pos k (- l l0)]
                             (let [nl (.indexOf text "\n" (int i))]
                               (cond (neg? nl) (count text)
                                     (= 1 k) (+ (inc nl) (dec c))
                                     :else (recur (inc nl) (dec k)))))))]
            (if (> end pos)
              {:form form :end end :v (assoc v :seon.batch/pos end)}
              {:error (ex-info "The reader did not advance." {:seon.batch/pos pos})}))))
      (catch Throwable t {:error t}))))

(def ^:private structural-options
  "edamame with every semantic hook neutral: structure and token validity
  only, every dispatch form admitted. `edamame.core/parse-next` reads only
  normalized options."
  (edamame/normalize-opts {:all true :read-cond :preserve :auto-resolve (constantly 'seon.sci.forgive.trial)
                           :suppress-read true :eof ::eof}))

(def ^:private suffix-options
  "The structural reader for a code suffix on a prose line: plain collections,
  no quote or dispatch form."
  (edamame/normalize-opts {:read-cond :preserve :auto-resolve (constantly 'seon.sci.forgive.trial)
                           :suppress-read true :eof ::eof}))

(defn- structural-forms
  "Every top-level form of `text` read structurally with `options`, or nil
  when the text has a structural error. Invokes no tag reader and resolves no
  alias."
  {:malli/schema [:=> [:cat :string :map] [:maybe [:vector :seon.batch/form]]]}
  [^String text options]
  (try
    (let [r (edamame/reader text)]
      (loop [out []]
        (let [f (edamame/parse-next r options)]
          (if (= ::eof f) out (recur (conj out f))))))
    (catch Throwable _ nil)))

(def ^:private prose-read-failure #"^Invalid (?:number|symbol|keyword|token)")

(defn- collection-open
  "The offset of the collection opener at `start`, after any dispatch or quote
  prefix (`#`, `#=`, `'`, `` ` ``, `~`, `@`), or nil."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe nat-int?]]}
  [^String text start]
  (loop [i start]
    (when (< i (count text))
      (let [c (.charAt text (int i))]
        (cond (#{\( \[ \{} c) i
              (#{\# \= \' \` \~ \@} c) (recur (inc i))
              :else nil)))))

(defn- string-end
  "The offset of the quote closing the string opened at `open`, or nil."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe nat-int?]]}
  [^String text open]
  (loop [j (inc (long open))]
    (cond (>= j (count text)) nil
          (= \\ (.charAt text (int j))) (recur (+ j 2))
          (= \" (.charAt text (int j))) j
          :else (recur (inc j)))))

(defn- balanced-end
  "The offset just past the balanced collection starting at `start` (strings,
  character literals and `;` comments skipped), after any dispatch or quote
  prefix, or nil when `start` opens no collection or it never closes."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe nat-int?]]}
  [^String text start]
  (when-let [open (collection-open text start)]
    (loop [i (long open) depth 0]
      (when (< i (count text))
        (let [c (.charAt text (int i))]
          (case c
            \" (when-let [close (string-end text i)] (recur (inc (long close)) depth))
            \\ (recur (+ i 2) depth)
            \; (let [nl (.indexOf text "\n" (int i))] (if (neg? nl) nil (recur (inc nl) depth)))
            (\( \[ \{) (recur (inc i) (inc depth))
            (\) \] \}) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
            (recur (inc i) depth)))))))

(defn- structural-failure?
  "Whether the form at `pos` fails to read structurally in a way a delimiter
  repair could change: not as an invalid token, which survives Parinfer. A
  collection is read only to its matching closer, so an unclosed one costs one
  scan; a plain token can fail only as a token and is not read. No tag reader
  invoked, no alias resolved."
  {:malli/schema [:=> [:cat :string nat-int?] :boolean]}
  [^String text pos]
  (let [end (balanced-end text pos)]
    (cond
      (and (nil? end) (collection-open text pos)) true
      (not (#{\( \[ \{ \) \] \} \" \# \' \` \~ \@ \^ \\} (.charAt text (int pos)))) false
      :else
      (try (edamame/parse-next (edamame/reader (if end
                                                 (subs text pos end)
                                                 (doto (java.io.StringReader. text) (.skip (long pos)))))
                               structural-options)
           false
           (catch Throwable t (not (re-find prose-read-failure (str (ex-message t)))))))))

(defn- code-suffix
  "The offset, within `tail`, of a suffix whose every form is a collection
  (today's readable-code-suffix rule), or nil. Structural reads only."
  {:malli/schema [:=> [:cat :string] [:maybe nat-int?]]}
  [^String tail]
  (some (fn [i]
          (let [forms (structural-forms (subs tail i) suffix-options)]
            (when (and (seq forms) (every? coll? forms)) i)))
        (keep-indexed (fn [i ch] (when (and (pos? i) (#{\( \[ \{} ch)) i)) tail)))

(defn- prose-repair
  "The edit making `start`'s line from `start` on a `;` comment: through a
  structured code suffix, which stays code on its own line, else through the
  line's end."
  {:malli/schema [:=> [:cat :string nat-int?] :seon.batch/repair]}
  [^String text start]
  (let [[_ le] (line-bounds text start)
        tail (subs text start le)
        suffix (code-suffix tail)]
    {:seon.batch/repair-kind :prose :seon.batch/prose (str/trim (if suffix (subs tail 0 suffix) tail))
     :seon.batch/at start :seon.batch/through (if suffix (+ start suffix) le)}))

(defn- form-lines-end
  "Where the lines of the form at `pos` end: the next line outside a string
  starting at column 0 with neither a closer nor a comment, else the text's
  end. Parinfer's indent mode closes every open form there."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text pos]
  (let [n (count text)]
    (loop [i (long pos)]
      (if (>= i n)
        n
        (case (.charAt text (int i))
          \" (if-let [close (string-end text i)] (recur (inc (long close))) n)
          \\ (recur (+ i 2))
          \; (let [nl (.indexOf text "\n" (int i))] (if (neg? nl) n (recur (long nl))))
          \newline (let [j (inc i)]
                     (if (and (< j n) (let [c (.charAt text (int j))]
                                        (not (or (Character/isWhitespace c) (#{\) \] \} \;} c)))))
                       j
                       (recur j)))
          (recur (inc i)))))))

(defn- delimiter-repair
  "Parinfer indent mode over the form's lines from `pos`, accepted only if it
  changed them and they then read as forms without a structural error. A
  failure at no collection, as a stray closer, is judged with the next form's
  lines (a fence ends them), so a closer is dropped only before a form. Known
  issue: each accepted repair copies the text
  (`seon.sci.forgive/each-accepted-delimiter-repair-copies-the-whole-reply`)."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe [:map [:text :string] [:repair :seon.batch/repair]]]]}
  [^String text pos]
  (let [own (form-lines-end text pos)
        end (if (or (collection-open text pos) (fence-line? (subs text own (second (line-bounds text own)))))
              own
              (form-lines-end text own))
        lines (subs text pos end)
        parsed (parinferish/parse lines {:mode :indent})
        candidate (parinferish/flatten parsed)]
    (when (and (not (:error? (meta parsed))) (not= candidate lines)
               (seq (structural-forms candidate structural-options)))
      {:text (str (subs text 0 pos) candidate (subs text end))
       :repair {:seon.batch/repair-kind :delimiters :seon.batch/at pos}})))

(def ^:private prompt #"^[A-Za-z][\w.\-]*=>[ \t]*")

(defn structure?
  "Whether a reply holds any line that begins with a collection.

  Then a bare token alone on its line is code. One linear pass per reply."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String text]
  (boolean (some #(re-find #"^[\(\[\{]" (str/triml %)) (str/split-lines text))))

(defn- code-line?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [line]
  (boolean (re-find #"^[\(\[\{\#'`~@^]" (str/triml line))))

(defn- structured?
  "Whether a read form is code by its shape: a collection, or a dispatch form."
  {:malli/schema [:=> [:cat :seon.batch/form :string] :boolean]}
  [form ^String source]
  (or (coll? form) (str/starts-with? source "#")))

(defn next-form
  "The next read step at the cursor.

  {:seon.batch/form :seon.batch/start :seon.batch/end} or {:seon.batch/eof
  true} or {:seon.batch/unread-from :seon.batch/reason}, each with the
  :seon.batch/version (its text carries the delimiter repairs) and the
  accumulated :seon.batch/repairs (prose and marker repairs are edits that
  `forms` applies). `state` is :seon.batch/read-state. Known issue: a prose
  line is recognized by a thrown reader error
  (`seon.sci.forgive/each-prose-line-costs-a-thrown-reader-error`)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map [:enum :reply :code] :seon.batch/read-state]
                  :seon.batch/read-step]}
  [ctx opts mode {v :seon.batch/version repairs :seon.batch/repairs
                  structured-reply? :seon.batch/structured? last-code-end :seon.batch/last-code-end}]
  ;; `line-from` is where the last edit ended: the repaired text has a line
  ;; break there when the edit kept a code suffix or removed a marker
  (loop [v v repairs repairs guard 0 line-from 0]
    (let [text (:seon.batch/text v)
          start (skip-to text (:seon.batch/pos v))
          v (advance v start)
          [line-start le] (line-bounds text start)
          ls (max line-start line-from)
          begins-line? (str/blank? (subs text ls start))
          follows-code? (and last-code-end (<= last-code-end start)
                             (str/blank? (subs text last-code-end start))
                             (not (str/includes? (subs text last-code-end start) "\n")))
          ;; code is read as written: only a model reply is repaired
          reply? (= :reply mode)
          marker (when (and reply? begins-line?) (re-find prompt (subs text start le)))
          done (fn [m] (assoc m :seon.batch/version v :seon.batch/repairs repairs))
          ;; Parinfer only moves closers, so a token error survives it and
          ;; its repair would be refused: only a structural failure is repaired
          fixed (delay (when (and reply? (< start (count text)) (structural-failure? text start))
                         (delimiter-repair text start)))
          step
          (cond
            (> guard 64) (done {:seon.batch/unread-from start :seon.batch/reason :too-many-repairs})
            (>= start (count text)) (done {:seon.batch/eof true})
            marker
            [:edit {:seon.batch/repair-kind :prompt-marker :seon.batch/at start
                    :seon.batch/through (+ start (count marker))}]
            ;; the delimiter decision is structural and comes first, so the one
            ;; semantic read (tag readers, alias resolution) runs once, on the
            ;; accepted form
            @fixed
            [:redo (:text @fixed) (:repair @fixed)]
            :else
            (let [r (read-one ctx opts v)
                  source (when (contains? r :form) (subs text start (:end r)))]
              (cond
                (:eof r) (done {:seon.batch/eof true})
                ;; Only the REPL writes responses: a map naming :seon.repl/*
                ;; keys is fabricated output, returned refused, never run.
                (and (map? (:form r)) (some #(and (keyword? %) (= "seon.repl" (namespace %))) (keys (:form r))))
                {:seon.batch/form (:form r) :seon.batch/start start :seon.batch/end (:end r)
                 :seon.batch/version (:v r) :seon.batch/repairs repairs :seon.batch/refused :fabricated-response}
                (contains? r :form)
                (if (or (= :code mode)
                        (and (structured? (:form r) source) (or begins-line? follows-code?))
                        (and structured-reply? begins-line?
                             (= (str/trim (subs text ls le)) (str/trim source))))
                  {:seon.batch/form (:form r) :seon.batch/start start :seon.batch/end (:end r)
                   :seon.batch/version (:v r) :seon.batch/repairs repairs}
                  [:edit (prose-repair text start)])
                :else
                (let [msg (str (ex-message (:error r)))]
                  (cond
                    (and (= :reply mode) (re-find prose-read-failure msg)
                         (not (code-line? (subs text ls le))))
                    [:edit (prose-repair text start)]
                    :else (done {:seon.batch/unread-from start :seon.batch/reason msg
                                 :seon.batch/cause (:error r)}))))))]
      (case (when (vector? step) (first step))
        :edit (let [rep (second step) through (:seon.batch/through rep)]
                (recur (version text through) (conj repairs rep) (inc guard) through))
        :redo (let [[_ t rep] step] (recur (version t start) (conj repairs rep) (inc guard) line-from))
        step))))

(defn read-state
  "The initial read state of `text`."
  {:malli/schema [:=> [:cat :string] :seon.batch/read-state]}
  [text]
  {:seon.batch/version (version text 0) :seon.batch/structured? (structure? text)
   :seon.batch/repairs []})

(defn- repair-edits
  "The prose and marker repairs of `repairs` as [at through replacement] over
  `text`: a prose line becomes `; prose`, broken before a kept code suffix."
  {:malli/schema [:=> [:cat :string :seon.batch/repairs] [:vector [:tuple nat-int? nat-int? :string]]]}
  [^String text repairs]
  (into []
        (keep (fn [{:seon.batch/keys [repair-kind at through prose]}]
                (case repair-kind
                  :prose [at through (str "; " prose (when (and (< through (count text))
                                                                 (not= \newline (.charAt text (int through))))
                                                        "\n"))]
                  :prompt-marker [at through ""]
                  nil)))
        repairs))

(defn- apply-edits
  "`text` with `changes` applied, and `offsets` (ascending, none inside an
  edit) moved into the repaired text."
  {:malli/schema [:=> [:cat :string [:vector [:tuple nat-int? nat-int? :string]] [:vector nat-int?]]
                  [:map [:text :string] [:offsets [:vector nat-int?]]]]}
  [^String text changes offsets]
  (loop [es changes from 0 shift 0 os offsets pieces [] moved []]
    (let [[at through replacement] (first es)
          o (first os)]
      (cond
        ;; an offset before the next edit moves by the edits before it
        (and o (or (nil? at) (<= o at))) (recur es from shift (rest os) pieces (conj moved (+ o shift)))
        at (recur (rest es) through (+ shift (count replacement) (- at through)) os
                  (conj pieces (subs text from at) replacement) moved)
        :else {:text (apply str (conj pieces (subs text from))) :offsets moved}))))

(defn forms
  "Every form of `text` through `next-form`, read only.

  Answers the accepted sources with their spans in the repaired text, the
  repaired text, the repairs and where reading stopped (the structural
  segmentation of a reply)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map [:enum :reply :code] :string] :map]}
  [ctx opts mode text]
  (loop [state (read-state text) out [] spans []]
    (let [r (next-form ctx opts mode state)
          t (:seon.batch/text (:seon.batch/version r))]
      (if (or (:seon.batch/eof r) (contains? r :seon.batch/unread-from))
        (let [{repairs :seon.batch/repairs unread-from :seon.batch/unread-from reason :seon.batch/reason} r
              changes (repair-edits t repairs)
              ;; spans and the unread offset move into the repaired text
              {fixed :text offsets :offsets}
              (if (seq changes)
                (apply-edits t changes (cond-> (into [] cat spans) unread-from (conj unread-from)))
                {:text t :offsets (cond-> [] unread-from (conj unread-from))})]
          (cond-> {:forms out :spans (if (seq changes) (mapv vec (partition 2 offsets)) spans)
                   :text fixed :repairs repairs}
            unread-from (assoc :unread-from (peek offsets) :reason reason)))
        (recur (assoc state :seon.batch/version (:seon.batch/version r) :seon.batch/repairs (:seon.batch/repairs r)
                      :seon.batch/last-code-end (:seon.batch/end r))
               (conj out (str/trimr (subs t (:seon.batch/start r) (:seon.batch/end r))))
               (conj spans [(:seon.batch/start r) (:seon.batch/end r)]))))))

(def ^:private structural-ctx
  "A bare SCI context for structural reads: they resolve no alias and invoke
  no tag reader, so they need no live program."
  (delay (sci/init {})))

(defn- comment-of
  "The prose before a form as its comment: the `;` lines between the previous
  form and this one, markers kept (the agent-facing grammar reads them back
  as it typed them: `ns=> ;; prose`); fence lines dropped."
  {:malli/schema [:=> [:cat :string] :string]}
  [^String between]
  (->> (str/split-lines between)
       (map str/trim)
       (remove fence-line?)
       (filter (fn [l] (str/starts-with? l ";")))
       (remove #(str/blank? (str/replace-first % #"^;+" "")))
       (str/join "\n")))

(defn segments
  "Each form's source with the prose above it as its comment; the unread rest.

  A reply (`:reply`, the default: prose becomes comments) or code (`:code`:
  every form is a form, nothing is repaired) is read structurally (edamame,
  every semantic hook neutral: tags unread, any alias), with the forgiving
  stage's repairs. A balanced form the
  structural read refuses (an odd map) is one form whose evaluation-time read
  answers its error once, and reading continues after it; an unbalanced rest
  is the unread rest. The one semantic read of each form happens later, at
  the live cursor, when it is evaluated."
  {:malli/schema [:function
                  [:=> [:cat :string] :seon.batch/segmented]
                  [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]]}
  ([text] (segments text :reply))
  ([text mode]
  (let [ctx @structural-ctx
        opts {:all true :read-cond :preserve :auto-resolve (constantly 'seon.sci.forgive.trial)
              :suppress-read true :end-location true}
        {sources :forms :keys [spans unread-from] repaired :text} (forms ctx opts mode text)
        ends (into [0] (map second) spans)
        found (mapv (fn [source [start _] previous-end]
                     (let [c (comment-of (subs repaired previous-end start))]
                       (cond-> {:seon.batch/source source}
                         (not (str/blank? c)) (assoc :seon.batch/comment c))))
                   sources spans ends)
        start (when unread-from (skip-to repaired unread-from))
        end (when start (balanced-end repaired start))]
    (cond
      (nil? unread-from) {:seon.batch/segments found}
      end (let [c (comment-of (subs repaired (peek ends) start))
                rest-segments (segments (subs repaired end) mode)]
            (update rest-segments :seon.batch/segments
                    #(into (conj found (cond-> {:seon.batch/source (subs repaired start end)}
                                        (not (str/blank? c)) (assoc :seon.batch/comment c)))
                           %)))
      (str/blank? (subs repaired unread-from)) {:seon.batch/segments found}
      :else {:seon.batch/segments found :seon.batch/unread (str/trim (subs repaired unread-from))}))))
