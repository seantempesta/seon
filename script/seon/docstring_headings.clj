(ns seon.docstring-headings
  "The docstring heading sweep (TASK-CHAIN phase 2): every `Name:` section heading line in a docstring becomes a
  `## Name` Markdown heading, and the code of an Example or Examples section is fenced, so the one Markdown parse
  (`seon.ref/document`) reads the sections and excludes the example code. Run once with Babashka from the checkout:

  ```
  bb script/seon/docstring_headings.clj src test script
  ```

  It rewrites each docstring's string node in place (rewrite-clj), prints the inventory of converted headings by
  file and touches nothing outside docstrings."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(def headings
  "The section names written as `Name:` lines."
  ["Examples" "Example" "Edge cases" "Errors" "Known issues" "Costs" "Recommendations"])

(def defining
  "The forms whose first string after their name is a docstring."
  '#{defn defn- defmacro def defonce ns deftest defmulti defprotocol})

(defn- colon-heading
  "The section name and the text after its colon when `line` opens one, else nil."
  [line]
  (let [stripped (str/triml line)]
    (some #(when (str/starts-with? stripped (str % ":")) [% (str/trim (subs stripped (inc (count %))))]) headings)))

(defn- opens-section?
  "Does `line` open a section: a colon heading or a Markdown heading?"
  [line]
  (or (colon-heading line) (str/starts-with? (str/triml line) "#")))

(defn- indentation [line] (subs line 0 (- (count line) (count (str/triml line)))))

(defn- fenced
  "`code` lines as one fenced Clojure block at `indent`, trailing blank lines left off."
  [indent code]
  (let [code (vec (reverse (drop-while str/blank? (reverse code))))]
    (when (seq code) (concat [(str indent "```clojure")] code [(str indent "```")]))))

(defn- convert
  "`lines` of one docstring with every colon heading converted and each Example's code fenced; and the converted
  heading names."
  [lines]
  (loop [i 0 out [] converted []]
    (if (>= i (count lines))
      [out converted]
      (let [line (nth lines i)
            [heading rest-text] (colon-heading line)
            indent (if (zero? i) "  " (indentation line))
            markdown-example (and (not heading) (#{"## Example" "## Examples"} (str/trim line)))]
        (cond
          heading
          (let [end (or (first (filter #(opens-section? (nth lines %)) (range (inc i) (count lines)))) (count lines))
                following (subvec lines (inc i) end)
                body (cond->> following (seq rest-text) (cons (str indent rest-text)))
                lead (if (and (seq out) (not (str/blank? (peek out)))) [""] [])
                section (if (#{"Example" "Examples"} heading)
                          (concat [(str indent "## " heading) ""] (fenced indent body))
                          (concat [(str indent "## " heading) ""] body))]
            (recur end (into out (concat lead section)) (conj converted heading)))
          ;; an existing Example heading whose code is not yet fenced
          (and markdown-example
               (some-> (first (remove str/blank? (subvec lines (inc i)))) str/triml (str/starts-with? "(")))
          (let [end (or (first (filter #(opens-section? (nth lines %)) (range (inc i) (count lines)))) (count lines))
                code (drop-while str/blank? (subvec lines (inc i) end))]
            (recur end (into out (concat [line ""] (fenced indent code))) (conj converted (str/trim line))))
          :else (recur (inc i) (conj out line) converted))))))

(defn- docstring-loc
  "The docstring node of the defining form at `loc`, or nil."
  [loc]
  (when (and (= :list (z/tag loc)) (defining (z/sexpr (z/down loc))))
    (some->> (z/right (z/down loc))
             (iterate z/right)
             (take-while #(and % (not (#{:vector :list} (z/tag %)))))
             (filter #(= :multi-line (z/tag %)))
             first)))

(defn- sweep-file
  "Rewrite `path`'s docstrings; the converted heading names, or nil when none."
  [path]
  (let [text (slurp path)
        converted (volatile! [])
        root (z/of-string text {:track-position? false})
        result (loop [loc root]
                 (if (z/end? loc)
                   (z/root-string loc)
                   (let [doc (docstring-loc loc)
                         loc (if doc
                               (let [[lines names] (convert (vec (:lines (z/node doc))))]
                                 (if (seq names)
                                   (do (vswap! converted into names)
                                       (z/up (z/replace doc (n/string-node lines))))
                                   loc))
                               loc)]
                     (recur (z/next loc)))))]
    (when (seq @converted)
      (spit path result)
      @converted)))

(defn -main
  "Sweep each named file, or every Clojure file under each named directory, and print the inventory."
  [& roots]
  (let [inventory (into (sorted-map)
                        (for [root roots
                              file (if (fs/directory? root) (fs/glob root "**.{clj,cljc}") [root])
                              :let [names (sweep-file (str file))]
                              :when names]
                          [(str file) (frequencies names)]))]
    (doseq [[file counts] inventory] (println file counts))
    (println "files" (count inventory) "headings" (reduce + (mapcat vals (vals inventory))))))

(apply -main *command-line-args*)
