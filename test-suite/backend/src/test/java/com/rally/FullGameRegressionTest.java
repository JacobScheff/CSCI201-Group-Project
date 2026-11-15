package com.rally;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * REG-01: plays the same scripted three-player game every run and compares the final
 * leaderboard with the expected result in the Testing Plan. Any change to scoring,
 * tie-breaking, or response-time accounting makes this test fail.
 */
@Tag("regression")
class FullGameRegressionTest extends GameTestSupport {

    @Test
    @DisplayName("REG-01: scripted three-player game produces the stored final leaderboard")
    void scriptedGame() {
        Quiz quiz = new Quiz(1L, "Regression quiz", "", false, List.of(
            question(1, "SINGLE", 20, option(11, true), option(12, false)),
            question(2, "MULTI", 20, option(21, true), option(22, false), option(23, true)),
            question(3, "SINGLE", 10, option(31, true), option(32, false))));
        GameSession game = newGame(quiz);
        WebSocketSession host = connectHost(game);
        WebSocketSession aliceSocket = member(game, "Alice", alice);
        WebSocketSession bobSocket = member(game, "Bob", bob);
        WebSocketSession caraSocket = guest(game, "Cara");
        game.command(host, "START", -1, Set.of());

        // Q1: Alice correct at 2 s, Bob correct at 10 s, Cara does not answer; the host reveals.
        elapse(game, 2_000);
        answer(game, aliceSocket, 0, 11L);
        elapse(game, 10_000);
        answer(game, bobSocket, 0, 11L);
        game.command(host, "NEXT", 0, Set.of());
        game.command(host, "NEXT", 0, Set.of());

        // Q2: Alice correct at 5 s, Bob partial at 4 s, Cara correct at 8 s (closes early).
        elapse(game, 5_000);
        answer(game, aliceSocket, 1, 21L, 23L);
        elapse(game, 4_000);
        answer(game, bobSocket, 1, 21L);
        elapse(game, 8_000);
        answer(game, caraSocket, 1, 21L, 23L);
        assertEquals("REVEAL", game.phase);
        game.command(host, "NEXT", 1, Set.of());

        // Q3: Alice wrong at 3 s, Bob correct at 1 s, Cara correct at 5 s (closes early).
        elapse(game, 3_000);
        answer(game, aliceSocket, 2, 32L);
        elapse(game, 1_000);
        answer(game, bobSocket, 2, 31L);
        elapse(game, 5_000);
        answer(game, caraSocket, 2, 31L);
        game.command(host, "NEXT", 2, Set.of());

        assertEquals("COMPLETED", game.phase);
        List<GameSession.Player> ranked = game.ranked();
        assertEquals(List.of("Alice", "Bob", "Cara"), ranked.stream().map(p -> p.name).toList());
        assertEquals(List.of(1825, 1700, 1550), ranked.stream().map(p -> p.score).toList());
        assertEquals(10_000, ranked.get(0).totalResponseMs, 50, "Alice");
        assertEquals(15_000, ranked.get(1).totalResponseMs, 50, "Bob");
        assertEquals(33_000, ranked.get(2).totalResponseMs, 50, "Cara: includes the full 20 s for her missed Q1");

        JsonNode finalBoard = last(caraSocket, "GAME_OVER").path("state").path("leaderboard");
        assertEquals("Alice", finalBoard.get(0).path("name").asText());
        assertEquals(1, finalBoard.get(0).path("rank").asInt());
        assertEquals(3, last(caraSocket, "GAME_OVER").path("state").path("me").path("rank").asInt());
        verify(history, times(1)).save(game);
    }
}
