(ns seon.teachings-test
  "EXECUTABLE TEACHINGS — the standing invariant that every TAUGHT
   example in the rendered context surfaces RUNS against a scratch
   boot-seeded world (fix-everything PRD 2026-06-11 §1 ROOT-1 / §3 B1:
   'a teaching that cannot execute may not render'). A taught form that
   errors, or that promises data and returns empty, is a RED test
   naming the source surface + line.

   ## THE EXTRACTION CONVENTION (documented HERE, the one place)

   A TAUGHT EXAMPLE is a block of code lines found in a teaching TEXT
   (an ns docstring, a fn docstring, a soul row, a section header, a
   tutorial eval source):

   - A block starts at a line whose content (after stripping any
     leading `;;` comment markers) begins with `(`, and continues
     until delimiters balance (string- and comment-aware).
   - Adjacent code lines join one block; an UNQUALIFIED follow-up call
     joins only when an earlier form in the block defines its head
     (the `(defn square …)` + `(square 7)` pair).
   - The block must consist ONLY of complete forms (no trailing prose
     — prose parentheticals like `(an empty result means …)` are not
     examples), and its first form's head must be a qualified symbol
     (contains `/` or `.`) or a definer (`ns`/`def`/`defn`/`defonce`/
     `let`/`fn`) or `await` (extracted ON PURPOSE: a top-level `await`
     in a teaching is the exact defect blind-spot #6 named, and it
     must go red here).
   - PLACEHOLDER examples are SHAPES, not runnable teachings, and are
     skipped. A placeholder is visible by convention: `…`/`...`,
     `<angle-bracket-slots>`, or the obviously-fake path roots
     `/Users/me/`, `/Users/you/`, `/path/to/`. A teaching that wants
     to show an unrunnable value must use these markers — a CONCRETE
     repo file/line that is wrong runs here and goes red (that is the
     point).
   - An eval failing ONLY on an undeclared free local in the eval ns
     (e.g. a metavariable `id` in a retract shape) classifies as a
     shape, not a red — any OTHER undeclared (a bad alias, a typo'd
     core fn) is a teaching lie and goes red.
   - `;; => …` comment lines after a block are its PROMISE. A promise
     asserts the live run returns data: red when the value is
     nil/empty, when the promise shows rows (`[{`/`[\"`) and none come
     back, or when an ok?-envelope comes back false (unless the
     promise itself shows `ok? false`).

   ## THE CORPUS (derived from the live render world, not a list)

   Examples run IN PROMPT-READING ORDER, sharing ONE scratch world —
   the session an agent imitating its prompt top-to-bottom would
   produce (so a teaching may rely on data an EARLIER teaching stores,
   exactly like the reader can):

   1. The live identity text (SOUL.md / AGENTS.md — the LLM system
      message, read first).
   2. ns docstrings of every full-source `:seon.ns/source` row (the
      `<namespace>` tags' teaching docstrings).
   3. Section headers: `seon.ctx/system-text` + the namespaces-section
      header (its taught member-rows query).
   4. The creation-turn tutorial eval sources (tile wiring, the
      store-inventory read, the instructions read) — run VERBATIM,
      they are real evals by construction.
   5. `:seon.fn/doc` docstrings of every boot-indexed fn — the surface
      agents reach through the taught store queries.

   The world mirrors a live boot: `client/open-agent-conn!` +
   `client/boot-seed!` + a created agent (reply!/wiring teachings need
   an agent entity), fs roots = src/ + docs/ read-only (the pod's
   gym-parity capability)."
  (:require
    [cljs.test :refer [deftest is async]]
    [cljs.tools.reader :as reader]
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.agent.fs :as sfs]
    [seon.agent.fs.internal :as sfs-int]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.ctx.namespaces :as ctx-namespaces]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.render.live-tile :as live-tile]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ============================================================
;; Extraction — see the ns docstring for the convention.
;; ============================================================

(def ^:private placeholder-re
  "The visible-placeholder markers (convention above)."
  #"…|\.\.\.|<[A-Za-z:][^>\n]*>|/Users/me/|/Users/you/|/path/to/")

(defn- head-of [src]
  (second (re-find #"^\(\s*([^\s()\[\]{}\"]+)" src)))

(defn- example-head? [h]
  (boolean (and h
                (re-matches #"[a-zA-Z][\w.!?*+<>=/'-]*" h)
                (or (str/includes? h "/")
                    (str/includes? h ".")
                    (contains? #{"ns" "def" "defn" "defonce" "let" "fn" "await"} h)))))

(defn- strip-comment
  "Line content behind a leading `;;` marker (trimmed), nil for
   non-comment lines."
  [line]
  (let [t (str/triml line)]
    (when (str/starts-with? t ";")
      (str/triml (str/replace t #"^;+\s?" "")))))

(defn- delim-delta
  "Net ()[]{} depth change of `s` — string-, escape-, and
   line-comment-aware."
  [s]
  (loop [i 0 depth 0 in-str? false]
    (if (>= i (count s))
      depth
      (let [c (nth s i)]
        (cond
          in-str? (cond (= c "\\") (recur (+ i 2) depth true)
                        (= c "\"") (recur (inc i) depth false)
                        :else (recur (inc i) depth true))
          (= c "\\") (recur (+ i 2) depth false)
          (= c "\"") (recur (inc i) depth true)
          (= c ";") depth
          (contains? #{"(" "[" "{"} c) (recur (inc i) (inc depth) false)
          (contains? #{")" "]" "}"} c) (recur (inc i) (dec depth) false)
          :else (recur (inc i) depth false))))))

(defn- forms-only?
  "True when `s` is nothing but complete forms + whitespace/comments —
   rejects prose-parenthetical candidates and trailing prose."
  [s]
  (loop [i 0 depth 0 in-str? false]
    (if (>= i (count s))
      (zero? depth)
      (let [c  (nth s i)
            nl #(or (str/index-of s "\n" i) (count s))]
        (cond
          in-str? (cond (= c "\\") (recur (+ i 2) depth true)
                        (= c "\"") (recur (inc i) depth false)
                        :else (recur (inc i) depth true))
          (zero? depth)
          (cond
            (re-matches #"\s" c) (recur (inc i) 0 false)
            (= c "(") (recur (inc i) 1 false)
            (= c ";") (recur (long (nl)) 0 false)
            (contains? #{"'" "@" "#"} c) (recur (inc i) 0 false)
            :else false)
          :else
          (cond
            (= c "\\") (recur (+ i 2) depth false)
            (= c "\"") (recur (inc i) depth true)
            (= c ";") (recur (long (nl)) depth false)
            (contains? #{"(" "[" "{"} c) (recur (inc i) (inc depth) false)
            (contains? #{")" "]" "}"} c) (recur (inc i) (dec depth) false)
            :else (recur (inc i) depth false)))))))

(defn- defined-names
  "Names `(defn|def|defonce <name> …)` forms in `forms-src` define —
   the unqualified follow-up call allowance."
  [forms-src]
  (into #{} (map second)
        (re-seq #"\((?:defn|def|defonce)\s+(?:\^[^\s]+\s+)*([^\s()\[\]{}\"]+)"
                forms-src)))

(defn- extract-blocks
  "Raw candidate blocks of `text` — {:acc [lines] :depth n :line n
   :commented? b :promise s?} — before the head/placeholder filters."
  [text]
  (let [lines (vec (str/split-lines text))
        n     (count lines)]
    (loop [i 0 out [] cur nil]
      (if (>= i n)
        (if (and cur (<= (:depth cur) 0) (seq (:acc cur))) (conj out cur) out)
        (let [line     (nth lines i)
              stripped (strip-comment line)
              tl       (str/triml line)]
          (cond
            ;; inside an open form — accumulate (a commented block hit
            ;; by a non-comment line is broken: abandon)
            (and cur (pos? (:depth cur)))
            (let [content (if (:commented? cur) stripped line)]
              (if (and (:commented? cur) (nil? content))
                (recur (inc i) out nil)
                (recur (inc i) out
                       (-> cur
                           (update :acc conj (or content ""))
                           (update :depth + (delim-delta (or content "")))))))

            ;; form(s) complete — adjacent code joins, `=>` opens the
            ;; promise, anything else closes the block
            cur
            (let [code-line (if (:commented? cur)
                              (when (and stripped (str/starts-with? stripped "("))
                                stripped)
                              (when (and (nil? stripped) (str/starts-with? tl "("))
                                tl))
                  join?     (when (and code-line (not (:promise cur)))
                              (let [h (head-of code-line)]
                                (or (example-head? h)
                                    (contains? (defined-names
                                                 (str/join "\n" (:acc cur)))
                                               h))))
                  prom-line (when (and stripped (str/starts-with? stripped "=>"))
                              stripped)]
              (cond
                join?
                (recur (inc i) out
                       (-> cur
                           (update :acc conj code-line)
                           (assoc :depth (delim-delta code-line))))

                prom-line
                (recur (inc i) out
                       (update cur :promise (fnil str "") (str prom-line "\n")))

                (and (:promise cur) stripped
                     (not (str/starts-with? stripped "(")))
                (recur (inc i) out (update cur :promise str (str stripped "\n")))

                :else (recur i (conj out cur) nil)))

            (and stripped (str/starts-with? stripped "("))
            (recur (inc i) out {:acc [stripped] :depth (delim-delta stripped)
                                :commented? true :line (inc i)})

            (and (nil? stripped) (str/starts-with? tl "("))
            (recur (inc i) out {:acc [tl] :depth (delim-delta tl)
                                :commented? false :line (inc i)})

            :else (recur (inc i) out cur)))))))

(defn taught-examples
  "Every taught example of teaching text `text` (the convention in the
   ns docstring): [{:seon.teachings/src :seon.teachings/line
   :seon.teachings/promise?}…]. Placeholder shapes are excluded."
  [text]
  (->> (extract-blocks (or text ""))
       (map (fn [{:keys [acc line promise]}]
              {:seon.teachings/src     (str/join "\n" acc)
               :seon.teachings/line    line
               :seon.teachings/promise promise}))
       (filter #(forms-only? (:seon.teachings/src %)))
       (filter #(example-head? (head-of (:seon.teachings/src %))))
       (remove #(re-find placeholder-re (:seon.teachings/src %)))
       vec))

;; ============================================================
;; Corpus — derived from the seeded world (the same rows the renderer
;; reads), never a parallel list of strings.
;; ============================================================

(defn- first-form-text
  "Text of the first complete top-level form of `src`."
  [src]
  (when-let [start (str/index-of src "(")]
    (loop [i start depth 0 in-str? false]
      (when (< i (count src))
        (let [c (nth src i)]
          (cond
            in-str? (cond (= c "\\") (recur (+ i 2) depth true)
                          (= c "\"") (recur (inc i) depth false)
                          :else (recur (inc i) depth true))
            (= c "\\") (recur (+ i 2) depth false)
            (= c "\"") (recur (inc i) depth true)
            (= c ";") (recur (long (or (str/index-of src "\n" i) (count src)))
                             depth false)
            (contains? #{"(" "[" "{"} c) (recur (inc i) (inc depth) false)
            (contains? #{")" "]" "}"} c)
            (let [d (dec depth)]
              (if (zero? d) (subs src start (inc i)) (recur (inc i) d false)))
            :else (recur (inc i) depth false)))))))

(defn- ns-docstring
  "The docstring of the `(ns …)` form at the head of file source `src`,
   or nil."
  [src]
  (when-let [ff (first-form-text src)]
    (let [form (try (reader/read-string ff) (catch :default _ nil))]
      (when (and (seq? form) (= 'ns (first form)) (string? (nth form 2 nil)))
        (nth form 2)))))

(defn- surface-examples [surface text]
  (mapv #(assoc % :seon.teachings/surface surface) (taught-examples text)))

(defn- soul-examples [_dbv]
  ;; The identity is read LIVE from SOUL.md / AGENTS.md (no store rows) —
  ;; surface its examples straight from the live identity-file text so a
  ;; code block a user puts in the identity file is still validated.
  (surface-examples "identity files (live SOUL.md/AGENTS.md)"
                    (ctx/identity-files-text)))

(defn- ns-doc-examples [dbv]
  (->> (db/query {:seon.db/db dbv
                  :seon.db/query '[:find ?nm ?src
                                   :where
                                   [?n :seon.ns/name ?nm]
                                   [?n :seon.ns/source ?src]]})
       (filter (fn [[nm _]] (and (ctx-namespaces/included-ns? nm)
                                 (ctx-namespaces/full-source-ns? (name nm)))))
       (sort-by (comp name first))
       (mapcat (fn [[nm src]]
                 (surface-examples (str "ns docstring of " (name nm))
                                   (ns-docstring src))))
       vec))

(defn- header-examples [dbv]
  ;; The namespaces-section HEADER is the prose ABOVE the first rendered
  ;; namespace block; everything from the first `;; ── namespace …` marker
  ;; on is REAL framework/my source (rendered full), validated by
  ;; ns-doc/fn-doc-examples, NOT by re-running whole fn bodies (which use
  ;; framework-internal aliases like `internal/…`). The old `<namespace`
  ;; marker no longer exists (the section switched to `── namespace`
  ;; comment markers), so the cut silently grabbed the entire 130 KB of
  ;; full source — every framework `(defn …)`/`(schema/register! …)` body
  ;; became a spurious "taught example". Cut at the real boundary.
  (let [nss    (ctx-namespaces/namespaces-section {:seon.db/db dbv})
        cut    (or (str/index-of nss "── namespace") (count nss))
        header (subs nss 0 cut)]
    (vec (concat (surface-examples "seon.ctx/system-text" ctx/system-text)
                 (surface-examples "namespaces-section header" header)))))

(defn- tutorial-examples
  "The creation-turn eval sources, VERBATIM — they are real evals by
   construction, so the whole source is one example."
  [agent-id]
  [{:seon.teachings/surface "tutorial seon.render.live-tile/wiring-source"
    :seon.teachings/src     (live-tile/wiring-source agent-id)
    :seon.teachings/line    1}])

(defn- fn-doc-examples [dbv]
  (->> (db/query {:seon.db/db dbv
                  :seon.db/query '[:find ?sym ?doc
                                   :where
                                   [?f :seon.fn/sym ?sym]
                                   [?f :seon.fn/doc ?doc]]})
       (sort-by first)
       (mapcat (fn [[sym doc]]
                 (surface-examples (str ":seon.fn/doc of " sym) doc)))
       vec))

(defn- corpus
  "Every taught example of the seeded world, in prompt-reading order
   (the ns docstring's §CORPUS)."
  [dbv agent-id]
  (vec (concat (soul-examples dbv)
               (ns-doc-examples dbv)
               (header-examples dbv)
               (tutorial-examples agent-id)
               (fn-doc-examples dbv))))

;; ============================================================
;; Running — error = red; promised-data-empty = red.
;; ============================================================

(defn- envelope-false? [v]
  (boolean (and (map? v)
                (some (fn [[k val]]
                        (and (keyword? k) (= "ok?" (name k)) (false? val)))
                      v))))

(defn- empty-ish? [v]
  (or (nil? v)
      (and (or (coll? v) (string? v)) (empty? v))))

(defn- rows-present? [v]
  (boolean (or (and (sequential? v) (seq v))
               (and (map? v) (some #(and (sequential? %) (seq %)) (vals v))))))

(defn- free-local-undeclared?
  "True when eval result `res` failed ONLY on an undeclared free local
   in the EVAL ns `eval-ns` (a metavariable like `id` in a shape
   example) — a shape, not a lie. Any other undeclared (bad alias,
   typo'd core fn) stays red. Examples run in the agent's HOME ns (the
   real agent environment), so a free local resolves under that ns."
  [res eval-ns]
  (let [und (get-in res [:error :seon.error/data :seon.eval/undeclared])]
    (boolean (and und (str/starts-with? und (str eval-ns "/"))))))

(defn ^:async run-example!
  "Run one taught example in the world; resolves to nil (green),
   {:seon.teachings/skip …}, or the red map naming surface + line."
  [compile-state agent-id {:seon.teachings/keys [src promise surface line]
                           :as ex}]
  (let [red     (fn [why] (assoc ex :seon.teachings/why why))
        ;; Examples run in the agent's HOME ns — the REAL agent
        ;; environment, with the `message`/`agent`/`schema`/`db` aliases
        ;; `setup-agent-ns!` wires — so the SHORT-aliased taught forms
        ;; (`schema/register!`, `db/query`, `::db/…`) resolve exactly as
        ;; they do for the agent reading the prompt. Running in `cljs.user`
        ;; (no aliases) made every short-alias teaching a false red.
        eval-ns (agent/home-ns agent-id)
        res     (await (db/with-agent agent-id
                         (fn [] (seval/eval compile-state src {:ns eval-ns}))))]
    (if-not (:ok res)
      (if (free-local-undeclared? res eval-ns)
        {:seon.teachings/skip (str surface " line " line " — shape (free local)")}
        (red (str "eval error: "
                  (or (get-in res [:error :seon.error/message])
                      (pr-str (:error res))))))
      (let [v0 (:value res)
            v  (if (instance? js/Promise v0)
                 (try (await v0)
                      (catch :default e
                        (red (str "returned Promise rejected: " e))))
                 v0)]
        (cond
          (:seon.teachings/why v) v          ; the rejection red above
          (and (envelope-false? v)
               (not (re-find #"ok\?\s+false" (or promise ""))))
          (red (str "envelope came back ok? false: " (pr-str v)))
          (and promise (empty-ish? v))
          (red (str "promises output, returned empty: " (pr-str v)))
          (and promise
               (or (str/includes? promise "[{") (str/includes? promise "[\""))
               (not (rows-present? v)))
          (red (str "promises rows, none came back: " (pr-str v)))
          :else nil)))))

(defn ^:async run-corpus!
  "Run examples in order against the seeded world; resolves to
   {:seon.teachings/ran n :seon.teachings/skips […] :seon.teachings/reds […]}."
  [compile-state agent-id examples]
  (let [!reds (atom []) !skips (atom []) !ran (atom 0)]
    (doseq [ex examples]
      (let [r (await (run-example! compile-state agent-id ex))]
        (swap! !ran inc)
        (cond
          (:seon.teachings/why r)  (swap! !reds conj r)
          (:seon.teachings/skip r) (swap! !skips conj r))))
    {:seon.teachings/ran   @!ran
     :seon.teachings/skips @!skips
     :seon.teachings/reds  @!reds}))

(defn ^:async with-scratch-world
  "Boot-seeded scratch world around `body` — (fn [conn compile-state
   agent-id] → Promise). Mirrors the gym's world: open-agent-conn! +
   boot-seed! under the agent's with-agent scope, fs roots src/+docs/
   read-only, root conn/fs/registry restored after."
  [body]
  (let [prev-conn   db/*conn*
        prev-fs     @sfs-int/!config
        keys-before (schema/current-keys)]
    (try
      (let [conn (await (client/open-agent-conn!))]
        (set! db/*conn* conn)
        (let [cwd (.cwd js/process)]
          (sfs/configure! {:seon.agent.fs/allowed-roots [(str cwd "/src")
                                                         (str cwd "/docs")]
                           :seon.agent.fs/read-only?    true}))
        (let [agent-id      (db/new-id!)
              compile-state (await (repl/ensure-bootstrap!))]
          (await (db/with-agent agent-id
                   (fn [] (client/boot-seed! {:seon.db/conn conn}))))
          (await (db/with-agent agent-id
                   (fn ^:async boot-harness-agent! []
                     (await (seval/setup-agent-ns!
                              compile-state (agent/home-ns agent-id) agent-id))
                     (await (agent/create! {:seon.agent/id agent-id})))))
          (await (body conn compile-state agent-id))))
      (finally
        (set! db/*conn* prev-conn)
        (reset! sfs-int/!config prev-fs)
        (let [minted (remove keys-before (schema/current-keys))]
          (when (seq minted)
            (swap! schema/*schemas #(apply dissoc % minted))))))))

;; ============================================================
;; Extraction self-tests (pure).
;; ============================================================

(def ^:private fixture-teaching
  "Synthetic teaching text exercising every convention branch."
  (str "   Some prose explaining things.\n"
       "   (the same gate read-file uses) — prose, not an example.\n"
       "\n"
       "     ;; a worked pair:\n"
       "     (defn fixture-sq [x] (* x x))\n"
       "     (fixture-sq 7)\n"
       "     ;; => 49\n"
       "\n"
       "     (seon.db/transact! {:seon.db/tx-data [{:a/b \"<your-value>\"}]})\n"
       "\n"
       "   ;; a commented example:\n"
       "   ;;   (seon.db/query {:seon.db/query '[:find ?e :where\n"
       "   ;;                                    [?e :my.x/y ?v]]})\n"
       "   (def !x (atom 0)) persists across turns — trailing prose.\n"))

(deftest extraction-follows-the-convention
  (let [exs (taught-examples fixture-teaching)]
    (is (= 2 (count exs))
        "the defn pair + the commented query; placeholder + prose excluded")
    (is (= "(defn fixture-sq [x] (* x x))\n(fixture-sq 7)"
           (:seon.teachings/src (first exs)))
        "adjacent unqualified call joins its defn")
    (is (= "=> 49\n" (:seon.teachings/promise (first exs)))
        "the => comment is the promise")
    (is (str/starts-with? (:seon.teachings/src (second exs)) "(seon.db/query")
        "commented example extracted with ;; stripped")))

(deftest placeholder-and-prose-shapes-are-skipped
  (is (empty? (taught-examples
                "   (seon.db/query {:seon.db/query '[:find … :where …]})"))
      "ellipsis placeholder is a shape")
  (is (empty? (taught-examples
                "   (seon.agent.fs/read-file {:seon.agent.fs/path \"/Users/me/x.md\"})"))
      "obviously-fake path root is a shape")
  (is (empty? (taught-examples "   (an empty result means no rows)"))
      "prose parenthetical is not an example"))

;; ============================================================
;; THE HARNESS — every taught example of the seeded world runs.
;; ============================================================

(defn- render-reds [reds]
  (str/join "\n\n"
            (map (fn [{:seon.teachings/keys [surface line src why]}]
                   (str "TEACHING LIES — " surface " (example at line " line "):\n"
                        src "\n→ " why))
                 reds)))

(deftest taught-examples-execute-against-a-boot-seeded-world
  (async done
    (-> (with-scratch-world
          (fn ^:async run-harness! [conn compile-state agent-id]
            (let [examples (corpus @conn agent-id)
                  result   (await (run-corpus! compile-state agent-id examples))
                  reds     (:seon.teachings/reds result)]
              (is (>= (:seon.teachings/ran result) 10)
                  (str "the corpus is non-trivial — extracted "
                       (:seon.teachings/ran result) " examples"))
              (is (empty? reds) (render-reds reds))
              ;; the canary: the harness itself must be able to go red —
              ;; a deliberately-broken teaching produces a red naming
              ;; its surface + line.
              (let [broken (surface-examples
                             "canary fixture"
                             "   (seon.db/store-inventoryy)\n   ;; => [{:x 1}]")
                    canary (await (run-corpus! compile-state agent-id broken))]
                (is (= 1 (count (:seon.teachings/reds canary)))
                    "a broken taught example goes red")
                (is (str/includes?
                      (render-reds (:seon.teachings/reds canary))
                      "canary fixture")
                    "the red names its source surface")))))
        (.then (fn [_] (done)))
        (.catch (fn [e]
                  (is false (str "harness threw — " e))
                  (done))))))
