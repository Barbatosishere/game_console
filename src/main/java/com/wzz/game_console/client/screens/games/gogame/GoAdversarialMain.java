package com.wzz.game_console.client.screens.games.gogame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 对抗训练 CLI 入口：己方 MCTS AI vs 外部 KataGo 引擎。
 *
 * 参数：
 *   --games          对局数（默认 10）
 *   --parallelism    并行数（默认 5）
 *   --generations    训练轮次（默认 1）
 *   --epochs         每代训练轮次（默认 1）
 *   --learningRate   学习率（默认 0.001）
 *   --weights        权重文件路径（必填）
 *   --katago         KataGo 可执行文件路径（必填）
 *   --katagoModel    KataGo 模型文件路径（可选）
 *   --katagoConfig   KataGo 配置文件（可选，默认 default_gtp.cfg）
 *   --maxReplaySamples replay buffer 上限（默认 20000）
 */
public final class GoAdversarialMain {
    private GoAdversarialMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);

        GoAdversarialTrainer.Config config = new GoAdversarialTrainer.Config();
        config.searchTimeMillis = intOption(options, "searchTime", config.searchTimeMillis);
        config.maxIterations = intOption(options, "iterations", config.maxIterations);
        config.maxMoves = intOption(options, "maxMoves", config.maxMoves);
        config.maxReplaySamples = intOption(options, "maxReplaySamples", config.maxReplaySamples);
        config.katagoPath = required(options, "katago");
        config.katagoModel = stringOption(options, "katagoModel", "");
        config.katagoConfig = stringOption(options, "katagoConfig", "default_gtp.cfg");

        int games = intOption(options, "games", 10);
        int parallelism = intOption(options, "parallelism", 5);
        int generations = intOption(options, "generations", 1);
        int epochs = intOption(options, "epochs", 1);
        double learningRate = doubleOption(options, "learningRate", 0.001);
        long seed = longOption(options, "seed", 0x5EEDL);
        Path weights = Path.of(required(options, "weights"));

        NeuralEvaluator evaluator = new NeuralEvaluator();
        if (Files.exists(weights)) {
            evaluator.load(weights);
            System.out.println("loaded=" + weights.toAbsolutePath());
        }

        GoAdversarialTrainer trainer = new GoAdversarialTrainer(config, evaluator);

        try {
            for (int gen = 0; gen < generations; gen++) {
                double frac = generations > 1 ? (double) gen / (generations - 1) : 0.0;
                double currentLR = Math.max(learningRate * 0.5 * (1.0 + Math.cos(Math.PI * frac)), 1e-6);
                GoAdversarialTrainer.Result result = trainer.runGeneration(
                        games, parallelism, epochs, currentLR, seed + gen);
                System.out.printf("generation=%d lr=%.6f games=%d completed=%d samples=%d ourWins=%d replay=%d meanLoss=%.8f%n",
                        gen + 1, currentLR, result.games, result.completedGames,
                        result.samples, result.ourWins, trainer.getReplayBufferSize(), result.meanLoss);
            }
        } catch (RuntimeException | Error t) {
            // ★ 崩溃保存：多代训练中途崩（OOM/GTP 异常/引擎崩溃）时，
            //   已训练完的各代权重不能随进程一起丢掉
            try {
                evaluator.save(weights);
                System.out.println("saved-on-crash=" + weights.toAbsolutePath());
            } catch (Exception saveErr) {
                System.err.println("save-on-crash failed: " + saveErr);
            }
            throw t;
        }

        evaluator.save(weights);
        System.out.println("saved=" + weights.toAbsolutePath());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected: " + arg);
            String name = arg.substring(2);
            if (name.isEmpty() || i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("Missing value for --" + name);
            result.put(name, args[++i]);
        }
        return result;
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing required --" + key);
        return v;
    }
    private static String stringOption(Map<String, String> opts, String key, String fallback) {
        return opts.containsKey(key) ? opts.get(key) : fallback;
    }
    private static int intOption(Map<String, String> opts, String key, int fallback) {
        return opts.containsKey(key) ? Integer.parseInt(opts.get(key)) : fallback;
    }
    private static long longOption(Map<String, String> opts, String key, long fallback) {
        return opts.containsKey(key) ? Long.parseLong(opts.get(key)) : fallback;
    }
    private static double doubleOption(Map<String, String> opts, String key, double fallback) {
        return opts.containsKey(key) ? Double.parseDouble(opts.get(key)) : fallback;
    }
}