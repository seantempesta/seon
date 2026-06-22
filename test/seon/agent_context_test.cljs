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
         without `result/<id>` handles, `lookup-result` prior-session
         wording, oldest-first eval eviction with messages exempt.
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
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.agent.inspect :as inspect]
    [seon.ai :as ai]
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
   separately under the `:core-seed` tx-context, exactly like a
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
            ;; (with-turn! records it on the open tx); seeded here so the
            ;; turns look like real LLM turns. (Render caps no longer
            ;; branch on turn provenance — cap-split-2026-06-22 — the
            ;; result body caps at 16384 for every eval.)
            :seon.agent.turn/prompt-chars 4321
            ;; The wake that opened this turn — the human's message. The
            ;; threaded transcript renders its content as the turn's
            ;; <user> line. Turn 2 (below) carries NO woken-by, so its
            ;; <user> line is omitted (boot/manual-turn case). A NESTED
            ;; MAP sharing the message's IDENTITY (not a lookup ref):
            ;; datahike unifies it with the same-tx component message,
            ;; whereas a lookup ref to a same-tx tempid throws
            ;; entity-id/missing (the message has no eid yet).
            :seon.agent.turn/woken-by {:seon.agent.message/id "MSGctxtest0001"}
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
   `{:seon.db/origin :core-seed}` tx-context, because provenance
   is load-bearing since the S-21 fix (2026-06-10):
   `seon.warn/domain-attrs` treats any `:seon.schema/key` row asserted
   OUTSIDE the seed context as an AGENT-registered domain attr."
  []
  (vec
    (concat
      ;; the user entity + the my.kb.system instruction singleton
      ;; (read by eval — the :instructions section died, V4-0)
      (client/seed-core!)
      ;; the introspection-indexed core-fn :seon.ns + :seon.fn rows
      (client/index-core!)
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
   `{:seon.db/origin :core-seed}`, then the runtime fixture
   [[seed-tx]] in an ordinary tx. Returns a Promise."
  ([body] (with-seeded-conn [] body))
  ([extra-evals body]
   (-> (client/open-agent-conn!)
       (.then (fn [conn]
                ;; transact under the binding so tx-context defaults resolve,
                ;; then re-establish it around the synchronous body call.
                (binding [db/*conn* conn]
                  (-> (db/with-tx-context {:seon.db/origin :core-seed}
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
                      :warnings :reply-over-claim :open-todos
                      :relevant-source :transcript
                      :turns :inventory :prompt]
                     sections)
                  "the v4 core-default section names, in order
                   (static→volatile) — the catalogs, capabilities,
                   exemplars, namespace-context, and seed sections are
                   all DEAD (context-v4); :inventory is the cheap
                   <data-inventory> surface in the volatile tail.
                   :reply-over-claim (#51) is in the LAYOUT provenance
                   even when it renders blank (no over-claim this turn).
                   :relevant-source (P2-D, env-gated default-OFF) is in
                   the LAYOUT provenance even when it renders blank — its
                   TEXT is dropped from the prompt (see ctx_test
                   off-path-is-byte-identical), so the assembled prompt
                   is unchanged when no retrieval stash is active"))))
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
                  ;; render-prompt is BLOCK 2 (the ctx / LLM user message);
                  ;; the adapters add the soul system block (BLOCK 1) at call
                  ;; time. The debug surfaces (inspector + persisted log) show
                  ;; the FULL prompt = soul + ctx via the ONE shared composer
                  ;; `seon.ai/debug-full-prompt`.
                  agent-text  (strip-now (agent/render-prompt agent-id))
                  composer    (strip-now
                                (:seon.render/text
                                  (agent/assemble-context
                                    {:seon.db/db db :seon.agent/id agent-id})))
                  full        (strip-now
                                (ai/debug-full-prompt
                                  {:seon.ai/ctx (agent/render-prompt agent-id)}))
                  inspector   (strip-now
                                (:seon.render/text
                                  (inspect/ctx-preview {:seon.agent/id agent-id})))]
              (is (pos? (count agent-text)) "agent path non-empty")
              (is (= agent-text composer)
                  "render-prompt == assemble-context (same composer, block 2)")
              (is (= full inspector)
                  "inspector left-pane == the FULL prompt the agent sees (soul + ctx) — no divergence"))))
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
              (is (str/includes? text "<past-evals>") "transcript present")
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
;; (d2) transcript-section threads turns: <turn id=… evals=N/M> blocks,
;;      each carrying its woken-by <user> line + REPL-faithful evals.
;; ---------------------------------------------------------------------------

(deftest transcript-threads-turns-with-user-and-evals
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  ts   (agent/transcript-section
                         {:seon.db/db db :seon.agent/id agent-id})
                  i-t1     (str/index-of ts "<turn id=TRNctxtest0001")
                  i-user   (str/index-of ts "<user>build me a thing</user>")
                  i-failed (str/index-of ts "=> ✗ boom — bad query")
                  i-t2     (str/index-of ts "<turn id=TRNctxtest0002")
                  i-success (str/index-of ts "my.agent.ctx-260610/greet")]
              (is (str/includes? ts "<past-evals>") "transcript envelope present")
              (is (str/includes? ts "</past-evals>") "transcript envelope closed")
              (is (and i-t1 i-user i-failed i-t2 i-success)
                  "both <turn> blocks, the <user> line, and both evals present")
              ;; turn 1 opens, its <user> woken-by line is INSIDE it,
              ;; then its failed eval; turn 2 follows with its success.
              (is (< i-t1 i-user i-failed i-t2 i-success)
                  "turn-1 (user + failed eval) renders before turn-2 (success)")
              ;; evals=N/M summary — turn 1 has 1 eval, 0 ok; turn 2 has
              ;; 1 eval, 1 ok (the success in this default seed).
              (is (str/includes? ts "<turn id=TRNctxtest0001 evals=0/1>")
                  "turn-1 summary: 0 ok of 1 total (the failed eval)")
              (is (str/includes? ts "<turn id=TRNctxtest0002 evals=1/1>")
                  "turn-2 summary: 1 ok of 1 total")
              ;; turn 2 has NO woken-by → NO <user> line. There is exactly
              ;; ONE <user> line in the whole transcript (turn 1's).
              (is (= 1 (count (re-seq #"<user>" ts)))
                  "the no-woken-by turn omits its <user> line")
              ;; the form renders verbatim, under a `;; in <ns>` marker;
              ;; NO `<ns>=>` prompt prefix on the form.
              (is (str/includes? ts "(defn greet [] :hi)")
                  "eval rows render the form verbatim")
              (is (not (str/includes? ts "=> (defn greet"))
                  "no <ns>=> history prompt prefix")
              (is (str/includes? ts ";; in my.agent.ctx-260610")
                  "ns shown via a ;; in <ns> marker on the ns change")
              ;; the success value rides a `=>` OUTPUT line (NOT a `;;`
              ;; comment) trailing its result/<id> handle.
              (is (str/includes? ts "=> #'my.agent.ctx-260610/greet ;; result/EVLctxtestK001")
                  "success value on a `=>` line with its result/<id> handle"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d3) transcript budget eviction is OLDEST-TURN-FIRST: the newest turns
;;      stay whole, older turns drop with an elision note.
;; ---------------------------------------------------------------------------

(defn- flood-turn
  "One of N same-shaped big TURNS for the budget-eviction test — `i`
   disambiguates ids/at; each turn's single 2000-char-result eval renders
   WHOLE (under the 16384 result-body cap), ≈2k chars, so 20 turns
   (~40k chars) overflow the 24k transcript budget and force eviction.
   The +1h offset guarantees the flood sorts AFTER every [[seed-tx]] turn."
  [i]
  (let [pad (.padStart (str i) 3 "0")
        at  (js/Date. (+ (.getTime (js/Date.)) 3600000 i))]
    {:seon.agent.turn/id (str "TRNctxflood" pad)
     :seon.agent.turn/at at
     :seon.agent.turn/status :done
     :seon.agent.turn/prompt-chars 4321
     :seon.agent.turn/evals
     [{:seon.eval/id (str "EVLctxflood" pad)
       :seon.eval/at at
       :seon.eval/duration-ms 4
       :seon.eval/source (str "(flood " i ")")
       :seon.eval/ok? true
       :seon.eval/result-edn (apply str (repeat 2000 "y"))
       :seon.eval/ns :my.agent.ctx-260610}]}))

(deftest transcript-eviction-keeps-newest-turns-drops-oldest
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (binding [db/*conn* conn]
              (-> (db/with-tx-context {:seon.db/origin :core-seed}
                    (fn []
                      (db/transact! {:seon.db/conn conn
                                     :seon.db/tx-data (boot-seed-tx)})))
                  (.then (fn [_]
                           ;; seed the two base turns…
                           (db/transact! {:seon.db/conn conn
                                          :seon.db/tx-data (seed-tx [])})))
                  (.then (fn [_]
                           ;; …then append 20 flood turns onto the session.
                           (db/transact!
                             {:seon.db/conn conn
                              :seon.db/tx-data
                              [{:seon.agent.session/id "SESctxtest0001"
                                :seon.agent.session/turns
                                (mapv flood-turn (range 1 21))}]})))
                  (.then
                    (fn [_]
                      (binding [db/*conn* conn]
                        (let [db @conn
                              ts (agent/transcript-section
                                   {:seon.db/db db :seon.agent/id agent-id})]
                          (is (str/includes? ts "older turn")
                              "the elision note fired — the flood overflowed the budget")
                          (is (not (str/includes? ts "<turn id=TRNctxtest0001"))
                              "the OLDEST turn was evicted FIRST")
                          (is (str/includes? ts "(flood 20)")
                              "the newest turn is kept whole")))))))))
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
              ;; ("store-inventory" itself now legitimately appears
              ;; twice: the consult call AND the taught
              ;; {:seon.db/system? true} full-inventory form.)
              (doseq [sentinel ["Consult stored knowledge FIRST"
                                "(seon.db/store-inventory {:seon.db/system? true})"
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
                        ;; (registered OUTSIDE the :core-seed boot
                        ;; index — :zzinv.domain here, despite sorting
                        ;; LAST alphabetically) come BEFORE core
                        ;; kinds; alphabetical within each group.
                        (is (< (idx :zzinv.domain) (idx :seon.fn))
                            "user-domain kind sorts BEFORE core
                             kinds — the inventory leads with what prior
                             agents stored for THIS human")
                        (is (< (idx :seon.agent) (idx :seon.fn))
                            "core group stays alphabetical"))))
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

(deftest store-inventory-splits-rows-by-bootstrap-provenance
  ;; Task #28 — the per-ROW origin split, never per-kind-name: a row
  ;; is BOOTSTRAP iff its identity datom landed under a
  ;; `:core-seed` tx (`seon.db/bootstrap-row-ids`, THE shared
  ;; derivation). The default inventory shows only post-bootstrap
  ;; data; `{:seon.db/system? true}` shows everything. The fixture is
  ;; exactly the discriminating world: boot-indexed `:seon.fn` rows
  ;; (bootstrap) AND the agent-authored greet fn row (same kind, data).
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  inv  (db/store-inventory {:seon.db/db db})
                  sys  (db/store-inventory {:seon.db/db db
                                            :seon.db/system? true})
                  row  (fn [v k]
                         (some #(when (= k (:seon.db/kind %)) %) v))
                  fn-default (get-in (row inv :seon.fn)
                                     [:seon.db/attrs :seon.fn/sym])
                  fn-system  (get-in (row sys :seon.fn)
                                     [:seon.db/attrs :seon.fn/sym])]
              (is (= 1 fn-default)
                  "PER-ROW: only the agent-authored greet row counts by
                   default — the boot index's :seon.fn rows are
                   bootstrap, the kind NAME decides nothing")
              (is (> fn-system 1)
                  "system view counts every row, boot index included")
              (is (nil? (row inv :my.kb.system))
                  "boot-seeded :my.kb.system rows are bootstrap — the
                   kind is absent by default DESPITE the my.* spelling
                   (provenance, never a name-list)")
              (is (some? (row sys :my.kb.system))
                  "…and visible in the system view")
              (is (nil? (row inv :seon.schema))
                  "the boot schema index is bootstrap")
              (is (nil? (row inv :seon.db))
                  "tx provenance entities are bookkeeping, not data rows")
              (is (some? (row sys :seon.db))
                  "…but the system view shows even those")
              ;; the shared derivation, consumed directly
              (let [boot  (db/bootstrap-row-ids db)
                    kb    (ffirst (db/query
                                    {:seon.db/db db
                                     :seon.db/query
                                     '[:find ?e :where
                                       [?e :my.kb.system/id _]]}))
                    greet (ffirst (db/query
                                    {:seon.db/db db
                                     :seon.db/query
                                     '[:find ?e :where
                                       [?e :seon.fn/sym
                                        "my.agent.ctx-260610/greet"]]}))]
                (is (contains? boot kb)
                    "the seeded kb.system row classifies bootstrap")
                (is (not (contains? boot greet))
                    "the agent-authored fn row classifies data"))
              (is (contains? (db/core-kinds db) :seon.fn)
                  ":seon.fn was registered by the boot index →
                   core kind (the inventory ordering filter)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (l) namespaces-section in the assembled context (B9 compact tiering) —
;;     every included ns renders COMPACT (ns form + schemas full + fns as
;;     elided defns, standalone tests DROPPED); the agent's OWN CURRENT ns
;;     renders full ONLY when it has real persisted source (this seed's own
;;     ns is a stub, so it too renders compact). Compiled-core stubs (e.g.
;;     seon.db) render COMPACT from their indexed member rows — PRESENT,
;;     bodies elided. A stub with NO member rows is omitted (nothing to show).
;; ---------------------------------------------------------------------------

(deftest namespaces-render-compact-with-elided-fn-bodies
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}
                  txt   (agent/namespaces-section input)]
              ;; The ns form renders (its :require deps are the dependency map).
              (is (str/includes? txt "(ns seon.agent.search")
                  "seon.agent.search's real ns form renders")
              ;; A fn renders as its real `defn` HEAD (signature + attr-map),
              ;; with the BODY elided to `…)` — not the full implementation.
              (is (str/includes? txt "(defn ^:async grep")
                  "grep renders as its real defn head")
              (is (str/includes? txt "(defn ^:async add!")
                  "seon.agent.todo's add! renders its defn head")
              (is (str/includes? txt "\n  …)")
                  "fn bodies are elided to `…)` (the B9 compact form)")
              ;; Standalone deftests are DROPPED from the prompt BODY (they
              ;; live in the on-demand render-namespace deep view).
              (is (not (str/includes? txt "(deftest match-found-with-path-line-text"))
                  "search's test sibling is DROPPED from the compact body")
              (is (str/includes? txt "(ns my.kb\n")
                  "my.kb renders its ns form (my.* rule)")
              (is (str/includes? txt "(ns my.kb.system")
                  "my.kb.system (the system-wide instruction home) renders")
              ;; compiled-core stubs render COMPACT from their indexed
              ;; member rows — PRESENT (a tag), bodies elided (API surface).
              (is (str/includes? txt "<namespace name=\"seon.db\">")
                  "seon.db (a compiled-core stub) renders compact — has a tag")
              (is (str/includes? txt "(defn ^:async transact!")
                  "seon.db's transact! renders as an elided defn head")
              (is (not (str/includes? txt "normalize-transact-args"))
                  "seon.db's transact! BODY is elided — not inlined")
              ;; the agent's own current ns renders compact here (its seed
              ;; source is a bare stub — no full text to show); its member
              ;; fn `greet` shows as an elided defn head.
              (is (str/includes? txt "<namespace name=\"my.agent.ctx-260610\">")
                  "own ns is a tag like any other")
              (is (str/includes? txt "(defn greet []")
                  "own ns renders its member fn as an elided defn head"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest sourceless-tee-ns-reconstitutes-and-stub-core-renders-compact
  ;; fix-everything A3 (S-21 root): the tee's nested `{:seon.ns/name kw}`
  ;; upsert mints SOURCELESS ns rows for a prior agent's register! calls;
  ;; requiring `:seon.ns/source` in the section's join silently dropped
  ;; them — the agent could not see the domain anywhere. A ns that owns
  ;; member rows RECONSTITUTES from them regardless of provenance: a
  ;; compiled-core stub (seon.db) renders COMPACT from its indexed
  ;; member rows (PRESENT, bodies elided), and a sourceless agent-minted
  ;; ns renders its members the same way. Only a stub with NO member
  ;; rows yields a blank body and is omitted (nothing to show).
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
                      (is (str/includes?
                            txt "<namespace name=\"seon.db\">")
                          "a compiled-core ns (stub source) renders compact
                           from its member rows — PRESENT")
                      (is (str/includes? txt "(defn ^:async transact!")
                          "seon.db's transact! shows as an elided defn head")
                      (is (not (str/includes? txt "source not indexed"))
                          "the old self-describing stub marker is gone")
                      (is (not (str/includes? txt "normalize-transact-args"))
                          "seon.db's fn BODIES stay elided — never
                           inlined")))))))
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
  ;; The v4 layout's turn-0 total stays bounded. my.* render full source;
  ;; every other included ns (incl. compiled core like seon.db) renders
  ;; COMPACT — ns form + full schemas + elided fn heads, bodies dropped.
  ;; The whole compiled core is now visible compact (~200k here): the
  ;; dominant cost is fn-head docstrings + :malli/schema attr-maps, not
  ;; bodies. Guard at 250k — if this grows, something ported scar tissue
  ;; or a huge ns started inlining full BODIES, not the compact surface.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<namespace name=\"seon.agent.todo\">")
                  "budget measured WITH the namespace payload present")
              (is (<= (count text) 250000)
                  (str "turn-0 context within the 250k budget — got "
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
                    ;; namespace payload — a deliberate static cost (the whole
                    ;; compiled core, compact: fn heads + schemas, bodies
                    ;; elided), not result blow-up.
                    full-ceil (+ ceil 200000)]
                (is (< (count ts) ceil)
                    (str "transcript bounded despite " big-n
                         "-char result — got " (count ts)))
                (is (< (count full) full-ceil)
                    (str "render-prompt bounded — got " (count full)))
                (is (str/includes? ts "⚠ TRUNCATED at")
                    "the big result was clipped with the LOUD size marker")
                (is (str/includes? ts "live value is COMPLETE")
                    "the clip says the display, not the value, is clipped")
                (is (str/includes? ts "result/")
                    "guide points the agent at the live full value var"))))
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
        "no guide on a small result — no false positive"))
  ;; #53 fix: a result body between the OLD 1500 cap and the store
  ;; ceiling now renders WHOLE under the default arity (the body cap is
  ;; result-body-render-cap=16384, NOT eval-render-cap=1500). A
  ;; store-inventory-sized (~2800-char) result no longer clips mid-value.
  (let [mid (apply str (repeat 2800 "m"))]
    (is (= mid (agent/cap-result-body mid))
        "a 2800-char result (between 1500 and 16384) renders WHOLE")
    (is (not (str/includes? (agent/cap-result-body mid) "TRUNCATED"))
        "no clip below the 16384 body cap — the #53 mid-value clip is gone")))

(deftest cap-result-body-clips-a-huge-scalar-with-a-guiding-message
  (let [huge (apply str (repeat 5000 "z"))
        out  (agent/cap-result-body huge agent/eval-render-cap "hg0000abcd")]
    (testing "bounded to the display cap (+ the appended guide)"
      (is (< (count out) (+ agent/eval-render-cap 500))))
    (testing "LOUD marker: shown of full chars, display-only clip"
      (is (str/includes? out "⚠ TRUNCATED at 1500 of 5000 chars"))
      (is (str/includes? out "live value is COMPLETE"))
      (is (str/includes? out "Never summarize or quote beyond")))
    (testing "guide points at the live full value via the result/<id> var"
      (is (str/includes? out "result/hg0000abcd")))))

(deftest cap-result-body-uses-placeholder-when-no-eid
  ;; The default arity caps the citable result body at
  ;; `result-body-render-cap` (16384), so the input must exceed THAT to
  ;; clip — a 5000-char string now renders WHOLE (the #53 fix).
  (let [huge (apply str (repeat (+ ctx/result-body-render-cap 1000) "z"))
        out  (agent/cap-result-body huge)]
    (is (str/includes? out "result/<id>")
        "no eid → generic placeholder, still actionable")
    (is (str/includes? out
                       (str "⚠ TRUNCATED at " ctx/result-body-render-cap " of"))
        "the default body cap is result-body-render-cap (16384), not 1500")))

(deftest format-eval-row-repl-faithful-stream
  ;; THE byte-level pin for the transcript-redesign render (2026-06-18):
  ;; comments + form + a `=> value ;; result/<id>` OUTPUT line that is
  ;; visually DISTINCT from `;;` comments. No `<ns>=>` history prompt
  ;; prefix (the live cursor lives once at the END of the context).
  ;; Failures render `=> ✗ <guidance>` with NO result/<id>.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/ns :my.agent.pin
               :seon.eval/narration "add 1 and 2"}
              false)]
    (is (= (str ";; add 1 and 2\n"
                "(+ 1 2)\n"
                "=> 3 ;; result/sm0000001a")
           row)
        "the pinned current-session row, byte-exact")
    (is (not (str/includes? row "=> (+ 1 2)"))
        "no <ns>=> history prompt prefix on the form")
    ;; the value line is REPL OUTPUT, not a comment: it starts with `=>`,
    ;; not `;;`. This is the agents-confusing-output-for-comments fix.
    (is (some #(str/starts-with? % "=> ") (str/split-lines row))
        "the value rides a `=>` output line, distinct from `;;` comments"))
  ;; prior-session rows render WITHOUT the result-var handle.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "sm0000001a"
               :seon.eval/ns :my.agent.pin}
              true)]
    (is (= "(+ 1 2)\n=> 3" row)
        "prior-session rows carry NO result/<id> handle"))
  ;; errors render `=> ✗ <guidance>` — no result/<id> (no value to reuse).
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(boom)" :seon.eval/ok? false
               :seon.eval/error "kaput" :seon.eval/id "er0000001a"
               :seon.eval/ns :my.agent.pin}
              false)]
    (is (= "(boom)\n=> ✗ kaput" row)
        "failure rows render the form then a crystal-clear `=> ✗` line")
    (is (not (str/includes? row "result/"))
        "a FAILED eval gets NO result/<id> — there is no value to reuse"))
  ;; a comment-only row (blank source) renders just its `;;` preamble.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "" :seon.eval/ok? true
               :seon.eval/id "cm0000001a"
               :seon.eval/ns :my.agent.pin
               :seon.eval/narration "just a trailing thought"}
              false)]
    (is (= ";; just a trailing thought" row)
        "comment-only row → only the ;; preamble, no form, no value")))

(deftest format-eval-row-clipped-value-annotates-N-of-M
  ;; a clipped value appends `(N of M)` to the result/<id> handle so the
  ;; agent knows the shown display is a partial view of a live whole value.
  ;; The result body clips at `result-body-render-cap` (16384), so the
  ;; value must exceed THAT to clip (a 5000-char value now renders whole).
  (let [full (+ ctx/result-body-render-cap 5000)
        huge (apply str (repeat full "z"))
        row  (#'seon.ctx/format-eval-row
               {:seon.eval/source "(big)" :seon.eval/ok? true
                :seon.eval/result-edn huge :seon.eval/id "cp0000001a"
                :seon.eval/ns :my.agent.pin}
               false)]
    (is (str/includes? row (str "result/cp0000001a ("
                                ctx/result-body-render-cap " of " full ")"))
        "the handle carries (shown of full) so the clip is unambiguous")
    (is (str/includes? row "live value is COMPLETE")
        "the size guide still fires for the clipped scalar")))

(deftest format-eval-row-roundtrips-to-forms-and-comments
  ;; round-trip: the rendered row re-parses to the SAME form + the `;;`
  ;; comment preamble; the runtime annotations (`=>` / `;; result/`) are
  ;; the only added lines (they read as comment/bare narration on re-parse).
  (let [row   (#'seon.ctx/format-eval-row
                {:seon.eval/source "(seon.db/transact! {:seon.db/tx-data []})"
                 :seon.eval/ok? true :seon.eval/result-edn "{:seon.db/ok? true}"
                 :seon.eval/id "rt0000001a" :seon.eval/ns :my.agent.pin
                 :seon.eval/narration "store the rows"}
                false)
        lines (str/split-lines row)]
    ;; the prose preamble came back as a `;;` comment, in position.
    (is (= ";; store the rows" (first lines))
        "narration prose re-renders as a `;;` comment line")
    ;; the form line is verbatim and re-readable.
    (is (some #(= "(seon.db/transact! {:seon.db/tx-data []})" %) lines)
        "the form re-renders verbatim (re-parseable to the same form)")
    ;; the value line is the runtime-owned `=>` annotation — distinct.
    (is (some #(str/starts-with? % "=> ") lines)
        "the value line is a `=>` annotation, not part of the agent's forms")))

;; ---------------------------------------------------------------------------
;; C-19 — model-authored result-claim comments are NEUTRALIZED in the
;; transcript (two live fabrication incidents, F13/F14: an agent wrote
;; `;; => …` narration that later turns trusted as a real read). The
;; provenance gate: narration + source are rewritten BEFORE the composer
;; appends the runtime-owned `=> <value> ;; result/<id>` value line, so
;; real result lines never pass through the rewrite.
;; ---------------------------------------------------------------------------

(deftest neutralize-result-claims-rewrites-claim-shapes
  ;; every result-claim shape variant is rewritten WHOLE — the claimed
  ;; value (the poison) is dropped, not quoted.
  (doseq [claim [";; => {:events 7}"
                 ";; ⇒ \"all stored\""
                 "; => 42"
                 ";⇒ :ok"
                 ";;=> [1 2 3]"]]
    (let [out (ctx/neutralize-result-claims claim)]
      (is (= ctx/unverified-narration-marker out)
          (str (pr-str claim) " rewritten to the marker"))
      (is (not (str/includes? out (subs claim (inc (str/last-index-of claim " ")))))
          "claimed value absent")))
  ;; ordinary narration / code is untouched — byte-identical.
  (doseq [plain [";; storing the events now"
                 ";; note: x maps to y, see => is not at comment start? no:"
                 "(def x 1)"
                 ";; TODO follow up"]]
    (is (= plain (ctx/neutralize-result-claims plain))
        (str (pr-str plain) " passes through byte-identical"))))

(deftest neutralize-result-claims-multiline-inline-idempotent
  ;; multi-line: claim lines rewritten IN POSITION, others untouched.
  (let [narr (str ";; reading the 7 events\n"
                  ";; => [{:e 1} {:e 2} {:e 3} {:e 4} {:e 5} {:e 6} {:e 7}]\n"
                  ";; all good")
        out  (ctx/neutralize-result-claims narr)]
    (is (= (str ";; reading the 7 events\n"
                ctx/unverified-narration-marker "\n"
                ";; all good")
           out)
        "line position preserved, only the claim line rewritten")
    (is (not (str/includes? out "{:e 1}")) "fabricated value gone"))
  ;; inline: code before the claim survives, the claim is replaced.
  (is (= (str "(+ 1 2) " ctx/unverified-narration-marker)
         (ctx/neutralize-result-claims "(+ 1 2) ;; => 99")))
  ;; idempotent: the marker does not match the claim shape.
  (is (= ctx/unverified-narration-marker
         (ctx/neutralize-result-claims ctx/unverified-narration-marker)))
  (let [once (ctx/neutralize-result-claims ";; => 1\n;; ⇒ 2")]
    (is (= once (ctx/neutralize-result-claims once))
        "re-applying changes nothing — no double-marking")))

(deftest format-eval-row-neutralizes-fake-claims-keeps-real-results
  ;; fake `;; =>` in stored narration → rewritten in the rendered row;
  ;; the runtime-owned `;; =>` value line is untouched (provenance gate:
  ;; it's appended by the composer AFTER neutralize).
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "fk0000001a"
               :seon.eval/ns :my.agent.pin
               :seon.eval/narration ";; => {:fabricated 7}"}
              false)]
    (is (= (str ";; [unverified narration — not a real result]\n"
                "(+ 1 2)\n"
                "=> 3 ;; result/fk0000001a")
           row)
        "fake claim neutralized; real value line is the runtime-owned `=>`")
    (is (not (str/includes? row ":fabricated")) "claimed value absent"))
  ;; inline claim inside SOURCE is neutralized too — code survives.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2) ;; => 99" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "fk0000002b"
               :seon.eval/ns :my.agent.pin}
              false)]
    (is (str/includes? row "(+ 1 2) ;; [unverified"))
    (is (not (str/includes? row "99")) "inline claimed value absent")
    (is (str/includes? row "=> 3 ;; result/fk0000002b")
        "real value line unaffected"))
  ;; clean narration renders the stream — no false positives.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "cl0000003c"
               :seon.eval/ns :my.agent.pin
               :seon.eval/narration ";; adding two numbers"}
              false)]
    (is (= (str ";; adding two numbers\n"
                "(+ 1 2)\n"
                "=> 3 ;; result/cl0000003c")
           row)))
  ;; re-render is stable: a row whose stored narration already carries
  ;; the marker renders it ONCE, unchanged.
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3" :seon.eval/id "id0000004d"
               :seon.eval/ns :my.agent.pin
               :seon.eval/narration ctx/unverified-narration-marker}
              false)]
    (is (= 1 (count (re-seq #"\[unverified narration" row)))
        "no double-marking on re-render")))

(deftest format-eval-row-huge-result-is-bounded-and-guided
  ;; The CITABLE RESULT BODY caps at `result-body-render-cap` (16384 = the
  ;; store ceiling), NOT `eval-render-cap` (1500): a stored ≤16384 result
  ;; renders whole (#53), but a body LARGER than the store ceiling is still
  ;; bounded + guided (the context-SAFETY invariant — agent code can return
  ;; anything, e.g. the legacy 9.7M-char pull).
  (let [huge-edn (pr-str (apply str (repeat (+ ctx/result-body-render-cap 5000) "z")))
        row      (#'seon.ctx/format-eval-row
                   {:seon.eval/source "(big-string)" :seon.eval/ok? true
                    :seon.eval/result-edn huge-edn :seon.eval/id "hg0000002b"
                    :seon.eval/duration-ms 7})]
    (testing "row is bounded regardless of how large the result is"
      (is (< (count row) (+ ctx/result-body-render-cap 600))))
    (testing "guiding message present, anchored to the row's own eid"
      (is (str/includes? row (str "⚠ TRUNCATED at " ctx/result-body-render-cap " of")))
      (is (str/includes? row "live value is COMPLETE"))
      (is (str/includes? row "result/hg0000002b")))))

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
;; (g3) Render caps are SPLIT BY COMPONENT (cap-split-2026-06-22): the
;;      CITABLE RESULT BODY caps at result-body-render-cap (16384 = the
;;      store ceiling), so a stored ≤16384 result renders WHOLE — fixing
;;      both the #53 mid-value clip AND the original inventory-clip defect
;;      (2026-06-11, where the store-inventory result clipped at 1500
;;      BEFORE the user-domain rows). Echoed source (form-ln) and captured
;;      stdout (out-ln) stay at eval-render-cap (1500) — neither is
;;      dereferenceable via result/<id>, so a large one is context-wasting
;;      noise. core-eval-render-cap + the :seon.ctx/core-authored? routing
;;      are DELETED (dead by construction): one cap per component, no
;;      provenance branch.
;; ---------------------------------------------------------------------------

(deftest format-eval-row-result-body-renders-whole-to-the-store-ceiling
  ;; The #53 fix: a result body up to result-body-render-cap (16384)
  ;; renders WHOLE — no provenance flag, no core/agent branch.
  (let [whole (pr-str (apply str (repeat 5000 "k")))   ; well under 16384
        row   (#'seon.ctx/format-eval-row
                {:seon.eval/source "(seon.db/store-inventory)"
                 :seon.eval/ok? true
                 :seon.eval/result-edn whole
                 :seon.eval/id "sb0000004d"
                 :seon.eval/duration-ms 3
                 :seon.eval/ns :my.agent.pin}
                false)]
    (is (str/includes? row whole)
        "a 5000-char result renders WHOLE (between the old 1500 cap and 16384)")
    (is (not (str/includes? row "TRUNCATED"))
        "no clip below the 16384 body cap")))

(deftest format-eval-row-source-and-stdout-stay-at-1500
  ;; The cap split's other half (Gemini's source-confirmed contribution):
  ;; form-ln (echoed source) and out-ln (captured stdout) cap at the
  ;; SMALLER eval-render-cap (1500), even when the result body itself is
  ;; large — neither is citable via result/<id>, so a wall of it would
  ;; crowd the 24000 transcript budget.
  (let [big-src (str "(do " (apply str (repeat 3000 "x")) ")")
        big-out (apply str (repeat 3000 "p"))
        row     (#'seon.ctx/format-eval-row
                  {:seon.eval/source big-src
                   :seon.eval/output big-out
                   :seon.eval/ok? true
                   :seon.eval/result-edn "42"
                   :seon.eval/id "sb0000004e"
                   :seon.eval/duration-ms 3
                   :seon.eval/ns :my.agent.pin}
                  false)]
    (is (= 2 (count (re-seq (re-pattern (str "⚠ TRUNCATED at "
                                             agent/eval-render-cap " of 3"))
                            row)))
        "BOTH the echoed source AND the stdout clip at 1500 (two clips)")
    (is (str/includes? row "=> 42 ;; result/sb0000004e")
        "the small result body renders whole with its handle, no (N of M) clip")
    (is (not (str/includes? row "result/sb0000004e ("))
        "the result body itself was NOT clipped (handle carries no count)")))

;; ---------------------------------------------------------------------------
;; (g2) V4-4 — session resume: the boundary marker, prior-session rendering,
;;      and the lookup-result prior-session wording.
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
                        ;; prior-session evals: no result/<id> handle.
                        (is (not (str/includes? ts "result/EVLctxtestK001"))
                            "prior-session eval carries NO result/<id> handle")
                        ;; current-session eval keeps its handle.
                        (is (str/includes? ts "result/EVLctxtestN001")
                            "current-session eval keeps its result/<id> var")
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
                  "lookup-result of a prior-session id says PRIOR SESSION")
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
               :seon.eval/id "pr0000001a"
               :seon.eval/ns :my.agent.pin})]
    (is (str/includes? row "(println \"hi\")\nhi\n=> nil ;; result/pr0000001a")
        "captured output renders above the `=>` value line, REPL-style"))
  (let [row (#'seon.ctx/format-eval-row
              {:seon.eval/source "(+ 1 2)" :seon.eval/ok? true
               :seon.eval/result-edn "3"
               :seon.eval/id "pr0000002b"
               :seon.eval/ns :my.agent.pin})]
    (is (str/includes? row "(+ 1 2)\n=> 3 ;; result/pr0000002b")
        "no output attr → form then `=>` value, no blank line injected")))

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

(deftest core-default-ctx-slots-live-tile-after-your-entity
  (let [secs  (agent/core-default-ctx)
        names (mapv :seon.ctx/name secs)
        lt    (first (filter #(= :live-tile (:seon.ctx/name %)) secs))]
    (is (= [:system :namespaces :your-entity :live-tile :warnings]
           (vec (take 5 names)))
        "the v4 slot order: your-entity → live-tile → warnings")
    (is (= 35 (:seon.ctx/priority lt))
        ":live-tile renders at 35 — after :your-entity (30), before
         :warnings (40)")
    (is (= 'seon.ctx.live-tile/live-tile-section (:seon.render/ai lt)))))

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
              (is (str/includes? text "the core default")
                  "provenance: the welcome is the core default, not agent-wired")
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
            (let [txt (seon.ctx.live-tile/live-tile-section
                        {:seon.db/db @conn
                         :seon.agent/id "AGTnoSuchAgent"})]
              (is (= "" txt)
                  "no agent entity → no tile resolves → the section suppresses
                   itself (the unwired correctness floor)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
