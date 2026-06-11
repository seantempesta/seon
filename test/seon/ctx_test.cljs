(ns seon.ctx-test
  "Contract tests for `seon.ctx` — the v4 composer
   (context-v4-repl-realism 2026-06-11).

   Pins: the ONE namespace-selection rule (included-ns? — all seon.* +
   my.* minus *.internal) and the full-source depth rule; the
   `<namespace>` tags (internal never renders, an agent-authored ns
   appears with NO config change, recency = most-recently-modified
   LAST with a byte-identical prefix above the moved tag); the
   `:seon.agent/purpose` entity seed + `<your-entity>` render;
   merge/override-by-name semantics; the render guard; the per-agent
   section budget; and the mixed-:or slot storage roundtrip.

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
;; Selection rules — the ONE inclusion rule + the depth rule.
;; ------------------------------------------------------------

(deftest selection-rules
  ;; included-ns? — ALL seon.* + my.* EXCEPT *.internal. One rule.
  (doseq [n ["seon.db" "seon.eval" "seon.agent.search" "my.kb"
             "my.agent.a1" "my.finance" "seon.agent.search-test"]]
    (is (true? (ctx/included-ns? n)) (str n " is included")))
  (doseq [n ["seon.db.internal" "seon.x.internal.y" "my.foo.internal"
             "cljs.core" "datahike.api"]]
    (is (false? (ctx/included-ns? n)) (str n " is NOT included")))
  ;; hidden beats everything, even under my.*.
  (doseq [n ["seon.db.internal" "seon.agent.internal" "my.foo.internal"]]
    (is (true? (ctx/hidden-ns-name? n)) (str n " is hidden")))
  ;; 2-arity: a configured downstream prefix includes its root + children;
  ;; the *.internal exclusion is STRUCTURAL — it applies to every prefix.
  (is (true? (ctx/included-ns? "acme.core" ["seon." "my." "acme."])))
  (is (true? (ctx/included-ns? "acme" ["acme."]))
      "a prefix includes its bare root ns")
  (is (false? (ctx/included-ns? "acme.core" ["seon." "my."]))
      "unconfigured prefix is NOT included")
  (is (false? (ctx/included-ns? "acme.core.internal" ["acme."]))
      "*.internal exclusion applies to configured prefixes too")
  (is (false? (ctx/included-ns? "acmene.core" ["acme."]))
      "prefix matches on segment boundary, not substring")
  ;; full-source depth: my.* by RULE + the seon exemplar roots +
  ;; children + test siblings; big unsplit substrate stays shallow.
  (doseq [n ["my.kb" "my.kb.system" "my.soul" "my.soul-test"
             "seon.agent.search" "seon.agent.search-test"
             "seon.agent.todo" "seon.agent.todo-test"]]
    (is (true? (ctx/full-source-ns? n)) (str n " is full-source")))
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.db" "seon.ctx"
             "seon.agent.searcher" "my.foo.internal"]]
    (is (false? (ctx/full-source-ns? n)) (str n " is NOT full-source"))))

;; ------------------------------------------------------------
;; namespaces-section — tags, hiding, reconstitution, recency.
;; ------------------------------------------------------------

(defn- transact-ns-row!
  [nm]
  (db/transact!
    {:seon.db/tx-data [{:seon.ns/name   (keyword nm)
                        :seon.ns/source (str "(ns " nm ")")}]}))

(deftest namespaces-section-tags-hiding-reconstitution-recency
  (async done
    (let [!before (atom nil)]
      (-> (with-conn
            (fn [_conn]
              (-> (transact-ns-row! "seon.client")
                  (.then (fn [_] (transact-ns-row! "seon.db.internal")))
                  (.then (fn [_] (transact-ns-row! "my.agent.a1")))
                  ;; agent-authored ns: stub row + a fn member → must
                  ;; RECONSTITUTE (ns form + fn source), no config.
                  (.then (fn [_]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:seon.fn/sym     "my.agent.a1/helper"
                                :seon.fn/ns      [:seon.ns/name :my.agent.a1]
                                :seon.fn/source  "(defn helper [] 1)"
                                :seon.fn/fn-var? true
                                :seon.fn/created-at (js/Date.)}]})))
                  (.then
                    (fn [_]
                      (let [txt (ctx/namespaces-section {:seon.db/db @db/*conn*})]
                        (reset! !before txt)
                        (is (str/includes? txt "<namespace name=\"seon.client\">")
                            "an included ns renders as a tag")
                        (is (str/includes? txt "<namespace name=\"my.agent.a1\">")
                            "a runtime-defined ns appears with NO config change")
                        (is (str/includes? txt "(defn helper [] 1)")
                            "stub ns with members reconstitutes member source")
                        (is (not (str/includes? txt "seon.db.internal"))
                            "*.internal never appears")
                        (is (not (str/includes? txt "<exemplar"))
                            "the <exemplars> wrapper is dead")
                        ;; recency: my.agent.a1 was touched LAST (member
                        ;; upsert bumps its name datom) → renders last.
                        (is (> (str/index-of txt "<namespace name=\"my.agent.a1\">")
                               (str/index-of txt "<namespace name=\"seon.client\">"))
                            "most-recently-modified renders LAST"))))
                  ;; modify seon.client between turns → it moves LAST and
                  ;; the prefix ABOVE the moved tag is byte-identical.
                  (.then (fn [_]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:seon.ns/name   :seon.client
                                :seon.ns/source "(ns seon.client) (def touched 1)"}]})))
                  (.then
                    (fn [_]
                      (let [before @!before
                            after  (ctx/namespaces-section
                                     {:seon.db/db @db/*conn*})
                            moved  "<namespace name=\"seon.client\">"]
                        (is (> (str/index-of after moved)
                               (str/index-of after "<namespace name=\"my.agent.a1\">"))
                            "modified ns moved LAST")
                        (is (= (subs before 0 (str/index-of before moved))
                               (subs after 0 (str/index-of before moved)))
                            "prefix above the moved tag's old position is byte-identical")))))))
          (.then (fn [] (done)))
          (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done)))))))

;; ------------------------------------------------------------
;; Downstream prefix extensibility — the customize-with-data row.
;; A downstream consumer adds its ns prefix by ONE transact onto the
;; config entity; the next render carries the tags; a retract removes
;; them. Defaults seed-if-absent ([[ctx/ensure-ctx-config!]] via the
;; composer); reads fall back to the defaults until then.
;; ------------------------------------------------------------

(deftest included-prefix-extensibility
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (transact-ns-row! "acme.core")
                (.then (fn [_] (transact-ns-row! "seon.client")))
                (.then
                  (fn [_]
                    (is (= (vec (sort ctx/default-included-prefixes))
                           (ctx/included-prefixes @db/*conn*))
                        "no config row → built-in defaults")
                    (is (not (str/includes?
                               (ctx/namespaces-section {:seon.db/db @db/*conn*})
                               "acme.core"))
                        "unconfigured downstream prefix does not render")))
                ;; the seed row (what ensure-ctx-config! transacts) …
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.ctx/config-id "substrate"
                              :seon.ctx/included-prefixes
                              ctx/default-included-prefixes}]})))
                ;; … then the downstream's ONE transact (identity upsert +
                ;; cardinality-many ADD — the defaults are never restated
                ;; or clobbered).
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.ctx/config-id "substrate"
                              :seon.ctx/included-prefixes ["acme."]}]})))
                (.then
                  (fn [_]
                    (let [txt (ctx/namespaces-section {:seon.db/db @db/*conn*})]
                      (is (str/includes? txt "<namespace name=\"acme.core\">")
                          "ONE transact → downstream ns renders as a tag")
                      (is (str/includes? txt "<namespace name=\"seon.client\">")
                          "defaults still render alongside"))))
                ;; the *.internal exclusion stays structural.
                (.then (fn [_] (transact-ns-row! "acme.core.internal")))
                (.then
                  (fn [_]
                    (is (not (str/includes?
                               (ctx/namespaces-section {:seon.db/db @db/*conn*})
                               "acme.core.internal"))
                        "*.internal never renders, configured prefix or not")))
                ;; retract → gone next render.
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [[:db/retract ctx/config-ref
                              :seon.ctx/included-prefixes "acme."]]})))
                (.then
                  (fn [_]
                    (let [txt (ctx/namespaces-section {:seon.db/db @db/*conn*})]
                      (is (not (str/includes? txt "acme.core"))
                          "retracted prefix → tag gone next render")
                      (is (str/includes? txt "<namespace name=\"seon.client\">")
                          "defaults unaffected by the retract")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; Composer: purpose-as-entity-data, your-entity, merge, verbs.
;; ------------------------------------------------------------

(defn- assemble
  [id]
  (ctx/assemble-context {:seon.db/db @db/*conn* :seon.agent/id id}))

(defn- section-text
  [id nm]
  (some #(when (= nm (:seon.ctx/name %)) (:seon.render/text %))
        (:seon.render/section-texts (assemble id))))

(deftest purpose-entity-and-your-entity-and-verbs
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00p1"
                                :seon.agent/purpose "watch the ledger"})
                (.then
                  (fn [_]
                    (let [{:seon.render/keys [sections]} (assemble "AGTctxtest00p1")
                          ent-txt (section-text "AGTctxtest00p1" :your-entity)]
                      (is (some #{:your-entity} sections)
                          "minted agent renders <your-entity>")
                      (is (str/includes? (str ent-txt) "watch the ledger")
                          "stated purpose is entity data, rendered in the map")
                      (is (str/includes? (str ent-txt) "<your-entity>")
                          "tag wrapper present")
                      (is (some #{:system} sections)
                          "substrate defaults merged in")
                      (is (some #{:prompt} sections))
                      (is (not-any? #{:purpose} sections)
                          "the :purpose seed section is dead")
                      (is (not-any? #{:your-sections} sections)
                          "the :your-sections seed section is dead"))))
                ;; set-purpose! now writes the entity attr.
                (.then (fn [_]
                         (db/with-agent "AGTctxtest00p1"
                           (fn []
                             (agent/set-purpose!
                               {:seon.render/ai "guard the books"})))))
                ;; create! again = resume — must NOT overwrite purpose.
                (.then (fn [_] (agent/create! {:seon.agent/id "AGTctxtest00p1"})))
                (.then
                  (fn [_]
                    (is (str/includes?
                          (str (section-text "AGTctxtest00p1" :your-entity))
                          "guard the books")
                        "resume (re-create!) keeps the agent's own purpose")))
                ;; add-section! upsert-by-name + envelopes (unchanged).
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
