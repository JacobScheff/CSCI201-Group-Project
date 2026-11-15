package com.rally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/** Saving finished games, listing history, and restricting who can read it. */
class GameHistoryServiceTest extends SpringTestSupport {

    /** Creates a game for the host's copy of an example quiz without leaving it in the live-game map. */
    GameSession endedGame(User host, String status, int lastQuestionIndex) {
        Quiz quiz = quizzes.cloneExample(quizzes.examples().get(0).id(), host.id());
        GameSession game = games.create(quiz.id(), host);
        games.games.remove(game.code);
        game.phase = status;
        game.index = lastQuestionIndex;
        return game;
    }

    static GameSession.Player addPlayer(GameSession game, Long userId, String name, long order) {
        GameSession.Player player = new GameSession.Player(userId, name, order, null);
        game.players.put(player.id, player);
        return player;
    }

    static void addAnswer(GameSession game, GameSession.Player player, int questionIndex, long responseMs, int points) {
        long optionId = game.quiz.questions().get(questionIndex).options().get(0).id();
        player.answers.put(questionIndex, new GameSession.Answer(Set.of(optionId), responseMs, points));
        player.score += points;
        player.totalResponseMs += responseMs;
    }

    int count(String sql, Object... args) {
        return store.db.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("HIST-01: a completed game saves the game, every player, and one answer row per player per question")
    void saveCompletedGame() {
        User host = register("hhost");
        User member = register("hmember");
        GameSession game = endedGame(host, "COMPLETED", 1);
        GameSession.Player registered = addPlayer(game, member.id(), "Member", 1);
        GameSession.Player guest = addPlayer(game, null, "Guest", 2);
        addAnswer(game, registered, 0, 2000, 950);
        addAnswer(game, registered, 1, 4000, 900);
        addAnswer(game, guest, 0, 6000, 850);
        guest.totalResponseMs += 20_000; // the full limit for the skipped question

        long id = history.save(game);

        Map<String, Object> row = store.db.queryForMap("SELECT * FROM games WHERE id=?", id);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(game.code, row.get("game_code").toString().trim());
        assertEquals(host.id(), ((Number) row.get("host_id")).longValue());
        assertTrue(row.get("snapshot_json").toString().contains(game.quiz.title()));

        List<Map<String, Object>> players = store.db.queryForList(
            "SELECT * FROM game_players WHERE game_id=? ORDER BY final_rank", id);
        assertEquals(2, players.size());
        assertEquals("Member", players.get(0).get("display_name"));
        assertEquals(1850, ((Number) players.get(0).get("final_score")).intValue());
        assertEquals(1, ((Number) players.get(0).get("final_rank")).intValue());
        assertEquals(26_000L, ((Number) players.get(1).get("total_response_ms")).longValue());

        assertEquals(4, count("SELECT COUNT(*) FROM player_answers a JOIN game_players p ON a.game_player_id=p.id WHERE p.game_id=?", id));
        Map<String, Object> skipped = store.db.queryForMap(
            "SELECT a.* FROM player_answers a JOIN game_players p ON a.game_player_id=p.id " +
            "WHERE p.game_id=? AND p.display_name='Guest' AND a.question_position=1", id);
        assertEquals("[]", skipped.get("selected_option_ids"));
        assertNull(skipped.get("response_ms"));
        assertEquals(0, ((Number) skipped.get("points")).intValue());
    }

    @Test
    @DisplayName("HIST-02: a game terminated during question 1 saves answers only for question 1")
    void saveTerminatedGame() {
        User host = register("thost");
        GameSession game = endedGame(host, "TERMINATED", 0);
        GameSession.Player player = addPlayer(game, null, "Early leaver", 1);
        addAnswer(game, player, 0, 3000, 925);

        long id = history.save(game);
        assertEquals("TERMINATED", store.db.queryForObject("SELECT status FROM games WHERE id=?", String.class, id));
        assertEquals(1, count("SELECT COUNT(*) FROM player_answers a JOIN game_players p ON a.game_player_id=p.id WHERE p.game_id=?", id));
    }

    @Test
    @DisplayName("HIST-03: hosts and players see their games with the right role, newest first")
    void listHistory() throws Exception {
        User host = register("lhost");
        User member = register("lmember");
        GameSession older = endedGame(host, "COMPLETED", 0);
        addPlayer(older, member.id(), "Member", 1);
        long olderId = history.save(older);
        Thread.sleep(50);
        GameSession newer = endedGame(host, "COMPLETED", 0);
        long newerId = history.save(newer);

        List<Map<String, Object>> hostList = history.list(host.id());
        assertEquals(List.of(newerId, olderId), hostList.stream().map(g -> ((Number) g.get("id")).longValue()).toList());
        assertTrue(hostList.stream().allMatch(g -> g.get("role").equals("HOST")));

        List<Map<String, Object>> memberList = history.list(member.id());
        assertEquals(1, memberList.size());
        assertEquals("PLAYER", memberList.get(0).get("role"));
    }

    @Test
    @DisplayName("HIST-06: people who did not take part, and missing IDs, get 404")
    void historyAccessControl() {
        User host = register("ahost");
        User outsider = register("outsider");
        long id = history.save(endedGame(host, "COMPLETED", 0));
        assertEquals("HOST", history.detail(id, host.id()).get("role"));
        assertEquals(404, assertThrows(ApiException.class, () -> history.detail(id, outsider.id())).status);
        assertEquals(404, assertThrows(ApiException.class, () -> history.detail(999_999_999L, host.id())).status);
    }

    @Test
    @DisplayName("HIST-08: guest players are saved without an account, and two guests can share a game")
    void guestsSaved() {
        User host = register("ghost");
        GameSession game = endedGame(host, "COMPLETED", 0);
        addPlayer(game, null, "Guest One", 1);
        addPlayer(game, null, "Guest Two", 2);
        long id = history.save(game);
        assertEquals(2, count("SELECT COUNT(*) FROM game_players WHERE game_id=? AND user_id IS NULL", id));
    }
}
