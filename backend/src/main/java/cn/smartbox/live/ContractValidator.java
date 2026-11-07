package cn.smartbox.live;

import java.nio.*;
import java.nio.charset.*;
import java.math.*;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import cn.smartbox.common.ApiException;

/** Local-only schema graph and strict parser. Never loads a URL from a message. */
@Component
public class ContractValidator {
    private final ObjectMapper parser=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(8192).build()).build());
    private final JsonNode definitions;
    private final JsonSchemaFactory factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String,JsonSchema> schemas=new HashMap<>();
    public ContractValidator()throws Exception {
        try(var input=new ClassPathResource("contracts/effective/common.schema.json").getInputStream()){
            definitions=parser.readTree(input).get("$defs");
        }
    }
    public synchronized void validate(String name,JsonNode value){
        JsonSchema schema=schemas.computeIfAbsent(name,key->{
            ObjectNode local=parser.createObjectNode();local.put("$schema","https://json-schema.org/draft/2020-12/schema");
            local.set("$defs",definitions);local.put("$ref","#/$defs/"+key);
            return factory.getSchema(local);
        });
        if(!schema.validate(value).isEmpty())throw ApiException.invalid();
        if((name.equals("CommandInput")||name.equals("Command"))&&value.path("cmd").asText().equals("profile")){
            JsonNode p=value.path("params");
            double tMin=p.path("temp_min_c").asDouble(),tMax=p.path("temp_max_c").asDouble(),tH=p.path("temp_hysteresis_c").asDouble();
            double hMin=p.path("humi_min_pct").asDouble(),hMax=p.path("humi_max_pct").asDouble(),hH=p.path("humi_hysteresis_pct").asDouble();
            if(tMin>=tMax||hMin>=hMax||tMin+tH>tMax-tH||hMin+hH>hMax-hH)throw ApiException.invalid();
        }
    }
    public JsonNode parse(byte[] raw){
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            try(var input=parser.createParser(text)){
                JsonNode node=parser.readTree(input);
                if(node==null||!node.isObject()||input.nextToken()!=null)throw ApiException.invalid();
                return node;
            }
        }catch(Exception e){throw ApiException.invalid();}
    }
    public JsonNode json(Object raw){try{return parser.readTree(raw.toString());}catch(Exception e){throw new IllegalStateException("Invalid stored JSON");}}
    public JsonNode eventData(String type,JsonNode data){
        ObjectNode result=JsonNodeFactory.instance.objectNode();
        for(JsonNode branch:definitions.path("Event").path("allOf"))if(branch.path("if").path("properties").path("type").path("const").asText().equals(type)){
            branch.path("then").path("properties").path("data").path("properties").fieldNames().forEachRemaining(key->{if(data.has(key))result.set(key,data.get(key).deepCopy());});
        }
        if(result.has("lost_ranges")){
            ArrayNode ranges=result.putArray("lost_ranges");for(JsonNode range:data.path("lost_ranges")){
                ObjectNode safe=ranges.addObject();for(String field:List.of("boot_id","first_seq","last_seq"))safe.set(field,range.get(field));
            }
        }
        return result;
    }
    public JsonNode actualState(JsonNode input){ObjectNode safe=JsonNodeFactory.instance.objectNode();for(String key:List.of("sentry","sentry_revision","profile_id","mode","kind","on","calibrated"))if(input.has(key))safe.set(key,input.get(key));return safe;}
    public String text(Object value){try{return parser.writeValueAsString(value);}catch(Exception e){throw ApiException.invalid();}}
    public static JsonNode canonical(JsonNode value){
        if(value.isObject()){
            ObjectNode result=JsonNodeFactory.instance.objectNode();TreeSet<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);
            for(String key:keys)result.set(key,canonical(value.get(key)));return result;
        }
        if(value.isArray()){ArrayNode a=JsonNodeFactory.instance.arrayNode();value.forEach(v->a.add(canonical(v)));return a;}
        if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        return value;
    }
    public byte[] hash(JsonNode value){try{return MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    public JsonNode mqtt(String topic,byte[] raw,int qos,boolean retained){
        int bytes=topic.getBytes(StandardCharsets.UTF_8).length;
        int remaining=2+bytes+(qos>0?2:0)+raw.length, lengthBytes=1;
        for(int v=remaining;v>=128;v/=128)lengthBytes++;
        if(bytes>128||raw.length>1536||1+lengthBytes+remaining>2048)throw ApiException.invalid();
        String[] parts=topic.split("/",-1);
        if(parts.length!=4||!parts[0].equals("box"))throw ApiException.invalid();
        String suffix=parts[3];String type=switch(suffix){case "property"->"Property";case "heartbeat"->"Heartbeat";case "event"->"EventBatch";case "command_ack"->"CommandAck";case "availability"->"Availability";default->throw ApiException.invalid();};
        if(qos!=(suffix.equals("property")?0:1)||retained)throw ApiException.invalid();
        JsonNode value=parse(raw);validate(type,value);
        checkTree(value);
        if(!parts[2].equals(value.path("node_id").asText())||value.has("box_id")&&!parts[1].equals(value.path("box_id").asText()))throw ApiException.invalid();
        if(value.has("loc")&&value.path("loc").has("observation")){
            JsonNode observation=value.path("loc").path("observation");
            if(observation.path("uptime_ms").bigIntegerValue().compareTo(value.path("uptime_ms").bigIntegerValue())>0)throw ApiException.invalid();
        }
        return value;
    }
    private void checkTree(JsonNode value){
        if(value.isFloatingPointNumber()&&!Double.isFinite(value.doubleValue()))throw ApiException.invalid();
        if(value.isObject()){
            for(String key:List.of("request_id","operation_id"))if(value.has(key)&&value.path(key).asText().getBytes(StandardCharsets.UTF_8).length>64)throw ApiException.invalid();
            if(value.has("first_seq")&&value.has("last_seq")&&value.path("first_seq").asLong()>value.path("last_seq").asLong())throw ApiException.invalid();
            if(value.path("valid").asBoolean()){
                List<String> fields=value.has("temp_c")?List.of("temp_c","humi_pct","lux"):value.has("lat")?List.of("lat","lon"):value.has("acc_g")?List.of("acc_g","tilt_deg"):List.of();
                for(String field:fields)if(value.path(field).isNull()||value.path(field).isMissingNode())throw ApiException.invalid();
            }
        }
        if(value.isContainerNode())value.forEach(this::checkTree);
    }
}
