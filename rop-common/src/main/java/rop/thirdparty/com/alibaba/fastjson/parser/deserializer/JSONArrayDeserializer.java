package rop.thirdparty.com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Type;

import rop.thirdparty.com.alibaba.fastjson.JSONArray;
import rop.thirdparty.com.alibaba.fastjson.parser.DefaultJSONParser;
import rop.thirdparty.com.alibaba.fastjson.parser.JSONToken;

public class JSONArrayDeserializer implements ObjectDeserializer {
    public final static JSONArrayDeserializer instance = new JSONArrayDeserializer();

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type clazz, Object fieldName) {
        JSONArray array = new JSONArray();
        parser.parseArray(array);
        return (T) array;
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACKET;
    }
}