(ns spike.invoke
  "Open-question spike (PRD tile-isolation): can an AGENT tile fn's SOURCE be
   evaluated INTO an SCI ctx so its body is INTERPRETED (and thus
   interrupt-protected), while core fns + js interop are resolvable as HOST
   vars, the live input map is passed per-render, and a per-render wall-clock
   deadline aborts a loop in the tile body?

   This proves the exact incantation seon.render.sci/invoke-bounded will use —
   WITHOUT touching the live pod. The mechanics mirror production:
     - ONE shared ctx built once (sci/init), interrupt-fn closes over a mutable
       deadline volatile that we vreset! per render;
     - host fns (faking seon.db/query + the agent's own ns helpers) exposed via
       :namespaces — built from arbitrary fn values, exactly how lookup-value
       will feed them;
     - the live input map exposed via a host accessor reading a volatile, so the
       call string never has to inline a non-serializable db value;
     - per render: vreset! deadline + input, then ONE eval-string* of
       `<source>\\n(<simple-name> (host/current-input))`."
  (:require [sci.core :as sci]
            [sci.interrupt :as interrupt]))

(defn now [] (js/Date.now))
(defn line [] (println (apply str (repeat 72 "="))))

;; ---- mutable per-render holders the ctx closes over -----------------------
(def !deadline (volatile! 0))
(def !input    (volatile! nil))

(defn current-input [] @!input)

(defn classify [e]
  (cond
    (and (instance? cljs.core/ExceptionInfo e)
         (contains? (ex-data e) :sci.impl/interrupt))
    "SCI-INTERRUPT (marker present)"
    (instance? js/Error e) (str "js/Error: " (.-message e))
    :else (str "other: " (pr-str e))))

;; ---- fake host surface (stands in for the compiled seon.* corpus) ---------
(defn fake-db-query
  "A trusted, terminating core fn — like seon.db/query. Exposed as a HOST var;
   when interpreted code calls it, it runs as compiled host code (fast, fine)."
  [_db]
  [{:row 1} {:row 2} {:row 3}])

(defn host-helper-loop
  "A COMPILED agent helper that loops forever (self-bounded so the spike
   returns). Stands in for the residual class: a loop hidden in a host/compiled
   fn — the interrupt-fn never fires inside it."
  [self-abort-ms]
  (let [deadline (+ (now) self-abort-ms)]
    (loop [i 0] (if (> (now) deadline) i (recur (inc i))))))

;; ---- the shared ctx (built ONCE, like production) -------------------------
(def ctx
  (sci/init
    {:interrupt-fn (fn [] (when (> (now) @!deadline) (interrupt/interrupt!)))
     :classes      {'js js/globalThis :allow :all}
     ;; host vars under arbitrary ns symbols — exactly the shape
     ;; (eval/lookup-value sym) feeds: a map of ns-sym -> {fn-sym fn-value}.
     :namespaces   {'seon.db        {'query fake-db-query}
                    'spike.hostns   {'helper-loop host-helper-loop}
                    'spike.invoke   {'current-input current-input}}}))

(def budget-ms 250)

(defn invoke-bounded
  "Production-shaped: reset deadline+input, eval the agent SOURCE so its body is
   interpreted, then call it with the live input via the host accessor. Returns
   {:ok v} | {:interrupt true} | {:error e}."
  [sym source input]
  (vreset! !input input)
  (vreset! !deadline (+ (now) budget-ms))
  (let [simple (name sym)
        call   (str source "\n(" simple " (spike.invoke/current-input))")]
    (try
      {:ok (sci/eval-string* ctx call)}
      (catch :default e
        (if (and (instance? cljs.core/ExceptionInfo e)
                 (or (contains? (ex-data e) :sci.impl/interrupt)
                     ;; the rewrapped form: interpreter re-wraps the propagated
                     ;; interrupt into a :sci/error whose CAUSE carries the marker
                     (some-> (ex-cause e) ex-data (contains? :sci.impl/interrupt))))
          {:interrupt true}
          {:error e})))))

(defn run-one [label sym source input]
  (let [t0 (now)
        r  (invoke-bounded sym source input)
        el (- (now) t0)]
    (println)
    (println (str "  [" label "]  " el " ms"))
    (cond
      (:interrupt r) (println "    => INTERRUPTED at deadline (good for a hang)")
      (:error r)     (println (str "    => threw: " (classify (:error r))))
      :else          (println (str "    => returned: " (pr-str (:ok r)))))
    (assoc r :elapsed el)))

(defn main [& _]
  (println)
  (line)
  (println "SCI invoke spike — agent source eval'd into ctx, host-resolvable, bounded")
  (println (str "Node " js/process.version "  budget " budget-ms "ms"))
  (line)

  (println "TEST A — GOOD tile: source eval'd, calls host fn (seon.db/query) +")
  (println "         reads the live input map. Must RETURN a value, no hang.")
  (run-one "good-tile"
           'my.workouts/chart-tile
           "(defn chart-tile [m]
              {:rows (count (seon.db/query (:db m)))
               :who  (:who m)
               :year (.getFullYear (js/Date.))})"
           {:db :fake-db-value :who "sean"})

  (line)
  (println "TEST B — HANGING tile: a loop in the INTERPRETED body. Must be")
  (println "         ABORTED at the deadline; the script must CONTINUE.")
  ;; falsification probe: arm a macrotask + microtask; if the loop yielded the
  ;; event loop, one would flip. Both must stay false.
  (let [macro (atom false) micro (atom false)]
    (js/setTimeout #(reset! macro true) 0)
    (.then (js/Promise.resolve) (fn [_] (reset! micro true)))
    (run-one "hang-loop"
             'my.workouts/bad-tile
             "(defn bad-tile [m] (loop [] (recur)))"
             {:db :fake-db-value})
    (println (str "    falsification: macrotask ran? " @macro
                  "  microtask ran? " @micro "  (both must be FALSE)")))

  (run-one "hang-while"
           'my.workouts/bad-tile2
           "(defn bad-tile2 [m] (while true (inc 1)))"
           {:db :fake-db-value})

  (line)
  (println "TEST C — UN-CATCHABLE: the tile wraps the loop in a hostile catch.")
  (println "         Must STILL be interrupted (not :swallowed).")
  (run-one "hostile-catch"
           'my.workouts/evil-tile
           "(defn evil-tile [m] (try (loop [] (recur)) (catch :default _ :swallowed)))"
           {:db :fake-db-value})

  (line)
  (println "TEST D — RESIDUAL: tile calls a COMPILED host helper that loops.")
  (println "         interrupt-fn never fires inside host code; NOT bounded.")
  (run-one "host-loop-residual"
           'my.workouts/host-tile
           "(defn host-tile [m] (spike.hostns/helper-loop 1200))"
           {:db :fake-db-value})

  (line)
  (println "TEST E — re-use: run the GOOD tile again to prove the shared ctx is")
  (println "         reusable after interrupts (deadline reset each render).")
  (run-one "good-tile-again"
           'my.workouts/chart-tile
           "(defn chart-tile [m] {:rows (count (seon.db/query (:db m)))})"
           {:db :fake-db-value})

  (line)
  (println "TEST F — OWN-NS HELPER: eval source IN the agent's ns (in-ns) so a")
  (println "         SIMPLE-NAME helper exposed under that ns resolves. Tests")
  (println "         no-regression for tiles that call own-ns helpers.")
  ;; expose a helper under ns my.helpers (simulating an agent-defined helper
  ;; reachable by simple name once we (in-ns 'my.helpers)).
  (let [ctx2 (sci/init
               {:interrupt-fn (fn [] (when (> (now) @!deadline) (interrupt/interrupt!)))
                :classes      {'js js/globalThis :allow :all}
                :namespaces   {'seon.db      {'query fake-db-query}
                               'my.helpers   {'fmt (fn [x] (str "fmt:" x))}
                               'spike.invoke {'current-input current-input}}})]
    (vreset! !input {:db :fake})
    (vreset! !deadline (+ (now) budget-ms))
    (let [r (try
              {:ok (sci/eval-string* ctx2
                     "(in-ns 'my.helpers)
                      (defn tile [m] (fmt (count (seon.db/query (:db m)))))
                      (tile (spike.invoke/current-input))")}
              (catch :default e {:error (classify e)}))]
      (println)
      (println (str "    own-ns simple-name helper resolved? "
                    (if (:ok r) (str "YES => " (pr-str (:ok r)))
                        (str "NO => " (:error r)))))))

  (line)
  (println "TEST G — WARMUP: a one-time warmup eval at ctx init should make the")
  (println "         FIRST real hang land near budget (not the cold ~356ms).")
  (sci/eval-string* ctx "(defn __warm [_] (loop [i 0] (if (< i 50) (recur (inc i)) i))) (__warm nil)")
  (run-one "hang-after-warmup"
           'my.workouts/bad3
           "(defn bad3 [m] (loop [] (recur)))"
           {:db :fake})

  (line)
  (println "TEST H — ALIASES: agent source uses `db/query` (an :as alias for")
  (println "         seon.db). Establish the alias so the bare defn resolves it.")
  ;; (1) does evaling the agent's (ns ...) require form establish the alias
  ;;     against an exposed :namespaces ns?
  (let [ctxh (sci/init
               {:interrupt-fn (fn [] (when (> (now) @!deadline) (interrupt/interrupt!)))
                :classes      {'js js/globalThis :allow :all}
                :namespaces   {'seon.db      {'query fake-db-query}
                               'spike.invoke {'current-input current-input}}})]
    (vreset! !input {:db :fake})
    (vreset! !deadline (+ (now) budget-ms))
    (let [r (try
              {:ok (sci/eval-string* ctxh
                     "(ns my.agent.x (:require [seon.db :as db]))
                      (defn tile [m] {:n (count (db/query (:db m)))})
                      (tile (spike.invoke/current-input))")}
              (catch :default e {:error (classify e)}))]
      (println (str "    (1) eval ns-form require to alias: "
                    (if (:ok r) (str "YES => " (pr-str (:ok r))) (str "NO => " (:error r)))))))
  ;; (2) does :ns-aliases option resolve `db/query` -> seon.db/query?
  (let [ctxa (sci/init
               {:interrupt-fn (fn [] (when (> (now) @!deadline) (interrupt/interrupt!)))
                :classes      {'js js/globalThis :allow :all}
                :ns-aliases   {'db 'seon.db}
                :namespaces   {'seon.db      {'query fake-db-query}
                               'spike.invoke {'current-input current-input}}})]
    (vreset! !input {:db :fake})
    (vreset! !deadline (+ (now) budget-ms))
    (let [r (try
              {:ok (sci/eval-string* ctxa
                     "(defn tile [m] {:n (count (db/query (:db m)))}) (tile (spike.invoke/current-input))")}
              (catch :default e {:error (classify e)}))]
      (println (str "    (2) :ns-aliases {'db 'seon.db}: "
                    (if (:ok r) (str "YES => " (pr-str (:ok r))) (str "NO => " (:error r)))))))

  (line)
  (println "SPIKE COMPLETE — process reached the end (it never hung).")
  (line))
