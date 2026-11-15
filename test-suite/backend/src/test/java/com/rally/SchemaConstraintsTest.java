package com.rally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** The database itself rejects bad data, independent of the Java validation in front of it. */
class SchemaConstraintsTest extends SpringTestSupport {

    long user() {
        String name = uniqueName("db");
        return store.insert("INSERT INTO users(username,email,password_hash) VALUES(?,?,?)", name, name + "@example.com", "x".repeat(60));
    }

    long quiz(long owner) {
        return store.insert("INSERT INTO quizzes(owner_id,title,description,is_example) VALUES(?,?,?,?)", owner, "Schema quiz", "", false);
    }

    long question(long quiz, int position, String type, int limit) {
        return store.insert("INSERT INTO questions(quiz_id,position,text,type,time_limit_sec,image_url) VALUES(?,?,?,?,?,?)",
            quiz, position, "Question?", type, limit, null);
    }

    long option(long question, int position) {
        return store.insert("INSERT INTO answer_options(question_id,position,text,is_correct) VALUES(?,?,?,?)",
            question, position, "Choice", position == 0);
    }

    long game(Long quiz, long host) {
        Timestamp now = Timestamp.from(Instant.now());
        return store.insert("INSERT INTO games(quiz_id,host_id,game_code,status,started_at,ended_at,snapshot_json) VALUES(?,?,?,?,?,?,?)",
            quiz, host, "123456", "COMPLETED", now, now, "{}");
    }

    long player(long game, Long user, String name) {
        return store.insert("INSERT INTO game_players(game_id,user_id,display_name,final_score,final_rank,total_response_ms) VALUES(?,?,?,?,?,?)",
            game, user, name, 0, 1, 0L);
    }

    int count(String sql, Object... args) {
        return store.db.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("DB-01: usernames and emails are unique")
    void uniqueAccounts() {
        String name = uniqueName("unique");
        store.insert("INSERT INTO users(username,email,password_hash) VALUES(?,?,?)", name, name + "@example.com", "hash");
        assertThrows(DataIntegrityViolationException.class, () ->
            store.insert("INSERT INTO users(username,email,password_hash) VALUES(?,?,?)", name, "new_" + name + "@example.com", "hash"));
        assertThrows(DataIntegrityViolationException.class, () ->
            store.insert("INSERT INTO users(username,email,password_hash) VALUES(?,?,?)", "new_" + name, name + "@example.com", "hash"));
    }

    @Test
    @DisplayName("DB-02: time limits outside 5 to 120 seconds are rejected")
    void timeLimitCheck() {
        long quiz = quiz(user());
        assertThrows(DataIntegrityViolationException.class, () -> question(quiz, 0, "SINGLE", 4));
        assertThrows(DataIntegrityViolationException.class, () -> question(quiz, 1, "SINGLE", 121));
        assertDoesNotThrow(() -> question(quiz, 2, "SINGLE", 5));
        assertDoesNotThrow(() -> question(quiz, 3, "SINGLE", 120));
    }

    @Test
    @DisplayName("DB-03: option positions must be 0 to 3 and unique within a question")
    void optionPositionChecks() {
        long question = question(quiz(user()), 0, "SINGLE", 20);
        assertThrows(DataIntegrityViolationException.class, () -> option(question, 4));
        option(question, 0);
        assertThrows(DataIntegrityViolationException.class, () -> option(question, 0));
    }

    @Test
    @DisplayName("DB-04: question types other than SINGLE and MULTI are rejected")
    void questionTypeCheck() {
        long quiz = quiz(user());
        assertThrows(DataIntegrityViolationException.class, () -> question(quiz, 0, "TRUE_FALSE", 20));
    }

    @Test
    @DisplayName("DB-05: deleting a quiz deletes its questions and options and keeps its game history")
    void quizDeleteCascades() {
        long owner = user();
        long quiz = quiz(owner);
        long question = question(quiz, 0, "SINGLE", 20);
        option(question, 0);
        long game = game(quiz, owner);

        store.db.update("DELETE FROM quizzes WHERE id=?", quiz);
        assertEquals(0, count("SELECT COUNT(*) FROM questions WHERE quiz_id=?", quiz));
        assertEquals(0, count("SELECT COUNT(*) FROM answer_options WHERE question_id=?", question));
        assertEquals(1, count("SELECT COUNT(*) FROM games WHERE id=? AND quiz_id IS NULL", game));
    }

    @Test
    @DisplayName("DB-06: deleting a game deletes its players and answers")
    void gameDeleteCascades() {
        long host = user();
        long game = game(null, host);
        long player = player(game, null, "Guest");
        store.insert("INSERT INTO player_answers(game_player_id,question_position,selected_option_ids,response_ms,points) VALUES(?,?,?,?,?)",
            player, 0, "[]", null, 0);

        store.db.update("DELETE FROM games WHERE id=?", game);
        assertEquals(0, count("SELECT COUNT(*) FROM game_players WHERE game_id=?", game));
        assertEquals(0, count("SELECT COUNT(*) FROM player_answers WHERE game_player_id=?", player));
    }

    @Test
    @DisplayName("DB-07: a registered user appears at most once per game, while guests may repeat")
    void onePlayerRowPerUser() {
        long host = user();
        long member = user();
        long game = game(null, host);
        player(game, member, "Member");
        assertThrows(DataIntegrityViolationException.class, () -> player(game, member, "Member again"));
        assertDoesNotThrow(() -> player(game, null, "Guest One"));
        assertDoesNotThrow(() -> player(game, null, "Guest Two"));
    }
}
