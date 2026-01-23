(ns seon.render.example
  "Example usage of seon.render multi-format rendering.

   Demonstrates:
   - Schema registration with seon.schema
   - Renderer registration with inheritance
   - Typed values and multi-format rendering
   - for-ai helper for AI agents

   Run the demo functions at the REPL to see rendering in action."
  (:require [seon.render :as render]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::ticker
                  [:string {:min 1 :max 10
                            :description "Stock ticker symbol"}])

(schema/register! ::quantity
                  [:int {:min 0
                         :description "Number of shares"}])

(schema/register! ::price
                  [:double {:min 0
                            :description "Price per share"}])

(schema/register! ::position
                  [:map {:description "A stock position"}
                   [::ticker ::ticker]
                   [::quantity ::quantity]
                   [::price ::price]])

;;; ---------------------------------------------------------------------------
;;; Renderer Registration
;;; ---------------------------------------------------------------------------

(render/register-renderer! ::position
  {:ai (fn [{::keys [ticker quantity price]}]
         (str ticker " x" quantity " @ $" (format "%.2f" price)))

   :html (fn [{::keys [ticker quantity price]}]
           [:div.position-card
            {:class "p-2 border border-border-subtle rounded"}
            [:span.ticker.font-bold ticker]
            [:span.mx-2 "×"]
            [:span.quantity quantity]
            [:span.mx-2 "@"]
            [:span.price (str "$" (format "%.2f" price))]])

   :raw (fn [v] v)

   :human (fn [{::keys [ticker quantity price]}]
            (str "Position\n"
                 "  Ticker:   " ticker "\n"
                 "  Quantity: " quantity "\n"
                 "  Price:    $" (format "%.2f" price) "\n"
                 "  Value:    $" (format "%.2f" (* quantity price))))})

;;; ---------------------------------------------------------------------------
;;; Factory Functions
;;; ---------------------------------------------------------------------------

(defn position
  "Create a typed Position value.

   Arguments:
     ticker   - Stock ticker symbol
     quantity - Number of shares
     price    - Price per share

   Returns:
     Position map with :seon/schema metadata attached.

   Example:
     (position \"AAPL\" 100 150.0)"
  [ticker quantity price]
  (render/typed ::position
                {::ticker ticker
                 ::quantity quantity
                 ::price price}))

;;; ---------------------------------------------------------------------------
;;; Demo Functions
;;; ---------------------------------------------------------------------------

(defn demo-single-position
  "Demonstrate rendering a single position in all formats.

   Returns:
     Map of format->rendered output."
  []
  (let [pos (position "AAPL" 100 150.0)]
    {:ai (render/render pos :ai)
     :html (render/render pos :html)
     :raw (render/render pos :raw)
     :human (render/render pos :human)}))

(defn demo-portfolio
  "Demonstrate rendering a portfolio with multiple positions.

   Shows how for-ai handles nested structures."
  []
  (let [positions [(position "AAPL" 100 150.0)
                   (position "GOOGL" 50 140.0)
                   (position "MSFT" 75 380.0)]
        portfolio {:positions positions
                   :total-value (reduce + (map (fn [p]
                                                 (* (::quantity p) (::price p)))
                                               positions))}]
    {:positions-ai (mapv #(render/render % :ai) positions)
     :portfolio-for-ai (render/for-ai portfolio)
     :positions-html (mapv #(render/render % :html) positions)}))

(defn demo-for-ai
  "Show how for-ai produces concise output for AI agents.

   Compare EDN output vs for-ai output."
  []
  (let [pos (position "AAPL" 100 150.0)
        data {:position pos
              :signals [:buy :hold]
              :confidence 0.85}]
    {:edn-output (pr-str data)
     :for-ai-output (render/for-ai data)}))

;;; ---------------------------------------------------------------------------
;;; REPL Examples
;;; ---------------------------------------------------------------------------

(comment
  ;; Create positions
  (def aapl (position "AAPL" 100 150.0))
  (def googl (position "GOOGL" 50 140.0))

  ;; Check metadata
  (meta aapl)  ; => {:seon/schema :seon.render.example/position}
  (render/schema-of aapl)  ; => :seon.render.example/position

  ;; Render in different formats
  (render/render aapl :ai)
  ;; => "AAPL x100 @ $150.00"

  (render/render aapl :html)
  ;; => [:div.position-card {...} ...]

  (render/render aapl :human)
  ;; => "Position\n  Ticker:   AAPL\n  ..."

  (render/render aapl :raw)
  ;; => {:seon.render.example/ticker "AAPL" ...}

  ;; Run demos
  (demo-single-position)
  (demo-portfolio)
  (demo-for-ai)

  ;; for-ai on complex nested structure
  (render/for-ai {:portfolio [aapl googl]
                  :metrics {:total-value 22000.0
                            :day-change 150.0}
                  :tags #{:tech :growth}})
  ;; => "{:portfolio [AAPL x100 @ $150.00, GOOGL x50 @ $140.00], ...}"

  nil)
