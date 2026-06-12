(ns seon.agent.findings-test
  "seon.agent.findings contract — the derived salience rung: stored
   user-domain row CONTENT renders into context; substrate-seeded rows
   never do; retraction makes the section vanish (reactive-context —
   derived, nothing stored, nothing to clear); pathological content is
   LOUDLY truncated, never quietly clipped. All on a boot-seeded
   scratch `:memory` world (`client/open-agent-conn!` + `boot-seed!` —
   the same provenance layout a pod boots into), never the live conn."
  (:require
    [cljs.test :refer [deftest is async]]
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
