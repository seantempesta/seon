(ns seon.agent-context-test
  "Guard tests for the v4 composer (context-v4-repl-realism 2026-06-11).
   These pin the invariants the prior live bugs violated, plus the v4
   falsification lines:

     (a) an agent with NO stored `:seon.agent/ctx` still gets the FULL
         default context — context is a pure function of the DB with a
         CODE-default section layout, never empty.
     (b) agent-path (`render-prompt`) ≡ inspector-path
         (`inspect/ctx-preview`) ≡ the would-be persisted
         `:seon.agent.turn/prompt-text` for the same (db,id) — ONE
         composer, divergence impossible.
     (c) each section fn renders non-blank given seeded data.
     (d) the composed context contains the v4 section markers; the
         transcript interleaves messages + evals chronologically.
     (e) bounded-context guard — a single eval with a multi-MB result
         does NOT blow the agent's context (context-SAFETY invariant).
     (f) V4-1 — the `<system>` block is BYTE-IDENTICAL across agents,
         provider-neutral, and the agent id appears nowhere above
         `<your-entity>`; the four standing teachings render exactly
         once.
     (g) V4-4 — REPL-real eval rows (the pinned result-var glyph),
         the session-resume boundary marker, prior-session rows
         without handles, `(result <old-id>)` prior-session wording,
         oldest-first eval eviction with messages exempt.
     (h) V4-5 — the §2.9 status line is the ONLY timestamped line and
         carries ns/turn/since-user/inbox/agent-id.

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
;; section fns (current-session / messages / evals / current-ns) default to
;; `@db/*conn*` when no :seon.db/db is threaded.
;; ---------------------------------------------------------------------------

(def ^:private agent-id "AGTctxtest0001")        ; 14 chars (:seon.db/id)

(defn- seed-tx
  "tx-data for an agent with NO `:seon.agent/ctx`, a session with two
   turns. Turn 1: a user message + a FAILED eval (drives warnings).
   Turn 2: a successful eval in ns `:my.agent.ctx-260610` plus a
   `:seon.ns`/`:seon.fn` for that ns (drives current-ns). `extra-evals`
   lets a test append big-result evals to turn 2.

   Boot-equivalent rows live in [[boot-seed-tx]] — transacted
   separately under the `:substrate-seed` tx-context, exactly like a
   real pod boot."
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
            ;; LLM turns ALWAYS carry their prompt's char count
            ;; (with-turn! records it on the open tx). A PROMPTLESS
            ;; turn means substrate-scripted evals — rendered UNCAPPED
            ;; (seon.ctx/substrate-authored-turn?) — so the seeds must
            ;; look like real LLM turns for the clip tests to bite.
            :seon.agent.turn/prompt-chars 4321
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
            :seon.agent.turn/prompt-chars 4321
            :seon.agent.turn/evals
            (into [{:seon.eval/id "EVLctxtestK001"
                    :seon.eval/at (t 21)
                    :seon.eval/duration-ms 3
                    :seon.eval/source "(defn greet [] :hi)"
                    :seon.eval/ok? true
                    :seon.eval/result-edn "#'my.agent.ctx-260610/greet"
                    :seon.eval/ns :my.agent.ctx-260610}]
                  extra-evals)}]}]}
       ;; Program-graph entities for the agent's current ns so the
       ;; namespaces-section reconstitutes it.
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
   OUTSIDE the seed context as an AGENT-registered domain attr."
  []
  (vec
    (concat
      ;; the user entity + the my.kb.system instruction singleton
      ;; (read by eval — the :instructions section died, V4-0)
      (client/seed-substrate!)
      ;; the introspection-indexed core-fn :seon.ns + :seon.fn rows
      (client/index-substrate!)
      ;; the :seon.schema entities for every entity kind
      (schema/all-entity-schemas-tx-data)
      ;; the whole-registry :seon.schema rows (unit #23 fix b)
      (client/index-schemas)
      ;; the full-source TEST SIBLINGS' :seon.ns + :seon.test rows — the
      ;; preload-populated default roster is empty in the :node-test
      ;; build, so seed one deftest per sibling explicitly (the SAME
      ;; builder the pod boot uses). Drives the :namespaces section's
      ;; test-sibling tags.
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
              (is (= [:system :namespaces :your-entity :live-tile
                      :warnings :open-todos :transcript :prompt]
                     sections)
                  "the v4 substrate-default section names, in order
                   (static→volatile) — the catalogs, capabilities,
                   exemplars, namespace-context, and seed sections are
                   all DEAD (context-v4)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (b) agent-path ≡ inspector-path ≡ would-be persisted prompt-text.
;; ---------------------------------------------------------------------------

;; The §2.9 status line embeds the wall-clock time, so two renders
;; microseconds apart differ ONLY on that line. Normalize it away
;; before comparing — everything else is a pure function of the DB and
;; must be byte-identical across the three paths.
(defn- strip-now [s]
  (str/replace s #"(?m)^;; ── \S+ · turn [^\n]*$" ";; ── <STATUS NORMALIZED> ──"))

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
                  ent   (db/pull {:seon.db/db db
                                  :seon.db/pull-pattern
                                  '[:db/id :seon.agent/id :seon.agent/state
                                    :seon.agent/purpose]
                                  :seon.db/ref [:seon.agent/id agent-id]})
                  input {:seon.db/db db :seon.agent/id agent-id
                         :seon.agent/entity ent}]
              (is (not (str/blank? (agent/system-section input))) "system")
              (is (not (str/blank? (agent/namespaces-section input)))
                  "namespaces — non-blank because :seon.ns rows are seeded")
              (is (not (str/blank? (agent/your-entity-section input)))
                  "your-entity — non-blank because the agent entity exists")
              (is (not (str/blank? (agent/warnings-section input)))
                  "warnings — the seeded failed eval surfaces")
              (is (not (str/blank? (agent/transcript-section input)))
                  "transcript — seeded messages + evals")
              (is (not (str/blank? (agent/prompt-section input))) "prompt"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d) Composed context contains the v4 section markers; the dead sections'
;;     markers are GONE.
;; ---------------------------------------------------------------------------

(deftest composed-context-includes-v4-markers-and-excludes-dead-ones
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<system>") "system marker present")
              (is (str/includes? text "<namespace name=\"seon.agent.todo\">")
                  "namespace tags present")
              (is (str/includes? text "<your-entity>") "your-entity present")
              (is (str/includes? text "<transcript>") "transcript present")
              (is (str/includes? text "<warnings>") "warnings present")
              ;; the dead sections (context-v4 falsification lines) —
              ;; LINE-anchored: rendered SOURCE CODE may legitimately
              ;; mention these strings; a real section opens its tag on
              ;; its own line.
              (doseq [dead ["<capabilities>" "<exemplars>" "<schema-catalog>"
                            "<functions>" "<store>" "<namespace-context>"
                            "<instructions>"]]
                (is (not (re-find (re-pattern (str "(?m)^" dead "$")) text))
                    (str dead " is dead — must not render"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d2) transcript-section interleaves messages + evals chronologically.
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
              (is (< i-user i-assistant)
                  "user message before assistant message")
              (is (< i-assistant i-failed)
                  "messages interleaved BEFORE the eval that followed them
                   (proves merge by :at, not message-block-then-eval-block)")
              (is (< i-failed i-success)
                  "failed eval (t13) before successful eval (t21)")
              ;; V4-4: REPL-real prompt lines — the eval renders under its
              ;; own ns prompt, not the old bare `> `.
              (is (str/includes? ts "my.agent.ctx-260610=> (defn greet [] :hi)")
                  "eval rows render as <ns>=> <form>"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d3) transcript budget eviction NEVER drops messages — the S-12 live
;;      defect. Messages are exempt; eval rows evict OLDEST-FIRST.
;; ---------------------------------------------------------------------------

(defn- flood-eval
  "One of N same-shaped big evals for the budget-eviction test — `i`
   disambiguates id/at; the 2000-char result renders at the 1500-char
   eval cap + clip guide, ≈1.8k chars/row. The +1h offset guarantees
   the flood sorts AFTER every [[seed-tx]] item."
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
                  "the OLDEST eval row was evicted FIRST — eviction still
                   works, it just no longer takes messages with it")
              (is (str/includes? ts "(flood 20)")
                  "the newest eval row is kept"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (f) V4-1 — the universal <system> block.
;; ---------------------------------------------------------------------------

(deftest system-block-is-universal-and-id-free
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))
                  sys  (subs text 0 (str/index-of text "</system>"))]
              ;; byte-identity across agents is structural: the block is a
              ;; constant (def), not a fn of the agent.
              (is (= (agent/system-section {:seon.agent/id "SOMEOTHERAGENT"})
                     (agent/system-section {:seon.agent/id agent-id}))
                  "<system> is byte-identical for every agent")
              (is (str/starts-with? text "<system>")
                  "the system tag carries NO agent attribute")
              ;; the agent id appears nowhere above <your-entity>.
              (is (not (str/includes?
                         (subs text 0 (str/index-of text "<your-entity>"))
                         agent-id))
                  "agent id absent from the cacheable prefix — it lives in
                   the status line")
              ;; provider-neutral: no model/vendor words, ever.
              (doseq [w ["DeepSeek" "deepseek" "Claude" "claude" "GPT"
                         "OpenAI" "Anthropic" "LLM"]]
                (is (not (str/includes? sys w))
                    (str "provider word " w " must not appear in <system>")))
              ;; *1-family is PARKED — the system prompt must not mention it.
              (is (not (str/includes? sys "*1"))
                  "*1/*2/*3 are parked — unmentioned")
              ;; the four standing teachings render in <system> —
              ;; sentinel per teaching (plain substring count; JS
              ;; regexes have no \\Q quoting).
              (doseq [sentinel ["store-inventory"
                                "Store what you verify, without being asked"
                                "ONE reply per question"
                                "Your code is my.*"]]
                (let [n (loop [from 0 n 0]
                          (if-let [i (str/index-of sys sentinel from)]
                            (recur (+ i (count sentinel)) (inc n))
                            n))]
                  (is (= 1 n)
                      (str "teaching sentinel appears exactly once in "
                           "<system>: " sentinel)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (h2) store-inventory — the catalogs' replacement (V4-3, per-attr counts
;;      since fix-everything A3): one row per attr NAMESPACE carrying every
;;      attr with live rows + its entity count; derived from datoms, so
;;      identity-less kinds are visible by construction and fully-retracted
;;      attrs vanish.
;; ---------------------------------------------------------------------------

(deftest store-inventory-lists-attr-namespaces-with-live-counts
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [inv     (db/store-inventory {:seon.db/db @conn})
                  kinds   (set (map :seon.db/kind inv))
                  by-kind (into {} (map (juxt :seon.db/kind identity)) inv)]
              (is (contains? kinds :seon.fn) ":seon.fn kind listed")
              (is (contains? kinds :seon.ns) ":seon.ns kind listed")
              (is (contains? kinds :seon.agent) ":seon.agent kind listed")
              (is (pos? (get-in by-kind [:seon.fn :seon.db/attrs :seon.fn/sym]))
                  "per-attr counts are live entity counts")
              (is (every? pos? (vals (get-in by-kind [:seon.agent :seon.db/attrs])))
                  "ONLY attrs with rows appear — never a zero count")
              (is (not-any? #(= "db" (namespace %))
                            (mapcat (comp keys :seon.db/attrs) inv))
                  "datahike's own :db/* attrs are excluded")
              ;; IDENTITY-LESS kind: visible by construction (the S-21
              ;; killer — :my.workout/* had no identity attr and was
              ;; invisible to the old identity-derived inventory).
              (is (not (contains? kinds :zzinv.domain))
                  "throwaway kind absent before any data")
              (schema/register! :zzinv.domain/note :string)
              (-> (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data [{:zzinv.domain/note "one"}
                                       {:zzinv.domain/note "two"}]})
                  (.then
                    (fn [_]
                      (let [inv'  (db/store-inventory {:seon.db/db @conn})
                            kinds (mapv :seon.db/kind inv')
                            row   (->> inv'
                                       (filter #(= :zzinv.domain (:seon.db/kind %)))
                                       first)
                            idx   (fn [k] (.indexOf kinds k))]
                        (is (= {:zzinv.domain/note 2} (:seon.db/attrs row))
                            "identity-less kind appears with per-attr
                             entity counts the moment data lands")
                        ;; ORDERING — consult-first: user-domain kinds
                        ;; (registered OUTSIDE the :substrate-seed boot
                        ;; index — :zzinv.domain here, despite sorting
                        ;; LAST alphabetically) come BEFORE substrate
                        ;; kinds; alphabetical within each group.
                        (is (< (idx :zzinv.domain) (idx :seon.fn))
                            "user-domain kind sorts BEFORE substrate
                             kinds — the inventory leads with what prior
                             agents stored for THIS human")
                        (is (< (idx :seon.agent) (idx :seon.fn))
                            "substrate group stays alphabetical"))))
                  ;; retract ALL rows → the kind vanishes from the next run.
                  (.then
                    (fn [_]
                      (let [db   @conn
                            eids (map first
                                      (db/query {:seon.db/db db
                                                 :seon.db/query
                                                 '[:find ?e :where
                                                   [?e :zzinv.domain/note]]}))]
                        (db/transact!
                          {:seon.db/conn conn
                           :seon.db/tx-data
                           (vec (for [e eids]
                                  [:db/retractEntity e]))}))))
                  (.then
                    (fn [_]
                      (let [kinds'' (set (map :seon.db/kind
                                              (db/store-inventory
                                                {:seon.db/db @conn})))]
                        (is (not (contains? kinds'' :zzinv.domain))
                            "fully-retracted kind vanishes — derived from
                             datoms, not from registration"))))))))
        (.then (fn [_]
                 (swap! schema/*schemas dissoc :zzinv.domain/note)
                 (done)))
        (.catch (fn [e]
                  (swap! schema/*schemas dissoc :zzinv.domain/note)
                  (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (l) namespaces-section in the assembled context — full source for the
;;     full-source set, shallow tags for unsplit substrate, byte-stable
;;     static prefix.
;; ---------------------------------------------------------------------------

(deftest namespaces-render-full-source-for-the-full-source-set
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}
                  txt   (agent/namespaces-section input)]
              ;; FULL source for the full-source set.
              (is (str/includes? txt "(ns seon.agent.search")
                  "seon.agent.search's real ns form renders")
              (is (str/includes? txt "(defn ^:async grep")
                  "grep's full defn body renders")
              (is (str/includes? txt "(defn ^:async add!")
                  "seon.agent.todo's full source renders")
              (is (str/includes? txt "(deftest match-found-with-path-line-text")
                  "search's test sibling renders a full deftest body")
              (is (str/includes? txt "(ns my.kb\n")
                  "my.kb renders full source (my.* rule)")
              (is (str/includes? txt "(ns my.kb.system")
                  "my.kb.system (the system-wide instruction home) renders")
              ;; shallow tags exist for unsplit substrate — present but
              ;; ns-form only.
              (is (str/includes? txt "<namespace name=\"seon.db\">")
                  "seon.db appears as a tag")
              (is (not (str/includes? txt "(defn transact!"))
                  "seon.db's body is NOT inlined (shallow tag until the
                   *.internal split lands)")
              ;; the agent's own ns is just a tag (namespace-context died).
              (is (str/includes? txt "<namespace name=\"my.agent.ctx-260610\">")
                  "own ns is a tag like any other")
              (is (str/includes? txt "(defn greet [] :hi)")
                  "own ns reconstitutes its member source"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest sourceless-tee-ns-reconstitutes-and-bare-stubs-self-describe
  ;; fix-everything A3 (S-21 root): the tee's nested `{:seon.ns/name kw}`
  ;; upsert mints SOURCELESS ns rows for a prior agent's register! calls;
  ;; requiring `:seon.ns/source` in the section's join silently dropped
  ;; them — the agent could not see the domain anywhere. And a stub that
  ;; genuinely has no indexed source must SELF-DESCRIBE (a bare stub has
  ;; baited a fabricated quotation before — the judge-95 near-miss).
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   ;; tee-shaped register! row: NO :seon.ns/source anywhere.
                   [{:seon.schema/key :my.kb.zztest/note
                     :seon.schema/source
                     "(seon.schema/register! :my.kb.zztest/note :string)"
                     :seon.schema/created-at (js/Date.)
                     :seon.schema/ns {:seon.ns/name :my.kb.zztest}}]})
                (.then
                  (fn [_]
                    (let [txt (agent/namespaces-section
                                {:seon.db/db @conn :seon.agent/id agent-id})]
                      (is (str/includes? txt
                                         "<namespace name=\"my.kb.zztest\">")
                          "a SOURCELESS tee-minted ns renders a tag")
                      (is (str/includes? txt "(ns my.kb.zztest)")
                          "its ns form is synthesized from the name")
                      (is (str/includes?
                            txt
                            "(seon.schema/register! :my.kb.zztest/note :string)")
                          "a prior agent's register! source reconstitutes
                           from member rows alone")
                      (is (str/includes? txt "stub — source not indexed")
                          "bare/seed stubs SELF-DESCRIBE instead of
                           rendering as deceptively-empty source")
                      (is (not (str/includes? txt "(defn transact!"))
                          "self-description never inlines substrate
                           bodies")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest static-prefix-is-byte-stable
  ;; The cache-prefix invariant: system + namespaces must be
  ;; BYTE-IDENTICAL across consecutive renders — no timestamps, no
  ;; map-order nondeterminism.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db     @conn
                  input  {:seon.db/db db :seon.agent/id agent-id}
                  prefix (fn []
                           (str (agent/system-section input)
                                (agent/namespaces-section input)))
                  a      (prefix)
                  b      (prefix)]
              (is (pos? (count a)) "static prefix non-empty")
              (is (= a b) "two consecutive static-prefix renders are
                           byte-identical"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest turn0-context-respects-the-budget-ceiling
  ;; The v4 layout's turn-0 total stays bounded with the full-source
  ;; namespace payload in place. The previous design point was ≤84k;
  ;; v4 swaps capabilities+catalogs (~15k) for my.* full sources +
  ;; shallow substrate tags. Guard at 100k — if this grows, something
  ;; ported scar tissue or a huge ns joined the full-source set.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<namespace name=\"seon.agent.todo\">")
                  "budget measured WITH the namespace payload present")
              (is (<= (count text) 100000)
                  (str "turn-0 context within the 100k budget — got "
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
                    ;; The full prompt additionally carries the byte-stable
                    ;; namespace payload — a deliberate static cost, not
                    ;; result blow-up.
                    full-ceil (+ ceil 60000)]
                (is (< (count ts) ceil)
                    (str "transcript bounded despite " big-n
                         "-char result — got " (count ts)))
                (is (< (count full) full-ceil)
                    (str "render-prompt bounded — got " (count full)))
                (is (str/includes? ts "⚠ TRUNCATED at")
                    "the big result was clipped with the LOUD size marker")
                (is (str/includes? ts "live value is COMPLETE")
                    "the clip says the display, not the value, is clipped")
                (is (str/includes? ts "(result :")
                    "guide points the agent at the live full value"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; (g) Display-surface guiding messages + the V4-4 pinned glyph — pure unit
;; tests on the render helpers.
;; ---------------------------------------------------------------------------

(deftest cap-result-body-leaves-a-small-result-clean
  (let [small "[0 1 2 3 4]"]
    (is (= small (agent/cap-result-body small))
        "under the cap → verbatim, no marker")
    (is (not (str/includes? (agent/cap-result-body small) "TRUNCATED"))
        "no guide on a small result — no false positive")))

(deftest cap-result-body-clips-a-huge-scalar-with-a-guiding-message
  (let [huge (apply str (repeat 5000 "z"))
        out  (agent/cap-result-body huge agent/eval-render-cap "hg0000abcd")]
    (testing "bounded to the display cap (+ the appended guide)"
      (is (< (count out) (+ agent/eval-render-cap 500))))
    (testing "LOUD marker: shown of full chars, display-only clip"
      (is (str/includes? out "⚠ TRUNCATED at 1500 of 5000 chars"))
      (is (str/includes? out "live value is COMPLETE"))
      (is (str/includes? out "Never summarize or quote beyond")))
    (testing "guide points at the live full value via (result :<eid>)"
      (is (str/includes? out "(result :hg0000abcd)")))))

(deftest cap-result-body-uses-placeholder-when-no-eid
  (let [huge (apply str (repeat 5000 "z"))
        out  (agent/cap-result-body huge)]
    (is (str/includes? out "(result :<id>)")
        "no eid → generic placeholder, still actionable")))

(deftest format-eval-row-pinned-glyph
  ;; THE byte-level pin for the V4-4 result-var glyph (context-v4 §2.8
  ;; DECIDE(build), decided here): `<ns>=> <form>` then
  ;; `<value>  ; ⇒ (result :<id>) · <dur>ms`.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/duration-ms 1 :seon.eval/ns :my.agent.pin}
              false)]
    (is (= "my.agent.pin=> (+ 1 2)\n3  ; ⇒ (result :sm0000001a) · 1ms" row)
        "the pinned current-session glyph, byte-exact"))
  ;; prior-session rows render WITHOUT the result-var handle.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/duration-ms 1 :seon.eval/ns :my.agent.pin}
              true)]
    (is (= "my.agent.pin=> (+ 1 2)\n3" row)
        "prior-session rows carry NO result-var handle"))
  ;; errors keep a plain id footer — no derefable value exists.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(boom)" :seon.eval/ok? false
               :seon.eval/error "kaput" :seon.eval/id "er0000001a"
               :seon.eval/duration-ms 2 :seon.eval/ns :my.agent.pin}
              false)]
    (is (= "my.agent.pin=> (boom)\n;; ERROR kaput  ; # er0000001a · 2ms" row)
        "error rows carry the plain id footer, not a result var")))

(deftest format-eval-row-huge-result-is-bounded-and-guided
  (let [huge-edn (pr-str (apply str (repeat 5000 "z")))
        row      (#'seon.ctx/format-eval-row
                   {:seon.eval/source "(big-string)" :seon.eval/ok? true
                    :seon.eval/result-edn huge-edn :seon.eval/id "hg0000002b"
                    :seon.eval/duration-ms 7})]
    (testing "row is bounded regardless of how large the result is"
      (is (< (count row) (+ agent/eval-render-cap 600))))
    (testing "guiding message present, anchored to the row's own eid"
      (is (str/includes? row "⚠ TRUNCATED at 1500 of"))
      (is (str/includes? row "live value is COMPLETE"))
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
    (is (not (str/includes? row "TRUNCATED"))
        "no SECOND size guide — preview is already small (no double-noise)")
    (is (< (count row) 1000) "preview row is bounded")))

;; ---------------------------------------------------------------------------
;; (g3) Substrate-authored rows render IN FULL — the inventory-clip defect
;;      (2026-06-11): the creation-turn store-inventory result clipped at
;;      1500 chars BEFORE the user-domain rows rendered, so the surface
;;      defeated its own purpose. Substrate-scripted evals (a promptless
;;      owning turn — seon.ctx/substrate-authored-turn?) are OUR output and
;;      render uncapped (50k runaway backstop); agent evals keep the loud
;;      ⚠ clip (pinned by format-eval-row-huge-result-is-bounded-and-guided).
;; ---------------------------------------------------------------------------

(deftest format-eval-row-substrate-row-renders-whole
  (let [huge (pr-str (apply str (repeat 5000 "k")))
        row  (#'seon.ctx/format-eval-row
               {:seon.eval/source "(seon.db/store-inventory)"
                :seon.eval/ok? true
                :seon.eval/result-edn huge
                :seon.eval/id "sb0000004d"
                :seon.eval/duration-ms 3
                :seon.eval/ns :my.agent.pin
                :seon.ctx/substrate-authored? true}
               false)]
    (is (str/includes? row huge)
        "the full 5000-char substrate result renders WHOLE")
    (is (not (str/includes? row "TRUNCATED"))
        "no clip marker on a substrate-authored row"))
  ;; the 50k backstop still bites on a runaway substrate value.
  (let [runaway (apply str (repeat 60000 "r"))
        row     (#'seon.ctx/format-eval-row
                  {:seon.eval/source "(seon.db/store-inventory)"
                   :seon.eval/ok? true
                   :seon.eval/result-edn runaway
                   :seon.eval/id "sb0000005e"
                   :seon.eval/duration-ms 3
                   :seon.eval/ns :my.agent.pin
                   :seon.ctx/substrate-authored? true}
                  false)]
    (is (str/includes? row "⚠ TRUNCATED at 50000 of 60000")
        "the runaway backstop clips LOUDLY at 50k, never silently")))

(deftest session-evals-tag-substrate-authorship-from-promptless-turns
  (async done
    (let [big (pr-str (apply str (repeat 5000 "k")))
          at  (js/Date. (+ (.getTime (js/Date.)) 40))]
      (-> (with-seeded-conn
            (fn [conn]
              ;; A creation-shaped turn: NO prompt-chars (creation-evals!
              ;; passes prompt-text "" → with-turn! records 0; this seeds
              ;; the attr ABSENT, the same promptless classification).
              (-> (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data
                     [{:seon.agent.session/id "SESctxtest0001"
                       :seon.agent.session/turns
                       [{:seon.agent.turn/id "TRNctxtest0003"
                         :seon.agent.turn/at at
                         :seon.agent.turn/status :done
                         :seon.agent.turn/evals
                         [{:seon.eval/id "EVLctxtestSUB1"
                           :seon.eval/at at
                           :seon.eval/duration-ms 2
                           :seon.eval/source "(seon.db/store-inventory)"
                           :seon.eval/ok? true
                           :seon.eval/result-edn big
                           :seon.eval/ns :my.agent.ctx-260610}]}]}]})
                  (.then
                    (fn [_]
                      (let [db  @conn
                            es  (seon.ctx/session-evals agent-id db)
                            sub (->> es
                                     (filter #(= "EVLctxtestSUB1"
                                                 (:seon.eval/id %)))
                                     first)
                            agt (->> es
                                     (filter #(= "EVLctxtestK001"
                                                 (:seon.eval/id %)))
                                     first)
                            ts  (agent/transcript-section
                                  {:seon.db/db db :seon.agent/id agent-id})]
                        (is (true? (:seon.ctx/substrate-authored? sub))
                            "promptless turn → substrate-authored eval")
                        (is (false? (:seon.ctx/substrate-authored? agt))
                            "prompted (LLM) turn → agent eval")
                        (is (str/includes? ts big)
                            "the substrate result is IN the rendered
                             transcript, whole — agents see every
                             inventory row")
                        (is (not (str/includes?
                                   ts (str "TRUNCATED at 1500 of")))
                            "no agent-cap clip fired anywhere")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; (g2) V4-4 — session resume: the boundary marker, prior-session rendering,
;;      and the (result <old-id>) wording.
;; ---------------------------------------------------------------------------

(deftest transcript-renders-resume-boundary-and-strips-prior-handles
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            ;; open a SECOND session with one eval — the seeded session
            ;; becomes the prior one.
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.agent/id agent-id
                     :seon.agent/sessions
                     [{:seon.agent.session/id "SESctxtest0002"
                       :seon.agent.session/at (js/Date. (+ (.getTime (js/Date.)) 7200000))
                       :seon.agent.session/turns
                       [{:seon.agent.turn/id "TRNctxtest0003"
                         :seon.agent.turn/at (js/Date. (+ (.getTime (js/Date.)) 7200010))
                         :seon.agent.turn/status :done
                         :seon.agent.turn/evals
                         [{:seon.eval/id "EVLctxtestN001"
                           :seon.eval/at (js/Date. (+ (.getTime (js/Date.)) 7200020))
                           :seon.eval/duration-ms 2
                           :seon.eval/source "(+ 40 2)"
                           :seon.eval/ok? true
                           :seon.eval/result-edn "42"
                           :seon.eval/ns :my.agent.ctx-260610}]}]}]}]})
                (.then
                  (fn [_]
                    (binding [db/*conn* conn]
                      (let [ts (agent/transcript-section
                                 {:seon.db/db @conn :seon.agent/id agent-id})]
                        (is (str/includes? ts "session resumed")
                            "the resume boundary marker renders")
                        (is (= 1 (count (re-seq #"session resumed" ts)))
                            "ONE marker per resume")
                        ;; prior-session evals: no result-var handle.
                        (is (not (str/includes? ts "(result :EVLctxtestK001)"))
                            "prior-session eval carries NO result-var handle")
                        ;; current-session eval keeps its handle.
                        (is (str/includes? ts "(result :EVLctxtestN001)")
                            "current-session eval keeps its result var")
                        ;; the marker sits between the two sessions' evals.
                        (is (< (str/index-of ts "(defn greet [] :hi)")
                               (str/index-of ts "session resumed")
                               (str/index-of ts "(+ 40 2)"))
                            "marker sits between prior and current evals"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest lookup-result-misses-are-legible-error-values
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            ;; no globalThis stash exists for the seeded eval ids — exactly
            ;; the post-restart state.
            (let [prior   (seval/lookup-result "EVLctxtestK001")
                  errored (seval/lookup-result "EVLctxtestF001")
                  unknown (seval/lookup-result "EVLnosuchideee")]
              (is (false? (:seon.eval/ok? prior)))
              (is (str/includes? (:seon.error/message prior) "prior session")
                  "(result <old-id>) says PRIOR SESSION")
              (is (str/includes? (:seon.error/message errored) "ERRORED")
                  "an error eval's miss says it produced no value")
              (is (str/includes? (:seon.error/message unknown) "no eval")
                  "an unknown id says no such eval"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (h) V4-5 — the §2.9 status line + clean prompt.
;; ---------------------------------------------------------------------------

(deftest prompt-section-is-the-two-line-status-form
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db     @conn
                  prompt (agent/prompt-section
                           {:seon.db/db db :seon.agent/id agent-id})
                  full   (:seon.render/text
                           (agent/assemble-context
                             {:seon.db/db db :seon.agent/id agent-id}))]
              (is (re-find #"(?m)^;; ── my\.agent\.ctx-260610 · turn \d+ · \d+ since-user \(cap \d+\) · \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \S+ · inbox \d+ · agent AGTctxtest0001 ──$"
                           prompt)
                  "status line: ns · turn · since-user (cap) · localized
                   time+tz · inbox · agent id")
              (is (re-find #"(?m)^my\.agent\.ctx-260610=> $" prompt)
                  "final line is exactly `<current-ns>=> ` (clean)")
              (is (str/ends-with? prompt "=> ")
                  "prompt string ends at the clean REPL prompt")
              ;; V4-5 falsification: the STATIC prefix (system +
              ;; namespaces — everything above <your-entity>) carries no
              ;; clock bytes. (The live-tile twin between <your-entity>
              ;; and <warnings> legitimately mirrors what the human sees,
              ;; which today includes a dated greeting — a cache-contract
              ;; tension reported to the tiles lane.)
              (is (not (re-find #"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}"
                                (subs full 0 (str/index-of full "<your-entity>"))))
                  "no timestamps above <your-entity> — the static prefix
                   is clock-free"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (k) Captured println/prn output renders in the eval row.
;; ---------------------------------------------------------------------------

(deftest format-eval-row-shows-captured-print-output
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(println \"hi\")" :seon.eval/ok? true
               :seon.eval/result-edn "nil" :seon.eval/output "hi\n"
               :seon.eval/id "pr0000001a" :seon.eval/duration-ms 1
               :seon.eval/ns :my.agent.pin})]
    (is (str/includes? row "hi\nnil")
        "captured output renders above the result, REPL-style"))
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3"
               :seon.eval/id "pr0000002b" :seon.eval/duration-ms 1
               :seon.eval/ns :my.agent.pin})]
    (is (str/includes? row "my.agent.pin=> (+ 1 2)\n3")
        "no output attr → row unchanged (no blank line injected)")))

;; ---------------------------------------------------------------------------
;; (l2) live-tile awareness section (live-tiles U5) — context-v4 only fixes
;;      its SLOT: after :your-entity (30), before :warnings (40).
;; ---------------------------------------------------------------------------

(defn boom-tile
  "Test tile renderer that always throws — the section's error-envelope
   target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  (throw (ex-info "deliberate ctx tile failure"
                  {:seon.ctx/live-tile-test true})))

(deftest substrate-default-ctx-slots-live-tile-after-your-entity
  (let [secs  (agent/substrate-default-ctx)
        names (mapv :seon.ctx/name secs)
        lt    (first (filter #(= :live-tile (:seon.ctx/name %)) secs))]
    (is (= [:system :namespaces :your-entity :live-tile :warnings]
           (vec (take 5 names)))
        "the v4 slot order: your-entity → live-tile → warnings")
    (is (= 35 (:seon.ctx/priority lt))
        ":live-tile renders at 35 — after :your-entity (30), before
         :warnings (40)")
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
