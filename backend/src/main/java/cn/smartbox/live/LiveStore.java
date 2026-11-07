package cn.smartbox.live;

import java.util.*;
import org.springframework.stereotype.Repository;

/** Persistence boundary for live data; controllers never receive database rows. */
@Repository
public class LiveStore {
    private final LiveMapper mapper;
    public LiveStore(LiveMapper mapper){this.mapper=mapper;}
    public List<Map<String,Object>> rows(String sql,Object...args){return mapper.rows(sql,args);}
    public Map<String,Object> one(String sql,Object...args){var rows=rows(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    public int update(String sql,Object...args){return mapper.update(sql,args);}
    public long count(String sql,Object...args){return mapper.count(sql,args);}
}
