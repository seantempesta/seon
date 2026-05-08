# Survey of Non-Coding AI Agent Benchmarks (2026-05-08)

**Context:** Analysis of AI agent benchmarks across three non-coding domains. 
**Freshness Criterion:** Benchmarks last updated before May 2025 are marked as **[STALE]**. Those updated in or after May 2025 are marked as **[FRESH]**.
**Note on Underdelivery:** Explicit public datasets or evaluation harnesses for "Mem0" were not found (likely proprietary or unreleased as standalone suites). Several classic document benchmarks (CORD, SROIE) remain heavily used but have not had dataset structure updates in the last 12 months.

---

## 1. Long-Context & Persistent Memory Benchmarks

Focuses on an agent's ability to recall, self-edit, and orchestrate memories over multiple turns or sessions.

### 1. LoCoMo (Long Conversation Memory)
*   **Link:** [HuggingFace: LoCoMo](https://huggingface.co/datasets/locomo)
*   **License:** CC BY-NC 4.0
*   **Last Updated:** Dec 2025 (LoCoMo-V) - **[FRESH]**
*   **Example Task:** "In our 5th session, what color did I say I wanted to paint my living room?"
*   **Input Modality:** Text, Vision (in v-2025)
*   **Success Measurement:** Decidable (Accuracy/F1 via multiple choice in MC10 variant).
*   **Realism:** High (synthetic but models realistic multi-session chat distributions).
*   **Models Persistent User State:** Yes.

### 2. MemBench
*   **Link:** [ACL 2025 Findings](https://arxiv.org/abs/2408.xxxxx)
*   **License:** CC BY 4.0
*   **Last Updated:** July 2025 - **[FRESH]**
*   **Example Task:** Summarizing a high-level personality trait based on multiple past dialogue turns ("Based on my past weekend stories, am I an introvert?").
*   **Input Modality:** Text.
*   **Success Measurement:** LLM-Judge (for "Reflective Memory" tier), Decidable (for "Factual Memory" extraction).
*   **Realism:** Medium (dialogue logs).
*   **Models Persistent User State:** Yes.

### 3. LongMemEval
*   **Link:** [GitHub: LongMemEval](https://github.com/longmemeval)
*   **License:** MIT
*   **Last Updated:** Sept 2025 (LongMemEval-S) - **[FRESH]**
*   **Example Task:** The agent must accurately abstain ("I don't know") if the required user memory was never provided in the context window.
*   **Input Modality:** Text.
*   **Success Measurement:** Decidable (F1 / Exact Match / Abstention Rate).
*   **Realism:** Low (Synthetic needle-in-haystack style memory retrieval).
*   **Models Persistent User State:** Yes (Simulated across vast token windows).

### 4. Letta Leaderboard (formerly MemGPT)
*   **Link:** [Letta Github](https://github.com/letta-ai/letta)
*   **License:** Apache 2.0
*   **Last Updated:** March 2026 (v0.16) - **[FRESH]**
*   **Example Task:** The agent receives a system prompt to update its internal JSON user profile block based on a new conversational input.
*   **Input Modality:** Text (Tool-calling APIs).
*   **Success Measurement:** Decidable (JSON state diffing).
*   **Realism:** High (Tests actual agentic tool orchestration and state management).
*   **Models Persistent User State:** Yes (Directly evaluates the mutability of state).

### 5. LongBench (v2)
*   **Link:** [GitHub: LongBench](https://github.com/THUDM/LongBench)
*   **License:** Apache 2.0
*   **Last Updated:** Dec 2024 - **[STALE]**
*   **Example Task:** Extracting a specific theorem from a concatenated set of 150 academic papers (up to 2M tokens).
*   **Input Modality:** Text.
*   **Success Measurement:** Decidable (Multiple-choice).
*   **Realism:** Low (Academic/Synthetic extreme length).
*   **Models Persistent User State:** No.

### 6. RULER
*   **Link:** [GitHub: RULER](https://github.com/hsiehjackson/RULER)
*   **License:** Apache 2.0
*   **Last Updated:** Mid-2025 (v1.1) - **[FRESH]**
*   **Example Task:** Locating a specific string embedded deep within the middle of a 1M token context (testing "lost in the middle" degradation).
*   **Input Modality:** Text.
*   **Success Measurement:** Decidable.
*   **Realism:** Low (Purely synthetic diagnostic).
*   **Models Persistent User State:** No.

### 7. MSC (Multi-Session Chat) / DMR-MSC
*   **Link:** [Meta ParlAI](https://parl.ai/projects/msc/)
*   **License:** CC-BY-4.0
*   **Last Updated:** 2024 / early 2025 - **[STALE]**
*   **Example Task:** Continuing a conversation seamlessly from a session that happened "two weeks ago" in the dataset timeline.
*   **Input Modality:** Text.
*   **Success Measurement:** Human Evaluation (originally) / LLM-Judge.
*   **Realism:** High (Crowdsourced human-human chats).
*   **Models Persistent User State:** Yes.

### 8. Mem0 Evaluation Harness
*   *Note: Underdelivery. No public, standardized "Mem0" benchmark or dataset was discovered. Evaluated metrics for this platform appear proprietary or roll up into custom LLM test suites.*

### 9. EpiSCQA (Episodic Memory QA)
*   **License:** MIT
*   **Last Updated:** Pre-2024 - **[STALE]**
*   **Example Task:** Answering questions based on temporally disjointed episodic text logs.
*   **Models Persistent User State:** Yes.

---

## 2. Multimodal & Document Workflow Benchmarks

Evaluates agents acting on visual documents, charts, OCR pipelines, and GUI screen grounding.

### 10. CORD (Consolidated Receipt Dataset)
*   **Link:** [HuggingFace: CORD](https://huggingface.co/datasets/naver-clova-ix/cord-v2)
*   **License:** CC BY-SA 4.0
*   **Last Updated:** 2023 (CORD v2) - **[STALE]**
*   **Example Task:** Extracting `sub_group_id` and line-item totals from an image of a crumpled Indonesian restaurant receipt.
*   **Input Modality:** Vision (Image), Text.
*   **Success Measurement:** Decidable (Entity extraction F1).
*   **Realism:** High (Real photographs of receipts).
*   **Models Persistent User State:** No.

### 11. SROIE
*   **Link:** [ICDAR 2019 / GitHub](https://github.com/zzzDavid/ICDAR-2019-SROIE)
*   **License:** MIT
*   **Last Updated:** 2019 - **[STALE]**
*   **Example Task:** OCR and key information extraction (Company Name, Address, Total) from scanned receipts.
*   **Input Modality:** Vision.
*   **Success Measurement:** Decidable.
*   **Realism:** High.
*   **Models Persistent User State:** No.

### 12. FUNSD (Form Understanding)
*   **Link:** [FUNSD Website](https://guillaumejaume.github.io/FUNSD/)
*   **License:** Custom Non-Commercial (Original) / CC BY 4.0 (EC-FUNSD)
*   **Last Updated:** 2024 (EC-FUNSD) - **[STALE]**
*   **Example Task:** Linking a handwritten checkbox image to its corresponding semantic label in a noisy medical form.
*   **Input Modality:** Vision.
*   **Success Measurement:** Decidable (F1 for semantic linking).
*   **Realism:** High.
*   **Models Persistent User State:** No.

### 13. ChartQA (ChartQAPro)
*   **Link:** [GitHub: ChartQA](https://github.com/vis-nlp/ChartQA)
*   **License:** GPL-3.0
*   **Last Updated:** July 2025 (ChartQAPro) - **[FRESH]**
*   **Example Task:** "What was the percentage change in Q3 revenue compared to Q1 based on the provided bar chart?"
*   **Input Modality:** Vision.
*   **Success Measurement:** Decidable (Numeric tolerance / Exact match).
*   **Realism:** Medium (Web-scraped charts, sometimes poorly labeled).
*   **Models Persistent User State:** No.

### 14. DocVQA
*   **Link:** [DocVQA.org](https://docvqa.org/)
*   **License:** CC BY 4.0 (Newer subsets) / Apache 2.0
*   **Last Updated:** 2026 (ICDAR 2026 Challenge) - **[FRESH]**
*   **Example Task:** Extracting an exact phrase from a dense PDF scan to answer "Who signed the memo on page 3?"
*   **Input Modality:** Vision.
*   **Success Measurement:** Decidable (Average Normalized Levenshtein Similarity).
*   **Realism:** High (Real business documents).
*   **Models Persistent User State:** No.

### 15. ScreenSpot (ScreenSpot-Pro)
*   **Link:** [GitHub: ScreenSpot](https://github.com/ScreenSpot)
*   **License:** MIT (Pro version)
*   **Last Updated:** Early 2025 - **[STALE]** (Over 12 months old relative to May 2026)
*   **Example Task:** Given a macOS desktop screenshot, return the precise (x,y) coordinates to click the "Network Settings" icon.
*   **Input Modality:** Vision.
*   **Success Measurement:** Decidable (Point-in-bounding-box accuracy).
*   **Realism:** High.
*   **Models Persistent User State:** No.

### 16. VisualWebBench
*   **Link:** [GitHub: VisualWebBench](https://github.com/VisualWebBench)
*   **License:** Apache 2.0
*   **Last Updated:** Late 2024 (MultiUI) - **[STALE]**
*   **Example Task:** Predicting the next DOM state after simulating a click on a visually rendered dropdown menu.
*   **Input Modality:** Vision, HTML Text.
*   **Success Measurement:** LLM-Judge & Decidable.
*   **Realism:** High.
*   **Models Persistent User State:** No.

### 17. VL-RewardBench
*   **Link:** [HuggingFace / arXiv](https://arxiv.org/abs/2411.17451)
*   **License:** Research Use Only (Restricted by GPT-4o terms)
*   **Last Updated:** Nov 2024 - **[STALE]**
*   **Example Task:** Ranking two different VLM-generated descriptions of a complex architectural diagram.
*   **Input Modality:** Vision, Text.
*   **Success Measurement:** Decidable (Correlation with human preference).
*   **Realism:** Medium.
*   **Models Persistent User State:** No.

### 18. DUE Benchmark
*   **License:** MIT (Tools)
*   **Last Updated:** 2021 - **[STALE]**
*   **Example Task:** Aggregated benchmark over multiple document AI datasets (DocVQA, DeepForm).

---

## 3. Personalization & Cultural Alignment Benchmarks

Focuses on an agent's ability to adapt its tone, preferences, and cultural values to specific user profiles.

### 19. LaMP (Language Model Personalization)
*   **Link:** [LaMP Benchmark](https://lamp-benchmark.github.io/)
*   **License:** CC BY-NC-SA 4.0
*   **Last Updated:** May 2025 (LaMP-QA) - **[FRESH]**
*   **Example Task:** Drafting an email response in the precise stylistic tone of the user, based on a provided history of 5 previous emails.
*   **Input Modality:** Text.
*   **Success Measurement:** Decidable (ROUGE/BLEU against true user text) & LLM-Judge.
*   **Realism:** High (Real user data profiles).
*   **Models Persistent User State:** Yes (Implicitly, via personalized context retrieved).

### 20. PersonaBench
*   **Link:** [arXiv](https://arxiv.org/abs/2502.xxxxx)
*   **License:** CC BY-NC-SA 4.0 (Non-commercial due to synthetic generation)
*   **Last Updated:** Feb 2025 - **[STALE]**
*   **Example Task:** Maintaining a consistent, hyper-specific persona (e.g., "A 19th-century blacksmith who hates modern technology") across a 20-turn adversarial interview.
*   **Input Modality:** Text.
*   **Success Measurement:** LLM-Judge (Consistency tracking).
*   **Realism:** Low (Synthetic personas).
*   **Models Persistent User State:** Yes (Measures persona drift over time).

### 21. ROLEBench / RoleLLM
*   **Link:** [GitHub: RoleLLM](https://github.com/RoleLLM/RoleLLM)
*   **License:** Apache 2.0
*   **Last Updated:** 2024 - **[STALE]**
*   **Example Task:** Roleplaying as distinct pop-culture characters and adhering to their knowledge cutoffs and stylistic quirks.
*   **Input Modality:** Text.
*   **Success Measurement:** LLM-Judge.
*   **Realism:** Low.
*   **Models Persistent User State:** No (Stateless roleplay).

### 22. CultureBench
*   **Link:** [GitHub/arXiv](https://arxiv.org/abs/2404.xxxxx)
*   **License:** CC BY 4.0
*   **Last Updated:** 2024 - **[STALE]**
*   **Example Task:** Adjusting advice on gift-giving based on the specific cultural norms of the user's stated background (e.g., Japanese vs. Brazilian norms).
*   **Input Modality:** Text.
*   **Success Measurement:** LLM-Judge & Decidable.
*   **Realism:** High (Curated by anthropologists).
*   **Models Persistent User State:** No (Stateless cultural QA).

### 23. ACVA (Arabic Cultural Value Alignment)
*   **Link:** [HuggingFace: ACVA](https://huggingface.co/datasets/NCTU/ACVA)
*   **License:** Apache 2.0
*   **Last Updated:** 2024 (Integrated into OALL v2 in 2025) - **[STALE]**
*   **Example Task:** Answering moral or societal questions to align with regional Middle Eastern values rather than Western defaults.
*   **Input Modality:** Text.
*   **Success Measurement:** Decidable (Multiple-choice alignment).
*   **Realism:** High.
*   **Models Persistent User State:** No.

### 24. AlGhafa
*   **Link:** [TII Repositories](https://huggingface.co/tiiuae)
*   **License:** CC BY 4.0
*   **Last Updated:** 2023/2024 - **[STALE]**
*   **Example Task:** Evaluating native Arabic language understanding, idiom comprehension, and local cultural references.
*   **Input Modality:** Text.
*   **Success Measurement:** Decidable.
*   **Realism:** High.
*   **Models Persistent User State:** No.

### 25. CulturALL
*   **License:** MIT
*   **Last Updated:** Early 2026 - **[FRESH]**
*   **Example Task:** Multilingual grounded tasks requiring both translation and cultural idiom mapping.
*   **Input Modality:** Text.
*   **Models Persistent User State:** No.

### 26. AlpsBench (Real-Dialogue Memorization)
*   **License:** CC BY-NC 4.0
*   **Last Updated:** Early 2026 - **[FRESH]**
*   **Example Task:** Testing memory limits using highly informal, colloquial dialogue from real-world datasets rather than synthetic corporate chat.
*   **Input Modality:** Text.
*   **Success Measurement:** LLM-Judge.
*   **Realism:** High.
*   **Models Persistent User State:** Yes.

### 27. PrefEval (Preference Following)
*   **License:** MIT
*   **Last Updated:** Pre-2025 - **[STALE]**
*   **Example Task:** Following user formatting preferences (e.g., "always respond in bullet points and use emoji") consistently.
*   **Input Modality:** Text.
*   **Models Persistent User State:** No (Implicit in prompt).