(ns seon.agent.fs.match
  "Deterministic matching cascade for anchored in-place file edits.

   Pure fns — string in, decision out, NO IO — so a JVM gold-patch
   replay harness can drive the exact production matcher without a pod.
   The core rule: smart matching may FIND candidates; only DETERMINISTIC
   matching MUTATES. The cascade (first hit wins):

     1. exact text, exactly `::expected-count` occurrences → apply
     2. exact text within the `::near` line window, exactly-count → apply
     3. conservative normalization ONLY (CRLF/LF, per-line trailing
        whitespace, final newline — NEVER indentation or internal
        whitespace), line-based, exactly-count → apply, flagging which
        normalizations matched (3b: the same within `::near`)
     4. otherwise FAIL with `::reason` (`::not-found` / `::ambiguous`)
        and `::candidates` — every occurrence carries a line-numbered
        `::preview`. No fuzzy matching, no scoring, no guessing.

   Stage 3 is LINE-based: a `::find` that does not span whole lines can
   only match exactly (stages 1–2). [[decide]] is the single entry
   point; [[number-lines]] is the ONE line-number formatter, shared
   with `seon.agent.fs`'s read surface and the candidate previews."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — request, decision, candidates.
;; ============================================================

(schema/register! ::content        :string)
(schema/register! ::find           [:string {:min 1}])
(schema/register! ::replace        :string)
(schema/register! ::expected-count [:int {:min 1}])
;; Replace EVERY occurrence without knowing the count — legitimizes any
;; count >=1 at the matched stage. Mutually exclusive with ::expected-count
;; (schema-enforced on ::decide-request below): one says "exactly N", the
;; other says "however many there are".
(schema/register! ::all?           :boolean)
;; 1-based inclusive [from-line to-line].
(schema/register! ::range          [:tuple :int :int])
(schema/register! ::near           ::range)
(schema/register! ::lines          [:vector :string])
(schema/register! ::from-line      [:int {:min 1}])

(schema/register! ::preview    :string)
(schema/register! ::candidate  [:map [::range ::range] [::preview ::preview]])
(schema/register! ::candidates [:vector ::candidate])

(schema/register! ::normalization  [:enum ::crlf ::trailing-ws ::final-newline])
(schema/register! ::normalizations [:vector ::normalization])
(schema/register! ::stage   [:enum ::exact ::exact-near ::normalized ::normalized-near])
(schema/register! ::reason  [:enum ::not-found ::ambiguous])
(schema/register! ::message :string)

(schema/register! ::ranges        [:vector ::range])
(schema/register! ::new-content   :string)
(schema/register! ::range-after   ::range)
(schema/register! ::lines-added   :int)
(schema/register! ::lines-removed :int)

(schema/register! ::decide-request
  [:and
   [:map
    [::content        ::content]
    [::find           ::find]
    [::expected-count {:optional true} ::expected-count]
    [::all?           {:optional true} ::all?]
    [::near           {:optional true} ::near]
    [::replace        {:optional true} ::replace]]
   ;; ::all? and ::expected-count answer the SAME question two ways — a
   ;; request carrying both is incoherent, refused at the boundary.
   [:fn {:error/message "::all? and ::expected-count are mutually exclusive"}
    (fn [m] (not (and (contains? m ::expected-count) (contains? m ::all?))))]])

(schema/register! ::decision
  [:or
   [:map
    [::action         [:= :apply]]
    [::stage          ::stage]
    [::ranges         ::ranges]
    [::normalizations ::normalizations]
    [::new-content    {:optional true} ::new-content]
    [::range-after    {:optional true} ::range-after]
    [::lines-added    {:optional true} ::lines-added]
    [::lines-removed  {:optional true} ::lines-removed]]
   [:map
    [::action     [:= :fail]]
    [::reason     ::reason]
    [::message    ::message]
    [::candidates ::candidates]]])

;; ============================================================
;; Line plumbing — split, count, format.
;; ============================================================

(defn content-lines
  "File `content` as a line vector; a final newline adds no line."
  {:malli/schema [:=> [:catn [::content ::content]] ::lines]}
  [content]
  (if (= "" content)
    []
    (let [ls (str/split content #"\n" -1)]
      (if (= "" (peek ls)) (pop ls) ls))))

(defn- newline-count
  "Newlines in `s`."
  [s]
  (count (filter #(= \newline %) s)))

(defn- line-span
  "Lines beyond the first that `s` spans when spliced into a line.

   A trailing newline TERMINATES the last line, it doesn't extend into
   the next one."
  [s]
  (let [n (newline-count s)]
    (if (str/ends-with? s "\n") (max 0 (dec n)) n)))

(def preview-context-lines
  "Context lines shown on each side of a candidate preview."
  3)

(defn number-lines
  "Render `lines` with right-aligned 1-based numbers + a tab.

   Numbering starts at `from-line`. The ONE line-number formatter —
   the read view and every candidate preview use this exact format;
   strip the `N<tab>` prefix when copying text back into an edit."
  {:malli/schema [:=> [:catn [::lines ::lines] [::from-line ::from-line]] :string]}
  [lines from-line]
  (let [w (count (str (+ from-line (max 0 (dec (count lines))))))]
    (->> lines
         (map-indexed
           (fn [i l]
             (let [n (str (+ from-line i))]
               (str (apply str (repeat (- w (count n)) " ")) n "\t" l))))
         (str/join "\n"))))

(defn preview
  "Line-numbered excerpt of `lines` around a 1-based inclusive range.

   Shows `preview-context-lines` extra lines on each side, clamped to
   the file."
  {:malli/schema [:=> [:catn [::lines ::lines] [::range ::range]] ::preview]}
  [lines [from to]]
  (let [total (count lines)]
    (if (zero? total)
      ""
      (let [from  (min (max 1 from) total)
            to    (min (max from to) total)
            start (max 1 (- from preview-context-lines))
            end   (min total (+ to preview-context-lines))]
        (number-lines (subvec lines (dec start) end) start)))))

;; ============================================================
;; Occurrence scans — exact (character) + normalized (line).
;; ============================================================

(defn- exact-occurrences
  "All non-overlapping exact occurrences of `find` in `content`.

   Each is `{::start-idx <char-idx> ::range [from-line to-line]}`,
   in ascending order."
  [content find]
  (let [flen (count find)
        span (line-span find)
        nl-f (newline-count find)]
    (loop [idx 0 line 1 acc []]
      (if-let [i (str/index-of content find idx)]
        (let [line' (+ line (newline-count (subs content idx i)))]
          (recur (+ i flen)
                 (+ line' nl-f)
                 (conj acc {::start-idx i
                            ::range     [line' (+ line' span)]})))
        acc))))

(defn- norm-line
  "Conservative per-line normalization: trailing whitespace (incl. a
   CRLF `\\r`) stripped. Leading/internal whitespace untouched."
  [l]
  (str/trimr l))

(defn- normalized-occurrences
  "Non-overlapping line runs of `c-lines` equal to `f-lines` after
   [[norm-line]] on both sides. Each is `{::range [from to]}`."
  [c-lines f-lines]
  (let [cn   (mapv norm-line c-lines)
        fn*  (mapv norm-line f-lines)
        flen (count fn*)]
    (if (zero? flen)
      []
      (loop [i 0 acc []]
        (if (> (+ i flen) (count cn))
          acc
          (if (= fn* (subvec cn i (+ i flen)))
            (recur (+ i flen) (conj acc {::range [(inc i) (+ i flen)]}))
            (recur (inc i) acc)))))))

(defn- region-normalizations
  "Which conservative normalizations a matched region needed —
   a vector drawn from `::crlf` / `::trailing-ws` / `::final-newline`."
  [c-lines f-lines content find {[from to] ::range}]
  (let [region   (subvec c-lines (dec from) to)
        strip-cr #(if (str/ends-with? % "\r") (subs % 0 (dec (count %))) %)
        pairs    (map vector region f-lines)
        crlf?    (boolean (some (fn [[c f]]
                                  (not= (str/ends-with? c "\r")
                                        (str/ends-with? f "\r")))
                                pairs))
        trail?   (boolean (some (fn [[c f]]
                                  (let [c' (strip-cr c) f' (strip-cr f)]
                                    (and (not= c' f')
                                         (= (str/trimr c') (str/trimr f')))))
                                pairs))
        fnl?     (and (= to (count c-lines))
                      (not= (str/ends-with? content "\n")
                            (str/ends-with? find "\n")))]
    (cond-> []
      crlf?  (conj ::crlf)
      trail? (conj ::trailing-ws)
      fnl?   (conj ::final-newline))))

;; ============================================================
;; Applying — splice the ORIGINAL content; untouched bytes stay put.
;; ============================================================

(defn- replace-lines
  "`replacement` as a line vector — empty string = zero lines; a
   trailing newline adds no phantom blank line."
  [replacement]
  (if (= "" replacement)
    []
    (let [ls (str/split replacement #"\n" -1)]
      (if (and (> (count ls) 1) (= "" (peek ls))) (pop ls) ls))))

(defn- splice-exact
  "Replace each exact occurrence (char-indexed, ascending) with
   `replace`; every untouched byte of `content` is preserved."
  [content occs find replace]
  (let [flen   (count find)
        span-f (line-span find)
        span-r (line-span replace)
        n      (count occs)]
    (loop [occs (seq occs) prev 0 sb "" delta 0 rf nil rl nil]
      (if-let [{::keys [start-idx range]} (first occs)]
        (let [[from _] range
              from'    (+ from delta)]
          (recur (next occs)
                 (+ start-idx flen)
                 (str sb (subs content prev start-idx) replace)
                 (+ delta (- span-r span-f))
                 (or rf from')
                 (+ from' span-r)))
        {::new-content   (str sb (subs content prev))
         ::range-after   [(max 1 (or rf 1)) (max 1 (or rl 1))]
         ::lines-added   (if (= "" replace) 0 (* n (inc span-r)))
         ::lines-removed (* n (inc span-f))}))))

(defn- splice-lines
  "Replace each 1-based inclusive line region (ascending,
   non-overlapping) of `c-lines` with `r-lines`; rejoin with the
   original final-newline convention."
  [c-lines regions r-lines trailing?]
  (let [rspan (count r-lines)
        n     (count regions)]
    (loop [regions (seq regions) prev 0 out [] delta 0 rf nil rl nil removed 0]
      (if-let [[from to] (first regions)]
        (let [from' (+ from delta)]
          (recur (next regions)
                 to
                 (-> out (into (subvec c-lines prev (dec from))) (into r-lines))
                 (+ delta (- rspan (inc (- to from))))
                 (or rf from')
                 (+ from' (max 0 (dec rspan)))
                 (+ removed (inc (- to from)))))
        (let [new-lines (into out (subvec c-lines prev))]
          {::new-content   (cond-> (str/join "\n" new-lines)
                             (and trailing? (seq new-lines)) (str "\n"))
           ::range-after   [(max 1 (or rf 1)) (max 1 (or rl 1))]
           ;; `n` is the ORIGINAL region count — the loop binding `regions`
           ;; is consumed to empty by this exit branch (the mid-bugfix: a
           ;; `(count regions)` here always read 0).
           ::lines-added   (* n rspan)
           ::lines-removed removed})))))

(defn- exact-apply
  [stage content occs find replace]
  (cond-> {::action         :apply
           ::stage          stage
           ::ranges         (mapv ::range occs)
           ::normalizations []}
    replace (merge (splice-exact content occs find replace))))

(defn- normalized-apply
  [stage content c-lines occs find f-lines replace]
  (let [flags (->> occs
                   (mapcat #(region-normalizations c-lines f-lines content find %))
                   distinct
                   vec)]
    (cond-> {::action         :apply
             ::stage          stage
             ::ranges         (mapv ::range occs)
             ::normalizations flags}
      replace (merge (splice-lines c-lines (mapv ::range occs)
                                   (replace-lines replace)
                                   (str/ends-with? content "\n"))))))

;; ============================================================
;; Failing — reasons + line-numbered candidates, never a guess.
;; ============================================================

(defn- ->candidates
  [c-lines occs]
  (mapv (fn [{::keys [range]}]
          {::range range ::preview (preview c-lines range)})
        occs))

(defn- fail-ambiguous
  [c-lines occs expected near]
  {::action     :fail
   ::reason     ::ambiguous
   ::candidates (->candidates c-lines occs)
   ::message    (str "found " (count occs) " exact occurrence(s), expected "
                     expected
                     (when near (str " — the ::near window " (pr-str near)
                                     " did not narrow it to " expected))
                     ". Disambiguate with ::near [from-line to-line], more "
                     "surrounding context, or ::expected-count " (count occs)
                     " to change ALL of them.")})

(defn- fail-near-miss
  [c-lines n-occs expected]
  {::action     :fail
   ::reason     ::not-found
   ::candidates (->candidates c-lines n-occs)
   ::message    (str "exact text not found; " (count n-occs) " near-miss(es) "
                     "differ only by line-ending / trailing-whitespace "
                     "normalization (expected " expected "). Copy the exact "
                     "text from the candidate previews (strip the N<tab> "
                     "line-number prefix).")})

(def ^:private fail-not-found
  {::action     :fail
   ::reason     ::not-found
   ::candidates []
   ::message    (str "text not found — re-read the file and copy the EXACT "
                     "text, including whitespace (strip the N<tab> "
                     "line-number prefix from a numbered view).")})

;; ============================================================
;; The cascade.
;; ============================================================

(defn decide
  "Decide where `::find` matches `::content` — deterministically, or fail.

   Runs the four-stage cascade (exact → exact-in-`::near` → conservative
   line normalization → fail-with-candidates); `::expected-count`
   defaults to 1. `::all?` (mutually exclusive with `::expected-count`)
   legitimizes ANY count >=1 at the matched stage — it changes each
   stage's gate from \"exactly N\" to \"one or more\", so a find present
   several times applies to all of them without ever refusing as
   ambiguous (only `::not-found` remains a failure). With `::replace`
   present, an apply decision also carries `::new-content`,
   `::range-after` (1-based lines in the NEW content), `::lines-added`
   and `::lines-removed`. Pure — no IO."
  {:malli/schema [:=> [:cat ::decide-request] ::decision]}
  [{::keys [content find expected-count all? near replace]}]
  (let [all?     (boolean all?)
        expected (or expected-count 1)
        ;; ::all? makes any positive count a hit; otherwise the stage
        ;; matches only the EXACT expected count.
        hit?     (fn [n] (if all? (pos? n) (= expected n)))
        c-lines  (content-lines content)
        ;; `::near` matches by the occurrence's START line only — a match
        ;; whose start is inside the window is kept even if its end line
        ;; extends past it (multi-line finds are anchored, not contained).
        window?  (fn [{[from _] ::range}]
                   (and near (<= (first near) from (second near))))]
    (if (or (nil? find) (= "" find))
      (assoc fail-not-found ::message "::find must be a non-empty string")
      (let [occs      (exact-occurrences content find)
            near-occs (when near (filterv window? occs))]
        (cond
          ;; 1. exact — expected-count occurrences (or any >=1 under ::all?)
          (hit? (count occs))
          (exact-apply ::exact content occs find replace)

          ;; 2. exact, inside the ::near window
          (and near (hit? (count near-occs)))
          (exact-apply ::exact-near content near-occs find replace)

          :else
          (let [f-lines (content-lines find)
                n-occs  (normalized-occurrences c-lines f-lines)
                n-near  (when near (filterv window? n-occs))]
            (cond
              ;; 3. conservative normalization
              (hit? (count n-occs))
              (normalized-apply ::normalized content c-lines n-occs
                                find f-lines replace)

              ;; 3b. …inside the ::near window
              (and near (hit? (count n-near)))
              (normalized-apply ::normalized-near content c-lines n-near
                                find f-lines replace)

              ;; 4. fail — with candidates, never a guess
              (pos? (count occs))
              (fail-ambiguous c-lines occs expected near)

              (pos? (count n-occs))
              (fail-near-miss c-lines n-occs expected)

              :else
              fail-not-found)))))))
