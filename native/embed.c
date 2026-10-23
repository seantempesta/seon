// The embedding model in process: llama.cpp's encoder behind a C ABI of plain pointers and
// ints, so Jolt binds it with seon.lib/defc (docs/vector.md#the-model). One handle owns one
// llama context; texts arrive concatenated with n+1 byte offsets, and each answer is the
// model's mean-pooled vector of `dims` floats, L2-normalized. ggml starts threads, so only
// root's :embed effect worker loads this library (docs/failure-points.md#physics).
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "llama.h"

typedef struct {
  struct llama_model *model;
  struct llama_context *ctx;
  const struct llama_vocab *vocab;
  int n_ctx, n_seq, dims;
  llama_token *toks;   // one text's tokens, grown to the longest text's
  int cap;
} seon_embed;

static void quiet(enum ggml_log_level level, const char *text, void *ud) { (void)level; (void)text; (void)ud; }

// The handle, or NULL when the model or its context cannot be made (the caller reads errno-free:
// a NULL is the one failure, its cause in llama's log, which a nonzero `verbose` keeps).
void *seon_embed_open(const char *path, int n_ctx, int n_seq, int verbose) {
  if (!verbose) llama_log_set(quiet, NULL);
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 999;
  struct llama_model *model = llama_model_load_from_file(path, mp);
  if (!model) return NULL;
  struct llama_context_params cp = llama_context_default_params();
  cp.embeddings = true;
  cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;
  cp.n_ctx = n_ctx; cp.n_batch = n_ctx; cp.n_ubatch = n_ctx;
  cp.n_seq_max = n_seq;
  cp.kv_unified = true;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); return NULL; }
  seon_embed *h = calloc(1, sizeof *h);
  h->model = model; h->ctx = ctx; h->vocab = llama_model_get_vocab(model);
  h->n_ctx = n_ctx; h->n_seq = n_seq; h->dims = llama_model_n_embd_out(model);
  h->cap = n_ctx; h->toks = malloc(sizeof(llama_token) * h->cap);
  return h;
}

// The model's output width (768 for EmbeddingGemma-2).
int seon_embed_dims(void *hv) { return ((seon_embed *)hv)->dims; }

static int flush(seon_embed *h, struct llama_batch *b, int first, int n, float *out) {
  if (b->n_tokens == 0) return 0;
  llama_memory_clear(llama_get_memory(h->ctx), true);
  if (llama_encode(h->ctx, *b) != 0) return -2;
  for (int s = 0; s < n; s++) {
    const float *e = llama_get_embeddings_seq(h->ctx, s);
    if (!e) return -3;
    float *o = out + (size_t)(first + s) * h->dims;
    double ss = 0;
    for (int i = 0; i < h->dims; i++) ss += (double)e[i] * e[i];
    float inv = ss > 0 ? (float)(1.0 / sqrt(ss)) : 0.f;
    for (int i = 0; i < h->dims; i++) o[i] = e[i] * inv;
  }
  b->n_tokens = 0;
  return 0;
}

// Embeds n texts (texts[off[i]..off[i+1]) as UTF-8) into out (n x dims floats), and writes each
// text's whole token count into tokens[i]; a text longer than max_tokens keeps its first
// max_tokens, so the caller records what it left. Special-token text in a text is read as text.
// Texts are packed, each its own sequence, into encodes of at most n_ctx tokens and n_seq
// sequences. 0, or negative on a failed encode: -2 the encode, -3 a missing pooled vector.
int seon_embed_batch(void *hv, const char *texts, const long *off, int n, int max_tokens, float *out, int *tokens) {
  seon_embed *h = hv;
  if (max_tokens > h->n_ctx) max_tokens = h->n_ctx;
  struct llama_batch b = llama_batch_init(h->n_ctx, 0, 1);
  int first = 0, seqs = 0;
  for (int i = 0; i < n; i++) {
    int len = (int)(off[i + 1] - off[i]);
    int nt = llama_tokenize(h->vocab, texts + off[i], len, h->toks, h->cap, true, false);
    if (nt < 0) {                                  // the buffer was short: -nt tokens are needed
      h->cap = -nt; h->toks = realloc(h->toks, sizeof(llama_token) * h->cap);
      nt = llama_tokenize(h->vocab, texts + off[i], len, h->toks, h->cap, true, false);
    }
    tokens[i] = nt;
    if (nt > max_tokens) nt = max_tokens;
    if (b.n_tokens + nt > h->n_ctx || seqs == h->n_seq) {
      int r = flush(h, &b, first, seqs, out);
      if (r) { llama_batch_free(b); return r; }
      first = i; seqs = 0;
    }
    for (int t = 0; t < nt; t++) {
      int k = b.n_tokens++;
      b.token[k] = h->toks[t]; b.pos[k] = t; b.n_seq_id[k] = 1; b.seq_id[k][0] = seqs; b.logits[k] = 1;
    }
    seqs++;
  }
  int r = flush(h, &b, first, seqs, out);
  llama_batch_free(b);
  return r;
}

void seon_embed_close(void *hv) {
  seon_embed *h = hv;
  llama_free(h->ctx); llama_model_free(h->model); free(h->toks); free(h);
}
