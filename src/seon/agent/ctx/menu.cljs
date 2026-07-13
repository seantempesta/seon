(ns seon.agent.ctx.menu
  "The typeahead MENU block family — glyph-numbered, strictly-OPTIONAL
   offers derived from the db at render time (diffusion-typeahead P3a;
   see docs/prds/diffusion-dynamic-context/typeahead-design.md). One
   section, a pure fn of the db (reactive-context — the query
   returns nothing → the section vanishes, nothing is stored):

     - `:function-menu` ([[function-menu-block]]) — the agent's most-used
       public fns, derived from its OWN eval log (the `:seon.eval` rows
       the turn loop already persists — no new storage), each rendered
       as a glyph-numbered entry: glyph, `(fn-sym [args] …)` in the same
       arity grammar as the compact ns cards, docstring line 1.

   (The former `:plan-ledger` section retired 2026-07-11 — owner ruling,
   planner-worker-design.md: `:plan` is THE plan surface; its ▶/☐/
   done-dropped compactness contract lives in
   `my.plan.internal/plan-block` now. Its glyphs were never wire offers,
   so [[function-offers]] alignment is untouched — and the render-side
   duplicate-① ambiguity is gone with it.)

   The one law (settled by measurement — see the design doc): selection
   is STRICTLY OPTIONAL, forever. Each section header teaches it once,
   colocated with the block: select by outputting the glyph alone, or
   ignore the menu and write any Clojure — both work.

   This ns also owns the `:seon.typeahead/*` driver-policy row — the ONE
   policy surface the typeahead design allows (no new config system).
   Defaults live in code ([[default-policy]]); the
   `[:seon.typeahead/id \"policy\"]` singleton row, when present,
   OVERRIDES per knob ([[policy]] — read at render, so a `db/transact!`
   changes the next render). The config→DB migration is in flight on
   another lane, so the defaults deliberately stay code-side; the row is
   the override. (The keyword ns `seon.typeahead` names the DRIVER
   surface later phases add; the design doc pins these keyword names.)"
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]))

;; ============================================================
;; Glyphs — the model→driver selection vocabulary (①–⑩, all single
;; tokens, measured — typeahead-design "The glyph vocabulary").
;; ============================================================

(def glyphs
  "The ten menu-entry selection glyphs ①–⑩, in menu order.

   Single tokens each (measured). Every glyph-numbered menu this family
   renders indexes into THIS vector — the hard ceiling on any menu is
   its length, whatever the policy `menu-cap` says."
  ["①" "②" "③" "④" "⑤" "⑥" "⑦" "⑧" "⑨" "⑩"])

;; ============================================================
;; The `:seon.typeahead/policy` row — auto-offer margin, worst-token
;; gate, probe budget, menu cap. One singleton entity, identity
;; `[:seon.typeahead/id "policy"]` (the `:seon.ai/id "config"`
;; precedent). The two confidence knobs + the probe budget have NO
;; consumer in this ns — they are the DRIVER's dials (P2/P3b),
;; registered now so the row shape is stable (the PARKED
;; `::capabilities` precedent in seon.agent.ctx).
;; ============================================================

(schema/register! :seon.typeahead/id [:string {:seon.db/identity true}])
;; Calibrated glyph-posterior margin an auto-offer must clear (logits;
;; measured first-slot inflation −0.0 vs −6.4 — the design's calibration
;; rule subtracts the null-intent baseline BEFORE this threshold).
(schema/register! :seon.typeahead/auto-offer-margin :double)
;; Auto-accept gates use WORST-token confidence over a span, never mean
;; (probability dilution) — the minimum per-token probability accepted.
(schema/register! :seon.typeahead/worst-token-gate :double)
;; How many hole lengths the CAL confidence probe tries per hole.
(schema/register! :seon.typeahead/probe-budget :int)
;; Glyph page size — max entries any menu section renders (further
;; bounded by (count glyphs)).
(schema/register! :seon.typeahead/menu-cap :int)
;; Max TOOLKIT-group entries in the function menu (P6 — the second offer
;; group; the recency group keeps menu-cap; the combined menu is bounded
;; by the glyph vocabulary).
(schema/register! :seon.typeahead/toolkit-cap :int)
;; Step-loop round budget — how many mode=step calls one provider turn
;; may make before it stops with whatever locked (the P3b driver's cap;
;; mirrors the worker Policy's max_rounds default).
(schema/register! :seon.typeahead/max-rounds :int)
;; The per-step PLAN PASS scheduling (planner-worker-design W2): the step
;; loop's edit-with-prefill plan-document pass runs at every call open
;; (:every-step), only after an observed stuck round (:on-stuck), or
;; never (:off). LOOP-side knob — never rides the worker Policy wire.
(schema/register! :seon.typeahead/plan-pass [:enum :every-step :on-stuck :off])

;; The stored row shape — every knob optional (absent = code default;
;; optional = absent, never a stored nil).
(schema/register! :seon.typeahead/policy
  [:map {:seon.db/entity true}
   [:seon.typeahead/id :seon.typeahead/id]
   [:seon.typeahead/auto-offer-margin {:optional true} :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/worst-token-gate  {:optional true} :seon.typeahead/worst-token-gate]
   [:seon.typeahead/probe-budget      {:optional true} :seon.typeahead/probe-budget]
   [:seon.typeahead/menu-cap          {:optional true} :seon.typeahead/menu-cap]
   [:seon.typeahead/toolkit-cap       {:optional true} :seon.typeahead/toolkit-cap]
   [:seon.typeahead/max-rounds        {:optional true} :seon.typeahead/max-rounds]
   [:seon.typeahead/plan-pass         {:optional true} :seon.typeahead/plan-pass]])

;; The EFFECTIVE policy view [[policy]] returns — every knob present.
(schema/register! ::policy-view
  [:map
   [:seon.typeahead/auto-offer-margin :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/worst-token-gate  :seon.typeahead/worst-token-gate]
   [:seon.typeahead/probe-budget      :seon.typeahead/probe-budget]
   [:seon.typeahead/menu-cap          :seon.typeahead/menu-cap]
   [:seon.typeahead/toolkit-cap       :seon.typeahead/toolkit-cap]
   [:seon.typeahead/max-rounds        :seon.typeahead/max-rounds]
   [:seon.typeahead/plan-pass         :seon.typeahead/plan-pass]])

(def policy-row-id
  "The `:seon.typeahead/id` of the ONE policy singleton row."
  "policy")

(def default-policy
  "The code-default typeahead policy — the row overrides per knob."
  {:seon.typeahead/auto-offer-margin 3.0
   :seon.typeahead/worst-token-gate  0.9
   :seon.typeahead/probe-budget      3
   :seon.typeahead/menu-cap          8
   :seon.typeahead/toolkit-cap       4
   :seon.typeahead/max-rounds        8
   :seon.typeahead/plan-pass         :every-step})

(defn policy
  "The effective typeahead driver policy in db value `db`.

   [[default-policy]] with the `[:seon.typeahead/id \"policy\"]`
   singleton row's knobs merged over it (per-knob override; an absent
   row/attr keeps the code default). Read at render time — transact the
   row and the next render behaves differently. Never throws: a db
   without the attr installed answers the defaults."
  {:malli/schema [:=> [:catn [::db :seon.db/db]] ::policy-view]}
  [db]
  (let [row (when (and db (contains? (db/installed-schema db) :seon.typeahead/id))
              (db/entity-lazy {:seon.db/db db
                               :seon.db/ref [:seon.typeahead/id policy-row-id]}))]
    (merge default-policy
           (when row (select-keys row (keys default-policy))))))

(defn- menu-cap
  "The effective entry cap for one rendered menu: the policy's
   `:seon.typeahead/menu-cap` bounded by the glyph vocabulary size."
  [db]
  (-> (:seon.typeahead/menu-cap (policy db))
      (min (count glyphs))
      (max 0)))

;; ============================================================
;; :function-menu — most recently/frequently eval'd public fns, derived
;; from the eval log + the program graph. Nothing new is stored: the
;; `:seon.eval` rows are the log the turn loop already writes, the
;; `:seon.fn` rows are the boot/tee-indexed program graph, and alias
;; resolution reads the STORED `:seon.ns/require-edges` of each eval's
;; own ns (the one requires store, C36).
;; ============================================================

(def ^:private eval-scan-window
  "How many of the agent's most recent successful evals feed the
   ranking. A window (not all history) keeps the menu tracking what the
   agent is doing NOW and bounds the per-render read."
  30)

(defn- eval-rows
  "The agent's most recent successful eval rows in `db`, newest first.

   `{:seon.eval/at … :seon.eval/source … :seon.eval/ns …}` maps, capped
   at [[eval-scan-window]]. [] when the agent/attrs are absent."
  [db agent-id]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find [?e ...]
                                   :in $ ?aid
                                   :where
                                   [?a :seon.agent/id ?aid]
                                   [?e :seon.eval/agent ?a]
                                   [?e :seon.eval/ok? true]]
                  :seon.db/args [agent-id]})
       (map #(db/pull db [:seon.eval/at :seon.eval/source :seon.eval/ns] %))
       (filter :seon.eval/at)
       (sort-by #(.getTime ^js (:seon.eval/at %)) >)
       (take eval-scan-window)
       vec))

(defn- call-syms
  "Every symbol in call position anywhere in eval `source`, in order.

   Reads the whole source structurally (`repl-internal/read-forms` — the
   ONE whole-source read; nil on a broken source → []), then walks each
   form collecting the head symbol of every list. Strings/comments can't
   false-positive; a quoted list's head is accepted noise."
  [source]
  (into []
        (comp (mapcat #(tree-seq coll? seq %))
              (keep (fn [f]
                      (when (and (seq? f) (symbol? (first f)))
                        (first f)))))
        (or (repl-internal/read-forms source) [])))

(defn- require-info-for
  "The `::seval/require-info` lexical env of ns `ns-kw` in `db` — the
   alias→ns + refers maps its STORED require-edges fold to. The empty
   info for a nil/unstored ns (fully-qualified calls still resolve)."
  [db ns-kw]
  (seval/edges->require-info
    (if ns-kw (seval/stored-require-edges db ns-kw) #{})))

(defn- resolve-call-sym
  "Resolve call symbol `sym` to a full `\"ns/name\"` string via the
   eval ns's require `info`, or nil when unresolvable (a local, a core
   fn, an unaliased bare symbol)."
  [{aliases :seon.eval/aliases refers :seon.eval/refers} sym]
  (if-let [ns-part (namespace sym)]
    (str (get aliases (symbol ns-part) (symbol ns-part)) "/" (name sym))
    (some (fn [[target syms]]
            (when (contains? syms sym)
              (str target "/" (name sym))))
          refers)))

(defn- public-fn-row
  "The `:seon.fn` program-graph row for full-sym string `s` when it is a
   PUBLIC fn var (indexed, `:seon.fn/fn-var?`, not private) — else nil."
  [db s]
  (let [e (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.fn/sym s]})]
    (when (and e (:seon.fn/fn-var? e) (not (:seon.fn/private? e)))
      e)))

(defn- ranked-functions
  "`[full-sym-str fn-row]` pairs for the public fns called across eval
   `rows` (newest first), ranked most-CALLED first, most-RECENT first on
   ties. Uncapped — the section applies the policy menu-cap."
  [db rows]
  (let [infos (into {}
                    (map (fn [k] [k (require-info-for db k)]))
                    (distinct (map :seon.eval/ns rows)))
        occ   (vec (mapcat (fn [{src :seon.eval/source ns-kw :seon.eval/ns}]
                             (keep #(resolve-call-sym (get infos ns-kw) %)
                                   (call-syms src)))
                           rows))
        freq  (frequencies occ)
        seen  (reduce (fn [m [i s]] (if (contains? m s) m (assoc m s i)))
                      {}
                      (map-indexed vector occ))]
    (->> (keys freq)
         (sort-by (juxt #(- (freq %)) seen))
         (keep (fn [s] (when-let [row (public-fn-row db s)] [s row])))
         vec)))

(defn- capped-functions
  "The RECENCY-group `[full-sym-str fn-row]` pairs for agent `id` in
   `db` — [[ranked-functions]] over the eval window, policy menu-capped.
   [] when the db/agent/attrs are absent, so callers share ONE guard.
   [[combined-functions]] (recency + toolkit, one glyph numbering) is
   what the rendered menu AND the driver's wire offers both consume."
  [db id]
  (if (and db id
           (contains? (db/installed-schema db) :seon.eval/agent)
           (contains? (db/installed-schema db) :seon.fn/sym))
    (vec (take (menu-cap db) (ranked-functions db (eval-rows db id))))
    []))

;; ============================================================
;; Toolkit group (P6) — task-relevant offers from the program graph. The
;; P5 measurement: all 13 auto-offer fires were argmax-correct ON the
;; menu, but 0/13 selected a task-required function — the recency-only
;; menu structurally cannot contain a function the agent has not called
;; yet. This group puts the agent's core function surface on the menu
;; regardless of its own usage:
;;
;;   - the TOOLKIT NSES are the nses the agent's CURRENT ns requires
;;     (the stored `:seon.ns/require-edges` — the SAME set whose compact
;;     cards the `:namespaces` section renders, mirroring its
;;     `required-ns-set`; for a home ns that is the canonical
;;     `seon.agent.home/home-ns-require-specs` wiring). No hand list: the
;;     require edges are data, `included-ns?` is the structural filter.
;;   - the CANDIDATES are those nses' public SPECCED `:seon.fn` rows
;;     (the program graph; a specced public fn is the agent-facing
;;     contract surface).
;;   - the RANK is cross-agent global call frequency over the newest
;;     [[global-eval-scan-window]] successful evals (any agent — the
;;     cross-agent reactive-context precedent), admitted ROUND-ROBIN per
;;     ns (every toolkit ns gets its top function before any ns gets its
;;     second) so one chatty ns cannot crowd the rest out. Zero-usage
;;     stores without eval history fall back to (ns-name, fn-name) order.
;;
;; ONE numbering across both groups (decided here, P6): the rendered
;; section numbers recent + toolkit entries through the SAME glyph
;; vector, and [[function-offers]] mirrors the concatenation — glyph N on
;; the wire is glyph N in the prompt for EVERY offer. (The retired
;; `:plan-ledger` section used to number its own lines from ① too;
;; that render-side ambiguity is gone — this menu is the only
;; glyph-numbered surface.)
;; ============================================================

(def ^:private global-eval-scan-window
  "How many of the CLUSTER's most recent successful evals (any agent)
   feed the toolkit-group ranking. Bounds the per-render read."
  200)

(defn- all-eval-rows
  "The newest [[global-eval-scan-window]] successful eval rows in `db`
   across ALL agents, newest first — [[eval-rows]] without the agent
   filter. [] when the attr is absent."
  [db]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find [?e ...]
                                   :where [?e :seon.eval/ok? true]]})
       (map #(db/pull db [:seon.eval/at :seon.eval/source :seon.eval/ns] %))
       (filter :seon.eval/at)
       (sort-by #(.getTime ^js (:seon.eval/at %)) >)
       (take global-eval-scan-window)
       vec))

(defn- call-freq
  "full-sym-str → call count across eval `rows` (aliases resolved via
   each eval ns's stored require-edges, as in [[ranked-functions]])."
  [db rows]
  (let [infos (into {}
                    (map (fn [k] [k (require-info-for db k)]))
                    (distinct (map :seon.eval/ns rows)))]
    (frequencies
      (mapcat (fn [{src :seon.eval/source ns-kw :seon.eval/ns}]
                (keep #(resolve-call-sym (get infos ns-kw) %)
                      (call-syms src)))
              rows))))

(defn- toolkit-nses
  "The agent's toolkit ns names (keywords): the nses its CURRENT ns
   requires, per the stored require-edges — the same set the compact ns
   cards render (`seon.agent.ctx.namespaces` `required-ns-set` mirror),
   structurally filtered by [[ns-cards/included-ns?]]. #{} when the
   agent/attrs are absent."
  [db id]
  (let [cur (try (some-> (ctx/current-ns {:seon.agent/id id :seon.db/db db})
                         name keyword)
                 (catch :default _ nil))]
    (if (and cur (contains? (db/installed-schema db) :seon.ns/name))
      (into #{}
            (filter ns-cards/included-ns?)
            (seval/stored-require-targets db cur))
      #{})))

(defn- ns-public-specced-fns
  "`[full-sym-str fn-row]` pairs for ns `ns-kw`'s PUBLIC SPECCED fns in
   `db`, fn-name order. [] when the ns is unindexed."
  [db ns-kw]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find [?e ...]
                                   :in $ ?nsname
                                   :where
                                   [?ns :seon.ns/name ?nsname]
                                   [?e :seon.fn/ns ?ns]
                                   [?e :seon.fn/fn-var? true]]
                  :seon.db/args [ns-kw]})
       (map #(db/pull db [:seon.fn/sym :seon.fn/private? :seon.fn/spec
                          :seon.fn/arglists :seon.fn/doc] %))
       (filter (fn [row] (and (not (:seon.fn/private? row))
                              (:seon.fn/spec row)
                              (:seon.fn/sym row))))
       (map (fn [row] [(:seon.fn/sym row) row]))
       (sort-by first)
       vec))

(defn- toolkit-functions
  "Up to `cap` `[full-sym-str fn-row]` toolkit entries for agent `id`,
   excluding syms in `exclude` (the recency group's — no duplicate
   offers). Round-robin per ns: nses ordered by their best candidate's
   global call frequency (desc, ns name on ties); within an ns,
   frequency desc then fn name."
  [db id exclude cap]
  (if (and db id (pos? cap)
           (contains? (db/installed-schema db) :seon.fn/sym))
    (let [freq     (if (contains? (db/installed-schema db) :seon.eval/ok?)
                     (call-freq db (all-eval-rows db))
                     {})
          per-ns   (->> (toolkit-nses db id)
                        (map (fn [ns-kw]
                               (->> (ns-public-specced-fns db ns-kw)
                                    (remove #(contains? exclude (first %)))
                                    (sort-by (fn [[s _]] [(- (freq s 0)) s]))
                                    vec)))
                        (remove empty?)
                        (sort-by (fn [cands]
                                   [(- (freq (ffirst cands) 0))
                                    (ffirst cands)])))
          max-len  (apply max 0 (map count per-ns))]
      (->> (for [k    (range max-len)
                 cands per-ns
                 :when (< k (count cands))]
             (nth cands k))
           (take cap)
           vec))
    []))

;; ============================================================
;; The combined function menu — recent group then toolkit group, ONE
;; glyph numbering, bounded by the glyph vocabulary. The SAME structure
;; drives the rendered section AND the wire offers.
;; ============================================================

(defn- combined-functions
  "`{::recent [[sym row]…] ::toolkit [[sym row]…]}` for agent `id` in
   `db` — the recency group ([[capped-functions]]) plus the toolkit group
   ([[toolkit-functions]], deduped, policy `toolkit-cap`), together
   bounded by the glyph vocabulary. Both groups [] when db/agent are
   absent."
  [db id]
  (let [recent  (capped-functions db id)
        t-cap   (-> (:seon.typeahead/toolkit-cap (policy db))
                    (min (- (count glyphs) (count recent)))
                    (max 0))
        toolkit (toolkit-functions db id (into #{} (map first) recent) t-cap)]
    {::recent recent ::toolkit toolkit}))

(def ^:private menu-teaching
  ;; The measured P5 teaching, byte-stable (the bench's teaching overlay
  ;; keys on these lines).
  (str "; A MENU, never a mandate: select an entry by outputting its glyph\n"
       "; alone (e.g. ①), or ignore this and write any Clojure — both work.\n"
       "; Example: to select entry ①, output the single character ① and\n"
       "; nothing else — its call template is expanded for you to fill."))

(def ^:private recent-functions-header
  (str "; recent functions — the fns you have been calling, most-used first.\n"
       menu-teaching))

(def ^:private toolkit-only-header
  (str "; toolkit functions — your required namespaces' public fns.\n"
       menu-teaching))

(def ^:private toolkit-group-header
  "; toolkit — more functions from your required namespaces:")

(defn- function-line
  "One rendered menu entry: `; <glyph> (<sym> [args] …) — <doc line 1>`.
   The arity grammar is the compact-card one ([[ns-cards/compact-arities]]);
   a fn with no docstring renders without the ` — …` tail."
  [glyph sym-str row]
  (let [doc1 (some->> (:seon.fn/doc row) str/split-lines first str/trim
                      not-empty)
        doc1 (when doc1
               (if (> (count doc1) 78) (str (subs doc1 0 77) "…") doc1))]
    (str "; " glyph " (" sym-str " "
         (ns-cards/compact-arities (:seon.fn/arglists row)) ")"
         (when doc1 (str " — " doc1)))))

(defn function-menu-block
  "The `:function-menu` section — recent + toolkit functions, glyph-listed.

   ONE menu, TWO derived groups under ONE glyph numbering (P6):

     - recent — every fn called in YOUR last [[eval-scan-window]]
       successful evals that is a PUBLIC program-graph fn, most-called
       first, most-recent first on ties, policy `menu-cap`.
     - toolkit — your current ns's required nses' public SPECCED fns
       ([[toolkit-functions]]: cross-agent frequency rank, per-ns
       round-robin, policy `toolkit-cap`) — the task-relevant surface a
       recency menu structurally misses (P5: 0/13 fires could select a
       required function).

   Entry grammar per line is unchanged: glyph, `(fn-sym [args] …)`
   (compact-card arity grammar), docstring line 1. Aliased calls resolve
   through each eval ns's STORED require-edges; nothing new is stored.
   Selection is strictly optional (the header teaches it); REACTIVE:
   both groups empty → \"\" and the composer drops the section. The
   section is named `:function-menu` (renamed from `:recent-verbs`,
   owner 2026-07-12 — ctx rows seed-copied into agents BEFORE the rename
   keep the old name + fn symbol and are orphaned; a cluster reset
   re-seeds from the manifest)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [db (or db (some-> db/*conn* deref))
        {::keys [recent toolkit]} (combined-functions db id)
        lines (fn [offset entries]
                (str/join "\n"
                          (map-indexed
                            (fn [i [s row]] (function-line (glyphs (+ offset i)) s row))
                            entries)))]
    (cond
      (and (seq recent) (seq toolkit))
      (str recent-functions-header "\n" (lines 0 recent) "\n"
           toolkit-group-header "\n" (lines (count recent) toolkit))

      (seq recent)  (str recent-functions-header "\n" (lines 0 recent))
      (seq toolkit) (str toolkit-only-header "\n" (lines 0 toolkit))
      :else "")))

;; ============================================================
;; Driver offers — the SAME capped function list as the rendered menu,
;; in the step-driver's offer shape (typeahead-design "Wire modes":
;; glyph + label + a clamp/free template). The P3b provider
;; (`seon.ai.typeahead`) converts these to the worker's string-keyed
;; wire maps; glyph N here is glyph N in the rendered `:function-menu`
;; section by construction (both read [[capped-functions]]).
;; ============================================================

(schema/register! :seon.typeahead/glyph :string)
(schema/register! :seon.typeahead/label :string)
(schema/register! :seon.typeahead/template-segment
  [:or [:tuple [:= "clamp"] :string] [:tuple [:= "free"] :int]])
(schema/register! :seon.typeahead/template
  [:vector :seon.typeahead/template-segment])
(schema/register! :seon.typeahead/offer
  [:map
   [:seon.typeahead/glyph    :seon.typeahead/glyph]
   [:seon.typeahead/label    :seon.typeahead/label]
   [:seon.typeahead/template :seon.typeahead/template]])
(schema/register! ::offers-view [:vector :seon.typeahead/offer])

(def ^:private offer-args-free-tokens
  "Free tokens a function template grants for the call's arguments."
  24)

(defn function-offers
  "Driver offers mirroring the agent's rendered `:function-menu` menu.

   One offer per [[combined-functions]] entry (recent group then toolkit
   group — the SAME concatenation the section renders, so glyph N on
   the wire is glyph N in the prompt for every offer) — the selection
   glyph, a `sym [args] …` label, and a `(sym ` + free-args-hole + `)`
   clamp template the driver expands on selection. [] when the agent
   has no menu (same guard as the rendered section), so the wire
   carries offers exactly when the prompt shows the menu."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [:seon.agent/id :string]]
                  ::offers-view]}
  [db id]
  (let [{::keys [recent toolkit]} (combined-functions db id)]
    (vec
      (map-indexed
        (fn [i [s row]]
          {:seon.typeahead/glyph    (glyphs i)
           :seon.typeahead/label    (str s " "
                                         (ns-cards/compact-arities
                                           (:seon.fn/arglists row)))
           :seon.typeahead/template [["clamp" (str "(" s " ")]
                                     ["free" offer-args-free-tokens]
                                     ["clamp" ")"]]})
        (concat recent toolkit)))))

;; (The `:plan-ledger` section that used to live here retired 2026-07-11
;; — see the ns docstring; `my.plan.internal/plan-block` carries the
;; ▶/☐/done-dropped contract now.)
