# Vectors

Semantic search is native to the store and its query language: each form's, wiki section's and task plan's text is embedded once by a small local model in root, its vector is kept on that entity as facts, and `seon.vector/near` is a Datalog function, so similarity is one more relation a query joins. The context fuses it with the call graph to name the helpers a task needs (`seon.context/found`), and an agent asks it at its REPL (`my.agent/similar`). The ruling is DECISIONS "Embeddings join the context: fused, small and measured"; the work was task `seon.context/similar-finds-helpers-by-embedding`.

```clojure
[(seon.vector/near $ ?q 10 {:of :form/id}) [[?e ?score]]]   ; ?q an entity with a vector, or a vector
```

## The model

EmbeddingGemma-2 (768 dims, Matryoshka, mean-pooled, normalized) runs in process through llama.cpp: `native/embed.c` puts its encoder behind a C ABI of pointers and ints (open, embed a batch of texts given as one UTF-8 buffer and offsets, close), and `bin/build embed` builds llama.cpp v0.7.0 from `reference-code/llama.cpp` (static, Metal, no OpenMP) with it into `target/lib/libseon-embed.dylib`. The model file is ggml-org's Q8_0 GGUF, fetched once by `bin/build` into the main tree's `target/cache/models/`, named by its sha256 and checked against it (`bin/env`'s `SEON_EMBED_MODEL`); its name is the model's id on every vector (`seon.vector/model-id`), and `bin/env` is the one place the pin is written (`:seon.config.vector/model` reads it), so a model change makes every vector due again. Nothing is installed outside `target/`; cmake is the one added prerequisite.

The model runs only in root, as the `:embed` effect kind (`resources/seon/system.edn`): ggml starts threads, and agents and the fork server must stay single-threaded to fork ([failure points](failure-points.md#physics-who-may-run-threads)). The effect's worker loads the library and the model on its first request (`seon.vector/open`, an effect kind's `:open`) and keeps the handle in its own state; the kind is a batch kind, so one worker step embeds the texts of every request queued (up to `:seon.config.vector/batch`), in encodes of at most `:seon.config.vector/batch-tokens` tokens (`seon.vector/embed`). Its answer is plain data, `{:model id :vectors [[x ...] ...]}`, the same in root and across an agent's socket.

Measured on Seon's 1,393 src forms (M5 Max, Metal, machine load ~10): the model opens in 3.7 s; one text takes 5-15 ms warm; batched, 37k tokens/s (175 texts/s) at a 4,096-token encode (512: 122/s, 1,024: 167/s), 0.86 GB RSS; the BF16 file is no faster (34k tokens/s). v1 ran the same model in MLX over HTTP (5.7 ms one text, 66-105k tokens/s batched on short texts). mistral.rs (`reference-code/mistral.rs`) was evaluated and not used: it has no EmbeddingGemma-2, did 32 texts/s on Qwen3-Embedding-0.6B in its place, and brings tokio and 461 crates.

## What is embedded

Each kind's text is its attribute's value with the model's prompt: a form's `:form/src` and a section's `:seon.section/text` as documents (`seon.vector/document-prompt`), a task's `:seon.task/plan` as what a search asks (`seon.vector/query-prompt`). A text over `:seon.config.vector/max-tokens` keeps its first tokens, and special-token text in it is read as text. A vector is five facts on the entity: `:seon.vector/f16` (the whole vector), `:seon.vector/coarse` (the [coarse level](#the-pyramid)), `:seon.vector/model`, `:seon.vector/text-t` (the transaction of the text it embeds) and `:seon.vector/tokens` (the text's whole token count: past the bound, what was left out).

`seon.vector/due` lists the texts whose vector is missing, made from an earlier text (its `:seon.vector/text-t` is not the text's transaction, so a text changed while its round was out stays due) or another model's. A form is content-addressed, so its text never changes and it is embedded once; a superseded form is retracted with its vector. `seon.vector/vectors-proc` keeps main current: at the writer's commit notice it sends the first `:seon.config.vector/texts` due to `:embed` and writes their vectors to main through the writer; that commit's notice starts the next round, so a backlog is embedded round by round and an idle main asks nothing. A failed embedding is root's problem (the effect owner records it) and the next notice tries again; texts llama.cpp could not encode are kept out of later rounds (the proc's `:unembeddable`, in its ping), so they never hold back those due after them. Seon's whole text (2,681 texts: 1,783 forms with the schemas, 321 sections, the task plans) embeds in 17-19 s at boot. An agent's branch reads main's vectors as of its fork; a form new on the branch has none until it merges.

## The pyramid

A Matryoshka vector's prefix is itself an embedding, so one stored vector serves two levels (`native/vector.c`, in libprobe: no threads, every process loads it). The coarse row is the first `:seon.config.vector/coarse-dims` dims renormalized and scaled to int8, with its scale (`seon.vector/pack`); the coarse level scans every row with integer dot products and keeps the best `:seon.config.vector/shortlist` in a heap; the fine level ranks those at f16 over `:seon.config.vector/fine-dims` dims, each prefix renormalized. A query option changes either (`{:shortlist n :fine-dims d}`); a shortlist of every vector is the exact search. `near` reads the coarse rows from the db value each call (no cache: a db value is its own index), copies them into one buffer, and reads only the shortlist's f16 rows.

Recall@10 against the exact 768-dim search:

| corpus | coarse | shortlist | fine | recall@10 |
|---|---|---|---|---|
| Seon's whole text, 2,681 (`bin/check vectors`, 207 queries) | 128 | 50 / 100 / 200 / 400 | 768 | 0.946 / 0.979 / 0.990 / **0.998** |
| src forms, 1,393 (every form a query) | 64 / 128 / 256 | 100 | 768 | 0.945 / 0.9975 / 1.0 |
| src forms | 128 | 100-400 | 256 | at most 0.83 |

The C levels take 0.08 ms at 1,393 vectors and 0.31 ms at 100k (v1's f32 brute force: 4.1 ms at 100k; em1's kernel 0.39 ms at 10k). The whole `near` in Jolt takes 1.9 ms at shortlist 100 and 4.6 ms at 400 on 1,737 vectors, nearly all of it the datom reads and copies; at 100k those would cost about 20 ms (estimated), the next lever if a store grows that large. `bin/check vectors` proves the default on Seon's own text each run, and `seon.vector-test/the-pyramid-ranks-as-an-exact-search` on made Matryoshka-shaped vectors. Proximum's HNSW was rejected in v1 (1.6-4.3 MB per commit, recall 0.16-0.62).

## Fused with the call graph

`seon.context/found` ranks the helpers a task's plan does not name two ways and fuses them by reciprocal rank (`:seon.config.vector/rrf-k`): the call graph's (the cited vars' and proofs' one-hop callees, tf-idf) and the program forms nearest the task's plan vector (`:seon.config.render/found-near` of them). A task with no vector yet ranks by the call graph alone. Proof: `seon.context-test/similar-finds-a-helper-the-call-graph-misses`.

Measured on 283 pairs from main's history (each non-merge commit's message as the plan; the functions its diff touched, still in the program, less those the message names, as what it needed), recall@10:

| ranking | all (283) | message names functions (166) | names none (117) |
|---|---|---|---|
| call graph (`found` before) | 0.042 | 0.071 | 0 |
| embeddings | 0.495 | 0.482 | 0.514 |
| fused, k 10 (k 60: 0.448) | 0.451 | 0.407 | 0.514 |

Fused beats the call graph alone, so it is kept. Two biases run the other way from each other: the embeddings read each form as it is today, after the change its commit describes (flattering them), and a commit pair has no proofs, the call graph's richest source (understating it). v1 measured 0.274 / 0.386 / 0.441 on its own corpus. Weighting the two rankings is the lever if a task-shaped corpus shows embeddings alone ahead.
