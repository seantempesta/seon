(ns seon.agent.findings-test
  "seon.agent.findings contract — the derived salience rung: stored
   user-domain row CONTENT renders into context; substrate-seeded rows
   never do; retraction makes the section vanish (reactive-context —
   derived, nothing stored, nothing to clear); pathological content is
   LOUDLY truncated, never quietly clipped. All on a boot-seeded
   scratch `:memory` world (`client/open-agent-conn!` + `boot-seed!` —
   the same provenance layout a pod boots into), never the live conn."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.agent.findings :as findings]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]))

(defn ^:async with-world
  "Boot-seeded scratch world around `body` (fn [conn] → Promise): the
   pod's bootstrap schema + `client/boot-seed!` (so `:substrate-seed`
   tx provenance exists exactly like a live boot). Root conn and the
   schema registry are restored after (minted scratch keys removed)."
  [body]
  (let [prev-conn   db/*conn*
        keys-before (schema/current-keys)]
    (try
      (let [conn (await (client/open-agent-conn!))]
        (set! db/*conn* conn)
        (await (client/boot-seed! {:seon.db/conn conn}))
        (await (body conn)))
      (finally
        (set! db/*conn* prev-conn)
        (let [minted (remove keys-before (schema/current-keys))]
          (when (seq minted)
            (swap! schema/*schemas #(apply dissoc % minted))))))))

(def scratch-rows
  "A prior agent's findings layer — domain attrs mixed with the shared
   :my.kb/* provenance attrs (the taught shape, line range included)."
  [{:my.kb.scratch/question "How does the envelope look on failure?"
    :my.kb.scratch/claim    "transact! resolves to ok? false plus an error map — it never rejects"
    :my.kb/source-path      "src/seon/db.cljs"
    :my.kb/source-line      287
    :my.kb/source-line-end  296
    :my.kb/confidence       :verified}
   {:my.kb.scratch/question "Who writes to the cluster store?"
    :my.kb.scratch/claim    "the JVM wire-server is the sole writer; pods forward over the socket"
    :my.kb/source-path      "src/seon/store/wire.cljs"
    :my.kb/source-line      12
    :my.kb/confidence       :inferred}])

(defn ^:async seed-scratch-kind!
  "Register a scratch user domain + transact `rows` under a minted
   agent id — agent provenance, like the gym's prior-agent layer (NOT
   `:substrate-seed`, so the kind classifies user-domain)."
  [registrations rows]
  (await
    (db/with-agent (db/new-id!)
      (fn ^:async seed-prior-agent-layer! []
        (doseq [[k v] registrations] (schema/register! k v))
        (let [{ok? :seon.db/ok? :as env}
              (await (db/transact! {:seon.db/tx-data rows}))]
          (when-not ok?
            (throw (ex-info "findings-test: scratch seed failed" env))))))))

(def scratch-registrations
  [[:my.kb.scratch/question [:string {:seon.db/identity true}]]
   [:my.kb.scratch/claim :string]])

(deftest boot-seeded-store-renders-nothing
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (is (= "" (findings/findings-block @conn))
                "substrate-seeded rows (soul, kb.system singleton, user
                 entity, program-graph index) NEVER render as findings —
                 a fresh world has no section at all")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest stored-rows-render-their-content-in-full
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (await (seed-scratch-kind! scratch-registrations scratch-rows))
            (let [block (findings/findings-block @conn)]
              (is (str/includes? block "<findings>"))
              (is (str/includes? block ":my.kb.scratch — 2 rows")
                  "the kind header carries name + row count")
              (is (str/includes? block "it never rejects")
                  "claim CONTENT renders — not just attr names (the #26
                   salience defect)")
              (is (str/includes? block
                                 "the JVM wire-server is the sole writer")
                  "EVERY row of the kind renders")
              (is (and (str/includes? block ":my.kb/source-path")
                       (str/includes? block ":my.kb/source-line-end")
                       (str/includes? block "296"))
                  "provenance attrs (incl. the line range) ride along")
              (is (str/includes? block "re-read: (seon.db/query")
                  "the header teaches the copy-paste read-back query")
              (is (not (str/includes? block ":my.soul/text"))
                  "the soul (substrate-seeded :my.* kind) is NOT
                   re-injected through this section")
              (is (= block (findings/findings-block @conn))
                  "deterministic — byte-identical for one db value"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest retracted-rows-vanish
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (await (seed-scratch-kind! scratch-registrations scratch-rows))
            (is (str/includes? (findings/findings-block @conn)
                               ":my.kb.scratch"))
            (let [eids (map first
                            (db/query {:seon.db/query
                                       '[:find ?e :where
                                         [?e :my.kb.scratch/claim _]]}))
                  {ok? :seon.db/ok?}
                  (await (db/transact!
                           {:seon.db/tx-data
                            (vec (for [e eids] [:db/retractEntity e]))}))]
              (is (true? ok?) "retraction transact lands")
              (is (= "" (findings/findings-block @conn))
                  "rows retracted → section gone — derived, nothing
                   stored, nothing to acknowledge"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest user-domain-kinds-is-the-shared-pane-derivation
  ;; The inspector findings pane derives its per-kind summary from this
  ;; PUBLIC fn — same derivation as findings-block (shared-shape rule,
  ;; no twin query). Pins the [[kind attrs rows]] entry contract.
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (is (= [] (findings/user-domain-kinds @conn))
                "boot-seeded store → no user-domain kinds")
            (await (seed-scratch-kind! scratch-registrations scratch-rows))
            (let [entries (findings/user-domain-kinds @conn)
                  [kind attrs rows] (first entries)]
              (is (= 1 (count entries)))
              (is (= :my.kb.scratch kind))
              (is (contains? attrs :my.kb.scratch/claim)
                  "attrs map carries the kind's live attrs")
              (is (= 2 (count rows)))
              (is (some #(str/includes? (str (:my.kb.scratch/claim %))
                                        "never rejects")
                        rows)
                  "rows are the pulled content the pane samples from"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest numeric-only-kinds-carry-nothing-to-read
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (await (seed-scratch-kind!
                     [[:my.tally.scratch/hits :int]]
                     [{:my.tally.scratch/hits 3}]))
            (is (= "" (findings/findings-block @conn))
                "a kind with no string content has nothing to render —
                 rule (b), structural, not a name list")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; The question-adjacent pointer (L12). Synthetic Acme rows ONLY — the
;; mechanism never special-cases any scenario's terms (no-coaching rule).
;; ---------------------------------------------------------------------------

(def acme-registrations
  [[:my.acme.scratch/question [:string {:seon.db/identity true}]]
   [:my.acme.scratch/claim :string]])

(def acme-rows
  [{:my.acme.scratch/question "How does acme-sync! handle quota overflow?"
    :my.acme.scratch/claim    (str "acme-sync! retries with exponential "
                                   "backoff when the portal quota is "
                                   "exhausted")}])

(defn ^:async seed-inbound!
  "Mint `agent-id` + ONE unanswered inbound user message carrying
   `content` — the mid-task shape the pointer gates on."
  [agent-id content]
  (let [env (await
              (db/transact!
                {:seon.db/tx-data
                 [{:seon.agent/id agent-id :seon.agent/state :idle}
                  {:seon.agent.message/id      (db/new-id!)
                   :seon.agent.message/from    {:seon.user/id "user"}
                   :seon.agent.message/to      [{:seon.agent/id agent-id}]
                   :seon.agent.message/content content
                   :seon.agent.message/at      (js/Date.)
                   :seon.agent.message/hops    0}]}))]
    (when-not (:seon.db/ok? env)
      (throw (ex-info "findings-test: inbound seed failed" env)))))

(deftest terms-keeps-code-tokens-and-drops-structural-noise
  (let [ts (findings/terms
             (str "Where does the system check entity values — it calls "
                  "(seon.db/transact! …) and validate-values!."))]
    (is (contains? ts "validate-values!")
        "code-ish tokens survive INTACT — bang included, trailing
         sentence period trimmed")
    (is (contains? ts "seon.db/transact!")
        "qualified call names stay one token (paren split off)")
    (is (and (contains? ts "entity") (contains? ts "values")))
    (is (not (contains? ts "where"))
        "wh- pro-forms are stopwords")
    (is (not (contains? ts "the")) "articles are stopwords")
    (is (not (contains? ts "it")) "pronouns are stopwords")
    (is (not-any? #(< (count %) findings/pointer-min-term-len) ts)
        "short structural tokens (and/is/db) fall to the length floor")
    (is (= ts (findings/terms
                (str "Where does the system check entity values — it calls "
                     "(seon.db/transact! …) and validate-values!.")))
        "deterministic")))

(deftest question-matches-scores-shared-distinctive-terms
  (let [kinds [[:my.acme.scratch
                {:my.acme.scratch/claim 1}
                [{:my.acme.scratch/claim
                  "acme-sync! retries when the portal quota is exhausted"}]]]]
    (testing "two+ shared distinctive terms clear the threshold"
      (let [[m :as ms] (findings/question-matches
                         "did acme-sync! exhaust the portal quota again?"
                         kinds)]
        (is (= 1 (count ms)))
        (is (= :my.acme.scratch (:seon.db/kind m)))
        (is (= ["acme-sync!" "portal" "quota"]
               (:seon.agent.findings/shared-terms m))
            "the ACTUAL shared terms ride the match, sorted")))
    (testing "one shared term is coincidence — below threshold"
      (is (= [] (findings/question-matches
                  "show me the quota dashboard please" kinds))))
    (testing "unrelated question matches nothing"
      (is (= [] (findings/question-matches
                  "summarize tuesday's standup notes" kinds))))
    (testing "blank question matches nothing"
      (is (= [] (findings/question-matches "" kinds))))
    (testing "top-N rows cap (identical matches dedupe first)"
      (let [many [[:my.acme.scratch
                   {:my.acme.scratch/claim 1}
                   (vec (for [i (range 5)]
                          {:my.acme.scratch/claim
                           (str "acme-sync! portal quota marker-" i)}))]]
            q    "acme-sync! portal quota marker-0 marker-1 marker-2 marker-3 marker-4"]
        (is (= findings/pointer-max-rows
               (count (findings/question-matches q many)))
            "5 distinct matching rows → top 3 — a pointer, not a second
             findings render")))))

(deftest pointer-renders-terms-kind-and-readback-near-the-question
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (await (seed-scratch-kind! acme-registrations acme-rows))
            (await (seed-inbound!
                     "AGTfindptr0001"
                     "did acme-sync! exhaust the portal quota again?"))
            (let [block (findings/findings-pointer-block
                          @conn "AGTfindptr0001")]
              (is (str/includes? block "<findings-pointer>"))
              (is (str/includes? block ":my.acme.scratch")
                  "the pointer NAMES the overlapping kind")
              (is (and (str/includes? block "acme-sync!")
                       (str/includes? block "quota"))
                  "the pointer shows the ACTUAL shared terms")
              (is (str/includes? block "re-read: (seon.db/query")
                  "the read-back query rides the pointer")
              (is (str/includes? block "<findings> above")
                  "points BACK at the full rows, never re-renders them")
              (is (not (str/includes?
                         block "exponential"))
                  "row CONTENT stays in <findings> — the pointer is
                   terms + kind only")
              (is (<= (count (str/split-lines block)) 5)
                  "tiny — tag lines + at most 3 match lines")
              (is (= block (findings/findings-pointer-block
                             @conn "AGTfindptr0001"))
                  "deterministic — byte-identical for one db value")
              (is (= block (findings/findings-pointer-section
                             {:seon.db/db    @conn
                              :seon.agent/id "AGTfindptr0001"}))
                  "the section fn matches the block (same derivation)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest pointer-vanishes-when-nothing-overlaps-or-nothing-pends
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (testing "inbound question + EMPTY store → \"\""
              (await (seed-inbound!
                       "AGTfindptr0002"
                       "did acme-sync! exhaust the portal quota again?"))
              (is (= "" (findings/findings-pointer-block
                          @conn "AGTfindptr0002"))))
            (testing "stored rows + UNRELATED question → \"\""
              (await (seed-scratch-kind! acme-registrations acme-rows))
              (await (seed-inbound!
                       "AGTfindptr0003"
                       "summarize tuesday's standup notes"))
              (is (= "" (findings/findings-pointer-block
                          @conn "AGTfindptr0003"))
                  "no row clears the 2-shared-term threshold"))
            (testing "stored rows + NO unanswered inbound → \"\""
              (let [env (await (db/transact!
                                 {:seon.db/tx-data
                                  [{:seon.agent/id    "AGTfindptr0004"
                                    :seon.agent/state :idle}]}))]
                (is (true? (:seon.db/ok? env)))
                (is (= "" (findings/findings-pointer-block
                            @conn "AGTfindptr0004"))
                    "idle agent — no question, no pointer")))
            (testing "agent already REPLIED since the inbound → \"\""
              (await (seed-inbound!
                       "AGTfindptr0005"
                       "did acme-sync! exhaust the portal quota again?"))
              (let [env (await
                          (db/transact!
                            {:seon.db/tx-data
                             [{:seon.agent.message/id   (db/new-id!)
                               :seon.agent.message/from {:seon.agent/id "AGTfindptr0005"}
                               :seon.agent.message/to   [{:seon.user/id "user"}]
                               :seon.agent.message/content "yes — quota hit"
                               :seon.agent.message/at   (js/Date. (+ (js/Date.now) 50))
                               :seon.agent.message/hops 1}]}))]
                (is (true? (:seon.db/ok? env)))
                (is (= "" (findings/findings-pointer-block
                            @conn "AGTfindptr0005"))
                    "answered = idle — derived, vanishes by itself")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest pointer-survives-the-self-fold-and-rearms
  ;; THE finding-1 regression (opus-live-tests 2026-06-12): the pointer
  ;; rendered in 1 of B's 14 blobs — its first — because the per-turn
  ;; self-fold outbound (from = to = me) closed the unanswered-inbox
  ;; window. The gate is now seon.ctx/task-in-progress? (reply-aware)
  ;; and the question text is the MOST RECENT live inbound regardless
  ;; of fold state, so the pointer persists through a research wake.
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (let [id "AGTfindptr0006"
                  me {:seon.agent/id id}
                  at (fn [ms] (js/Date. (+ (js/Date.now) ms)))]
              (await (seed-scratch-kind! acme-registrations acme-rows))
              (await (seed-inbound!
                       id "did acme-sync! exhaust the portal quota again?"))
              (let [block (findings/findings-pointer-block @conn id)]
                (is (str/includes? block ":my.acme.scratch")
                    "pre-fold: pointer renders (turn 1 of the wake)")
                ;; The self-fold — the agent's per-turn assistant
                ;; self-message, the outbound that used to kill the
                ;; pointer after turn 1.
                (let [env (await
                            (db/transact!
                              {:seon.db/tx-data
                               [{:seon.agent.message/id      (db/new-id!)
                                 :seon.agent.message/from    me
                                 :seon.agent.message/to      [me]
                                 :seon.agent.message/content "[fold] grepping…"
                                 :seon.agent.message/at      (at 50)
                                 :seon.agent.message/hops    1}]}))]
                  (is (true? (:seon.db/ok? env))))
                (is (= block (findings/findings-pointer-block @conn id))
                    "the self-fold does NOT kill the pointer — it stays
                     byte-identical through the research turns"))
              ;; The reply — outbound to a non-self recipient.
              (let [env (await
                          (db/transact!
                            {:seon.db/tx-data
                             [{:seon.agent.message/id      (db/new-id!)
                               :seon.agent.message/from    me
                               :seon.agent.message/to      [{:seon.user/id "user"}]
                               :seon.agent.message/content "yes — quota hit"
                               :seon.agent.message/at      (at 100)
                               :seon.agent.message/hops    1}]}))]
                (is (true? (:seon.db/ok? env))))
              (is (= "" (findings/findings-pointer-block @conn id))
                  "replied + idle → pointer gone")
              ;; A NEW matching inbound re-arms the pointer — and the
              ;; question text is the MOST RECENT inbound, so the match
              ;; reflects the live question.
              (let [env (await
                          (db/transact!
                            {:seon.db/tx-data
                             [{:seon.agent.message/id      (db/new-id!)
                               :seon.agent.message/from    {:seon.user/id "user"}
                               :seon.agent.message/to      [me]
                               :seon.agent.message/content
                               "walk me through acme-sync! portal quota retries"
                               :seon.agent.message/at      (at 150)
                               :seon.agent.message/hops    0}]}))]
                (is (true? (:seon.db/ok? env))))
              (is (str/includes?
                    (findings/findings-pointer-block @conn id)
                    ":my.acme.scratch")
                  "a new inbound re-arms the pointer"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest oversized-kind-truncates-loudly
  (async done
    (-> (with-world
          (fn ^:async t [conn]
            (let [big (apply str (repeat (+ findings/kind-render-cap 1000)
                                         "x"))]
              (await (seed-scratch-kind!
                       scratch-registrations
                       [{:my.kb.scratch/question "what is the big one?"
                         :my.kb.scratch/claim    big}]))
              (let [block (findings/findings-block @conn)]
                (is (str/includes? block
                                   (str "⚠ TRUNCATED at "
                                        findings/kind-render-cap))
                    "over the backstop → LOUD marker, never a quiet clip")
                (is (str/includes? block "Read the rest yourself:")
                    "the marker carries the read-back guidance")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
