(ns seon.index-substrate-test
  "Guard tests for `seon.client/index-substrate!` — the runtime-introspection
   substrate indexer (Step 2 of coherent-bootstrap-indexing-2026-06-08).

   index-substrate! replaced the old curated `core-fn-curated` /
   `synthesize-fn-source` / `seed-core-fns!` table. It builds `:seon.fn` rows
   from REAL runtime introspection: spec + doc from var meta, and source +
   real arglists from a file-read at the var's `:file`/`:line` (paren-balance,
   the cljs.repl/source-fn mechanism). These tests pin the invariants that the
   old curated path got WRONG:

     - source is REAL `(defn …)` text, never a `,,,` stub;
     - arglists come from that real source, not the mangled var meta;
     - `:seon.fn/spec` is the exact `m/form` string when the fn is specced,
       and ABSENT when it is not (honestly unspecced).

   File-read needs Node `fs` + cwd = repo root; the `:node-test` build runs
   under Node with cwd = repo root (bin/test-cljs cds there), so this is a
   straight unit test — no live pod.

   Run interactively via MCP eval:
     (require 'seon.index-substrate-test :reload)
     (cljs.test/run-tests 'seon.index-substrate-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is]]
    [seon.client :as client]))

(defn- by-sym [tx sym]
  (first (filter #(= sym (:seon.fn/sym %)) tx)))

(deftest transact!-indexes-with-real-source-spec-arglists
  ;; The single most important criterion: transact! carries the real spec
  ;; form AND real source/arglists — not the old curated `([arg])`-mangled,
  ;; `,,,`-stubbed, `specced? false` lie.
  (let [tx (client/index-substrate!)
        t  (by-sym tx "seon.db/transact!")]
    (is (some? t) "transact! is present in the indexed tx-data")
    (is (str/starts-with? (:seon.fn/source t) "(defn ^:async transact!")
        "source is the REAL (defn …) text read from the file")
    (is (not (str/includes? (:seon.fn/source t) ",,,"))
        "NO `,,,` placeholder stub in the source")
    (is (= "[:=> [:cat :seon.db/transact-request] :seon.db/transact-response]"
           (:seon.fn/spec t))
        "spec is the exact m/form string of the fn's :malli/schema")
    (is (str/includes? (:seon.fn/arglists t) "arg")
        "arglists parsed from real source (transact! destructures in body)")))

(deftest specced-vs-unspecced-matches-reality
  (let [tx       (client/index-substrate!)
        query    (by-sym tx "seon.db/query")
        register (by-sym tx "seon.schema/register!")
        cai      (by-sym tx "seon.db/current-agent-id")]
    ;; query IS specced → spec present, exact form.
    (is (some? (:seon.fn/spec query))
        "query is specced → :seon.fn/spec present")
    ;; register! and current-agent-id have NO :malli/schema → no spec key.
    (is (not (contains? register :seon.fn/spec))
        "register! is honestly unspecced → :seon.fn/spec ABSENT")
    (is (not (contains? cai :seon.fn/spec))
        "current-agent-id is honestly unspecced → :seon.fn/spec ABSENT")
    ;; but both still get real source (file-read, not stub).
    (is (str/starts-with? (:seon.fn/source register) "(defn register!")
        "register! still gets REAL source despite being unspecced")))

(deftest real-arglists-not-mangled
  ;; query/pull/entity have real map-destructure arglists in their source;
  ;; the parser must recover them (NOT the instrumentation-mangled `([arg])`).
  (let [tx    (client/index-substrate!)
        query (by-sym tx "seon.db/query")]
    (is (str/includes? (:seon.fn/arglists query) "::keys")
        "query's real map-in arglists are recovered from source")
    (is (not= "([arg])" (:seon.fn/arglists query))
        "query arglists are the real destructure, not the mangled var-meta")))

(deftest no-stub-source-anywhere
  ;; Permissive + honest: every indexed fn has REAL source (or is OMITTED),
  ;; never a `,,,` stub.
  (let [tx (client/index-substrate!)]
    (is (every? #(str/starts-with? (:seon.fn/source %) "(defn")
                (filter :seon.fn/sym tx))
        "every indexed :seon.fn row has real (defn …) source")
    (is (not-any? #(and (:seon.fn/source %)
                        (str/includes? (:seon.fn/source %) ",,,"))
                  tx)
        "no row carries a `,,,` stub")))

(deftest emits-ns-rows-for-owning-nses
  ;; Each owning ns gets a :seon.ns row so the [:seon.ns/name kw] lookup-ref
  ;; on :seon.fn/ns resolves. ns source is the minimal `(ns x)` stub today
  ;; (replay-safe — see index-substrate! docstring).
  (let [tx      (client/index-substrate!)
        ns-rows (filter :seon.ns/name tx)
        names   (set (map :seon.ns/name ns-rows))]
    (is (contains? names :seon.db) ":seon.db ns row emitted")
    (is (contains? names :seon.schema) ":seon.schema ns row emitted")
    (is (contains? names :seon.test.runner) ":seon.test.runner ns row emitted")
    (is (= "(ns seon.db)"
           (:seon.ns/source (first (filter #(= :seon.db (:seon.ns/name %)) ns-rows))))
        "ns source is the minimal (ns x) stub (replay-safe)")))
