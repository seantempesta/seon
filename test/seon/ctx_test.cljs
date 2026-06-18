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
    [seon.ctx.inventory :as ctx-inventory]
    [seon.ctx.namespaces :as ctx-namespaces]
    [seon.db :as db]
    [seon.schema :as schema]))

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
  ;; included-ns? — ALL seon.* + my.* EXCEPT *.internal and *-test. One rule.
  (doseq [n ["seon.db" "seon.eval" "seon.agent.search" "my.kb"
             "my.agent.a1" "my.finance"]]
    (is (true? (ctx/included-ns? n)) (str n " is included")))
  (doseq [n ["seon.db.internal" "seon.x.internal.y" "my.foo.internal"
             "cljs.core" "datahike.api"
             ;; *-test namespaces are indexed but NEVER rendered into the
             ;; agent prompt (their deftests are noise; the per-fn :test
             ;; usage example rides the regular fn's compact head).
             "seon.agent.search-test" "my.soul-test"
             ;; debug capture lives under *.internal — dropped structurally,
             ;; same rule as every other internal ns. No name-list.
             "seon.debug.internal"]]
    (is (false? (ctx/included-ns? n)) (str n " is NOT included")))
  ;; the *-test structural exclusion.
  (doseq [n ["seon.agent.search-test" "my.soul-test"]]
    (is (true? (ctx/test-ns-name? n)) (str n " is a test ns")))
  (is (false? (ctx/test-ns-name? "seon.agent.search")) "non-test ns")
  ;; debug capture is hidden via the structural *.internal rule, no name-list.
  (is (true? (ctx/hidden-ns-name? "seon.debug.internal"))
      "seon.debug.internal is hidden structurally")
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
  ;; full-source depth: my.* by RULE (test siblings ride along via the
  ;; `-test` strip); every seon.* ns is COMPACT, never full-source.
  (doseq [n ["my.kb" "my.kb.system" "my.soul" "my.soul-test"]]
    (is (true? (ctx/full-source-ns? n)) (str n " is full-source")))
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.db" "seon.ctx"
             "seon.agent.search" "seon.agent.search-test"
             "seon.agent.todo" "seon.agent.todo-test"
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

(defn- transact-ns-with-member!
  "An ns stub row PLUS one fn member, so the ns reconstitutes and renders
   (a bare stub with no members is omitted entirely from the section)."
  [nm]
  (-> (transact-ns-row! nm)
      (.then (fn [_]
               (db/transact!
                 {:seon.db/tx-data
                  [{:seon.fn/sym        (str nm "/probe")
                    :seon.fn/ns         [:seon.ns/name (keyword nm)]
                    :seon.fn/source     "(defn probe [] :p)"
                    :seon.fn/fn-var?    true
                    :seon.fn/created-at (js/Date.)}]})))))

(deftest namespaces-section-tags-hiding-reconstitution-recency
  (async done
    (let [!before (atom nil)]
      (-> (with-conn
            (fn [_conn]
              ;; seon.client: stub row + a fn member → it RECONSTITUTES
              ;; (a bare stub with NO members would be omitted entirely).
              (-> (transact-ns-with-member! "seon.client")
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
                      (let [txt (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})]
                        (reset! !before txt)
                        (is (str/includes? txt "<namespace name=\"seon.client\">")
                            "a stub ns with a member renders as a tag")
                        (is (str/includes? txt "<namespace name=\"my.agent.a1\">")
                            "a runtime-defined ns appears with NO config change")
                        ;; B9 compact: a sourceless ns's fn member renders
                        ;; as its elided defn head (body → `…)`), not the
                        ;; full source. No id in this input → all compact.
                        (is (str/includes? txt "(defn helper []")
                            "stub ns with members renders the member's defn head")
                        (is (not (str/includes? txt "(defn helper [] 1)"))
                            "the member BODY is elided in the compact form")
                        (is (not (str/includes? txt "seon.db.internal"))
                            "*.internal never appears")
                        (is (not (str/includes? txt "<exemplar"))
                            "the <exemplars> wrapper is dead")
                        ;; recency: my.agent.a1's member was upserted LAST
                        ;; (bumps its name datom) → renders last; seon.client
                        ;; was touched earlier → renders before it.
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
                            after  (ctx-namespaces/namespaces-section
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
            (-> (transact-ns-with-member! "acme.core")
                (.then (fn [_] (transact-ns-with-member! "seon.client")))
                (.then
                  (fn [_]
                    (is (= (vec (sort ctx/default-included-prefixes))
                           (ctx/included-prefixes @db/*conn*))
                        "no config row → built-in defaults")
                    (is (not (str/includes?
                               (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})
                               "acme.core"))
                        "unconfigured downstream prefix does not render")))
                ;; the seed row (what ensure-ctx-config! transacts) …
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.ctx/config-id "core"
                              :seon.ctx/included-prefixes
                              ctx/default-included-prefixes}]})))
                ;; … then the downstream's ONE transact (identity upsert +
                ;; cardinality-many ADD — the defaults are never restated
                ;; or clobbered).
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.ctx/config-id "core"
                              :seon.ctx/included-prefixes ["acme."]}]})))
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})]
                      (is (str/includes? txt "<namespace name=\"acme.core\">")
                          "ONE transact → downstream ns renders as a tag")
                      (is (str/includes? txt "<namespace name=\"seon.client\">")
                          "defaults still render alongside"))))
                ;; the *.internal exclusion stays structural — give it a
                ;; member so its absence is attributable ONLY to the
                ;; *.internal rule, not to bare-stub omission.
                (.then (fn [_] (transact-ns-with-member! "acme.core.internal")))
                (.then
                  (fn [_]
                    (is (not (str/includes?
                               (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})
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
                    (let [txt (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})]
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

(deftest your-entity-teaches-derive-purpose-only-while-unset
  ;; Chat-surface task #29 (a23): the derive-your-purpose instruction
  ;; is CONTEXT — never stored on the attr the customer tile renders.
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00p2"})
                (.then
                  (fn [_]
                    (let [txt (str (section-text "AGTctxtest00p2"
                                                 :your-entity))]
                      ;; The header's example transact contains the
                      ;; literal `:seon.agent/purpose "…"` — exclude it:
                      ;; a REAL value is any other string after the attr.
                      (is (not (re-find #":seon\.agent/purpose \"(?!…)" txt))
                          "no purpose VALUE rendered — the attr is absent")
                      (is (str/includes? txt "purpose is UNSET")
                          "unset purpose → the derive teaching renders")
                      (is (str/includes? txt "transact it onto your own")
                          "…and names the transact move"))))
                ;; The agent claims a purpose → the teaching vanishes
                ;; (derived section, self-healing — nothing to clear).
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.db/ref [:seon.agent/id "AGTctxtest00p2"]
                              :seon.agent/purpose "watch Acme invoices"}]})))
                (.then
                  (fn [_]
                    (let [txt (str (section-text "AGTctxtest00p2"
                                                 :your-entity))]
                      (is (str/includes? txt "watch Acme invoices")
                          "claimed purpose renders as entity data")
                      (is (not (str/includes? txt "purpose is UNSET"))
                          "teaching gone the moment the attr exists")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest system-text-teaches-markdown-replies
  ;; Chat-surface task #29 (a21 writing teaching): one prose-shaped
  ;; standing teaching — no parens-leading lines for the extractor.
  (is (str/includes? ctx/system-text
                     "replies render as markdown"))
  (let [bullet-lines (->> (str/split-lines ctx/system-text)
                          (drop-while #(not (str/includes? % "markdown")))
                          (take 3))]
    (is (seq bullet-lines))
    (is (not-any? #(str/starts-with? (str/triml %) "(") bullet-lines)
        "prose-shaped — the B1 extractor must not eval it")))

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
                          "core defaults merged in")
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

;; ------------------------------------------------------------
;; Stable/volatile split — the provider-cache contract (task #34).
;; Two assembles over the SAME db value → byte-identical stable
;; blocks; a volatile-only change (a new turn row) leaves the stable
;; block untouched; split-context recovers exactly the two halves
;; from the joined text.
;; ------------------------------------------------------------

(deftest stable-volatile-split-determinism
  (async done
    (let [!first (atom nil)]
      (-> (with-conn
            (fn [_conn]
              (-> (agent/create! {:seon.agent/id "AGTctxtest00d1"})
                  (.then (fn [_] (transact-ns-with-member! "seon.client")))
                  (.then
                    (fn [_]
                      (let [db @db/*conn*
                            a1 (ctx/assemble-context {:seon.db/db db
                                                      :seon.agent/id "AGTctxtest00d1"})
                            a2 (ctx/assemble-context {:seon.db/db db
                                                      :seon.agent/id "AGTctxtest00d1"})]
                        (reset! !first a1)
                        (is (= (:seon.render/stable-text a1)
                               (:seon.render/stable-text a2))
                            "same db value → byte-identical stable blocks")
                        (is (not (str/blank? (:seon.render/stable-text a1)))
                            "stable block is non-blank (system + namespaces)")
                        (is (str/includes? (:seon.render/stable-text a1)
                                           "<namespace name=\"seon.client\">")
                            "the namespaces body lives in the STABLE half")
                        (is (not (str/includes? (:seon.render/stable-text a1)
                                                ctx/stable-boundary))
                            "the boundary line is the join, never inside a half")
                        (is (str/includes? (:seon.render/text a1)
                                           ctx/stable-boundary)
                            "the joined text carries the in-band boundary")
                        (is (= {:seon.render/stable-text
                                (:seon.render/stable-text a1)
                                :seon.render/volatile-text
                                (:seon.render/volatile-text a1)}
                               (ctx/split-context (:seon.render/text a1)))
                            "split-context recovers exactly the two halves"))))
                  ;; volatile-only change: a NEW TURN ROW on a fresh
                  ;; session — transcript/turns are volatile sections.
                  (.then (fn [_] (agent/start-session! "AGTctxtest00d1")))
                  (.then (fn [sess]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:seon.agent.session/id
                                (:seon.agent.session/id sess)
                                :seon.agent.session/turns
                                [{:seon.agent.turn/id (db/new-id!)
                                  :seon.agent.turn/at (js/Date.)
                                  :seon.agent.turn/status :running
                                  :seon.agent.turn/prompt-chars 1}]}]})))
                  (.then
                    (fn [_]
                      (let [after (ctx/assemble-context
                                    {:seon.db/db @db/*conn*
                                     :seon.agent/id "AGTctxtest00d1"})]
                        (is (= (:seon.render/stable-text @!first)
                               (:seon.render/stable-text after))
                            "a volatile-only change (new turn row) leaves the stable block untouched")))))))
          (.then (fn [] (done)))
          (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done)))))))

(deftest split-context-without-boundary-is-all-volatile
  (is (= {:seon.render/stable-text   ""
          :seon.render/volatile-text "plain ctx, no boundary"}
         (ctx/split-context "plain ctx, no boundary"))
      "boundary-less text degrades to all-volatile (pre-split behavior)"))

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

;; ------------------------------------------------------------
;; inventory-section — the cheap <data-inventory> discovery surface.
;; ------------------------------------------------------------

(deftest inventory-section-renders-stored-kinds-compact
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; REACTIVE: a fresh conn has NO post-bootstrap data → the
            ;; section is suppressed (composer drops it), not an empty shell.
            (is (= "" (ctx-inventory/inventory-section {:seon.db/db @db/*conn*}))
                "no user-domain data → \"\" (reactive suppression)")
            (schema/register! :my.workout/date :string)
            (schema/register! :my.workout/type :keyword)
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.workout/date "2026-06-17" :my.workout/type :run}
                    {:my.workout/date "2026-06-16" :my.workout/type :lift}
                    {:my.workout/date "2026-06-15" :my.workout/type :run}]})
                (.then
                  (fn [_]
                    (let [txt   (ctx-inventory/inventory-section {:seon.db/db @db/*conn*})
                          lines (str/split-lines txt)]
                      (is (str/includes? txt "<data-inventory>")
                          "rendered behind the <data-inventory> tag")
                      ;; ONE line per kind: the kind name is the line label,
                      ;; written ONCE, then bare attr-name count pairs.
                      (is (str/includes? txt "my.workout: ")
                          "kind is the line label (namespace written once)")
                      ;; count is correct (3 rows, both attrs present on each).
                      (is (str/includes? txt "date 3")
                          "attr count is the live row count, namespace stripped")
                      (is (str/includes? txt "type 3")
                          "second attr counted the same")
                      ;; attr NAMES appear WITHOUT their namespace prefix —
                      ;; the line label already carries it.
                      (is (not (str/includes? txt ":my.workout/date"))
                          "attr namespace prefix is stripped from the pairs")
                      (is (not (str/includes? txt "my.workout/date"))
                          "no qualified attr name leaks into the pairs")
                      ;; one-line-per-kind: exactly ONE body line mentions
                      ;; the kind (the header is ;; comments, not a kind line).
                      (is (= 1 (count (filter #(str/starts-with? % "my.workout: ")
                                              lines)))
                          "exactly one line per kind")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))
