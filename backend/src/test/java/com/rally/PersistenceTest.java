package com.rally;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:rallytest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.username=sa","spring.datasource.password=","app.images=./target/test-images"
})
@DirtiesContext
class PersistenceTest {
    @Autowired AuthService auth;
    @Autowired QuizService quizzes;
    @Autowired GameManager games;
    @Autowired GameHistoryService history;
    @Autowired jakarta.validation.Validator validator;
    @Test void accountsTemplatesOwnershipAndHistorySurviveQuizDeletion() {
        var session=auth.register(new Register("owner","owner@example.com","safe-test-password"));
        var user=(User)session.get("user");
        assertEquals(user,auth.token((String)session.get("token")));
        assertNotNull(auth.login(new Login("owner@example.com","safe-test-password")).get("token"));
        assertThrows(ApiException.class,()->auth.token(session.get("token")+"bad"));
        assertThrows(ApiException.class,()->auth.login(new Login("owner","incorrect")));
        var other=(User)auth.register(new Register("other","other@example.com","safe-test-password")).get("user");
        assertEquals(3,quizzes.examples().size());
        var example=quizzes.examples().getFirst();
        var quiz=quizzes.cloneExample(example.id(),user.id());
        assertFalse(quiz.example()); assertNotEquals(example.id(),quiz.id());
        assertNotEquals(example.questions().getFirst().id(),quiz.questions().getFirst().id());
        assertThrows(ApiException.class,()->quizzes.owned(quiz.id(),other.id()));
        assertThrows(ApiException.class,()->quizzes.update(example.id(),user.id(),quiz));
        var game=games.create(quiz.id(),user);
        game.phase="COMPLETED";game.index=0;
        var player=new GameSession.Player(other.id(),"Other",1,null);
        player.score=900;player.answers.put(0,new GameSession.Answer(Set.of(quiz.questions().getFirst().options().getFirst().id()),4000,900));
        game.players.put(player.id,player);
        long gameId=history.save(game);
        quizzes.delete(quiz.id(),user.id());
        assertEquals(1,history.list(user.id()).size());assertEquals(1,history.list(other.id()).size());
        var detail=history.detail(gameId,other.id());
        assertEquals("PLAYER",detail.get("role"));assertEquals("HOST",history.detail(gameId,user.id()).get("role"));
        assertEquals(quiz.title(),((com.fasterxml.jackson.databind.JsonNode)detail.get("quiz")).path("title").asText());
        assertEquals(1,((List<?>)detail.get("answers")).size());
        assertFalse(validator.validate(new Quiz(null," ","",false,List.of())).isEmpty());
        games.games.remove(game.code);
    }
}
