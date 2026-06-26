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
    [seon.agent.inspect :as inspect]
    [seon.agent.run :as run]
    [seon.agent.turn :as turn]
    [seon.ai :as llm]
    [seon.ai.openai-compat :as openai]
    [seon.analyzer-info :as ai]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.ctx.inventory :as ctx-inventory]
    [seon.ctx.live-tile :as ctx-live-tile]
    [seon.ctx.namespaces :as ctx-namespaces]
    [seon.ctx.relevant :as ctx-relevant]
    [seon.ctx.your-entity :as ctx-your-entity]
    [seon.db :as db]
    [seon.embed.stash :as embed-stash]
    [seon.render :as render]
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
             "seon.agent.search-test" "my.notes-test" "acme.widget-test"
             ;; debug capture lives under *.internal — dropped structurally,
             ;; same rule as every other internal ns. No name-list.
             "seon.debug.internal"]]
    (is (false? (ctx-namespaces/included-ns? n)) (str n " is NOT included")))
  ;; the *-test structural exclusion.
  (doseq [n ["seon.agent.search-test" "my.notes-test" "acme.widget-test"]]
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
  ;; seon.* whitelist. The whitelist CONTENTS are not mirrored here (that
  ;; drifts every prune) — derive the expected set from the source of truth
  ;; so the RULE is tested, not a hand-copy of the membership.
  (doseq [n ["my.kb" "my.kb.shared" "my.notes" "my.notes-test"]]
    (is (true? (ctx-namespaces/full-source-ns? n)) (str n " is full-source")))
  (doseq [kw ctx-namespaces/full-source-whitelist
          n  [(name kw) (str (name kw) "-test")]]
    (is (true? (ctx-namespaces/full-source-ns? n))
        (str n " (whitelist member / its -test sibling) is full-source")))
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.ctx"
             "seon.warn" "seon.ai" "seon.agent.search" "seon.agent.fs"
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

(deftest namespaces-section-curated-full-only-recency
  ;; CURATED render (de-stub 2026-06-24): ONLY full nses (my.*, third-party
  ;; acme.*, the curated seon.* whitelist, the current ns) render — each its
  ;; WHOLE source as a tag, UNCLIPPED. Every OTHER seon.* framework ns is
  ;; DROPPED from the rendered section entirely (no block, no body, no
  ;; signature manifest) — it stays indexed + searchable, just not shown.
  (async done
    (let [!before (atom nil)]
      (-> (with-conn
            (fn [_conn]
              ;; my.agent.a1 (my.* → FULL tag) with a real body.
              (-> (transact-full-ns! "my.agent.a1" "(def helper 1)")
                  ;; a third-party acme ns (non-seon, non-my → FULL tag).
                  (.then (fn [_] (transact-full-ns! "acme.widget" "(def w 2)")))
                  ;; framework nses → DROPPED entirely. seon.client carries a
                  ;; faux body to PROVE the body is never rendered for a
                  ;; dropped ns.
                  (.then (fn [_] (transact-full-ns! "seon.client" "(def never-shown 3)")))
                  (.then (fn [_] (transact-ns-row! "seon.warn")))
                  ;; a framework ns WITH a public fn — STILL dropped (no
                  ;; signature manifest anymore). A `defn-` private sibling
                  ;; obviously must not show either.
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
                        ;; FULL: my.* + third-party acme render their whole
                        ;; source. Anchor on the rendered ns-source HEAD
                        ;; (`(ns X`) — real content in every full block — plus
                        ;; the body, NOT the decorative per-ns label glyph.
                        ;; Block ORDERING is NOT asserted (priority is numeric
                        ;; + movable, ordering is not a contract).
                        (is (str/includes? txt "(ns my.agent.a1") "a my.* ns block renders")
                        (is (str/includes? txt "(def helper 1)")
                            "the my.* ns body is shown FULL (no clipping)")
                        (is (str/includes? txt "(ns acme.widget")
                            "a third-party acme ns renders")
                        (is (str/includes? txt "(def w 2)")
                            "the acme body is shown FULL (no clipping)")
                        ;; DROPPED: a non-whitelisted framework ns is absent
                        ;; entirely — no block, no body, no name.
                        (is (not (str/includes? txt "(def never-shown 3)"))
                            "a dropped ns's body is NEVER rendered")
                        (is (not (str/includes? txt "seon.client"))
                            "a dropped framework ns does not appear at all")
                        (is (not (str/includes? txt "seon.warn"))
                            "another dropped framework ns is absent")
                        ;; DROPPED: a framework ns WITH public fns is STILL
                        ;; absent — there is no signature manifest anymore.
                        (is (not (str/includes? txt "(signatures)"))
                            "there is NO signatures manifest block anywhere")
                        (is (not (str/includes? txt "seon.frob"))
                            "a framework ns with public fns is dropped, not signatured")
                        (is (not (str/includes? txt "Frobnicate a and b."))
                            "no doc line for a dropped ns's fn")
                        (is (not (str/includes? txt "(+ a b)"))
                            "a dropped fn BODY is never rendered")
                        ;; *.internal never appears anywhere.
                        (is (not (str/includes? txt "seon.db.internal"))
                            "*.internal never appears")))))))
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
  ;; seon.* framework ns (`seon.client`) is DROPPED entirely (no block, no
  ;; name); `acme.widget.internal` (*.internal) and `acme.widget-test`
  ;; (*-test) are excluded by the structural rules.
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
                      (is (str/includes? txt "(ns acme.widget")
                          "downstream acme.widget renders FULL with NO config")
                      (is (str/includes? txt "(def w 1)")
                          "the acme body is shown FULL")
                      ;; my.* renders FULL.
                      (is (str/includes? txt "(ns my.kb")
                          "my.* renders FULL")
                      ;; a non-whitelisted framework ns is dropped entirely.
                      (is (not (str/includes? txt "seon.client"))
                          "a framework ns is NOT a full block")
                      (is (not (str/includes? txt "seon.client"))
                          "the framework ns is DROPPED entirely (not even named)")
                      ;; *.internal never renders.
                      (is (not (str/includes? txt "acme.widget.internal"))
                          "*.internal is excluded structurally, no allow-list needed")
                      ;; *-test never renders into the agent prompt.
                      (is (not (str/includes? txt "acme.widget-test"))
                          "*-test is excluded structurally")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest cur-ns-always-renders-empty-workspace-stub
  ;; GI-2: the agent's CURRENT ns ALWAYS renders, even before anything is
  ;; defined in it — keeping the prompt's promise that YOUR OWN namespace
  ;; renders in full. A fresh home ns (a :seon.ns/name row, no stored source,
  ;; no fns/schemas) would otherwise be omitted as an empty full block;
  ;; instead it shows a reconstructed `(ns …)` form + a one-line workspace
  ;; note. This also exercises the symbol→keyword cur-ns normalization: a
  ;; fresh agent has no successful evals, so current-ns falls back to the
  ;; home-ns SYMBOL, which must still match the keyword ns row.
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact! {:seon.db/tx-data [{:seon.ns/name :my.agent.wtest}]})
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-section
                                {:seon.db/db @db/*conn* :seon.agent/id "wtest"})]
                      (is (str/includes? txt "; namespace my.agent.wtest")
                          "the empty current ns renders as a block")
                      (is (not (str/includes? txt "not in db"))
                          "no misleading 'not in db' for the indexed home ns")
                      (is (str/includes? txt "(ns my.agent.wtest")
                          "shows the reconstructed (ns …) form")
                      (is (str/includes? txt "nothing defined here yet")
                          "carries the empty-workspace note")))))))
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
  "The assembled context as a map, derived from the keystone ONE-render
   (`context-root` + `render` + `ctx-sections`) — the shape the old
   `assemble-context` returned, rebuilt from the new system so these tests
   keep asserting against the agent's real context."
  [id]
  (let [ctx   {:seon.db/db @db/*conn* :seon.agent/id id}
        root  (ctx/context-root ctx)
        text  (or (render/render :seon.render/ai ctx root) "")
        split (ctx/split-context text)
        {:seon.render/keys [section-texts section-html]} (ctx/ctx-sections ctx)]
    {:seon.render/text           text
     :seon.render/stable-text    (:seon.render/stable-text split)
     :seon.render/volatile-text  (:seon.render/volatile-text split)
     ;; LAYOUT PROVENANCE — every child section name in render order
     ;; (including ones that rendered blank this turn), the same shape the
     ;; old assemble-context's :seon.render/sections carried.
     :seon.render/sections       (mapv :seon.ctx/name (:seon.ctx/children root))
     :seon.render/section-texts  section-texts
     :seon.render/section-html   section-html
     :seon.render/token-estimate (quot (count text) 4)}))

(defn- section-text
  [id nm]
  (some #(when (= nm (:seon.ctx/name %)) (:seon.render/text %))
        (:seon.render/section-texts (assemble id))))

(deftest your-entity-teaches-derive-purpose-only-while-unset
  ;; Chat-surface task #29 (a23): the derive-your-purpose instruction
  ;; is CONTEXT — never stored on the attr the customer tile renders.
  (async done
    (let [!unset (atom nil)]
     (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00p2"})
                (.then
                  (fn [_]
                    (let [txt (str (section-text "AGTctxtest00p2"
                                                 :your-entity))]
                      (reset! !unset txt)
                      ;; The header's example transact contains the
                      ;; literal `:seon.agent/purpose "..."` (ASCII
                      ;; placeholder, no glyphs — no-bare-prose unit) —
                      ;; exclude it: a REAL value is any other string.
                      (is (not (re-find #":seon\.agent/purpose \"(?!\.\.\.)" txt))
                          "no purpose VALUE rendered — the attr is absent")
                      ;; while unset, the section teaches deriving the purpose
                      ;; attr — anchor on the CONTRACT TOKEN (the attr keyword),
                      ;; not the prose wording of the teaching.
                      (is (str/includes? txt ":seon.agent/purpose")
                          "the unset section is about the :seon.agent/purpose attr"))))
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
                      ;; self-healing vanish: once the attr is set the derive
                      ;; teaching block is gone, so the section SHRINKS — we
                      ;; assert the mechanism (section got smaller), not the
                      ;; exact teaching text that disappeared.
                      (is (< (count txt) (count @!unset))
                          "the derive teaching vanished — section shrank once purpose is set")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest live-tile-section-stable-on-composer-input
  ;; REGRESSION GUARD (live-tile-nil-entity-render-failed): the composer
  ;; injects ONLY {:seon.db/db … :seon.agent/id …} — it does NOT pass
  ;; :seon.agent/entity. The section must resolve the agent entity from
  ;; the db by id itself; it must NEVER surface a bare "⚠ render failed"
  ;; or a swallowed malli code, on a fresh store or a broken tile.
  (async done
    (-> (with-conn
         (fn [_conn]
           (-> (agent/create! {:seon.agent/id "AGTctxtile00p1"})
               (.then
                 (fn [_]
                   ;; (a) the EXACT composer input shape — db + id, no entity.
                   (let [out (str (ctx-live-tile/live-tile-section
                                    {:seon.db/db    @db/*conn*
                                     :seon.agent/id "AGTctxtile00p1"}))]
                     (is (seq out) "section renders content, never blank")
                     (is (not (str/includes? out "⚠"))
                         "no bare ⚠ render-failed placeholder")
                     (is (not (str/includes? out "malli"))
                         "no swallowed malli code in the agent's context")
                     (is (str/includes? out "Wired:")
                         "the wired-label header resolves (welcome by default)"))
                   ;; (a2) the sibling F1 case: your-entity must ALSO resolve
                   ;; its entity from the db under the bare composer ctx —
                   ;; never silently return "" (it must always show the agent
                   ;; its own entity). Pure fn of db, no injected entity.
                   (let [ye (str (ctx-your-entity/your-entity-section
                                   {:seon.db/db    @db/*conn*
                                    :seon.agent/id "AGTctxtile00p1"}))]
                     (is (seq ye) "your-entity renders under bare composer ctx")
                     (is (str/includes? ye "YOUR OWN ENTITY")
                         "your-entity resolves the entity from db, not nil"))
                   ;; (b) the REAL prompt path (render-context-ai, NOT the
                   ;; inspector's ctx-sections) must also be render-failure-free.
                   (let [ctx  {:seon.db/db @db/*conn* :seon.agent/id "AGTctxtile00p1"}
                         text (str (render/render :seon.render/ai ctx
                                                  (ctx/context-root ctx)))]
                     (is (not (str/includes? text "render failed"))
                         "the assembled prompt has no render-failed section"))))
               ;; (c) a broken tile (a symbol that resolves nowhere) must
               ;; degrade to a CLEAR, actionable message — never a stack,
               ;; never a malli keyword — and name the broken fn.
               (.then (fn [_]
                        (db/transact!
                          {:seon.db/tx-data
                           [{:seon.db/ref [:seon.agent/id "AGTctxtile00p1"]
                             :seon.render.live-tile/content
                             'my.broken/does-not-exist}]})))
               (.then
                 (fn [_]
                   (let [out (str (ctx-live-tile/live-tile-section
                                    {:seon.db/db    @db/*conn*
                                     :seon.agent/id "AGTctxtile00p1"}))]
                     (is (not (str/includes? out "⚠"))
                         "broken tile: no bare ⚠ placeholder")
                     (is (not (str/includes? out "malli"))
                         "broken tile: no swallowed malli code")
                     (is (str/includes? out "my.broken/does-not-exist")
                         "broken tile: the agent is told WHICH fn is wired")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest system-text-has-no-bare-margin-prose
  ;; system-text reads as eval'able Clojure by MIXING single-`;` prose
  ;; comments with real, indented COMMON-DB-OPS code examples (register!/
  ;; transact!/query) — it is NOT all comments. The invariant: no BARE
  ;; prose at the margin. Every column-0 non-blank line is a `;` comment
  ;; (prose) or a code form; multi-line code bodies are indented. De-pinned
  ;; from any teaching's exact wording (that prose is a refactoring surface).
  (let [lines (str/split-lines ctx/system-text)]
    (is (seq lines) "system-text is non-empty")
    (is (every? #(or (str/blank? %)
                     (re-find #"^\s" %)         ; indented code/continuation
                     (str/starts-with? % ";")   ; margin prose comment
                     (re-find #"^[(\[{]" %))     ; a code form at the margin
                lines)
        "no bare margin prose — every line is blank, indented, a `;` comment, or a code form")))

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
                      ;; The `;; ── your entity ──` header was REMOVED
                      ;; (keystone): the section renderer's bracket demarcates
                      ;; the section now.
                      (is (some #{:namespaces} sections)
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
                  ;; block in the STABLE half (a non-whitelisted framework ns
                  ;; would be dropped entirely).
                  (.then (fn [_] (transact-full-ns! "my.client" "(def x 1)")))
                  (.then
                    (fn [_]
                      (let [a1 (assemble "AGTctxtest00d1")
                            a2 (assemble "AGTctxtest00d1")]
                        (reset! !first a1)
                        (is (= (:seon.render/stable-text a1)
                               (:seon.render/stable-text a2))
                            "same db value → byte-identical stable blocks")
                        (is (not (str/blank? (:seon.render/stable-text a1)))
                            "stable block is non-blank (system + namespaces)")
                        (is (str/includes? (:seon.render/stable-text a1)
                                           "my.client")
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
                  ;; volatile-only change: a NEW TURN ROW under a fresh run —
                  ;; transcript/turns are volatile sections.
                  (.then (fn [_] (run/open-run! {:seon.agent/id "AGTctxtest00d1"
                                                 :seon.agent.run/trigger :message})))
                  (.then (fn [opened]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:seon.agent.turn/id (db/new-id!)
                                :seon.agent.turn/at (js/Date.)
                                :seon.agent.turn/status :running
                                :seon.agent.turn/prompt-chars 1
                                :seon.agent.turn/run
                                [:seon.agent.run/id (:seon.agent.run/id opened)]}]})))
                  (.then
                    (fn [_]
                      (let [after (assemble "AGTctxtest00d1")]
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
                                  '[{:seon.agent/sections [*]}]
                                  :seon.db/ref [:seon.agent/id "AGTctxtest00s1"]})
                          raw-tile (some #(when (= :tile (:seon.ctx/name %)) %)
                                         (:seon.agent/sections raw))]
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
                      ;; The section renderer's bracket demarcates the
                      ;; section; the body is header-less. ONE line per kind:
                      ;; the kind name is the line label, written ONCE, then
                      ;; bare attr-name count pairs. Anchor on the kind NAME,
                      ;; not the comment-prefix glyph (format is not pinned).
                      (is (str/includes? txt "my.workout: ")
                          "kind is the line label (namespace written once)")
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
                      (let [wline (first (filter #(str/includes? % "my.workout: ")
                                                 lines))]
                        (is (some? wline) "the my.workout kind line is present")
                        (is (not (str/includes? wline ":my.workout/date"))
                            "attr namespace prefix is stripped from the pairs")
                        (is (not (str/includes? wline "my.workout/date"))
                            "no qualified attr name leaks into the pairs")
                        ;; low-card keyword attr shows DISTINCT members inline
                        ;; as an ILLUSTRATIVE SAMPLE — anchor on the member
                        ;; VALUES present (the behavior), not the decorative
                        ;; «…» delimiter glyphs (a render surface).
                        (is (and (str/includes? wline ":lift")
                                 (str/includes? wline ":run"))
                            "low-cardinality categorical values render inline as a sample"))
                      ;; one-line-per-kind: exactly ONE body line mentions the kind.
                      (is (= 1 (count (filter #(str/includes? % "my.workout: ")
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
      ;; The `;; ── relevant context ──` header was REMOVED (keystone): the
      ;; section renderer's bracket demarcates the section now.
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
          "an entity-less hit renders a header-only <unknown> block"))))

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
                      ;; byte-identical across two assemblies (the section
                      ;; is not pulling query-dependent content into the
                      ;; prompt when off). The byte-stability contract is the
                      ;; CACHEABLE PREFIX (`stable-text`), NOT the full prompt:
                      ;; the volatile tail's readline carries the ONE
                      ;; legitimate live `now` (current-time line, below the
                      ;; cache breakpoint), which ticks between two calls that
                      ;; cross a second boundary — by design (context-render
                      ;; "Time and the as-of cache-diff"). Asserting the full
                      ;; text was a latent flake; the prefix is the contract.
                      (is (= (:seon.render/stable-text r1)
                             (:seon.render/stable-text r2))
                          "OFF-path cacheable prefix is byte-identical")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; THE single render path — prompt == view, byte-identical by
;; construction. The model's prompt (the loop's `render-prompt`) and the
;; human inspector's context pane (`ctx-preview`) both route through the
;; ONE producer `seon.ctx/render-context` over the SAME unfiltered db, so
;; the `:ai` side is byte-identical by construction. Asserted THROUGH the
;; real fns — never a hand-built ctx string (the trap that let the old
;; tests lie). The only per-render-moment difference is the single live
;; `now` in the transcript readline; normalize that one line away.
;; ------------------------------------------------------------

(defn- strip-readline-now
  "Normalize the ONE wall-clock line in a rendered context — the transcript
   readline status line (`; <ns> · turn N · loop K/cap · <state> · <now> ·
   agent <id>`), the only render output that depends on `now` rather than
   the db (transcript ns docstring). Everything else is a pure fn of the db
   value and must be byte-identical across the prompt + inspector paths."
  [s]
  (str/replace s #"(?m)^;[^\n]* · loop [^\n]*$" "; <READLINE NOW NORMALIZED>"))

(deftest prompt-and-inspector-are-byte-identical
  ;; THE headline property. `render-context` is the SINGLE producer; the
  ;; loop's `render-prompt` and the inspector's `ctx-preview` both call it
  ;; over the SAME `@*conn*`. Prove: (1) render-prompt IS render-context;
  ;; (2) the inspector's full prompt text ENDS WITH the exact prompt bytes
  ;; (system + boundary + context, the context byte-identical); (3) every
  ;; per-section `:ai` twin appears verbatim in the prompt (one render, two
  ;; consumers); (4) derived-never-stored — rendering writes NO datoms.
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTbyteid00001"})
                (.then (fn [_] (transact-full-ns! "my.client" "(def x 1)")))
                (.then
                  (fn [_]
                    (let [id      "AGTbyteid00001"
                          loop-txt (strip-readline-now (turn/render-prompt id))
                          prod-txt (strip-readline-now
                                     (ctx/render-context {:seon.agent/id id}))
                          preview  (inspect/ctx-preview {:seon.agent/id id})
                          full     (strip-readline-now (:seon.render/text preview))]
                      (is (pos? (count prod-txt)) "the prompt is non-empty")
                      (is (= loop-txt prod-txt)
                          "render-prompt IS render-context (the loop routes through the one producer)")
                      (is (str/ends-with? full prod-txt)
                          "inspector context pane is byte-identical to the prompt (full = system + boundary + the EXACT context bytes)")
                      (doseq [{nm  :seon.ctx/name
                               txt :seon.render/text} (:seon.render/section-texts preview)
                              :when (not= nm :system)]
                        (is (str/includes? prod-txt (strip-readline-now txt))
                            (str "section " nm " :ai twin appears verbatim in the prompt")))
                      (let [before (count (d/datoms @db/*conn* :eavt))]
                        (turn/render-prompt id)
                        (inspect/ctx-preview {:seon.agent/id id})
                        (ctx/render-context {:seon.agent/id id})
                        (is (= before (count (d/datoms @db/*conn* :eavt)))
                            "rendering wrote NO datoms — derived, never stored"))
                      (is (not (str/includes? prod-txt "malli"))
                          "no swallowed malli code leaks into the prompt")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; file-section — the GENERIC markdown-file → context-section UTILITY
;; folded into seon.ctx. The mechanism, not any file's prose:
;;   - a PRESENT file → a renderable section (both views: ai = `;;`
;;     markdown, html = markdown hiccup);
;;   - an ABSENT file → NO section (nil — NO fallback);
;;   - it is GENERIC — any path, not soul/agents-specific.
;; File reads hit cwd = repo root; the present-file cases write a temp
;; file under tmp/ (no dependency on any particular repo file's wording).
;; ------------------------------------------------------------

(def ^:private fs-tmp-rel "tmp/seon-ctx-file-section-test.md")
(def ^:private fs-absent-rel "tmp/seon-ctx-file-section-DOES-NOT-EXIST.md")
(def ^:private fs-fixture-text "# Heading\n\nA paragraph with `(some code)` inside.\n")

(defn- fs-abs [rel] (str (.cwd js/process) "/" rel))

(defn- write-fs-fixture! []
  (let [fs (js/require "fs")]
    (.mkdirSync fs (fs-abs "tmp") #js {:recursive true})
    (.writeFileSync fs (fs-abs fs-tmp-rel) fs-fixture-text "utf8")))

(defn- rm-fs-fixture! []
  (try (.unlinkSync (js/require "fs") (fs-abs fs-tmp-rel)) (catch :default _ nil)))

(deftest file-section-present-file-yields-section-both-views
  (write-fs-fixture!)
  (try
    (let [sect (ctx/file-section {:seon.ctx/file-path fs-tmp-rel
                                  :seon.ctx/name :fixture
                                  :seon.ctx/priority 5})]
      (is (map? sect) "a present file → a section map")
      (is (= :fixture (:seon.ctx/name sect)))
      (is (= 5 (:seon.ctx/priority sect)))
      (is (= fs-tmp-rel (:seon.ctx/file-path sect)))
      (is (symbol? (:seon.render/ai sect)) "ai slot is a symbol (fresh read each render)")
      (is (symbol? (:seon.render/html sect)) "html slot is a symbol")
      ;; AI view — the file rendered as reader-valid `;` markdown.
      (let [ai-txt (render/render :seon.render/ai {} sect)]
        (is (string? ai-txt))
        (is (str/includes? ai-txt "# Heading")
            "the file's markdown content is rendered (content, not the comment glyph)")
        (is (every? #(or (str/blank? %) (str/starts-with? % ";"))
                    (str/split-lines ai-txt))
            "every line is reader-valid (a comment) — keeps the prompt valid source"))
      ;; HTML view — markdown hiccup.
      (let [html (render/render :seon.render/html {} sect)]
        (is (vector? html) "html view is hiccup")
        (is (= :div (first html)))))
    (finally (rm-fs-fixture!))))

(deftest file-section-absent-file-yields-no-section-no-fallback
  (is (nil? (ctx/file-section {:seon.ctx/file-path fs-absent-rel
                               :seon.ctx/name :missing
                               :seon.ctx/priority 5}))
      "an absent file → nil → no section (NO fallback)"))

(deftest file-section-is-generic-any-path
  ;; The SAME mechanism produces a section for an unrelated path/name —
  ;; nothing soul-specific is hardcoded.
  (write-fs-fixture!)
  (try
    (let [a (ctx/file-section {:seon.ctx/file-path fs-tmp-rel
                               :seon.ctx/name :alpha :seon.ctx/priority 1})
          b (ctx/file-section {:seon.ctx/file-path fs-tmp-rel
                               :seon.ctx/name :beta :seon.ctx/priority 9})]
      (is (= :alpha (:seon.ctx/name a)))
      (is (= :beta (:seon.ctx/name b)))
      (is (= (:seon.render/ai a) (:seon.render/ai b))
          "same generic render fn regardless of name/priority"))
    (finally (rm-fs-fixture!))))

;; ------------------------------------------------------------
;; The system-message DECOUPLING contract (moved here from the deleted
;; my.soul-test): the LLM `system` role message is the HARDCODED
;; system-specific mechanics (seon.ctx/system-text), NOT the soul, NOT a
;; file, NO fallback; the identity files (SOUL.md / AGENTS.md) ride the
;; user-message context as file-sections; identity-files-text reads them
;; live (used by the teachings validator).
;; ------------------------------------------------------------

(deftest identity-files-text-reads-files-live
  ;; The identity is the LIVE text of the on-disk identity files — no
  ;; conn, no store, no seed. We pin the MECHANISM (files read, joined),
  ;; not any wording.
  (let [text (ctx/identity-files-text)]
    (is (string? text) "identity-files-text returns a string")))

(deftest system-message-is-hardcoded-mechanics-not-the-soul
  ;; THE decoupling: the LLM system message is the hardcoded mechanics.
  (is (= ctx/system-text (llm/effective-system-prompt {}))
      "system message = the hardcoded seon mechanics (seon.ctx/system-text)")
  (is (= ctx/system-text (llm/effective-system-prompt {:seon.ai/system-prompt nil}))
      "no override → still the hardcoded mechanics (no fallback const)")
  (is (= "OVERRIDE" (llm/effective-system-prompt {:seon.ai/system-prompt "OVERRIDE"}))
      "an explicit override still wins")
  ;; The system message is NOT the identity-file text (decoupled).
  (when (not (str/blank? (ctx/identity-files-text)))
    (is (not= (ctx/identity-files-text) (llm/effective-system-prompt {}))
        "the system message is NOT the identity-file text"))
  ;; No dead fallback const survives.
  (is (not (contains? (ns-publics 'seon.ai) 'fallback-system-prompt))
      "fallback-system-prompt is DELETED — no fallback path"))

(deftest llm-call-system-message-is-the-hardcoded-mechanics
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; The adapter's system message IS the hardcoded mechanics —
            ;; NOT the live identity text, NOT a fallback.
            (let [body (openai/request-params {:seon.ai/ctx "hi"})
                  sys  (-> body :messages first :content)]
              (is (= sys ctx/system-text)
                  "the system message sent to the API is the hardcoded mechanics")
              (is (= "OVERRIDE"
                     (-> (openai/request-params {:seon.ai/ctx "hi"
                                                 :seon.ai/system-prompt "OVERRIDE"})
                         :messages first :content))
                  "an explicit :seon.ai/system-prompt still wins"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
