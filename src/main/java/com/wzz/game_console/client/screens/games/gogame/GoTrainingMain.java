package com.wzz.game_console.client.screens.games.gogame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Command-line entry point for pure-Java Go self-play training. */
public final class GoTrainingMain {
    private GoTrainingMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        GoSelfPlayTrainer.Config config = new GoSelfPlayTrainer.Config();
        config.searchTimeMillis = intOption(options, "searchTime", config.searchTimeMillis);
        config.maxIterations = intOption(options, "iterations", config.maxIterations);
        config.maxMoves = intOption(options, "maxMoves", config.maxMoves);
        config.maxReplaySamples = intOption(options, "maxReplaySamples", config.maxReplaySamples);
        int games = intOption(options, "games", 30);
        int parallelism = intOption(options, "parallelism", intOption(options, "threads", 30));
        int generations = intOption(options, "generations", 1);
        int epochs = intOption(options, "epochs", 1);
        double learningRate = doubleOption(options, "learningRate", 0.001);
        long seed = longOption(options, "seed", 0x5EEDL);
        Path weights = Path.of(required(options, "weights"));
        int checkpointInterval = intOption(options, "checkpoint", 10);

        NeuralEvaluator evaluator = new NeuralEvaluator();
        if (Files.exists(weights)) evaluator.load(weights);
        GoSelfPlayTrainer trainer = new GoSelfPlayTrainer(config, evaluator);
        GoSelfPlayTrainer.Result result = null;
        try {
            for (int generation = 0; generation < generations; generation++) {
                // 余弦退火学习率调度：从初始 LR 平滑衰减
                double frac = generations > 1 ? (double) generation / (generations - 1) : 0.0;
                double currentLR = Math.max(learningRate * 0.5 * (1.0 + Math.cos(Math.PI * frac)), 1e-6);
                result = trainer.runGeneration(games, parallelism, epochs, currentLR, seed + generation);
                System.out.printf("generation=%d lr=%.6f games=%d completed=%d samples=%d replay=%d meanLoss=%.8f%n",
                        generation + 1, currentLR, result.games, result.completedGames,
                        result.samples, trainer.getReplayBufferSize(), result.meanLoss);
                // 定期 checkpoint 保存，支持断点续训
                if (checkpointInterval > 0 && (generation + 1) % checkpointInterval == 0) {
                    evaluator.save(weights);
                    System.out.println("checkpoint saved at generation " + (generation + 1));
                }
            }
            evaluator.save(weights);
            System.out.println("saved=" + weights.toAbsolutePath());
        } catch (Throwable t) {
            // 崩溃时保存当前权重，防止训练白跑
            try {
                evaluator.save(weights);
                System.out.println("crash-saved=" + weights.toAbsolutePath());
            } catch (Exception ignored) {}
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            String name = arg.substring(2);
            if (name.isEmpty() || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + name);
            }
            result.put(name, args[++i]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required --" + key);
        return value;
    }

    private static int intOption(Map<String, String> options, String key, int fallback) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback;
    }
    private static long longOption(Map<String, String> options, String key, long fallback) {
        return options.containsKey(key) ? Long.parseLong(options.get(key)) : fallback;
    }
    private static double doubleOption(Map<String, String> options, String key, double fallback) {
        return options.containsKey(key) ? Double.parseDouble(options.get(key)) : fallback;
    }
}