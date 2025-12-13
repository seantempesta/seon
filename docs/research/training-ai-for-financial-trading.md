

# **Neuro-Symbolic Alpha Discovery: Reverse-Engineering Quantitative Strategies via Hindsight Experience Replay and Oracle Policy Distillation in Bitemporal Environments**

## **1\. Executive Summary**

The prevailing paradigm in quantitative finance has shifted rapidly from heuristic, model-based approaches to high-dimensional, model-free Deep Reinforcement Learning (DRL). While DRL agents—such as those utilizing Proximal Policy Optimization (PPO) or Deep Q-Networks (DQN)—have demonstrated the capacity to extract non-linear signals from market microstructure, they suffer from a critical deficiency: opacity. In a regulated and high-stakes environment, a "black box" trading agent that cannot articulate the causality of its decisions represents an unquantifiable risk. This report proposes and details a novel, comprehensive framework to bridge this epistemological gap by integrating **Large Language Models (LLMs)** with **Symbolic Reasoning** and **Bitemporal Data Architectures**.

Specifically, we leverage the reasoning capabilities of models like **DeepSeek R1**, which utilize Chain-of-Thought (CoT) processing, to function as the "Student" in a rigorous **Oracle Policy Distillation (OPD)** setup. The system is grounded in a **Hindsight Experience Replay (HER)** methodology where an "Oracle Teacher" utilizes future data (T+n) to determine mathematically optimal trading outcomes (the "ground truth" of the market). The Student, constrained to a frozen T0 environment via the **XTDB** bitemporal database, must learn to query the environment using **Clojure s-expressions** to discover the specific information states that justify the Oracle's optimal actions.

This architecture fundamentally reverses the standard feature engineering workflow. Instead of pre-feeding the model with selected factors, the model essentially "invents" its own feature extraction logic by formulating executable Datalog queries. The resulting "Reasoning Traces"—sequences of database queries and logical deductions—are then distilled using **Genetic Programming** and **Symbolic Regression** into compact, human-readable mathematical laws (alpha signals). This report provides an exhaustive technical analysis of this pipeline, covering data ingestion from **ThetaData** (which provides free historical options data with pre-calculated Greeks), bitemporal schema design, the mathematical formulation of the Oracle's loss functions, and detailed case studies on **Volatility Arbitrage**, **Gamma Scalping**, and **Dispersion Trading**. We demonstrate that this neuro-symbolic approach not only improves model interpretability but also enhances sample efficiency by grounding the stochastic policy in the deterministic logic of market mechanics.1

---

## **2\. Introduction: The Interpretability Crisis in Algorithmic Trading**

### **2.1 The Limitations of Model-Free Reinforcement Learning**

Algorithmic trading has historically evolved through two distinct phases. The first, "Model-Based" trading, relied on analytical frameworks like the Black-Scholes-Merton (BSM) model or Almgren-Chriss execution logic. These models are highly interpretable but rigid; they rely on idealized assumptions (e.g., geometric Brownian motion, constant volatility) that fail to capture the "stylized facts" of real markets, such as volatility clustering, heavy tails, and microstructure noise.4

The second phase, "Model-Free" DRL, abandons these assumptions in favor of learning policies directly from data. Agents observe the state $S\_t$ (usually a tensor of limit order book snapshots) and output an action $a\_t$ to maximize a reward function $R$. While effective, these models often learn spurious correlations or exploit transient arbitrage opportunities that vanish under different regimes. Furthermore, they lack the capacity for semantic explanation. A DRL agent might learn to sell an option when the "Vanna" (sensitivity of Vega to spot price) is high, but it cannot explain *why* Vanna matters in that specific context, nor can it express the concept of Vanna unless explicitly engineered to do so.1

### **2.2 The Rise of Reasoning Models: DeepSeek R1 and Chain-of-Thought**

The advent of "Reasoning Models," exemplified by **DeepSeek R1**, introduces a third paradigm. Unlike standard LLMs optimized for next-token prediction, reasoning models are trained via Reinforcement Learning from Human Feedback (RLHF) and other alignment techniques to generate "Chain-of-Thought" (CoT) sequences—intermediate reasoning steps that precede the final answer. This mimics human cognitive processes, allowing the model to decompose complex problems, verify assumptions, and backtrack when necessary.3

In our proposed framework, we harness this CoT capability by restricting the model's interaction with the world to a formal language: **Clojure s-expressions**. By forcing the model to "think" in code, we achieve two critical objectives:

1. **Operational Validity**: The model's reasoning is executable. It does not hallucinate a "high volatility" state; it must *query* the database for the volatility and receive a numeric response.  
2. **Auditability**: The sequence of s-expressions serves as a precise, immutable log of the model's epistemological journey. We can see exactly what data the model sought, in what order, and how it combined those data points to reach a conclusion.8

### **2.3 The Oracle-Student Paradigm with Hindsight Experience Replay**

To train such a model, we cannot rely on human demonstration, as the "optimal" query sequence for finding a trading signal is often unknown or highly context-dependent. Instead, we employ **Hindsight Experience Replay (HER)**. HER was originally developed for robotics, allowing agents to learn from failures by pretending that the unintended result was actually the goal.10

We adapt HER for finance by creating an **Oracle Teacher** that has access to the future (time $T \> t\_0$). The Oracle observes the future price trajectory and calculates the optimal strategy (e.g., "The asset price stayed within a range, so the optimal strategy was to sell a strangle"). This generates a labeled training example: *State* ($S\_{t\_0}$), *Optimal Action* ($a^\*$), and *Goal* (Maximize Sharpe Ratio).

The Student, which only sees $S\_{t\_0}$, is tasked with finding the "reason" for $a^\*$. It must query the $t\_0$ database to find features (e.g., high implied volatility relative to historical realized volatility) that would predict the success of $a^\*$. Through **Oracle Policy Distillation**, the Student minimizes the divergence between its logic and the Oracle's proven outcomes, effectively "reverse-engineering" the future.2

---

## **3\. Data Infrastructure: The Bitemporal Substrate**

The foundational requirement for this research is a data environment that supports "time travel." We must be able to present the Student with the database *exactly* as it appeared at any historical moment $T\_0$, without any leakage of future corrections or restatements. This requires a **Bitemporal Database**.

### **3.1 Technology Selection: XTDB and the Clojure Ecosystem**

We utilize **XTDB** (formerly Crux), an open-source bitemporal database written in Clojure. XTDB is uniquely suited for this task due to its native support for two time axes:

1. **Valid Time (valid-time)**: The time at which a fact is true in the real world (e.g., the timestamp of a trade).  
2. **Transaction Time (tx-time)**: The time at which the fact was recorded in the database (e.g., the timestamp of the ingestion).

This distinction is crucial in finance. For instance, an earnings report might be released at 16:00 (Valid Time), but due to latency, it enters our system at 16:00:05 (Transaction Time). If we want to simulate a trading agent operating at 16:00:01, it *must not* see that report, even though the Valid Time is 16:00. XTDB handles this natively via its as-of query semantics.12

### **3.2 Data Ingestion: ThetaData Options API**

The raw material for our training set is historical options data from **ThetaData**, a cost-effective provider that offers **free historical EOD (end-of-day) options data with pre-calculated Greeks**. This is a significant advantage over raw OPRA feeds, which require expensive subscriptions and manual Greeks calculation.

**Why ThetaData over raw OPRA/Databento:**
- **FREE tier**: 1 year of historical EOD data at no cost
- **Pre-calculated Greeks**: IV, delta, gamma, vega, theta, rho included
- **Perfect for daily screening**: Not HFT, so EOD granularity is sufficient
- **Unlimited symbols**: No per-symbol costs

#### **3.2.1 Data Schema and Normalization**

Options data is high-dimensional and sparse. A single underlying asset (e.g., SPY) may have thousands of active option contracts across different strikes and expirations. We normalize this data into a schema that supports efficient Datalog querying.

**Table 1: XTDB Document Schema for Options Microstructure**

| Field | Type | Description | Source |
| :---- | :---- | :---- | :---- |
| :xt/id | String | Deterministic ID (OCC symbol + timestamp) | Generated |
| :asset/ticker | String | Underlying symbol (e.g., "AAPL") | ThetaData |
| :option/id | String | OCC Symbol (e.g., "AAPL230616C00150000") | ThetaData |
| :option/strike | Double | Strike price | ThetaData |
| :option/type | Keyword | :call or :put | ThetaData |
| :option/expiry | Instant | Expiration timestamp | ThetaData |
| :quote/bid | Double | Best Bid Price | ThetaData |
| :quote/ask | Double | Best Ask Price | ThetaData |
| :quote/iv | Double | Implied Volatility | ThetaData (pre-calculated) |
| :greeks/delta | Double | Sensitivity to spot price | ThetaData (pre-calculated) |
| :greeks/gamma | Double | Sensitivity of Delta to spot | ThetaData (pre-calculated) |
| :greeks/vega | Double | Sensitivity to Volatility | ThetaData (pre-calculated) |
| :greeks/theta | Double | Time decay | ThetaData (pre-calculated) |
| :greeks/rho | Double | Sensitivity to interest rate | ThetaData (pre-calculated) |
| :market/volume | Long | Contracts traded in interval | ThetaData |

The ingestion pipeline fetches data via the ThetaData Python API, saves to Parquet format, and transacts into XTDB using deterministic IDs for deduplication on re-ingestion.

#### **3.2.2 The Greeks and Surface Construction**

Unlike raw price feeds that require manual Greeks calculation, **ThetaData provides pre-calculated Greeks** including first, second, and third-order sensitivities. This eliminates the need for Black-Scholes inversion and reduces both development time and computational overhead.

We explicitly model the surface parameters (e.g., SVI parameterization: $a, b, \\rho, m, \\sigma$) and store them as entities. This allows the Student to query "Surface Skew" directly, rather than having to query individual options and calculate it manually. This abstraction layer is critical for enabling higher-level reasoning.5

### **3.3 The "Frozen" T0 Environment**

For the Student LLM, the environment is strictly defined by the valid-time parameter. When the training loop requests a decision at time $T\_0$, we instantiate an XTDB database value:

Clojure

(def db-t0 (xt/db node {::xt/valid-time \#inst "2023-11-28T10:00:00Z"  
                        ::xt/tx-time    \#inst "2023-11-28T10:00:00Z"}))

This object db-t0 is immutable. No matter what queries the Student runs, it can never access information from 10:00:01. This guarantees that any signal discovered by the Student is a valid *ex-ante* signal, eliminating the look-ahead bias that plagues many financial ML models.21

---

## **4\. Methodology: Oracle Policy Distillation & Hindsight Experience Replay**

The core of our training methodology is the interaction between the **Oracle Teacher** and the **Student Agent**. This section details the mathematical and algorithmic structure of this interaction.

### **4.1 Hindsight Experience Replay (HER) in Finance**

In standard HER, an agent attempts to reach a goal $g$, fails, and ends up in state $s\_{final}$. HER stores this trajectory as a success for a *new* goal $g' \= s\_{final}$. In our financial context, the "goal" is profit, but the path to profit is stochastic. We modify HER to use the Oracle's hindsight to label the *intent* of the market.10

Let $\\tau \= (s\_t, a\_t, r\_t, s\_{t+1},...)$ be a trajectory.  
The Oracle observes $\\tau$ from $t=0$ to $t=N$.  
It calculates the Realized Value $V^\*(\\tau)$ of various canonical strategies (e.g., Long Volatility, Short Volatility, Delta Neutral).  
The strategy $S\_{opt}$ that maximizes $V^\*$ becomes the label for the state $s\_0$.  
**Example:**

* **Time:** $T\_0$  
* **Future (T0 to T30):** The asset price drops by 20%.  
* **Oracle Calculation:**  
  * Strategy A (Long Call): Loss.  
  * Strategy B (Short Put): Massive Loss.  
  * Strategy C (Long Put): Massive Profit.  
  * Strategy D (Iron Condor): Loss.  
* **Optimal Label:** Action: Buy Put.  
* **Student Task:** "Find the query sequence on $S\_{T\_0}$ that justifies Buy Put."

### **4.2 Oracle Policy Distillation (OPD) Loss Function**

We aim to distill the Oracle's perfect policy $\\pi^\*$ into the Student's parameterized policy $\\pi\_\\theta$. The loss function $\\mathcal{L}$ is a composite of three terms, as defined in the literature on OPD for order execution.2

$$ \\mathcal{L}(\\theta) \= \\lambda\_1 \\mathcal{L}*{policy} \+ \\lambda\_2 \\mathcal{L}*{value} \+ \\lambda\_3 \\mathcal{L}*{distill} \+ \\lambda\_4 \\mathcal{L}*{aux} $$

Where:

1. Policy Loss ($\\mathcal{L}\_{policy}$): The standard policy gradient loss (e.g., PPO) maximizing the expected reward (profit).  
   $$ \\mathcal{L}\_{policy} \= \-\\mathbb{E}\_t \[\\min(r\_t(\\theta)\\hat{A}\_t, \\text{clip}(r\_t(\\theta), 1-\\epsilon, 1+\\epsilon)\\hat{A}\_t)\] $$  
2. Value Loss ($\\mathcal{L}\_{value}$): Minimizes the error between the Student's value estimation and the realized returns.

   $$\\mathcal{L}\_{value} \= (V\_\\theta(s\_t) \- V\_{target})^2$$  
3. Distillation Loss ($\\mathcal{L}\_{distill}$): The Kullback-Leibler (KL) divergence between the Student's action distribution and the Oracle's optimal action distribution. This forces the Student to mimic the Oracle's decisions.1  
   $$ \\mathcal{L}{distill} \= D{KL}(\\pi\_{oracle}(\\cdot | s\_t, \\text{future}) |

| \\pi\_\\theta(\\cdot | s\_t)) $$  
4\. Auxiliary Symbolic Loss ($\\mathcal{L}\_{aux}$): A novel term we introduce to penalize the complexity of the generated Clojure queries. This encourages parsimony—finding the simplest explanation for the trade.

$$\\mathcal{L}\_{aux} \= \\text{length}(\\text{query\\\_tree}) \+ \\text{depth}(\\text{query\\\_tree})$$

### **4.3 The "Reasoning Trace" Generation**

The Student (DeepSeek R1) does not output a raw action vector. It outputs a **Reasoning Trace** wrapped in Clojure code.

**Protocol:**

1. **Input:** A prompt containing the current Ticker, Time, and available Datalog schemas.  
2. **Step 1 (Exploration):** The LLM generates a query to fetch the "state" (e.g., (get-iv "AAPL")).  
3. **Step 2 (Execution):** The system executes this query against the bitemporal DB and returns the result to the LLM context window.  
4. **Step 3 (Reflection):** The LLM analyzes the result. If insufficient, it generates a refined query (e.g., (get-historical-iv "AAPL" :window 30)).  
5. **Step 4 (Conclusion):** The LLM outputs a final decision and a summary of the rationale.

This iterative loop effectively creates a "dynamic feature selection" mechanism. The model learns which questions to ask to approximate the Oracle's knowledge.25

---

## **5\. The Cognitive Interface: Clojure & S-Expressions**

The choice of **Clojure** as the interaction language is strategic. Unlike Python or SQL, Clojure's **homoiconicity** (code as data) makes it an ideal target for machine generation and manipulation. S-expressions are abstract syntax trees (ASTs) serialized as text, meaning the LLM is effectively generating the AST directly.8

### **5.1 Datalog as a Query Logic**

XTDB uses **EDN-based Datalog**, a declarative logic language. Datalog allows for recursive rules and graph traversals, which are essential for financial reasoning. For example, finding "all stocks in the same sector as AAPL with correlated volatility" is a graph query, not a simple table scan.

**Example Datalog Query (The "What"):**

Clojure

;; Find all options with Strike \< Spot (ITM) and IV \< 20%  
(xt/q (xt/db node)  
      '{:find \[?ticker?strike?iv\]  
        :where \[\[?e :asset/ticker?ticker\]  
                \[?e :quote/spot?spot\]  
                \[?o :option/underlying?e\]  
                \[?o :option/strike?strike\]  
                \[?q :quote/option?o\]  
                \[?q :quote/iv?iv\]  
                \[(\<?strike?spot)\]   ;; Strike \< Spot  
                \[(\<?iv 0.20)\]\]})     ;; IV \< 20%

### **5.2 The DSL for Financial Reasoning (The "Why")**

To facilitate higher-level reasoning, we wrap raw Datalog in a Clojure DSL. This DSL exposes semantic primitives that the LLM can compose.

**DSL Primitives:**

* (iv-rank ticker window): Returns the percentile rank of current IV relative to history.  
* (term-structure-slope ticker): Returns the slope between near-term and far-term IV.  
* (put-call-ratio ticker volume-or-oi): Returns the sentiment ratio.  
* (skew-index ticker): Returns the difference between 25-delta Put IV and 25-delta Call IV.

This abstraction allows the reasoning trace to read like a financial argument:

Clojure

(let  
  (if (and (\< rank 0.1) (\> slope 0.05))  
      :buy-calendar-spread  
      :pass))

The homoiconic nature of this code means we can parse it, mutate it (via Genetic Programming), and execute it without complex transpilation.27

---

## **6\. Deep Research Strategy I: Volatility Arbitrage**

**Volatility Arbitrage** is the practice of exploiting the difference between the **Implied Volatility (IV)** (market expectation) and the **Realized Volatility (RV)** (actual future movement). It is the purest form of "trading the view on volatility".29

### **6.1 Theoretical Basis**

The Black-Scholes price $C(S, K, T, \\sigma\_{imp})$ assumes $\\sigma\_{imp}$ is constant. However, if the trader hedges the option continuously (delta hedging), the PnL of the position depends on the actual volatility $\\sigma\_{real}$ experienced by the stock.

$$PnL \\approx \\frac{1}{2} S^2 \\Gamma (\\sigma\_{real}^2 \- \\sigma\_{imp}^2) dt$$

If $\\sigma\_{real} \> \\sigma\_{imp}$, a long gamma position (Long Straddle) is profitable. The Oracle knows $\\sigma\_{real}$ because it sees the future price path. The Student only sees $\\sigma\_{imp}$ and T0 microstructure.

### **6.2 The Oracle Logic (T+n)**

The Oracle calculates the annualized standard deviation of log returns from $T\_0$ to $T\_{expiry}$.

1. **Calculate RV**: $\\sigma\_{real} \= \\sqrt{\\frac{252}{N} \\sum\_{i=1}^N (\\ln \\frac{S\_i}{S\_{i-1}})^2}$  
2. **Compare to IV**: $\\Delta\_{vol} \= \\sigma\_{real} \- \\sigma\_{imp}$  
3. **Action**:  
   * If $\\Delta\_{vol} \> \\tau$ (Threshold), Label \= :long-straddle.  
   * If $\\Delta\_{vol} \< \-\\tau$, Label \= :short-straddle.

### **6.3 The Student's Reasoning Trace (T0)**

The Student must find predictors of high future volatility. Common predictors include earnings announcements, low liquidity, or technical breakouts.

**Trace Example:**

* **Student**: "Oracle says BUY STRADDLE. Why? Is IV low?"  
  * Query: (xt/q... :where \[\[?e :quote/iv?iv\]\]...) \-\> IV \= 12%.  
* **Student**: "12% is low. Is it historically low?"  
  * Query: (calc-iv-percentile "XYZ" :lookback "6m") \-\> Rank \= 0.02.  
* **Student**: "Extremely low. Is there a catalyst?"  
  * Query: (get-upcoming-events "XYZ") \-\> {:type :earnings, :days 3}.  
* **Conclusion**: "Buy Straddle because IV is at 2nd percentile ahead of earnings."

Reverse-Engineered Logic:  
The system distills this trace into a rule:

$$\\text{Signal} \= \\mathbb{I}(\\text{IV}\_{\\text{rank}} \< 0.05) \\land \\mathbb{I}(\\text{DaysToEvent} \< 5)$$

This confirms the classic "Pre-Earnings Volatility Run-up" strategy, discovered autonomously by the agent.30

---

## **7\. Deep Research Strategy II: Gamma Scalping**

**Gamma Scalping** is a dynamic strategy involving a long options position (usually a straddle) and the continuous buying/selling of the underlying stock to remain delta-neutral. The profit comes from "scalping" the stock moves: buying when the delta drops (stock falls) and selling when the delta rises (stock rises).32

### **7.1 Theoretical Basis**

Gamma ($\\Gamma$) measures the rate of change of Delta ($\\Delta$).

$$\\Gamma \= \\frac{\\partial \\Delta}{\\partial S} \= \\frac{\\partial^2 C}{\\partial S^2}$$

In a long gamma position, as $S$ rises, $\\Delta$ increases. To stay neutral, the trader sells shares (selling high). As $S$ falls, $\\Delta$ decreases. The trader buys shares (buying low). This mechanical "buy low, sell high" generates positive cash flow. The cost is Theta ($\\Theta$), the daily time decay.

$$\\text{Net Profit} \= \\text{Scalping PnL} \- \\Theta$$

### **7.2 The Oracle Logic (T+n)**

The Oracle simulates the scalping process tick-by-tick.

1. **Simulation**: For every minute $t$, calculate $\\Delta\_t$. If $|\\Delta\_t| \> \\text{threshold}$, rebalance.  
2. **Accumulate Cash**: Sum the cash flows from stock trades.  
3. **Subtract Premium**: Subtract the decay of the option price.  
4. **Action**: If Net Profit \> 0, Label \= :initiate-gamma-scalp.

### **7.3 The Student's Reasoning Trace (T0)**

To justify Gamma Scalping, the Student needs to find conditions where the stock is "choppy" (high mean reversion) and hedging costs (spreads) are low.

**Trace Example:**

* **Student**: "Oracle says SCALP. This requires high realized movement but range-bound price. Is the stock pinned?"  
  * Query: (get-open-interest-distribution "XYZ") \-\> {:strike 100 :oi 50000} (Massive OI at ATM strike).  
* **Student**: "High OI at 100 suggests pinning. Are spreads tight enough to scalp cheaply?"  
  * Query: (get-bid-ask-spread "XYZ") \-\> 0.01%.  
* **Student**: "Is Gamma cheap?"  
  * Query: (get-gamma-rent "XYZ") \-\> Gamma/Theta ratio is high.  
* **Conclusion**: "Initiate scalp: Stock pinned by OI, spreads tight, Gamma is underpriced relative to Theta."

Symbolic Insight:  
The system extracts a complex interaction:  
$$ \\alpha\_{scalp} \= \\frac{\\text{OpenInterest}\_{\\text{ATM}}}{\\text{Volume}} \\times \\frac{1}{\\text{Spread}} \\times \\frac{\\Gamma}{|\\Theta|} $$  
This formulation highlights that Gamma Scalping is most effective when liquidity is high (tight spreads) and "pinning" forces (OI) create predictable mean reversion.34

---

## **8\. Deep Research Strategy III: Dispersion Trading**

**Dispersion Trading** is an advanced strategy that exploits the difference between **Implied Correlation** and **Realized Correlation**. It typically involves selling options on an index (short index volatility) and buying options on the index components (long single-stock volatility).36

### **8.1 Theoretical Basis: The Correlation Trade**

The variance of an index $\\sigma\_I^2$ is related to the variance of its components $\\sigma\_i^2$ and their pairwise correlations $\\rho\_{ij}$:

$$\\sigma\_I^2 \= \\sum\_i w\_i^2 \\sigma\_i^2 \+ \\sum\_i \\sum\_{j \\neq i} w\_i w\_j \\sigma\_i \\sigma\_j \\rho\_{ij}$$

If correlations $\\rho\_{ij}$ are high (approaching 1), the index volatility is high. If correlations are low (approaching 0), the component volatilities cancel each other out, and index volatility is low.  
Traders sell the index (short correlation) and buy components (long correlation) when they believe implied correlation is too high.38

### **8.2 The Oracle Logic (T+n)**

1. Compute Realized Correlation: The Oracle calculates the actual correlation matrix of the top 50 index components over the future window.

   $$\\rho\_{real} \= \\text{Avg}(\\text{Corr}(R\_i, R\_j))$$  
2. Compute Implied Correlation: Based on T0 option prices.  
   $$ \\rho\_{imp} \\approx \\frac{\\sigma\_{imp, I}^2 \- \\sum w\_i^2 \\sigma\_{imp, i}^2}{\\sum\_{i \\neq j} w\_i w\_j \\sigma\_{imp, i} \\sigma\_{imp, j}} $$  
3. **Action**:  
   * If $\\rho\_{imp} \\gg \\rho\_{real}$, Label \= :short-index-long-components (Short Correlation).  
   * If $\\rho\_{imp} \\ll \\rho\_{real}$, Label \= :long-index-short-components (Long Correlation).

### **8.3 The Student's Reasoning Trace (T0)**

The Student must detect that the market is overpricing correlation.

**Trace Example:**

* **Student**: "Oracle is Short Correlation. Is Implied Correlation high?"  
  * Query: (calc-implied-correlation "SPX" :components 50\) \-\> 0.65 (Historically High).  
* **Student**: "Why would correlation break down? Is there sector rotation?"  
  * Query: (get-sector-correlations "XLK" "XLE") \-\> \-0.4 (Tech and Energy are decoupling).  
* **Student**: "Are earnings dispersed?"  
  * Query: (earnings-dispersion "SPX") \-\> High variance in earnings dates.  
* **Conclusion**: "Execute Dispersion Trade. Implied Correlation (0.65) is elevated, but sector rotation data suggests decoupling."

Symbolic Insight:

$$\\alpha\_{disp} \= \\text{ImpliedCorr} \- \\text{RollCorr}(\\text{SectorETFs}, 30d)$$

This simple yet powerful rule uses Sector ETF correlation as a proxy for future single-stock correlation.40

---

## **9\. Genetic Programming & Symbolic Regression: The Distillation Engine**

Once the Student generates thousands of successful reasoning traces (sequences of s-expressions), we need to generalize them into robust formulas. We employ **Genetic Programming (GP)** to perform **Symbolic Regression**.43

### **9.1 The Algorithm**

We treat the Student's queries as the "population" for the GP algorithm.

1. **Initialization**: Parse the successful Clojure queries into expression trees.  
2. **Crossover**: Swap sub-trees between two high-performing queries (e.g., replace the volatility check in a Gamma Scalping rule with a liquidity check from a Vol Arb rule).  
3. **Mutation**: Randomly alter a parameter (e.g., change (mean iv) to (max iv)).  
4. Fitness Function: Evaluate the simplified rule against the Oracle's labels on a validation set.  
   $$ \\text{Fitness} \= \\text{Correlation}(\\text{RuleOutput}, \\text{OracleValue}) \- \\lambda \\times \\text{Complexity} $$  
5. **Selection**: Keep the fittest rules.45

### **9.2 From S-Expressions to Python/C++**

The final output of this stage is not a neural network weight matrix, but a compact equation.

* **Input**: (if (\> (iv-rank) 0.8) 1 0\)  
* Output (LaTeX): $Signal \= \\mathbb{1}(Rank(IV) \> 0.8)$  
  This formula can be implemented in C++ for low-latency execution, completely removing the LLM from the live trading loop. The LLM is the researcher, not the trader.44

---

## **10\. System Architecture & Engineering**

### **10.1 High-Performance Infrastructure**

Training this system requires significant computational resources.

* **Compute**: NVIDIA H100 clusters for the DeepSeek R1 fine-tuning and inference.  
* **Memory**: High-RAM instances (e.g., AWS r6i.24xlarge) to hold the XTDB in-memory indices for fast bitemporal queries.  
* **Storage**: NVMe SSDs for the transaction log. XTDB separates the *Transaction Log* (Kafka) from the *Document Store* (RocksDB/LMDB), allowing independent scaling.48

### **10.2 Integration Layer: Python-Clojure Bridge**

While the LLM training happens in Python (PyTorch), the environment is Clojure. We use **libpython-clj** to bridge the two runtimes.

* **Python Side**: The RL loop sends an action (string of Clojure code).  
* **Bridge**: libpython-clj executes the string in the running Clojure JVM instance.  
* **Clojure Side**: XTDB executes the query and returns a EDN map.  
* **Bridge**: Converts EDN to a Python Dict/Tensor for the LLM.28

---

## **11\. Ethical Considerations and Risks**

### **11.1 Overfitting and Data Mining Bias**

The "Oracle" approach carries the risk of overfitting to historical anomalies. The Oracle knows *exactly* what happened, so the Student might learn to memorize specific dates rather than general principles (e.g., "Buy puts on Oct 19, 1987").

* **Mitigation**: We apply **Regularization via Symbolic Complexity** ($\\mathcal{L}\_{aux}$). Rules that rely on date \== 1987-10-19 have high complexity compared to iv \> 99th\_percentile. The GP algorithm filters out "memorization" rules in favor of "mechanistic" rules.50

### **11.2 Interpretability as a Safety Mechanism**

The primary ethical advantage of this system is safety. Standard black-box models can blow up due to "edge cases" they have never seen. Because our system outputs symbolic rules, human risk managers can audit every single strategy before deployment. If the system proposes a formula that divides by zero or takes the log of a negative number, it is caught immediately during the symbolic regression phase.6

---

## **12\. Conclusion**

This research presents a paradigm shift in financial machine learning. By moving from **Black Box DRL** to **Glass Box Neuro-Symbolic AI**, we unlock the ability to discover strategies that are not only profitable but provable. The integration of **DeepSeek R1's Chain-of-Thought**, **XTDB's Bitemporality**, and **Oracle Policy Distillation** creates a robust pipeline for generating "Alpha with an Explanation."

The Student agent, guided by the omniscient Oracle, learns to navigate the bitemporal history of the options market, asking the right questions to uncover hidden arbitrage opportunities in Volatility, Gamma, and Dispersion. Finally, Symbolic Regression crystallizes these insights into mathematical laws, bridging the gap between the stochastic intuition of AI and the rigorous determinism of quantitative finance. This framework ensures that as AI becomes more central to global markets, it remains a tool of transparent discovery rather than obscure risk.

---

Citations  
14 OPRA & Market Data.  
1 RL Interpretability.  
10 Hindsight Experience Replay.  
1 Oracle Policy Distillation & Loss Functions.  
12 XTDB & Bitemporality.  
8 Clojure & S-expressions.  
29 Volatility Arbitrage.  
32 Gamma Scalping.  
36 Dispersion Trading.  
44 Symbolic Regression.  
3 DeepSeek R1 & Reasoning Models.

#### **Works cited**

1. Universal Trading for Order Execution with Oracle Policy Distillation \- The Association for the Advancement of Artificial Intelligence, accessed November 28, 2025, [https://cdn.aaai.org/ojs/16083/16083-13-19577-1-2-20210518.pdf](https://cdn.aaai.org/ojs/16083/16083-13-19577-1-2-20210518.pdf)  
2. \[2103.10860\] Universal Trading for Order Execution with Oracle Policy Distillation \- arXiv, accessed November 28, 2025, [https://arxiv.org/abs/2103.10860](https://arxiv.org/abs/2103.10860)  
3. DeepSeek R1 Explained: Chain of Thought, Reinforcement Learning, and Model Distillation | by Tahir | Medium, accessed November 28, 2025, [https://medium.com/@tahirbalarabe2/deepseek-r1-explained-chain-of-thought-reinforcement-learning-and-model-distillation-0eb165d928c9](https://medium.com/@tahirbalarabe2/deepseek-r1-explained-chain-of-thought-reinforcement-learning-and-model-distillation-0eb165d928c9)  
4. Universal Trading for Order Execution with Oracle Policy Distillation \- Sequence Machine Learning, accessed November 28, 2025, [https://seqml.github.io/opd/opd\_aaai21\_supplement.pdf](https://seqml.github.io/opd/opd_aaai21_supplement.pdf)  
5. Louis-Pierre Arguin \- A First Course in Stochastic Calculus-American Mathematical Society (2021) | PDF | Probability Distribution | Random Variable \- Scribd, accessed November 28, 2025, [https://www.scribd.com/document/580885621/Louis-Pierre-Arguin-A-First-Course-in-Stochastic-Calculus-American-Mathematical-Society-2021](https://www.scribd.com/document/580885621/Louis-Pierre-Arguin-A-First-Course-in-Stochastic-Calculus-American-Mathematical-Society-2021)  
6. Explainable AI Part 7: SHAP — Financial Decision-Making \- DZone, accessed November 28, 2025, [https://dzone.com/articles/explainable-ai-shap-financial-decision-making](https://dzone.com/articles/explainable-ai-shap-financial-decision-making)  
7. DeepSeek's reasoning AI shows power of small models, efficiently trained | IBM, accessed November 28, 2025, [https://www.ibm.com/think/news/deepseek-r1-ai](https://www.ibm.com/think/news/deepseek-r1-ai)  
8. clojure-finance \- GitHub, accessed November 28, 2025, [https://github.com/clojure-finance](https://github.com/clojure-finance)  
9. Distributed Systems \- Jorge Israel Peña, accessed November 28, 2025, [https://jip.dev/notes/distributed-systems/](https://jip.dev/notes/distributed-systems/)  
10. What is Hindsight Experience Replay | AI Basics \- Ai Online Course, accessed November 28, 2025, [https://www.aionlinecourse.com/ai-basics/hindsight-experience-replay](https://www.aionlinecourse.com/ai-basics/hindsight-experience-replay)  
11. Universal Trading for Order Execution with Oracle Policy Distillation \- ResearchGate, accessed November 28, 2025, [https://www.researchgate.net/publication/363399797\_Universal\_Trading\_for\_Order\_Execution\_with\_Oracle\_Policy\_Distillation](https://www.researchgate.net/publication/363399797_Universal_Trading_for_Order_Execution_with_Oracle_Policy_Distillation)  
12. SQL Quickstart \- XTDB, accessed November 28, 2025, [https://docs.xtdb.com/quickstart/sql-overview.html](https://docs.xtdb.com/quickstart/sql-overview.html)  
13. SQL Queries \- XTDB Docs, accessed November 28, 2025, [https://v1-docs.xtdb.com/language-reference/1.24.3/sql-queries/](https://v1-docs.xtdb.com/language-reference/1.24.3/sql-queries/)  
14. Options Analytics: Greeks and Implied Volatility \- CME Group, accessed November 28, 2025, [https://www.cmegroup.com/market-data/greeks-and-implied-volatility-data.html](https://www.cmegroup.com/market-data/greeks-and-implied-volatility-data.html)  
15. Options Price Reporting Authority \- OPRA \- LSEG, accessed November 28, 2025, [https://www.lseg.com/en/data-analytics/financial-data/pricing-and-market-data/options-data/options-price-reporting-authority](https://www.lseg.com/en/data-analytics/financial-data/pricing-and-market-data/options-data/options-price-reporting-authority)  
16. Historical Data & Analytics \- Stocks, Options, & Futures \- SpiderRock, accessed November 28, 2025, [https://spiderrock.net/data/historical-data-analytics/](https://spiderrock.net/data/historical-data-analytics/)  
17. Understanding QuantLib Architecture: A Visual Guide to European Option Pricing \- Medium, accessed November 28, 2025, [https://medium.com/towardsdev/understanding-quantlib-architecture-a-visual-guide-to-european-option-pricing-894522d58b9e](https://medium.com/towardsdev/understanding-quantlib-architecture-a-visual-guide-to-european-option-pricing-894522d58b9e)  
18. صفحة رقم ‎22‎ | نصوص مميزة \- TradingView — تتبع جميع الأسواق, accessed November 28, 2025, [https://ar.tradingview.com/scripts/editors-picks/page-22/](https://ar.tradingview.com/scripts/editors-picks/page-22/)  
19. black-scholes-equation · GitHub Topics, accessed November 28, 2025, [https://github.com/topics/black-scholes-equation](https://github.com/topics/black-scholes-equation)  
20. Quantitative Volatility Trading \- Ceremade, accessed November 28, 2025, [https://www.ceremade.dauphine.fr/\~brugiere/files/MASEF/Conferences/QFSlidesOption.pdf](https://www.ceremade.dauphine.fr/~brugiere/files/MASEF/Conferences/QFSlidesOption.pdf)  
21. Datalog Queries \- XTDB Docs, accessed November 28, 2025, [https://v1-docs.xtdb.com/language-reference/1.24.3/datalog-queries/](https://v1-docs.xtdb.com/language-reference/1.24.3/datalog-queries/)  
22. Launching XTDB v2 — time-travel SQL database to simplify compliance, accessed November 28, 2025, [https://xtdb.com/blog/launching-xtdb-v2](https://xtdb.com/blog/launching-xtdb-v2)  
23. The Wisdom of Hindsight Makes Language Models Better Instruction Followers \- arXiv, accessed November 28, 2025, [https://arxiv.org/pdf/2302.05206](https://arxiv.org/pdf/2302.05206)  
24. Universal Trading for Order Execution with Oracle Policy Distillation \- IDEAS/RePEc, accessed November 28, 2025, [https://ideas.repec.org/p/arx/papers/2103.10860.html](https://ideas.repec.org/p/arx/papers/2103.10860.html)  
25. Daily Papers \- Hugging Face, accessed November 28, 2025, [https://huggingface.co/papers?q=answer%20explainability](https://huggingface.co/papers?q=answer+explainability)  
26. Usable XAI: 10 Strategies Towards Exploiting Explainability in the LLM Era \- arXiv, accessed November 28, 2025, [https://arxiv.org/pdf/2403.08946](https://arxiv.org/pdf/2403.08946)  
27. Java Interop \- Clojure, accessed November 28, 2025, [https://clojure.org/reference/java\_interop](https://clojure.org/reference/java_interop)  
28. ajoberstar/cljj: Clojure to Java Interop APIs \- GitHub, accessed November 28, 2025, [https://github.com/ajoberstar/cljj](https://github.com/ajoberstar/cljj)  
29. What is Volatility Arbitrage? \- CQF, accessed November 28, 2025, [https://www.cqf.com/blog/quant-finance-101/what-is-volatility-arbitrage](https://www.cqf.com/blog/quant-finance-101/what-is-volatility-arbitrage)  
30. Volatility Arbitrage Strategy with a C++ Example | by DeVillar | Renata Villar \- Medium, accessed November 28, 2025, [https://medium.com/@devillar/volatility-arbitrage-strategy-with-a-c-example-0ace5c87ab1d](https://medium.com/@devillar/volatility-arbitrage-strategy-with-a-c-example-0ace5c87ab1d)  
31. Three models of market impact \- Baruch MFE Program, accessed November 28, 2025, [https://mfe.baruch.cuny.edu/wp-content/uploads/2012/09/Chicago2016OptimalExecution.pdf](https://mfe.baruch.cuny.edu/wp-content/uploads/2012/09/Chicago2016OptimalExecution.pdf)  
32. Gamma Scalping: Building an Options Strategy with Python and Alpaca's Trading API, accessed November 28, 2025, [https://alpaca.markets/learn/gamma-scalping](https://alpaca.markets/learn/gamma-scalping)  
33. Gamma Scalping: How to Use in Trading, Strategies, Formula, Examples and More, accessed November 28, 2025, [https://blog.quantinsti.com/gamma-scalping/](https://blog.quantinsti.com/gamma-scalping/)  
34. Gamma Scalping: A Primer \- Charles Schwab, accessed November 28, 2025, [https://www.schwab.com/learn/story/gamma-scalping-primer](https://www.schwab.com/learn/story/gamma-scalping-primer)  
35. Mastering Gamma Scalping: An Advanced Options Strategy for Algo Traders \- Reddit, accessed November 28, 2025, [https://www.reddit.com/r/alpacamarkets/comments/1iu27is/mastering\_gamma\_scalping\_an\_advanced\_options/](https://www.reddit.com/r/alpacamarkets/comments/1iu27is/mastering_gamma_scalping_an_advanced_options/)  
36. Dispersion Trading in Practice: The “Dirty” Version \- Interactive Brokers, accessed November 28, 2025, [https://www.interactivebrokers.com/campus/ibkr-quant-news/dispersion-trading-in-practice-the-dirty-version/](https://www.interactivebrokers.com/campus/ibkr-quant-news/dispersion-trading-in-practice-the-dirty-version/)  
37. What is Dispersion trading? \- CQF, accessed November 28, 2025, [https://www.cqf.com/blog/quant-finance-101/what-is-dispersion-trading](https://www.cqf.com/blog/quant-finance-101/what-is-dispersion-trading)  
38. Dispersion Trading For The Uninitiated | by by Kris Abdelmessih \- Medium, accessed November 28, 2025, [https://medium.com/@moontower/dispersion-trading-for-the-uninitiated-f96d9f6d6c7a](https://medium.com/@moontower/dispersion-trading-for-the-uninitiated-f96d9f6d6c7a)  
39. Dispersion Trading Using Options \[EPAT PROJECT\] \- QuantInsti Blog, accessed November 28, 2025, [https://blog.quantinsti.com/dispersion-trading-using-options/](https://blog.quantinsti.com/dispersion-trading-using-options/)  
40. Almost Everything You Wanted to Know About Dispersion Trading (But Were Afraid to Ask) : r/quant \- Reddit, accessed November 28, 2025, [https://www.reddit.com/r/quant/comments/1nmxdef/almost\_everything\_you\_wanted\_to\_know\_about/](https://www.reddit.com/r/quant/comments/1nmxdef/almost_everything_you_wanted_to_know_about/)  
41. Quant Lab Equity Dispersion | Investcorp-Tages, accessed November 28, 2025, [https://www.investcorptages.com/wp-content/uploads/2020/08/Quant-Lab-Equity-Dispersion-January-2020.pdf](https://www.investcorptages.com/wp-content/uploads/2020/08/Quant-Lab-Equity-Dispersion-January-2020.pdf)  
42. Dispersion Trading Based on the Explanatory Power of S\&P 500 Stock Returns \- MDPI, accessed November 28, 2025, [https://www.mdpi.com/2227-7390/8/9/1627](https://www.mdpi.com/2227-7390/8/9/1627)  
43. SYMBOLIC REGRESSION USING GENETIC PROGRAMMING LEVERAGING NEURAL INFORMATION PROCESSING \- Lund University Publications, accessed November 28, 2025, [https://lup.lub.lu.se/student-papers/record/9046100/file/9046102.pdf](https://lup.lub.lu.se/student-papers/record/9046100/file/9046102.pdf)  
44. Finding the Formula in the Data. Symbolic Regression for Model… | by Ayo Akinkugbe | Python in Plain English, accessed November 28, 2025, [https://python.plainenglish.io/finding-the-formula-in-the-data-18f2bd8335cc](https://python.plainenglish.io/finding-the-formula-in-the-data-18f2bd8335cc)  
45. Population Dynamics in Genetic Programming for Dynamic Symbolic Regression \- MDPI, accessed November 28, 2025, [https://www.mdpi.com/2076-3417/14/2/596](https://www.mdpi.com/2076-3417/14/2/596)  
46. EasyChair Preprint Building Cross-Sectional Trading Strategies via Geometric Semantic Genetic Programming, accessed November 28, 2025, [https://easychair.org/publications/preprint/FjSF/open](https://easychair.org/publications/preprint/FjSF/open)  
47. Article and book summaries by Vincent Zoonekynd (2025-07), accessed November 28, 2025, [http://zoonek.free.fr/Ecrits/articles.pdf](http://zoonek.free.fr/Ecrits/articles.pdf)  
48. Time in Finance \- XTDB, accessed November 28, 2025, [https://docs.xtdb.com/tutorials/financial-usecase/time-in-finance.html](https://docs.xtdb.com/tutorials/financial-usecase/time-in-finance.html)  
49. AWS Marketplace: XTDB Self-Managed with Grid Dynamics (Open Source) \- Amazon.com, accessed November 28, 2025, [https://aws.amazon.com/marketplace/pp/prodview-iokxqykgxqe7i](https://aws.amazon.com/marketplace/pp/prodview-iokxqykgxqe7i)  
50. SHAP and LIME: An Evaluation of Discriminative Power in Credit Risk \- Frontiers, accessed November 28, 2025, [https://www.frontiersin.org/journals/artificial-intelligence/articles/10.3389/frai.2021.752558/full](https://www.frontiersin.org/journals/artificial-intelligence/articles/10.3389/frai.2021.752558/full)  
51. Explaining the Black Box: From Beta Coefficients to SHAP Values | by ODSC, accessed November 28, 2025, [https://odsc.medium.com/explaining-the-black-box-from-beta-coefficients-to-shap-values-9bbea817bfb0](https://odsc.medium.com/explaining-the-black-box-from-beta-coefficients-to-shap-values-9bbea817bfb0)  
52. Physics Reports Discovering causal relations and equations from data \- electronic library \-, accessed November 28, 2025, [https://elib.dlr.de/201063/1/2023\_Camps-Valls\_Discovering\_Journal-Version.pdf](https://elib.dlr.de/201063/1/2023_Camps-Valls_Discovering_Journal-Version.pdf)