package com.rally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Races that a single-threaded test cannot reach: timer against answers, host against players. */
class ConcurrencyTest extends GameTestSupport {

    @Test
    @DisplayName("PERF-02: the timer and the final answer race 200 times; the question closes once and scores once")
    void timerRacesLastAnswer() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 200; round++) {
                GameSession game = newGame();
                WebSocketSession host = connectHost(game);
                WebSocketSession early = guest(game, "Early");
                WebSocketSession last = guest(game, "Last");
                game.command(host, "START", -1, Set.of());
                answer(game, early, 0, 11L);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> lastAnswer = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        answer(game, last, 0, 11L);
                        return true;
                    } catch (ApiException closed) {
                        assertEquals("This question is closed.", closed.getMessage());
                        return false;
                    }
                });
                Future<?> timer = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    game.lock.lock(); // exactly what the scheduled timer task does
                    try {
                        game.closeQuestion(0);
                    } finally {
                        game.lock.unlock();
                    }
                    return null;
                });
                boolean lastAccepted = lastAnswer.get(5, TimeUnit.SECONDS);
                timer.get(5, TimeUnit.SECONDS);

                game.lock.lock();
                try {
                    assertEquals("REVEAL", game.phase, "round " + round);
                    for (GameSession.Player player : game.players.values()) {
                        GameSession.Answer given = player.answers.get(0);
                        assertEquals(given == null ? 0 : given.points(), player.score,
                            "round " + round + ": each answer is scored exactly once");
                    }
                    assertEquals(lastAccepted, game.playerFor(last).answers.containsKey(0));
                } finally {
                    game.lock.unlock();
                }
                manager.games.remove(game.code);
            }
        }
    }

    @Test
    @DisplayName("PERF-03: 20 players answer while the host reveals; every answer is scored once or cleanly rejected")
    void hostRevealRacesPlayers() throws Exception {
        GameSession game = newGame();
        WebSocketSession host = connectHost(game);
        List<WebSocketSession> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) players.add(guest(game, "Player " + i));
        game.command(host, "START", -1, Set.of());

        CyclicBarrier barrier = new CyclicBarrier(21);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(21)) {
            for (WebSocketSession player : players) {
                tasks.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        answer(game, player, 0, 11L);
                        accepted.incrementAndGet();
                    } catch (ApiException closed) {
                        assertEquals("This question is closed.", closed.getMessage());
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            tasks.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    game.command(host, "NEXT", 0, Set.of());
                } catch (ApiException alreadyMoved) {
                    // Allowed: if every player answered first, the question already closed.
                }
                return null;
            }));
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS); // rethrows any unexpected exception
        }

        assertEquals(20, accepted.get() + rejected.get());
        game.lock.lock();
        try {
            assertFalse(game.phase.equals("QUESTION") && game.index == 0, "question 0 is closed");
            int answered = 0;
            for (GameSession.Player player : game.players.values()) {
                GameSession.Answer given = player.answers.get(0);
                if (given != null) answered++;
                assertEquals(given == null ? 0 : given.points(), player.score, "each answer is scored exactly once");
            }
            assertEquals(accepted.get(), answered);
        } finally {
            game.lock.unlock();
        }
    }
}
