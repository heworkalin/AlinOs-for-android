package alin.android.alinos.proot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文本编辑引擎，语义对齐 pi 的 edit 工具（{@code dist/core/tools/edit-diff.js}）。
 *
 * <ul>
 *   <li>匹配顺序：精确匹配优先，失败再 fuzzy 匹配；</li>
 *   <li>fuzzy 归一化：NFKC + 去每行尾随空白 + 智能引号/破折号/特殊空格 → ASCII；</li>
 *   <li>每个 oldText 必须唯一（除非 replaceAll）；</li>
 *   <li>{@code oldText}/{@code newText} 先归一到 LF 再匹配，写回时恢复原行尾；</li>
 *   <li>fuzzy 命中时，只重写被替换触及的行，其余行按原始字节保留；</li>
 *   <li>替换后内容与原文相同 → 报错。</li>
 * </ul>
 */
public final class EditEngine {

    private EditEngine() {
    }

    // =====================================================================
    // 文本原语（移植自 pi）
    // =====================================================================

    /** 探测文件的主行尾风格（CRLF / LF）。 */
    public static String detectLineEnding(String content) {
        int crlf = content.indexOf("\r\n");
        int lf = content.indexOf("\n");
        if (lf == -1) return "\n";
        if (crlf == -1) return "\n";
        return crlf < lf ? "\r\n" : "\n";
    }

    public static String normalizeToLF(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    public static String restoreLineEndings(String text, String ending) {
        return "\r\n".equals(ending) ? text.replace("\n", "\r\n") : text;
    }

    /** pi 的 fuzzy 归一化：NFKC + 去尾随空白 + 引号/破折号/空格归一。 */
    public static String normalizeForFuzzyMatch(String text) {
        if (text == null) return "";
        String t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);

        StringBuilder sb = new StringBuilder(t.length());
        String[] lines = t.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(trimEnd(lines[i]));
        }
        String s = sb.toString();

        s = s.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201A', '\'').replace('\u201B', '\'');
        s = s.replace('\u201C', '"').replace('\u201D', '"').replace('\u201E', '"').replace('\u201F', '"');
        s = s.replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2015', '-').replace('\u2212', '-');
        s = s.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u205F', ' ').replace('\u3000', ' ');
        for (char c = '\u2002'; c <= '\u200A'; c++) s = s.replace(c, ' ');
        return s;
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    // =====================================================================
    // 结果模型
    // =====================================================================

    public static final class Replacement {
        final int matchIndex;
        final int matchLength;
        final String newText;

        Replacement(int matchIndex, int matchLength, String newText) {
            this.matchIndex = matchIndex;
            this.matchLength = matchLength;
            this.newText = newText;
        }
    }

    public static final class Result {
        public String newContent;
        public int editCount;
        public boolean usedFuzzyMatch;
        public String diff;
        public int firstChangedLine;
    }

    private static final class DiffResult {
        String text = "";
        int firstChangedLine = 1;
    }

    // =====================================================================
    // 主入口
    // =====================================================================

    /**
     * 在 LF 归一化后的内容上应用编辑。
     *
     * @param path        用于错误信息
     * @param replaceAll  AlinOs 扩展：替换每个 oldText 的全部出现（pi 无此参数）
     */
    public static Result apply(String normalizedContent, List<String[]> edits, String path,
                               boolean replaceAll) throws IOException {
        for (int i = 0; i < edits.size(); i++) {
            String oldText = edits.get(i)[0];
            if (oldText == null || oldText.isEmpty()) {
                throw new IOException(emptyOldText(path, i, edits.size()));
            }
        }

        String fuzzyContent = normalizeForFuzzyMatch(normalizedContent);
        boolean usedFuzzy = false;
        for (String[] e : edits) {
            if (normalizedContent.indexOf(e[0]) < 0
                    && fuzzyContent.indexOf(normalizeForFuzzyMatch(e[0])) >= 0) {
                usedFuzzy = true;
                break;
            }
        }

        String base = usedFuzzy ? fuzzyContent : normalizedContent;

        List<Replacement> reps = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            String oldT = edits.get(i)[0];
            String newT = edits.get(i)[1] == null ? "" : edits.get(i)[1];
            String searchOld = usedFuzzy ? normalizeForFuzzyMatch(oldT) : oldT;

            List<Integer> all = findAll(base, searchOld);
            if (all.isEmpty()) throw new IOException(notFound(path, i, edits.size()));
            if (!replaceAll && all.size() > 1) {
                throw new IOException(duplicate(path, i, edits.size(), all.size()));
            }
            List<Integer> use = replaceAll ? all : Collections.singletonList(all.get(0));
            for (int idx : use) reps.add(new Replacement(idx, searchOld.length(), newT));
        }

        reps.sort((a, b) -> Integer.compare(a.matchIndex, b.matchIndex));
        if (!replaceAll) {
            for (int i = 1; i < reps.size(); i++) {
                Replacement prev = reps.get(i - 1);
                Replacement cur = reps.get(i);
                if (prev.matchIndex + prev.matchLength > cur.matchIndex) {
                    throw new IOException("edits overlap in " + path
                            + ". Merge them into one edit or target disjoint regions.");
                }
            }
        }

        String newContent = usedFuzzy
                ? applyReplacementsPreservingUnchangedLines(normalizedContent, base, reps)
                : applyReplacements(base, reps);

        if (normalizedContent.equals(newContent)) {
            throw new IOException(noChange(path, edits.size()));
        }

        Result res = new Result();
        res.newContent = newContent;
        res.editCount = edits.size();
        res.usedFuzzyMatch = usedFuzzy;
        DiffResult d = generateDiff(normalizedContent, newContent);
        res.diff = d.text;
        res.firstChangedLine = d.firstChangedLine;
        return res;
    }

    // =====================================================================
    // 替换应用
    // =====================================================================

    private static List<Integer> findAll(String content, String needle) {
        List<Integer> out = new ArrayList<>();
        if (needle == null || needle.isEmpty()) return out;
        int i = 0;
        while (true) {
            int idx = content.indexOf(needle, i);
            if (idx < 0) break;
            out.add(idx);
            i = idx + needle.length();
        }
        return out;
    }

    private static String applyReplacements(String content, List<Replacement> reps) {
        List<Replacement> sorted = new ArrayList<>(reps);
        sorted.sort((a, b) -> Integer.compare(a.matchIndex, b.matchIndex));
        String result = content;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Replacement r = sorted.get(i);
            result = result.substring(0, r.matchIndex) + r.newText
                    + result.substring(r.matchIndex + r.matchLength);
        }
        return result;
    }

    private static List<String> splitLinesWithEndings(String content) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = content.length();
        while (i < n) {
            int j = content.indexOf('\n', i);
            if (j < 0) {
                out.add(content.substring(i));
                break;
            }
            out.add(content.substring(i, j + 1));
            i = j + 1;
        }
        return out;
    }

    private static int[][] lineSpans(String content) {
        List<String> lines = splitLinesWithEndings(content);
        int[][] spans = new int[lines.size()][2];
        int off = 0;
        for (int i = 0; i < lines.size(); i++) {
            spans[i][0] = off;
            spans[i][1] = off + lines.get(i).length();
            off = spans[i][1];
        }
        return spans;
    }

    private static int[] replacementLineRange(int[][] lines, Replacement r) {
        int start = r.matchIndex;
        int end = r.matchIndex + r.matchLength;
        int startLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (start >= lines[i][0] && start < lines[i][1]) {
                startLine = i;
                break;
            }
        }
        if (startLine == -1) {
            throw new IllegalStateException("Replacement range is outside the base content.");
        }
        int endLine = startLine;
        while (endLine < lines.length && lines[endLine][1] < end) endLine++;
        if (endLine >= lines.length) {
            throw new IllegalStateException("Replacement range is outside the base content.");
        }
        return new int[]{startLine, endLine + 1};
    }

    /**
     * 把针对规范化内容（{@code baseContent}）的替换，套用到原始内容上：
     * 只重写被触及的行，其余行按 {@code originalContent} 的原始字节保留。
     */
    private static String applyReplacementsPreservingUnchangedLines(
            String originalContent, String baseContent, List<Replacement> reps) {
        List<String> originalLines = splitLinesWithEndings(originalContent);
        int[][] baseLines = lineSpans(baseContent);
        if (originalLines.size() != baseLines.length) {
            throw new IllegalStateException(
                    "Cannot preserve unchanged lines because the base content has a different line count.");
        }

        List<Replacement> sorted = new ArrayList<>(reps);
        sorted.sort((a, b) -> Integer.compare(a.matchIndex, b.matchIndex));

        List<int[]> groups = new ArrayList<>();
        List<List<Replacement>> groupReps = new ArrayList<>();
        for (Replacement r : sorted) {
            int[] range = replacementLineRange(baseLines, r);
            if (!groups.isEmpty()) {
                int[] cur = groups.get(groups.size() - 1);
                if (range[0] < cur[1]) {
                    cur[1] = Math.max(cur[1], range[1]);
                    groupReps.get(groupReps.size() - 1).add(r);
                    continue;
                }
            }
            groups.add(new int[]{range[0], range[1]});
            List<Replacement> list = new ArrayList<>();
            list.add(r);
            groupReps.add(list);
        }

        StringBuilder result = new StringBuilder();
        int originalLineIndex = 0;
        for (int g = 0; g < groups.size(); g++) {
            int[] grp = groups.get(g);
            for (int i = originalLineIndex; i < grp[0]; i++) {
                result.append(originalLines.get(i));
            }
            int groupStart = baseLines[grp[0]][0];
            int groupEnd = baseLines[grp[1] - 1][1];
            String slice = baseContent.substring(groupStart, groupEnd);
            List<Replacement> shifted = new ArrayList<>();
            for (Replacement r : groupReps.get(g)) {
                shifted.add(new Replacement(r.matchIndex - groupStart, r.matchLength, r.newText));
            }
            result.append(applyReplacements(slice, shifted));
            originalLineIndex = grp[1];
        }
        for (int i = originalLineIndex; i < originalLines.size(); i++) {
            result.append(originalLines.get(i));
        }
        return result.toString();
    }

    // =====================================================================
    // diff（简化版 LCS；过大则省略）
    // =====================================================================

    private static DiffResult generateDiff(String oldContent, String newContent) {
        DiffResult out = new DiffResult();
        String[] a = oldContent.split("\n", -1);
        String[] b = newContent.split("\n", -1);
        if ((long) a.length * (long) b.length > 4_000_000L) {
            out.text = "(diff omitted: file too large)";
            out.firstChangedLine = 1;
            return out;
        }

        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<String> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        int firstChanged = -1;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                lines.add(" " + a[i]);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                lines.add("-" + a[i]);
                if (firstChanged < 0) firstChanged = j + 1;
                i++;
            } else {
                lines.add("+" + b[j]);
                if (firstChanged < 0) firstChanged = j + 1;
                j++;
            }
        }
        while (i < a.length) {
            lines.add("-" + a[i]);
            i++;
        }
        while (j < b.length) {
            lines.add("+" + b[j]);
            j++;
        }

        out.text = String.join("\n", lines);
        out.firstChangedLine = firstChanged < 0 ? 1 : firstChanged;
        return out;
    }

    // =====================================================================
    // 错误信息（英文，对齐 pi）
    // =====================================================================

    private static String emptyOldText(String path, int editIndex, int total) {
        return total == 1
                ? "oldText must not be empty in " + path + "."
                : "edits[" + editIndex + "].oldText must not be empty in " + path + ".";
    }

    private static String notFound(String path, int editIndex, int total) {
        return total == 1
                ? "Could not find the exact text in " + path
                + ". The old text must match exactly including all whitespace and newlines."
                : "Could not find edits[" + editIndex + "] in " + path
                + ". The oldText must match exactly including all whitespace and newlines.";
    }

    private static String duplicate(String path, int editIndex, int total, int occurrences) {
        return total == 1
                ? "Found " + occurrences + " occurrences of the text in " + path
                + ". The text must be unique. Please provide more context to make it unique."
                : "Found " + occurrences + " occurrences of edits[" + editIndex + "] in " + path
                + ". Each oldText must be unique. Please provide more context to make it unique.";
    }

    private static String noChange(String path, int total) {
        return total == 1
                ? "No changes made to " + path
                + ". The replacement produced identical content. This might indicate an issue "
                + "with special characters or the text not existing as expected."
                : "No changes made to " + path + ". The replacements produced identical content.";
    }
}
