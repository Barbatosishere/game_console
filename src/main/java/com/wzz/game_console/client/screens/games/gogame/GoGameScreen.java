package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.client.screens.games.LanMultiplayerScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class GoGameScreen extends Screen implements LanMultiplayerScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoGameScreen.class);
    boolean showExitConfirm = false;
    private static final int BOARD_SIZE = 19;

    private enum State { MENU, SETTINGS, PLAYING, SCORING, GAME_OVER }
    private final Set<Long> markedDead = new HashSet<>();
    private boolean scoringConfirmed;
    private long scoringRevision;
    private UUID scoringEpoch;
    private String scoringDigest = "";
    private boolean remoteScoringConfirmed;
    private long remoteConfirmedRevision = -1L;
    private String remoteConfirmedDigest = "";
    /** Canonical komi captured for this round's final scoring. */
    private double roundKomi = GoGame.DEFAULT_KOMI;
    private boolean roundKomiSet;
    private long scoringLastBroadcastTick = -40L;
    private GoScoringProtocol.Snapshot finalScoringSnapshot;
    /** Round identity and accepted move index prevent delayed packets crossing a restart. */
    private UUID gameEpoch = UUID.randomUUID();
    private long networkPly;
    private State state = State.MENU;
    private final GoGame game;
    private long tickCount = 0;
    private int cellSize, boardStartX, boardStartY;

    // ── 胜负结果 ──
    private String resultMsg  = "";
    private boolean myWin     = false;

    // ── LAN 联机 ──
    private static final int LAN_NONE   = LanMultiplayerScreen.LAN_NONE;
    private static final int LAN_HOST   = LanMultiplayerScreen.LAN_HOST;
    private static final int LAN_CLIENT = LanMultiplayerScreen.LAN_CLIENT;
    private int     lanMode    = LAN_NONE;
    private java.util.UUID remotePeer = null;
    /** LAN HOST=黑棋先手，CLIENT=白棋后手 */
    private boolean myTurn = true; // 单机或 HOST 默认先手
    /** 防重复发送 LEAVE_GAME 标志 */
    private boolean lanLeaveSent = false;

    // ── AI 引擎设置 ─────────────────────────────────────────
    /** 设置界面中当前选中的引擎 */
    private String settingsEngine = GoAI.normalizeEngine(GameSettings.getString("go", "engine", "mcts"));
    /** 设置界面中当前的搜索时间（ms） */
    private int settingsSearchTime = GameSettings.getInt("go", "searchTime", 3000);
    /** 设置界面中当前的 KataGo 路径 */
    private String settingsKatagoPath = GameSettings.getString("go", "katagoPath", "");
    private EditBox katagoPathEditBox;

    /** AI 后台思考标记（防止重复启动线程） */
    private volatile boolean aiThinking = false;
    /** A failed AI turn remains frozen until the round is restarted. */
    private boolean aiFailed = false;
    /** Versioned result preserves action semantics and its source position across the worker boundary. */
    private volatile GoGame.AiMoveComputation aiPendingComputation = null;
    /** Serializes AI worker start, completion publication, and client-side consumption. */
    private final Object aiWorkerLock = new Object();
    /** AI 后台是否已完成思考（待客户端线程消费） */
    private volatile boolean aiComputed = false;
    /** Generation associated with the published result; access under aiWorkerLock. */
    private int aiComputedGeneration = -1;
    /**
     * AI 搜索代际：resetGame 时递增，worker 落地前比对。
     * 修复：worker 的 finally 无条件 aiComputed=true，重开对局后迟到的落地会让
     * tick 对新 game 调 applyAiMove(null) 强制空过一手。代际守卫使旧 worker 的
     * 任何落地（含异常路径）全部失效，与 interrupt 的时序无关。
     */
    private volatile int aiGeneration = 0;

    /** 单机 / AI 构造 */
    public GoGameScreen(GoGame game) {
        this(game, game.isAiEnabled());
    }

    /** 单机构造，可选择 AI 对战或本地双人模式。 */
    public GoGameScreen(GoGame game, boolean aiMode) {
        super(Component.literal("围棋"));
        this.game = game;
        this.game.setAiMode(aiMode);
    }

    /** LAN 联机构造 */
    public GoGameScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("围棋"));
        this.game       = new GoGame(false);
        this.lanMode    = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
        this.myTurn     = isHost; // HOST（黑）先手
    }

    // ── LanMultiplayerScreen 接口 ──────────────────────────────
    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId() { return "go"; }

    /**
     * 来源校验：仅接受已配对对端（服务端盖章 UUID）发来的走法，
     * 防止在线第三方伪造报文注入走法。
     */
    @Override
    public void onRemoteMove(java.util.UUID senderUuid, String data) {
        if (remotePeer == null || !remotePeer.equals(senderUuid)) {
            LOGGER.warn("[围棋] 丢弃来源非法的联机走法: sender={}，期望对端={}", senderUuid, remotePeer);
            return;
        }
        this.onRemoteMove(data);
    }

    /** 退出对局时向对端发送 LEAVE_GAME（带防重复标志，避免重复发包） */
    private void sendLeaveGameOnce() {
        if (lanMode == LAN_NONE || lanLeaveSent || remotePeer == null) return;
        lanLeaveSent = true;
        sendLeaveGame();
    }

    /** 后台 AI 计算线程（cleanup 时 interrupt 并等待其退出） */
    private volatile Thread aiWorker = null;
    private boolean cleanedUp = false;

    /** 统一、幂等地释放本屏幕拥有的 worker 和棋局资源。 */
    private synchronized void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;
        Thread t;
        synchronized (aiWorkerLock) {
            aiGeneration++;
            t = aiWorker;
            aiWorker = null;
            aiThinking = false;
            aiComputed = false;
            aiComputedGeneration = -1;
            aiPendingComputation = null;
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(2000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try { game.close(); } catch (Exception ignored) {}
    }

    @Override
    public void removed() {
        sendLeaveGameOnce();
        cleanup();
        super.removed();
    }

    @Override
    public void onClose() {
        sendLeaveGameOnce();
        cleanup();
        super.onClose();
    }

    @Override
    public void onRemoteMove(String data) {
        if (lanMode == LAN_NONE || data == null) return;
        String[] message = data.split("\\|", -1);
        if (message.length != 4 || !"GO_MOVE1".equals(message[0])) return;
        UUID epoch = GoScoringProtocol.parseEpoch(message[1]);
        Long ply = GoScoringProtocol.parseRevision(message[2]);
        if (epoch == null || ply == null) return;
        String action = message[3];
        if ("START".equals(action) || "RESTART".equals(action)) {
            // START/RESTART is idempotent per epoch; a duplicate or delayed packet from
            // the current round must not erase moves already accepted on this client.
            if (lanMode != LAN_CLIENT || ply != 0 || epoch.equals(gameEpoch)) return;
            resetGame();
            gameEpoch = epoch;
            networkPly = 0;
            state = State.PLAYING;
            return;
        }
        if (!epoch.equals(gameEpoch) || ply != networkPly) return;
        // 认输不受回合状态限制；否则对方在本地回合认输时会被 guard 丢弃。
        if ("RESIGN".equals(action)) {
            if (state != State.PLAYING || game.isGameOver()) return;
            networkPly++;
            myWin = true;
            resultMsg = "对方认输，你赢了！";
            state = State.GAME_OVER;
            return;
        }
        if (state != State.PLAYING || game.isGameOver() || myTurn) return;
        if ("PASS".equals(action)) {
            game.pass();
            networkPly++;
            myTurn = true;
            // 只有 HOST 建立评分 epoch。CLIENT 等待 HOST 的 BEGIN，
            // 避免先生成随机 epoch 后拒绝主机的 canonical 状态。
            if (game.isGameOver() && lanMode == LAN_HOST) enterScoring();
            return;
        }
        String[] coordinates = action.split(",", -1);
        if (coordinates.length != 2) return;
        try {
            int x = Integer.parseInt(coordinates[0]);
            int y = Integer.parseInt(coordinates[1]);
            if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return;
            if (!game.placeStone(x, y)) return;
            networkPly++;
            myTurn = true;
            if (game.isGameOver() && lanMode == LAN_HOST) enterScoring();
        } catch (NumberFormatException ignored) {}
    }


    @Override public void onRemoteState(UUID senderUuid, String data) {
        if (remotePeer == null || !remotePeer.equals(senderUuid) || data == null) return;
        String[] parts = data.split("\\|", -1);
        if (parts.length < 2 || !GoScoringProtocol.PREFIX.equals(parts[0])) return;
        String action = parts[1];
        try {
            if ("BEGIN".equals(action) || "STATE".equals(action) || "FINAL".equals(action)) {
                GoScoringProtocol.Phase phase = switch (state) {
                    case PLAYING -> GoScoringProtocol.Phase.PLAYING;
                    case SCORING -> GoScoringProtocol.Phase.SCORING;
                    case GAME_OVER -> GoScoringProtocol.Phase.FINISHED;
                    default -> GoScoringProtocol.Phase.OTHER;
                };
                GoScoringProtocol.Snapshot current = currentScoringSnapshot();
                GoScoringProtocol.Snapshot incoming = GoScoringProtocol.receiveSnapshot(game,
                        new GoScoringProtocol.Receiver(lanMode == LAN_CLIENT, remotePeer, gameEpoch, phase, current),
                        senderUuid, parts);
                if (incoming == null) return;
                if ("FINAL".equals(action)) {
                    finishGame(incoming.marks());
                    return;
                }
                if (!incoming.equals(current)) {
                    scoringConfirmed = false;
                    remoteScoringConfirmed = false;
                    remoteConfirmedRevision = -1L;
                    remoteConfirmedDigest = "";
                }
                scoringEpoch = incoming.epoch();
                scoringRevision = incoming.revision();
                markedDead.clear();
                markedDead.addAll(incoming.marks());
                scoringDigest = incoming.digest();
                roundKomi = incoming.komi();
                roundKomiSet = true;
                state = State.SCORING;
            } else if ("CLEAR".equals(action) && lanMode == LAN_HOST && state == State.SCORING && parts.length == 4) {
                UUID epoch = GoScoringProtocol.parseEpoch(parts[2]);
                Long baseRevision = GoScoringProtocol.parseRevision(parts[3]);
                if (epoch == null || baseRevision == null || !epoch.equals(scoringEpoch)
                        || baseRevision != scoringRevision) return;
                markedDead.clear();
                scoringRevision++;
                scoringConfirmed = false;
                remoteScoringConfirmed = false;
                remoteConfirmedRevision = -1L;
                remoteConfirmedDigest = "";
                sendScoringState();
            } else if ("TOGGLE".equals(action) && lanMode == LAN_HOST && state == State.SCORING && parts.length == 5) {
                UUID epoch = GoScoringProtocol.parseEpoch(parts[2]);
                Long baseRevision = GoScoringProtocol.parseRevision(parts[3]);
                if (epoch == null || baseRevision == null || !epoch.equals(scoringEpoch)
                        || baseRevision != scoringRevision) return;
                String[] xy = parts[4].split(",", -1);
                if (xy.length != 2) return;
                int x = Integer.parseInt(xy[0]);
                int y = Integer.parseInt(xy[1]);
                if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE
                        || game.getStone(x, y) == GoPlayer.NONE) return;
                toggleMarkedGroupLocal(x, y);
                sendScoringState();
            } else if ("CONFIRM".equals(action) && lanMode == LAN_HOST && state == State.SCORING
                    && parts.length == 7) {
                UUID epoch = GoScoringProtocol.parseEpoch(parts[2]);
                Long revision = GoScoringProtocol.parseRevision(parts[3]);
                Double komi = GoScoringProtocol.parseKomi(parts[6]);
                if (epoch == null || revision == null || komi == null || !epoch.equals(scoringEpoch)
                        || revision != scoringRevision || !sameKomi(komi, roundKomi)) return;
                Set<Long> confirmedMarks = GoScoringProtocol.parseMarks(parts[4]);
                validateMarks(confirmedMarks);
                String digest = GoScoringProtocol.digest(confirmedMarks);
                if (!digest.equals(parts[5]) || !confirmedMarks.equals(markedDead)) return;
                remoteScoringConfirmed = true;
                remoteConfirmedRevision = revision;
                remoteConfirmedDigest = digest;
                if (scoringConfirmed && remoteConfirmedRevision == scoringRevision
                        && remoteConfirmedDigest.equals(scoringDigest)) {
                    finishConfirmedScoring();
                }
            }
        } catch (IllegalArgumentException ignored) {
            LOGGER.warn("[围棋] 丢弃畸形结算消息");
        }
    }
    @Override public void onRemoteState(String data) { }
    @Override public void onRemoteGameOver(String data) { }

    private void sendLanMove(String action) {
        if (lanMode == LAN_NONE || gameEpoch == null) return;
        sendMoveEnvelope("GO_MOVE1|" + gameEpoch + "|" + networkPly + "|" + action);
    }

    private void sendLanStart(String action) {
        if (lanMode != LAN_HOST || gameEpoch == null) return;
        sendMoveEnvelope("GO_MOVE1|" + gameEpoch + "|0|" + action);
    }

    /** 单一入口判断本地玩家当前是否可落子/虚着/显示预览。 */
    private boolean canLocalPlayerMove() {
        if (state != State.PLAYING || game.isGameOver()) return false;
        if (lanMode != LAN_NONE) return myTurn;
        return !game.isAiMode() || game.getCurrentPlayer() == GoPlayer.BLACK;
    }

    // ── 游戏逻辑 ──────────────────────────────────────────────
    private void resetGame() {
        // ★ Bug修复：玩家在 AI 思考中按 N 重开,旧 AI 线程仍持有旧 game 引用,
        //   写入的 aiPendingComputation 可能是新 game 还没准备好的状态,后续落子错乱。
        //   这里中断旧 AI 线程并清空 pending 状态
        Thread old;
        synchronized (aiWorkerLock) {
            old = aiWorker;
            aiGeneration++; // 旧 worker 的迟到落地一律作废
            aiWorker = null;
            aiThinking = false;
            aiPendingComputation = null;
            aiFailed = false;
            aiComputed = false;
            aiComputedGeneration = -1;
        }
        if (old != null && old != Thread.currentThread()) {
            old.interrupt();
            try { old.join(1000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        game.reset();
        myTurn    = (lanMode != LAN_CLIENT);
        resultMsg = "";
        myWin     = false;
        markedDead.clear();
        scoringConfirmed = false;
        remoteScoringConfirmed = false;
        remoteConfirmedRevision = -1L;
        remoteConfirmedDigest = "";
        scoringRevision = 0;
        scoringEpoch = null;
        scoringDigest = "";
        finalScoringSnapshot = null;
        roundKomi = GoGame.DEFAULT_KOMI;
        roundKomiSet = false;
        scoringLastBroadcastTick = tickCount - 40L;
        state     = State.PLAYING;
    }

    /**
     * 游戏结束时计算胜负（中国规则数子法，黑棋贴目 7.5）。
     * 修复 Bug：原版 endGame() 不计算胜者，导致局域网双方都显示"你赢了"。
     */
    private void enterScoring() {
        markedDead.clear();
        scoringRevision = 0;
        // A scoring session belongs to exactly one game round; reuse the round epoch
        // so delayed scoring packets from a previous restart cannot enter this game.
        scoringEpoch = gameEpoch;
        roundKomi = GoGame.normalizeKomi(GoGame.getConfiguredKomi());
        roundKomiSet = true;
        scoringDigest = GoScoringProtocol.digest(markedDead);
        scoringConfirmed = false;
        remoteScoringConfirmed = false;
        remoteConfirmedRevision = -1L;
        remoteConfirmedDigest = "";
        state = State.SCORING;
        if (lanMode == LAN_HOST) {
            // Bootstrap is self-contained so a reordered follow-up STATE cannot strand the client.
            sendStateEnvelope("GO_SCORE1|BEGIN|" + scoringEpoch + "|" + scoringRevision + "|"
                    + encodeMarks() + "|" + scoringDigest + "|" + formatRoundKomi());
            sendScoringState();
        }
    }

    private String formatRoundKomi() {
        return GoScoringProtocol.formatKomi(roundKomiSet ? roundKomi : GoGame.DEFAULT_KOMI);
    }

    private static boolean sameKomi(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private GoScoringProtocol.Snapshot currentScoringSnapshot() {
        if (scoringEpoch == null || !roundKomiSet) return null;
        return new GoScoringProtocol.Snapshot(scoringEpoch, scoringRevision, markedDead, scoringDigest, roundKomi);
    }

    private void finishConfirmedScoring() {
        finalScoringSnapshot = currentScoringSnapshot();
        if (finalScoringSnapshot == null) return;
        sendStateEnvelope(finalScoringSnapshot.encode("FINAL"));
        scoringLastBroadcastTick = tickCount;
        finishGame(finalScoringSnapshot.marks());
    }

    private void sendScoringState() {
        if (lanMode != LAN_HOST || scoringEpoch == null) return;
        scoringDigest = GoScoringProtocol.digest(markedDead);
        sendStateEnvelope("GO_SCORE1|STATE|" + scoringEpoch + "|" + scoringRevision
                + "|" + GoScoringProtocol.encodeMarks(markedDead) + "|" + scoringDigest
                + "|" + formatRoundKomi());
    }

    private void validateMarks(Set<Long> marks) {
        for (long point : marks) {
            if (game.getStone(GoScoringProtocol.x(point), GoScoringProtocol.y(point)) == GoPlayer.NONE) {
                throw new IllegalArgumentException("marked point is empty");
            }
        }
    }

    private void finishGame() { finishGame(Collections.emptySet()); }

    private void finishGame(Set<Long> deadGroups) {
        // 统一使用 GoGame 的显式死棋计分实现，避免自动猜测死活。
        double komi = roundKomiSet ? roundKomi : GoGame.getConfiguredKomi();
        GoGame.Score score = game.getScores(deadGroups, komi);
        double blackScore = score.black();
        double whiteScore = score.white();
        GoPlayer winner = score.winner();
        boolean blackWins = winner == GoPlayer.BLACK;
        boolean tied = winner == GoPlayer.NONE;

        if (lanMode == LAN_NONE) {
            // 单机/AI 模式
            if (tied) {
                myWin = false;
                resultMsg = String.format("平局！黑%.1f 白%.1f", blackScore, whiteScore);
            } else if (game.isAiMode()) {
                // AI 执白，玩家执黑
                myWin = blackWins;
                resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                        blackWins ? "玩家（黑）" : "AI（白）", blackScore, whiteScore);
            } else {
                // 双人模式：本地双人
                myWin = blackWins;
                resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                        blackWins ? "黑方" : "白方", blackScore, whiteScore);
            }
        } else {
            // LAN 模式：HOST=黑，CLIENT=白
            boolean iAmBlack = (lanMode == LAN_HOST);
            myWin = !tied && iAmBlack == blackWins;
            resultMsg = tied
                    ? String.format("平局！黑%.1f 白%.1f", blackScore, whiteScore)
                    : String.format("%s 胜！黑%.1f 白%.1f",
                    myWin ? "你" : "对方", blackScore, whiteScore);
        }
        state = State.GAME_OVER;
    }

    @Override protected void init() {
        super.init();
        int cx = width / 2, cy = height / 2;
        int px = cx - 160, py = cy - 130;
        int pathY = py + 45 + 45 + 45;
        katagoPathEditBox = new EditBox(font, px + 90, pathY - 4, 215, 22, Component.literal("KataGo 路径"));
        katagoPathEditBox.setValue(settingsKatagoPath == null ? "" : settingsKatagoPath);
        katagoPathEditBox.setMaxLength(512);
        katagoPathEditBox.setResponder(value -> settingsKatagoPath = value);
        katagoPathEditBox.setVisible(state == State.SETTINGS && "katago".equals(settingsEngine));
        addRenderableWidget(katagoPathEditBox);
    }

    @Override public void tick() {
        tickCount++;
        if (lanMode == LAN_HOST && tickCount - scoringLastBroadcastTick >= 40L) {
            if (state == State.SCORING) {
                sendScoringState();
                scoringLastBroadcastTick = tickCount;
            } else if (state == State.GAME_OVER && finalScoringSnapshot != null) {
                sendStateEnvelope(finalScoringSnapshot.encode("FINAL"));
                scoringLastBroadcastTick = tickCount;
            }
        }
        if (showExitConfirm) return;
        // AI 模式：AI 执白，黑棋下完后触发
        if (state == State.PLAYING && lanMode == LAN_NONE && game.isAiMode()
                && !aiFailed && !game.isGameOver() && game.getCurrentPlayer() == GoPlayer.WHITE) {
            // 后台线程计算 AI 走法，避免阻塞客户端线程（MCTS 搜索 1~12 秒）
            synchronized (aiWorkerLock) {
                if (!aiThinking && !aiComputed) {
                    aiThinking = true;
                    final int gen = aiGeneration;
                    Thread t = new Thread(() -> {
                        GoGame.AiMoveComputation computation = null;
                        try {
                            // 外部引擎创建和握手也在 worker 中，绝不阻塞客户端线程。
                            if (Thread.currentThread().isInterrupted()) return;
                            game.initAiIfAbsent();
                            if (Thread.currentThread().isInterrupted() || gen != aiGeneration) return;
                            computation = game.computeAiMoveComputation();
                        } catch (Exception e) {
                            LOGGER.warn("[KataGo] AI turn failed: {}", e.getMessage());
                        } finally {
                            synchronized (aiWorkerLock) {
                                if (gen == aiGeneration && !cleanedUp) {
                                    aiPendingComputation = computation;
                                    aiComputedGeneration = gen;
                                    aiThinking = false;
                                    aiComputed = true;
                                }
                            }
                        }
                    }, "go-ai-worker");
                    aiWorker = t;
                    t.setDaemon(true);
                    t.start();
                }
            }
        }
        // 客户端线程消费 AI 结果并落子
        GoGame.AiMoveComputation computation = null;
        boolean consumeAiMove = false;
        synchronized (aiWorkerLock) {
            if (aiComputed && aiComputedGeneration == aiGeneration) {
                aiComputed = false;
                aiComputedGeneration = -1;
                computation = aiPendingComputation;
                aiPendingComputation = null;
                consumeAiMove = true;
            } else if (aiComputed && aiComputedGeneration != aiGeneration) {
                aiComputed = false;
                aiComputedGeneration = -1;
                aiPendingComputation = null;
            }
        }
        if (consumeAiMove && computation != null && state == State.PLAYING && !game.isGameOver()
                && lanMode == LAN_NONE && game.isAiMode()
                && game.getCurrentPlayer() == GoPlayer.WHITE) {
            GoAI.MoveResult result = computation.result();
            if (!game.applyAiMoveComputation(computation)) return;
            if (result.type() == GoAI.MoveType.ERROR) {
                aiFailed = true;
                return;
            }
            if (game.isGameOver()) {
                if (result.type() == GoAI.MoveType.RESIGN) {
                    myWin = true;
                    resultMsg = "AI认输，你赢了！";
                    state = State.GAME_OVER;
                } else {
                    enterScoring();
                }
            }
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state == State.SETTINGS) {
                state = State.MENU;
                if (katagoPathEditBox != null) {
                    katagoPathEditBox.setFocused(false);
                    katagoPathEditBox.setVisible(false);
                }
                return true;
            }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (katagoPathEditBox != null && katagoPathEditBox.visible
                && katagoPathEditBox.isFocused() && super.keyPressed(key, scan, mods)) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_N) {
            // LAN：CLIENT 不能单方面重开；HOST 重开需广播 RESTART 同步对端，否则双方棋盘永久分叉
            if (lanMode == LAN_CLIENT) return true;
            resetGame();
            gameEpoch = UUID.randomUUID();
            networkPly = 0;
            state = State.PLAYING;
            if (lanMode == LAN_HOST) sendLanStart("RESTART");
            return true;
        }
        if (key == GLFW.GLFW_KEY_P && state == State.PLAYING && !game.isGameOver()) {
            if (!canLocalPlayerMove()) return true;
            if (lanMode == LAN_NONE) {
                game.pass();
                if (game.isGameOver()) enterScoring();
            } else if (myTurn) {
                game.pass();
                sendLanMove("PASS");
                networkPly++;
                myTurn = false;
                if (game.isGameOver() && lanMode != LAN_CLIENT) enterScoring();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_R && state == State.PLAYING && !game.isGameOver()) {
            // 认输：仅联机有效（单机没有结算对手）。对端在 onRemoteMove 收到 RESIGN: 判胜。
            if (lanMode != LAN_NONE) {
                sendLanMove("RESIGN");
                myWin = false;
                resultMsg = "你认输了";
                state = State.GAME_OVER;
            }
            return true;
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = Math.max(8, Math.min((width - 120) / BOARD_SIZE, (height - 80) / BOARD_SIZE));
        int boardPixels = BOARD_SIZE * cellSize;
        boardStartX = (width - boardPixels) / 2 - 40;
        boardStartY = (height - boardPixels) / 2;

        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case SETTINGS -> renderSettings(g, mx, my);
            case PLAYING -> renderPlaying(g, mx, my);
            case SCORING -> { renderPlaying(g, mx, my); renderScoring(g, mx, my); }
            case GAME_OVER -> { renderPlaying(g, mx, my); renderGameOver(g, mx, my); }
        }
        if (katagoPathEditBox != null && katagoPathEditBox.visible) {
            katagoPathEditBox.render(g, mx, my, pt);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x112200);
        GameRenderHelper.drawShadowedCenteredText(g, font, "围 棋", cx, cy - 60, 0xFFFFFF, 2);
        g.drawCenteredString(font, "Go Game", cx, cy - 42, 0x555555);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFD2B48C, 0xFF8B7355);
        g.drawCenteredString(font, "鼠标点击落子  N新游戏  P虚着  R认输", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "围地为王，黑白博弈的艺术", cx, cy + 5, 0xCCCCCC);
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 30, 120, 22, mx, my);
        // 设置按钮
        GameRenderHelper.drawPrimaryButton(g, font, "⚙ AI设置", cx - 60, cy + 58, 120, 22, mx, my);
    }

    private void renderSettings(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x112200);

        // 设置面板背景
        int pw = 320, ph = 260;
        int px = cx - pw / 2, py = cy - ph / 2;
        g.fill(px, py, px + pw, py + ph, 0xCC0A1520);
        g.fill(px - 2, py - 2, px + pw + 2, py + 2, 0xFF3A5A8A);
        g.fill(px - 2, py + ph, px + pw + 2, py + ph + 2, 0xFF3A5A8A);
        g.fill(px - 2, py, px + 2, py + ph, 0xFF3A5A8A);
        g.fill(px + pw, py, px + pw + 2, py + ph, 0xFF3A5A8A);

        // 标题
        GameRenderHelper.drawShadowedCenteredText(g, font, "AI 引擎设置", cx, py + 12, 0xFFFFFF, 1);

        // 引擎选择
        int optionY = py + 45;
        g.drawString(font, "引擎类型:", px + 15, optionY, 0xCCCCCC);

        // MCTS 按钮
        boolean mctsSelected = "mcts".equals(settingsEngine);
        int mctsColor = mctsSelected ? 0xFF44AA44 : 0xFF666666;
        int mctsBg = mctsSelected ? 0xFF1A3A1A : 0xFF2A2A2A;
        g.fill(px + 90, optionY - 4, px + 200, optionY + 18, mctsBg);
        g.drawString(font, "改进版MCTS", px + 95, optionY, mctsColor);

        // KataGo 按钮
        boolean kataSelected = "katago".equals(settingsEngine);
        int kataColor = kataSelected ? 0xFF44AA44 : 0xFF666666;
        int kataBg = kataSelected ? 0xFF1A3A1A : 0xFF2A2A2A;
        g.fill(px + 205, optionY - 4, px + 305, optionY + 18, kataBg);
        g.drawString(font, "KataGo", px + 215, optionY, kataColor);

        // 搜索时间
        int timeY = optionY + 45;
        g.drawString(font, "搜索时间: " + (settingsSearchTime / 1000.0) + "s", px + 15, timeY, 0xCCCCCC);

        // 时间滑块背景
        int sliderX = px + 90, sliderW = 200;
        g.fill(sliderX, timeY + 8, sliderX + sliderW, timeY + 18, 0xFF3A3A3A);
        // 滑块填充
        int searchRange = GameSettings.GO_SEARCH_TIME_MAX - GameSettings.GO_SEARCH_TIME_MIN;
        int fillW = (settingsSearchTime - GameSettings.GO_SEARCH_TIME_MIN) * sliderW / searchRange;
        g.fill(sliderX, timeY + 8, sliderX + fillW, timeY + 18, 0xFF4A6A8A);
        // 滑块位置
        int thumbX = sliderX + fillW - 5;
        g.fill(thumbX, timeY + 3, thumbX + 10, timeY + 23, 0xFF88AACC);

        // KataGo 路径（仅当选择 KataGo 时显示）
        int pathY = timeY + 45;
        if ("katago".equals(settingsEngine)) {
            g.drawString(font, "KataGo 路径:", px + 15, pathY, 0xCCCCCC);
            // 路径显示（截断过长路径）
            String displayPath = settingsKatagoPath.isEmpty() ? "(未配置)" :
                    (settingsKatagoPath.length() > 30 ?
                            "..." + settingsKatagoPath.substring(settingsKatagoPath.length() - 30) :
                            settingsKatagoPath);
            int pathColor = settingsKatagoPath.isEmpty() ? 0xFF666666 : 0xFFAAAAAA;
            g.fill(px + 90, pathY - 4, px + 305, pathY + 18, 0xFF2A2A2A);
            g.drawString(font, displayPath, px + 95, pathY, pathColor);
            pathY += 45;
        }

        // 当前状态
        int statusY = pathY + 10;
        String configuredEngine = GoAI.normalizeEngine(GameSettings.getString("go", "engine", "mcts"));
        String status = "配置引擎: " + ("mcts".equals(configuredEngine) ? "改进版MCTS" : "KataGo");
        g.drawString(font, status, px + 15, statusY, 0xFF888888);

        // 保存并返回按钮
        int btnY = py + ph - 50;
        GameRenderHelper.drawPrimaryButton(g, font, "保存并返回", cx - 60, btnY, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        int bw = BOARD_SIZE * cellSize;
        g.fill(boardStartX - 4, boardStartY - 4, boardStartX + bw + 4, boardStartY + bw + 4, 0xFF3A2A10);
        g.fill(boardStartX, boardStartY, boardStartX + bw, boardStartY + bw, 0xFFD2B48C);

        for (int i = 0; i < BOARD_SIZE; i++) {
            int x = boardStartX + i * cellSize + cellSize/2;
            int y = boardStartY + i * cellSize + cellSize/2;
            g.fill(x, boardStartY + cellSize/2, x+1, boardStartY + bw - cellSize/2, 0xFF000000);
            g.fill(boardStartX + cellSize/2, y, boardStartX + bw - cellSize/2, y+1, 0xFF000000);
        }

        int[] stars = {3, 9, 15};
        for (int sx : stars)
            for (int sy : stars)
                GameRenderHelper.drawCircle(g, boardStartX + sx * cellSize + cellSize/2,
                    boardStartY + sy * cellSize + cellSize/2, 2, 0xFF000000);

        int stoneR = cellSize / 2 - 1;
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++) {
                GoPlayer stone = game.getStone(x, y);
                if (stone != GoPlayer.NONE) {
                    int scx = boardStartX + x * cellSize + cellSize/2;
                    int scy = boardStartY + y * cellSize + cellSize/2;
                    int color = stone == GoPlayer.BLACK ? 0xFF111111 : 0xFFEEEEEE;
                    GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
                    if (stone == GoPlayer.WHITE) GameRenderHelper.drawCircle(g, scx-stoneR/3, scy-stoneR/3, stoneR/4, 0x44FFFFFF);
                    if (state == State.SCORING && markedDead.contains(key(x, y))) {
                        GameRenderHelper.drawCircle(g, scx, scy, Math.max(2, stoneR / 2), 0x99CC3333);
                    }
                }
            }

        // 悬停预览
        if (canLocalPlayerMove()) {
            int[] pos = getBoardPos(mx, my);
            if (pos != null && game.canPlaceStone(pos[0], pos[1])) {
                int scx = boardStartX + pos[0] * cellSize + cellSize/2;
                int scy = boardStartY + pos[1] * cellSize + cellSize/2;
                int color = game.getCurrentPlayer() == GoPlayer.BLACK ? 0x66111111 : 0x66EEEEEE;
                GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
            }
        }

        // 信息面板
        int infoX = boardStartX + bw + 15;
        int infoY = boardStartY;
        GameRenderHelper.drawPanel(g, infoX, infoY, 100, 140, GameRenderHelper.BG_PANEL, 0xFF334455);

        String curColor = game.getCurrentPlayer() == GoPlayer.BLACK ? "黑棋" : "白棋";
        g.drawString(font, "当前: " + curColor, infoX + 5, infoY + 8, 0xFFFFFF);

        String turnHint;
        if (lanMode == LAN_NONE) {
            turnHint = aiFailed ? "AI错误，请重开" : game.isAiMode()
                    ? (game.getCurrentPlayer()==GoPlayer.BLACK ? "你的回合" : "AI思考中") : "进行中";
        } else {
            turnHint = myTurn ? "你的回合" : "等待对方";
        }
        g.drawString(font, turnHint, infoX + 5, infoY + 24, 0xFFFFFF);
        g.drawString(font, "黑捕: " + game.getBlackCaptured(), infoX + 5, infoY + 44, 0xCCCCCC);
        g.drawString(font, "白捕: " + game.getWhiteCaptured(), infoX + 5, infoY + 58, 0xCCCCCC);

        String modeStr = lanMode != LAN_NONE ? (lanMode==LAN_HOST ? "局域网(黑)" : "局域网(白)")
                : (game.isAiMode() ? "AI模式" : "双人模式");
        g.drawString(font, modeStr, infoX + 5, infoY + 78, 0xCCCCCC);

        // AI 引擎状态
        String engineLabel = game.getRuntimeAiEngineLabel();
        if (engineLabel == null) {
            engineLabel = aiFailed ? "不可用" : aiThinking ? "启动中" : "未启动";
        }
        g.drawString(font, "AI: " + engineLabel, infoX + 5, infoY + 92, 0x666666);

        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "⚫⚪ 围棋", 8, 7, 0xFFFFFF);
        GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 菜单  N 新游戏  P 弃权");
    }

    private void renderScoring(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        g.fill(cx - 145, boardStartY + BOARD_SIZE * cellSize + 10, cx + 145,
                boardStartY + BOARD_SIZE * cellSize + 62, 0xCC0A1520);
        g.drawCenteredString(font, "结算：点击棋块标记死棋", cx, boardStartY + BOARD_SIZE * cellSize + 16, 0xFFFFFF);
        GameRenderHelper.drawPrimaryButton(g, font, "清除标记", cx - 140, boardStartY + BOARD_SIZE * cellSize + 32, 90, 22, mx, my);
        GameRenderHelper.drawPrimaryButton(g, font, "确认结果", cx + 50, boardStartY + BOARD_SIZE * cellSize + 32, 90, 22, mx, my);
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    private Set<Long> collectGroup(int sx, int sy) {
        GoPlayer color = game.getStone(sx, sy);
        if (color == GoPlayer.NONE) return Collections.emptySet();
        Set<Long> group = new HashSet<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        queue.add(new int[]{sx, sy}); group.add(key(sx, sy));
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!queue.isEmpty()) {
            int[] p = queue.remove();
            for (int[] d : dirs) {
                int x = p[0] + d[0], y = p[1] + d[1];
                if (x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE
                        && game.getStone(x, y) == color && group.add(key(x, y))) queue.add(new int[]{x, y});
            }
        }
        return group;
    }

    private void toggleMarkedGroupLocal(int x, int y) {
        Set<Long> group = collectGroup(x, y);
        if (group.isEmpty()) return;
        if (markedDead.contains(key(x, y))) markedDead.removeAll(group); else markedDead.addAll(group);
        scoringConfirmed = false;
        scoringRevision++;
        remoteScoringConfirmed = false;
        remoteConfirmedRevision = -1L;
        remoteConfirmedDigest = "";
    }

    private void toggleMarkedGroup(int x, int y) {
        if (lanMode == LAN_CLIENT) {
            if (scoringEpoch == null) return;
            sendStateEnvelope("GO_SCORE1|TOGGLE|" + scoringEpoch + "|" + scoringRevision + "|" + x + "," + y);
            return;
        }
        toggleMarkedGroupLocal(x, y);
        if (lanMode == LAN_HOST) sendScoringState();
    }

    private String encodeMarks() {
        return GoScoringProtocol.encodeMarks(markedDead);
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        g.flush(); // 防止先绘制的棋盘/HUD文字盖住遮罩背景（批量渲染text批次后置）
        g.fill(0, 0, width, height, 0xAA000000);

        int pw = 300, ph = 120;
        int px = cx - pw/2, py = cy - ph/2;
        g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, myWin ? 0xFF44FF44 : 0xFFFF4444);
        g.fill(px, py, px + pw, py + ph, 0xFF0A1A2A);

        String headline = myWin ? "🎉 你赢了！" : "游戏结束";
        g.drawCenteredString(font, headline, cx, py + 12, myWin ? 0x44FF44 : 0xFF4444);
        g.drawCenteredString(font, resultMsg, cx, py + 30, 0xFFFFFF);
        g.drawCenteredString(font, "N - 再来一局  |  ESC - 返回", cx, py + 50, 0xAAAAAA);
    }

    private int[] getBoardPos(int mx, int my) {
        int bx = Mth.floor((mx - boardStartX) / (float)cellSize);
        int by = Mth.floor((my - boardStartY) / (float)cellSize);
        if (bx >= 0 && bx < BOARD_SIZE && by >= 0 && by < BOARD_SIZE) return new int[]{bx, by};
        return null;
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }

        // ── 设置界面 ──
        if (state == State.SETTINGS) {
            if (katagoPathEditBox != null && katagoPathEditBox.visible
                    && katagoPathEditBox.mouseClicked(mx, my, btn)) {
                return true;
            }
            if (katagoPathEditBox != null) katagoPathEditBox.setFocused(false);
            int cx = width / 2, cy = height / 2;
            int pw = 320, ph = 260;
            int px = cx - pw / 2, py = cy - ph / 2;

            // 引擎选择
            int optionY = py + 45;
            if (mx >= px + 90 && mx <= px + 200 && my >= optionY - 4 && my <= optionY + 18) {
                settingsEngine = "mcts";
                if (katagoPathEditBox != null) katagoPathEditBox.setVisible(false);
                return true;
            }
            if (mx >= px + 205 && mx <= px + 305 && my >= optionY - 4 && my <= optionY + 18) {
                settingsEngine = "katago";
                if (katagoPathEditBox != null) katagoPathEditBox.setVisible(true);
                return true;
            }

            // 时间滑块
            int timeY = optionY + 45;
            int sliderX = px + 90, sliderW = 200;
            if (mx >= sliderX && mx <= sliderX + sliderW && my >= timeY + 3 && my <= timeY + 23) {
                double ratio = (mx - sliderX) / sliderW;
                int range = GameSettings.GO_SEARCH_TIME_MAX - GameSettings.GO_SEARCH_TIME_MIN;
                settingsSearchTime = GameSettings.GO_SEARCH_TIME_MIN + (int) Math.round(ratio * range);
                return true;
            }

            // 保存并返回按钮
            int btnY = py + ph - 50;
            if (mx >= cx - 60 && mx <= cx + 60 && my >= btnY && my <= btnY + 22) {
                // 保存设置到 GameSettings
                saveSettings();
                state = State.MENU;
                if (katagoPathEditBox != null) {
                    katagoPathEditBox.setFocused(false);
                    katagoPathEditBox.setVisible(false);
                }
                return true;
            }
            return true;
        }

        if (state == State.MENU) {
            int cx = width/2, cy = height/2;
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+30 && my <= cy+52) {
                resetGame();
                gameEpoch = UUID.randomUUID();
                networkPly = 0;
                state = State.PLAYING;
                if (lanMode == LAN_HOST) sendLanStart("START");
                return true;
            }
            // 设置按钮
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+58 && my <= cy+80) {
                state = State.SETTINGS;
                if (katagoPathEditBox != null) katagoPathEditBox.setVisible("katago".equals(settingsEngine));
                return true;
            }
        }
        if (state == State.SCORING && btn == 0) {
            int[] pos = getBoardPos((int) mx, (int) my);
            if (pos != null) { toggleMarkedGroup(pos[0], pos[1]); return true; }
            int cx = width / 2;
            int by = boardStartY + BOARD_SIZE * cellSize + 32;
            if (mx >= cx - 140 && mx <= cx - 50 && my >= by && my <= by + 22) {
                if (lanMode == LAN_CLIENT && scoringEpoch != null) {
                    sendStateEnvelope("GO_SCORE1|CLEAR|" + scoringEpoch + "|" + scoringRevision);
                } else {
                    markedDead.clear(); scoringConfirmed = false;
                    scoringRevision++;
                    remoteScoringConfirmed = false; remoteConfirmedRevision = -1L; remoteConfirmedDigest = "";
                    if (lanMode == LAN_HOST) sendScoringState();
                }
                return true;
            }
            if (mx >= cx + 50 && mx <= cx + 140 && my >= by && my <= by + 22) {
                scoringConfirmed = true;
                if (lanMode == LAN_NONE) finishGame(markedDead);
                else if (lanMode == LAN_HOST) {
                    if (remoteScoringConfirmed && remoteConfirmedRevision == scoringRevision
                            && remoteConfirmedDigest.equals(scoringDigest)) {
                        finishConfirmedScoring();
                    } else sendScoringState();
                } else if (scoringEpoch != null && roundKomiSet) {
                    sendStateEnvelope("GO_SCORE1|CONFIRM|" + scoringEpoch + "|" + scoringRevision + "|"
                            + encodeMarks() + "|" + scoringDigest + "|" + formatRoundKomi());
                }
                return true;
            }
            return true;
        }
        if (state == State.PLAYING && btn == 0 && !game.isGameOver()) {
            if (!canLocalPlayerMove()) return true;

            int[] pos = getBoardPos((int)mx, (int)my);
            if (pos != null && game.canPlaceStone(pos[0], pos[1])) {
                if (game.placeStone(pos[0], pos[1])) {
                    if (lanMode != LAN_NONE) {
                        sendLanMove(pos[0] + "," + pos[1]);
                        networkPly++;
                        myTurn = false;
                    }
                    if (game.isGameOver() && lanMode != LAN_CLIENT) enterScoring();
                    else if (lanMode == LAN_NONE && game.isAiMode()) {
                        // AI 在 tick 里触发
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    /**
     * 保存 AI 设置到 data/game_settings.json
     */
    private void saveSettings() {
        try {
            // 使用 GameSettings 的内部机制保存
            // 这里通过反射或直接修改 settings map 来保存
            // 由于 GameSettings 没有提供保存单个键的方法，我们使用 importFromFile 的变通方式
            java.nio.file.Path dataDir = com.wzz.game_console.util.ExternalFileManager.getDataDir();
            java.nio.file.Path settingsPath = dataDir.resolve("game_settings.json");

            // 读取现有设置
            java.util.Map<String, java.util.Map<String, Object>> allSettings = new java.util.HashMap<>();
            if (java.nio.file.Files.exists(settingsPath)) {
                // ★ Bug修复：原版 Files.readString 整文件读入,无大小限制,settings 文件
                //   被外部异常增长时瞬时占大块堆。改为 BufferedReader + try-with-resources,
                //   并通过 size 预检拒绝 > 1MB 的文件
                long size = java.nio.file.Files.size(settingsPath);
                if (size > 1024 * 1024) {
                    // 配置文件超 1MB,视为异常,直接跳过读取
                } else {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(
                            settingsPath, java.nio.charset.StandardCharsets.UTF_8)) {
                        char[] buf = new char[4096];
                        int n;
                        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                    }
                    String content = sb.toString();
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.Map<String, java.util.Map<String, Object>>>(){}.getType();
                    java.util.Map<String, java.util.Map<String, Object>> loaded = gson.fromJson(content, type);
                    if (loaded != null) allSettings = loaded;
                }
            }

            // 更新围棋设置
            java.util.Map<String, Object> goSettings = allSettings.getOrDefault("go", new java.util.HashMap<>());
            // ★ Bug修复：settingsEngine/KatagoPath 可能为 null(null 进 GSON 序列化为
            //   "engine": null,后续 getString 虽 instanceof 兜底不崩,但下游分支
            //   可能因 null 走错路径。改为只 put 非空字段
            if (settingsEngine != null && !settingsEngine.isBlank()) {
                goSettings.put("engine", settingsEngine);
            }
            goSettings.put("searchTime", settingsSearchTime);
            if (settingsKatagoPath != null && !settingsKatagoPath.isBlank()) {
                goSettings.put("katagoPath", settingsKatagoPath);
            } else {
                // 允许在 EditBox 中清空路径，并从配置中移除旧值。
                goSettings.remove("katagoPath");
            }
            allSettings.put("go", goSettings);

            // 保存 — 走 ExternalFileManager 原子写(tmp+atomic move),防 JVM 崩溃
            // 时截断 settings.json 导致玩家全部游戏配置丢失
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(allSettings);
            if (!com.wzz.game_console.util.ExternalFileManager.writeTextFile("data", "game_settings.json", json)) {
                throw new java.io.IOException("写入 game_settings.json 失败");
            }
            // 刷新 GameSettings 的内存快照，让下一局初始化 AI/Komi 时立即使用新配置。
            GameSettings.importFromFile(settingsPath);

            LOGGER.info("[围棋] AI 设置已保存: engine={}, searchTime={}ms", settingsEngine, settingsSearchTime);
        } catch (Exception e) {
            LOGGER.error("[围棋] 保存 AI 设置失败: {}", e.getMessage());
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}

