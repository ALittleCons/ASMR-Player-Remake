package com.codex.asmrplayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全应用统一的固定资源排序（音轨、图片共用）。
 *
 * 规则：
 *  1. 把 track / tr + 数字（如 track01、tr 02、TR_10）视作一个整体序号，
 *     与纯数字开头的文件归入同一「数字序号」组，序号按数值比较；
 *  2. 整体顺序为 数字序号 > 英文 > 中文（CJK）> 其他；
 *  3. 组内采用自然排序（数字串按数值而非字典序）；
 *  4. 最后用大小写无关比较与原始字符串兜底，保证结果严格全序、跨扫描顺序稳定。
 */
final class NameOrder {

    /** track / tr 前缀（前面允许任意非字母数字字符，中间允许空格 _ -），后接 1~6 位数字。 */
    private static final Pattern TRACK_NUMBER = Pattern.compile(
            "(?i)(?:^|[^0-9a-z])(?:track|tr)[\\s_\\-]*(\\d{1,6})(?![0-9])");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d{1,9})");

    private static final int GROUP_NUMBER = 0;
    private static final int GROUP_LATIN = 1;
    private static final int GROUP_CJK = 2;
    private static final int GROUP_OTHER = 3;

    private NameOrder() {
    }

    /** 比较两个文件名（含扩展名），结果恒为非 0（除非两者完全相同）。 */
    static int compare(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        Key a = keyOf(left);
        Key b = keyOf(right);
        if (a.group != b.group) {
            return Integer.compare(a.group, b.group);
        }
        if (a.group == GROUP_NUMBER && a.seq != b.seq) {
            return Long.compare(a.seq, b.seq);
        }
        int cmp = naturalCompare(a.rest, b.rest);
        if (cmp != 0) {
            return cmp;
        }
        cmp = a.original.compareTo(b.original);
        return cmp != 0 ? cmp : left.compareTo(right);
    }

    private static Key keyOf(String fileName) {
        String original = fileName.toLowerCase(Locale.ROOT);
        String lower = normalizeFullwidth(stripExtension(fileName)).toLowerCase(Locale.ROOT);

        Matcher track = TRACK_NUMBER.matcher(lower);
        if (track.find()) {
            long seq = parseSeq(track.group(1));
            String rest = (lower.substring(0, track.start()) + " "
                    + lower.substring(track.end())).trim();
            return new Key(GROUP_NUMBER, seq, rest, original);
        }
        Matcher leading = LEADING_NUMBER.matcher(lower);
        if (leading.find()) {
            long seq = parseSeq(leading.group(1));
            return new Key(GROUP_NUMBER, seq, lower.substring(leading.end()).trim(), original);
        }
        char first = firstNonBlank(lower);
        if (isCjk(first)) {
            return new Key(GROUP_CJK, 0, lower, original);
        }
        if (first >= 'a' && first <= 'z') {
            return new Key(GROUP_LATIN, 0, lower, original);
        }
        return new Key(GROUP_OTHER, 0, lower, original);
    }

    private static long parseSeq(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 全角字符归一化为半角：真实 ASMR 库大量使用 ＴＲ１２、ＴＲＡＣＫ１、１　封面 这类命名，
     * 且 Android 与桌面 JVM 的 \d Unicode 语义不同，显式统一才能保证顺序固定、序号可识别。
     */
    private static String normalizeFullwidth(String value) {
        StringBuilder builder = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char mapped = 0;
            if (c >= '\uFF10' && c <= '\uFF19') {
                mapped = (char) ('0' + (c - '\uFF10'));
            } else if (c >= '\uFF21' && c <= '\uFF3A') {
                mapped = (char) ('A' + (c - '\uFF21'));
            } else if (c >= '\uFF41' && c <= '\uFF5A') {
                mapped = (char) ('a' + (c - '\uFF41'));
            } else if (c == '\u3000') {
                mapped = ' ';
            }
            if (mapped != 0) {
                if (builder == null) {
                    builder = new StringBuilder(value.length()).append(value, 0, i);
                }
                builder.append(mapped);
            } else if (builder != null) {
                builder.append(c);
            }
        }
        return builder == null ? value : builder.toString();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static char firstNonBlank(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }

    /** 汉字、日文假名、韩文音节等统一归为「中文」组。 */
    private static boolean isCjk(char c) {
        return (c >= '\u2E80' && c <= '\u9FFF')
                || (c >= '\u3040' && c <= '\u30FF')
                || (c >= '\uAC00' && c <= '\uD7AF')
                || (c >= '\uF900' && c <= '\uFAFF');
    }

    /** 数字串按数值比较（忽略前导零差异），其余字符按小写码点比较。 */
    private static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            boolean da = ca >= '0' && ca <= '9';
            boolean db = cb >= '0' && cb <= '9';
            if (da && db) {
                int iEnd = i;
                while (iEnd < a.length() && isDigit(a.charAt(iEnd))) {
                    iEnd++;
                }
                int jEnd = j;
                while (jEnd < b.length() && isDigit(b.charAt(jEnd))) {
                    jEnd++;
                }
                int cmp = compareDigitRuns(a.substring(i, iEnd), b.substring(j, jEnd));
                if (cmp != 0) {
                    return cmp;
                }
                i = iEnd;
                j = jEnd;
            } else {
                char la = Character.toLowerCase(ca);
                char lb = Character.toLowerCase(cb);
                if (la != lb) {
                    return Character.compare(la, lb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int compareDigitRuns(String a, String b) {
        int ia = 0;
        while (ia < a.length() - 1 && a.charAt(ia) == '0') {
            ia++;
        }
        int ib = 0;
        while (ib < b.length() - 1 && b.charAt(ib) == '0') {
            ib++;
        }
        String na = a.substring(ia);
        String nb = b.substring(ib);
        if (na.length() != nb.length()) {
            return Integer.compare(na.length(), nb.length());
        }
        return na.compareTo(nb);
    }

    private static final class Key {
        final int group;
        final long seq;
        final String rest;
        final String original;

        Key(int group, long seq, String rest, String original) {
            this.group = group;
            this.seq = seq;
            this.rest = rest;
            this.original = original;
        }
    }
}
