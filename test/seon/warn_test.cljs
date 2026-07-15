(ns seon.warn-test
  "Unit tests for the seon.warn check registry (unit 1.3 / Track A §A2).

   Each check is exercised independently against a fresh seeded
   `:memory` conn: one ns of deliberately-defective fns (no spec, :any
   return, :any arg, [:maybe], missing input/output, no test) plus one
   clean fn WITH a test, a second ns proving ns-scoping, failed evals
   (one generic, one lookup-ref), and a failing test entity. Then the
   clustered renderer: one explanation per kind, affected list with
   locations, empty string when clean.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.warn-test :reload)
     (cljs.test/run-tests 'seon.warn-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async use-fixtures]]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; Domain attrs for the parallel-attr check — registered at load so the
;; seed transact installs them into the test conn's datahike schema.
;; duration-seconds vs duration-minutes share the stem 'duration' with
;; unit suffixes (the run-4 ham defect, :workout/duration-minutes);
;; date/type have no unit suffix and must never collide.
(schema/register! :warntest.dom/duration-seconds :int)
(schema/register! :warntest.dom/duration-minutes :int)
(schema/register! :warntest.dom/date :inst)
(schema/register! :warntest.dom/type :keyword)
;; The provenance test's agent-authored DATA domain. It was renamed
;; :seon.workout → :my.workout on 2026-06-11: agent data domains are my.*,
;; and the old name is out of the shipping product.
(schema/register! :my.workout/date :string)
;; The unmarked-entity-kinds fixture: an identity attr plus a
;; registered-but-UNMARKED :map schema carrying it (the shape the old
;; register!-time warn could not tell apart from an envelope). The
;; marked :warntest.ent kind is registered INSIDE the test, after rows
;; exist, to prove the warning self-heals.
(schema/register! :warntest.ent/id [:string {:seon.db/identity true}])
(schema/register! :warntest.ent/lookup
  [:map [:warntest.ent/id :warntest.ent/id]])

;; ---------------------------------------------------------------------------
;; Fixture — fresh conn + a corpus of defective and clean rows.
;; ---------------------------------------------------------------------------

;; The slow eval's seeded duration — ONE constant feeds both the fixture row
;; and the assertion, so the test pins the `(str dur "ms")` GENERATION in
;; warn/check-slow-evals (the formatting), not a hardcoded "1500ms" literal.
(def ^:private slow-eval-duration-ms 1500)

(defn- seed-tx []
  (let [now (js/Date.)
        t   (fn [ms] (js/Date. (+ (.getTime now) ms)))]
    [;; ── ns under test ────────────────────────────────────────────
     {:seon.ns/name :warntest.main
      :seon.ns/source "(ns warntest.main)"}
     ;; no :malli/schema at all
     {:seon.fn/sym "warntest.main/no-spec"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn no-spec [x] x)"
      :seon.fn/fn-var? true
      :seon.fn/private? false}
     ;; return is :any
     {:seon.fn/sym "warntest.main/any-ret"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn any-ret [s] s)"
      :seon.fn/fn-var? true
      :seon.fn/private? false
      :seon.fn/spec "[:=> [:cat :string] :any]"}
     ;; a NAMED arg is :any (catn)
     {:seon.fn/sym "warntest.main/any-arg"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn any-arg [m] m)"
      :seon.fn/fn-var? true
      :seon.fn/private? false
      :seon.fn/spec "[:=> [:catn [:warntest.main/payload :any]] :string]"}
     ;; uses [:maybe X]
     {:seon.fn/sym "warntest.main/maybe-fn"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn maybe-fn [s] s)"
      :seon.fn/fn-var? true
      :seon.fn/private? false
      :seon.fn/spec "[:=> [:cat [:maybe :string]] :string]"}
     ;; :=> missing its output
     {:seon.fn/sym "warntest.main/no-ret"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn no-ret [s] s)"
      :seon.fn/fn-var? true
      :seon.fn/private? false
      :seon.fn/spec "[:=> [:cat :string]]"}
     ;; :=> missing its input [:cat …]
     {:seon.fn/sym "warntest.main/no-input"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn no-input [] :x)"
      :seon.fn/fn-var? true
      :seon.fn/private? false
      :seon.fn/spec "[:=> :string]"}
     ;; private + unspecced — EXEMPT from contract checks
     {:seon.fn/sym "warntest.main/-helper"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn- -helper [x] x)"
      :seon.fn/fn-var? true
      :seon.fn/private? true}
     ;; clean fn WITH a test → appears in NO cluster
     {:seon.fn/sym "warntest.main/clean"
      :seon.fn/ns [:seon.ns/name :warntest.main]
      :seon.fn/source "(defn clean [s] (str s))"
      :seon.fn/fn-var? true
      :seon.fn/private? false
      :seon.fn/spec "[:=> [:cat :string] :string]"}
     {:seon.test/sym "warntest.main/clean-test"
      :seon.test/ns [:seon.ns/name :warntest.main]
      :seon.test/source "(deftest clean-test (is (= \"x\" (clean \"x\"))))"
      :seon.test/created-at (t 0)}
     ;; ── a DIFFERENT ns — proves ns-scoping ───────────────────────
     {:seon.ns/name :warntest.other
      :seon.ns/source "(ns warntest.other)"}
     {:seon.fn/sym "warntest.other/also-unspecced"
      :seon.fn/ns [:seon.ns/name :warntest.other]
      :seon.fn/source "(defn also-unspecced [x] x)"
      :seon.fn/fn-var? true
      :seon.fn/private? false}
     ;; ── runtime rows: a user msg + failed evals after it ─────────
     ;; from/to refs (unit 1.5): nested-map upserts create the user +
     ;; a stub agent entity in the same tx. Fully formed message:
     ;; from + to + content + at + id + hops.
     {:seon.agent.message/id "MSGwarntest001"
      :seon.agent.message/from {:seon.user/id "user"}
      :seon.agent.message/to [{:seon.agent/id "warntest-agent"}]
      :seon.agent.message/content "hello"
      :seon.agent.message/at (t 100)
      :seon.agent.message/hops 0}
     ;; a hop-exhausted message AFTER the user msg — wake was refused;
     ;; check-hop-exhausted must surface exactly this one
     {:seon.agent.message/id "MSGwarntestHOP"
      :seon.agent.message/from {:seon.agent/id "warntest-agent"}
      :seon.agent.message/to [{:seon.agent/id "warntest-agnt2"}]
      :seon.agent.message/content "ping"
      :seon.agent.message/at (t 400)
      :seon.agent.message/hops 4}
     {:seon.eval/id "EVLwarnFAIL001"
      :seon.eval/at (t 200)
      :seon.eval/source "(boom)"
      :seon.eval/ok? false
      :seon.eval/error "boom — generic failure"}
     {:seon.eval/id "EVLwarnREF0001"
      :seon.eval/at (t 210)
      :seon.eval/source "(seon.db/transact! …)"
      :seon.eval/ok? false
      :seon.eval/error
      "Error: Lookup ref attribute should be marked as :db/unique: [:kb.doc/path \"x\"]"}
     ;; a failed eval BEFORE the user msg — must NOT surface
     {:seon.eval/id "EVLwarnSTALE01"
      :seon.eval/at (t 50)
      :seon.eval/source "(old-boom)"
      :seon.eval/ok? false
      :seon.eval/error "stale failure"}
     ;; slow eval after the cutoff window start
     {:seon.eval/id "EVLwarnSLOW001"
      :seon.eval/at (t 220)
      :seon.eval/duration-ms slow-eval-duration-ms
      :seon.eval/source "(slow)"
      :seon.eval/ok? true}
     ;; ── fs allowlist denials (check-fs-denied) ───────────────────
     ;; fs ops never throw: a denial is an ok? TRUE eval whose RESULT
     ;; carries the :seon.agent.fs/error envelope. This one is after
     ;; the user msg — must surface.
     {:seon.eval/id "EVLwarnFSDENY1"
      :seon.eval/at (t 230)
      :seon.eval/source "(seon.agent.fs/read-file {:seon.agent.fs/path \"/etc/passwd\"})"
      :seon.eval/ok? true
      :seon.eval/result-edn
      (str "{:seon.agent.fs/ok? false, :seon.agent.fs/path \"/etc/passwd\", "
           ":seon.agent.fs/error \"path outside allowed-roots [\\\"/Users/x/work\\\"]\"}")}
     ;; a denial BEFORE the user msg — must NOT surface
     {:seon.eval/id "EVLwarnFSSTALE"
      :seon.eval/at (t 60)
      :seon.eval/source "(seon.agent.fs/list-dir {:seon.agent.fs/path \"/old\"})"
      :seon.eval/ok? true
      :seon.eval/result-edn
      (str "{:seon.agent.fs/ok? false, :seon.agent.fs/path \"/old\", "
           ":seon.agent.fs/error \"seon.agent.fs has no allowed-roots configured (default-deny).\"}")}
     ;; a grants READ-BACK after the user msg — mentions allowed-roots
     ;; but carries no :seon.agent.fs/error; must NOT fire
     {:seon.eval/id "EVLwarnFSGRANT"
      :seon.eval/at (t 240)
      :seon.eval/source "(seon.agent.fs/grants)"
      :seon.eval/ok? true
      :seon.eval/result-edn
      "{:seon.agent.fs/allowed-roots [\"/Users/x/work\"], :seon.agent.fs/read-only? false}"}
     ;; ── domain data: the parallel-attr fork ──────────────────────
     ;; 2 entities on the ESTABLISHED attr (duration-seconds), 1 on the
     ;; fork (duration-minutes) — mirrors run 4's live :workout data.
     ;; The tee-shaped :seon.schema rows give the attrs repl-process
     ;; provenance (this whole seed tx is not boot-authored), exactly
     ;; like seon.eval/build-tee-entities does for a real register!
     ;; eval — domain-attrs discriminates on that provenance.
     {:seon.schema/key :warntest.dom/duration-seconds
      :seon.schema/created-at (t 0)}
     {:seon.schema/key :warntest.dom/duration-minutes
      :seon.schema/created-at (t 0)}
     {:seon.schema/key :warntest.dom/date
      :seon.schema/created-at (t 0)}
     {:seon.schema/key :warntest.dom/type
      :seon.schema/created-at (t 0)}
     {:warntest.dom/date (t 0)
      :warntest.dom/type :run
      :warntest.dom/duration-seconds 1470}
     {:warntest.dom/date (t 1)
      :warntest.dom/type :strength
      :warntest.dom/duration-seconds 3600}
     {:warntest.dom/duration-minutes 35}
     ;; failing test (failed, never passed)
     {:seon.test/sym "warntest.main/broken-test"
      :seon.test/ns [:seon.ns/name :warntest.main]
      :seon.test/source "(deftest broken-test (is false))"
      :seon.test/last-failed-at (t 300)
      :seon.test/created-at (t 0)}]))

;; Slowness #2: booting a fresh conn + transacting the large `(seed-tx)`
;; in EACH pure-reader deftest dominated this ns's runtime. Boot + seed
;; ONE conn ONCE; every pure reader derefs the resolved post-seed db
;; VALUE (warn checks take `:seon.db/db` explicitly — no conn needed).
;; The :once :before awaits the seed so no body races it. Tests that
;; mutate their own store (provenance, unmarked-entity-kinds,
;; tile-unresolved) still open their own isolated conn below.
(def ^:private seeded-db
  "Memoized Promise: opens one :memory conn, seeds it with `(seed-tx)`,
   resolves to the post-seed db VALUE. Same Promise on every deref."
  (delay
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
                 (binding [db/*conn* conn]
                   (-> (db/transact! {:seon.db/tx-data (seed-tx)})
                       (.then (fn [_] @conn)))))))))

(use-fixtures :once
  {:before (fn [] @seeded-db)})

(defn- with-seeded-db
  "Resolve the shared once-seeded db value and call `body` with it.
   Returns a Promise. Used by the pure-reader deftests; mutating tests
   open their own conn instead."
  [body]
  (.then @seeded-db (fn [db] (body db))))

(defn- affected-syms [resp]
  (set (map :seon.warn/sym (:seon.warn/affected resp))))

(defn- scoped [db] {:seon.db/db db :seon.warn/ns :warntest.main})

;; ---------------------------------------------------------------------------
;; Corpus checks — each independently, scoped to :warntest.main.
;; ---------------------------------------------------------------------------

(deftest no-malli-schema-names-only-the-unspecced-public-fn
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-no-malli-schema (scoped db))]
              (is (= :no-malli-schema (:seon.warn/kind r)))
              (is (= #{"warntest.main/no-spec"} (affected-syms r))
                  "private helper + specced fns are exempt; other ns excluded"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest return-is-any-names-the-return
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-return-is-any (scoped db))]
              (is (= #{"warntest.main/any-ret"} (affected-syms r)))
              (is (= "return" (:seon.warn/where (first (:seon.warn/affected r))))
                  "the affected entry carries the SPECIFIC location"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest arg-is-any-names-which-arg
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-arg-is-any (scoped db))]
              (is (= #{"warntest.main/any-arg"} (affected-syms r)))
              (is (= "arg :warntest.main/payload"
                     (:seon.warn/where (first (:seon.warn/affected r))))
                  "names the exact catn arg"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest uses-maybe-flags-the-schema
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-uses-maybe (scoped db))]
              (is (= #{"warntest.main/maybe-fn"} (affected-syms r))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest missing-output-and-input-are-separate-kinds
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [no-ret (warn/check-no-return-spec (scoped db))
                  no-in  (warn/check-no-input-spec  (scoped db))]
              (is (= #{"warntest.main/no-ret"} (affected-syms no-ret)))
              (is (= #{"warntest.main/no-input"} (affected-syms no-in))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; NOTE: the former `missing-test-spares-the-tested-fn` test was removed
;; with `check-missing-test` (B9): a usage example is OPT-IN, so there is
;; no "this fn has no test" warning to assert. A currently-FAILING test is
;; covered by the runtime `check-failing-tests` path instead.

(deftest ns-scope-defaults-to-everything-when-unscoped
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [scoped-r   (warn/check-no-malli-schema (scoped db))
                  unscoped-r (warn/check-no-malli-schema {:seon.db/db db})]
              (is (not (contains? (affected-syms scoped-r)
                                  "warntest.other/also-unspecced"))
                  "ns-scope excludes the other ns")
              (is (contains? (affected-syms unscoped-r)
                             "warntest.other/also-unspecced")
                  "unscoped = whole-core overview"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Domain-attr check — parallel-attr (run-4 ham defect).
;; ---------------------------------------------------------------------------

(deftest parallel-attr-flags-the-forked-unit-attr
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r     (warn/check-parallel-attr {:seon.db/db db})
                  entry (first (:seon.warn/affected r))]
              (is (= :parallel-attr (:seon.warn/kind r)))
              (is (= #{":warntest.dom/duration-minutes"} (affected-syms r))
                  "the fork is flagged, not the established attr")
              (is (and (str/includes? (:seon.warn/where entry)
                                      ":warntest.dom/duration-seconds")
                       (str/includes? (:seon.warn/where entry) "2"))
                  "names the established attr + its instance count")
              (is (not (contains? (affected-syms r) ":warntest.dom/date"))
                  "no unit suffix → never collides (date/type are safe)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest domain-attrs-discriminate-by-provenance-not-keyword-namespace
  ;; S-21 production-bug pin (2026-06-10): the old keyword-namespace
  ;; blanket `(db|seon)(\..*)?` hid agent-authored `seon.*` data
  ;; domains (then-live :seon.workout/* — domain since renamed
  ;; :my.workout/*, 2026-06-11) from the whole reuse surface.
  ;; Domain-attrs now discriminate by PROVENANCE: a
  ;; :seon.schema/key row asserted outside root/boot provenance is an
  ;; agent-registered domain; root/boot is core and
  ;; hidden — whatever the keyword namespace.
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
          (-> ;; core layer — :seon.agent/id's schema row + an
              ;; install of the attr, inside the seed tx-context (the
              ;; same provenance seon.client/start-agent! stamps).
              (db/with-tx-context {:seon.db/user [:seon.agent/id "root"]
                                   :seon.db/process
                                   [:seon.db.process/id :seon.db.process/boot]}
                (fn []
                  (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data
                     [{:seon.schema/key :seon.agent/id
                       :seon.schema/created-at (js/Date.)}
                      ;; 14 chars — :seon.agent/id is :seon.db/id-shaped
                      {:seon.agent/id "warntest-prova"}]})))
              (.then (fn [env]
                (is (:seon.db/ok? env) "core-layer tx lands")
                ;; agent layer — the tee row + data for an agent DATA
                ;; domain, in an ordinary (non-seed) tx.
                (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.schema/key :my.workout/date
                     :seon.schema/form
                     "(seon.schema/register! :my.workout/date :string)"
                     :seon.schema/created-at (js/Date.)}
                    {:my.workout/date "2026-06-10"}]})))
              (.then (fn [env]
                (is (:seon.db/ok? env) "agent-layer tx lands")
                (let [attrs (set (warn/domain-attrs {:seon.db/db @conn}))]
                  (is (contains? attrs :my.workout/date)
                      "agent-registered DATA domain renders as a domain attr")
                  (is (not (contains? attrs :seon.agent/id))
                      "boot-authored seon.* attr stays hidden")
                  (is (not (contains? attrs :seon.schema/key))
                      "attrs with no :seon.schema row at all stay hidden")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest unmarked-entity-kinds-fires-on-stored-rows-and-heals-when-marked
  ;; The entity-marker nudge, REWORKED behavioral (user ruling
  ;; 2026-06-10): the old register!-time warn was a false-positive
  ;; generator by construction. This check fires only where rows EXIST
  ;; under an identity attr no marked schema declares — and vanishes
  ;; the moment the kind is marked (registry change, same db value).
  (async done
    (let [before-state (schema/snapshot-state)]
      (-> (client/open-agent-conn!)
          (.then
            (fn [conn]
              (binding [db/*conn* conn]
                (-> (db/transact!
                      {:seon.db/conn conn
                       :seon.db/tx-data [{:warntest.ent/id "row-1"}]})
                    (.then
                      (fn [env]
                        (is (:seon.db/ok? env) "the row lands")
                        (let [r     (warn/check-unmarked-entity-kinds
                                      {:seon.db/db @conn})
                              entry (->> (:seon.warn/affected r)
                                         (filter #(= ":warntest.ent/id"
                                                     (:seon.warn/sym %)))
                                         first)]
                          (is (= :unmarked-entity-kinds (:seon.warn/kind r)))
                          (is (some? entry)
                              "rows under an undeclared id-attr → fires, naming the attr")
                          (is (str/includes? (str (:seon.warn/where entry))
                                             ":warntest.ent/lookup")
                              "names the registered-but-unmarked map schema carrying it")
                          (is (str/includes? (:seon.warn/example r)
                                             "{:seon.db/entity true}")
                              "the fix example shows the marker")
                          ;; Model an accepted schema-fact transition. A bare
                          ;; register! is only a candidate, never publication.
                          (schema/register! :warntest.ent
                            [:map {:seon.db/entity true}
                             [:warntest.ent/id :warntest.ent/id]])
                          (schema/activate! (schema/snapshot))
                          (let [r2 (warn/check-unmarked-entity-kinds
                                     {:seon.db/db @conn})]
                            (is (not (contains? (affected-syms r2)
                                                ":warntest.ent/id"))
                                "marked → vanishes, same db value")))))))))
        (.finally
          (fn []
            (schema/restore-state! before-state)))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest unmarked-entity-kinds-clean-when-no-rows-exist
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
          ;; the FULL bootstrap schema is installed (identity attrs
          ;; everywhere) but NO data rows — the check must stay silent:
          ;; an id-carrying schema without rows is not a defect.
          (let [r (warn/check-unmarked-entity-kinds {:seon.db/db @conn})]
            (is (= [] (:seon.warn/affected r))
                "no rows → no warning, whatever schemas are installed"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest parallel-attr-is-global-ignores-ns-scope
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-parallel-attr (scoped db))]
              (is (= #{":warntest.dom/duration-minutes"} (affected-syms r))
                  "keyword namespaces are data domains — ns-scope is ignored"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Runtime checks.
;; ---------------------------------------------------------------------------

(deftest failed-evals-since-latest-user-msg-excluding-bad-ref
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-failed-evals {:seon.db/db db})]
              (is (contains? (affected-syms r) "EVLwarnFAIL001"))
              (is (not (contains? (affected-syms r) "EVLwarnSTALE01"))
                  "failures BEFORE the latest user msg don't surface")
              (is (not (contains? (affected-syms r) "EVLwarnREF0001"))
                  "lookup-ref failures belong to check-bad-ref"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest fs-denied-surfaces-only-post-user-denials
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r     (warn/check-fs-denied {:seon.db/db db})
                  entry (first (:seon.warn/affected r))]
              (is (= :fs-denied (:seon.warn/kind r)))
              (is (= #{"EVLwarnFSDENY1"} (affected-syms r))
                  (str "stale (pre-user-msg) denials and grants "
                       "read-backs never fire"))
              (is (str/includes? (:seon.warn/where entry)
                                 "path outside allowed-roots")
                  "the affected entry carries the SPECIFIC denial text")
              (is (str/includes? (:seon.warn/example r) "seon.agent.fs/grants")
                  "the fix teaches the read API — never guessing from a listing"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest hop-exhausted-surfaces-only-post-user-cap-messages
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r     (warn/check-hop-exhausted {:seon.db/db db})
                  entry (first (:seon.warn/affected r))]
              (is (= :hop-exhausted (:seon.warn/kind r)))
              (is (= #{"MSGwarntestHOP"} (affected-syms r))
                  "only the hops>=cap message after the user msg")
              (is (str/includes? (:seon.warn/where entry) "4/4")
                  "the where carries the hops/cap ratio"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest bad-ref-translates-the-cryptic-datahike-error
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r     (warn/check-bad-ref {:seon.db/db db})
                  entry (first (:seon.warn/affected r))]
              (is (= #{"EVLwarnREF0001"} (affected-syms r)))
              (is (str/includes? (:seon.warn/where entry) ":kb.doc/path")
                  "names the exact attr from the error text")
              (is (str/includes? (:seon.warn/explain r) ":seon.db/identity")
                  "the explanation teaches the real fix"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest slow-and-failing-test-checks-fire
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [slow (warn/check-slow-evals {:seon.db/db db})
                  ftst (warn/check-failing-tests {:seon.db/db db})]
              (is (= #{"EVLwarnSLOW001"} (affected-syms slow)))
              ;; pins the GENERATION (str dur "ms"), not a "1500ms" literal:
              ;; the `where` is the seeded duration formatted with the "ms" unit.
              (is (= (str slow-eval-duration-ms "ms")
                     (:seon.warn/where (first (:seon.warn/affected slow)))))
              (is (= #{"warntest.main/broken-test"} (affected-syms ftst))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Urgent broken-canvas warning (Group C / §C.2). check-canvas-unresolved is the
;; one URGENT check: when a canvas points at a fn that isn't loaded, the
;; human is staring at the calm "preparing this view…" placeholder RIGHT NOW.
;; It is DERIVED (query the stored pointer + resolvability), self-heals, and
;; renders FIRST with the louder ‼ URGENT template.
;; ---------------------------------------------------------------------------

(defn- with-tile-db
  "Open a fresh conn, transact `tx`, call `body` with the post-tx db value.
   Like with-seeded-db but with caller-supplied tx (the broken-tile tests
   each need their own tile pointer). Returns a Promise."
  [tx body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/conn conn :seon.db/tx-data tx})
                     (.then (fn [env]
                              (is (:seon.db/ok? env)
                                  (str "tile tx lands — " (pr-str (:seon.db/error env))))
                              (binding [db/*conn* conn]
                                (body @conn))))))))))

(deftest tile-unresolved-is-urgent-and-names-the-broken-tile
  ;; tile points at an UNRESOLVABLE qualified symbol → fires, urgent? true,
  ;; affected names the symbol + which agent's tile is dead.
  (async done
    (-> (with-tile-db
          [{:seon.agent/id "warntst-tile01"
            :seon.render.canvas/content 'my.agent.warntst/missing-tile}]
          (fn [db]
            (let [r     (warn/check-canvas-unresolved {:seon.db/db db})
                  entry (first (:seon.warn/affected r))]
              (is (= :canvas-unresolved (:seon.warn/kind r)))
              (is (true? (:seon.warn/urgent? r))
                  "broken-tile is the URGENT tier")
              (is (= #{"my.agent.warntst/missing-tile"} (affected-syms r))
                  "names the exact unresolvable symbol")
              (is (str/includes? (:seon.warn/where entry) "warntst-tile01")
                  "names which agent's canvas is dead"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest tile-unresolved-clean-when-symbol-resolves-or-is-literal
  ;; a tile pointing at a fn that EXISTS (warn/render-warnings resolves) and a
  ;; tile holding LITERAL hiccup both produce NOTHING — only genuinely
  ;; unresolvable qualified symbols fire.
  (async done
    (-> (with-tile-db
          [{:seon.agent/id "warntst-rslv01"
            :seon.render.canvas/content 'seon.warn/render-warnings}
           {:seon.agent/id "warntst-hicc01"
            :seon.render.canvas/content [:div {:class "seon-card"} "literal"]}]
          (fn [db]
            (let [r (warn/check-canvas-unresolved {:seon.db/db db})]
              (is (= [] (:seon.warn/affected r))
                  "resolving symbol + literal hiccup → clean, no warning"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest tile-unresolved-self-heals-when-symbol-resolves
  ;; the reactive guarantee: re-point the SAME agent's tile at a fn that
  ;; exists and the warning vanishes — same db lineage, no acknowledgement,
  ;; nothing stored to clear.
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
          (binding [db/*conn* conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.agent/id "warntst-heal01"
                     :seon.render.canvas/content 'my.agent.warntst/not-yet-defined}]})
                (.then (fn [env]
                  (is (:seon.db/ok? env) "broken-tile tx lands")
                  (let [r (warn/check-canvas-unresolved {:seon.db/db @conn})]
                    (is (= #{"my.agent.warntst/not-yet-defined"} (affected-syms r))
                        "fires while the symbol is unresolvable"))
                  ;; re-point the tile at a fn that resolves → self-heal
                  (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data
                     [{:seon.agent/id "warntst-heal01"
                       :seon.render.canvas/content 'seon.warn/render-warnings}]})))
                (.then (fn [env]
                  (is (:seon.db/ok? env) "re-point tx lands")
                  (let [r2 (warn/check-canvas-unresolved {:seon.db/db @conn})]
                    (is (= [] (:seon.warn/affected r2))
                        "symbol now resolves → warning vanished, same db lineage"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))


(deftest render-warnings-empty-when-clean
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
                 (binding [db/*conn* conn]
                   (is (= "" (warn/render-warnings {:seon.db/db @conn}))
                       "fresh conn, no defects → empty string, section suppressed"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Per-check degradation (self-defeating-surfaces audit 2026-06-11
;; finding 3): ONE throwing check must NOT kill the whole <warnings>
;; section — it becomes its own loud :warn-check-error cluster while
;; every healthy check still renders.
;; ---------------------------------------------------------------------------

(defn- healthy-fake-check
  "A registry stand-in that always fires with one affected entry."
  [_req]
  {:seon.warn/kind     :fake-healthy
   :seon.warn/affected [{:seon.warn/sym "warntest.main/fake-defect"}]
   :seon.warn/explain  "a healthy check that fires"
   :seon.warn/example  ";; nothing to fix — fixture"})

(defn- throwing-fake-check
  "A registry stand-in that throws — the broken-check failure mode
   (datalog over an unexpected store shape, etc.)."
  [_req]
  (throw (ex-info "boom from fake check" {})))

(deftest one-throwing-check-degrades-to-its-own-cluster
  (async done
    (-> (with-seeded-db
          (fn [db]
            (with-redefs [warn/checks [healthy-fake-check
                                       throwing-fake-check]]
              (let [clusters (warn/run-checks {:seon.db/db db})
                    by-kind  (group-by :seon.warn/kind clusters)
                    synth    (first (:warn-check-error by-kind))]
                (is (= 2 (count clusters))
                    "BOTH checks produced a cluster — the throw did not
                     propagate and kill the section")
                (is (some? (:fake-healthy by-kind))
                    "the healthy check's warnings survive")
                (is (some? synth) "the throw became a synthetic cluster")
                (is (str/includes? (:seon.warn/explain synth)
                                   "boom from fake check")
                    "the cluster carries the throw's message")
                (is (str/includes? (:seon.warn/explain synth)
                                   "throwing-fake-check")
                    "names the EXACT broken check, not 'a check'")
                (let [text (warn/render-warnings {:seon.db/db db})]
                  (is (str/includes? text "fake-healthy")
                      "rendered block keeps the healthy cluster")
                  (is (str/includes? text "warn-check-error")
                      "and renders the broken check loudly"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
