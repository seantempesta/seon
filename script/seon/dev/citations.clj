(ns seon.dev.citations
  "Skill citation check: a `path:N`, `path:A-B` or following `:N` code span
  must still hold its name (`name` (`path:N`)): a Clojure Var (clj-kondo
  `:var-definitions`) overlapping the lines, or the name on them. A missing
  path fails. `bb script/seon/dev/citations.clj [file ...]`; with no file, the
  staged skill Markdown (the git pre-commit hook). Git blame says what moved."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- spans
  "[[text open close] ...]: one Markdown line's code spans and backtick columns."
  [line]
  (loop [from 0 out []]
    (let [a (str/index-of line "`" from) b (when a (str/index-of line "`" (inc a)))]
      (if b (recur (inc b) (cond-> out (< (inc a) b) (conj [(subs line (inc a) b) a b]))) out))))

(defn- parse-cite
  "[path start end] for `path:N`, `path:A-B` or `:N` (path \"\"), else nil."
  [text]
  (let [i (str/last-index-of text ":") path (when i (subs text 0 i))
        r (when i (subs text (inc i))) d (some-> r (str/index-of "-"))
        a (some-> (if d (subs r 0 d) r) parse-long) b (some-> (if d (subs r (inc d)) r) parse-long)]
    (when (and a b (pos? a) (<= a b) (not-any? #(Character/isWhitespace (char %)) text)
               (not (str/includes? path ":"))
               (or (= "" path) (str/includes? path "/") (str/includes? path ".")))
      [path a b])))

(defn- blocks
  "[[first-line-number text] ...]: runs of non-blank lines outside fenced code."
  [content]
  (let [fence? #(str/starts-with? (str/triml %) "```")
        lines (str/split-lines content)
        fenced (rest (reductions #(if (fence? %2) (not %1) %1) false lines))]
    (->> (map vector (iterate inc 1) lines fenced)
         (partition-by (fn [[_ l f]] (or f (fence? l) (str/blank? l))))
         (remove (fn [[[_ l f]]] (or f (fence? l) (str/blank? l))))
         (map (fn [ls] [(ffirst ls) (str/join "\n" (map second ls))])))))

(defn citations
  "Every citation of one document: {:line :text :cited :start :end :name}. The
  i-th of a run of k takes the i-th of the k names before it (`a`, `b`
  (`p:1`, `:2`)); a run's first also takes `name` (`p:N`) or `p:N` (`name`)."
  [content]
  (for [[line0 text] (blocks content)
        :let [ss (spans text) n (count ss) cs (mapv (comp parse-cite first) ss)
              gap (fn [i] (str/trim (subs text (inc (nth (ss (dec i)) 2)) (second (ss i)))))
              name? (fn [i] (and (< -1 i n) (nil? (cs i))))
              joined? (fn [i] (and (< 0 i n) (cs i) (cs (dec i)) (= "," (gap i))))
              paths (vec (rest (reductions #(if (seq (first %2)) (first %2) %1) nil cs)))]
        j (range n)
        :let [[_ start end] (cs j) path (get paths j)]
        :when (and (cs j) path)
        :let [r (loop [i j] (if (joined? i) (recur (dec i)) i))
              k (- (loop [i j] (if (joined? (inc i)) (recur (inc i)) i)) r -1)
              nm (cond (and (pos? r) (#{"" "(" ","} (gap r)) (every? name? (range (- r k) r))
                            (every? #(#{"," "and" ", and" "or"} (gap %)) (range (- r k -1) r))) (first (ss (- j k)))
                       (not= j r) nil
                       (and (pos? j) (name? (dec j)) (#{"" "(" ","} (gap j))) (first (ss (dec j)))
                       (and (name? (inc j)) (= "(" (gap (inc j)))) (first (ss (inc j))))]]
    (cond-> {:line (+ line0 (count (filter #{\newline} (subs text 0 (second (ss j))))))
             :text (first (ss j)) :cited path :start start :end end}
      nm (assoc :name (str/replace-first nm "#'" "")))))

(defn- definitions
  "{path [{:row :end-row :name}]}: clj-kondo `:var-definitions`, one process per
  path, run concurrently (one run over many paths is serial: 0.95 s for 7 files).
  A run that yields no analysis throws with its exit and stderr."
  [root paths]
  (into {}
        (pmap (fn [path]
                (let [{:keys [exit out err]} (process/sh ["clj-kondo" "--cache" "false" "--config"
                                                          (str "{:output {:format :edn} :linters ^:replace {} :analysis {:var-usages false"
                                                               " :locals false :keywords false :arglists false :protocol-impls false}}")
                                                          "--lint" path] {:dir root})
                      analysis (:analysis (edn/read-string out))]
                  (when-not (map? analysis)
                    (throw (ex-info "clj-kondo returned no analysis" {:exit exit :err err :path path})))
                  [path (remove #(= 'clojure.core/declare (:defined-by %)) (:var-definitions analysis))]))
              paths)))

(defn- verdict
  "nil when the citation holds, else why it does not. A name holds when it
  (or its unqualified part) is on the cited lines, or its Var overlaps them; a
  name found nowhere in the file (a paraphrase) is unverified and holds."
  [{:keys [path cited start end] nm :name} lines defs]
  (let [bare (if (and nm (not-any? #(Character/isWhitespace (char %)) nm))
               (subs nm (inc (or (str/last-index-of nm "/") -1)))
               nm)
        vars (filter #(= bare (str (:name %))) defs)
        hits (keep-indexed #(when (and nm (or (str/includes? %2 nm) (str/includes? %2 bare))) (inc %1))
                           lines)]
    (cond
      (nil? path) (when (str/includes? cited "/") (str cited " does not exist"))
      (< (count lines) end) (str path " has " (count lines) " lines")
      (or (nil? nm) (some #(<= start % end) hits)
          (some #(and (<= (:row %) end) (<= start (:end-row %))) vars)) nil
      (seq vars) (str "`" nm "` is at " (str/join ", " (distinct (map #(str (:row %) "-" (:end-row %)) vars))))
      (seq hits) (str "`" nm "` is at line " (str/join ", " (take 3 hits))))))

(defn check
  "The failing citations of `documents` (root-relative) under `root`."
  [root documents]
  (let [cites (for [doc documents
                    :let [cs (citations (slurp (str (fs/path root doc))))
                          dirs (->> cs (map :cited) (filter #(fs/regular-file? (fs/path root %)))
                                    (mapcat #(take-while some? (rest (iterate fs/parent (fs/path %))))) distinct)]
                    c cs]
                (let [file? #(fs/regular-file? (fs/path root %))
                      ;; a bare filename resolves below one directory the document cites
                      ms (distinct (filter file? (map #(str (fs/path % (:cited c))) dirs)))]
                  (assoc c :doc doc :path (if (file? (:cited c)) (:cited c) (when (= 1 (count ms)) (first ms))))))
        lines (into {} (map (fn [p] [p (str/split-lines (slurp (str (fs/path root p))))]))
                    (distinct (keep :path cites)))
        ;; a name on its cited lines holds without analysis, so only the Clojure
        ;; files of text failures are linted: the Var may still overlap the lines
        suspects (filter #(verdict % (lines (:path %)) []) cites)
        defs (definitions root (distinct (filter #(some (partial str/ends-with? %) [".clj" ".cljc" ".cljs"])
                                                 (keep :path suspects))))]
    (for [c suspects :let [why (verdict c (lines (:path c)) (get defs (:path c) []))] :when why]
      (assoc c :why why))))

(defn -main
  "Print failing citations of `files` (default: staged skill Markdown); exit 1 on any."
  [& files]
  (let [started (System/nanoTime)
        root (str/trim (:out (process/sh ["git" "rev-parse" "--show-toplevel"])))
        docs (or (seq files)
                 (filter #(and (str/starts-with? % ".agents/skills/") (str/ends-with? % ".md"))
                         (str/split-lines (:out (process/sh ["git" "diff" "--cached" "--name-only"
                                                             "--diff-filter=d"] {:dir root})))))
        failures (check root docs)]
    (doseq [{:keys [doc line text why]} failures] (println (str doc ":" line " `" text "` " why)))
    (binding [*out* *err*]
      (println (str "citations: " (count docs) " documents, " (count failures) " failures, "
                    (quot (- (System/nanoTime) started) 1000000) " ms")))
    (System/exit (if (seq failures) 1 0))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
