#!/usr/bin/env bb
;; Delete the named tasks from resources/seon/tasks/<ns>.edn and every `:seon/tasks`
;; tag naming them, in one sweep, from the checkout root:
;;   bb docs/research/agent-platform/2026-10-08/task-census/prune.clj ns/name ...
;; Entries are cut as text (string-aware brace matching), so the rest of each file keeps
;; its layout. A tag set left empty is removed with its key (and its `^{}` when alone).
(require '[clojure.java.io :as io] '[clojure.string :as str])

(defn form-end
  "Index just past the map that opens at `start` (a `{`), skipping strings."
  [^String text start]
  (loop [i start depth 0 in-string? false]
    (let [c (.charAt text i)]
      (cond in-string? (case c \\ (recur (+ i 2) depth true) \" (recur (inc i) depth false) (recur (inc i) depth true))
            (= c \") (recur (inc i) depth true)
            (= c \{) (recur (inc i) (inc depth) false)
            (= c \}) (if (= 1 depth) (inc i) (recur (inc i) (dec depth) false))
            :else (recur (inc i) depth false)))))

(defn drop-entry
  "`text` without the task entry named `task-name`; unchanged when it holds none."
  [text task-name]
  (if-let [start (some-> (re-matcher (re-pattern (str "#:seon\\.task\\{:name\\s+\"" (java.util.regex.Pattern/quote task-name) "\""))
                                     text)
                         (as-> m (when (.find m) (.start m))))]
    (let [brace (str/index-of text "{" start)
          end (form-end text brace)
          ;; take the whitespace before the entry with it, keep the vector's opening bracket
          lead (loop [i start] (if (and (pos? i) (Character/isWhitespace (.charAt text (dec i)))) (recur (dec i)) i))
          lead (if (= \[ (.charAt text (dec lead))) start lead)
          end (if (= lead start) (loop [i end] (if (Character/isWhitespace (.charAt text i)) (recur (inc i)) i)) end)]
      (str (subs text 0 lead) (subs text end)))
    text))

(defn drop-tag
  "`text` without the quoted task symbol `sym` in any `:seon/tasks` set."
  [text sym]
  (let [quoted (java.util.regex.Pattern/quote sym)
        pruned (-> text
                   (str/replace (re-pattern (str "#\\{'" quoted "(?=[\\s}])\\s*")) "#{")
                   (str/replace (re-pattern (str "\\s*'" quoted "(?=[\\s}])")) ""))]
    ;; only a file this symbol was removed from loses its emptied tag sets
    (if (= text pruned)
      text
      (-> pruned
          (str/replace #"\^\{:seon/tasks #\{\}\}\s*" "")
          (str/replace #":seon/tasks #\{\}\s*" "")))))

(let [syms *command-line-args*]
  (doseq [[task-ns names] (group-by #(first (str/split % #"/")) syms)
          :let [path (str "resources/seon/tasks/" task-ns ".edn")]]
    (spit path (reduce drop-entry (slurp path) (map #(second (str/split % #"/")) names))))
  (doseq [root ["src" "test" "script"]
          file (file-seq (io/file root))
          :when (and (.isFile file) (re-find #"\.(clj|cljc|bb)$" (.getName file)))
          :let [text (slurp file) pruned (reduce drop-tag text syms)]
          :when (not= text pruned)]
    (spit file pruned)
    (.println System/out (str "untagged " file))))
