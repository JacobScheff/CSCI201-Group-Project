package com.rally;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.rally.Models.*;

@Service
public class AuthService {
    final Store store;
    final Json json;
    final byte[] secret;
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    final String dummy;
    static final Base64.Encoder ENCODE=Base64.getUrlEncoder().withoutPadding();
    public AuthService(Store store, Json json, @Value("${app.jwt-secret}") String secret) {
        this.store=store; this.json=json; this.secret=secret.getBytes(StandardCharsets.UTF_8);
        if(this.secret.length<32) throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        dummy=passwords.encode(UUID.randomUUID().toString());
    }
    public Map<String,Object> register(Register request) {
        if(request.password().getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.bad("Password must be at most 72 UTF-8 bytes.");
        long id=store.insert("INSERT INTO users(username,email,password_hash) VALUES(?,?,?)",
            request.username().toLowerCase(Locale.ROOT),request.email().toLowerCase(Locale.ROOT),passwords.encode(request.password()));
        return session(user(id));
    }
    public Map<String,Object> login(Login request) {
        if(request.password().getBytes(StandardCharsets.UTF_8).length>72) throw new ApiException(401,"BAD_CREDENTIALS","The email, username, or password is incorrect.");
        var rows=store.db.queryForList("SELECT * FROM users WHERE username=? OR email=?",request.identifier().toLowerCase(Locale.ROOT),request.identifier().toLowerCase(Locale.ROOT));
        String hash=rows.isEmpty()?dummy:(String)rows.getFirst().get("password_hash");
        if(!passwords.matches(request.password(),hash)||rows.isEmpty()) throw new ApiException(401,"BAD_CREDENTIALS","The email, username, or password is incorrect.");
        return session(user(((Number)rows.getFirst().get("id")).longValue()));
    }
    Map<String,Object> session(User user) {
        long now=Instant.now().getEpochSecond();
        String header=ENCODE.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload=ENCODE.encodeToString(json.write(Map.of("sub",Long.toString(user.id()),"iat",now,"exp",now+86400,"iss","rally","jti",UUID.randomUUID().toString())).getBytes(StandardCharsets.UTF_8));
        String unsigned=header+"."+payload;
        return Map.of("user",user,"token",unsigned+"."+ENCODE.encodeToString(sign(unsigned)));
    }
    byte[] sign(String value) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public User token(String token) {
        try {
            if(token==null||token.length()>4096) throw ApiException.unauthorized();
            String[] parts=token.split("\\.");
            if(parts.length!=3||!MessageDigest.isEqual(sign(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2]))) throw ApiException.unauthorized();
            JsonNode header=json.mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims=json.mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if(!"HS256".equals(header.path("alg").asText())||!"rally".equals(claims.path("iss").asText())||claims.path("exp").asLong()<=Instant.now().getEpochSecond()) throw ApiException.unauthorized();
            return user(Long.parseLong(claims.path("sub").asText()));
        } catch(Exception e) { throw ApiException.unauthorized(); }
    }
    public User bearer(String header) {
        if(header==null||!header.startsWith("Bearer ")) throw ApiException.unauthorized();
        return token(header.substring(7));
    }
    User user(long id) {
        var rows=store.db.query("SELECT id,username,email FROM users WHERE id=?",(r,i)->new User(r.getLong(1),r.getString(2),r.getString(3)),id);
        if(rows.isEmpty()) throw ApiException.unauthorized();
        return rows.getFirst();
    }
}
