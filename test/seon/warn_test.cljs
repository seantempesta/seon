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
    [cljs.test :refer [deftest is testing async]]
    [seon.client :as client]
    [seon.db :as db]
    [seon.warn :as warn]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh conn + a corpus of defective and clean rows.
;; ---------------------------------------------------------------------------

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
     {:seon.message/id "MSGwarntest001"
      :seon.message/role :user
      :seon.message/content "hello"
      :seon.message/at (t 100)}
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
      :seon.eval/duration-ms 1500
      :seon.eval/source "(slow)"
      :seon.eval/ok? true}
     ;; failing test (failed, never passed)
     {:seon.test/sym "warntest.main/broken-test"
      :seon.test/ns [:seon.ns/name :warntest.main]
      :seon.test/source "(deftest broken-test (is false))"
      :seon.test/last-failed-at (t 300)
      :seon.test/created-at (t 0)}]))

(defn- with-seeded-db
  "Open a fresh conn, seed it, call `body` with the post-tx db value.
   Returns a Promise."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data (seed-tx)})
                     (.then (fn [_]
                              (binding [db/*conn* conn]
                                (body @conn))))))))))

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

(deftest missing-test-spares-the-tested-fn
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r (warn/check-missing-test (scoped db))]
              (is (not (contains? (affected-syms r) "warntest.main/clean"))
                  "clean has clean-test — covered")
              (is (contains? (affected-syms r) "warntest.main/any-ret")
                  "untested fns are flagged"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

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
                  "unscoped = whole-substrate overview"))))
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

(deftest bad-ref-translates-the-cryptic-datahike-error
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [r     (warn/check-bad-ref {:seon.db/db db})
                  entry (first (:seon.warn/affected r))]
              (is (= #{"EVLwarnREF0001"} (affected-syms r)))
              (is (= "lookup-ref on :kb.doc/path" (:seon.warn/where entry))
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
              (is (= "1500ms" (:seon.warn/where
                                (first (:seon.warn/affected slow)))))
              (is (= #{"warntest.main/broken-test"} (affected-syms ftst))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Clustered renderer.
;; ---------------------------------------------------------------------------

(deftest render-warnings-clusters-once-per-kind
  (async done
    (-> (with-seeded-db
          (fn [db]
            (let [text (warn/render-warnings (scoped db))]
              (is (str/starts-with? text "<warnings>"))
              (is (= 1 (count (re-seq #"\[return-is-any\]" text)))
                  "ONE cluster header per kind — explanation never repeats")
              (is (str/includes? text "Affecting: warntest.main/any-ret (return) (1). Please correct before moving on.")
                  "affected list carries the location + the closing ask")
              (is (str/includes? text "Fix example:"))
              (is (not (str/includes? text "warntest.other/also-unspecced"))
                  "corpus clusters respect the ns scope"))))
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
