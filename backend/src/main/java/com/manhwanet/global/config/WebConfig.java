package com.manhwanet.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    private static final String BASE_PACKAGE = "com.manhwanet";

    /**
     * com.manhwanet 하위의 모든 @RestController 경로 앞에 /api/v1 을 붙인다.
     * 각 Controller는 /api/v1 을 생략하고 도메인 경로만 선언한다.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                API_PREFIX,
                HandlerTypePredicate.forBasePackage(BASE_PACKAGE)
                        .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }
}
