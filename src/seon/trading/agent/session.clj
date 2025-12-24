(ns seon.trading.agent.session
  "Session template system for trading agents.

   This namespace provides:
   1. Session ID generation (pronounceable CVCV pattern)
   2. REPL input/output capture for training data
   3. Simple thinking/code separation (last paragraph = code)
   4. Self-reinforcing session templates

   Design Philosophy:
   - Sessions are isolated execution environments
   - Everything the agent does becomes training data
   - The format of examples teaches the expected format
   - Simple rules beat complex heuristics

   Parsing Rule (V2 - Simplified):
   The last paragraph (after final `\\n\\n`) is code to execute.
   Everything before is thinking. Each non-empty line in the
   code section executes as a separate REPL input.

   Author: Claude (Session Designer)
   Status: V2 - Simplified parsing"
  (:require [clojure.string :as str]
            [clojure.pprint :as pp])
  (:import [java.security MessageDigest SecureRandom]
           [java.time Instant]))

;;; ===========================================================================
;;; PART 1: NAMESPACE GENERATION
;;; ===========================================================================
;;
;; CVCV pattern: 4 characters like "bako", "meli", "toxa"
;; - Short (4 chars) - fast to type
;; - Pronounceable - easy to remember and communicate
;; - No confusing characters (no 0/O, 1/l)
;; - 9,025 combinations - enough for parallel sessions
;;

(def ^:private consonants "bcdfghjklmnprstvwxz")  ; excluded q, y
(def ^:private vowels "aeiou")

(defn- random-syllable
  "Generate a random consonant-vowel syllable like 'ba', 'ko', 'me'."
  []
  (str (rand-nth consonants) (rand-nth vowels)))

(defn gen-session-id
  "Generate a unique session ID using CVCV pattern.

   Example: (gen-session-id) => \"bako\""
  []
  (str (random-syllable) (random-syllable)))

(defn session-namespace
  "Generate the full session namespace string.

   Example: (session-namespace \"bako\") => \"seon.agent.bako\""
  [session-id]
  (str "seon.agent." session-id))

;;; ===========================================================================
;;; PART 2: OUTPUT TRUNCATION
;;; ===========================================================================

(def ^:dynamic *output-limit*
  "Maximum characters for truncated output in context window."
  2000)

(def ^:dynamic *collection-limit*
  "Maximum items to show in collection output."
  10)

(def ^:dynamic *print-depth*
  "Maximum nesting depth for pretty-print."
  4)

(defn truncate-output
  "Truncate a value for display in context window.
   Returns {:output truncated-string :truncated? bool :full-chars count}"
  [value]
  (let [full-str (binding [*print-length* *collection-limit*
                           *print-level* *print-depth*]
                   (with-out-str (pp/pprint value)))
        char-count (count full-str)
        truncated? (> char-count *output-limit*)
        output (if truncated?
                 (str (subs full-str 0 (min (- *output-limit* 50) char-count))
                      "\n;; ... (" char-count " chars, truncated)")
                 (str/trim full-str))]
    {:output output
     :truncated? truncated?
     :full-chars char-count}))

(defn content-hash
  "Generate a short content-addressed hash for a value.
   Returns string like 'v_a1b2c3d4' (8 hex chars)."
  [value]
  (let [serialized (pr-str value)
        md (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes serialized "UTF-8"))]
    (->> hash-bytes
         (take 4)
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str)
         (str "v_"))))

;;; ===========================================================================
;;; PART 3: SIMPLE THINKING/CODE SEPARATION (V2)
;;; ===========================================================================
;;
;; The V2 parsing rule is dead simple:
;;
;;   Split on the LAST `\n\n` (double newline).
;;   - Everything BEFORE = thinking
;;   - Everything AFTER = code to execute
;;   - Each non-empty line in code section = one REPL input
;;
;; This handles all valid Clojure expressions:
;;   @ctx              - deref
;;   ctx               - symbol
;;   *1                - last result
;;   42                - literal number
;;   :keyword          - keyword
;;   "string"          - string
;;   (foo bar)         - s-expression
;;   {:a 1}            - map
;;   [1 2 3]           - vector
;;
;; The agent writes thinking, then a blank line, then code:
;;
;;   I'll check the context state.
;;
;;   @ctx
;;
;; Multiple expressions work too:
;;
;;   Let me check a few things.
;;
;;   (iv-rank ctx {:ticker "SPY"})
;;   (skew ctx {:ticker "SPY"})
;;   @ctx
;;

(defn split-thinking-code
  "Split agent response into thinking and code sections.

   Uses the simple rule: split on last `\\n\\n`.
   Everything before = thinking, everything after = code.

   Returns {:thinking string-or-nil :code string-or-nil}

   Examples:
     \"I'll check.\\n\\n@ctx\"
     => {:thinking \"I'll check.\" :code \"@ctx\"}

     \"(iv-rank ctx {:ticker \\\"SPY\\\"})\"
     => {:thinking nil :code \"(iv-rank ctx {:ticker \\\"SPY\\\"})\"}

     \"Just some thinking.\"
     => {:thinking \"Just some thinking.\" :code nil}"
  [response]
  (let [trimmed (str/trim response)]
    (if-let [idx (str/last-index-of trimmed "\n\n")]
      ;; Found double newline - split there
      (let [thinking (str/trim (subs trimmed 0 idx))
            code (str/trim (subs trimmed (+ idx 2)))]
        {:thinking (when (seq thinking) thinking)
         :code (when (seq code) code)})
      ;; No double newline - is it code or thinking?
      ;; Heuristic: if it starts with a code character, it's code
      (if (and (seq trimmed)
               (contains? #{\( \[ \{ \@ \* \: \" \' \` \# \^}
                          (first trimmed)))
        {:thinking nil :code trimmed}
        ;; Check if it looks like a symbol/number (starts with letter/digit/-)
        (if (and (seq trimmed)
                 (re-matches #"^[a-zA-Z0-9_\-+].*" trimmed)
                 ;; Single line or short - likely code
                 (not (str/includes? trimmed " "))
                 (<= (count trimmed) 50))
          {:thinking nil :code trimmed}
          ;; Otherwise it's thinking
          {:thinking trimmed :code nil})))))

(defn extract-code-lines
  "Extract individual code expressions from the code section.

   Each non-empty line becomes a separate REPL input.
   Empty lines are skipped.

   Example:
     \"(iv-rank ctx {:ticker \\\"SPY\\\"})\\n(skew ctx {:ticker \\\"SPY\\\"})\\n@ctx\"
     => [\"(iv-rank ctx {:ticker \\\"SPY\\\"})\"
         \"(skew ctx {:ticker \\\"SPY\\\"})\"
         \"@ctx\"]"
  [code-section]
  (when code-section
    (->> (str/split-lines code-section)
         (map str/trim)
         (remove empty?)
         vec)))

(defn parse-agent-response
  "Parse an agent response into thinking and executable code.

   Returns a map with:
     :raw      - The original input (preserved exactly)
     :thinking - Reasoning text before the code (may be nil)
     :code     - Vector of code strings to execute (may be empty)

   The parsing rule is simple:
   - Split on last `\\n\\n`
   - Everything before = thinking
   - Everything after = code (each line is one expression)

   Examples:
     (parse-agent-response \"I'll check.\\n\\n@ctx\")
     => {:raw \"I'll check.\\n\\n@ctx\"
         :thinking \"I'll check.\"
         :code [\"@ctx\"]}

     (parse-agent-response \"Check these.\\n\\n(foo)\\n(bar)\\n@ctx\")
     => {:raw \"Check these.\\n\\n(foo)\\n(bar)\\n@ctx\"
         :thinking \"Check these.\"
         :code [\"(foo)\" \"(bar)\" \"@ctx\"]}"
  [response]
  (let [{:keys [thinking code]} (split-thinking-code response)
        code-lines (extract-code-lines code)]
    {:raw response
     :thinking thinking
     :code (or code-lines [])}))

;; Keep the old function name for backward compatibility
(defn extract-executable-code
  "Extract executable code strings from an agent response.
   Returns a vector of code strings."
  [response]
  (:code (parse-agent-response response)))

(defn extract-thinking
  "Extract the thinking/reasoning portion from an agent response.
   Returns the thinking string or nil."
  [response]
  (:thinking (parse-agent-response response)))

;;; ===========================================================================
;;; PART 4: REPL PAIR RECORDING
;;; ===========================================================================

(defn create-repl-pair
  "Create a REPL input/output pair record.

   Args:
     input-str  - The input expression as a string
     output-val - The result value
     opts       - Optional map with :thinking, :fn-name, :duration-ms, :raw-input

   Returns map ready for storage in session history."
  [input-str output-val & [{:keys [thinking fn-name duration-ms raw-input]}]]
  (let [{:keys [output truncated? full-chars]} (truncate-output output-val)]
    {:input input-str
     :raw-input (or raw-input input-str)  ; Original input before any processing
     :output output
     :val-id (content-hash output-val)
     :timestamp (Instant/now)
     :fn-name (or fn-name
                  (when (string? input-str)
                    (or
                     ;; Try to extract function name from s-expr
                     (second (re-find #"^\s*\(([^\s]+)" input-str))
                     ;; Or the expression itself if it's simple
                     (when (re-matches #"^[@*]?\w+$" (str/trim input-str))
                       (str/trim input-str)))))
     :duration-ms duration-ms
     :thinking thinking
     :truncated? truncated?
     :full-chars full-chars}))

;;; ===========================================================================
;;; PART 5: SESSION STATE AND HISTORY
;;; ===========================================================================

(defn create-session
  "Create a new agent session.

   Args:
     db   - XTDB node for the trading domain
     opts - {:frozen-time inst?, :goal string?, :ticker string?}

   Returns:
     Session atom containing all session state."
  [db & [{:keys [frozen-time goal ticker] :as opts}]]
  (let [session-id (gen-session-id)]
    (atom {:session/id session-id
           :session/namespace (session-namespace session-id)
           :session/created (Instant/now)
           :session/frozen-time (or frozen-time (Instant/now))
           :session/goal goal
           :session/ticker ticker

           ;; Context for agent functions
           :db/node db
           :config/default-lookback 252

           ;; History - the REPL pairs
           :history []

           ;; Full values by content hash (for retrieval)
           :values {}})))

(defn record-interaction!
  "Record a REPL interaction in the session.

   Args:
     session    - Session atom
     input-str  - The input as a string
     output-val - The result value
     opts       - {:thinking string?, :raw-input string?}

   Returns the recorded pair."
  [session input-str output-val & [opts]]
  (let [pair (create-repl-pair input-str output-val opts)]
    (swap! session (fn [s]
                     (-> s
                         (update :history conj pair)
                         (assoc-in [:values (:val-id pair)] output-val))))
    pair))

(defn get-full-value
  "Retrieve full value by val-id from session."
  [session val-id]
  (get-in @session [:values val-id]))

(defn session-history
  "Get the REPL history as formatted text for context window."
  [session]
  (->> (:history @session)
       (map (fn [{:keys [input output thinking]}]
              (str (when thinking
                     (str ";; " (str/replace thinking #"\n" "\n;; ") "\n"))
                   input "\n"
                   ";; => " output)))
       (str/join "\n\n")))

;;; ===========================================================================
;;; PART 6: SESSION TEMPLATE
;;; ===========================================================================
;;
;; The template teaches agents the expected format through examples.
;; Key insight: The format of examples should match what we expect
;; the agent to produce.
;;

(def template-instructions
  "SEON Trading Agent Session

You are a quantitative trading analyst with access to options market data.
Your workspace is the `ctx` atom containing database connection and session state.

COMMANDS:
  (overview ctx)                          ; Market overview for watched tickers
  (analyze ctx {:ticker \"SPY\"})           ; Full analysis with recommendation
  (iv-rank ctx {:ticker \"SPY\"})           ; IV percentile rank (0-1)
  (skew ctx {:ticker \"SPY\"})              ; Put-call skew index
  (options-chain ctx {:ticker \"SPY\" :dte 30})  ; Options by days to expiration

INSPECT:
  @ctx      ; Dereference ctx to see current session state
  *1        ; Last REPL result
  ctx       ; The ctx atom itself

PATTERN: All functions take ctx and an options map with namespaced keywords.

TIME: Everything is relative - use :dte for expiration, :lookback for history.
  :dte 7        - Options expiring in ~7 days
  :lookback 252 - 1 trading year of history

FORMAT:
Write your thinking first, then leave a blank line, then the code.
Each line after the blank line will execute as a separate REPL input.

Example 1 - Single expression:

I'll check the current IV rank for SPY to see if options are expensive.

(iv-rank ctx {:ticker \"SPY\"})

Example 2 - Multiple expressions:

Let me check several metrics at once.

(iv-rank ctx {:ticker \"SPY\"})
(skew ctx {:ticker \"SPY\"})
@ctx

Example 3 - Quick inspection:

@ctx

Results will appear after each command. Analyze and continue.")

(defn generate-template
  "Generate the full session template with instructions.

   Args:
     session - Session atom (must have :db/node)
     opts    - {:show-examples? true}

   Returns:
     String containing the full template ready to show the agent."
  [session & [{:keys [show-examples?]
               :or {show-examples? true}}]]
  (let [s @session
        lines [(str "Session: " (:session/namespace s))
               (str "Started: " (:session/created s))
               (when-let [ft (:session/frozen-time s)]
                 (str "Market Date: " ft))
               ""
               (str (apply str (repeat 67 "=")))
               ""
               template-instructions]]
    (str/join "\n" (filter some? lines))))

(defn format-repl-pair-for-display
  "Format a REPL pair for display in the session.
   This is what the agent sees after executing code."
  [{:keys [input output thinking duration-ms]}]
  (str (when thinking
         (str ";; " (str/replace thinking #"\n" "\n;; ") "\n"))
       input "\n"
       ";; => " output
       (when duration-ms
         (str "  ; " duration-ms "ms"))))

;;; ===========================================================================
;;; PART 7: EXECUTION HELPERS
;;; ===========================================================================

(defmacro exec!
  "Execute a form and record it in the session.

   Usage:
     (exec! session (iv-rank ctx {:ticker \"SPY\"}))
     (exec! session \"Checking IV\" (iv-rank ctx {:ticker \"SPY\"}))

   Returns the result value."
  ([session expr]
   `(exec! ~session nil ~expr))
  ([session thinking expr]
   (let [input-str (pr-str expr)]
     `(let [start# (System/currentTimeMillis)
            result# ~expr
            end# (System/currentTimeMillis)]
        (record-interaction! ~session ~input-str result#
                             {:thinking ~thinking
                              :duration-ms (- end# start#)})
        result#))))

(defn process-agent-response!
  "Process an agent's response, extracting and executing code.

   Args:
     session  - Session atom
     response - Raw text response from agent
     eval-fn  - Function to evaluate code strings (default: read-string + eval)

   Returns:
     Map with:
       :raw      - Original response
       :thinking - Extracted thinking
       :results  - Vector of {:input code-str :output result :error? bool}"
  [session response & [eval-fn]]
  (let [eval-fn (or eval-fn #(eval (read-string %)))
        {:keys [raw thinking code]} (parse-agent-response response)]

    ;; Record the raw input in session metadata
    (swap! session update :raw-inputs (fnil conj []) raw)

    ;; Process each code line
    (let [results
          (mapv (fn [code-str]
                  (let [start (System/currentTimeMillis)]
                    (try
                      (let [result (eval-fn code-str)
                            end (System/currentTimeMillis)]
                        (record-interaction! session code-str result
                                             {:thinking thinking
                                              :raw-input raw
                                              :duration-ms (- end start)})
                        {:input code-str
                         :output result
                         :error? false})
                      (catch Exception e
                        {:input code-str
                         :output (ex-message e)
                         :error? true
                         :exception e}))))
                code)]

      {:raw raw
       :thinking thinking
       :results results})))

;;; ===========================================================================
;;; PART 8: TRAINING DATA EXPORT
;;; ===========================================================================

(defn session->training-example
  "Convert a session to a training example in chat format.

   Returns map with :messages vector suitable for JSONL export."
  [session]
  (let [s @session
        system-prompt (str "You are a quantitative trading analyst with access to "
                           "a Clojure REPL for analyzing options data. "
                           (when-let [t (:session/frozen-time s)]
                             (str "Today's date is " t ". "))
                           "Use the available functions to analyze the market and "
                           "provide trading recommendations.")

        user-prompt (or (:session/goal s)
                        (str "Analyze " (or (:session/ticker s) "the market")
                             " for trading opportunities."))

        ;; Convert history to assistant content with new format
        assistant-content (->> (:history s)
                               (map (fn [{:keys [input output thinking]}]
                                      (str (when thinking
                                             (str thinking "\n\n"))
                                           input "\n\n"
                                           ";; => " output)))
                               (str/join "\n\n---\n\n"))]

    {:messages [{:role "system" :content system-prompt}
                {:role "user" :content user-prompt}
                {:role "assistant" :content assistant-content}]
     :metadata {:session-id (:session/id s)
                :frozen-time (str (:session/frozen-time s))
                :ticker (:session/ticker s)
                :raw-inputs (:raw-inputs s)}}))

;;; ===========================================================================
;;; COMMENT BLOCK - EXAMPLES AND TESTING
;;; ===========================================================================

(comment
  ;; --- Namespace Generation ---

  (repeatedly 10 gen-session-id)
  ;; => ("bako" "meli" "toxa" "firu" "weno" "saji" "kepo" "dalu" "hivo" "nube")

  ;; --- V2 Parsing (Simple Rule) ---

  ;; Single expression with thinking
  (parse-agent-response
   "I'll check the IV rank.

(iv-rank ctx {:ticker \"SPY\"})")
  ;; => {:raw "I'll check...", :thinking "I'll check the IV rank.", :code ["(iv-rank ctx {:ticker \"SPY\"})"]}

  ;; Multiple expressions
  (parse-agent-response
   "Let me check several things.

(iv-rank ctx {:ticker \"SPY\"})
(skew ctx {:ticker \"SPY\"})
@ctx")
  ;; => {:raw "...", :thinking "Let me check several things.", :code ["(iv-rank...)" "(skew...)" "@ctx"]}

  ;; Just code, no thinking
  (parse-agent-response "@ctx")
  ;; => {:raw "@ctx", :thinking nil, :code ["@ctx"]}

  (parse-agent-response "(iv-rank ctx {:ticker \"SPY\"})")
  ;; => {:raw "(iv-rank...)", :thinking nil, :code ["(iv-rank ctx {:ticker \"SPY\"})"]}

  ;; Non-paren expressions
  (parse-agent-response "ctx")
  ;; => {:raw "ctx", :thinking nil, :code ["ctx"]}

  (parse-agent-response "*1")
  ;; => {:raw "*1", :thinking nil, :code ["*1"]}

  (parse-agent-response ":keyword")
  ;; => {:raw ":keyword", :thinking nil, :code [":keyword"]}

  (parse-agent-response "42")
  ;; => {:raw "42", :thinking nil, :code ["42"]}

  ;; Just thinking (prose paragraph)
  (parse-agent-response "This is just some thinking about the market.")
  ;; => {:raw "This is just...", :thinking "This is just some thinking about the market.", :code []}

  ;; --- Extract Functions ---

  (extract-code-lines "(foo)\n(bar)\n\n@ctx")
  ;; => ["(foo)" "(bar)" "@ctx"]

  (extract-executable-code "I'll check.\n\n(foo)\n(bar)")
  ;; => ["(foo)" "(bar)"]

  ;; --- Session Usage ---

  (def test-session (create-session nil {:goal "Test the system"
                                         :ticker "SPY"}))

  @test-session
  ;; => {:session/id "bako", :session/namespace "seon.agent.bako", ...}

  (record-interaction! test-session
                       "(iv-rank ctx {:ticker \"SPY\"})"
                       {:iv-rank/value 0.73 :iv-rank/label :elevated}
                       {:thinking "Checking IV rank"
                        :raw-input "I'll check IV.\n\n(iv-rank ctx {:ticker \"SPY\"})"})

  (session-history test-session)
  ;; => ";; Checking IV rank\n(iv-rank ctx ...)\\n;; => {:iv-rank/value 0.73 ...}"

  ;; --- Template Generation ---

  (println (generate-template test-session))

  nil)
