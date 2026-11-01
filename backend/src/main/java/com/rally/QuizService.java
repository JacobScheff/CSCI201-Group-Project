package com.rally;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.rally.Models.*;

@Service
public class QuizService {
    final Store store;
    public QuizService(Store store) { this.store=store; }
    public List<Quiz> list(long owner) {
        return store.db.queryForList("SELECT id FROM quizzes WHERE owner_id=? AND is_example=FALSE ORDER BY updated_at DESC",Long.class,owner).stream().map(this::load).toList();
    }
    public List<Quiz> examples() {
        return store.db.queryForList("SELECT id FROM quizzes WHERE is_example=TRUE ORDER BY id",Long.class).stream().map(this::load).toList();
    }
    public Quiz owned(long id,long owner) {
        if(store.db.queryForObject("SELECT COUNT(*) FROM quizzes WHERE id=? AND owner_id=? AND is_example=FALSE",Integer.class,id,owner)==0) throw ApiException.missing();
        return load(id);
    }
    Quiz load(long id) {
        var quizzes=store.db.query("SELECT * FROM quizzes WHERE id=?",(r,i)->new Quiz(r.getLong("id"),r.getString("title"),r.getString("description"),r.getBoolean("is_example"),questions(id)),id);
        if(quizzes.isEmpty()) throw ApiException.missing();
        return quizzes.getFirst();
    }
    List<Question> questions(long quizId) {
        return store.db.query("SELECT * FROM questions WHERE quiz_id=? ORDER BY position",(r,i)->new Question(r.getLong("id"),r.getString("text"),r.getString("type"),r.getInt("time_limit_sec"),r.getString("image_url"),options(r.getLong("id"))),quizId);
    }
    List<Option> options(long id) {
        return store.db.query("SELECT * FROM answer_options WHERE question_id=? ORDER BY position",(r,i)->new Option(r.getLong("id"),r.getString("text"),r.getBoolean("is_correct")),id);
    }
    void validate(Quiz quiz) {
        for(Question q:quiz.questions()) {
            long correct=q.options().stream().filter(Option::correct).count();
            if(correct==0||("SINGLE".equals(q.type())&&correct!=1)) throw ApiException.bad("Mark exactly one correct answer for single choice, or at least one for multi-select.");
            if(q.imageUrl()!=null&&!q.imageUrl().isBlank()&&!q.imageUrl().matches("/images/[a-f0-9-]+\\.(png|jpg|gif|webp)")) throw ApiException.bad("Please use an uploaded question image.");
        }
    }
    @Transactional
    public Quiz create(long owner,Quiz quiz) { return insert(owner,quiz,false); }
    @Transactional
    public Quiz insert(Long owner,Quiz quiz,boolean example) {
        validate(quiz);
        long id=store.insert("INSERT INTO quizzes(owner_id,title,description,is_example) VALUES(?,?,?,?)",owner,quiz.title().trim(),Objects.requireNonNullElse(quiz.description(),""),example);
        writeQuestions(id,quiz.questions());
        return load(id);
    }
    @Transactional
    public Quiz update(long id,long owner,Quiz quiz) {
        owned(id,owner); validate(quiz);
        store.db.update("UPDATE quizzes SET title=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",quiz.title().trim(),Objects.requireNonNullElse(quiz.description(),""),id);
        store.db.update("DELETE FROM questions WHERE quiz_id=?",id);
        writeQuestions(id,quiz.questions());
        return load(id);
    }
    void writeQuestions(long id,List<Question> questions) {
        for(int i=0;i<questions.size();i++) {
            var q=questions.get(i);
            long qid=store.insert("INSERT INTO questions(quiz_id,position,text,type,time_limit_sec,image_url) VALUES(?,?,?,?,?,?)",id,i,q.text().trim(),q.type(),q.timeLimitSec(),q.imageUrl());
            for(int j=0;j<q.options().size();j++) {
                var o=q.options().get(j);
                store.insert("INSERT INTO answer_options(question_id,position,text,is_correct) VALUES(?,?,?,?)",qid,j,o.text().trim(),o.correct());
            }
        }
    }
    @Transactional
    public void delete(long id,long owner) { owned(id,owner); store.db.update("DELETE FROM quizzes WHERE id=?",id); }
    @Transactional
    public Quiz cloneExample(long id,long owner) {
        var quiz=load(id);
        if(!quiz.example()) throw ApiException.missing();
        return insert(owner,quiz,false);
    }
}
