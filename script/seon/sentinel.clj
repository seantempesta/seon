(ns seon.sentinel
  "Performance sentinel: the end-to-end probes every restart is compared against.
  Root calls `run` from its JVM REPL: each probe is an MCP eval request measured
  through `seon.profile/measure`, and its reply's request cost is the figure
  recorded. `summary` reduces the probes to one data map; `-main` prints it through
  the value printer. Probe branches are retired by the caller, never here."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.render.value :as value]))

(def ^:private probe-namespace "seon.test")

(defn- eval-probe
  "One measured MCP eval request as `id` in `mode`: the measure window and the reply's request cost."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [id mode code]
  (let [m (profile/measure
           #(#'mcp/eval-clj {:seon.mcp/code code :seon.mcp/agent id
                             :seon.mcp/namespace probe-namespace
                             :seon.mcp/cluster "default" :seon.mcp/mode mode}))
        [texts failed?] (:seon.profile/value m)]
    {:agent id
     :failed? failed?
     :window-ms (:seon.profile/ms m)
     :window-alloc-mb (/ (:seon.profile/alloc-bytes m) 1048576.0)
     :request (some :seon.repl/request (map edn/read-string texts))}))

(defn- median
  "The lower middle of `xs` after sorting."
  {:malli/schema [:=> [:cat [:sequential number?]] number?]}
  [xs]
  (let [sorted (vec (sort xs))]
    (nth sorted (quot (dec (count sorted)) 2))))

(defn- figures
  "The recorded figures of one probe's request cost."
  {:malli/schema [:=> [:cat :map] :map]}
  [{request :request}]
  {:ms (:seon.cost/ms request)
   :alloc-mb (:seon.cost/alloc-mb request)
   :calls (:seon.cost/calls request)
   :reads (:seon.cost/reads request)
   :transactions (:seon.cost/transactions request)
   :stages (:seon.cost/stages request)})

(defn summary
  "Baseline figures of the probes: warm, fresh and defn.
  The warm figures are those of the median-ms sample."
  {:malli/schema [:=> [:cat [:map [:warm [:sequential :map]] [:fresh [:sequential :map]] [:definition :map]]] :map]}
  [{:keys [warm fresh definition]}]
  (let [warm-ms (map #(get-in % [:request :seon.cost/ms]) warm)
        median-ms (median warm-ms)
        median-sample (first (filter #(= median-ms (get-in % [:request :seon.cost/ms])) warm))]
    {:warm (assoc (figures median-sample)
                  :median-ms median-ms
                  :samples-ms (vec warm-ms)
                  :settle-ms (vec (map #(get-in % [:request :seon.cost/stages :settle]) warm))
                  :window-alloc-mb-median (median (map :window-alloc-mb warm)))
     :fresh (mapv figures fresh)
     :defn (figures definition)}))

(defn- head-commit
  "The short commit id of the checkout's HEAD."
  {:malli/schema [:=> [:cat] :string]}
  []
  (str/trim (:out (shell/sh "git" "rev-parse" "--short" "HEAD"))))

(defn run
  "Run the probes on the running default cluster.
  Returns the summary, HEAD, the running commit and the probe agent ids; the
  caller retires those branches with my.agent/retire-branch!."
  {:malli/schema [:=> [:cat [:map [:running-commit :string]]] :map]}
  [{:keys [running-commit]}]
  (let [stamp (System/currentTimeMillis)
        warm-id "sentinel-warm"
        fresh-ids (mapv #(str "sentinel-fresh-" % "-" stamp) (range 3))
        defn-id "sentinel-defn"
        code "(+ 1 2)"
        agents (into [warm-id defn-id] fresh-ids)]
    (dotimes [_ 2] (eval-probe warm-id "sci" code))
    (let [warm (mapv (fn [_] (eval-probe warm-id "sci" code)) (range 5))
          fresh (mapv #(eval-probe % "sci" code) fresh-ids)
          definition (eval-probe defn-id "sci" "(defn sentinel-probe-inc [x] (inc x))")]
      {:head (head-commit)
       :running-commit running-commit
       :summary (summary {:warm warm :fresh fresh :definition definition})
       :fresh-failed? (mapv :failed? fresh)
       :agents agents})))

(defn -main
  "Print the probes through the value printer.
  The argument is default's running commit."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& args]
  (println (value/core-text (run {:running-commit (first args)}) 0))
  nil)
