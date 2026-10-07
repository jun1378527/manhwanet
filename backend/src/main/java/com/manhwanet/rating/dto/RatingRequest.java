package com.manhwanet.rating.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 별점 등록·수정 요청. 사용자 ID 등 다른 필드는 받지 않는다(알 수 없는 필드는 무시된다).
 *
 * @param score 누락이나 JSON null 이면 null
 */
public record RatingRequest(Score score) {

    /**
     * score 필드의 JSON 원문. 숫자 토큰이면 원문 그대로의 BigDecimal 을, 그 밖의 토큰(문자열·불리언·배열·객체)이면
     * number 가 null 인 값을 담는다. Jackson 의 기본 문자열→숫자 변환을 쓰지 않아 "4.5" 같은 문자열을 거절할 수 있다.
     */
    @JsonDeserialize(using = Score.Deserializer.class)
    public record Score(BigDecimal number) {

        public boolean isNumber() {
            return number != null;
        }

        static final class Deserializer extends JsonDeserializer<Score> {

            @Override
            public Score deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                JsonToken token = parser.currentToken();
                if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    // 원문 텍스트에서 바로 BigDecimal 로 읽는다. (double 변환 없음)
                    return new Score(parser.getDecimalValue());
                }
                parser.skipChildren();
                return new Score(null);
            }
        }
    }
}
