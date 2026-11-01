package com.rally;

import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import static com.rally.Models.*;
import static com.rally.GameHistoryService.row;

public class GameSession {
    final String code;
    final long hostId;
    final Quiz quiz;
    final GameManager manager;
    final ReentrantLock lock=new ReentrantLock();
    final LinkedHashMap<String,Player> players=new LinkedHashMap<>();
    final Map<String,Long> heartbeats=new HashMap<>();
    final long createdAt=System.currentTimeMillis();
    Instant startedAt=Instant.now();
    WebSocketSession host;
    String phase="LOBBY",reason="";
    int index=-1;
    long questionStartedNanos,deadline;
    Long historyId;
    ScheduledFuture<?> timer;
    boolean historySaved;
    static final class Player {
        final String id=UUID.randomUUID().toString();
        final Long userId;
        final String name;
        final long order;
        WebSocketSession socket;
        int score;
        long totalResponseMs;
        final Map<Integer,Answer> answers=new HashMap<>();
        Player(Long userId,String name,long order,WebSocketSession socket) { this.userId=userId; this.name=name; this.order=order; this.socket=socket; }
    }
    record Answer(Set<Long> optionIds,long responseMs,int points) {}
    GameSession(String code,long hostId,Quiz quiz,GameManager manager) { this.code=code; this.hostId=hostId; this.quiz=quiz; this.manager=manager; }
    boolean terminal() { return phase.equals("COMPLETED")||phase.equals("TERMINATED"); }
    public void connectHost(WebSocketSession socket,User user) {
        lock.lock(); try {
            if(user.id()!=hostId) throw new ApiException(403,"FORBIDDEN","Only this game's host can control it.");
            if(host!=null&&host.isOpen()) throw new ApiException(409,"HOST_CONNECTED","This game already has a host connection.");
            if(terminal()) throw ApiException.bad("This game has ended.");
            host=socket; touch(socket); broadcast("LOBBY_UPDATE");
        } finally { lock.unlock(); }
    }
    public void join(WebSocketSession socket,String name,User user) {
        lock.lock(); try {
            if(terminal()) throw ApiException.bad("This game has ended.");
            if(name==null||name.isBlank()||name.trim().length()>20) throw ApiException.bad("Choose a display name from 1 to 20 characters.");
            if(hostId==(user==null?-1:user.id())) throw ApiException.bad("The host controls the game from the host screen. Join as a guest in another browser to play.");
            if(playerFor(socket)!=null||host==socket) throw ApiException.bad("This connection has already joined.");
            Player existing=user==null?null:players.values().stream().filter(p->Objects.equals(p.userId,user.id())).findFirst().orElse(null);
            if(existing!=null) {
                if(existing.socket!=null&&existing.socket.isOpen()) throw new ApiException(409,"ALREADY_JOINED","You're already playing this game in another tab.");
                existing.socket=socket; touch(socket); broadcast("STATE_SYNC"); return;
            }
            if(players.size()>=20) throw new ApiException(409,"GAME_FULL","This room has reached its 20-player limit.");
            String display=name.trim();
            if(players.values().stream().anyMatch(p->p.name.equalsIgnoreCase(display))) throw new ApiException(409,"NAME_TAKEN","That display name is already in this game.");
            var player=new Player(user==null?null:user.id(),display,System.nanoTime(),socket);
            players.put(player.id,player); touch(socket); broadcast("LOBBY_UPDATE");
        } finally { lock.unlock(); }
    }
    Player playerFor(WebSocketSession socket) { return players.values().stream().filter(p->p.socket==socket).findFirst().orElse(null); }
    public void command(WebSocketSession socket,String type,int questionIndex,Set<Long> optionIds) {
        lock.lock(); try {
            if(host!=socket&&playerFor(socket)==null) throw new ApiException(403,"NOT_JOINED","Join the game first.");
            touch(socket);
            if(type.equals("PING")) { send(socket,row("type","PONG")); return; }
            if(terminal()) throw ApiException.bad("This game has ended.");
            if(type.equals("ANSWER")) { answer(socket,questionIndex,optionIds); return; }
            if(host!=socket) throw new ApiException(403,"HOST_ONLY","Only the host can control the game.");
            switch(type) {
                case "START" -> {
                    if(!phase.equals("LOBBY")) throw ApiException.bad("The game has already started.");
                    if(players.values().stream().noneMatch(p->p.socket!=null&&p.socket.isOpen())) throw ApiException.bad("Wait for at least one player before starting.");
                    startedAt=Instant.now(); nextQuestion();
                }
                case "NEXT" -> {
                    if(questionIndex!=index) throw ApiException.bad("This question has already advanced.");
                    if(phase.equals("QUESTION")) closeQuestion(index);
                    else if(phase.equals("REVEAL")) nextQuestion();
                    else throw ApiException.bad("Start the game first.");
                }
                case "END" -> finish("TERMINATED","The host ended the game.");
                default -> throw ApiException.bad("Unknown game message.");
            }
        } finally { lock.unlock(); }
    }
    void answer(WebSocketSession socket,int questionIndex,Set<Long> selected) {
        Player player=playerFor(socket);
        if(player==null) throw ApiException.bad("Hosts cannot submit answers.");
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-questionStartedNanos);
        if(!phase.equals("QUESTION")||questionIndex!=index||elapsed>=quiz.questions().get(index).timeLimitSec()*1000L) throw ApiException.bad("This question is closed.");
        var question=quiz.questions().get(index);
        var valid=question.options().stream().map(Option::id).collect(Collectors.toSet());
        if(selected.isEmpty()||!valid.containsAll(selected)||("SINGLE".equals(question.type())&&selected.size()!=1)) throw ApiException.bad("Choose valid answer options.");
        if(player.answers.containsKey(index)) throw new ApiException(409,"ALREADY_ANSWERED","Your first answer is already locked in.");
        var expected=question.options().stream().filter(Option::correct).map(Option::id).collect(Collectors.toSet());
        int points=ScoreCalculator.score(expected,selected,elapsed,question.timeLimitSec()*1000L);
        player.answers.put(index,new Answer(Set.copyOf(selected),elapsed,points));
        send(socket,row("type","ANSWER_ACK","accepted",true,"state",view(socket)));
        broadcast("ANSWER_COUNT");
        if(players.values().stream().filter(p->p.socket!=null&&p.socket.isOpen()).allMatch(p->p.answers.containsKey(index))) closeQuestion(index);
    }
    void nextQuestion() {
        if(timer!=null) timer.cancel(false);
        if(index+1>=quiz.questions().size()) { finish("COMPLETED",""); return; }
        index++; phase="QUESTION";
        questionStartedNanos=System.nanoTime();
        long limit=quiz.questions().get(index).timeLimitSec()*1000L;
        deadline=System.currentTimeMillis()+limit;
        int current=index;
        timer=manager.timers.schedule(()-> { lock.lock(); try { closeQuestion(current); } finally { lock.unlock(); } },limit,TimeUnit.MILLISECONDS);
        broadcast("QUESTION");
    }
    void closeQuestion(int questionIndex) {
        if(!phase.equals("QUESTION")||index!=questionIndex) return;
        phase="REVEAL";
        if(timer!=null) timer.cancel(false);
        for(var player:players.values()) {
            Answer answer=player.answers.get(index);
            if(answer!=null) { player.score+=answer.points(); player.totalResponseMs+=answer.responseMs(); }
            else player.totalResponseMs+=quiz.questions().get(index).timeLimitSec()*1000L;
        }
        broadcast("REVEAL");
        int closed=index;
        timer=manager.timers.schedule(()-> {
            lock.lock(); try { if(phase.equals("REVEAL")&&index==closed) nextQuestion(); } finally { lock.unlock(); }
        },4,TimeUnit.SECONDS);
    }
    void finish(String status,String reason) {
        if(terminal()) return;
        if(phase.equals("QUESTION")) closeQuestion(index);
        if(timer!=null) timer.cancel(false);
        phase=status; this.reason=reason;
        persist();
        broadcast(status.equals("COMPLETED")?"GAME_OVER":"GAME_TERMINATED");
        manager.games.remove(code,this);
    }
    void persist() {
        try { historyId=manager.history.save(this); historySaved=true; }
        catch(Exception e) {
            LoggerFactory.getLogger(GameSession.class).error("History persistence failed for {}; retrying",code,e);
            manager.timers.schedule(()-> { lock.lock(); try { if(!historySaved) { persist(); if(historySaved) broadcast("STATE_SYNC"); } } finally { lock.unlock(); } },5,TimeUnit.SECONDS);
        }
    }
    List<Player> ranked() {
        return players.values().stream().sorted(Comparator.comparingInt((Player p)->p.score).reversed().thenComparingLong(p->p.totalResponseMs).thenComparingLong(p->p.order)).toList();
    }
    Map<String,Object> view(WebSocketSession socket) {
        var ranked=ranked();
        var leaderboard=new ArrayList<Map<String,Object>>();
        for(int i=0;i<ranked.size();i++) {
            var p=ranked.get(i);
            leaderboard.add(row("id",p.id,"name",p.name,"score",p.score,"rank",i+1,"connected",p.socket!=null&&p.socket.isOpen()));
        }
        var state=row("code",code,"title",quiz.title(),"status",phase,"reason",reason,"host",socket==host,
            "questionIndex",index,"questionCount",quiz.questions().size(),"serverNow",System.currentTimeMillis(),"deadline",deadline,
            "leaderboard",leaderboard,"answerCount",players.values().stream().filter(p->p.answers.containsKey(index)).count(),
            "historyId",historyId,"historySaved",historySaved);
        if(index>=0) {
            var q=quiz.questions().get(index);
            state.put("question",row("text",q.text(),"type",q.type(),"imageUrl",q.imageUrl(),"timeLimitSec",q.timeLimitSec(),"options",q.options().stream().map(o->row("id",o.id(),"text",o.text())).toList()));
            if(!phase.equals("QUESTION")) state.put("correctOptionIds",q.options().stream().filter(Option::correct).map(Option::id).toList());
        }
        var p=playerFor(socket);
        if(p!=null) {
            var answer=p.answers.get(index);
            state.put("me",row("id",p.id,"name",p.name,"score",p.score,"rank",ranked.indexOf(p)+1,"submitted",answer!=null,
                "selectedOptionIds",answer==null?List.of():answer.optionIds(),"points",phase.equals("QUESTION")?null:answer==null?0:answer.points()));
        }
        return state;
    }
    void broadcast(String type) {
        if(host!=null) send(host,row("type",type,"state",view(host)));
        for(var p:players.values()) if(p.socket!=null) send(p.socket,row("type",type,"state",view(p.socket)));
    }
    void send(WebSocketSession socket,Map<String,Object> payload) {
        if(!socket.isOpen()) return;
        try { socket.sendMessage(new TextMessage(manager.json.write(payload))); }
        catch(IOException|IllegalStateException e) { heartbeats.put(socket.getId(),0L); }
    }
    void touch(WebSocketSession socket) { heartbeats.put(socket.getId(),System.currentTimeMillis()); }
    public void disconnect(WebSocketSession socket) {
        lock.lock(); try {
            heartbeats.remove(socket.getId());
            if(terminal()) return;
            if(host==socket) { finish("TERMINATED","The host disconnected. This game has ended."); return; }
            var p=playerFor(socket);
            if(p==null) return;
            p.socket=null;
            if(p.userId==null) players.remove(p.id);
            broadcast("LOBBY_UPDATE");
        } finally { lock.unlock(); }
    }
    public void checkConnections() {
        lock.lock(); try {
            long now=System.currentTimeMillis();
            if(host==null&&now-createdAt>60000) { finish("TERMINATED","The host did not connect."); return; }
            var sockets=new ArrayList<WebSocketSession>();
            if(host!=null) sockets.add(host);
            players.values().forEach(p->{if(p.socket!=null) sockets.add(p.socket);});
            for(var socket:sockets) if(!socket.isOpen()||now-heartbeats.getOrDefault(socket.getId(),0L)>45000) {
                disconnect(socket);
                try { socket.close(); } catch(IOException ignored) {}
            }
        } finally { lock.unlock(); }
    }
}
