package com.rally;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Shared fixture for game engine tests. Runs the real GameManager and GameSession with
 * a mocked QuizService, a mocked GameHistoryService, and mocked WebSocket connections.
 */
abstract class GameTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();

    final User hostUser = new User(1, "host", "host@example.com");
    final User alice = new User(2, "alice", "alice@example.com");
    final User bob = new User(3, "bob", "bob@example.com");

    GameManager manager;
    QuizService quizzes;
    GameHistoryService history;
    Json json;

    @BeforeEach
    void startManager() {
        quizzes = mock(QuizService.class);
        history = mock(GameHistoryService.class);
        when(history.save(any())).thenReturn(42L);
        json = new Json(MAPPER);
        manager = new GameManager(quizzes, history, json);
    }

    @AfterEach
    void stopManager() {
        manager.stop();
    }

    // ---------- Quiz builders ----------

    static Option option(long id, boolean correct) {
        return new Option(id, "Option " + id, correct);
    }

    static Question question(long id, String type, int limitSec, Option... options) {
        return new Question(id, "Question " + id + "?", type, limitSec, null, List.of(options));
    }

    /**
     * Default quiz: Q0 SINGLE (11 correct), Q1 MULTI (21 and 23 correct), Q2 SINGLE (31 correct).
     * Long time limits keep the real timer from firing during a test.
     */
    static Quiz defaultQuiz() {
        return new Quiz(1L, "Test quiz", "", false, List.of(
            question(1, "SINGLE", 120, option(11, true), option(12, false), option(13, false), option(14, false)),
            question(2, "MULTI", 120, option(21, true), option(22, false), option(23, true), option(24, false)),
            question(3, "SINGLE", 120, option(31, true), option(32, false))));
    }

    // ---------- Game helpers ----------

    GameSession newGame(Quiz quiz) {
        when(quizzes.owned(anyLong(), anyLong())).thenReturn(quiz);
        return manager.create(quiz.id(), hostUser);
    }

    GameSession newGame() {
        return newGame(defaultQuiz());
    }

    static WebSocketSession socket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        return socket;
    }

    WebSocketSession connectHost(GameSession game) {
        WebSocketSession host = socket();
        game.connectHost(host, hostUser);
        return host;
    }

    static WebSocketSession guest(GameSession game, String name) {
        WebSocketSession socket = socket();
        game.join(socket, name, null);
        return socket;
    }

    static WebSocketSession member(GameSession game, String name, User user) {
        WebSocketSession socket = socket();
        game.join(socket, name, user);
        return socket;
    }

    /** Makes the server believe the current question opened {@code ms} milliseconds ago. */
    static void elapse(GameSession game, long ms) {
        game.questionStartedNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(ms);
    }

    static void answer(GameSession game, WebSocketSession socket, int index, Long... optionIds) {
        game.command(socket, "ANSWER", index, Set.of(optionIds));
    }

    /** Closes the socket the way the WebSocket handler does after a connection drops. */
    static void drop(GameSession game, WebSocketSession socket) {
        when(socket.isOpen()).thenReturn(false);
        game.disconnect(socket);
    }

    /** Reads game state under the game's lock so timer threads' writes are visible. */
    static <T> T locked(GameSession game, Supplier<T> read) {
        game.lock.lock();
        try {
            return read.get();
        } finally {
            game.lock.unlock();
        }
    }

    static String phase(GameSession game) {
        return locked(game, () -> game.phase);
    }

    static void waitUntil(BooleanSupplier condition, long timeoutMs, String description) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: " + description);
            Thread.sleep(20);
        }
    }

    static ApiException expectError(int status, Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(status, error.status, "HTTP-style status of: " + error.getMessage());
        return error;
    }

    // ---------- Message inspection ----------

    /** Every JSON message the server sent to this socket, oldest first. */
    static List<JsonNode> messages(WebSocketSession socket) {
        try {
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(socket, atLeast(0)).sendMessage(captor.capture());
            List<JsonNode> result = new ArrayList<>();
            for (TextMessage message : captor.getAllValues()) result.add(MAPPER.readTree(message.getPayload()));
            return result;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The most recent message of the given type sent to this socket. */
    static JsonNode last(WebSocketSession socket, String type) {
        List<JsonNode> all = messages(socket);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (type.equals(all.get(i).path("type").asText())) return all.get(i);
        }
        fail("No " + type + " message was sent. Received: " + all.stream().map(m -> m.path("type").asText()).toList());
        return null;
    }

    static long count(WebSocketSession socket, String type) {
        return messages(socket).stream().filter(m -> type.equals(m.path("type").asText())).count();
    }
}
