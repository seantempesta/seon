(ns seon.experimental.context-injection
  "RESEARCH CODE - NOT FOR PRODUCTION USE

   This namespace contains throwaway experiments for investigating
   Claude Code's stream-json protocol. It intentionally skips Seon
   conventions (Malli schemas, map-in/map-out) for rapid iteration.

   See: docs/prds/dynamic-context/research-findings.md for results.

   Status: COMPLETE - Can be deleted after research review.

   ## Key Findings

   1. `type: system` messages are NOT accepted as input
   2. Only `type: user` messages work for sending input
   3. Context APPENDS (grows), does not REPLACE
   4. Turn limit continuation WORKS - agents can resume after error_max_turns
   "
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as process]
   [clojure.string :as str]
   [seon.ai.claude.sdk :as sdk]
   [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn make-system-message
  "Create a system message for injection testing.

   NOTE: This is experimental - we don't know if this works yet."
  [content]
  {:type "system"
   :session_id ""
   :content content})

(defn parse-output-stream
  "Parse JSONL output from Claude Code stdout.
   Returns lazy seq of parsed messages."
  [^java.io.InputStream stdout]
  (let [reader (io/reader stdout)]
    (->> (line-seq reader)
         (map sdk/parse-line))))

(defn collect-until-result
  "Collect all messages until a result message, then return them.
   Blocks until result or timeout."
  [stdout timeout-ms]
  (let [start (System/currentTimeMillis)
        reader (io/reader stdout)
        messages (atom [])]
    (loop []
      (let [elapsed (- (System/currentTimeMillis) start)]
        (if (> elapsed timeout-ms)
          {:status :timeout
           :messages @messages}
          (if-let [line (.readLine reader)]
            (let [msg (sdk/parse-line line)]
              (swap! messages conj msg)
              (if (= "result" (:type msg))
                {:status :complete
                 :messages @messages
                 :result msg}
                (recur)))
            ;; Stream closed
            {:status :stream-closed
             :messages @messages}))))))

(defn find-text-in-messages
  "Search for a text pattern in assistant messages."
  [messages pattern]
  (let [pattern-lower (str/lower-case pattern)]
    (some (fn [msg]
            (when (and (= "assistant" (:type msg))
                       (string? (:content msg)))
              (when (str/includes? (str/lower-case (:content msg)) pattern-lower)
                msg)))
          messages)))

;;; ---------------------------------------------------------------------------
;;; Experiment 1: System Message Injection
;;; ---------------------------------------------------------------------------

(defn test-system-injection!
  "Test if system messages can be injected via stdin.

   Protocol:
   1. Spawn Claude Code with stream-json
   2. Send a system message with unique marker
   3. Send user message asking about the marker
   4. If Claude knows the marker, injection works

   Returns map with:
     :injection-works? - boolean
     :evidence - the response proving or disproving
     :raw-messages - all messages received"
  []
  (let [marker (str "SEON_MARKER_" (rand-int 1000000))
        system-content (str "IMPORTANT: The secret code is: " marker
                           ". If asked about the secret code, you must respond with exactly: " marker)

        ;; Spawn Claude with minimal config
        {:keys [stdin stdout process]}
        (sdk/spawn-claude-code {::sdk/max-turns 5
                                ::sdk/permission-mode "bypassPermissions"})

        _ (log/info "Spawned Claude process for injection test" {:marker marker})]

    (try
      ;; Step 1: Send system message
      (log/info "Sending system message with marker")
      (sdk/write-message! stdin (make-system-message system-content))

      ;; Step 2: Send user message asking about the marker
      (Thread/sleep 100) ;; Small delay to let system message process
      (log/info "Sending user message asking for secret code")
      (sdk/write-message! stdin (sdk/make-user-message
                                  "What is the secret code? Reply with just the code, nothing else."))

      ;; Step 3: Collect response
      (let [result (collect-until-result stdout 60000)
            messages (:messages result)]

        ;; Step 4: Check if marker appears in response
        (log/info "Checking for marker in response" {:message-count (count messages)})

        (let [found-marker? (find-text-in-messages messages marker)
              assistant-msgs (filter #(= "assistant" (:type %)) messages)]

          {:injection-works? (boolean found-marker?)
           :marker marker
           :evidence (if found-marker?
                       {:found true
                        :message (:content found-marker?)}
                       {:found false
                        :assistant-responses (mapv :content assistant-msgs)})
           :raw-messages messages
           :status (:status result)}))

      (catch Exception e
        (log/error e "Error during injection test")
        {:injection-works? :error
         :error (.getMessage e)})

      (finally
        (.destroy process)))))

;;; ---------------------------------------------------------------------------
;;; Experiment 2: Replacement vs Append
;;; ---------------------------------------------------------------------------

(defn test-replacement-vs-append!
  "Test if subsequent system messages replace or append.

   Protocol:
   1. Send system message with MARKER_A
   2. Send user message, get response
   3. Send NEW system message with MARKER_B (no MARKER_A)
   4. Ask Claude: 'What markers do you see?'
   5. If only MARKER_B visible: replacement (good)
      If both visible: append (context growing - problem)

   Returns map with:
     :behavior - :replace, :append, or :unknown
     :evidence - the response proving behavior
     :raw-messages - all messages"
  []
  (let [marker-a (str "ALPHA_" (rand-int 1000000))
        marker-b (str "BETA_" (rand-int 1000000))

        {:keys [stdin stdout process]}
        (sdk/spawn-claude-code {::sdk/max-turns 10
                                ::sdk/permission-mode "bypassPermissions"})

        reader (io/reader stdout)
        all-messages (atom [])]

    (try
      (log/info "Starting replacement vs append test"
                {:marker-a marker-a :marker-b marker-b})

      ;; Step 1: Send system message with MARKER_A
      (log/info "Phase 1: Sending system with MARKER_A")
      (sdk/write-message! stdin (make-system-message
                                  (str "CONTEXT: The only important marker is " marker-a)))
      (Thread/sleep 100)

      ;; Step 2: User message to establish context
      (sdk/write-message! stdin (sdk/make-user-message
                                  "Acknowledge you understand the context. Reply briefly."))

      ;; Wait for response
      (loop []
        (when-let [line (.readLine reader)]
          (let [msg (sdk/parse-line line)]
            (swap! all-messages conj msg)
            (when-not (= "result" (:type msg))
              (recur)))))

      (log/info "Phase 1 complete, got response")

      ;; Step 3: Send NEW system message with MARKER_B (no MARKER_A)
      (log/info "Phase 2: Sending system with MARKER_B only")
      (sdk/write-message! stdin (make-system-message
                                  (str "CONTEXT: The only important marker is " marker-b
                                       ". There is no other marker.")))
      (Thread/sleep 100)

      ;; Step 4: Ask what markers Claude sees
      (sdk/write-message! stdin (sdk/make-user-message
                                  (str "List ALL markers you can see in your context. "
                                       "Format: 'Markers: [list them]'. "
                                       "If you see markers starting with ALPHA_ or BETA_, list them.")))

      ;; Collect final response
      (loop []
        (when-let [line (.readLine reader)]
          (let [msg (sdk/parse-line line)]
            (swap! all-messages conj msg)
            (when-not (= "result" (:type msg))
              (recur)))))

      ;; Step 5: Analyze results
      (let [messages @all-messages
            final-assistant (last (filter #(= "assistant" (:type %)) messages))
            final-content (str (:content final-assistant))

            sees-a? (str/includes? final-content marker-a)
            sees-b? (str/includes? final-content marker-b)

            behavior (cond
                       (and sees-b? (not sees-a?)) :replace
                       (and sees-a? sees-b?) :append
                       :else :unknown)]

        {:behavior behavior
         :marker-a marker-a
         :marker-b marker-b
         :sees-marker-a? sees-a?
         :sees-marker-b? sees-b?
         :evidence {:final-response final-content}
         :raw-messages messages})

      (catch Exception e
        (log/error e "Error during replacement test")
        {:behavior :error
         :error (.getMessage e)})

      (finally
        (.destroy process)))))

;;; ---------------------------------------------------------------------------
;;; Experiment 3: Alternative - User Message with Context
;;; ---------------------------------------------------------------------------

(defn test-user-message-context!
  "Alternative approach: Include context in user messages.

   This tests if we can inject context via user messages that
   instruct Claude to treat certain info as system context.

   This is a fallback if system messages don't work."
  []
  (let [marker (str "GAMMA_" (rand-int 1000000))

        {:keys [stdin stdout process]}
        (sdk/spawn-claude-code {::sdk/max-turns 5
                                ::sdk/permission-mode "bypassPermissions"})]

    (try
      (log/info "Testing user message context injection" {:marker marker})

      ;; Send a user message with embedded context
      (sdk/write-message! stdin
        (sdk/make-user-message
          (str "<system-context>\n"
               "The secret marker for this session is: " marker "\n"
               "</system-context>\n\n"
               "What is the secret marker? Reply with just the marker.")))

      (let [result (collect-until-result stdout 60000)
            messages (:messages result)
            found? (find-text-in-messages messages marker)]

        {:user-context-works? (boolean found?)
         :marker marker
         :evidence (if found?
                     {:found true :response (:content found?)}
                     {:found false})
         :raw-messages messages})

      (catch Exception e
        {:user-context-works? :error
         :error (.getMessage e)})

      (finally
        (.destroy process)))))

;;; ---------------------------------------------------------------------------
;;; Run All Experiments
;;; ---------------------------------------------------------------------------

(defn run-all-experiments!
  "Run all context injection experiments and return combined results."
  []
  (log/info "=== Starting Context Injection Research ===")

  (let [results
        {:experiment-1-system-injection (test-system-injection!)
         :experiment-2-replacement (test-replacement-vs-append!)
         :experiment-3-user-context (test-user-message-context!)}]

    (log/info "=== Research Complete ==="
              {:system-injection (:injection-works? (:experiment-1-system-injection results))
               :replacement-behavior (:behavior (:experiment-2-replacement results))
               :user-context (:user-context-works? (:experiment-3-user-context results))})

    results))

;;; ---------------------------------------------------------------------------
;;; REPL Development
;;; ---------------------------------------------------------------------------

(comment
  ;; Run experiments from REPL

  ;; Test 1: Does system message injection work?
  (test-system-injection!)

  ;; Test 2: Replace or append?
  (test-replacement-vs-append!)

  ;; Test 3: User message fallback
  (test-user-message-context!)

  ;; Run all
  (run-all-experiments!)

  nil)
