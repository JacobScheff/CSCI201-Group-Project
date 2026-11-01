package com.rally;

import java.util.Set;

public final class ScoreCalculator {
    private ScoreCalculator() {}
    public static int score(Set<Long> expected,Set<Long> selected,long responseMs,long timeLimitMs) {
        if(!expected.equals(selected)) return 0;
        long elapsed=Math.clamp(responseMs,0,timeLimitMs);
        return (int)Math.round(1000.0*(1-elapsed/(2.0*timeLimitMs)));
    }
}
