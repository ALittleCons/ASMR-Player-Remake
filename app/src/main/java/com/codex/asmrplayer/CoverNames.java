package com.codex.asmrplayer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 封面文件名判定（与 Android 无关，便于独立测试）。
 *
 * 设计目标：
 *  1. 不再只认 main：中文「封面」、英文 cover/front/jacket、日文「表紙 / 表纸 /
 *     ジャケット / サムネイル」等常见封面命名一律视为封面候选，且优先级相同；
 *  2. 支持带编号或社团号的变体，如 main01、cover_1、表紙2、RJ123456_表紙；
 *  3. 「排序第一位的图片」始终是封面候选与兜底：没有任何关键词命中时，
 *     直接采用固定排序后的第 0 张，不会被其它图片挤走；
 *  4. 仅把最像封面的那张提到第 0 位，其余图片保持原有固定相对顺序。
 *
 * 判定采用「去扩展名 → 全角转半角 → 小写 → 去除所有分隔符」的紧凑串：
 * 这样 main-01 / main_1 / ＭＡＩＮ １ / 表紙 (1) 都会归到同一形态。
 */
final class CoverNames {

    /** 未命中任何封面关键词。 */
    static final int NO_MATCH = 99;
    /** 排序第一位的图片作为兜底候选，优先级低于关键词命中，但高于其它普通图片。 */
    private static final int FIRST_SORTED = 98;

    private static final int RANK_EXACT = 0;
    private static final int RANK_NUMBERED = 1;
    private static final int RANK_CONTAINS = 2;

    /**
     * 常见封面命名（紧凑串，已小写）。
     * 中文 / 英文 / 日文汉字 / 日文假名 / 罗马字 / 拼音都覆盖。
     */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "main", "cover", "coverart", "coverimage", "front", "folder",
            "poster", "thumb", "thumbnail", "jacket", "artwork", "package",
            "keyvisual", "title", "titlepage", "header", "topimage",
            "封面", "封面图", "封面图片", "封图", "封绘", "封繪", "封套", "盒绘",
            "表紙", "表纸", "表紙画像", "ジャケット", "ジャケット画像",
            "サムネイル", "サムネ", "カバー", "カバー画像", "パッケージ",
            "パッケージ画像", "フロント", "表表紙",
            "hyoushi", "omote", "fengmian", "fengmian tu", "fm", "kv"));

    /** 短词只允许精确匹配，避免 topic / fmradio 之类被 contains 误判。 */
    private static final int MIN_CONTAINS_LENGTH = 4;

    private CoverNames() {
    }

    /** 返回应被提到第一位的图片下标；列表为空时返回 -1。 */
    static int chooseCoverIndex(List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            return -1;
        }
        int bestIndex = 0;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < fileNames.size(); i++) {
            int rank = rank(fileNames.get(i));
            if (i == 0 && rank == NO_MATCH) {
                rank = FIRST_SORTED;
            }
            if (rank < bestRank) {
                bestRank = rank;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    static boolean isCoverName(String fileName) {
        return rank(fileName) != NO_MATCH;
    }

    /** 数值越小越像封面；NO_MATCH 表示不是封面候选。 */
    static int rank(String fileName) {
        String compact = compact(fileName);
        if (compact.isEmpty()) {
            return NO_MATCH;
        }
        if (KEYWORDS.contains(compact)) {
            return RANK_EXACT;
        }
        for (String keyword : KEYWORDS) {
            if (compact.length() <= keyword.length()) {
                continue;
            }
            if (compact.startsWith(keyword)) {
                String tail = compact.substring(keyword.length());
                if (isDigitsOnly(tail)) {
                    return RANK_NUMBERED;
                }
            }
            // RJ123456表紙 / 表紙2：关键词紧邻纯数字或社团号
            if (compact.endsWith(keyword)) {
                String head = compact.substring(0, compact.length() - keyword.length());
                if (head.matches("(?i)(?:rj)?\\d{1,10}")) {
                    return RANK_NUMBERED;
                }
            }
            int hit = compact.indexOf(keyword);
            while (hit >= 0) {
                if (hasWordBoundary(compact, keyword, hit)) {
                    return RANK_CONTAINS;
                }
                hit = compact.indexOf(keyword, hit + 1);
            }
        }
        return NO_MATCH;
    }

    /**
     * ASCII 关键词（main/cover/fm…）要求前后不是英文字母，避免 domain / recover /
     * fmradio 这类误判；中文、日文汉字与假名没有词边界问题，允许任意位置包含，
     * 才能覆盖「作品封面」「RJ123456_表紙_2」这类常见命名。
     */
    private static boolean hasWordBoundary(String compact, String keyword, int hit) {
        if (keyword.length() < MIN_CONTAINS_LENGTH && !containsNonAscii(keyword)) {
            return false;
        }
        if (!containsNonAscii(keyword)) {
            int before = hit - 1;
            int after = hit + keyword.length();
            if (before >= 0 && isAsciiLetter(compact.charAt(before))) {
                return false;
            }
            if (after < compact.length() && isAsciiLetter(compact.charAt(after))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNonAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 去扩展名、全角转半角、小写、移除所有非字母数字/CJK/假名字符。 */
    private static String compact(String fileName) {
        if (fileName == null) {
            return "";
        }
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        stem = normalizeFullwidth(stem).toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(stem.length());
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            // 保留字母数字、CJK、假名；丢弃空格 _ - ( ) [ ] 等所有分隔符
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

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

    private static boolean isDigitsOnly(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
