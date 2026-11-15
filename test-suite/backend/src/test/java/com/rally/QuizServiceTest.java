package com.rally;

import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/** Quiz validation, ownership, editing, deletion, and example templates. */
class QuizServiceTest extends SpringTestSupport {

    @Autowired Validator validator;

    /** Property paths that fail Bean Validation, e.g. "questions[0].timeLimitSec". */
    Set<String> violations(Quiz quiz) {
        Set<String> paths = new HashSet<>();
        validator.validate(quiz).forEach(v -> paths.add(v.getPropertyPath().toString()));
        return paths;
    }

    static Question withOptions(int count) {
        List<Option> options = IntStream.range(0, count).mapToObj(i -> option("Choice " + i, i == 0)).toList();
        return new Question(null, "How many options?", "SINGLE", 20, null, options);
    }

    static Question withLimit(int seconds) {
        return single("Timed question", seconds);
    }

    static Question typed(String type, boolean... correct) {
        List<Option> options = IntStream.range(0, correct.length).mapToObj(i -> option("Choice " + i, correct[i])).toList();
        return new Question(null, "Which ones?", type, 20, null, options);
    }

    static Question withImage(String url) {
        Question base = single("Look at this", 20);
        return new Question(null, base.text(), base.type(), base.timeLimitSec(), url, base.options());
    }

    static Set<Long> questionAndOptionIds(Quiz quiz) {
        Set<Long> ids = new HashSet<>();
        for (Question q : quiz.questions()) {
            ids.add(q.id());
            q.options().forEach(o -> ids.add(o.id()));
        }
        return ids;
    }

    @Test
    @DisplayName("QUIZ-01: a valid quiz is saved with new IDs and listed first")
    void createQuiz() {
        User owner = register("owner");
        Quiz created = quizzes.create(owner.id(), validQuiz("CSCI 201 Review"));
        assertNotNull(created.id());
        assertFalse(created.example());
        assertNotNull(created.questions().get(0).id());
        assertTrue(created.questions().get(0).options().stream().allMatch(o -> o.id() != null));
        assertEquals(created.id(), quizzes.list(owner.id()).get(0).id());
    }

    @Test
    @DisplayName("QUIZ-02: questions need 2 to 4 options")
    void optionCountBoundaries() {
        assertTrue(violations(quizOf("Q", withOptions(1))).contains("questions[0].options"));
        assertTrue(violations(quizOf("Q", withOptions(2))).isEmpty());
        assertTrue(violations(quizOf("Q", withOptions(4))).isEmpty());
        assertTrue(violations(quizOf("Q", withOptions(5))).contains("questions[0].options"));
    }

    @Test
    @DisplayName("QUIZ-03: time limits must be 5 to 120 seconds")
    void timeLimitBoundaries() {
        assertTrue(violations(quizOf("Q", withLimit(4))).contains("questions[0].timeLimitSec"));
        assertTrue(violations(quizOf("Q", withLimit(5))).isEmpty());
        assertTrue(violations(quizOf("Q", withLimit(120))).isEmpty());
        assertTrue(violations(quizOf("Q", withLimit(121))).contains("questions[0].timeLimitSec"));
    }

    @Test
    @DisplayName("QUIZ-04: a SINGLE question needs exactly one correct option")
    void singleNeedsExactlyOneCorrect() {
        User owner = register("single");
        for (Question bad : List.of(typed("SINGLE", false, false, false), typed("SINGLE", true, true, false))) {
            ApiException error = assertThrows(ApiException.class, () -> quizzes.create(owner.id(), quizOf("Q", bad)));
            assertEquals(400, error.status);
            assertTrue(error.getMessage().startsWith("Mark exactly one correct answer"));
        }
    }

    @Test
    @DisplayName("QUIZ-05: a MULTI question needs at least one correct option")
    void multiNeedsOneOrMoreCorrect() {
        User owner = register("multi");
        assertEquals(400, assertThrows(ApiException.class,
            () -> quizzes.create(owner.id(), quizOf("Q", typed("MULTI", false, false, false)))).status);
        assertDoesNotThrow(() -> quizzes.create(owner.id(), quizOf("One", typed("MULTI", true, false, false))));
        assertDoesNotThrow(() -> quizzes.create(owner.id(), quizOf("Four", typed("MULTI", true, true, true, true))));
    }

    @Test
    @DisplayName("QUIZ-06: titles, descriptions, question text, and option text have length limits")
    void textLengthBoundaries() {
        assertTrue(violations(quizOf(" ", single("Q", 20))).contains("title"));
        assertTrue(violations(quizOf("t".repeat(100), single("Q", 20))).isEmpty());
        assertTrue(violations(quizOf("t".repeat(101), single("Q", 20))).contains("title"));

        assertTrue(violations(quizOf("Q", single("q".repeat(300), 20))).isEmpty());
        assertTrue(violations(quizOf("Q", single("q".repeat(301), 20))).contains("questions[0].text"));

        Question longOption = new Question(null, "Q", "SINGLE", 20, null,
            List.of(option("o".repeat(101), true), option("Fine", false)));
        Question maxOption = new Question(null, "Q", "SINGLE", 20, null,
            List.of(option("o".repeat(100), true), option("Fine", false)));
        assertTrue(violations(quizOf("Q", maxOption)).isEmpty());
        assertTrue(violations(quizOf("Q", longOption)).contains("questions[0].options[0].text"));

        Quiz maxDescription = new Quiz(null, "Q", "d".repeat(2000), false, List.of(single("Q", 20)));
        Quiz longDescription = new Quiz(null, "Q", "d".repeat(2001), false, List.of(single("Q", 20)));
        assertTrue(violations(maxDescription).isEmpty());
        assertTrue(violations(longDescription).contains("description"));
    }

    @Test
    @DisplayName("QUIZ-07: a quiz needs 1 to 50 questions")
    void questionCountBoundaries() {
        assertTrue(violations(new Quiz(null, "Q", "", false, List.of())).contains("questions"));
        assertTrue(violations(quizOf("Q", single("Q", 20))).isEmpty());
        List<Question> fifty = Stream.generate(() -> single("Q", 20)).limit(50).toList();
        List<Question> fiftyOne = Stream.generate(() -> single("Q", 20)).limit(51).toList();
        assertTrue(violations(new Quiz(null, "Q", "", false, fifty)).isEmpty());
        assertTrue(violations(new Quiz(null, "Q", "", false, fiftyOne)).contains("questions"));
    }

    @Test
    @DisplayName("QUIZ-08: only SINGLE and MULTI question types are accepted")
    void unknownTypeRejected() {
        assertTrue(violations(quizOf("Q", typed("TRUE_FALSE", true, false))).contains("questions[0].type"));
        assertTrue(violations(quizOf("Q", typed("SINGLE", true, false))).isEmpty(), "true/false is SINGLE with two options");
    }

    @Test
    @DisplayName("QUIZ-09: question images must be uploaded images")
    void imageUrlsMustBeUploads() {
        User owner = register("images");
        assertDoesNotThrow(() -> quizzes.create(owner.id(),
            quizOf("Q", withImage("/images/3f1c2b4a-1111-2222-3333-444455556666.png"))));
        for (String url : List.of("https://evil.example/x.png", "/images/../secret.png")) {
            ApiException error = assertThrows(ApiException.class, () -> quizzes.create(owner.id(), quizOf("Q", withImage(url))), url);
            assertEquals("Please use an uploaded question image.", error.getMessage());
        }
    }

    @Test
    @DisplayName("QUIZ-10: other users get 404 for a quiz they do not own, and the quiz is unchanged")
    void ownershipEnforced() {
        User owner = register("owner");
        User intruder = register("intruder");
        Quiz quiz = quizzes.create(owner.id(), validQuiz("Private"));

        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.owned(quiz.id(), intruder.id())).status);
        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.update(quiz.id(), intruder.id(), validQuiz("Hacked"))).status);
        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.delete(quiz.id(), intruder.id())).status);
        assertEquals("Private", quizzes.owned(quiz.id(), owner.id()).title());
    }

    @Test
    @DisplayName("QUIZ-11: the owner can replace a quiz's title and questions")
    void updateQuiz() {
        User owner = register("editor");
        Quiz quiz = quizzes.create(owner.id(), validQuiz("Before"));
        Quiz updated = quizzes.update(quiz.id(), owner.id(), quizOf("After", single("First?", 30), single("Second?", 45)));

        assertEquals(quiz.id(), updated.id());
        assertEquals("After", updated.title());
        assertEquals(List.of("First?", "Second?"), updated.questions().stream().map(Question::text).toList());
        assertEquals(List.of(30, 45), updated.questions().stream().map(Question::timeLimitSec).toList());
        assertFalse(updated.questions().stream().anyMatch(q -> q.text().equals("What is 2 + 2?")));
    }

    @Test
    @DisplayName("QUIZ-12: a deleted quiz disappears from the list and returns 404")
    void deleteQuiz() {
        User owner = register("deleter");
        Quiz quiz = quizzes.create(owner.id(), validQuiz("Doomed"));
        quizzes.delete(quiz.id(), owner.id());
        assertTrue(quizzes.list(owner.id()).stream().noneMatch(q -> q.id().equals(quiz.id())));
        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.owned(quiz.id(), owner.id())).status);
    }

    @Test
    @DisplayName("QUIZ-13: the list shows only the caller's quizzes, most recently updated first")
    void listOwnQuizzesOnly() throws Exception {
        User owner = register("lister");
        User other = register("neighbor");
        quizzes.create(other.id(), validQuiz("Not mine"));
        Quiz a = quizzes.create(owner.id(), validQuiz("A"));
        Thread.sleep(50);
        Quiz b = quizzes.create(owner.id(), validQuiz("B"));
        Thread.sleep(50);
        quizzes.update(a.id(), owner.id(), validQuiz("A edited"));

        List<Quiz> list = quizzes.list(owner.id());
        assertEquals(List.of(a.id(), b.id()), list.stream().map(Quiz::id).toList());
        assertTrue(list.stream().noneMatch(Quiz::example));
    }

    @Test
    @DisplayName("EX-01: three example quizzes are available with their questions")
    void examplesListed() {
        List<Quiz> examples = quizzes.examples();
        assertEquals(3, examples.size());
        for (Quiz example : examples) {
            assertTrue(example.example());
            assertFalse(example.questions().isEmpty());
            assertTrue(example.questions().stream().allMatch(q -> q.options().size() >= 2));
        }
    }

    @Test
    @DisplayName("EX-03 and EX-04: cloning makes an independent copy owned by the caller")
    void cloneExample() {
        User owner = register("cloner");
        Quiz example = quizzes.examples().get(0);
        Quiz copy = quizzes.cloneExample(example.id(), owner.id());

        assertNotEquals(example.id(), copy.id());
        assertFalse(copy.example());
        assertEquals(example.title(), copy.title());
        assertEquals(example.questions().stream().map(Question::text).toList(), copy.questions().stream().map(Question::text).toList());
        Set<Long> shared = new HashSet<>(questionAndOptionIds(example));
        shared.retainAll(questionAndOptionIds(copy));
        assertTrue(shared.isEmpty(), "every question and option has a new ID");
        assertEquals(copy.id(), quizzes.owned(copy.id(), owner.id()).id());

        quizzes.update(copy.id(), owner.id(), validQuiz("My version"));
        Quiz exampleAfter = quizzes.examples().stream().filter(q -> q.id().equals(example.id())).findFirst().orElseThrow();
        assertEquals(example, exampleAfter, "EX-04: editing the copy leaves the example unchanged");
    }

    @Test
    @DisplayName("EX-05: only example quizzes can be cloned")
    void cloneRegularQuizRejected() {
        User owner = register("regular");
        Quiz quiz = quizzes.create(owner.id(), validQuiz("Regular"));
        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.cloneExample(quiz.id(), register("taker").id())).status);
    }

    @Test
    @DisplayName("EX-06: examples cannot be edited or deleted through the API")
    void examplesAreReadOnly() {
        User user = register("vandal");
        Quiz example = quizzes.examples().get(1);
        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.update(example.id(), user.id(), validQuiz("Changed"))).status);
        assertEquals(404, assertThrows(ApiException.class, () -> quizzes.delete(example.id(), user.id())).status);
        assertEquals(3, quizzes.examples().size());
    }

    @Test
    @DisplayName("EX-07: running the example seeder again does not duplicate examples")
    void seederIsIdempotent() throws Exception {
        new Examples(quizzes).run(null);
        assertEquals(3, quizzes.examples().size());
    }
}
