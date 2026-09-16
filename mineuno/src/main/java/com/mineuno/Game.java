package com.mineuno;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 纯规则层：不依赖任何 Bukkit 类型，所有表现通过 Events 回调。 */
public class Game {

    public enum Phase { WAITING, DEALING, PLAYING, POST_DRAW, PICK_COLOR, CHALLENGE, ROUND_END, ENDED }

    public enum Mode {
        QUICK("Quick", "第一个出完手牌的人获胜"),
        CLASSIC("Classic 500", "官方计分，累计 500 分获胜");

        public final String label;
        public final String desc;

        Mode(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }
    }

    public interface Events {
        default void onDeal() {}
        default void onPlay(UUID player, Card card) {}
        default void onDraw(UUID player, int count, Card card) {}
        default void onSkip(UUID target) {}
        default void onReverse() {}
        default void onColor(Card.Color color) {}
        default void onTurn(UUID player) {}
        default void onUno(UUID player, boolean safe) {}
        default void onCaught(UUID player, int count) {}
        default void onChallenge(UUID offender, UUID challenger) {}
        default void onChallengeResult(boolean success, UUID offender, UUID challenger) {}
        default void onRoundEnd(UUID winner) {}
        default void onMatchEnd(UUID winner) {}
    }

    public final UUID id = UUID.randomUUID();
    public final String name;
    public final Mode mode;
    public final boolean wild4Challenge;
    public final int startingCards;
    public final int maxPlayers;
    public Events events;

    public Phase phase = Phase.WAITING;
    public final LinkedHashMap<UUID, List<Card>> hands = new LinkedHashMap<>();
    public final Set<UUID> ready = new HashSet<>();
    public final Map<UUID, Integer> scores = new HashMap<>();
    public final Set<UUID> away = new HashSet<>();
    public UUID host;

    public final List<Card> draw = new ArrayList<>();
    public final List<Card> discard = new ArrayList<>();
    public Card.Color activeColor = Card.Color.RED;
    public int turn;
    public int dir = 1;
    public int round;
    public long deadline;

    public Card drawn;
    public UUID w4offender, w4challenger;
    public UUID vulnerable;
    private boolean w4hadColor;
    private boolean wildWasLast;
    private UUID armed;
    private int nextId = 1;
    private final Random rnd = new Random();

    public Game(String name, Mode mode, boolean wild4Challenge, int startingCards, int maxPlayers) {
        this.name = name;
        this.mode = mode;
        this.wild4Challenge = wild4Challenge;
        this.startingCards = startingCards;
        this.maxPlayers = maxPlayers;
    }

    // ---------- 基础查询 ----------

    public List<UUID> order() {
        return new ArrayList<>(hands.keySet());
    }

    public int count() {
        return hands.size();
    }

    public int rel(int index) {
        return Math.floorMod(index, count());
    }

    public UUID current() {
        List<UUID> o = order();
        return o.isEmpty() ? null : o.get(rel(turn));
    }

    public UUID next(int steps) {
        List<UUID> o = order();
        return o.isEmpty() ? null : o.get(rel(turn + dir * steps));
    }

    public Card top() {
        return discard.isEmpty() ? null : discard.get(discard.size() - 1);
    }

    public List<Card> hand(UUID player) {
        return hands.getOrDefault(player, List.of());
    }

    public boolean hasColor(UUID player, Card.Color color) {
        return hand(player).stream().anyMatch(c -> c.color() == color);
    }

    public boolean canPlay(UUID player, Card card) {
        if (!player.equals(current())) return false;
        if (phase == Phase.POST_DRAW) return drawn != null && drawn.id() == card.id();
        if (phase != Phase.PLAYING) return false;
        return playable(card);
    }

    public boolean playable(Card card) {
        if (card.wild()) return true;
        if (card.color() == activeColor) return true;
        Card top = top();
        if (top == null) return true;
        if (card.type() == Card.Type.NUMBER && top.type() == Card.Type.NUMBER) return card.number() == top.number();
        return card.type() == top.type();
    }

    public Card.Color bestColor(UUID player) {
        Map<Card.Color, Integer> counts = new HashMap<>();
        for (Card c : hand(player)) if (!c.wild()) counts.merge(c.color(), 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Card.Color.RED);
    }

    // ---------- 大厅 ----------

    public boolean join(UUID player) {
        if (phase != Phase.WAITING || hands.size() >= maxPlayers || hands.containsKey(player)) return false;
        hands.put(player, new ArrayList<>());
        scores.put(player, 0);
        ready.add(player);
        if (host == null) host = player;
        return true;
    }

    public void leave(UUID player) {
        hands.remove(player);
        ready.remove(player);
        scores.remove(player);
        away.remove(player);
        if (player.equals(host)) host = hands.keySet().stream().findFirst().orElse(null);
    }

    public boolean allReady() {
        return !hands.isEmpty() && hands.keySet().stream().allMatch(ready::contains);
    }

    // ---------- 开局 ----------

    public void startRound() {
        round++;
        draw.clear();
        discard.clear();
        drawn = null;
        armed = null;
        vulnerable = null;
        w4offender = w4challenger = null;
        for (List<Card> h : hands.values()) h.clear();

        for (Card.Color color : Card.COLORS) {
            draw.add(newCard(color, Card.Type.NUMBER, 0));
            for (int n = 1; n <= 9; n++) {
                draw.add(newCard(color, Card.Type.NUMBER, n));
                draw.add(newCard(color, Card.Type.NUMBER, n));
            }
            for (Card.Type type : List.of(Card.Type.SKIP, Card.Type.REVERSE, Card.Type.DRAW2)) {
                draw.add(newCard(color, type, -1));
                draw.add(newCard(color, type, -1));
            }
        }
        for (int i = 0; i < 4; i++) {
            draw.add(newCard(Card.Color.WILD, Card.Type.WILD, -1));
            draw.add(newCard(Card.Color.WILD, Card.Type.WILD4, -1));
        }
        Collections.shuffle(draw, rnd);

        while (true) {
            Card first = draw.remove(draw.size() - 1);
            if (first.type() == Card.Type.NUMBER) {
                discard.add(first);
                break;
            }
            draw.add(0, first);
        }
        activeColor = top().color();

        for (int i = 0; i < startingCards; i++) {
            for (List<Card> h : hands.values()) h.add(pop());
        }
        turn = rnd.nextInt(count());
        dir = 1;
        phase = Phase.DEALING;
        events.onDeal();
    }

    public void beginPlay() {
        phase = Phase.PLAYING;
        events.onTurn(current());
    }

    private Card newCard(Card.Color color, Card.Type type, int number) {
        return new Card(nextId++, color, type, number);
    }

    private Card pop() {
        if (draw.isEmpty()) {
            Card top = discard.remove(discard.size() - 1);
            draw.addAll(discard);
            discard.clear();
            discard.add(top);
            Collections.shuffle(draw, rnd);
        }
        return draw.isEmpty() ? null : draw.remove(draw.size() - 1);
    }

    private void give(UUID player, int amount) {
        List<Card> h = hands.get(player);
        if (h == null) return;
        Card last = null;
        int given = 0;
        for (int i = 0; i < amount; i++) {
            Card card = pop();
            if (card == null) break;
            h.add(card);
            last = card;
            given++;
        }
        if (given > 0) events.onDraw(player, given, last);
    }

    // ---------- 行动 ----------

    public boolean play(UUID player, int cardId) {
        if (phase != Phase.PLAYING && phase != Phase.POST_DRAW) return false;
        if (!player.equals(current())) return false;
        List<Card> hand = hands.get(player);
        if (hand == null) return false;
        Card card = hand.stream().filter(c -> c.id() == cardId).findFirst().orElse(null);
        if (card == null) return false;
        if (phase == Phase.POST_DRAW && (drawn == null || drawn.id() != cardId)) return false;
        if (phase == Phase.PLAYING && !playable(card)) return false;

        closeUnoWindow();
        hand.remove(card);
        discard.add(card);
        drawn = null;
        if (card.color() != Card.Color.WILD) activeColor = card.color();
        if (card.type() == Card.Type.WILD4) {
            w4offender = player;
            w4hadColor = hasColor(player, activeColor);
        }
        events.onPlay(player, card);

        if (hand.size() == 1) {
            if (player.equals(armed)) {
                events.onUno(player, true);
            } else {
                vulnerable = player;
                events.onUno(player, false);
            }
        }
        armed = null;

        wildWasLast = hand.isEmpty();
        switch (card.type()) {
            case NUMBER -> advance(1);
            case SKIP -> {
                events.onSkip(next(1));
                advance(2);
            }
            case REVERSE -> {
                dir = -dir;
                events.onReverse();
                advance(1);
            }
            case DRAW2 -> {
                give(next(1), 2);
                events.onSkip(next(1));
                advance(2);
            }
            case WILD, WILD4 -> {
                phase = Phase.PICK_COLOR;
                return true;
            }
        }
        if (wildWasLast) {
            endRound(player);
            return true;
        }
        events.onTurn(current());
        return true;
    }

    public boolean drawCard(UUID player) {
        if (phase != Phase.PLAYING || !player.equals(current())) return false;
        Card card = pop();
        if (card == null) return false;
        closeUnoWindow();
        hands.get(player).add(card);
        armed = null;
        events.onDraw(player, 1, card);
        if (playable(card)) {
            drawn = card;
            phase = Phase.POST_DRAW;
        } else {
            drawn = null;
            advance(1);
            events.onTurn(current());
        }
        return true;
    }

    public boolean pass(UUID player) {
        if (phase != Phase.POST_DRAW || !player.equals(current())) return false;
        drawn = null;
        advance(1);
        events.onTurn(current());
        return true;
    }

    public boolean chooseColor(UUID player, Card.Color color) {
        if (phase != Phase.PICK_COLOR || !player.equals(current()) || color == Card.Color.WILD) return false;
        activeColor = color;
        events.onColor(color);
        Card played = top();
        if (played.type() == Card.Type.WILD4 && wild4Challenge && !wildWasLast) {
            w4challenger = next(1);
            phase = Phase.CHALLENGE;
            events.onChallenge(w4offender, w4challenger);
            return true;
        }
        finishWild(played, player);
        return true;
    }

    private void finishWild(Card played, UUID player) {
        if (played.type() == Card.Type.WILD4) {
            give(next(1), 4);
            events.onSkip(next(1));
            if (wildWasLast) {
                endRound(player);
                return;
            }
            advance(2);
        } else {
            if (wildWasLast) {
                endRound(player);
                return;
            }
            advance(1);
        }
        events.onTurn(current());
    }

    public boolean acceptWild4(UUID player) {
        if (phase != Phase.CHALLENGE || !player.equals(w4challenger)) return false;
        UUID offender = w4offender;
        w4offender = w4challenger = null;
        phase = Phase.PLAYING;
        give(player, 4);
        events.onSkip(player);
        advance(2);
        events.onTurn(current());
        if (hands.getOrDefault(offender, List.of()).isEmpty() && offender != null) endRound(offender);
        return true;
    }

    public boolean challenge(UUID player) {
        if (phase != Phase.CHALLENGE || !player.equals(w4challenger)) return false;
        boolean success = w4hadColor;
        UUID offender = w4offender;
        UUID challenger = w4challenger;
        w4offender = w4challenger = null;
        phase = Phase.PLAYING;
        events.onChallengeResult(success, offender, challenger);
        if (success) {
            give(offender, 4);
            advance(1);
        } else {
            give(challenger, 6);
            events.onSkip(challenger);
            advance(2);
        }
        events.onTurn(current());
        return true;
    }

    /** 超时或断线时强制推进：摸 1 结束回合 / 自动选色 / 自动接受 +4。 */
    public void forceResolve() {
        UUID cur = current();
        if (cur == null || phase == Phase.ENDED || phase == Phase.WAITING) return;
        if (phase == Phase.PLAYING) {
            drawCard(cur);
            if (phase == Phase.POST_DRAW) pass(cur);
        } else if (phase == Phase.POST_DRAW) {
            pass(cur);
        } else if (phase == Phase.PICK_COLOR) {
            chooseColor(cur, bestColor(cur));
        } else if (phase == Phase.CHALLENGE) {
            acceptWild4(w4challenger != null ? w4challenger : cur);
        }
    }

    // ---------- UNO ----------

    public boolean callUno(UUID player) {
        List<Card> hand = hands.get(player);
        if (hand == null || phase == Phase.WAITING || hand.isEmpty()) return false;
        if (hand.size() == 1 && player.equals(vulnerable)) {
            vulnerable = null;
            events.onUno(player, true);
            return true;
        }
        if (hand.size() == 2) {
            armed = player;
            return true;
        }
        return false;
    }

    public boolean catchUno(UUID actor, UUID target) {
        if (vulnerable == null || !vulnerable.equals(target)) return false;
        if (!hands.containsKey(actor) || actor.equals(target)) return false;
        vulnerable = null;
        give(target, 2);
        events.onCaught(target, 2);
        return true;
    }

    private void closeUnoWindow() {
        vulnerable = null;
    }

    // ---------- 结束 ----------

    private void advance(int steps) {
        turn = rel(turn + dir * steps);
        deadline = 0;
        if (phase == Phase.POST_DRAW || phase == Phase.PICK_COLOR || phase == Phase.CHALLENGE) {
            phase = Phase.PLAYING;
        }
    }

    public void endRound(UUID winner) {
        phase = Phase.ROUND_END;
        if (winner != null && mode == Mode.CLASSIC) {
            int points = 0;
            for (var e : hands.entrySet()) {
                if (!e.getKey().equals(winner)) points += e.getValue().stream().mapToInt(Card::points).sum();
            }
            scores.merge(winner, points, Integer::sum);
        }
        events.onRoundEnd(winner);
        if (mode == Mode.QUICK || winner == null || scores.getOrDefault(winner, 0) >= 500) endMatch(winner);
    }

    public void endMatch(UUID winner) {
        phase = Phase.ENDED;
        events.onMatchEnd(winner);
    }

    public void forfeit(UUID player) {
        if (!hands.containsKey(player)) return;
        int index = order().indexOf(player);
        List<Card> hand = hands.remove(player);
        draw.addAll(hand);
        Collections.shuffle(draw, rnd);
        ready.remove(player);
        away.remove(player);
        if (player.equals(host)) host = hands.keySet().stream().findFirst().orElse(null);
        if (count() <= 1) {
            endMatch(current());
            return;
        }
        if (index < turn) turn--;
        turn = rel(turn);
        UUID cur = current();
        if (phase == Phase.PICK_COLOR) {
            chooseColor(cur, bestColor(cur));
        } else if (phase == Phase.CHALLENGE) {
            if (player.equals(w4challenger)) {
                w4offender = w4challenger = null;
                phase = Phase.PLAYING;
                give(cur, 4);
                events.onSkip(cur);
                advance(2);
                events.onTurn(current());
            } else {
                acceptWild4(w4challenger);
            }
        } else if (phase == Phase.PLAYING || phase == Phase.POST_DRAW) {
            events.onTurn(cur);
        }
    }
}
