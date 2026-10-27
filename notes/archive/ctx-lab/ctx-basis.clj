;; After ctx-atom.clj: the map carries its environment, the basis it was read at (:t the db's max-tx,
;; :branch, :commit its last commit), read-only; a swap! whose value was computed from another basis
;; than the db's now is refused (an optimistic check, as :db/cas is), so a function that writes the
;; db runs once and nothing retries.
(def env-keys [:t :branch :commit])
(defn env [db] {:t (:max-tx db) :branch (:branch @agent/state) :commit (str (get-in db [:meta :datahike/commit-id]))})
(defn bare [m] (apply dissoc m env-keys))

(defn load! []
  (vreset! sch (d/schema (agent/db)))
  (let [t (binding [*schema* @sch] (tree (me)))]
    (reset! ctx-ix (index-paths :seon.db/agent-fact t))
    (binding [*patching* true] (reset! ctx (merge t (env (agent/db)))))))

(defn on-state [_ _ old new]
  (let [a (:db old) b (:db new)]
    (when (and a b @ctx (not *writing*) (not (identical? a b)) (not= (:max-tx a) (:max-tx b)))
      (let [[gone added] (d/datoms-diff a b :eavt [])
            c (binding [*schema* @sch] (patch {:tree (bare @ctx) :ix @ctx-ix} b (concat (map #(dm % false) gone) (map #(dm % true) added))))]
        (reset! ctx-ix (:ix c))
        (binding [*patching* true] (reset! ctx (merge (:tree c) (env b))))))))

(set-validator! ctx (fn [nv]
  (or *patching* (nil? @ctx)
      (let [cur @ctx]
        (cond
          (not= (:t nv) (:t cur))
          (throw (ex-info (str "ctx: the db moved while your swap! function ran (it read t " (:t nv) ", the db is at " (:t cur)
                               "): a function given to swap! must not write; write after the swap!")
                          {:seon.error/type :seon.ctx/moved :read (:t nv) :now (:t cur)}))
          (not= (select-keys nv env-keys) (select-keys cur env-keys))
          (throw (ex-info "ctx: :t, :branch and :commit are where this map was read, read-only" {:seon.error/type :seon.ctx/read-only :keys env-keys}))
          :else (write! (bare cur) (bare nv)))))))
(remove-watch ctx ::held)
(add-watch ctx ::held (fn [_ _ _ _] (when-let [c @pending] (vreset! pending nil) (reset! ctx-ix (:ix c))
                                      (binding [*patching* true] (reset! ctx (merge (:tree c) (env (agent/db))))))))
(remove-watch agent/state ::ctx)
(add-watch agent/state ::ctx on-state)
(load!)
:basis
