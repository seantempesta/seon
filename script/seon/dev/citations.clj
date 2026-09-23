(ns seon.dev.citations
  "Skill citation checker: a `path:line` citation that no longer points at
  what it named fails by name, with its old and current line, instead of
  going stale in silence (AGENTS.md: a skill's every claim carries
  `file:line` and is verified when touched).

  A citation is one Markdown code span, `path:N` or `path:A-B`, or a
  continuation `:N` inheriting the previous path in the same block
  (paragraph, list item or table row). It is checked two ways:

  - Its SYMBOL: a code span joined to it (`sym` (`p:N`), `sym`, `p:N`, or
    `p:N` (`sym`)), the paired name of a list (`a`, `b` (`p:1`, `:2`)), or
    a name earlier in the block. A Clojure target's Vars come from
    clj-kondo's `:var-definitions` (row, end row; `declare` excluded); any
    other span is looked up as literal text on the target's lines.
  - Its BASELINE: the target's lines at the commit that last wrote the
    citing line unchanged. Unchanged lines pass; lines that moved are found
    again by exact content, so a symbol-less citation into a body is
    checked too.

  Statuses: ::current, ::contained (inside the named Var's form, not at its
  head), ::drifted (carries the current line), ::changed (the cited lines
  were edited since the baseline; read them), ::missing-file, ::unverifiable
  (no symbol and no baseline, or a bare filename no cited directory holds).
  ::drifted and ::missing-file fail; `--fix` rewrites each ::drifted line
  number in place. A target with another writer's uncommitted changes is
  read at HEAD unless the commit being checked carries it.

  CLI: bb script/seon/dev/citations.clj [--fix] [--verbose] [--worktree] [file ...]
  With no file, every Markdown file under .agents/skills is checked. The
  development hook (bin/seon-hook) runs `commit-request`/`commit-refusal`
  before a `git commit` that carries a skill file."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Contracts (ordinary Vars keep this Babashka-loadable)
;;; ---------------------------------------------------------------------------

(def status-schema
  [:enum ::current ::contained ::drifted ::changed ::missing-file ::unverifiable])

(def span-schema
  [:map
   [::text :string]
   [::line [:int {:min 1}]]
   [::column [:int {:min 0}]]])

(def citation-schema
  [:map
   [::document :string]
   [::line [:int {:min 1}]]
   [::column [:int {:min 0}]]
   [::text :string]
   [::cited-path :string]
   [::start [:int {:min 1}]]
   [::end [:int {:min 1}]]
   [::candidates [:vector :string]]
   [::explicit [:vector :string]]
   [::path {:optional true} :string]
   [::source-line {:optional true} :string]])

(def result-schema
  [:map
   [::document :string]
   [::line [:int {:min 1}]]
   [::text :string]
   [::status status-schema]
   [::symbol {:optional true} :string]
   [::current-start {:optional true} [:int {:min 1}]]
   [::current-end {:optional true} [:int {:min 1}]]
   [::occurrences {:optional true} [:vector [:int {:min 1}]]]
   [::reason {:optional true} :string]
   [::read-at {:optional true} :string]])

(def definition-schema
  [:map [::ns :symbol] [::name :symbol] [::row [:int {:min 1}]]
   [::end-row [:int {:min 1}]]])



(def check-request-schema
  [:map
   [::root :string]
   [::documents [:vector :string]]
   [::worktree-paths {:optional true} [:vector :string]]
   [::worktree? {:optional true} :boolean]
   ;; a disposable root links the checkout's cache instead of starting empty
   [::cache-directory {:optional true} :string]])

(def check-response-schema
  [:map
   [::results [:vector result-schema]]
   [::counts [:map-of status-schema :int]]
   [::failures [:vector result-schema]]
   [::cache-hits :int]
   [::cache-misses :int]
   [::elapsed-ms :int]])

;;; ---------------------------------------------------------------------------
;;; Code spans
;;; ---------------------------------------------------------------------------

(defn- line-spans
  "The inline code spans of one Markdown line, with their opening column."
  {:malli/schema [:=> [:cat :string [:int {:min 1}]] [:vector #'span-schema]]}
  [line line-number]
  (loop [from 0 spans []]
    (let [open (str/index-of line "`" from)
          close (when open (str/index-of line "`" (inc open)))]
      (if (and open close)
        (recur (inc close)
               (if (= close (inc open))
                 spans
                 (conj spans {::text (subs line (inc open) close)
                              ::line line-number
                              ::column open})))
        spans))))

(defn- digits?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [s]
  (and (pos? (count s)) (every? #(Character/isDigit (char %)) s)))

(defn- line-range
  "[start end] for `N` or `A-B`, or nil."
  {:malli/schema [:=> [:cat :string]
                  [:maybe [:tuple [:int {:min 1}] [:int {:min 1}]]]]}
  [s]
  (let [dash (str/index-of s "-")
        [a b] (if dash [(subs s 0 dash) (subs s (inc dash))] [s s])]
    (when (and (digits? a) (digits? b))
      (let [a (parse-long a) b (parse-long b)]
        (when (and (pos? a) (<= a b)) [a b])))))

(defn- blank-free?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [s]
  (not-any? #(Character/isWhitespace (char %)) s))

(defn- span-citation
  "{::cited-path ::start ::end} for a `path:N` span, {::start ::end} for a
  `:N` continuation, else nil."
  {:malli/schema [:=> [:cat :string] [:maybe :map]]}
  [text]
  (when (blank-free? text)
    (let [colon (str/last-index-of text ":")
          lines (when colon (line-range (subs text (inc colon))))
          path (when colon (subs text 0 colon))]
      (cond
        (nil? lines) nil
        (= "" path) {::start (first lines) ::end (second lines)}
        ;; a git revision form (`abc123^:path`) cites history, not the tree
        (str/includes? path ":") nil
        (or (str/includes? path "/") (str/includes? path "."))
        {::cited-path path ::start (first lines) ::end (second lines)}
        :else nil))))

(def ^:private file-extensions
  [".clj" ".cljc" ".cljs" ".edn" ".md" ".java" ".json" ".css" ".js" ".sh"])

(defn- symbol-candidate
  "The span's text when it reads as one symbol or keyword (a name a target
  can define or hold), else nil: prose code, forms and paths are no name."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [text]
  (let [text (cond-> text (str/starts-with? text "#'") (subs 2))]
    (when (and (<= 2 (count text))
               (blank-free? text)
               (not-any? #(str/ends-with? text %) file-extensions)
               (not (str/ends-with? text "/"))
               (let [form (try (edn/read-string text)
                               ;; not EDN at all: prose code, not a name
                               (catch Exception _ ::not-a-name))]
                 (or (symbol? form) (keyword? form))))
      text)))

;;; ---------------------------------------------------------------------------
;;; Documents -> citations
;;; ---------------------------------------------------------------------------

(defn- block-start?
  "True when `line` opens a new block: a table row or a list item."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [line]
  (let [t (str/triml line)]
    (boolean
     (or (str/starts-with? t "|")
         (str/starts-with? t "- ")
         (str/starts-with? t "* ")
         (let [dot (str/index-of t ". ")]
           (and dot (digits? (subs t 0 dot))))))))

(defn- document-blocks
  "Lines grouped into blocks, fenced code excluded: [[line-number text] ...]."
  {:malli/schema [:=> [:cat :string] [:vector [:vector :any]]]}
  [content]
  (loop [[line & more] (map-indexed (fn [i l] [(inc i) l])
                                    (str/split-lines content))
         fenced? false blocks [] block []]
    (if-not line
      (cond-> blocks (seq block) (conj block))
      (let [[_ text] line
            fence? (str/starts-with? (str/triml text) "```")]
        (cond
          fence? (recur more (not fenced?) (cond-> blocks (seq block) (conj block)) [])
          fenced? (recur more true blocks block)
          (str/blank? text) (recur more false (cond-> blocks (seq block) (conj block)) [])
          (block-start? text) (recur more false (cond-> blocks (seq block) (conj block)) [line])
          :else (recur more false blocks (conj block line)))))))

(defn- adjacent-gap?
  "True when the text between two spans on one line joins them: nothing but
  spaces and one of `(`, `,` or nothing (`sym` (`path:N`), `sym`, `path:N`)."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [gap]
  (boolean (and gap (#{"" "(" ","} (str/trim gap)))))

(defn- spans-with-gaps
  "A block's spans in reading order, each with ::gap-before / ::gap-after:
  the text between it and its neighbour on the same line, else absent."
  {:malli/schema [:=> [:cat [:vector :any]] [:vector :map]]}
  [block]
  (into []
        (mapcat
         (fn [[n text]]
           (let [spans (line-spans text n)
                 gap (fn [a b] (subs text (+ (::column a) (count (::text a)) 2) (::column b)))]
             (map-indexed
              (fn [i span]
                (cond-> span
                  (pos? i) (assoc ::gap-before (gap (nth spans (dec i)) span))
                  (< (inc i) (count spans)) (assoc ::gap-after (gap span (nth spans (inc i))))))
              spans))))
        block))

(defn- block-citations
  "The citations of one block, before group pairing: each with its own
  window (candidate spans since the previous citation, reading order) and
  its explicit candidates — a candidate span joined to it (`sym` (`p:N`),
  `sym` `p:N`) or a `(`sym`)` right after it."
  {:malli/schema [:=> [:cat :string [:vector :any]] [:vector :map]]}
  [document block]
  (let [spans (spans-with-gaps block)]
    (loop [i 0 window [] previous-path nil out []]
      (if (= i (count spans))
        out
        (let [span (nth spans i)
              cite (span-citation (::text span))
              path (or (::cited-path cite) previous-path)
              candidate (fn [j] (when (< -1 j (count spans))
                                  (let [s (nth spans j)]
                                    (when-not (span-citation (::text s))
                                      (symbol-candidate (::text s))))))]
          (cond
            (nil? cite)
            (recur (inc i) (if-let [c (symbol-candidate (::text span))] (conj window c) window)
                   previous-path out)

            (nil? path)
            (recur (inc i) [] previous-path out)

            :else
            (let [before (when (adjacent-gap? (::gap-before span)) (candidate (dec i)))
                  after (when (= "(" (some-> (::gap-after span) str/trim)) (candidate (inc i)))]
              (recur (inc i) [] path
                     (conj out
                           (merge (select-keys span [::text ::line ::column])
                                  {::document document
                                   ::cited-path path
                                   ::continuation? (nil? (::cited-path cite))
                                   ::start (::start cite)
                                   ::end (::end cite)
                                   ::window window
                                   ::explicit (vec (remove nil? [after before]))}))))))))))

(defn- paired
  "Candidates per citation. A run of k citations whose first has a window of
  at least k candidates and whose rest are bare continuations
  (`a`, `b` and `c` (`x.clj:1`, `:2`, `:3`)) pairs the i-th citation with the
  i-th of the window's last k candidates. Every other citation keeps its own
  window, nearest first, after its explicit candidates; a continuation with
  no window of its own has only its explicit candidates."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector #'citation-schema]]}
  [cites]
  (let [runs (reduce (fn [runs c]
                       (if (and (seq runs) (::continuation? c) (empty? (::window c)))
                         (conj (pop runs) (conj (peek runs) c))
                         (conj runs [c])))
                     []
                     cites)]
    (into []
          (mapcat
           (fn [run]
             (let [window (::window (first run))
                   k (count run)
                   pair? (and (< 1 k) (<= k (count window)))]
               (map-indexed
                (fn [i c]
                  ;; a pairing orders candidates; only adjacency makes one
                  ;; explicit, because a list of names may pair by accident
                  (let [pairing (when pair? [(nth window (+ (- (count window) k) i))])
                        own (if (zero? i) (vec (rseq window)) [])]
                    (-> c
                        (dissoc ::window ::continuation?)
                        (assoc ::candidates
                               (vec (distinct (concat pairing (::explicit c) own)))))))
                run))))
          runs)))

(defn citations
  "Every citation in one document, with its symbol candidates in precedence
  order (explicit first, then its own window nearest first)."
  {:malli/schema [:=> [:cat :string :string] [:vector #'citation-schema]]}
  [document content]
  (into [] (mapcat #(paired (block-citations document %))) (document-blocks content)))

;;; ---------------------------------------------------------------------------
;;; Paths and definitions
;;; ---------------------------------------------------------------------------

(defn- resolve-paths
  "Assoc ::path onto each citation whose cited path names a file: the path
  from the root, else the one file it names below a directory of a full
  path cited in the same document (`db/transaction.cljc` beside a cited
  `reference-code/datahike/src/datahike/api/impl.cljc`)."
  {:malli/schema [:=> [:cat :string [:vector #'citation-schema]]
                  [:vector #'citation-schema]]}
  [root cites]
  (let [exists? (memoize #(fs/regular-file? (fs/path root %)))
        directories (->> cites
                         (map ::cited-path)
                         (filter exists?)
                         (mapcat (fn [p] (take-while some? (rest (iterate fs/parent (fs/path p))))))
                         (map str)
                         distinct
                         vec)]
    (mapv (fn [{::keys [cited-path] :as c}]
            (let [path (if (exists? cited-path)
                         cited-path
                         (let [ms (into #{}
                                        (comp (map #(str (fs/path % cited-path)))
                                              (filter exists?))
                                        directories)]
                           (when (= 1 (count ms)) (first ms))))]
              (cond-> c path (assoc ::path path))))
          cites)))

(defn- clojure-path?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (boolean (some #(str/ends-with? path %) [".clj" ".cljc" ".cljs"])))

(def ^:private kondo-config
  (str "{:output {:format :edn :canonical-paths true}"
       " :analysis {:var-usages false :locals false :keywords false"
       " :arglists false :protocol-impls false :instance-invocations false"
       " :java-class-definitions false :java-member-definitions false}"
       " :linters ^:replace {}}"))

(def lint-processes
  "clj-kondo processes one analysis runs at once. Measured 2026-09-23 on
  this checkout over the 77 Clojure files the skills cite: one process
  4,022 ms, 4 processes 1,735 ms, 8 1,165 ms, 12 822 ms (18 cores)."
  12)

(defn- analysis-entry
  "A kondo var-definition reduced to the members the checker reads."
  {:malli/schema [:=> [:cat :symbol :symbol [:int {:min 1}] [:int {:min 1}]]
                  #'definition-schema]}
  [entry-ns entry-var row end-row]
  {::ns entry-ns ::name entry-var ::row row ::end-row end-row})

(def ^:private extraction
  "What a cached entry was derived by, beside the file's bytes: the kondo
  configuration and the one extraction rule (declares dropped)."
  (str kondo-config " declare-excluded"))

(defn- content-digest
  "SHA-256 over the extraction identity, the clj-kondo version and the
  text, hex: the key of one text's cached analysis."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [kondo-version text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str extraction kondo-version) "UTF-8"))
    (.update md (.getBytes ^String text "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- lint-definitions
  "{root-relative-path [definition ...]} for `contents` ({path text}): one
  clj-kondo run per file over stdin (`--lint - --filename path`), at most
  `lint-processes` at once. A run that yields no analysis throws with its
  exit and stderr."
  {:malli/schema [:=> [:cat :string [:map-of :string :string]]
                  [:map-of :string [:vector #'definition-schema]]]}
  [root contents]
  (let [run (fn [[path text]]
              (let [result (process/sh ["clj-kondo" "--cache" "false" "--config" kondo-config
                                        "--lint" "-" "--filename" path]
                                       {:dir root :in text})
                    parsed (try (edn/read-string (:out result))
                                (catch Exception e
                                  (throw (ex-info "clj-kondo output is not EDN"
                                                  {::exit (:exit result)
                                                   ::err (:err result)
                                                   ::path path}
                                                  e))))]
                (when-not (map? (:analysis parsed))
                  (throw (ex-info "clj-kondo returned no analysis"
                                  {::exit (:exit result) ::err (:err result) ::path path})))
                [path (into []
                            (keep (fn [{entry-ns :ns entry-var :name :keys [row end-row defined-by]}]
                                    ;; a `declare` names a Var ahead of its
                                    ;; definition; it is no definition row
                                    (when (and row (not= 'clojure.core/declare defined-by))
                                      (analysis-entry entry-ns entry-var row (or end-row row)))))
                            (get-in parsed [:analysis :var-definitions]))]))
        entries (vec contents)
        per-process (max 1 (long (Math/ceil (/ (count entries) (double lint-processes)))))]
    (into {}
          (mapcat deref)
          (mapv #(future (mapv run %)) (partition-all per-process entries)))))

(def ^:private kondo-version
  "The clj-kondo on PATH, asked once per process: part of every cache key."
  (delay (str/trim (:out (process/sh ["clj-kondo" "--version"])))))

(defn- cache-file
  "The analysis cache entry for one content digest."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [cache-directory digest]
  (str (fs/path cache-directory (str digest ".edn"))))

(defn definitions
  "Var definitions per root-relative Clojure path of `contents` ({path
  text}), and the cache counts: {::definitions {path [...]} ::hits ::misses}.

  The analysis is clj-kondo's own `:var-definitions` (row and end row). Each
  text's result is keyed by the SHA-256 of the extraction, the clj-kondo
  version and the text, in `cache-directory` (the check's default is
  <root>/tmp/citations/analysis), so one content is
  linted once: clj-kondo's own cache (.clj-kondo/.cache) is keyed by
  namespace, not content, and keeps no end rows, so it cannot answer this.
  Only misses are linted."
  {:malli/schema [:=> [:cat :string :string [:map-of :string :string]] map?]}
  [root cache-directory contents]
  (let [version @kondo-version
        digests (update-vals contents #(content-digest version %))
        cached (into {}
                     (keep (fn [[p d]]
                             (let [f (cache-file cache-directory d)]
                               (when (fs/regular-file? f) [p (edn/read-string (slurp f))]))))
                     digests)
        misses (into {} (remove (comp cached key)) contents)
        linted (when (seq misses) (lint-definitions root misses))]
    (doseq [[p entries] linted
            :let [f (cache-file cache-directory (get digests p))]]
      (fs/create-dirs (fs/parent f))
      (spit f (pr-str entries)))
    {::definitions (merge cached linted)
     ::hits (count cached)
     ::misses (count misses)}))

;;; ---------------------------------------------------------------------------
;;; Baseline: the target as it was when the citation was committed
;;; ---------------------------------------------------------------------------

(defn- git-out
  "stdout of one git command in `dir`, or nil when git answers nonzero (an
  object or path absent at that commit is a declared absence, not a fault)."
  {:malli/schema [:=> [:cat :string [:vector :string]] [:maybe :string]]}
  [dir argv]
  (let [{:keys [exit out]} (process/sh (into ["git"] argv) {:dir dir})]
    (when (zero? exit) out)))

(defn- submodule-paths
  "The gitlink paths .gitmodules declares, longest first."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [root]
  (let [f (str (fs/path root ".gitmodules"))]
    (if-not (fs/regular-file? f)
      []
      (->> (str/split-lines (slurp f))
           (map str/trim)
           (filter #(str/starts-with? % "path = "))
           (map #(subs % (count "path = ")))
           (sort-by (comp - count))
           vec))))

(defn- input-stream?
  "True for a java.io.InputStream."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? java.io.InputStream value))

(defn- read-exactly
  "`n` bytes of `in` as a UTF-8 string."
  {:malli/schema [:=> [:cat [:fn {:error/message "must be an InputStream"} #'input-stream?] :int]
                  :string]}
  [^java.io.InputStream in n]
  (String. (.readNBytes in (int n)) "UTF-8"))

(defn- read-header
  "One `git cat-file` header line of `in`, without its newline."
  {:malli/schema [:=> [:cat [:fn {:error/message "must be an InputStream"} #'input-stream?]]
                  :string]}
  [^java.io.InputStream in]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (if (or (= b 10) (= b -1))
          (.toString out "UTF-8")
          (do (.write out (int b)) (recur)))))))

(defn- cat-file-batch
  "{object-name text-or-nil} for `names` (`rev:path`), from `git cat-file`
  in `dir`: one --batch-check names every object, then one --batch reads
  each DISTINCT object once (a file unchanged across commits is one blob).
  A name git reports missing maps to nil."
  {:malli/schema [:=> [:cat :string [:vector :string]] [:map-of :string [:maybe :string]]]}
  [dir names]
  (if (empty? names)
    {}
    (let [check (process/sh ["git" "cat-file" "--batch-check=%(objectname)"]
                            {:dir dir :in (str (str/join "\n" names) "\n")})
          _ (when-not (zero? (:exit check))
              (throw (ex-info "git cat-file --batch-check failed"
                              {::dir dir ::exit (:exit check) ::err (:err check)})))
          ids (zipmap names
                      (map #(when-not (str/ends-with? % " missing") %)
                           (str/split-lines (:out check))))
          objects (vec (distinct (remove nil? (vals ids))))
          proc (process/process ["git" "cat-file" "--batch"]
                                {:dir dir :in (str (str/join "\n" objects) "\n")})
          in (:out proc)
          texts (into {}
                      (map (fn [object]
                             (let [header (read-header in)
                                   size (parse-long (subs header (inc (str/last-index-of header " "))))
                                   text (read-exactly in size)]
                               (.read ^java.io.InputStream in)
                               [object text])))
                      objects)
          {:keys [exit]} @proc]
      (when-not (zero? exit)
        (throw (ex-info "git cat-file --batch failed" {::dir dir ::exit exit})))
      (update-vals ids #(get texts %)))))

(defn- lines-at-commits
  "{[commit path] lines-or-nil} for every requested pair, following a
  gitlink into its submodule at that commit. One `git ls-tree` per commit
  that needs a gitlink, then one `git cat-file --batch` per repository."
  {:malli/schema [:=> [:cat :string [:vector [:tuple :string :string]]] map?]}
  [root pairs]
  (let [submodules (submodule-paths root)
        module-of (fn [path] (first (filter #(str/starts-with? path (str % "/")) submodules)))
        link-needs (distinct (for [[c p] pairs :let [m (module-of p)] :when m] [c m]))
        links (into {}
                    cat
                    (pmap
                     (fn [[commit modules]]
                       (for [line (some-> (git-out root (into ["ls-tree" commit "--"] modules))
                                          str/split-lines)
                             ;; `<mode> SP <type> SP <object> TAB <path>`
                             :let [tab (str/index-of line "\t")
                                   type-at (str/index-of line " ")
                                   object-at (str/index-of line " " (inc type-at))]]
                         [[commit (subs line (inc tab))] (subs line (inc object-at) tab)]))
                     (update-vals (group-by first link-needs) #(vec (distinct (map second %))))))
        named (for [[c p :as pair] pairs
                    :let [m (module-of p)]]
                (if m
                  (when-let [link (get links [c m])]
                    [pair m (str link ":" (subs p (inc (count m))))])
                  [pair nil (str c ":" p)]))
        by-repo (group-by second (remove nil? named))
        texts (into {}
                    cat
                    (pmap (fn [[m entries]]
                              (let [dir (if m (str (fs/path root m)) root)
                                    got (cat-file-batch dir (vec (distinct (map #(nth % 2) entries))))]
                                (map (fn [[pair _ n]] [pair (get got n)]) entries)))
                          by-repo))
        ;; identical texts (one blob at several commits) split once
        split (memoize (fn [text] (vec (str/split-lines text))))]
    (into {} (map (fn [pair] [pair (some-> (get texts pair) split)])) pairs)))

(defn- document-baseline
  "{::commit c ::lines #{...}}: the last commit that wrote `document` and the
  document's lines then, or nil for a document Git has never committed."
  {:malli/schema [:=> [:cat :string :string] [:maybe :map]]}
  [root document]
  (when-let [commit (some-> (git-out root ["log" "-1" "--format=%H" "--" document])
                            str/trim
                            not-empty)]
    (when-let [text (git-out root ["show" (str commit ":" document)])]
      {::commit commit ::lines (set (str/split-lines text))})))

(defn- relocated
  "The line where `old` (the cited lines as committed) now starts in `lines`,
  nearest to `start`, or nil when that exact run of lines is nowhere."
  {:malli/schema [:=> [:cat [:vector :string] [:vector :string] [:int {:min 1}]]
                  [:maybe [:int {:min 1}]]]}
  [lines old start]
  (let [n (count old)
        first-line (first old)]
    (when (and (pos? n) (not (str/blank? first-line)))
      (let [at (into []
                     (keep (fn [i]
                             (when (and (= first-line (nth lines i))
                                        (<= (+ i n) (count lines))
                                        (= old (subvec lines i (+ i n))))
                               (inc i))))
                     (range (count lines)))]
        (when (seq at) (apply min-key #(Math/abs (long (- % start))) at))))))

;;; ---------------------------------------------------------------------------
;;; Verification
;;; ---------------------------------------------------------------------------

(defn- candidate-parts
  "[namespace-or-nil name] of a candidate (`ns/name` or `name`)."
  {:malli/schema [:=> [:cat :string] [:tuple [:maybe :string] :string]]}
  [candidate]
  (let [slash (str/index-of candidate "/")]
    (if (and slash (pos? slash) (< slash (dec (count candidate))))
      [(subs candidate 0 slash) (subs candidate (inc slash))]
      [nil candidate])))

(defn- named-entries
  "The definitions a candidate names, qualified (`ns/name`) or bare (`name`)."
  {:malli/schema [:=> [:cat [:vector #'definition-schema] :string]
                  [:vector #'definition-schema]]}
  [entries candidate]
  (let [[ns-part name-part] (candidate-parts candidate)]
    (filterv #(and (= name-part (str (::name %)))
                   (or (nil? ns-part) (= ns-part (str (::ns %)))))
             entries)))

(defn- text-lines
  "1-based numbers of the lines of `lines` containing `text`."
  {:malli/schema [:=> [:cat [:vector :string] :string] [:vector [:int {:min 1}]]]}
  [lines text]
  (into [] (keep-indexed (fn [i l] (when (str/includes? l text) (inc i)))) lines))

(defn- first-verdict
  "The first candidate `verdict` answers, as a result, or nil."
  {:malli/schema [:=> [:cat [:vector :string] ifn?] [:maybe #'result-schema]]}
  [candidates verdict]
  (some (fn [c] (some-> (verdict c) (assoc ::symbol c))) candidates))

(defn- verdict
  "The status of one resolved citation, tier by tier; each symbol tier is
  tried over ALL candidates before the next.

  1. a candidate Var is defined at the cited line                 current
  2. the cited lines are byte-identical to the committed baseline  current
  3. the baseline's cited lines now start elsewhere                drifted
  4. a candidate's text is on a cited line (a use, a keyword)      current
  5. the cited range lies inside a candidate Var's form            contained
  6. a candidate Var is defined elsewhere, or an EXPLICIT
     candidate's text (one joined to the citation) is elsewhere    drifted
  7. the cited lines changed since the baseline                    changed

  `old` is the target's lines at the commit that last wrote the citing line
  unchanged, or nil (a citation edited since, or an uncommitted document)."
  {:malli/schema [:=> [:cat #'citation-schema [:vector #'definition-schema]
                       [:vector :string] [:maybe [:vector :string]]]
                  [:maybe #'result-schema]]}
  [{::keys [start end path candidates explicit]} defined lines old]
  (let [clojure? (clojure-path? path)
        cited? (fn [row] (<= start row end))
        defs (fn [c] (when clojure? (named-entries defined c)))
        ;; a qualified Var is written by its alias or bare name at a use
        texts (memoize (fn [c] (text-lines lines (if clojure? (second (candidate-parts c)) c))))
        span (fn [ls] (when (<= end (count ls)) (subvec ls (dec start) end)))
        old-span (when old (span old))
        moved (fn [at limit]
                {::status ::drifted ::current-start at
                 ::current-end (cond-> (+ at (- end start)) limit (min limit))})]
    (or
     (first-verdict candidates
                    (fn [c] (when (some #(and (= start (::row %)) (<= end (::end-row %))) (defs c))
                              {::status ::current})))
     (when (and old-span (= old-span (span lines)))
       {::status ::current ::reason "unchanged since the citing commit"})
     (when-let [at (when old-span (relocated lines old-span start))]
       (let [named (first (filter #(some (fn [d] (= at (::row d))) (defs %)) candidates))]
         (cond-> (assoc (moved at nil) ::reason "the committed lines moved")
           named (assoc ::symbol named))))
     (first-verdict candidates
                    (fn [c] (when (some cited? (texts c)) {::status ::current})))
     (first-verdict candidates
                    (fn [c] (when (some #(<= (::row %) start end (::end-row %)) (defs c))
                              {::status ::contained})))
     (first-verdict
      candidates
      (fn [c]
        (let [ds (defs c)
              ts (when (some #{c} explicit) (texts c))]
          (cond
            (seq ds) (let [{::keys [row end-row]} (first ds)] (moved row end-row))
            (seq ts) (moved (apply min-key #(Math/abs (long (- % start))) ts) nil)))))
     (when old-span
       {::status ::changed
        ::reason "the cited lines changed since the citing commit; read them"}))))

(defn verify
  "One result per citation, against `defs`, the target files' lines and the
  citation's committed baseline."
  {:malli/schema [:=> [:cat #'citation-schema [:map-of :string [:vector #'definition-schema]]
                       ifn? ifn?]
                  #'result-schema]}
  [{::keys [document line text path cited-path candidates] :as cite} defs file-lines baseline-lines]
  (let [base {::document document ::line line ::text text}]
    (cond
      (nil? path)
      (if (str/includes? cited-path "/")
        (assoc base ::status ::missing-file ::reason (str cited-path " does not exist"))
        (assoc base ::status ::unverifiable
               ::reason (str "bare filename " cited-path
                             " is below no directory this document cites")))

      :else
      (merge base
             (or (verdict cite (get defs path []) (file-lines path) (baseline-lines cite))
                 {::status ::unverifiable
                  ::reason (if (seq candidates)
                             (str "no preceding span names something in " path
                                  ": " (str/join ", " candidates))
                             "no symbol precedes the citation")})))))

(defn skill-documents
  "Every Markdown file under the root's .agents/skills, root-relative, sorted."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [root]
  (->> (fs/glob (fs/path root ".agents/skills") "**.md")
       (map #(str (fs/relativize root %)))
       sort
       vec))

(def statuses
  [::current ::contained ::drifted ::changed ::missing-file ::unverifiable])

(defn check
  "Verify every citation in `documents` (root-relative Markdown paths).

  A target is read from the working tree when it is committed as it stands,
  or is one of `::worktree-paths` (the paths the commit being checked
  carries). A target with another writer's uncommitted changes is read at
  HEAD: those lines are not this commit's, and they are rechecked when they
  land. Such results carry ::read-at \"HEAD\". `::worktree? true` reads
  every target as it stands."
  {:malli/schema [:=> [:cat #'check-request-schema] #'check-response-schema]}
  [{::keys [root documents worktree-paths worktree? cache-directory]}]
  (let [started (System/nanoTime)
        worktree-paths (set worktree-paths)
        contents (into {} (map (fn [d] [d (slurp (str (fs/path root d)))])) documents)
        baselines (into {} (pmap (fn [d] [d (document-baseline root d)]) documents))
        cites (into []
                    (mapcat (fn [doc]
                              (let [lines (str/split-lines (get contents doc))]
                                (map #(assoc % ::source-line (nth lines (dec (::line %))))
                                     (resolve-paths root (citations doc (get contents doc)))))))
                    documents)
        targets (->> cites (keep ::path) distinct vec)
        ;; each (commit, target) read once, in one batch per repository
        old (lines-at-commits
             root
             (vec (distinct
                   (concat
                    (map (fn [p] ["HEAD" p]) targets)
                    (for [{::keys [document path source-line]} cites
                          :let [{::keys [commit] :as b} (get baselines document)]
                          :when (and path b (contains? (::lines b) source-line))]
                      [commit path])))))
        disk (into {} (map (fn [p] [p (slurp (str (fs/path root p)))])) targets)
        at-head (into #{}
                      (filter (fn [p]
                                (let [head (get old ["HEAD" p])]
                                  (and head
                                       (not worktree?)
                                       (not (contains? worktree-paths p))
                                       (not= head (str/split-lines (get disk p)))))))
                      targets)
        texts (into {}
                    (map (fn [p] [p (if (at-head p)
                                      (str (str/join "\n" (get old ["HEAD" p])) "\n")
                                      (get disk p))]))
                    targets)
        {::keys [hits misses] :as analysis}
        (definitions root
                     (or cache-directory (str (fs/path root "tmp/citations/analysis")))
                     (into {} (filter (comp clojure-path? key)) texts))
        defs (::definitions analysis)
        file-lines (memoize #(vec (str/split-lines (get texts %))))
        baseline-lines (fn [{::keys [document path source-line]}]
                         (let [{::keys [commit] :as b} (get baselines document)]
                           (when (and b (contains? (::lines b) source-line))
                             (get old [commit path]))))
        results (mapv (fn [c]
                        (cond-> (verify c defs file-lines baseline-lines)
                          (at-head (::path c)) (assoc ::read-at "HEAD")))
                      cites)
        failures (filterv #(#{::drifted ::missing-file} (::status %)) results)]
    {::results results
     ::counts (merge (zipmap statuses (repeat 0))
                     (frequencies (map ::status results)))
     ::failures failures
     ::cache-hits hits
     ::cache-misses misses
     ::elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))}))

;;; ---------------------------------------------------------------------------
;;; Fix
;;; ---------------------------------------------------------------------------

(defn- rewritten-span
  "The citation span's new text with the current line (range) substituted."
  {:malli/schema [:=> [:cat :string [:int {:min 1}] [:int {:min 1}]] :string]}
  [text start end]
  (let [colon (str/last-index-of text ":")]
    (str (subs text 0 (inc colon)) start (when (not= start end) (str "-" end)))))

(defn fix-content
  "`content` with each fixable drifted result's span rewritten in place."
  {:malli/schema [:=> [:cat :string [:vector #'citation-schema] [:vector #'result-schema]]
                  :string]}
  [content cites results]
  (let [edits (->> (map vector cites results)
                   (filter (fn [[_ r]] (and (= ::drifted (::status r)) (::current-start r))))
                   (group-by (fn [[c _]] (::line c))))
        lines (str/split-lines content)
        fixed (map-indexed
               (fn [i line]
                 (reduce (fn [line [c r]]
                           ;; right to left: earlier columns stay valid
                           (let [col (::column c)
                                 was (::text c)
                                 now (rewritten-span was (::current-start r) (::current-end r))]
                             (str (subs line 0 (inc col)) now
                                  (subs line (+ col 1 (count was))))))
                         line
                         (sort-by (comp - ::column first) (get edits (inc i)))))
               lines)]
    (str (str/join "\n" fixed) (when (str/ends-with? content "\n") "\n"))))

(defn fix!
  "Rewrite drifted citations in `documents`; returns the check it acted on."
  {:malli/schema [:=> [:cat #'check-request-schema] #'check-response-schema]}
  [{::keys [root documents] :as request}]
  (let [report (check request)
        by-doc (group-by ::document (::results report))]
    (doseq [doc documents
            :let [file (str (fs/path root doc))
                  content (slurp file)
                  cites (resolve-paths root (citations doc content))
                  results (get by-doc doc [])]
            :when (some #(and (= ::drifted (::status %)) (::current-start %)) results)]
      (spit file (fix-content content cites results)))
    report))

;;; ---------------------------------------------------------------------------
;;; Report and CLI
;;; ---------------------------------------------------------------------------

(defn format-result
  "One result as a line: where, the span, its symbol, status and reason."
  {:malli/schema [:=> [:cat #'result-schema] :string]}
  [{::keys [document line text status current-start current-end reason read-at]
    cited-symbol ::symbol}]
  (str document ":" line " `" text "`"
       (when cited-symbol (str " (" cited-symbol ")"))
       " " (name status)
       (when current-start
         (str ": now at " current-start
              (when (and current-end (not= current-start current-end))
                (str "-" current-end))))
       (when reason (str ": " reason))
       (when read-at (str " [read at " read-at "; the working tree has uncommitted changes]"))))

(defn format-report
  "Every failure (every result when `verbose?`) and the one summary line."
  {:malli/schema [:=> [:cat #'check-response-schema :boolean] :string]}
  [{::keys [counts failures results elapsed-ms cache-hits cache-misses]} verbose?]
  (str/join
   "\n"
   (concat
    (map format-result (if verbose? results failures))
    [(str (count results) " citations in " elapsed-ms " ms (analysis cache "
          cache-hits " hit, " cache-misses " miss): "
          (str/join ", " (map (fn [[k v]] (str (name k) " " v)) (sort-by key counts))))])))

;;; ---------------------------------------------------------------------------
;;; The commit gate: which skill files a `git commit` command carries
;;; ---------------------------------------------------------------------------

(defn shell-words
  "`command` split into shell words: whitespace separates words outside
  quotes; '...' and \"...\" group (quotes removed); a backslash escapes the
  next character outside single quotes; an unquoted newline becomes `;`.
  No expansion is performed."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [command]
  (loop [[ch & more] (seq command) open-quote nil word nil words []]
    (let [finish (fn [] (cond-> words word (conj word)))]
      (cond
        (nil? ch) (finish)
        (and (= ch \\) (not= open-quote \') (seq more))
        (recur (rest more) open-quote (str word (first more)) words)
        (and open-quote (= ch open-quote)) (recur more nil (str word) words)
        open-quote (recur more open-quote (str word ch) words)
        (or (= ch \') (= ch \")) (recur more ch (str word) words)
        ;; an unquoted newline ends a command, as `;` does: a heredoc body
        ;; is never the command's arguments
        (= ch \newline) (recur more nil nil (conj (finish) ";"))
        (Character/isWhitespace (char ch)) (recur more nil nil (finish))
        :else (recur more nil (str word ch) words)))))

(def ^:private separators #{"&&" "||" ";" "|" "&"})

(def ^:private valued-options
  "git commit options whose value is the next word."
  #{"-m" "-F" "-C" "-c" "-t" "--message" "--file" "--author" "--date"
    "--template" "--cleanup" "--fixup" "--squash" "--trailer" "--reuse-message"
    "--reedit-message" "--pathspec-from-file"})

(defn- commit-arguments
  "The words after `commit` in the first `git ... commit` segment, or nil."
  {:malli/schema [:=> [:cat [:vector :string]] [:maybe [:vector :string]]]}
  [words]
  (some (fn [segment]
          (let [segment (vec segment)
                git-at (.indexOf ^java.util.List segment "git")
                commit-at (when (<= 0 git-at)
                            (first (filter #(= "commit" (nth segment %))
                                           (range (inc git-at) (count segment)))))]
            (when commit-at (subvec segment (inc commit-at)))))
        (remove #(separators (first %)) (partition-by separators words))))

(defn- redirection?
  "True for a redirection word (`<<'EOF'`, `>out`, `2>&1`)."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [word]
  (let [head (first (drop-while #(Character/isDigit (char %)) word))]
    (boolean (#{\< \>} head))))

(defn- pathspecs
  "The pathspec words of commit arguments, up to the first redirection:
  everything after `--`, else every word that is neither an option nor an
  option's value."
  {:malli/schema [:=> [:cat [:vector :string]] [:vector :string]]}
  [arguments]
  (let [arguments (vec (take-while (complement redirection?) arguments))
        dashes (.indexOf ^java.util.List arguments "--")]
    (if (<= 0 dashes)
      (subvec arguments (inc dashes))
      (loop [[w & more] arguments out []]
        (cond
          (nil? w) out
          (valued-options w) (recur (rest more) out)
          (str/starts-with? w "-") (recur more out)
          :else (recur more (conj out w)))))))

(defn- git-names
  "The paths one `git diff --name-only` form names, root-relative."
  {:malli/schema [:=> [:cat :string [:vector :string]] [:vector :string]]}
  [root argv]
  (let [out (git-out root (into ["diff" "--name-only"] argv))]
    (when-not out
      (throw (ex-info "git diff --name-only failed" {::root root ::argv argv})))
    (vec (remove str/blank? (str/split-lines out)))))

(defn commit-request
  "The check a shell `command` run in `cwd` needs when it is a `git commit`
  that carries a skill file, or nil: {::root ::documents ::worktree-paths}.

  The commit's files are its pathspecs' changes against HEAD, or, with no
  pathspec, the staged files (plus tracked changes under -a). Those files
  are read as they stand; every other target at HEAD."
  {:malli/schema [:=> [:cat [:map [::root :string] [::cwd :string] [::command :string]]]
                  [:maybe #'check-request-schema]]}
  [{::keys [root cwd command]}]
  (when-let [arguments (commit-arguments (shell-words command))]
    (let [specs (->> (pathspecs arguments)
                     (map #(str (fs/normalize (fs/absolutize (fs/path cwd %)))))
                     (filter #(fs/starts-with? % root))
                     (mapv #(let [r (str (fs/relativize root %))] (if (= "" r) "." r))))
          all? (some #{"-a" "--all"} arguments)
          files (if (seq specs)
                  (into (git-names root (into ["HEAD" "--"] specs))
                        ;; a new file the command names is committed too
                        (filter #(fs/regular-file? (fs/path root %)) specs))
                  (cond-> (git-names root ["--cached"])
                    all? (into (git-names root ["HEAD"]))))
          files (vec (distinct files))
          documents (vec (sort (filter #(and (str/starts-with? % ".agents/skills/")
                                             (str/ends-with? % ".md")
                                             (fs/regular-file? (fs/path root %)))
                                       files)))]
      (when (seq documents)
        {::root root ::documents documents ::worktree-paths files}))))

(defn commit-refusal
  "The refusal text for a commit whose skill files carry a failing citation,
  or nil when every citation passes. Carries the check's own timing."
  {:malli/schema [:=> [:cat #'check-request-schema] [:maybe :string]]}
  [request]
  (let [{::keys [failures] :as report} (check request)]
    (when (seq failures)
      (str "BLOCKED: " (count failures) " skill citation(s) no longer point at what they name.\n"
           (format-report report false)
           "\nRepair the line numbers in place with: bb script/seon/dev/citations.clj --fix "
           (str/join " " (distinct (map ::document failures)))))))

(defn -main
  "CLI: check (or --fix) the named Markdown files, else every skill file;
  exit 1 when any citation drifted or names a missing file."
  [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        root (str (fs/canonicalize (or (System/getenv "SEON_CITATIONS_ROOT") ".")))
        named (vec (remove #(str/starts-with? % "--") args))
        documents (if (seq named)
                    (mapv #(str (fs/relativize root (fs/canonicalize %))) named)
                    (skill-documents root))
        request (cond-> {::root root ::documents documents}
                  ;; read every target as it stands, uncommitted lines included
                  (flags "--worktree") (assoc ::worktree? true))
        report (if (flags "--fix") (fix! request) (check request))
        after (if (flags "--fix") (check request) report)]
    (when (flags "--fix")
      (println (str "fixed " (count (filter ::current-start (::failures report)))
                    " drifted citation(s)")))
    (println (format-report after (boolean (flags "--verbose"))))
    (System/exit (if (seq (::failures after)) 1 0))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
