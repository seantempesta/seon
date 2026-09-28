(ns seon.sci.forgive
  "The forgiving input stage in front of the one reader.

  One reader cursor per text version. `next-form` reads ONE form at the
  cursor with the caller's reader (SCI's, under the live resolver). Only when
  that read fails, or, in a model reply, when a bare token does not stand
  alone on its line, does it repair the text at that point and read again
  from the same offset. Only a model reply (`:reply`) is repaired; code
  (`:code`) is read as written:

    prose line          -> `; prose` comment line (a structured code suffix on
                           the line stays code, on its own line)
    prompt marker       -> removed (`user=> (f 1)`)
    unbalanced closers  -> Parinfer indent mode, accepted only when the rest
                           of the text then reads without a structural error
    anything else       -> unread from here, as data

  Trials (is the rest balanced? is this suffix all collections?) use a
  structural reader: edamame with every semantic hook neutral (any alias, tags
  unread, no evaluation), so a tag reader or an alias ahead of the cursor is
  never invoked or resolved before the forms before it have run. The one
  semantic read of each accepted form happens at the advancing cursor.

  A repair makes a new text version (new line index, new cursor advanced to
  the offset): cost is proportional to the text once per repair, never per
  form. The repaired text is what is evaluated, stored and rendered.

  ## Costs

  - 52 recorded replies (7 run4, 18 stored, 18 reply_test, 9 by hand): 69 ms
    total, max 35 ms on 12 KB (default, 2026-09-27, unarmed)."
  (:require [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [edamame.core :as edamame]
            [parinferish.core :as parinferish]
            [sci.core :as sci]))

(defn- line-starts
  {:malli/schema [:=> [:cat :string] [:vector nat-int?]]}
  [^String text]
  (into [0] (keep-indexed (fn [i ch] (when (= \newline ch) (inc i)))) text))

(defn version
  "A text version: the text, its line index and a reader cursor at `pos`."
  {:malli/schema [:=> [:cat :string nat-int?] :seon.batch/text-version]}
  [^String text pos]
  (let [rdr (sci/source-reader text)]
    (dotimes [_ pos] (rt/read-char rdr))
    {:seon.batch/text text :seon.batch/line-starts (line-starts text)
     :seon.batch/reader rdr :seon.batch/pos pos}))

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
        {text :seon.batch/text starts :seon.batch/line-starts rdr :seon.batch/reader pos :seon.batch/pos} v]
    (try
      (let [form (sci/parse-next ctx rdr (assoc opts :eof eof))]
        (if (identical? eof form)
          {:eof true}
          (let [l (sci/get-line-number rdr) c (sci/get-column-number rdr)
                end (min (count text) (+ (get starts (dec l) (count text)) (dec c)))]
            (if (> end pos)
              {:form form :end end :v (assoc v :seon.batch/pos end)}
              {:error (ex-info "The reader did not advance." {:seon.batch/pos pos})}))))
      (catch Throwable t {:error t}))))

(def ^:private structural-options
  "edamame with every semantic hook neutral: structure and token validity only."
  {:all true :read-cond :preserve :auto-resolve (constantly 'seon.sci.forgive.trial)
   :suppress-read true :eof ::eof})

(defn- structural-forms
  "Every top-level form of `text` read structurally, or nil when the text has
  a structural error. Invokes no tag reader and resolves no alias."
  {:malli/schema [:=> [:cat :string] [:maybe [:vector :seon.batch/form]]]}
  [^String text]
  (try
    (let [r (edamame/reader text)]
      (loop [out []]
        (let [f (edamame/parse-next r structural-options)]
          (if (= ::eof f) out (recur (conj out f))))))
    (catch Throwable _ nil)))

(defn- first-form-structural?
  "Whether the first form of `text` (or its end) reads structurally: no tag
  reader invoked, no alias resolved."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String text]
  (try (edamame/parse-next (edamame/reader text) structural-options) true
       (catch Throwable _ false)))

(defn- code-suffix
  "The offset, within `tail`, of a suffix whose every form is a collection
  (today's readable-code-suffix rule), or nil. Structural reads only."
  {:malli/schema [:=> [:cat :string] [:maybe nat-int?]]}
  [^String tail]
  (some (fn [i]
          (let [forms (structural-forms (subs tail i))]
            (when (and (seq forms) (every? coll? forms)) i)))
        (keep-indexed (fn [i ch] (when (and (pos? i) (#{\( \[ \{} ch)) i)) tail)))

(defn- comment-rest-of-line
  "The text with everything from `start` to the end of its line made a `;`
  comment, keeping a structured code suffix as code on its own line. The
  commented text begins with `;`, so the next read advances."
  {:malli/schema [:=> [:cat :string nat-int?] [:map [:text :string] [:repair :seon.batch/repair]]]}
  [^String text start]
  (let [[_ le] (line-bounds text start)
        tail (subs text start le)
        suffix (code-suffix tail)
        prose (str/trim (if suffix (subs tail 0 suffix) tail))]
    {:text (str (subs text 0 start) "; " prose (when suffix (str "\n" (subs tail suffix))) (subs text le))
     :repair {:seon.batch/repair-kind :prose :seon.batch/prose prose}}))

(defn- delimiter-repair
  "Parinfer indent mode over the rest of the text, accepted only if it changed
  the text and the result has no structural error."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe [:map [:text :string] [:repair :seon.batch/repair]]]]}
  [^String text pos]
  (let [rest-text (subs text pos)
        parsed (parinferish/parse rest-text {:mode :indent})
        candidate (parinferish/flatten parsed)]
    (when (and (not (:error? (meta parsed))) (not= candidate rest-text)
               (some? (structural-forms candidate)))
      {:text (str (subs text 0 pos) candidate)
       :repair {:seon.batch/repair-kind :delimiters :seon.batch/at pos}})))

(def ^:private prompt #"^[A-Za-z][\w.\-]*=>[ \t]*")

(defn structure?
  "Whether a reply holds any line that begins with a collection.

  Then a bare token alone on its line is code. One linear pass per reply."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String text]
  (boolean (some #(re-find #"^[\(\[\{]" (str/triml %)) (str/split-lines text))))

(def ^:private prose-read-failure #"^Invalid (?:number|symbol|keyword|token)")

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
  (possibly repaired) :seon.batch/version and the accumulated
  :seon.batch/repairs. `state` is :seon.batch/read-state."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map [:enum :reply :code] :seon.batch/read-state]
                  :seon.batch/read-step]}
  [ctx opts mode {v :seon.batch/version repairs :seon.batch/repairs
                  structured-reply? :seon.batch/structured? last-code-end :seon.batch/last-code-end}]
  (loop [v v repairs repairs guard 0]
    (let [text (:seon.batch/text v)
          start (skip-to text (:seon.batch/pos v))
          v (advance v start)
          [ls le] (line-bounds text start)
          begins-line? (str/blank? (subs text ls start))
          follows-code? (and last-code-end (<= last-code-end start)
                             (str/blank? (subs text last-code-end start))
                             (not (str/includes? (subs text last-code-end start) "\n")))
          ;; code is read as written: only a model reply is repaired
          reply? (= :reply mode)
          marker (when (and reply? begins-line?) (re-find prompt (subs text start le)))
          done (fn [m] (assoc m :seon.batch/version v :seon.batch/repairs repairs))
          fixed (delay (when (and reply? (< start (count text)) (not (first-form-structural? (subs text start))))
                         (delimiter-repair text start)))
          step
          (cond
            (> guard 64) (done {:seon.batch/unread-from start :seon.batch/reason :too-many-repairs})
            (>= start (count text)) (done {:seon.batch/eof true})
            marker
            [:redo (str (subs text 0 start) (subs text (+ start (count marker)))) start
             {:seon.batch/repair-kind :prompt-marker}]
            ;; the delimiter decision is structural and comes first, so the one
            ;; semantic read (tag readers, alias resolution) runs once, on the
            ;; accepted form
            @fixed
            [:redo (:text @fixed) start (:repair @fixed)]
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
                  (let [{t :text rep :repair} (comment-rest-of-line text start)]
                    [:redo t start rep]))
                :else
                (let [msg (str (ex-message (:error r)))]
                  (cond
                    (and (= :reply mode) (re-find prose-read-failure msg)
                         (not (code-line? (subs text ls le))))
                    (let [{t :text rep :repair} (comment-rest-of-line text start)]
                      [:redo t start rep])
                    :else (done {:seon.batch/unread-from start :seon.batch/reason msg
                                 :seon.batch/cause (:error r)}))))))]
      (if (vector? step)
        (let [[_ t at rep] step] (recur (version t at) (conj repairs rep) (inc guard)))
        step))))

(defn read-state
  "The initial read state of `text`."
  {:malli/schema [:=> [:cat :string] :seon.batch/read-state]}
  [text]
  {:seon.batch/version (version text 0) :seon.batch/structured? (structure? text)
   :seon.batch/repairs []})

(defn forms
  "Every form of `text` through `next-form`, read only.

  Answers the accepted sources with their spans in the repaired text, the
  repaired text, the repairs and where reading stopped (tests, the refresh
  classifier and the structural segmentation of a reply)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map [:enum :reply :code] :string] :map]}
  [ctx opts mode text]
  (loop [state (read-state text) out [] spans []]
    (let [r (next-form ctx opts mode state)
          v (:seon.batch/version r)
          t (:seon.batch/text v)]
      (if (or (:seon.batch/eof r) (contains? r :seon.batch/unread-from))
        (cond-> {:forms out :spans spans :text t :repairs (:seon.batch/repairs r)}
          (contains? r :seon.batch/unread-from)
          (assoc :unread-from (:seon.batch/unread-from r) :reason (:seon.batch/reason r)))
        (recur (assoc state :seon.batch/version v :seon.batch/repairs (:seon.batch/repairs r)
                      :seon.batch/last-code-end (:seon.batch/end r))
               (conj out (str/trimr (subs t (:seon.batch/start r) (:seon.batch/end r))))
               (conj spans [(:seon.batch/start r) (:seon.batch/end r)]))))))

(def ^:private structural-ctx
  "A bare SCI context for structural reads: they resolve no alias and invoke
  no tag reader, so they need no live program."
  (delay (sci/init {})))

(defn- comment-of
  "The prose before a form as its comment: the `;` lines between the previous
  form and this one, their markers removed; fence lines dropped."
  {:malli/schema [:=> [:cat :string] :string]}
  [^String between]
  (->> (str/split-lines between)
       (map str/trim)
       (remove fence-line?)
       (keep (fn [l] (when (str/starts-with? l ";") (str/trim (str/replace-first l #"^;+" "")))))
       (remove str/blank?)
       (str/join "\n")))

(defn- balanced-end
  "The offset just past the balanced collection starting at `start` (strings,
  character literals and `;` comments skipped), after any dispatch or quote
  prefix (`#`, `#=`, `'`, `` ` ``, `~`, `@`), or nil when `start` opens no
  collection or it never closes."
  {:malli/schema [:=> [:cat :string nat-int?] [:maybe nat-int?]]}
  [^String text start]
  (when-let [open (loop [i start]
                    (when (< i (count text))
                      (let [c (.charAt text (int i))]
                        (cond (#{\( \[ \{} c) i
                              (#{\# \= \' \` \~ \@} c) (recur (inc i))
                              :else nil))))]
    (loop [i open depth 0]
      (when (< i (count text))
        (let [c (.charAt text (int i))]
          (case c
            \" (let [close (loop [j (inc i)]
                             (cond (>= j (count text)) nil
                                   (= \\ (.charAt text (int j))) (recur (+ j 2))
                                   (= \" (.charAt text (int j))) j
                                   :else (recur (inc j))))]
                 (when close (recur (inc close) depth)))
            \\ (recur (+ i 2) depth)
            \; (let [nl (.indexOf text "\n" (int i))] (if (neg? nl) nil (recur (inc nl) depth)))
            (\( \[ \{) (recur (inc i) (inc depth))
            (\) \] \}) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
            (recur (inc i) depth)))))))

(defn segments
  "A reply's forms (`:reply`, the default: prose becomes comments) or code's
  (`:code`: every form is a form, nothing is repaired) read structurally (edamame, every semantic hook neutral:
  tags unread, any alias), with the forgiving stage's repairs: each form's
  source text and the prose above it as its comment. A balanced form the
  structural read refuses (an odd map) is one form whose evaluation-time read
  answers its error once, and reading continues after it; an unbalanced rest
  is the unread rest. The one semantic read of each form happens later, at
  the live cursor, when it is evaluated.

  ## Costs

  - 52 recorded replies: 64 ms total (default, 2026-09-28, unarmed)."
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
