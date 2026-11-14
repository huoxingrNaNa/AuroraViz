package com.operit.audioviz;

/** 就地基-2 FFT（长度必须为 2 的幂） */
final class FFT {

    private FFT() {
    }

    static void transform(float[] re, float[] im) {
        final int n = re.length;
        if (n <= 1) return;

        // 位反转置换
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang);
            float wi = (float) Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < half; k++) {
                    int p = i + k;
                    int q = p + half;
                    float ur = re[p], ui = im[p];
                    float vqr = re[q], vqi = im[q];
                    float vr = vqr * cr - vqi * ci;
                    float vi = vqr * ci + vqi * cr;
                    re[p] = ur + vr;
                    im[p] = ui + vi;
                    re[q] = ur - vr;
                    im[q] = ui - vi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}