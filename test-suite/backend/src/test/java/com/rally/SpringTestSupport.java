package com.rally;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.rally.Models.*;

/**
 * Starts the real Spring Boot application on a random port with an in-memory H2 database
 * in PostgreSQL mode. Every subclass shares one application context, so tests create
 * uniquely named users instead of relying on an empty database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:rallysuite;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "app.images=./target/test-images"
})
abstract class SpringTestSupport {
    static final String PASSWORD = "Correct-Horse-2026";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired AuthService auth;
    @Autowired QuizService quizzes;
    @Autowired GameManager games;
    @Autowired GameHistoryService history;
    @Autowired Store store;
    @Autowired Json json;

    /** A unique, valid username such as {@code owner_7_3fa2c1}. */
    static String uniqueName(String prefix) {
        return prefix + "_" + COUNTER.incrementAndGet() + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    User register(String prefix) {
        String name = uniqueName(prefix);
        return (User) auth.register(new Register(name, name + "@example.com", PASSWORD)).get("user");
    }

    static Option option(String text, boolean correct) {
        return new Option(null, text, correct);
    }

    static Question single(String text, int limitSec) {
        return new Question(null, text, "SINGLE", limitSec, null, List.of(
            option("Correct", true), option("Wrong A", false), option("Wrong B", false), option("Wrong C", false)));
    }

    static Quiz quizOf(String title, Question... questions) {
        return new Quiz(null, title, "Made by a test", false, List.of(questions));
    }

    static Quiz validQuiz(String title) {
        return quizOf(title, single("What is 2 + 2?", 20));
    }
}
