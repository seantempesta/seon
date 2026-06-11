(ns seon.ctx-test
  "V3-C contract tests for `seon.ctx` — the ONE classifier + the ONE
   composer (agent-self-context spec, 2026-06-10).

   The AGREEMENT property: every classification surface (catalog
   grouping/depth, full-source selection, warn provenance, replay
   skip) derives from the one model — so for any ns name the verdicts
   can never disagree. Plus: merge/override-by-name semantics, the
   render guard, the per-agent section budget, the :purpose seed, and
   the mixed-:or slot storage roundtrip.

   All on a FRESH :memory conn seeded like the pod boots — never the
   live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn, `set!` as the ROOT db/*conn* for `body` (conn →
   Promise), prior root restored after (root set!, not `binding` — CLJS
   dynamic bindings pop at the first microtask boundary)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

;; ------------------------------------------------------------
;; Classifier rules on generated names — precedence is the contract.
;; ------------------------------------------------------------

(deftest classifier-name-rules-precedence
  ;; Rule 1 — *.internal is hidden ALWAYS, even under my.* (precedence).
  (doseq [n ["seon.db.internal" "seon.agent.internal"
             "seon.x.internal.y" "my.foo.internal"]]
    (is (true? (ctx/hidden-ns-name? n)) (str n " is hidden")))
  ;; Rule 2 — my.* is the human's world.
  (doseq [n ["my.kb" "my.kb.system" "my.agent.a1" "my.finance"]]
    (is (true? (ctx/my-ns-name? n)) (str n " is my.*"))
    (is (false? (ctx/hidden-ns-name? n)) (str n " is not hidden")))
  ;; Rule 4 — relevant = the full-source root set + children + test
  ;; siblings; plumbing is not.
  (doseq [n ["seon.agent.search" "seon.agent.search-test"
             "seon.agent.todo" "my.kb" "my.kb.system"]]
    (is (true? (ctx/relevant-ns? n)) (str n " renders full source")))
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.db"
             "seon.agent.searcher" "my.finance"]]
    (is (false? (ctx/relevant-ns? n)) (str n " is NOT full-source"))))

;; ------------------------------------------------------------
;; The agreement property on a live (test) index: agent-authored vs
;; substrate-seed provenance, replay-skip disjointness, hidden nses
;; absent from every rendered surface.
;; ------------------------------------------------------------

(defn- transact-ns-row!
  [nm]
  (db/transact!
    {:seon.db/tx-data [{:seon.ns/name   (keyword nm)
                        :seon.ns/source (str "(ns " nm ")")}]}))

(deftest agreement-provenance-replay-and-hidden
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/with-tx-context {:seon.db/origin :substrate-seed
                                     :seon.db/agent-id "boot"}
                  ;; seed txs carry BOTH agent-id and seed origin — the
                  ;; booting agent's with-agent scope (live evidence,
                  ;; context-v3 unit 4). MUST classify substrate.
                  (fn [] (transact-ns-row! "seon.client")))
                (.then (fn [_]
                         (db/with-tx-context {:seon.db/agent-id "a1"}
                           (fn [] (transact-ns-row! "my.agent.a1")))))
                (.then (fn [_]
                         (db/with-tx-context {:seon.db/origin :substrate-seed
                                              :seon.db/agent-id "boot"}
                           (fn [] (transact-ns-row! "seon.db.internal")))))
                (.then
                  (fn [_]
                    (let [dbv   @db/*conn*
                          model (ctx/context-model {:seon.db/db dbv})
                          {:seon.ctx/keys [agent-nses hidden-nses
                                           relevant-nses]} model]
                      (is (contains? agent-nses "my.agent.a1")
                          "agent-tx ns row → agent-authored")
                      (is (not (contains? agent-nses "seon.client"))
                          "seed-origin tx (even agent-stamped) → substrate")
                      (is (some #{"seon.db.internal"} hidden-nses)
                          "*.internal is in the hidden leg")
                      (is (not-any? #{"seon.db.internal"} relevant-nses)
                          "*.internal never relevant")
                      ;; replay-skip disjointness: classifier verdict
                      ;; agent-authored ⇒ ns ∉ substrate-ns-set (the
                      ;; replay discriminator answers a different
                      ;; question but must never overlap).
                      (let [replay-set (client/substrate-ns-set)]
                        (doseq [n agent-nses]
                          (is (not (contains? replay-set (keyword n)))
                              (str n " is agent-authored — never "
                                   "replay-skipped as substrate"))))
                      (db/transact!
                        {:seon.db/tx-data
                         [{:seon.fn/sym     "seon.db.internal/secret"
                           :seon.fn/ns      [:seon.ns/name :seon.db.internal]
                           :seon.fn/source  "(defn secret [] 1)"
                           :seon.fn/fn-var? true
                           :seon.fn/created-at (js/Date.)}
                          {:seon.fn/sym     "my.agent.a1/helper"
                           :seon.fn/ns      [:seon.ns/name :my.agent.a1]
                           :seon.fn/source  "(defn helper [] 1)"
                           :seon.fn/fn-var? true
                           :seon.fn/created-at (js/Date.)}]}))))
                (.then
                  (fn [_]
                    (let [dbv @db/*conn*
                          txt (ctx/functions-catalog-section
                                {:seon.db/db dbv :seon.agent/id "a1"})]
                      (is (not (str/includes? txt "seon.db.internal"))
                          "*.internal never appears in a rendered section")
                      (is (str/includes? txt "my.agent.a1/helper")
                          "agent-authored fns render as callable lines")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; Composer: seeds, merge/override, upsert-by-name, verbs.
;; ------------------------------------------------------------

(defn- assemble
  [id]
  (ctx/assemble-context {:seon.db/db @db/*conn* :seon.agent/id id}))

(defn- section-text
  [id nm]
  (some #(when (= nm (:seon.ctx/name %)) (:seon.render/text %))
        (:seon.render/section-texts (assemble id))))

(deftest purpose-seed-and-merge-and-verbs
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00p1"
                                :seon.agent/purpose "watch the ledger"})
                (.then
                  (fn [_]
                    (let [{:seon.render/keys [sections]} (assemble "AGTctxtest00p1")]
                      (is (some #{:purpose} sections)
                          "minted agent renders its :purpose section")
                      (is (= "Your human created you for: watch the ledger"
                             (section-text "AGTctxtest00p1" :purpose))
                          "stated purpose renders verbatim")
                      (is (some #{:your-sections} sections)
                          "the fn-shaped copyable seeds beside it")
                      (is (some #{:system} sections)
                          "substrate defaults still merged in")
                      (is (some #{:prompt} sections))
                      (is (< (.indexOf (clj->js (mapv str sections)) ":system")
                             (.indexOf (clj->js (mapv str sections)) ":purpose"))
                          "purpose (12) renders after :system (10)"))))
                ;; set-purpose! (explicit-id path goes through
                ;; add-section!'s ALS default — wrap in with-agent).
                (.then (fn [_]
                         (db/with-agent "AGTctxtest00p1"
                           (fn []
                             (agent/set-purpose!
                               {:seon.render/ai "guard the books"})))))
                ;; create! again = resume — must NOT re-seed/overwrite.
                (.then (fn [_] (agent/create! {:seon.agent/id "AGTctxtest00p1"})))
                (.then
                  (fn [_]
                    (is (= "guard the books" (section-text "AGTctxtest00p1" :purpose))
                        "resume (re-create!) keeps the agent's own purpose")))
                ;; add-section! upsert-by-name + envelopes.
                (.then (fn [_]
                         (agent/add-section!
                           {:seon.ctx/name :doctrine
                            :seon.ctx/priority 15
                            :seon.render/ai "Always check twice."
                            :seon.agent/id "AGTctxtest00p1"})))
                (.then (fn [res]
                         (is (= {:seon.agent/ok? true :seon.ctx/name :doctrine}
                                res)
                             "add-section! success envelope")
                         (agent/add-section!
                           {:seon.ctx/name :doctrine
                            :seon.ctx/priority 16
                            :seon.render/ai "Always check three times."
                            :seon.agent/id "AGTctxtest00p1"})))
                (.then
                  (fn [_]
                    (let [secs (ctx/ctx-entities {:seon.agent/id "AGTctxtest00p1"})
                          doctrines (filter #(= :doctrine (:seon.ctx/name %))
                                            secs)]
                      (is (= 1 (count doctrines))
                          "re-adding a name replaces — upsert-by-name")
                      (is (= "Always check three times."
                             (:seon.render/ai (first doctrines)))
                          "slot stored + decoded as the verbatim string"))))
                (.then (fn [_]
                         (agent/add-section!
                           {:seon.ctx/name :blank :seon.render/ai "  "
                            :seon.agent/id "AGTctxtest00p1"})))
                (.then (fn [res]
                         (is (false? (:seon.agent/ok? res))
                             "blank text refused")
                         (is (str/includes? (:seon.agent/error res) "blank"))))
                (.then (fn [_]
                         (agent/remove-section!
                           {:seon.ctx/name :nope :seon.agent/id "AGTctxtest00p1"})))
                (.then (fn [res]
                         (is (false? (:seon.agent/ok? res))
                             "unknown remove → error envelope")
                         (is (str/includes? (:seon.agent/error res) ":doctrine")
                             "unknown remove names the current sections")
                         (agent/remove-section!
                           {:seon.ctx/name :doctrine :seon.agent/id "AGTctxtest00p1"})))
                (.then (fn [res]
                         (is (= {:seon.agent/ok? true
                                 :seon.ctx/name :doctrine} res))
                         (is (nil? (section-text "AGTctxtest00p1" :doctrine))
                             "removed section vanishes from the render"))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest render-guard-and-budget
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00g1"})
                (.then (fn [_]
                         (agent/add-section!
                           {:seon.ctx/name :broken
                            :seon.ctx/priority 14
                            :seon.render/ai 'my.nowhere/missing-fn
                            :seon.agent/id "AGTctxtest00g1"})))
                (.then
                  (fn [_]
                    (let [{:seon.render/keys [text sections]} (assemble "AGTctxtest00g1")]
                      (is (str/includes? text "[broken] render failed:")
                          "broken symbol → inline error line")
                      (is (some #{:prompt} sections)
                          "assembly continues past the broken section"))))
                ;; budget: one huge agent section truncates loudly.
                (.then (fn [_]
                         (agent/add-section!
                           {:seon.ctx/name :huge
                            :seon.ctx/priority 47
                            :seon.render/ai (apply str (repeat 9000 "x"))
                            :seon.agent/id "AGTctxtest00g1"})))
                (.then
                  (fn [_]
                    (let [huge (section-text "AGTctxtest00g1" :huge)]
                      (is (some? huge))
                      (is (str/includes? (str huge) "TRUNCATED")
                          "over-budget agent section carries the loud marker")
                      (is (< (count (str huge))
                             (+ ctx/agent-section-char-budget 400))
                          "rendered size bounded by the budget")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest slot-storage-roundtrip
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00s1"})
                (.then (fn [_]
                         (agent/add-section!
                           {:seon.ctx/name :tile
                            :seon.ctx/priority 30
                            :seon.render/ai 'my.x/view-section
                            :seon.render/html [:div "static badge"]
                            :seon.agent/id "AGTctxtest00s1"})))
                (.then
                  (fn [_]
                    (let [secs (ctx/ctx-entities {:seon.agent/id "AGTctxtest00s1"})
                          tile (some #(when (= :tile (:seon.ctx/name %)) %)
                                     secs)
                          raw  (db/pull
                                 {:seon.db/pull-pattern
                                  '[{:seon.agent/ctx [*]}]
                                  :seon.db/ref [:seon.agent/id "AGTctxtest00s1"]})
                          raw-tile (some #(when (= :tile (:seon.ctx/name %)) %)
                                         (:seon.agent/ctx raw))]
                      (is (= 'my.x/view-section (:seon.render/ai tile))
                          "symbol slot decodes back to a symbol")
                      (is (= [:div "static badge"] (:seon.render/html tile))
                          "hiccup literal roundtrips through the bridge")
                      (is (string? (:seon.render/ai raw-tile))
                          "storage representation is the EDN string")
                      (is (= "my.x/view-section" (:seon.render/ai raw-tile))
                          "…the pr-str of the symbol")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))
