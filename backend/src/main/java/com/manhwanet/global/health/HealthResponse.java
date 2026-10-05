package com.manhwanet.global.health;

public record HealthResponse(String status, String database) {

    private static final String UP = "UP";

    public static HealthResponse up() {
        return new HealthResponse(UP, UP);
    }
}
