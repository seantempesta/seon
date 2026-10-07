(ns seon.sci.forgive
  "The reader stage in front of evaluation: one stepper, `step`, for a model
  reply (`:reply`) and code (`:code`), with the read itself supplied as a
  value (`:seon.batch/read-stage`).

  A reply is read region by region (the text between fence lines). A code
  fence's lines are code; a non-code fence's lines are prose. Outside fences
  a line whose first character starts a form is code to the end of every form
  on it; every other line is prose, which becomes the next form's comment. A
  failed read is its own step, and reading continues after it: past the
  closer of the collection open at the failure, else where the reader
  stopped, never past its region's end. `#_` and a reader conditional that
  selects nothing are skipped inside their region. Nothing is repaired."
  (:require [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [edamame.core :as edamame]
            [sci.core :as sci]
            [seon.sci.reader :as reader]))

;;; Lines and regions

(defn- line-end
  "The offset of the newline ending the line at `pos`, else the text's end."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text pos]
  (let [i (.indexOf text "\n" (int pos))] (if (neg? i) (count text) i)))

(defn- line-start
  "The offset where the line holding `pos` starts."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text pos]
  (inc (.lastIndexOf text "\n" (int (max 0 (dec pos))))))

(defn- non-blank
  "The first offset from `pos` below `limit` that is not whitespace, else `limit`."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] nat-int?]}
  [^String text pos limit]
  (loop [i (long pos)]
    (if (and (< i limit) (Character/isWhitespace (.charAt text (int i)))) (recur (inc i)) i)))

(defn- fence-line
  "The backtick count and lowercased first word of the fence line `[ls le)`, or nil."
  {:malli/schema [:=> [:cat :string nat-int? nat-int?] [:maybe [:tuple pos-int? :string]]]}
  [^String text ls le]
  (let [s (non-blank text ls le)
        e (loop [i s] (if (and (< i le) (= \` (.charAt text (int i)))) (recur (inc i)) i))
        tag-start (non-blank text e le)
        tag-end (loop [i tag-start] (if (and (< i le) (not (Character/isWhitespace (.charAt text (int i))))) (recur (inc i)) i))]
    (when (>= (- e s) 3)
      [(- e s) (str/lower-case (subs text tag-start tag-end))])))

(defn- region-end
  "The start of the first line from the line start `pos` that ends the region:
  any fence line when `ticks` is nil, else an untagged line of at least
  `ticks` backticks; the text's end when none does."
  {:malli/schema [:=> [:cat :string nat-int? [:maybe pos-int?]] nat-int?]}
  [^String text pos ticks]
  (let [n (count text)]
    (loop [ls (long pos)]
      (if (>= ls n)
        n
        (let [le (line-end text ls)
              [t tag] (when (.startsWith text "```" (int (non-blank text ls le))) (fence-line text ls le))]
          (if (and t (or (nil? ticks) (and (= "" tag) (>= t ticks)))) ls (recur (inc le))))))))

(def ^:private code-tags
  "The fence tags whose body is code; an untagged fence is code too."
  #{"" "clojure" "clj" "cljc" "edn"})

(defn- form-start?
  "Whether the line character at `s` starts a form outside a fence: a
  collection, quote, deref or meta character, or `#` before a non-blank
  character other than `#`."
  {:malli/schema [:=> [:cat :string nat-int?] :boolean]}
  [^String text s]
  (let [c (.charAt text (int s))
        d (when (< (inc s) (count text)) (.charAt text (int (inc s))))]
    (boolean (or (#{\( \[ \{ \' \@ \^} c)
                 (and (= \# c) d (not= \# d) (not (Character/isWhitespace ^char d)))))))

(defn- skip-blank
  "The first offset from `pos` that is neither whitespace nor a comma."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text pos]
  (loop [p (long pos)]
    (if (and (< p (count text)) (let [c (.charAt text (int p))] (or (Character/isWhitespace c) (= \, c))))
      (recur (inc p))
      p)))

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

;;; The cursor

(defn- advance
  "The version with its reader moved forward to `pos`."
  {:malli/schema [:=> [:cat :seon.batch/text-version nat-int?] :seon.batch/text-version]}
  [v pos]
  (dotimes [_ (- pos (:seon.batch/pos v))] (rt/read-char (:seon.batch/reader v)))
  (assoc v :seon.batch/pos pos))

(defn- at
  "The version moved to `pos`: its reader advanced there, or dropped when it
  cannot move forward to it."
  {:malli/schema [:=> [:cat :seon.batch/text-version nat-int?] :seon.batch/text-version]}
  [v pos]
  (cond (= pos (:seon.batch/pos v)) v
        (and (:seon.batch/reader v) (> pos (:seon.batch/pos v))) (advance v pos)
        :else (-> v (assoc :seon.batch/pos pos) (dissoc :seon.batch/reader))))

(defn- reader-at
  "The version with a reader at `pos` that ends at its region's bound: its
  own, advanced, when it has one at or before `pos`, else one built once."
  {:malli/schema [:=> [:cat :seon.batch/text-version nat-int?] :seon.batch/text-version]}
  [v pos]
  (if (and (:seon.batch/reader v) (<= (:seon.batch/pos v) pos))
    (advance v pos)
    (let [^String text (:seon.batch/text v)]
      (assoc v :seon.batch/pos pos
             :seon.batch/reader (sci/reader (java.io.StringReader. (subs text pos (:seon.batch/bound v (count text)))))))))

(defn- string-end
  "The offset of the quote closing the string opened at `open`, or nil."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe nat-int?]]}
  [^String text open]
  (loop [j (inc (long open))]
    (cond (>= j (count text)) nil
          (= \\ (.charAt text (int j))) (recur (+ j 2))
          (= \" (.charAt text (int j))) j
          :else (recur (inc j)))))

(defn- closer-end
  "Just past the closer matching the collection opener at `open` (strings,
  characters and comments skipped), or nil when it never closes."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe nat-int?]]}
  [^String text open]
  (loop [i (long open) depth 0]
    (when (< i (count text))
      (case (.charAt text (int i))
        \" (when-let [c (string-end text i)] (recur (inc (long c)) depth))
        \\ (recur (+ i 2) depth)
        \; (let [nl (.indexOf text "\n" (int i))] (when-not (neg? nl) (recur (inc nl) depth)))
        (\( \[ \{) (recur (inc i) (inc depth))
        (\) \] \}) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
        (recur (inc i) depth)))))

(defn- outermost-opener
  "The line and column of the outermost collection open when a read failed
  (edamame's containers, outermost last), or nil."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:maybe [:tuple pos-int? pos-int?]]]}
  [failure]
  (when-let [c (peek (:edamame/containers (ex-data failure)))]
    [(:edamame/row c) (:edamame/column c)]))

(defn- read-one
  "One read with the stage at the version's cursor, as a read step.

  A failure spans past the closer of the outermost collection open at it (the
  text's end when that never closes), and at least to where the reader stopped."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/text-version] :seon.batch/read-step]}
  [stage v]
  (let [eof (Object.)
        {^String text :seon.batch/text rdr :seon.batch/reader pos :seon.batch/pos} v
        n (count text)
        l0 (sci/get-line-number rdr) c0 (sci/get-column-number rdr)
        ;; the reader's lines and columns count from where it started
        offset (fn [l c]
                 (min n (if (= l l0)
                          (+ pos (- c c0))
                          (loop [i pos k (- l l0)]
                            (let [nl (.indexOf text "\n" (int i))]
                              (cond (neg? nl) n
                                    (= 1 k) (+ (inc nl) (dec c))
                                    :else (recur (inc nl) (dec k))))))))
        stopped #(offset (sci/get-line-number rdr) (sci/get-column-number rdr))]
    (try
      (let [form ((:seon.batch/read stage) rdr eof)
            end (stopped)]
        (if (identical? eof form)
          {:seon.batch/eof true :seon.batch/version v}
          {:seon.batch/form form :seon.batch/start pos :seon.batch/end end
           :seon.batch/version (assoc v :seon.batch/pos end)}))
      (catch Throwable t
        (let [stop (stopped)
              opener (some->> (outermost-opener t) (apply offset))]
          {:seon.batch/read-error t :seon.batch/start pos
           ;; never short of where the reader stopped, so its reader moves on, never back
           :seon.batch/end (max (if opener (or (closer-end text opener) n) stop) stop (inc pos))
           :seon.batch/version (assoc v :seon.batch/pos stop)})))))

(defn- no-value?
  "Whether a read form yields no value: a reader conditional selecting no `:clj` branch."
  {:malli/schema [:=> [:cat :seon.batch/form] :boolean]}
  [form]
  (and (reader-conditional? form) (not-any? #{:clj :default} (take-nth 2 (:form form)))))

(def ^:private token-end-chars
  "Characters that end a bare token, as the reader's token rule has them."
  #{\( \) \[ \] \{ \} \" \; \@ \^ \` \~ \\ \,})

(defn- token-end
  "The end of the bare token at `s`: the next whitespace or terminating character."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String text s]
  (loop [i (inc (long s))]
    (if (and (< i (count text)) (let [c (.charAt text (int i))] (not (or (Character/isWhitespace c) (token-end-chars c)))))
      (recur (inc i))
      i)))

(defn- span-step
  "A spans-only step over `[s e)` that takes no read."
  {:malli/schema [:=> [:cat :seon.batch/text-version nat-int? nat-int?] :seon.batch/read-step]}
  [v s e]
  {:seon.batch/form ::token :seon.batch/start s :seon.batch/end e :seon.batch/version (at v e)})

(defn- read-at
  "The step reading the construct at `s`: a form, a failed read clamped to the
  region, or a no-value construct's end as `::skip` (nil at the region's end)."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/text-version nat-int?]
                  [:or :seon.batch/read-step [:map [::skip [:maybe nat-int?]] [:seon.batch/version :seon.batch/text-version]]]]}
  [stage v s]
  (let [^String text (:seon.batch/text v)
        bound (:seon.batch/bound v (count text))
        c (.charAt text (int s))
        discard? (and (= \# c) (< (inc s) (count text)) (= \_ (.charAt text (int (inc s)))))
        spans? (:seon.batch/spans-only? stage)
        r (cond
            ;; a stray closer is one character; the live read answers its error
            (and spans? (#{\) \] \}} c)) (span-step v s (inc s))
            (and spans? (not (#{\( \[ \{ \" \\ \# \' \` \~ \@ \^} c))) (span-step v s (min (token-end text s) bound))
            :else (read-one stage (reader-at v (if discard? (+ s 2) s))))]
    (cond
      (:seon.batch/eof r) {::skip nil :seon.batch/version v}
      (:seon.batch/read-error r)
      (let [end (min (:seon.batch/end r) bound)]
        (assoc r :seon.batch/start s :seon.batch/end end :seon.batch/version (at (:seon.batch/version r) end)))
      (or discard? (no-value? (:seon.batch/form r))) {::skip (:seon.batch/end r) :seon.batch/version (:seon.batch/version r)}
      :else (assoc r :seon.batch/start s))))

;;; The stepper

(defn- enter
  "The version of the region from the line start `pos`: a fence body to its
  closing line when `fence` (`ticks` backticks), else to the next fence line."
  {:malli/schema [:=> [:cat :string nat-int? [:maybe :seon.batch/fence] [:maybe :seon.batch/ticks]] :seon.batch/text-version]}
  [text pos fence ticks]
  (cond-> {:seon.batch/text text :seon.batch/pos pos :seon.batch/bound (region-end text pos ticks)}
    fence (assoc :seon.batch/fence fence :seon.batch/ticks ticks)))

(defn- reply-step
  "The next step of a model reply under the line rules, region by region;
  `code-end` is where the last construct ended."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/text-version [:maybe nat-int?]] :seon.batch/read-step]}
  [stage v code-end]
  (let [^String text (:seon.batch/text v)
        n (count text)]
    (loop [v (if (:seon.batch/bound v) v (enter text (:seon.batch/pos v) nil nil))
           p (:seon.batch/pos v)
           code-end code-end]
      (let [{fence :seon.batch/fence bound :seon.batch/bound} v
            s (min (skip-blank text p) bound)]
        (cond
          (>= s n) {:seon.batch/eof true :seon.batch/version v}
          ;; a region ends at a fence line: close the open fence, or open one by its tag
          (>= s bound)
          (let [le (line-end text bound)
                [t tag] (fence-line text bound le)
                start (min n (inc le))]
            (recur (if fence (enter text start nil nil) (enter text start (if (contains? code-tags tag) :code :prose) t))
                   start code-end))
          (= :prose fence) (recur v bound code-end)
          ;; a code fence's lines, and the rest of a line a form started, are code
          (or (= :code fence) (and code-end (<= (line-start text s) code-end))
              (and (= s (line-start text s)) (form-start? text s)))
          (if (= \; (.charAt text (int s)))
            (recur v (inc (line-end text s)) code-end)
            (let [r (read-at stage v s)]
              (if (find r ::skip)
                (let [e (or (::skip r) bound)] (recur (:seon.batch/version r) e e))
                r)))
          :else (recur v (inc (line-end text s)) code-end))))))

(defn- code-step
  "The next step of code read as written: every construct is code."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/text-version] :seon.batch/read-step]}
  [stage v]
  (let [text (:seon.batch/text v)]
    (loop [v v]
      (let [s (skip-to text (:seon.batch/pos v))]
        (if (>= s (count text))
          {:seon.batch/eof true :seon.batch/version v}
          (let [r (read-at stage v s)]
            (if (find r ::skip) (recur (at (:seon.batch/version r) (or (::skip r) (count text)))) r)))))))

(defn step
  "The next read step at the cursor in `mode`.

  A failed read's step carries the reader's own failure."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/mode :seon.batch/read-state] :seon.batch/read-step]}
  [stage mode {v :seon.batch/version code-end :seon.batch/last-code-end}]
  (if (= :reply mode) (reply-step stage v code-end) (code-step stage v)))

(defn read-state
  "The initial read state of `text`."
  {:malli/schema [:=> [:cat :string] :seon.batch/read-state]}
  [text]
  {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}})

(defn- carried
  "The read-error carrier of the error `value`."
  {:malli/schema [:=> [:cat :seon.sci.reader/failure] :seon.error/throwable]}
  [value]
  (ex-info (:seon.error/message value) {:seon.batch/error-value value}))

(defn next-form
  "The next read step with the live `stage`.

  A failed read, or a reserved response map (`seon.sci.reader/response-map?`),
  is that span's read error, carried as its error value."
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/mode :seon.batch/read-state] :seon.batch/read-step]}
  [stage mode state]
  (let [{:seon.batch/keys [form read-error start end version] :as r} (step stage mode state)
        source #(subs (:seon.batch/text version) start end)]
    (cond
      read-error (assoc r :seon.batch/read-error (carried (reader/reader-error (source) read-error)))
      (reader/response-map? form)
      (-> r (dissoc :seon.batch/form) (assoc :seon.batch/read-error (carried (reader/response-error (source) form))))
      :else r)))

;;; The structural pass

(def ^:private structural-options
  "edamame with every semantic hook neutral, normalized once: spans only."
  (edamame/normalize-opts {:all true :read-cond :preserve :features #{:clj}
                           :auto-resolve (constantly 'seon.sci.forgive.trial)
                           :suppress-read true :eof ::eof
                           :location? (constantly false) :end-location false}))

(def ^:private structural-stage
  "The structural read stage: edamame under `structural-options`, and token
  spans by the reader's token rule with no read. Resolves no alias and
  invokes no tag reader."
  {:seon.batch/read (fn [rdr eof] (let [f (edamame/parse-next rdr structural-options)] (if (= ::eof f) eof f)))
   :seon.batch/spans-only? true})

(defn failure-step
  "The read-error step of a host read that failed at `start`.

  Its span is the structural construct there; its error value is the reader's
  error of that span."
  {:malli/schema [:=> [:cat :string nat-int? :seon.error/throwable] :seon.batch/read-step]}
  [text start failure]
  (let [end (:seon.batch/end (code-step structural-stage {:seon.batch/text text :seon.batch/pos start}) (count text))]
    {:seon.batch/read-error (carried (reader/reader-error (subs text start end) failure))
     :seon.batch/start start :seon.batch/end end
     :seon.batch/version {:seon.batch/text text :seon.batch/pos end}}))

(defn- comment-of
  "The text between two forms as comment lines: prose as `; line`, the
  agent's `;` lines kept, blank, `;`-only and fence lines dropped."
  {:malli/schema [:=> [:cat :string] :string]}
  [^String between]
  (->> (str/split-lines between)
       (keep (fn [line]
               (let [l (str/trim line)]
                 (cond (or (= "" l) (str/starts-with? l "```") (str/starts-with? l "~~~") (every? #{\;} l)) nil
                       (str/starts-with? l ";") l
                       :else (str "; " l)))))
       (str/join "\n")))

(defn segments
  "Each construct's source with the text above it as its comment.

  Read structurally in `mode` (`:reply` by default); a failed read's span is
  one more source, whose live read answers its error."
  {:malli/schema [:function
                  [:=> [:cat :string] :seon.batch/segmented]
                  [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]]}
  ([text] (segments text :reply))
  ([text mode]
   (loop [state (read-state text) prev 0 out []]
     (let [r (step structural-stage mode state)]
       (if (:seon.batch/eof r)
         {:seon.batch/segments out}
         (let [{:seon.batch/keys [start end]} r
               c (when (< (skip-blank text prev) start) (comment-of (subs text prev start)))]
           (recur (assoc state :seon.batch/version (:seon.batch/version r) :seon.batch/last-code-end end)
                  end
                  (conj out (cond-> {:seon.batch/source (str/trimr (subs text start end))}
                              (seq c) (assoc :seon.batch/comment c))))))))))
