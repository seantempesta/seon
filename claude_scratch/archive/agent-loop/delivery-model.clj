;; The two message deliveries' wake rules, model-checked (design agent-loop section 3a; owner,
;; 2026-10-25: "Launch a comparison"). One recipient session; messages sent at random points
;; while its process runs a turn, decides to park, parks, or has no process. Every event order
;; of a schedule is the interleaving of the senders with the recipient's loop. A rule is safe
;; when, at quiescence (the reconciler has nothing to start), every message is on the
;; recipient's branch, and no run starts while one runs (no double turns).
;;
;; A (main-first): the fact is written on main (count on main +1), then delivered to a live
;; process's inbox; a turn takes the inbox onto the branch; at park the run records taken =
;; messages on its branch. Ready: no run running and (on main) > taken of the last run.
;; B (sender-branch, ctx-atom section 11): the fact is written on the sender's branch, then
;; delivered to a live process, which merges it onto its branch; ready: no run running and
;; some message to it is not yet on its branch (a set difference over facts).
;; Run: bin/jolt run notes/agent-loop/delivery-model.clj (n schedules of up to 100 events).

(defn step-a [st [ev x]]
  (case ev
    :send (cond-> (-> st (update :main conj x))
            (:live st) (update :inbox conj x))
    :turn (if (:running st) (-> st (update :branch into (:inbox st)) (assoc :inbox #{})) st)
    :park (if (:running st) (assoc st :running false :taken (count (:branch st))) st)
    :die (assoc st :live false :running false :inbox #{} :taken (count (:branch st)))
    st))

(defn ready-a? [st] (and (not (:running st)) (> (count (:main st)) (:taken st))))

(defn start-a [st]
  ;; a wake (live) or a load (no process: what is on main and not on the branch comes in at load)
  (-> st (assoc :running true :live true :starts (inc (:starts st)))
      (update :branch into (if (:live st) (:inbox st) (remove (:branch st) (:main st))))
      (assoc :inbox #{})))

(defn step-b [st [ev x]]
  (case ev
    :send (cond-> (update st :sent conj x)
            (:live st) (update :branch conj x))        ; a live process merges it at once
    :turn st
    :park (if (:running st) (assoc st :running false) st)
    :die (assoc st :live false :running false)
    st))

(defn ready-b? [st] (and (not (:running st)) (seq (remove (:branch st) (:sent st)))))

(defn start-b [st]
  (-> st (assoc :running true :live true :starts (inc (:starts st)))
      (update :branch into (:sent st))))                ; a load merges what is pending

(defn run-schedule
  "Apply events evs, letting the reconciler start a run whenever ready? holds after an event;
  then run to quiescence: each started run takes a turn and parks. Answers the final state."
  [step ready? start st0 evs]
  (let [settle (fn [st] (loop [st st k 0]
                          (if (and (ready? st) (< k 1000))
                            (recur (-> (start st) (step [:turn]) (step [:park])) (inc k))
                            st)))
        st (reduce (fn [st ev]
                     (let [st (step st ev)]
                       (if (and (ready? st) (not (:running st))) (start st) st)))
                   st0 evs)]
    (settle (-> st (step [:turn]) (step [:park])))))

(defn schedule [rng n]
  (let [ids (atom 0)]
    (vec (for [_ (range n)]
           (let [r (.nextInt rng 10)]
             (cond (< r 4) [:send (swap! ids inc)]
                   (< r 7) [:turn]
                   (< r 9) [:park]
                   :else [:die]))))))

(defn check [label step ready? start st0]
  (let [rng (java.util.Random. 42)
        bad (atom [])]
    (dotimes [i 2000]
      (let [evs (schedule rng (inc (.nextInt rng 100)))
            sent (set (keep (fn [[e x]] (when (= e :send) x)) evs))
            st (run-schedule step ready? start (assoc st0 :running true :live true :starts 1) evs)]
        (when-not (and (= sent (set (filter sent (:branch st)))) (not (ready? st)))
          (swap! bad conj {:evs evs :final st}))))
    (println label {:schedules 2000 :lost (count @bad)})
    (when (seq @bad) (println "first counterexample:" (pr-str (first @bad))))))

(check "A main-first, count vs taken:" step-a ready-a? start-a {:main #{} :inbox #{} :branch #{} :taken 0})
(check "B sender-branch, set difference:" step-b ready-b? start-b {:sent #{} :branch #{}})
