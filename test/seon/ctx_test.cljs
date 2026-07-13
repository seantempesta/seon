(ns seon.ctx-test
  "Contract tests for `seon.agent.ctx` — the ONE composer.

   Pins: the ONE namespace-selection rule (included-ns? — EVERY indexed
   :seon.ns row minus *.internal and *-test, no prefix allow-list) and
   the full-source depth rule; the `;; ── namespace x ──` blocks
   (internal never renders, an agent-authored ns appears with NO config
   change, downstream code renders with NO config, recency =
   most-recently-modified LAST with a byte-identical prefix above the
   moved block); the `:seon.agent/purpose` entity seed;
   merge/override-by-name semantics; the render guard; the
   per-agent section budget; and the mixed-:or slot storage roundtrip.

   All on a FRESH :memory conn seeded like the pod boots — never the
   live agent conn."
  (:require
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop :include-macros true]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.debug :as agent-debug]
    [seon.agent.run :as run]
    [seon.agent.turn :as turn]
    [seon.ai :as llm]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.ai.openai-compat :as openai]
    [seon.analyzer-info :as ai]
    [seon.client :as client]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.canvas :as ctx-canvas]
    [seon.agent.ctx.namespaces :as ctx-namespaces]
    [seon.agent.ctx.transcript :as transcript]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.render :as render]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]
    [seon.test-seed :as test-seed]))

;; This ns asserts STRICT-dial behavior (canvas-block-stable-on-composer-
;; input's "no bare ⚠" contract holds because under SEON_RENDER_STRICT=1 a
;; broken tile yields the legible fail-loud message, not the graceful
;; ⚠ CANVAS-BROKEN guard). `bin/test-cljs` exports the dial, but a bare
;; `node out/test/test.js --test=seon.ctx-test` inherits the ambient env —
;; a test asserting dial-dependent behavior must SET the dial itself
;; (hermetic fixtures or flaky truth). Pin ON for the ns; restore the
;; caller's value after (process-global env, async-safe — a scoped
;; with-redefs would restore before an async body runs).
(defonce ^:private prior-strict-env
  (atom nil))

(use-fixtures :once
  {:before (fn []
             (reset! prior-strict-env
                     (.. js/globalThis -process -env -SEON_RENDER_STRICT))
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT) "1"))
   :after  (fn []
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT)
                   (or @prior-strict-env "")))})

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema."
  []
  (-> (client/open-agent-conn!)
      ;; the my.* slice of the boot index — SCI bounding is fail-loud, so the
      ;; default ctx blocks' my.* render fns need their stored source rows to
      ;; render BOUNDED here. db/transact! (not raw d/transact!) so any
      ;; :seon.fn/* attr missing from the bootstrap set auto-installs.
      (.then (fn [conn]
               (-> (db/transact! {:seon.db/conn    conn
                                  :seon.db/tx-data (test-seed/my-core-rows)})
                   (.then (fn [_] conn)))))))

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

(defonce ^:private !debug-ai-render-count (atom 0))

(defn counted-debug-ai
  "Test renderer used to prove debug prompt assembly does not rerun AI blocks."
  {:malli/schema [:=> [:cat :map] :string]}
  [_]
  (swap! !debug-ai-render-count inc)
  "test-rendered-ai-block")

(defn- allocate-turn!
  "Allocate and commit one turn fixture."
  [conn turn]
  (db.id/allocate!
    {::db.id/allocations
     [{::db.id/key ::turn
       ::db.id/identity-attr :seon.agent.turn/id}]
     ::db.id/transaction-builder
     (fn [ids]
       {:seon.db/tx-data
        [(assoc turn :seon.agent.turn/id (::turn ids))]})
     :seon.db/conn conn}))

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
  ;; RULE (test siblings ride along via the `-test` strip) PLUS the seon.*
  ;; nses the config policy lists in `:seon.config/always`. The policy CONTENTS
  ;; are not mirrored here (that drifts every prune) — derive the expected set
  ;; from the source of truth so the RULE is tested, not a hand-copy.
  ;; my.* INCLUDING .internal: hidden keeps it out of the PROMPT
  ;; (included-ns? above), but the boot indexer still stores its REAL
  ;; source — the SCI cage rebuilds a my.* render fn's require aliases
  ;; from :seon.ns/source, and a stub would strand the fn UNBOUNDED
  ;; (the my.plan.internal/plan-block defect).
  (doseq [n ["my.kb" "my.kb.shared" "my.notes" "my.notes-test"
             "my.foo.internal"]]
    (is (true? (ctx-namespaces/full-source-ns? n)) (str n " is full-source")))
  (doseq [kw    (filter #(str/starts-with? (name %) "seon.")
                        (:seon.config/always (config/namespaces-policy)))
          n     [(name kw) (str (name kw) "-test")]]
    (is (true? (ctx-namespaces/full-source-ns? n))
        (str n " (an :seon.config/always seon.* ns / its -test sibling) is full-source")))
  ;; seon.* internals stay stubs — the .internal suffix beats the config
  ;; policy for framework nses (internal-boundary-test pins the same).
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.agent.ctx"
             "seon.warn" "seon.ai" "seon.agent.search" "seon.agent.fs"
             "seon.agent.searcher" "seon.db" "seon.db.internal"]]
    (is (false? (ctx-namespaces/full-source-ns? n)) (str n " is NOT full-source"))))

;; ------------------------------------------------------------
;; namespaces-block — tags, hiding, reconstitution, recency.
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

;; The block-level SELECTION model (FULL = current ns + ::full-source pins;
;; COMPACT = the current ns's requires; DROPPED = everything else) is covered
;; by seon.agent.ctx.namespaces-test. Here we keep only the current-ns
;; workspace-stub promise, the *.internal/*-test structural exclusion, and the
;; stable/volatile split.

(deftest namespaces-block-drops-unreachable-code
  ;; With NO current ns and NO pins/requires, the include set is empty —
  ;; EVERYTHING is DROPPED (indexed + searchable, just not resident). Also
  ;; proves the structural exclusions (*.internal / *-test never render).
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (transact-full-ns! "acme.widget" "(def w 1)")
                (.then (fn [_] (transact-full-ns! "my.kb" "(def k 3)")))
                (.then (fn [_] (transact-full-ns! "seon.client" "(def c 2)")))
                (.then (fn [_] (transact-ns-row! "acme.widget.internal")))
                (.then (fn [_] (transact-ns-row! "acme.widget-test")))
                (.then
                  (fn [_]
                    ;; No :seon.agent/id → no current ns → include set empty.
                    (let [txt (ctx-namespaces/namespaces-block {:seon.db/db @db/*conn*})]
                      (is (not (str/includes? txt "acme.widget"))
                          "a non-current, non-required, non-pinned ns is DROPPED")
                      (is (not (str/includes? txt "my.kb"))
                          "my.* is NOT auto-pinned — dropped when unreachable")
                      (is (not (str/includes? txt "seon.client"))
                          "a framework ns is dropped")
                      (is (not (str/includes? txt "acme.widget.internal"))
                          "*.internal never renders")
                      (is (not (str/includes? txt "acme.widget-test"))
                          "*-test never renders")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest cur-ns-always-renders-even-when-empty
  ;; The agent's CURRENT ns ALWAYS appears — even a brand-new home ns with no
  ;; source/fns keeps the "your own namespace renders" promise (a workspace
  ;; stub), never a misleading "not in db" note. Behavior only: the exact
  ;; require/refer form of the stub is NOT pinned (it changes as the toolkit
  ;; does). Also exercises the symbol→keyword cur-ns normalization (a fresh
  ;; agent's current-ns falls back to the home-ns symbol).
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact! {:seon.db/tx-data [{:seon.ns/name :my.agent.wtest}]})
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-block
                                {:seon.db/db @db/*conn* :seon.agent/id "wtest"})]
                      (is (str/includes? txt ";;; ┌─ namespace my.agent.wtest ─")
                          "the empty current ns is present (bracketed)")
                      (is (str/includes? txt ";;; └─ end namespace my.agent.wtest ─")
                          "and demarcated with a matching end bracket")
                      (is (not (str/includes? txt "not in db"))
                          "no misleading 'not in db' note for the indexed home ns")))))))
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
        nses   (set (map :seon.analyzer-info/ns new))
        syms   (set (map :seon.analyzer-info/sym new))]
    (is (not (contains? nses 'result))
        "the reserved result ns must not produce a new-def entry")
    (is (not (contains? syms 'OKf))
        "the synthetic result var must be skipped")
    (is (contains? syms 'real-fn)
        "a genuine agent-authored def is still teed")))

;; ------------------------------------------------------------
;; Composer: purpose-as-entity-data, merge, functions.
;; ------------------------------------------------------------

(defn- assemble
  "The assembled context as a map, derived from the keystone ONE-render
   (`context-root` + `rendered-context-blocks`) — the shape the old
   `assemble-context` returned, rebuilt from the new system so these tests
   keep asserting against the agent's real context."
  [id]
  (let [ctx   {:seon.db/db @db/*conn* :seon.agent/id id}
        root  (ctx/context-root ctx)
        text  (or (render/render :seon.render/ai ctx root) "")
        split (ctx/split-context text)
        blocks (ctx/rendered-context-blocks ctx #{:ai :html})
        section-texts (filterv #(contains? % :seon.render/text) blocks)
        section-html  (filterv #(contains? % :seon.render/hiccup) blocks)]
    {:seon.render/text           text
     :seon.render/stable-text    (:seon.render/stable-text split)
     :seon.render/volatile-text  (:seon.render/volatile-text split)
     ;; LAYOUT PROVENANCE — every child section name in render order
     ;; (including ones that rendered blank this turn), the same shape the
     ;; old assemble-context's :seon.render/sections carried.
     :seon.render/sections       (mapv :seon.agent.ctx/name (:seon.agent.ctx/children root))
     :seon.render/section-texts  section-texts
     :seon.render/section-html   section-html
     :seon.render/token-estimate (tokens/estimate text)}))

(defn- section-text
  [id nm]
  (some #(when (= nm (:seon.agent.ctx/name %)) (:seon.render/text %))
        (:seon.render/section-texts (assemble id))))

(deftest canvas-block-stable-on-composer-input
  ;; REGRESSION GUARD (canvas-nil-entity-render-failed): the composer
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
                   (let [out (str (ctx-canvas/canvas-block
                                    {:seon.db/db    @db/*conn*
                                     :seon.agent/id "AGTctxtile00p1"}))]
                     (is (seq out) "section renders content, never blank")
                     (is (not (str/includes? out "⚠"))
                         "no bare ⚠ render-failed placeholder")
                     (is (not (str/includes? out "malli"))
                         "no swallowed malli code in the agent's context"))
                   ;; (b) the REAL prompt path (render-context-ai, NOT the
                   ;; debug view's rendered-context-blocks) must also be render-failure-free.
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
                             :seon.render.canvas/content
                             'my.broken/does-not-exist}]})))
               (.then
                 (fn [_]
                   (let [out (str (ctx-canvas/canvas-block
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

(defn- agent-purpose
  "The stored `:seon.agent/purpose` attr value for `id` (entity data, not
   a context surface)."
  [id]
  (:seon.agent/purpose
   (db/pull {:seon.db/pull-pattern '[:seon.agent/purpose]
             :seon.db/ref [:seon.agent/id id]})))

(deftest purpose-entity-and-functions
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00p1"
                                :seon.agent/purpose "watch the ledger"})
                (.then
                  (fn [_]
                    (let [{:seon.render/keys [sections]} (assemble "AGTctxtest00p1")]
                      ;; the stated purpose is stored as ENTITY DATA on the
                      ;; attr (the welcome tile reads it; no context section
                      ;; renders it anymore).
                      (is (= "watch the ledger" (agent-purpose "AGTctxtest00p1"))
                          "create! stores the stated purpose on the entity attr")
                      (is (some #{:namespaces} sections)
                          "default blocks seed-copied in")
                      (is (some #{:transcript} sections))
                      (is (not-any? #{:purpose} sections)
                          "the :purpose seed section is dead")
                      (is (not-any? #{:your-sections} sections)
                          "the :your-sections seed section is dead"))))
                ;; set-purpose! writes the entity attr.
                (.then (fn [_]
                         (db/with-agent "AGTctxtest00p1"
                           (fn []
                             (agent/set-purpose!
                               {:seon.render/ai "guard the books"})))))
                (.then
                  (fn [_]
                    (is (= "guard the books" (agent-purpose "AGTctxtest00p1"))
                        "set-purpose! writes the purpose attr")))
                ;; create! again = resume — must NOT overwrite purpose.
                (.then (fn [_] (agent/create! {:seon.agent/id "AGTctxtest00p1"})))
                (.then
                  (fn [_]
                    (is (= "guard the books" (agent-purpose "AGTctxtest00p1"))
                        "resume (re-create!) keeps the agent's own purpose")))
                ;; install! upsert-by-name within the agent's scope.
                (.then (fn [_]
                         (db/with-agent "AGTctxtest00p1"
                           (fn ^:async []
                             (ctx/install!
                               {:seon.agent.ctx/name :doctrine
                                :seon.agent.ctx/priority 15
                                :seon.render/ai "Always check twice."})))))
                (.then (fn [res]
                         (is (true? (:seon.agent.ctx/ok? res))
                             "install! success envelope")
                         (db/with-agent "AGTctxtest00p1"
                           (fn ^:async []
                             (ctx/install!
                               {:seon.agent.ctx/name :doctrine
                                :seon.agent.ctx/priority 16
                                :seon.render/ai "Always check three times."})))))
                (.then
                  (fn [_]
                    (let [secs (ctx/ctx-entities {:seon.agent/id "AGTctxtest00p1"})
                          doctrines (filter #(= :doctrine (:seon.agent.ctx/name %))
                                            secs)]
                      (is (= 1 (count doctrines))
                          "re-installing a name replaces — upsert-by-name")
                      (is (= "Always check three times."
                             (:seon.render/ai (first doctrines)))
                          "slot stored + decoded as the verbatim string"))))
                (.then (fn [_]
                         (db/with-agent "AGTctxtest00p1"
                           (fn ^:async [] (ctx/remove! :doctrine)))))
                (.then (fn [res]
                         (is (true? (:seon.agent.ctx/ok? res))
                             "remove! success envelope")
                         (is (nil? (section-text "AGTctxtest00p1" :doctrine))
                             "removed block vanishes from the render"))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest render-guard
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtest00g1"})
                (.then (fn [_]
                         (db/with-agent "AGTctxtest00g1"
                           (fn ^:async []
                             (ctx/install!
                               {:seon.agent.ctx/name :broken
                                :seon.agent.ctx/priority 14
                                :seon.render/ai 'my.nowhere/missing-fn})))))
                (.then
                  (fn [_]
                    (let [{:seon.render/keys [text sections]} (assemble "AGTctxtest00g1")]
                      (is (str/includes? text "[broken] render failed:")
                          "broken symbol → inline error line")
                      (is (some #{:transcript} sections)
                          "assembly continues past the broken block")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; Prompt-bloat guard: an html-only block (a human-facing widget — the
;; canvas/canvas, an acme dashboard tile) has nothing to say to the
;; agent, so it contributes NO prompt section — no self-demarcating
;; bracket, no generic data-dump stub. The inverse of the html view's
;; "ai-only block contributes no tile" rule.
;; ------------------------------------------------------------

(deftest html-only-block-omitted-from-prompt
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxtesth001"})
                (.then (fn [_]
                         (db/with-agent "AGTctxtesth001"
                           (fn ^:async []
                             (ctx/install!
                               [{:seon.agent.ctx/name :widget-only
                                 :seon.agent.ctx/priority 13
                                 :seon.render/html [:div "human-only widget"]}
                                {:seon.agent.ctx/name :has-ai
                                 :seon.agent.ctx/priority 14
                                 :seon.render/ai "; real ai content"}])))))
                (.then
                  (fn [_]
                    (let [{:seon.render/keys [text section-texts]}
                          (assemble "AGTctxtesth001")
                          names (set (map :seon.agent.ctx/name section-texts))]
                      (is (not (contains? names :widget-only))
                          "an html-only block contributes NO prompt section")
                      (is (not (str/includes? text "widget-only"))
                          "…no empty bracket / data-dump stub for it leaks into the prompt")
                      (is (contains? names :has-ai)
                          "a sibling block that DOES carry an ai render is present")
                      (is (str/includes? text "real ai content")
                          "…with its ai content intact")))))))
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
                  ;; The agent's CURRENT ns always renders (its workspace stub)
                  ;; as a `;;; ┌─ namespace x ─` block in the namespaces section,
                  ;; which lives in the STABLE half.
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
                                           "my.agent.AGTctxtest00d1")
                            "the namespaces body (current ns) lives in the STABLE half")
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
                           (allocate-turn!
                             db/*conn*
                             {:seon.agent.turn/at (js/Date.)
                              :seon.agent.turn/status :running
                              :seon.agent.turn/prompt-chars 1
                              :seon.agent.turn/run
                              [:seon.agent.run/id (:seon.agent.run/id opened)]})))
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
                         (db/with-agent "AGTctxtest00s1"
                           (fn ^:async []
                             (ctx/install!
                               {:seon.agent.ctx/name :tile
                                :seon.agent.ctx/priority 30
                                :seon.render/ai 'my.x/view-section
                                :seon.render/html [:div "static badge"]})))))
                (.then
                  (fn [_]
                    (let [secs (ctx/ctx-entities {:seon.agent/id "AGTctxtest00s1"})
                          tile (some #(when (= :tile (:seon.agent.ctx/name %)) %)
                                     secs)
                          raw  (db/pull
                                 {:seon.db/pull-pattern
                                  '[{:seon.agent/ctx [*]}]
                                  :seon.db/ref [:seon.agent/id "AGTctxtest00s1"]})
                          raw-tile (some #(when (= :tile (:seon.agent.ctx/name %)) %)
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

(deftest assembly-stable-prefix-is-deterministic
  ;; THE CACHE CONTRACT. Assembling the same agent's context twice yields a
  ;; byte-identical STABLE PREFIX — no section pulls query-dependent or
  ;; wall-clock content above the cache boundary. (Formerly also pinned the
  ;; retired `:relevant-source` block's default-OFF path; that block +
  ;; `seon.embed.stash` were deleted 2026-07-12.)
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTctxrel0001p"})
                (.then
                  (fn [_]
                    (let [r1 (assemble "AGTctxrel0001p")
                          r2 (assemble "AGTctxrel0001p")]
                      ;; byte-identical across two assemblies (no section
                      ;; pulls query-dependent content into the prompt).
                      ;; The byte-stability contract is the
                      ;; CACHEABLE PREFIX (`stable-text`), NOT the full prompt:
                      ;; the volatile tail's readline carries the ONE
                      ;; legitimate live `now` (current-time line, below the
                      ;; cache breakpoint), which ticks between two calls that
                      ;; cross a second boundary — by design (context-render
                      ;; "Time and the as-of cache-diff"). Asserting the full
                      ;; text was a latent flake; the prefix is the contract.
                      (is (= (:seon.render/stable-text r1)
                             (:seon.render/stable-text r2))
                          "cacheable prefix is byte-identical across assemblies")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; THE single render path — prompt == view, byte-identical by
;; construction. The model's prompt (the loop's `render-prompt`) and the
;; human debug view's context pane (`ctx-preview`) both route through the
;; ONE producer `seon.agent.ctx/render-context` over the SAME unfiltered db, so
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
   value and must be byte-identical across the prompt + debug view paths."
  [s]
  (str/replace s #"(?m)^;[^\n]* · loop [^\n]*$" "; <READLINE NOW NORMALIZED>"))

(deftest prompt-and-debug-view-are-byte-identical
  ;; THE headline property. `render-context` is the SINGLE producer; the
  ;; loop's `render-prompt` and the debug view's `ctx-preview` both call it
  ;; over the SAME `@*conn*`. Prove: (1) render-prompt IS render-context;
  ;; (2) the debug view's full prompt text ENDS WITH the exact prompt bytes
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
                          preview  (agent-debug/ctx-preview {:seon.agent/id id})
                          full     (strip-readline-now (:seon.render/text preview))]
                      (is (pos? (count prod-txt)) "the prompt is non-empty")
                      (is (= loop-txt prod-txt)
                          "render-prompt IS render-context (the loop routes through the one producer)")
                      (is (str/ends-with? full prod-txt)
                          "debug view context pane is byte-identical to the prompt (full = system + boundary + the EXACT context bytes)")
                      (doseq [{nm  :seon.agent.ctx/name
                               txt :seon.render/text} (:seon.render/section-texts preview)
                              :when (not= nm :system)]
                        (is (str/includes? prod-txt (strip-readline-now txt))
                            (str "section " nm " :ai twin appears verbatim in the prompt")))
                      (let [before (count (d/datoms @db/*conn* :eavt))]
                        (turn/render-prompt id)
                        (agent-debug/ctx-preview {:seon.agent/id id})
                        (ctx/render-context {:seon.agent/id id})
                        (is (= before (count (d/datoms @db/*conn* :eavt)))
                            "rendering wrote NO datoms — derived, never stored"))
                      ;; the word "malli" appears legitimately (the system
                      ;; text teaches :malli/schema; error lines carry the
                      ;; HUMANIZED explain) — the leak signature is raw
                      ;; validator internals, never the taught vocabulary
                      (is (not (str/includes? prod-txt ":malli.core/"))
                          "no raw malli internals leak into the prompt")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest debug-preview-reuses-the-ai-block-render
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTdebugonce01"})
                (.then
                  (fn [_]
                    (db/with-agent
                      "AGTdebugonce01"
                      (fn ^:async []
                        (ctx/install!
                          {:seon.agent.ctx/name :debug-count-probe
                           :seon.agent.ctx/priority 25
                           :seon.render/ai 'seon.ctx-test/counted-debug-ai})))))
                (.then
                  (fn [_]
                    (reset! !debug-ai-render-count 0)
                    (let [preview (agent-debug/ctx-preview
                                    {:seon.agent/id "AGTdebugonce01"})
                          block (some #(when (= :debug-count-probe
                                                (:seon.agent.ctx/name %))
                                         %)
                                      (:seon.agent.ctx/rendered-blocks preview))]
                      (is (= 1 @!debug-ai-render-count)
                          "one debug snapshot invokes each AI producer once")
                      (is (string? (:seon.render/text block))
                          "the rendered block string is retained for the breakdown")
                      (is (str/includes? (:seon.render/text preview)
                                         (:seon.render/text block))
                          "the full prompt is assembled from that retained string")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; file-section — the GENERIC markdown-file → context-section UTILITY
;; folded into seon.agent.ctx. The mechanism, not any file's prose:
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
    (let [sect (ctx/file-block {:seon.agent.ctx/file-path fs-tmp-rel
                                  :seon.agent.ctx/name :fixture
                                  :seon.agent.ctx/priority 5})]
      (is (map? sect) "a present file → a section map")
      (is (= :fixture (:seon.agent.ctx/name sect)))
      (is (= 5 (:seon.agent.ctx/priority sect)))
      (is (= fs-tmp-rel (:seon.agent.ctx/file-path sect)))
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
  (is (nil? (ctx/file-block {:seon.agent.ctx/file-path fs-absent-rel
                               :seon.agent.ctx/name :missing
                               :seon.agent.ctx/priority 5}))
      "an absent file → nil → no section (NO fallback)"))

(deftest file-section-is-generic-any-path
  ;; The SAME mechanism produces a section for an unrelated path/name —
  ;; nothing soul-specific is hardcoded.
  (write-fs-fixture!)
  (try
    (let [a (ctx/file-block {:seon.agent.ctx/file-path fs-tmp-rel
                               :seon.agent.ctx/name :alpha :seon.agent.ctx/priority 1})
          b (ctx/file-block {:seon.agent.ctx/file-path fs-tmp-rel
                               :seon.agent.ctx/name :beta :seon.agent.ctx/priority 9})]
      (is (= :alpha (:seon.agent.ctx/name a)))
      (is (= :beta (:seon.agent.ctx/name b)))
      (is (= (:seon.render/ai a) (:seon.render/ai b))
          "same generic render fn regardless of name/priority"))
    (finally (rm-fs-fixture!))))

;; ------------------------------------------------------------
;; The system-message DECOUPLING contract (moved here from the deleted
;; my.soul-test): the LLM `system` role message is the HARDCODED
;; system-specific mechanics (seon.agent.ctx/system-text), NOT the soul, NOT a
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

(deftest system-message-comes-from-config-state-not-identity-files
  (let [expected (or (:seon.config/system-text (config/config-view))
                     ctx/system-text)]
    (is (= expected (llm/effective-system-prompt {})))
    (is (= expected (llm/effective-system-prompt {:seon.ai/system-prompt nil}))))
  (is (= "OVERRIDE" (llm/effective-system-prompt {:seon.ai/system-prompt "OVERRIDE"}))
      "an explicit override still wins")
  ;; The system message is NOT the identity-file text (decoupled).
  (when (not (str/blank? (ctx/identity-files-text)))
    (is (not= (ctx/identity-files-text) (llm/effective-system-prompt {}))
        "the system message is NOT the identity-file text"))
  ;; No dead fallback const survives.
  (is (not (contains? (ns-publics 'seon.ai) 'fallback-system-prompt))
      "fallback-system-prompt is DELETED — no fallback path"))

(deftest system-message-or-chain-reads-the-config-datom
  ;; agent-ctx Phase 3: request override > the cluster's
  ;; :seon.config/system-text datom (config-through-DB via config-view) >
  ;; the shipped default. The datom is transacted here exactly as the boot
  ;; reconcile seeds it (the :seon.config singleton, identity "cluster").
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data [{:seon.config/id "cluster"
                                      :seon.config/system-text "; minimal prompt"}]})
                (.then
                  (fn [tx]
                    (is (:seon.db/ok? tx) "singleton system-text datom transacts")
                    (is (= "; minimal prompt" (llm/effective-system-prompt {}))
                        "the seeded :seon.config/system-text datom wins over the shipped default")
                    (is (= "OVERRIDE"
                           (llm/effective-system-prompt {:seon.ai/system-prompt "OVERRIDE"}))
                        "the per-request override still wins over the datom"))))))
        (.then (fn [_]
                 ;; The ambient conn is restored, so resolution returns to the
                 ;; ambient config state. Do not pin the prompt's wording.
                 (is (= (or (:seon.config/system-text (config/config-view))
                            ctx/system-text)
                        (llm/effective-system-prompt {})))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest llm-call-system-message-is-the-hardcoded-mechanics
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; The adapter must use the same config-backed resolution path as
            ;; the agent loop; this test deliberately does not pin its wording.
            (let [body     (openai/request-params {:seon.ai/ctx "hi"})
                  sys      (-> body :messages first :content)
                  expected (llm/effective-system-prompt {})]
              (is (= sys expected)
                  "the API adapter and agent loop resolve the same system prompt")
              (is (= "OVERRIDE"
                     (-> (openai/request-params {:seon.ai/ctx "hi"
                                                 :seon.ai/system-prompt "OVERRIDE"})
                         :messages first :content))
                  "an explicit :seon.ai/system-prompt still wins"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;;; ─────────────────────────────────────────────────────────────────────
;;; Block-chain KV cache keys — the Seon half of the prefix-KV-reuse win.
;;; PURE fn (blocks, agent-id) → per-block chain-hash vector. Mirrors vLLM
;;; APC (kv_cache_utils.py hash_block_tokens :577-603 + the chain :703-728,
;;; cache_salt :560-561). No conn, no GPU.
;;; ─────────────────────────────────────────────────────────────────────

(defn- blk
  "A keyable block carrying byte-stable rendered text (name optional)."
  [nm text]
  {:seon.agent.ctx/name nm :seon.render/text text})

(def ^:private static-prefix
  "A shared static head: soul → :namespaces (the cacheable prefix)."
  [(blk :soul "; serve the human")
   (blk :shared-instructions "; the shared manual")
   (blk :skills-catalog "; data-modeling — design a schema")
   (blk :namespaces ";;; ┌─ my.kb ─ … ─ end ─")])

(defn- chain
  [blocks agent-id]
  (-> (ctx/block-chain-keys {:seon.agent.ctx/blocks blocks
                             :seon.agent/id agent-id})
      :seon.agent.ctx/chain-hashes))

(deftest block-chain-keys-identical-sequences-identical-keys
  ;; Invariant 1: identical block sequences + same agent ⇒ identical keys.
  (let [blocks (conj static-prefix (blk :transcript "; turn 1"))
        a      (chain blocks "agent-7")
        b      (chain blocks "agent-7")]
    (is (= a b) "identical (blocks, agent) ⇒ identical key vector")
    (is (= (count blocks) (count a)) "one key per block")
    (is (every? #(re-matches #"[0-9a-f]{64}" %) a)
        "each key is a sha256 hex digest")))

(deftest block-chain-keys-shared-prefix-diverges-at-first-change
  ;; Invariant 2: a shared static prefix shares keys; the chain breaks at
  ;; EXACTLY the first changed block and every key after it differs.
  (let [t1 (chain (into static-prefix [(blk :data-summary "; 3 ledgers")
                                       (blk :transcript "; turn 1")]) "agent-7")
        t2 (chain (into static-prefix [(blk :data-summary "; 3 ledgers")
                                       (blk :transcript "; turn 2 DIFFERENT")]) "agent-7")
        n  (count static-prefix)]
    ;; the 4 static blocks + the unchanged summary (index n) share keys
    (is (= (subvec t1 0 (inc n)) (subvec t2 0 (inc n)))
        "every block up to and including the last unchanged one shares its key")
    ;; the changed :transcript (index n+1) and beyond diverge
    (is (not= (nth t1 (inc n)) (nth t2 (inc n)))
        "the first changed block's key differs — chain breaks here")
    ;; and the shared static head is byte-identical key-for-key
    (is (= (subvec t1 0 n) (subvec t2 0 n))
        "the whole static prefix's keys are reused across turns"))
  ;; A change to the HEAD block busts every downstream key (chain property).
  (let [base   (chain static-prefix "agent-7")
        head'  (chain (assoc static-prefix 0 (blk :soul "; serve DIFFERENT human"))
                      "agent-7")]
    (is (not= (first base) (first head')) "head key changes when head changes")
    (is (every? false? (map = base head'))
        "a head edit cascades — NO downstream key survives")))

(deftest block-chain-keys-salt-scopes-by-agent
  ;; Invariant 3: different :seon.agent/id ⇒ different keys for identical
  ;; blocks (cache_salt rides the head block, scoping the whole chain).
  (let [a (chain static-prefix "agent-A")
        b (chain static-prefix "agent-B")]
    (is (= (count a) (count b)) "same shape")
    (is (every? false? (map = a b))
        "every key differs across agents — salt scopes the whole chain")
    ;; same agent again ⇒ back to identical (salt is the only difference)
    (is (= a (chain static-prefix "agent-A"))
        "same agent ⇒ identical (salt is deterministic, not random)")))

;;; ─────────────────────────────────────────────────────────────────────
;;; Block-chain KV keys — GENERATIVE properties. The three example tests
;;; above pin specific fixtures; these run the SAME four invariants over
;;; randomly generated block-text vectors + agent-ids (100 cases each,
;;; shrinking to the smallest counterexample on failure). PURE fn → no
;;; conn, no async, plain test.check.
;;; ─────────────────────────────────────────────────────────────────────

(def ^:private gen-block-text
  "A byte-stable rendered block text (`;`-prose, may be blank)."
  (gen/fmap #(str "; " %) gen/string-ascii))

(def ^:private gen-block
  (gen/fmap (fn [[nm t]] {:seon.agent.ctx/name nm :seon.render/text t})
            (gen/tuple (gen/elements [:soul :shared-instructions :skills-catalog
                                      :namespaces :data-summary :warnings :transcript])
                       gen-block-text)))

(def ^:private gen-blocks (gen/vector gen-block 1 8))
(def ^:private gen-agent-id (gen/fmap #(str "agent-" %) gen/string-alphanumeric))

(defn- check
  "Run a test.check property `n` times; assert it held, surfacing the shrunk
   counterexample on failure (a falsification IS a real bug in block-chain-keys
   — report it, never weaken the property)."
  [n property]
  (let [{:keys [result shrunk] :as res} (tc/quick-check n property)]
    (is (true? result)
        (str "block-chain-keys property falsified — shrunk: "
             (pr-str (:smallest shrunk)) " | " (pr-str res)))))

;; Invariant 1: identical (blocks, agent) ⇒ identical key vectors; one
;; 64-hex key per block.
(deftest block-chain-keys-prop-deterministic
  (check 100
    (prop/for-all [blocks gen-blocks id gen-agent-id]
      (let [a (chain blocks id)
            b (chain blocks id)]
        (and (= a b)
             (= (count blocks) (count a))
             (every? #(re-matches #"[0-9a-f]{64}" %) a))))))

;; Invariant 2: two vectors sharing a generated PREFIX share exactly that
;; prefix of keys and diverge at the first differing block (and, by the
;; chain property, at every block after it).
(deftest block-chain-keys-prop-shared-prefix
  (check 100
    (prop/for-all [prefix  gen-blocks
                   nb-text gen-block-text
                   tail1   (gen/vector gen-block 0 4)
                   tail2   (gen/vector gen-block 0 4)
                   id      gen-agent-id]
      (let [d   (count prefix)
            nb1 {:seon.agent.ctx/name :divergent :seon.render/text nb-text}
            nb2 {:seon.agent.ctx/name :divergent :seon.render/text (str nb-text "∆")}
            v1  (into (conj prefix nb1) tail1)
            v2  (into (conj prefix nb2) tail2)
            k1  (chain v1 id)
            k2  (chain v2 id)]
        (and (= (subvec k1 0 d) (subvec k2 0 d))         ; shared prefix keys identical
             (not= (nth k1 d) (nth k2 d))                ; diverge at the first changed block
             ;; once a parent differs, every downstream key differs too
             (every? true? (map not= (subvec k1 d) (subvec k2 d))))))))

;; Invariant 3: a different :seon.agent/id ⇒ ALL keys differ (the salt rides
;; the head block and scopes the whole chain).
(deftest block-chain-keys-prop-salt-scopes
  (check 100
    (prop/for-all [blocks gen-blocks
                   id1    gen-agent-id
                   suffix (gen/not-empty gen/string-alphanumeric)]
      (let [id2 (str id1 suffix)                          ; guaranteed distinct id
            k1  (chain blocks id1)
            k2  (chain blocks id2)]
        (and (= (count k1) (count k2))
             (every? true? (map not= k1 k2)))))))

;; Invariant 4: a single edit to block i changes keys i..n and leaves
;; 0..i-1 intact.
(deftest block-chain-keys-prop-single-edit
  (check 100
    (prop/for-all [blocks gen-blocks id gen-agent-id idx gen/nat]
      (let [i       (mod idx (count blocks))
            old     (get-in blocks [i :seon.render/text])
            blocks' (assoc-in blocks [i :seon.render/text] (str old "∆EDIT"))
            k       (chain blocks id)
            k'      (chain blocks' id)]
        (and (= (subvec k 0 i) (subvec k' 0 i))           ; 0..i-1 untouched
             (every? true? (map not= (subvec k i) (subvec k' i)))))))) ; i..n changed

;; ------------------------------------------------------------
;; :seon.render/full? — the no-clip opt-out (#43). A flagged
;; value/block/eval-row renders WHOLE past the cap; unflagged still
;; clips with the loud marker. ONE full?-aware clip gate (clip-or-full)
;; behind every authored-content clip site.
;; ------------------------------------------------------------

(deftest full-flag-bypasses-authored-content-clip
  (let [big (apply str (repeat 6000 "x"))]
    ;; cap-result-body — the citable eval RESULT body
    (let [clipped (ctx/cap-result-body big 2048 "Big")]
      (is (< (count clipped) (count big)) "unflagged → clipped to the cap")
      (is (str/includes? clipped "TRUNCATED") "loud clip marker present")
      (is (str/includes? clipped "result/Big") "the dig handle survives the clip"))
    (let [whole (ctx/cap-result-body big 2048 "Big" true)]
      (is (= big whole) ":seon.render/full? renders the body WHOLE past the cap")
      (is (not (str/includes? whole "TRUNCATED")) "no clip marker when full?"))
    ;; cap-result — echoed source / stdout
    (is (str/includes? (ctx/cap-result big 2048) "TRUNCATED") "unflagged source clips")
    (is (= big (ctx/cap-result big 2048 true)) "full? source renders whole")
    ;; truncate-edn — pr-str'd display
    (is (str/includes? (ctx/truncate-edn big 2048) "TRUNCATED") "unflagged edn clips")
    (is (= (pr-str big) (ctx/truncate-edn big 2048 true)) "full? edn renders whole")))

(deftest eval-row-full-flag-renders-result-whole
  ;; The flag pinned on an eval ROW flows through format-eval-row to the
  ;; result-body clip — the whole value lands in the transcript.
  (let [big     (apply str (repeat 20000 "y"))     ; > result-body-render-cap (16384)
        row     {:seon.eval/id "Ev1" :seon.eval/ok? true
                 :seon.eval/source "(huge)"
                 :seon.eval/result-edn (pr-str big)}
        clipped (ctx/format-eval-row row)
        whole   (ctx/format-eval-row (assoc row :seon.render/full? true))]
    (is (str/includes? clipped "TRUNCATED")
        "a big eval result clips by default")
    (is (not (str/includes? whole "TRUNCATED"))
        ":seon.render/full? on the eval row renders the result WHOLE")
    (is (str/includes? whole big)
        "the full value is present uncut in the row")))

(deftest eval-row-clip-marker-is-tokens-not-chars
  ;; The `(N of M)` handle marker must speak the SAME unit as the inline
  ;; ⟨… tokens⟩ guide — both TOKENS (no mixed units in one row). body-cap /
  ;; full are CHAR budgets converted at the display site via chars->tokens.
  (let [big-edn (str "[" (str/join " " (repeat 300 "\"item-value\"")) "]")
        row     {:seon.eval/source "(get-stuff)" :seon.eval/ok? true
                 :seon.eval/result-edn big-edn :seon.eval/id "tok0000001a"
                 :seon.render/result-body-cap 200}
        out     (ctx/format-eval-row row false)
        handle  (first (filter #(str/includes? % "result/tok0000001a")
                               (str/split-lines out)))]
    (is (str/includes? handle (str "(" (tokens/chars->tokens 200)
                                   " of " (tokens/chars->tokens (count big-edn))
                                   " tokens)"))
        "the handle marker is token-denominated and labeled 'tokens'")
    ;; the OLD char-denominated marker must be gone
    (is (not (str/includes? out (str "(200 of " (count big-edn) ")")))
        "no bare char marker survives")
    ;; and it matches the inline TRUNCATED guide's unit
    (is (str/includes? out (str "of " (tokens/chars->tokens (count big-edn))
                                " tokens"))
        "the inline TRUNCATED guide speaks the same token unit")))

;; ------------------------------------------------------------
;; format-eval-row — the REPL-faithful transcript row (ported 2026-07-02
;; from agent_context_test.cljs.disabled; assertions retargeted to the
;; CURRENT glyphs: `;` prose preamble, `; ⟹ value ; result/<id>` output
;; comment, `; ⟹ ✗ guidance` failures). These behaviors had no live
;; coverage beyond the full?-flag clip path above.
;; ------------------------------------------------------------

(deftest eval-row-repl-faithful-stream
  ;; success: preamble as `;` prose, form verbatim, the value on a BARE
  ;; `⟹ <value> ⟸ result/<id>` line INLINED onto the form (not comment-shaped).
  (let [row (ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/narration "add 1 and 2"})
        lines (str/split-lines row)]
    (is (= "; add 1 and 2" (first lines))
        "narration prose renders as a single-`;` comment line")
    (is (str/includes? row "(+ 1 2)")
        "the form renders verbatim")
    (is (str/includes? row (str "(+ 1 2) " ctx/result-marker " 3 "
                                ctx/result-close " result/sm0000001a"))
        "the value inlines onto the form as `(form) ⟹ <value> ⟸ result/<id>`")
    (is (not (some #(str/starts-with? % "; ⟹") lines))
        "the result is NOT comment-shaped — no `; ⟹` line for a model to mimic")
    (is (not (str/includes? row "=> (+ 1 2)"))
        "no <ns>=> history prompt prefix on the form"))
  ;; prior-session rows render the value WITHOUT the handle (the var
  ;; died with the process) — so no `⟸ result/` close.
  (let [row (ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"}
              true)]
    (is (str/includes? row (str ctx/result-marker " 3")) "prior rows still show the value")
    (is (not (str/includes? row "result/"))
        "prior-session rows carry NO result/<id> handle")
    (is (not (str/includes? row ctx/result-close))
        "no close handle without a live var to point at"))
  ;; failures render a bare `⟹ ✗ <guidance>` line — and no handle (no value).
  (let [row (ctx/format-eval-row
              {:seon.eval/source "(boom)" :seon.eval/ok? false
               :seon.eval/error "kaput" :seon.eval/id "er0000001a"})]
    (is (str/includes? row (str ctx/result-marker " ✗ kaput"))
        "failure rows render the form then a crystal-clear bare `⟹ ✗` line")
    (is (not (some #(str/starts-with? % "; ⟹") (str/split-lines row)))
        "the failure line is not comment-shaped either")
    (is (not (str/includes? row "result/"))
        "a FAILED eval gets NO result/<id> — there is no value to reuse"))
  ;; a comment-only row (blank source) renders just its prose preamble.
  (let [row (ctx/format-eval-row
              {:seon.eval/source "" :seon.eval/ok? true
               :seon.eval/id "cm0000001a"
               :seon.eval/narration "just a trailing thought"})]
    (is (= "; just a trailing thought" row)
        "comment-only row → only the `;` preamble, no form, no value")))

(deftest eval-row-shows-captured-print-output
  (let [row (ctx/format-eval-row
              {:seon.eval/source "(println \"hi\")" :seon.eval/ok? true
               :seon.eval/result-edn "nil" :seon.eval/output "hi\n"
               :seon.eval/id "pr0000001a"})]
    (is (str/includes? row "(println \"hi\")\nhi\n⟹ nil")
        "captured output renders between the form and the bare `⟹` value line,
         REPL-style (stdout present ⇒ result stays on its own line, not inlined)"))
  (let [row (ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "pr0000002b"})]
    (is (str/includes? row "(+ 1 2) ⟹ 3")
        "no output attr → single-line result inlines onto the form")))

(deftest eval-row-clipped-value-annotates-shown-of-full
  ;; a clipped value appends `(N of M tokens)` to the result/<id> handle so
  ;; the agent knows the shown display is a partial view of a live whole
  ;; value. The marker speaks TOKENS (Token Reporting rule) — same unit as
  ;; the inline ⟨… tokens⟩ guide. The result body clips at
  ;; result-body-render-cap (the store ceiling), so the value must exceed
  ;; THAT to clip.
  (let [full (+ ctx/result-body-render-cap 5000)
        huge (apply str (repeat full "z"))
        row  (ctx/format-eval-row
               {:seon.eval/source "(big)" :seon.eval/ok? true
                :seon.eval/result-edn huge :seon.eval/id "cp0000001a"})]
    (is (str/includes? row (str "result/cp0000001a ("
                                (tokens/chars->tokens ctx/result-body-render-cap)
                                " of " (tokens/chars->tokens full) " tokens)"))
        "the handle carries (shown of full tokens) so the clip is unambiguous")
    (is (str/includes? row (str "bind result/cp0000001a for the full value"))
        "the size guide still fires for the clipped scalar")))

;; ------------------------------------------------------------
;; Transcript-render redesign — the bare `⟹ … ⟸` grammar and its
;; single-source glyph constants (the reply-boundary strip detects them).
;; ------------------------------------------------------------

(deftest reserved-glyphs-are-single-source-and-distinct
  ;; the five reserved runtime glyphs, each a distinct one-char constant —
  ;; the emit sites, detector, and lint all reference THESE, never a literal.
  (is (= #{ctx/result-marker ctx/result-close ctx/status-open
           ctx/status-close ctx/prompt}
         ctx/reserved-glyphs)
      "the constant set IS the five glyphs")
  (is (every? #(and (string? %) (= 1 (count %))) ctx/reserved-glyphs)
      "each reserve is a single glyph")
  (is (= 5 (count ctx/reserved-glyphs)) "five distinct reserves"))

(deftest bare-grammar-emits-from-the-constants
  ;; a real result is a BARE `⟹ <value> ⟸ result/<id>` line built from the
  ;; glyph constants — NOT comment-shaped, so a model can't fabricate one by
  ;; writing a `;` comment (the T4 6/24 `; ⟹` mimicry).
  (let [row (ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "bg0000001a"})]
    (is (str/includes? row (str ctx/result-marker " 3 " ctx/result-close
                                " result/bg0000001a"))
        "value + handle emit from the result-open/close constants")
    (is (not (str/includes? row (str "; " ctx/result-marker)))
        "the result line is NOT comment-shaped")))

;; ------------------------------------------------------------
;; repl-mode Phase 1 — the fabrication DETECTOR (`first-result-claim`) and
;; the `:batch` reply-boundary STRIP (`strip-result-claims`). The detector
;; SKIPS matches inside a successfully-parsed form span (so a legit `⟹`
;; string literal / `:=>` malli schema never fires); the strip DELETES the
;; fabricated tails/lines so the clean forms eval and the real rows arrive
;; next turn.
;; ------------------------------------------------------------

(deftest first-result-claim-detects-fabrications-skips-in-form
  ;; a fabricated tail / bare line / commented claim → the offset of the
  ;; first claim; a string literal or `:=>` malli schema → nil (in-form).
  (is (= 8 (ctx/first-result-claim "(+ 1 2) ⟹ 3 ⟸ result/FAKE"))
      "fabricated ⟹ tail after a form is detected at its offset")
  (is (= 0 (ctx/first-result-claim "=> 61 ;; result/LFd"))
      "a bare col-0 fabrication is detected at offset 0")
  (is (= 6 (ctx/first-result-claim "(foo) ;; => 3"))
      "an inline commented claim is detected")
  (is (nil? (ctx/first-result-claim "(println \"⟹\")"))
      "a ⟹ inside a string literal does NOT fire (in a parsed form span)")
  (is (nil? (ctx/first-result-claim
              "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"))
      ":=> inside a :malli/schema vector does NOT fire")
  (is (nil? (ctx/first-result-claim "(+ 1 2)\n(message/user \"hi\")"))
      "a clean multi-form reply has no claim")
  (is (nil? (ctx/first-result-claim (str "the " ctx/prompt " shell prompt")))
      "❯ is never a claim (excluded glyph)")
  ;; a glyph inside a #code heredoc payload sits inside the enclosing form's
  ;; ORIGINAL-coordinate span (parse-forms maps spans back through the
  ;; heredoc rewrite), so the strip can never truncate a heredoc payload.
  (is (nil? (ctx/first-result-claim
              "(fs/replace! {:my.fs/find #code/py <<PY\nprint(1) ⟹ 2\nPY\n})"))
      "a ⟹ inside a #code heredoc payload never fires (in the form span)")
  ;; an in-form glyph must not SHADOW a fabrication after the form on the
  ;; SAME line — the scan resumes at the form span's end, not the match end.
  (is (= 14 (ctx/first-result-claim "(println \"⟹\") ⟹ 99"))
      "a fabricated tail after an in-form glyph on the same line still fires")
  (let [{t :seon.agent.ctx/strip-text n :seon.agent.ctx/strip-count}
        (ctx/strip-result-claims "(println \"⟹\") ⟹ 99")]
    (is (= 1 n) "the shadowed fabrication is stripped")
    (is (str/includes? t "(println \"⟹\")") "the form (and its legit glyph) survives")
    (is (not (str/includes? t "99")) "the fabricated value is gone")))

(deftest strip-result-claims-removes-fabrications-keeps-forms
  ;; `:batch` boundary fix-up: the fabricated tail is spliced out, the form
  ;; to its left survives, and the count is honest.
  (let [{t :seon.agent.ctx/strip-text n :seon.agent.ctx/strip-count}
        (ctx/strip-result-claims "(+ 1 2) ⟹ 3 ⟸ result/FAKE")]
    (is (= 1 n) "one fabrication stripped")
    (is (str/starts-with? t "(+ 1 2)") "the form survives")
    (is (not (str/includes? t "⟹")) "the fabricated value is gone")
    ;; the surviving text parses to exactly the real form, no fabricated tail
    (is (= 1 (count (filter #(= :form (:seon.repl/kind %))
                            (repl-internal/parse-forms t))))))
  ;; multi-form, multi-fabrication → both stripped, both forms kept
  (let [{t :seon.agent.ctx/strip-text n :seon.agent.ctx/strip-count}
        (ctx/strip-result-claims "(+ 1 2) ⟹ 3\n(* 2 3) ⟹ 6")]
    (is (= 2 n) "both fabricated tails stripped")
    (is (and (str/includes? t "(+ 1 2)") (str/includes? t "(* 2 3)"))
        "both forms survive"))
  ;; a standalone bare fabrication line is removed
  (let [{t :seon.agent.ctx/strip-text n :seon.agent.ctx/strip-count}
        (ctx/strip-result-claims "(db/transact! x)\n=> {:ok true} ;; result/AAA\n(message/user \"hi\")")]
    (is (= 1 n))
    (is (not (str/includes? t "{:ok true}")) "the fabricated result line is gone")
    (is (str/includes? t "(db/transact! x)")))
  ;; clean reply is byte-identical, count 0 (idempotent)
  (let [clean "(println \"⟹\")"
        {t :seon.agent.ctx/strip-text n :seon.agent.ctx/strip-count}
        (ctx/strip-result-claims clean)]
    (is (= 0 n) "nothing to strip")
    (is (= clean t) "a clean reply passes through byte-identical")))

(deftest large-value-decay-is-display-only-value-unchanged
  ;; DISPLAY-ONLY central decay: a fixed eval rendered at a SMALLER result-body
  ;; cap (an aged row) clips its DISPLAY but the live value behind result/<id>
  ;; is unchanged — the row still points at the whole value, and a bigger cap
  ;; renders more of the SAME value (never a different one).
  (let [big  (apply str (repeat 8000 "q"))
        row  {:seon.eval/id "dk0000001a" :seon.eval/ok? true
              :seon.eval/source "(big)" :seon.eval/result-edn (pr-str big)}
        aged (ctx/format-eval-row (assoc row :seon.render/result-body-cap 200))
        recent (ctx/format-eval-row (assoc row :seon.render/result-body-cap 16384))]
    (is (< (count aged) (count recent))
        "the aged display is smaller — decay reduces render resolution")
    (is (and (str/includes? aged "result/dk0000001a")
             (str/includes? recent "result/dk0000001a"))
        "both resolutions point at the SAME live value handle")
    (is (str/includes? aged "TRUNCATED")
        "the aged row says its DISPLAY is clipped, the value COMPLETE")))

(deftest aged-eval-row-is-byte-identical-at-a-fixed-cap
  ;; byte-stability law #62: a FIXED eval at a FIXED age (result-body-cap)
  ;; renders byte-identically across renders — the prompt cache prefix holds.
  (let [row {:seon.eval/id "bs0000001a" :seon.eval/ok? true
             :seon.eval/source "(+ 1 2)" :seon.eval/result-edn "3"}
        r1  (ctx/format-eval-row (assoc row :seon.render/result-body-cap 200))
        r2  (ctx/format-eval-row (assoc row :seon.render/result-body-cap 200))]
    (is (= r1 r2) "same eval + same cap ⇒ byte-identical render")))
