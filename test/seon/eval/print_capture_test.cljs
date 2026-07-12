(ns seon.eval.print-capture-test
  "#64 regression: per-eval println/prn capture is per-FIBER, isolated across
   overlapping `^:async` evals.

   `*print-fn*` / `*print-err-fn*` are process-global. The old eval-form-entry!
   capture did `(set! *print-fn* cap)` BEFORE the eval+auto-await and restored
   it AFTER — a `set!` straddling an `await`. When the captured form yielded
   (any awaiting function), a CONCURRENT eval ran with this eval's `cap` still
   installed, so its prints bled into the wrong `:seon.eval/output` bucket.

   The fix routes capture through `seon.eval/print-als` (a Node
   AsyncLocalStorage): a single global dispatcher reads the active bucket via
   `.getStore`, and each eval opens its OWN bucket via `.run`. ALS carries the
   bucket across the form's awaits, so concurrent evals stay isolated.

   This test runs two batches concurrently: A prints, AWAITS (long), prints
   again; B (started during A's await) prints, awaits briefly, prints again.
   Each eval row's `:seon.eval/output` must contain ONLY its own prints.

   Run via `bin/test-cljs`, or:
     (require 'seon.eval.print-capture-test :reload)
     (cljs.test/run-tests 'seon.eval.print-capture-test)"
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.home :as home]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-int]))

(defn- with-conn
  "Open a fresh :memory conn, `set!` it as the ROOT `db/*conn*` (a plain
   `binding` does NOT survive await boundaries in CLJS), run `body` (0-arg,
   returns a Promise), restore the prior root after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- with-print-dispatcher
  "Make a `print-als`-routing dispatcher the active `*print-fn*` /
   `*print-err-fn*` for `body` (0-arg, returns a Promise), restore after. The
   pod installs this dispatcher at boot (install-print-dispatcher!), but the
   `:node-test` runner owns `*print-fn*` itself — so the test installs the
   SAME-shaped dispatcher over the SAME `seon.eval/print-als` instance to make
   the per-fiber routing observable here. This is exactly what production does;
   the eval-form-entry! `.run print-als` scope is what's under test."
  [body]
  (let [prev-out *print-fn*
        prev-err *print-err-fn*
        dispatch (fn [& xs]
                   (if-let [bucket (.getStore seval/print-als)]
                     (swap! bucket str (apply str xs))
                     (apply prev-out xs)))]
    (set! *print-fn* dispatch)
    (set! *print-err-fn* dispatch)
    (-> (js/Promise.resolve (body))
        (.finally (fn []
                    (set! *print-fn* prev-out)
                    (set! *print-err-fn* prev-err))))))

(defn- run-batch
  "Run `source` (one form) through eval-batch! in `aid`'s home ns against the
   current root conn. Returns a Promise of the eval-batch! result map. Goes
   through eval-form-entry! — the real per-eval print-capture site."
  [_aid _turn-id source]
  (-> (repl/ensure-bootstrap!)
      (.then (fn [cs]
               (-> (db.id/allocate!
                     {::db.id/allocations
                      [{::db.id/key ::fixture-agent
                        ::db.id/identity-attr :seon.agent/id}
                       {::db.id/key ::fixture-turn
                        ::db.id/identity-attr :seon.agent.turn/id}]
                      ::db.id/transaction-builder
                      (fn [ids]
                        {:seon.db/tx-data
                         [{:seon.agent/id (::fixture-agent ids)}
                          {:seon.agent.turn/id (::fixture-turn ids)}]})
                      :seon.db/conn db/*conn*})
                   (.then
                     (fn [env]
                       (let [aid (get-in env [::db.id/ids ::fixture-agent])
                             hns (home/home-ns aid)]
                         (-> (seval/setup-agent-ns! cs hns aid)
                             (.then
                               (fn [_]
                                 (seval/eval-batch!
                                   cs (repl-int/parse-forms source) hns aid
                                   (get-in env [::db.id/ids ::fixture-turn])
                                   nil))))))))))))

(defn- output-containing
  "The `:seon.eval/output` string of the eval row whose output contains
   `needle`, or nil. Reads against the current root conn."
  [needle]
  (->> (db/query '[:find ?out :where [?e :seon.eval/output ?out]])
       (map first)
       (some (fn [o] (when (str/includes? o needle) o)))))

;; A self-invoking `^:async` fn that prints, awaits (so control yields), then
;; prints again. The auto-await resolves the returned Promise to data within
;; the capture span — the prints land in this eval's `:seon.eval/output`.
(def ^:private a-src
  (str "((fn ^:async f []"
       " (println \"AAA-before\")"
       " (await (js/Promise. (fn [r] (js/setTimeout r 150))))"
       " (println \"AAA-after\") :a-done))"))

(def ^:private b-src
  (str "((fn ^:async f []"
       " (println \"BBB-before\")"
       " (await (js/Promise. (fn [r] (js/setTimeout r 20))))"
       " (println \"BBB-after\") :b-done))"))

(deftest print-capture-isolated-across-overlapping-evals-64
  (async done
    (-> (with-conn
          (fn []
            (with-print-dispatcher
              (fn []
                (-> (js/Promise.all
                      #js [;; A: starts now, awaits 150ms.
                           (run-batch "print-a-64" "turnprint001" a-src)
                           ;; B: starts 40ms in (during A's await), runs entirely
                           ;; inside A's await window — the exact bleed condition.
                           (js/Promise.
                             (fn [res rej]
                               (js/setTimeout
                                 (fn [] (.then (run-batch "print-b-64" "turnprint002" b-src)
                                               res rej))
                                 40)))])
                    ;; Assert INSIDE with-conn — the rows live on the :memory
                    ;; conn it set! as root; with-conn restores the prior conn
                    ;; on resolve, so a query in an OUTER `.then` would hit the
                    ;; wrong conn and read nil.
                    (.then
                      (fn [_]
                        (let [a-out (output-containing "AAA")
                              b-out (output-containing "BBB")]
                          (is (some? a-out) "A produced a captured output row")
                          (is (some? b-out) "B produced a captured output row")
                          (is (and a-out (str/includes? a-out "AAA-before")) "A keeps its pre-await print")
                          (is (and a-out (str/includes? a-out "AAA-after")) "A keeps its post-await print")
                          (is (and a-out (not (str/includes? a-out "BBB")))
                              "no bleed: B's prints stay out of A's bucket")
                          (is (and b-out (str/includes? b-out "BBB-before")) "B keeps its pre-await print")
                          (is (and b-out (str/includes? b-out "BBB-after")) "B keeps its post-await print")
                          (is (and b-out (not (str/includes? b-out "AAA")))
                              "no bleed: A's prints stay out of B's bucket")))))))))
        (.then (fn [_] (done))
               (fn [e] (is false (str "eval threw: " e)) (done))))))
