#!/usr/bin/env bb
;; Bare names to references: in every task intent, wrap in backticks each bare token that
;; resolves exactly to an existing var (src, test, script) or a registered schema key
;; (resources/seon/schemas). A short alias `a/x` is rewritten to its full name only when
;; exactly one namespace ending in `.a` (or named `a`) defines `x`. Text already inside
;; backticks and everything that does not resolve stays prose. Prints counts per file;
;; `--apply` rewrites the files named by `--files`.
;;   bb docs/research/agent-platform/2026-10-08/task-census/backtick.clj [--apply --files a.edn,b.edn]
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(defn clj-files
  "Every Clojure source file under `root`."
  [root]
  (filter #(and (.isFile %) (re-find #"\.(clj|cljc)$" (.getName %))) (file-seq (io/file root))))

(def defined-re
  #"\((?:defn-?|def|defmacro|defmulti|defonce|deftest|defprotocol|defrecord|deftype)\s+(?:\^(?::[^\s]+|\{[^}]*\})\s+)*([^\s\)\]\(]+)")

(def vars
  ;; namespace -> defined names
  (into {} (for [root ["src" "test" "script"] file (clj-files root)
                 :let [text (slurp file)
                       file-ns (some-> (re-find #"\(ns\s+(?:\^\{[^}]*\}\s+|\^:[^\s]+\s+)*([^\s\)]+)" text) second)]
                 :when file-ns]
             [file-ns (into #{} (map second) (re-seq defined-re text))])))

(def schema-keys
  (into #{} (for [file (.listFiles (io/file "resources/seon/schemas"))
                  :when (str/ends-with? (.getName file) ".edn")
                  :let [value (try (edn/read-string {:default (fn [_ v] v)} (slurp file)) (catch Exception _ nil))]
                  :when (map? value)
                  k (keys value)
                  :when (qualified-keyword? k)]
              (str k))))

(defn full-name
  "The fully qualified name `token` resolves to exactly, or nil."
  [token]
  (if (str/starts-with? token ":")
    (when (schema-keys token) token)
    (let [[short var-name] (str/split token #"/" 2)]
      (if (some-> (vars short) (contains? var-name))
        token
        (let [owners (filter (fn [[n defined]] (and (str/ends-with? n (str "." short))
                                                    (contains? defined var-name)))
                             vars)]
          (when (= 1 (count owners)) (str (ffirst owners) "/" var-name)))))))

(def token-re #"(?<![A-Za-z0-9'#:./*+!?<>=_-]):?[a-z][a-z0-9.*+!?<>=_-]*/[a-zA-Z0-9*+!?<>=_-]*[a-zA-Z0-9*+!?<>=_]")

(defn rewrite
  "`intent` with each resolving bare token backticked; returns [text count]."
  [intent]
  (let [parts (str/split intent #"`" -1)
        n (volatile! 0)
        ;; even parts are outside backticks
        out (map-indexed (fn [i part]
                           (if (odd? i)
                             part
                             (str/replace part token-re
                                          (fn [token]
                                            (if-let [full (full-name token)]
                                              (do (vswap! n inc) (str "`" full "`"))
                                              token)))))
                         parts)]
    [(str/join "`" out) @n]))

(defn rewrite-file
  "Task file `text` with every `:intent` string rewritten: [text count]."
  [text]
  (let [m (re-matcher #":intent\s+\"((?:[^\"\\]|\\.)*)\"" text)
        sb (StringBuilder.)]
    (loop [last-end 0 total 0]
      (if (.find m)
        (let [raw (.group m 1)
              ;; tokens never contain quotes or backslashes, so the escaped text rewrites as is
              [new-raw n] (rewrite raw)]
          (.append sb (subs text last-end (.start m 1)))
          (.append sb new-raw)
          (recur (.end m 1) (+ total n)))
        (do (.append sb (subs text last-end)) [(str sb) total])))))

(let [args *command-line-args*
      apply? (some #{"--apply"} args)
      free (some->> args (drop-while #(not= "--files" %)) second (#(str/split % #",")) set)]
  (doseq [file (sort (.listFiles (io/file "resources/seon/tasks")))
          :let [path (str "resources/seon/tasks/" (.getName file))
                [text n] (rewrite-file (slurp file))]
          :when (pos? n)]
    (.println System/out (str path "\t" n))
    (when (and apply? (free path)) (spit file text))))
