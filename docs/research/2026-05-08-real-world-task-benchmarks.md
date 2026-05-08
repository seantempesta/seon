# Real-world task benchmarks for the agent's curriculum

**Date:** 2026-05-08
**Question:** which existing benchmarks have *tasks we could lift* into the the agent curriculum (not just methodology to borrow)? Skip coding benchmarks. Map findings to the prebuilt scenario taxonomy in `docs/2026-05-07-brainstorm-decisions.md` (memory/recall, working-memory pressure, cultural-judge, work-product, personal-vs-work boundary, self-cleanup).
**Method:** Three Gemini-3-pro surveys + WebSearch cross-validation on every benchmark cited as fresh or load-bearing. The Gemini-only earlier draft (`2026-05-08-non-coding-agent-benchmarks.md`) is a starting list and should be considered superseded by this file — kept for the appendix one-liners.

> **Caution carried forward.** Gemini's first pass invented arxiv IDs and dates for several entries. Every benchmark below was reverified against its own repo / paper before inclusion. Where I couldn't reverify, I either dropped it or moved it to the appendix with a `[unverified]` flag.

---

## 1. TL;DR — top 5 benchmarks with liftable tasks

If we want concrete *tasks* (not just methodology) for V1's curriculum, in priority order:

1. **AppWorld** (Stony Brook, ACL 2024 best resource, MIT). 9 daily-life apps populated by ~100 fictitious people with cross-app state (Gmail, Venmo, Spotify, Splitwise, Todoist, etc.). **Lift directly:** the entity-resolution + cross-app coordination tasks ("look up Splitwise debt → send Venmo with correct memo") map cleanly onto the agent's *multi-turn entity tracking* (scenario 2) and *work-product / tool-calling* family (scenarios 11, 14). Sandbox is reusable as a personas world.

2. **PersonalWAB** (WWW 2025 oral, MIT). 1,000 user profiles + 40K real web behaviors + 9K personalized instructions. **Lift directly:** profile-sensitive tasks where the agent must filter against persistent preferences ("vacuum cleaner under $200, but you only buy from eco-friendly bagless brands"). Maps to *backstory recall* (scenario 1), *surface stated value* (curriculum item from brainstorm-decisions). Closest existing analog to what the agent is trying to evaluate.

3. **LongMemEval** (ICLR 2025, MIT, last release Sept 2025). 500 questions over multi-session chat with 5 ability categories: extraction, multi-session reasoning, temporal reasoning, knowledge updates, **abstention**. **Lift directly:** every category. *Knowledge updates* is the contradiction-handling scenario (3); *abstention* is a working-memory-pressure check ("did you say 'I don't know' when the fact wasn't in context?"). 115K and 1.5M token configs both available.

4. **τ²-bench** (Sierra Research, MIT, active 2025). Dual-control conversational agents in airline / retail / telecom / banking / mock domains, with a stubborn user-simulator LLM and `pass^k` reliability metric. **Lift directly:** the *user-simulator pattern itself* validates the agent's persona-reactor architecture, and the policy-following customer-service tasks transplant well to the agent's *work-product* family (e.g., "follow the company travel policy when booking the trip"). Also relevant for *boundary refusal* (scenario 16) — task definitions explicitly include cases where the agent *must refuse* per policy.

5. **TheAgentCompany** (CMU, MIT, Dec 2024). Dockerized simulated software company with persistent NPCs, GitLab, Plane, ownCloud, Rocket.Chat. Best models hit ~24% — meaningful headroom. **Lift directly:** the *cross-tool* tasks ("ask HR-bot for onboarding PDF → extract policy → update wiki") map to multi-tool work-product (scenario 14) and to the personal-vs-work boundary (the NPC-colleague structure forces explicit who-can-see-what reasoning). The simulated-colleagues design is also the best public analog to the agent's persona-reactor as a *grading* counterparty.

The other ~25 benchmarks below are useful as a vocabulary check (what's the SOTA, what's the saturation level, what's gameable), but tasks are not as directly liftable.

---

## 2. Personal-maintenance benchmarks

Ordered by the agent-fit.

### AppWorld
- **Link:** https://github.com/StonyBrookNLP/appworld · paper https://arxiv.org/abs/2407.18901
- **License:** MIT (Apache-style permissive). **Last update:** Late 2024 (ACL 2024 best resource paper). **Stale-ish** by the 12-month rule but the API surface is large enough that staleness matters less than for benchmarks where saturation is the issue. GPT-4o still ~30%.
- **Concrete task:** *"Look at my Splitwise. Figure out exactly how much I owe John for last night's dinner. Then go to Venmo and send him that exact amount with the note 'Pizza'."* (Real example from the bench.)
- **Modality:** Python orchestration over 457 mocked APIs across 9 apps (Gmail, Amazon, Venmo, Spotify, Todoist, SimpleNote, Splitwise, file system, phone). No screenshots — pure tool calls.
- **Success:** Decidable. State-based — checks all backend DBs for both the intended mutation *and* "collateral damage" (did the agent also change something it shouldn't have?). The collateral-damage check is the rare design choice that maps onto the agent's *self-cleanup / failure-recovery* family (scenarios 17, 18) — it punishes side effects, not just final state.
- **Realism:** Synthetic but meticulously engineered — the simulated population has internally consistent transaction histories, social graphs, and inboxes.
- **Persistent state:** High. The fictitious-people world is the persistent state. Same world across tasks; tasks reference each other implicitly (you've split rent with this person before, etc.).
- **the agent-fit (specific):** the populated-world design is ~70% of what the agent's persona-reactor curriculum needs to construct anyway. We could plausibly *use AppWorld's environment* (or fork it) as the substrate for V1's work-product tasks, swapping in our persona-reactor for the scripted user. Direct map: scenario 2 (multi-turn entity), scenario 11 (email composition), scenario 14 (multi-tool workflow), scenario 17 (bad-assertion rollback — the collateral-damage check forces this).

### PersonalWAB
- **Link:** https://github.com/HongruCai/PersonalWAB · paper https://arxiv.org/abs/2410.17236 (WWW 2025 oral)
- **License:** MIT. **Last update:** mid-2025 (paper published WWW 2025). **Fresh.**
- **Concrete task:** *"Find a new vacuum cleaner on Amazon under $200."* The user profile (persistent across all tasks for that user) says: only buys eco-friendly, prefers bagless. The agent's job is to filter results and recommend / act consistent with the persistent preferences, not just the surface query.
- **Modality:** Text instruction + simulated web (search, recommendation, review tools). Three task tracks: personalized search, personalized recommendation, personalized review-generation.
- **Success:** Mixed — exact-match on action selection where decidable, LLM-judge on review-generation outputs. Also reports a "personalization gap" metric (perf with profile vs without).
- **Realism:** **High by design.** 40K behaviors scraped from real-world traces across 1,000 user profiles — not synthesized.
- **Persistent state:** **Very high — this is the benchmark's whole thesis.** Each user has a long behavior history; the benchmark explicitly penalizes ignoring it.
- **the agent-fit (specific):** the closest existing benchmark to the agent's central question. *"Did the agent honor the user's stated value?"* (curriculum item in brainstorm-decisions §scenario list). Can probably lift a few hundred (instruction, profile, expected-action) triples directly into V1's *backstory recall* and *surface-stated-value* scenarios. Caveat: profiles are shopping/web-behavior anchored, not cultural — won't help with the cultural-register cells.

### NaturalPlan
- **Link:** https://github.com/google-deepmind/natural-plan · paper https://arxiv.org/abs/2406.04520
- **License:** Apache 2.0. **Last update:** mid-2024. **Stale** by the 12-month rule, but the dataset itself isn't a freshness-sensitive thing — it's constraint-satisfaction puzzles that don't go out of date.
- **Concrete task:** *"Schedule a 45-minute sync with Sarah and David sometime next week. It must be before 3 PM, can't overlap with my Focus Time blocks, can't conflict with the kid's soccer pickup at 4 PM."* Three tracks: Trip Planning, Meeting Planning, Calendar Scheduling.
- **Modality:** Natural-language constraints + outputs from mocked Google Flights / Maps / Calendar provided as context.
- **Success:** **Decidable** — algorithmic constraint-satisfaction check. SOTA models below 5% on 10-city trip plans, so plenty of headroom.
- **Realism:** Constraints are realistic (the things people actually want); the tools are static text, not live APIs. Medium-high.
- **Persistent state:** Per-task only.
- **the agent-fit (specific):** Best existing source for *decidable scheduling tasks* with constraint structure. Maps onto a future curriculum scenario "calendar coordination across the persona's life" (currently absent from the brainstorm scenarios — flagged as a gap below). The constraint-satisfaction format also gives us a template for our own decidable goals.

### τ-bench / τ²-bench / τ³-bench
- **Link:** https://github.com/sierra-research/tau2-bench
- **License:** MIT (per repo). **Last update:** 2025–early 2026 active development; voice eval added; banking domain folded in. **Fresh.**
- **Concrete task (airline):** *User: "My flight to JFK tomorrow was delayed, I need to switch to the 6 PM flight, but I refuse to pay a change fee." Agent must: look up booking, check rebooking policy via API, execute the flight change, and waive the fee only if policy allows.* Includes user-simulator LLM that pushes back, gets confused, changes its mind.
- **Modality:** Multi-turn dialogue + simulated-API tool calls. τ²-bench adds dual-tool environments (user has tools too, e.g., for telecom troubleshooting). τ³ adds voice.
- **Success:** Decidable. Final-DB-state check. `pass^k` measures reliability across k independent runs of the same task — exposes inconsistency that single-shot accuracy hides. **This metric is the most directly steal-able idea in the whole survey** for the agent's training loop.
- **Realism:** Synthetic but anchored on real corporate policy structure. High for customer-service shape; the personas inside the customer roles are thin.
- **Persistent state:** Per-session account profiles, not cross-session.
- **the agent-fit (specific):** (a) the user-simulator-LLM-with-attitude pattern *validates the persona-reactor design* — Sean's tree-search-with-reactor architecture is essentially τ²-bench × MCTS. (b) Policy-following tasks lift to the agent's "follow the company's rules" cell (scenario 11 + the work-product family). (c) `pass^k` is a candidate scoring metric for our curriculum-survival gate.

### AndroidWorld
- **Link:** https://github.com/google-research/android_world · paper https://arxiv.org/abs/2405.14573 (ICLR 2025)
- **License:** Apache 2.0. **Last update:** ICLR 2025; ongoing community use, leaderboard updates through 2025–2026 (Droidrun at 91.4%). **Fresh-ish** but saturating.
- **Concrete task:** *"Open the Clock app, delete the 6:00 AM alarm, and create a new recurring alarm for 7:30 AM on weekdays."* 116 programmatic tasks, 20 real Android apps, dynamic parameterization (same task family with different concrete values).
- **Modality:** Live Android emulator. Screenshots + accessibility tree → taps/swipes.
- **Success:** Decidable via ADB system-state inspection.
- **Realism:** Very high — actual Android.
- **Persistent state:** Device state mutates within a task; cross-task is fresh-snapshot.
- **the agent-fit (specific):** **Mostly out of scope for V1** — the agent's modality is text + persona dialogue + (later) document images, not GUI control. But: the dynamic-parameterization design (template task → many concrete instantiations) is a useful pattern for the curriculum if we want to vary "user mentions kid's school turn 1" with hundreds of different schools/contexts cheaply. Note for V2/V3 if the agent ever gets a phone-control affordance.

### GAIA
- **Link:** https://huggingface.co/gaia-benchmark · paper https://arxiv.org/abs/2311.12983
- **License:** CC BY-NC 4.0 (non-commercial). **Last update:** 2023. **Stale**, and **saturated** — modern scaffolded agents now hit >70% on it. Used to be a frontier; now more of a regression test.
- **Concrete task:** *"Look at the attached spreadsheet of monthly expenses. Calculate the percentage difference in utility bills between January and March. Tell me if it exceeds my 10% variance rule."* Multi-modal — PDFs, Excel, audio.
- **Success:** Exact match on final answer. Decidable.
- **Realism:** High — uses real messy files.
- **Persistent state:** None — fully isolated.
- **the agent-fit:** **Low.** Saturated, no persona, single-turn. Pass on for curriculum lift; revisit only for the audio/Excel multimodal stretch.

### AssistantBench
- **Link:** https://github.com/oriyor/assistantbench · paper https://arxiv.org/abs/2407.15711
- **License:** MIT. **Last update:** mid-2024. **Stale.**
- **Concrete task:** *"Find me 3 real-estate agencies in downtown Austin that have active listings for 2-bedroom apartments under $3000/month, and return their phone numbers."* Live-web open research.
- **Success:** Exact-match / high-overlap on closed-form answers.
- **Realism:** High (live web) but suffers severe **link rot**.
- **Persistent state:** None.
- **the agent-fit:** Low for task lift (link rot makes tasks decay). Useful as a *templating source* — the question patterns are realistic information needs the agent's personas would actually have.

### TravelPlanner
- **Link:** https://github.com/osunlp/TravelPlanner · paper https://arxiv.org/abs/2402.01622
- **License:** CC BY-NC 4.0. **Last update:** 2024. **Stale-ish.**
- **Concrete task:** *"Plan a 5-day trip to Chicago. Budget $1,500. Vegan-friendly restaurants only. No attractions on the arrival-flight day."* 4M+ records of real flights/hotels/restaurants in a frozen sandbox.
- **Success:** Decidable — plan checked against constraints, common-sense rules, budget.
- **Realism:** Frozen real-data sandbox. Medium-high.
- **Persistent state:** None — persona constraints injected per task.
- **the agent-fit (specific):** Use the sandbox + constraint-checker as the *engine* for an the agent scenario "the persona is planning their kid's spring-break trip and has X constraints." The persona's stated values become the constraints; the agent's job is to draft the plan + answer follow-ups while honoring them. Cleaner work-product cell than starting from scratch.

### WebVoyager
- **Link:** https://github.com/MinorJerry/WebVoyager · paper https://arxiv.org/abs/2401.13919
- **License:** Apache 2.0. **Last update:** 2024. **Stale.**
- **Concrete task:** *"Go to Booking.com, find a 4-star hotel in Paris for next weekend under $300/night, proceed to checkout."*
- **Success:** **LLM-judge on final screen.** Gameable.
- **the agent-fit:** Low. Live web + LLM-judge = expensive and noisy. Pass for curriculum lift.

### Mind2Web / Mind2Web 2
- **Link:** https://github.com/OSU-NLP-Group/Mind2Web-2
- **License:** MIT. **Last update:** 2025 (V2). **Borderline-fresh.**
- **Concrete task:** *"Find a white bed frame, desk, chair from IKEA. Total budget for all three between $200–$600."*
- **Success:** Agent-as-judge in V2.
- **the agent-fit:** Same critique as WebVoyager (LLM-judge floor). Tasks are realistic but the eval doesn't ground hard.

### ClawsBench *(newcomer — flagged)*
- **Link:** https://github.com/benchflow-ai/ClawsBench · paper https://arxiv.org/abs/2604.05172 (April 2026)
- **License:** MIT. **Last update:** April 2026. **Fresh — released last month.**
- **Concrete task:** *"Check my recent emails for an invoice from the plumber. If found, download the PDF, save it to my 'Home Maintenance' Drive folder, and reply 'Received, will pay by Friday'."*
- **Modality:** Mock Gmail / Calendar / Slack / Docs / Drive with full state and snapshot/restore.
- **Success:** Decidable on task success **AND** an *unsafe-action rate* (did the agent delete something it shouldn't have, send to the wrong recipient, etc.). The dual metric maps onto the AppWorld collateral-damage idea but more focused on "agent did harm vs got the job done."
- **Realism:** Mock environments, but high-fidelity. 44 tasks (single-service, cross-service, safety-critical).
- **Persistent state:** Tasks rely on pre-existing inbox/folder state — modeled as persistent.
- **the agent-fit (specific):** **Strong fit but new** — only 44 tasks. The unsafe-action metric is the right shape for the agent's *boundary refusal* (scenario 16) and *self-cleanup* (scenarios 17, 18). Worth tracking but treat as supplemental, not foundational, until the task pool grows.

### AgentBench (Email environment)
- **Link:** https://github.com/THUDM/AgentBench · paper https://arxiv.org/abs/2308.03688
- **License:** Apache 2.0. **Last update:** 2023. **Very stale, saturated.**
- **Concrete task:** *"Search inbox for unread emails from HR, forward to personal address, mark read."*
- **the agent-fit:** Pass. Mock-text inbox is too thin compared to AppWorld/ClawsBench. Cited only because the "office subset" question came up.

### MMLU-style life-admin / household coordination
- Doesn't really exist as a standalone benchmark in this shape. The closest live items are the planning subtests of NaturalPlan and the household-app subset of AppWorld. **Honest gap noted in §6.**

---

## 3. Office / knowledge-work benchmarks

Ordered by the agent-fit.

### TheAgentCompany
- **Link:** https://github.com/TheAgentCompany/TheAgentCompany · paper https://arxiv.org/abs/2412.14161 · site https://the-agent-company.com/
- **License:** MIT. **Last update:** Dec 2024. **Stale-ish but headroom is enormous (best model 24%).**
- **Concrete task:** *"Log into Rocket.Chat, ask the HR bot for the new employee onboarding PDF, extract the vision-insurance policy details, and update the internal GitLab wiki with those details."* 175 tasks across SWE / PM / financial-analysis / HR / etc. roles.
- **Modality:** Real software in Docker — GitLab, Plane (project mgmt), ownCloud (drive), Rocket.Chat. Some tasks involve interacting with simulated NPC colleagues whose responses are scripted/LLM-generated.
- **Success:** Decidable — checkpoint scripts query the backend DBs of the running services.
- **Realism:** Among the highest in this whole survey. Real OSS enterprise tools, not mocks.
- **Persistent state:** Tasks reference each other; NPC colleagues persist across tasks within a session; corporate hierarchy is consistent.
- **the agent-fit (specific):** Best fit for the *company corpora as work-product encoding* idea in brainstorm-decisions §"Company corpora." Already does what we'd otherwise build: a fictitious company with persistent state, simulated colleagues, real cross-tool workflows. Direct lifts: scenarios 11 (email composition with retrieved facts), 12 (meeting prep summary — via Rocket.Chat + Plane data), 14 (multi-tool workflow), and 15 (personal-context-affecting-work-action — extend by adding personal-graph state on top of TheAgentCompany's worker profile). The simulated-colleagues design is also the closest precedent for our persona-reactor inside a work env.

### WorkArena / WorkArena++
- **Link:** https://github.com/ServiceNow/WorkArena · paper https://arxiv.org/abs/2403.07718 · WorkArena++ NeurIPS 2024 paper
- **License:** Apache 2.0 / MIT. **Last update:** Mid-2025 (results stable since mid-2025). **Borderline-fresh.**
- **Concrete task:** *"Navigate to the ServiceNow hardware catalog, find the Apple MacBook Pro 15-inch, configure with 16GB RAM, submit IT request on behalf of user 'John Doe'."* WorkArena++ adds 682 multi-step compositional tasks (planning, retrieval, arithmetic, cross-session memory).
- **Modality:** Browser DOM / accessibility tree / screenshots, via BrowserGym.
- **Success:** Decidable — final ServiceNow backend state.
- **Realism:** High — runs on real ServiceNow developer instances.
- **Persistent state:** Low to medium per task; WorkArena++ adds cross-browser-session memory in some tasks.
- **the agent-fit (specific):** Useful for *enterprise workflow shape* — IT requests, catalog ordering, ticket triage — but ServiceNow-flavored and UI-heavy. WorkArena++'s memory-required tasks are the relevant slice for the agent. Direct map: scenario 14 (multi-tool workflow). Probably pass for direct lift (UI control isn't the agent's modality), but the *task families* are reusable as templates.

### OSWorld / OSWorld-Verified
- **Link:** https://github.com/xlang-ai/OSWorld · paper https://arxiv.org/abs/2404.07972
- **License:** MIT. **Last update:** Mid-2025 (Verified version fixed many ambiguous-instruction issues). **Borderline-fresh.**
- **Concrete task:** *"Open LibreOffice Calc, merge data from Q1_sales.csv and Q2_sales.csv on the desktop, generate a bar chart comparing regional revenue, save as PDF."*
- **Success:** Decidable — VM-side scripts check file content / system state.
- **Realism:** Very high — real Ubuntu/Windows/macOS VMs.
- **Persistent state:** Fresh snapshot per task.
- **the agent-fit:** Out of scope for V1's modality. Note for the future as the canonical desktop-control benchmark.

### Windows Agent Arena
- **Link:** https://github.com/microsoft/WindowsAgentArena
- **License:** MIT. **Last update:** Sep 2024. **Stale.**
- **the agent-fit:** Same as OSWorld — out of V1's modality. Pass.

### OfficeBench
- **Link:** https://github.com/zlwang-cs/OfficeBench
- **License:** MIT. **Last update:** Mid-2024. **Stale.**
- **Concrete task:** *"Extract the list of attendees from the provided meeting notes (Word), look up their email addresses in the contact list (Excel), draft an email to all of them with the summary attached."*
- **Success:** Mixed. LLM-judge on generated text **— gameable.**
- **the agent-fit:** Workflow shape is right but the LLM-judge dilutes the signal. Useful as a *task-template source*, not as a scored benchmark.

### SpreadsheetBench
- **Link:** https://github.com/shortcut-ai/spreadsheetbench-verified
- **License:** MIT. **Last update:** Early 2025. **Borderline-stale.**
- **Concrete task:** *"Given an HR spreadsheet with employee start dates and salaries, write a formula or macro to calculate the prorated bonus for employees who joined after June 1st, outputting to Column E."* Sourced from real Excel-help-forum questions.
- **Success:** **Decidable, Online-Judge style** — multiple hidden test fixtures per task. Hard to game.
- **Realism:** Very high (real user questions).
- **the agent-fit (specific):** Strong shape match for *decidable work-product* tasks but only if the agent's curriculum includes spreadsheet manipulation. **Open question for the V1 scenario list:** does the persona's job involve spreadsheets? If yes, lift directly. If we pick a non-spreadsheet job for V1's persona, defer.

### WebArena / VisualWebArena / WebArena-Verified
- **Link:** https://github.com/web-arena/webarena (also ServiceNow's verified fork)
- **License:** MIT. **Last update:** Late 2024. **Stale.**
- **Concrete task:** *"Log into the mock Reddit, find the most upvoted post about 'budget monitors' last week, find that monitor on the mock e-commerce site, add to cart."*
- **Success:** Decidable (mock-DB state).
- **the agent-fit:** Mock-web isn't the agent's modality. Pass for direct lift; useful as a precedent for "self-hosted environment with verifiable end state."

### BrowseComp (OpenAI)
- **Link:** https://github.com/openai/simple-evals · BrowseComp-Plus addresses link rot
- **License:** MIT. **Last update:** April 2025. **Stale-borderline.**
- **Concrete task:** *"According to the PDF buried in the 2023 Springfield City Council meeting minutes, what was the exact budget for the new park fountain?"* Deep needle-in-a-haystack research.
- **Success:** Exact-match short answer.
- **the agent-fit:** Useful as a *templating source* for the persona's "research a thing for me" requests. Don't lift the live-web tasks (link rot); BrowseComp-Plus's frozen corpus is better.

### FinanceBench (Patronus)
- **Link:** https://github.com/patronus-ai/financebench
- **License:** Apache 2.0. **Last update:** Late 2023. **Stale.**
- **Concrete task:** *"Based on Boeing's 2021 10-K, what was the year-over-year percentage change in total commercial airplane deliveries?"*
- **Success:** **LLM-judge against rubric** — gameable, plus pretrained models have likely memorized the underlying 10-Ks.
- **the agent-fit:** Low. Tasks are RAG-shaped, not agent-shaped, and the eval is gameable.

### DocBench
- **Link:** https://github.com/Anni-Zou/DocBench
- **License:** MIT. **Last update:** Mid-2024. **Stale.**
- **Concrete task:** *"In this 40-page legal contract, identify all termination clauses that don't require a 30-day notice."*
- **Success:** Mixed (exact match for extraction; LLM-judge for reasoning).
- **the agent-fit:** Useful for the *artifact-decode* shape (compressed document → answer) — supports the OCR-investigation arm in brainstorm-decisions. Not core to V1 curriculum.

### MP-DocVQA
- **Link:** https://rrc.cvc.uab.es/?ch=17
- **License:** Research-only.
- **Last update:** 2023. **Stale.**
- **the agent-fit:** OCR-pipeline reference dataset. Low for direct V1 task lift; relevant to the multimodal-compression investigation arm.

### MeetingBench / QMSum / AMI / MeetingBank
- These are static NLP summarization datasets, not agentic benchmarks. **Last update:** 2021–2023. **Stale.**
- **Example task:** *"Given the 2-hour transcript of this product design meeting, summarize the decisions made about the UI color scheme and list action items assigned to Sarah."*
- **Success:** ROUGE/BERTScore or LLM-judge.
- **the agent-fit:** Useful as a *transcript source* for synthetic meeting-prep scenarios (12) — pull the transcript, give it to the persona-reactor, ask the agent to prep the persona. Don't use the eval as scoring; build our own decidable check (did the briefing include the right action items keyed to the persona's role?).

### SUPER (AI2)
- **Link:** https://github.com/allenai/super-benchmark
- **License:** Apache 2.0. **Last update:** Late 2024. **Stale.**
- **the agent-fit:** Bordering on coding (clone repo, fix dependencies, run script). Out of scope per the user's "skip coding" rule. Pass.

### AgentBench (DB / OS subsets)
- **Last update:** 2023. Saturated. Pass.

---

## 4. Multimodal / document-flow benchmarks

For the agent's OCR-pipeline / artifact-recall layer (V2 territory but worth scoping now).

### DocVQA
- **Link:** https://docvqa.org/
- **License:** CC BY 4.0 / Apache 2.0. **Last update:** 2026 (ICDAR 2026 challenge). **Fresh.**
- **Example:** *"Who signed the memo on page 3 of this PDF?"*
- **Success:** ANLS (decidable).
- **the agent-fit:** Foundation dataset for the OCR investigation arm (brainstorm-decisions §"DeepSeek-OCR"). Low for V1; revisit when the OCR arm produces a checkpoint to evaluate.

### CORD / SROIE / FUNSD
- Receipt / form extraction. CORD https://huggingface.co/datasets/naver-clova-ix/cord-v2 (CC BY-SA), SROIE (MIT, 2019), FUNSD (custom non-commercial).
- All **stale** but still standard. Useful only if the agent's personas have personal-finance / expense-report scenarios in V1 — currently absent from the brainstorm scenarios. Flagged below.

### ChartQA / ChartQAPro
- **Link:** https://github.com/vis-nlp/ChartQA. ChartQAPro updated July 2025. **Borderline-fresh.**
- **Example:** *"What was the percentage change in Q3 revenue compared to Q1 based on this bar chart?"*
- **the agent-fit:** Useful for the "photo I sent my AI" shape (someone sends the agent a screenshot of a dashboard). Tier-2 priority.

### ScreenSpot / ScreenSpot-Pro
- **Link:** https://github.com/ScreenSpot
- **License:** MIT. **Last update:** Early 2025. **Borderline-stale.**
- **the agent-fit:** Pure GUI-grounding. Out of V1 modality. Pass.

### VisualWebBench / VL-RewardBench
- Both **stale**. Pass.

---

## 5. Long-context / persistent-memory benchmarks

The closest existing analogs to what the agent is actually trying to do.

### LongMemEval
- **Link:** https://github.com/xiaowu0162/LongMemEval · paper https://arxiv.org/abs/2410.10813 (ICLR 2025)
- **License:** MIT. **Last update:** Sept 2025. **Fresh.**
- **Concrete task examples (5 ability categories):**
  - *Information extraction:* "What did I say my doctor's name was?" (multi-session recall).
  - *Multi-session reasoning:* "Given things I've said across our last 6 sessions, would I prefer the morning or evening flight?"
  - *Temporal reasoning:* "When did I last mention my dentist appointment?"
  - *Knowledge updates:* user said X in session 2, ¬X in session 5 — agent must track the update.
  - *Abstention:* "What's my mother's maiden name?" — agent should say *I don't know* if it was never mentioned, **not hallucinate**.
- **Modality:** Text-only multi-session chat with realistic distractors. Two configs: 115K tokens (LongMemEval_S) and ~1.5M tokens (LongMemEval_M).
- **Success:** Decidable (F1 / exact-match / explicit abstention rate).
- **Realism:** Synthetic but multi-session-modeled with persona grounding.
- **Persistent state:** Multi-session by construction.
- **the agent-fit (specific):** **Highest direct mapping of any benchmark surveyed.** The five categories map almost 1-to-1 to brainstorm-decisions scenarios:
  - Information extraction → scenario 2 (multi-turn entity tracking)
  - Multi-session reasoning → scenario 1 (backstory recall) + scenario 4 (long-arc theme)
  - Temporal reasoning → scenario 4
  - Knowledge updates → scenario 3 (contradiction handling)
  - Abstention → scenario 6 (forced fuzzy-recall) + the working-memory-pressure family
  - This is the candidate for "the personalization-quality benchmark" gap (open question 14 in CLAUDE.md). We can probably ship V1 with LongMemEval as the externally-recognizable scoring overlay on top of our own curriculum.

### LoCoMo
- **Link:** https://snap-research.github.io/locomo/ · paper https://arxiv.org/abs/2402.17753
- **License:** Apache-style (per repo). **Last update:** community use through 2025; original 2024. **Borderline.**
- **Concrete task:** very-long-term conversation (avg 300 turns / 9K tokens / up to 35 sessions) with QA + event-summarization + multi-modal-dialogue.
- **Success:** Mixed — decidable on QA, LLM-judge on summarization.
- **Realism:** Machine-human pipeline grounded on personas + temporal event graphs.
- **the agent-fit (specific):** Persona-grounded multi-session is exactly the agent's setting. Use the dataset as a *secondary* eval alongside LongMemEval. The persona-temporal-event-graph generation pipeline is also worth studying — overlaps heavily with our synthetic-persona generation approach.

### MemBench
- **Last update:** July 2025 (ACL findings). **Fresh.** Real, but exact arxiv ID was wrong in Gemini's first pass — verify before citing.
- **the agent-fit:** Reflective-memory tasks are LLM-judge ("based on past stories, am I an introvert?") — gameable but interesting for the cultural-judge gate. Tier-2.

### Letta / MemGPT eval suite
- **Link:** https://github.com/letta-ai/letta · Apache 2.0. **Active 2026.**
- **the agent-fit:** Letta provides JSON-state-diffing eval over an agent's self-edited memory blocks. The *eval pattern* (state-diff against expected) is reusable for the agent's `assert/retract` decidability checks. **Don't use Letta's memory API itself** — brainstorm-decisions explicitly rejects fixed memory APIs in favor of agent-learned idiom. Take the eval shape, not the system.

### MSC (Multi-Session Chat) — Meta ParlAI
- **Last update:** 2024 / early 2025. **Stale.**
- **the agent-fit:** Crowdsourced human-human chats are higher-quality persona data than purely-synthetic. Could be a calibration source for our multi-LLM persona generation. Tier-2.

### LongBench v2 / RULER
- Long-context recall benchmarks (no persona). RULER is purely synthetic needle-in-haystack. Pass for direct lift; useful as a long-context regression test.

### AlpsBench *(newcomer — verified)*
- **Link:** https://arxiv.org/abs/2603.26680 (verified by web search — exists, recent).
- **License:** CC BY-NC 4.0 (per first-pass note; verify if licensing is load-bearing).
- **Concrete task:** 2,500 long-term interaction sequences from WildChat real human-LLM dialogues, paired with human-verified structured memories. Four task families: personalized info extraction, updating, retrieval, utilization.
- **Success:** Mixed — decidable on extraction/update/retrieval, judge-on-quality for utilization.
- **Realism:** **Real WildChat dialogues, human-verified memories.** Among the highest realism in the personalization category.
- **the agent-fit (specific):** Maps onto the brainstorm-decisions §scenario list almost as cleanly as LongMemEval. The "extract → update → retrieve → utilize" lifecycle is exactly what the agent's `assert/retract/query/project` primitives are designed for. **Strong candidate as a secondary external eval.** Caveat: 2,500 sequences may overlap with personas the agent's own pipeline generates — check for contamination if used.

### PerLTQA
- Mentioned; actual benchmark with that exact name not robustly verified in WebSearch — treat as unverified, in the appendix.

---

## 6. Personalization / cultural benchmarks

### LaMP (Language Model Personalization)
- **Link:** https://lamp-benchmark.github.io/
- **License:** CC BY-NC-SA 4.0. **Last update:** May 2025 (LaMP-QA). **Fresh.**
- **Example:** *"Draft an email response in the user's stylistic tone, given 5 prior emails."*
- **Success:** ROUGE/BLEU vs ground-truth + LLM-judge.
- **Realism:** Real user data profiles (with non-commercial license).
- **the agent-fit (specific):** Closest existing benchmark for *style/voice consistency over a user's history* — a piece of the user-as-protagonist promise. Useful as a baseline for the agent's "respond in this persona's voice" tasks. Caveat: LaMP measures matching *the user's own past output*, which is a weaker signal than the agent's "respond *for* the user in a way they'd endorse" goal.

### CulturalBench
- **Link:** https://arxiv.org/abs/2410.02677 (ACL 2025). Active red-teaming process.
- **License:** unverified beyond paper (CC BY-style typical for ACL).
- **Example:** Cultural-knowledge MCQ — gift-giving norms across regions, etc.
- **Success:** MCQ (decidable).
- **the agent-fit (specific):** Knowledge-test, not register-test. Useful as a *floor check* for the cultural-native graders ("does the Falcon-Ar grader actually know KSA cultural facts?") but doesn't measure register / hedging / hierarchy posture, which is what scenarios 8–10 actually test.

### ACVA (Arabic Cultural Value Alignment)
- **Last update:** 2024 (folded into OALL v2 in 2025).
- **License:** Apache 2.0.
- **the agent-fit (specific):** Useful for KSA validation of the persona-reactor and grader. **Not a substitute for native-reader veto** — ACVA is MCQ; cultural-judge in the agent's curriculum is open-generation register evaluation. Use ACVA as the floor-test that the Falcon-Ar grader is at least Arab-cultural-knowledge-aware before trusting it on register.

### AlGhafa
- **Link:** TII repos. **Last update:** 2024. **Stale.**
- **the agent-fit:** Same role as ACVA — Arabic-language floor-check. Pass for direct task lift.

### CulturALL *(verified — exists)*
- **Link:** https://arxiv.org/abs/2604.19262
- **Modality:** Multilingual grounded tasks across 14 languages / 51 regions / 16 topics. Best LLM at 44.48%.
- **the agent-fit:** Useful as a *cross-cultural calibration set* for V1's KSA + Egyptian-American + (stretch) France/Japan cultural pair. Tier-2 — supplemental scoring overlay.

### PersonaBench / RoleLLM / PrefEval
- All real but **stale and synthetic-persona based.** PersonaBench measures persona-consistency drift across 20-turn adversarial interview — useful pattern for the agent's per-turn anchor re-injection robustness check. PrefEval measures preference-following ("always respond in bullet points") — too superficial for the agent's value-honoring scenarios. Tier-2 / pass.

---

## 7. The honest gaps

Areas where no good external benchmark exists for what the agent's curriculum actually wants to measure. **the agent likely needs to build these.**

1. **Cultural-register evaluation in open generation, graded by native readers.** Every existing cultural benchmark is MCQ or has Western-LLM judges. CulturalBench, ACVA, CulturALL all fall short. *Native-reader veto + Falcon-Ar grader as articulated in brainstorm-decisions is novel — there's no existing benchmark to copy.* This is open question 14 in CLAUDE.md and the survey confirms the gap is real.

2. **Personal-vs-work boundary leakage.** No existing benchmark measures "did the agent leak personal context to the corporate tool?" ClawsBench's unsafe-action rate is the closest cousin but its safety axis is more around "did you delete the wrong email" than "did you reveal user mood to the boss." Scenario 16 (boundary refusal) is essentially uncovered. **the agent has to design its own decidable check** — probably a corporate-tool-call-log inspector that flags any string from the personal-graph.

3. **Long-arc narrative coherence (McAdams Level 3).** No benchmark grades whether an agent surfaced the right life-arc moment when the persona later asked. LongMemEval's multi-session reasoning is the closest but it's surface-fact reasoning, not narrative-arc reasoning. Open question 22 in CLAUDE.md (McAdams Level 2 vs Level 3) — survey confirms there's no off-the-shelf dataset that distinguishes them.

4. **Working-memory pressure as adaptive triage.** Existing long-context benchmarks (RULER, LongBench, LongMemEval-M) test recall under increasing context length but don't test the *triage decision* — "you're approaching the limit, what do you compress vs evict vs persist?" Curriculum scenarios 5–7 in brainstorm-decisions are uncovered.

5. **Cross-tool retrieval routing.** Brainstorm-decisions §"Agent learns to route between retrieval mechanisms" — graph vs vector vs OCR-compressed. No benchmark measures retrieval-strategy choice, just retrieval-quality given a fixed strategy. AppWorld is the closest in *spirit* (multi-tool composition under verification) but doesn't expose three retrieval primitives the agent must choose between.

6. **Calendar/personal-admin with persistent persona constraints.** NaturalPlan is per-task; PersonalWAB is web-shopping anchored. No benchmark threads persistent calendar preferences across tasks the way a real personal AI would need to.

7. **Multimodal recall (the OCR-substrate experiment).** No benchmark tests "compress this image stack into language vectors, then answer a recall question requiring decode." DocVQA tests document QA, MP-DocVQA tests multi-page, but none stage the compression-then-recall pattern. The investigation arm in brainstorm-decisions §"DeepSeek-OCR memory-substrate experiment" will need its own eval rig.

---

## 8. Appendix — passed-on benchmarks

One-liners so we don't relitigate. (More-or-less ordered.)

- **VisualWebArena, VisualWebBench, ScreenSpot, ScreenSpot-Pro** — GUI-grounding, not the agent's modality.
- **WebVoyager** — LLM-judge on final screen; gameable.
- **GAIA** — saturated; modern scaffolds >70%.
- **AssistantBench** — link rot on live web makes tasks decay.
- **AgentBench (any subset)** — saturated, simplified mocks.
- **Windows Agent Arena, OSWorld** — desktop GUI control, out of V1 modality.
- **WebArena / WebArena-Verified** — mock-web shape, lower realism than AppWorld for the agent's purposes.
- **FinanceBench** — RAG-shaped; LLM-judge; pretrain contamination on 10-Ks.
- **MeetingBank / QMSum / AMI** — static summarization, not agentic; useful only as transcript source.
- **SUPER** — bordering on coding; out of "skip coding" rule.
- **CORD / SROIE / FUNSD** — receipt extraction; only relevant if expense-report scenarios make V1.
- **PerLTQA** — couldn't verify a canonical artifact; treating as unverified.
- **Mem0 standalone benchmark** — Gemini hedged; no public standardized eval suite found. Mem0's own claims are evaluated against LongMemEval / LoCoMo, not a Mem0-named benchmark. Pass.
- **EpiSCQA** — pre-2024, stale.
- **PersonaChat** — 2018-era, mostly subsumed by MSC/LoCoMo.
- **DUE Benchmark** — 2021, stale, aggregate of older docs.
- **AppAgent / AppAgentX, AutoUI / AITW** — Android pixel-only, V2+ territory.
- **PrefEval, RoleLLM, PersonaBench** — stale or superficial-preference-only.
- **AlGhafa** — Arabic-knowledge floor-check; supplementary at best.

---

## 9. Suggested follow-ups (for the next pass)

1. **Stand up LongMemEval + AlpsBench + PersonalWAB locally** as the agent's external scoreboard. They cover the memory + personalization axes our own curriculum measures.
2. **Fork or instrument AppWorld's environment** to host the agent's persona-reactor instead of scripted users. Cheaper than building the populated-world from scratch.
3. **Check TheAgentCompany's NPC-colleague design** for ideas on how to layer the agent's persona-reactor into a *work* environment (gives us scenario 15 — personal-context-shaping-work-action — concretely).
4. **Decide whether the V1 persona has a spreadsheet job** — if so, SpreadsheetBench is liftable; if not, defer.
5. **Build the boundary-leakage decidable check** ourselves — survey confirms no precedent. This becomes part of V1's defensible novelty (and was already flagged as such in §"Sovereign memory" interposition story).
