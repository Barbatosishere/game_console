package com.wzz.game_console.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

final class MultiplayerGameDataEnvelope {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");
    private static final String PREFIX = "MGP1|";
    private static final int MAX_DATA_BYTES = 32767;

    record Value(UUID sessionId, long sequence, String body, boolean legacy) {
        Value {
            body = body == null ? "" : body;
        }
    }

    private MultiplayerGameDataEnvelope() {}

    static Value of(UUID sessionId, long sequence, String body) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId cannot be null");
        if (sequence < 0) throw new IllegalArgumentException("sequence cannot be negative");
        return new Value(sessionId, sequence, body, false);
    }

    static Value parse(String data) {
        if (data == null || !data.startsWith(PREFIX)) return legacy(data);
        try {
            String[] fields = data.split("\\|", -1);
            if (fields.length != 4 || !"MGP1".equals(fields[0])) return null;
            UUID session = UUID.fromString(fields[1]);
            long sequence = Long.parseLong(fields[2]);
            if (sequence < 0 || fields[3].length() > MAX_DATA_BYTES * 2) return null;
            String body = new String(Base64.getUrlDecoder().decode(fields[3]), StandardCharsets.UTF_8);
            if (utf8Length(body) > MAX_DATA_BYTES) return null;
            return new Value(session, sequence, body, false);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String encode(Value value) {
        if (value.legacy() || value.sessionId() == null || value.sequence() < 0) return value.body();
        String head = PREFIX + value.sessionId() + "|" + value.sequence() + "|";
        String body = value.body();
        int bodyBytes = utf8Length(body);
        // 正文预算 =（上限 - 头部长度）的 base64 换算：floor(chars/4)*3 保证 no-padding
        // 编码后恒不超限。超限时按码点边界截断而非抛异常——与 wire 层 safeUtf 的
        // “截断优先于断连”策略一致；接收端对残缺 body 会在游戏逻辑层安全丢弃。
        int allowedBody = Math.max(0, (MAX_DATA_BYTES - utf8Length(head)) / 4 * 3);
        if (bodyBytes > allowedBody) {
            LOGGER.warn("[游戏机联机] MGP1 正文 {} 字节超过上限 {}，已按码点边界截断", bodyBytes, allowedBody);
            body = truncateUtf8(body, allowedBody);
        }
        String encodedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(
                body.getBytes(StandardCharsets.UTF_8));
        String envelope = head + encodedBody;
        if (utf8Length(envelope) > MAX_DATA_BYTES) {
            throw new IllegalStateException("MGP1 envelope exceeds " + MAX_DATA_BYTES + " UTF-8 bytes");
        }
        return envelope;
    }

    /** 按码点边界截断字符串到 maxBytes 个 UTF-8 字节以内（与 MultiplayerGamePacket.safeUtf 同策略）。 */
    private static String truncateUtf8(String s, int maxBytes) {
        if (utf8Length(s) <= maxBytes) return s;
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cb = cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            if (bytes + cb > maxBytes) break;
            bytes += cb;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    private static Value legacy(String data) {
        return new Value(null, -1L, data == null ? "" : data, true);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
