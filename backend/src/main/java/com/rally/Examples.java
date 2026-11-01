package com.rally;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.util.List;
import static com.rally.Models.*;

@Component
public class Examples implements ApplicationRunner {
    final QuizService quizzes;
    public Examples(QuizService quizzes) { this.quizzes=quizzes; }
    @Override public void run(ApplicationArguments args) {
        if(!quizzes.examples().isEmpty()) return;
        quizzes.insert(null,new Quiz(null,"The curious minds club","A little science, a little geography, a lot of friendly competition.",true,List.of(
            single("Which planet has the most prominent ring system?",0,"Saturn","Mars","Venus","Mercury"),
            single("What is the largest ocean on Earth?",1,"Atlantic","Pacific","Indian","Arctic"),
            multi("Which of these are mammals?",List.of(0,2),"Dolphin","Shark","Bat","Penguin"),
            single("Light travels faster than sound.",0,"True","False"),
            single("How many sides does a hexagon have?",2,"Five","Eight","Six","Seven"))),true);
        quizzes.insert(null,new Quiz(null,"Java, actually","Put your programming instincts to the test.",true,List.of(
            single("What does JVM stand for?",1,"Java Visual Mode","Java Virtual Machine","Joined Vector Map","Java Version Manager"),
            single("Which keyword prevents a class from being extended?",2,"static","private","final","abstract"),
            multi("Which collections implement List?",List.of(0,2),"ArrayList","HashSet","LinkedList","TreeMap"),
            single("Java supports multiple inheritance of classes.",1,"True","False"),
            single("Which mechanism protects shared state between threads?",0,"Synchronization","Recursion","Serialization","Overloading"))),true);
        quizzes.insert(null,new Quiz(null,"Around the world","Your next adventure starts with a question.",true,List.of(
            single("Which city is home to the Colosseum?",0,"Rome","Athens","Paris","Madrid"),
            single("Which country has the city of Kyoto?",2,"China","South Korea","Japan","Thailand"),
            single("The Sahara is the largest hot desert.",0,"True","False"),
            multi("Which countries are in South America?",List.of(0,3),"Brazil","Portugal","Mexico","Chile"),
            single("Which river flows through London?",1,"Seine","Thames","Danube","Rhine"))),true);
    }
    static Question single(String text,int correct,String... options) {
        return new Question(null,text,"SINGLE",20,null,java.util.stream.IntStream.range(0,options.length).mapToObj(i->new Option(null,options[i],i==correct)).toList());
    }
    static Question multi(String text,List<Integer> correct,String... options) {
        return new Question(null,text,"MULTI",30,null,java.util.stream.IntStream.range(0,options.length).mapToObj(i->new Option(null,options[i],correct.contains(i))).toList());
    }
}
