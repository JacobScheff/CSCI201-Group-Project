package com.rally;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.*;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Black-box and white-box tests for one live game: joining, host controls, answers,
 * timing, ranking, disconnections, and history retries. Test IDs match the Testing Plan.
 */
class GameSessionTest extends GameTestSupport {

    // =====================================================================================
    @Nested
    @DisplayName("Connecting and joining")
    class Joining {

        @Test
        @DisplayName("JOIN-02: the host connects and receives LOBBY_UPDATE")
        void hostConnects() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            JsonNode state = last(host, "LOBBY_UPDATE").path("state");
            assertEquals("LOBBY", state.path("status").asText());
            assertTrue(state.path("host").asBoolean());
            assertEquals(game.code, state.path("code").asText());
        }

        @Test
        @DisplayName("JOIN-03: other users cannot host, and a second host connection is rejected")
        void onlyOneRealHost() {
            GameSession game = newGame();
            ApiException notHost = expectError(403, () -> game.connectHost(socket(), alice));
            assertEquals("FORBIDDEN", notHost.code);
            connectHost(game);
            ApiException second = expectError(409, () -> game.connectHost(socket(), hostUser));
            assertEquals("HOST_CONNECTED", second.code);
        }

        @Test
        @DisplayName("JOIN-04: a guest join is broadcast to everyone with 0 points")
        void guestJoinBroadcast() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            guest(game, "Traveler");
            JsonNode board = last(host, "LOBBY_UPDATE").path("state").path("leaderboard");
            assertEquals(1, board.size());
            assertEquals("Traveler", board.get(0).path("name").asText());
            assertEquals(0, board.get(0).path("score").asInt());
        }

        @Test
        @DisplayName("JOIN-05: display names must be 1 to 20 characters and are trimmed")
        void displayNameBoundaries() {
            GameSession game = newGame();
            connectHost(game);
            expectError(400, () -> guest(game, ""));
            expectError(400, () -> guest(game, "   "));
            expectError(400, () -> guest(game, "a".repeat(21)));
            assertDoesNotThrow(() -> guest(game, "b".repeat(20)));
            guest(game, "  Marco  ");
            assertTrue(game.players.values().stream().anyMatch(p -> p.name.equals("Marco")));
        }

        @Test
        @DisplayName("JOIN-06: display names are unique regardless of case")
        void duplicateNameRejected() {
            GameSession game = newGame();
            guest(game, "Traveler");
            ApiException error = expectError(409, () -> guest(game, "traveler"));
            assertEquals("NAME_TAKEN", error.code);
        }

        @Test
        @DisplayName("JOIN-07: the 20th player is accepted and the 21st is rejected")
        void twentyPlayerLimit() {
            GameSession game = newGame();
            for (int i = 1; i <= 20; i++) guest(game, "Player " + i);
            assertEquals(20, game.players.size());
            ApiException error = expectError(409, () -> guest(game, "Player 21"));
            assertEquals("GAME_FULL", error.code);
        }

        @Test
        @DisplayName("JOIN-08: the host's own account cannot join as a player")
        void hostCannotJoinAsPlayer() {
            GameSession game = newGame();
            ApiException error = expectError(400, () -> member(game, "Host", hostUser));
            assertTrue(error.getMessage().contains("host"));
        }

        @Test
        @DisplayName("JOIN-09: one connection cannot join twice")
        void sameConnectionCannotJoinTwice() {
            GameSession game = newGame();
            WebSocketSession socket = guest(game, "Once");
            ApiException error = expectError(400, () -> game.join(socket, "Twice", null));
            assertEquals("This connection has already joined.", error.getMessage());
        }

        @Test
        @DisplayName("JOIN-11: nobody can join after the game ends")
        void cannotJoinEndedGame() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            game.command(host, "END", -1, Set.of());
            ApiException error = expectError(400, () -> guest(game, "Too late"));
            assertEquals("This game has ended.", error.getMessage());
        }

        @Test
        @DisplayName("JOIN-12: a connection that has not joined cannot send commands")
        void commandsRequireJoining() {
            GameSession game = newGame();
            connectHost(game);
            WebSocketSession stranger = socket();
            assertEquals("NOT_JOINED", expectError(403, () -> game.command(stranger, "START", -1, Set.of())).code);
            assertEquals("NOT_JOINED", expectError(403, () -> answer(game, stranger, 0, 11L)).code);
        }

        @Test
        @DisplayName("JOIN-13: a late joiner sees the open question and can answer it")
        void lateJoinerCanAnswer() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            guest(game, "Early");
            game.command(host, "START", -1, Set.of());

            WebSocketSession late = guest(game, "Late");
            JsonNode state = last(late, "LOBBY_UPDATE").path("state");
            assertEquals("QUESTION", state.path("status").asText());
            assertEquals("Question 1?", state.path("question").path("text").asText());
            assertTrue(state.path("deadline").asLong() > 0);
            assertEquals(0, state.path("me").path("score").asInt());

            answer(game, late, 0, 11L);
            assertTrue(last(late, "ANSWER_ACK").path("accepted").asBoolean());
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Host controls and game flow")
    class HostControls {

        @Test
        @DisplayName("HOST-01: START broadcasts the first question without the answer key")
        void startBroadcastsQuestion() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());

            JsonNode state = last(player, "QUESTION").path("state");
            assertEquals("QUESTION", state.path("status").asText());
            assertEquals(0, state.path("questionIndex").asInt());
            JsonNode question = state.path("question");
            assertEquals("Question 1?", question.path("text").asText());
            assertEquals("SINGLE", question.path("type").asText());
            assertEquals(120, question.path("timeLimitSec").asInt());
            assertEquals(4, question.path("options").size());
            for (JsonNode option : question.path("options")) {
                Set<String> fields = new HashSet<>();
                option.fieldNames().forEachRemaining(fields::add);
                assertEquals(Set.of("id", "text"), fields, "options must not reveal which answer is correct");
            }
            assertTrue(state.path("correctOptionIds").isMissingNode());
        }

        @Test
        @DisplayName("HOST-02: START needs at least one connected player")
        void startNeedsConnectedPlayer() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            expectError(400, () -> game.command(host, "START", -1, Set.of()));

            WebSocketSession member = member(game, "Alice", alice);
            drop(game, member);
            ApiException error = expectError(400, () -> game.command(host, "START", -1, Set.of()));
            assertEquals("Wait for at least one player before starting.", error.getMessage());
            assertEquals("LOBBY", game.phase);
        }

        @Test
        @DisplayName("HOST-03: players cannot send START, NEXT, or END")
        void hostOnlyCommands() {
            GameSession game = newGame();
            connectHost(game);
            WebSocketSession player = guest(game, "Player");
            for (String type : List.of("START", "NEXT", "END")) {
                assertEquals("HOST_ONLY", expectError(403, () -> game.command(player, type, 0, Set.of())).code, type);
            }
        }

        @Test
        @DisplayName("HOST-04: START cannot be sent twice")
        void startTwice() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            assertEquals("The game has already started.",
                expectError(400, () -> game.command(host, "START", -1, Set.of())).getMessage());
        }

        @Test
        @DisplayName("HOST-05 to HOST-07: NEXT reveals, then advances, and ignores stale clicks")
        void nextRevealsThenAdvances() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            guest(game, "Player");
            game.command(host, "START", -1, Set.of());

            game.command(host, "NEXT", 0, Set.of());
            assertEquals("REVEAL", game.phase, "HOST-05: NEXT during a question reveals it");

            game.command(host, "NEXT", 0, Set.of());
            assertEquals("QUESTION", game.phase, "HOST-06: NEXT during the reveal opens the next question");
            assertEquals(1, game.index);

            ApiException stale = expectError(400, () -> game.command(host, "NEXT", 0, Set.of()));
            assertEquals("This question has already advanced.", stale.getMessage(), "HOST-07");
            assertEquals(1, game.index);
            assertEquals("QUESTION", game.phase);
        }

        @Test
        @DisplayName("HOST-08: NEXT in the lobby is rejected")
        void nextInLobby() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            assertEquals("Start the game first.",
                expectError(400, () -> game.command(host, "NEXT", -1, Set.of())).getMessage());
        }

        @Test
        @DisplayName("HOST-09: NEXT after the last reveal completes the game, saves history, and frees the code")
        void finishAfterLastQuestion() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            for (int i = 0; i < 3; i++) {
                game.command(host, "NEXT", i, Set.of()); // reveal question i
                game.command(host, "NEXT", i, Set.of()); // open question i + 1, or finish
            }
            assertEquals("COMPLETED", game.phase);
            assertEquals("COMPLETED", last(player, "GAME_OVER").path("state").path("status").asText());
            verify(history, times(1)).save(game);
            assertFalse(manager.games.containsKey(game.code));
        }

        @Test
        @DisplayName("HOST-10: END during a question scores given answers and terminates the game")
        void endDuringQuestion() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession answered = guest(game, "Answered");
            guest(game, "Waiting");
            game.command(host, "START", -1, Set.of());
            elapse(game, 1000);
            answer(game, answered, 0, 11L);

            game.command(host, "END", -1, Set.of());
            assertEquals("TERMINATED", game.phase);
            assertTrue(game.playerFor(answered).score >= 990, "the answer given before END still counts");
            JsonNode state = last(answered, "GAME_TERMINATED").path("state");
            assertEquals("The host ended the game.", state.path("reason").asText());
            verify(history, times(1)).save(game);
        }

        @Test
        @DisplayName("HOST-11: PING is answered with PONG to the sender only")
        void pingPong() {
            GameSession game = newGame();
            connectHost(game);
            WebSocketSession pinger = guest(game, "Pinger");
            WebSocketSession other = guest(game, "Other");
            game.command(pinger, "PING", -1, Set.of());
            assertEquals(1, count(pinger, "PONG"));
            assertEquals(0, count(other, "PONG"));
        }

        @Test
        @DisplayName("HOST-12: unknown message types are rejected")
        void unknownType() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            assertEquals("Unknown game message.",
                expectError(400, () -> game.command(host, "DANCE", -1, Set.of())).getMessage());
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Answer submission")
    class Answers {

        @Test
        @DisplayName("ANS-01: a valid answer is acknowledged and the answer count is broadcast")
        void validAnswerAcknowledged() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession first = guest(game, "First");
            WebSocketSession second = guest(game, "Second");
            game.command(host, "START", -1, Set.of());

            answer(game, first, 0, 11L);
            assertTrue(last(first, "ANSWER_ACK").path("accepted").asBoolean());
            assertTrue(last(first, "ANSWER_ACK").path("state").path("me").path("submitted").asBoolean());
            assertEquals(1, last(second, "ANSWER_COUNT").path("state").path("answerCount").asInt());
            assertEquals(1, last(host, "ANSWER_COUNT").path("state").path("answerCount").asInt());
        }

        @Test
        @DisplayName("ANS-03: answers to the wrong question, in the lobby, or during the reveal are closed")
        void closedQuestionRejected() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            assertEquals("This question is closed.", expectError(400, () -> answer(game, player, 0, 11L)).getMessage(), "lobby");

            game.command(host, "START", -1, Set.of());
            assertEquals("This question is closed.", expectError(400, () -> answer(game, player, 1, 21L)).getMessage(), "wrong index");

            game.command(host, "NEXT", 0, Set.of());
            assertEquals("This question is closed.", expectError(400, () -> answer(game, player, 0, 11L)).getMessage(), "reveal");
        }

        @Test
        @DisplayName("ANS-04: the server deadline is exact, even before the timer runs")
        void deadlineBoundary() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession late = guest(game, "Late");
            WebSocketSession justInTime = guest(game, "JustInTime");
            game.command(host, "START", -1, Set.of());

            elapse(game, 120_000);
            expectError(400, () -> answer(game, late, 0, 11L));
            assertTrue(game.playerFor(late).answers.isEmpty(), "nothing is stored for a late answer");

            elapse(game, 119_900);
            assertDoesNotThrow(() -> answer(game, justInTime, 0, 11L));
        }

        @Test
        @DisplayName("ANS-05: options from another question and empty answers are rejected")
        void invalidOptionsRejected() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            assertEquals("Choose valid answer options.", expectError(400, () -> answer(game, player, 0, 21L)).getMessage());
            assertEquals("Choose valid answer options.", expectError(400, () -> game.command(player, "ANSWER", 0, Set.of())).getMessage());
            assertTrue(game.playerFor(player).answers.isEmpty());
        }

        @Test
        @DisplayName("ANS-06: a SINGLE question accepts exactly one option")
        void singleNeedsOneOption() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            expectError(400, () -> answer(game, player, 0, 11L, 12L));
        }

        @Test
        @DisplayName("ANS-07: a MULTI question accepts one to four options")
        void multiAcceptsOneToFour() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession one = guest(game, "One");
            WebSocketSession two = guest(game, "Two");
            WebSocketSession four = guest(game, "Four");
            game.command(host, "START", -1, Set.of());
            game.command(host, "NEXT", 0, Set.of());
            game.command(host, "NEXT", 0, Set.of());
            assertEquals(1, game.index);

            assertDoesNotThrow(() -> answer(game, one, 1, 21L));
            assertDoesNotThrow(() -> answer(game, two, 1, 21L, 23L));
            assertDoesNotThrow(() -> answer(game, four, 1, 21L, 22L, 23L, 24L));
            assertEquals(Set.of(21L, 23L), game.playerFor(two).answers.get(1).optionIds());
        }

        @Test
        @DisplayName("ANS-09: the host cannot answer")
        void hostCannotAnswer() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            assertEquals("Hosts cannot submit answers.", expectError(400, () -> answer(game, host, 0, 11L)).getMessage());
        }

        @Test
        @DisplayName("ANS-11: the question closes as soon as every connected player answers")
        void allAnsweredClosesEarly() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession first = guest(game, "First");
            WebSocketSession second = guest(game, "Second");
            game.command(host, "START", -1, Set.of());
            answer(game, first, 0, 11L);
            assertEquals("QUESTION", game.phase);
            answer(game, second, 0, 12L);
            assertEquals("REVEAL", game.phase);
        }

        @Test
        @DisplayName("ANS-12: disconnected players do not hold a question open")
        void disconnectedPlayersDoNotBlockEarlyClose() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession away = member(game, "Alice", alice);
            WebSocketSession first = guest(game, "First");
            WebSocketSession second = guest(game, "Second");
            game.command(host, "START", -1, Set.of());
            drop(game, away);
            answer(game, first, 0, 11L);
            answer(game, second, 0, 11L);
            assertEquals("REVEAL", game.phase);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("WebSocket messages through GameSocket")
    class SocketMessages {

        AuthService auth;
        GameSocket handler;
        GameSession game;
        WebSocketSession host;

        @BeforeEach
        void setUpHandler() {
            auth = mock(AuthService.class);
            when(auth.token("bad-token")).thenThrow(ApiException.unauthorized());
            handler = new GameSocket(manager, auth, json);
            game = newGame();
            host = connectHost(game);
        }

        WebSocketSession attach() {
            WebSocketSession raw = socket();
            handler.connections.put(raw.getId(), new GameSocket.Connection(raw, game));
            return raw;
        }

        void send(WebSocketSession raw, String body) throws Exception {
            handler.handleTextMessage(raw, new TextMessage(body));
        }

        @Test
        @DisplayName("JOIN-01: a socket for a code with no game gets GAME_NOT_FOUND and is closed")
        void unknownCode() throws Exception {
            WebSocketSession raw = socket();
            when(raw.getUri()).thenReturn(URI.create("ws://localhost/ws/game/000000"));
            handler.afterConnectionEstablished(raw);
            assertEquals("GAME_NOT_FOUND", last(raw, "ERROR").path("code").asText());
            verify(raw).close(CloseStatus.POLICY_VIOLATION);
        }

        @Test
        @DisplayName("JOIN-14: JOIN with an invalid token is rejected")
        void invalidTokenRejected() throws Exception {
            WebSocketSession raw = attach();
            send(raw, "{\"type\":\"JOIN\",\"displayName\":\"Mallory\",\"token\":\"bad-token\"}");
            assertEquals("UNAUTHORIZED", last(raw, "ERROR").path("code").asText());
            assertTrue(game.players.isEmpty());
        }

        @Test
        @DisplayName("ANS-08: duplicate option IDs count as one selection")
        void duplicateOptionIdsCollapse() throws Exception {
            WebSocketSession raw = attach();
            send(raw, "{\"type\":\"JOIN\",\"displayName\":\"Ann\"}");
            game.command(host, "START", -1, Set.of());
            send(raw, "{\"type\":\"ANSWER\",\"questionIndex\":0,\"optionIds\":[11,11]}");
            assertTrue(last(raw, "ANSWER_ACK").path("accepted").asBoolean());
            assertEquals(Set.of(11L), game.playerFor(raw).answers.get(0).optionIds());
        }

        @Test
        @DisplayName("ANS-10: client-supplied points and response times are ignored")
        void clientTimingIgnored() throws Exception {
            WebSocketSession raw = attach();
            send(raw, "{\"type\":\"JOIN\",\"displayName\":\"Ann\"}");
            game.command(host, "START", -1, Set.of());
            elapse(game, 60_000);
            send(raw, "{\"type\":\"ANSWER\",\"questionIndex\":0,\"optionIds\":[11],\"responseMs\":1,\"points\":1000}");
            GameSession.Answer saved = game.playerFor(raw).answers.get(0);
            assertTrue(saved.responseMs() >= 60_000, "response time comes from the server clock");
            assertEquals(750, saved.points(), "60 s of a 120 s limit earns 750 points");
        }

        @Test
        @DisplayName("ANS-14: malformed option IDs and malformed JSON get INVALID_INPUT and keep the socket open")
        void malformedMessages() throws Exception {
            WebSocketSession raw = attach();
            send(raw, "{\"type\":\"JOIN\",\"displayName\":\"Ann\"}");
            game.command(host, "START", -1, Set.of());

            send(raw, "{\"type\":\"ANSWER\",\"questionIndex\":0,\"optionIds\":[\"a\"]}");
            assertEquals("INVALID_INPUT", last(raw, "ERROR").path("code").asText());
            assertEquals("Invalid answer option.", last(raw, "ERROR").path("message").asText());

            send(raw, "{not json");
            assertEquals("Invalid game message.", last(raw, "ERROR").path("message").asText());
            verify(raw, never()).close(any());
            assertTrue(game.playerFor(raw).answers.isEmpty());
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Timing and reveal")
    class Timing {

        @Test
        @DisplayName("TIME-01: the timer closes an unanswered question and charges the full time limit")
        void timerClosesUnansweredQuestion() throws Exception {
            Quiz quick = new Quiz(1L, "Quick", "", false, List.of(
                question(1, "SINGLE", 1, option(11, true), option(12, false)),
                question(2, "SINGLE", 120, option(21, true), option(22, false))));
            GameSession game = newGame(quick);
            WebSocketSession host = connectHost(game);
            WebSocketSession first = guest(game, "First");
            WebSocketSession second = guest(game, "Second");
            game.command(host, "START", -1, Set.of());

            waitUntil(() -> "REVEAL".equals(phase(game)), 3000, "the 1-second timer to close the question");
            for (WebSocketSession socket : List.of(first, second)) {
                GameSession.Player player = locked(game, () -> game.playerFor(socket));
                assertEquals(0, locked(game, () -> player.score));
                assertEquals(1000L, locked(game, () -> player.totalResponseMs));
            }
        }

        @Test
        @DisplayName("TIME-04: the reveal shows the answer key, each player's own points, and the leaderboard")
        void revealContents() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession right = guest(game, "Right");
            WebSocketSession wrong = guest(game, "Wrong");
            game.command(host, "START", -1, Set.of());
            elapse(game, 60_000);
            answer(game, right, 0, 11L);
            answer(game, wrong, 0, 12L);

            JsonNode rightState = last(right, "REVEAL").path("state");
            assertEquals("[11]", rightState.path("correctOptionIds").toString());
            assertEquals(750, rightState.path("me").path("points").asInt());
            assertEquals("[11]", rightState.path("me").path("selectedOptionIds").toString());
            assertEquals(2, rightState.path("leaderboard").size());

            JsonNode wrongState = last(wrong, "REVEAL").path("state");
            assertEquals(0, wrongState.path("me").path("points").asInt());
        }

        @Test
        @DisplayName("TIME-05: the reveal advances automatically after 4 seconds, and the last one completes the game")
        void autoAdvance() throws Exception {
            Quiz twoQuestions = new Quiz(1L, "Two", "", false, List.of(
                question(1, "SINGLE", 120, option(11, true), option(12, false)),
                question(2, "SINGLE", 120, option(21, true), option(22, false))));
            GameSession game = newGame(twoQuestions);
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());

            answer(game, player, 0, 11L);
            long revealedAt = System.currentTimeMillis();
            waitUntil(() -> locked(game, () -> game.phase.equals("QUESTION") && game.index == 1), 6000, "automatic advance");
            assertTrue(System.currentTimeMillis() - revealedAt >= 3500, "the reveal lasts about 4 seconds");

            answer(game, player, 1, 21L);
            waitUntil(() -> "COMPLETED".equals(phase(game)), 6000, "automatic completion");
            verify(history, times(1)).save(game);
        }

        @Test
        @DisplayName("TIME-06: if the host advances during the reveal, the automatic advance does nothing")
        void hostNextBeatsAutoAdvance() throws Exception {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            answer(game, player, 0, 11L);
            game.command(host, "NEXT", 0, Set.of());
            assertEquals(1, game.index);

            Thread.sleep(4500);
            assertEquals(1, (int) locked(game, () -> game.index), "question 1 was not skipped");
            assertEquals("QUESTION", phase(game));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Leaderboard and ranking")
    class Ranking {

        List<String> rankedNames(GameSession game) {
            return game.ranked().stream().map(p -> p.name).toList();
        }

        void set(GameSession game, WebSocketSession socket, int score, long totalMs) {
            GameSession.Player player = game.playerFor(socket);
            player.score = score;
            player.totalResponseMs = totalMs;
        }

        @Test
        @DisplayName("RANK-01: players are ordered by score, highest first")
        void byScore() {
            GameSession game = newGame();
            set(game, guest(game, "A"), 1500, 0);
            set(game, guest(game, "B"), 2000, 0);
            set(game, guest(game, "C"), 900, 0);
            assertEquals(List.of("B", "A", "C"), rankedNames(game));
        }

        @Test
        @DisplayName("RANK-02: a score tie goes to the lower total response time")
        void tieBrokenByTime() {
            GameSession game = newGame();
            set(game, guest(game, "Slow"), 1500, 8000);
            set(game, guest(game, "Fast"), 1500, 6500);
            assertEquals(List.of("Fast", "Slow"), rankedNames(game));
        }

        @Test
        @DisplayName("RANK-03: a full tie goes to the player who joined first")
        void tieBrokenByJoinOrder() {
            GameSession game = newGame();
            set(game, guest(game, "First"), 1000, 5000);
            set(game, guest(game, "Second"), 1000, 5000);
            assertEquals(List.of("First", "Second"), rankedNames(game));
        }

        @Test
        @DisplayName("RANK-04: ranks are consecutive from 1 with no gaps")
        void consecutiveRanks() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            int[] scores = {300, 900, 100, 700, 500};
            for (int i = 0; i < scores.length; i++) set(game, guest(game, "P" + i), scores[i], 0);
            JsonNode board = MAPPER.valueToTree(game.view(host)).path("leaderboard");
            for (int i = 0; i < board.size(); i++) assertEquals(i + 1, board.get(i).path("rank").asInt());
            assertEquals(900, board.get(0).path("score").asInt());
        }

        @Test
        @DisplayName("RANK-05: a late joiner's response time counts only questions after they joined")
        void lateJoinerTime() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession early = guest(game, "Early");
            game.command(host, "START", -1, Set.of());
            elapse(game, 3000);
            answer(game, early, 0, 11L); // only player: question 0 closes

            WebSocketSession late = guest(game, "Late");
            game.command(host, "NEXT", 0, Set.of());
            elapse(game, 2000);
            answer(game, early, 1, 21L, 23L);
            answer(game, late, 1, 21L, 23L);

            assertEquals("REVEAL", game.phase);
            assertEquals(2000, game.playerFor(late).totalResponseMs, 50, "question 0 is not charged to the late joiner");
            assertEquals(5000, game.playerFor(early).totalResponseMs, 50);
        }

        @Test
        @DisplayName("RANK-06: every client sees the same order and its own correct rank")
        void ownRankMatchesBoard() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            List<WebSocketSession> players = List.of(guest(game, "A"), guest(game, "B"), guest(game, "C"));
            game.command(host, "START", -1, Set.of());
            long[] delays = {9000, 1000, 5000};
            for (int i = 0; i < 3; i++) {
                elapse(game, delays[i]);
                answer(game, players.get(i), 0, 11L);
            }
            String hostOrder = last(host, "REVEAL").path("state").path("leaderboard").toString();
            for (WebSocketSession socket : players) {
                JsonNode state = last(socket, "REVEAL").path("state");
                assertEquals(hostOrder, state.path("leaderboard").toString());
                String myId = state.path("me").path("id").asText();
                JsonNode board = state.path("leaderboard");
                for (int i = 0; i < board.size(); i++) {
                    if (board.get(i).path("id").asText().equals(myId)) {
                        assertEquals(i + 1, state.path("me").path("rank").asInt());
                    }
                }
            }
            assertEquals(List.of("B", "C", "A"), game.ranked().stream().map(p -> p.name).toList());
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Disconnections")
    class Disconnections {

        @Test
        @DisplayName("DISC-02: a guest who leaves the lobby is removed and their name is freed")
        void guestLeavesLobby() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession traveler = guest(game, "Traveler");
            drop(game, traveler);
            assertTrue(game.players.isEmpty());
            assertEquals(0, last(host, "LOBBY_UPDATE").path("state").path("leaderboard").size());
            assertDoesNotThrow(() -> guest(game, "Traveler"));
        }

        @Test
        @DisplayName("DISC-03: a registered player who drops stays on the board and keeps their score")
        void registeredPlayerKept() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession member = member(game, "Alice", alice);
            guest(game, "Guest");
            game.command(host, "START", -1, Set.of());
            elapse(game, 1000);
            answer(game, member, 0, 11L);
            GameSession.Player player = game.playerFor(member);

            drop(game, member);
            game.command(host, "NEXT", 0, Set.of());
            assertTrue(game.players.containsValue(player));
            assertTrue(player.score >= 990);
            JsonNode entry = last(host, "REVEAL").path("state").path("leaderboard").get(0);
            assertEquals("Alice", entry.path("name").asText());
            assertFalse(entry.path("connected").asBoolean());
        }

        @Test
        @DisplayName("DISC-06: when the last unanswered player leaves, the question closes early (Tech Spec 13)")
        void lastUnansweredPlayerLeaves() {
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession answered = guest(game, "Answered");
            WebSocketSession leaving = guest(game, "Leaving");
            game.command(host, "START", -1, Set.of());
            answer(game, answered, 0, 11L);

            drop(game, leaving);
            assertEquals("REVEAL", game.phase,
                "Every connected player has answered, so the question should close without waiting for the timer");
        }

        @Test
        @DisplayName("DISC-07: a connection silent for more than 45 seconds is disconnected and closed")
        void silentConnectionDropped() throws Exception {
            GameSession game = newGame();
            connectHost(game);
            WebSocketSession silent = guest(game, "Silent");
            game.heartbeats.put(silent.getId(), System.currentTimeMillis() - 46_000);

            game.checkConnections();
            assertNull(game.playerFor(silent));
            verify(silent).close();
            assertEquals("LOBBY", game.phase, "the host is still connected, so the game continues");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("History saving")
    class HistoryRetry {

        @Test
        @DisplayName("HIST-07: a failed history save is retried after 5 seconds and clients are told when it succeeds")
        void retriesFailedSave() throws Exception {
            doThrow(new RuntimeException("database down")).doReturn(7L).when(history).save(any());
            GameSession game = newGame();
            WebSocketSession host = connectHost(game);
            WebSocketSession player = guest(game, "Player");
            game.command(host, "START", -1, Set.of());
            game.command(host, "END", -1, Set.of());

            assertEquals("TERMINATED", game.phase, "players still see the game end");
            assertFalse(locked(game, () -> game.historySaved));
            assertFalse(last(player, "GAME_TERMINATED").path("state").path("historySaved").asBoolean());

            waitUntil(() -> locked(game, () -> game.historySaved), 8000, "the retry to save history");
            verify(history, times(2)).save(game);
            JsonNode state = last(player, "STATE_SYNC").path("state");
            assertTrue(state.path("historySaved").asBoolean());
            assertEquals(7, state.path("historyId").asInt());
        }
    }
}
