package com.wzz.game_console.client.screens.games.gogame;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * OpenCL GPU 加速后端。CPU 负责构建输入，GPU 负责矩阵运算。
 * <p>
 * 进程内共享一个上下文：训练时 30 局自对弈各自 new NeuralEvaluator 再各自
 * new OpenCLBackend 会把 RTX 级卡打成 30 份互斥队列。共享后 MCTS 前向可以
 * 在同一设备上排队，再由 {@link #inferForward} 一次提交整批。
 */
public class OpenCLBackend implements AutoCloseable {
    private static final int CL_MEM_READ_WRITE = 1;
    private static final int CL_DEVICE_EXTENSIONS = 0x1030;
    private static final int CL_DEVICE_DOUBLE_FP_CONFIG = 0x1032;
    private static final Object SHARED_LOCK = new Object();
    private static OpenCLBackend shared;
    private static String sharedFailure;
    static final long RETRY_DELAY_NANOS = 30_000_000_000L;
    private static long sharedFailureAt;

    private volatile boolean available = false;
    private volatile boolean closed;
    /** Serializes native use with close; close waits until the active batch has left. */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicInteger retainCount = new AtomicInteger(1);
    private String deviceName = "CPU";
    private NativeLibrary cl;
    private Pointer context, queue, device, program;
    private final java.util.Map<String, Pointer> kernels = new java.util.concurrent.ConcurrentHashMap<>();
    private long uploadedVersion = Long.MIN_VALUE;
    private int uploadedOwnerId = 0;
    private Pointer dSubW, dSubB, dBlkW, dBlkB, dTopW, dTopB, dPolW, dPolB, dValW1, dValB1, dValW2;
    private int actCapacity;
    private Pointer aSubIn, aSubOut, aBlkIn, aBlkOut, aTopIn, aShared, aAux, aPol, aVal;

    public OpenCLBackend() {
        this(true);
    }

    // Lifecycle tests can exercise sharing without loading a native driver.
    OpenCLBackend(boolean initialize) {
        if (!initialize) return;
        // ★ catch Throwable：JNA 缺失/UnsatisfiedLinkError 是 Error 不是 Exception，
        //   原版 catch (Exception) 拦不住，GPU 探测失败会直接炸掉调用方
        try { init(); available = true; }
        catch (Throwable t) {
            System.err.println("[OpenCL] 初始化失败: " + t);
            destroyNative();
        }
    }

    /**
     * 进程级共享后端。失败后冷却 30 秒再探测，避免每次推理重新加载 OpenCL。
     * 调用方必须 {@link #close()}（内部按引用计数释放）。
     */
    public static OpenCLBackend acquireShared() {
        return acquireShared(OpenCLBackend::new, System.nanoTime());
    }

    static OpenCLBackend acquireShared(Supplier<OpenCLBackend> factory, long now) {
        synchronized (SHARED_LOCK) {
            if (shared != null) {
                if (shared.isAvailable()) {
                    shared.retainCount.incrementAndGet();
                    return shared;
                }
                shared = null;
            }
            if (sharedFailure != null && now - sharedFailureAt < RETRY_DELAY_NANOS) return null;
            OpenCLBackend created = factory.get();
            if (!created.isAvailable()) {
                sharedFailure = "unavailable";
                sharedFailureAt = now;
                created.destroyNative();
                return null;
            }
            sharedFailure = null;
            created.retainCount.set(1);
            shared = created;
            return created;
        }
    }

    public static String lastSharedFailure() {
        synchronized (SHARED_LOCK) {
            return sharedFailure;
        }
    }

    /** 只读探测：不增加引用计数，供训练入口/探针打印设备名。 */
    public static OpenCLBackend peekShared() {
        synchronized (SHARED_LOCK) {
            return (shared != null && shared.isAvailable()) ? shared : null;
        }
    }

    public boolean isAvailable() { return available && !closed; }
    public String getDeviceName() { return deviceName; }

    private void init() throws Exception {
        cl = NativeLibrary.getInstance("OpenCL");
        IntByReference n = new IntByReference();
        checkCl("clGetPlatformIDs(count)", calli("clGetPlatformIDs", 0, null, n));
        if (n.getValue() == 0) throw new Exception("无 OpenCL 平台");
        Pointer[] platforms = new Pointer[n.getValue()];
        checkCl("clGetPlatformIDs(list)", calli("clGetPlatformIDs", n.getValue(), platforms, null));
        for (Pointer p : platforms) {
            if (p == null) continue;
            device = findDevice(p, 4L);
            if (device != null) break;
        }
        if (device == null) throw new Exception("无 GPU");
        deviceName = getDeviceName(device);
        Pointer[] devs = {device};
        IntByReference ec = new IntByReference();
        context = create("clCreateContext", ec, null, 1, devs, null, null, ec);
        try { queue = create("clCreateCommandQueueWithProperties", ec, context, device, null, ec); }
        catch (Throwable e) { queue = create("clCreateCommandQueue", ec, context, device, 0L, ec); }

        String source = fp64Pragma(device) + KERNEL_SOURCE;
        byte[] src = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (Memory mem = new Memory(src.length + 1)) {
            mem.write(0, src, 0, src.length); mem.setByte(src.length, (byte)0);
            Pointer[] strings = {mem};
            long[] lens = {src.length};
            program = create("clCreateProgramWithSource", ec, context, 1, strings, lens, ec);
        }
        int be = calli("clBuildProgram", program, 0, null, null, null, null);
        if (be != 0) { String log = getBuildLog(program); throw new Exception("编译失败: " + log); }

        for (String name : "sub_fwd,block_fwd,top_fwd,policy_fwd,value_fwd,pack_blk_in,pack_top_in".split(",")) {
            Pointer k = create("clCreateKernel(" + name + ")", "clCreateKernel", ec, program, name, ec);
            kernels.put(name, k);
        }
        // 关键内核缺失（如老驱动编译失败被吞）时必须回退 CPU，
        // 否则 batchPass0Forward 会拿到 null 结果被当作"成功"
        if (kernels.size() < 7) throw new Exception("计算内核不足: 仅加载 " + kernels.keySet());
        System.out.println("[OpenCL] " + deviceName + " 内核: " + kernels.size());
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
    public boolean batchPass0Forward(double[][][][] planes, double[][] aux, int B, int ownerId, long modelVersion,
                                      double[][][] subW, double[][] subB,
                                      double[][][] blkW, double[][] blkB,
                                      double[][] topW, double[] topB,
                                      double[][] polW, double[] polB,
                                      double[][] valW1, double[] valB1, double[] valW2, double valB2,
                                      double[][][][] bSubIn, double[][][][] bSubZ,
                                      double[][][] bBlkIn, double[][][] bBlkZ,
                                      double[][] bTopIn, double[][] bShared, double[][] bShZ,
                                      double[][] bPolicyOut, double[] bValueOut) {
        if (!lifecycleLock.tryLock()) return false;
        boolean failed = false;
        try {
            if (closed || !available || Thread.currentThread().isInterrupted()) return false;
            runDeviceForward(planes, aux, B, ownerId, modelVersion,
                    subW, subB, blkW, blkB, topW, topB,
                    polW, polB, valW1, valB1, valW2, valB2,
                    bSubIn, bSubZ, bBlkIn, bBlkZ, bTopIn, bShared, bShZ,
                    bPolicyOut, bValueOut, true);
            return true;
        } catch (Throwable t) {
            failed = true;
            available = false;
            System.err.println("[OpenCL] batchPass0Forward，已禁用 GPU: " + t);
            return false;
        } finally {
            lifecycleLock.unlock();
            if (failed) destroyNative();
        }
    }

    /**
     * MCTS / 对局推理：只回填策略和价值，不拷贝中间激活。
     * 失败返回 false，调用方回退 CPU；不因单次失败关掉设备。
     */
    public boolean inferForward(double[][][][] planes, double[][] aux, int B, int ownerId, long modelVersion,
                                double[][][] subW, double[][] subB,
                                double[][][] blkW, double[][] blkB,
                                double[][] topW, double[] topB,
                                double[][] polW, double[] polB,
                                double[][] valW1, double[] valB1, double[] valW2, double valB2,
                                double[][] policyOut, double[] valueOut) {
        lifecycleLock.lock();
        try {
            if (closed || !available || Thread.currentThread().isInterrupted()) return false;
            runDeviceForward(planes, aux, B, ownerId, modelVersion,
                    subW, subB, blkW, blkB, topW, topB,
                    polW, polB, valW1, valB1, valW2, valB2,
                    null, null, null, null, null, null, null,
                    policyOut, valueOut, false);
            return true;
        } catch (Throwable t) {
            System.err.println("[OpenCL] inferForward 失败，本批回退 CPU: " + t);
            return false;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void runDeviceForward(double[][][][] planes, double[][] aux, int B, int ownerId, long modelVersion,
                                  double[][][] subW, double[][] subB,
                                  double[][][] blkW, double[][] blkB,
                                  double[][] topW, double[] topB,
                                  double[][] polW, double[] polB,
                                  double[][] valW1, double[] valB1, double[] valW2, double valB2,
                                  double[][][][] bSubIn, double[][][][] bSubZ,
                                  double[][][] bBlkIn, double[][][] bBlkZ,
                                  double[][] bTopIn, double[][] bShared, double[][] bShZ,
                                  double[][] policyOut, double[] valueOut,
                                  boolean copyIntermediates) throws Exception {
        ensureWeights(ownerId, modelVersion, subW, subB, blkW, blkB, topW, topB, polW, polB, valW1, valB1, valW2);
        ensureActs(B);
        double[][][] subIn = extractSubInputs(planes, B);
        Pointer subK = kernels.get("sub_fwd");
        Pointer packBlk = kernels.get("pack_blk_in");
        Pointer blkK = kernels.get("block_fwd");
        Pointer packTop = kernels.get("pack_top_in");
        Pointer topK = kernels.get("top_fwd");
        Pointer polK = kernels.get("policy_fwd");
        Pointer valK = kernels.get("value_fwd");
        if (subK == null || packBlk == null || blkK == null || packTop == null
                || topK == null || polK == null || valK == null) {
            throw new Exception("GPU 内核未返回完整结果");
        }
        Memory subMem = flatten3D(subIn);
        Memory auxMem = flatten2D(aux);
        try {
            writeGAsync(aSubIn, subMem);
            writeGAsync(aAux, auxMem);
            setPtr(subK, 0, aSubIn); setPtr(subK, 1, dSubW); setPtr(subK, 2, dSubB);
            setPtr(subK, 3, aSubOut); setInt(subK, 4, B);
            enqueue3D(subK, 81, B, 16);

            setPtr(packBlk, 0, aSubOut); setPtr(packBlk, 1, aBlkIn); setInt(packBlk, 2, B);
            enqueue3D(packBlk, 9, B, 144);

            setPtr(blkK, 0, aBlkIn); setPtr(blkK, 1, dBlkW); setPtr(blkK, 2, dBlkB);
            setPtr(blkK, 3, aBlkOut); setInt(blkK, 4, B);
            enqueue3D(blkK, 9, B, 64);

            setPtr(packTop, 0, aBlkOut); setPtr(packTop, 1, aAux); setPtr(packTop, 2, aTopIn);
            setInt(packTop, 3, B);
            enqueue2D(packTop, B, 600);

            setPtr(topK, 0, aTopIn); setPtr(topK, 1, dTopW); setPtr(topK, 2, dTopB);
            setPtr(topK, 3, aShared); setInt(topK, 4, B);
            enqueue2D(topK, B, 256);

            setPtr(polK, 0, aShared); setPtr(polK, 1, dPolW); setPtr(polK, 2, dPolB);
            setPtr(polK, 3, aPol); setInt(polK, 4, B);
            enqueue1D(polK, B);

            setPtr(valK, 0, aShared); setPtr(valK, 1, dValW1); setPtr(valK, 2, dValB1);
            setPtr(valK, 3, dValW2); setPtr(valK, 4, aVal); setInt(valK, 5, B); setF64(valK, 6, valB2);
            enqueue1D(valK, B);

            checkCl("clFinish(forward)", calli("clFinish", queue));
        } catch (Exception e) {
            try {
                if (queue != null && cl != null) calli("clFinish", queue);
            } catch (Throwable ignored) {
                // 失败路径仍要排空队列，才能安全释放主机缓冲。
            }
            throw e;
        } finally {
            subMem.close();
            auxMem.close();
        }
        readBack2D(aPol, policyOut, B, 362);
        readBack(aVal, valueOut);
        if (!copyIntermediates) return;

        double[][][] subOut = new double[81][B][16];
        double[][][] blkIn = new double[9][B][144];
        double[][][] blkOut = new double[9][B][64];
        double[][] topIn = new double[B][600];
        double[][] sharedOut = new double[B][256];
        readBack3D(aSubOut, subOut, 81, B, 16);
        readBack3D(aBlkIn, blkIn, 9, B, 144);
        readBack3D(aBlkOut, blkOut, 9, B, 64);
        readBack2D(aTopIn, topIn, B, 600);
        readBack2D(aShared, sharedOut, B, 256);
        for (int n = 0; n < B; n++) {
            for (int b = 0; b < 9; b++) {
                for (int s = 0; s < 9; s++) {
                    System.arraycopy(subIn[b * 9 + s][n], 0, bSubIn[n][b][s], 0, 36);
                    System.arraycopy(subOut[b * 9 + s][n], 0, bSubZ[n][b][s], 0, 16);
                }
                System.arraycopy(blkIn[b][n], 0, bBlkIn[n][b], 0, 144);
                System.arraycopy(blkOut[b][n], 0, bBlkZ[n][b], 0, 64);
            }
            System.arraycopy(topIn[n], 0, bTopIn[n], 0, 600);
            System.arraycopy(sharedOut[n], 0, bShared[n], 0, 256);
            System.arraycopy(sharedOut[n], 0, bShZ[n], 0, 256);
        }
    }

    private void ensureWeights(int ownerId, long version, double[][][] subW, double[][] subB,
                               double[][][] blkW, double[][] blkB,
                               double[][] topW, double[] topB,
                               double[][] polW, double[] polB,
                               double[][] valW1, double[] valB1, double[] valW2) throws Exception {
        if (dSubW == null) {
            try {
                dSubW = alloc(9L * 36 * 16 * 8);
                dSubB = alloc(9L * 16 * 8);
                dBlkW = alloc(9L * 144 * 64 * 8);
                dBlkB = alloc(9L * 64 * 8);
                dTopW = alloc(600L * 256 * 8);
                dTopB = alloc(256L * 8);
                dPolW = alloc(256L * 362 * 8);
                dPolB = alloc(362L * 8);
                dValW1 = alloc(256L * 128 * 8);
                dValB1 = alloc(128L * 8);
                dValW2 = alloc(128L * 8);
            } catch (Exception e) {
                freeWeights();
                throw e;
            }
            uploadedVersion = Long.MIN_VALUE;
            uploadedOwnerId = 0;
        }
        if (version == uploadedVersion && ownerId == uploadedOwnerId) return;
        // Each block's nine subregions share one weight/bias set on the device.
        try (Memory m = flatten3D(subW)) { writeG(dSubW, m); }
        try (Memory m = flatten2D(subB)) { writeG(dSubB, m); }
        try (Memory m = flatten3D(blkW)) { writeG(dBlkW, m); }
        try (Memory m = flatten2D(blkB)) { writeG(dBlkB, m); }
        try (Memory m = flatten2D(topW)) { writeG(dTopW, m); }
        try (Memory m = flatten1D(topB)) { writeG(dTopB, m); }
        try (Memory m = flatten2D(polW)) { writeG(dPolW, m); }
        try (Memory m = flatten1D(polB)) { writeG(dPolB, m); }
        try (Memory m = flatten2D(valW1)) { writeG(dValW1, m); }
        try (Memory m = flatten1D(valB1)) { writeG(dValB1, m); }
        try (Memory m = flatten1D(valW2)) { writeG(dValW2, m); }
        checkCl("clFinish(weights)", calli("clFinish", queue));
        uploadedVersion = version;
        uploadedOwnerId = ownerId;
    }

    private void ensureActs(int B) throws Exception {
        if (aSubIn != null && B <= actCapacity) return;
        freeActs();
        int cap = Math.max(B, 64);
        try {
            aSubIn = alloc(81L * cap * 36 * 8);
            aSubOut = alloc(81L * cap * 16 * 8);
            aBlkIn = alloc(9L * cap * 144 * 8);
            aBlkOut = alloc(9L * cap * 64 * 8);
            aTopIn = alloc((long) cap * 600 * 8);
            aShared = alloc((long) cap * 256 * 8);
            aAux = alloc((long) cap * 24 * 8);
            aPol = alloc((long) cap * 362 * 8);
            aVal = alloc((long) cap * 8);
            actCapacity = cap;
        } catch (Exception e) {
            freeActs();
            throw e;
        }
    }

    private void freeActs() {
        free(aSubIn, aSubOut, aBlkIn, aBlkOut, aTopIn, aShared, aAux, aPol, aVal);
        aSubIn = aSubOut = aBlkIn = aBlkOut = aTopIn = aShared = aAux = aPol = aVal = null;
        actCapacity = 0;
    }

    private void freeWeights() {
        free(dSubW, dSubB, dBlkW, dBlkB, dTopW, dTopB, dPolW, dPolB, dValW1, dValB1, dValW2);
        dSubW = dSubB = dBlkW = dBlkB = dTopW = dTopB = dPolW = dPolB = dValW1 = dValB1 = dValW2 = null;
        uploadedVersion = Long.MIN_VALUE;
        uploadedOwnerId = 0;
    }

    // ── 数据提取 ──
    private double[][][] extractSubInputs(double[][][][] planes, int B) {
        double[][][] out = new double[81][B][36];
        for (int n = 0; n < B; n++) {
            double[][][] p = planes[n];
            for (int b = 0; b < 9; b++) {
                // 与 NeuralEvaluator.BLOCK_STARTS 一致：x=(b/3)*6, y=(b%3)*6
                int bx = (b / 3) * 6, by = (b % 3) * 6;
                for (int s = 0; s < 9; s++) {
                    // 与 NeuralEvaluator.SUB_OFFSETS 一致：x=(s/3)*2, y=(s%3)*2
                    int sx = bx + (s / 3) * 2, sy = by + (s % 3) * 2, si = b * 9 + s, idx = 0;
                    for (int pp = 0; pp < 4; pp++)
                        for (int dx = 0; dx < 3; dx++)
                            for (int dy = 0; dy < 3; dy++)
                                out[si][n][idx++] = p[pp][sx + dx][sy + dy];
                }
            }
        }
        return out;
    }

    // ── GPU 内存/执行 ──
    private Pointer alloc(long bytes) throws Exception {
        IntByReference error = new IntByReference();
        return create("clCreateBuffer", error, context, CL_MEM_READ_WRITE, bytes, null, error);
    }
    private void free(Pointer... ps) { for (Pointer p : ps) if (p != null) safe("clReleaseMemObject", p); }
    private void writeG(Pointer dst, Memory src) throws Exception {
        writeBuffer(dst, src, 1);
    }
    private void writeGAsync(Pointer dst, Memory src) throws Exception {
        writeBuffer(dst, src, 0);
    }
    private void writeBuffer(Pointer dst, Memory src, int blocking) throws Exception {
        checkCl("clEnqueueWriteBuffer", calli("clEnqueueWriteBuffer", queue, dst, blocking, 0L, src.size(), src, 0, null, null));
    }
    private void readBack(Pointer src, double[] out) throws Exception {
        try (Memory m = new Memory((long)out.length * 8)) {
            checkCl("clEnqueueReadBuffer", calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null));
            checkCl("clFinish(read)", calli("clFinish", queue)); double[] flat = m.getDoubleArray(0, out.length);
            System.arraycopy(flat, 0, out, 0, out.length);
        }
    }
    private void readBack1D(Pointer src, double[] out, int n) throws Exception {
        try (Memory m = new Memory((long)n * 8)) {
            checkCl("clEnqueueReadBuffer", calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null));
            checkCl("clFinish(read)", calli("clFinish", queue));
            m.read(0, out, 0, n);
        }
    }
    private void readBack2D(Pointer src, double[][] out, int B, int N) throws Exception {
        try (Memory m = new Memory((long)B * N * 8)) {
            checkCl("clEnqueueReadBuffer", calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null));
            checkCl("clFinish(read)", calli("clFinish", queue)); double[] flat = m.getDoubleArray(0, B * N);
            for (int n = 0; n < B; n++) System.arraycopy(flat, n * N, out[n], 0, N);
        }
    }
    private void readBack3D(Pointer src, double[][][] out, int S, int B, int N) throws Exception {
        try (Memory m = new Memory((long)S * B * N * 8)) {
            checkCl("clEnqueueReadBuffer", calli("clEnqueueReadBuffer", queue, src, 1, 0L, m.size(), m, 0, null, null));
            checkCl("clFinish(read)", calli("clFinish", queue)); double[] flat = m.getDoubleArray(0, S * B * N);
            for (int s = 0; s < S; s++)
                for (int n = 0; n < B; n++)
                    System.arraycopy(flat, (s * B + n) * N, out[s][n], 0, N);
        }
    }
    private void setPtr(Pointer k, int idx, Pointer p) throws Exception {
        try (Memory ref = new Memory(Native.POINTER_SIZE)) {
            ref.setPointer(0, p);
            checkCl("clSetKernelArg(pointer)", calli("clSetKernelArg", k, idx,
                    (long) Native.POINTER_SIZE, ref));
        }
    }
    private void setInt(Pointer k, int idx, int v) throws Exception {
        try (Memory m = new Memory(4)) {
            m.setInt(0, v);
            checkCl("clSetKernelArg(int)", calli("clSetKernelArg", k, idx, 4L, m));
        }
    }
    private void setF64(Pointer k, int idx, double v) throws Exception {
        try (Memory m = new Memory(8)) {
            m.setDouble(0, v);
            checkCl("clSetKernelArg(double)", calli("clSetKernelArg", k, idx, 8L, m));
        }
    }
    private void enqueue1D(Pointer k, int n) throws Exception {
        try (Memory g = new Memory(Native.SIZE_T_SIZE)) {
            writeSizeT(g, 0, ceil(n, 256) * 256);
            checkCl("clEnqueueNDRangeKernel(1D)", calli("clEnqueueNDRangeKernel", queue, k, 1, null, g, null, 0, null, null));
        }
    }
    private void enqueue2D(Pointer k, int x, int y) throws Exception {
        try (Memory g = new Memory((long) Native.SIZE_T_SIZE * 2)) {
            writeSizeT(g, 0, ceil(x, 16) * 16);
            writeSizeT(g, Native.SIZE_T_SIZE, ceil(y, 16) * 16);
            checkCl("clEnqueueNDRangeKernel(2D)", calli("clEnqueueNDRangeKernel", queue, k, 2, null, g, null, 0, null, null));
        }
    }
    private void enqueue3D(Pointer k, int x, int y, int z) throws Exception {
        try (Memory g = new Memory((long) Native.SIZE_T_SIZE * 3)) {
            writeSizeT(g, 0, ceil(x, 16) * 16);
            writeSizeT(g, Native.SIZE_T_SIZE, ceil(y, 16) * 16);
            writeSizeT(g, (long) Native.SIZE_T_SIZE * 2, ceil(z, 16) * 16);
            checkCl("clEnqueueNDRangeKernel(3D)", calli("clEnqueueNDRangeKernel", queue, k, 3, null, g, null, 0, null, null));
        }
    }
    private static long ceil(long a, long b) { return (a + b - 1) / b; }
    private static void writeSizeT(Memory memory, long offset, long value) {
        if (Native.SIZE_T_SIZE == Long.BYTES) memory.setLong(offset, value);
        else memory.setInt(offset, (int) value);
    }

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
        checkCl("clGetDeviceIDs(list)", calli("clGetDeviceIDs", platform, type, n.getValue(), devs, null));
        for (Pointer candidate : devs) {
            if (candidate != null && supportsFp64(candidate)) return candidate;
        }
        return null;
    }
    private boolean supportsFp64(Pointer dev) throws Exception {
        try (Memory config = new Memory(Long.BYTES)) {
            return calli("clGetDeviceInfo", dev, CL_DEVICE_DOUBLE_FP_CONFIG,
                    Long.BYTES, config, null) == 0 && config.getLong(0) != 0L;
        }
    }
    private String fp64Pragma(Pointer dev) throws Exception {
        String extensions = getDeviceString(dev, CL_DEVICE_EXTENSIONS);
        if (extensions.contains("cl_khr_fp64")) {
            return "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n";
        }
        if (extensions.contains("cl_amd_fp64")) {
            return "#pragma OPENCL EXTENSION cl_amd_fp64 : enable\n";
        }
        return "";
    }
    private String getDeviceName(Pointer dev) throws Exception {
        return getDeviceString(dev, 0x102B);
    }
    private String getDeviceString(Pointer dev, int property) throws Exception {
        try (Memory sizeOut = new Memory(Native.SIZE_T_SIZE)) {
            checkCl("clGetDeviceInfo(size)", calli("clGetDeviceInfo", dev, property, 0, null, sizeOut));
            long size = readSizeT(sizeOut);
            if (size <= 0 || size > Integer.MAX_VALUE) return "";
            try (Memory m = new Memory(size)) {
                checkCl("clGetDeviceInfo(value)", calli("clGetDeviceInfo", dev, property, size, m, null));
                return m.getString(0, "UTF-8");
            }
        }
    }
    private String getBuildLog(Pointer prog) throws Exception {
        try (Memory sizeOut = new Memory(Native.SIZE_T_SIZE)) {
            checkCl("clGetProgramBuildInfo(size)", calli("clGetProgramBuildInfo", prog, device, 0x1183, 0, null, sizeOut));
            long size = readSizeT(sizeOut);
            if (size <= 0 || size > Integer.MAX_VALUE) return "";
            try (Memory m = new Memory(size)) {
                checkCl("clGetProgramBuildInfo(value)", calli("clGetProgramBuildInfo", prog, device, 0x1183, size, m, null));
                return m.getString(0, "UTF-8");
            }
        }
    }
    private static long readSizeT(Memory memory) {
        return Native.SIZE_T_SIZE == Long.BYTES
                ? memory.getLong(0) : Integer.toUnsignedLong(memory.getInt(0));
    }
    private static void checkCl(String operation, int status) throws Exception {
        if (status != 0) throw new Exception(operation + " 失败，OpenCL 错误码 " + status);
    }
    private Pointer create(String function, IntByReference error, Object... args) throws Exception {
        return create(function, function, error, args);
    }
    private Pointer create(String operation, String function, IntByReference error, Object... args) throws Exception {
        error.setValue(Integer.MIN_VALUE);
        Pointer pointer = callp(function, args);
        checkCl(operation, error.getValue());
        if (pointer == null) throw new Exception(operation + " 返回空句柄");
        return pointer;
    }
    private int calli(String fn, Object... args) throws Exception { return cl.getFunction(fn).invokeInt(args); }
    private Pointer callp(String fn, Object... args) throws Exception { return cl.getFunction(fn).invokePointer(args); }
    private void safe(String fn, Pointer p) {
        if (p == null || cl == null) return;
        try {
            calli(fn, p);
        } catch (Throwable ignored) {
            // 释放阶段必须是 best-effort，不能让缺失/损坏的本地库越过 close 边界。
        }
    }

    @Override
    public void close() {
        synchronized (SHARED_LOCK) {
            if (this == shared) {
                if (retainCount.decrementAndGet() > 0) return;
                // The last evaluator releases device buffers and native handles.
                // Finish teardown before another acquire creates a new context.
                shared = null;
                destroyNative();
                return;
            }
        }
        destroyNative();
    }

    private void destroyNative() {
        // The same mutex guards every native batch.  Do not release any handle
        // until the in-flight batch (including clFinish/readback) has returned.
        lifecycleLock.lock();
        try {
            if (closed) return;
            closed = true;
            available = false;
            freeActs();
            freeWeights();
            for (Pointer k : kernels.values()) safe("clReleaseKernel", k);
            kernels.clear();
            safe("clReleaseProgram", program);
            safe("clReleaseCommandQueue", queue);
            safe("clReleaseContext", context);
            program = null;
            queue = null;
            context = null;
            device = null;
            cl = null;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private static final String KERNEL_SOURCE = "" +
    "__kernel void sub_fwd(__global double* in, __global double* w, __global double* b, __global double* out, int B) {\n" +
    "  int s=get_global_id(0), r=get_global_id(1), c=get_global_id(2);\n" +
    "  if(s>=81||r>=B||c>=16)return; int block=s/9; double sum=b[block*16+c];\n" +
    "  for(int k=0;k<36;k++) sum+=in[(s*B+r)*36+k]*w[block*36*16+k*16+c];\n" +
    "  out[(s*B+r)*16+c] = sum>0?sum:0;\n" +
    "}\n" +
    "__kernel void block_fwd(__global double* in, __global double* w, __global double* b, __global double* out, int B) {\n" +
    "  int s=get_global_id(0), r=get_global_id(1), c=get_global_id(2);\n" +
    "  if(s>=9||r>=B||c>=64)return; double sum=b[s*64+c];\n" +
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
    "  double ise=1.0/max(se,1e-30); for(int j=0;j<362;j++) out[r*362+j]=l[j]*ise;\n" +
    "}\n" +
    "__kernel void value_fwd(__global double* in, __global double* w1, __global double* b1, __global double* w2, __global double* out, int B, double b2) {\n" +
    "  int r=get_global_id(0); if(r>=B)return;\n" +
    "  double h[128]; for(int j=0;j<128;j++){ double s=b1[j]; for(int k=0;k<256;k++) s+=in[r*256+k]*w1[k*128+j]; h[j]=s>0?s:0; }\n" +
    "  double s=b2; for(int k=0;k<128;k++) s+=w2[k]*h[k]; out[r]=tanh(s);\n" +
    "}\n" +
    "__kernel void pack_blk_in(__global double* subOut, __global double* blkIn, int B) {\n" +
    "  int b=get_global_id(0), n=get_global_id(1), i=get_global_id(2);\n" +
    "  if(b>=9||n>=B||i>=144)return;\n" +
    "  blkIn[(b*B+n)*144+i] = subOut[((b*9+i/16)*B+n)*16+(i%16)];\n" +
    "}\n" +
    "__kernel void pack_top_in(__global double* blkOut, __global double* aux, __global double* topIn, int B) {\n" +
    "  int n=get_global_id(0), i=get_global_id(1);\n" +
    "  if(n>=B||i>=600)return;\n" +
    "  if(i<576){ int b=i/64; topIn[n*600+i]=blkOut[(b*B+n)*64+(i%64)]; }\n" +
    "  else topIn[n*600+i]=aux[n*24+(i-576)];\n" +
    "}\n";
}
