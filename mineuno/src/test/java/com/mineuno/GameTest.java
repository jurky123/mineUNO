package com.mineuno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 纯规则层回归测试（不依赖 Bukkit 运行时）。 */
class GameTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static int nextCardId = 100000;

    private Game game(int players, Game.Mode mode) {
        Game g = new Game("T", mode, true, 7, 8);
        g.events = new Game.Events() {};
        g.join(A);
        if (players > 1) g.join(B);
        if (players > 2) g.join(C);
        g.startRound();
        g.beginPlay();
        return g;
    }

    private Card card(Card.Color color, Card.Type type, int number) {
        return new Card(nextCardId++, color, type, number);
    }

    // ---------- 牌库与基本流转 ----------

    @Test
    void deckIs108UniqueCards() {
        Game g = game(8, Game.Mode.QUICK);
        Map<Integer, Integer> seen = new HashMap<>();
        for (Card c : g.draw) seen.merge(c.id(), 1, Integer::sum);
        for (Card c : g.discard) seen.merge(c.id(), 1, Integer::sum);
        for (List<Card> hand : g.hands.values()) for (Card c : hand) seen.merge(c.id(), 1, Integer::sum);
        assertEquals(108, seen.size());
        assertEquals(108, seen.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(seen.values().stream().allMatch(n -> n == 1));
        assertEquals(7, g.hand(A).size());
    }

    @Test
    void activeColorFollowsPlayedCard() {
        Game g = game(3, Game.Mode.QUICK);
        UUID cur = g.current();
        Card green = card(Card.Color.GREEN, Card.Type.NUMBER, 4);
        g.hand(cur).add(green);
        g.activeColor = Card.Color.GREEN;
        assertTrue(g.play(cur, green.id()));
        assertEquals(Card.Color.GREEN, g.activeColor);
    }

    // ---------- 两人局与方向 ----------

    @Test
    void twoPlayerReverseKeepsTurn() {
        for (int i = 0; i < 50; i++) {
            Game g = game(2, Game.Mode.QUICK);
            g.turn = 0;
            UUID cur = g.current();
            g.activeColor = Card.Color.RED;
            Card reverse = card(Card.Color.RED, Card.Type.REVERSE, -1);
            g.hand(cur).add(reverse);
            assertTrue(g.play(cur, reverse.id()));
            assertEquals(cur, g.current(), "两人局反转后应仍由自己出牌");
        }
    }

    @Test
    void reverseDirectionForfeitPicksCorrectSuccessor() {
        Game g = game(3, Game.Mode.QUICK);
        g.dir = -1;
        g.turn = 1;                                  // 当前 = B
        UUID expected = g.order().get(0);            // 逆序下一位 = A
        g.forfeit(B);
        assertEquals(expected, g.current());
    }

    // ---------- 弃权边界 ----------

    @Test
    void postDrawForfeitClearsPendingDraw() {
        Game g = game(3, Game.Mode.QUICK);
        UUID cur = g.current();
        g.phase = Game.Phase.POST_DRAW;
        g.drawn = g.hand(cur).get(0);
        g.forfeit(cur);
        assertEquals(Game.Phase.PLAYING, g.phase);
        assertEquals(null, g.drawn);
    }

    @Test
    void reversePostDrawForfeitUsesDirection() {
        Game g = game(3, Game.Mode.QUICK);
        g.dir = -1;
        g.turn = 1;
        UUID cur = g.current();
        UUID expected = g.order().get(0);
        g.phase = Game.Phase.POST_DRAW;
        g.drawn = g.hand(cur).get(0);
        g.forfeit(cur);
        assertEquals(Game.Phase.PLAYING, g.phase);
        assertEquals(expected, g.current());
    }

    @Test
    void challengeOffenderForfeitFinesChallenger() {
        Game g = game(3, Game.Mode.QUICK);
        g.turn = 0;
        UUID offender = g.current();
        Card wild4 = card(Card.Color.WILD, Card.Type.WILD4, -1);
        g.hand(offender).add(wild4);
        assertTrue(g.play(offender, wild4.id()));
        assertTrue(g.chooseColor(offender, Card.Color.RED));
        assertEquals(Game.Phase.CHALLENGE, g.phase);
        UUID challenger = g.w4challenger;
        int before = g.hand(challenger).size();
        g.forfeit(offender);
        assertEquals(Game.Phase.PLAYING, g.phase);
        assertEquals(before + 4, g.hand(challenger).size(), "质疑者应摸 4 张");
    }

    @Test
    void challengeChallengerForfeitStillFinesSuccessor() {
        for (int i = 0; i < 20; i++) {
            Game g = game(3, Game.Mode.QUICK);
            g.turn = 0;
            UUID offender = g.current();
            Card wild4 = card(Card.Color.WILD, Card.Type.WILD4, -1);
            g.hand(offender).add(wild4);
            g.play(offender, wild4.id());
            g.chooseColor(offender, Card.Color.RED);
            int before = g.hand(offender).size();
            g.forfeit(g.w4challenger);
            assertEquals(before, g.hand(offender).size(), "罚牌不应给原来的出牌者");
            assertEquals(Game.Phase.PLAYING, g.phase);
        }
    }

    @Test
    void pickColorOffenderForfeitResolvesPhase() {
        Game g = game(3, Game.Mode.QUICK);
        g.turn = 0;
        UUID a = g.current();
        Card wild = card(Card.Color.WILD, Card.Type.WILD, -1);
        g.hand(a).add(wild);
        assertTrue(g.play(a, wild.id()));
        assertEquals(Game.Phase.PICK_COLOR, g.phase);
        g.forfeit(a);
        assertEquals(Game.Phase.PLAYING, g.phase);
        assertNotNull(g.current());
        assertNotEquals(a, g.current());
    }

    @Test
    void lastCardWildForfeitScoresLeaver() {
        Game quick = game(3, Game.Mode.QUICK);
        quick.turn = 0;
        UUID a = quick.current();
        quick.hand(a).clear();
        Card wild = card(Card.Color.WILD, Card.Type.WILD, -1);
        quick.hand(a).add(wild);
        quick.play(a, wild.id());
        quick.forfeit(a);
        assertEquals(Game.Phase.ENDED, quick.phase, "最后一张 Wild 弃权应结算");

        Game classic = game(3, Game.Mode.CLASSIC);
        classic.turn = 0;
        UUID b = classic.current();
        classic.hand(b).clear();
        Card wild4 = card(Card.Color.WILD, Card.Type.WILD4, -1);
        classic.hand(b).add(wild4);
        classic.play(b, wild4.id());
        classic.forfeit(b);
        assertTrue(classic.scores.getOrDefault(b, 0) > 0, "最后一张 +4 弃权应记给离开者");
    }

    @Test
    void twoPlayerForfeitEndsMatch() {
        Game reverse = game(2, Game.Mode.QUICK);
        reverse.turn = 0;
        UUID a = reverse.current();
        reverse.activeColor = Card.Color.RED;
        Card rev = card(Card.Color.RED, Card.Type.REVERSE, -1);
        reverse.hand(a).add(rev);
        reverse.play(a, rev.id());
        assertEquals(a, reverse.current());
        reverse.forfeit(a);
        assertEquals(Game.Phase.ENDED, reverse.phase);

        Game challenge = game(2, Game.Mode.QUICK);
        challenge.turn = 0;
        UUID b = challenge.current();
        Card wild4 = card(Card.Color.WILD, Card.Type.WILD4, -1);
        challenge.hand(b).add(wild4);
        challenge.play(b, wild4.id());
        challenge.chooseColor(b, Card.Color.RED);
        challenge.forfeit(challenge.w4challenger);
        assertEquals(Game.Phase.ENDED, challenge.phase);
    }

    // ---------- 计时序号 ----------

    @Test
    void turnSeqAdvancesOnActionAndDecision() {
        Game g = game(3, Game.Mode.QUICK);
        g.turn = 0;
        UUID cur = g.current();
        int seq = g.turnSeq;
        g.activeColor = Card.Color.RED;
        Card number = card(Card.Color.RED, Card.Type.NUMBER, 5);
        g.hand(cur).add(number);
        assertTrue(g.play(cur, number.id()));
        assertTrue(g.turnSeq > seq, "出牌后 turnSeq 应递增");

        Game w = game(3, Game.Mode.QUICK);
        w.turn = 0;
        UUID wcur = w.current();
        int wseq = w.turnSeq;
        Card wild = card(Card.Color.WILD, Card.Type.WILD, -1);
        w.hand(wcur).add(wild);
        assertTrue(w.play(wcur, wild.id()));
        assertTrue(w.turnSeq > wseq, "进入选色后 turnSeq 应递增");
    }

    // ---------- 手牌排布与分页 ----------

    @Test
    void handLayoutRowsAndPaging() {
        for (int n = 0; n <= 40; n++) {
            List<HandLayout.Slot> slots = HandLayout.layout(n, 8);
            assertEquals(n, slots.size());
            if (n > 8) {
                assertEquals(2, slots.get(n - 1).row() + 1, "超过每排上限应分两排");
            } else if (n > 0) {
                assertEquals(1, slots.get(n - 1).row() + 1);
            }
            int pages = HandLayout.pageCount(n, 16);
            assertEquals(Math.max(1, (n + 15) / 16), pages);
            assertEquals(pages - 1, HandLayout.clampPage(99, n, 16));
        }
    }

    // ---------- 随机对局 + 不变量 ----------

    @Test
    void randomGamesKeepInvariants() {
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(seed);
            int players = 2 + rnd.nextInt(7);
            Game g = new Game("T", seed % 2 == 0 ? Game.Mode.CLASSIC : Game.Mode.QUICK, true, 7, 8);
            g.events = new Game.Events() {};
            for (int i = 0; i < players; i++) g.join(new UUID(0, i + 1));
            g.startRound();
            g.beginPlay();

            int steps = 0;
            int rounds = 0;
            while (g.phase != Game.Phase.ENDED && steps++ < 20000) {
                checkInvariants(g);
                UUID cur = g.current();
                switch (g.phase) {
                    case PLAYING -> {
                        if (rnd.nextInt(3) == 0) {
                            g.drawCard(cur);
                        } else {
                            List<Card> playable = new ArrayList<>();
                            for (Card c : g.hand(cur)) if (g.playable(c)) playable.add(c);
                            if (playable.isEmpty()) g.drawCard(cur);
                            else g.play(cur, playable.get(rnd.nextInt(playable.size())).id());
                        }
                    }
                    case POST_DRAW -> {
                        if (g.drawn != null && rnd.nextInt(3) != 0) g.play(cur, g.drawn.id());
                        else g.pass(cur);
                    }
                    case PICK_COLOR -> g.chooseColor(cur, Card.COLORS.get(rnd.nextInt(4)));
                    case CHALLENGE -> {
                        if (rnd.nextBoolean()) g.acceptWild4(cur);
                        else g.challenge(cur);
                    }
                    case ROUND_END -> {
                        if (rounds++ > 100) return;
                        g.startRound();
                        g.beginPlay();
                    }
                    default -> throw new IllegalStateException("未处理阶段 " + g.phase);
                }
                if (rnd.nextInt(30) == 0) g.forceResolve();
                if (rnd.nextInt(40) == 0) {
                    UUID leaver = new UUID(0, 1 + rnd.nextInt(players));
                    if (g.hands.containsKey(leaver)) g.forfeit(leaver);
                }
            }
            assertEquals(Game.Phase.ENDED, g.phase, "对局应能结束");
            checkInvariants(g);
        }
    }

    private void checkInvariants(Game g) {
        Map<Integer, Integer> seen = new HashMap<>();
        for (Card c : g.draw) seen.merge(c.id(), 1, Integer::sum);
        for (Card c : g.discard) seen.merge(c.id(), 1, Integer::sum);
        for (List<Card> hand : g.hands.values()) for (Card c : hand) seen.merge(c.id(), 1, Integer::sum);
        if (!seen.isEmpty()) {
            assertEquals(108, seen.size());
            assertFalse(seen.values().stream().anyMatch(n -> n != 1), "存在重复牌");
        }
        if (g.phase != Game.Phase.ENDED) assertNotNull(g.current());
        if (g.phase == Game.Phase.POST_DRAW) assertNotNull(g.drawn, "POST_DRAW 必须有摸到的牌");
        assertNotNull(g.activeColor);
    }
}
