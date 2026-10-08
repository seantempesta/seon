#!/usr/bin/env bb
;; Task references, per docs/research/agent-platform/2026-10-08/references/references.md:
;; code is qualified symbols, schemas qualified keywords, files tracked paths; file and line
;; are derived, never written. From the checkout root:
;;   bb refs.clj count [TASK-DIR]          counts (TASK-DIR defaults to resources/seon/tasks)
;;   bb refs.clj backtick [--apply FILES]  step 1: backtick bare names that resolve exactly
;;   bb refs.clj lines [--apply FILES]     step 2: drop `file.clj:N` where the intent names its definition
;;   bb refs.clj tmp [--apply FILES]       step 3: tmp/ evidence copied to evidence/ or dropped
;;   bb refs.clj problems                  step 5: the unresolved references left, per task
;; FILES is a comma-separated list of task files this run may rewrite (the free ones).
;; Step 4 (prose proofs to `:seon/tasks`) is proofs.clj.
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str]
         '[babashka.process :as p])

(defn clj-files
  "Every Clojure source file under `root`."
  [root]
  (filter #(and (.isFile %) (re-find #"\.(clj|cljc|bb)$" (.getName %))) (file-seq (io/file root))))

(def source-files (mapcat clj-files ["src" "test" "script"]))

(def defined-re
  #"\((?:defn-?|def|defmacro|defmulti|defonce|deftest|defprotocol|defrecord|deftype)\s+(?:\^(?::[^\s]+|\{[^}]*\})\s+)*([^\s\)\]\(]+)")

(defn file-ns
  "The namespace a source text declares."
  [text]
  (some-> (re-find #"\(ns\s+(?:\^\{[^}]*\}\s+|\^:[^\s]+\s+)*([^\s\)]+)" text) second))

(def vars
  ;; namespace -> defined names
  (into {} (for [file source-files :let [text (slurp file) n (file-ns text)] :when n]
             [n (into #{} (map second) (re-seq defined-re text))])))

(def schema-keys
  (into #{} (for [file (.listFiles (io/file "resources/seon/schemas"))
                  :when (str/ends-with? (.getName file) ".edn")
                  :let [value (try (edn/read-string {:default (fn [_ v] v)} (slurp file)) (catch Exception _ nil))]
                  :when (map? value)
                  k (keys value)
                  :when (qualified-keyword? k)]
              (str k))))

(defn resolves?
  "Whether a qualified symbol or keyword text names a var, namespace or schema key."
  [token]
  (if (str/starts-with? token ":")
    (contains? schema-keys token)
    (let [[n v] (str/split token #"/" 2)]
      (if v (boolean (some-> (vars n) (contains? v))) (contains? vars n)))))

(defn full-name
  "The fully qualified name bare `token` resolves to exactly, or nil; a short alias
  expands only when one namespace ending in that segment defines the name."
  [token]
  (if (str/starts-with? token ":")
    (when (schema-keys token) token)
    (let [[short var-name] (str/split token #"/" 2)]
      (if (some-> (vars short) (contains? var-name))
        token
        (let [owners (filter (fn [[n defined]] (and (str/ends-with? n (str "." short)) (contains? defined var-name))) vars)]
          (when (= 1 (count owners)) (str (ffirst owners) "/" var-name)))))))

(def token-re #"(?<![A-Za-z0-9'#:./*+!?<>=_-]):?[a-z][a-z0-9.*+!?<>=_-]*/[a-zA-Z0-9*+!?<>=_-]*[a-zA-Z0-9*+!?<>=_]")

(defn outside-backticks
  "`text` with `f` applied to each part outside backticks; f answers [part n]. Returns [text n]."
  [text f]
  (let [results (map-indexed (fn [i part] (if (odd? i) [part 0] (f part))) (str/split text #"`" -1))]
    [(str/join "`" (map first results)) (reduce + (map second results))]))

(defn backtick-part
  "One prose part with each resolving bare token backticked."
  [part]
  (let [n (volatile! 0)
        out (str/replace part token-re (fn [t] (if-let [full (full-name t)] (do (vswap! n inc) (str "`" full "`")) t)))]
    [out @n]))

;;; step 2: file.clj:N mentions

(def line-re #"(?<![A-Za-z0-9_/.-])((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\.(?:clj|cljc|bb)):(\d+)(?:-\d+)?")

(defn source-path
  "The one checkout source file a mention like `turn.clj` or `my/fs.clj` names, or nil."
  [mention]
  (let [hits (filter #(or (= (str %) mention) (str/ends-with? (str %) (str "/" mention))) source-files)]
    (when (= 1 (count hits)) (str (first hits)))))

(def spans
  ;; path -> [{:row :end-row :sym}] from clj-kondo's analysis, computed per file on demand
  (memoize (fn [path]
             (let [out (:out (p/sh ["clj-kondo" "--lint" path "--config"
                                    "{:output {:format :edn} :analysis {:var-definitions true} :linters ^:replace {}}"]))]
               (for [{:keys [row end-row ns name]} (get-in (edn/read-string {:default (fn [_ v] v)} out) [:analysis :var-definitions])]
                 {:row row :end-row end-row :sym (str ns "/" name)})))))

(defn line-symbol
  "The symbol whose definition spans line `n` of the file `mention` names, or nil."
  [mention n]
  (when-let [path (source-path mention)]
    (some #(when (<= (:row %) n (:end-row %)) (:sym %)) (spans path))))

(def placed (volatile! []))

(def ^:dynamic *intent*
  "The whole intent being rewritten: a line number is trusted only when the intent also names
  the definition it lands in, since lines drift and the mention may predate the current file."
  "")

(defn trusted-symbol
  "The symbol `file.clj:N` lands in, when the intent also names that definition."
  [mention n]
  (when-let [sym (line-symbol mention n)]
    (when (re-find (re-pattern (str "(?<![\\w.*+!?<>=-])" (java.util.regex.Pattern/quote (second (str/split sym #"/" 2))) "(?![\\w*+!?<>=-])"))
                   *intent*)
      sym)))

(defn lines-part
  "One prose part with each placeable `file.clj:N` mention rewritten to its symbol."
  [part]
  (let [n (volatile! 0)
        out (str/replace part line-re
                         (fn [[whole file line]]
                           (if-let [sym (trusted-symbol file (parse-long line))]
                             ;; the intent already names the definition, so the location just goes
                             (do (vswap! n inc) (vswap! placed conj [whole sym]) "")
                             (do (vswap! placed conj [whole nil]) whole))))]
    [(if (pos? @n) (-> out (str/replace #"\s*\(\s*\)" "") (str/replace #"\(\s*,\s*" "(") (str/replace #",\s*\)" ")")) out) @n]))

;;; file rewriting

(defn rewrite-intents
  "Task file `text` with `f` applied to every `:intent` string's prose parts: [text n]."
  [text f]
  (let [m (re-matcher #":intent\s+\"((?:[^\"\\]|\\.)*)\"" text)
        sb (StringBuilder.)]
    (loop [last-end 0 total 0]
      (if (.find m)
        (let [[new-raw n] (binding [*intent* (.group m 1)] (outside-backticks (.group m 1) f))]
          (.append sb (subs text last-end (.start m 1)))
          (.append sb new-raw)
          (recur (.end m 1) (+ total n)))
        (do (.append sb (subs text last-end)) [(str sb) total])))))

(def evidence-dir "docs/research/agent-platform/2026-10-08/task-census/evidence/")

(defn tmp-evidence
  "Task file `text` with each \"tmp/...\" evidence string copied under evidence/ (when the file
  exists and is under 1 MB) or dropped: [text n]. With `apply?` false nothing is copied."
  [text apply?]
  (let [n (volatile! 0)
        one (fn [evidence]
              (str/replace evidence #"\s*\"(tmp/[^\"]+)\""
                         (fn [[whole path]]
                           (vswap! n inc)
                           (let [file (io/file path)
                                 target (str evidence-dir (str/replace (subs path 4) "/" "--"))]
                             (if (and (.isFile file) (< (.length file) 1000000))
                               (do (when apply? (io/make-parents target) (io/copy file (io/file target)))
                                   (str/replace whole path target))
                               "")))))
        ;; only `:evidence` sets: an intent may quote a tmp/ path as prose
        out (str/replace text #":evidence\s+#\{[^}]*\}" one)]
    [(str/replace out #"#\{\s+\"" "#{\"") @n]))

;;; counts and problems

(defn tasks [dir]
  (for [file (sort (.listFiles (io/file dir)))
        :when (str/ends-with? (.getName file) ".edn")
        :let [n (.getName file)]
        entry (edn/read-string (slurp file))]
    (assoc entry ::sym (str (subs n 0 (- (count n) 4)) "/" (:seon.task/name entry)))))

(def tagged
  ;; tasks named by a deftest's `:seon/tasks` (a proof); a tag on a definition names its subject
  (into #{} (for [file (clj-files "test")
                  [_ body] (re-seq #":seon/tasks\s+#\{([^}]*)\}" (slurp file))
                  token (re-seq #"[A-Za-z0-9.*+!?<>=_-]+/[A-Za-z0-9.*+!?<>=_-]+" body)]
              token)))

(defn backticked [intent]
  (keep-indexed (fn [i part] (when (odd? i) part)) (str/split intent #"`" -1)))

(defn reference-span?
  "Whether a backticked span reads as one qualified symbol or keyword."
  [span]
  (boolean (re-matches #":?[a-zA-Z][\w.*+!?<>=-]*/[\w*+!?<>=-]+" span)))

(defn problems [{:seon.task/keys [intent must-read evidence]}]
  (concat (for [s must-read :when (not (resolves? (str s)))] [:must-read (str s)])
          (for [s (backticked intent)
                :when (and (reference-span? s) (re-find #"^:?(?:seon|my)\." s) (not (.exists (io/file s))) (not (resolves? s)))]
            ;; library names resolve on the host (CS-01c2), not in the checkout
            [:unresolved s])
          (for [e evidence :when (str/starts-with? e "tmp/")] [:not-tracked e])
          (for [e evidence :when (and (not (str/starts-with? e "tmp/")) (not (.exists (io/file e))))] [:missing e])))

(defn counts [dir]
  (let [ts (tasks dir)
        must (mapcat :seon.task/must-read ts)
        all-spans (mapcat (comp backticked :seon.task/intent) ts)
        refs (filter reference-span? all-spans)
        bare (reduce + (map #(second (outside-backticks (:seon.task/intent %) backtick-part)) ts))]
    {:tasks (count ts)
     :must-reads (count must) :must-reads-resolving (count (filter #(resolves? (str %)) must))
     :backticked-references (count refs) :backticked-resolving (count (filter resolves? refs))
     :bare-resolvable-names bare
     :file-line-mentions (count (mapcat #(re-seq line-re (:seon.task/intent %)) ts))
     :tmp-evidence (count (filter #(str/starts-with? % "tmp/") (mapcat :seon.task/evidence ts)))
     :tasks-with-a-linked-proof (count (filter #(tagged (::sym %)) ts))}))

(let [[command & args] *command-line-args*
      apply? (= "--apply" (first args))
      free (set (some-> (second args) (str/split #",")))
      files (sort (.listFiles (io/file "resources/seon/tasks")))
      sweep (fn [f]
              (doseq [file files
                      :let [path (str "resources/seon/tasks/" (.getName file))
                            [text n] (f (slurp file))]
                      :when (pos? n)]
                (.println System/out (str path "\t" n (when (and apply? (not (free path))) "\theld")))
                (when (and apply? (free path)) (spit file text))))]
  (case command
    "count" (.println System/out (str (counts (or (first args) "resources/seon/tasks"))))
    "backtick" (sweep #(rewrite-intents % backtick-part))
    "lines" (do (sweep #(rewrite-intents % lines-part))
                (doseq [[whole sym] @placed] (.println System/out (str "  " whole " -> " (or sym "unplaced")))))
    "tmp" (sweep #(tmp-evidence % apply?))
    "problems" (doseq [t (tasks "resources/seon/tasks") [k v] (problems t)]
                 (.println System/out (str (::sym t) "\t" (name k) "\t" v)))))
