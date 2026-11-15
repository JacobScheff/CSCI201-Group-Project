package com.rally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unit and boundary tests for points = round(1000 x (1 - elapsed / (2 x limit))). */
class ScoreCalculatorTest {

    @ParameterizedTest(name = "{0}: {1} ms of {2} ms -> {3} points")
    @DisplayName("SCORE-01 to SCORE-06: correct answers earn 500 to 1,000 points by speed")
    @CsvSource({
        "SCORE-01 instant answer,         0, 20000, 1000",
        "SCORE-02 half the time,      10000, 20000,  750",
        "SCORE-03 at the limit,       20000, 20000,  500",
        "SCORE-04 past the limit,     50000, 20000,  500",
        "SCORE-05 negative elapsed,      -5, 20000, 1000",
        "SCORE-06 rounding 876.6,      1234,  5000,  877",
    })
    void correctAnswerPoints(String id, long elapsedMs, long limitMs, int expected) {
        assertEquals(expected, ScoreCalculator.score(Set.of(1L), Set.of(1L), elapsedMs, limitMs), id);
    }

    @Test
    @DisplayName("SCORE-07: a wrong answer earns 0 points even when instant")
    void wrongAnswerEarnsZero() {
        assertEquals(0, ScoreCalculator.score(Set.of(1L), Set.of(2L), 0, 20000));
    }

    @Test
    @DisplayName("SCORE-08: multi-select order does not matter")
    void multiSelectOrderDoesNotMatter() {
        int forward = ScoreCalculator.score(Set.of(1L, 3L), Set.of(1L, 3L), 0, 20000);
        int reversed = ScoreCalculator.score(Set.of(1L, 3L), Set.of(3L, 1L), 0, 20000);
        assertEquals(1000, forward);
        assertEquals(forward, reversed);
    }

    @Test
    @DisplayName("SCORE-09: partial or extra multi-select answers earn 0")
    void partialOrExtraMultiSelectEarnsZero() {
        assertEquals(0, ScoreCalculator.score(Set.of(1L, 3L), Set.of(1L), 0, 20000), "partial set");
        assertEquals(0, ScoreCalculator.score(Set.of(1L, 3L), Set.of(1L, 2L, 3L), 0, 20000), "extra option");
        assertEquals(0, ScoreCalculator.score(Set.of(1L, 3L), Set.of(), 0, 20000), "no options");
    }

    @Test
    @DisplayName("SCORE-10: faster correct answers score higher")
    void fasterAnswersScoreHigher() {
        int fast = ScoreCalculator.score(Set.of(1L), Set.of(1L), 2000, 20000);
        int slow = ScoreCalculator.score(Set.of(1L), Set.of(1L), 8000, 20000);
        assertEquals(950, fast);
        assertEquals(800, slow);
        assertTrue(fast > slow);
    }
}
