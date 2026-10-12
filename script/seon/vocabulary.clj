(ns seon.vocabulary
  "Retired words left in prose, by file and entry of script/seon/vocabulary.edn.
  clojure -Sdeps '{:paths [\"script\" \"src\" \"resources\"]}' -M -m seon.vocabulary [PATH ...]
  (default: the living documents)
  Prose is Markdown outside fenced blocks and backtick spans, and EDN string
  literals outside backtick spans; code identifiers keep their spelling until
  their CN entry renames them. `report` answers one map; `-main` shows it through
  the value printer and exits 1 when a non-:prose? word remains."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.render.value :as value]))

(def table (edn/read-string (slurp (io/resource "seon/vocabulary.edn"))))

(defn- without-code
  "Markdown text with fenced blocks and backtick spans removed."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (-> text
      (str/replace #"(?ms)^```.*?^```" "")
      (str/replace #"`[^`]*`" "")
      (str/replace #"\]\([^)]*\)" "]")))

(defn- prose
  "The prose of one file: Markdown outside code, or an EDN file's string literals outside code."
  {:malli/schema [:=> [:cat :string] :string]}
  [path]
  (let [text (slurp (str path))]
    (if (str/ends-with? (str path) ".edn")
      (str/join "\n" (map (comp without-code second) (re-seq #"\"((?:[^\"\\]|\\.)*)\"" text)))
      (without-code text))))

(defn- current?
  "Whether `path` is a current document: not the table's own authority and not
  marked superseded, shelved or draft in its front matter."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (and (not (contains? (set (:authorities table)) path))
       (not (re-find #"(?m)\A---\n(?:.*\n)*?status:\s*(superseded|shelved|draft)" (slurp path)))))

(defn- files
  "The current Markdown and EDN files under `roots`."
  {:malli/schema [:=> [:cat [:sequential :string]] [:vector :string]]}
  [roots]
  (into [] (comp (mapcat #(file-seq (io/file %)))
                 (filter #(.isFile ^java.io.File %))
                 (map str)
                 (filter #(re-find #"\.(md|edn)$" %))
                 (filter current?))
        roots))

(defn- counts
  "Entry id -> occurrences in `text`, for entries that occur."
  {:malli/schema [:=> [:cat :string] [:map-of :string pos-int?]]}
  [text]
  (into (sorted-map)
        (keep (fn [{:keys [id pattern]}]
                (let [n (count (re-seq (re-pattern (str "(?i)" pattern)) text))]
                  (when (pos? n) [id n]))))
        (:retired table)))

(defn report
  "Retired-word occurrences in the prose under `roots`: per file, totals,
  and whether a strict word remains."
  {:malli/schema [:=> [:cat [:sequential :string]]
                  [:map [:files nat-int?] [:totals [:map-of :string pos-int?]]
                   [:remaining [:map-of :string pos-int?]] [:clean? :boolean]
                   [:by-file [:map-of :string [:map-of :string pos-int?]]]]]}
  [roots]
  (let [by-file (into (sorted-map) (keep (fn [f] (let [c (counts (prose f))] (when (seq c) [f c])))) (files roots))
        totals (into (sorted-map) (apply merge-with + (vals by-file)))
        strict (into #{} (comp (remove :prose?) (map :id)) (:retired table))]
    {:files (count by-file) :totals totals :remaining (select-keys totals strict)
     :clean? (not-any? strict (keys totals)) :by-file by-file}))

(defn -main
  "Show `report` of `roots` (default: the living documents); exit 1 unless clean."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& roots]
  (let [result (report (or (seq roots) (:living table)))]
    (println (value/core-text result 0))
    (shutdown-agents)
    (System/exit (if (:clean? result) 0 1))))
