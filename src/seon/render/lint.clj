(ns seon.render.lint
  "Judge a rendered page WITHOUT a browser.

  Every block is a function returning Hiccup, so \"is this page ugly?\" is a
  question about DATA, not about pixels: the defects that made the live drive
  pages unusable — 67 identical `renderer unavailable` placeholders, a form cut
  off mid-list inside a fence, the same subtree emitted twice, a printed map
  dumped as one long text node — are all structural properties of the returned
  value. This namespace derives them.

  IT IS PURE AND TOTAL. `check` takes one Hiccup value (or a
  `:seon.render/page` vector of them) and returns a data report: findings with
  a child-index path, a defect key, and evidence. It renders nothing, holds
  nothing, transacts nothing, and never throws. `check-render` is the one
  convenience that produces the Hiccup first, through the ordinary
  `seon.render/render-call` owner with a caller-supplied complete request —
  values carry their world, so this namespace never reaches for a database,
  a ctx, or a profile of its own.

  ABSENCE IS A FINDING, NOT HEALTH. A declared required region that is missing
  from the tree reports `:seon.render.lint/empty-region` with
  `:seon.render.lint/region-absent`, because the recurring failure class of
  this project is a check that reads no-signal as fine.

  NOTHING HERE MIRRORS PRODUCTION BY HAND. The placeholder class set, the fence
  tags, and both size floors are declared REQUEST inputs echoed back in the
  report, so a report always states the policy that produced it. Void-element
  behavior is asked of the one serializer owner (`seon.render.hiccup/->string`)
  rather than restated as a local table, and class tokens come from
  `seon.render.hiccup/shorthand` plus the `:class` attribute — the same two
  sources the serializer reads."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/seon.render.lint.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(def ^:private default-placeholder-classes
  #{"seon-render-unavailable"})

(def ^:private default-fence-tags
  #{"pre" "code"})

(def ^:private default-duplicate-node-floor 16)

(def ^:private default-soup-character-floor 240)

(def ^:private excerpt-characters 120)

;;; ---------------------------------------------------------------------------
;;; The grammar, read exactly as the serializer reads it
;;; ---------------------------------------------------------------------------

(defn- element?
  [node]
  (and (vector? node)
       (pos? (count node))
       (let [head (nth node 0)]
         (or (keyword? head) (symbol? head) (string? head)))))

(defn- attributed?
  [node]
  (let [second-node (when (> (count node) 1) (nth node 1))]
    (and (map? second-node) (not (hiccup/raw? second-node)))))

(defn- attributes-of
  [node]
  (if (attributed? node) (nth node 1) {}))

(defn- children-of
  [node]
  (subvec node (if (attributed? node) 2 1)))

(defn- fragment?
  [node]
  (and (sequential? node) (not (vector? node))))

(defn- parsed-tag
  [node]
  (hiccup/shorthand (nth node 0)))

(defn- tag-of
  [node]
  (let [parsed (parsed-tag node)]
    (if (:seon.error/kind parsed) "" (:seon.render.hiccup/tag parsed))))

(def ^:private void-tag?
  "Ask the ONE serializer whether a tag elides its children.

  A local list of void elements would be a hand-maintained mirror of
  `seon.render.hiccup`'s, stale the first time HTML gains a tag. Serializing a
  probe child and looking for it back answers the same question from the owner.
  Memoized per tag name; the probe string cannot occur in the tag."
  (memoize
   (fn [tag]
     (let [probe "seon-render-lint-void-probe"
           html (hiccup/->string [(keyword tag) probe])]
       (and (string? html) (not (string/includes? html probe)))))))

(defn- string-tokens
  "Whitespace-free tokens of `text`, without a regular expression."
  [text]
  (into []
        (comp (remove #(= \space (first %)))
              (map string/join))
        (partition-by #(= \space %) (seq (string/replace text \tab \space)))))

(defn- attribute-token
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else (str value)))

(defn- node-classes
  [node]
  (let [parsed (parsed-tag node)
        shorthand-classes (if (:seon.error/kind parsed)
                            []
                            (:seon.render.hiccup/classes parsed))
        attribute (:class (attributes-of node))]
    (into (set shorthand-classes)
          (cond
            (nil? attribute) nil
            (string? attribute) (string-tokens attribute)
            (sequential? attribute) (into []
                                          (comp (remove nil?)
                                                (mapcat (comp string-tokens
                                                              attribute-token)))
                                          attribute)
            :else (string-tokens (attribute-token attribute))))))

(defn- node-id
  [node]
  (let [attributes (attributes-of node)
        parsed (parsed-tag node)]
    (or (:id attributes)
        (when-not (:seon.error/kind parsed)
          (:seon.render.hiccup/id parsed)))))

(defn- child-nodes
  "The nodes a walk descends into, in document order."
  [node]
  (cond
    (element? node) (children-of node)
    (fragment? node) (vec node)
    :else []))

(defn- walk
  "Depth-first `[path node]` pairs, root first; `path` is child indices."
  [root]
  ((fn step [path node]
     (cons [path node]
           (mapcat (fn [index child]
                     (step (conj path index) child))
                   (range)
                   (child-nodes node))))
   [] root))

;;; ---------------------------------------------------------------------------
;;; Text — the extractor the honesty falsifier uses
;;; ---------------------------------------------------------------------------

(defn text-content
  "The text a viewer reads, concatenated in document order.

  Mirrors `seon.render.hiccup`'s node semantics exactly: strings, numbers,
  keywords, symbols, instants, and `true` are text; `nil`, `false`, and a bare
  map contribute nothing; a `raw` value contributes its own bytes; a void
  element's children are elided because the serializer elides them.

  This is the byte-comparison side of the transcript honesty contract: for a
  block rendered from a stored capture, `text-content` of the block must equal
  the capture. Total: never throws."
  {:malli/schema [:=> [:cat :seon.render.lint/subject] :string]}
  [node]
  (let [builder (StringBuilder.)]
    ((fn append! [node]
       (cond
         (element? node) (when-not (void-tag? (tag-of node))
                           (run! append! (children-of node)))
         (string? node) (.append builder ^String node)
         (nil? node) nil
         (false? node) nil
         (hiccup/raw? node) (.append builder ^String (:string node))
         (map? node) nil
         (sequential? node) (run! append! node)
         (number? node) (.append builder (str node))
         (keyword? node) (.append builder (str node))
         (symbol? node) (.append builder (str node))
         (true? node) (.append builder "true")
         (inst? node) (.append builder (str node))
         :else nil))
     node)
    (.toString builder)))

(defn element-with-id
  "The one element carrying `:seon.render.lint/id`, or a flat refusal.

  The honesty falsifier needs to compare the CAPTURED region's text, never the
  whole turn: an ordinal, a timestamp, and a basis label are legitimate chrome
  that add characters outside the verbatim element. So the comparison targets
  one addressed element, and an absent address refuses loudly instead of
  returning an empty string that would compare equal to nothing."
  {:malli/schema [:=> [:cat :seon.render.lint/id-request]
                  [:or :seon.render.lint/subject :seon.error/value]]}
  [{subject :seon.render.lint/hiccup id :seon.render.lint/id}]
  (let [root (if (hiccup/hiccup? subject) subject (seq subject))
        found (some (fn [[_path node]]
                      (when (and (element? node) (= id (node-id node)))
                        node))
                    (walk root))]
    (or found
        (error/diagnostic
         {:seon.error/kind ::absent-element
          :seon.error/message
          (str "The rendered value carries no element with id " (pr-str id) ".")
          :seon.error/diagnostic-layer :render
          :seon.error/diagnostic-operation `element-with-id
          :seon.error/diagnostic-member :seon.render.lint/id
          :seon.error/diagnostic-expected id
          :seon.error/diagnostic-offending :seon.render.lint/absent
          :seon.error/diagnostic-cause ::absent-element
          :seon.error/diagnostic-evidence
          {:seon.render.lint/nodes (count (walk root))}}))))

;;; ---------------------------------------------------------------------------
;;; Delimiter balance — one scan, no regular expression
;;; ---------------------------------------------------------------------------

(def ^:private closers
  {\( \), \[ \], \{ \}})

(defn balance
  "Whether `text` closes every Clojure delimiter it opens.

  Returns `:seon.render.lint/unclosed` (the open delimiters still on the stack,
  outermost first), plus `:seon.render.lint/unexpected-close` for the first
  close that matched nothing and `:seon.render.lint/unterminated-string` when
  the text ends inside a string. Strings, character literals, and `;` comments
  are honored, so `\"(\"` and `\\(` do not count as opens.

  A fence whose text is unbalanced is a form the renderer cut in half — the
  defect this predicate exists to name."
  {:malli/schema [:=> [:cat :string] :seon.render.lint/balance]}
  [text]
  (let [length (count text)]
    (loop [index 0
           stack []
           unexpected nil
           in-string? false
           escaped? false
           in-comment? false]
      (if (>= index length)
        (cond-> {:seon.render.lint/unclosed (mapv str stack)}
          unexpected (assoc :seon.render.lint/unexpected-close (str unexpected))
          in-string? (assoc :seon.render.lint/unterminated-string true))
        (let [character (.charAt ^String text index)
              next-index (inc index)]
          (cond
            escaped?
            (recur next-index stack unexpected in-string? false in-comment?)

            in-string?
            (cond
              (= \\ character)
              (recur next-index stack unexpected true true in-comment?)
              (= \" character)
              (recur next-index stack unexpected false false in-comment?)
              :else
              (recur next-index stack unexpected true false in-comment?))

            in-comment?
            (recur next-index stack unexpected false false
                   (not (or (= \newline character) (= \return character))))

            (= \; character)
            (recur next-index stack unexpected false false true)

            (= \" character)
            (recur next-index stack unexpected true false false)

            ;; a character literal: the next character is data, never a
            ;; delimiter — `\(` opens nothing
            (= \\ character)
            (recur next-index stack unexpected false true false)

            (contains? closers character)
            (recur next-index (conj stack character) unexpected false false
                   false)

            (contains? #{\) \] \}} character)
            (if (and (seq stack) (= character (closers (peek stack))))
              (recur next-index (pop stack) unexpected false false false)
              (recur next-index stack (or unexpected character) false false
                     false))

            :else
            (recur next-index stack unexpected false false false)))))))

(defn- unbalanced?
  [report]
  (boolean (or (seq (:seon.render.lint/unclosed report))
               (:seon.render.lint/unexpected-close report)
               (:seon.render.lint/unterminated-string report))))

;;; ---------------------------------------------------------------------------
;;; Findings
;;; ---------------------------------------------------------------------------

(defn- excerpt
  [text]
  (if (> (count text) excerpt-characters)
    (str (subs text 0 excerpt-characters) "…")
    text))

(defn- node-count
  [node]
  (count (walk node)))

(defn- reads-as-collection?
  "True when `text` reads as one EDN collection — a printed value, not prose."
  [text]
  (try
    (coll? (edn/read-string {:default (fn [_tag value] value)} text))
    (catch Exception _ false)
    (catch StackOverflowError _ false)))

(defn- finding
  [defect path detail]
  {:seon.render.lint/defect defect
   :seon.render.lint/path path
   :seon.render.lint/detail detail})

(defn- placeholder-findings
  [nodes placeholder-classes]
  (into []
        (keep (fn [[path node]]
                (when (element? node)
                  (let [classes (node-classes node)
                        hit (some placeholder-classes classes)]
                    (when hit
                      (finding :seon.render.lint/renderer-unavailable path
                               {:seon.render.lint/tag (tag-of node)
                                :seon.render.lint/classes classes
                                :seon.render.lint/excerpt
                                (excerpt (text-content node))}))))))
        nodes))

(defn- fence-findings
  [nodes fence-tags]
  (into []
        (keep (fn [[path node]]
                (when (and (element? node) (contains? fence-tags (tag-of node)))
                  (let [text (text-content node)
                        report (balance text)]
                    (when (unbalanced? report)
                      (finding :seon.render.lint/truncated-form path
                               (merge {:seon.render.lint/tag (tag-of node)
                                       :seon.render.lint/characters (count text)
                                       :seon.render.lint/excerpt (excerpt text)}
                                      report)))))))
        nodes))

(defn- duplicate-findings
  [nodes duplicate-node-floor]
  (into []
        (mapcat (fn [[path node]]
                  (let [children (child-nodes node)
                        positions (reduce
                                   (fn [acc [index child]]
                                     (if (element? child)
                                       (update acc child (fnil conj []) index)
                                       acc))
                                   {}
                                   (map-indexed vector children))]
                    (keep (fn [[child indices]]
                            (let [nodes-in-child (node-count child)]
                              (when (and (> (count indices) 1)
                                         (>= nodes-in-child
                                             duplicate-node-floor))
                                (finding :seon.render.lint/duplicated-block
                                         (conj path (first indices))
                                         {:seon.render.lint/tag (tag-of child)
                                          :seon.render.lint/repeats
                                          (count indices)
                                          :seon.render.lint/nodes nodes-in-child
                                          :seon.render.lint/excerpt
                                          (excerpt (text-content child))}))))
                          positions))))
        nodes))

(defn- soup-findings
  [root soup-character-floor fence-tags]
  ;; Text inside a fence is a REPL transcript and is supposed to be printed
  ;; source; soup is a printed value that escaped into ordinary page text.
  ((fn step [path node fenced?]
     (cond
       (element? node)
       (let [fenced? (or fenced? (contains? fence-tags (tag-of node)))]
         (mapcat (fn [index child] (step (conj path index) child fenced?))
                 (range)
                 (children-of node)))

       (fragment? node)
       (mapcat (fn [index child] (step (conj path index) child fenced?))
               (range)
               node)

       (and (string? node)
            (not fenced?)
            (>= (count node) soup-character-floor)
            (reads-as-collection? node))
       [(finding :seon.render.lint/pr-str-soup path
                 {:seon.render.lint/characters (count node)
                  :seon.render.lint/excerpt (excerpt node)})]

       :else []))
   [] root false))

(defn- region-findings
  [nodes required-regions]
  (let [present (into {}
                      (keep (fn [[path node]]
                              (when (element? node)
                                (when-some [id (node-id node)]
                                  (when (contains? required-regions id)
                                    [id [path node]])))))
                      nodes)]
    (into []
          (keep (fn [region]
                  (if-some [[path node] (get present region)]
                    (let [text (text-content node)]
                      (when (string/blank? text)
                        (finding :seon.render.lint/empty-region path
                                 {:seon.render.lint/region region
                                  :seon.render.lint/tag (tag-of node)
                                  :seon.render.lint/characters (count text)})))
                    ;; ABSENT is the loud case: a region nobody rendered must
                    ;; never read as a region that is fine.
                    (finding :seon.render.lint/empty-region []
                             {:seon.render.lint/region region
                              :seon.render.lint/region-absent true})))
                (sort required-regions)))))

;;; ---------------------------------------------------------------------------
;;; The entries
;;; ---------------------------------------------------------------------------

(defn check
  "Report one rendered page's structural defects.

  `:seon.render.lint/hiccup` is one Hiccup value or a `:seon.render/page`
  vector of them. The optional inputs are the report's stated policy:
  `required-regions` (element ids that must exist and carry text),
  `duplicate-node-floor` (how large a repeated sibling subtree must be to
  count), `soup-character-floor` (how long a printed-value text node must be to
  count), `placeholder-classes`, and `fence-tags`. Every one is echoed in the
  report, so no reader has to guess which policy produced these findings.

  Findings are ordered by defect class and then by document order within a
  class. Total: pure, and never throws."
  {:malli/schema [:=> [:cat :seon.render.lint/request] :seon.render.lint/report]}
  [{subject :seon.render.lint/hiccup
    required-regions :seon.render.lint/required-regions
    :as request}]
  (let [duplicate-node-floor (get request :seon.render.lint/duplicate-node-floor
                                  default-duplicate-node-floor)
        soup-character-floor (get request :seon.render.lint/soup-character-floor
                                  default-soup-character-floor)
        placeholder-classes (get request :seon.render.lint/placeholder-classes
                                 default-placeholder-classes)
        fence-tags (get request :seon.render.lint/fence-tags default-fence-tags)
        ;; a page is a vector of Hiccup, which is not itself Hiccup; read it as
        ;; the fragment the serializer would read
        root (if (hiccup/hiccup? subject) subject (seq subject))
        nodes (walk root)
        findings (vec (concat (placeholder-findings nodes placeholder-classes)
                              (fence-findings nodes fence-tags)
                              (duplicate-findings nodes duplicate-node-floor)
                              (soup-findings root soup-character-floor
                                             fence-tags)
                              (when required-regions
                                (region-findings nodes required-regions))))]
    (cond-> {:seon.render.lint/findings findings
             :seon.render.lint/counts
             (into {} (map (fn [[defect group]] [defect (count group)]))
                   (group-by :seon.render.lint/defect findings))
             :seon.render.lint/nodes (count nodes)
             :seon.render.lint/characters (count (text-content root))
             :seon.render.lint/floors
             {:seon.render.lint/duplicate-node-floor duplicate-node-floor
              :seon.render.lint/soup-character-floor soup-character-floor}
             :seon.render.lint/placeholder-classes placeholder-classes
             :seon.render.lint/fence-tags fence-tags}
      required-regions (assoc :seon.render.lint/required-regions
                              required-regions))))

(defn check-render
  "Render one value's HTML block through the ordinary owner, then `check` it.

  The caller hands a complete `seon.render/render-call` request — database
  value, ctx, admission caps, time limit, core-error dial, and the value — plus
  any `check` policy. Nothing is defaulted from a registry or a dynamic var:
  the request IS the world. A refusal from the render owner is returned as the
  flat `:seon.error/value` it already is, never swallowed into an empty report."
  {:malli/schema [:=> [:cat :seon.render.lint/render-request]
                  [:or :seon.render.lint/report :seon.error/value]]}
  [request]
  (let [rendered (render/render-call
                  (assoc request :seon.render/output :seon.render/html))]
    (if (:seon.error/kind rendered)
      rendered
      (check (assoc (select-keys request
                                 [:seon.render.lint/required-regions
                                  :seon.render.lint/duplicate-node-floor
                                  :seon.render.lint/soup-character-floor
                                  :seon.render.lint/placeholder-classes
                                  :seon.render.lint/fence-tags])
                    :seon.render.lint/hiccup rendered)))))
