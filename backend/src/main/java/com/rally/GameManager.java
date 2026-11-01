package com.rally;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.concurrent.*;
import static com.rally.Models.*;

@Service
public class GameManager {
    final ConcurrentHashMap<String,GameSession> games=new ConcurrentHashMap<>();
    final ScheduledExecutorService timers=Executors.newScheduledThreadPool(6);
    final SecureRandom random=new SecureRandom();
    final QuizService quizzes;
    final GameHistoryService history;
    final Json json;
    public GameManager(QuizService quizzes,GameHistoryService history,Json json) {
        this.quizzes=quizzes; this.history=history; this.json=json;
        timers.scheduleAtFixedRate(()->games.values().forEach(GameSession::checkConnections),10,10,TimeUnit.SECONDS);
    }
    public synchronized GameSession create(long quizId,User host) {
        if(games.size()>=3) throw new ApiException(409,"CAPACITY","All three game rooms are in use. Please try again when one finishes.");
        if(games.values().stream().anyMatch(g->g.hostId==host.id())) throw new ApiException(409,"ALREADY_HOSTING","You already have a live game. End it before hosting another.");
        Quiz quiz=quizzes.owned(quizId,host.id());
        for(int i=0;i<10;i++) {
            String code=Integer.toString(random.nextInt(900000)+100000);
            var session=new GameSession(code,host.id(),quiz,this);
            if(games.putIfAbsent(code,session)==null) return session;
        }
        throw new ApiException(503,"CODE_UNAVAILABLE","Could not create a room. Please try again.");
    }
    public GameSession get(String code) {
        var game=games.get(code);
        if(game==null) throw new ApiException(404,"GAME_NOT_FOUND","No live game has that code. Check the code with your host.");
        return game;
    }
    @PreDestroy public void stop() { timers.shutdownNow(); }
}
