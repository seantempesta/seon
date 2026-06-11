(ns seon.agent-context-test
  "Guard tests for the context-render refactor (context-refactor-spec
   2026-06-08) + the api-discoverability fix (capabilities-section).
   These pin the invariants that the prior live bugs violated:

     (a) an agent with NO stored `:seon.agent/ctx` still gets the FULL
         default context — the regression that started this. Context is
         a pure function of the DB with a CODE-default section layout,
         never empty just because no `:seon.agent/ctx` was seeded.
     (b) agent-path (`render-prompt`) ≡ inspector-path
         (`inspect/ctx-preview`) ≡ the would-be persisted
         `:seon.agent.turn/prompt-text` for the same (db,id) — ONE composer,
         divergence impossible.
     (c) each section fn renders non-blank given seeded data (would have
         caught a section silently returning \"\" when data IS present).
     (d) the composed context contains the section markers when the
         underlying sections have content; the transcript interleaves
         messages + evals chronologically (not block-after-block).
     (e) bounded-context guard — a single eval with a multi-MB result
         does NOT blow the agent's context; `transcript-section`
         (and therefore `render-prompt`) stays under a sane bound. This
         is the context-SAFETY invariant: no single entity may dominate
         the context.
     (f) capabilities-section — the `## What you can do` worked-examples
         block is in the default ctx, renders the map-in call shapes, and
         is DERIVED from the persisted core `:seon.fn` arglists (not a
         hardcoded blob).

   All tests open a FRESH `:memory` datahike conn (via
   `seon.client/open-agent-conn!`, the same boot helper the pod uses)
   and seed an agent + session + turns + messages + evals directly — so
   nothing here touches the live agent.

   Run interactively via MCP eval:
     (require 'seon.agent-context-test :reload)
     (cljs.test/run-tests 'seon.agent-context-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.client :as client]
    ;; required explicitly: the format-eval-row tests deref
    ;; #'seon.ctx/format-eval-row directly (private fn, var-quote)
    [seon.ctx]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.agent.inspect :as inspect]
    [seon.schema :as schema]
    ;; The exemplar TEST SIBLINGS — required so the fixture can seed their
    ;; :seon.ns rows (full file text) via client/index-tests, the same
    ;; mechanism the pod's preload-driven boot uses.
    [seon.agent.search-test]
    [seon.agent.todo-test]
    [my.kb-test]))

;; ---------------------------------------------------------------------------
;; Fixture — a fresh conn seeded with one agent + session + turns. Returns a
;; Promise of the conn so tests can chain. `db/*conn*` is bound for the extent
;; of `body` (a 0-arg fn that may itself return a Promise) because several
;; section fns (current-session / messages / evals / current-ns) reach
;; `@db/*conn*` directly rather than the `:seon.db/db` in their input.
;; ---------------------------------------------------------------------------

(def ^:private agent-id "AGTctxtest0001")        ; 14 chars (:seon.db/id)

(defn- seed-tx
  "tx-data for an agent with NO `:seon.agent/ctx`, a session with two
   turns. Turn 1: a user message + a FAILED eval (drives warnings).
   Turn 2: a successful eval in ns `:my.agent.ctx-260610` plus a
   `:seon.ns`/`:seon.fn` for that ns (drives current-ns). `extra-evals`
   lets a test append big-result evals to turn 2.

   Boot-equivalent rows (capabilities/catalog data) live in
   [[boot-seed-tx]] — transacted separately under the
   `:substrate-seed` tx-context, exactly like a real pod boot."
  [extra-evals]
  (let [now (js/Date.)
        t   (fn [ms] (js/Date. (+ (.getTime now) ms)))]
    (into
      [{:seon.agent/id agent-id
        :seon.agent/state :idle
        :seon.agent/sessions
        [{:seon.agent.session/id "SESctxtest0001"
          :seon.agent.session/at (t 0)
          :seon.agent.session/turns
          [{:seon.agent.turn/id "TRNctxtest0001"
            :seon.agent.turn/at (t 10)
            :seon.agent.turn/status :done
            :seon.agent.turn/messages
            [{:seon.agent.message/id "MSGctxtest0001"
              :seon.agent.message/from {:seon.user/id "user"}
              :seon.agent.message/to [{:seon.agent/id agent-id}]
              :seon.agent.message/content "build me a thing"
              :seon.agent.message/at (t 11)
              :seon.agent.message/hops 0}
             {:seon.agent.message/id "MSGctxtest0002"
              :seon.agent.message/from {:seon.agent/id agent-id}
              :seon.agent.message/to [{:seon.user/id "user"}]
              :seon.agent.message/content "on it"
              :seon.agent.message/at (t 12)
              :seon.agent.message/hops 1}]
            :seon.agent.turn/evals
            [{:seon.eval/id "EVLctxtestF001"
              :seon.eval/at (t 13)
              :seon.eval/duration-ms 5
              :seon.eval/source "(seon.db/query [:bad])"
              :seon.eval/ok? false
              :seon.eval/error "boom — bad query"
              :seon.eval/ns :my.agent.ctx-260610}]}
           {:seon.agent.turn/id "TRNctxtest0002"
            :seon.agent.turn/at (t 20)
            :seon.agent.turn/status :done
            :seon.agent.turn/evals
            (into [{:seon.eval/id "EVLctxtestK001"
                    :seon.eval/at (t 21)
                    :seon.eval/duration-ms 3
                    :seon.eval/source "(defn greet [] :hi)"
                    :seon.eval/ok? true
                    :seon.eval/result-edn "#'my.agent.ctx-260610/greet"
                    :seon.eval/ns :my.agent.ctx-260610}]
                  extra-evals)}]}]}
       ;; Program-graph entities for the agent's current ns so
       ;; namespace-context-section has source to render.
       {:seon.ns/name :my.agent.ctx-260610
        :seon.ns/source "(ns my.agent.ctx-260610)"}
       {:seon.fn/sym "my.agent.ctx-260610/greet"
        :seon.fn/ns [:seon.ns/name :my.agent.ctx-260610]
        :seon.fn/source "(defn greet [] :hi)"}])))

(defn- boot-seed-tx
  "The pod's boot-seed rows — the SAME data `seon.client/start-agent!`
   transacts. Transacted SEPARATELY from [[seed-tx]], inside the
   `{:seon.db/origin :substrate-seed}` tx-context, because provenance
   is load-bearing since the S-21 fix (2026-06-10):
   `seon.warn/domain-attrs` treats any `:seon.schema/key` row asserted
   OUTSIDE the seed context as an AGENT-registered domain attr — these
   ~300 registry rows in a non-seed tx made every installed substrate
   attr render in the catalog's domain-attrs block (and blew the
   turn-0 budget)."
  []
  (vec
    (concat
      ;; the user entity + the my.kb.system instruction singleton
      ;; (read by eval — the :instructions section died, V4-0)
      (client/seed-substrate!)
      ;; the introspection-indexed core-fn :seon.ns + :seon.fn rows
      ;; (drives capabilities)
      (client/index-substrate!)
      ;; the :seon.schema entities for every entity kind — drives
      ;; schema-catalog-section.
      (schema/all-entity-schemas-tx-data)
      ;; the whole-registry :seon.schema rows (unit #23 fix b) — drives
      ;; the schema-catalog's per-ns summary block. Deduped by key
      ;; against the entity rows above via identity upsert.
      (client/index-schemas)
      ;; the exemplar TEST SIBLINGS' :seon.ns + :seon.test rows — the
      ;; preload-populated default roster is empty in the :node-test
      ;; build, so seed one deftest per sibling explicitly (the SAME
      ;; builder the pod boot uses). Drives the :exemplars section's
      ;; test-sibling blocks.
      (client/index-tests
        [#'seon.agent.search-test/match-found-with-path-line-text
         #'seon.agent.todo-test/the-store-retrieve-arc-with-resume
         #'my.kb-test/system-instructions-append-by-transact]))))

(defn- with-seeded-conn
  "Open a fresh conn, seed it (optionally with `extra-evals` on turn 2),
   and run `body` (1-arg `conn`) with `db/*conn*` bound for the SYNC
   extent of `body`. `body`'s assertions must be synchronous: a plain
   `binding` does NOT survive Promise `.then` boundaries in CLJS (unlike
   the ALS-backed `db/with-agent`), so we rebind `db/*conn*` right
   around the synchronous `body` call. Two transacts, matching a real
   pod boot's provenance: [[boot-seed-tx]] under
   `{:seon.db/origin :substrate-seed}`, then the runtime fixture
   [[seed-tx]] in an ordinary tx. Returns a Promise."
  ([body] (with-seeded-conn [] body))
  ([extra-evals body]
   (-> (client/open-agent-conn!)
       (.then (fn [conn]
                ;; transact under the binding so tx-context defaults resolve,
                ;; then re-establish it around the synchronous body call.
                (binding [db/*conn* conn]
                  (-> (db/with-tx-context {:seon.db/origin :substrate-seed}
                        (fn []
                          (db/transact! {:seon.db/conn conn
                                         :seon.db/tx-data (boot-seed-tx)})))
                      (.then (fn [_]
                               (db/transact!
                                 {:seon.db/conn conn
                                  :seon.db/tx-data (seed-tx extra-evals)})))
                      (.then (fn [_]
                               (binding [db/*conn* conn]
                                 (body conn)))))))))))

(defn- big-eval
  "A turn-2 eval whose `:seon.eval/result-edn` is `n` chars long — the
   shape of the live 9.7M-char `pull` blob that blew the context."
  [n]
  {:seon.eval/id "EVLctxtestBIG1"
   :seon.eval/at (js/Date. (+ (.getTime (js/Date.)) 30))
   :seon.eval/duration-ms 9
   :seon.eval/source "(seon.db/pull {:seon.db/pull-pattern '[*]})"
   :seon.eval/ok? true
   :seon.eval/result-edn (apply str (repeat n "x"))
   :seon.eval/ns :my.agent.ctx-260610})

;; ---------------------------------------------------------------------------
;; (a) THE regression — no stored ctx still yields the full default context.
;; ---------------------------------------------------------------------------

(deftest no-stored-ctx-still-gets-full-default-context
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db @conn
                  {:seon.render/keys [text sections]}
                  (agent/assemble-context {:seon.db/db db :seon.agent/id agent-id})]
              (is (pos? (count text))
                  "no :seon.agent/ctx → STILL non-empty (code default, not 0)")
              (is (= [:system :capabilities :exemplars
                      :schema-catalog :functions-catalog :live-tile
                      :namespace-context :warnings :open-todos :transcript
                      :prompt]
                     sections)
                  "the substrate-default section names, in order
                   (static→dynamic): system, capabilities, exemplars,
                   schema-catalog, functions-catalog, live-tile,
                   namespace-context, warnings, open-todos, transcript,
                   prompt — :instructions DIED (context-v4 V4-0)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (b) agent-path ≡ inspector-path ≡ would-be persisted prompt-text.
;; ---------------------------------------------------------------------------

;; The prompt section (context TAIL — cache-prefix fix 2026-06-09; the
;; timestamp used to live in <system> at char 35 and busted the provider
;; cache every turn) embeds `(js/Date.)` in the `;; ── turn …` status
;; line, so two renders microseconds apart differ ONLY on that line.
;; Normalize it away before comparing — everything else is a pure
;; function of the DB and must be byte-identical across the three paths.
(defn- strip-now [s]
  (str/replace s #"(?m)^;; ── turn [^\n]*$" ";; ── <STATUS NORMALIZED> ──"))

(deftest agent-inspector-and-prompt-text-agree
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db          @conn
                  ;; render-prompt is what run-turn! persists as
                  ;; :seon.agent.turn/prompt-text — assert that exact source.
                  agent-text  (strip-now (agent/render-prompt agent-id))
                  composer    (strip-now
                                (:seon.render/text
                                  (agent/assemble-context
                                    {:seon.db/db db :seon.agent/id agent-id})))
                  inspector   (strip-now
                                (:seon.render/text
                                  (inspect/ctx-preview {:seon.agent/id agent-id})))]
              (is (pos? (count agent-text)) "agent path non-empty")
              (is (= agent-text composer)
                  "render-prompt == assemble-context (same composer)")
              (is (= agent-text inspector)
                  "inspector left-pane text == agent prompt text — no divergence"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (c) Each section fn renders non-blank given seeded data.
;; ---------------------------------------------------------------------------

(deftest each-section-renders-non-blank
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}]
              (is (not (str/blank? (agent/system-section input))) "system")
              (is (not (str/blank? (agent/capabilities-section input)))
                  "capabilities — non-blank because core :seon.fn rows are seeded")
              (is (not (str/blank? (agent/schema-catalog-section input)))
                  "schema-catalog — non-blank because :seon.schema entities are seeded")
              (is (not (str/blank? (agent/functions-catalog-section input)))
                  "functions-catalog — non-blank because :seon.fn entities are seeded")
              (is (not (str/blank? (agent/namespace-context-section input)))
                  "namespace-context — non-blank because a :seon.ns + :seon.fn exist")
              (is (not (str/blank? (agent/warnings-section input)))
                  "warnings — the seeded failed eval surfaces")
              (is (not (str/blank? (agent/transcript-section input)))
                  "transcript — seeded messages + evals")
              (is (not (str/blank? (agent/prompt-section input))) "prompt"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d) Composed context contains the section markers when sections have content.
;; ---------------------------------------------------------------------------

(deftest composed-context-includes-non-blank-section-markers
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<system") "system marker present")
              (is (str/includes? text "<schema-catalog>") "schema-catalog marker present")
              (is (str/includes? text "<functions>") "functions-catalog marker present")
              (is (str/includes? text "<transcript>") "transcript marker present")
              (is (str/includes? text "<namespace-context>")
                  "namespace-context marker present")
              (is (str/includes? text "<warnings>") "warnings marker present"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d2) transcript-section interleaves messages + evals chronologically.
;;      Seed time order: user msg (t11) → assistant msg (t12) → failed eval
;;      (t13) → successful eval (t21). The merged stream must preserve that
;;      order — a user message BEFORE its eval, an eval AFTER the message
;;      that prompted it — proving the two streams are merged by :at, not
;;      concatenated block-after-block.
;; ---------------------------------------------------------------------------

(deftest transcript-interleaves-messages-and-evals-chronologically
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  ts   (agent/transcript-section
                         {:seon.db/db db :seon.agent/id agent-id})
                  i-user      (str/index-of ts "user> build me a thing")
                  i-assistant (str/index-of ts "assistant> on it")
                  i-failed    (str/index-of ts "boom — bad query")
                  i-success   (str/index-of ts "my.agent.ctx-260610/greet")]
              (is (str/includes? ts "<transcript>") "transcript marker present")
              (is (and i-user i-assistant i-failed i-success)
                  "all four transcript items present (2 msgs + 2 evals)")
              ;; chronological: user msg < assistant msg < failed eval < success eval
              (is (< i-user i-assistant)
                  "user message before assistant message")
              (is (< i-assistant i-failed)
                  "messages interleaved BEFORE the eval that followed them
                   (proves merge by :at, not message-block-then-eval-block)")
              (is (< i-failed i-success)
                  "failed eval (t13) before successful eval (t21)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d3) transcript budget eviction NEVER drops messages — the S-12 live
;;      defect (KoQ turn Ckz-2606101827): a burst of eval rows after the
;;      user's last message pushed the message past the 24k budget, and
;;      the agent saw a transcript with its waking question missing.
;;      Messages are exempt from eviction; eval rows still evict
;;      oldest-first under the remaining budget.
;; ---------------------------------------------------------------------------

(defn- flood-eval
  "One of N same-shaped big evals for the budget-eviction test — `i`
   disambiguates id/at; the 2000-char result renders at the 1500-char
   eval cap + clip guide, ≈1.8k chars/row. The +1h offset guarantees
   the flood sorts AFTER every [[seed-tx]] item: seed timestamps are
   captured at TRANSACT time (seconds after these maps are built, the
   boot seed is slow), so a small ms offset here would land BEFORE the
   seed's t13/t21 evals and invert the eviction order under test."
  [i]
  {:seon.eval/id (str "EVLctxflood" (.padStart (str i) 3 "0"))
   :seon.eval/at (js/Date. (+ (.getTime (js/Date.)) 3600000 i))
   :seon.eval/duration-ms 4
   :seon.eval/source (str "(flood " i ")")
   :seon.eval/ok? true
   :seon.eval/result-edn (apply str (repeat 2000 "y"))
   :seon.eval/ns :my.agent.ctx-260610})

(deftest transcript-eviction-keeps-messages-under-eval-flood
  (async done
    (-> (with-seeded-conn
          (mapv flood-eval (range 1 21))      ; ~36k rendered eval chars
          (fn [conn]
            (let [db @conn
                  ts (agent/transcript-section
                       {:seon.db/db db :seon.agent/id agent-id})]
              (is (str/includes? ts "user> build me a thing")
                  "the user's message SURVIVES the eval flood — the S-12
                   'last message missing from the visible transcript' bug")
              (is (str/includes? ts "assistant> on it")
                  "the agent's own reply survives too")
              (is (str/includes? ts "older eval item")
                  "the elision note fired — the flood DID overflow the budget")
              (is (not (str/includes? ts "boom — bad query"))
                  "the OLDEST eval row was evicted — eviction still works,
                   it just no longer takes messages with it")
              (is (str/includes? ts "(flood 20)")
                  "the newest eval row is kept"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (f) capabilities-section — the "## What you can do" worked-examples block.
;;     Guards the api-discoverability bug: the section must be in the default
;;     ctx, render the map-in call shapes, and be DERIVED from the persisted
;;     core :seon.fn arglists (not a hardcoded blob).
;; ---------------------------------------------------------------------------

(deftest substrate-default-ctx-includes-capabilities-after-system
  (let [names (mapv :seon.ctx/name (agent/substrate-default-ctx))]
    (is (some #{:capabilities} names)
        "substrate-default-ctx contains the :capabilities section")
    (is (= [:system :capabilities :exemplars]
           (vec (take 3 names)))
        ":capabilities and :exemplars right after :system (the cache
         prefix) — no :instructions section (context-v4 V4-0)")
    (is (not (contains? (set names) :root-pull))
        "no :root-pull section in the default layout — the amplifier is gone")))

;; ---------------------------------------------------------------------------
;; root-pull is DELETED — no fn, no advertisement, no default section.
;; ---------------------------------------------------------------------------

(deftest root-pull-is-deleted
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (not (str/includes? text "root-pull"))
                  "no system-section advertisement of root-pull in context"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest capabilities-section-renders-worked-call-shapes
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}
                  cap   (agent/capabilities-section input)
                  ;; the WHOLE assembled context, not just the section,
                  ;; so we prove the worked shapes reach the agent.
                  full  (:seon.render/text
                          (agent/assemble-context
                            {:seon.db/db db :seon.agent/id agent-id}))]
              (is (not (str/blank? cap)) "capabilities-section non-blank")
              (is (str/includes? full "## What you can do")
                  "the promised heading is present in assembled context")
              ;; map-in shapes — the exact mistakes we observed live.
              (is (str/includes? full ":seon.db/tx-data")
                  "transact! map-in key shown — positional call impossible")
              (is (str/includes? full "(seon.db/query {")
                  "query shown as map-in, not positional")
              (is (str/includes? full "(seon.db/current-agent-id)")
                  "current-agent-id shown — no seon.agent/current-agent-id guess")
              ;; DERIVED from persisted :seon.fn arglists, not hardcoded:
              ;; the rendered shape must be the CALLABLE per-arity form built
              ;; from the seeded entity's real arglists. T15 gave `transact!`/
              ;; `query` variadic dispatchers (`[& call-args]` / `[& args]`),
              ;; so the callable render is `(sym & args)` — derived from real
              ;; source, not a curated fiction. The OLD garbled renders
              ;; (`(seon.db/transact! ([& call-args]))`, `(seon.db/pull ())`)
              ;; must never come back (context-audit 2026-06-09 §2).
              (is (str/includes? cap "(seon.db/transact! & call-args)")
                  "transact! renders the CALLABLE shape from real arglists")
              (is (str/includes? cap "(seon.db/query & args)")
                  "query renders the CALLABLE shape from real arglists")
              (is (not (str/includes? cap "(seon.db/transact! ([& call-args]))"))
                  "no double-wrapped arglists render")
              (is (not (re-find #"\(seon\.db/(pull|entity) \(\)\)" cap))
                  "no empty-arglists `(sym ())` render for pull/entity")
              ;; V3-B — consult-before-research is an INSTRUCTION row;
              ;; capabilities keeps the HOW: a copyable catalog-driven
              ;; my.kb consult query + the my.kb.<domain> storage shape.
              (is (str/includes? cap "CONSULT KNOWLEDGE → SEARCH → READ")
                  "recipe leads with consulting stored knowledge")
              (is (str/includes? cap ":my.kb.codebase.fn/claim")
                  "storage example models a my.kb.<domain> schema")
              (is (str/includes? cap ":my.kb/source-path")
                  "storage example references the SHARED provenance attrs")
              (is (str/includes? cap "single-segment namespace")
                  "the multi-segment rule is EXPLAINED, not just modeled")
              (is (not (str/includes? cap "kb.finding"))
                  "zero kb.finding occurrences — the generic taught shape is dead")
              (is (not (str/includes? cap "STORE PROACTIVELY"))
                  "store-proactively prose lives in the system prompt
                   (V4-0), not capabilities")
              ;; #26 — concise message!/reply! return modeled in the example.
              (is (str/includes? cap ":seon.agent.message/ok? true")
                  "reply! example shows the concise return shape")
              ;; bounded — curated core API + worked examples, not a dump.
              ;; (raised from 4000 when the fs/search recipe, finding shape,
              ;; pull/entity/listen! examples landed; raised again to 13000
              ;; for the #26 consult-first recipe + multi-segment rule +
              ;; store-proactively nudge.)
              (is (< (count cap) 13000)
                  (str "capabilities-section bounded — got " (count cap))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (h) schema-catalog-section — the GLOBAL cross-namespace catalog of every
;;     entity KIND in the system. This is HOW a fresh agent knows what data
;;     exists (user, 2026-06-08 night). Guards: every entity kind listed,
;;     grouped by namespace, with attrs + identity flag + live instance
;;     counts; DERIVED from the seeded :seon.schema entities (a new kind
;;     appears, a retracted one vanishes); in the default ctx after
;;     capabilities and before namespace-context.
;; ---------------------------------------------------------------------------

(deftest substrate-default-ctx-has-schema-catalog-between-caps-and-ns
  (let [names (mapv :seon.ctx/name (agent/substrate-default-ctx))]
    (is (some #{:schema-catalog} names)
        "substrate-default-ctx contains the :schema-catalog section")
    (is (= [:system :capabilities :exemplars
            :schema-catalog :functions-catalog]
           (vec (take 5 names)))
        ":schema-catalog and :functions-catalog sit between :exemplars
         (static) and :namespace-context (the deep per-ns view)")))

(deftest schema-catalog-lists-all-entity-kinds
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db  @conn
                  txt (agent/schema-catalog-section
                        {:seon.db/db db :seon.agent/id agent-id})]
              (is (not (str/blank? txt)) "catalog non-blank with seeded schemas")
              (is (str/includes? txt "<schema-catalog>") "wrapper marker present")
              ;; Every substrate entity kind must be listed.
              (doseq [k [:seon.fn :seon.ns :seon.schema :seon.eval
                         :seon.agent.message :seon.test]]
                (is (str/includes? txt (str "[" k "]"))
                    (str k " kind listed in the catalog")))
              ;; Attributes surfaced, with the identity flag.
              (is (str/includes? txt "id :seon.fn/sym")
                  ":seon.fn/sym shown as the identity attr")
              (is (str/includes? txt ":seon.eval/source")
                  "non-identity attrs are listed too")
              (is (str/includes? txt "=== seon.fn ===")
                  "kinds grouped by owning namespace"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest schema-catalog-instance-counts-match-seeded-data
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db  @conn
                  txt (agent/schema-catalog-section
                        {:seon.db/db db :seon.agent/id agent-id})]
              ;; HIGH-CHURN substrate kinds (eval/message — every turn adds
              ;; instances) render WITHOUT a live count so the semi-static
              ;; catalog stays a stable cache prefix (2026-06-09 fix; an
              ;; exact per-turn count busted the prompt cache every render).
              (is (str/includes? txt "[:seon.eval]  (per-turn data — uncounted)")
                  "eval kind listed, count omitted (cache-prefix stability)")
              (is (str/includes? txt "[:seon.agent.message]  (per-turn data — uncounted)")
                  "message kind listed, count omitted")
              ;; Low-churn kinds keep a (bucketed) count.
              (is (re-find #"\[:seon.ns\]  \d+\+? instances?" txt)
                  ":seon.ns count present"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(defn- catalog-text [conn]
  (agent/schema-catalog-section {:seon.db/db @conn :seon.agent/id agent-id}))

(deftest schema-catalog-is-derived-new-kind-appears-retracted-vanishes
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (is (not (str/includes? (catalog-text conn) "seon.zzcatalog"))
                "throwaway kind absent before registration")
            ;; Register a throwaway entity kind + seed its :seon.schema entity
            ;; (the same mechanism boot uses) — NOT hardcoded anywhere.
            (schema/register! :seon.zzcatalog/id [:string {:seon.db/identity true}])
            (schema/register! :seon.zzcatalog/label :string)
            (schema/register! :seon.zzcatalog
              [:map {:seon.db/entity true
                     :seon.render/ai 'seon.handlers.fn/render-ai}
               [:seon.zzcatalog/id :seon.zzcatalog/id]
               [:seon.zzcatalog/label :seon.zzcatalog/label]])
            ;; NOTE: db/*conn* binding does NOT survive `.then` boundaries
            ;; (see with-seeded-conn docstring), so pass :seon.db/conn
            ;; explicitly inside the promise chain.
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data (schema/entity-schema-tx-data :seon.zzcatalog)})
                (.then (fn [_]
                         (let [after (catalog-text conn)]
                           (is (str/includes? after "[:seon.zzcatalog]")
                               "newly-registered kind APPEARS — derived, not hardcoded")
                           (is (str/includes? after "=== seon.zzcatalog ===")
                               "new kind grouped under its owning namespace"))
                         (db/transact!
                           {:seon.db/conn conn
                            :seon.db/tx-data
                            [[:db/retractEntity [:seon.schema/key :seon.zzcatalog]]]})))
                (.then (fn [_]
                         (is (not (str/includes? (catalog-text conn) "seon.zzcatalog"))
                             "retract :seon.schema entity → kind vanishes (self-healing)"))))))
        (.then (fn [_]
                 (swap! schema/*schemas dissoc
                        :seon.zzcatalog :seon.zzcatalog/id :seon.zzcatalog/label)
                 (done)))
        (.catch (fn [e]
                  (swap! schema/*schemas dissoc
                         :seon.zzcatalog :seon.zzcatalog/id :seon.zzcatalog/label)
                  (is false (str "threw — " e)) (done))))))

(deftest schema-catalog-surfaces-stored-finding-claims
  ;; #26 finding-salience: attr names alone proved discoverable but not
  ;; CONSULTED (run 7 re-derived a stored answer). The catalog must
  ;; surface the claim CONTENT as one-liners.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (is (not (str/includes? (catalog-text conn) "stored findings"))
                "no findings block before any claims exist")
            (schema/register! :kbtest.finding/claim :string)
            ;; the tee-shaped :seon.schema row a real register! eval
            ;; always writes (seon.eval/build-tee-entities) — domain
            ;; attrs (and therefore the findings block) discriminate by
            ;; that agent-provenance row since the S-21 fix (2026-06-10)
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.schema/key :kbtest.finding/claim
                     :seon.schema/source
                     "(seon.schema/register! :kbtest.finding/claim :string)"
                     :seon.schema/created-at (js/Date.)}
                    {:kbtest.finding/claim
                     (str "transact! Malli-validates every entity "
                          "before the tx reaches datahike")}]})
                (.then
                  (fn [_]
                    (let [txt (catalog-text conn)]
                      (is (str/includes?
                            txt "=== stored findings — CONSULT these before re-deriving ===")
                          "claims block appears once a claim is stored")
                      (is (str/includes?
                            txt "transact! Malli-validates every entity")
                          "claim CONTENT renders as a one-liner, not just the attr name")))))))
        (.then (fn [_]
                 (swap! schema/*schemas dissoc :kbtest.finding/claim)
                 (done)))
        (.catch (fn [e]
                  (swap! schema/*schemas dissoc :kbtest.finding/claim)
                  (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (i) functions-catalog-section — the GLOBAL cross-namespace catalog of every
;;     fn defined in the substrate. Sibling of schema-catalog: it answers "what
;;     CODE already exists" so a later agent reuses an earlier one's work
;;     instead of re-deriving it. Guards: in the default ctx (after
;;     schema-catalog, before namespace-context); own-ns fns render with full
;;     source; other-ns fns render as signature + one-line doc; DERIVED from
;;     the :seon.fn corpus (a newly-registered fn in another ns appears).
;; ---------------------------------------------------------------------------

(deftest substrate-default-ctx-has-functions-catalog-after-schema-catalog
  (let [names (mapv :seon.ctx/name (agent/substrate-default-ctx))]
    (is (some #{:functions-catalog} names)
        "substrate-default-ctx contains the :functions-catalog section")
    (is (= [:system :capabilities :exemplars :schema-catalog
            :functions-catalog :live-tile :namespace-context]
           (vec (take 7 names)))
        ":functions-catalog sits right after :schema-catalog and before
         :live-tile / :namespace-context")))

(deftest functions-catalog-is-a-thin-count-index
  ;; Context-focus-redesign E2/E3: the catalog collapsed to a thin per-ns
  ;; index. Substrate nses are ONE count line each; exemplar nses
  ;; cross-reference the full source in :exemplars; the own-ns
  ;; full-source duplicate DIED (own ns renders in :namespace-context).
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}
                  txt   (agent/functions-catalog-section input)
                  full  (:seon.render/text
                          (agent/assemble-context
                            {:seon.db/db db :seon.agent/id agent-id}))]
              (is (not (str/blank? txt)) "functions-catalog non-blank with seeded fns")
              (is (str/includes? txt "<functions>") "wrapper marker present")
              (is (str/includes? full "<functions>")
                  "section reaches the assembled context")
              ;; SUBSTRATE nses collapse to count lines — every one of them.
              (is (re-find #"seon\.db — \d+ fns" txt)
                  "seon.db collapses to a count line")
              (is (re-find #"seon\.schema — \d+ fns" txt)
                  "small substrate nses (seon.schema) ALSO collapse to a count
                   line — no per-fn signature lines for compiled substrate")
              (is (not (str/includes? txt "(seon.schema/register! k v)"))
                  "no per-fn signature lines for substrate nses")
              ;; EXEMPLAR nses cross-reference the full source above.
              (is (re-find #"seon\.agent\.search — \d+ fns? \(full source above\)" txt)
                  "exemplar ns count line carries the full-source cross-reference")
              (is (re-find #"seon\.agent\.todo — \d+ fns? \(full source above\)" txt)
                  "seon.agent.todo cross-references too")
              (is (and (re-find #"seon\.agent\.fs — \d+ fns?" txt)
                       (not (re-find #"seon\.agent\.fs — \d+ fns? \(full source above\)"
                                     txt)))
                  "seon.agent.fs (rotated out) is a PLAIN count line — no
                   full-source cross-reference")
              ;; The OWN-ns full-source special case is DEAD.
              (is (not (str/includes? txt "(your ns)"))
                  "no own-ns special case in the catalog")
              (is (not (str/includes? txt "(defn greet [] :hi)"))
                  "own-ns source does NOT render here (it renders ONCE, in
                   :namespace-context)")
              ;; AGENT-authored ns (my.agent.ctx-260610) keeps per-fn callable
              ;; lines (greet has no stored arglists → the `(sym …)` fallback).
              (is (str/includes? txt "(my.agent.ctx-260610/greet …)")
                  "agent-authored ns renders one callable line per fn")
              ;; No other ns's full source leaks in.
              (is (not (str/includes? txt "(defn transact!"))
                  "no substrate fn inlines its full (defn …) source")
              ;; THE budget: the whole section is a thin index (spec E3:
              ;; turn-0 <functions> ≤ 2k chars).
              (is (< (count txt) 2000)
                  (str "functions-catalog is thin — got " (count txt))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest functions-catalog-is-derived-new-fn-appears
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (is (not (str/includes?
                       (agent/functions-catalog-section
                         {:seon.db/db @conn :seon.agent/id agent-id})
                       "zzcat.domain/helper"))
                "throwaway fn absent before it's transacted")
            ;; Transact a :seon.ns + :seon.fn in a brand-new AGENT-authored
            ;; namespace (non-seon.*, the taught domain-ns shape) — the
            ;; same rows detect-and-tee writes. (Pass :seon.db/conn explicitly:
            ;; the db/*conn* binding does not survive .then — see fixture.)
            ;; Stamped as an AGENT tx — exactly what a live eval tee
            ;; does (it runs inside the agent's with-agent scope). The
            ;; V3-C classifier derives agent-authored-ness from this
            ;; provenance, not from the ns name.
            (-> (db/with-tx-context {:seon.db/agent-id agent-id}
                  (fn []
                    (db/transact!
                      {:seon.db/conn conn
                       :seon.db/tx-data
                       [{:seon.ns/name :zzcat.domain
                         :seon.ns/source "(ns zzcat.domain)"}
                        {:seon.fn/sym      "zzcat.domain/helper"
                         :seon.fn/ns       [:seon.ns/name :zzcat.domain]
                         :seon.fn/arglists "([x])"
                         :seon.fn/doc      "A throwaway helper for the derivation test."
                         :seon.fn/source   "(defn helper [x] (inc x))"}]})))
                (.then (fn [_]
                         ;; rebind: the fixture's binding does not survive
                         ;; the .then boundary.
                         (let [after (binding [db/*conn* conn]
                                       (agent/functions-catalog-section
                                         {:seon.db/db @conn :seon.agent/id agent-id}))]
                           (is (str/includes? after "=== zzcat.domain ===")
                               "newly-defined fn's ns APPEARS — derived, not hardcoded")
                           ;; CALLABLE per-arity shape (2026-06-09 fix):
                           ;; arglists "([x])" renders as `(sym x)`, not the
                           ;; old bracket-wrapped `(sym [x])`.
                           (is (str/includes? after "(zzcat.domain/helper x)")
                               "the new fn's signature renders as a CALLABLE shape
                                (agent-authored ns → one line per fn)")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (l) exemplars-section — FULL exemplar source from the program graph
;;     (context-focus-redesign 2026-06-10, units E1+E2; roots swapped
;;     fs→todo in context-v3 unit 2). Guards: full seon.agent.search/seon.agent.todo
;;     + test-sibling source renders, in deterministic
;;     order; byte-stable across renders (cache-prefix invariant);
;;     a stubbed root renders nothing (fail-loud, no stub padding); the whole
;;     turn-0 context respects the spec's budget ceiling.
;; ---------------------------------------------------------------------------

(deftest exemplars-section-renders-full-source-in-order
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}
                  txt   (agent/exemplars-section input)
                  full  (:seon.render/text
                          (agent/assemble-context
                            {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? txt "<exemplars>") "wrapper marker present")
              (is (str/includes? full "<exemplar ns=\"seon.agent.search\">")
                  "the exemplar section reaches the assembled context")
              ;; FULL source, not reconstituted/clipped blocks.
              (is (str/includes? txt "(ns seon.agent.search")
                  "seon.agent.search's real ns form renders")
              (is (str/includes? txt "(defn ^:async grep")
                  "grep's full defn body renders (not a 240-char clip)")
              (is (str/includes? txt "(ns seon.agent.todo")
                  "seon.agent.todo renders too")
              (is (str/includes? txt "(defn ^:async add!")
                  "add!'s full defn body renders")
              (is (str/includes? txt "(deftest match-found-with-path-line-text")
                  "search's test sibling renders a full deftest body")
              (is (str/includes? txt "(deftest the-store-retrieve-arc-with-resume")
                  "todo's test sibling renders a full deftest body")
              (is (not (str/includes? txt "<exemplar ns=\"seon.agent.fs\">"))
                  "seon.agent.fs rotated OUT of the exemplar set (context-v3 unit 2)")
              ;; V3-B: the my.kb scaffold renders at full source too —
              ;; root (provenance shapes + ns-doc guidance), the
              ;; my.kb.system instruction singleton, and the test sibling.
              (is (str/includes? txt "(ns my.kb\n")
                  "my.kb's real ns form renders (the fn-less root)")
              (is (str/includes? txt "(ns my.kb.system")
                  "my.kb.system (the system-wide instruction home) renders")
              (is (str/includes? txt "(deftest system-instructions-append-by-transact")
                  "my.kb's test sibling renders a full deftest body")
              ;; Deterministic order: my.kb → my.kb-test → my.kb.system
              ;; → search → search-test → todo → todo-test (alphabetical by
              ;; subject, test sibling after its subject).
              (let [idx      (fn [ns-str]
                               (str/index-of txt (str "<exemplar ns=\"" ns-str "\">")))
                    i-kb     (idx "my.kb")
                    i-kbtest (idx "my.kb-test")
                    i-sys    (idx "my.kb.system")
                    i-search (idx "seon.agent.search")
                    i-stest  (idx "seon.agent.search-test")
                    i-todo   (idx "seon.agent.todo")
                    i-ttest  (idx "seon.agent.todo-test")]
                (is (and i-kb i-kbtest i-sys i-search i-stest i-todo i-ttest)
                    "all seven exemplar blocks present")
                (is (< i-kb i-kbtest i-sys i-search i-stest i-todo i-ttest)
                    "render order is alphabetical by subject, test sibling
                     after its subject")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest exemplars-static-prefix-is-byte-stable
  ;; The cache-prefix invariant: system + capabilities + exemplars must be
  ;; BYTE-IDENTICAL across consecutive renders — no timestamps, no
  ;; map-order nondeterminism in the new section.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db     @conn
                  input  {:seon.db/db db :seon.agent/id agent-id}
                  prefix (fn []
                           (str (agent/system-section input)
                                (agent/capabilities-section input)
                                (agent/exemplars-section input)))
                  a      (prefix)
                  b      (prefix)]
              (is (pos? (count a)) "static prefix non-empty")
              (is (= a b) "two consecutive static-prefix renders are
                           byte-identical"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest exemplars-stub-root-renders-nothing-for-that-ns
  ;; A root whose :seon.ns/source is still the `(ns x)` stub (e.g. an
  ;; un-upgraded store) renders NOTHING for that ns — never the stub.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.ns/name   :seon.agent.search
                     :seon.ns/source "(ns seon.agent.search)"}]})
                (.then (fn [_]
                         (let [txt (agent/exemplars-section
                                     {:seon.db/db @conn :seon.agent/id agent-id})]
                           (is (not (str/includes? txt "<exemplar ns=\"seon.agent.search\">"))
                               "stubbed root omitted — no stub padding")
                           (is (str/includes? txt "<exemplar ns=\"seon.agent.todo\">")
                               "the other exemplars still render")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest turn0-context-respects-the-budget-ceiling
  ;; Spec E2 pass predicate: turn-0 total stays bounded with the FULL
  ;; exemplar set in place (measured design point ≈ 59k on the live pod;
  ;; this fixture's transcript/ns sections are smaller). Guard was 65k;
  ;; raised to 68k 2026-06-10 with the batch-2 reorg: the toolbelt moved
  ;; under seon.agent.* (seon.search → seon.agent.search etc.), and the
  ;; exemplar section renders FULL SOURCE, so identical content got
  ;; longer ns-name tokens (+1,208 chars in search src+test alone; the
  ;; renamed registry keys add +6/key in the schema catalog). Measured
  ;; fixture total post-rename: 66,265 (exemplars 47,775, capabilities
  ;; 9,782, schema-catalog 3,858, functions-catalog 1,432, system 1,768,
  ;; warnings 1,033, transcript 222, namespace-context 187).
  ;; Raised to 84k 2026-06-10 evening: V3-B (#14) added the my.kb
  ;; exemplar family (my.kb 1,830 + my.kb.instruction 6,086 +
  ;; my.kb-test 5,520 + wrappers ≈ +14k full-source chars) — the test
  ;; was red at HEAD 951dedb before V3-C touched anything (verified by
  ;; a stash run). Measured fixture total with my.kb: 80,421.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<exemplars>")
                  "budget measured WITH the exemplar payload present")
              (is (<= (count text) 84000)
                  (str "turn-0 context within the 84k budget — got "
                       (count text))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (e) Bounded-context guard — one huge eval result does NOT blow context.
;; ---------------------------------------------------------------------------

(deftest huge-eval-result-does-not-blow-context
  (async done
    (let [big-n 5000000]                            ; 5 MB result
      (-> (with-seeded-conn
            [(big-eval big-n)]
            (fn [conn]
              (let [db    @conn
                    ts    (agent/transcript-section
                            {:seon.db/db db :seon.agent/id agent-id})
                    full  (agent/render-prompt agent-id)
                    ;; Comfortable ceiling: a handful of capped rows +
                    ;; the other sections. Far below the 5 MB blob.
                    ceil  50000
                    ;; The full prompt additionally carries the ~45k
                    ;; byte-stable exemplar payload (context-focus-redesign
                    ;; E2) — a deliberate static cost, not result blow-up.
                    full-ceil (+ ceil 50000)]
                (is (< (count ts) ceil)
                    (str "transcript bounded despite " big-n
                         "-char result — got " (count ts)))
                (is (< (count full) full-ceil)
                    (str "render-prompt bounded — got " (count full)))
                ;; T7: the size clip now carries a GUIDING message in
                ;; place of a bare marker — a clip is feedback, not a
                ;; failure. The agent is taught how to narrow.
                (is (str/includes? ts "chars clipped at")
                    "the big result was clipped with the size marker")
                (is (str/includes? ts "Narrow it")
                    "the size clip carries a guiding narrow-it message")
                (is (str/includes? ts "(result :")
                    "guide points the agent at the live full value"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; (g) T7 display-surface guiding messages — pure unit tests on the render
;; helpers. A clip is FEEDBACK (errors are values the agent reads), so when
;; output is clipped the rendered row carries an ACTIONABLE guide, not a bare
;; marker. Small results render fully with NO guide (no false-positive noise).
;; ---------------------------------------------------------------------------

(deftest cap-result-body-leaves-a-small-result-clean
  (let [small "[0 1 2 3 4]"]
    (is (= small (agent/cap-result-body small))
        "under the cap → verbatim, no marker")
    (is (not (str/includes? (agent/cap-result-body small) "Narrow it"))
        "no guide on a small result — no false positive")))

(deftest cap-result-body-clips-a-huge-scalar-with-a-guiding-message
  (let [huge (apply str (repeat 5000 "z"))
        out  (agent/cap-result-body huge agent/eval-render-cap "hg0000abcd")]
    (testing "bounded to the display cap (+ the appended guide)"
      (is (< (count out) (+ agent/eval-render-cap 300))))
    (testing "carries the size marker AND a guiding narrow-it message"
      (is (str/includes? out "chars clipped at 1500"))
      (is (str/includes? out "Narrow it")))
    (testing "guide points at the live full value via (result :<eid>)"
      (is (str/includes? out "(result :hg0000abcd)")))))

(deftest cap-result-body-uses-placeholder-when-no-eid
  (let [huge (apply str (repeat 5000 "z"))
        out  (agent/cap-result-body huge)]
    (is (str/includes? out "(result :<id>)")
        "no eid → generic placeholder, still actionable")))

(deftest format-eval-row-small-result-is-clean
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/duration-ms 1})]
    (is (str/includes? row "3"))
    (is (not (str/includes? row "Narrow it")) "no guide on a small row")
    (is (not (str/includes? row "clipped")) "no clip marker on a small row")))

(deftest format-eval-row-huge-result-is-bounded-and-guided
  (let [huge-edn (pr-str (apply str (repeat 5000 "z")))
        row      (#'seon.ctx/format-eval-row
                   {:seon.eval/source "(big-string)" :seon.eval/ok? true
                    :seon.eval/result-edn huge-edn :seon.eval/id "hg0000002b"
                    :seon.eval/duration-ms 7})]
    (testing "row is bounded regardless of how large the result is"
      (is (< (count row) (+ agent/eval-render-cap 400))))
    (testing "guiding message present, anchored to the row's own eid"
      (is (str/includes? row "chars clipped at 1500"))
      (is (str/includes? row "Narrow it"))
      (is (str/includes? row "(result :hg0000002b)")))))

(deftest format-eval-row-row-bounded-collection-preview-keeps-its-guide
  ;; A large collection is bounded UPSTREAM (render-result-edn) into a preview
  ;; whose row-guide is prepended; that guide must survive format-eval-row's
  ;; display cap, and NOT trigger a second (size) guide — no double-noising.
  (let [edn (seval/render-result-edn "cc0000003c" (vec (range 5000)))
        row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(seon.db/query {…})" :seon.eval/ok? true
               :seon.eval/result-edn edn :seon.eval/id "cc0000003c"
               :seon.eval/duration-ms 12})]
    (is (str/includes? row "more clipped") "row-count guide survives")
    (is (str/includes? row "5000 rows"))
    (is (not (str/includes? row "Narrow it"))
        "no SECOND size guide — preview is already small (no double-noise)")
    (is (< (count row) 1000) "preview row is bounded")))

;; ---------------------------------------------------------------------------
;; (j) Unit #23 fix e — TERMINAL prompt redesign. Status block above (turn ·
;; since-user (cap N) · timestamp, plus pressure nudges when escalating),
;; contract line first, final line EXACTLY `<current-ns>=> ` — clean, no
;; trailing `; turn N`.
;; ---------------------------------------------------------------------------

(deftest prompt-section-is-a-clean-terminal-prompt
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db     @conn
                  prompt (agent/prompt-section
                           {:seon.db/db db :seon.agent/id agent-id})
                  lines  (str/split-lines prompt)]
              (is (= ";; You are at a ClojureScript REPL — reply ONLY with forms + ;; comments."
                     (first lines))
                  "contract line first")
              (is (re-find #"(?m)^;; ── turn \d+ · \d+ since-user \(cap \d+\) · \d{4}-\d{2}-\d{2}T[^\n]*──$"
                           prompt)
                  "status block: turn · since-user (cap N) · ISO timestamp")
              ;; the final line is EXACTLY the REPL prompt — ns + `=> `,
              ;; nothing after (the old `; turn N` tail is gone).
              (is (re-find #"(?m)^my\.agent\.ctx-260610=> $" prompt)
                  "final line is exactly `<current-ns>=> ` (clean)")
              (is (not (str/includes? prompt "; turn "))
                  "no trailing `; turn N` metadata on the prompt line")
              (is (str/ends-with? prompt "=> ")
                  "prompt string ends at the clean REPL prompt"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (k) Unit #23 fix f — captured println/prn output renders in the eval row
;; (a REPL shows print output next to the result).
;; ---------------------------------------------------------------------------

(deftest format-eval-row-shows-captured-print-output
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(println \"hi\")" :seon.eval/ok? true
               :seon.eval/result-edn "nil" :seon.eval/output "hi\n"
               :seon.eval/id "pr0000001a" :seon.eval/duration-ms 1})]
    (is (str/includes? row "hi\nnil")
        "captured output renders above the result, REPL-style"))
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3"
               :seon.eval/id "pr0000002b" :seon.eval/duration-ms 1})]
    (is (str/includes? row "> (+ 1 2)\n3")
        "no output attr → row unchanged (no blank line injected)")))

;; ---------------------------------------------------------------------------
;; (l) live-tile awareness section (live-tiles U5) — "what your human
;;     currently sees". Kills the false belief a live T2 proof caught: a
;;     DeepSeek agent replied "My tile is currently blank — I haven't set it
;;     up yet" while its tile showed the substrate welcome. Guards:
;;       • in the default ctx at priority 28 (after :functions-catalog,
;;         before :namespace-context);
;;       • default (welcome-wired) agent → section quotes the welcome twin
;;         and names the wired fn;
;;       • literal hiccup on the key → the section shows that hiccup
;;         VERBATIM (you see exactly what's wired);
;;       • throwing renderer → the section shows the error envelope (a
;;         broken tile NEVER silently vanishes);
;;       • no agent entity → "" (the unwired correctness floor).
;; ---------------------------------------------------------------------------

(defn boom-tile
  "Test tile renderer that always throws — the section's error-envelope
   target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  (throw (ex-info "deliberate ctx tile failure"
                  {:seon.ctx/live-tile-test true})))

(deftest substrate-default-ctx-has-live-tile-between-catalog-and-ns
  (let [secs  (agent/substrate-default-ctx)
        names (mapv :seon.ctx/name secs)
        lt    (first (filter #(= :live-tile (:seon.ctx/name %)) secs))]
    (is (some #{:live-tile} names)
        "substrate-default-ctx contains the :live-tile section")
    (is (= 28 (:seon.ctx/priority lt))
        ":live-tile renders at priority 28 — after :functions-catalog (27),
         before :namespace-context (30)")
    (is (= 'seon.ctx/live-tile-section (:seon.render/ai lt)))))

(deftest live-tile-section-quotes-the-welcome-twin-by-default
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<live-tile>")
                  "the awareness section reaches the assembled context")
              (is (str/includes? text
                                 "Wired: seon.render.live-tile/welcome")
                  "header names the wired fn — the agent sees HOW to change it")
              (is (str/includes? text "the substrate default")
                  "provenance: the welcome is the substrate default, not agent-wired")
              (is (re-find #"(?s)<live-tile>.*Good (morning|afternoon|evening|night)"
                           text)
                  "body is the welcome's :seon.render/ai twin — the agent can
                   never believe its tile is blank while the human sees the
                   welcome (the T2 false-belief incident)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest live-tile-section-shows-literal-hiccup-verbatim
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.agent/id agent-id
                     :seon.render.live-tile/content [:h1 "wired!"]}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [text (:seon.render/text
                                        (agent/assemble-context
                                          {:seon.db/db @conn
                                           :seon.agent/id agent-id}))]
                             (is (str/includes?
                                   text "Wired: literal hiccup on your entity")
                                 "header identifies the wired value as literal hiccup")
                             (is (str/includes? text "[:h1 \"wired!\"]")
                                 "body is the literal hiccup VERBATIM — you see
                                  exactly what's wired"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest live-tile-section-shows-error-envelope-on-throw
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.agent/id agent-id
                     :seon.render.live-tile/content
                     'seon.agent-context-test/boom-tile}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [text (:seon.render/text
                                        (agent/assemble-context
                                          {:seon.db/db @conn
                                           :seon.agent/id agent-id}))]
                             (is (str/includes? text "YOUR LIVE TILE IS BROKEN")
                                 "the twin says the renderer is broken — never a
                                  silent vanish")
                             (is (str/includes? text "boom-tile")
                                 "the broken twin names the wired fn")
                             (is (str/includes? text "deliberate ctx tile failure")
                                 "the envelope carries what the exception said")
                             (is (str/includes? text ":seon.error/message")
                                 "the :seon.error/* envelope shape renders in the
                                  section"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest live-tile-section-renders-nothing-without-an-agent-entity
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [txt (seon.ctx/live-tile-section
                        {:seon.db/db @conn
                         :seon.agent/id "AGTnoSuchAgent"})]
              (is (= "" txt)
                  "no agent entity → no tile resolves → the section suppresses
                   itself (the unwired correctness floor)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
