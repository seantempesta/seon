(ns seon.trading.agent.template
  "Agent session template.

  This namespace generates the agent's initial context by executing
  real commands and capturing output. The agent can also read this
  file to understand how the system works.

  To generate a session:
    (generate-session ctx)

  To customize:
    (generate-session ctx {:show-overview true
                           :example-ticker \"SPY\"
                           :show-functions true})

  NOTE: This template references functions in seon.trading.agent.functions
  which are not yet implemented. See PRD for implementation status."
  (:require [seon.trading.agent.session :as session]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Instructions (static text)
;;; ---------------------------------------------------------------------------

(def instructions
  "SEON Trading Agent

You have access to options market data through the ctx atom.

ctx holds your session state - inspect it anytime with @ctx

All functions follow the pattern:
  (function-name ctx {:key value})

Functions return namespaced maps. Use (raw result) for full data.

Available functions:
  (overview ctx)                           ; Market overview
  (analyze ctx {:ticker \"SPY\"})            ; Full analysis
  (iv-rank ctx {:ticker \"SPY\"})            ; IV percentile (0-1)
  (skew ctx {:ticker \"SPY\"})               ; Put-call skew
  (term-structure ctx {:ticker \"SPY\"})     ; IV term structure
  (options-chain ctx {:ticker \"SPY\" :dte 30}) ; Options by DTE

Time is always relative:
  :dte      - Days to expiration (7, 30, :nearest-weekly)
  :lookback - Days of history (252=1yr, 60=3mo, 30=1mo)

Start with (overview ctx) to see current market conditions.")

;;; ---------------------------------------------------------------------------
;;; Example Commands (placeholders - will call real functions when implemented)
;;; ---------------------------------------------------------------------------

;; TODO: These functions will be implemented in seon.trading.agent.functions
;; For now, they show placeholder output

(defn- stub-result [type-name data]
  (merge {:seon.type/name type-name} data))

(defn example-overview
  "Show market overview.
   Currently returns placeholder data until functions.clj is implemented."
  [ctx]
  (println "=> (overview ctx)")
  (println)
  (println "MARKET OVERVIEW (placeholder)")
  (println)
  (println "TICKER  PRICE    IV-RANK           SKEW    SIGNAL")
  (println "SPY     543.21   0.73 [▓▓▓▓▓▓▓░░░] 0.048   short-vol")
  (println "QQQ     468.45   0.65 [▓▓▓▓▓▓░░░░] 0.052   short-vol")
  (println)
  (stub-result :market-overview
               {:overview/tickers [{:ticker "SPY" :iv-rank 0.73}
                                   {:ticker "QQQ" :iv-rank 0.65}]}))

(defn example-iv-rank
  "Show IV rank for a ticker.
   Currently returns placeholder data until functions.clj is implemented."
  [ctx ticker]
  (println (str "=> (iv-rank ctx {:ticker \"" ticker "\"})"))
  (println)
  (println (str "IV RANK: " ticker))
  (println)
  (println "Value:    0.73 (elevated)")
  (println "Lookback: 252 days")
  (println)
  (stub-result :iv-rank
               {:iv-rank/ticker ticker
                :iv-rank/value 0.73
                :iv-rank/label :elevated}))

(defn example-analyze
  "Show full analysis for a ticker.
   Currently returns placeholder data until functions.clj is implemented."
  [ctx ticker]
  (println (str "=> (analyze ctx {:ticker \"" ticker "\"})"))
  (println)
  (println (str "ANALYSIS: " ticker))
  (println)
  (println "RECOMMENDATION: short-vol (confidence: medium)")
  (println)
  (println "SIGNALS:")
  (println "  IV Rank:        0.73 [▓▓▓▓▓▓▓░░░] elevated")
  (println "  Skew:           0.048              normal")
  (println)
  (stub-result :ticker-analysis
               {:analysis/ticker ticker
                :analysis/recommendation :short-vol
                :analysis/confidence :medium}))

(defn example-options-chain
  "Show options chain.
   Currently returns placeholder data until functions.clj is implemented."
  [ctx ticker dte]
  (println (str "=> (options-chain ctx {:ticker \"" ticker "\" :dte " dte "})"))
  (println)
  (println (str "OPTIONS CHAIN: " ticker "  (" dte " DTE)"))
  (println)
  (println "CALLS                          STRIKE         PUTS")
  (println "Bid    Ask    IV    Delta               Delta   IV    Bid    Ask")
  (println "5.45   5.55   0.30  0.58       540      -0.42  0.28   3.80   3.90")
  (println)
  (stub-result :options-chain
               {:chain/ticker ticker
                :chain/dte dte
                :chain/strikes []}))

;;; ---------------------------------------------------------------------------
;;; Session Generation
;;; ---------------------------------------------------------------------------

(def default-config
  {:show-instructions true
   :show-overview true
   :show-example-analysis true
   :example-ticker "SPY"
   :example-dte 30})

(defn generate-session
  "Generate the agent's initial context.

  Executes real commands and captures output to show the agent
  how the system works through examples.

  Options:
    :show-instructions    - Include usage instructions (default true)
    :show-overview        - Run (overview ctx) (default true)
    :show-example-analysis - Run example analysis (default true)
    :example-ticker       - Ticker for examples (default \"SPY\")
    :example-dte          - DTE for options example (default 30)"
  ([ctx]
   (generate-session ctx {}))
  ([ctx opts]
   (let [config (merge default-config opts)]

     (println "═══════════════════════════════════════════════════════════════")
     (println)

     ;; Instructions
     (when (:show-instructions config)
       (println instructions)
       (println)
       (println "───────────────────────────────────────────────────────────────")
       (println))

     ;; Live examples
     (when (:show-overview config)
       (println "CURRENT MARKET:")
       (println)
       (example-overview ctx)
       (println)
       (println "───────────────────────────────────────────────────────────────")
       (println))

     (when (:show-example-analysis config)
       (println "EXAMPLE ANALYSIS:")
       (println)
       (example-analyze ctx (:example-ticker config))
       (println))

     (println "═══════════════════════════════════════════════════════════════")
     (println)
     (println "Ready. Start with (overview ctx) or (analyze ctx {:ticker ...})")

     ;; Return ctx for chaining
     ctx)))

;;; ---------------------------------------------------------------------------
;;; Custom Session Variants
;;; ---------------------------------------------------------------------------

(defn minimal-session
  "Minimal session - just instructions, no examples."
  [ctx]
  (generate-session ctx {:show-overview false
                         :show-example-analysis false}))

(defn full-session
  "Full session with all examples."
  [ctx ticker]
  (generate-session ctx {:example-ticker ticker
                         :show-overview true
                         :show-example-analysis true}))

(defn options-focused-session
  "Session focused on options chain analysis."
  [ctx ticker dte]
  (println "═══════════════════════════════════════════════════════════════")
  (println)
  (println instructions)
  (println)
  (println "───────────────────────────────────────────────────────────────")
  (println)
  (println "OPTIONS CHAIN EXAMPLE:")
  (println)
  (example-options-chain ctx ticker dte)
  (println)
  (println "═══════════════════════════════════════════════════════════════")
  ctx)

;;; ---------------------------------------------------------------------------
;;; Inspect This File
;;; ---------------------------------------------------------------------------

(defn show-template-source
  "Print the source of this template file.

  The agent can call this to understand how sessions are generated."
  []
  ;; Note: resource path may vary depending on how classpath is configured
  (try
    (println (slurp (clojure.java.io/resource "seon/trading/agent/template.clj")))
    (catch Exception _
      (println "Template source not available via classpath.")
      (println "Read src/seon/trading/agent/template.clj directly."))))

(comment
  ;; Generate a session
  (generate-session ctx)

  ;; Minimal session
  (minimal-session ctx)

  ;; Full session for specific ticker
  (full-session ctx "AAPL")

  ;; Options-focused session
  (options-focused-session ctx "SPY" 30)

  ;; Agent can inspect how this works
  (show-template-source))
