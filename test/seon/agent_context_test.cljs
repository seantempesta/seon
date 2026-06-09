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
         `:seon.turn/prompt-text` for the same (db,id) — ONE composer,
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
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.inspect :as inspect]
    [seon.schema :as schema]))

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
   Turn 2: a successful eval in ns `:seon.agent.ctxtest` plus a
   `:seon.ns`/`:seon.fn` for that ns (drives current-ns). `extra-evals`
   lets a test append big-result evals to turn 2.

   The introspection-indexed core-fn `:seon.ns`/`:seon.fn` rows
   (`seon.client/index-substrate!`) are appended INTO the same tx-vector —
   the SAME data the pod seeds at boot — so `capabilities-section` has
   the persisted entities it derives the `## What you can do` block from
   (no parallel hardcoded fixture)."
  [extra-evals]
  (let [now (js/Date.)
        t   (fn [ms] (js/Date. (+ (.getTime now) ms)))]
    (into
      [{:seon.agent/id agent-id
        :seon.agent/state :idle
        :seon.agent/sessions
        [{:seon.session/id "SESctxtest0001"
          :seon.session/at (t 0)
          :seon.session/turns
          [{:seon.turn/id "TRNctxtest0001"
            :seon.turn/at (t 10)
            :seon.turn/status :done
            :seon.turn/messages
            [{:seon.message/id "MSGctxtest0001"
              :seon.message/from {:seon.user/id "user"}
              :seon.message/to [{:seon.agent/id agent-id}]
              :seon.message/content "build me a thing"
              :seon.message/at (t 11)
              :seon.message/hops 0}
             {:seon.message/id "MSGctxtest0002"
              :seon.message/from {:seon.agent/id agent-id}
              :seon.message/to [{:seon.user/id "user"}]
              :seon.message/content "on it"
              :seon.message/at (t 12)
              :seon.message/hops 1}]
            :seon.turn/evals
            [{:seon.eval/id "EVLctxtestF001"
              :seon.eval/at (t 13)
              :seon.eval/duration-ms 5
              :seon.eval/source "(seon.db/query [:bad])"
              :seon.eval/ok? false
              :seon.eval/error "boom — bad query"
              :seon.eval/ns :seon.agent.ctxtest}]}
           {:seon.turn/id "TRNctxtest0002"
            :seon.turn/at (t 20)
            :seon.turn/status :done
            :seon.turn/evals
            (into [{:seon.eval/id "EVLctxtestK001"
                    :seon.eval/at (t 21)
                    :seon.eval/duration-ms 3
                    :seon.eval/source "(defn greet [] :hi)"
                    :seon.eval/ok? true
                    :seon.eval/result-edn "#'seon.agent.ctxtest/greet"
                    :seon.eval/ns :seon.agent.ctxtest}]
                  extra-evals)}]}]}
       ;; Program-graph entities for the agent's current ns so
       ;; namespace-context-section has source to render.
       {:seon.ns/name :seon.agent.ctxtest
        :seon.ns/source "(ns seon.agent.ctxtest)"}
       {:seon.fn/sym "seon.agent.ctxtest/greet"
        :seon.fn/ns [:seon.ns/name :seon.agent.ctxtest]
        :seon.fn/source "(defn greet [] :hi)"}]
      ;; the introspection-indexed core-fn :seon.ns + :seon.fn rows
      ;; (drives capabilities) — the SAME data the pod seeds at boot
      (concat
        (client/index-substrate!)
        ;; the :seon.schema entities for every entity kind — the SAME data
        ;; the pod seeds at boot; drives schema-catalog-section.
        (schema/all-entity-schemas-tx-data)
        ;; the whole-registry :seon.schema rows (unit #23 fix b) — drives
        ;; the schema-catalog's per-ns summary block. Deduped by key
        ;; against the entity rows above via identity upsert.
        (client/index-schemas)))))

(defn- with-seeded-conn
  "Open a fresh conn, seed it (optionally with `extra-evals` on turn 2),
   and run `body` (1-arg `conn`) with `db/*conn*` bound for the SYNC
   extent of `body`. `body`'s assertions must be synchronous: a plain
   `binding` does NOT survive Promise `.then` boundaries in CLJS (unlike
   the ALS-backed `db/with-agent`), so we rebind `db/*conn*` right
   around the synchronous `body` call. Returns a Promise."
  ([body] (with-seeded-conn [] body))
  ([extra-evals body]
   (-> (client/open-agent-conn!)
       (.then (fn [conn]
                ;; transact under the binding so tx-context defaults resolve,
                ;; then re-establish it around the synchronous body call.
                (binding [db/*conn* conn]
                  (-> (db/transact! {:seon.db/tx-data (seed-tx extra-evals)})
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
   :seon.eval/ns :seon.agent.ctxtest})

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
              (is (= [:system :capabilities :schema-catalog :functions-catalog
                      :namespace-context :warnings :transcript :prompt]
                     sections)
                  "the substrate-default section names, in order
                   (static→dynamic): system, capabilities, schema-catalog,
                   functions-catalog, namespace-context, warnings, transcript,
                   prompt"))))
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
                  ;; :seon.turn/prompt-text — assert that exact source.
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
                  i-success   (str/index-of ts "seon.agent.ctxtest/greet")]
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
;; (f) capabilities-section — the "## What you can do" worked-examples block.
;;     Guards the api-discoverability bug: the section must be in the default
;;     ctx, render the map-in call shapes, and be DERIVED from the persisted
;;     core :seon.fn arglists (not a hardcoded blob).
;; ---------------------------------------------------------------------------

(deftest substrate-default-ctx-includes-capabilities-after-system
  (let [names (mapv :seon.ctx/name (agent/substrate-default-ctx))]
    (is (some #{:capabilities} names)
        "substrate-default-ctx contains the :capabilities section")
    (is (= [:system :capabilities]
           (vec (take 2 names)))
        ":capabilities renders right after :system")
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
              ;; bounded — curated core API + worked examples, not a dump.
              ;; (raised from 4000 when the fs/search recipe, finding shape,
              ;; pull/entity/listen! examples landed — demo-context fixes.)
              (is (< (count cap) 11000)
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
    (is (= [:system :capabilities :schema-catalog :functions-catalog]
           (vec (take 4 names)))
        ":schema-catalog and :functions-catalog sit between :capabilities
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
                         :seon.message :seon.test]]
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
              (is (str/includes? txt "[:seon.message]  (per-turn data — uncounted)")
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
              [:map {:seon.render/ai 'seon.handlers.fn/render-ai}
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
    (is (= [:system :capabilities :schema-catalog :functions-catalog
            :namespace-context]
           (vec (take 5 names)))
        ":functions-catalog sits right after :schema-catalog and before
         :namespace-context")))

(deftest functions-catalog-own-ns-full-other-ns-brief
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
              ;; current-ns derives to :seon.agent.ctxtest (the latest ok eval);
              ;; the seeded greet fn lives there, so it is the agent's OWN ns.
              (is (str/includes? txt "=== seon.agent.ctxtest  (your ns) ===")
                  "the agent's own ns is flagged (your ns)")
              ;; OWN-ns fn renders WITH full source.
              (is (str/includes? txt "(defn greet [] :hi)")
                  "own-ns fn (greet) shows its full source body")
              ;; OTHER-ns rendering scales with the widened corpus (unit #23
              ;; fix b): SMALL nses render one callable line per fn; LARGE
              ;; nses (seon.db with the whole package indexed) collapse to a
              ;; count line — the DB carries everything, context shows the
              ;; index.
              (is (str/includes? txt "=== seon.db ===")
                  "other namespaces (seon.db) are listed")
              (is (re-find #"=== seon\.db ===  \d+ fns" txt)
                  "large other-ns group collapses to a count line")
              (is (str/includes? txt "(seon.schema/register! k v)")
                  "small other-ns group renders one callable line per fn")
              ;; transact!'s real source is long + multiline; the catalog
              ;; must NOT inline another ns's full source.
              (let [db-section (subs txt (str/index-of txt "=== seon.db ==="))]
                (is (not (str/includes? db-section "(defn transact!"))
                    "other-ns fn does NOT inline its full (defn …) source")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest functions-catalog-is-derived-new-fn-appears
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (is (not (str/includes?
                       (agent/functions-catalog-section
                         {:seon.db/db @conn :seon.agent/id agent-id})
                       "zzcat/helper"))
                "throwaway fn absent before it's transacted")
            ;; Transact a :seon.ns + :seon.fn in a brand-new namespace — the
            ;; same shape detect-and-tee writes. (Pass :seon.db/conn explicitly:
            ;; the db/*conn* binding does not survive .then — see fixture.)
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.ns/name :seon.zzcat
                     :seon.ns/source "(ns seon.zzcat)"}
                    {:seon.fn/sym      "seon.zzcat/helper"
                     :seon.fn/ns       [:seon.ns/name :seon.zzcat]
                     :seon.fn/arglists "([x])"
                     :seon.fn/doc      "A throwaway helper for the derivation test."
                     :seon.fn/source   "(defn helper [x] (inc x))"}]})
                (.then (fn [_]
                         ;; rebind: the fixture's binding does not survive
                         ;; the .then boundary, and functions-catalog-section
                         ;; reaches @db/*conn* for cross-ns fn rows.
                         (let [after (binding [db/*conn* conn]
                                       (agent/functions-catalog-section
                                         {:seon.db/db @conn :seon.agent/id agent-id}))]
                           (is (str/includes? after "=== seon.zzcat ===")
                               "newly-defined fn's ns APPEARS — derived, not hardcoded")
                           ;; CALLABLE per-arity shape (2026-06-09 fix):
                           ;; arglists "([x])" renders as `(sym x)`, not the
                           ;; old bracket-wrapped `(sym [x])`.
                           (is (str/includes? after "(seon.zzcat/helper x)")
                               "the new fn's signature renders as a CALLABLE shape
                                (small other-ns group → one line per fn)")))))))
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
                    ceil  50000]
                (is (< (count ts) ceil)
                    (str "transcript bounded despite " big-n
                         "-char result — got " (count ts)))
                (is (< (count full) ceil)
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
  (let [row (#'agent/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/duration-ms 1})]
    (is (str/includes? row "3"))
    (is (not (str/includes? row "Narrow it")) "no guide on a small row")
    (is (not (str/includes? row "clipped")) "no clip marker on a small row")))

(deftest format-eval-row-huge-result-is-bounded-and-guided
  (let [huge-edn (pr-str (apply str (repeat 5000 "z")))
        row      (#'agent/format-eval-row
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
        row (#'agent/format-eval-row
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
              (is (re-find #"(?m)^seon\.agent\.ctxtest=> $" prompt)
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
  (let [row (#'agent/format-eval-row
              {:seon.eval/source "(println \"hi\")" :seon.eval/ok? true
               :seon.eval/result-edn "nil" :seon.eval/output "hi\n"
               :seon.eval/id "pr0000001a" :seon.eval/duration-ms 1})]
    (is (str/includes? row "hi\nnil")
        "captured output renders above the result, REPL-style"))
  (let [row (#'agent/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3"
               :seon.eval/id "pr0000002b" :seon.eval/duration-ms 1})]
    (is (str/includes? row "> (+ 1 2)\n3")
        "no output attr → row unchanged (no blank line injected)")))
