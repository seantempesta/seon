(ns seon.sci.forgive
  "Light forgiveness in front of evaluation, and the stepper that reads each
  form once with the read itself supplied as a value
  (`:seon.batch/read-stage`).

  `forgive` is one linear scan. In a model reply (`:reply`), outside a
  Clojure-tagged code fence and outside an open form, a line is code when its
  first non-blank character starts a form or it is one literal or qualified
  symbol; every other line becomes a `;` comment, fence lines go, and words
  after a form on its line become a comment. An untagged fence's body is read
  by this line rule; another tag's body is prose; a fence line ends a form
  still waiting for a prefix's operand. In both modes an unbalanced text is repaired: a stray closer is
  dropped and missing closers are inserted before the next column-zero line,
  each change a repair note. Balanced text keeps its delimiters byte for
  byte. The scan also finds each top-level form's span, which `step` reads."
  (:require [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [edamame.core :as edamame]
            [seon.sci.reader :as reader]))

;;; Characters and lines

(defn- line-end
  "The offset of the newline ending the line at `pos`, else the text's end."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  ^long [^String text ^long pos]
  (let [i (.indexOf text "\n" (int pos))] (if (neg? i) (.length text) i)))

(defn- non-blank
  "The first offset from `pos` below `limit` that is not whitespace, else `limit`."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] nat-int?]}
  ^long [^String text ^long pos ^long limit]
  (loop [i pos]
    (if (and (< i limit) (Character/isWhitespace (.charAt text (int i)))) (recur (inc i)) i)))

(defn- token-end
  "The end, at most `limit`, of the bare token continuing at `pos`: the next
  whitespace or character the reader's token rule ends a token at."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] nat-int?]}
  ^long [^String text ^long pos ^long limit]
  (loop [i pos]
    (if (and (< i limit)
             (let [c (.charAt text (int i))]
               (not (or (Character/isWhitespace c)
                        (case c (\( \) \[ \] \{ \} \" \; \@ \^ \` \~ \\ \,) true false)))))
      (recur (inc i))
      i)))

(defn- fence-line
  "The backtick count and lowercased first word of the line `[ls le)` when it
  is a fence line (three or more backticks first), else nil."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] [:maybe [:tuple pos-int? :string]]]}
  [^String text ^long ls ^long le]
  (let [s (non-blank text ls le)
        e (loop [i s] (if (and (< i le) (= \` (.charAt text (int i)))) (recur (inc i)) i))
        tag-start (non-blank text e le)
        tag-end (loop [i tag-start] (if (and (< i le) (not (Character/isWhitespace (.charAt text (int i))))) (recur (inc i)) i))]
    (when (>= (- e s) 3)
      [(- e s) (str/lower-case (subs text tag-start tag-end))])))

(defn- code-fence-line?
  "Whether the line `[ls le)` of code is a fence line: three backticks or tildes first."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] :boolean]}
  [^String text ^long ls ^long le]
  (let [s (int (non-blank text ls le))]
    (or (.startsWith text "```" s) (.startsWith text "~~~" s))))

(def ^:private code-tags
  "The fence tags whose body is code. An untagged fence's body is read by
  the line rule, as outside fences: models fence prose and quotes too."
  #{"clojure" "clj" "cljc" "edn"})

(defn- form-start?
  "Whether the character at `s` starts a form by its first character: a
  collection, quote, deref or meta character, `#` before a non-blank
  character other than `#`, or a backtick before a collection."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] :boolean]}
  [^String text ^long s ^long le]
  (let [c (.charAt text (int s))
        d (when (< (inc s) le) (.charAt text (int (inc s))))]
    (boolean (case c
               (\( \[ \{ \' \@ \^) true
               \# (and d (not= \# d) (not (Character/isWhitespace ^char d)))
               \` (#{\( \[ \{} d)
               false))))

(defn- string-closes-at?
  "Whether the string opened at `s` closes exactly at `end`."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] :boolean]}
  [^String text ^long s ^long end]
  (loop [j (inc (long s))]
    (cond (> j end) false
          (= \\ (.charAt text (int j))) (recur (+ j 2))
          (= \" (.charAt text (int j))) (= j end)
          :else (recur (inc j)))))

(defn- literal-line?
  "Whether the trimmed line `[s le)` is exactly one literal (number, string,
  keyword, character, `##` value, `true`, `false`, `nil`) or one
  namespace-qualified symbol."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] :boolean]}
  [^String text ^long s ^long le]
  (let [e (loop [e le] (if (and (> e s) (Character/isWhitespace (.charAt text (int (dec e))))) (recur (dec e)) e))
        c (.charAt text (int s))
        one-token? (fn [from] (= e (token-end text from e)))]
    (boolean
     (cond
       (= \" c) (string-closes-at? text s (dec e))
       (= \\ c) (and (>= (- e s) 2) (one-token? (+ s 2)))
       (= \# c) (and (> (- e s) 2) (= \# (.charAt text (int (inc s)))) (one-token? (+ s 2)))
       (not (one-token? s)) false
       (#{"true" "false" "nil"} (subs text s e)) true
       (Character/isDigit c) true
       (#{\+ \-} c) (and (> (- e s) 1) (Character/isDigit (.charAt text (int (inc s)))))
       (= \: c) (> (- e s) 1)
       :else (let [slash (.indexOf text "/" (int s))]
               (and (or (Character/isLetter c) (#{\* \! \_ \? \< \> \= \-} c))
                    (< s slash (dec e))
                    (neg? (.indexOf (subs text s e) ":"))))))))

(def ^:private closer-of
  "The closer of each opener."
  {\( \) \[ \] \{ \}})

;;; The scan

(defn- scan
  "One pass over `text`: the forgiven text, each top-level form's span
  `[start end line column]` in it, the repairs, and whether `text`'s own
  delimiters balance. Prose rules apply when `reply?`; delimiters are
  repaired when `repair?`, else kept as written."
  {:malli/schema [:=> [:cat :string :boolean :boolean] [:map [:seon.batch/text :string] [:seon.batch/spans :seon.batch/spans]
                                                        [:seon.batch/repairs :seon.batch/repairs] [::balanced? :boolean]]]}
  [^String text reply? repair?]
  (let [n (.length text)
        out (StringBuilder. (+ n 16))
        ;; the openers of the open collections, outermost first
        open (StringBuilder.)
        spans (volatile! (transient []))
        repairs (volatile! (transient []))
        balanced (volatile! true)
        in-string (volatile! false)
        ;; the open top-level form: [out-start line column]
        form (volatile! nil)
        ;; the items the open top-level form still needs; just past the last
        ;; code item in `out`, and its line (kept only when repairing)
        marks (long-array [0 0 1])
        fence (volatile! nil)
        closers (fn [from] (let [sb (StringBuilder.)]
                             (loop [k (dec (.length open))]
                               (when (>= k from) (.append sb (char (closer-of (.charAt open k)))) (recur (dec k))))
                             (str sb)))
        matching (fn [c] (loop [k (dec (.length open))]
                           (cond (neg? k) -1 (= c (closer-of (.charAt open k))) k :else (recur (dec k)))))
        at-code! (fn [o line] (when repair? (aset marks 1 (long o)) (aset marks 2 (long line))))
        repair! (fn [line at k v] (vswap! repairs conj! {:seon.batch/line line :seon.batch/at at k v}))
        ;; closers inserted at `o` move every repair made after `o` by `d`
        shift! (fn [o d]
                 (loop [k (dec (count @repairs))]
                   (let [r (when (>= k 0) (nth @repairs k))]
                     (when (and r (>= (long (:seon.batch/at r)) (long o)))
                       (vswap! repairs assoc! k (update r :seon.batch/at + d))
                       (recur (dec k))))))
        start! (fn [o line ols] (vreset! form [o line (inc (- (long o) (long ols)))]) (aset marks 0 1))
        ;; an item ended at `o`: true when it completes the top-level form
        item! (fn [o line]
                (at-code! o line)
                (when (and @form (zero? (.length open)) (zero? (aset marks 0 (dec (aget marks 0)))))
                  (let [[s l c] @form] (vswap! spans conj! [s o l c]) (vreset! form nil) true)))
        prefix! (fn [arity] (when (zero? (.length open)) (aset marks 0 (+ (aget marks 0) (dec (long arity))))))
        close-all! (fn []
                     (let [o (aget marks 1)
                           line (aget marks 2)
                           cls (closers 0)]
                       (.insert out (int o) ^String cls)
                       (shift! o (count cls))
                       (repair! line o :seon.batch/inserted cls)
                       (.setLength open 0)
                       (aset marks 0 1)
                       (item! (+ o (count cls)) line)))
        code-line!
        (fn [^long ls ^long le ^long line trailing?]
          (let [ols (.length out)]
            (loop [i (long ls) copied (long ls) after false]
              (let [o (+ (.length out) (- i copied))]
                (cond
                  (>= i le) (.append out text (int copied) (int le))
                  @in-string
                  (case (.charAt text (int i))
                    \\ (recur (Math/min le (+ i 2)) copied after)
                    \" (do (vreset! in-string false) (recur (inc i) copied (or (item! (inc o) line) after)))
                    (recur (inc i) copied after))
                  :else
                  (let [c (.charAt text (int i))
                        d (when (< (inc i) le) (.charAt text (int (inc i))))
                        top? (and (nil? @form) (zero? (.length open)))]
                    (cond
                      (or (Character/isWhitespace c) (= \, c)) (recur (inc i) copied after)
                      (or (= \; c) (and (= \# c) (= \! d))) (.append out text (int copied) (int le))
                      ;; words after a form on its line become its comment
                      (and top? after trailing? (not (form-start? text i le)))
                      (do (.append out text (int copied) (int i)) (.append out "; ") (.append out text (int i) (int le)))
                      :else
                      (do
                        (when top? (start! o line ols))
                        (case c
                          \" (do (vreset! in-string true) (recur (inc i) copied after))
                          (\( \[ \{) (do (.append open c) (at-code! (inc o) line) (recur (inc i) copied after))
                          (\) \] \})
                          (let [m (long (matching c))
                                top (dec (.length open))]
                            (cond
                              (and (>= m 0) (= m top))
                              (do (.setLength open m)
                                  (at-code! (inc o) line)
                                  (recur (inc i) copied (or (item! (inc o) line) after)))
                              (not repair?)
                              ;; kept as written: the reader answers it
                              (do (vreset! balanced false)
                                  (if (neg? top) (aset marks 0 (inc (aget marks 0))) (.setLength open top))
                                  (recur (inc i) copied (or (item! (inc o) line) after)))
                              (>= m 0)
                              (let [cls (closers (inc m))]
                                (.append out text (int copied) (int i))
                                (repair! line (.length out) :seon.batch/inserted cls)
                                (.append out ^String cls)
                                (.setLength open m)
                                (recur (inc i) i (or (item! (+ (.length out) 1) line) after)))
                              :else
                              (do (.append out text (int copied) (int i))
                                  (repair! line (.length out) :seon.batch/dropped (str c))
                                  (when top? (vreset! form nil) (aset marks 0 0))
                                  (recur (inc i) (inc i) after))))
                          \\ (let [j (token-end text (Math/min (+ i 2) le) le)] (recur j copied (or (item! (+ o (- j i)) line) after)))
                          (\' \@ \`) (do (prefix! 1) (recur (inc i) copied after))
                          \~ (do (prefix! 1) (recur (if (= \@ d) (+ i 2) (inc i)) copied after))
                          \^ (do (prefix! 2) (recur (inc i) copied after))
                          \# (case d
                               (\( \{) (do (.append open (char d)) (at-code! (+ o 2) line) (recur (+ i 2) copied after))
                               \" (do (vreset! in-string true) (recur (+ i 2) copied after))
                               ;; a discard starting a form is that no-value
                               ;; construct; any other needs one more item
                               \_ (do (when-not top? (prefix! 2)) (recur (+ i 2) copied after))
                               (\' \=) (do (prefix! 1) (recur (+ i 2) copied after))
                               \^ (do (prefix! 2) (recur (+ i 2) copied after))
                               \? (do (prefix! 1) (recur (if (and (< (+ i 2) le) (= \@ (.charAt text (int (+ i 2))))) (+ i 3) (+ i 2)) copied after))
                               \# (let [j (token-end text (+ i 2) le)] (recur j copied (or (item! (+ o (- j i)) line) after)))
                               (let [j (token-end text (inc i) le)]
                                 (if (or (= \: d) (and d (> j (inc i)) (not (Character/isDigit ^char d))))
                                   ;; a tag or namespaced-map prefix before its form
                                   (do (prefix! 1) (recur j copied after))
                                   (recur j copied (or (item! (+ o (- j i)) line) after)))))
                          (let [j (token-end text (inc i) le)] (recur j copied (or (item! (+ o (- j i)) line) after))))))))))))
        prose! (fn [ls le] (.append out "; ") (.append out text (int ls) (int le)))
        line!
        (fn line! [^long ls ^long le ^long line]
          (let [s (non-blank text ls le)
                [kind ticks] @fence]
            (cond
              @in-string (code-line! ls le line false)
              ;; a fence line is never a prefix's operand: the form ends
              ;; before it, its read answering what is missing
              (and @form (zero? (.length open)) (code-fence-line? text ls le))
              (let [[s l c] @form] (vswap! spans conj! [s (.length out) l c]) (vreset! form nil) (line! ls le line))
              (or @form (pos? (.length open)))
              (if (and repair? (pos? (.length open)) (< s le)
                       (or (code-fence-line? text ls le)
                           (and (= s ls) (not (#{\) \] \} \;} (.charAt text (int s)))))))
                (do (close-all!) (line! ls le line))
                (code-line! ls le line (and reply? (not= :code kind))))
              (>= s le) (.append out text (int ls) (int le))
              (not reply?) (when-not (code-fence-line? text ls le) (code-line! ls le line false))
              :else
              (if-let [[t tag] (fence-line text ls le)]
                (cond (nil? kind) (vreset! fence [(cond (code-tags tag) :code (= "" tag) :lines :else :prose) t])
                      (and (= "" tag) (>= t ticks)) (vreset! fence nil)
                      (= :code kind) (code-line! ls le line false)
                      :else (prose! ls le))
                (cond (= :code kind) (code-line! ls le line false)
                      (= :prose kind) (prose! ls le)
                      (= \; (.charAt text (int s))) (.append out text (int ls) (int le))
                      (or (form-start? text s le) (literal-line? text s le)) (code-line! ls le line true)
                      :else (prose! ls le))))))]
    (let [last-line (loop [ls 0 line 1]
                      (if (< ls n)
                        (let [le (line-end text ls)]
                          (line! ls le line)
                          (when (< le n) (.append out \newline))
                          (recur (inc le) (inc line)))
                        (dec line)))]
      (when @in-string
        (vreset! balanced false)
        (when repair?
          (repair! last-line (.length out) :seon.batch/inserted "\"")
          (.append out \")
          (vreset! in-string false)
          (item! (.length out) last-line))))
    (when (pos? (.length open))
      (vreset! balanced false)
      (when repair? (close-all!)))
    (when-let [[s l c] @form]
      (vswap! spans conj! [s (.length out) l c]))
    {:seon.batch/text (str out) :seon.batch/spans (persistent! @spans)
     :seon.batch/repairs (persistent! @repairs) ::balanced? @balanced}))

(defn forgive
  "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.

  Repairs are present only when its delimiters did not balance; a balanced
  text keeps every delimiter as written."
  {:malli/schema [:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven]}
  [text mode]
  (let [reply? (= :reply mode)
        strict (scan text reply? false)
        {r :seon.batch/repairs :as result} (if (::balanced? strict) strict (scan text reply? true))]
    (cond-> (dissoc result ::balanced? :seon.batch/repairs)
      (seq r) (assoc :seon.batch/repairs r))))

;;; The stepper

(defn- span-reader
  "A reader over the span `[s e line column]` of `text` that numbers lines and columns as `text` does."
  {:malli/schema [:=> [:cat :string :seon.batch/span] :seon.batch/reader]}
  [^String text [s e line column]]
  (rt/->IndexingPushbackReader (rt/push-back-reader (java.io.StringReader. (subs text s e))) line column (= 1 column) nil 0 nil false))

(defn step
  "The next read step at the cursor in `mode`.

  The first step forgives the text; each step then reads the next form's span
  once. A failed read's step carries the reader's own failure."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/mode :seon.batch/read-state] :seon.batch/read-step]}
  [stage mode {v :seon.batch/version}]
  (let [eof (Object.)]
    (loop [v (if (find v :seon.batch/spans) v (merge v (forgive (:seon.batch/text v) mode)))]
      (if-let [[s e :as span] (first (:seon.batch/spans v))]
        (let [v' (-> v (assoc :seon.batch/pos e) (update :seon.batch/spans subvec 1))
              [form failure] (try [((:seon.batch/read stage) (span-reader (:seon.batch/text v) span) eof)]
                                  (catch Throwable t [nil t]))]
          (cond failure {:seon.batch/read-error failure :seon.batch/start s :seon.batch/end e :seon.batch/version v'}
                (identical? eof form) (recur v')
                :else {:seon.batch/form form :seon.batch/start s :seon.batch/end e :seon.batch/version v'}))
        {:seon.batch/eof true :seon.batch/version v}))))

(defn read-state
  "The initial read state of `text`."
  {:malli/schema [:=> [:cat :string] :seon.batch/read-state]}
  [text]
  {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}})

(defn skip-to
  "The offset of the next form start from `pos`.

  Skips whitespace, commas, `;` comments and fence lines (a line whose first
  characters are three backticks or tildes)."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text pos]
  (let [n (count text)]
    (loop [p (long pos)
           ;; boxed: `recur` passes literal booleans
           line-blank? (identity (or (zero? pos) (= \newline (.charAt text (int (dec pos))))))]
      (if (>= p n)
        n
        (let [c (.charAt text (int p))]
          (cond
            (= \newline c) (recur (inc p) true)
            (or (Character/isWhitespace c) (= \, c)) (recur (inc p) line-blank?)
            (or (= \; c) (and line-blank? (or (.startsWith text "```" (int p)) (.startsWith text "~~~" (int p)))))
            (recur (line-end text p) false)
            :else p))))))

(defn- carried
  "The read-error carrier of the error `value`."
  {:malli/schema [:=> [:cat :seon.sci.reader/failure] :seon.error/throwable]}
  [value]
  (ex-info (:seon.error/message value) {:seon.batch/error-value value}))

(defn next-form
  "The next read step with the live `stage`.

  A failed read is that span's read error, carried as its error value."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/mode :seon.batch/read-state] :seon.batch/read-step]}
  [stage mode state]
  (let [{:seon.batch/keys [read-error start end version] :as r} (step stage mode state)]
    (if read-error
      (assoc r :seon.batch/read-error (carried (reader/reader-error (subs (:seon.batch/text version) start end) read-error)))
      r)))

(defn failure-step
  "The read-error step of a host read that failed at `start`.

  Its span is the construct there as the scan finds it, delimiters as
  written; its error value is the reader's error of that span."
  {:malli/schema [:=> [:cat :string nat-int? :seon.error/throwable] :seon.batch/read-step]}
  [text start failure]
  (let [[_ e] (first (:seon.batch/spans (scan (subs text start) false false)))
        end (+ start (or e (- (count text) start)))]
    {:seon.batch/read-error (carried (reader/reader-error (subs text start end) failure))
     :seon.batch/start start :seon.batch/end end
     :seon.batch/version {:seon.batch/text text :seon.batch/pos end}}))

;;; Segments

(def ^:private conditional-options
  "edamame with every semantic hook neutral, reader conditionals preserved."
  (edamame/normalize-opts {:all true :read-cond :preserve :features #{:clj}
                           :auto-resolve (constantly 'seon.sci.forgive.trial)
                           :suppress-read true :location? (constantly false) :end-location false}))

(defn- no-value?
  "Whether the source reads no value: a discard, or a reader conditional selecting no `:clj` branch."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [source]
  (boolean
   (or (str/starts-with? source "#_")
       (and (str/starts-with? source "#?")
            (let [form (try (edamame/parse-string source conditional-options) (catch Exception _ nil))]
              (and (reader-conditional? form) (not-any? #{:clj :default} (take-nth 2 (:form form)))))))))

(defn- comment-of
  "The text between two forms as comment lines: the `;` lines kept, blank,
  `;`-only and comma-only lines dropped."
  {:malli/schema [:=> [:cat :string] :string]}
  [^String between]
  (->> (str/split-lines between)
       (keep (fn [line]
               (let [l (str/trim line)]
                 (cond (or (= "" l) (every? #{\; \,} l)) nil
                       (str/starts-with? l ";") l
                       :else (str "; " l)))))
       (str/join "\n")))

(defn- owned
  "`segments` with each of `repairs`, in offset order, on the last segment
  starting at or before its offset (`starts` are the segments' offsets in
  the forgiven text), else on the first."
  {:malli/schema [:=> [:cat [:vector :map] [:vector nat-int?] [:maybe :seon.batch/repairs]] [:vector :map]]}
  [segments starts repairs]
  (if (empty? segments)
    segments
    (let [starts (long-array starts)]
      (reduce (fn [out {at :seon.batch/at :as repair}]
                (let [k (java.util.Arrays/binarySearch starts (long at))]
                  (update-in out [(if (neg? k) (max 0 (- -2 k)) k) :seon.batch/repairs] (fnil conj []) repair)))
              segments repairs))))

(defn segments
  "Each form's source in the forgiven text, the text above it as its comment.

  The text's repairs ride beside the segments in text order, and each segment
  carries those made from its start to the next segment's. `mode` is `:reply`
  by default; a failed read's span is one more source, whose live read answers
  its error."
  {:malli/schema [:function
                  [:=> [:cat :string] :seon.batch/segmented]
                  [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]]}
  ([text] (segments text :reply))
  ([text mode]
   (let [{t :seon.batch/text spans :seon.batch/spans repairs :seon.batch/repairs} (forgive text mode)
         repairs (some->> repairs (sort-by :seon.batch/at) vec)]
     (loop [[[s e] & more] spans prev 0 out [] starts []]
       (if-not s
         (cond-> {:seon.batch/segments (owned out starts repairs)} repairs (assoc :seon.batch/repairs repairs))
         (let [source (str/trimr (subs t s e))
               c (when (< (non-blank t prev s) (long s)) (comment-of (subs t prev s)))
               skip? (no-value? source)]
           (recur more e
                  (if skip? out (conj out (cond-> {:seon.batch/source source} (seq c) (assoc :seon.batch/comment c))))
                  (if skip? starts (conj starts s)))))))))
