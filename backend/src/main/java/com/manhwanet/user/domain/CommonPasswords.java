package com.manhwanet.user.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 흔한 비밀번호 차단 목록. 출처·라이선스: resources/security/COMMON-PASSWORDS-SOURCE.md
 * 목록은 NFKC 정규화 + Locale.ROOT 소문자로 저장되어 있다.
 */
@Component
public class CommonPasswords {

    static final String RESOURCE = "security/common-passwords.txt";

    private final Set<String> passwords;

    public CommonPasswords() {
        this.passwords = load();
    }

    /** @param normalizedPassword NFKC 정규화된 비밀번호 */
    public boolean contains(String normalizedPassword) {
        return passwords.contains(normalizedPassword.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return passwords.size();
    }

    private static Set<String> load() {
        Set<String> result = new HashSet<>();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    result.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, e);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " is empty");
        }
        return Set.copyOf(result);
    }
}
