package com.rally;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class GameHistoryService {
    final Store store;
    final Json json;
    public GameHistoryService(Store store,Json json) { this.store=store; this.json=json; }
    @Transactional
    public long save(GameSession game) {
        // The quiz may have been edited or deleted during play; the immutable snapshot remains complete.
        Long quizId=store.db.queryForObject("SELECT COUNT(*) FROM quizzes WHERE id=?",Integer.class,game.quiz.id())>0?game.quiz.id():null;
        long id=store.insert("INSERT INTO games(quiz_id,host_id,game_code,status,started_at,ended_at,snapshot_json) VALUES(?,?,?,?,?,?,?)",
            quizId,game.hostId,game.code,game.phase,Timestamp.from(game.startedAt),Timestamp.from(Instant.now()),json.write(game.quiz));
        var ranked=game.ranked();
        for(int i=0;i<ranked.size();i++) {
            var player=ranked.get(i);
            long playerId=store.insert("INSERT INTO game_players(game_id,user_id,display_name,final_score,final_rank,total_response_ms) VALUES(?,?,?,?,?,?)",
                id,player.userId,player.name,player.score,i+1,player.totalResponseMs);
            for(int question=0;question<=game.index;question++) {
                var answer=player.answers.get(question);
                store.insert("INSERT INTO player_answers(game_player_id,question_position,selected_option_ids,response_ms,points) VALUES(?,?,?,?,?)",
                    playerId,question,json.write(answer==null?List.of():answer.optionIds()),answer==null?null:answer.responseMs(),answer==null?0:answer.points());
            }
        }
        return id;
    }
    public List<Map<String,Object>> list(long user) {
        return store.db.query("SELECT g.* FROM games g WHERE g.host_id=? OR EXISTS (SELECT 1 FROM game_players p WHERE p.game_id=g.id AND p.user_id=?) ORDER BY g.ended_at DESC",
            (r,i)-> {
                try {
                    var quiz=json.mapper.readTree(r.getString("snapshot_json"));
                    return row("id",r.getLong("id"),"title",quiz.path("title").asText(),"status",r.getString("status"),"endedAt",r.getTimestamp("ended_at").toInstant().toString(),"role",r.getLong("host_id")==user?"HOST":"PLAYER");
                } catch(Exception e) { throw new IllegalStateException(e); }
            },user,user);
    }
    public Map<String,Object> detail(long id,long user) {
        var rows=store.db.queryForList("SELECT g.* FROM games g WHERE g.id=? AND (g.host_id=? OR EXISTS (SELECT 1 FROM game_players p WHERE p.game_id=g.id AND p.user_id=?))",id,user,user);
        if(rows.isEmpty()) throw ApiException.missing();
        var game=rows.getFirst();
        boolean host=((Number)game.get("host_id")).longValue()==user;
        try {
            var board=store.db.query("SELECT * FROM game_players WHERE game_id=? ORDER BY final_rank",(r,i)->row("id",r.getLong("id"),"name",r.getString("display_name"),"score",r.getInt("final_score"),"rank",r.getInt("final_rank")),id);
            var players=store.db.queryForList("SELECT id FROM game_players WHERE game_id=? AND user_id=?",Long.class,id,user);
            var answers=players.isEmpty()?List.of():store.db.query("SELECT * FROM player_answers WHERE game_player_id=? ORDER BY question_position",(r,i)-> {
                try { return row("questionIndex",r.getInt("question_position"),"optionIds",json.mapper.readTree(r.getString("selected_option_ids")),"responseMs",r.getObject("response_ms"),"points",r.getInt("points")); }
                catch(Exception e) { throw new IllegalStateException(e); }
            },players.getFirst());
            return row("id",id,"quiz",json.mapper.readTree((String)game.get("snapshot_json")),"status",game.get("status"),"role",host?"HOST":"PLAYER","endedAt",game.get("ended_at").toString(),"leaderboard",board,"answers",answers);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    static Map<String,Object> row(Object... entries) {
        var map=new LinkedHashMap<String,Object>();
        for(int i=0;i<entries.length;i+=2) map.put((String)entries[i],entries[i+1]);
        return map;
    }
}
