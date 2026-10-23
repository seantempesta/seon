// The vector search's two levels (docs/vector.md#the-pyramid), brute force over rows the caller
// lays out contiguously. A Matryoshka vector's prefix is itself an embedding, so one stored
// vector serves every level: the coarse row is its first `cdims` dims renormalized and scaled to
// int8 (one float scale after them), the fine row its f16 dims. No threads, no allocation that
// outlives a call: agents and root alike load it (libprobe).
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// One vector v (dims floats, unit length) as its f16 row (dims halves) and its coarse row
// (cdims int8, then the float scale: the prefix's p_i is about q_i * scale).
void seon_vec_pack(const float *v, int dims, int cdims, _Float16 *f16, int8_t *coarse) {
  for (int i = 0; i < dims; i++) f16[i] = (_Float16)v[i];
  double ss = 0; float mx = 0;
  for (int i = 0; i < cdims; i++) ss += (double)v[i] * v[i];
  float inv = ss > 0 ? (float)(1.0 / sqrt(ss)) : 0.f;
  for (int i = 0; i < cdims; i++) { float a = fabsf(v[i] * inv); if (a > mx) mx = a; }
  float scale = mx > 0 ? mx / 127.f : 1.f;
  for (int i = 0; i < cdims; i++) coarse[i] = (int8_t)lrintf(v[i] * inv / scale);
  memcpy(coarse + cdims, &scale, sizeof scale);
}

// Keeps the k best (score, row) seen in a min-heap at heap[0..k).
static void offer(float *hs, long *hi, int k, int *m, float s, long i) {
  if (*m < k) {
    int c = (*m)++;
    while (c > 0) { int p = (c - 1) / 2; if (hs[p] <= s) break; hs[c] = hs[p]; hi[c] = hi[p]; c = p; }
    hs[c] = s; hi[c] = i;
  } else if (s > hs[0]) {
    int c = 0;
    for (;;) {
      int l = 2 * c + 1, r = l + 1, b = c; float bs = s;
      if (l < k && hs[l] < bs) { b = l; bs = hs[l]; }
      if (r < k && hs[r] < bs) { b = r; }
      if (b == c) break;
      hs[c] = hs[b]; hi[c] = hi[b]; c = b;
    }
    hs[c] = s; hi[c] = i;
  }
}

// The coarse level: the k rows of rows (n coarse rows, each cdims int8 + scale) nearest the coarse
// query q, as row indexes in out_i with their cosine estimates in out_s, best first. Answers how
// many it wrote (min k n).
int seon_vec_coarse(const int8_t *rows, long n, int cdims, const int8_t *q, int k, long *out_i, float *out_s) {
  size_t stride = cdims + sizeof(float);
  float qs; memcpy(&qs, q + cdims, sizeof qs);
  int m = 0;
  for (long r = 0; r < n; r++) {
    const int8_t *row = rows + r * stride;
    int32_t dot = 0;
    for (int i = 0; i < cdims; i++) dot += (int32_t)row[i] * q[i];
    float rs; memcpy(&rs, row + cdims, sizeof rs);
    offer(out_s, out_i, k, &m, (float)dot * rs * qs, r);
  }
  // heap -> best first
  for (int e = m - 1; e > 0; e--) {
    float s = out_s[e]; long i = out_i[e];
    out_s[e] = out_s[0]; out_i[e] = out_i[0];
    int sz = e, c = 0;
    for (;;) {
      int l = 2 * c + 1, r = l + 1, b = c; float bs = s;
      if (l < sz && out_s[l] < bs) { b = l; bs = out_s[l]; }
      if (r < sz && out_s[r] < bs) { b = r; }
      if (b == c) break;
      out_s[c] = out_s[b]; out_i[c] = out_i[b]; c = b;
    }
    out_s[c] = s; out_i[c] = i;
  }
  return m;
}

// The fine level: the cosine of query q (dims floats) with each of n f16 rows (each dims halves)
// over their first fdims dims, each prefix renormalized (a Matryoshka prefix is not unit length).
void seon_vec_fine(const _Float16 *rows, long n, int dims, int fdims, const float *q, float *out_s) {
  double qq = 0;
  for (int i = 0; i < fdims; i++) qq += (double)q[i] * q[i];
  float qinv = qq > 0 ? (float)(1.0 / sqrt(qq)) : 0.f;
  for (long r = 0; r < n; r++) {
    const _Float16 *row = rows + r * dims;
    float dot = 0, rr = 0;
    for (int i = 0; i < fdims; i++) { float x = (float)row[i]; dot += x * q[i]; rr += x * x; }
    out_s[r] = rr > 0 ? dot * qinv / sqrtf(rr) : 0.f;
  }
}

// An f16 row (dims halves) as floats: a stored vector made a query.
void seon_vec_f32(const _Float16 *row, int dims, float *out) {
  for (int i = 0; i < dims; i++) out[i] = (float)row[i];
}
