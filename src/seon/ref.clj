(ns seon.ref
  "References in Markdown text (a task intent, a doc, a docstring), found by one
  parse and resolved by one rule: a code span is a candidate name or path, a
  link is a path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [seon.ai.tokens :as tokens]
            [seon.cache :as cache]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.lookup :as lookup]
            [seon.test.cache :as test.cache])
  (:import [java.net URI URISyntaxException]
           [org.commonmark.ext.front.matter YamlFrontMatterExtension]
           [org.commonmark.ext.heading.anchor IdGenerator]
           [org.commonmark.node AbstractVisitor Code FencedCodeBlock Heading IndentedCodeBlock Link Node SourceSpan]
           [org.commonmark.parser IncludeSourceSpans Parser]
           [org.commonmark.renderer.text TextContentRenderer]))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; One parse per text
;;; ---------------------------------------------------------------------------

(def ^:private ^Parser parser
  (-> (Parser/builder)
      (.extensions [(YamlFrontMatterExtension/create)])
      (.includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
      .build))

(def ^:private ^TextContentRenderer text-renderer (.build (TextContentRenderer/builder)))

(defn- parsed
  "The headings and the code spans and links of `text`, in document order."
  {:malli/schema [:=> [:cat :string] :seon.ref/document]}
  [^String text]
  (let [ids (.build (IdGenerator/builder))
        headings (java.util.ArrayList.)
        spans (java.util.ArrayList.)
        ;; commonmark's 0-based line index as the line an editor shows
        line (fn [^Node node] (inc (.getLineIndex ^SourceSpan (first (.getSourceSpans node)))))]
    (.accept (.parse parser text)
             (proxy [AbstractVisitor] []
               (visit [node]
                 (condp instance? node
                   Heading (let [heading (str/trim (.render text-renderer ^Node node))]
                             (.add headings {:seon.ref/level (.getLevel ^Heading node) :seon.ref/heading heading
                                             :seon.ref/anchor (.generateId ids heading) :seon.ref/line (line node)})
                             (proxy-super visitChildren node))
                   Code (.add spans {:seon.ref/span (.getLiteral ^Code node) :seon.ref/line (line node)})
                   Link (do (.add spans {:seon.ref/link (.getDestination ^Link node) :seon.ref/line (line node)})
                            (proxy-super visitChildren node))
                   ;; code is never a reference
                   FencedCodeBlock nil
                   IndentedCodeBlock nil
                   (proxy-super visitChildren node)))))
    {:seon.ref/headings (vec headings)
     :seon.ref/spans (vec spans)
     :seon.ref/line-count (count (str/split-lines text))}))

(defn document
  "Markdown text's headings and reference candidates, parsed once per digest."
  {:malli/schema [:=> [:cat :string] :seon.ref/document]}
  [^String text]
  (cache/lookup ::document (schema/sha-256 [(.getBytes text "UTF-8")])
                #(* 64 (+ (count (:seon.ref/headings %)) (count (:seon.ref/spans %))))
                #(parsed text)))

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
      (let [forms (try (edamame/parse-string-all span)
                       ;; a span that does not read is prose
                       (catch Exception _ nil))
            [form & more] forms]
        ;; only a span of exactly one qualified symbol or keyword is a name
        (when (and (nil? more) (or (qualified-symbol? form) (qualified-keyword? form)))
          {:seon.ref/name form})))))

(defn- candidate
  "One span or link read as a reference: a qualified name, a based path with an optional anchor, a line citation, or nil (prose)."
  {:malli/schema [:=> [:cat [:set :string] [:maybe :seon.ref/path] [:or :seon.ref/span-entry :seon.ref/link-entry]]
                  [:maybe :seon.ref/candidate]]}
  [roots document-path entry]
  (if-let [destination (:seon.ref/link entry)]
    (if (str/starts-with? destination "#")
      ;; an anchor alone names the document itself
      (when document-path (relative document-path (subs destination 1)))
      (link-candidate document-path destination))
    (span-candidate roots (:seon.ref/span entry))))

;;; ---------------------------------------------------------------------------
;;; Resolution
;;; ---------------------------------------------------------------------------

(defn- pinned-revision
  "The reference-code project and pinned revision a loaded resource URL names, or nil when it is not under a pin."
  {:malli/schema [:=> [:cat :string] [:or :nil :seon.ref/pin]]}
  [url]
  (when (str/starts-with? url "file:")
    (let [parts (mapv str (iterator-seq (.iterator (java.nio.file.Paths/get (URI. url)))))
          [project revision] (rest (drop-while #(not= "pins" %) parts))]
      (when (and revision (str/starts-with? project "reference-code-"))
        {:seon.ref/project (subs project 15) :seon.ref/revision revision}))))

(defn- library-name
  "A qualified symbol resolved read-only from the host's loaded Vars, else, when its namespace is not loaded,
  from the dependency cache's definition (never loading it), with its pinned revision."
  {:malli/schema [:=> [:cat :qualified-symbol] [:or :seon.ref/resolved :seon.ref/problem]]}
  [reference]
  (let [loaded? (find-ns (symbol (namespace reference)))
        v (when loaded? (find-var reference))
        m (meta v)
        unloaded (when-not loaded? (analyzer/unloaded-definition reference))
        pin (some-> (if v (some-> (:file m) io/resource str) (::analyzer/url unloaded)) pinned-revision)
        arglists (or (:arglists m) (::analyzer/arglists unloaded))
        doc (or (:doc m) (::analyzer/doc unloaded))]
    (if (or v unloaded)
      (cond-> {:seon.ref/name reference :seon.render/operations [(list 'doc reference) (list 'clojure.repl/source reference)]}
        arglists (assoc :arglists arglists)
        doc (assoc :doc (first (str/split-lines (:summary (lookup/docstring-parts doc)))))
        pin (assoc :seon.ref/pin pin))
      {:seon.ref/name reference :seon.ref/problem-kind :unresolved})))

(defn- stored-arglists
  "A function's stored arglists text read as data in its own namespace: `::k` is
  its namespace's keyword, `::alias/k` resolves through the loaded aliases."
  {:malli/schema [:=> [:cat :qualified-symbol :string] :seon.ref/arglists]}
  [function text]
  ;; stored as source text until PR-25 stores the arglists as data
  (let [home (symbol (namespace function))
        aliases (some-> (find-ns home) ns-aliases)]
    (edamame/parse-string text {:auto-resolve (fn [requested]
                                                (if (= :current requested)
                                                  home
                                                  (or (some-> (get aliases requested) ns-name) requested)))})))

(defn- resolve-name
  "One referenced name resolved to its current definition on `database`, or a problem."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/name]
                  [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]}
  [database reference]
  (let [unresolved {:seon.ref/name reference :seon.ref/problem-kind :unresolved}
        ;; a program declaration's source is answered by my.program/source, a value;
        ;; clojure.repl/source only prints and answers nil
        operations [(list 'doc reference) (list 'my.program/source (list 'quote reference))]]
    (if (qualified-keyword? reference)
      (let [found (lookup/schema-documentation database reference reference)]
        (cond
          (:seon.sci.eval/documentation-unavailable found) unresolved
          (:seon.error/at found) found
          :else {:seon.ref/name reference :seon.ref/schema-form (:form found)
                 :seon.render/operations [(list 'doc reference)]}))
      (let [function (db/pull database [:seon.fn/sym :seon.fn/arglists :seon.fn/spec :seon.fn/doc] [:seon.fn/sym reference])
            test-entity (when-not (:seon.fn/sym function) (db/pull database [:seon.test/sym] [:seon.test/sym reference]))]
        (cond
          (:seon.error/at function) function
          (:seon.fn/sym function)
          (cond-> {:seon.ref/name reference :seon.render/operations operations}
            (:seon.fn/arglists function) (assoc :arglists (stored-arglists reference (:seon.fn/arglists function)))
            (:seon.fn/spec function) (assoc :seon.fn/spec (:seon.fn/spec function))
            (:seon.fn/doc function) (assoc :doc (first (str/split-lines (:summary (lookup/docstring-parts (:seon.fn/doc function)))))))
          (:seon.error/at test-entity) test-entity
          (:seon.test/sym test-entity) {:seon.ref/name reference :seon.render/operations operations}
          :else (library-name reference))))))

(defn- answer
  "One candidate resolved on `database`, or its problem, at its line."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/candidate]
                  [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]}
  [database {:seon.ref/keys [line problem-kind] reference :seon.ref/name :as found}]
  (let [resolved (cond
                   reference (resolve-name database reference)
                   problem-kind found
                   ;; a path resolves once CS-01c3's resolve-path lands
                   :else (assoc found :seon.ref/problem-kind :unresolved))]
    (cond-> resolved (and line (not (:seon.error/at resolved))) (assoc :seon.ref/line line))))

(defn references
  "Each reference `source`'s text makes, resolved on `database` or a problem.

  Held names come first, sorted, then the text's in first-seen order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ref/source]
                  [:vector [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]]}
  [database {:seon.ref/keys [text names document-path]}]
  (let [roots (set (.list (io/file (fs/source-directory))))
        held (map (fn [reference] {:seon.ref/name reference}) (sort names))
        found (keep (fn [entry] (some-> (candidate roots document-path entry)
                                        (assoc :seon.ref/line (:seon.ref/line entry))))
                    (:seon.ref/spans (document text)))
        identity-of (juxt :seon.ref/name :seon.ref/path :seon.ref/anchor :seon.ref/problem-kind)]
    (->> (concat held found)
         (reduce (fn [[seen kept] c] (if (seen (identity-of c)) [seen kept] [(conj seen (identity-of c)) (conj kept c)]))
                 [#{} []])
         second
         (mapv #(answer database %)))))

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

(defn- common-prefix
  "The length of the shared leading characters of two strings."
  {:malli/schema [:=> [:cat :string :string] :int]}
  [a b]
  (count (take-while true? (map = a b))))

(defn- section-error
  "The error for a reference with no file or no such heading, naming the nearest."
  {:malli/schema [:=> [:cat :keyword :seon.ref/path [:maybe :seon.ref/anchor] :seon.ref/nearest] :seon.ref/section-error]}
  [missing path anchor nearest]
  (refusal/diagnostic (java.util.Date.) :seon.ref/section 'seon.ref/section
                      (cond-> {:seon.error/message (if (= :file missing) "No such file at this reference." "No heading with this anchor.")
                               :seon.ref/section-missing missing :seon.ref/path path :seon.ref/nearest nearest}
                        anchor (assoc :seon.ref/anchor anchor))))

(defn- section-end
  "The line after the section of the heading at `from` of `level`: the next heading at or above its level, else the end."
  {:malli/schema [:=> [:cat [:vector :seon.ref/heading-entry] :int :int :int] :int]}
  [headings from level end]
  (or (some #(when (and (> (:seon.ref/line %) from) (<= (:seon.ref/level %) level)) (:seon.ref/line %)) headings) end))

(defn- span-tokens
  "The estimated tokens of source lines `from` (1-based) up to `end`."
  {:malli/schema [:=> [:cat [:vector :string] :int :int] :seon.ref/tokens]}
  [lines from end]
  (tokens/estimate (str/join "\n" (subvec lines (dec from) (dec end)))))

(defn section
  "The Markdown section `reference` names, with the headings below it and its size, or an error naming the nearest."
  {:malli/schema [:=> [:cat :seon.ref/reference-text] [:or :seon.ref/section :seon.ref/section-error]]}
  [reference]
  (let [hash-at (str/index-of reference "#")
        path (cond-> reference hash-at (subs 0 hash-at))
        anchor (when hash-at (not-empty (subs reference (inc hash-at))))
        located (locate path)]
    (if-not located
      (section-error :file path anchor (nearest-files path))
      (let [text (slurp (:seon.ref/file located))
            {:seon.ref/keys [headings line-count]} (document text)
            lines (vec (str/split-lines text))
            end (inc line-count)
            target (if anchor
                     (first (filter #(= anchor (:seon.ref/anchor %)) headings))
                     {:seon.ref/level 0 :seon.ref/heading (or (:seon.ref/heading (first (filter #(= 1 (:seon.ref/level %)) headings))) path)
                      ;; the line after the front matter's closing delimiter
                      :seon.ref/line (if (= "---" (first lines)) (+ 2 (count (take-while #(not= "---" %) (rest lines)))) 1)})]
        (if-not target
          (section-error :heading path anchor
                         (mapv #(select-keys % [:seon.ref/anchor :seon.ref/heading])
                               (sort-by (comp - #(common-prefix anchor %) :seon.ref/anchor) headings)))
          (let [{:seon.ref/keys [level line heading]} target
                stop (if anchor (section-end headings line level end) end)
                inside (filter #(and (> (:seon.ref/line %) (if anchor line (dec line))) (< (:seon.ref/line %) stop)) headings)
                next-level (some->> inside (map :seon.ref/level) (filter #(> % level)) seq (apply min))]
            (cond-> {:seon.ref/path path :seon.ref/heading heading
                     :seon.ref/text (str/join "\n" (subvec lines (dec line) (dec stop)))
                     :seon.ref/tokens (span-tokens lines line stop)
                     :seon.ref/below (vec (for [child inside :when (= next-level (:seon.ref/level child))]
                                            {:seon.ref/anchor (:seon.ref/anchor child) :seon.ref/heading (:seon.ref/heading child)
                                             :seon.ref/tokens (span-tokens lines (:seon.ref/line child)
                                                                           (section-end headings (:seon.ref/line child) next-level stop))}))}
              anchor (assoc :seon.ref/anchor anchor)
              (:seon.ref/pin located) (assoc :seon.ref/pin (:seon.ref/pin located)))))))))
