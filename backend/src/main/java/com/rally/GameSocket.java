package com.rally;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.rally.GameHistoryService.row;

@Component
public class GameSocket extends TextWebSocketHandler {
    record Connection(WebSocketSession socket,GameSession game) {}
    final ConcurrentHashMap<String,Connection> connections=new ConcurrentHashMap<>();
    final GameManager games;
    final AuthService auth;
    final Json json;
    public GameSocket(GameManager games,AuthService auth,Json json) { this.games=games;this.auth=auth;this.json=json; }
    @Override public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        raw.setTextMessageSizeLimit(8192);
        var socket=new ConcurrentWebSocketSessionDecorator(raw,2000,128*1024);
        try {
            String code=raw.getUri().getPath().substring(raw.getUri().getPath().lastIndexOf('/')+1);
            connections.put(raw.getId(),new Connection(socket,games.get(code)));
        } catch(ApiException e) { error(socket,e); socket.close(CloseStatus.POLICY_VIOLATION); }
    }
    @Override protected void handleTextMessage(WebSocketSession raw,TextMessage message) throws Exception {
        var connection=connections.get(raw.getId());
        if(connection==null) return;
        try {
            JsonNode body=json.mapper.readTree(message.getPayload());
            String type=body.path("type").asText();
            var socket=connection.socket(); var game=connection.game();
            switch(type) {
                case "HOST_CONNECT" -> game.connectHost(socket,auth.token(body.path("token").asText()));
                case "JOIN" -> game.join(socket,body.path("displayName").asText(),body.hasNonNull("token")&&!body.path("token").asText().isBlank()?auth.token(body.path("token").asText()):null);
                default -> {
                    var ids=new HashSet<Long>();
                    var options=body.path("optionIds");
                    if(options.isArray()) for(var option:options) { if(!option.isIntegralNumber()||!option.canConvertToLong()) throw ApiException.bad("Invalid answer option."); ids.add(option.asLong()); }
                    game.command(socket,type,body.path("questionIndex").asInt(-1),ids);
                }
            }
        } catch(ApiException e) { error(connection.socket(),e); }
        catch(Exception e) { error(connection.socket(),ApiException.bad("Invalid game message.")); }
    }
    void error(WebSocketSession socket,ApiException e) throws Exception {
        if(socket.isOpen()) socket.sendMessage(new TextMessage(json.write(row("type","ERROR","code",e.code,"message",e.getMessage()))));
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status) {
        var connection=connections.remove(socket.getId());
        if(connection!=null) connection.game().disconnect(connection.socket());
    }
    @Override public void handleTransportError(WebSocketSession socket,Throwable exception) throws Exception { socket.close(CloseStatus.SERVER_ERROR); }
}
