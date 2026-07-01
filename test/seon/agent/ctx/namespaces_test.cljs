(ns seon.agent.ctx.namespaces-test
  "Unit tests for the COMPACT-CARD renderer
   (`seon.agent.ctx.namespaces/render-one-ns-compact`) — the sibling
   detail-level to the full-source block. Pins the CARD SHAPE against a
   couple of small hand-built ns fixtures: the `register!` block is
   reconstructed verbatim (ns-local keywords abbreviated to `::`), each
   PUBLIC fn is a one-line `defn` head with the body elided (`…`), private
   fns are skipped, and the edge cases (no docstring, no `:malli/schema`,
   multi-arity, no owned schemas, ns not indexed) degrade cleanly.

   Reads INDEXED ROWS ONLY — the fixtures seed `:seon.ns` / `:seon.fn` /
   `:seon.schema` rows into a scratch conn; there is no file read.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.agent.ctx.namespaces-test :reload)
     (cljs.test/run-tests 'seon.agent.ctx.namespaces-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.ctx.namespaces :as nss]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]))

;; Live-register the fixture schemas so the card renders their form from
;; the registry (`schema/schema-definition`) — the real card path.
(schema/register! :ktest.demo/id           :int)
(schema/register! :ktest.demo/thing-request [:map [:ktest.demo/id :ktest.demo/id]])

;; A namespace with two owned schemas and a spread of fn shapes: a
;; specced+documented fn, a fn with no docstring, a fn with no
;; `:malli/schema`, a multi-arity fn, and a PRIVATE fn (must be skipped).
(defn- seed-tx []
  [{:seon.ns/name :ktest.demo :seon.ns/source "(ns ktest.demo)"}
   ;; The `:seon.schema` index rows the card reads for the register! block
   ;; (the form itself comes LIVE from the registry above).
   {:seon.schema/key :ktest.demo/id
    :seon.schema/ns  [:seon.ns/name :ktest.demo]
    :seon.schema/source "[:int]"}
   {:seon.schema/key :ktest.demo/thing-request
    :seon.schema/ns  [:seon.ns/name :ktest.demo]
    :seon.schema/source "[:map [:ktest.demo/id :ktest.demo/id]]"}
   {:seon.fn/sym      "ktest.demo/store!"
    :seon.fn/ns       [:seon.ns/name :ktest.demo]
    :seon.fn/source   "(defn store! [{:ktest.demo/keys [id]}] id)"
    :seon.fn/fn-var?  true
    :seon.fn/private? false
    :seon.fn/doc      "Store one thing by id.\n\n   The mechanism lives here in the body prose."
    :seon.fn/arglists "([{:ktest.demo/keys [id]}])"
    :seon.fn/spec     "[:=> [:cat :ktest.demo/thing-request] :ktest.demo/id]"}
   {:seon.fn/sym      "ktest.demo/bare"
    :seon.fn/ns       [:seon.ns/name :ktest.demo]
    :seon.fn/source   "(defn bare [x] x)"
    :seon.fn/fn-var?  true
    :seon.fn/private? false
    :seon.fn/arglists "([x])"}
   {:seon.fn/sym      "ktest.demo/no-doc"
    :seon.fn/ns       [:seon.ns/name :ktest.demo]
    :seon.fn/source   "(defn no-doc [x] x)"
    :seon.fn/fn-var?  true
    :seon.fn/private? false
    :seon.fn/arglists "([x])"
    :seon.fn/spec     "[:=> [:cat :int] :int]"}
   {:seon.fn/sym      "ktest.demo/multi"
    :seon.fn/ns       [:seon.ns/name :ktest.demo]
    :seon.fn/source   "(defn multi ([a] a) ([a b] b))"
    :seon.fn/fn-var?  true
    :seon.fn/private? false
    :seon.fn/doc      "Two arities."
    :seon.fn/arglists "([a] [a b])"
    :seon.fn/spec     "[:function [:=> [:cat :int] :int] [:=> [:cat :int :int] :int]]"}
   {:seon.fn/sym      "ktest.demo/secret"
    :seon.fn/ns       [:seon.ns/name :ktest.demo]
    :seon.fn/source   "(defn- secret [x] x)"
    :seon.fn/fn-var?  true
    :seon.fn/private? true
    :seon.fn/arglists "([x])"}
   ;; A second ns with a fn but NO owned schemas → no register! block.
   {:seon.ns/name :ktest.noschema :seon.ns/source "(ns ktest.noschema)"}
   {:seon.fn/sym      "ktest.noschema/go"
    :seon.fn/ns       [:seon.ns/name :ktest.noschema]
    :seon.fn/source   "(defn go [] :ok)"
    :seon.fn/fn-var?  true
    :seon.fn/private? false
    :seon.fn/arglists "([])"
    :seon.fn/spec     "[:=> :cat :keyword]"}])

(defn- with-seeded-db [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data (seed-tx)})
                     (.then (fn [_] (body @conn)))))))))

;; ---------------------------------------------------------------------------
;; namespaces-block FULL-vs-COMPACT dispatch (the compact-card wiring).
;;
;; Two seeded nses with a distinctive fn BODY token so full (body shown) is
;; unambiguously distinguishable from compact (body elided `…`):
;;   - :seon.agent.demo-verb  — agent-facing (compact-worthy) → compact card
;;                              by default; `(+ x 100)` only in a FULL render.
;;   - :seon.web.demo-plumbing — deep framework (NOT compact-worthy) → DROPPED
;;                              by default; `(* x 7)` only when promoted full.
;; ---------------------------------------------------------------------------

(defn- seed-dispatch-tx []
  [{:seon.ns/name :seon.agent.demo-verb :seon.ns/source "(ns seon.agent.demo-verb)"}
   {:seon.fn/sym      "seon.agent.demo-verb/do-verb"
    :seon.fn/ns       [:seon.ns/name :seon.agent.demo-verb]
    :seon.fn/source   "(defn do-verb [x] (+ x 100))"
    :seon.fn/fn-var?  true :seon.fn/private? false
    :seon.fn/doc      "Do the verb."
    :seon.fn/arglists "([x])"
    :seon.fn/spec     "[:=> [:cat :int] :int]"}
   {:seon.ns/name :seon.web.demo-plumbing :seon.ns/source "(ns seon.web.demo-plumbing)"}
   {:seon.fn/sym      "seon.web.demo-plumbing/plumb"
    :seon.fn/ns       [:seon.ns/name :seon.web.demo-plumbing]
    :seon.fn/source   "(defn plumb [x] (* x 7))"
    :seon.fn/fn-var?  true :seon.fn/private? false
    :seon.fn/arglists "([x])"
    :seon.fn/spec     "[:=> [:cat :int] :int]"}
   {:seon.agent/id "tst-2606260000"}])

(defn- with-dispatch-db [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data (seed-dispatch-tx)})
                     (.then (fn [_] (body conn)))))))))

(deftest namespaces-block-default-compact-and-drop
  (async done
    (-> (with-dispatch-db
          (fn [conn]
            (let [out (nss/namespaces-block {:seon.db/db @conn :seon.agent/id "tst-2606260000"})]
              (testing "agent-facing (seon.agent.*) verb ns → COMPACT card, body elided"
                (is (str/includes? out ";;; ┌─ namespace seon.agent.demo-verb ─"))
                (is (str/includes? out "(defn do-verb"))
                (is (str/includes? out "…"))
                (is (not (str/includes? out "(+ x 100)"))
                    "compact card must NOT contain the fn body"))
              (testing "deep-framework (seon.web.*) ns → DROPPED, searchable-only"
                (is (not (str/includes? out "demo-plumbing")))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest namespaces-block-full-source-override
  (async done
    (-> (with-dispatch-db
          (fn [conn]
            (binding [db/*conn* conn]
              (-> (db/transact!
                    {:seon.db/tx-data
                     [{:seon.agent/id "tst-2606260000"
                       :seon.agent.ctx.namespaces/full-source
                       [:seon.agent.demo-verb :seon.web.demo-plumbing]}]})
                  (.then (fn [_]
                           (let [out (nss/namespaces-block
                                       {:seon.db/db @conn :seon.agent/id "tst-2606260000"})]
                             (testing "::full-source promotes a compact ns to FULL (body shown)"
                               (is (str/includes? out "(+ x 100)")))
                             (testing "::full-source promotes a dropped framework ns to FULL"
                               (is (str/includes? out "(* x 7)"))))))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest render-one-ns-compact-shape
  (async done
    (-> (with-seeded-db
          (fn [dbv]
            (let [card (nss/render-one-ns-compact
                         {:seon.ns/name :ktest.demo :seon.db/db dbv})]
              (testing "demarcation brackets"
                (is (str/includes? card ";;; ┌─ namespace ktest.demo ─"))
                (is (str/includes? card ";;; └─ end namespace ktest.demo ─")))
              (testing "register! block, ns-local keywords abbreviated to ::"
                (is (str/includes? card "(register! ::id :int)"))
                (is (str/includes? card
                      "(register! ::thing-request [:map [::id ::id]])")))
              (testing "specced+documented fn: line-1 doc only, abbreviated spec+arglist, body elided"
                (is (str/includes? card
                      "(defn store! \"Store one thing by id.\" {:malli/schema [:=> [:cat ::thing-request] ::id]} [{::keys [id]}] …)"))
                (is (not (str/includes? card "mechanism lives here"))))
              (testing "fn with no docstring AND no spec: name directly followed by arglist"
                (is (str/includes? card "(defn bare [x] …)")))
              (testing "fn with a spec but no docstring omits ONLY the string literal"
                (is (str/includes? card "(defn no-doc {:malli/schema [:=> [:cat :int] :int]} [x] …)")))
              (testing "multi-arity renders each arity with an elided body"
                (is (str/includes? card "([a] …) ([a b] …)")))
              (testing "private fn is skipped"
                (is (not (str/includes? card "secret")))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest render-one-ns-compact-no-doc-exact
  (async done
    (-> (with-seeded-db
          (fn [dbv]
            (let [card (nss/render-one-ns-compact
                         {:seon.ns/name :ktest.demo :seon.db/db dbv})]
              ;; `bare` has no doc AND no spec → head is name directly
              ;; followed by the arglist, body elided.
              (is (str/includes? card "(defn bare [x] …)")))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest render-one-ns-compact-no-owned-schemas
  (async done
    (-> (with-seeded-db
          (fn [dbv]
            (let [card (nss/render-one-ns-compact
                         {:seon.ns/name :ktest.noschema :seon.db/db dbv})]
              (testing "no register! block when the ns owns no schemas"
                (is (not (str/includes? card "register!"))))
              (testing "the public fn head still renders"
                (is (str/includes? card "(defn go {:malli/schema [:=> :cat :keyword]} [] …)"))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest render-one-ns-compact-not-indexed
  (async done
    (-> (with-seeded-db
          (fn [dbv]
            (let [card (nss/render-one-ns-compact
                         {:seon.ns/name :ktest.absent :seon.db/db dbv})]
              (testing "a ns with no :seon.ns entity renders a one-line note, never throws"
                (is (str/includes? card ";;; ┌─ namespace ktest.absent ─"))
                (is (str/includes? card "not in db"))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))
