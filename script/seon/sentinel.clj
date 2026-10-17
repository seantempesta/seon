(ns seon.sentinel
  "Performance sentinel: the end-to-end probes every restart is compared against.
  Root calls `run` from its JVM REPL: each probe is an MCP eval request measured
  through `seon.profile/measure`, and its reply's request cost is the figure
  recorded. `summary` reduces the probes to one data map; `-main` prints it through
  the value printer. `run` retires the branches of the agents it made before it
  returns, so a run leaves the store's branches as it found them."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [my.agent]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.profile :as profile]
            [seon.render.value :as value]))

(def ^:private probe-namespace
  "The scratch namespace every probe evaluates in, so the `defn` probe defines
  nothing in a namespace the program owns."
  "sentinel.scratch")

(defn- eval-probe
  "One measured MCP eval request as `id` in `mode`: the measure window and the
  reply's whole request cost. The reply's AI face shows only the headline, so the
  whole cost (stages, reads, transactions) is read from the handle the reply
  names (`:seon.repl/cost-result`) by a second request, outside the window."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [id mode code]
  (let [request (fn [form] (#'mcp/eval-clj {:seon.mcp/code form :seon.mcp/agent id
                                                :seon.mcp/namespace probe-namespace
                                                :seon.mcp/cluster "default" :seon.mcp/mode mode}))
        m (profile/measure #(request code))
        [texts failed?] (:seon.profile/value m)
        reply (some #(when (:seon.repl/cost-result %) %) (map edn/read-string texts))
        [cost-texts] (request (str (:seon.repl/cost-result reply)))]
    {:agent id
     :failed? failed?
     :window-ms (:seon.profile/ms m)
     :window-alloc-mb (/ (:seon.profile/alloc-bytes m) 1048576.0)
     :request (some :seon.repl/value (map edn/read-string cost-texts))}))

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

(defn- retire-agents!
  "Retire the branch of each agent in `ids` on the running default cluster
  through `my.agent/retire-branch!`, as root retires a lane's branch; answers
  each retirement's value."
  {:malli/schema [:=> [:cat [:sequential :string]] [:vector :map]]}
  [ids]
  (let [found (#'mcp/instance @runtime/running-instances "default")
        handle (:seon.turn.loop/cluster found)
        connection (:seon.db/connection handle)
        context {:seon.store/store (:seon.store/store found) :seon.db/connection connection
                 :seon.agent/routing (:seon.agent/routing handle)}]
    (mapv (fn [id]
            (my.agent/retire-branch!
             {:seon.store/branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id id]))}
             context))
          ids)))

(defn run
  "Run the probes on the running default cluster.
  Returns the summary, HEAD, the running commit and the probe agent ids, whose
  branches `run` has retired when it returns."
  {:malli/schema [:=> [:cat [:map [:running-commit :string]]] :map]}
  [{:keys [running-commit]}]
  (let [stamp (System/currentTimeMillis)
        warm-id (str "sentinel-warm-" stamp)
        fresh-ids (mapv #(str "sentinel-fresh-" % "-" stamp) (range 3))
        defn-id (str "sentinel-defn-" stamp)
        code "(+ 1 2)"
        agents (into [warm-id defn-id] fresh-ids)]
    (try
      (dotimes [_ 2] (eval-probe warm-id "sci" code))
      (let [warm (mapv (fn [_] (eval-probe warm-id "sci" code)) (range 5))
            fresh (mapv #(eval-probe % "sci" code) fresh-ids)
            definition (eval-probe defn-id "sci" "(defn sentinel-probe-inc [x] (inc x))")]
        {:head (head-commit)
         :running-commit running-commit
         :summary (summary {:warm warm :fresh fresh :definition definition})
         :fresh-failed? (mapv :failed? fresh)
         :agents agents})
      (finally (retire-agents! agents)))))

(defn -main
  "Print the probes through the value printer.
  The argument is default's running commit."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& args]
  (println (value/core-text (run {:running-commit (first args)}) 0))
  nil)
