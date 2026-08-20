package com.wzz.game_console.client.screens.games.gogame;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * OpenCL GPU 加速后端。CPU 负责构建输入，GPU 负责矩阵运算。
 */
public class OpenCLBackend implements AutoCloseable {
    private static final int CL_MEM_READ_WRITE = 1;
    private boolean available = false;
    private String deviceName = "CPU";
    private NativeLibrary cl;
    private Pointer context, queue, device, program;
    private java.util.Map<String, Pointer> kernels = new java.util.HashMap<>();

    public OpenCLBackend() {
        try { init(); available = true; } catch (Exception e) { System.err.println("[OpenCL] " + e.getMessage()); close(); }
    }
    public boolean isAvailable() { return available; }
    public String getDeviceName() { return deviceName; }

    private void init() throws Exception {
        cl = NativeLibrary.getInstance("OpenCL");
        IntByReference n = new IntByReference();
        calli("clGetPlatformIDs", 0, null, n);
        if (n.getValue() == 0) throw new Exception("无 OpenCL 平台");
        Pointer[] platforms = new Pointer[n.getValue()];
        calli("clGetPlatformIDs", n.getValue(), platforms, null);
        for (Pointer p : platforms) {
            if (p == null) continue;
            device = findDevice(p, 4L);
            if (device != null) break;
        }
        if (device == null) throw new Exception("无 GPU");
        deviceName = getDeviceName(device);
        Pointer[] devs = {device};
        IntByReference ec = new IntByReference();
        context = callp("clCreateContext", null, 1, devs, null, null, ec);
        if (context == null) throw new Exception("clCreateContext 失败");
        try { queue = callp("clCreateCommandQueueWithProperties", context, device, null, ec); }
        catch (Exception e) { queue = callp("clCreateCommandQueue", context, device, 0L, ec); }
        if (queue == null) throw new Exception("clCreateCommandQueue 失败");

        byte[] src = KERNEL_SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Memory mem = new Memory(src.length + 1);
        mem.write(0, src, 0, src.length); mem.setByte(src.length, (byte)0);
        Pointer[] strings = {mem};
        long[] lens = {src.length};
        program = callp("clCreateProgramWithSource", context, 1, strings, lens, ec);
        if (program == null) throw new Exception("clCreateProgramWithSource 失败");
        int be = calli("clBuildProgram", program, 0, null, null, null, null);
        if (be != 0) { String log = getBuildLog(program); throw new Exception("编译失败: " + log); }

        for (String name : "sub_fwd,block_fwd,top_fwd,policy_fwd,value_fwd,fwd_reduce,matmul,matmul_tA,sgd".split(",")) {
            try { Pointer k = callp("clCreateKernel", program, name, ec); if (k != null) kernels.put(name, k); }
            catch (Exception e) {}
        }
        System.out.println("[OpenCL] " + deviceName + " 内核: " + kernels.size());
    }

    // ── 完整训练 batch（GPU 主计算）──
    public double trainBatch(double[][][][] planes, double[][] aux, double[] vTgt, double[][] pTgt,
                              double[][][] subW, double[][] subB, double[][][] blkW, double[][] blkB,
                              double[][] topW, double[] topB, double[][] polW, double[] polB,
                              double[][] valW1, double[] valB1, double[] valW2, double valB2,
                              double lr, double l2, double clip) {
        if (!available) return -1;
        int B = planes.length;
        try {
            // 子块输入提取（CPU）
            double[][][] subIn = extractSubInputs(planes, B);
            // 子块 GPU 前向
            double[][][] subOut = gpuSubFwd(subIn, subW, subB, B);
            // 字块 GPU 前向
            double[][][] blkIn = buildBlkIn(subOut, B);
            double[][][] blkOut = gpuBlockFwd(blkIn, blkW, blkB, B);
            // 顶级 GPU 前向
            double[][] topIn = buildTopIn(blkOut, aux, B);
            double[][] shared = gpuTopFwd(topIn, topW, topB, B);
            // 策略头 GPU
            double[][] policy = gpuPolicyFwd(shared, polW, polB, B);
            // 价值头 GPU
            double[] value = gpuValueFwd(shared, valW1, valB1, valW2, valB2, B);
            // Loss + 梯度（CPU，轻量）
            double loss = 0;
            double[][] dLogit = new double[B][362];
            double[] dValue = new double[B];
            for (int n = 0; n < B; n++) {
                for (int j = 0; j < 362; j++) {
                    dLogit[n][j] = policy[n][j] - pTgt[n][j];
                    if (pTgt[n][j] > 0) loss -= pTgt[n][j] * Math.log(Math.max(policy[n][j], 1e-15));
                }
                double dv = 2 * (value[n] - vTgt[n]) * (1 - value[n] * value[n]);
                dValue[n] = dv;
                loss += (value[n] - vTgt[n]) * (value[n] - vTgt[n]);
            }
            // 返回 loss（权重更新由 CPU 的 trainMiniBatch 处理）
            return loss / B;
        } catch (Exception e) { System.err.println("[OpenCL] " + e.getMessage()); return -1; }
    }

    // ── 完整 GPU Pass 0 前向：子块→字块→顶级，一次批量完成 ──
    /**
     * 在整个 batch 上 GPU 执行 子块/字块/顶级 三层前向（ReLU 中间结果），
     * 并填充成 NeuralEvaluator.trainMiniBatch GPU 路径所需的全部中间量。
     * 若 GPU 不可用或失败返回 false，调用方回退 CPU 路径。
     *
     * @param bSubIn [B][9][9][36] 子块输入
     * @param bSubZ  [B][9][9][16] 子块 ReLU 输出（供反向的 ReLU 掩码使用）
     * @param bBlkIn [B][9][144]   字块输入
     * @param bBlkZ  [B][9][64]    字块 ReLU 输出
     * @param bTopIn [B][600]      顶级输入
     * @param bShared [B][256]     顶级 ReLU 输出
     * @param bShZ   [B][256]      顶级 ReLU 输出（bShared 的副本，供反向掩码）
     */
    public boolean batchPass0Forward(double[][][][] planes, double[][] aux, int B,
                                      double[][][] subW, double[][] subB,
                                      double[][][] blkW, double[][] blkB,
                                      double[][] topW, double[] topB,
                                      double[][] polW, double[] polB,
                                      double[][] valW1, double[] valB1, double[] valW2, double valB2,
                                      double[][][][] bSubIn, double[][][][] bSubZ,
                                      double[][][] bBlkIn, double[][][] bBlkZ,
                                      double[][] bTopIn, double[][] bShared, double[][] bShZ,
                                      double[][] bPolicyOut, double[] bValueOut) {
        if (!available) return false;
        try {
            // ── 关键：子块权重需从 [9][36][16] 复制为 GPU 内核期望的 [81][36][16]
            //    （每大块内 9 个子块共享该块的权重，内核按子块全局索引 s=0..80 取权）
            double[][][] subWGpu = new double[81][36][16];
            double[][] subBGpu = new double[81][16];
            for (int si = 0; si < 81; si++) {
                int b = si / 9;
                subWGpu[si] = subW[b];
                subBGpu[si] = subB[b];
            }
            // ── 子块前向 ──
            double[][][] subIn = extractSubInputs(planes, B);      // [81][B][36]
            double[][][] subOut = gpuSubFwd(subIn, subWGpu, subBGpu, B); // [81][B][16]
            for (int n = 0; n < B; n++)
                for (int b = 0; b < 9; b++)
                    for (int s = 0; s < 9; s++) {
                        System.arraycopy(subIn[b*9+s][n], 0, bSubIn[n][b][s], 0, 36);
                        System.arraycopy(subOut[b*9+s][n], 0, bSubZ[n][b][s], 0, 16);
                    }
            // ── 字块前向 ──
            double[][][] blkIn = buildBlkIn(subOut, B);            // [9][B][144]
            double[][][] blkOut = gpuBlockFwd(blkIn, blkW, blkB, B); // [9][B][64]
            for (int n = 0; n < B; n++)
                for (int b = 0; b < 9; b++) {
                    System.arraycopy(blkIn[b][n], 0, bBlkIn[n][b], 0, 144);
                    System.arraycopy(blkOut[b][n], 0, bBlkZ[n][b], 0, 64);
                }
            // ── 顶级前向 ──
            double[][] topIn = buildTopIn(blkOut, aux, B);         // [B][600]
            double[][] sharedOut = gpuTopFwd(topIn, topW, topB, B); // [B][256]
            for (int n = 0; n < B; n++) {
                System.arraycopy(topIn[n], 0, bTopIn[n], 0, 600);
                System.arraycopy(sharedOut[n], 0, bShared[n], 0, 256);
                System.arraycopy(sharedOut[n], 0, bShZ[n], 0, 256);
            }
            // ── 策略头 GPU ―─
            double[][] policyOut = gpuPolicyFwd(sharedOut, polW, polB, B); // [B][362] softmax
            for (int n = 0; n < B; n++) System.arraycopy(policyOut[n], 0, bPolicyOut[n], 0, 362);
            // ── 价值头 GPU ―─
            double[] valueOut = gpuValueFwd(sharedOut, valW1, valB1, valW2, valB2, B); // [B]
            System.arraycopy(valueOut, 0, bValueOut, 0, B);
            return true;
        } catch (Exception e) {
            System.err.println("[OpenCL] batchPass0Forward: " + e.getMessage());
            return false;
        }
    }

    // ── 兼容现有 NeuralEvaluator 集成：顶级 FC GPU 前向 ──
    /**
     * GPU 执行顶级 FC(600→256) + ReLU，对 batch 一次完成。
     * @param output  ReLU 后输出 [batch][256]
     * @param preAct  同时写入 ReLU 后值（ReLU 掩码判断只依赖正负，等价于 pre-activation 掩码）
     */
    public boolean batchTopForward(double[][] input, double[][] weights, double[] bias,
                                    double[][] output, double[][] preAct) {
        if (!available) return false;
        Pointer k = kernels.get("top_fwd");
        if (k == null) return false;
        int B = input.length;
        try {
            double[][] out = gpuTopFwd(input, weights, bias, B);
            for (int n = 0; n < B; n++) {
                System.arraycopy(out[n], 0, output[n], 0, 256);
                System.arraycopy(out[n], 0, preAct[n], 0, 256);
            }
            return true;
        } catch (Exception e) {
            System.err.println("[OpenCL] batchTopForward: " + e.getMessage());
            return false;
        }
    }

    // ── GPU 权重更新（代理 sgd 内核，避免 CPU 逐元素循环）──
    /**
     * 在 GPU 上执行 SGD 更新：w[i] -= lr * (g[i] + l2 * w[i])。
     * 3D 权重 [a][b][c]
     */
    public boolean batchWeightUpdate(double[][][] w, double[][][] g, double lr, double l2) {
        if (!available) return false;
        Pointer k = kernels.get("sgd");
        if (k == null) return false;
        int n = 0;
        for (double[][] mm : w) for (double[] r : mm) n += r.length;
        if (n == 0) return false;
        try {
            Memory dW = flatten3D(w); Pointer dWG = alloc(dW.size()); writeG(dWG, dW);
            Memory dG = flatten3D(g); Pointer dGG = alloc(dG.size()); writeG(dGG, dG);
            setPtr(k, 0, dWG); setPtr(k, 1, dGG); setF64(k, 2, lr); setF64(k, 3, l2); setInt(k, 4, n);
            launch1D(k, n);
            double[] flat = new double[n];
            readBack1D(dWG, flat, n);
            int off = 0;
            for (double[][] mm : w) for (double[] r : mm) { System.arraycopy(flat, off, r, 0, r.length); off += r.length; }
            free(dWG, dGG);
            return true;
        } catch (Exception e) { return false; }
    }
    /** 2D 权重 [a][b] */
    public boolean batchWeightUpdate(double[][] w, double[][] g, double lr, double l2) {
        if (!available) return false;
        Pointer k = kernels.get("sgd");
        if (k == null) return false;
        int n = 0;
        for (double[] r : w) n += r.length;
        if (n == 0) return false;
        try {
            Memory dW = flatten2D(w); Pointer dWG = alloc(dW.size()); writeG(dWG, dW);
            Memory dG = flatten2D(g); Pointer dGG = alloc(dG.size()); writeG(dGG, dG);
            setPtr(k, 0, dWG); setPtr(k, 1, dGG); setF64(k, 2, lr); setF64(k, 3, l2); setInt(k, 4, n);
            launch1D(k, n);
            double[] flat = new double[n];
            readBack1D(dWG, flat, n);
            int off = 0;
            for (double[] r : w) { System.arraycopy(flat, off, r, 0, r.length); off += r.length; }
            free(dWG, dGG);
            return true;
        } catch (Exception e) { return false; }
    }
    /** 1D 权重 [a] */
    public boolean batchWeightUpdate(double[] w, double[] g, double lr, double l2) {
        if (!available) return false;
        Pointer k = kernels.get("sgd");
        if (k == null) return false;
        int n = w.length;
        if (n == 0) return false;
        try {
            Memory dW = flatten1D(w); Pointer dWG = alloc(dW.size()); writeG(dWG, dW);
            Memory dG = flatten1D(g); Pointer dGG = alloc(dG.size()); writeG(dGG, dG);
            setPtr(k, 0, dWG); setPtr(k, 1, dGG); setF64(k, 2, lr); setF64(k, 3, l2); setInt(k, 4, n);
            launch1D(k, n);
            readBack1D(dWG, w, n);
            free(dWG, dGG);
            return true;
        } catch (Exception e) { return false; }
    }

    // ── GPU 前向方法 ──
    private double[][][] gpuSubFwd(double[][][] in, double[][][] w, double[][] b, int B) throws Exception {
        return run3D("sub_fwd", in, w, b, 81, 36, 16, B);
    }
    private double[][][] gpuBlockFwd(double[][][] in, double[][][] w, double[][] b, int B) throws Exception {
        return run3D("block_fwd", in, w, b, 9, 144, 64, B);
    }
    private double[][] gpuTopFwd(double[][] in, double[][] w, double[] b, int B) throws Exception {
        return run2D("top_fwd", in, w, b, 600, 256, B);
    }
    private double[][] gpuPolicyFwd(double[][] in, double[][] w, double[] b, int B) throws Exception {
        return run2D("policy_fwd", in, w, b, 256, 362, B);
    }
    private double[] gpuValueFwd(double[][] in, double[][] w1, double[] b1, double[] w2, double b2, int B) throws Exception {
        Pointer k = kernels.get("value_fwd"); if (k == null) return null;
        Memory dIn = flatten2D(in); Pointer dInG = alloc(dIn.size()); writeG(dInG, dIn);
        Memory dW1 = flatten2D(w1); Pointer dW1G = alloc(dW1.size()); writeG(dW1G, dW1);
        Memory dB1 = flatten1D(b1); Pointer dB1G = alloc(dB1.size()); writeG(dB1G, dB1);
        Memory dW2 = flatten1D(w2); Pointer dW2G = alloc(dW2.size()); writeG(dW2G, dW2);
        Pointer dOut = alloc((long)B * 8);
        setPtr(k, 0, dInG); setPtr(k, 1, dW1G); setPtr(k, 2, dB1G);
        setPtr(k, 3, dW2G); setPtr(k, 4, dOut); setInt(k, 5, B);
        launch(k, B, 1);
        double[] out = new double[B]; readBack(dOut, out);
        free(dInG, dW1G, dB1G, dW2G, dOut); return out;
    }

    // ── 数据提取 ──
    private double[][][] extractSubInputs(double[][][][] planes, int B) {
        double[][][] out = new double[81][B][36];
        for (int n = 0; n < B; n++) {
            double[][][] p = planes[n];
            for (int b = 0; b < 9; b++) {
                int bx = (b % 3) * 6, by = (b / 3) * 6;
                for (int s = 0; s < 9; s++) {
                    int sx = bx + (s % 3) * 2, sy = by + (s / 3) * 2, si = b * 9 + s, idx = 0;
                    for (int pp = 0; pp < 4; pp++)
                        for (int dx = 0; dx < 3; dx++)
                            for (int dy = 0; dy < 3; dy++)
                                out[si][n][idx++] = p[pp][sx + dx][sy + dy];
                }
            }
        }
        return out;
    }
    private double[][][] buildBlkIn(double[][][] subOut, int B) {
        double[][][] out = new double[9][B][144];
        for (int b = 0; b < 9; b++)
            for (int n = 0; n < B; n++)
                for (int i = 0; i < 144; i++)
                    out[b][n][i] = subOut[b * 9 + i / 16][n][i % 16];
        return out;
    }
    private double[][] buildTopIn(double[][][] blkOut, double[][] aux, int B) {
        double[][] out = new double[B][600];
        for (int n = 0; n < B; n++) {
            int idx = 0;
            for (int b = 0; b < 9; b++) { System.arraycopy(blkOut[b][n], 0, out[n], idx, 64); idx += 64; }
            System.arraycopy(aux[n], 0, out[n], 576, 24);
        }
        return out;
    }

    // ── GPU 前向辅助 ──
    private double[][][] run3D(String kName, double[][][] in, double[][][] w, double[][] b, int S, int K, int N, int B) throws Exception {
        Pointer k = kernels.get(kName); if (k == null) return null;
        Memory dIn = flatten3D(in); Pointer dInG = alloc(dIn.size()); writeG(dInG, dIn);
        Memory dW = flatten3D(w); Pointer dWG = alloc(dW.size()); writeG(dWG, dW);
        Memory dB = flatten2D(b); Pointer dBG = alloc(dB.size()); writeG(dBG, dB);
        Pointer dOut = alloc((long)S * B * N * 8);
        setPtr(k, 0, dInG); setPtr(k, 1, dWG); setPtr(k, 2, dBG); setPtr(k, 3, dOut); setInt(k, 4, B);
        launch3D(k, S, B, N);
        double[][][] out = new double[S][B][N]; readBack3D(dOut, out, S, B, N);
        free(dInG, dWG, dBG, dOut); return out;
    }
    private double[][] run2D(String kName, double[][] in, double[][] w, double[] b, int K, int N, int B) throws Exception {
        Pointer k = kernels.get(kName); if (k == null) return null;
        Memory dIn = flatten2D(in); Pointer dInG = alloc(dIn.size()); writeG(dInG, dIn);
        Memory dW = flatten2D(w); Pointer dWG = alloc(dW.size()); writeG(dWG, dW);
        Memory dB = flatten1D(b); Pointer dBG = alloc(dB.size()); writeG(dBG, dB);
        Pointer dOut = alloc((long)B * N * 8);
        setPtr(k, 0, dInG); setPtr(k, 1, dWG); setPtr(k, 2, dBG); setPtr(k, 3, dOut); setInt(k, 4, B);
        launch(k, B, N);
        double[][] out = new double[B][N]; readBack2D(dOut, out, B, N);
        free(dInG, dWG, dBG, dOut); return out;
    }

    // ── GPU 内存/执行 ──
    private Pointer alloc(long bytes) throws Exception { return callp("clCreateBuffer", context, CL_MEM_READ_WRITE, bytes, null, null); }
    private void free(Pointer... ps) { for (Pointer p : ps) safe("clReleaseMemObject", p); }
    private void writeG(Pointer dst, Memory src) throws Exception { calli("clEnqueueWriteBuffer", queue, dst, 1, 0L, src.size(), src, 0, null, null); }
    private void readBack(Pointer src, double[] out) throws Exception {
        Memory m = new Memory((long)out.length * 8);
        calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null);
        calli("clFinish", queue); double[] flat = m.getDoubleArray(0, out.length);
        System.arraycopy(flat, 0, out, 0, out.length);
    }
    private void readBack1D(Pointer src, double[] out, int n) throws Exception {
        Memory m = new Memory((long)n * 8);
        calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null);
        calli("clFinish", queue);
        m.read(0, out, 0, n);
    }
    private void readBack2D(Pointer src, double[][] out, int B, int N) throws Exception {
        Memory m = new Memory((long)B * N * 8);
        calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null);
        calli("clFinish", queue); double[] flat = m.getDoubleArray(0, B * N);
        for (int n = 0; n < B; n++) System.arraycopy(flat, n * N, out[n], 0, N);
    }
    private void readBack3D(Pointer src, double[][][] out, int S, int B, int N) throws Exception {
        Memory m = new Memory((long)S * B * N * 8);
        calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null);
        calli("clFinish", queue); double[] flat = m.getDoubleArray(0, S * B * N);
        for (int s = 0; s < S; s++)
            for (int n = 0; n < B; n++)
                System.arraycopy(flat, (s * B + n) * N, out[s][n], 0, N);
    }
    private void setPtr(Pointer k, int idx, Pointer p) throws Exception {
        PointerByReference ref = new PointerByReference(p);
        calli("clSetKernelArg", k, idx, (long)Native.POINTER_SIZE, ref.getPointer());
    }
    private void setInt(Pointer k, int idx, int v) throws Exception {
        Memory m = new Memory(4); m.setInt(0, v);
        calli("clSetKernelArg", k, idx, 4L, m);
    }
    private void setF64(Pointer k, int idx, double v) throws Exception {
        Memory m = new Memory(8); m.setDouble(0, v);
        calli("clSetKernelArg", k, idx, 8L, m);
    }
    private void launch(Pointer k, int x, int y) throws Exception {
        Memory g = new Memory(16); g.setLong(0, ceil(x, 16)*16); g.setLong(8, ceil(y, 16)*16);
        calli("clEnqueueNDRangeKernel", queue, k, 2, null, g, null, 0, null, null);
        calli("clFinish", queue);
    }
    private void launch1D(Pointer k, int n) throws Exception {
        long gs = ceil(n, 256) * 256;
        Memory g = new Memory(8); g.setLong(0, gs);
        calli("clEnqueueNDRangeKernel", queue, k, 1, null, g, null, 0, null, null);
        calli("clFinish", queue);
    }
    private void launch3D(Pointer k, int x, int y, int z) throws Exception {
        Memory g = new Memory(24); g.setLong(0, x); g.setLong(8, ceil(y, 16)*16); g.setLong(16, ceil(z, 16)*16);
        calli("clEnqueueNDRangeKernel", queue, k, 3, null, g, null, 0, null, null);
        calli("clFinish", queue);
    }
    private static long ceil(long a, long b) { return (a + b - 1) / b; }

    // ── 展平 ──
    private static Memory flatten3D(double[][][] m) {
        int d1 = m.length, d2 = m[0].length, d3 = m[0][0].length;
        Memory mem = new Memory((long)d1 * d2 * d3 * 8);
        double[] f = new double[d1 * d2 * d3];
        for (int i = 0; i < d1; i++) for (int j = 0; j < d2; j++) System.arraycopy(m[i][j], 0, f, (i * d2 + j) * d3, d3);
        mem.write(0, f, 0, f.length); return mem;
    }
    private static Memory flatten2D(double[][] m) {
        int r = m.length, c = m[0].length; Memory mem = new Memory((long)r * c * 8);
        double[] f = new double[r * c]; for (int i = 0; i < r; i++) System.arraycopy(m[i], 0, f, i * c, c);
        mem.write(0, f, 0, f.length); return mem;
    }
    private static Memory flatten1D(double[] v) {
        Memory mem = new Memory((long)v.length * 8); mem.write(0, v, 0, v.length); return mem;
    }

    // ── OpenCL 底层 ──
    private Pointer findDevice(Pointer platform, long type) throws Exception {
        IntByReference n = new IntByReference();
        if (calli("clGetDeviceIDs", platform, type, 0, null, n) != 0 || n.getValue() == 0) return null;
        Pointer[] devs = new Pointer[n.getValue()];
        calli("clGetDeviceIDs", platform, type, n.getValue(), devs, null); return devs[0];
    }
    private String getDeviceName(Pointer dev) throws Exception {
        IntByReference n = new IntByReference();
        calli("clGetDeviceInfo", dev, 0x102B, 0, null, n);
        if (n.getValue() <= 0) return "Unknown";
        Memory m = new Memory(n.getValue());
        calli("clGetDeviceInfo", dev, 0x102B, n.getValue(), m, null);
        return m.getString(0, "UTF-8");
    }
    private String getBuildLog(Pointer prog) throws Exception {
        IntByReference n = new IntByReference();
        calli("clGetProgramBuildInfo", prog, device, 0x1183, 0, null, n);
        if (n.getValue() <= 0) return "";
        Memory m = new Memory(n.getValue());
        calli("clGetProgramBuildInfo", prog, device, 0x1183, n.getValue(), m, null);
        return m.getString(0, "UTF-8");
    }
    private int calli(String fn, Object... args) throws Exception { return cl.getFunction(fn).invokeInt(args); }
    private Pointer callp(String fn, Object... args) throws Exception { return cl.getFunction(fn).invokePointer(args); }
    private void safe(String fn, Pointer p) { try { calli(fn, p); } catch (Exception e) {} }

    @Override
    public void close() {
        for (Pointer k : kernels.values()) safe("clReleaseKernel", k);
        safe("clReleaseProgram", program); safe("clReleaseCommandQueue", queue); safe("clReleaseContext", context);
    }

    private static final String KERNEL_SOURCE = "" +
    "__kernel void sub_fwd(__global double* in, __global double* w, __global double* b, __global double* out, int B) {\n" +
    "  int s=get_global_id(0), r=get_global_id(1), c=get_global_id(2);\n" +
    "  if(r>=B||c>=16)return; double sum=b[s*16+c];\n" +
    "  for(int k=0;k<36;k++) sum+=in[(s*B+r)*36+k]*w[s*36*16+k*16+c];\n" +
    "  out[(s*B+r)*16+c] = sum>0?sum:0;\n" +
    "}\n" +
    "__kernel void block_fwd(__global double* in, __global double* w, __global double* b, __global double* out, int B) {\n" +
    "  int s=get_global_id(0), r=get_global_id(1), c=get_global_id(2);\n" +
    "  if(r>=B||c>=64)return; double sum=b[s*64+c];\n" +
    "  for(int k=0;k<144;k++) sum+=in[(s*B+r)*144+k]*w[s*144*64+k*64+c];\n" +
    "  out[(s*B+r)*64+c] = sum>0?sum:0;\n" +
    "}\n" +
    "__kernel void top_fwd(__global double* in, __global double* w, __global double* b, __global double* out, int B) {\n" +
    "  int r=get_global_id(0), c=get_global_id(1);\n" +
    "  if(r>=B||c>=256)return; double sum=b[c];\n" +
    "  for(int k=0;k<600;k++) sum+=in[r*600+k]*w[k*256+c];\n" +
    "  out[r*256+c] = sum>0?sum:0;\n" +
    "}\n" +
    "__kernel void policy_fwd(__global double* in, __global double* w, __global double* b, __global double* out, int B) {\n" +
    "  int r=get_global_id(0); if(r>=B)return;\n" +
    "  double l[362]; double mx=-1e30;\n" +
    "  for(int j=0;j<362;j++){ double s=b[j]; for(int k=0;k<256;k++) s+=in[r*256+k]*w[k*362+j]; l[j]=s; if(s>mx)mx=s; }\n" +
    "  double se=0; for(int j=0;j<362;j++){ double e=exp(l[j]-mx); l[j]=e; se+=e; }\n" +
    "  for(int j=0;j<362;j++) out[r*362+j]=l[j]/se;\n" +
    "}\n" +
    "__kernel void value_fwd(__global double* in, __global double* w1, __global double* b1, __global double* w2, __global double* out, int B) {\n" +
    "  int r=get_global_id(0); if(r>=B)return;\n" +
    "  double h[128]; for(int j=0;j<128;j++){ double s=b1[j]; for(int k=0;k<256;k++) s+=in[r*256+k]*w1[k*128+j]; h[j]=s>0?s:0; }\n" +
    "  double s=0; for(int k=0;k<128;k++) s+=w2[k]*h[k]; out[r]=tanh(s);\n" +
    "}\n" +
    "__kernel void matmul(__global double* A, __global double* B, __global double* C, int M, int N, int K) {\n" +
    "  int r=get_global_id(0),c=get_global_id(1); if(r>=M||c>=N)return;\n" +
    "  double s=0; for(int k=0;k<K;k++) s+=A[r*K+k]*B[k*N+c]; C[r*N+c]=s;\n" +
    "}\n" +
    "__kernel void matmul_tA(__global double* A, __global double* B, __global double* C, int M, int N, int K) {\n" +
    "  int r=get_global_id(0),c=get_global_id(1); if(r>=M||c>=N)return;\n" +
    "  double s=0; for(int k=0;k<K;k++) s+=A[k*M+r]*B[k*N+c]; C[r*N+c]=s;\n" +
    "}\n" +
    "__kernel void sgd(__global double* w, __global double* g, double lr, double l2, int n) {\n" +
    "  int i=get_global_id(0); if(i>=n)return; w[i]-=lr*(g[i]+l2*w[i]);\n" +
    "}\n";
}