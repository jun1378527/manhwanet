package com.manhwanet.review.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * JSON 정수 필드(Long 범위). 정수 숫자 토큰의 원문을 double 을 거치지 않고 long 으로 읽는다.
 * 소수 표기(1.0, 1e0)·Long 범위 밖·문자열·불리언·배열·객체는 value 가 null 인 값을 담는다.
 * 필드 누락·JSON null 이면 이 객체 자체가 null 이다.
 */
@JsonDeserialize(using = JsonInteger.Deserializer.class)
public record JsonInteger(Long value) {

    public boolean isInteger() {
        return value != null;
    }

    static final class Deserializer extends JsonDeserializer<JsonInteger> {

        @Override
        public JsonInteger deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                JsonParser.NumberType type = parser.getNumberType();
                if (type == JsonParser.NumberType.INT || type == JsonParser.NumberType.LONG) {
                    return new JsonInteger(parser.getLongValue());
                }
                return new JsonInteger(null);
            }
            parser.skipChildren();
            return new JsonInteger(null);
        }
    }
}
