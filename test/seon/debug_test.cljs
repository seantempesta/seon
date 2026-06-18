(ns seon.debug-test
  "Part D — debug-capture coverage (eval-robustness-and-debug PRD §B).

   Pins the FOUR behaviors the user asked for, kept deliberately simple:

     1. Flag parsing — OFF by default; ON for truthy values; OFF for
        unset / \"\" / \"0\" / \"off\" / \"false\" / \"no\" (case-insensitive).
     2. Gated writes — OFF → nothing on disk; ON → prompt.txt +
        response.txt + response.edn under
        <dir>/<agent-id>/<turn-idx>-<turn-id>/, contents VERBATIM
        (incl. a BLANK response still writing response.txt).
     3. No silent overwrite — a second capture for the same turn key
        suffixes (response-2.txt), never clobbers the first.
     4. Round-trip — what was written reads back equal; response.edn
        `cljs.reader/read-string`s back to the original resp map.

   Plus a real-`run-turn!` integration test proving the turn-id is
   threaded as a LOCAL to the write site (paired prompt + response in
   one dir) and that the turn datom carries the debug-dir pointer.

   FS tests use a project-local tmp dir (`tmp/debug-test-<rand>`), NEVER
   /tmp, and clean up after themselves. The env var + process override
   are saved and restored around every test that touches them, so a
   failure can't leak capture-on into the rest of the suite."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is testing async use-fixtures]]
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.debug :as debug]
    [seon.eval :as seval]
    [seon.repl :as repl]))

(def ^:private fs (js/require "node:fs"))

;; ---------------------------------------------------------------------------
;; Env / override save-restore — capture-on must never leak across tests.
;; ---------------------------------------------------------------------------

(defn- env-get [k] (some-> (.. js/globalThis -process -env) (aget k)))

(defn- env-set! [k v]
  (if (nil? v)
    (js-delete (.. js/globalThis -process -env) k)
    (aset (.. js/globalThis -process -env) k v)))

(use-fixtures :each
  {:before (fn []
             (set! (.-prev-flag js/globalThis) (env-get "SEON_DEBUG_CAPTURE"))
             (set! (.-prev-dir js/globalThis) (env-get "SEON_DEBUG_CAPTURE_DIR"))
             ;; force env-driven so each test controls the flag explicitly
             (debug/set-override! :env)
             (env-set! "SEON_DEBUG_CAPTURE" nil)
             (env-set! "SEON_DEBUG_CAPTURE_DIR" nil))
   :after  (fn []
             (debug/set-override! :env)
             (env-set! "SEON_DEBUG_CAPTURE" (.-prev-flag js/globalThis))
             (env-set! "SEON_DEBUG_CAPTURE_DIR" (.-prev-dir js/globalThis)))})

(defn- tmp-base []
  (str "tmp/debug-test-" (.toString (rand-int 1e9))))

(defn- rm-rf! [path] (.rmSync fs path #js {:recursive true :force true}))

;; ===========================================================================
;; 1 — flag parsing
;; ===========================================================================

(deftest flag-off-by-default
  (testing "unset env + env-driven override → OFF"
    (debug/set-override! :env)
    (env-set! "SEON_DEBUG_CAPTURE" nil)
    (is (false? (debug/enabled?)))))

(deftest flag-truthy-on-falsy-off
  (debug/set-override! :env)
  (testing "truthy values enable"
    (doseq [v ["1" "on" "true" "yes" "anything" "PROMPT"]]
      (env-set! "SEON_DEBUG_CAPTURE" v)
      (is (true? (debug/enabled?)) (str "ON for " (pr-str v)))))
  (testing "falsy / blank values (case-insensitive) disable"
    (doseq [v ["" "0" "off" "false" "no" "OFF" "False" "NO"]]
      (env-set! "SEON_DEBUG_CAPTURE" v)
      (is (false? (debug/enabled?)) (str "OFF for " (pr-str v)))))
  (testing "unset disables"
    (env-set! "SEON_DEBUG_CAPTURE" nil)
    (is (false? (debug/enabled?)))))

(deftest override-wins-over-env
  (testing ":on override forces on even with env off"
    (env-set! "SEON_DEBUG_CAPTURE" "off")
    (debug/set-override! :on)
    (is (true? (debug/enabled?))))
  (testing ":off override forces off even with env on"
    (env-set! "SEON_DEBUG_CAPTURE" "true")
    (debug/set-override! :off)
    (is (false? (debug/enabled?))))
  (testing ":env defers back to env"
    (env-set! "SEON_DEBUG_CAPTURE" "true")
    (debug/set-override! :env)
    (is (true? (debug/enabled?)))))

(deftest capture-dir-default-and-override
  (testing "default is logs/turns"
    (env-set! "SEON_DEBUG_CAPTURE_DIR" nil)
    (is (= "logs/turns" (debug/capture-dir))))
  (testing "SEON_DEBUG_CAPTURE_DIR is honored"
    (env-set! "SEON_DEBUG_CAPTURE_DIR" "tmp/some-other-dir")
    (is (= "tmp/some-other-dir" (debug/capture-dir))))
  (testing "turn-dir keys by agent / turn-idx-turn-id"
    (env-set! "SEON_DEBUG_CAPTURE_DIR" "logs/turns")
    (is (= "logs/turns/AGTx/3-tid-9"
           (debug/turn-dir "AGTx" 3 "tid-9")))))

;; ===========================================================================
;; 2 — gated writes (off → nothing; on → all three artifacts, verbatim)
;; ===========================================================================

(deftest off-writes-nothing
  (let [base (tmp-base)]
    (try
      (env-set! "SEON_DEBUG_CAPTURE_DIR" base)
      (debug/set-override! :off)
      (is (nil? (debug/capture-prompt! "AGTa" 0 "tid" "PROMPT")))
      (is (nil? (debug/capture-response! "AGTa" 0 "tid" "REPLY" {:text "REPLY"})))
      (is (false? (.existsSync fs base)) "no directory created when off")
      (finally (rm-rf! base)))))

(deftest on-writes-all-three-verbatim
  (let [base (tmp-base)]
    (try
      (env-set! "SEON_DEBUG_CAPTURE_DIR" base)
      (debug/set-override! :on)
      (let [prompt "VERBATIM PROMPT\nline two"
            reply  "(defn f [] 1)\n"
            resp   {:text reply :seon.ai/usage {:tokens 7} :seon.ai/raw {:k :v}}
            pf     (debug/capture-prompt! "AGTb" 2 "tid-xyz" prompt)
            dd     (debug/capture-response! "AGTb" 2 "tid-xyz" reply resp)
            dir    "/AGTb/2-tid-xyz"]
        (is (str/ends-with? pf (str dir "/prompt.txt")))
        (is (str/ends-with? dd dir) "capture-response! returns the per-turn dir")
        (is (= prompt (.readFileSync fs pf "utf8")) "prompt.txt is verbatim")
        (is (= reply (.readFileSync fs (str dd "/response.txt") "utf8"))
            "response.txt is verbatim")
        (is (= resp (reader/read-string (.readFileSync fs (str dd "/response.edn") "utf8")))
            "response.edn round-trips to the resp map"))
      (finally (rm-rf! base)))))

(deftest blank-response-still-writes-response-txt
  (let [base (tmp-base)]
    (try
      (env-set! "SEON_DEBUG_CAPTURE_DIR" base)
      (debug/set-override! :on)
      (let [dd (debug/capture-response! "AGTc" 0 "tid" "" {:text ""})]
        (is (.existsSync fs (str dd "/response.txt"))
            "a BLANK reply still writes response.txt (closes the blank-output gap)")
        (is (= "" (.readFileSync fs (str dd "/response.txt") "utf8"))))
      (finally (rm-rf! base)))))

;; ===========================================================================
;; 3 — collision / no silent overwrite
;; ===========================================================================

(deftest no-silent-overwrite-suffixes
  (let [base (tmp-base)]
    (try
      (env-set! "SEON_DEBUG_CAPTURE_DIR" base)
      (debug/set-override! :on)
      (let [p1 (debug/capture-prompt! "AGTd" 0 "same-tid" "FIRST")
            p2 (debug/capture-prompt! "AGTd" 0 "same-tid" "SECOND")
            p3 (debug/capture-prompt! "AGTd" 0 "same-tid" "THIRD")
            dir "AGTd/0-same-tid"]
        (is (str/ends-with? p1 "/prompt.txt"))
        (is (str/ends-with? p2 "/prompt-2.txt") "second capture suffixes")
        (is (str/ends-with? p3 "/prompt-3.txt") "third capture suffixes again")
        (is (= "FIRST" (.readFileSync fs p1 "utf8")) "first is NOT clobbered")
        (is (= "SECOND" (.readFileSync fs p2 "utf8")))
        (is (= "THIRD" (.readFileSync fs p3 "utf8")))
        (is (= #{"prompt.txt" "prompt-2.txt" "prompt-3.txt"}
               (set (.readdirSync fs (str base "/" dir))))))
      (finally (rm-rf! base)))))

;; ===========================================================================
;; 4 — real run-turn! integration: paired prompt+response, datom pointer,
;;     turn-id threaded as a LOCAL to the write site (survives the await).
;; ===========================================================================

(defn- with-conn
  "Fresh schema-loaded :memory conn as the ROOT db/*conn* (set! survives
   the await boundary), run `body` (0-arg, may return a Promise), restore
   the prior conn after. Returns a Promise."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(deftest run-turn!-captures-paired-prompt-and-response
  (async done
    (let [base (tmp-base)]
      (env-set! "SEON_DEBUG_CAPTURE_DIR" base)
      (debug/set-override! :on)
      (-> (with-conn
            (fn ^:async run []
              (let [cs  (await (repl/ensure-bootstrap!))
                    aid (db/new-id!)]   ;; valid 14-char :seon.db/id
                (await (db/with-agent aid
                         (fn ^:async boot []
                           (await (seval/setup-agent-ns! cs (agent/home-ns aid) aid))
                           (await (agent/create! {:seon.agent/id aid})))))
                ;; A stub LLM whose reply is a known verbatim string. The
                ;; turn-id is minted inside run-turn! and threaded as a
                ;; local to the response write site — if ALS were relied
                ;; on across the await, the prompt + response would land
                ;; in DIFFERENT dirs (or none). Same-dir pairing pins it.
                (let [reply "(+ 100 200)\n"
                      turn  (await
                              (db/with-agent aid
                                (fn []
                                  (agent/run-turn!
                                    {:seon.agent/id aid
                                     :seon.agent/llm-fn
                                     (fn [_p] (js/Promise.resolve
                                                {:text reply :seon.ai/usage {:t 3}}))
                                     :seon.agent/compile-state cs}))))
                      dd    (:seon.agent.turn/debug-dir turn)
                      pf    (:seon.agent.turn/prompt-file turn)]
                  (is (some? dd) "turn datom carries the debug-dir pointer")
                  (is (some? pf) "turn datom carries the prompt-file pointer")
                  ;; both pointers are the SAME per-turn dir → paired
                  (is (str/starts-with? pf (str dd "/"))
                      "prompt + response live in the same per-turn dir")
                  (is (str/includes? dd (str base "/" aid "/0-"))
                      "keyed by agent + monotonic turn-idx 0")
                  (is (= reply (.readFileSync fs (str dd "/response.txt") "utf8"))
                      "response.txt holds the verbatim reply written post-await")
                  (let [prompt-text (.readFileSync fs pf "utf8")]
                    (is (pos? (count prompt-text)) "prompt.txt is non-empty"))))))
          (.then (fn [] (rm-rf! base) (done)))
          (.catch (fn [e] (rm-rf! base) (is false (str "threw — " e)) (done)))))))

(deftest run-turn!-off-writes-no-artifacts
  (async done
    (let [base (tmp-base)]
      (env-set! "SEON_DEBUG_CAPTURE_DIR" base)
      (debug/set-override! :off)
      (-> (with-conn
            (fn ^:async run []
              (let [cs  (await (repl/ensure-bootstrap!))
                    aid (db/new-id!)]   ;; valid 14-char :seon.db/id
                (await (db/with-agent aid
                         (fn ^:async boot []
                           (await (seval/setup-agent-ns! cs (agent/home-ns aid) aid))
                           (await (agent/create! {:seon.agent/id aid})))))
                (let [turn (await
                             (db/with-agent aid
                               (fn []
                                 (agent/run-turn!
                                   {:seon.agent/id aid
                                    :seon.agent/llm-fn
                                    (fn [_p] (js/Promise.resolve {:text "(+ 1 1)\n"}))
                                    :seon.agent/compile-state cs}))))]
                  (is (nil? (:seon.agent.turn/debug-dir turn))
                      "no debug-dir pointer when capture is off")
                  (is (nil? (:seon.agent.turn/prompt-file turn))
                      "no prompt-file pointer when capture is off")
                  (is (false? (.existsSync fs base))
                      "no capture dir created when off")
                  (is (= :done (:seon.agent.turn/status turn))
                      "turn still completes normally with capture off")))))
          (.then (fn [] (rm-rf! base) (done)))
          (.catch (fn [e] (rm-rf! base) (is false (str "threw — " e)) (done)))))))
