package dev.xigua.bilicc;

import java.net.URI;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VideoLinks {
    private static final Pattern BV = Pattern.compile("BV[A-Za-z0-9]{10}");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>「」【】]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO = Pattern.compile("/video/(BV[A-Za-z0-9]{10})/?");
    private static final Pattern SHORT = Pattern.compile("/[A-Za-z0-9]{1,40}/?");
    private static final Pattern PART = Pattern.compile("(?:^|&)p=([1-9][0-9]{0,3})(?:&|$)");
    private static final List<String> VIDEO_HOSTS = Arrays.asList("www.bilibili.com", "bilibili.com", "m.bilibili.com");
    private static final List<String> SHORT_HOSTS = Arrays.asList("b23.tv", "bili2233.cn");

    static String normalize(CharSequence input) {
        if (input == null || input.length() > 16_384) return null;
        String text = input.toString().trim();
        if (BV.matcher(text).matches()) return text;
        Matcher candidates = URL.matcher(text);
        while (candidates.find()) {
            try {
                URI uri = URI.create(candidates.group().replaceFirst("[。，、；！）)]+$", ""));
                if (uri.getHost() == null || uri.getPort() != -1 || uri.getUserInfo() != null) continue;
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                String path = uri.getRawPath();
                if (SHORT_HOSTS.contains(host) && SHORT.matcher(path).matches()) return "https://" + host + path;
                Matcher video = VIDEO.matcher(path);
                if (VIDEO_HOSTS.contains(host) && video.matches()) {
                    Matcher part = PART.matcher(uri.getRawQuery() == null ? "" : uri.getRawQuery());
                    return "https://www.bilibili.com/video/" + video.group(1) + "/"
                            + (part.find() ? "?p=" + part.group(1) : "");
                }
            } catch (IllegalArgumentException invalid) {
                // A share can contain other text or links before the video address.
            }
        }
        return null;
    }
}
