(ns seon.code.errors
  "Extracted child namespace of `seon.code.parse`."
  (:require [clojure.string :as str]
            [jolt.analyzer]
            [jolt.host]))

(defn- failing-user-helper-name [err-str helper-index]
  (let [e (or err-str "")
        line1 (or (first (str/split-lines e)) "")
        sym (or (second (re-find #"passed to:\s+([a-zA-Z0-9_.\-\!\?\*\$]+(?:/[a-zA-Z0-9_.\-\!\?\*\$]+)?)" e))
                (second (re-find #"Schema contract violation[^:\n]*?([a-zA-Z0-9_.\-]+/[a-zA-Z0-9_.\-\!\?\*]+)" e))
                (second (re-find #"\b(user/[a-zA-Z0-9\-\!\?\*]+)\b" line1)))]
    (when sym
      (cond
        (str/starts-with? sym "user/")
        (let [h (subs sym 5)] (when (contains? helper-index h) h))

        (str/starts-with? sym "user$")
        (let [raw (second (str/split sym #"\$" 2))
              h (-> (str raw) (str/replace "_BANG_" "!") (str/replace "_QMARK_" "?")
                    (str/replace "_STAR_" "*") (str/replace "_" "-"))]
          (when (contains? helper-index h) h))

        (str/includes? sym "/")
        (let [[ns-s fn-s] (str/split sym #"/" 2)]
          (when (and (contains? #{"seon.fork.helpers" "seon.fork.calls" "seon.repl" "seon.code.edit"
                                  "seon.render.print" "seon.task.gate" "seon.ctx"} ns-s)
                     (contains? helper-index fn-s))
            fn-s))

        :else nil))))

(def ^{:private true} static-error-hints
 "Declarative `[match-substrs hint]` recovery rules appended by `enrich-eval-error`."
 [[["is not public"]
   "Hint: `(user/source 'ns/var)` and `(user/edit-fn! 'ns/var ...)` work directly on private vars; or deref `#'ns/var`."]
  [["No such namespace: io"]
   "Hint: Require `[clojure.java.io :as io]` (never edit source files on disk — use `user/edit-fn!` or `user/add-fn!`)."]
  [["Unable to resolve symbol: deftest" "Unable to resolve symbol: is"]
   "Hint: Persist tests with `(user/add-fn! 'ns-test '(deftest my-test (is (= ...))))` instead of evaluating bare `(deftest ...)` in `user`."]
  [["invalid-schema" "child-error"]
   "Hint: In Malli use predicate symbols like `number?`, `pos-int?`, `nat-int?` (NEVER `:number`/`:pos-int`), or built-ins `:int`, `:double`, `:string`, `:boolean`, `:keyword`, `:map`, `:vector`, `:any`."]
  [["Unsupported escape character"]
   "Hint: Inside Clojure \"...\" strings, double backslashes (e.g. `\\\\[`, `\\\\d`, `\\\\-`, `\\\\n`) or write a Python/shell script via `(user/fs-write \"/tmp/run.py\" ...)` instead of escaping regexes in bash one-liners."]
  [["Store :id must be a UUID type"]
   "Hint: Datahike `:memory` stores require `:id (java.util.UUID/randomUUID)` — or prefer `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)` for Seon schema tests."]
  [["No such var: seon.ctx/"]
   "Hint: Do not reach into `seon.ctx/*` internals; use `(user/db)` for your branch db, `(user/main-db)` for `:main`, and `@ctx` for your live context snapshot."]
  [["validate-against" "No such var: schema/validate"]
   "Hint: Validate values with `(malli.core/validate sch val {:registry (:registry (seon.schema/registry-for-db (user/db)))})`."]
  [["Unable to resolve symbol: pprint"]
   "Hint: Require `[clojure.pprint :refer [pprint]]` or use `prn`."]
  [["Long cannot be cast to class clojure.lang.Named"
    "cannot be compared to :"]
   "Hint: `(:schema db)` contains both keyword attributes AND integer reverse idents — use `(user/schema \"term\")` or `(filter keyword? (keys (:schema db)))`."]
  [["Lookup ref attribute should be marked as :db/unique"]
   "Hint: Pass the bare integer entity ID `eid` directly to `(d/pull db '[*] eid)` — `:db/id` is not a `:db/unique` lookup-ref attribute."]
  [["the namespace name must be a symbol, got: (quote"]
   "Hint: `(ns my.ns ...)` takes an unquoted symbol (unlike `(in-ns 'my.ns)`)."]])

(defn- fuzzy-user-helper-hint [e helper-index]
  (let [bad (second (re-find #"user/([a-zA-Z0-9\-\!\?\*]+)" e))
        toks (when bad (remove str/blank? (str/split (str/lower-case bad) #"[\-\!\?\*]+")))
        scored (when (seq toks)
                 (->> (keys helper-index)
                      (map (fn [k] [(reduce + 0 (map #(if (str/includes? (str/lower-case k) %) 1 0) toks)) k]))
                      (filter #(pos? (first %)))
                      (sort-by (fn [[sc k]] [(- sc) (count k) k]))
                      (take 5)
                      (mapv #(str "user/" (second %)))))]
    (when (seq scored)
      (str "Did you mean: " (str/join ", " scored) " (or call `(user/help)`)."))))

(defn enrich-eval-error
  "Appends actionable, class-eliminating recovery hints to REPL evaluation errors:
  frame-scoped usage signatures for miscalled `user/*` helpers, fuzzy matches for misspelled `user/*`
  vars, 2-arg `(d/datoms db :aevt)` hints, batching hints on allocation-budget overruns, and Datahike/Malli tips."
  {:malli/schema [:=> {:examples [[["(user/callers 'a 'b)" "Wrong number of args (2) passed to: user/callers" {"callers" {:arglists "([sym & more])" :doc "Find callers."}}]
                                   "Wrong number of args (2) passed to: user/callers\nUsage: user/callers ([sym & more]) — Find callers."]]}
                  [:cat :string :string [:map-of :string :map]] :string]
   :cost/ticks 500000
   :cost/bytes 1048576}
  [code-str err-str helper-index]
  (let [c (or code-str "") e (or err-str "") hints (volatile! [])]
    (when (str/includes? e "allocation budget")
      (when (and (re-find #"\(user/grep-src(?:\s|$)" c) (re-find #"\((?:doseq|map|mapv|for)\b" c))
        (vswap! hints conj "Hint: Pass a vector of patterns in one cached pass: `(user/grep-src [\"p1\" \"p2\"] {:ns 'ns})`."))
      (when (> (count (re-seq #"\(user/edit-fn!(?:\s|$)" c)) 1)
        (vswap! hints conj "Hint: Batch edits to the same fn in 1 parse+compile: `(user/edit-fn! 'ns/fn [[old1 new1] [old2 new2]])`."))
      (when (str/includes? c "clojure.test/run-tests")
        (vswap! hints conj "Hint: Run tests with `(user/run-tests! 'ns-test)` (4 GB budget override) instead of `clojure.test/run-tests`.")))
    (when (and (re-find #"\((?:d|datahike\.api)/datoms\b[^()]*?(?:\([^()]*\))?\s+:(?:aevt|avet)\s*\)" c)
               (or (str/includes? e "allocation budget") (str/includes? e "tick budget")
                   (str/includes? e "Wrong number of args") (str/includes? e "datoms")))
      (vswap! hints conj "Hint: Pass the attribute as 3rd arg: (d/datoms db :aevt :attr/name)"))
    (when (or (str/includes? e "Wrong number of args") (str/includes? e "Schema contract violation")
              (str/includes? e "cannot be cast") (str/includes? e "find not supported"))
      (when-let [h (failing-user-helper-name e helper-index)]
        (when-let [info (get helper-index h)]
          (let [doc-line (some-> (:doc info) str str/split-lines first str/trim)]
            (vswap! hints conj (str "Usage: user/" h " " (or (:arglists info) "")
                                    (when (not (str/blank? doc-line)) (str " — " doc-line))))))))
    (when (or (str/includes? e "No such var: user/") (str/includes? e "Unable to resolve var: user/"))
      (when-let [fh (fuzzy-user-helper-hint e helper-index)] (vswap! hints conj fh)))
    (when (and (str/includes? e "PersistentArrayMap cannot be cast to")
               (or (str/includes? c "user/fs-read") (str/includes? c "user/sh!")))
      (vswap! hints conj "Hint: `(user/fs-read path)` returns `{:ok? true :lines N :content \"...\"}` (extract `(:content ...)`), and `(user/sh! cmd)` returns `{:exit N :out \"...\" :err \"...\"}` (extract `(:out ...)`)."))
    (when (and (str/includes? e "datahike.db.DB")
               (or (str/includes? e "cannot be cast to class java.util.concurrent.Future") (str/includes? e "IDeref")))
      (vswap! hints conj "Hint: `(user/db)` and `(user/main-db)` ALREADY return immutable Datahike DB values — do NOT deref with `@`. Use `(user/db)` directly (or `@(user/conn)` for the connection)."))
    (when (and (str/includes? e "Cannot find entity") (re-find #"\((?:d|datahike\.api)/pull\b" c))
      (vswap! hints conj "Hint: `d/pull` requires a lookup ref vector `[:attr val]` or integer EID, not a bare keyword."))
    (doseq [[needles hint] static-error-hints]
      (when (some #(str/includes? e %) needles) (vswap! hints conj hint)))
    (if (seq @hints) (str e "\n" (str/join "\n" (distinct @hints))) e)))
