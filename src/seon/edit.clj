(ns seon.edit
  "Pure structural and exact source transformations.

  This namespace deliberately has no cluster, database, filesystem, schema
  loading, or JVM-handler dependency so the edit hook can load it while Seon
  is down."
  (:require [rewrite-clj.zip :as z]))

(defn- flat-error
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- utf8-bytes
  [text]
  (alength (.getBytes ^String text java.nio.charset.StandardCharsets/UTF_8)))

(defn- next-char-index
  [^String text index]
  (+ index (Character/charCount (.codePointAt text index))))

(defn- previous-char-index
  [^String text index]
  (- index (Character/charCount (.codePointBefore text index))))

(defn- prefix-end-within
  [text start end byte-limit]
  (loop [index start
         used 0]
    (if (= index end)
      index
      (let [next-index (next-char-index text index)
            next-bytes (utf8-bytes (subs text index next-index))]
        (if (> (+ used next-bytes) byte-limit)
          index
          (recur next-index (+ used next-bytes)))))))

(defn- suffix-start-within
  [text start end byte-limit]
  (loop [index end
         used 0]
    (if (= index start)
      index
      (let [previous-index (previous-char-index text index)
            previous-bytes (utf8-bytes (subs text previous-index index))]
        (if (> (+ used previous-bytes) byte-limit)
          index
          (recur previous-index (+ used previous-bytes)))))))

(defn- context-window
  [source start end byte-limit]
  (let [changed-bytes (utf8-bytes (subs source start end))]
    (if (> changed-bytes byte-limit)
      (let [context-end (prefix-end-within source start end byte-limit)]
        {:my.edit/context (subs source start context-end)
         :my.edit/context-complete? false})
      (let [remaining (- byte-limit changed-bytes)
            left-budget (quot remaining 2)
            right-budget (- remaining left-budget)
            context-start (suffix-start-within source 0 start left-budget)
            context-end (prefix-end-within source end (count source)
                                           right-budget)
            unused-left (- left-budget
                           (utf8-bytes (subs source context-start start)))
            unused-right (- right-budget
                            (utf8-bytes (subs source end context-end)))
            final-start (suffix-start-within source 0 context-start
                                             unused-right)
            final-end (prefix-end-within source context-end (count source)
                                         unused-left)]
        {:my.edit/context (subs source final-start final-end)
         :my.edit/context-complete? true}))))

(defn- line-starts
  [^String source]
  (loop [index 0
         starts [0]]
    (if (= index (.length source))
      starts
      (recur (inc index)
             (cond-> starts
               (= \newline (.charAt source index)) (conj (inc index)))))))

(defn- line-at
  [starts index]
  (loop [low 0
         high (dec (count starts))]
    (if (> low high)
      high
      (let [middle (quot (+ low high) 2)
            line-start (nth starts middle)]
        (if (<= line-start index)
          (recur (inc middle) high)
          (recur low (dec middle)))))))

(defn- line-range
  [source start end]
  (let [starts (line-starts source)
        final-index (if (< start end) (dec end) start)]
    {:my.edit/from-line (inc (line-at starts start))
     :my.edit/to-line (inc (line-at starts final-index))}))

(defn- position-index
  [source starts [row column]]
  (let [line-start (nth starts (dec row) nil)
        index (some-> line-start (+ (dec column)))]
    (when (and index (<= 0 index (count source))) index)))

(defn- parse-root
  [source]
  (try
    {:seon.edit/root (z/of-string* source {:track-position? true})}
    (catch Throwable error
      (flat-error :my.edit/parse-refused
                  "The complete source could not be parsed structurally."
                  {:seon.edit/cause (.getMessage error)}))))

(defn- top-level-locations
  [root]
  (loop [location (z/down root)
         locations []]
    (if location
      (recur (z/right location) (conj locations location))
      locations)))

(defn- location-sexpr
  [location]
  (try
    {:seon.edit/sexpr (z/sexpr location)}
    (catch Throwable _ nil)))

(defn- single-form
  [source error-kind message]
  (let [parsed (parse-root source)]
    (if (:seon.error/kind parsed)
      (assoc parsed
             :seon.error/kind error-kind
             :seon.error/message message)
      (let [locations (top-level-locations (:seon.edit/root parsed))
            location (first locations)
            semantic (some-> location location-sexpr)]
        (if (and (= 1 (count locations)) semantic)
          {:seon.edit/location location
           :seon.edit/node (z/node location)
           :seon.edit/sexpr (:seon.edit/sexpr semantic)}
          (flat-error error-kind message
                      {:seon.edit/form-count (count locations)}))))))

(defn single-form?
  "True when `source` is exactly one readable semantic form."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [source]
  (not (:seon.error/kind
        (single-form source :my.edit/invalid-replacement
                     "Replacement source must contain exactly one form."))))

(defn- semantic-form
  [location]
  (let [semantic (location-sexpr location)
        value (:seon.edit/sexpr semantic)]
    (when (and semantic
               (sequential? value)
               (symbol? (first value))
               (symbol? (second value)))
      {:seon.edit/location location
       :seon.edit/sexpr value
       :my.edit.form/head (first value)
       :my.edit.form/name (second value)})))

(defn- location-span
  [source starts location]
  (let [[[start-row start-column] [end-row end-column]]
        (z/position-span location)
        start (position-index source starts [start-row start-column])
        end (position-index source starts [end-row end-column])]
    (when (and start end (<= start end)) [start end])))

(defn- candidate-evidence
  [source starts semantic]
  (let [[start end] (location-span source starts
                                   (:seon.edit/location semantic))]
    (merge (select-keys semantic [:my.edit.form/head
                                  :my.edit.form/name])
           (line-range source start end))))

(defn- bounded-values
  [values byte-limit]
  (loop [remaining values
         bounded []
         used 0]
    (if-let [value (first remaining)]
      (let [value-bytes (utf8-bytes (pr-str value))]
        (if (> (+ used value-bytes) byte-limit)
          {:seon.edit/values bounded
           :seon.edit/complete? false}
          (recur (next remaining) (conj bounded value)
                 (+ used value-bytes))))
      {:seon.edit/values bounded
       :seon.edit/complete? true})))

(defn- selector-matches?
  [selector dispatch-form semantic]
  (let [value (:seon.edit/sexpr semantic)]
    (and (= (:my.edit.form/head selector) (:my.edit.form/head semantic))
         (= (:my.edit.form/name selector) (:my.edit.form/name semantic))
         (or (not (contains? selector :my.edit.form/dispatch-source))
             (= dispatch-form (nth value 2 nil))))))

(defn- splice
  [source start end fragment]
  (str (subs source 0 start) fragment (subs source end)))

(defn- zipper-edit
  [location operation replacement-node]
  (case operation
    :replace (z/replace location replacement-node)
    :insert-before (z/insert-left location replacement-node)
    :insert-after (z/insert-right location replacement-node)
    :delete (z/remove* location)))

(defn- actual-edit
  [source start end operation replacement-source]
  (case operation
    :replace {:seon.edit/source (splice source start end replacement-source)
              :seon.edit/start start
              :seon.edit/end (+ start (count replacement-source))}
    :insert-before
    (let [fragment (str replacement-source " ")]
      {:seon.edit/source (splice source start start fragment)
       :seon.edit/start start
       :seon.edit/end (+ start (count fragment))})
    :insert-after
    (let [fragment (str " " replacement-source)]
      {:seon.edit/source (splice source end end fragment)
       :seon.edit/start end
       :seon.edit/end (+ end (count fragment))})
    :delete {:seon.edit/source (splice source start end "")
             :seon.edit/start start
             :seon.edit/end start}))

(defn- lossless-candidate
  [source start end operation replacement selected replacement-node]
  (try
    (let [zipper-result (zipper-edit selected operation replacement-node)
          rendered (z/root-string zipper-result)
          actual (actual-edit source start end operation replacement)
          candidate (:seon.edit/source actual)
          reparsed (parse-root candidate)]
      (if (or (:seon.error/kind reparsed)
              (not= rendered (z/root-string (:seon.edit/root reparsed))))
        (flat-error :my.edit/lossless-check-failed
                    "The structural edit did not preserve the source boundary."
                    {})
        actual))
    (catch Throwable error
      (flat-error :my.edit/lossless-check-failed
                  "The structural edit could not be verified losslessly."
                  {:seon.edit/cause (.getMessage error)}))))

(defn- matched-form-result
  [source starts match request context-byte-limit]
  (let [operation (:my.edit/operation request)
        replacement
        (when-not (= :delete operation)
          (single-form
           (:my.edit/source request)
           :my.edit/invalid-replacement
           "Replacement source must contain exactly one complete form."))]
    (if (:seon.error/kind replacement)
      replacement
      (let [selected (:seon.edit/location match)
            [start end] (location-span source starts selected)
            candidate
            (lossless-candidate source start end operation
                                (or (:my.edit/source request) "") selected
                                (:seon.edit/node replacement))]
        (if (:seon.error/kind candidate)
          candidate
          (let [candidate-source (:seon.edit/source candidate)
                changed-start (:seon.edit/start candidate)
                changed-end (:seon.edit/end candidate)]
            (merge candidate
                   (line-range candidate-source changed-start changed-end)
                   (context-window candidate-source changed-start changed-end
                                   context-byte-limit))))))))

(defn form
  "Transform one unambiguous named top-level form."
  {:malli/schema
   [:=> [:cat :string :my.edit/form-request [:int {:min 1}]]
    [:or :seon.edit/candidate :seon.error/value]]}
  [source request context-byte-limit]
  (let [parsed (parse-root source)]
    (if (:seon.error/kind parsed)
      parsed
      (let [root (:seon.edit/root parsed)
            starts (line-starts source)
            semantics (keep semantic-form (top-level-locations root))
            selector (:my.edit/form request)
            dispatch-source (:my.edit.form/dispatch-source selector)
            dispatch (when dispatch-source
                       (single-form
                        dispatch-source :my.edit/parse-refused
                        (str "The selector dispatch source is not one "
                             "complete form.")))]
        (if (:seon.error/kind dispatch)
          dispatch
          (let [matches (filterv #(selector-matches?
                                   selector (:seon.edit/sexpr dispatch) %)
                                 semantics)]
            (cond
              (empty? matches)
              (let [evidence
                    (bounded-values
                     (map #(candidate-evidence source starts %) semantics)
                     context-byte-limit)]
                (flat-error
                 :my.edit/no-match
                 "No top-level form matches the exact selector."
                 {:seon.edit/candidates (:seon.edit/values evidence)
                  :seon.edit/candidates-complete?
                  (:seon.edit/complete? evidence)}))

              (< 1 (count matches))
              (let [evidence
                    (bounded-values
                     (map #(candidate-evidence source starts %) matches)
                     context-byte-limit)]
                (flat-error
                 :my.edit/ambiguous-match
                 "More than one top-level form matches the selector."
                 {:seon.edit/candidates (:seon.edit/values evidence)
                  :seon.edit/candidates-complete?
                  (:seon.edit/complete? evidence)}))

              :else
              (matched-form-result source starts (first matches) request
                                   context-byte-limit))))))))

(defn- occurrences
  [^String source ^String sought]
  (loop [offset 0
         found []]
    (let [position (.indexOf source sought offset)]
      (if (neg? position)
        found
        (recur (+ position (.length sought)) (conj found position))))))

(defn- replace-occurrences
  [source positions old-string new-string]
  (let [old-length (count old-string)]
    (loop [positions positions
           previous-end 0
           parts []]
      (if-let [position (first positions)]
        (recur (next positions)
               (+ position old-length)
               (conj parts (subs source previous-end position) new-string))
        (apply str (conj parts (subs source previous-end)))))))

(defn exact
  "Replace one exact string occurrence, or every occurrence when explicit."
  {:malli/schema
   [:=> [:cat :string :my.edit/exact-request [:int {:min 1}]]
    [:or :seon.edit/candidate :seon.error/value]]}
  [source request context-byte-limit]
  (let [old-string (:my.edit/old-string request)
        new-string (:my.edit/new-string request)
        positions (occurrences source old-string)
        replace-all? (true? (:my.edit/replace-all? request))
        evidence (bounded-values
                  (map (fn [position]
                         (:my.edit/from-line
                          (line-range source position
                                      (+ position (count old-string)))))
                       positions)
                  context-byte-limit)]
    (cond
      (empty? positions)
      (flat-error :my.edit/no-match
                  "The exact prior string does not occur in the source."
                  {:my.edit/replacements 0
                   :seon.edit/lines (:seon.edit/values evidence)
                   :seon.edit/lines-complete? (:seon.edit/complete? evidence)})

      (and (not replace-all?) (< 1 (count positions)))
      (flat-error :my.edit/ambiguous-match
                  "The exact prior string occurs more than once."
                  {:my.edit/replacements (count positions)
                   :seon.edit/lines (:seon.edit/values evidence)
                   :seon.edit/lines-complete? (:seon.edit/complete? evidence)})

      :else
      (let [applied (if replace-all? positions [(first positions)])
            candidate-source
            (replace-occurrences source applied old-string new-string)
            first-start (first applied)
            last-start (+ (last applied)
                          (* (dec (count applied))
                             (- (count new-string) (count old-string))))
            changed-end (+ last-start (count new-string))]
        (merge {:seon.edit/source candidate-source
                :seon.edit/start first-start
                :seon.edit/end changed-end
                :my.edit/replacements (count applied)}
               (line-range candidate-source first-start changed-end)
               (context-window candidate-source first-start changed-end
                               context-byte-limit))))))

(defn lines
  "Replace a one-based inclusive line window guarded by its exact bytes."
  {:malli/schema
   [:=> [:cat :string :my.edit/lines-request [:int {:min 1}]]
    [:or :seon.edit/candidate :seon.error/value]]}
  [source request context-byte-limit]
  (let [starts (line-starts source)
        from-line (:my.edit/from-line request)
        to-line (:my.edit/to-line request)]
    (if (or (> from-line to-line) (> to-line (count starts)))
      (flat-error :my.edit/no-match
                  "The guarded line range is outside the current source."
                  {:my.edit/from-line from-line
                   :my.edit/to-line to-line
                   :seon.edit/line-count (count starts)})
      (let [start (nth starts (dec from-line))
            end (or (nth starts to-line nil) (count source))
            actual (subs source start end)
            expected (:my.edit/old-window request)]
        (if (not= expected actual)
          (let [bounded-end (prefix-end-within actual 0 (count actual)
                                               context-byte-limit)]
            (flat-error :my.edit/no-match
                        "The guarded line window does not match current source."
                        {:my.edit/from-line from-line
                         :my.edit/to-line to-line
                         :my.edit/actual-window (subs actual 0 bounded-end)
                         :my.edit/context-complete?
                         (= bounded-end (count actual))}))
          (let [new-window (:my.edit/new-window request)
                candidate-source (splice source start end new-window)
                changed-end (+ start (count new-window))]
            (merge {:seon.edit/source candidate-source
                    :seon.edit/start start
                    :seon.edit/end changed-end}
                   (line-range candidate-source start changed-end)
                   (context-window candidate-source start changed-end
                                   context-byte-limit))))))))
