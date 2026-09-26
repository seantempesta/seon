(ns seon.cluster.program-change-test
  "Development adoption waits for no one. A test member reads the loaded
  program identity when it starts and when it ends; a change between them
  makes it unknown (`:program-unknown`, never green or red). A failed adoption
  leaves the program unknown until one records. Every test injects its own
  identity atom; the JVM's `seon.sci.eval/loaded-identity` is never touched."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [my.program]
            [seon.fn]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test]
            [seon.test-support :as support]))

(def ^:private adopted
  {:seon.source/reloaded-namespaces [] :seon.source/arming-identities #{}})

(defn- adopt!
  "Run one adoption toward `commit` on `loaded`; a failure answers its root
  cause's message (a throw inside `load-string` arrives wrapped by the Compiler)."
  [loaded commit work]
  (try (@#'cluster/call-with-program-change loaded commit work)
       (catch Exception failure
         (ex-message (last (take-while some? (iterate ex-cause failure)))))))

(defn- member-run
  "Run `body` as one member test through `seon.test/bounded-result` on `loaded`,
  admitted at `since` (default: the identity it reads now)."
  ([loaded body] (member-run loaded @loaded body))
  ([loaded since body]
  (let [owned (create-ns (gensym "seon.cluster.program-change-test.member-"))
        probe (intern owned (with-meta 'probe {:test body}) nil)]
    (try (@#'seon.test/bounded-result
          loaded since (symbol probe) (constantly probe) 5000
          {:seon.sci.eval/ctx (:seon.sci.eval/ctx (support/execution-handle nil))} (fn [_] nil))
         (finally (remove-ns (ns-name owned)))))))

(deftest an-adoption-proceeds-while-a-member-runs-and-the-member-reads-unknown
  (testing "no change: the member's ordinary result"
    (let [result (member-run (atom {:seon.source/commit-id (random-uuid)}) #(is true))]
      (is (= 1 (:seon.test/pass-count result)))
      (is (not (contains? result :seon.test.run/stopped)))))
  (testing "an adoption completes while the member is still running"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})
          commit (random-uuid)
          entered (promise)
          release (promise)
          member (future (member-run loaded #(do (deliver entered true) (is @release))))
          _ (is (true? (deref entered 1000 false)) "the member started")
          adoption (future (adopt! loaded commit (constantly adopted)))]
      (is (= adopted (deref adoption 1000 nil)) "the adoption never waited for the member")
      (is (= {:seon.source/commit-id commit} @loaded))
      (deliver release true)
      (let [result (deref member 5000 nil)]
        (is (= :program-unknown (:seon.test.run/stopped result)) "unknown, never green")
        (is (zero? (:seon.test/pass-count result))))))
  (testing "an adoption the member runs itself is its own work"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})]
      (is (= :own (sci.eval/call-detecting-program-change
                   loaded `own #(do (adopt! loaded (random-uuid) (constantly adopted)) :own))))))
  (testing "one member's own adoption makes a concurrently running member unknown, even under one enclosing execution"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})
          b-entered (promise)
          b-release (promise)
          [a b] (sci.eval/call-detecting-program-change
                 loaded `enclosing
                 (fn []
                   (let [b (future (member-run loaded #(do (deliver b-entered true) (is @b-release))))
                         _ (is (true? (deref b-entered 1000 false)))
                         a (member-run loaded #(is (= adopted (adopt! loaded (random-uuid) (constantly adopted)))))]
                     (deliver b-release true)
                     [a (deref b 5000 nil)])))]
      (is (= 1 (:seon.test/pass-count a)) "A's own adoption keeps A's result")
      (is (= :program-unknown (:seon.test.run/stopped b)) "B saw its program change")))
  (testing "an adoption between admission and a member's start: the member never runs"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})
          since @loaded
          ran (atom false)]
      (adopt! loaded (random-uuid) (constantly adopted))
      (let [result (member-run loaded since #(reset! ran true))]
        (is (= :program-unknown (:seon.test.run/stopped result)))
        (is (false? @ran) "admission's program is not the execution's")))))

(deftest an-adoption-failing-after-roots-change-keeps-the-program-unknown-until-one-records
  (let [jvm-loaded @sci.eval/loaded-identity
        loaded (atom {})
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        entries (atom 0)
        member #(sci.eval/call-detecting-program-change
                 loaded `member (fn [] (swap! entries inc) :entered))
        scratch "seon.cluster.program-change-scratch"]
    (testing "a reload that fails after defining a Var"
      (let [commit (random-uuid)]
        (is (= "reload stopped midway"
               (adopt! loaded commit #(load-string (str "(ns " scratch ") (def loaded :new)"
                                                        " (throw (ex-info \"reload stopped midway\" {}))")))))
        (is (= :new @(resolve (symbol scratch "loaded"))) "the root changed before the failure")
        (is (= commit (:seon.source/commit-id @loaded)))
        (is (= "reload stopped midway" (last (keep :seon.error/message (:seon.error/chain @loaded))))
            "the chain keeps the root cause")))
    (doseq [[label message] [["arming" "Development JVM instrumentation did not restore contracts."]
                             ["the record" "The cluster population transaction was refused: basis moved"]]]
      (testing (str label " refused after the reload")
        (let [commit (random-uuid)]
          (is (= message (adopt! loaded commit #(@#'cluster/refused! message {:seon.cluster.source/phase :adoption}))))
          (is (= commit (:seon.source/commit-id @loaded))))))
    (testing "nothing starts in the unknown program; it answers the diagnostic"
      (let [refusal (member)]
        (is (zero? @entries))
        (is (= (:seon.source/commit-id @loaded) (:seon.source/commit-id refusal)))
        (is (schema/valid-candidate-value? (db/carried-projection database)
                                           :seon.test/not-runnable-error refusal))))
    (testing "a nested entry after the execution's own adoption failed refuses by name"
      (let [own (atom {:seon.source/commit-id (random-uuid)})
            answer (sci.eval/call-detecting-program-change
                    own `outer
                    (fn [] (adopt! own (random-uuid) #(throw (ex-info "arming refused" {})))
                      (sci.eval/call-detecting-program-change own `inner (fn [] :entered))))]
        (is (= 'seon.cluster/call-with-program-change (:seon.error/operation answer)) "the failed adoption, named")
        (is (= ["arming refused"] (keep :seon.error/message (:seon.error/chain answer))))))
    (testing "a later adoption that records makes execution available again"
      (let [commit (random-uuid)]
        (is (= adopted (adopt! loaded commit (constantly adopted))))
        (is (= {:seon.source/commit-id commit} @loaded))
        (is (= :entered (member)))
        (is (= 1 @entries))))
    (testing "a loaded commit this value's store lacks, known or unknown, still keys a projection"
      ;; A failed materialization cached as a thrown `delay` once refused every later read.
      (let [absent (random-uuid)
            builder #(@#'db/loaded-builder database % nil)]
        (doseq [program [{:seon.source/commit-id absent}
                         (let [failed (atom {})]
                           (adopt! failed absent #(throw (ex-info "arming refused" {})))
                           @failed)]]
          (is (string? (builder program)))
          (is (= (builder program) (builder program)) "nothing failed was cached"))))
    (is (= jvm-loaded @sci.eval/loaded-identity) "the JVM's loaded identity is untouched")
    (remove-ns (symbol scratch))))

(deftest a-jvm-running-the-published-bytes-names-that-commit
  ;; Boot names its published commit as the loaded program only when every
  ;; published file it loaded carries the published bytes; the adoption after
  ;; boot then writes rows and never reloads what the JVM just loaded.
  (let [database (db/db (:seon.db/connection (support/execution-handle nil)))
        published (into {} (take 3) (db/q '[:find ?path ?digest
                                           :where [?file :seon.fn.file/relative-path ?path]
                                                  [?file :seon.fn.file/digest ?digest]]
                                         database))
        commit (random-uuid)
        named {:seon.source/commit-id commit}]
    (is (= 3 (count published)) "the fixture publishes files")
    (is (= named (cluster/loaded-commit database commit published)))
    (is (= named (cluster/loaded-commit database commit (assoc published "src/not/published.clj" (apply str (repeat 64 "a")))))
        "a loaded file the commit does not publish is no program of it")
    (is (nil? (cluster/loaded-commit database commit (assoc published (key (first published)) (apply str (repeat 64 "b")))))
        "one loaded file with other bytes names nothing: adoption reloads")
    (is (nil? (cluster/loaded-commit database commit {})) "nothing loaded names nothing")))

(deftest the-loaded-program-inputs-include-every-arming-input
  ;; Arming reads the loaded source files AND the packaged schema resources
  ;; (contracts and supplied defaults compile from both), so the boot shortcut
  ;; compares both against the one commit it armed (audit F10).
  (let [inputs (@#'cluster/loaded-program-digests)
        paths (set (keys inputs))]
    (is (some #(str/starts-with? % "resources/seon/schemas/") paths)
        "every packaged schema resource is an input")
    (is (contains? paths "src/seon/cluster.clj") "a loaded first-party namespace is an input")
    (is (every? string? (vals inputs)))))

(deftest a-booted-wrapper-supplies-its-declared-defaults
  ;; Boot arms against the published commit's projection, which holds the
  ;; function contracts a call-preparation plan is read from; a JVM test body
  ;; runs as its member's agent (`kernel/*host-world*`), so a plain call
  ;; receives its supplied :seon.db/db exactly as an agent's evaluation does.
  (support/with-database
   (fn [_connection]
     (let [result (my.program/breaks {:seon.program/subject 'seon.id/valid?})]
       (is (map? result))
       (is (not (:seon.error/at result)) (pr-str (select-keys result [:seon.error/message])))))))

(deftest an-explicit-empty-custody-clears-the-callers-world
  ;; A JVM test body runs as its member (`kernel/*host-world*`), so an omitted
  ;; declared value is supplied from the member's world. A custody the caller
  ;; states is not a default: `(call-with-custody {} f)` means no custody, and
  ;; `seon.cluster.host/start!` relies on it to write another branch
  ;; deliberately. Its entry has its own schema name so preparation never
  ;; fills it (`:seon.db/custody-connection`).
  (support/with-database
   (fn [connection]
     (is (identical? connection (db/call-with-custody {:seon.db/connection connection} (fn [] db/*conn*))))
     (is (nil? (db/call-with-custody {} (fn [] db/*conn*))))
     (is (nil? (db/call-without-custody (fn [] db/*conn*)))))))

(deftest a-call-matching-a-declared-arity-is-never-prefixed
  ;; `seon.fn/gate-sets` declares a request-map arity and a positional
  ;; [database symbols] arity. A one-argument call is the map arity in full:
  ;; supplying the positional arity's leading database in front of the map
  ;; (the dispatch meant for two shapes that share their first slot) sent
  ;; the call two arguments and refused it. Tests run as their member, so the
  ;; call is prepared exactly as an agent's (patch call-preparation-declared-arity).
  (support/with-database
   (fn [connection]
     (let [result (seon.fn/gate-sets {:seon.db/db (db/db connection) :seon.fn/seeds ['seon.id/valid?]})]
       (is (vector? result) (pr-str result))))))
