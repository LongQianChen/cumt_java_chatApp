package cn.edu.course.chat.common;

import java.util.regex.Pattern;

public final class Validation {
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern HOST = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");

    private Validation() {
    }

    public static String nickname(String value) {
        if (value == null) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        String result = value.strip();
        int count = result.codePointCount(0, result.length());
        if (count < 1 || count > 16 || result.equals("PUBLIC") || result.equals("SYSTEM")) {
            throw new IllegalArgumentException("昵称长度须为 1～16 个字符，且不能使用保留名称");
        }
        if (!result.codePoints().allMatch(codePoint -> Character.isLetterOrDigit(codePoint)
                || codePoint == '_' || codePoint == '-')) {
            throw new IllegalArgumentException("昵称仅允许字母、数字、下划线和连字符");
        }
        return result;
    }

    public static void text(String value) {
        if (value == null) {
            throw new IllegalArgumentException("正文不能为空");
        }
        int count = value.codePointCount(0, value.length());
        if (count < 1 || count > 2_000 || value.codePoints().allMatch(codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
            throw new IllegalArgumentException("正文须为 1～2000 个字符，且不能只有空白");
        }
        if (value.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("正文包含无效 Unicode");
        }
    }

    public static String profile(String value) {
        if (value == null || !PROFILE.matcher(value).matches()) {
            throw new IllegalArgumentException("profile 须为 1～32 个 ASCII 字母、数字、下划线或连字符");
        }
        return value;
    }

    public static void port(int value) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException("端口须为 1～65535");
        }
    }

    public static void host(String value) {
        if (value == null || !HOST.matcher(value).matches() || value.equals("0.0.0.0")) {
            throw new IllegalArgumentException("地址须为明确的数字 IPv4 地址");
        }
    }
}
