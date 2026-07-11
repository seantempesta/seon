(ns seon.agent.turn-capture-test
  "Observability turn-capture contract (observability.md):

     1. CAPTURE ON SUCCESS — a real `run-turn!` persists, ALWAYS ON (no
        debug flag): `:seon.agent.turn/rendered-as-of` (the PRE-turn
        basis-t of the frozen db the prompt rendered from), a
        `:seon.agent.turn/prompt-blob` ref whose content is the VERBATIM
        assembled prompt (system + ctx — the exact bytes the adapters
        build), and a `:seon.agent.turn/reply-blob` ref holding the raw
        LLM reply.
     2. CAPTURE ON ERROR — an LLM failure still leaves rendered-as-of +
        the prompt blob on the turn, plus `:seon.agent.turn/error` as
        data; capture never depends on turn success.
     3. `inspect/turn` ROUND-TRIP — one call reconstructs the turn:
        the verbatim prompt/reply text back from the blobs, token
        estimates from the ONE estimator, and the tx trail via the
        `:seon.db/turn-id` tx-meta join. Unknown ids are error VALUES.
     4. `inspect/turn-diff` — basis-t delta + prompt drift summary
        between two captured turns.

   Hermetic: blobs go to a pid-scoped tmp dir (my.blob/!dir re-pointed
   and restored), and each test runs on a fresh :memory conn root-set!
   as db/*conn* (set!, not binding — CLJS dynamic bindings don't survive
   await; see my.blob-test)."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [my.blob :as blob]
    [seon.agent :as agent]
    [seon.agent.run :as run]
    [seon.agent.inspect :as inspect]
    [seon.agent.turn :as turn]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.test-seed :as test-seed]))

;; ---------------------------------------------------------------------------
;; Fixtures — pid-scoped blob dir, fresh conn per test.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/turn-capture-test-" (.-pid js/process))))

(defonce ^:private !saved-dir (atom nil))

(use-fixtures :once
  {:before (fn []
             (reset! !saved-dir @blob/!dir)
             (reset! blob/!dir fixture-dir)
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))
   :after  (fn []
             (reset! blob/!dir @!saved-dir)
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))})

(defn- with-conn
  "Fresh schema-loaded :memory conn as the ROOT db/*conn*, run `body`
   (0-arg, may return a Promise), restore the prior conn after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (db/transact! {:seon.db/tx-data (test-seed/my-core-rows)})
                     (.then (fn [_] (body)))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- run-test [chain done]
  (-> (with-conn chain)
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

(defn ^:async fresh-agent!
  "Create a booted agent on the current conn; resolves to its id + cs."
  []
  (let [cs  (await (repl/ensure-bootstrap!))
        aid (db/new-id!)]
    (await (db/with-agent aid
             (fn ^:async boot []
               (await (seval/setup-agent-ns! cs (agent/home-ns aid) aid))
               (await (agent/create! {:seon.agent/id aid})))))
    {:seon.agent/id aid :seon.agent/compile-state cs}))

(defn ^:async drive-turn!
  "One real run-turn! for the agent with a stub llm-fn; resolves to the turn."
  [{aid :seon.agent/id cs :seon.agent/compile-state} llm-fn]
  (await (db/with-agent aid
           (fn []
             (turn/run-turn! {:seon.agent/id            aid
                              :seon.agent/llm-fn        llm-fn
                              :seon.agent/compile-state cs})))))

;; ---------------------------------------------------------------------------
;; 1 + 3. Capture on success → inspect/turn round-trip.
;; ---------------------------------------------------------------------------

(deftest success-turn-captures-basis-t-and-both-blobs
  (async done
    (run-test
      (fn ^:async run []
        (let [a        (await (fresh-agent!))
              reply    "(+ 100 200)\n"
              !ctx     (atom nil)
              expected (db/basis-t @db/*conn*)
              turn     (await (drive-turn!
                                a (fn [p]
                                    (reset! !ctx p)
                                    (js/Promise.resolve {:text reply}))))
              turn-id  (:seon.agent.turn/id turn)]
          (is (= :done (:seon.agent.turn/status turn)))
          ;; the PRE-turn basis-t — the frozen db the prompt rendered from
          (is (= expected (:seon.agent.turn/rendered-as-of turn))
              "rendered-as-of is the basis-t BEFORE the turn's own txs")
          (is (some? (:seon.agent.turn/prompt-blob turn))
              "the turn carries a prompt blob ref — always on")
          (is (some? (:seon.agent.turn/reply-blob turn))
              "the turn carries a reply blob ref")
          ;; inspect/turn — one call reconstructs the whole bundle
          (let [b (inspect/turn {:seon.agent.turn/id turn-id})]
            (is (true? (:seon.agent.inspect/ok? b)))
            (is (= (ai/debug-full-prompt {:seon.ai/ctx @!ctx})
                   (:seon.agent.inspect/prompt b))
                "the prompt comes back VERBATIM — the exact bytes built for the model")
            (is (= reply (:seon.agent.inspect/reply b))
                "the raw reply comes back VERBATIM")
            (is (= (tokens/estimate (:seon.agent.inspect/prompt b))
                   (:seon.agent.inspect/prompt-tokens b))
                "prompt size reports in TOKENS from the one estimator")
            (is (= expected (:seon.agent.turn/rendered-as-of b)))
            (is (pos? (count (:seon.agent.inspect/txs b)))
                "the tx trail joins via the :seon.db/turn-id tx-meta stamp"))))
      done)))

;; ---------------------------------------------------------------------------
;; 2. Capture on error — an LLM failure still captures; error lands as data.
;; ---------------------------------------------------------------------------

(deftest error-turn-still-captures-prompt-and-records-the-error
  (async done
    (run-test
      (fn ^:async run []
        (let [a    (await (fresh-agent!))
              turn (await (drive-turn!
                            a (fn [_p]
                                (js/Promise.resolve
                                  {:seon.ai/error {:seon.ai/msg "boom: provider down"
                                                   :seon.ai/status 401}}))))
              turn-id (:seon.agent.turn/id turn)]
          (is (= :error (:seon.agent.turn/status turn)))
          (is (some? (:seon.agent.turn/rendered-as-of turn))
              "basis-t captured before the LLM call — independent of success")
          (is (some? (:seon.agent.turn/prompt-blob turn))
              "prompt blob captured before the LLM call")
          (is (nil? (:seon.agent.turn/reply-blob turn))
              "no reply ⇒ no reply blob (optional = absent, never nil)")
          (is (str/includes? (str (:seon.agent.turn/error turn))
                             "boom: provider down")
              "WHY it errored is on the turn, as data")
          (let [b (inspect/turn {:seon.agent.turn/id turn-id})]
            (is (true? (:seon.agent.inspect/ok? b)))
            (is (string? (:seon.agent.inspect/prompt b))
                "an errored turn still replays its prompt")
            (is (nil? (:seon.agent.inspect/reply b))))))
      done)))

;; ---------------------------------------------------------------------------
;; 3b. Errors are values — unknown turn id.
;; ---------------------------------------------------------------------------

(deftest inspect-turn-of-unknown-id-is-an-error-value
  (async done
    (run-test
      (fn ^:async run []
        (let [b (inspect/turn {:seon.agent.turn/id "20990101-xxxx"})]
          (is (false? (:seon.agent.inspect/ok? b)))
          (is (str/includes? (:seon.agent.inspect/error b) "no turn stored")
              "unknown id is a guiding value, never a throw")))
      done)))

;; ---------------------------------------------------------------------------
;; 4. turn-diff — basis-t delta + prompt drift between two captured turns.
;; ---------------------------------------------------------------------------

(deftest turn-diff-reports-basis-t-delta-and-prompt-drift
  (async done
    (run-test
      (fn ^:async run []
        (let [a   (await (fresh-agent!))
              llm (fn [reply] (fn [_p] (js/Promise.resolve {:text reply})))
              t1  (await (drive-turn! a (llm "(+ 1 1)\n")))
              t2  (await (drive-turn! a (llm "(+ 2 2)\n")))
              d   (inspect/turn-diff {:seon.agent.inspect/from (:seon.agent.turn/id t1)
                                      :seon.agent.inspect/to   (:seon.agent.turn/id t2)})]
          (is (true? (:seon.agent.inspect/ok? d)))
          (is (pos? (:seon.agent.inspect/basis-t-delta d))
              "turn 2 rendered over a LATER basis-t — the world advanced")
          (is (int? (:seon.agent.inspect/prompt-token-delta d))
              "prompt drift summarized in TOKENS")
          (is (pos? (:seon.agent.inspect/prompt-lines-added d))
              "turn 1's eval shows up in turn 2's transcript — lines added")
          (is (= (:seon.agent.turn/id t1)
                 (get-in d [:seon.agent.inspect/from-turn :seon.agent.turn/id])))
          (is (= "(+ 2 2)\n"
                 (get-in d [:seon.agent.inspect/to-turn :seon.agent.inspect/reply]))
              "the diff carries both reconstructed turns")))
      done)))

;; ---------------------------------------------------------------------------
;; 5. Current-ns persists ACROSS turns (rung-1 root cause, 2026-07-10):
;;    an (in-ns …) in turn N must be where turn N+1's forms run — the batch
;;    seeds from the DERIVED current-ns over the turn's frozen db, not the
;;    home ns. Before the fix every turn silently ran at home: defns landed
;;    in my.agent.*, ns-interns showed nil, cross-ns resolution failed
;;    (evals/runs/2026-07-10-minimal-buildup, ds-r1-ns-probe-d1).
;; ---------------------------------------------------------------------------

(deftest current-ns-persists-across-turns
  (async done
    (run-test
      (fn ^:async run []
        (let [a   (await (fresh-agent!))
              aid (:seon.agent/id a)
              ;; a REAL run: ctx/current-ns derives over agent->runs->turns,
              ;; so runless turns are invisible to it — the live loop always
              ;; drives under a run, and this pin must too.
              r   (await (run/open-run! {:seon.agent/id aid
                                         :seon.agent.run/trigger :message}))
              rid (:seon.agent.run/id r)
              drive! (fn ^:async drive! [reply]
                       (await (db/with-agent aid
                                (fn []
                                  (turn/run-turn!
                                    {:seon.agent/id            aid
                                     :seon.agent/llm-fn        (fn [_] (js/Promise.resolve {:text reply}))
                                     :seon.agent/compile-state (:seon.agent/compile-state a)
                                     :seon.agent.run/id        rid})))))]
          (await (drive! "(in-ns 'probe.tc.move)\n"))
          (await (drive! "(defn tmv [x] (* x 3))\n(tmv 2)\n"))
          (let [db*  @db/*conn*
                rows (->> (db/query {:seon.db/db db*
                                     :seon.db/query
                                     '[:find ?e ?src ?ns ?ok
                                       :where
                                       [?e :seon.eval/source ?src]
                                       [?e :seon.eval/ns ?ns]
                                       [?e :seon.eval/ok? ?ok]]})
                          (sort-by first))
                defn-row (first (filter #(str/includes? (second %) "defn tmv") rows))
                call-row (first (filter #(= "(tmv 2)" (second %)) rows))]
            (is (some? defn-row) "the defn eval recorded")
            (is (= :probe.tc.move (nth defn-row 2))
                "turn N+1's defn ran in the ns turn N moved to — not home")
            (is (true? (nth call-row 3)) "the same-ns call resolves")
            (is (= :probe.tc.move (nth call-row 2)))))
        nil)
      done)))
