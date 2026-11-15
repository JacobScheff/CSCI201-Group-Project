package com.rally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Game creation: six-digit codes, collisions, the three-room limit, and abandoned lobbies. */
class GameManagerTest extends GameTestSupport {

    /** A random source that returns scripted values, so code collisions can be forced. */
    static final class ScriptedRandom extends SecureRandom {
        final Deque<Integer> values = new ArrayDeque<>();

        ScriptedRandom(int... values) {
            for (int value : values) this.values.add(value);
        }

        @Override
        public int nextInt(int bound) {
            return values.isEmpty() ? 0 : values.poll();
        }
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    User host(long id) {
        return new User(id, "host" + id, "host" + id + "@example.com");
    }

    @Test
    @DisplayName("CODE-01: codes have six digits from 100000 to 999999 and games start in the lobby")
    void codesAreSixDigits() {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        for (int i = 0; i < 50; i++) {
            GameSession game = manager.create(1, host(100 + i));
            assertTrue(game.code.matches("\\d{6}"), game.code);
            int value = Integer.parseInt(game.code);
            assertTrue(value >= 100000 && value <= 999999, game.code);
            assertEquals("LOBBY", game.phase);
            manager.games.remove(game.code);
        }
    }

    @Test
    @DisplayName("CODE-02: a code already in use is skipped and a free code is drawn")
    void collisionRetries() throws Exception {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        manager.games.put("123456", new GameSession("123456", 99, defaultQuiz(), manager));
        setField(manager, "random", new ScriptedRandom(23456, 23456, 65432));

        GameSession game = manager.create(1, hostUser);
        assertEquals("165432", game.code);
    }

    @Test
    @DisplayName("CODE-03: ten collisions in a row return CODE_UNAVAILABLE")
    void tenCollisionsFail() throws Exception {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        manager.games.put("123456", new GameSession("123456", 99, defaultQuiz(), manager));
        int[] alwaysTaken = new int[10];
        Arrays.fill(alwaysTaken, 23456);
        setField(manager, "random", new ScriptedRandom(alwaysTaken));

        ApiException error = expectError(503, () -> manager.create(1, hostUser));
        assertEquals("CODE_UNAVAILABLE", error.code);
        assertEquals(1, manager.games.size(), "no game was created");
    }

    @Test
    @DisplayName("CODE-04: a fourth live game is rejected")
    void threeRoomLimit() {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        for (int i = 1; i <= 3; i++) manager.create(1, host(i));
        assertEquals("CAPACITY", expectError(409, () -> manager.create(1, host(4))).code);
    }

    @Test
    @DisplayName("CODE-05: a host cannot run two games at once")
    void oneGamePerHost() {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        manager.create(1, hostUser);
        assertEquals("ALREADY_HOSTING", expectError(409, () -> manager.create(1, hostUser)).code);
    }

    @Test
    @DisplayName("CODE-06: hosting a quiz you do not own returns 404")
    void cannotHostOthersQuiz() {
        when(quizzes.owned(eq(99L), anyLong())).thenThrow(ApiException.missing());
        assertEquals("NOT_FOUND", expectError(404, () -> manager.create(99, hostUser)).code);
        assertTrue(manager.games.isEmpty());
    }

    @Test
    @DisplayName("CODE-07: an ended game releases its code and its room")
    void endedGameFreesRoom() {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        GameSession first = manager.create(1, host(1));
        manager.create(1, host(2));
        manager.create(1, host(3));

        WebSocketSession firstHost = socket();
        first.connectHost(firstHost, host(1));
        first.command(firstHost, "END", -1, Set.of());

        assertFalse(manager.games.containsKey(first.code));
        assertEquals(2, manager.games.size());
        assertDoesNotThrow(() -> manager.create(1, host(4)));
    }

    @Test
    @DisplayName("CODE-08: when ten hosts create games at once, exactly three succeed")
    void concurrentCreation() throws Exception {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(defaultQuiz());
        CyclicBarrier barrier = new CyclicBarrier(10);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                User user = host(i + 1);
                results.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return manager.create(1, user).code;
                    } catch (ApiException e) {
                        return e.code;
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));

            List<String> codes = outcomes.stream().filter(o -> o.matches("\\d{6}")).toList();
            assertEquals(3, codes.size());
            assertEquals(3, new HashSet<>(codes).size(), "codes are distinct");
            assertEquals(7, outcomes.stream().filter("CAPACITY"::equals).count());
        }
    }

    @Test
    @DisplayName("CODE-09: a lobby whose host never connects is closed after 60 seconds")
    void abandonedLobbyCloses() throws Exception {
        GameSession game = newGame();
        setField(game, "createdAt", System.currentTimeMillis() - 61_000);
        game.checkConnections();
        assertEquals("TERMINATED", game.phase);
        assertEquals("The host did not connect.", game.reason);
        assertFalse(manager.games.containsKey(game.code));
    }
}
