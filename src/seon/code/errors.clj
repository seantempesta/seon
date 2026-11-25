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
  "Hint: Inside Clojure \"...\" strings, double backslashes (e.g. `\\\\[`, `\\\\d`, `\\\\-`, `\\\\n`) — for regex searches pass `#\"a|b\"` or a vector `(user/grep-src [\"a\" \"b\"])` instead of `\"a\\|b\"`."]
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
   "Hint: `(ns my.ns ...)` takes an unquoted symbol (unlike `(in-ns 'my.ns)`)."]
  [["get-else: nil default value is not supported"]
   "Hint: Datahike `get-else` rejects `nil` as the 3rd arg — use a non-nil sentinel like `:none` or `:missing`, or pull optional attrs via `(d/pull db '[...] eid)`."]
  [["Character cannot be cast to class java.util.Map$Entry"]
   "Hint: You passed a String to a map fn (`keys`/`vals`/`merge`) — `(user/source 'ns)` and `(user/source 'ns/var)` return source strings directly (use `(user/ns-outline 'ns)` for a map/vector of vars)."]
  [["Can't take value of a macro:"]
   "Hint: A bare symbol resolved to a Clojure macro (e.g. `ns`, `defn`, `let`) — quote symbol literals in `:examples` (e.g. `'ns`) or wrap the `:examples` vector in a single outer quote `'[[...]]`."]
  [["Unable to resolve symbol: thrown?"]
   "Hint: `thrown?` is valid ONLY inside `(clojure.test/is (thrown? ExClass expr))`, or use `(try ... (catch Throwable e ...))`."]
  [["contains? not supported on type: malli.registry" "create ISeq from: malli.registry" "malli.registry$"]
   "Hint: Malli registries are not Clojure maps/seqs/fns — check schemas via `(malli.core/schema key {:registry reg})` or `(contains? (:forms (seon.schema/entity-index db)) key)`."]
  [["No matching clause: :ave"]
   "Hint: Datahike index keywords are `:eavt`, `:aevt`, `:avet`, and `:vaet` (4 letters — `:avet`, not `:ave`)."]
  [["Lookup ref should contain 2 elements"]
   "Hint: Pass a bare integer `eid` directly to `(d/pull db '[...] eid)`, or a 2-element `[attr val]` lookup ref vector (never `[eid]`)."]
  [["Unable to resolve symbol: testing in this context"]
   "Hint: Add `[clojure.test :refer [deftest is testing]]` via `(user/add-require! 'ns-test '[clojure.test :refer [deftest is testing]])` or qualify `clojure.test/testing`."]
  [["Unable to resolve symbol: doc in this context"]
   "Hint: Use `(user/help)` or `(user/help 'fn-name)` (or `(clojure.repl/doc ...)`) to inspect helper docs and arglists."]
  [["not valid start/end indices" "StringIndexOutOfBoundsException" "IndexOutOfBoundsException"]
   "Hint: `subs`/`subvec`/`nth` bounds exceeded the actual length — clamp with `(subs s 0 (min n (count s)))` or use `take`/`get`."]
  [["No such var: m/valid?" "No such var: malli.core/valid?"]
   "Hint: Malli's validation fn is `malli.core/validate` (not `valid?`): `(m/validate schema val {:registry reg})`."]
  [["Wrong number of args (3) passed to: datahike.impl.entity/entity"]
   "Hint: `d/entity` takes 2 args `(d/entity db eid-or-lookup-ref)` — wrap lookup refs in a vector: `(d/entity db [:attr val])`."]
  [["Wrong number of args (2) passed to: malli.core/schema?"]
   "Hint: `malli.core/schema?` takes 1 arg (tests a compiled Schema object); to compile/check a schema form with a registry use `(malli.core/schema form {:registry reg})`."]
  [["Expected number or lookup ref for entity id, got #datahike/Datom"]
   "Hint: Pass `(:e dt)` (the integer entity ID), not the Datom record itself, to `(d/pull db '[...] (:e dt))`."]
  [["Alias d already exists in namespace repl" "Alias str already exists in namespace repl"]
   "Hint: `d` (`datahike.api`), `str` (`clojure.string`), `fs` (`jolt.fs`), and `proc` (`jolt.process`) are pre-aliased in `user`/`repl` — pick a distinct alias (e.g. `:as diag`)."]
  [["No such namespace: seon.db" "Could not locate seon/db"]
  "Hint: There is no `seon.db` namespace — query Datahike with `(d/q ... (user/db))` or `(d/datoms (user/db) :avet :attr val)` (`datahike.api` is pre-aliased as `d`)."]
  [["Var cannot be cast to class clojure.lang.Named"]
   "Hint: Pass a quoted symbol `'ns/var` (not a Var `#'ns/var`) to helpers expecting a symbol."]
  [["user$conn cannot be cast to class java.util.concurrent.Future"]
   "Hint: `user/conn` is a 0-arg function — use `(user/db)` directly (or `@(user/conn)`), not `@user/conn`."]
  [["datahike.db.DB cannot be cast to class clojure.lang.IFn"]
   "Hint: A Datahike DB is not a function — pass it to `(d/pull db '[...] ref)`, `(d/datoms db :avet :attr val)`, or `(d/entity db ref)`."]
  [["Expected number or lookup ref for entity id, got \""]
   "Hint: Wrap string keys in a 2-element lookup ref vector for `d/pull`, e.g. `[:seon.code.form/key \"ns/fn\"]` or `[:seon.code.doc/id \"docs/...\"]`."]
  [["String cannot be cast to class clojure.lang.Named"]
   "Hint: `name`/`namespace` only accept keywords or symbols, not strings — use `(str x)` or `(filter keyword? ...)`."]
  [["Unable to resolve symbol: display in this context"]
   "Hint: Use Clojure's `println` / `prn` (not Scheme's `display`)."]
  [["emit-quoted: unsupported quoted form #uuid"]
   "Hint: Avoid `#uuid \"...\"` inside quoted forms like `'[[...]]` — use `(java.util.UUID/fromString \"...\")` in unquoted `:examples` or write a `deftest`."]
  [["No such var: result/e"]
   "Hint: `result/e<n>` handles live in-memory for this worker and match the exact handle printed in prior eval results (never guess a handle number)."]])

(defn- fuzzy-user-helper-hint [e helper-index]
  (let [bad (second (re-find #"user/([a-zA-Z0-9\-\!\?\*]+)" e))]
    (if (and bad (contains? helper-index bad))
      (str "Hint: `user/" bad "` exists, so the current namespace likely shadows `user` via `:as user` — switch back with `(in-ns 'user)` (or `(in-ns 'repl)`).")
      (let [toks (when bad (remove str/blank? (str/split (str/lower-case bad) #"[\-\!\?\*]+")))
            scored (when (seq toks)
                     (->> (keys helper-index)
                          (map (fn [k] [(reduce + 0 (map #(if (str/includes? (str/lower-case k) %) 1 0) toks)) k]))
                          (filter #(pos? (first %)))
                          (sort-by (fn [[sc k]] [(- sc) (count k) k]))
                          (take 5)
                          (mapv #(str "user/" (second %)))))]
        (when (seq scored)
          (str "Did you mean: " (str/join ", " scored) " (or call `(user/help)`)."))))))

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
      (when (and (re-find #"(?:run-tests|run-test-var|test-var)\b" c) (not (str/includes? c "user/run-tests!")))
        (vswap! hints conj "Hint: Run tests with `(user/run-tests! 'ns-test)` or `(user/run-tests! 'ns-test/test-var)` (12 GB budget override) instead of `clojure.test/run-tests`/`run-test-var`."))
      (when (re-find #"\((?:d|datahike\.api)/q\b" c)
        (vswap! hints conj "Hint: Full-DB `d/q` joins can exceed the 256 MB eval cap — use indexed `(d/datoms db :avet/:aevt :attr val)` + `(d/pull db '[...] (:e dt))` or `(user/search ...)` / `(user/grep-src ...)`."))
      (when (> (count (re-seq #"\(user/run-tests!(?:\s|$)" c)) 1)
        (vswap! hints conj "Hint: Pass multiple targets to ONE `(user/run-tests! 'ns1-test 'ns2-test)` call (or run each in a separate eval) instead of calling `user/run-tests!` multiple times in one eval.")))
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
    (do (when (or (str/includes? e "No such var: user/") (str/includes? e "Unable to resolve var: user/"))
      (when-let [fh (fuzzy-user-helper-hint e helper-index)] (vswap! hints conj fh)))
    (when-let [bare (second (re-find #"Unable to resolve symbol: ([a-zA-Z0-9_!?*-]+) in this context" e))]
      (when-let [info (get helper-index bare)]
        (let [doc-line (some-> (:doc info) str str/split-lines first str/trim)]
          (vswap! hints conj (str "Hint: Qualify REPL helpers with `user/` — `user/" bare "` " (or (:arglists info) "")
                                  (when (not (str/blank? doc-line)) (str " — " doc-line))))))))
    (when (and (str/includes? e "PersistentArrayMap cannot be cast to")
               (or (str/includes? c "user/fs-read") (str/includes? c "user/sh!")))
      (vswap! hints conj "Hint: `(user/fs-read path)` returns `{:ok? true :lines N :content \"...\"}` (extract `(:content ...)`), and `(user/sh! cmd)` returns `{:exit N :out \"...\" :err \"...\"}` (extract `(:out ...)`)."))
    (when (and (str/includes? e "datahike.db.DB")
               (or (str/includes? e "cannot be cast to class java.util.concurrent.Future") (str/includes? e "IDeref")))
      (vswap! hints conj "Hint: `(user/db)` and `(user/main-db)` ALREADY return immutable Datahike DB values — do NOT deref with `@`. Use `(user/db)` directly (or `@(user/conn)` for the connection)."))
    (when (and (str/includes? e "Cannot find entity") (re-find #"\((?:d|datahike\.api)/pull\b" c))
      (vswap! hints conj "Hint: `d/pull` requires a lookup ref vector `[:attr val]` or integer EID, not a bare keyword."))
    (when (and (str/includes? e "cannot be cast to class clojure.lang.Named") (re-find #"class [a-zA-Z0-9_.]+\$" e))
      (vswap! hints conj "Hint: Quote the symbol argument (e.g. `'seon.ns/fn` or `'user/edit-fn!`), not the unquoted function value."))
    (when (re-find #"No such namespace: u(?:\s|$)" e)
      (vswap! hints conj "Hint: REPL helpers live in `user/` (e.g. `(user/source ...)`, `(user/grep-src ...)`), not `u/`."))
    (when (re-find #"No such namespace: d(?:\s|$)" e)
      (vswap! hints conj "Hint: Alias `d` (`datahike.api`) is pre-aliased in `user`/`repl` only — inside another `(in-ns '...)` qualify `datahike.api/datoms` or require `[datahike.api :as d]`."))
    (doseq [[needles hint] static-error-hints]
      (when (some #(str/includes? e %) needles) (vswap! hints conj hint)))
    (if (seq @hints) (str e "\n" (str/join "\n" (distinct @hints))) e)))
