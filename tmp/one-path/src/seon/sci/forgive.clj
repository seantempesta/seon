(ns seon.sci.forgive
  "The forgiving input stage in front of the one reader.

  One reader cursor per text version. `next-form` reads ONE form at the
  cursor with the caller's reader (SCI's, under the live resolver). Only when
  that read fails, or, in a model reply, when a bare token does not stand
  alone on its line, does it repair the text at that point and read again
  from the same offset:

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
  {:malli/schema [:=> [:cat :string] [:maybe [:vector :any]]]}
  [^String text]
  (try
    (let [r (edamame/reader text)]
      (loop [out []]
        (let [f (edamame/parse-next r structural-options)]
          (if (= ::eof f) out (recur (conj out f))))))
    (catch Throwable _ nil)))

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

(defn- structured? [form ^String source]
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
          marker (when begins-line? (re-find prompt (subs text start le)))
          done (fn [m] (assoc m :seon.batch/version v :seon.batch/repairs repairs))
          step
          (cond
            (> guard 64) (done {:seon.batch/unread-from start :seon.batch/reason :too-many-repairs})
            (>= start (count text)) (done {:seon.batch/eof true})
            marker
            [:redo (str (subs text 0 start) (subs text (+ start (count marker)))) start
             {:seon.batch/repair-kind :prompt-marker}]
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
                (let [msg (str (ex-message (:error r)))
                      ;; a structural error in the rest (not the live reader's message,
                      ;; which can name an alias or tag past an unclosed form)
                      fixed (when (nil? (structural-forms (subs text start)))
                              (delimiter-repair text start))]
                  (cond
                    fixed [:redo (:text fixed) start (:repair fixed)]
                    (and (= :reply mode) (re-find prose-read-failure msg)
                         (not (code-line? (subs text ls le))))
                    (let [{t :text rep :repair} (comment-rest-of-line text start)]
                      [:redo t start rep])
                    :else (done {:seon.batch/unread-from start :seon.batch/reason msg}))))))]
      (if (vector? step)
        (let [[_ t at rep] step] (recur (version t at) (conj repairs rep) (inc guard)))
        step))))

(defn read-state
  "The initial read state of `text`."
  {:malli/schema [:=> [:cat :string] :seon.batch/read-state]}
  [text]
  {:seon.batch/version (version text 0) :seon.batch/structured? (structure? text)
   :seon.batch/repairs [] :seon.batch/last-code-end nil})

(defn forms
  "Every form of `text` through `next-form`, read only.

  Answers the accepted sources, the repaired text, the repairs and where
  reading stopped (tests and the refresh classifier)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map [:enum :reply :code] :string] :map]}
  [ctx opts mode text]
  (loop [state (read-state text) out []]
    (let [r (next-form ctx opts mode state)
          v (:seon.batch/version r)
          t (:seon.batch/text v)]
      (if (or (:seon.batch/eof r) (contains? r :seon.batch/unread-from))
        (cond-> {:forms out :text t :repairs (:seon.batch/repairs r)}
          (contains? r :seon.batch/unread-from)
          (assoc :unread-from (:seon.batch/unread-from r) :reason (:seon.batch/reason r)))
        (recur (assoc state :seon.batch/version v :seon.batch/repairs (:seon.batch/repairs r)
                      :seon.batch/last-code-end (:seon.batch/end r))
               (conj out (str/trimr (subs t (:seon.batch/start r) (:seon.batch/end r)))))))))
