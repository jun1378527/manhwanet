package com.manhwanet.review.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * JSON 문자열 필드. 문자열 토큰이면 값을, 그 밖의 토큰(숫자·불리언·배열·객체)이면 value 가 null 인 값을 담는다.
 * Jackson 의 기본 변환(숫자 → 문자열)을 쓰지 않아 형식 오류를 필드 오류로 안내할 수 있다.
 * 필드 누락·JSON null 이면 이 객체 자체가 null 이다.
 */
@JsonDeserialize(using = JsonText.Deserializer.class)
public record JsonText(String value) {

    public boolean isText() {
        return value != null;
    }

    static final class Deserializer extends JsonDeserializer<JsonText> {

        @Override
        public JsonText deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                return new JsonText(parser.getText());
            }
            parser.skipChildren();
            return new JsonText(null);
        }
    }
}
