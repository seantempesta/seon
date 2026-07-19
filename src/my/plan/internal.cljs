(ns my.plan.internal
  "Implement the private derived mechanics behind `my.plan`.

   This hidden namespace owns plan compilation, agent scoping, Datalog rules,
   dependency and progress derivations, tree mutation support, replanning
   escalation, and windowed context rendering. It keeps implementation detail
   out of the agent-facing teaching surface and uses fully qualified
   `:my.plan/*` attributes across the namespace boundary."
  (:require
    [cljs.reader :as edn]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [seon.agent.message :as msg]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.repair.candidates :as cand]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]))

(def rules
  "Datalog rules over the plan graph — `my.plan/rules` re-defs this value:
   descendant (transitive tree closure, cycle-safe), leaf (no children),
   unfinished (:open/:active/:blocked — anything not :done), open-work
   (unfinished leaf in the subtree), blocked (an explicit :blocked status OR
   a `needs` target with open work), ready (work to do now — an open unblocked
   leaf, OR an open unblocked non-leaf whose subtree is fully drained: its one
   remaining action is verify-and-close). An :active step is in hand, not
   re-listed as ready. Negations (`leaf`, `not blocked`, `not open-work`) only
   FILTER bound tuples, so bind the entity positively BEFORE invoking these."
  '[[(descendant ?a ?n) [?n :my.plan/parent ?a]]
    [(descendant ?a ?n) [?m :my.plan/parent ?a] (descendant ?m ?n)]
    [(leaf ?t) (not-join [?t] [?c :my.plan/parent ?t])]
    [(unfinished ?t) [?t :my.plan/status :open]]
    [(unfinished ?t) [?t :my.plan/status :active]]
    [(unfinished ?t) [?t :my.plan/status :blocked]]
    [(open-work ?t) (unfinished ?t) (leaf ?t)]
    [(open-work ?t) (descendant ?t ?l) (unfinished ?l) (leaf ?l)]
    [(blocked ?t) [?t :my.plan/status :blocked]]
    [(blocked ?t) [?t :my.plan/needs ?d] (open-work ?d)]
    [(ready ?t) [?t :my.plan/status :open] (leaf ?t) (not (blocked ?t))]
    [(ready ?t) [?t :my.plan/status :open] (not (leaf ?t))
     (not (open-work ?t)) (not (blocked ?t))]])

(defn fail [msg] {:my.plan/ok? false :my.plan/error msg})

(defn agent-ref
  "Lookup ref for agent id `id` — nil when no id resolved.

   The id arrives as the functions' DECLARED `:seon.agent/id` request key
   (filled at the eval boundary when the caller omits it — see
   `seon.instrument/injectables`); no ambient read happens here."
  [id]
  (when id [:seon.agent/id id]))

(defn write-result
  "Convert a transaction report into a plan write response."
  [fn-name id report]
  (if-not (:seon.error/message report)
    {:my.plan/ok? true :my.plan/id id}
    (fail (str fn-name ": db write failed — "
               (:seon.error/message report)))))

;; --- Ordinary plan rows ---------------------------------------------------
;; The public my.plan surface acquires these rows from the writer in one
;; bounded request.  Everything below is an immutable projection over that
;; data: no pod-local Datahike value, entity wrapper, index, or cache.

(defn row-id [row] (:my.plan/id row))
(defn row-parent-id [row] (:my.plan/id (:my.plan/parent row)))
(defn row-need-ids [row]
  (into #{} (keep :my.plan/id) (:my.plan/needs row)))

(defn rows-for-agent
  [rows agent-ref]
  (let [agent-id (second agent-ref)]
    (filterv #(= agent-id (get-in % [:my.plan/agent :seon.agent/id])) rows)))

(defn rows-by-id [rows]
  (into {} (map (juxt row-id identity)) rows))

(defn- child-ids [rows id]
  (into [] (keep #(when (= id (row-parent-id %)) (row-id %))) rows))

(defn descendant-ids-from-rows
  [rows id]
  (loop [pending (child-ids rows id) seen #{}]
    (if-let [candidate (first pending)]
      (if (seen candidate)
        (recur (subvec pending 1) seen)
        (recur (into (subvec pending 1) (child-ids rows candidate))
               (conj seen candidate)))
      (vec seen))))

(defn- has-open-work?
  [by-id children id seen]
  (when-not (seen id)
    (let [row (by-id id)
          child-ids (children id)]
      (if (seq child-ids)
        (boolean (some #(has-open-work? by-id children % (conj seen id))
                       child-ids))
        (contains? #{:open :active :blocked} (:my.plan/status row))))))

(defn blocked-from-rows?
  [rows id]
  (let [by-id (rows-by-id rows)
        children #(child-ids rows %)
        row (by-id id)]
    (boolean
      (or (= :blocked (:my.plan/status row))
          (some #(has-open-work? by-id children % #{}) (row-need-ids row))))))

(defn ready-from-rows?
  [rows id]
  (let [by-id (rows-by-id rows)
        row (by-id id)
        children #(child-ids rows %)
        ids (children id)]
    (boolean
      (and (= :open (:my.plan/status row))
           (not (blocked-from-rows? rows id))
           (or (empty? ids)
               (not (some #(has-open-work? by-id children % #{id}) ids)))))))

(defn plan-rollup-from-rows
  [rows id]
  (let [by-id (rows-by-id rows)
        ids (conj (set (descendant-ids-from-rows rows id)) id)
        leaves (filter #(empty? (child-ids rows %)) ids)
        total (count leaves)
        done (count (filter #(= :done (:my.plan/status (by-id %))) leaves))]
    {:my.plan/done done
     :my.plan/total total
     :my.plan/done? (and (pos? total) (= done total))}))

(defn status-view-from-rows
  [rows id]
  (let [{:my.plan/keys [done total done?]} (plan-rollup-from-rows rows id)]
    {:my.plan/id id
     :my.plan/done? done?
     :my.plan/blocked? (blocked-from-rows? rows id)
     :my.plan/ready? (ready-from-rows? rows id)
     :my.plan/progress {:my.plan/done done :my.plan/total total}}))

(defn ready-leaves-from-rows
  [rows]
  (->> rows
       (filter #(ready-from-rows? rows (row-id %)))
       (sort-by #(.getTime ^js (:my.plan/created-at %)))
       (mapv #(select-keys % [:my.plan/id :my.plan/title :my.plan/created-at]))))

(defn active-steps-from-rows
  [rows]
  (->> rows
       (filter #(= :active (:my.plan/status %)))
       (sort-by #(.getTime ^js (:my.plan/created-at %)))
       vec))

(defn ancestor-chain-from-rows
  [rows id]
  (let [by-id (rows-by-id rows)]
    (loop [chain () current id seen #{}]
      (if (or (nil? current) (seen current))
        (vec chain)
        (let [row (by-id current)]
          (recur (cons (select-keys row [:my.plan/id :my.plan/title
                                         :my.plan/goal :my.plan/pace]) chain)
                 (row-parent-id row)
                 (conj seen current)))))))

(defn anchor-from-rows
  [rows]
  (let [active (first (active-steps-from-rows rows))
        step (or active (first (ready-leaves-from-rows rows)))]
    (when step
      (let [chain (ancestor-chain-from-rows rows (row-id step))
            root (first chain)]
        {:my.plan/step step
         :my.plan/chain chain
         :my.plan/active? (some? active)
         :my.plan/progress (plan-rollup-from-rows rows (row-id root))}))))

(defn subtree-from-rows
  [rows id]
  (let [by-id (rows-by-id rows)]
    (letfn [(build [current seen]
              (when-let [row (and (not (seen current)) (by-id current))]
                (let [children (into [] (keep #(build % (conj seen current)))
                                     (child-ids rows current))
                      node (select-keys row [:my.plan/id :my.plan/title
                                             :my.plan/status :my.plan/goal
                                             :my.plan/expect :my.plan/pace
                                             :my.plan/description
                                             :my.plan/needs])]
                  (cond-> node (seq children)
                    (assoc :my.plan/_parent children)))))]
      (build id #{}))))

(defn forest-from-rows
  [rows]
  (->> rows
       (filter #(nil? (row-parent-id %)))
       (sort-by #(.getTime ^js (:my.plan/created-at %)))
       (keep #(subtree-from-rows rows (row-id %)))
       vec))

(defn open-steps-from-rows
  [rows]
  (->> rows
       (remove #(= :done (:my.plan/status %)))
       (sort-by #(.getTime ^js (:my.plan/created-at %)))
       (mapv #(select-keys % [:my.plan/id :my.plan/title :my.plan/status
                              :my.plan/created-at :my.plan/description
                              :my.plan/message]))))

;; --- Loud unknown-key guard (registry class: silent unknown-key acceptance
;; --- in my.* request maps). A request map is OPEN — the eval boundary
;; --- composes injectable keys in, and foreign-namespace keys pass through —
;; --- so a MISSPELLED `:my.plan/*` key (e.g. `:my.plan/steps` for
;; --- `:my.plan/children`) is silently dropped and mints a childless plan.
;; --- The accepted key set is DERIVED from the registered schemas (never a
;; --- hand list), so it can't drift; the fix suggestion reuses the ONE
;; --- candidate ranker (`seon.repair.candidates`).

(defn schema-map-keys
  "The accepted map-entry keys of registered schema `k`, DERIVED from its
   definition — every key of every nested `[:map …]` (so a `:schema`/
   registry-wrapped node contributes its keys). Never a hand list."
  [k]
  (let [acc (volatile! #{})]
    (walk/postwalk
      (fn [x]
        (when (and (vector? x) (= :map (first x)))
          (doseq [e (rest x)]
            (when (and (vector? e) (keyword? (first e)))
              (vswap! acc conj (first e)))))
        x)
      (schema/schema-definition k))
    @acc))

(defn my-plan-key?
  "True iff keyword `k` is namespaced under `my.plan` (or `my.plan.*`)."
  [k]
  (boolean (when-let [ns (namespace k)]
             (or (= ns "my.plan") (str/starts-with? ns "my.plan.")))))

(defn unknown-key-fail
  "Fail envelope for the FIRST `my.plan`-namespaced key in `request` that
   `accepted` doesn't contain — naming the key + a did-you-mean over the
   accepted `my.plan` keys ([[cand/rank-candidates]]) + the full accepted
   set — or nil when every my.plan key is accepted. Foreign-namespace and
   injectable keys pass (the open-map convention stays intact)."
  [fn-name request accepted]
  (when-let [bad (->> (keys request)
                      (filter my-plan-key?)
                      (remove accepted)
                      first)]
    (let [targets (filterv my-plan-key? accepted)
          sugg    (->> (cand/rank-candidates (name bad) (mapv name targets))
                       (mapv (fn [{to :seon.repair/to}] (str ":my.plan/" to))))]
      (fail (str fn-name ": unknown key " bad
                 (when (seq sugg)
                   (str " — did you mean " (str/join " or " sugg) "?"))
                 " Accepted my.plan keys: "
                 (str/join " " (sort targets)) ".")))))

(defn check-request-keys
  "Nil, or a fail envelope, when `request` carries an unknown `my.plan` key —
   accepted set DERIVED from the registered `schema-kw` request schema."
  [fn-name request schema-kw]
  (unknown-key-fail fn-name request (schema-map-keys schema-kw)))

(defn check-plan-keys
  "The recursive plan! key guard: the top `request` map against
   `:my.plan/plan-request`, then every `:my.plan/children` node (at any
   depth) against `:my.plan/plan-node` — first offender → fail envelope,
   else nil. Catches a misspelled key that would otherwise vanish and mint
   a childless plan."
  [fn-name request]
  (let [node-keys (schema-map-keys :my.plan/plan-node)
        check-node (fn check-node [node]
                     (or (unknown-key-fail fn-name node node-keys)
                         (some #(when (map? %) (check-node %))
                               (:my.plan/children node))))]
    (or (unknown-key-fail fn-name request (schema-map-keys :my.plan/plan-request))
        (some #(when (map? %) (check-node %)) (:my.plan/children request)))))

;; --- The document compiler — ONE compile for plan! AND reconcile!. --------
;; --- Authoring IS reconciling against an empty tree: plan! delegates via
;; --- [[compile-plan]] with a nil db (empty baseline), reconcile! diffs the
;; --- edited document against the caller's live open tree. Cross-sibling
;; --- edges link by STRING TEMPID, never same-tx lookup-refs (a lookup-ref
;; --- to a not-yet-asserted sibling throws `:entity-id/missing`).

(defn doc-children
  "A document node's children — the pull shape (`:my.plan/_parent`) and the
   authoring shape (`:my.plan/children`) both accepted."
  [node]
  (vec (concat (:my.plan/_parent node) (:my.plan/children node))))

(defn- doc-needs-ids
  "The step ids a doc node's `:my.plan/needs` names — accepts the pull shape
   `{:my.plan/id x}`, a `[:my.plan/id x]` lookup-ref, or a bare id string; an
   unrecognizable entry yields nil (the compiler fails loudly on it)."
  [node]
  (mapv (fn [n]
          (cond
            (map? n) (:my.plan/id n)
            (and (vector? n) (= :my.plan/id (first n))) (second n)
            (string? n) n))
        (:my.plan/needs node)))

(defn- collect-doc-nodes
  "Depth-first internal entries over document `forest`."
  [forest]
  (letfn [(walk [entries parent-index node]
            (let [index   (count entries)
                  entries (conj entries
                                {::node         node
                                 ::id           (:my.plan/id node)
                                 ::label        (:my.plan/ref node)
                                 ::parent-index parent-index})]
              (reduce (fn [acc child]
                        (walk acc index child))
                      entries
                      (doc-children node))))]
    (reduce (fn [entries node]
              (walk entries nil node))
            []
            forest)))

(defn prune-done
  "Tree/forest with every `:done` node (and its whole subtree) removed —
   the OPEN projection behind `my.plan/document`. History can't be edited
   away: open descendants of a done step leave the document WITH it and
   stay untouched by any reconcile."
  [t]
  (letfn [(prune [node]
            (when (and node (not= :done (:my.plan/status node)))
              (let [kids (into [] (keep prune) (:my.plan/_parent node))]
                (if (seq kids)
                  (assoc node :my.plan/_parent kids)
                  (dissoc node :my.plan/_parent)))))]
    (cond
      (map? t)    (prune t)
      (vector? t) (into [] (keep prune) t)
      :else       t)))

(defn flatten-open
  "Open forest to the compiler's namespaced baseline rows."
  [forest]
  (letfn [(walk [acc parent-id node]
            (let [acc (assoc acc (:my.plan/id node)
                             (-> (select-keys node [:my.plan/title
                                                    :my.plan/description
                                                    :my.plan/expect
                                                    :my.plan/goal
                                                    :my.plan/pace])
                                 (assoc ::parent-id parent-id
                                        ::need-ids (into #{} (keep :my.plan/id)
                                                         (:my.plan/needs node)))))]
              (reduce (fn [a c] (walk a (:my.plan/id node) c))
                      acc (:my.plan/_parent node))))]
    (reduce (fn [a n] (walk a nil n)) {} forest)))

(def ^:private doc-scalar-fields
  "The reconcilable scalar fields: `[attr retract-when-absent?]`. Title is
   required on every node, so it is never retracted."
  [[:my.plan/title false] [:my.plan/description true] [:my.plan/expect true]
   [:my.plan/goal true] [:my.plan/pace true]])

(defn check-doc-keys
  "First unknown `my.plan` key anywhere in document `nodes` → fail
   envelope, else nil — the recursive analog of [[check-plan-keys]] with
   the accepted set DERIVED from the registered `:my.plan/doc-node`."
  [fn-name nodes]
  (let [ks    (schema-map-keys :my.plan/doc-node)
        check (fn check [node]
                (or (unknown-key-fail fn-name node ks)
                    (some #(when (map? %) (check %)) (doc-children node))))]
    (some #(when (map? %) (check %)) nodes)))

(defn- allocation-key
  "Stable request-local key for the generated id of document entry `idx`."
  [idx]
  (keyword "my.plan.internal" (str "id-" idx)))

(defn- candidate-list
  "Readable `\"id «title»\"` listing of baseline step `ids`, sorted."
  [baseline ids]
  (str/join ", " (map (fn [id] (str (pr-str id) " «"
                                    (:my.plan/title (baseline id)) "»"))
                      (sort ids))))

(defn resolve-doc-identities
  "Resolve id-less document entries onto the open steps they re-state.

   The reconcile boundary rule — identity is unforgeable by construction:
   an edited document that OMITS a step's `:my.plan/id` must never mint a
   copy over a drop of the original (the id-less-root re-mint hazard).
   Root rule: exactly one id-less document root + exactly one open root
   unnamed by the document ⇒ the same root (update-in-place); several
   unnamed roots ⇒ only an IDENTICAL title picks one; still ambiguous ⇒
   `::error` naming the candidate ids — refuse, never guess-mint. Child
   rule (conservative): an id-less node resolves onto an unnamed open
   child of its own resolved parent ONLY on a one-to-one identical-title
   match; an ambiguous match ⇒ `::error`; no match ⇒ mint (a genuinely
   new step). Pure and deterministic over (entries, baseline) — safe to
   run identically in both compile passes.
   → `{::entries … ::resolved-root? bool}` or `{::error msg}`."
  [fn-name entries baseline]
  (let [doc-ids       (into #{} (keep ::id) entries)
        entry-title   (fn [e] (:my.plan/title (::node e)))
        unnamed-roots (into []
                            (keep (fn [[id row]]
                                    (when (and (nil? (::parent-id row))
                                               (not (doc-ids id)))
                                      id)))
                            baseline)
        idless-roots  (into []
                            (keep-indexed
                              (fn [i {::keys [id parent-index]}]
                                (when (and (nil? parent-index) (nil? id)) i)))
                            entries)
        root-res
        (cond
          (or (empty? idless-roots) (empty? unnamed-roots))
          {}

          (and (= 1 (count idless-roots)) (= 1 (count unnamed-roots)))
          {(first idless-roots) (first unnamed-roots)}

          :else
          (let [by-title (group-by #(:my.plan/title (baseline %)) unnamed-roots)
                matches  (mapv (fn [i]
                                 [i (by-title (entry-title (nth entries i)))])
                               idless-roots)
                resolved (into {} (keep (fn [[i cs]]
                                          (when (= 1 (count cs)) [i (first cs)])))
                               matches)]
            (if (and (every? (fn [[_ cs]] (< (count cs) 2)) matches)
                     (= (count resolved) (count (distinct (vals resolved))))
                     (or (= (count resolved) (count idless-roots))
                         (= (count resolved) (count unnamed-roots))))
              resolved
              {::error (str fn-name ": a document root carries no :my.plan/id "
                            "while open root(s) "
                            (candidate-list baseline unnamed-roots)
                            " are absent from the document — carry the "
                            ":my.plan/id of the root you are editing; an "
                            "id-less root resolves only when exactly one open "
                            "root (or a title-identical one) matches, otherwise "
                            "it would drop-and-re-mint the original.")})))]
    (if (::error root-res)
      root-res
      (let [entries   (reduce-kv (fn [es i id] (assoc-in es [i ::id] id))
                                 (vec entries) root-res)
            ;; Static claimant counts per [parent-index title] over the
            ;; still-id-less entries — computed BEFORE any child resolves,
            ;; so two same-title id-less siblings register as the
            ;; ambiguity they are (never first-come-takes-the-id).
            claimants (frequencies
                        (keep (fn [{::keys [id parent-index] :as e}]
                                (when (and (nil? id) parent-index)
                                  [parent-index (entry-title e)]))
                              entries))]
        (loop [i     0
               es    entries
               taken (into doc-ids (vals root-res))]
          (if (= i (count es))
            {::entries es ::resolved-root? (boolean (seq root-res))}
            (let [{::keys [id parent-index] :as e} (nth es i)
                  pid (when (and (nil? id) parent-index)
                        (::id (nth es parent-index)))]
              (if (nil? pid)
                (recur (inc i) es taken)
                (let [title (entry-title e)
                      cands (into []
                                  (keep (fn [[bid row]]
                                          (when (and (= pid (::parent-id row))
                                                     (not (taken bid))
                                                     (= title (:my.plan/title row)))
                                            bid)))
                                  baseline)]
                  (cond
                    (empty? cands)
                    (recur (inc i) es taken)

                    (and (= 1 (count cands))
                         (= 1 (claimants [parent-index title])))
                    (recur (inc i)
                           (assoc-in es [i ::id] (first cands))
                           (conj taken (first cands)))

                    :else
                    {::error (str fn-name ": «" title "» carries no "
                                  ":my.plan/id but matches your open step(s) "
                                  (candidate-list baseline cands)
                                  " under the same parent — carry the "
                                  ":my.plan/id of the step you are editing, or "
                                  "retitle the new one; minting here would "
                                  "drop-and-re-mint the original.")}))))))))))

(defn- compile-resolved
  "The post-resolution compile behind [[compile-reconcile]]: identity-
   resolved `entries` + the open `baseline` → the flat tx + the receipt
   fields (`{::error …}` on a bad id / label / needs reference)."
  [known-status fn-name agent entries resolved-root? baseline ids now]
  (let [entries  (vec (map-indexed
                        (fn [i e]
                          (if (::id e)
                            (assoc e ::index i)
                            (let [allocation-token (allocation-key i)]
                              (assoc e
                                     ::index i
                                     ::tempid (str "t" i)
                                     ::allocation-key allocation-token
                                     ::new-id (get ids allocation-token)))))
                        entries))
        target   (fn [{::keys [id tempid]}]
                   (if id [:my.plan/id id] tempid))
        l->t     (into {} (keep (fn [e]
                                  (when (::label e)
                                    [(::label e) (target e)])))
                       entries)
        doc-ids  (into [] (keep ::id) entries)]
    (or
      (some (fn [{::keys [id]}]
              (when id
                (let [s (known-status id)]
                  (cond
                    (nil? s)
                    {::error (str fn-name ": no step " (pr-str id) " — keep "
                                  ":my.plan/id only on steps that exist; omit "
                                  "it to mint a new one.")}
                    (= :done s)
                    {::error (str fn-name ": " (pr-str id) " is :done — done steps "
                                  "are immune (absent from the document by "
                                  "construction); reopen! it first if it truly "
                                  "isn't done.")}
                    (not (contains? baseline id))
                    {::error (str fn-name ": step " (pr-str id) " is not in your "
                                  "open tree — reconcile edits only your own "
                                  "open steps.")}))))
            entries)
      (let [unknown (->> entries (mapcat #(:my.plan/after (::node %)))
                         distinct (remove l->t) seq)]
        (when unknown
          {::error (str fn-name ": :my.plan/after names unknown label(s) "
                        (str/join ", " (map pr-str unknown))
                        " — each :after must match some node's :my.plan/ref.")}))
      (some (fn [{::keys [node]}]
              (some (fn [nid]
                      (cond
                        (nil? nid)
                        {::error (str fn-name ": unrecognizable :my.plan/needs "
                                      "entry on «" (:my.plan/title node)
                                      "» — use {:my.plan/id \"…\"}.")}
                        (nil? (known-status nid))
                        {::error (str fn-name ": :my.plan/needs names unknown step "
                                      (pr-str nid) ".")}))
                    (doc-needs-ids node)))
            entries)
      (let [parent-t (fn [{::keys [parent-index]}]
                       (when parent-index (target (nth entries parent-index))))
            news     (into []
                           (keep
                             (fn [{::keys [id tempid new-id node] :as e}]
                               (when-not id
                                 (let [needs (-> (mapv l->t (:my.plan/after node))
                                                 (into (map (fn [nid] [:my.plan/id nid]))
                                                       (doc-needs-ids node)))]
                                   (cond-> {:db/id              tempid
                                            :my.plan/id         new-id
                                            :my.plan/title      (:my.plan/title node)
                                            :my.plan/status     :open
                                            :my.plan/agent      agent
                                            :my.plan/created-at now}
                                     (:my.plan/goal node)        (assoc :my.plan/goal (:my.plan/goal node))
                                     (:my.plan/pace node)        (assoc :my.plan/pace (:my.plan/pace node))
                                     (:my.plan/description node) (assoc :my.plan/description (:my.plan/description node))
                                     (:my.plan/expect node)      (assoc :my.plan/expect (:my.plan/expect node))
                                     (parent-t e)                (assoc :my.plan/parent (parent-t e))
                                     (seq needs)                 (assoc :my.plan/needs needs))))))
                           entries)
            updates  (into []
                           (keep
                             (fn [{::keys [id node] :as e}]
                               (when id
                                 (let [base   (baseline id)
                                       pt     (parent-t e)
                                       sets   (into {}
                                                    (keep (fn [[k _]]
                                                            (let [dv (get node k)]
                                                              (when (and (some? dv)
                                                                         (not= dv (get base k)))
                                                                [k dv]))))
                                                    doc-scalar-fields)
                                       sets   (cond-> sets
                                                (and pt (or (string? pt)
                                                            (not= (second pt)
                                                                  (::parent-id base))))
                                                (assoc :my.plan/parent pt))
                                       rets   (into []
                                                    (keep (fn [[k retractable?]]
                                                            (when (and retractable?
                                                                       (nil? (get node k))
                                                                       (some? (get base k)))
                                                              [:db/retract [:my.plan/id id] k])))
                                                    doc-scalar-fields)
                                       rets   (cond-> rets
                                                (and (nil? pt) (::parent-id base))
                                                (conj [:db/retract [:my.plan/id id]
                                                       :my.plan/parent]))
                                       want   (-> (mapv l->t (:my.plan/after node))
                                                  (into (map (fn [nid] [:my.plan/id nid]))
                                                        (doc-needs-ids node)))
                                       want-ids (into #{} (keep (fn [t] (when (vector? t) (second t))))
                                                      want)
                                       need-ops (concat
                                                  (map (fn [nid] [:db/add [:my.plan/id id]
                                                                  :my.plan/needs [:my.plan/id nid]])
                                                       (remove (::need-ids base) want-ids))
                                                  (map (fn [tid] [:db/add [:my.plan/id id]
                                                                  :my.plan/needs tid])
                                                       (filter string? want))
                                                  (map (fn [nid] [:db/retract [:my.plan/id id]
                                                                  :my.plan/needs [:my.plan/id nid]])
                                                       (remove want-ids (::need-ids base))))
                                       ops    (concat
                                                (when (seq sets) [(assoc sets :my.plan/id id)])
                                                rets
                                                need-ops)]
                                   (when (seq ops) (vec ops))))))
                           entries)
            drops    (mapv (fn [i] [:db.fn/retractEntity [:my.plan/id i]])
                           (remove (set doc-ids) (keys baseline)))
            root-id  (when-let [e (first entries)]
                       (or (::id e) (::new-id e)))]
        (cond-> {::transaction-data (-> (vec news) (into cat updates) (into drops))
                 ::allocation-keys (into [] (keep ::allocation-key) entries)
                 ::labels (into {:root root-id}
                                (keep (fn [e]
                                        (when (::label e)
                                          [(::label e) (or (::id e) (::new-id e))])))
                                entries)
                 ::root-id root-id
                 ::diff {:my.plan/added   (count news)
                         :my.plan/dropped (count drops)
                         :my.plan/updated (count updates)}}
          resolved-root? (assoc ::resolved-root? true))))))

(defn compile-reconcile
  "Diff document `forest` against `agent`'s open tree → ONE flat tx.

   Returns a `:my.plan.internal/*` compiler result carrying transaction data,
   allocation keys, labels, root id, the public `:my.plan/diff`, and
   `::resolved-root?` when an id-less root resolved onto the open root;
   failures carry `:my.plan.internal/error`. Identity rules: a node WITH
   `:my.plan/id` updates in
   place (scalars + parent + needs; STATUS is never edited here — active!/
   done! own it); a node WITHOUT one first passes
   [[resolve-doc-identities]] — an unambiguous match onto the open step it
   re-states updates that step in place, an ambiguous one is an `::error`
   naming the candidates (never a guess-mint) — and only a genuinely new
   node is minted; a baseline open node absent
   from the document is dropped (entity retract; its absent descendants
   drop the same way); a `:done` or foreign id → `:error`. Empty `rows` ⇒
   empty baseline — plan!'s authoring path IS reconcile-against-empty.
   `:after` labels resolve to any node's `:my.plan/ref` (tempid for a
   minted node, lookup-ref for an existing one)."
  [rows fn-name agent forest ids now]
  (let [raw (collect-doc-nodes forest)
        all-by-id (rows-by-id rows)
        owned (rows-for-agent rows agent)
        open-rows (remove #(= :done (:my.plan/status %)) owned)
        baseline
        (into {}
              (map (fn [row]
                     [(row-id row)
                      (-> (select-keys row [:my.plan/title :my.plan/description
                                            :my.plan/expect :my.plan/goal
                                            :my.plan/pace])
                          (assoc ::parent-id (row-parent-id row)
                                 ::need-ids (row-need-ids row)))]))
              open-rows)
        known-status #(some-> (all-by-id %) :my.plan/status)]
    (or
      (some (fn [{::keys [node]}]
              (let [t (:my.plan/title node)]
                (when (or (nil? t) (str/blank? t))
                  {::error (str fn-name ": blank :my.plan/title refused — every "
                                "step names itself.")})))
            raw)
      (when-let [dup (some (fn [[id n]] (when (< 1 n) id))
                           (frequencies (keep ::id raw)))]
        {::error (str fn-name ": " (pr-str dup)
                      " appears twice in the document — one node per step.")})
      (let [res (resolve-doc-identities fn-name raw baseline)]
        (if (::error res)
          res
          (compile-resolved known-status fn-name agent (::entries res)
                            (::resolved-root? res) baseline ids now))))))

(defn compile-plan
  "plan!'s authoring compile — [[compile-reconcile]] with an EMPTY baseline
   (authoring IS reconciling against an empty tree; one code path). Same
   namespaced compiler contract as [[compile-reconcile]]."
  [agent root ids now]
  (compile-reconcile [] "plan!" agent [root] ids now))

;; --- Tree pull (the structural read behind my.plan/tree). -----------------

(def tree-pattern
  "ONE recursive reverse-ref pull: a node + its whole subtree (children
   under `:my.plan/_parent`, the reverse of the plain `parent` ref → a
   vector) + each node's dependency ids inline."
  '[:my.plan/id :my.plan/title :my.plan/status :my.plan/goal :my.plan/expect
    :my.plan/pace :my.plan/description
    {:my.plan/_parent ...}
    {:my.plan/needs [:my.plan/id]}])

;; --- The stuck×N → frontier re-plan ESCALATION (all DERIVED). -------------
;; --- A wedge = the ▶ :active step under which the SAME failure root has
;; --- repeated ≥ N times since the step went :active, with no success of
;; --- that root's own call between. Everything is a query over the eval
;; --- log + plan datoms — flagged IS "the query returns the step now";
;; --- nothing is stored, nothing needs clearing. Three faces, one
;; --- derivation: [[escalation]] (the flag query), [[escalation-section]]
;; --- (the reactive band in [[plan-body]]), [[maybe-consult!]] (the
;; --- once-per-episode planner message, fired post-turn by
;; --- `seon.agent.loop/run-loop!`). Episode identity is the FIRST failing
;; --- eval's id of the live streak — derived from the query's own inputs,
;; --- never a stored notified-flag; the consult message embeds it
;; --- ([[consult-marker]]) so "already consulted" is a message-log read.

(def escalation-stuck-n
  "The policy knob: same-root failures since the ▶ step went :active that
   flag the step for a frontier re-plan (the W3 wedge ran 8+ before this
   existed). [[escalation]] takes an explicit override for tests/tuning."
  3)

(defn head-sym-str
  "The head symbol of `source`'s first form, AS THE AGENT TYPED IT
   (alias-qualified, e.g. \"schema/register!\"), or nil. Structural read
   (`repl-internal/read-forms` — the ONE whole-source read), never a
   regex over the text; a broken source yields nil (fails closed)."
  [source]
  (let [f (first (repl-internal/read-forms (or source "")))]
    (when (and (seq? f) (symbol? (first f)))
      (str (first f)))))

(defn- eval-error-kind
  "The `:seon.error/kind` of a failed eval row, read from its persisted
   `:seon.eval/error-data` ENVELOPE projection (structured EDN via
   `pr-str-readable` — never parsed out of the message string). nil when
   the row carries no envelope data or it doesn't read back."
  [row]
  (when-let [s (:seon.eval/error-data row)]
    (try (:seon.error/kind (edn/read-string s))
         (catch :default _ nil))))

(defn wedge
  "The dominant live failure streak in ordered eval `rows`, or nil.

   A row's ROOT is `[head-sym error-kind]` — the call the agent typed +
   the envelope's kind class. Failures accumulate per root; a SUCCESS of
   the same head sym is progress on that root and resets every streak
   under it (a successful defn-redefine or unrelated form is not — the
   wedge is 'the same broken call keeps failing'). Returns the root with
   the most live failures when it reaches `n`:
   `{::root-sym ::root-kind ::fail-count ::episode ::last-error}` —
   `::episode` = the streak's FIRST failing eval id (stable while the
   streak grows; a broken-then-reformed wedge is a NEW episode)."
  [rows n]
  (let [streaks (reduce
                  (fn [acc {:seon.eval/keys [ok? source] :as row}]
                    (let [h (head-sym-str source)]
                      (cond
                        (nil? h) acc
                        ok?      (into {} (remove (fn [[[rh _] _]] (= rh h))) acc)
                        :else    (update acc [h (eval-error-kind row)]
                                         (fnil conj []) row))))
                  {}
                  rows)
        [[sym kind] fails]
        (->> streaks
             (filter (fn [[_ fs]] (>= (count fs) n)))
             (sort-by (fn [[_ fs]] (- (count fs))))
             first)]
    (when sym
      (cond-> {:my.plan/root-sym   sym
               :my.plan/fail-count (count fails)
               :my.plan/episode    (:seon.eval/id (first fails))
               :my.plan/last-error (or (:seon.eval/error (peek fails)) "")}
        kind (assoc :my.plan/root-kind kind)))))

(defn consult-marker
  "The episode-identity line a consult message embeds — derived from the
   flag query's own inputs (step id + first-fail eval id), so
   [[consult-sent?]] is a message-log read, never a stored flag."
  [step-id episode]
  (str "[escalation :my.plan/step " (pr-str step-id)
       " :my.plan/episode " (pr-str episode) "]"))

(defn- comment-lines
  "String `s` as `; `-prefixed comment lines (the context is eval'able
   Clojure — prose rides `;`)."
  [s]
  (->> (str/split-lines (str s))
       (map #(str ";   " %))
       (str/join "\n")))

(defn- format-escalation-section
  [escalation planner sent?]
  (if-let [{:my.plan/keys [id title root-sym root-kind fail-count last-error]}
           escalation]
    (str "; STUCK ▶ " id " «" title "» — the same call has failed "
         fail-count "× since this step went active (root " root-sym
         (when root-kind (str ", kind " root-kind)) "):\n"
         (comment-lines (tokens/clip-str last-error 60)) "\n"
         (cond
           sent?
           (str "; The planner (" planner ") has been consulted to "
                "revise this subtree; its\n"
                "; revision lands in this plan. Do not re-run the "
                "failing form — work a\n"
                "; different angle or await the revised plan.")

           planner
           (str "; The planner (" planner ") is being consulted to "
                "revise this subtree.")

           :else
           (str "; No frontier planner agent exists in this cluster — "
                "no consult sent.\n"
                "; Revise this subtree yourself "
                "(my.plan/reconcile!) before retrying the form.")))
    ""))

(defn- consult-content
  "The consult message body: the episode marker, the distilled failure
   envelope (once), the flagged subtree document, and the ask — revise
   THIS subtree via reconcile!, planner zone only, then `complete` the
   moment the receipt renders (the W3 turn-economy fix: the ask CARRIES
   its completion condition, so fulfilling it ends the run instead of
   leaking idle turns)."
  [worker-id {:my.plan/keys [id title root-sym root-kind fail-count
                             last-error episode]}
   document]
  (str (consult-marker id episode) "\n"
         "Worker " worker-id " is stuck on plan step " id " «" title
         "» — the same root has failed " fail-count
         "× since the step went :active (root " root-sym
         (when root-kind (str ", kind " root-kind)) "):\n"
         (tokens/clip-str last-error 80) "\n"
         "Flagged subtree (the worker's open document, EDN):\n"
         (tokens/clip-str (pr-str document) 400) "\n"
         "The ask — your zone only: read the worker's full open plan with "
         "(my.plan/document {:seon.agent/id \"" worker-id "\"}); revise "
         "ONLY this subtree — split it, sharpen its expects, reroute — and "
         "write the edited document back with (my.plan/reconcile! "
         "{:my.plan/tree <edited> :seon.agent/id \"" worker-id "\"}). "
         "Optionally send the worker ONE line of guidance: (message/agent "
         "\"" worker-id "\" \"…\"). When the reconcile receipt renders, "
         "the ask is fulfilled — call (complete \"re-planned " id "\") in "
         "that SAME turn; further turns add nothing."))

(declare acquire-plan-block)

(defn ^:async maybe-consult!
  "Fire the once-per-episode planner consult for a flagged agent.

   Called post-turn by the loop. Recomputes [[escalation]] from the
   current db (a transition is 'flagged now AND no consult message for
   this episode yet' — both derived); when it fires, ONE message goes
   from the worker to the derived [[planner-for]] via the existing
   `message!` path (no new channel). Returns a value envelope:
   `{:my.plan/consulted? bool :my.plan/consult-reason kw …}` — reasons
   `:not-flagged` / `:no-planner` / `:already-consulted` / `:sent` /
   `:send-failed`. Never throws."
  [{agent-id :seon.agent/id database ::db/db}]
  (try
    (let [database (or database (await (db/db)))
          acquired (if (:seon.error/message database)
                     database
                     (await (acquire-plan-block
                             {::db/db database :seon.agent/id agent-id})))
          consult (::consult acquired)]
      (cond
        (:seon.error/message acquired)
        (merge acquired
               {:my.plan/consulted? false
                :my.plan/consult-reason :send-failed})

        (nil? consult)
        {:my.plan/consulted? false :my.plan/consult-reason :not-flagged}

        :else
        (let [escalation (::escalation consult)
              planner (::planner consult)]
          (cond
            (nil? planner)
            {:my.plan/consulted? false :my.plan/consult-reason :no-planner}

            (::consult-sent? consult)
            {:my.plan/consulted? false
             :my.plan/consult-reason :already-consulted}

            :else
            (let [env (await (msg/message!
                               {::db/db database
                                :seon.agent.message/content
                                (consult-content agent-id escalation
                                                 (::subtree consult))
                                :seon.agent.message/from
                                [:seon.agent/id agent-id]
                                :seon.agent.message/to
                                [[:seon.agent/id planner]]}))]
              (if-not (:seon.error/message env)
                {:my.plan/consulted?     true
                 :my.plan/consult-reason :sent
                 :my.plan/planner        planner
                 :my.plan/episode        (:my.plan/episode escalation)}
                (merge env {:my.plan/consulted?     false
                            :my.plan/consult-reason :send-failed})))))))
    (catch :default e
      (js/console.error "my.plan.internal/maybe-consult! failed:"
                        (or (.-message e) e))
      {:my.plan/consulted? false :my.plan/consult-reason :send-failed})))

;; --- The WINDOWED plan-block render (`:plan` context section). ------------
;; --- Constant-size for any plan depth: position anchor + open frontier +
;; --- a small recently-completed tail; the completed interior is DROPPED
;; --- from the prompt (it stays queryable — tree/status/db-query read it).

(def open-keys
  "The frontier projection of one unfinished item — `[*]`-pulled then
   trimmed. (Not a pull PATTERN: naming a never-yet-transacted attr there
   throws.)"
  [:my.plan/id :my.plan/title :my.plan/status
   :my.plan/created-at :my.plan/description :my.plan/message])

(def frontier-limit
  "Max ready steps the frontier renders — the constant-size guarantee. The
   overflow renders as one `… and N more ready` line; `(my.plan/next {})`
   reads the full queue."
  7)

(def recent-done-limit
  "How many just-finished steps the resume tail recalls — the bounded
   anti-redo band. COUNT-bounded (not a wall-clock window) so the rendered
   line set is byte-identical until the agent actually closes another step,
   keeping the block cache-stable across renders."
  5)

(def ^:private active-query
  {:find '[?id ?title ?expect ?created ?message ?active-tx]
   :in '[$ ?agent-id]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?step :my.plan/agent ?agent]
            [?step :my.plan/status :active ?active-tx]
            [?step :my.plan/id ?id]
            [?step :my.plan/title ?title]
            [?step :my.plan/created-at ?created]
            [(get-else $ ?step :my.plan/expect "") ?expect]
            [(get-else $ ?step :my.plan/message false) ?message]]
   :order-by '[?created :asc ?id :asc]
   :limit (inc frontier-limit)})

(def ^:private ready-query
  {:find '[?id ?title ?expect ?created ?message]
   :in '[$ % ?agent-id]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?step :my.plan/agent ?agent]
            (ready ?step)
            [?step :my.plan/id ?id]
            [?step :my.plan/title ?title]
            [?step :my.plan/created-at ?created]
            [(get-else $ ?step :my.plan/expect "") ?expect]
            [(get-else $ ?step :my.plan/message false) ?message]]
   :order-by '[?created :asc ?id :asc]
   :limit (inc frontier-limit)})

(def ^:private recent-done-query
  {:find '[?id ?title ?completed]
   :in '[$ ?agent-id]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?step :my.plan/agent ?agent]
            [?step :my.plan/status :done]
            [?step :my.plan/id ?id]
            [?step :my.plan/title ?title]
            [?step :my.plan/completed-at ?completed]]
   :order-by '[?completed :desc ?id :asc]
   :limit recent-done-limit})

(def ^:private run-cause-step-query
  {:find '[?id ?title ?expect ?created ?message ?status ?status-tx]
   :in '[$ ?agent-id ?run-id]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?run :seon.agent.run/id ?run-id]
            [?run :seon.agent.run/cause ?message]
            [?step :my.plan/agent ?agent]
            [?step :my.plan/message ?message]
            [?step :my.plan/status ?status ?status-tx]
            [?step :my.plan/id ?id]
            [?step :my.plan/title ?title]
            [?step :my.plan/created-at ?created]
            [(get-else $ ?step :my.plan/expect "") ?expect]]
   :limit 1})

(def ^:private ancestor-selector
  '[:my.plan/id :my.plan/title :my.plan/goal :my.plan/pace
    {:my.plan/parent ...}])

(def ^:private root-rollup-query
  '[:find ?status (count ?leaf)
    :in $ % ?selected-id
    :where
    [?selected :my.plan/id ?selected-id]
    [?root :my.plan/id _]
    (or-join [?root ?selected]
      [(= ?root ?selected)]
      (descendant ?root ?selected))
    (not-join [?root] [?root :my.plan/parent _])
    [?leaf :my.plan/status ?status]
    (or-join [?leaf ?root]
      [(= ?leaf ?root)]
      (descendant ?root ?leaf))
    (leaf ?leaf)])

(def ^:private eval-projections-query
  {:find '[?eval
           (pull ?eval [:seon.eval/id :seon.eval/ok? :seon.eval/source
                        :seon.eval/error :seon.eval/error-data])
           ?eval-tx]
   :in '[$ ?agent-id ?active-tx]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?eval :seon.eval/agent ?agent]
            [?eval :seon.eval/ok? _ ?eval-tx]
            [(> ?eval-tx ?active-tx)]]
   :order-by '[?eval-tx :asc ?eval :asc]})

(def ^:private planner-candidates-query
  {:find '[?id ?provider]
   :in '[$ ?worker-id]
   :where '[[?agent :seon.agent/id ?id]
            [?agent :seon.eval/home-requires _]
            [(not= ?id ?worker-id)]
            (not-join [?agent] [?agent :seon.agent/terminated-at _])
            [(get-else $ ?agent :seon.ai/agent-provider :inherit) ?provider]]
   :order-by '[?id :asc]})

(def ^:private planner-authors-query
  '[:find [?author-id ...]
    :in $ ?step-id
    :where
    [?step :my.plan/id ?step-id]
    [?step _ _ ?tx]
    [?tx :seon.db/user ?author]
    [?author :seon.agent/id ?author-id]])

(def ^:private consult-message-query
  {:find '[?message]
   :in '[$ ?worker-id ?marker]
   :where '[[?worker :seon.agent/id ?worker-id]
            [?message :seon.agent.message/from ?worker]
            [?message :seon.agent.message/content ?content]
            [(clojure.string/includes? ?content ?marker)]]
   :limit 1})

(defn- query-member
  [query arguments max-work max-results max-result-weight]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query
   ::protocol/arguments arguments
   :datahike.resource/max-work max-work
   :datahike.resource/max-results max-results
   :datahike.resource/max-result-weight max-result-weight})

(defn- initial-acquisition-members
  [agent-id run-id]
  (cond->
    [(query-member active-query [agent-id]
                   2000000 2000000 65536)
     (query-member ready-query [rules agent-id]
                   5000000 5000000 131072)
     (query-member recent-done-query [agent-id]
                   2000000 2000000 65536)
     {::protocol/operation protocol/pull-operation
      ::protocol/selector [:db/id :seon.agent/id]
      ::protocol/entity-id [:seon.agent/id agent-id]
      :datahike.resource/max-work 10000
      :datahike.resource/max-results 8
      :datahike.resource/max-result-weight 1024}]
    run-id
    (conj (query-member run-cause-step-query [agent-id run-id]
                        100000 100000 4096))))

(defn- selected-acquisition-members
  ([step-id] (selected-acquisition-members step-id nil nil))
  ([step-id agent-id active-tx]
   (cond->
     [{::protocol/operation protocol/pull-operation
       ::protocol/selector ancestor-selector
       ::protocol/entity-id [:my.plan/id step-id]
       :datahike.resource/max-work 100000
       :datahike.resource/max-results 2048
       :datahike.resource/max-result-weight 65536}
      (query-member root-rollup-query [rules step-id]
                    5000000 5000000 4096)]
     active-tx
     (conj (query-member eval-projections-query [agent-id active-tx]
                         5000000 5000000 2097152)))))

(defn- escalation-acquisition-members
  [worker-id step-id marker]
  [(query-member planner-candidates-query [worker-id]
                 500000 100000 262144)
   {::protocol/operation protocol/pull-operation
    ::protocol/selector [:seon.ai/provider]
    ::protocol/entity-id [:seon.ai/id "config"]
    :datahike.resource/max-work 10000
    :datahike.resource/max-results 8
    :datahike.resource/max-result-weight 1024}
   (query-member planner-authors-query [step-id]
                 500000 100000 65536)
   (query-member consult-message-query [worker-id marker]
                 1000000 1000000 4096)
   {::protocol/operation protocol/pull-operation
    ::protocol/selector tree-pattern
    ::protocol/entity-id [:my.plan/id step-id]
    :datahike.resource/max-work 5000000
    :datahike.resource/max-results 200000
    :datahike.resource/max-result-weight 1048576}])

(defn- member-result
  [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- acquisition-error
  [stage value]
  {:seon.error/message (str "Plan " stage " failed.")
   :seon.error/data value
   :seon.error/kind :core-bug})

(defn- step-row
  [[id title expect created message & [active-tx]]]
  (cond-> {:my.plan/id id
           :my.plan/title title
           :my.plan/created-at created}
    (seq expect) (assoc :my.plan/expect expect)
    message (assoc :my.plan/message message)
    active-tx (assoc :seon.db/tx active-tx)))

(defn- run-cause-step-row
  [[id title expect created message status status-tx]]
  (cond-> (assoc (step-row [id title expect created message])
                 :my.plan/status status)
    (= :active status) (assoc :seon.db/tx status-tx)))

(defn- ancestor-chain-from-pull
  [selected]
  (loop [node selected chain []]
    (if-not node
      (vec (reverse chain))
      (recur (:my.plan/parent node)
             (conj chain (dissoc node :my.plan/parent))))))

(defn- rollup-from-rows
  [rows]
  (let [counts (into {} rows)
        total (reduce + 0 (vals counts))
        done (get counts :done 0)]
    {:my.plan/done done
     :my.plan/total total
     :my.plan/done? (and (pos? total) (= done total))}))

(defn- planner-from-rows
  [worker-id candidate-rows global-provider author-ids]
  (let [global-provider (or global-provider :deepseek)
        authors (set author-ids)
        candidates
        (->> candidate-rows
             (keep (fn [[id raw-provider]]
                     (let [override
                           (db/decode-edn-value :seon.ai/agent-provider
                                                raw-provider)
                           provider (if (= :inherit override)
                                      global-provider
                                      override)]
                       (when (and (not= worker-id id)
                                  (ai/frontier-provider? provider))
                         id))))
             vec)]
    (or (first (filter authors candidates))
        (first candidates))))

(defn ^:async ^:private acquire-escalation-text
  [database agent-id active eval-member]
  (if-not active
    {::escalation-text ""}
    (let [evals (mapv second (member-result eval-member))]
      (if-let [wedge-state (wedge evals escalation-stuck-n)]
        (let [escalation (merge wedge-state
                                {:my.plan/id (:my.plan/id active)
                                 :my.plan/title (:my.plan/title active)})
              marker (consult-marker (:my.plan/id escalation)
                                     (:my.plan/episode escalation))
              acquired
              (await (db/execute-many
                       {::db/db database
                        ::db/members
                        (escalation-acquisition-members
                          agent-id (:my.plan/id escalation) marker)
                        ::db/max-result-weight 393216}))]
          (if (:seon.error/message acquired)
            (acquisition-error "escalation read" acquired)
            (let [[candidates-member config-member authors-member
                   message-member subtree-member] (::db/results acquired)]
              (if-not (every? #(true? (::protocol/success? %))
                              [candidates-member config-member authors-member
                               message-member subtree-member])
                (acquisition-error "escalation member"
                                   (::db/results acquired))
                (let [global-provider
                      (:seon.ai/provider (member-result config-member))
                      planner
                      (planner-from-rows
                        agent-id
                        (member-result candidates-member)
                        global-provider
                        (member-result authors-member))
                      sent? (boolean
                              (and planner
                                   (seq (member-result message-member))))]
                  {::escalation-text
                   (format-escalation-section escalation planner sent?)
                   ::escalation escalation
                   ::planner planner
                   ::consult-sent? sent?
                   ::subtree (prune-done (member-result subtree-member))})))))
        {::escalation-text ""}))))

(defn ^:async ^:private acquire-plan-block
  "Acquire the bounded plan prompt data at one immutable database value."
  [{agent-id :seon.agent/id run-id :seon.agent.run/id :as input}]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))]
    (if (:seon.error/message database)
      (acquisition-error "database acquisition" database)
      (let [initial (await (db/execute-many
                             {::db/db database
                              ::db/members
                              (initial-acquisition-members agent-id run-id)
                              ::db/max-result-weight 262144}))]
        (if (:seon.error/message initial)
          (acquisition-error "initial read" initial)
          (let [[active-member ready-member done-member agent-member
                 cause-step-member]
                (::db/results initial)]
            (if-not (every? #(true? (::protocol/success? %))
                            (cond-> [active-member ready-member done-member
                                     agent-member]
                              cause-step-member (conj cause-step-member)))
              (acquisition-error "initial member" (::db/results initial))
              (let [agent (member-result agent-member)
                    actives (mapv step-row (member-result active-member))
                    readies (mapv step-row (member-result ready-member))
                    dones (mapv (fn [[id title completed-at]]
                                  {:my.plan/id id
                                   :my.plan/title title
                                   :my.plan/completed-at completed-at})
                                (member-result done-member))
                    active (first actives)
                    cause-step (some-> cause-step-member member-result first
                                       run-cause-step-row)
                    step (or cause-step active (first readies))
                    selected-active? (contains? step :seon.db/tx)]
                (if-not step
                  {::db/db database
                   :seon.agent/entity agent
                   ::anchor nil
                   ::actives actives
                   ::readies readies
                   ::dones dones}
                  (let [selected
                        (await (db/execute-many
                                  {::db/db database
                                   ::db/members
                                   (selected-acquisition-members
                                    (:my.plan/id step)
                                    agent-id
                                    (:seon.db/tx step))
                                  ::db/max-result-weight
                                  (if selected-active? 2359296 131072)}))]
                    (if (:seon.error/message selected)
                      (acquisition-error "selected read" selected)
                      (let [[ancestor-member rollup-member eval-member]
                            (::db/results selected)]
                        (if-not (every?
                                  #(true? (::protocol/success? %))
                                  (cond-> [ancestor-member rollup-member]
                                    selected-active? (conj eval-member)))
                          (acquisition-error "selected member"
                                             (::db/results selected))
                          (let [escalation
                                (await (acquire-escalation-text
                                         database agent-id
                                         (when selected-active? step)
                                         eval-member))]
                            (if (:seon.error/message escalation)
                              escalation
                              {::db/db database
                               :seon.agent/entity agent
                               ::anchor
                               {:my.plan/step step
                                :my.plan/chain
                                (ancestor-chain-from-pull
                                  (member-result ancestor-member))
                                :my.plan/active? selected-active?
                                :my.plan/progress
                                (rollup-from-rows
                                  (member-result rollup-member))}
                               ::actives actives
                               ::readies readies
                               ::dones dones
                               ::escalation-text (::escalation-text escalation)
                               ::consult (when (::escalation escalation)
                                           escalation)})))))))))))))))

(defn stamp
  "Compact ABSOLUTE creation time of `at` — UTC `YYYY-MM-DD HH:MM`, derived
   only from the datom (NOT `now`), so a row renders byte-identical every
   turn while the agent still reads recency (it compares against the turn
   clock). A relative \"3m ago\" string would change on every render and
   bust the stable-prefix cache for an unchanged block."
  [at]
  (-> (.toISOString ^js at) (subs 0 16) (str/replace "T" " ")))

(defn anchor-section
  "The position-anchor lines for [[anchor]] map `a` — \"\" when nil.

   Line 1 names the goal (root title, `goal:` narrative + pace when
   present); line 2 is the you-are-here: the active, next-ready, or completed
   current-run step with the root's done/total roll-up. A completed current
   request tells the agent to close the run or deliberately select more work;
   a `verify before done!` line follows an unfinished step with an expectation."
  [a]
  (if (nil? a)
    ""
    (let [{:my.plan/keys [step chain active? progress]} a
          root   (first chain)
          {:my.plan/keys [done total]} progress
          goal   (:my.plan/goal root)
          pace   (:my.plan/pace root)
          expect (:my.plan/expect step)
          completed? (= :done (:my.plan/status step))]
      (str "; PLAN «" (:my.plan/title root) "»"
           (when goal (str " — goal: " goal))
           (when pace (str " [" (name pace) "]")) "\n"
           "; → " (cond completed? "CURRENT REQUEST COMPLETED"
                       active? "NOW (active)"
                       :else "next ready") ": "
           (:my.plan/id step) " «" (:my.plan/title step) "» — "
           done " of " total " steps done"
           (if completed?
             (str "\n;   close this run now with (complete \"<result>\"); "
                  "continue only by deliberately selecting another step.")
             (when expect (str "\n;   verify before done!: " expect)))))))

(defn frontier-section
  "The open-frontier lines: `actives` (`▶` — the step you are on) then up
   to [[frontier-limit]] `readies` (`☐` — open), one
   `; <glyph> <id> [<created-at>] <title>` line each (a `✉` marks a step
   auto-minted from your human's message) — or \"\" when both are empty.
   DONE steps never render here (the ▶/☐/done-dropped compactness
   contract, absorbed from the retired `:plan-ledger` block 2026-07-11);
   the recently-completed `✓` band is the only done recall."
  [actives readies]
  (if (and (empty? actives) (empty? readies))
    ""
    (let [shown (take frontier-limit readies)
          more  (- (count readies) (count shown))
          line  (fn [marker {:my.plan/keys [id title created-at message]}]
                  (str "; " marker (when message "✉ ") id
                       (when created-at (str " [" (stamp created-at) "]"))
                       " " title))]
      (str "; Open frontier (▶ = the step you are on, ☐ = open) — close each\n"
           "; step with (my.plan/done! {:my.plan/id \"<id>\"})\n"
           "; the MOMENT its work lands (never batch closes at the end);\n"
           "; take one up with (my.plan/active! {:my.plan/id \"<id>\"}); add a\n"
           "; DISCOVERED step UNDER this plan (never a new parentless root):\n"
           "; (my.plan/step! {:my.plan/title \"…\" :my.plan/parent [:my.plan/id \"<an id here>\"]})\n"
           (str/join "\n"
                     (concat (map #(line "▶ " %) actives)
                             (map #(line "☐ " %) shown)))
           (when (pos? more)
             (str "\n; … and " more " more ready — (my.plan/next {}) lists them all."))))))

(defn done-section
  "The `; ✓ [<completed-at>] <title>` lines for already-finished `dones`
   (newest first) — a recall band so you don't re-do setup you've already
   completed. \"\" when nothing's been finished."
  [dones]
  (if (empty? dones)
    ""
    (str "; Recently completed — already done, do NOT redo:\n"
         (str/join "\n"
                   (map (fn [{:my.plan/keys [title completed-at]}]
                          (str "; ✓ [" (stamp completed-at) "] " title))
                        dones)))))

(def empty-plan-teaching
  "The `:plan` block's OWN teaching for the no-plan-yet state.

   Colocation (owner directive 2026-07-10): the empty state is exactly
   when decompose-first must be taught — once a plan exists the anchor +
   frontier lines carry the workflow themselves, and this header is
   absent. Byte-stable (cache-safe)."
  (str "; ── plan ── (empty)\n"
       "; Multi-step work: decompose FIRST, before starting the work —\n"
       ";   (my.plan/plan! {:my.plan/title \"…\" :my.plan/goal \"…\"\n"
       ";                   :my.plan/children [{:my.plan/title \"step 1\"} …]})\n"
       "; mints the whole plan in one call; it renders here and survives\n"
       "; restarts. Close each step the MOMENT its work lands\n"
       "; ((my.plan/done! {:my.plan/id \"<id>\"})); add a discovered step\n"
       "; UNDER the plan: (my.plan/step! {:my.plan/title \"…\"\n"
       ";                                 :my.plan/parent [:my.plan/id \"<id>\"]})."))

(defn- format-plan-body
  [{::keys [anchor actives readies dones escalation-text]}]
  (let [body (str/join "\n" (remove str/blank?
                                      [(anchor-section anchor)
                                       escalation-text
                                       (frontier-section actives readies)
                                       (done-section dones)]))]
    (if (str/blank? body) empty-plan-teaching body)))

(defn ^:async plan-block
  "Acquire and render the calling agent's bounded plan from one database
   value. An agent with no plan data gets [[empty-plan-teaching]];
   everything else is derived and nothing is acknowledged or stored."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (let [acquired (await (acquire-plan-block input))]
    (if-let [error (:seon.error/message acquired)]
      (str "[plan] render failed: " (pr-str acquired))
      (format-plan-body acquired))))

;; --- The `:seon.render/html` twin — the human's live plan surface. --------
;; --- Colocated with [[plan-block]] (the transcript precedent). Zero prompt
;; --- cost: `*.internal` nses never render into agent context
;; --- (seon.agent.ctx.namespaces/hidden-ns-name?). Where the :ai block
;; --- Windows (anchor + capped frontier + recent-done tail), the surface shows
;; --- the WHOLE forest — the human explores what the prompt windows away.
;; ---
;; --- STRUCTURE vs SIGNAL (2026-07-11): [[build-forest]] assembles ONLY the
;; --- renderable nested TREE (parent→children layout, waiters from the
;; --- inverted `needs` edges, timestamps + message-origin, oldest-first) —
;; --- a projection the windowing :ai block never needs and the flat shared
;; --- fns cannot give (they answer counts/positions, not a tree). Every
;; --- derived SIGNAL — roll-up, done-ness, ready, blocked, the you-are-here
;; --- position — comes from the SAME shared db fns the :ai block uses
;; --- ([[rollup]] / [[ready?]] / [[blocked?]] / [[anchor]]), ONE derivation
;; --- mechanism over the fixed recursive `descendant` rule. (Until the
;; --- datahike-CLJS recursive-rule engine fix — fork 1598a824 — those rules
;; --- yielded nothing past depth 1, so the surface carried a parallel pure-walk
;; --- re-derivation; that workaround is now deleted and the surface agrees with
;; --- the :ai block on every tree by construction.) Plain pulled data only
;; --- (no lazy Entity walk): a `my.*` render symbol is SCI-re-interpreted
;; --- from its stored source at render time, and the plain-data primitives
;; --- are the SCI-proven path ([[plan-body]] uses the same ones).
;; ---
;; --- Interactivity rides Datastar SIGNALS (the client-side signal store
;; --- survives the SSE whole-element morph; `__ifmissing` keeps a re-morph
;; --- from resetting them): $planstep = the one expanded step id,
;; --- $planclosed = collapsed subtree ids (space-delimited), $planfull =
;; --- reveal the completed interior, $plandone = the recent-done list.

(defn- toggle-step-expr
  "The `data-on:click` expression toggling step `id`'s detail panel."
  [id]
  (str "$planstep = ($planstep === '" id "' ? '' : '" id "')"))

(defn- toggle-closed-expr
  "The `data-on:click` expression toggling subtree `id`'s collapse."
  [id]
  (str "$planclosed = ($planclosed.includes(' " id " ') ? "
       "$planclosed.replace(' " id " ', ' ') : $planclosed + ' " id " ')"))

(defn- step-order
  "Rows/nodes sorted oldest-first (created-at, then id — stable)."
  [rows]
  (sort-by (fn [r] [(or (some-> (:my.plan/created-at r) .getTime) 0)
                    (:my.plan/id r)])
           rows))

(def ^:private html-plan-selector
  [:db/id :my.plan/id :my.plan/title :my.plan/status :my.plan/goal
   :my.plan/pace :my.plan/expect :my.plan/description :my.plan/created-at
   :my.plan/completed-at :my.plan/message
   {:my.plan/agent [:seon.agent/id]}
   {:my.plan/parent [:my.plan/id]}
   {:my.plan/needs [:my.plan/id]}])

(defn- ^:async acquire-html-plan-rows
  [database agent-id]
  (await
   (db/query
    {::db/db database
     ::db/query
     '[:find [(pull ?step ?selector) ...]
       :in $ ?selector ?agent-id
       :where
       [?agent :seon.agent/id ?agent-id]
       [?step :my.plan/agent ?agent]]
     ::db/args [html-plan-selector agent-id]
     ::db/max-work 5000000
     ::db/max-results 200000
     ::db/max-result-weight 2097152})))

(defn- row->node
  "Plan row `r` to one walked node map with caller-supplied children."
  [by-id waiters r children]
  (let [needs (->> (:my.plan/needs r)
                   (keep (fn [need] (by-id (:my.plan/id need))))
                   (mapv (fn [nr] {:my.plan/id    (:my.plan/id nr)
                                   :my.plan/title (:my.plan/title nr)})))]
    (cond-> {:my.plan/id       (:my.plan/id r)
             :my.plan/title    (:my.plan/title r)
             :my.plan/status   (:my.plan/status r)
             :my.plan/children children}
      (:my.plan/goal r)         (assoc :my.plan/goal (:my.plan/goal r))
      (:my.plan/pace r)         (assoc :my.plan/pace (:my.plan/pace r))
      (:my.plan/expect r)       (assoc :my.plan/expect (:my.plan/expect r))
      (:my.plan/description r)  (assoc :my.plan/description
                                       (:my.plan/description r))
      (:my.plan/message r)      (assoc :my.plan/message? true)
      (:my.plan/created-at r)   (assoc :my.plan/created-at
                                       (:my.plan/created-at r))
      (:my.plan/completed-at r) (assoc :my.plan/completed-at
                                       (:my.plan/completed-at r))
      (seq needs)               (assoc :my.plan/needs needs)
      (seq (waiters (:my.plan/id r))) (assoc :my.plan/waiters
                                             (waiters (:my.plan/id r))))))

(defn- build-forest
  "Build one cycle-safe, oldest-first forest from ordinary plan rows."
  [rows]
  (let [by-id (rows-by-id rows)
        kids (group-by row-parent-id rows)
        waiters  (reduce (fn [m r]
                           (reduce (fn [m need]
                                     (update m (:my.plan/id need) (fnil conj [])
                                             (:my.plan/title r)))
                                   m (:my.plan/needs r)))
                         {} rows)
        node     (fn node [r seen]
                   (let [seen (conj seen (:my.plan/id r))
                         children
                         (->> (kids (:db/id r))
                              step-order
                              (remove #(seen (:my.plan/id %)))
                              (mapv #(node % seen)))]
                     (row->node by-id waiters r children)))]
    (->> rows
         (remove row-parent-id)
         step-order
         (mapv #(node % #{})))))

;; --- Per-node SIGNALS delegate to the shared rule-backed db fns
;; --- ([[rollup]] / [[ready?]] / [[blocked?]]) — the SAME derivations the
;; --- :ai block uses. `db` is the frozen render db value; a node carries its
;; --- `:my.plan/id` so every signal is one shared-fn call keyed by that id.
;; --- (Roll-up self-includes a leaf, so `(:my.plan/done? (rollup db id))` is
;; --- the done-ness of BOTH a leaf and a drained non-leaf — one call.)

(defn- need-line-html
  "One `waits on` line for need `n` — done-glyph + title + id (done-ness of
   the target derived via the shared [[rollup]] over db value `db`)."
  [rows n]
  (let [done? (:my.plan/done? (plan-rollup-from-rows rows (:my.plan/id n)))]
    [:li {:class "flex items-center gap-1"}
     [:span {:class (str "shrink-0 " (if done? "text-success" "text-warning"))}
      (if done? "✓" "○")]
     [:span {:class "text-text-200 truncate"} (:my.plan/title n)]
     [:span {:class "text-text-500 shrink-0"} (:my.plan/id n)]]))

(defn- step-detail-html
  "Walked `node`'s expand-in-place detail panel — `data-show`n by $planstep."
  [rows node]
  (let [id  (:my.plan/id node)
        row (fn [label body]
              [:div {:class "flex gap-2"}
               [:span {:class "text-text-500 shrink-0"} label]
               [:div {:class "text-text-200 min-w-0"} body]])]
    [:div {:data-show (str "$planstep === '" id "'")
           :style "display:none;border-left:1px solid #3d3a36;margin-left:3px;padding-left:10px"
           :class "flex flex-col gap-1 text-2xs py-1"}
     [:div {:class "text-text-500"}
      (str id
           (when-let [c (:my.plan/created-at node)]
             (str " · created " (stamp c)))
           (when-let [d (:my.plan/completed-at node)]
             (str " · done " (stamp d))))]
     (when-let [g (:my.plan/goal node)] (row "goal" g))
     (when-let [d (:my.plan/description node)] (row "desc" d))
     (when-let [x (:my.plan/expect node)] (row "verify" x))
     (when-let [needs (seq (:my.plan/needs node))]
       (row "waits on"
            (into [:ul {:class "flex flex-col gap-1"}]
                  (map #(need-line-html rows %))
                  needs)))
     (when-let [ws (seq (:my.plan/waiters node))]
       (row "blocks" (str/join ", " ws)))
     (when (:my.plan/message? node)
       (row "origin" "✉ auto-minted from a message"))]))

(defn- step-row-html
  "One tree row (+ its detail panel + its children `ul`) for walked `node`.

   Glyphs: `●` active (amber, NOW), `✓` done (dim; hidden until $planfull),
   `○` open (`ready` tag when actionable), `◌` blocked. A non-leaf carries
   its subtree `done/total` roll-up and a collapse chevron ($planclosed,
   click-stopped so it doesn't also toggle the detail). Signals delegate to
   the shared [[rollup]]/[[ready?]]/[[blocked?]] over db value `db`."
  [rows node next-id depth]
  (let [id       (:my.plan/id node)
        children (:my.plan/children node)
        leaf?    (empty? children)
        ru       (plan-rollup-from-rows rows id)
        done?    (:my.plan/done? ru)
        active?  (= :active (:my.plan/status node))
        next?    (= id next-id)
        blocked? (and (not done?) (blocked-from-rows? rows id))
        [glyph gcls] (cond
                       active?  ["●" "text-signal"]
                       done?    ["✓" "text-text-500"]
                       blocked? ["◌" "text-warning"]
                       :else    ["○" "text-text-400"])
        tcls     (cond
                   active?  "text-text-50"
                   done?    "text-text-500"
                   blocked? "text-text-400"
                   :else    "text-text-200")]
    [:li (cond-> {:class "flex flex-col"}
           done? (assoc :data-show "$planfull" :style "display:none"))
     [:div {:class (str "flex items-center gap-2 py-0.5 rounded cursor-pointer"
                        (when active? " bg-base-850"))
            :style (str "padding-left:" (* 12 depth) "px;box-sizing:border-box;"
                        "border-left:2px solid "
                        (if active? "#f0b429" "transparent"))
            (keyword "data-on:click") (toggle-step-expr id)}
      (if leaf?
        [:span {:class "shrink-0" :style "display:inline-block;width:1ch"}]
        [:span {:class "text-text-500 shrink-0 select-none"
                (keyword "data-on:click__stop") (toggle-closed-expr id)
                :data-text (str "$planclosed.includes(' " id " ') ? '▸' : '▾'")}
         "▾"])
      [:span {:class (str "shrink-0 " gcls)} glyph]
      (when (:my.plan/message? node) [:span {:class "text-info shrink-0"} "✉"])
      [:span {:class (str "truncate " tcls)} (:my.plan/title node)]
      (when active? [:span {:class "text-2xs text-signal shrink-0"} "NOW"])
      (when (and next? (not active?))
        [:span {:class "text-2xs text-signal shrink-0"} "next"])
      (when (and (not done?) (not active?) (not next?)
                 (ready-from-rows? rows id))
        [:span {:class "text-2xs text-success shrink-0"} "ready"])
      (when-not leaf?
        [:span {:class "text-2xs text-text-500 tabular-nums shrink-0"}
         (str (:my.plan/done ru) "/" (:my.plan/total ru))])]
     (step-detail-html rows node)
     (when-not leaf?
       (into
        [:ul {:class "flex flex-col"
              :data-show (str "!$planclosed.includes(' " id " ')")}]
        (map #(step-row-html rows % next-id (inc depth)) children)))]))

(defn- root-card-html
  "One bounded, collapsible plan-root card."
  [rows root next-id focused-root-id]
  (let [{:my.plan/keys [done total]}
        (plan-rollup-from-rows rows (:my.plan/id root))
        pct  (if (pos? total) (quot (* 100 done) total) 0)
        goal (:my.plan/goal root)
        pace (:my.plan/pace root)]
    [:details (cond-> {:class (str "plan-root rounded border border-base-800 "
                                   "bg-base-950/30 overflow-hidden")}
                (= (:my.plan/id root) focused-root-id) (assoc :open true))
     [:summary {:class (str "cursor-pointer px-2 py-1.5 min-w-0 "
                            "hover:bg-base-900")}
      [:div {:class "plan-root-heading"}
       [:span {:class "plan-title text-xs font-semibold text-text-50"}
        (:my.plan/title root)]
       [:span {:class "text-2xs text-text-400 tabular-nums shrink-0"}
        (str done "/" total " done")]]
      [:div {:class "flex items-center gap-2 min-w-0"}
       (when pace
         [:span {:class "text-2xs text-text-500 shrink-0"}
          (str "[" (name pace) "]")])
       (when goal
         [:span {:class "plan-goal text-2xs text-text-500"} goal])]
      [:div {:class "bg-base-800 w-full mt-1"
             :style "height:2px;border-radius:2px;overflow:hidden"}
       [:div {:style (str "height:2px;background:#f0b429;width:" pct "%")}]]]
     [:div {:class "plan-tree px-2 py-1 border-t border-base-800"}
      (into
       [:ul {:class "flex flex-col"}]
       (map #(step-row-html rows % next-id 0) (:my.plan/children root)))]]))

(defn ^:async plan-block-html
  "Live, explorable HTML twin of [[plan-block]] — a `/agent/{id}` surface.

   Renders the agent's WHOLE plan forest behind bounded root disclosures (the
   :ai block windows by content). The focused root starts open; other roots are
   summary rows until selected. Each root carries a title/goal/pace header with
   a done/total roll-up and a thin amber progress bar, then the step tree — `●` active (NOW,
   highlighted), `○` open (`ready`-tagged), `◌` blocked, `✓` done (hidden
   until the show-completed toggle), `✉` message-minted. STRUCTURE comes
   from [[build-forest]] (the renderable nested tree); every SIGNAL —
   roll-up, done, ready, blocked, and the you-are-here position — derives
   from the SAME shared db fns the :ai block uses ([[rollup]]/[[ready?]]/
   [[blocked?]]/[[anchor]], see the derivation note above); the
   recently-completed tail reuses [[recent-done]]. Interactivity is
   Datastar signals (they live
   client-side, so they survive the SSE whole-element morph): click a
   step to expand its detail in place (description, verify-before-done
   `expect`, needs edges both ways, timestamps, id); chevrons collapse
   subtrees; $planfull reveals the completed interior; the recently-
   completed tail expands on click. No plan → a quiet one-liner (the
   teaching text in [[empty-plan-teaching]] is for the model, not the
   human)."
  {:malli/schema [:=> [:cat :seon.render/section-request :any]
                  :seon.render.canvas/hiccup]}
  [{database :seon.db/db agent-id :seon.agent/id} _invoke-selected!]
  (let [database (or database (await (db/db)))
        rows (if (:seon.error/message database)
               database
               (await (acquire-html-plan-rows database agent-id)))]
    (if (:seon.error/message rows)
      [:div {:class "text-danger text-2xs font-mono py-1"}
       "plan unavailable"]
      (let [forest (build-forest rows)]
        (if (empty? forest)
      [:div {:class "text-text-500 italic text-2xs font-mono py-1"}
       "no plan yet"]
      (let [a (anchor-from-rows rows)
            focused-root-id (some-> a :my.plan/chain first :my.plan/id)
            next-id    (when-not (:my.plan/active? a)
                         (:my.plan/id (:my.plan/step a)))
            done-count
            (count (filter #(and (row-parent-id %)
                                 (:my.plan/done?
                                  (plan-rollup-from-rows rows (row-id %))))
                           rows))
            dones (->> rows
                       (filter #(= :done (:my.plan/status %)))
                       (filter :my.plan/completed-at)
                       (sort-by #(-> % :my.plan/completed-at .getTime) >)
                       (take recent-done-limit)
                       vec)]
        (cond->
         (into
          [:div {:class "flex flex-col gap-2 text-xs font-mono"
                 :data-signals__ifmissing
                 "{planstep: '', planclosed: '', planfull: false, plandone: false}"}]
          (map #(root-card-html rows % next-id focused-root-id) forest))
         (pos? done-count)
         (conj
           [:div {:class (str "flex items-center gap-1 text-2xs text-text-400 "
                              "cursor-pointer select-none")
                  (keyword "data-on:click") "$planfull = !$planfull"}
            [:span {:data-text "$planfull ? '▾' : '▸'"} "▸"]
            [:span {:data-text (str "$planfull ? 'hide completed steps' : "
                                    "'show all " done-count
                                    " completed steps in place'")}
             (str "show all " done-count " completed steps in place")]])
         (seq dones)
         (conj
           [:div {:class "flex flex-col"}
            [:div {:class (str "flex items-center gap-1 text-2xs text-text-500 "
                               "cursor-pointer select-none")
                   (keyword "data-on:click") "$plandone = !$plandone"}
             [:span {:data-text "$plandone ? '▾' : '▸'"} "▸"]
             [:span (str "recently completed (" (count dones) ")")]]
            (into
             [:ul {:class "flex flex-col" :data-show "$plandone"
                   :style "display:none"}]
             (map (fn [{:my.plan/keys [title completed-at]}]
                    [:li {:class "text-2xs text-text-500 truncate"}
                     (str "✓ [" (stamp completed-at) "] " title)])
                  dones))]))))))))
