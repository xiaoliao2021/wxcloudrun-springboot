package com.tencent.wxcloudrun.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.wxcloudrun.game.model.Card;
import com.tencent.wxcloudrun.game.model.GameState;
import com.tencent.wxcloudrun.game.model.Play;
import com.tencent.wxcloudrun.game.model.PlayerState;
import com.tencent.wxcloudrun.game.model.RoomConfig;
import com.tencent.wxcloudrun.game.model.RoomState;
import com.tencent.wxcloudrun.game.model.RoundAction;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 大厅、房间、牌局的内存协调器。事件接口保持与需求文档一致。 */
public class GameManager {
    /** 补牌节奏：每张牌间隔（毫秒），模拟真人逐张补牌的手感，由后端控制。 */
    private static final long REFILL_STEP_MS = 330;
    /** 收轮后第一张牌前的停顿，让客户端先播完收轮/清桌。 */
    private static final long REFILL_FIRST_DELAY_MS = 450;
    /** 托管思考时间：轮到托管玩家后略等再自动动作，避免瞬间连打。 */
    private static final long TRUSTEE_THINK_MS = 900;

    private final ObjectMapper mapper;
    private final Map<String, WebSocketSession> sessions = new HashMap<>();
    private final Map<String, String> sessionPlayers = new HashMap<>();
    private final Map<String, RoomState> rooms = new LinkedHashMap<>();
    private final Map<String, GameState> games = new HashMap<>();
    private final ScheduledExecutorService refillScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "510k-refill");
        thread.setDaemon(true);
        return thread;
    });

    public GameManager(ObjectMapper mapper) { this.mapper = mapper; }

    public synchronized void onOpen(WebSocketSession session) { }

    public synchronized void onClose(WebSocketSession session) {
        String playerId = sessionPlayers.remove(session.getId());
        if (playerId == null) return;

        // 重连场景：该 playerId 已绑定到新连接，旧连接关闭不能把人踢下线
        WebSocketSession current = sessions.get(playerId);
        if (current != null && !current.getId().equals(session.getId())) return;

        sessions.remove(playerId);
        for (RoomState room : rooms.values()) {
            PlayerState player = room.findPlayer(playerId);
            if (player != null) {
                player.setOnline(false);
                if (room.getStatus() == RoomState.Status.PLAYING || room.getStatus() == RoomState.Status.REPLENISHING) {
                    player.setTrustee(true);
                    safeBroadcastRoom(room, "presence.playerOffline", map("playerId", playerId, "autoTrustee", true));
                    // 离线自动托管：如果正好轮到他，交给托管任务出牌，别把牌局卡住
                    GameState game = room.getGameId() == null ? null : games.get(room.getGameId());
                    if (game != null) scheduleTrusteeForTurn(game);
                } else {
                    safeBroadcastRoom(room, "room.playerUpdated", playerPayload(player));
                }
            }
        }
    }

    public synchronized void handle(WebSocketSession session, String playerId, String event, String requestId,
                                     String roomId, String gameId, JsonNode payload) {
        try {
            if (playerId == null) playerId = sessionPlayers.get(session.getId());
            switch (event) {
                case "auth.login": login(session, payload); break;
                case "client.heartbeat.ping": send(session, "server.heartbeat.pong", requestId, map("serverTime", System.currentTimeMillis())); break;
                case "lobby.getRoomList": roomList(session, requestId); break;
                case "lobby.createRoom": createRoom(session, requestId, payload); break;
                case "lobby.joinRoom": joinRoom(session, requestId, text(payload, "roomId", roomId), text(payload, "password", ""),
                        text(payload, "nickname", ""), text(payload, "avatarUrl", "")); break;
                case "lobby.leaveRoom": leaveRoom(playerId, text(payload, "roomId", roomId)); break;
                case "room.getInfo": roomInfo(session, text(payload, "roomId", roomId)); break;
                case "room.ready": ready(playerId, text(payload, "roomId", roomId), true); break;
                case "room.cancelReady": ready(playerId, text(payload, "roomId", roomId), false); break;
                case "room.updateConfig": updateConfig(playerId, text(payload, "roomId", roomId), payload == null ? null : payload.get("config")); break;
                case "room.startGame": startGame(playerId, text(payload, "roomId", roomId)); break;
                case "room.leave": leaveRoom(playerId, text(payload, "roomId", roomId)); break;
                case "game.getState": gameState(session, text(payload, "gameId", gameId)); break;
                case "game.playCards": play(playerId, text(payload, "gameId", gameId), payload == null ? null : payload.get("cardIds")); break;
                case "game.pass": pass(playerId, text(payload, "gameId", gameId)); break;
                case "game.hint": hint(session, playerId, text(payload, "gameId", gameId)); break;
                case "game.trustee.enable": trustee(playerId, text(payload, "gameId", gameId), true); break;
                case "game.trustee.disable": trustee(playerId, text(payload, "gameId", gameId), false); break;
                case "chat.send": chat(playerId, text(payload, "roomId", roomId), text(payload, "content", "")); break;
                default: error(session, requestId, "INVALID_EVENT", "未知事件");
            }
        } catch (RuleService.RuleException e) {
            try { error(session, requestId, e.code, e.code); } catch (IOException ignored) { }
        } catch (Exception e) {
            try { error(session, requestId, "SERVER_ERROR", e.getMessage() == null ? "服务器错误" : e.getMessage()); } catch (IOException ignored) { }
        }
    }

    private void login(WebSocketSession session, JsonNode payload) throws IOException {
        String playerId = text(payload, "playerId", null);
        if (playerId == null || playerId.trim().isEmpty()) playerId = "P-" + UUID.randomUUID().toString().substring(0, 8);
        String nickname = text(payload, "nickname", playerId);
        String avatarUrl = text(payload, "avatarUrl", "");
        sessions.put(playerId, session); sessionPlayers.put(session.getId(), playerId);
        session.getAttributes().put("playerId", playerId);
        // 昵称头像缓存到连接上：建房/加入房间时直接复用
        session.getAttributes().put("nickname", nickname);
        session.getAttributes().put("avatarUrl", avatarUrl);

        // 断线重连：把该玩家在房间里的在线状态恢复，否则会一直显示离线
        for (RoomState room : rooms.values()) {
            PlayerState player = room.findPlayer(playerId);
            if (player != null && !player.isOnline()) {
                player.setOnline(true);
                safeBroadcastRoom(room, "room.playerUpdated", playerPayload(player));
            }
        }

        // 重连：优先回到进行中的牌局，其次是仍在等待的房间
        String scene = "LOBBY";
        String activeRoomId = null;
        String activeGameId = null;
        for (RoomState room : rooms.values()) {
            if (room.findPlayer(playerId) == null) continue;
            if (room.getStatus() == RoomState.Status.PLAYING || room.getStatus() == RoomState.Status.REPLENISHING) {
                scene = "GAME";
                activeRoomId = room.getRoomId();
                activeGameId = room.getGameId();
                break;
            }
            if (room.getStatus() == RoomState.Status.WAITING && activeRoomId == null) {
                scene = "ROOM";
                activeRoomId = room.getRoomId();
            }
        }

        Map<String, Object> result = map("success", true, "playerId", playerId, "nickname", nickname,
                "avatarUrl", avatarUrl, "scene", scene, "roomId", activeRoomId, "gameId", activeGameId);
        send(session, "auth.loginResult", null, result);
    }

    private void roomList(WebSocketSession session, String requestId) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RoomState room : rooms.values()) {
            if (room.getStatus() == RoomState.Status.WAITING) {
                PlayerState owner = room.findPlayer(room.getOwnerId());
                list.add(map("roomId", room.getRoomId(),
                        "name", room.getName(),
                        "ownerId", room.getOwnerId(),
                        "ownerNickname", owner == null || owner.getNickname() == null ? "" : owner.getNickname(),
                        "ownerAvatarUrl", owner == null ? "" : str(owner.getAvatar()),
                        "playerCount", room.getPlayers().size(),
                        "maxPlayers", room.getConfig().getMaxPlayers(),
                        "hasPassword", room.hasPassword(),
                        "status", room.getStatus().name()));
            }
        }
        send(session, "lobby.roomList", requestId, map("rooms", list, "total", list.size()));
    }

    private void createRoom(WebSocketSession session, String requestId, JsonNode payload) throws IOException {
        String playerId = sessionPlayers.get(session.getId());
        if (playerId == null) { failCreate(session, requestId, "AUTH_FAILED", "请先登录"); return; }

        RoomConfig config = payload == null || payload.get("config") == null ? new RoomConfig() : mapper.convertValue(payload.get("config"), RoomConfig.class);
        if (config.getMaxPlayers() < 2 || config.getMaxPlayers() > 5 || config.getDeckCount() < 1 || config.getDeckCount() > 3 || !DeckFactory.validateCapacity(config)) {
            failCreate(session, requestId, "INVALID_CONFIG", "房间配置无效"); return;
        }

        String nickname = text(payload, "nickname", str(session.getAttributes().get("nickname")));
        if (nickname == null || nickname.trim().isEmpty()) nickname = playerId;
        String avatarUrl = text(payload, "avatarUrl", str(session.getAttributes().get("avatarUrl")));

        // 房间名：客户端没填就按房主昵称生成一个，仍要做全局唯一校验
        String rawName = text(payload, "name", "").trim();
        if (rawName.isEmpty()) rawName = defaultRoomName(nickname);
        if (rawName.length() > 16) { failCreate(session, requestId, "ROOM_NAME_TOO_LONG", "房间名最多 16 个字"); return; }
        if (roomNameExists(rawName)) { failCreate(session, requestId, "ROOM_NAME_DUPLICATED", "房间名已存在，换一个吧"); return; }

        // 密码可选；填了就必须是 6 位数字
        String password = text(payload, "password", "").trim();
        if (!password.isEmpty() && !password.matches("\\d{6}")) {
            failCreate(session, requestId, "INVALID_PASSWORD", "密码需为 6 位数字"); return;
        }

        String roomId = "R-" + UUID.randomUUID().toString().substring(0, 8);
        RoomState room = new RoomState(roomId, playerId, config);
        room.setName(rawName);
        room.setPassword(password);
        PlayerState owner = new PlayerState(playerId, nickname, 0);
        owner.setAvatar(avatarUrl);
        room.getPlayers().add(owner);
        rooms.put(roomId, room);
        send(session, "lobby.createRoomResult", requestId,
                map("success", true, "roomId", roomId, "name", rawName, "hasPassword", !password.isEmpty()));
        broadcastRoom(room, "room.info", roomPayload(room));
    }

    /** 创建失败也走 createRoomResult，客户端可以保留创建弹层让用户改名/改密码重试 */
    private void failCreate(WebSocketSession session, String requestId, String code, String message) throws IOException {
        send(session, "lobby.createRoomResult", requestId, map("success", false, "code", code, "message", message));
    }

    private boolean roomNameExists(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return false;
        for (RoomState room : rooms.values()) {
            String exist = room.getName();
            if (exist != null && exist.trim().toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return false;
    }

    /** 昵称 + 的牌桌，重名时补序号 */
    private String defaultRoomName(String nickname) {
        String base = nickname == null ? "" : nickname.trim();
        if (base.isEmpty()) base = "我的";
        if (base.length() > 10) base = base.substring(0, 10);
        base = base + "的牌桌";
        if (!roomNameExists(base)) return base;
        for (int i = 2; i <= 9; i++) {
            String candidate = base + i;
            if (!roomNameExists(candidate)) return candidate;
        }
        return base.substring(0, 10) + "-" + (rooms.size() + 1);
    }

    private void joinRoom(WebSocketSession session, String requestId, String roomId, String password,
                          String nicknameArg, String avatarArg) throws IOException {
        RoomState room = rooms.get(roomId);
        String playerId = sessionPlayers.get(session.getId());
        if (room == null) { failJoin(session, requestId, "ROOM_NOT_FOUND", "房间不存在"); return; }
        if (playerId == null) { failJoin(session, requestId, "AUTH_FAILED", "请先登录"); return; }
        if (room.hasPassword() && !room.getPassword().equals(password == null ? "" : password.trim())) {
            failJoin(session, requestId, "WRONG_PASSWORD", "密码错误"); return;
        }
        if (room.getStatus() != RoomState.Status.WAITING || room.getPlayers().size() >= room.getConfig().getMaxPlayers()) {
            failJoin(session, requestId, "ROOM_FULL", "房间不可加入"); return;
        }

        // 昵称头像：优先用本次请求带的（客户端有登录缓存），其次用连接上缓存的
        String nickname = nicknameArg == null || nicknameArg.trim().isEmpty()
                ? str(session.getAttributes().get("nickname")) : nicknameArg.trim();
        if (nickname.isEmpty()) nickname = playerId;
        String avatar = avatarArg == null || avatarArg.trim().isEmpty()
                ? str(session.getAttributes().get("avatarUrl")) : avatarArg.trim();

        PlayerState existing = room.findPlayer(playerId);
        if (existing == null) {
            PlayerState joined = new PlayerState(playerId, nickname, nextSeat(room));
            joined.setAvatar(avatar);
            room.getPlayers().add(joined);
        } else {
            // 重连 / 重复加入：把昵称头像补齐，并把在线状态恢复
            if (!nickname.isEmpty()) existing.setNickname(nickname);
            if (!avatar.isEmpty()) existing.setAvatar(avatar);
            existing.setOnline(true);
        }
        room.incrementVersion();
        send(session, "lobby.joinRoomResult", requestId,
                map("success", true, "roomId", roomId, "name", room.getName(), "seatNo", room.findPlayer(playerId).getSeatNo()));
        broadcastRoom(room, "room.info", roomPayload(room));
    }

    private void failJoin(WebSocketSession session, String requestId, String code, String message) throws IOException {
        send(session, "lobby.joinRoomResult", requestId, map("success", false, "code", code, "message", message));
    }

    private int nextSeat(RoomState room) {
        int seat = 0; while (true) { boolean used = false; for (PlayerState p : room.getPlayers()) if (p.getSeatNo() == seat) used = true; if (!used) return seat; seat++; }
    }

    private void ready(String playerId, String roomId, boolean value) throws IOException {
        RoomState room = requiredRoom(roomId); PlayerState player = requiredPlayer(room, playerId);
        if (room.getStatus() != RoomState.Status.WAITING) throw new RuleService.RuleException("NOT_WAITING_PHASE");
        player.setReady(value); room.incrementVersion(); broadcastRoom(room, "room.readyChanged", map("playerId", playerId, "ready", value));
    }

    private void updateConfig(String playerId, String roomId, JsonNode configNode) throws IOException {
        RoomState room = requiredRoom(roomId);
        if (!room.getOwnerId().equals(playerId)) throw new RuleService.RuleException("NOT_ROOM_OWNER");
        if (room.getStatus() != RoomState.Status.WAITING) throw new RuleService.RuleException("NOT_WAITING_PHASE");
        RoomConfig config = mapper.convertValue(configNode, RoomConfig.class);
        if (config.getMaxPlayers() < 2 || config.getMaxPlayers() > 5 || config.getDeckCount() < 1 || config.getDeckCount() > 3 || !DeckFactory.validateCapacity(config)) throw new RuleService.RuleException("INVALID_CONFIG");
        room.setConfig(config); room.incrementVersion(); broadcastRoom(room, "room.configUpdated", map("config", config, "stateVersion", room.getStateVersion()));
    }

    private void startGame(String playerId, String roomId) throws IOException {
        RoomState room = requiredRoom(roomId);
        if (!room.getOwnerId().equals(playerId)) throw new RuleService.RuleException("NOT_ROOM_OWNER");
        if (room.getPlayers().size() < 2) throw new RuleService.RuleException("NOT_ENOUGH_PLAYERS");
        for (PlayerState p : room.getPlayers()) if (!p.getPlayerId().equals(playerId) && !p.isReady()) throw new RuleService.RuleException("PLAYER_NOT_READY");
        String gameId = "G-" + UUID.randomUUID().toString().substring(0, 8);
        GameState game = new GameState(gameId, roomId, room.getConfig());
        for (PlayerState p : room.getPlayers()) { game.getPlayers().put(p.getPlayerId(), p); game.getHands().put(p.getPlayerId(), new ArrayList<>()); game.getCapturedScores().put(p.getPlayerId(), 0); game.getCapturedCards().put(p.getPlayerId(), new ArrayList<>()); p.setReady(false); }
        // 每局重新开始都要取消上一局留下的托管，避免一开局就自动打
        for (PlayerState p : room.getPlayers()) {
            if (p.isTrustee()) {
                p.setTrustee(false);
                broadcastRoom(room, "game.trusteeChanged", map("playerId", p.getPlayerId(), "trustee", false, "reason", "NEW_GAME"));
            }
        }
        List<Card> deck = DeckFactory.createDeck(room.getConfig().getDeckCount()); DeckFactory.shuffle(deck); DeckFactory.assertNoJoker(deck);
        game.setDrawPile(deck);
        for (int i = 0; i < 5; i++) for (PlayerState p : room.getPlayers()) game.getHands().get(p.getPlayerId()).add(game.getDrawPile().remove(game.getDrawPile().size() - 1));

        // 先手：沿用上一局赢家；有新人加入或第一局则由房主掷两个骰子决定
        List<PlayerState> participants = new ArrayList<>(room.getPlayers());
        participants.sort(Comparator.comparingInt(PlayerState::getSeatNo));
        Set<String> currentIds = new LinkedHashSet<>();
        for (PlayerState p : participants) currentIds.add(p.getPlayerId());

        boolean sameLineup = room.getLastWinnerId() != null
                && room.getLastPlayerIds() != null
                && room.getLastPlayerIds().size() == currentIds.size()
                && currentIds.containsAll(room.getLastPlayerIds());

        String firstId;
        String firstReason;
        int[] dice = null;
        if (sameLineup) {
            firstId = room.getLastWinnerId();
            firstReason = "LAST_WINNER";
        } else {
            int d1 = ThreadLocalRandom.current().nextInt(1, 7);
            int d2 = ThreadLocalRandom.current().nextInt(1, 7);
            dice = new int[]{d1, d2};
            int ownerIndex = 0;
            for (int i = 0; i < participants.size(); i += 1) {
                if (participants.get(i).getPlayerId().equals(room.getOwnerId())) ownerIndex = i;
            }
            // 从房主开始数骰子点数，数到谁谁先出
            firstId = participants.get((ownerIndex + d1 + d2 - 1) % participants.size()).getPlayerId();
            firstReason = "DICE";
        }

        game.setTurnPlayerId(firstId); game.setPhase(GameState.Phase.PLAYING);
        room.setLastPlayerIds(currentIds);
        games.put(gameId, game); room.setGameId(gameId); room.setStatus(RoomState.Status.PLAYING); room.incrementVersion();
        broadcastRoom(room, "room.gameStarted", map("roomId", roomId, "gameId", gameId, "firstPlayerId", firstId, "firstReason", firstReason, "dice", dice, "config", room.getConfig()));
        for (PlayerState p : room.getPlayers()) sendPlayer(p.getPlayerId(), "game.deal", null, dealPayload(game, p.getPlayerId()));
    }

    private void play(String playerId, String gameId, JsonNode cardIdsNode) throws IOException {
        GameState game = requiredGame(gameId);
        if (game.getPhase() != GameState.Phase.PLAYING) throw new RuleService.RuleException("NOT_PLAYING_PHASE");
        if (!playerId.equals(game.getTurnPlayerId())) throw new RuleService.RuleException("NOT_YOUR_TURN");
        List<String> ids = new ArrayList<>(); if (cardIdsNode != null) cardIdsNode.forEach(n -> ids.add(n.asText()));
        List<Card> hand = game.getHands().get(playerId); List<Card> cards = new ArrayList<>();
        for (String id : ids) { Card found = null; for (Card c : hand) if (c.getCardId().equals(id)) found = c; if (found == null) throw new RuleService.RuleException("CARDS_NOT_IN_HAND"); cards.add(found); }
        Play play = RuleService.parsePlay(cards, game.getConfig());
        if (!RuleService.canBeat(game.getCurrentPlay(), play)) throw new RuleService.RuleException("CANNOT_BEAT_CURRENT");
        hand.removeAll(cards); game.getRoundTableCards().addAll(cards); game.setRoundTableScore(game.getRoundTableScore() + play.getScore()); game.setCurrentPlay(play); game.setLastPlayerId(playerId); game.setPassCount(0);
        game.getRoundActions().add(RoundAction.play(playerId, cards, play.getType().name(), play.getScore()));
        game.nextVersion();
        broadcastGame(game, "game.playApplied", map("playerId", playerId, "cards", cards, "playType", play.getType().name(), "currentPlay", play.toJson(), "roundTable", roundTableJson(game), "handsCount", hand.size(), "roundTableScore", game.getRoundTableScore(), "capturedScores", game.getCapturedScores(), "deckRemain", game.getDrawPile().size(), "byTrustee", game.getPlayers().get(playerId).isTrustee(), "stateVersion", game.getStateVersion()));
        if (game.getDrawPile().isEmpty() && hand.isEmpty()) { settle(game, playerId); return; }
        game.setTurnPlayerId(nextPlayer(game, playerId));
        broadcastGame(game, "game.turnChanged", map("turnPlayerId", game.getTurnPlayerId(), "stateVersion", game.nextVersion()));
        scheduleTrusteeForTurn(game);
    }

    private void pass(String playerId, String gameId) throws IOException {
        GameState game = requiredGame(gameId);
        if (game.getPhase() != GameState.Phase.PLAYING) throw new RuleService.RuleException("NOT_PLAYING_PHASE");
        if (!playerId.equals(game.getTurnPlayerId())) throw new RuleService.RuleException("NOT_YOUR_TURN");
        if (game.getCurrentPlay() == null) throw new RuleService.RuleException("CANNOT_PASS");
        game.setPassCount(game.getPassCount() + 1); game.setTurnPlayerId(nextPlayer(game, playerId));
        game.getRoundActions().add(RoundAction.pass(playerId));
        game.nextVersion();
        broadcastGame(game, "game.passApplied", map("playerId", playerId, "nextTurnPlayerId", game.getTurnPlayerId(), "roundTable", roundTableJson(game), "passCount", game.getPassCount(), "byTrustee", game.getPlayers().get(playerId).isTrustee(), "stateVersion", game.getStateVersion()));
        int activeOthers = game.getPlayers().size() - 1;
                if (game.getPassCount() >= activeOthers) endRound(game);
        else {
            broadcastGame(game, "game.turnChanged", map("turnPlayerId", game.getTurnPlayerId(), "stateVersion", game.nextVersion()));
            scheduleTrusteeForTurn(game);
        }
    }

    /**
     * 提示：返回当前手牌里所有能压过桌面牌的组合，按“从小到大”排序。
     * 客户端每次点击提示取下一个；列表为空说明没有能压的牌。
     */
    private void hint(WebSocketSession session, String playerId, String gameId) throws IOException {
        GameState game = requiredGame(gameId);
        if (game.getPhase() != GameState.Phase.PLAYING) throw new RuleService.RuleException("NOT_PLAYING_PHASE");
        if (!playerId.equals(game.getTurnPlayerId())) throw new RuleService.RuleException("NOT_YOUR_TURN");
        List<Card> hand = game.getHands().get(playerId);
        List<Play> plays = RuleService.enumerateLegalPlays(hand, game.getCurrentPlay(), game.getConfig());
        plays.sort(Comparator.comparingInt(Play::getSize)
                .thenComparingInt(GameManager::hintTypeOrder)
                .thenComparingInt(GameManager::hintRankStrength)
                .thenComparingInt(p -> p.getMainMaxSuit().getOrder()));

        List<Object> hints = new ArrayList<>();
        for (Play play : plays) {
            List<String> ids = new ArrayList<>();
            for (Card card : play.getCards()) ids.add(card.getCardId());
            hints.add(map("cardIds", ids, "playType", play.getType().name(), "size", play.getSize(), "score", play.getScore()));
        }
        send(session, "game.hintResult", null, map("gameId", gameId, "hints", hints, "stateVersion", game.getStateVersion(), "turnPlayerId", game.getTurnPlayerId()));
    }

    /** 提示排序用：同张数内牌力从弱到强（顺子 < 同花 < 三张 < 三带二 < 四带一 < 同花顺 < 510K） */
    private static int hintTypeOrder(Play play) {
        switch (play.getType()) {
            case SINGLE: return 1;
            case PAIR: return 2;
            case STRAIGHT: return 3;
            case FLUSH: return 4;
            case TRIPLE: return 5;
            case TRIPLE_WITH_TWO: return 6;
            case FOUR_WITH_ONE: return 7;
            case STRAIGHT_FLUSH: return 8;
            case FIVE_TEN_KING: return 9;
            case SUITED_FIVE_TEN_KING: return 10;
            default: return 99;
        }
    }

    /** 一手牌的最大点数（rankOrder 越大牌越大），用于同类型内从小到大 */
    private static int hintRankStrength(Play play) {
        int max = 0;
        for (Card card : play.getCards()) max = Math.max(max, card.getRank().getRankOrder());
        return max;
    }

    private void endRound(GameState game) throws IOException {
        String winner = game.getLastPlayerId();
        int roundScore = game.getRoundTableScore();
        game.setRoundWinnerId(winner); game.getCapturedScores().put(winner, game.getCapturedScores().get(winner) + roundScore);
        // 本轮桌上的牌（含 5/10/K 分牌）归赢家，客户端用来展示"谁抢到了哪些分牌"
        game.getCapturedCards().computeIfAbsent(winner, k -> new ArrayList<>()).addAll(game.getRoundTableCards());
        game.setCurrentPlay(null); game.setPassCount(0); game.setRoundTableCards(new ArrayList<>()); game.setRoundTableScore(0);
        game.getRoundActions().clear();
        game.setTurnPlayerId(winner);

        boolean needRefill = !game.getDrawPile().isEmpty();
        game.setPhase(needRefill ? GameState.Phase.REPLENISHING : GameState.Phase.PLAYING);
        // 先让客户端清桌面、把回合交给赢家，补牌会按后端节奏随后逐张下发
        broadcastGame(game, "game.roundEnd", map("roundWinnerId", winner, "roundScore", roundScore, "capturedScores", game.getCapturedScores(), "capturedCards", game.getCapturedCards(), "nextFirstPlayerId", winner, "roundTable", roundTableJson(game), "deckRemain", game.getDrawPile().size(), "phase", game.getPhase().name(), "stateVersion", game.nextVersion()));

        if (needRefill) startReplenish(game, winner);
        else scheduleTrusteeForTurn(game);
    }

    /** 按"每人轮流补 1 张、直到 5 张"生成补牌顺序，牌堆不足时截断。 */
    private List<String> replenishOrder(GameState game, String startPlayer) {
        List<PlayerState> ordered = orderedPlayers(game);
        int start = 0;
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).getPlayerId().equals(startPlayer)) start = i;

        Map<String, Integer> need = new LinkedHashMap<>();
        int total = 0;
        for (PlayerState p : ordered) {
            List<Card> hand = game.getHands().get(p.getPlayerId());
            int missing = Math.max(0, 5 - (hand == null ? 0 : hand.size()));
            need.put(p.getPlayerId(), missing);
            total += missing;
        }

        int capacity = Math.min(total, game.getDrawPile().size());
        List<String> order = new ArrayList<>();
        while (order.size() < capacity) {
            boolean added = false;
            for (int offset = 0; offset < ordered.size() && order.size() < capacity; offset++) {
                String playerId = ordered.get((start + offset) % ordered.size()).getPlayerId();
                int remaining = need.get(playerId);
                if (remaining <= 0) continue;
                order.add(playerId);
                need.put(playerId, remaining - 1);
                added = true;
            }
            if (!added) break;
        }
        return order;
    }

    private void startReplenish(GameState game, String startPlayer) {
        List<String> order = replenishOrder(game, startPlayer);
        if (order.isEmpty()) {
            try { finishReplenish(game, startPlayer); } catch (IOException ignored) { }
            return;
        }
        scheduleRefillStep(game, order, 0, startPlayer);
    }

    /** 逐张补牌：每张间隔 REFILL_STEP_MS，第一张多停一拍，模拟真人发牌节奏。 */
    private void scheduleRefillStep(GameState game, List<String> order, int index, String startPlayer) {
        long delay = index == 0 ? REFILL_FIRST_DELAY_MS : REFILL_STEP_MS;
        refillScheduler.schedule(() -> {
            synchronized (GameManager.this) {
                try {
                    if (game.getPhase() != GameState.Phase.REPLENISHING) return;
                    int cursor = Math.min(index, order.size() - 1);
                    String playerId = order.get(cursor);
                    List<Card> hand = game.getHands().get(playerId);
                    if (hand != null && hand.size() < 5 && !game.getDrawPile().isEmpty()) {
                        Card card = game.getDrawPile().remove(game.getDrawPile().size() - 1);
                        hand.add(card);
                        try {
                            sendPlayer(playerId, "game.replenish", null, map(
                                    "gameId", game.getGameId(), "playerId", playerId,
                                    "cards", Collections.singletonList(card), "handCount", hand.size(),
                                    "deckRemain", game.getDrawPile().size(), "stateVersion", game.nextVersion()));
                        } catch (IOException ignored) { }
                        // 其他玩家只收到"某人补了一张"的通知（不含牌面），用于播放飞牌动效
                        for (PlayerState other : orderedPlayers(game)) {
                            if (other.getPlayerId().equals(playerId)) continue;
                            try {
                                sendPlayer(other.getPlayerId(), "game.replenishNotice", null, map(
                                        "gameId", game.getGameId(), "playerId", playerId,
                                        "handCount", hand.size(), "deckRemain", game.getDrawPile().size(),
                                        "stateVersion", game.getStateVersion()));
                            } catch (IOException ignored) { }
                        }
                    }
                    if (cursor + 1 < order.size()) scheduleRefillStep(game, order, cursor + 1, startPlayer);
                    else finishReplenish(game, startPlayer);
                } catch (Exception e) {
                    System.err.println("[510k] replenish step failed: " + e.getMessage());
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void finishReplenish(GameState game, String startPlayer) throws IOException {
        if (game.getPhase() != GameState.Phase.REPLENISHING) return;
        broadcastGame(game, "game.replenishDone", map("playersHandCount", handCounts(game), "deckRemain", game.getDrawPile().size(), "nextFirstPlayerId", startPlayer, "stateVersion", game.nextVersion()));
        if (game.getDrawPile().isEmpty()) {
            String winner = null;
            for (PlayerState p : orderedPlayers(game)) {
                List<Card> hand = game.getHands().get(p.getPlayerId());
                if (hand != null && hand.isEmpty()) { winner = p.getPlayerId(); break; }
            }
            if (winner != null) { settle(game, winner); return; }
        }
        game.setPhase(GameState.Phase.PLAYING);
        game.setTurnPlayerId(startPlayer);
        scheduleTrusteeForTurn(game);
    }

    private void settle(GameState game, String winnerId) throws IOException {
        if (game.getPhase() == GameState.Phase.FINISHED) return;
        if (game.getRoundTableScore() > 0) { game.getCapturedScores().put(winnerId, game.getCapturedScores().get(winnerId) + game.getRoundTableScore()); game.setRoundTableScore(0); }
        // 结算时最后一手也可能还摆在桌上，分牌展示要一起归赢家
        game.getCapturedCards().computeIfAbsent(winnerId, k -> new ArrayList<>()).addAll(game.getRoundTableCards());
        game.getRoundTableCards().clear();
        int handsScore = 0; for (Map.Entry<String, List<Card>> e : game.getHands().entrySet()) if (!e.getKey().equals(winnerId)) handsScore += RuleService.calculateCardScore(e.getValue());
        List<PlayerState> rank = new ArrayList<>(game.getPlayers().values());
        for (PlayerState p : rank) { int hs = p.getPlayerId().equals(winnerId) ? 0 : RuleService.calculateCardScore(game.getHands().get(p.getPlayerId())); p.setHandsScore(hs); p.setCapturedScore(game.getCapturedScores().get(p.getPlayerId())); p.setTotalScore(p.getPlayerId().equals(winnerId) ? p.getCapturedScore() + handsScore : p.getCapturedScore()); p.setSettled(true); }
        rank.sort(Comparator.comparingInt(PlayerState::getCapturedScore).reversed().thenComparing(Comparator.comparingInt(PlayerState::getHandsScore).reversed()).thenComparingInt(p -> game.getHands().get(p.getPlayerId()).size()).thenComparingInt(PlayerState::getSeatNo));
        List<String> ranking = new ArrayList<>(); for (int i = 0; i < rank.size(); i++) { rank.get(i).setRankNo(i + 1); ranking.add(rank.get(i).getPlayerId()); }
        Map<String, Integer> finalScores = new LinkedHashMap<>();
        for (PlayerState p : rank) finalScores.put(p.getPlayerId(), p.getTotalScore());
        // 表格的显示顺序就是排名：finalScores 按名次插入
        game.setPhase(GameState.Phase.FINISHED); RoomState room = rooms.get(game.getRoomId());
        if (room != null) {
            room.setStatus(RoomState.Status.SETTLING);
            // 记录本局赢家与参与名单：下一局同阵容由他先出，人员一变就重掷骰子
            room.setLastWinnerId(winnerId);
            Set<String> ids = new LinkedHashSet<>();
            for (PlayerState p : room.getPlayers()) ids.add(p.getPlayerId());
            room.setLastPlayerIds(ids);
        }
        broadcastGame(game, "game.settle", map("winnerId", winnerId, "reason", "DECK_EMPTY_AND_HAND_EMPTY", "deckRemain", 0, "capturedScores", game.getCapturedScores(), "finalScores", finalScores, "handsScore", handsScore, "winnerTotalScore", game.getPlayers().get(winnerId).getTotalScore(), "remainingHands", remainingHands(game, winnerId), "ranking", ranking, "scoreDetail", map("tableCaptured", game.getCapturedScores(), "handsToWinner", handsScore, "drawPile", 0)));
        if (room != null) { room.setStatus(RoomState.Status.WAITING); room.setGameId(null); for (PlayerState p : room.getPlayers()) { p.setSettled(false); p.setReady(false); } }
    }

    private Map<String, Object> remainingHands(GameState game, String winner) { Map<String, Object> result = new LinkedHashMap<>(); for (Map.Entry<String, List<Card>> e : game.getHands().entrySet()) if (!e.getKey().equals(winner)) result.put(e.getKey(), e.getValue()); return result; }
    private Map<String, Integer> handCounts(GameState game) { Map<String, Integer> result = new LinkedHashMap<>(); for (Map.Entry<String, List<Card>> e : game.getHands().entrySet()) result.put(e.getKey(), e.getValue().size()); return result; }

    private void trustee(String playerId, String gameId, boolean value) throws IOException {
        GameState game = requiredGame(gameId);
        PlayerState p = requiredPlayer(game, playerId);
        p.setTrustee(value);
        broadcastGame(game, "game.trusteeChanged", map("playerId", playerId, "trustee", value, "reason", "MANUAL"));
        // 开启托管且当前正好轮到他：马上排队自动出牌
        if (value) scheduleTrusteeForTurn(game);
    }

    /** 轮到托管玩家时排队一次自动动作；换人或取消托管后旧任务会自动失效 */
    private void scheduleTrusteeForTurn(GameState game) {
        if (game == null || game.getPhase() != GameState.Phase.PLAYING) return;
        PlayerState player = game.getPlayers().get(game.getTurnPlayerId());
        if (player == null || !player.isTrustee()) return;
        scheduleTrusteeStep(game, player.getPlayerId());
    }

    private void scheduleTrusteeStep(GameState game, String playerId) {
        long version = game.getStateVersion();
        refillScheduler.schedule(() -> {
            synchronized (GameManager.this) {
                try {
                    if (game.getPhase() != GameState.Phase.PLAYING) return;
                    if (!playerId.equals(game.getTurnPlayerId())) return;
                    PlayerState player = game.getPlayers().get(playerId);
                    if (player == null || !player.isTrustee()) return;
                    if (game.getStateVersion() != version) return;

                    Play choice = chooseTrusteePlay(game, playerId);
                    if (choice != null) {
                        ArrayNode ids = mapper.createArrayNode();
                        for (Card card : choice.getCards()) ids.add(card.getCardId());
                        broadcastGame(game, "game.trusteeAction", map("playerId", playerId, "action", "PLAY", "playType", choice.getType().name(), "cards", choice.getCards(), "stateVersion", game.getStateVersion()));
                        play(playerId, game.getGameId(), ids);
                    } else if (game.getCurrentPlay() != null) {
                        broadcastGame(game, "game.trusteeAction", map("playerId", playerId, "action", "PASS", "stateVersion", game.getStateVersion()));
                        pass(playerId, game.getGameId());
                    }
                } catch (Exception e) {
                    System.err.println("[510k] trustee step failed: " + e.getMessage());
                }
            }
        }, TRUSTEE_THINK_MS, TimeUnit.MILLISECONDS);
    }

    /** 托管策略：能压就用张数最少、牌力最弱的一手；压不了返回 null（由调用方过牌） */
    private Play chooseTrusteePlay(GameState game, String playerId) {
        List<Card> hand = game.getHands().get(playerId);
        List<Play> plays = RuleService.enumerateLegalPlays(hand, game.getCurrentPlay(), game.getConfig());
        if (plays.isEmpty()) return null;
        plays.sort(Comparator.comparingInt(Play::getSize)
                .thenComparingInt(GameManager::hintTypeOrder)
                .thenComparingInt(GameManager::hintRankStrength));
        return plays.get(0);
    }
    private void chat(String playerId, String roomId, String content) throws IOException { if (rooms.containsKey(roomId)) broadcastRoom(rooms.get(roomId), "chat.message", map("playerId", playerId, "content", content, "timestamp", System.currentTimeMillis())); }

    private void leaveRoom(String playerId, String roomId) throws IOException { RoomState room = rooms.get(roomId); if (room == null) return; PlayerState p = room.findPlayer(playerId); if (p == null) return; if (room.getStatus() != RoomState.Status.WAITING) { p.setOnline(false); p.setTrustee(true); broadcastRoom(room, "presence.playerOffline", map("playerId", playerId, "autoTrustee", true)); return; } room.getPlayers().remove(p); if (room.getPlayers().isEmpty()) { rooms.remove(roomId); return; } if (room.getOwnerId().equals(playerId)) room.setOwnerId(room.getPlayers().get(0).getPlayerId()); room.incrementVersion(); broadcastRoom(room, "room.info", roomPayload(room)); }

    private void roomInfo(WebSocketSession session, String roomId) throws IOException { RoomState room = rooms.get(roomId); if (room == null) { error(session, null, "ROOM_NOT_FOUND", "房间不存在"); return; } send(session, "room.info", null, roomPayload(room)); }
    private void gameState(WebSocketSession session, String gameId) throws IOException { GameState game = requiredGame(gameId); String playerId = sessionPlayers.get(session.getId()); send(session, "game.stateSync", null, statePayload(game, playerId)); }

    private Map<String, Object> roomPayload(RoomState room) { List<Object> players = new ArrayList<>(); for (PlayerState p : room.getPlayers()) players.add(playerPayload(p)); return map("roomId", room.getRoomId(), "name", room.getName(), "hasPassword", room.hasPassword(), "ownerId", room.getOwnerId(), "config", room.getConfig(), "players", players, "status", room.getStatus().name(), "gameId", room.getGameId(), "stateVersion", room.getStateVersion()); }
    private Map<String, Object> playerPayload(PlayerState p) { return map("playerId", p.getPlayerId(), "nickname", p.getNickname(), "avatarUrl", str(p.getAvatar()), "seatNo", p.getSeatNo(), "ready", p.isReady(), "online", p.isOnline(), "trustee", p.isTrustee()); }
    private Map<String, Object> dealPayload(GameState game, String playerId) { return map("gameId", game.getGameId(), "myCards", game.getHands().get(playerId), "handCount", game.getHands().get(playerId).size(), "playersHandCount", handCounts(game), "turnPlayerId", game.getTurnPlayerId(), "deckRemain", game.getDrawPile().size(), "firstPlayerId", game.getTurnPlayerId(), "stateVersion", game.getStateVersion()); }
    private Map<String, Object> statePayload(GameState game, String playerId) { Map<String, Object> p = new LinkedHashMap<>(); p.put("gameId", game.getGameId()); p.put("gamePhase", game.getPhase().name()); p.put("stateVersion", game.getStateVersion()); p.put("turnPlayerId", game.getTurnPlayerId()); p.put("currentPlay", game.getCurrentPlay() == null ? null : game.getCurrentPlay().toJson()); p.put("roundTable", roundTableJson(game)); p.put("capturedCards", game.getCapturedCards()); p.put("players", handCounts(game)); p.put("myCards", game.getHands().get(playerId)); p.put("deckRemain", game.getDrawPile().size()); p.put("roundTableScore", game.getRoundTableScore()); p.put("capturedScores", game.getCapturedScores()); p.put("passCount", game.getPassCount()); p.put("roundWinnerId", game.getRoundWinnerId()); p.put("deadline", null); return p; }

    /** 本轮桌面动作（出牌/过牌）按发生顺序序列化给客户端铺开 */
    private List<ObjectNode> roundTableJson(GameState game) {
        List<ObjectNode> list = new ArrayList<>();
        for (RoundAction action : game.getRoundActions()) list.add(action.toJson());
        return list;
    }

    private List<PlayerState> orderedPlayers(GameState game) { List<PlayerState> result = new ArrayList<>(game.getPlayers().values()); result.sort(Comparator.comparingInt(PlayerState::getSeatNo)); return result; }
    private String nextPlayer(GameState game, String current) { List<PlayerState> ps = orderedPlayers(game); for (int i = 0; i < ps.size(); i++) if (ps.get(i).getPlayerId().equals(current)) return ps.get((i + 1) % ps.size()).getPlayerId(); return current; }
    private RoomState requiredRoom(String id) { RoomState r = rooms.get(id); if (r == null) throw new RuleService.RuleException("ROOM_NOT_FOUND"); return r; }
    private GameState requiredGame(String id) { GameState g = games.get(id); if (g == null) throw new RuleService.RuleException("GAME_NOT_STARTED"); return g; }
    private PlayerState requiredPlayer(RoomState room, String id) { PlayerState p = room.findPlayer(id); if (p == null) throw new RuleService.RuleException("PLAYER_NOT_IN_ROOM"); return p; }
    private PlayerState requiredPlayer(GameState game, String id) { PlayerState p = game.getPlayers().get(id); if (p == null) throw new RuleService.RuleException("PLAYER_NOT_IN_GAME"); return p; }
    private void safeBroadcastRoom(RoomState room, String event, Object payload) {
        try { broadcastRoom(room, event, payload); } catch (IOException ignored) { }
    }

    private void broadcastRoom(RoomState room, String event, Object payload) throws IOException { for (PlayerState p : room.getPlayers()) sendPlayer(p.getPlayerId(), event, null, payload); }
    private void broadcastGame(GameState game, String event, Object payload) throws IOException { broadcastRoom(rooms.get(game.getRoomId()), event, payload); }
    private void sendPlayer(String playerId, String event, String requestId, Object payload) throws IOException { WebSocketSession session = sessions.get(playerId); if (session != null && session.isOpen()) send(session, event, requestId, payload); }
    private void send(WebSocketSession session, String event, String requestId, Object payload) throws IOException { Map<String, Object> out = new LinkedHashMap<>(); out.put("event", event); out.put("eventId", UUID.randomUUID().toString()); if (requestId != null) out.put("requestId", requestId); out.put("payload", payload); session.sendMessage(new TextMessage(mapper.writeValueAsString(out))); }
    private void error(WebSocketSession session, String requestId, String code, String message) throws IOException { if (session != null && session.isOpen()) send(session, "system.error", requestId, map("requestId", requestId, "code", code, "message", message)); }
    private static String text(JsonNode node, String name, String fallback) { if (node == null || node.get(name) == null || node.get(name).isNull()) return fallback; return node.get(name).asText(); }
    private static String str(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Map<String, Object> map(Object... values) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < values.length; i += 2) m.put(String.valueOf(values[i]), values[i + 1]); return m; }
}
