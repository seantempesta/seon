(ns seon.ctx-test
  "Contract tests for `seon.ctx` — the ONE composer.

   Pins: the ONE namespace-selection rule (included-ns? — EVERY indexed
   :seon.ns row minus *.internal and *-test, no prefix allow-list) and
   the full-source depth rule; the `;; ── namespace x ──` blocks
   (internal never renders, an agent-authored ns appears with NO config
   change, downstream code renders with NO config, recency =
   most-recently-modified LAST with a byte-identical prefix above the
   moved block); the `:seon.agent/purpose` entity seed + your-entity
   render; merge/override-by-name semantics; the render guard; the
   per-agent section budget; and the mixed-:or slot storage roundtrip.

   All on a FRESH :memory conn seeded like the pod boots — never the
   live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.turn :as turn]
    [seon.analyzer-info :as ai]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.ctx.inventory :as ctx-inventory]
    [seon.ctx.namespaces :as ctx-namespaces]
    [seon.ctx.relevant :as ctx-relevant]
    [seon.db :as db]
    [seon.embed.stash :as embed-stash]
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
  ;; included-ns? — EVERY indexed :seon.ns row EXCEPT *.internal and
  ;; *-test. ONE structural rule, no prefix allow-list: seon.*, my.*,
  ;; AND downstream code (acme.*) all render the same way (the library
  ;; gate lives on the INDEX side — only first-party/SEON_EXTRA_SRC code
  ;; ever gets a :seon.ns row, so cljs.core/datahike.api never reach this
  ;; predicate at render time).
  (doseq [n ["seon.db" "seon.eval" "seon.agent.search" "my.kb"
             "my.agent.a1" "my.finance"
             ;; downstream product code: NO prefix allow-list, so it is
             ;; included structurally just like seon/my code.
             "acme.widget" "acme.persona" "acme"]]
    (is (true? (ctx-namespaces/included-ns? n)) (str n " is included")))
  ;; the no-prefix downstream case stated explicitly.
  (is (true? (ctx-namespaces/included-ns? "acme.widget"))
      "downstream code is included with NO prefix allow-list")
  (doseq [n [;; *.internal — STRUCTURAL exclusion, applies to seon/my/
             ;; downstream alike.
             "seon.db.internal" "seon.x.internal.y" "my.foo.internal"
             "acme.widget.internal"
             ;; *-test namespaces are indexed but NEVER rendered into the
             ;; agent prompt (their deftests are noise; the per-fn :test
             ;; usage example rides the regular fn's compact head). Applies
             ;; to downstream code too.
             "seon.agent.search-test" "my.soul-test" "acme.widget-test"
             ;; debug capture lives under *.internal — dropped structurally,
             ;; same rule as every other internal ns. No name-list.
             "seon.debug.internal"]]
    (is (false? (ctx-namespaces/included-ns? n)) (str n " is NOT included")))
  ;; the *-test structural exclusion.
  (doseq [n ["seon.agent.search-test" "my.soul-test" "acme.widget-test"]]
    (is (true? (ctx-namespaces/test-ns-name? n)) (str n " is a test ns")))
  (is (false? (ctx-namespaces/test-ns-name? "seon.agent.search")) "non-test ns")
  ;; debug capture is hidden via the structural *.internal rule, no name-list.
  (is (true? (ctx-namespaces/hidden-ns-name? "seon.debug.internal"))
      "seon.debug.internal is hidden structurally")
  ;; hidden beats everything, even under my.* and downstream code.
  (doseq [n ["seon.db.internal" "seon.agent.internal" "my.foo.internal"
             "acme.widget.internal"]]
    (is (true? (ctx-namespaces/hidden-ns-name? n)) (str n " is hidden")))
  ;; full-source depth (curated-namespaces): full-source ⇔ every my.* ns by
  ;; RULE (test siblings ride along via the `-test` strip) PLUS the curated
  ;; seon.* whitelist (full-source-whitelist =
  ;; #{:seon.agent.todo :seon.db :seon.agent.search}). Each whitelisted ns's
  ;; `-test` sibling rides along too (the `-test` strip lands on the
  ;; whitelisted base, e.g. seon.agent.search-test → seon.agent.search).
  ;; Every OTHER seon.* framework ns renders in the SIGNATURES manifest
  ;; (public fn signatures, bodies elided), NEVER full-source.
  (doseq [n ["my.kb" "my.kb.system" "my.soul" "my.soul-test"
             ;; the curated seon.* whitelist + each one's test sibling.
             "seon.agent.todo" "seon.agent.todo-test"
             "seon.db" "seon.db-test"
             "seon.agent.search" "seon.agent.search-test"]]
    (is (true? (ctx-namespaces/full-source-ns? n)) (str n " is full-source")))
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.ctx"
             "seon.agent.searcher" "my.foo.internal"]]
    (is (false? (ctx-namespaces/full-source-ns? n)) (str n " is NOT full-source"))))

;; ------------------------------------------------------------
;; namespaces-section — tags, hiding, reconstitution, recency.
;; ------------------------------------------------------------

(defn- transact-ns-row!
  [nm]
  (db/transact!
    {:seon.db/tx-data [{:seon.ns/name   (keyword nm)
                        :seon.ns/source (str "(ns " nm ")")}]}))

(defn- transact-full-ns!
  "An ns row carrying REAL full source (a `(ns …)` line + a def body) —
   the shape the boot indexer stores for a full-rendered ns (my.*,
   third-party, or the curated seon.* whitelist). Used to prove the FULL
   render path: the whole source appears in the tag, unclipped."
  [nm body]
  (db/transact!
    {:seon.db/tx-data [{:seon.ns/name   (keyword nm)
                        :seon.ns/source (str "(ns " nm ")\n" body)}]}))

(deftest namespaces-section-curated-full-vs-manifest-recency
  ;; CURATED render (curated-namespaces 2026-06-21): FULL nses (my.*,
  ;; third-party acme.*, the curated seon.* whitelist, the current ns)
  ;; render their WHOLE source as a tag, UNCLIPPED; every OTHER seon.*
  ;; framework ns renders in the SIGNATURES manifest (public fn signatures,
  ;; bodies elided) — or, when it has no indexed fns (this seed), is NAMED
  ;; in the manifest's fn-less line.
  (async done
    (let [!before (atom nil)]
      (-> (with-conn
            (fn [_conn]
              ;; my.agent.a1 (my.* → FULL tag) with a real body.
              (-> (transact-full-ns! "my.agent.a1" "(def helper 1)")
                  ;; a third-party acme ns (non-seon, non-my → FULL tag).
                  (.then (fn [_] (transact-full-ns! "acme.widget" "(def w 2)")))
                  ;; framework nses → NAME-MANIFEST only (stub source, no
                  ;; body shown). seon.client carries a faux body to PROVE
                  ;; the body is never rendered for a manifested ns.
                  (.then (fn [_] (transact-full-ns! "seon.client" "(def never-shown 3)")))
                  (.then (fn [_] (transact-ns-row! "seon.warn")))
                  ;; a framework ns WITH a public fn → SIGNATURES tag (the
                  ;; new manifest API view: name + arglist + one-line doc,
                  ;; BODY elided). A `defn-` private sibling must NOT show.
                  (.then (fn [_] (transact-ns-row! "seon.frob")))
                  (.then
                    (fn [_]
                      (db/transact!
                        {:seon.db/tx-data
                         [{:seon.fn/sym      "seon.frob/widget"
                           :seon.fn/ns       [:seon.ns/name :seon.frob]
                           :seon.fn/arglists "([a b])"
                           :seon.fn/doc      "Frobnicate a and b.\nMore detail here."
                           :seon.fn/source   "(defn widget [a b] (+ a b))"}
                          {:seon.fn/sym       "seon.frob/secret"
                           :seon.fn/ns        [:seon.ns/name :seon.frob]
                           :seon.fn/arglists  "([x])"
                           :seon.fn/private?  true
                           :seon.fn/source    "(defn- secret [x] x)"}]})))
                  ;; *.internal is excluded outright.
                  (.then (fn [_] (transact-ns-row! "seon.db.internal")))
                  (.then
                    (fn [_]
                      (let [txt (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})]
                        (reset! !before txt)
                        ;; FULL: my.* renders its whole source, unclipped.
                        (is (str/includes? txt ";; ── namespace my.agent.a1 ──")
                            "a my.* ns renders as a full block")
                        (is (str/includes? txt "(def helper 1)")
                            "the my.* ns body is shown FULL (no clipping)")
                        ;; FULL: third-party acme renders its whole source.
                        (is (str/includes? txt ";; ── namespace acme.widget ──")
                            "a third-party acme ns renders as a full block")
                        (is (str/includes? txt "(def w 2)")
                            "the acme body is shown FULL (no clipping)")
                        ;; MANIFEST: a framework ns with NO indexed fns (this
                        ;; seed) appears ONLY as a NAME in the fn-less line —
                        ;; never a full-source block, never its body.
                        (is (not (str/includes? txt ";; ── namespace seon.client ──"))
                            "a non-whitelisted framework ns is NOT a full-source block")
                        (is (not (str/includes? txt "(def never-shown 3)"))
                            "a manifested ns's body is NEVER rendered")
                        (is (str/includes? txt "seon.client")
                            "the framework ns appears as a NAME in the manifest")
                        (is (str/includes? txt "seon.warn")
                            "another framework ns is named in the same manifest")
                        ;; SIGNATURES: a framework ns WITH public fns shows
                        ;; them as a signatures tag — name + arglist + one-line
                        ;; doc, body ELIDED, private fn omitted.
                        (is (str/includes? txt
                                           ";; ── namespace seon.frob (signatures) ──")
                            "a framework ns with public fns is a signatures block")
                        ;; the signature line carries the fn tag + the callable
                        ;; (sym [arglist]) shape + its spec marker; the first
                        ;; doc line rides on the NEXT line as a `;;` comment
                        ;; (no-bare-prose unit — the manifest reads as eval'able
                        ;; Clojure), bodies elided.
                        (is (str/includes? txt "(seon.frob/widget [a b])")
                            "the public fn shows name + arglist (callable shape)")
                        (is (str/includes? txt ";; Frobnicate a and b.")
                            "first doc line rides as a `;;` comment under the sig")
                        (is (not (str/includes? txt "(+ a b)"))
                            "the fn BODY is elided in the signature manifest")
                        (is (not (str/includes? txt "More detail here."))
                            "only the FIRST docstring line is shown")
                        (is (not (str/includes? txt "seon.frob/secret"))
                            "a private (defn-) fn is omitted from the API view")
                        ;; the manifest carries a query-for-source pointer.
                        (is (str/includes? txt ":seon.ns/name")
                            "the manifest points at the query to fetch source")
                        ;; *.internal never appears anywhere.
                        (is (not (str/includes? txt "seon.db.internal"))
                            "*.internal never appears")
                        ;; recency among FULL blocks: acme.widget's row was
                        ;; written AFTER my.agent.a1 → renders later.
                        (is (> (str/index-of txt ";; ── namespace acme.widget ──")
                               (str/index-of txt ";; ── namespace my.agent.a1 ──"))
                            "most-recently-modified full block renders LAST"))))
                  ;; modify my.agent.a1 → it moves LAST among full tags and
                  ;; the prefix ABOVE the moved tag is byte-identical.
                  (.then (fn [_] (transact-full-ns! "my.agent.a1" "(def helper 99)")))
                  (.then
                    (fn [_]
                      (let [before @!before
                            after  (ctx-namespaces/namespaces-section
                                     {:seon.db/db @db/*conn*})
                            moved  ";; ── namespace my.agent.a1 ──"]
                        (is (> (str/index-of after moved)
                               (str/index-of after ";; ── namespace acme.widget ──"))
                            "modified full ns moved LAST among blocks")
                        (is (= (subs before 0 (str/index-of before moved))
                               (subs after 0 (str/index-of before moved)))
                            "prefix above the moved tag's old position is byte-identical")))))))
          (.then (fn [] (done)))
          (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done)))))))

;; ------------------------------------------------------------
;; No prefix allow-list — ALL indexed code renders (downstream `acme.*`
;; included with NO config), *.internal + *-test structurally excluded.
;; The library gate is on the INDEX side (only first-party/SEON_EXTRA_SRC
;; code gets a :seon.ns row), so render-time selection is structural only.
;; ------------------------------------------------------------

(defn- transact-ns-with-test-member!
  "An ns stub row PLUS one `:seon.test` (deftest) member only — a *-test
   ns's natural shape. The ns is still excluded by the *-test structural
   rule regardless; the member just makes it a real (non-bare-stub) row."
  [nm]
  (-> (transact-ns-row! nm)
      (.then (fn [_]
               (db/transact!
                 {:seon.db/tx-data
                  [{:seon.test/sym        (str nm "/probe-test")
                    :seon.test/ns         [:seon.ns/name (keyword nm)]
                    :seon.test/source     "(deftest probe-test (is true))"
                    :seon.test/created-at (js/Date.)}]})))))

(deftest renders-curated-code-internal-and-test-excluded
  ;; A fresh conn, NO config row anywhere: downstream `acme.widget`
  ;; (third-party) and `my.kb` (my.*) render as FULL `;; ── namespace x ──`
  ;; blocks purely because their :seon.ns rows exist; a non-whitelisted
  ;; seon.* framework ns (`seon.client`) is NAME-MANIFESTED, not a block;
  ;; `acme.widget.internal` (*.internal) and `acme.widget-test` (*-test)
  ;; are excluded by the structural rules.
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (transact-full-ns! "acme.widget" "(def w 1)")
                (.then (fn [_] (transact-full-ns! "seon.client" "(def c 2)")))
                (.then (fn [_] (transact-full-ns! "my.kb" "(def k 3)")))
                (.then (fn [_] (transact-ns-row! "acme.widget.internal")))
                (.then (fn [_] (transact-ns-with-test-member! "acme.widget-test")))
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-section {:seon.db/db @db/*conn*})]
                      ;; third-party code renders FULL with NO config transact.
                      (is (str/includes? txt ";; ── namespace acme.widget ──")
                          "downstream acme.widget renders FULL with NO config")
                      (is (str/includes? txt "(def w 1)")
                          "the acme body is shown FULL")
                      ;; my.* renders FULL.
                      (is (str/includes? txt ";; ── namespace my.kb ──")
                          "my.* renders FULL")
                      ;; a non-whitelisted framework ns is manifested, not a block.
                      (is (not (str/includes? txt ";; ── namespace seon.client ──"))
                          "a framework ns is NOT a full block")
                      (is (str/includes? txt "seon.client")
                          "the framework ns is NAMED in the manifest")
                      ;; *.internal never renders.
                      (is (not (str/includes? txt "acme.widget.internal"))
                          "*.internal is excluded structurally, no allow-list needed")
                      ;; *-test never renders into the agent prompt.
                      (is (not (str/includes? txt "acme.widget-test"))
                          "*-test is excluded structurally")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest defs-since-skips-result-vars
  ;; INDEX-SIDE pin (the load-bearing leak guard): the allow-list was the
  ;; only thing hiding the synthetic `result/<id>` vars that
  ;; seon.eval/bind-result-var! registers under the reserved `result` ns
  ;; with `:seon.eval/result-var? true`. With the allow-list gone,
  ;; defs-since MUST still drop them so they never tee as bogus :seon.fn
  ;; rows + a sourceless {:seon.ns/name :result} row.
  (let [before {}
        cs     (atom {:cljs.analyzer/namespaces
                      {'result {:name 'result
                                :defs {'OKf {:name 'result/OKf
                                             :seon.eval/result-var? true}}}
                       'my.ns  {:defs {'real-fn
                                        {:meta {:doc "a real fn"}
                                         :fn-var true
                                         :arglists '(quote ([x]))}}}}})
        new    (ai/defs-since before cs)
        nses   (set (map :ns new))
        syms   (set (map :sym new))]
    (is (not (contains? nses 'result))
        "the reserved result ns must not produce a new-def entry")
    (is (not (contains? syms 'OKf))
        "the synthetic result var must be skipped")
    (is (contains? syms 'real-fn)
        "a genuine agent-authored def is still teed")))

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
                      ;; literal `:seon.agent/purpose "..."` (ASCII
                      ;; placeholder, no glyphs — no-bare-prose unit) —
                      ;; exclude it: a REAL value is any other string.
                      (is (not (re-find #":seon\.agent/purpose \"(?!\.\.\.)" txt))
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
  ;; Chat-surface task #29 (a21 writing teaching): the markdown-replies
  ;; teaching is present, and (no-bare-prose unit) it rides as `;;;`
  ;; runtime-voice comment lines so the whole system block reads as
  ;; eval'able Clojure — the B1 extractor sees only comments here.
  (is (str/includes? ctx/system-text
                     "messages render as markdown"))
  (let [bullet-lines (->> (str/split-lines ctx/system-text)
                          (drop-while #(not (str/includes? % "messages render as markdown")))
                          (take 3))]
    (is (seq bullet-lines))
    (is (every? #(str/starts-with? (str/triml %) ";;") bullet-lines)
        "comment-shaped — the no-bare-prose unit makes every line a `;;`/`;;;` comment")))

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
                          "minted agent renders the your-entity section")
                      (is (str/includes? (str ent-txt) "watch the ledger")
                          "stated purpose is entity data, rendered in the map")
                      (is (str/includes? (str ent-txt) ";; ── your entity ──")
                          "your-entity header present")
                      (is (some #{:system} sections)
                          "core defaults merged in")
                      (is (some #{:transcript} sections))
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
                      (is (some #{:transcript} sections)
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
                  ;; a my.* ns → rendered FULL as a `;; ── namespace x ──`
                  ;; block in the STABLE half (a framework ns would only be
                  ;; name-manifested).
                  (.then (fn [_] (transact-full-ns! "my.client" "(def x 1)")))
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
                                           ";; ── namespace my.client ──")
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
                  (.then (fn [_] (turn/start-session! "AGTctxtest00d1")))
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
;; inventory-section — the cheap stored-data discovery surface.
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
                      (is (str/includes? txt ";; ── stored data inventory ──")
                          "rendered under the stored-data inventory header")
                      ;; ONE line per kind: the kind name is the line label,
                      ;; written ONCE, then bare attr-name count pairs. The
                      ;; whole body rides as `;;` comments (no-bare-prose
                      ;; unit — the context reads as eval'able Clojure).
                      (is (str/includes? txt ";; my.workout: ")
                          "kind is the line label (namespace written once), commented")
                      ;; count is correct (3 rows, both attrs present on each).
                      (is (str/includes? txt "date 3")
                          "attr count is the live row count, namespace stripped")
                      (is (str/includes? txt "type 3")
                          "second attr counted the same")
                      ;; attr NAMES appear WITHOUT their namespace prefix on
                      ;; the kind's OWN line — the line label already carries
                      ;; it. (The schema-key values on the seon.schema line
                      ;; legitimately ARE the qualified attr keywords now that
                      ;; low-card identity values render inline, so scope the
                      ;; check to the my.workout line.)
                      (let [wline (first (filter #(str/starts-with? % ";; my.workout: ")
                                                 lines))]
                        (is (some? wline) "the my.workout kind line is present")
                        (is (not (str/includes? wline ":my.workout/date"))
                            "attr namespace prefix is stripped from the pairs")
                        (is (not (str/includes? wline "my.workout/date"))
                            "no qualified attr name leaks into the pairs")
                        ;; the new value-surfacing: a low-card keyword attr
                        ;; shows its DISTINCT members inline as an ILLUSTRATIVE
                        ;; SAMPLE in «…» guillemets (NOT [..]) — the de-literaled
                        ;; convention marks them as example filter keys to query,
                        ;; never an authoritative readout. The whole line is a
                        ;; `;;` comment so the glyphs never break the reader.
                        (is (str/includes? wline "«:lift :run»")
                            "low-cardinality categorical values render inline as a «…» sample"))
                      ;; one-line-per-kind: exactly ONE body line mentions
                      ;; the kind (the header is ;; comments, not a kind line).
                      (is (= 1 (count (filter #(str/starts-with? % ";; my.workout: ")
                                              lines)))
                          "exactly one line per kind")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; relevant-source-section (P2-D) — the embedding-retrieval surface.
;; PURE reader of the per-turn `seon.embed.stash`; no conn needed.
;; ------------------------------------------------------------

(deftest relevant-source-section-renders-stashed-hits
  ;; NO stash active (the default-OFF / no-prefetch path) → "" so the
  ;; composer drops the section. WITH a stash → the relevant-context
  ;; header, the hits' syms + source, top-k respected, per-hit char cap
  ;; with a loud truncation marker, and the over-cap source NEVER leaks.
  (let [in   {:seon.db/db {} :seon.agent/id "X"}
        long-src (apply str (repeat (* 3 ctx-relevant/source-char-cap) "z"))
        hits (vec
               (for [i (range 8)]
                 {:seon.embed/eid i :seon.embed/distance (* 0.1 i)
                  :seon.embed/entity
                  {:seon.fn/sym    (str "my.ns/fn" i)
                   :seon.fn/source (if (zero? i) long-src
                                       (str "(defn fn" i " [] " i ")"))}}))]
    ;; (1) no stash → reactive blank.
    (is (= "" (ctx-relevant/relevant-source-section in))
        "no stash (default-OFF / no prefetch) → \"\" (reactive suppression)")
    ;; (2) with a stash → full render.
    (let [txt (embed-stash/with-hits hits
                #(ctx-relevant/relevant-source-section in))]
      (is (str/includes? txt ";; ── relevant context ──")
          "rendered under the relevant-context header")
      ;; top-k respected: only the first `top-k` hits render.
      (is (str/includes? txt "my.ns/fn0") "first hit's sym present")
      (is (str/includes? txt (str "my.ns/fn" (dec ctx-relevant/top-k)))
          "the k-th hit's sym present")
      (is (not (str/includes? txt (str "my.ns/fn" ctx-relevant/top-k)))
          "the (k+1)-th hit is dropped — top-k respected")
      (is (str/includes? txt "(defn fn1 [] 1)") "a hit's source renders inline")
      ;; per-hit char cap with a LOUD marker; the over-cap source is NOT
      ;; rendered whole.
      (is (str/includes? txt "TRUNCATED")
          "over-cap source carries the loud truncation marker")
      (is (not (str/includes? txt long-src))
          "the full over-cap source NEVER leaks (capped)"))))

(deftest relevant-source-section-renders-any-kind
  ;; GENERALITY (P2-D): the section is kind-general + has NO hard-coded attr
  ;; names — it renders the most relevant embedded ENTITY of ANY kind by a
  ;; uniform rule (the attribute IS the type; NO :seon/kind enum): header = the
  ;; entity's identity (its SHORTEST string attr, else :db/id), body = its
  ;; LONGEST string attr (the embedded text). A fn renders sym + source; a KB
  ;; row renders its id + body; an unknown kind renders its id + prose — NEVER a
  ;; blank `<unknown>` for an entity that has any string attr.
  (let [in        {:seon.db/db {} :seon.agent/id "X"}
        long-body (apply str (repeat (* 3 ctx-relevant/source-char-cap) "y"))
        fn-hit    {:seon.embed/eid 17 :seon.embed/distance 0.1
                   :seon.embed/entity
                   {:db/id 17
                    :seon.fn/sym    "seon.math/l2-normalize"
                    :seon.fn/source "(defn l2-normalize [v] :normalized)"}}
        kb-hit    {:seon.embed/eid 14 :seon.embed/distance 0.2
                   :seon.embed/entity
                   {:db/id 14
                    :my.kb/id    "kb-wire-server"
                    :my.kb/title "The wire-server is the sole datahike writer"
                    :my.kb/body  "The CLJS pod forwards every write over a UDS."}}
        kb-long   {:seon.embed/eid 15 :seon.embed/distance 0.3
                   :seon.embed/entity
                   {:db/id 15 :my.kb/id "kb-long"
                    :my.kb/title "Long KB" :my.kb/body long-body}}
        gen-hit   {:seon.embed/eid 99 :seon.embed/distance 0.4
                   :seon.embed/entity
                   {:db/id 99 :my.doc/id "doc-42"
                    :my.doc/prose "the longest string attr is the embedded text here"}}
        lost-hit  {:seon.embed/eid 7 :seon.embed/distance 0.5}   ; raced retraction → no entity
        render    (fn [hits] (embed-stash/with-hits hits
                               #(ctx-relevant/relevant-source-section in)))]
    ;; KB renders IDENTITY (shortest string attr) + BODY (longest string attr),
    ;; GENERICALLY — no hard-coded :my.kb/title dispatch (the attribute IS the
    ;; type). For this row the shortest string is :my.kb/id "kb-wire-server".
    (let [txt (render [kb-hit])]
      (is (str/includes? txt "kb-wire-server")
          "KB hit renders its shortest string attr (the id) as the header")
      (is (str/includes? txt "The CLJS pod forwards every write over a UDS.")
          "KB hit renders its body (longest string attr) inline")
      (is (not (str/includes? txt "<unknown>"))
          "a KB hit never renders the blank <unknown> placeholder"))
    ;; fn renders sym + source, as before.
    (let [txt (render [fn-hit])]
      (is (str/includes? txt "seon.math/l2-normalize") "fn hit renders its sym")
      (is (str/includes? txt "(defn l2-normalize [v] :normalized)")
          "fn hit renders its source"))
    ;; generic fallback: identity + longest string attr, never blank.
    (let [txt (render [gen-hit])]
      (is (str/includes? txt "doc-42") "generic hit renders its */id identity")
      (is (str/includes? txt "the longest string attr is the embedded text here")
          "generic hit renders its longest string attr as the body"))
    ;; MIXED: one section with a fn + a kb + a generic, each rendered right.
    (let [txt (render [fn-hit kb-hit gen-hit])]
      (is (str/includes? txt "seon.math/l2-normalize") "mixed: fn present")
      (is (str/includes? txt "kb-wire-server")
          "mixed: kb identity (shortest string attr) present")
      (is (str/includes? txt "doc-42") "mixed: generic identity present"))
    ;; KB body honours the per-hit char cap with a loud marker; never leaks.
    (let [txt (render [kb-long])]
      (is (str/includes? txt "TRUNCATED") "over-cap KB body carries the marker")
      (is (not (str/includes? txt long-body)) "over-cap KB body never leaks"))
    ;; entity-less hit (lost eid) → header-only <unknown>, never throws/blank-tag.
    (let [txt (render [lost-hit])]
      (is (str/includes? txt "<unknown>")
          "an entity-less hit renders a header-only <unknown> block")
      (is (str/includes? txt ";; ── relevant context ──")
          "and stays under the section header"))))

(deftest off-path-is-byte-identical
  ;; THE SAFETY CONTRACT. With NO retrieval stash active (the default-OFF
  ;; code path — `run-turn!` never calls `with-hits`), the :relevant-source
  ;; section renders blank, the composer drops it, and the assembled prompt
  ;; is byte-identical to a baseline assembled the same way. Prove BOTH:
  ;; the section is absent from the render order, and assembling twice with
  ;; no stash yields the identical string (no query-dependent drift).
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxrel0001p"})
                (.then
                  (fn [_]
                    (let [r1 (assemble "AGTctxrel0001p")
                          r2 (assemble "AGTctxrel0001p")
                          texts-of (fn [r]
                                     (into {} (map (juxt :seon.ctx/name
                                                         :seon.render/text))
                                           (:seon.render/section-texts r)))]
                      ;; :relevant-source IS in the LAYOUT provenance (every
                      ;; merged section name, blank or not — assemble-context
                      ;; docstring) ...
                      (is (some #{:relevant-source}
                                (:seon.render/sections r1))
                          ":relevant-source is part of the core layout")
                      ;; ... but with NO retrieval stash active (default-OFF —
                      ;; run-turn! never called with-hits) it renders BLANK, so
                      ;; it contributes NO :seon.render/section-texts entry and
                      ;; NO text to the prompt — the composer drops it.
                      (is (not (contains? (texts-of r1) :relevant-source))
                          ":relevant-source contributes no text (blank → dropped)")
                      (is (not (str/includes? (:seon.render/text r1)
                                              ";; ── relevant context ──"))
                          "no relevant-context header in the OFF-path prompt")
                      ;; byte-identical across two assemblies (the section
                      ;; is not pulling query-dependent content into the
                      ;; prompt when off).
                      (is (= (:seon.render/text r1) (:seon.render/text r2))
                          "OFF-path prompt is stable / byte-identical")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))
