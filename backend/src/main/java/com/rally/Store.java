package com.rally;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class Store {
    final JdbcTemplate db;
    public Store(JdbcTemplate db) { this.db=db; }
    long insert(String sql, Object... args) {
        var holder=new GeneratedKeyHolder();
        db.update(connection -> {
            var statement=connection.prepareStatement(sql,new String[]{"id"});
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },holder);
        return holder.getKey().longValue();
    }
}
