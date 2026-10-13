package com.manhwanet.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformUrlsTest {

    private static final List<String> HOSTS = List.of("www.mrblue.com", "m.mrblue.com");
    private static final String MRBLUE = "^https://(www|m)\\.mrblue\\.com/(webtoon|comic)/(detail/)?(?<id>wt_[0-9]{9}|C[0-9]{9})$";

    @Test
    void acceptsObservedForms() {
        assertThat(PlatformUrls.validate("https://www.mrblue.com/webtoon/wt_000081371", HOSTS, MRBLUE, "wt_000081371").ok()).isTrue();
        assertThat(PlatformUrls.validate("https://m.mrblue.com/comic/detail/C000061780", HOSTS, MRBLUE, "C000061780").ok()).isTrue();
    }

    @Test
    void rejectsUnsafeOrForeignUrls() {
        for (String url : List.of("ftp://www.mrblue.com/comic/C000061780", "https://www.mrblue.com.evil.io/comic/C000061780",
                "https://WWW.MRBLUE.COM@evil.io/comic/C000061780", "https://[::1]/comic/C000061780",
                "https://www.mrblue.com/comic/C000061780?x=1", "//www.mrblue.com/comic/C000061780", "https:/comic")) {
            assertThat(PlatformUrls.validate(url, HOSTS, MRBLUE, "C000061780").ok()).as(url).isFalse();
        }
        assertThat(PlatformUrls.validate("https://www.mrblue.com/comic/C000061780", HOSTS, MRBLUE, "C000061781").field())
                .isEqualTo("platformWorkId");
    }

    @Test
    void checksAdminPatterns() {
        assertThat(PlatformUrls.checkPattern(MRBLUE)).isNull();
        assertThat(PlatformUrls.checkPattern("https://x\\.com/.*")).isNotNull();
        assertThat(PlatformUrls.checkPattern("^https://x(\\.com$")).isNotNull();
        assertThat(PlatformUrls.checkPattern("^https://toomics\\.com/(?<id>[0-9]+)/\\k'id'$")).isNotNull();
        assertThat(PlatformUrls.checkPattern("^https://toomics\\.com/(?<id>[0-9]+)/\\k<id>$")).isNotNull();
        assertThat(PlatformUrls.isValidHost("toomics.com")).isTrue();
        assertThat(PlatformUrls.isValidHost("127.0.0.1")).isFalse();
        assertThat(PlatformUrls.isValidHost("Toomics.com")).isFalse();
    }

    @Test
    void acceptsObservedOfficialUrls() {
        assertOk("https://comic.naver.com/webtoon/list?titleId=651673", List.of("comic.naver.com"),
                "^https://comic\\.naver\\.com/webtoon/list\\?titleId=(?<id>[0-9]+)$", "651673");
        assertOk("https://page.kakao.com/content/50866481", List.of("page.kakao.com"),
                "^https://page\\.kakao\\.com/content/(?<id>[0-9]+)$", "50866481");
        assertOk("https://webtoon.kakao.com/content/%EC%98%A4%EB%AC%B4%EB%9D%BC%EC%9D%B4%EC%8A%A4/198",
                List.of("webtoon.kakao.com"),
                "^https://webtoon\\.kakao\\.com/content/[^/?#]+/(?<id>[0-9]+)$", "198");
        assertOk("https://www.lezhin.com/ko/comic/highschoolboy", List.of("www.lezhin.com"),
                "^https://www\\.lezhin\\.com/ko/comic/(?<id>[A-Za-z0-9_-]+)$", "highschoolboy");
        assertOk("https://ridibooks.com/books/4524000001", List.of("ridibooks.com"),
                "^https://ridibooks\\.com/books/(?<id>[0-9]+)$", "4524000001");
        assertOk("https://www.bomtoon.com/detail/alias-1", List.of("www.bomtoon.com"),
                "^https://www\\.bomtoon\\.com/detail/(?<id>[^/?#]+)$", "alias-1");
        assertOk("https://toptoon.com/comic/ep_list/some-slug", List.of("toptoon.com"),
                "^https://toptoon\\.com/(comic|complete)/ep_list/(?<id>[A-Za-z0-9_-]+)$", "some-slug");
    }

    @Test
    void rejectsEncodedControlsAndNestedDotPaths() {
        String pattern = "^https://www\\.bomtoon\\.com/detail/(?<id>[^/?#]+)$";
        List<String> hosts = List.of("www.bomtoon.com");
        for (String url : List.of(
                "https://www.bomtoon.com/detail/a%00b",
                "https://www.bomtoon.com/detail/a%0D%0Ab",
                "https://www.bomtoon.com/detail/%252e%252e",
                "https://www.bomtoon.com/detail/%25252e",
                "https://www.bomtoon.com/detail/a%5Cb",
                "https://www.bomtoon.com/detail/a%2Fb")) {
            assertThat(PlatformUrls.validate(url, hosts, pattern, "alias-1").ok()).as(url).isFalse();
        }
        assertThat(PlatformUrls.validate("https://www.bomtoon.com/detail/alias-1?q=a%2Fb", hosts, null, "alias-1").ok())
                .isTrue();
    }

    private static void assertOk(String url, List<String> hosts, String pattern, String id) {
        assertThat(PlatformUrls.validate(url, hosts, pattern, id).ok()).as(url).isTrue();
    }
}
