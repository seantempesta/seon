---
title: Plan component landing evidence
date: 2026-09-07
type: research
status: active
---

# Plan component landing evidence

## What landed

The agent's plan is one agent-owned component tree. `:my.plan/steps` is the
agent's component ref set of root steps; `:my.plan.item/steps` is the same edge
one level down; `:my.plan.item/position` makes sibling order a stored fact
because a cardinality-many value is a set; `:my.plan/current-step` is an
ordinary ref, because currentness neither owns nor copies a step.

`:my.plan/anchor`, `:my.plan.item/parent`, and the `:my.plan.item/agent`
backlink are no longer written. `:my.plan.item/agent` stays REGISTERED (and
optional on `:my.plan.item/item`) because `test/seon/render_simplification_test.clj`
and `test/seon/render/web_test.clj` still construct it; `:my.plan/anchor` and
`:my.plan.item/parent` had no executable reference outside the owned plan files
and are gone.

Parent, depth, dependencies, and state are DERIVED at read time and travel as
stable identity maps (`{:my.plan.item/id "..."}`), never entity ids. That is the
contract fix for the crash class `Don't know how to create ISeq from:
java.lang.Long`: the old path normalized a pulled ref to its entity id with
`seon.render.value/transacted` and then called `second` on it. `my.plan/plan`
never normalizes refs now, and `:my.plan/render-step` declares `:my.plan/needs`
and `:my.plan/parent` as `:my.plan/stable-item-reference`, so a bare numeric ref
fails the declared contract instead of throwing.
`my.plan-test/no-numeric-entity-reference-reaches-either-projection` is the
class regression: it asserts the derived value and both rendered projections
contain no plan-step entity id, and that the render-step contract accepts the
derived step and refuses `{:my.plan/needs [42]}`.

## The render-declaration coherence constraint (measured, not guessed)

`seon.schema/render-contract-observation` (`src/seon/schema.clj:1526-1568`)
accepts an attribute-level render declaration only when one of these holds for
the producer's first non-`:seon.db/database-value` input:

- the input form is LITERALLY the declaring schema key;
- the input form is `:seon.schema/value`;
- the input is `:seon.render/unit` and the declaring schema is map-shaped;
- `schema-accepts-schema?` succeeds, which for a non-map declaring shape only
  succeeds on exact form equality.

An `[:or :my.plan/steps :my.plan/render-steps :my.plan/render-step]` union input
was REFUSED: inside an `:or`, Malli hands `schema-accepts-schema?` the
dereferenced child form, so it never equals the declaring key. The landed
contracts therefore declare the attribute key itself:

```clojure
(defn render-plan-ai
  {:malli/schema [:=> [:cat :my.plan/steps] :seon.render/source]} ...)
(defn render-plan-html
  {:malli/schema [:=> [:cat :my.plan/steps :seon.db/database-value
                       :seon.cluster.agent/id]
                  :seon.render/hiccup]} ...)
```

Both renderers IGNORE that first argument and derive the plan from
`my.plan/plan`, so the two projections read one value from one function. The
database and the calling agent arrive through call preparation
(`config/default.edn:450-464` declares `:seon.db/db`, `:seon.db/connection`, and
`:seon.cluster.agent/id` as the suppliable defaults).

## Protected-path requests (not implemented here)

1. RESOLVED by `43a80d881` while this lane ran. `render-invocation-argument`
   previously handed a producer two different shapes: the attribute's
   TRANSACTED value (a set of entity ids) when the request value was the owning
   entity map, and the raw pulled value (a VECTOR of pulled step maps) when the
   debug page passed the attribute value directly
   (`src/seon/render/web.clj`, `debug-found-value` hands `(val entry)` from the
   agent pull). Both now arrive in the attribute's transaction shape, which is
   what the landed `:my.plan/steps` contract declares. One case remains: the
   walk renders a neighbour REACHED BY that attribute
   (`src/seon/render/walk.clj:200-300`), so the request value is one child step
   entity, and `{attribute <entity-map>}` does not transact to a ref set. That
   fails the declared contract as a typed refusal rather than silently, but it
   is the next thing to decide — either the walk should not select an
   attribute-declared producer for a child it reached, or it should hand the
   OWNER.
2. Keep `:my.plan/intent-subjects` registered with its existing definition and
   preserve `my.plan/ready-subjects`; `src/seon/bootstrap.clj:517`,
   `src/seon/render/walk.clj:679`, and `test/seon/bootstrap_test.clj:293`
   consume that contract. Done — both are unchanged in behavior.
3. Before `:my.plan.item/agent` can be unregistered, migrate
   `test/seon/render_simplification_test.clj:125-166` and the debug-datom
   fixtures in `test/seon/render/web_test.clj:1134-1166` to a surviving
   attribute. Until then the declaration stays.

## Foreign breakage observed while landing

At 2026-09-07 05:05 the shared published test base refused with
`Schema publication refused :seon.cluster.message/to: :seon.render/ai names
seon.cluster.message/render-inbox-ai whose declared input
:seon.cluster.message/inbox-value does not accept the declaring shape.` That is
the messages/units lane hitting the SAME coherence constraint described above;
the fix there is the same one: declare the attribute key itself as the
producer's first input. It blocked verification, not this lane's commits, and
cleared on its own by 05:10.

`seon.render-simplification-test/authored-source-invocation-reuses-one-stored-run-across-presentations`
was red at 04:55 and again at 05:20 (`:seon.render.call/source-run-id` nil,
`:seon.render.web/evaluator-absent`). It names no plan attribute or function and
belongs to the render source-run cache, not this lane.

`seon.render-simplification-test/non-rendering-more-specific-schema-does-not-shadow-agent-identity`
was red at 05:20 because `seon.cluster.agent/render-identity-ai` now prefixes
two thinking comment lines while the test still expects the bare
`(seon.cluster.agent/whoami)` string. That is the agent-units lane, not this one.

Green at the same run: `seon.bootstrap-test`, `seon.call-preparation-test`,
`seon.schema.datahike-test` (54 tests, 359 assertions with the two
render-simplification reds above as the only failures).

## Exact AI text for the example fixture

Produced by `my.plan/format-plan-ai` over `my.plan/plan` for the fixture tree in
`docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj`,
installed on a `seon.test-support/with-database` connection. Verbatim, no
normalization:

```text
Plan for juniper
Objective: Improve Juniper context inspection
Current step: Render this plan clearly

Steps:
1. Improve Juniper context inspection [juniper/understand-context] — open
     Make identity, messages, plans, changed results, and the eventual live agent context easy to inspect together.
     Done when: A clear paired context view whose facts and rendered results can be checked without guessing.
1.1 Inspect identity and messages [juniper/inspect-identity-messages] — completed
      Verify the fixture identity and the two sample messages in the paired AI and HTML blocks.
      Done when: The identity unit and both root messages were read on the context inspection page.
1.2 Render this plan clearly [juniper/render-plan] — current
      Replace raw entity numbers and dense prose with a readable current focus, progress summary, stable dependencies, and expandable step evidence.
      Done when: The AI plan is concise and actionable; the HTML plan shows progress and stable step references without numeric entity ids.
1.3 Compare refreshed results [juniper/compare-changed-results] — blocked — waiting for "juniper/render-plan"
      After context selections move from memory into durable agent-linked facts, change one relevant fact and compare the previous result with its automatically refreshed result.
      Done when: The comparison shows the previous and refreshed results together, with the relevant changed input.
1.4 Try the assembled context in a live agent turn [juniper/try-live-turn] — blocked — waiting for "juniper/compare-changed-results"
      After the visual and result checks pass, ask Juniper to find the same facts and update its own plan.
      Done when: Juniper identifies the current step and records a truthful plan update from the assembled context.

Ready now (1):
- Render this plan clearly [juniper/render-plan]

Waiting (2):
- Compare refreshed results [juniper/compare-changed-results] — waiting for "juniper/render-plan"
- Try the assembled context in a live agent turn [juniper/try-live-turn] — waiting for "juniper/compare-changed-results"

Recently finished (1):
- Inspect identity and messages [juniper/inspect-identity-messages]

Update the current step:
(seon.db/transact! [{:db/id [:seon.cluster.agent/id "juniper"], :my.plan/current-step [:my.plan.item/id "juniper/compare-changed-results"]}])
```

## Exact HTML Hiccup for the example fixture

Produced by `my.plan/render-plan-html`, pretty-printed by
`clojure.pprint/pprint`. Verbatim:

```clojure
[:section
 {:class "seon-family-entry my-plan"}
 [:h2 "juniper’s plan"]
 [:p
  {:class "my-plan-objective"}
  [:strong "Objective: "]
  "Improve Juniper context inspection"]
 [:p
  {:class "my-plan-progress"}
  [:strong "1 of 5 steps completed"]
  " · 1 ready · 2 blocked"]
 [:section
  {:class "my-plan-focus"}
  [:p {:class "my-plan-kicker"} "Current step"]
  [:h3 "Render this plan clearly"]]
 [:section
  {:class "my-plan-steps"}
  [:h3 "Steps (5)"]
  [:ol
   {:class "my-plan-tree"}
   [:li
    [:article
     {:class "seon-family-entry my-plan-item is-open"}
     [:p
      {:class "my-plan-id"}
      [:span {:class "my-plan-state"} "Open"]
      [:code "juniper/understand-context"]]
     [:h3 "Improve Juniper context inspection"]
     [:details
      [:summary "Details"]
      [:p
       "Make identity, messages, plans, changed results, and the eventual live agent context easy to inspect together."]
      [:p
       {:class "my-plan-expected"}
       [:strong "Done when: "]
       "A clear paired context view whose facts and rendered results can be checked without guessing."]
      [:p
       {:class "my-plan-reference"}
       [:strong "Reference "]
       [:code "[:my.plan.item/id \"juniper/understand-context\"]"]]]]
    [:ol
     {:class "my-plan-children"}
     [:li
      [:article
       {:class "seon-family-entry my-plan-item is-completed"}
       [:p
        {:class "my-plan-id"}
        [:span {:class "my-plan-state"} "Completed"]
        [:code "juniper/inspect-identity-messages"]]
       [:h3 "Inspect identity and messages"]
       [:details
        [:summary "Details"]
        [:p
         "Verify the fixture identity and the two sample messages in the paired AI and HTML blocks."]
        [:p
         {:class "my-plan-expected"}
         [:strong "Done when: "]
         "The identity unit and both root messages were read on the context inspection page."]
        [:p
         {:class "my-plan-relation"}
         [:strong "Part of "]
         [:code "juniper/understand-context"]]
        [:p
         {:class "my-plan-reference"}
         [:strong "Reference "]
         [:code
          "[:my.plan.item/id \"juniper/inspect-identity-messages\"]"]]]]]
     [:li
      [:article
       {:class "seon-family-entry my-plan-item is-current"}
       [:p
        {:class "my-plan-id"}
        [:span {:class "my-plan-state"} "Current step"]
        [:code "juniper/render-plan"]]
       [:h3 "Render this plan clearly"]
       [:details
        {:open true}
        [:summary "Details"]
        [:p
         "Replace raw entity numbers and dense prose with a readable current focus, progress summary, stable dependencies, and expandable step evidence."]
        [:p
         {:class "my-plan-expected"}
         [:strong "Done when: "]
         "The AI plan is concise and actionable; the HTML plan shows progress and stable step references without numeric entity ids."]
        [:p
         {:class "my-plan-relation"}
         [:strong "Part of "]
         [:code "juniper/understand-context"]]
        [:p
         {:class "my-plan-reference"}
         [:strong "Reference "]
         [:code "[:my.plan.item/id \"juniper/render-plan\"]"]]]]]
     [:li
      [:article
       {:class "seon-family-entry my-plan-item is-blocked"}
       [:p
        {:class "my-plan-id"}
        [:span {:class "my-plan-state"} "Blocked"]
        [:code "juniper/compare-changed-results"]]
       [:h3 "Compare refreshed results"]
       [:details
        [:summary "Details"]
        [:p
         "After context selections move from memory into durable agent-linked facts, change one relevant fact and compare the previous result with its automatically refreshed result."]
        [:p
         {:class "my-plan-expected"}
         [:strong "Done when: "]
         "The comparison shows the previous and refreshed results together, with the relevant changed input."]
        [:p
         {:class "my-plan-relation"}
         [:strong "Part of "]
         [:code "juniper/understand-context"]]
        [:p
         {:class "my-plan-relation"}
         [:strong "Waiting for "]
         "juniper/render-plan"]
        [:p
         {:class "my-plan-reference"}
         [:strong "Reference "]
         [:code
          "[:my.plan.item/id \"juniper/compare-changed-results\"]"]]]]]
     [:li
      [:article
       {:class "seon-family-entry my-plan-item is-blocked"}
       [:p
        {:class "my-plan-id"}
        [:span {:class "my-plan-state"} "Blocked"]
        [:code "juniper/try-live-turn"]]
       [:h3 "Try the assembled context in a live agent turn"]
       [:details
        [:summary "Details"]
        [:p
         "After the visual and result checks pass, ask Juniper to find the same facts and update its own plan."]
        [:p
         {:class "my-plan-expected"}
         [:strong "Done when: "]
         "Juniper identifies the current step and records a truthful plan update from the assembled context."]
        [:p
         {:class "my-plan-relation"}
         [:strong "Part of "]
         [:code "juniper/understand-context"]]
        [:p
         {:class "my-plan-relation"}
         [:strong "Waiting for "]
         "juniper/compare-changed-results"]
        [:p
         {:class "my-plan-reference"}
         [:strong "Reference "]
         [:code "[:my.plan.item/id \"juniper/try-live-turn\"]"]]]]]]]]]
 [:details
  {:class "my-plan-help"}
  [:summary "How to update this plan"]
  [:pre
   "(seon.db/transact! [{:db/id [:seon.cluster.agent/id \"juniper\"], :my.plan/current-step [:my.plan.item/id \"juniper/compare-changed-results\"]}])"]]]
```

## Gate

```text
bin/test my.plan-test
Ran 17 tests containing 146 assertions.
0 failures, 0 errors.
```
