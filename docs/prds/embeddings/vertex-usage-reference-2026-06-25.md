---
type: reference
status: active
tags: [reference, agent, database]
---

# Vertex Embeddings — Verified Usage Reference

Hard-won, LIVE-VERIFIED facts (2026-06-25). Don't lose these. Supersedes guesses in earlier notes. Pairs with [[docs/prds/embeddings/state-and-activation-2026-06-25]].

## The model + endpoint (VERIFIED 200 on our project)

- **Model:** `gemini-embedding-2` — GA (Apr 2026), natively **multimodal** (text/image/video/audio/documents → ONE unified vector space), MRL-scalable dims.
- **Endpoint:** the **GLOBAL** location, NOT a region. `us-central1` → 404. Correct:
  `POST https://aiplatform.googleapis.com/v1beta1/projects/seon-vertex-11392/locations/global/publishers/google/models/gemini-embedding-2:embedContent`
- **Method:** `:embedContent` (the Gemini surface), NOT the legacy `:predict`.
- **Request:** `{"content":{"parts":[{"text":"..."}]}}` (+ `outputDimensionality` for MRL).
- **Output:** **3072 dims** by default; MRL down to **1536** (matches the existing seon HNSW index) or 768. Live proof: a text call returned a 3072-float vector.
- **Auth:** ADC via the service-account key — no token code, auto-refresh (`google-auth-library-oauth2-http 1.33.0`, transitive in `google-genai 1.59.0`).
- (`gemini-embedding-001` also works but only at `us-central1`/`:predict`, text-only — NOT what we want.)

## GCP project (created + verified this session)

- Project `seon-vertex-11392` (display "seon"), **billing linked + working** (a paid Vertex call returned 200), **Vertex AI API enabled**.
- Service account `seon-embed@seon-vertex-11392.iam.gserviceaccount.com`, role `roles/aiplatform.user`.
- Key: `~/.config/gcloud/seon-vertex-sa.json` (chmod 600, outside repo).

## Governance (answers "same protections?")

YES — same as any GA model on the Gemini Enterprise Agent Platform (Vertex): **input/output NOT used to train Google's models** (Cloud Service Terms §17, requires express consent we don't give); 24h abuse-retention disableable at project level (ZDR). Difference vs a regional model: `gemini-embedding-2` is **Global** region → no single-region data-**residency** guarantee (training-exclusion is identical; only residency differs).

## Env to wire into bin/seon (the code lock-in)

```
GOOGLE_GENAI_USE_VERTEXAI=true
GOOGLE_CLOUD_PROJECT=seon-vertex-11392
GOOGLE_CLOUD_LOCATION=global          # gemini-embedding-2 is Global, not us-central1
GOOGLE_APPLICATION_CREDENTIALS=/Users/sean/.config/gcloud/seon-vertex-sa.json
# and UNSET GEMINI_API_KEY so the SDK can't silently fall back to the consumer endpoint
```

## Pricing (Vertex GA; batch ≈ 20% cheaper — use batch for bulk)

- Gemini Embedding (text): online $0.00015 / 1k tokens, **batch $0.00012 / 1k tokens**.
- Gemini Embedding 2 multimodal: text $0.20/1M tok, image $0.00012/img, video $0.00079/frame, audio $0.00016/sec. Output: no charge.

## The capacity "limit" (NOT a real ceiling)

`embed.clj:129` `(def ^:const capacity 10000)` — self-imposed to keep the dev mmap small; Proximum's own default is **10,000,000**. Throws "Index capacity exceeded" at the 10,000th vector. Raise it for bulk (cost = mmap size ∝ capacity × dim). With the cache/archive below, rebuilding at higher capacity is free (no re-embed).

## Content-addressed cache + durable archive (design direction)

The vectors are the SOURCE-OF-TRUTH; the HNSW index is DERIVED/rebuildable. Generalize the existing per-entity `:seon.embed/source-hash` (SHA-256) into a GLOBAL content-addressed cache + archive:
- **Key** = `SHA-256(content-bytes)` folded with **model + output-dim + task-instruction** (same bytes under different params → different vector). Uniform hash across all modalities: text → utf-8 bytes, image/audio/video/PDF → file bytes.
- **Value** = the full vector + metadata (entity-id link, modality, timestamp). Stored as durable data files — link back to source by entity-id, NEVER duplicate content.
- **Effect:** (a) never re-pay Gemini for duplicate content (cross-entity, post-wipe, on rebuild); (b) full-fidelity vectors retained for re-analysis / backend switch; (c) index rebuild from the archive is free → the capacity raise costs nothing extra.

## Live scale findings (synthetic, this session)

Datalog queries + memory held to 50k entities / 234k datoms (heap 58→100MB). Cold novel-query latency @50k: by-attr 20ms, ref-join 3ms, 2-hop 89ms, aggregate 67ms, KNN(k=10) 116ms cold / 5ms warm. **Persistence PROVEN:** wire-server restart → identical search hit, `embed-call-count=0` (Proximum sibling store restored all vectors).
