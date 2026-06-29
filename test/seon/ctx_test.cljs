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
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop :include-macros true]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.inspect :as inspect]
    [seon.agent.run :as run]
    [seon.agent.turn :as turn]
    [seon.ai :as llm]
    [seon.ai.openai-compat :as openai]
    [seon.analyzer-info :as ai]
    [seon.client :as client]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.findings :as ctx-findings]
    [seon.agent.ctx.inventory :as ctx-inventory]
    [seon.agent.ctx.live-tile :as ctx-live-tile]
    [seon.agent.ctx.namespaces :as ctx-namespaces]
    [seon.agent.ctx.relevant :as ctx-relevant]
    [seon.agent.ctx.transcript :as transcript]
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
  (doseq [n ["seon.client" "seon.eval" "seon.agent" "seon.agent.ctx"
             "seon.warn" "seon.ai" "seon.agent.search" "seon.agent.fs"
             "seon.agent.searcher" "seon.db" "my.foo.internal"]]
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

(deftest namespaces-block-curated-full-only-recency
  ;; CURATED render with SIGNATURE TRIM (#42): the agent's current ns,
  ;; third-party acme.*, the curated seon.* whitelist, and the ONE kept my.*
  ;; worked example (my.kb) render FULL — WHOLE source, UNCLIPPED. Every
  ;; OTHER my.* ns render-trims to its public verb SIGNATURES (name + arglist
  ;; + one-line doc, body elided). Every seon.* framework ns is DROPPED from
  ;; the rendered section entirely — indexed + searchable, just not shown.
  (async done
    (let [!before (atom nil)]
      (-> (with-conn
            (fn [_conn]
              ;; my.agent.a1 (my.*, NOT the kept example → SIGNATURE) with a
              ;; real public defn so the signature has an arglist + doc to show.
              (-> (transact-full-ns! "my.agent.a1" "(defn helper [a] (inc a))")
                  (.then
                    (fn [_]
                      (db/transact!
                        {:seon.db/tx-data
                         [{:seon.fn/sym      "my.agent.a1/helper"
                           :seon.fn/ns       [:seon.ns/name :my.agent.a1]
                           :seon.fn/arglists "([a])"
                           :seon.fn/doc      "Bump a by one.\nMore detail here."
                           :seon.fn/source   "(defn helper [a] (inc a))"}]})))
                  ;; my.kb (the kept canonical worked example → FULL body).
                  (.then (fn [_] (transact-full-ns! "my.kb" "(def k 1)")))
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
                      (let [txt (ctx-namespaces/namespaces-block {:seon.db/db @db/*conn*})]
                        ;; SIGNATURE-trim: a non-kept my.* ns renders its
                        ;; public verb signature (name + arglist + one-line
                        ;; doc) — NOT its full body. Block ORDERING is NOT
                        ;; asserted (priority is numeric + movable).
                        (is (str/includes? txt ";;; ┌─ namespace my.agent.a1 (signatures) ─")
                            "a non-kept my.* ns renders as a (signatures) block")
                        (is (str/includes? txt "(my.agent.a1/helper [a])")
                            "the my.* verb signature (name + arglist) is shown")
                        (is (str/includes? txt "; Bump a by one.")
                            "the one-line docstring rides the signature")
                        (is (not (str/includes? txt "(inc a)"))
                            "the non-kept my.* BODY is elided (signature-trim)")
                        ;; FULL: the kept my.* worked example renders whole.
                        (is (str/includes? txt "(ns my.kb")
                            "the kept my.kb worked example renders")
                        (is (str/includes? txt "(def k 1)")
                            "the kept my.kb body is shown FULL (no clipping)")
                        ;; FULL: third-party acme renders its whole source.
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
                        ;; absent — only my.* gets a signature block, never a
                        ;; dropped seon.* framework ns.
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
                    (let [txt (ctx-namespaces/namespaces-block {:seon.db/db @db/*conn*})]
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
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.ns/name :my.agent.wtest}
                    ;; index the messaging verb ns so the signature surfacing
                    ;; path (verb-signature-whitelist) has a real row to render.
                    {:seon.ns/name :seon.agent.message}
                    {:seon.fn/sym      "seon.agent.message/user"
                     :seon.fn/ns       [:seon.ns/name :seon.agent.message]
                     :seon.fn/arglists "([content])"
                     :seon.fn/doc      "Send a message to your human."
                     :seon.fn/source   "(defn user [content] content)"}]})
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-block
                                {:seon.db/db @db/*conn* :seon.agent/id "wtest"})]
                      ;; The empty home ns renders inside the per-ns
                      ;; begin/end demarcation brackets (the `;;;`
                      ;; runtime-structure convention) — so a truly-empty
                      ;; workspace is clearly framed, not silent poison.
                      (is (str/includes? txt ";;; ┌─ namespace my.agent.wtest ─")
                          "the empty current ns renders inside a begin bracket")
                      (is (str/includes? txt ";;; └─ end namespace my.agent.wtest ─")
                          "and a matching end bracket — the ns is demarcated")
                      (is (not (str/includes? txt "not in db"))
                          "no misleading 'not in db' for the indexed home ns")
                      (is (str/includes? txt "(ns my.agent.wtest")
                          "shows the (ns …) form")
                      ;; NO hidden aliasing: the workspace block shows the REAL
                      ;; canonical home-ns require form (seon.eval/home-ns-form)
                      ;; that setup-agent-ns! installs — WITH aliases/refers —
                      ;; not a bare-name reconstruction. The agent must SEE that
                      ;; `message/user`, `db/transact!`, `wait`, `complete`
                      ;; resolve.
                      (is (str/includes? txt "[seon.agent.message :as message]")
                          "shows seon.agent.message WITH its :as message alias")
                      (is (str/includes? txt "[seon.db :as db]")
                          "shows seon.db WITH its :as db alias")
                      (is (str/includes? txt ":refer [wait complete pause resume terminate]")
                          "shows the refer'd lifecycle verbs")
                      ;; and the messaging verb SIGNATURES are surfaced (arglist,
                      ;; not just the alias) so the verb is discoverable.
                      (is (str/includes? txt "(seon.agent.message/user [content])")
                          "surfaces the message/user signature with its arglist")
                      (is (str/includes? txt "nothing defined here yet")
                          "carries the empty-workspace note")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest spawn-verbs-discoverable-without-dumping-seon-agent
  ;; The spawn verbs (seon.agent/start! + create!) must be DISCOVERABLE — their
  ;; SIGNATURES render in the always-on :namespaces block, mirroring how the
  ;; message/lifecycle verbs are surfaced — so an agent can find HOW to spawn a
  ;; child (it already could discover `terminate`). But seon.agent is a large
  ;; framework ns: the `#{names}` selector in verb-signature-whitelist narrows
  ;; to JUST the spawn verbs — boot! and the wake predicates stay DROPPED.
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.ns/name :my.agent.sp}
                    {:seon.ns/name :seon.agent}
                    {:seon.fn/sym      "seon.agent/start!"
                     :seon.fn/ns       [:seon.ns/name :seon.agent]
                     :seon.fn/arglists "([{:seon.agent/keys [id purpose default-turn-limit]}])"
                     :seon.fn/doc      "Spawn a child agent; RETURNS {:seon.agent/id <child-id>} — THAT id is the one you message to reach the child you just spawned this turn (never invent one).\n   More prose below the first line."
                     :seon.fn/source   "(defn start! [m] m)"}
                    {:seon.fn/sym      "seon.agent/create!"
                     :seon.fn/ns       [:seon.ns/name :seon.agent]
                     :seon.fn/arglists "([{:seon.agent/keys [id purpose]}])"
                     :seon.fn/doc      "Allocate an agent entity."
                     :seon.fn/source   "(defn create! [m] m)"}
                    ;; framework-internal — must NOT be surfaced by the narrowed
                    ;; selector even though it is public.
                    {:seon.fn/sym      "seon.agent/boot!"
                     :seon.fn/ns       [:seon.ns/name :seon.agent]
                     :seon.fn/arglists "([m])"
                     :seon.fn/doc      "Boot one agent (system entry)."
                     :seon.fn/source   "(defn boot! [m] m)"}]})
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-block
                                {:seon.db/db @db/*conn* :seon.agent/id "sp"})]
                      (is (str/includes? txt ";;; ┌─ namespace seon.agent (signatures) ─")
                          "seon.agent renders a (signatures) block")
                      (is (str/includes? txt "(seon.agent/start!")
                          "the spawn verb start! signature is surfaced")
                      (is (str/includes? txt "(seon.agent/create!")
                          "the spawn verb create! signature is surfaced")
                      (is (str/includes? txt "RETURNS {:seon.agent/id")
                          "start!'s first-line doc teaches the return-id contract")
                      (is (not (str/includes? txt "boot!"))
                          "the framework-internal boot! is NOT dumped (narrowed selector)")
                      (is (not (str/includes? txt "(defn start!"))
                          "bodies are elided — signatures only")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest required-namespace-api-surfaces-and-self-heals
  ;; The agent's CURRENT ns renders FULL; the framework deps it `:require`s
  ;; render their PUBLIC API (signatures) so adding a require teaches the dep.
  ;; Excluded: core libs (clojure.* — never indexed / non-seon third-party
  ;; filter), and anything already shown full/verb-sig. Drop the require and
  ;; the API block VANISHES (pure fn of `:seon.ns/requires`).
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [;; the agent's home ns requires a framework dep + a core lib
                    {:seon.ns/name :my.agent.req :seon.ns/requires [:seon.frobx :clojure.set]}
                    ;; the framework dep, indexed with one public + one private fn
                    {:seon.ns/name :seon.frobx}
                    {:seon.fn/sym      "seon.frobx/doit"
                     :seon.fn/ns       [:seon.ns/name :seon.frobx]
                     :seon.fn/arglists "([x])"
                     :seon.fn/doc      "Frob the x."
                     :seon.fn/source   "(defn doit [x] (inc x))"}
                    {:seon.fn/sym      "seon.frobx/secret"
                     :seon.fn/ns       [:seon.ns/name :seon.frobx]
                     :seon.fn/arglists "([y])"
                     :seon.fn/private? true
                     :seon.fn/source   "(defn- secret [y] y)"}]})
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-block
                                {:seon.db/db @db/*conn* :seon.agent/id "req"})]
                      (is (str/includes? txt "API of the namespaces your current ns :requires")
                          "the required-API sub-header renders")
                      (is (str/includes? txt ";;; ┌─ namespace seon.frobx (signatures) ─")
                          "the required framework dep renders its (signatures) block")
                      (is (str/includes? txt "(seon.frobx/doit [x])")
                          "the public fn signature (name + arglist) is shown")
                      (is (str/includes? txt "; Frob the x.")
                          "the one-line docstring rides the signature")
                      (is (not (str/includes? txt "(inc x)"))
                          "the BODY is elided — signatures only, not full source")
                      (is (not (str/includes? txt "secret"))
                          "a private fn is not surfaced in the public API")
                      (is (not (str/includes? txt "clojure.set"))
                          "a core lib require is never dumped (not indexed / third-party)"))))
                ;; drop the seon.frobx require → its API block self-heals away
                (.then
                  (fn [_]
                    (db/transact!
                      {:seon.db/tx-data
                       [[:db/retract [:seon.ns/name :my.agent.req]
                         :seon.ns/requires :seon.frobx]]})))
                (.then
                  (fn [_]
                    (let [txt (ctx-namespaces/namespaces-block
                                {:seon.db/db @db/*conn* :seon.agent/id "req"})]
                      (is (not (str/includes? txt "seon.frobx"))
                          "dropping the require removes the dep's API block (self-healing)")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest required-namespace-api-respects-cap
  ;; The required-API section is char-capped (`SEON_RENDER_REQUIRES_CAP`):
  ;; blocks accrue until the budget is spent, the tail is ELIDED with a
  ;; one-line note that NAMES the dropped nses — never silent truncation.
  ;; Driven directly through the private helper with a fully-built scenario so
  ;; the elision branch is exercised regardless of the live env cap value.
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.ns/name :my.agent.cap
                     :seon.ns/requires [:seon.aaa :seon.bbb :seon.ccc]}
                    {:seon.ns/name :seon.aaa}
                    {:seon.fn/sym "seon.aaa/fa" :seon.fn/ns [:seon.ns/name :seon.aaa]
                     :seon.fn/arglists "([x])" :seon.fn/doc "Aaa." :seon.fn/source "(defn fa [x] x)"}
                    {:seon.ns/name :seon.bbb}
                    {:seon.fn/sym "seon.bbb/fb" :seon.fn/ns [:seon.ns/name :seon.bbb]
                     :seon.fn/arglists "([x])" :seon.fn/doc "Bbb." :seon.fn/source "(defn fb [x] x)"}
                    {:seon.ns/name :seon.ccc}
                    {:seon.fn/sym "seon.ccc/fc" :seon.fn/ns [:seon.ns/name :seon.ccc]
                     :seon.fn/arglists "([x])" :seon.fn/doc "Ccc." :seon.fn/source "(defn fc [x] x)"}]})
                (.then
                  (fn [_]
                    ;; Cap small enough that only the FIRST name-sorted block
                    ;; fits — the other two must be elided + named.
                    (with-redefs [seon.config/requires-api-cap (constantly 80)]
                      (let [blocks (#'ctx-namespaces/required-api-blocks
                                     @db/*conn* :my.agent.cap #{:my.agent.cap})
                            txt    (str/join "\n\n" blocks)]
                        (is (str/includes? txt "seon.aaa")
                            "the first (name-sorted) required dep still renders")
                        (is (str/includes? txt "more required-ns API")
                            "an elision note appears when the budget is exceeded")
                        (is (and (str/includes? txt "seon.bbb")
                                 (str/includes? txt "seon.ccc"))
                            "the elided nses are NAMED in the note (not silently dropped)")
                        (is (not (str/includes? txt "(seon.bbb/fb"))
                            "the elided dep's signatures are NOT rendered")))))))
          )
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
;; Composer: purpose-as-entity-data, merge, verbs.
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
     :seon.render/sections       (mapv :seon.agent.ctx/name (:seon.agent.ctx/children root))
     :seon.render/section-texts  section-texts
     :seon.render/section-html   section-html
     :seon.render/token-estimate (quot (count text) 4)}))

(defn- section-text
  [id nm]
  (some #(when (= nm (:seon.agent.ctx/name %)) (:seon.render/text %))
        (:seon.render/section-texts (assemble id))))

(deftest live-tile-block-stable-on-composer-input
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
                   (let [out (str (ctx-live-tile/live-tile-block
                                    {:seon.db/db    @db/*conn*
                                     :seon.agent/id "AGTctxtile00p1"}))]
                     (is (seq out) "section renders content, never blank")
                     (is (not (str/includes? out "⚠"))
                         "no bare ⚠ render-failed placeholder")
                     (is (not (str/includes? out "malli"))
                         "no swallowed malli code in the agent's context")
                     (is (str/includes? out "Wired:")
                         "the wired-label header resolves (welcome by default)"))
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
                   (let [out (str (ctx-live-tile/live-tile-block
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

(deftest purpose-entity-and-verbs
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
;; live-tile/canvas, an acme dashboard tile) has nothing to say to the
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

;; ------------------------------------------------------------
;; inventory-block — the cheap stored-data discovery surface.
;; ------------------------------------------------------------

(deftest inventory-block-renders-stored-kinds-compact
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; REACTIVE: a fresh conn has NO post-bootstrap data → the
            ;; section is suppressed (composer drops it), not an empty shell.
            (is (= "" (ctx-inventory/inventory-block {:seon.db/db @db/*conn*}))
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
                    (let [txt   (ctx-inventory/inventory-block {:seon.db/db @db/*conn*})
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
;; findings-block — the stored-findings CONTENT surface (sibling of
;; inventory's COUNTS). Renders claim TEXT + provenance; "" when empty;
;; loud-truncation footer + read-back query when clipped.
;; ------------------------------------------------------------

(deftest findings-block-renders-content-and-provenance
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; REACTIVE: a fresh conn holds no user-domain rows → "".
            (is (= "" (ctx-findings/findings-block {:seon.db/db @db/*conn*}))
                "no user-domain findings → \"\" (reactive suppression)")
            (schema/register! :my.kb.codebase/claim :string)
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.kb.codebase/claim
                     "transact! Malli-validates every entity before the tx reaches datahike"
                     :my.kb/source-path "src/seon/db.cljs"
                     :my.kb/source-line 630
                     :my.kb/confidence  :verified}
                    {:my.kb.codebase/claim
                     "the transaction report itself is swallowed at the boundary"
                     :my.kb/source-path "src/seon/agent/message.cljs"
                     :my.kb/source-line 42
                     :my.kb/confidence  :inferred}]})
                (.then
                  (fn [_]
                    (let [txt (ctx-findings/findings-block {:seon.db/db @db/*conn*})]
                      ;; The CLAIM TEXT itself renders (the regression fix) —
                      ;; not just a count, not just a low-card sample.
                      (is (str/includes?
                            txt "transact! Malli-validates every entity")
                          "the first claim's TEXT renders in full")
                      (is (str/includes?
                            txt "the transaction report itself is swallowed")
                          "the second claim's TEXT renders in full")
                      ;; provenance (path:line + confidence) rides each row.
                      (is (str/includes? txt "src/seon/db.cljs:630")
                          "source path:line provenance renders")
                      (is (str/includes? txt ":verified")
                          "confidence provenance renders")
                      ;; domain namespace labels the row.
                      (is (str/includes? txt "my.kb.codebase")
                          "the domain namespace labels the row")
                      ;; NO footer when nothing is clipped (2 rows < cap).
                      (is (not (str/includes? txt "older finding"))
                          "no truncation footer when all rows shown")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

(deftest findings-block-truncation-footer-and-read-back
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; 12 user-domain rows > max-rows (10) → loud footer + read-back.
            (schema/register! :my.kb.codebase/claim :string)
            (-> (db/transact!
                  {:seon.db/tx-data
                   (mapv (fn [i]
                           {:my.kb.codebase/claim (str "claim number " i)
                            :my.kb/source-path "src/seon/x.cljs"
                            :my.kb/source-line (inc i)
                            :my.kb/confidence :inferred})
                         (range 12))})
                (.then
                  (fn [_]
                    (let [txt   (ctx-findings/findings-block {:seon.db/db @db/*conn*})
                          lines (str/split-lines txt)]
                      ;; exactly max-rows content lines (the "#<eid>: claim" rows).
                      (is (= 10 (count (filter #(str/includes? % "claim number ")
                                               lines)))
                          "exactly max-rows (10) finding rows rendered")
                      (is (str/includes? txt "older finding")
                          "loud-truncation footer present when clipped")
                      (is (str/includes? txt ":my.kb/source-path")
                          "footer carries the read-back query")))))))
        (.then (fn [] (done)))
        (.catch (fn [e] (is (nil? e) (str "unexpected: " e)) (done))))))

;; ------------------------------------------------------------
;; relevant-source-block (P2-D) — the embedding-retrieval surface.
;; PURE reader of the per-turn `seon.embed.stash`; no conn needed.
;; ------------------------------------------------------------

(deftest relevant-source-block-renders-stashed-hits
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
    (is (= "" (ctx-relevant/relevant-source-block in))
        "no stash (default-OFF / no prefetch) → \"\" (reactive suppression)")
    ;; (2) with a stash → full render.
    (let [txt (embed-stash/with-hits hits
                #(ctx-relevant/relevant-source-block in))]
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

(deftest relevant-source-block-renders-any-kind
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
                               #(ctx-relevant/relevant-source-block in)))]
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
                                     (into {} (map (juxt :seon.agent.ctx/name
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
                      (doseq [{nm  :seon.agent.ctx/name
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

(deftest system-message-is-hardcoded-mechanics-not-the-soul
  ;; THE decoupling: the LLM system message is the hardcoded mechanics.
  (is (= ctx/system-text (llm/effective-system-prompt {}))
      "system message = the hardcoded seon mechanics (seon.agent.ctx/system-text)")
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
  (let [t1 (chain (into static-prefix [(blk :inventory "; 3 ledgers")
                                       (blk :transcript "; turn 1")]) "agent-7")
        t2 (chain (into static-prefix [(blk :inventory "; 3 ledgers")
                                       (blk :transcript "; turn 2 DIFFERENT")]) "agent-7")
        n  (count static-prefix)]
    ;; the 4 static blocks + the unchanged :inventory (index n) share keys
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
                                      :namespaces :inventory :warnings :transcript])
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

;; ── cite-card — the anti-fabrication surface ─────────────────────────────
;; A pure derivation over OK eval entity maps: surface the agent's last few
;; COMPUTED values (with their live result/<id> handle) so honesty is the
;; nearest-token path. No DB needed — the fn is pure over eval maps.

(defn- ev
  "A minimal OK eval entity map for cite-card tests."
  [id src res]
  {:seon.eval/id id :seon.eval/ok? true
   :seon.eval/source src :seon.eval/result-edn res})

(deftest cite-card-derives-recent-values
  (let [card (transcript/cite-card
               [(ev "yRn" "(db/store-inventory {:seon.db/system? true})"
                    "{:seon.db/attr-groups [...]}")
                (ev "Lbv" "(seon.db/query '[:find ?a (count ?e) ...])"
                    "[{:agent-id \"XeG-2606282241\", :eval-count 69}]")])]
    (is (str/includes? card "result/Lbv")
        "the value's live handle is surfaced")
    (is (str/includes? card "XeG-2606282241")
        "the real computed figure (the one fabrication invents) is right there")
    (is (str/includes? card "cite THESE")
        "the header steers toward citing, not retyping")
    (is (str/includes? card "(seon.db/query")
        "the producing form rides the line as a hint")))

(deftest cite-card-empty-when-nothing-citeable
  ;; nil / echo / verb-ack receipt / opaque fn / failed eval ⇒ nothing to cite
  (is (= "" (transcript/cite-card []))
      "no evals ⇒ no card")
  (is (= "" (transcript/cite-card
              [(ev "a" "nil" "nil")
               (ev "b" ":idle" ":idle")
               (ev "c" "(message/user \"hi\")"
                   "{:seon.agent.message/ok? true, :seon.agent.message/id \"x\"}")
               (ev "d" "result/Lbv" "[{:agent-id \"X\", :eval-count 69}]")
               (ev "e" "(recommendation-tile)" "#‹fn›")
               (assoc (ev "f" "(/ 1 0)" "boom") :seon.eval/ok? false)]))
      "trivial / receipt / re-reference / opaque / failed rows all skipped"))

(deftest cite-card-clips-big-value-keeps-handle
  (let [big  (apply str (repeat 2000 "x"))
        card (transcript/cite-card [(ev "Big" "(huge)" big)])]
    (is (str/includes? card "result/Big")
        "the handle to the whole value survives the clip")
    (is (str/includes? card "holds it whole")
        "a clipped preview points back at the live handle")
    (is (< (count card) (count big))
        "the card is a bounded preview, never the flood")))
