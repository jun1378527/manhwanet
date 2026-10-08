package com.manhwanet.review.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * JSON 불리언 필드. true/false 토큰만 값으로 받고, 문자열 "true"·숫자 1 등은 value 가 null 인 값을 담는다.
 * 필드 누락·JSON null 이면 이 객체 자체가 null 이다.
 */
@JsonDeserialize(using = JsonFlag.Deserializer.class)
public record JsonFlag(Boolean value) {

    public boolean isBoolean() {
        return value != null;
    }

    static final class Deserializer extends JsonDeserializer<JsonFlag> {

        @Override
        public JsonFlag deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
                return new JsonFlag(token == JsonToken.VALUE_TRUE);
            }
            parser.skipChildren();
            return new JsonFlag(null);
        }
    }
}
