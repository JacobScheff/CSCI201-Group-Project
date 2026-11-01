package com.rally;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
    final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper=mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
