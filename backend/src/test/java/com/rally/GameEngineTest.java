package com.rally;

import org.junit.jupiter.api.*;
import org.springframework.web.socket.WebSocketSession;
import java.util.*;
import java.util.concurrent.*;
import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameEngineTest {
    GameManager manager;
    GameHistoryService history;
    Quiz quiz;
    @BeforeEach void setup() {
        var quizzes=mock(QuizService.class);
        history=mock(GameHistoryService.class);
        when(history.save(any())).thenReturn(42L);
        var json=new Json(new com.fasterxml.jackson.databind.ObjectMapper());
        manager=new GameManager(quizzes,history,json);
        var options=List.of(new Option(1L,"Correct",true),new Option(2L,"Wrong",false));
        quiz=new Quiz(1L,"Race conditions","",false,List.of(new Question(1L,"First?","SINGLE",120,null,options),new Question(2L,"Second?","SINGLE",120,null,options)));
        when(quizzes.owned(anyLong(),anyLong())).thenReturn(quiz);
    }
    @AfterEach void shutdown() { manager.stop(); }
    WebSocketSession socket() {
        var socket=mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        return socket;
    }
    @Test void scoresUseExactSetsAndClampedServerTime() {
        assertEquals(1000,ScoreCalculator.score(Set.of(1L),Set.of(1L),0,20000));
        assertEquals(750,ScoreCalculator.score(Set.of(1L),Set.of(1L),10000,20000));
        assertEquals(500,ScoreCalculator.score(Set.of(1L),Set.of(1L),50000,20000));
        assertEquals(1000,ScoreCalculator.score(Set.of(1L),Set.of(1L),-5,20000));
        assertEquals(0,ScoreCalculator.score(Set.of(1L,2L),Set.of(1L),0,20000));
        assertEquals(0,ScoreCalculator.score(Set.of(1L),Set.of(1L,2L),0,20000));
    }
    @Test void sixtyConcurrentSubmissionsStayInTheirOwnGames() throws Exception {
        var games=new ArrayList<GameSession>();
        var hosts=new ArrayList<WebSocketSession>();
        var sockets=new ArrayList<List<WebSocketSession>>();
        for(int i=0;i<3;i++) {
            var user=new User(i+1,"host"+i,"host"+i+"@example.com");
            var game=manager.create(1,user);games.add(game);
            var host=socket();hosts.add(host);game.connectHost(host,user);
            var group=new ArrayList<WebSocketSession>();
            for(int j=0;j<20;j++) { var player=socket();group.add(player);game.join(player,"Player "+j,null); }
            sockets.add(group);game.command(host,"START",-1,Set.of());
        }
        try(var pool=Executors.newFixedThreadPool(60)) {
            var barrier=new CyclicBarrier(60);
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<3;i++) for(int j=0;j<20;j++) {
                final int g=i,p=j;
                tasks.add(pool.submit(()-> { barrier.await(5,TimeUnit.SECONDS);games.get(g).command(sockets.get(g).get(p),"ANSWER",0,Set.of(g==1?2L:1L));return null; }));
            }
            for(var task:tasks) task.get(10,TimeUnit.SECONDS);
        }
        for(int i=0;i<3;i++) {
            var game=games.get(i);
            assertEquals("REVEAL",game.phase);
            assertEquals(20,game.players.size());
            for(var player:game.players.values()) {
                assertEquals(1,player.answers.size());
                if(i==1) assertEquals(0,player.score); else assertTrue(player.score>=500);
            }
            int total=game.players.values().stream().mapToInt(p->p.score).sum();
            game.closeQuestion(0);
            assertEquals(total,game.players.values().stream().mapToInt(p->p.score).sum(),"Closing twice must not double-score");
            game.command(hosts.get(i),"NEXT",0,Set.of());
            game.closeQuestion(0);
            assertEquals("QUESTION",game.phase,"An old timer must not close the new question");
            assertEquals(1,game.index);
        }
        assertThrows(ApiException.class,()->manager.create(1,new User(10,"fourth","fourth@example.com")));
    }
    @Test void onlyFirstAnswerCountsAndHostCommandsAreProtected() {
        var user=new User(1,"host","host@example.com");
        var game=manager.create(1,user);var host=socket();game.connectHost(host,user);
        var first=socket();var second=socket();game.join(first,"First",null);game.join(second,"Second",null);
        assertThrows(ApiException.class,()->game.command(first,"START",-1,Set.of()));
        game.command(host,"START",-1,Set.of());
        game.command(first,"ANSWER",0,Set.of(1L));
        assertThrows(ApiException.class,()->game.command(first,"ANSWER",0,Set.of(2L)));
        game.command(host,"NEXT",0,Set.of());
        var player=game.playerFor(first);
        assertTrue(player.score>=500);
        assertEquals(0,game.playerFor(second).score);
        assertThrows(ApiException.class,()->game.command(second,"ANSWER",0,Set.of(1L)));
    }
    @Test void registeredPlayersReconnectAndGuestsLoseTheirScores() {
        var hostUser=new User(1,"host","host@example.com");
        var member=new User(2,"member","member@example.com");
        var game=manager.create(1,hostUser);var host=socket();game.connectHost(host,hostUser);
        var registered=socket();var guest=socket();game.join(registered,"Member",member);game.join(guest,"Guest",null);
        assertThrows(ApiException.class,()->game.join(socket(),"Duplicate",member));
        game.command(host,"START",-1,Set.of());game.command(registered,"ANSWER",0,Set.of(1L));game.command(guest,"ANSWER",0,Set.of(1L));
        int score=game.playerFor(registered).score;
        game.disconnect(registered);game.disconnect(guest);
        assertEquals(1,game.players.size());
        var reconnect=socket();game.join(reconnect,"Ignored rename",member);
        assertEquals(score,game.playerFor(reconnect).score);
        var guestAgain=socket();game.join(guestAgain,"Guest",null);
        assertEquals(0,game.playerFor(guestAgain).score);
        game.disconnect(host);
        assertEquals("TERMINATED",game.phase);assertFalse(manager.games.containsKey(game.code));
        verify(history,times(1)).save(game);
    }
    @Test void correctAnswersAreNotIncludedBeforeReveal() {
        var hostUser=new User(1,"host","host@example.com");var game=manager.create(1,hostUser);
        var host=socket();var player=socket();game.connectHost(host,hostUser);game.join(player,"Player",null);
        game.command(host,"START",-1,Set.of());
        var state=game.view(player);
        assertFalse(state.containsKey("correctOptionIds"));
        String publicState=manager.json.write(state);
        assertFalse(publicState.contains("\"correct\""));
        game.command(player,"ANSWER",0,Set.of(1L));
        assertTrue(game.view(player).containsKey("correctOptionIds"));
    }
    @Test void serverDeadlineRejectsLateAnswersEvenBeforeTimerRuns() {
        var hostUser=new User(1,"host","host@example.com");var game=manager.create(1,hostUser);
        var host=socket();var player=socket();game.connectHost(host,hostUser);game.join(player,"Player",null);game.command(host,"START",-1,Set.of());
        game.questionStartedNanos=System.nanoTime()-TimeUnit.SECONDS.toNanos(121);
        assertThrows(ApiException.class,()->game.command(player,"ANSWER",0,Set.of(1L)));
        assertTrue(game.playerFor(player).answers.isEmpty());
    }
}
