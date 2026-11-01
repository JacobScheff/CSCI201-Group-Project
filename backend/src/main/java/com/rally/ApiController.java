package com.rally;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.util.*;
import static com.rally.Models.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    final AuthService auth;
    final QuizService quizzes;
    final GameManager games;
    final GameHistoryService history;
    final Path images;
    public ApiController(AuthService auth,QuizService quizzes,GameManager games,GameHistoryService history,@Value("${app.images}") String images) { this.auth=auth;this.quizzes=quizzes;this.games=games;this.history=history;this.images=Path.of(images); }
    @GetMapping("/health") public Object health() { return Map.of("status","ok"); }
    @PostMapping("/auth/register") public Object register(@Valid @RequestBody Register body) { return auth.register(body); }
    @PostMapping("/auth/login") public Object login(@Valid @RequestBody Login body) { return auth.login(body); }
    @GetMapping("/auth/me") public Object me(@RequestHeader(value="Authorization",required=false) String token) { return auth.bearer(token); }
    @GetMapping("/quizzes") public Object quizzes(@RequestHeader(value="Authorization",required=false) String token) { return quizzes.list(auth.bearer(token).id()); }
    @GetMapping("/quizzes/{id}") public Object quiz(@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) { return quizzes.owned(id,auth.bearer(token).id()); }
    @PostMapping("/quizzes") public Object create(@Valid @RequestBody Quiz body,@RequestHeader(value="Authorization",required=false) String token) { return quizzes.create(auth.bearer(token).id(),body); }
    @PutMapping("/quizzes/{id}") public Object update(@PathVariable long id,@Valid @RequestBody Quiz body,@RequestHeader(value="Authorization",required=false) String token) { return quizzes.update(id,auth.bearer(token).id(),body); }
    @DeleteMapping("/quizzes/{id}") public Object delete(@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) { quizzes.delete(id,auth.bearer(token).id());return Map.of("deleted",true); }
    @GetMapping("/examples") public Object examples(@RequestHeader(value="Authorization",required=false) String token) { auth.bearer(token);return quizzes.examples(); }
    @PostMapping("/examples/{id}/clone") public Object cloneExample(@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) { return quizzes.cloneExample(id,auth.bearer(token).id()); }
    @PostMapping("/games") public Object host(@Valid @RequestBody HostRequest body,@RequestHeader(value="Authorization",required=false) String token) { var game=games.create(body.quizId(),auth.bearer(token));return Map.of("code",game.code); }
    @GetMapping("/history") public Object history(@RequestHeader(value="Authorization",required=false) String token) { return history.list(auth.bearer(token).id()); }
    @GetMapping("/history/{id}") public Object detail(@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) { return history.detail(id,auth.bearer(token).id()); }
    @PostMapping("/images") public Object upload(@RequestParam("file") MultipartFile file,@RequestHeader(value="Authorization",required=false) String token) throws Exception {
        auth.bearer(token);
        if(file.isEmpty()||file.getSize()>5*1024*1024) throw ApiException.bad("Choose an image up to 5 MB.");
        byte[] bytes=file.getBytes();
        String extension=imageExtension(bytes);
        if(extension==null||!Objects.equals(file.getContentType(),switch(extension) {case "jpg"->"image/jpeg";case "png"->"image/png";case "gif"->"image/gif";default->"image/webp";})) throw ApiException.bad("Upload a PNG, JPEG, GIF, or WebP image with a matching file type.");
        Files.createDirectories(images);
        String name=UUID.randomUUID()+"."+extension;
        Files.write(images.resolve(name),bytes,StandardOpenOption.CREATE_NEW);
        return Map.of("url","/images/"+name);
    }
    static String imageExtension(byte[] b) {
        if(b.length<12) return null;
        if((b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10) return "png";
        if((b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255) return "jpg";
        String first=new String(b,0,12,java.nio.charset.StandardCharsets.ISO_8859_1);
        if(first.startsWith("GIF87a")||first.startsWith("GIF89a")) return "gif";
        if(first.startsWith("RIFF")&&first.substring(8).equals("WEBP")) return "webp";
        return null;
    }
}
