package com.manhwanet.demo;

import java.util.List;

/**
 * 데모 가상 작품 목록(#67). <b>실제 작품이 아니다.</b> 제목·작가에 [DEMO]·가상을 붙였고, 모두 원래 등급 표기로
 * 18세 미만이 확인되는 작품(GENERAL)이라 현재 공개 정책에서 공개할 수 있다. 소개문·표지는 두지 않는다.
 */
public final class DemoCatalog {

    public record Item(String id, String title, String age, String story, String art) {
    }

    public static final List<Item> ITEMS = List.of(
            new Item("demo-2001", "[DEMO] 가상의 별빛 정원", "전체 이용가", "[DEMO] 가상작가 하늘", "[DEMO] 가상작가 바다"),
            new Item("demo-2002", "[DEMO] 가상의 달빛 서점", "12세 이용가", "[DEMO] 가상작가 구름", null),
            new Item("demo-2003", "[DEMO] 가상의 시계탑 소녀", "15세 이용가", "[DEMO] 가상작가 노을", "[DEMO] 가상작가 새벽"));

    private DemoCatalog() {
    }
}
