(ns seon.ref
  "References in Markdown text (a task plan, a doc, a docstring), found by one
  parse and resolved by one rule: a code span is a candidate name or path, a
  link is a path, and the delimiter picks the level shown (TASK-CHAIN §3a)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [seon.ai.tokens :as tokens]
            [seon.cache :as cache]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.internal :as internal]
            [seon.test.cache :as test.cache])
  (:import [java.net URI URISyntaxException]
           [org.commonmark.ext.front.matter YamlFrontMatterBlock YamlFrontMatterExtension]
           [org.commonmark.ext.gfm.tables TablesExtension]
           [org.commonmark.ext.heading.anchor IdGenerator]
           [org.commonmark.node AbstractVisitor Code Document FencedCodeBlock Heading IndentedCodeBlock Link Node Paragraph SourceSpan Text]
           [org.commonmark.parser IncludeSourceSpans Parser]
           [org.commonmark.renderer.text TextContentRenderer]))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; One parse per text
;;; ---------------------------------------------------------------------------

(def ^:private ^Parser parser
  (-> (Parser/builder)
      (.extensions [(YamlFrontMatterExtension/create) (TablesExtension/create)])
      (.includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
      .build))

(def ^:private ^TextContentRenderer text-renderer (.build (TextContentRenderer/builder)))

(defn- words
  "The whitespace-separated words of prose `text`, with the punctuation around a word left off."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [^String text]
  (let [opening (set "([{\"'`")
        closing (set ".,;:!?)]}\"'`")
        trimmed (fn [^String word]
                  (let [from (count (take-while opening word))
                        to (- (count word) (count (take-while closing (reverse word))))]
                    (when (< from to) (subs word from to))))]
    (into [] (comp (partition-by #(Character/isWhitespace (char %)))
                   (remove #(Character/isWhitespace (char (first %))))
                   (keep #(trimmed (apply str %))))
          text)))

(defn- with-sections
  "`headings` each with the line after its section (`:seon.ref/section-end`) and the lines of its child headings
  (`:seon.ref/children`), from one fold over them; `end` is the line after the text."
  {:malli/schema [:=> [:cat [:vector :map] :seon.ref/line] [:vector :seon.ref/heading-entry]]}
  [headings end]
  (let [level :seon.ref/heading-level
        line :seon.ref/line
        {:keys [stack ends children]}
        (reduce (fn [{:keys [stack ends children]} heading]
                  (let [[stack ends] (loop [stack stack ends ends]
                                       (if-let [top (peek stack)]
                                         (if (>= (level top) (level heading))
                                           (recur (pop stack) (assoc ends (line top) (line heading)))
                                           [stack ends])
                                         [stack ends]))]
                    {:stack (conj stack heading)
                     :ends ends
                     :children (cond-> children (peek stack) (update (line (peek stack)) (fnil conj []) (line heading)))}))
                {:stack [] :ends {} :children {}} headings)
        ends (into ends (map (juxt line (constantly end))) stack)]
    (mapv #(cond-> (assoc % :seon.ref/section-end (ends (line %)))
             (children (line %)) (assoc :seon.ref/children (children (line %))))
          headings)))

(defn- parsed
  "The headings (each with its lead or opening lines, section end and children), the code spans (each with its
  delimiter width, read from its source span), links and bare prose words of `text` in document order, the line the
  text starts at after its front matter, its first top-level paragraph's lines and its fenced code blocks."
  {:malli/schema [:=> [:cat :string] :seon.ref/document]}
  [^String text]
  (let [ids (.build (IdGenerator/builder))
        headings (java.util.ArrayList.)
        spans (java.util.ArrayList.)
        blocks (java.util.ArrayList.)
        paragraph (volatile! nil)
        lines (str/split-lines text)
        tree (.parse parser text)
        ;; the lines of a block, as an editor shows them
        block-lines (fn [^Node node] {:seon.ref/line (inc (.getLineIndex ^SourceSpan (first (.getSourceSpans node))))
                                      :seon.ref/end-line (inc (.getLineIndex ^SourceSpan (last (.getSourceSpans node))))})
        ;; commonmark's 0-based line index as the line an editor shows
        line (fn [^Node node] (inc (.getLineIndex ^SourceSpan (first (.getSourceSpans node)))))
        last-line (fn [^Node node] (inc (.getLineIndex ^SourceSpan (last (.getSourceSpans node)))))
        ;; a code span's source span starts at its opening backticks
        width (fn [^Node node]
                (let [^SourceSpan span (first (.getSourceSpans node))]
                  (count (take-while #{\`} (subs (nth lines (.getLineIndex span)) (.getColumnIndex span))))))]
    (.accept tree
             (proxy [AbstractVisitor] []
               (visit [node]
                 (condp instance? node
                   Heading (let [heading (str/trim (.render text-renderer ^Node node))]
                             ;; the heading's own next block is its lead when a paragraph (a table is no paragraph), else its opening
                             (let [following (.getNext ^Node node)]
                               (.add headings (cond-> {:seon.ref/heading-level (.getLevel ^Heading node) :seon.ref/heading heading
                                                       :seon.ref/anchor (.generateId ids heading) :seon.ref/line (line node)}
                                                (instance? Paragraph following) (assoc :seon.ref/lead-lines (block-lines following))
                                                (and following (not (instance? Paragraph following)) (not (instance? Heading following)))
                                                (assoc :seon.ref/opening-lines (block-lines following)))))
                             (proxy-super visitChildren node))
                   Paragraph (do (when-not (or @paragraph (not (instance? Document (.getParent node))))
                                   (vreset! paragraph {:seon.ref/line (line node) :seon.ref/end-line (last-line node)}))
                                 (proxy-super visitChildren node))
                   Code (.add spans {:seon.ref/span (.getLiteral ^Code node) :seon.ref/width (width node) :seon.ref/line (line node)})
                   Link (do (.add spans {:seon.ref/link (.getDestination ^Link node) :seon.ref/line (line node)})
                            (proxy-super visitChildren node))
                   ;; a prose word is a candidate only when it names an entity (`answer`)
                   Text (let [at (line node)]
                          ;; only a word with a `/` can be a qualified name or a based path
                          (doseq [word (words (.getLiteral ^Text node)) :when (str/includes? word "/")]
                            (.add spans {:seon.ref/word word :seon.ref/line at})))
                   ;; code is never a reference
                   FencedCodeBlock (.add blocks {:seon.ref/code (.getLiteral ^FencedCodeBlock node) :seon.ref/line (line node)})
                   IndentedCodeBlock nil
                   (proxy-super visitChildren node)))))
    (cond-> {:seon.ref/headings (with-sections (vec headings) (inc (count lines)))
             :seon.ref/spans (vec spans)
             :seon.ref/code-blocks (vec blocks)
             ;; the line after the front matter's closing delimiter
             :seon.ref/body-line (let [front (.getFirstChild tree)]
                                   (if (instance? YamlFrontMatterBlock front) (inc (:seon.ref/end-line (block-lines front))) 1))
             :seon.ref/line-count (count lines)}
      @paragraph (assoc :seon.ref/first-paragraph @paragraph))))

(defn document
  "Markdown text's headings and reference candidates, parsed once per digest."
  {:malli/schema [:=> [:cat :string] :seon.ref/document]}
  [^String text]
  (cache/lookup ::document (schema/sha-256 [(.getBytes text "UTF-8")])
                #(* 64 (+ (count (:seon.ref/headings %)) (count (:seon.ref/spans %))))
                #(parsed text)))

;;; ---------------------------------------------------------------------------
;;; A docstring's parts, read from its one parse
;;; ---------------------------------------------------------------------------

(defn- first-sentence
  "The first sentence of `flat` prose and the text after it; a period after an abbreviation ends no sentence."
  {:malli/schema [:=> [:cat :string] [:tuple :string :string]]}
  [flat]
  (let [abbreviations #{"e.g." "i.e." "etc." "vs."}
        stop (loop [from 0]
               (let [stop (str/index-of flat ". " from)]
                 (cond
                   (nil? stop) nil
                   (some #(str/ends-with? (subs flat 0 (inc stop)) %) abbreviations) (recur (inc stop))
                   :else stop)))]
    (if stop [(subs flat 0 (inc stop)) (str/trim (subs flat (+ stop 2)))] [flat ""])))

(defn docstring
  "A docstring's summary (the first sentence of its first paragraph), the body after it and the code of its
  `## Example` or `## Examples` section, each read from the one parse (`seon.ref/document`); an empty part is absent."
  {:malli/schema [:=> [:cat :string] :seon.ref/docstring]}
  [^String text]
  (let [{:seon.ref/keys [headings code-blocks first-paragraph line-count]} (document text)
        lines (mapv str/trim (str/split-lines text))
        line-text #(get lines (dec %) "")
        {from :seon.ref/line to :seon.ref/end-line} first-paragraph
        [summary leftover] (if from (first-sentence (str/join " " (map line-text (range from (inc to))))) ["" ""])
        example (last (filter #(#{"Example" "Examples"} (:seon.ref/heading %)) headings))
        example-end (when example
                      (or (some #(when (and (> (:seon.ref/line %) (:seon.ref/line example))
                                            (<= (:seon.ref/heading-level %) (:seon.ref/heading-level example)))
                                   (:seon.ref/line %))
                                headings)
                          (inc line-count)))
        inside? #(and example (<= (:seon.ref/line example) % (dec example-end)))
        code (str/trim (str/join "\n" (keep #(when (inside? (:seon.ref/line %)) (:seon.ref/code %)) code-blocks)))
        body (str/trim (str/join "\n" (cons leftover (keep #(when-not (inside? %) (line-text %))
                                                           (range (inc (or to 0)) (inc (count lines)))))))]
    (cond-> {:summary summary}
      (seq body) (assoc :body body)
      (seq code) (assoc :example code))))

;;; ---------------------------------------------------------------------------
;;; A schema's documentation
;;; ---------------------------------------------------------------------------

(defn documentation-unavailable
  "The refusal for a reference no public documentation answers."
  {:malli/schema [:=> [:cat :seon.sci.eval/authored-reference] :seon.sci.eval/documentation-unavailable-error]}
  [requested]
  (refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.ref/documentation-unavailable
   {:seon.error/message "No public documentation is available; request an installed public declaration."
    :seon.error/offending requested
    :seon.sci.eval/documentation-unavailable requested
    :seon.error/member :seon.fn/sym
    :seon.error/expected :seon.sci.eval/public-documentation}))

(defn schema-documentation
  "The declared form of the schema named `key`, with its description property and `:returns`, the functions whose
  output names it (`seon.db/contract-users`)."
  {:malli/schema [:=> [:cat :seon.db/db :qualified-keyword :seon.sci.eval/authored-reference]
                  [:or :seon.sci.eval/schema-doc :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database key authored]
  (let [row (db/pull database [:seon.schema/key :seon.schema/form] [:seon.schema/key key])]
    (cond
      (db/read-refusal? row) row
      (not (:seon.schema/form row)) (documentation-unavailable authored)
      :else
      (let [form (edn/read-string (:seon.schema/form row))
            description (when (and (vector? form) (map? (second form))) (:description (second form)))]
        (cond-> {:key key :form form :returns (db/contract-users database key :seon.fn.arity/output-refs)}
          description (assoc :description description))))))

;;; ---------------------------------------------------------------------------
;;; Candidates
;;; ---------------------------------------------------------------------------

(defn- relative
  "A relative reference's path, with its fragment as the anchor; nil when it names no path."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] [:or :nil :seon.ref/candidate]]}
  [path fragment]
  (when (seq path)
    (cond-> {:seon.ref/path path} (seq fragment) (assoc :seon.ref/anchor fragment))))

(defn- link-candidate
  "A link destination read as RFC 3986: a scheme is not a reference; a relative one resolves against the document's directory."
  {:malli/schema [:=> [:cat [:maybe :seon.ref/path] :string] [:or :nil :seon.ref/candidate]]}
  [document-path destination]
  (let [^URI uri (try (URI. destination)
                      ;; a destination that is not a URI is still a path, unresolved
                      (catch URISyntaxException _ nil))
        ^URI based (cond (nil? uri) nil
                         (.getScheme uri) nil
                         document-path (.normalize (.resolve (URI. nil nil document-path nil) uri))
                         :else (.normalize uri))]
    (cond
      (nil? uri) {:seon.ref/path destination}
      based (relative (.getPath based) (.getFragment based)))))

(defn- line-citation
  "The path part of a `path:N` or `path:A-B` citation, or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [span]
  (when-let [colon (str/last-index-of span ":")]
    (let [suffix (subs span (inc colon))
          [from to] (if-let [dash (str/index-of suffix "-")] [(subs suffix 0 dash) (subs suffix (inc dash))] [suffix])]
      (when (and (parse-long from) (or (nil? to) (parse-long to)))
        (subs span 0 colon)))))

(defn- span-candidate
  "A code span read as a based path, a line citation, a qualified name, or nil (prose)."
  {:malli/schema [:=> [:cat [:set :string] :string] [:or :nil :seon.ref/candidate]]}
  [roots span]
  (let [segment (subs span 0 (or (some #(str/index-of span %) ["/" "#" ":"]) (count span)))]
    (if (contains? roots segment)
      (if-let [cited (line-citation span)]
        {:seon.ref/problem-kind :line-reference :seon.ref/path cited}
        (or (try (let [uri (URI. span)] (relative (.getPath uri) (.getFragment uri)))
                 ;; a based span that is not a URI is still a path, unresolved
                 (catch URISyntaxException _ nil))
            {:seon.ref/path span}))
      (let [forms (try (edamame/parse-string-all span {:readers {'uuid parse-uuid}})
                       ;; a span that does not read is prose
                       (catch Exception _ nil))
            [form & more] forms
            ;; a trailing * names a family of names, not a name: such a span is prose
            wildcard? (and form (str/ends-with? (str form) "*"))]
        ;; only a span of exactly one qualified symbol or keyword is a name; a dotted bare symbol may name a namespace
        (cond
          (some? more) nil
          wildcard? nil
          (or (qualified-symbol? form) (qualified-keyword? form)) {:seon.ref/name form}
          ;; `[name at]`: the name read at a branch or a commit (`:seon.ref/at`)
          (and (vector? form) (= 2 (count form)) (or (qualified-symbol? (first form)) (qualified-keyword? (first form)))
               (or (keyword? (second form)) (uuid? (second form))))
          {:seon.ref/name (first form) :seon.ref/at (second form)}
          (and (simple-symbol? form) (str/includes? (str form) ".")) {:seon.ref/name form :seon.ref/resolve-only true})))))

(defn- delimiter-level
  "The level a span, link or prose word asks for (TASK-CHAIN §3a): a prose word its summary, one backtick or a link
  its doc, three or more backticks its code; nil for two backticks, which wrap a literal."
  {:malli/schema [:=> [:cat [:or :seon.ref/span-entry :seon.ref/link-entry :seon.ref/word-entry]] [:maybe :seon.ref/level]]}
  [{width :seon.ref/width :as entry}]
  (cond (:seon.ref/word entry) :summary
        (:seon.ref/link entry) :doc
        (= 1 width) :doc
        (<= 3 width) :code))

(defn- candidate
  "One span, link or prose word read as a reference at its `level`: a qualified name, a namespace name, a based path
  with an optional anchor, a line citation, an anchor with no document (a problem), or nil (prose, or a
  double-backtick literal). A prose word or an unqualified span is only resolve-only: it is a reference when it names
  an entity, else prose."
  {:malli/schema [:=> [:cat [:set :string] [:maybe :seon.ref/path] [:or :seon.ref/span-entry :seon.ref/link-entry :seon.ref/word-entry]]
                  [:maybe :seon.ref/candidate]]}
  [roots document-path entry]
  (when-let [asked (delimiter-level entry)]
    (some-> (if-let [destination (:seon.ref/link entry)]
              (if (str/starts-with? destination "#")
                ;; an anchor alone names the document itself; with no document it names nothing
                (if document-path
                  (relative document-path (subs destination 1))
                  (cond-> {:seon.ref/problem-kind :anchor-missing}
                    (< 1 (count destination)) (assoc :seon.ref/anchor (subs destination 1))))
                (link-candidate document-path destination))
              (if-let [word (:seon.ref/word entry)]
                (when-let [found (span-candidate roots word)]
                  (when-not (simple-symbol? (:seon.ref/name found)) (assoc found :seon.ref/resolve-only true)))
                (span-candidate roots (:seon.ref/span entry))))
            (assoc :seon.ref/level asked))))

(def ^:private strength
  "The order of the levels: a name asked for at two levels is shown at the stronger."
  {:summary 0 :doc 1 :code 2})

(defn- candidates
  "The distinct candidates of `entries` in first-seen order, each at the strongest level any mention asks for; one is
  resolve-only only when every mention of it is."
  {:malli/schema [:=> [:cat [:sequential :seon.ref/candidate]] [:vector :seon.ref/candidate]]}
  [found]
  (let [identity-of (juxt :seon.ref/name :seon.ref/at :seon.ref/path :seon.ref/anchor :seon.ref/problem-kind)
        explicit (into #{} (comp (remove :seon.ref/resolve-only) (map identity-of)) found)
        strongest (reduce (fn [kept {asked :seon.ref/level :as c}]
                            (cond-> kept asked (update (identity-of c) #(max-key strength (or % asked) asked))))
                          {} found)
        first-of (reduce (fn [kept c] (cond-> kept (not (contains? kept (identity-of c))) (assoc (identity-of c) c))) {} found)]
    (into [] (comp (map identity-of) (distinct)
                   (map #(cond-> (first-of %)
                           (explicit %) (dissoc :seon.ref/resolve-only)
                           (strongest %) (assoc :seon.ref/level (strongest %)))))
          found)))

;;; ---------------------------------------------------------------------------
;;; Resolution
;;; ---------------------------------------------------------------------------

(def ^:private problem-messages
  "What each problem kind means and what the agent does about it."
  {:unresolved "This resolves to nothing on your branch; its operations read what does exist. What the task intends for it is its plan's to say."
   :anchor-missing "The file has no heading with this anchor; (my.fs/section path) lists its headings. Tell root the task names it."
   :no-lead "This section opens with no paragraph, so its summary is only its heading and size; its doc shows its first block whole and the headings below. Give the section a lead paragraph, or cite it at code level."
   :no-summary "This function has no docstring, so it has no summary. Add one, or cite it at doc or code level."
   :line-reference "A path:N citation goes stale; the task should cite code by its symbol. Tell root the task names it."})

(defn- unresolved
  "The problem for a name or path that resolves to nothing: the fixed message and, as data, the form that searches
  the program for the name's last word."
  {:malli/schema [:=> [:cat [:or :seon.ref/name :seon.ref/path]] :seon.ref/problem]}
  [reference]
  (let [word (if (string? reference) (first (str/split (last (str/split reference #"/")) #"\.")) (name reference))]
    (cond-> {:seon.ref/problem-kind :unresolved :seon.ref/message (problem-messages :unresolved)
             :seon.render/operations [(list 'my.program/search word)]}
      (string? reference) (assoc :seon.ref/path reference)
      (not (string? reference)) (assoc :seon.ref/name reference))))

(defn- pinned-revision
  "The reference-code project and pinned revision a loaded resource URL names, or nil when it is not under a pin."
  {:malli/schema [:=> [:cat :string] [:or :nil :seon.ref/pin]]}
  [url]
  (when (str/starts-with? url "file:")
    (let [parts (mapv str (iterator-seq (.iterator (java.nio.file.Paths/get (URI. url)))))
          [project revision] (rest (drop-while #(not= "pins" %) parts))]
      (when (and revision (str/starts-with? project "reference-code-"))
        {:seon.ref/project (subs project 15) :seon.ref/revision revision}))))

(defn- library-definition
  "A qualified symbol's library Var read-only from the host's loaded Vars, else, when its namespace is not loaded,
  from the dependency cache's definition (never loading it): its docstring, arglists and pinned revision; nil when
  neither holds it."
  {:malli/schema [:=> [:cat :qualified-symbol]
                  [:maybe [:map [:doc {:optional true} :string] [:arglists {:optional true} :seon.ref/arglists]
                           [:seon.ref/pin {:optional true} :seon.ref/pin]]]]}
  [reference]
  (let [loaded? (find-ns (symbol (namespace reference)))
        v (when loaded? (find-var reference))
        m (meta v)
        unloaded (when-not loaded? (analyzer/unloaded-definition reference))
        pin (some-> (if v (some-> (:file m) io/resource str) (::analyzer/url unloaded)) pinned-revision)
        arglists (or (:arglists m) (::analyzer/arglists unloaded))
        doc (or (:doc m) (::analyzer/doc unloaded))]
    (when (or v unloaded)
      (cond-> {}
        arglists (assoc :arglists arglists)
        doc (assoc :doc doc)
        pin (assoc :seon.ref/pin pin)))))

(defn- library-name
  "A qualified symbol resolved read-only as a library Var (`library-definition`): the function it names, with its doc
  line, arglists and pinned revision."
  {:malli/schema [:=> [:cat :qualified-symbol] [:or :seon.ref/resolved-name :seon.ref/problem]]}
  [reference]
  (if-let [{:keys [doc arglists] pin :seon.ref/pin} (library-definition reference)]
    (cond-> {:seon.ref/name reference :seon.fn/sym reference}
      arglists (assoc :arglists arglists)
      doc (assoc :seon.fn/doc (first (str/split-lines (:summary (docstring doc)))))
      pin (assoc :seon.ref/pin pin))
    (unresolved reference)))

(defn- namespace-name
  "A namespace named `reference`, stored or loaded on the host, as its name, doc line and the form that lists it;
  nil when no namespace has that name."
  {:malli/schema [:=> [:cat :seon.db/database-value simple-symbol?] [:or :nil :seon.ref/resolved-name :seon.error/value]]}
  [database reference]
  (let [found (db/pull database [:seon.ns/name :seon.ns/doc] [:seon.ns/name reference])
        loaded (find-ns reference)
        doc (or (:seon.ns/doc found) (some-> loaded meta :doc))]
    (cond
      (:seon.error/at found) found
      (or (:seon.ns/name found) loaded)
      (cond-> {:seon.ref/name reference :seon.render/operations [(list 'dir reference)]}
        (seq doc) (assoc :doc (first (str/split-lines (:summary (docstring doc)))))))))

(defn- no-summary
  "The problem on a var cited for its summary that has no docstring, with the form that reads it."
  {:malli/schema [:=> [:cat :seon.ref/name :seon.render/read-form] :seon.ref/problem]}
  [reference read-form]
  {:seon.ref/name reference :seon.ref/problem-kind :no-summary :seon.ref/message (problem-messages :no-summary)
   :seon.render/operations [read-form]})

(defn brief
  "One name a referent's own content names, shown one level: its name, a one-line summary and the form that reads
  it; never its content."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/name] [:or :seon.ref/brief :seon.ref/problem :seon.error/value]]}
  [database reference]
  (let [read-form (list 'my.program/source (if (keyword? reference) reference (list 'quote reference)))
        summary #(first (str/split-lines (:summary (docstring %))))]
    (cond
      (simple-symbol? reference)
      (or (namespace-name database reference)
          (unresolved reference))
      (qualified-keyword? reference)
      (let [found (schema-documentation database reference reference)]
        (cond (:seon.sci.eval/documentation-unavailable found)
              (unresolved reference)
              (:seon.error/at found) found
              :else {:seon.ref/name reference :seon.render/operations [read-form]}))
      :else
      (let [declared (cond (seq (db/datoms database :avet :seon.fn/sym reference)) [:seon.fn/sym reference]
                           (seq (db/datoms database :avet :seon.test/sym reference)) [:seon.test/sym reference]
                           :else [:seon.task/sym reference])
            found (db/pull database
                           [:seon.fn/sym :seon.fn/doc :seon.test/sym :seon.task/sym :seon.task/plan :seon.task/instructions]
                           declared)]
        (cond
          (:seon.error/at found) found
          ;; a step may declare only its instructions, taking its goal from its subject
          (:seon.task/sym found) {:seon.ref/name reference
                                  :doc (first (str/split-lines (some found [:seon.task/plan :seon.task/instructions])))}
          (:seon.test/sym found) {:seon.ref/name reference :seon.render/operations [read-form]}
          (:seon.fn/sym found) (if (:seon.fn/doc found)
                                 {:seon.ref/name reference :seon.render/operations [read-form] :doc (summary (:seon.fn/doc found))}
                                 (no-summary reference read-form))
          :else (let [library (library-name reference)]
                  (if (:seon.ref/problem-kind library)
                    library
                    (if (:seon.fn/doc library)
                      {:seon.ref/name reference :seon.render/operations [(list 'doc reference)] :doc (:seon.fn/doc library)}
                      (no-summary reference (list 'doc reference))))))))))

(defn- inner
  "The names a referent's own `text` cites by the one rule, each shown one level (`brief`), the `excluded` names
  (itself) left out; nil when none. A name that resolves to nothing is the referent's own defect and is not shown:
  it is no problem of the text that cites the referent."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.ref/name] :string]
                  [:or :nil [:vector [:or :seon.ref/brief :seon.error/value]]]]}
  [database excluded text]
  (let [roots (set (.list (io/file (fs/source-directory))))
        cited (filter :seon.ref/name (candidates (keep #(candidate roots nil %) (:seon.ref/spans (document text)))))
        names (into [] (comp (map :seon.ref/name) (remove excluded) (distinct)) cited)]
    (not-empty (into [] (keep #(let [shown (brief database %)] (when-not (= :unresolved (:seon.ref/problem-kind shown)) shown))) names))))

(defn- with-inner
  "`entry` with the references its own content makes, one level, when it makes any."
  {:malli/schema [:=> [:cat :map [:maybe [:vector :seon.schema/value]]] :map]}
  [entry references]
  (cond-> entry (seq references) (assoc :seon.ref/references references)))

(defn- declared-render
  "The render pair `schema-key` declares for its values on `database`'s schema: its `:seon.render/ai` and
  `:seon.render/html` properties, an entity row's included; once per projection."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword] :seon.ref/render-pair]}
  [database schema-key]
  (let [projection (schema/projection-from-database database)]
    (schema/projection-cache-value
     projection [::render schema-key]
     #(select-keys (some-> (mr/schema (:seon.schema.projection/registry projection) schema-key)
                           (as-> s (or (internal/entity-properties s) (m/properties s))))
                   [:seon.render/ai :seon.render/html]))))

(defn- declarations
  "Each identity attribute's declaration on `database`'s schema, once per projection: the attributes its entity
  is pulled as (itself and its source attribute when its schema declares `:seon.ref/shown-by-source`, a referent
  that is its source, else its row's own attributes that are not refs) and its row schema."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:map-of :qualified-keyword :seon.ref/declaration]]}
  [database]
  (let [projection (schema/projection-from-database database)]
    (schema/projection-cache-value
     projection ::declarations
     (fn []
       (let [registry (:seon.schema.projection/registry projection)
             forms (:seon.schema.projection/forms projection)
             ref? (fn [form] (or (= :seon.db/ref form) (and (vector? form) (= :seon.db/ref (peek form)))))]
         (into {} (keep (fn [attribute]
                          (let [{row :seon.program/row-schema source :seon.program/source-attribute shown-by-source :seon.ref/shown-by-source}
                                (some-> (mr/schema registry attribute) m/properties)
                                definition (some->> row (mr/schema registry))]
                            (when definition
                              [attribute {:seon.ref/pull (if shown-by-source
                                                           [attribute source]
                                                           (into [] (comp (map first) (filter qualified-keyword?) (remove #(ref? (get forms %))))
                                                                 (internal/entity-entries definition)))
                                          :seon.ref/row row}]))))
               (keys forms)))))))

(defn- identity-of
  "The identity attribute whose index holds `reference` on `database`, or nil: an index probe."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/name] [:maybe :qualified-keyword]]}
  [database reference]
  (some #(when (seq (db/datoms database :avet % reference)) %)
        (cond (keyword? reference) [:seon.schema/key]
              (simple-symbol? reference) [:seon.ns/name]
              :else [:seon.fn/sym :seon.test/sym :seon.task/sym])))

(defn- own-prose
  "The text an entity's own content cites names in: its docstring, plan and instructions, and its schema form's
  description."
  {:malli/schema [:=> [:cat :map] :string]}
  [entity]
  (let [form (some-> (:seon.schema/form entity) edn/read-string)
        description (when (and (vector? form) (map? (second form))) (:description (second form)))]
    (str/join "\n\n" (keep identity (conj (mapv entity [:seon.fn/doc :seon.ns/doc :seon.task/plan :seon.task/instructions])
                                          description)))))

(defn- resolve-name
  "One referenced deftest, task or namespace identified on `database`: the entity its identity attribute's index
  holds, pulled as that identity declares (`declarations`), with its row's declared render pair as its own render keys
  and the names its own content cites, one level (`inner`); a namespace loaded only on the host as the same kind of
  entity; or a problem."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/name]
                  [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]}
  [database reference]
  (let [attribute (identity-of database reference)
        known (declarations database)
        render #(declared-render database (:seon.ref/row (known %)))]
    (cond
      attribute
      (let [pull (:seon.ref/pull (known attribute))
            found (db/pull database (into pull [:seon.fn/doc :seon.ns/doc]) [attribute reference])]
        (if (:seon.error/at found)
          found
          (with-inner (merge (select-keys found pull) {:seon.ref/name reference} (render attribute))
            (inner database #{reference} (own-prose found)))))
      (find-ns reference) (merge {:seon.ref/name reference :seon.ns/name reference} (render :seon.ns/name))
      :else (unresolved reference))))

(defn- at-level
  "A function or schema key at the `level` its citation asks for, each the value of one REPL function (TASK-CHAIN
  §3a): its summary (`brief`), its doc (`seon.sci.lookup/function-documentation`, `schema-documentation`, a library
  Var's docstring parts and arglists) or its code (`my.program/source`; a library Var's doc with the form that reads
  its source); or a problem. The doc and code owners sit above this namespace, so they are resolved at the call."
  {:malli/schema [:=> [:cat :seon.db/database-value [:or :qualified-symbol :qualified-keyword] :seon.ref/level]
                  [:or :map :seon.ref/problem :seon.error/value]]}
  [database reference level]
  (let [unresolved (unresolved reference)
        source #((requiring-resolve 'my.program/source) database reference)]
    (cond
      (= :summary level) (brief database reference)
      (keyword? reference)
      (let [found (schema-documentation database reference reference)]
        (cond (:seon.sci.eval/documentation-unavailable found) unresolved
              (or (:seon.error/at found) (= :doc level)) found
              :else (source)))
      (seq (db/datoms database :avet :seon.fn/sym reference))
      (if (= :doc level) ((requiring-resolve 'seon.sci.lookup/function-documentation) database reference) (source))
      :else
      (if-let [{:keys [doc arglists] pin :seon.ref/pin} (library-definition reference)]
        ;; a Var with neither docstring nor arglists (a deftest's) is still its read form, never nothing
        (cond-> (or (merge (when doc (docstring doc)) (when arglists {:arglists arglists}))
                    {:seon.render/operations [(list 'clojure.repl/source reference)]})
          pin (assoc :seon.ref/pin pin)
          (= :code level) (assoc :seon.render/operations [(list 'clojure.repl/source reference)]))
        unresolved))))

(declare resolve-path based-file)

(defn- named?
  "Does resolve-only `reference` name an entity on `database`, by its identity attribute's index, or a namespace or
  Var loaded on the host? An index probe, so a prose word that names nothing costs no resolution."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/name] :boolean]}
  [database reference]
  (boolean
   (or (identity-of database reference)
       (cond (keyword? reference) false
             (simple-symbol? reference) (find-ns reference)
             :else (and (find-ns (symbol (namespace reference))) (find-var reference))))))

(defn- directory?
  "Does based `path` name a directory? A directory is not an entity."
  {:malli/schema [:=> [:cat :seon.ref/path] :boolean]}
  [path]
  (boolean (some-> (based-file path) :seon.ref/file io/file .isDirectory)))

(defn- alias-form?
  "Is `reference` a qualified symbol whose namespace part names no namespace the resolver knows (a stored program
  namespace, a loaded one, or a dependency-cache one)? Then it is an alias form, which is code and no reference
  (TASK-CHAIN §3a)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :seon.ref/name]] :boolean]}
  [database reference]
  (boolean
   (when (qualified-symbol? reference)
     (let [space (symbol (namespace reference))]
       (not (or (find-ns space)
                (seq (db/datoms database :avet :seon.ns/name space))
                (analyzer/cached-namespace space)))))))

(defn- answer
  "One candidate resolved on `database`, or its problem with what it means; nil for a directory, and for a
  resolve-only candidate that names nothing (it is prose)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/candidate]
                  [:or :nil :seon.ref/resolved :seon.ref/problem :seon.error/value]]}
  [database {:seon.ref/keys [problem-kind resolve-only path level] reference :seon.ref/name :as found}]
  (let [leveled? (and level (or (qualified-keyword? reference)
                                (and (qualified-symbol? reference)
                                     (not (seq (db/datoms database :avet :seon.test/sym reference)))
                                     (not (seq (db/datoms database :avet :seon.task/sym reference))))))
        resolved (cond
                   (alias-form? database reference) nil
                   (and resolve-only reference (not (named? database reference))) nil
                   leveled? (at-level database reference level)
                   reference (resolve-name database reference)
                   problem-kind (assoc (dissoc found :seon.ref/resolve-only) :seon.ref/message (problem-messages problem-kind))
                   (directory? path) nil
                   :else (resolve-path database found))]
    (cond
      (and resolve-only (:seon.ref/problem-kind resolved) (not (#{:no-lead :no-summary} (:seon.ref/problem-kind resolved)))) nil
      (or (nil? resolved) (:seon.error/at resolved) (:seon.ref/problem-kind resolved)) resolved
      :else (cond-> (assoc resolved :seon.ref/name reference) (nil? reference) (dissoc :seon.ref/name) level (assoc :seon.ref/level level)))))

(defn read-where
  "`(f at-db)` of the database value the `:seon.ref/at` of `request` names (`seon.db/read-at`: a branch's head or a
  commit, released after `f` answers; `(f nil)` when the store lacks it), else `(f database)` when `request` has no
  `:seon.ref/at`. The one way a read tool accepts `:seon.ref/at`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:map [:seon.ref/at {:optional true} :seon.ref/at]]
                       [:=> [:cat [:maybe :seon.db/database-value]] :any]] :any]}
  [database request f]
  (if-some [at (:seon.ref/at request)] (db/read-at database at f) (f database)))

(defn- answer-at
  "`answer` of a candidate, read where its `:seon.ref/at` names (`read-where`), else on `database`; a branch or
  commit the store lacks is unresolved."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/candidate]
                  [:or :nil :seon.ref/resolved :seon.ref/problem :seon.error/value]]}
  [database {at :seon.ref/at :as found}]
  (read-where database found
              (fn [at-db]
                (let [resolved (if at-db (answer at-db found) (unresolved (:seon.ref/name found)))]
                  (cond-> resolved (and (some? at) (map? resolved)) (assoc :seon.ref/at at))))))

(defn- cite-once
  "`answers` with each name shown once: a name resolved at top level, or already shown below an earlier referent, is
  cited by its `:seon.ref/name` alone in every later mention, and a schema an earlier doc value described is left
  out of a later one's `:schemas` (its contract still names it)."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [answers]
  (let [shown (into #{} (keep :seon.ref/name) answers)]
    (second
     (reduce (fn [[seen kept] answer]
               (let [[seen below] (reduce (fn [[seen below] brief]
                                            (let [n (:seon.ref/name brief)]
                                              (if (and n (seen n))
                                                [seen (conj below {:seon.ref/name n})]
                                                [(cond-> seen n (conj n)) (conj below brief)])))
                                          [seen []] (:seon.ref/references answer))
                     schemas (:schemas answer)
                     described (when (map? schemas) (into (empty schemas) (remove (comp seen key)) schemas))]
                 [(into seen (keys described))
                  (conj kept (cond-> answer
                               (seq below) (assoc :seon.ref/references below)
                               (map? schemas) (assoc :schemas described)
                               (and (map? schemas) (empty? described)) (dissoc :schemas)))]))
             [shown []] answers))))

(defn references
  "Each reference `source`'s text makes, resolved on `database` or a problem; its subject never cites itself.

  Held names come first, sorted, each read where its `:seon.ref/at` names when it is a versioned map (a branch or a
  commit, as a `[name at]` code span asks), then the held paths (each read as a code span is), then the text's in first-seen
  order: code spans and links, and the prose words that name an entity. A name in `:seon.ref/code` cited at doc level is read at its code level."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/source]
                  [:vector [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]]}
  [database {:seon.ref/keys [text names paths document-path subject code]}]
  (let [roots (set (.list (io/file (fs/source-directory))))
        held (concat (map (fn [reference] (if (map? reference) reference {:seon.ref/name reference})) (sort-by str names))
                     (keep #(span-candidate roots %) (sort paths)))
        found (keep #(candidate roots document-path %) (:seon.ref/spans (document text)))]
    (->> (candidates (concat held (remove #(and subject (= subject (:seon.ref/name %))) found)))
         (into [] (comp (map #(cond-> % (and (= :doc (:seon.ref/level %)) (contains? code (:seon.ref/name %))) (assoc :seon.ref/level :code)))
                        (keep #(answer-at database %))))
         cite-once)))

(defn names
  "The qualified symbols `text` cites, read by the one rule `references` resolves: each code span naming one, and
  each prose word naming one in a namespace whose source the loaded commit holds, so the same text always cites
  the same names."
  {:malli/schema [:=> [:cat :string] [:set :qualified-symbol]]}
  [text]
  (let [source (fs/source-directory)
        roots (set (.list (io/file source)))
        sourced? (fn [cited]
                  (let [stem (str/replace (munge (namespace cited)) "." "/")]
                    (some (fn [[root extension]] (.isFile (io/file source root (str stem extension))))
                          (for [root ["src" "test" "script"] extension [".clj" ".cljc"]] [root extension]))))]
    (into #{} (comp (keep #(candidate roots nil %))
                    (filter (comp qualified-symbol? :seon.ref/name))
                    (keep (fn [{cited :seon.ref/name only :seon.ref/resolve-only}] (when (or (not only) (sourced? cited)) cited))))
          (:seon.ref/spans (document text)))))

;;; ---------------------------------------------------------------------------
;;; A doc section
;;; ---------------------------------------------------------------------------

(defn- based-file
  "The file a based path names in the loaded commit (a `reference-code/<project>` path in its pinned extract), with
  its pin; nil when the path leaves its base or names an unpinned project."
  {:malli/schema [:=> [:cat :string] [:maybe :seon.ref/location]]}
  [path]
  (let [source (io/file (fs/source-directory))
        [base project & more] (str/split path #"/")
        pins (when (= "reference-code" base) (test.cache/gitlink-pins (.getPath source)))
        revision (get pins (str "reference-code/" project))
        [home pin] (if (= "reference-code" base)
                     (when revision
                       [(io/file (.getParentFile source) "pins" (str "reference-code-" project) revision)
                        {:seon.ref/project project :seon.ref/revision revision}])
                     [source nil])
        file (when home (io/file home (if pin (str/join "/" more) path)))]
    (when (and file (.startsWith (.normalize (.toPath file)) (.toPath home)))
      (cond-> {:seon.ref/file (.getPath file)} pin (assoc :seon.ref/pin pin)))))

(defn locate
  "A based path's file in the loaded commit, with its pin for a `reference-code/<project>` path, or nil."
  {:malli/schema [:=> [:cat :seon.ref/path] [:maybe :seon.ref/location]]}
  [path]
  (when-let [found (based-file path)]
    (when (.isFile (io/file (:seon.ref/file found))) found)))

(defn- nearest-files
  "The Markdown file names in the nearest existing directory above `path`, or the pinned projects."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [path]
  (let [directory (loop [parent (some->> (str/last-index-of path "/") (subs path 0))]
                    (let [found (when parent (based-file parent))]
                      (cond (and found (.isDirectory (io/file (:seon.ref/file found)))) (io/file (:seon.ref/file found))
                            parent (recur (some->> (str/last-index-of parent "/") (subs parent 0)))
                            :else (io/file (fs/source-directory)))))]
    (vec (sort (filter #(str/ends-with? % ".md") (.list directory))))))

(defn- section-error
  "The error for a reference with no file or no such heading, naming the nearest; a reference with no path names none."
  {:malli/schema [:=> [:cat :keyword [:maybe :seon.ref/path] [:maybe :seon.ref/anchor] :seon.ref/nearest] :seon.ref/section-error]}
  [missing path anchor nearest]
  (let [named (cond-> {} path (assoc :seon.ref/path path) anchor (assoc :seon.ref/anchor anchor))]
    (refusal/diagnostic (java.util.Date.) :seon.ref/section 'seon.ref/section
                        (cond-> (merge named {:seon.error/message (if (= :file missing) "No such file at this reference." "No heading with this anchor.")
                                              :seon.error/expected nearest
                                              :seon.ref/section-missing missing :seon.ref/nearest nearest})
                          (seq named) (assoc :seon.error/offending named)))))

(defn- span-tokens
  "The estimated tokens of source lines `from` (1-based) up to `end`."
  {:malli/schema [:=> [:cat [:vector :string] :int :int] :seon.ref/tokens]}
  [lines from end]
  (tokens/estimate (str/join "\n" (subvec lines (dec from) (dec end)))))

(defn- block-text
  "The source lines of the block at `span`, whole."
  {:malli/schema [:=> [:cat [:vector :string] :seon.ref/lines] :string]}
  [lines {from :seon.ref/line to :seon.ref/end-line}]
  (str/join "\n" (subvec lines (dec from) to)))

(defn section
  "The Markdown section `reference` names, with the headings below it and its size, or an error naming the nearest.
  It carries its lead (its heading's own next paragraph) or, when the heading opens with another block, that opening
  block whole; a whole file starts after its front matter."
  {:malli/schema [:=> [:cat :seon.ref/reference-text] [:or :seon.ref/section :seon.ref/section-error]]}
  [reference]
  (let [hash-at (str/index-of reference "#")
        path (cond-> reference hash-at (subs 0 hash-at))
        anchor (when hash-at (not-empty (subs reference (inc hash-at))))
        located (when (seq path) (locate path))]
    (if-not located
      (section-error :file (not-empty path) anchor (nearest-files path))
      (let [text (slurp (:seon.ref/file located))
            {:seon.ref/keys [headings line-count body-line]} (document text)
            lines (vec (str/split-lines text))
            by-line (into {} (map (juxt :seon.ref/line identity)) headings)
            first-heading (first headings)
            ;; a whole file is its H1 when nothing but blank lines come before it
            title (when (and first-heading (= 1 (:seon.ref/heading-level first-heading))
                             (every? str/blank? (subvec lines (dec body-line) (dec (:seon.ref/line first-heading)))))
                    first-heading)
            target (if anchor (first (filter #(= anchor (:seon.ref/anchor %)) headings)) (or title {}))]
        (if-not target
          (section-error :heading path anchor
                         (mapv #(select-keys % [:seon.ref/anchor :seon.ref/heading])
                               (sort-by (comp #(me/levenshtein anchor %) :seon.ref/anchor) headings)))
          (let [from (if anchor (:seon.ref/line target) body-line)
                stop (if anchor (:seon.ref/section-end target) (inc line-count))
                children (if (seq target)
                           (map by-line (:seon.ref/children target))
                           (let [nested (into #{} (mapcat :seon.ref/children) headings)]
                             (remove #(nested (:seon.ref/line %)) headings)))
                {:seon.ref/keys [lead-lines opening-lines]} target]
            (cond-> {:seon.ref/path path
                     :seon.ref/heading (or (:seon.ref/heading target) path)
                     :seon.ref/text (str/join "\n" (subvec lines (dec from) (dec stop)))
                     :seon.ref/tokens (span-tokens lines from stop)
                     :seon.ref/below (vec (for [child children]
                                            {:seon.ref/anchor (:seon.ref/anchor child) :seon.ref/heading (:seon.ref/heading child)
                                             :seon.ref/tokens (span-tokens lines (:seon.ref/line child) (:seon.ref/section-end child))}))}
              anchor (assoc :seon.ref/anchor anchor)
              lead-lines (assoc :seon.ref/lead (block-text lines lead-lines))
              opening-lines (assoc :seon.ref/opening (block-text lines opening-lines))
              (:seon.ref/pin located) (assoc :seon.ref/pin (:seon.ref/pin located)))))))))

(defn- lead-sentence
  "The first sentence of a section's `lead` paragraph, on one line."
  {:malli/schema [:=> [:cat :string] :string]}
  [lead]
  (first (first-sentence (str/join " " (map str/trim (str/split-lines lead))))))

(defn- section-view
  "The `found` section as its reference `level` shows it (TASK-CHAIN §3a): its summary (heading, lead's first
  sentence, size), its doc (lead and the headings below with sizes) or its code (the whole section). A Markdown
  section with no lead shows the structure instead (heading and size; the first block whole and the headings below)
  and answers `:no-lead`."
  {:malli/schema [:=> [:cat :seon.ref/section [:maybe :seon.ref/level] :boolean] :map]}
  [found level markdown?]
  (let [{:seon.ref/keys [lead]} found
        common [:seon.ref/path :seon.ref/anchor :seon.ref/heading :seon.ref/tokens :seon.ref/pin]
        shown (case level
                :summary (cond-> (select-keys found common) lead (assoc :doc (lead-sentence lead)))
                :code found
                (select-keys found (conj common :seon.ref/lead :seon.ref/opening :seon.ref/below)))]
    (cond-> shown
      (and markdown? (not= :code level) (not lead))
      (assoc :seon.ref/problem-kind :no-lead :seon.ref/message (problem-messages :no-lead) :seon.ref/level level))))

(defn- resolve-path
  "A based path with an optional anchor as its document entry at the level its citation asks for: heading, pin, the
  render pair `:seon.ref/resolved-doc` declares and, below summary, the names its text cites, one level; or a problem."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/candidate] [:or :seon.ref/resolved-doc :seon.ref/problem :seon.error/value]]}
  [database {:seon.ref/keys [path anchor level]}]
  (let [reference (cond-> path anchor (str "#" anchor))
        found (section reference)
        markdown? (str/ends-with? path ".md")]
    (case (:seon.ref/section-missing found)
      :file (unresolved path)
      :heading {:seon.ref/problem-kind :anchor-missing :seon.ref/path path :seon.ref/anchor anchor
                :seon.ref/message (problem-messages :anchor-missing)}
      (-> (section-view found level markdown?)
          (merge (declared-render database :seon.ref/resolved-doc))
          ;; only a Markdown document's text is prose; a source or data file is code
          (with-inner (when (and markdown? (not= :summary level))
                        ;; the names of the text the level shows: the lead or opening at doc, the section at code
                        (inner database #{} (if (= :code level) (:seon.ref/text found) (or (:seon.ref/lead found) (:seon.ref/opening found) "")))))))))

(defn render-doc-ai
  "A document or section reference as data: its path, anchor, heading and pin, the one read of its text, and the
  names its text cites, one level."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.ref/doc-view]}
  [unit]
  (let [{:seon.ref/keys [path anchor] :as entry} (:seon.render/value unit)]
    (-> entry
        (dissoc :seon.render/ai :seon.render/html)
        (assoc :seon.render/operations [(list 'my.fs/section (cond-> path anchor (str "#" anchor)))]))))

(defn render-doc-html
  "Render a document or section reference: its heading and the path it names."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [{:seon.ref/keys [path anchor heading]} (or (:seon.render/value unit) unit)]
    [:article {:class "seon-family-entry seon-doc"}
     [:header [:p {:class "seon-kicker"} "Document"] [:h3 heading]]
     [:p [:code (cond-> path anchor (str "#" anchor))]]]))
