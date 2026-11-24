package dev.xigua.bilicc;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class VideoLinksTest {
    @Test
    public void parsesColorOsShareTextWithEscapedColon() {
        assertEquals("https://b23.tv/a9Tzl0J", VideoLinks.normalize(
                "老黄回应内存短缺：这是好事啊｜显卡日报3月8日 https\\://b23.tv/a9Tzl0J"));
    }

    @Test
    public void shareTextKeepsPartAndDropsTracking() {
        assertEquals("https://www.bilibili.com/video/BV1BbKw6XEWq/?p=2", VideoLinks.normalize(
                "【视频】 https://m.bilibili.com/video/BV1BbKw6XEWq?foo=bar&p=2&share_token=test。"));
        assertEquals("https://b23.tv/QaLink9", VideoLinks.normalize(
                "【视频】 https://b23.tv/QaLink9?share_source=qq 网页链接"));
    }

    @Test
    public void rejectsNonVideoAndNonBilibiliOrigins() {
        assertNull(VideoLinks.normalize("https://www.bilibili.com.evil.invalid/video/BV1BbKw6XEWq"));
        assertNull(VideoLinks.normalize("https://bilibili.com@evil.invalid/video/BV1BbKw6XEWq"));
        assertNull(VideoLinks.normalize("https://www.bilibili.com/read/cv1234"));
        assertEquals("BV1BbKw6XEWq", VideoLinks.normalize("  BV1BbKw6XEWq "));
    }
}
