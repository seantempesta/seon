(ns seon.render.tail
  "General data-driven ephemeral tail renderers evaluated at the end of an agent's wire context on every turn.
   Configured purely via `:seon.agent/tail-renders` on any agent record (zero `if root` hardcoding!):
   - `branch-status`: changed vars, live `:main` drift/conflicts, branch health errors (incl. `:missing-contract`), and latest fast proofs.
   - `scoped-health`: health errors & advisories tailored to `:seon.agent/scope` (0 tokens when healthy).
   - `system-health`: full system-wide health roll-up with owning specialists (placed on coordinator records like `:agent/root`).
   - `neighborhood-activity`: open incoming/outgoing calls & recent live data matching working schemas.
   - `live-viewport`: ephemeral WKWebView screenshot gated on `:seon.config/viewport-screenshot?`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render :as render]
            [seon.render.data :as rdata :refer [compact-entity-preview first-doc-line router-agent?]]
            [seon.store :as store]))

(def default-tail-renders
  "Default composable ephemeral tail renderers for any agent record without explicit `:seon.agent/tail-renders`."
  ['seon.render.tail/branch-status
   'seon.render.tail/scoped-health
   'seon.render.tail/neighborhood-activity
   'seon.render.tail/live-viewport])

(defn- form-hash-map [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/key) (contains? (:schema db) :seon.code.form/hash))
    {}
    (let [keys-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
      (reduce (fn [a dt]
                (if-let [k (get keys-by-e (:e dt))]
                  (assoc a k (:v dt))
                  a))
              {}
              (d/datoms db :aevt :seon.code.form/hash)))))

(defn- form-tx-map [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/key) (contains? (:schema db) :seon.code.form/hash))
    {}
    (let [keys-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
      (reduce (fn [a dt]
                (if-let [k (get keys-by-e (:e dt))]
                  (assoc a k (long (:tx dt 0)))
                  a))
              {}
              (d/datoms db :aevt :seon.code.form/hash)))))

(defn branch-status
  "Ephemeral tail renderer summarizing this branch's delta vs `:main`: changed forms, live `:main`
   conflicts (without needing a conn), branch-introduced health errors, and latest fast auto-proofs.
   Returns `nil` (0 tokens) on `:main` or when clean with zero pending issues."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db branch-db agent]}]
  (let [branch (:seon.agent/branch agent :main)]
    (when (and main-db branch-db (not= branch :main))
      (let [mcid (try (d/commit-id main-db) (catch Throwable _ nil))
            bcid (try (d/commit-id branch-db) (catch Throwable _ nil))]
        (when (and mcid bcid (not= mcid bcid))
          (let [m-hashes (form-hash-map main-db)
                b-hashes (form-hash-map branch-db)
                m-txs (form-tx-map main-db)
                b-txs (form-tx-map branch-db)
                all-ks (into (set (keys b-hashes)) (keys m-hashes))
                ;; Forms modified on branch whose tx differs from :main
                diff-ks (filterv (fn [k] (not= (get b-hashes k) (get m-hashes k))) all-ks)
                branch-edited (filterv (fn [k]
                                         (let [bt (get b-txs k 0)
                                               mt (get m-txs k 0)]
                                           (and (contains? b-hashes k) (> bt mt))))
                                       diff-ks)
                main-drifted (filterv (fn [k]
                                        (let [bt (get b-txs k 0)
                                              mt (get m-txs k 0)]
                                          (and (contains? m-hashes k) (> mt bt) (contains? b-hashes k))))
                                      diff-ks)
                ;; Check actual overlap: forms touched by BOTH sides after divergence
                ;; If a key is in diff-ks and both sides have txs > min(max-txs), surface drift cleanly
                touched-nss (into [] (distinct (keep (fn [k]
                                                       (let [ns-part (first (str/split (str k) #"[#/]" 2))]
                                                         (when (not (str/blank? ns-part)) (symbol ns-part))))
                                                     branch-edited)))
                b-errs (when (seq branch-edited)
                         (health/caused-by-branch main-db branch-db {} touched-nss))
                last-proofs (:seon.agent/last-proofs agent)
                lines (volatile! [])]
            (when (or (seq branch-edited) (seq b-errs) last-proofs (seq main-drifted))
              (vswap! lines conj
                      (str "branch=" branch " @ tx " (:max-tx branch-db)
                           " | edited-forms=" (count branch-edited)
                           (when (seq branch-edited)
                             (str " (" (str/join ", " (take 6 (sort branch-edited)))
                                  (when (> (count branch-edited) 6) (str " +" (- (count branch-edited) 6) " more")) ")"))
                           " | main-sync=" (if (empty? main-drifted)
                                             "up-to-date (non-conflicting :main changes sync lazily)"
                                             (str (count main-drifted) " form(s) moved on :main"))))
              (when (seq main-drifted)
                (vswap! lines conj
                        (str "MAIN DRIFT: :main updated " (str/join ", " (take 5 (sort main-drifted)))
                             " — check (user/diff) to verify no conflicts before merging.")))
              (when last-proofs
                (vswap! lines conj
                        (str "AUTO-PROOFS (tx " (:tx last-proofs) "): "
                             (if (:ok? last-proofs) "PASS" "FAIL")
                             " (" (:passed last-proofs 0) "/" (:ran last-proofs 0)
                             " in " (:ms last-proofs 0.0) " ms)"
                             (when (seq (:failures last-proofs))
                               (str " — " (str/join "; " (take 3 (:failures last-proofs))))))))
              (when (seq b-errs)
                (vswap! lines conj (str "MERGE-BLOCKING HEALTH ERRORS (" (count b-errs) "):"))
                (doseq [e (take 5 b-errs)]
                  (vswap! lines conj (str "  - [:" (name (:class e)) "] " (:subject e) " — " (:message e)))))
              {:tag "branch-status"
               :text (str/join "\n" @lines)})))))))

(defn scoped-health
  "Ephemeral tail renderer showing live & static health alerts tailored to `:seon.agent/scope`
   (including autofix hints). Returns `nil` (0 tokens) when scope is 100% clean."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db branch-db agent]}]
  (let [aid (:seon.agent/id agent)
        scope (:seon.agent/scope agent)]
    (when (and main-db scope)
      (let [live-probs (health/live-problems-for-scope main-db branch-db scope aid)
          fixable-static (filterv #(or (= :error (:severity %)) (:fix %))
                                  (health/static-problems-for-scope (or branch-db main-db) scope))
          all-probs (vec (distinct (concat live-probs fixable-static)))]
      (when (seq all-probs)
        {:tag "scoped-health"
         :text (str "Health & efficiency alerts for " scope ":\n"
                    (str/join "\n"
                              (map (fn [p]
                                     (str "  - [" (name (:severity p :warning)) " :" (name (:class p)) "] "
                                          (:subject p) " — " (:message p)
                                          (when (:fix p) " (auto-fixable via (user/autofix-health!))")))
                                   (take 6 all-probs))))})))))

(defn system-health
  "Ephemeral tail renderer showing the full system-wide health picture (`health/report`) with
   owning `:ns/<subsys>` specialists — intended for coordinator records such as `:agent/root`."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db]}]
  (when main-db
    (let [rep (health/report main-db)
          probs (:problems rep)]
      (if (empty? probs)
        {:tag "system-health"
         :text "System health: OK (0 errors, 0 advisories)."}
        {:tag "system-health"
         :text (str "System health: " (if (:ok? rep) "OK" "ERRORS")
                    " (" (pr-str (:counts rep)) ")\n"
                    (str/join "\n"
                              (map (fn [p]
                                     (let [owner (health/owner-specialist-id (:ns p))]
                                       (str "  - [" (name (:severity p :warning)) " :" (name (:class p)) "] "
                                            (:subject p)
                                            (when owner (str " (owner " owner ")"))
                                            " — " (:message p))))
                                   (take 8 probs))))}))))

(defn neighborhood-activity
  "Ephemeral tail renderer showing open incoming/outgoing calls and recent live records matching
   the agent's working schemas. Returns `nil` when empty."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db branch-db agent]}]
  (let [aid (:seon.agent/id agent)
        scope (:seon.agent/scope agent)
        router? (router-agent? agent)
        prefs (or (:seon.agent/context-prefs agent) {})
        rec-limit (long (or (:recent-data-limit prefs) 4))
        lines (volatile! [])
        add! (fn [s] (when-not (str/blank? s) (vswap! lines conj s)))]
    (when (and aid main-db (contains? (:schema main-db) :seon.agent.call/callee))
      (let [incoming (->> (d/datoms main-db :avet :seon.agent.call/callee aid)
                          (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                  :seon.agent.call/status :seon.agent.call/prompt
                                                  :seon.agent.call/result-schema] (:e %)))
                          (filter #(= :pending (:seon.agent.call/status %)))
                          vec)
            outgoing (->> (d/datoms main-db :avet :seon.agent.call/caller aid)
                          (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/callee
                                                  :seon.agent.call/status :seon.agent.call/created-tx] (:e %)))
                          (sort-by #(or (:seon.agent.call/created-tx %) 0))
                          (take-last 5)
                          vec)]
        (when (seq incoming)
          (add! "Open incoming calls to answer via (user/answer! call-id val):")
          (doseq [c incoming]
            (add! (str "  - " (pr-str (:seon.agent.call/id c)) " from " (:seon.agent.call/caller c)
                       (when (:seon.agent.call/result-schema c) (str " schema=" (:seon.agent.call/result-schema c)))
                       ": " (first-doc-line (:seon.agent.call/prompt c) 100)))))
        (when (seq outgoing)
          (add! "Recent outgoing calls:")
          (doseq [c outgoing]
            (add! (str "  - " (pr-str (:seon.agent.call/id c)) " -> " (:seon.agent.call/callee c)
                       " [" (name (:seon.agent.call/status c :pending)) "]"))))))
    (when (and main-db scope (pos? rec-limit))
      (let [router-defaults (when router?
                              [:seon.agent.call/id :seon.task/id :seon.test.run/branch
                               :seon.health.fault/id :seon.agent/id])
            extra-attrs (concat (:extra-schemas prefs) router-defaults)
            cand-attrs (->> extra-attrs
                            distinct
                            (filter #(and (contains? (:schema main-db) %)
                                          (first (take 1 (d/datoms main-db :avet %)))))
                            (take 5))
            samples (->> cand-attrs
                         (mapcat (fn [a]
                                   (map (fn [dt] {:attr a :tx (:tx dt) :eid (:e dt)})
                                        (take-last 2 (sort-by :tx (take 250 (d/datoms main-db :avet a)))))))
                         (sort-by (comp - :tx))
                         (take rec-limit))]
        (when (seq samples)
          (add! (str "Recent live data matching " scope " schemas:"))
          (doseq [{:keys [attr tx eid]} samples]
            (let [ent (d/pull main-db '[*] eid)]
              (add! (str "  - tx " tx " (" attr "): " (compact-entity-preview ent 150))))))))
    (when (seq @lines)
      {:tag "neighborhood-activity"
       :text (str/join "\n" @lines)})))

(defn- format-dom [dom]
  (when (map? dom)
    (let [parts (remove nil?
                        [(when (not (str/blank? (str (:viewing dom)))) (str "viewing=" (:viewing dom)))
                         (when (not (str/blank? (str (:mode dom)))) (str "mode=" (:mode dom)))
                         (when (:theme dom) (str "theme=" (:theme dom)))
                         (when (some? (:overflowX dom)) (str "overflowX=" (:overflowX dom)))
                         (when (some? (:svgCount dom)) (str "svgs=" (:svgCount dom)))])]
      (when (seq parts) (str/join " · " parts)))))

(defn live-viewport
  "Ephemeral tail renderer attaching the live WKWebView screenshot ONLY when enabled for this
   agent (`:seon.config/viewport-screenshot?` or explicit `:viewport-screenshot` passed in opts)."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db agent opts]}]
  (let [snap (:viewport-screenshot opts)
      aid (:seon.agent/id agent)
      cfg-installed? (and main-db (contains? (:schema main-db) :seon.config/viewport-screenshot?))
      enabled? (cond
                 (:force-vision? opts) true
                 cfg-installed? (boolean (store/config-val main-db aid :seon.config/viewport-screenshot?))
                 :else (boolean (:vision? opts true)))]
  (when (and snap enabled?)
    (let [dom-s (format-dom (:dom snap))]
      (cond-> {:tag "live-viewport"
               :attrs {:route (:route snap "/")
                       :size (str (:width snap 1280) "x" (:height snap 820))}
               :text (str "Current live WKWebView screenshot of what the user sees right now (always replaced at end of context — inspect closely and fix any clipping, overlap, contrast, or stray artifacts)."
                          (when dom-s (str "\nDOM: " dom-s)))}
        (and (:vision? opts true) (:base64 snap))
        (assoc :image {:mime (or (:mime snap) "image/jpeg")
                       :base64 (:base64 snap)
                       :raw snap}))))))

(defn render-tail-blocks
  "Evaluates every qualified tail render fn symbol in `(:seon.agent/tail-renders agent)` (falling
   back to `default-tail-renders`), fault-isolated so a bad custom tail renderer never crashes a turn.
   Returns `{:blocks [...] :image ...}`."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [agent] :as ctx}]
  (let [syms (or (seq (:seon.agent/tail-renders agent)) default-tail-renders)
        blocks (volatile! [])
        img (volatile! nil)]
    (doseq [sym syms
            :when (qualified-symbol? sym)]
      (try
        (when-let [rfn (or (try (resolve sym) (catch Throwable _ nil))
                           (try (requiring-resolve sym) (catch Throwable _ nil)))]
          (when-let [res (rfn ctx)]
            (let [b (cond
                      (map? res)
                      (let [txt (or (:text res)
                                    (when (:hiccup res) (render/hiccup->text (:hiccup res))))]
                        (when (or (not (str/blank? (str txt))) (:image res))
                          (assoc res :text (or txt "") :fn sym)))
                      (vector? res)
                      {:tag (name sym) :hiccup res :text (render/hiccup->text res) :fn sym}
                      (string? res)
                      (when-not (str/blank? res) {:tag (name sym) :text res :fn sym})
                      :else nil)]
              (when b
                (vswap! blocks conj b)
                (when (and (:image b) (nil? @img))
                  (vreset! img (:image b)))))))
        (catch Throwable ex
          (vswap! blocks conj {:tag "tail-render-fault"
                               :fn sym
                               :text (str "Tail render fn " sym " threw: " (or (.getMessage ex) (str ex)))}))))
    {:blocks @blocks :image @img}))
