;;; STANDALONE PROTOTYPE — cache-aware tiered transcript (research artifact).
;;; NOT on any classpath, NOT compiled by the test build, NOT instrumented.
;;; Paste into a live pod CLJS session (mcp__seon_cljs__eval, session "default")
;;; to reproduce the measurements in transcript-dynamic-cache-aware-2026-06-28.md.
;;;
;;; It pulls a REAL long transcript (agent "root") from the live store and applies
;;; the tiering at several configs. Read-only — touches no Core ns, writes nothing.

(require '[clojure.string :as str]
         '[seon.db :as db]
         '[seon.ai.tokens :as tok]
         '[seon.agent.ctx.transcript :as trx])

;; ------------------------------------------------------------
;; Pull the real ordered event stream for an agent (private fn, REPL-accessible).
;; ------------------------------------------------------------
(defn events-for [id]
  (let [conn @db/*conn*
        a    (db/entity-lazy {:seon.db/ref [:seon.agent/id id] :seon.db/db conn})]
    (trx/ordered-events conn id (:db/id a))))

;; current full render of one event (mirrors transcript-block's render* dispatch)
(defn full-render [ev]
  (let [f (case (str (:seon.render/ai ev))
            "seon.agent.ctx.transcript/eval->renderable"      trx/eval->renderable
            "seon.agent.ctx.transcript/message->renderable"   trx/message->renderable
            "seon.agent.ctx.transcript/coalesced->renderable" trx/coalesced->renderable
            (constantly ""))]
    (f {:seon.render/node ev})))

;; ------------------------------------------------------------
;; shape-hint — cheap type+size probe over the stored pr-str result-edn.
;; Reuse target in Core: the value-renderer's existing skeleton probe.
;; ------------------------------------------------------------
(defn shape-hint [res-edn]
  (let [s (str/trim (str res-edn)) n (count s)]
    (cond
      (str/blank? s)           "nil"
      (str/starts-with? s "{")  (str "map ~"    (quot n 4) " tok")
      (str/starts-with? s "[")  (str "vec ~"    (quot n 4) " tok")
      (str/starts-with? s "#{") (str "set ~"    (quot n 4) " tok")
      (str/starts-with? s "(")  (str "seq ~"    (quot n 4) " tok")
      (str/starts-with? s "\"") (str "string ~" (quot n 4) " tok")
      (re-matches #"-?\d.*" s)  (str "num " (subs s 0 (min n 12)))
      :else                     (str "val ~"    (quot n 4) " tok"))))

(defn clip-line [s cap] (if (> (count s) cap) (str (subs s 0 cap) "…") s))

;; ------------------------------------------------------------
;; render-tiered — the FROZEN per-tier render. PURE fn of (event, tier):
;; independent of how many newer events exist → byte-stable once the tier
;; is assigned. This is the cache-stability core.
;; ------------------------------------------------------------
(defn render-tiered [ev tier]
  (case (:seon.agent.ctx.transcript/kind ev)
    :message   (let [base (trx/message->renderable {:seon.render/node ev})]
                 (case tier (:full :light :pointer) base :summary (clip-line base 90)))
    :coalesced (trx/coalesced->renderable {:seon.render/node ev})
    :eval
    (let [e   (:seon.agent.ctx.transcript/entity ev)
          src (str (:seon.eval/source e)) narr (str (:seon.eval/narration e))
          eid (:seon.eval/id e) ok? (:seon.eval/ok? e) res (:seon.eval/result-edn e)
          marker  (:seon.agent.ctx.transcript/ns-marker ev)
          pre-cap (case tier :light 240 :pointer 140 0)
          pre (when (and (seq narr) (not (str/blank? narr)) (pos? pre-cap))
                (str "; " (clip-line (str/replace (str/trim narr) #"\n" " ") pre-cap)))
          row
          (case tier
            :full    (trx/eval->renderable {:seon.render/node ev})
            :light   (str/join "\n" (remove nil?
                       [pre (clip-line src 300)
                        (if ok? (str ";=> " (clip-line (str/trim (str res)) 400) " ; result/" eid)
                            (str ";=> ✗ " (clip-line (str (:seon.eval/error e)) 200)))]))
            :pointer (str/join "\n" (remove nil?
                       [pre (clip-line src 160)
                        (if ok? (str ";=> " (shape-hint res) " ; result/" eid " — deref to expand")
                            (str ";=> ✗ " (clip-line (str (:seon.eval/error e)) 120)))]))
            :summary (let [verb (clip-line (str/trim (first (str/split-lines src))) 60)]
                       (str "; · " (if (str/blank? verb) "(comment)" verb)
                            (if ok? (str " ;=> " (shape-hint res)) " ;=> ✗"))))]
      (if marker (str marker "\n" row) row))))

;; ------------------------------------------------------------
;; assign-tiers — DISCRETE age bands. tier(event) = f(age in events), where
;; age = (#events newer than it). Bands are sizes newest-first; older than
;; the last band → :summary. Quantized + monotone → the cacheable prefix
;; (the :summary run) only ever GROWS (append-only).
;; ------------------------------------------------------------
(defn assign-tiers [events bands]
  (let [total  (count events)
        cum    (reductions + (map first bands))
        levels (map second bands)
        age->tier (fn [age]
                    (loop [cs cum ls levels]
                      (cond (empty? cs)        :summary
                            (< age (first cs)) (first ls)
                            :else              (recur (rest cs) (rest ls)))))]
    (map-indexed (fn [i ev] [ev (age->tier (- total 1 i))]) events)))

(defn tiered-render [events config]
  (map (fn [[ev tier]] (let [t (render-tiered ev tier)]
                         {:tier tier :tok (tok/estimate t) :text t}))
       (assign-tiers events (:bands config))))

(defn measure [events config]
  (let [rendered  (tiered-render events config)
        total-tok (reduce + (map :tok rendered))
        stable    (filter #(= :summary (:tier %)) rendered)   ; the frozen contiguous prefix
        stable-tok (reduce + (map :tok stable))]
    {:config-name       (:name config)
     :total-tok         total-tok
     :stable-prefix-tok stable-tok
     :volatile-tail-tok (- total-tok stable-tok)
     :n-stable          (count stable)
     :by-tier (->> rendered (group-by :tier)
                   (map (fn [[k v]] [k {:n (count v) :tok (reduce + (map :tok v))}]))
                   (into {}))}))

;; ------------------------------------------------------------
;; Cross-turn byte-stability proof: as events accrue (older turns = fewer
;; events), the frozen :summary prefix is byte-identical + append-only.
;; ------------------------------------------------------------
(defn stable-prefix-strings [events config]
  (->> (assign-tiers events (:bands config))
       (map (fn [[ev tier]] [tier (render-tiered ev tier)]))
       (filter #(= :summary (first %)))
       (mapv second)))

(comment
  (def evs (events-for "root"))
  (def baseline (reduce + (map #(tok/estimate (full-render %)) evs)))   ; => 21843

  (def configs
    [{:name "A 6/10/16"   :bands [[6 :full] [10 :light] [16 :pointer]]}
     {:name "B 8/16/24"   :bands [[8 :full] [16 :light] [24 :pointer]]}
     {:name "C 12/20/32"  :bands [[12 :full] [20 :light] [32 :pointer]]}
     {:name "E 6/8/12/20" :bands [[6 :full] [8 :light] [12 :pointer] [20 :pointer]]}
     {:name "F tail-10"   :bands [[10 :full]]}])
  (mapv #(measure evs %) configs)

  ;; append-only / byte-identical proof across three accruing "turns"
  (let [cfg (first configs)
        s1 (stable-prefix-strings (vec (take 140 evs)) cfg)
        s2 (stable-prefix-strings (vec (take 143 evs)) cfg)
        s3 (stable-prefix-strings (vec (take 146 evs)) cfg)]
    {:n [(count s1) (count s2) (count s3)]
     :s1-prefix-of-s2? (= s1 (vec (take (count s1) s2)))
     :s2-prefix-of-s3? (= s2 (vec (take (count s2) s3)))}))
