package alin.android.alinos.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.util.Log;

/**
 * jlatexmath 兼容性预处理：在 Markwon 渲染前转换不支持的 LaTeX 语法。
 *
 * 规则 1：\color{xxx}  →  {\color{xxx} ...}（用 {} 限定着色域）
 * 规则 2：合并被 Markwon 拆散的 $$ 碎片块（如矩阵跨行）
 * 规则 3：命名色 → #hex（jlatexmath 只认 #hex，不认 lime / darkblue 等）
 */
public class LatexPreprocessor {

    // 匹配 $...$ 和 $$...$$ 块（与 JLatexMathInlineProcessor2 保持一致）
    // 注意：不用 DOTALL，[^$\\] 字符类本身就能匹配换行，不需要 .* 跨行
    private static final Pattern LATEX_BLOCK = Pattern.compile(
            "(?<!\\\\)(\\$\\$?)((?:\\\\.|[^$\\\\])*)\\1(?!\\$)"
    );

    // 匹配 \begin{xxx}
    private static final Pattern BEGIN_PATTERN = Pattern.compile("\\\\begin\\{");

    // 匹配 \colorbox{name}、\textcolor{name}、\color{name} 中的命名色
    // 兼容 "red"、"green!70!black" 等 xcolor 语法
    private static final Pattern COLOR_ARG_PATTERN =
            Pattern.compile("\\\\(colorbox|textcolor|color)\\{([a-zA-Z][a-zA-Z0-9!%,.]*)\\}");

    // 命名色 → #hex（不依赖 jlatexmath 内部色表）
    /** 命名色 → #hex 对照表（覆盖 xcolor / dvipsnames / SVG 常用色）
     *  保留已测试的既有条目；对同名的冲突值取已有值不变。 */
    private static final Map<String, String> COLOR_NAMES = new HashMap<>();

    static {
        // ── xcolor 基础19色 + 补充深色系 ──
        COLOR_NAMES.put("black",       "#000000");
        COLOR_NAMES.put("white",       "#FFFFFF");
        COLOR_NAMES.put("red",         "#FF0000");
        COLOR_NAMES.put("green",       "#00FF00");
        COLOR_NAMES.put("blue",        "#0000FF");
        COLOR_NAMES.put("yellow",      "#FFFF00");
        COLOR_NAMES.put("magenta",     "#FF00FF");
        COLOR_NAMES.put("cyan",        "#00FFFF");
        COLOR_NAMES.put("gray",        "#808080");
        COLOR_NAMES.put("darkgray",    "#A9A9A9");
        COLOR_NAMES.put("lightgray",   "#D3D3D3");
        COLOR_NAMES.put("brown",       "#A52A2A");
        COLOR_NAMES.put("orange",      "#FFA500");
        COLOR_NAMES.put("pink",        "#FFC0CB");
        COLOR_NAMES.put("purple",      "#800080");
        COLOR_NAMES.put("olive",       "#808000");
        COLOR_NAMES.put("teal",        "#008080");
        COLOR_NAMES.put("violet",      "#EE82EE");
        COLOR_NAMES.put("darkred",     "#8B0000");
        COLOR_NAMES.put("darkblue",    "#00008B");
        COLOR_NAMES.put("darkgreen",   "#006400");
        COLOR_NAMES.put("darkcyan",    "#008B8B");
        COLOR_NAMES.put("darkmagenta", "#8B008B");
        COLOR_NAMES.put("darkyellow",  "#8B8B00");
        COLOR_NAMES.put("navy",        "#000080");
        COLOR_NAMES.put("maroon",      "#800000");
        COLOR_NAMES.put("forestgreen", "#228B22");
        COLOR_NAMES.put("lime",        "#00FF00");
        COLOR_NAMES.put("gold",        "#FFD700");
        COLOR_NAMES.put("crimson",     "#DC143C");
        COLOR_NAMES.put("indigo",      "#4B0082");
        COLOR_NAMES.put("coral",       "#FF7F50");
        COLOR_NAMES.put("salmon",      "#FA8072");
        COLOR_NAMES.put("khaki",       "#F0E68C");
        COLOR_NAMES.put("plum",        "#DDA0DD");
        COLOR_NAMES.put("tan",         "#D2B48C");
        COLOR_NAMES.put("aqua",        "#00FFFF");
        COLOR_NAMES.put("fuchsia",     "#FF00FF");
        COLOR_NAMES.put("azure",       "#F0FFFF");
        COLOR_NAMES.put("ivory",       "#FFFFF0");
        COLOR_NAMES.put("beige",       "#F5F5DC");

        // ── dvipsnames 典型色 ──
        COLOR_NAMES.put("apricot",         "#FDD5B5");
        COLOR_NAMES.put("aquamarine",      "#70DB93");
        COLOR_NAMES.put("bittersweet",     "#C04F17");
        COLOR_NAMES.put("bluegreen",       "#00A59C");
        COLOR_NAMES.put("blueviolet",      "#4A1C72");
        COLOR_NAMES.put("brickred",        "#B22222");
        COLOR_NAMES.put("burntorange",     "#FF7C00");
        COLOR_NAMES.put("cadetblue",       "#5F9F9F");
        COLOR_NAMES.put("carnationpink",   "#FFAACC");
        COLOR_NAMES.put("cerulean",        "#009ED2");
        COLOR_NAMES.put("cornflowerblue",  "#6495ED");
        COLOR_NAMES.put("dandelion",       "#FDCE43");
        COLOR_NAMES.put("darkorchid",      "#9932CC");
        COLOR_NAMES.put("emerald",         "#50C878");
        COLOR_NAMES.put("goldenrod",       "#DAA520");
        COLOR_NAMES.put("greenyellow",     "#ADFF2F");
        COLOR_NAMES.put("junglegreen",     "#29AB87");
        COLOR_NAMES.put("lavender",        "#BF8FCC");
        COLOR_NAMES.put("limegreen",       "#32CD32");
        COLOR_NAMES.put("mahogany",        "#C04000");
        COLOR_NAMES.put("mauve",           "#E0B0FF");
        COLOR_NAMES.put("midnightblue",    "#191970");
        COLOR_NAMES.put("mulberry",        "#8B3868");
        COLOR_NAMES.put("olivegreen",      "#8B8B00");
        COLOR_NAMES.put("orangered",       "#FF4500");
        COLOR_NAMES.put("orchid",          "#AF7FAC");
        COLOR_NAMES.put("peach",           "#FFCBA4");
        COLOR_NAMES.put("periwinkle",      "#7A80B5");
        COLOR_NAMES.put("pinegreen",       "#01796F");
        COLOR_NAMES.put("processblue",     "#0086D6");
        COLOR_NAMES.put("rawsienna",       "#974706");
        COLOR_NAMES.put("redorange",       "#F0422F");
        COLOR_NAMES.put("redviolet",       "#A55D6D");
        COLOR_NAMES.put("rhodamine",       "#FBA0E3");
        COLOR_NAMES.put("royalblue",       "#4169E1");
        COLOR_NAMES.put("royalpurple",     "#6B3FA0");
        COLOR_NAMES.put("rubinered",       "#E62060");
        COLOR_NAMES.put("seagreen",        "#2E8B57");
        COLOR_NAMES.put("sepia",           "#6B3A2A");
        COLOR_NAMES.put("skyblue",         "#87CEEB");
        COLOR_NAMES.put("springgreen",     "#A0FFA0");
        COLOR_NAMES.put("thistle",         "#D8B9D8");
        COLOR_NAMES.put("turquoise",       "#46C0C6");
        COLOR_NAMES.put("violetred",       "#A14D5F");
        COLOR_NAMES.put("wildstrawberry",  "#EE3B5B");
        COLOR_NAMES.put("yellowgreen",     "#9ACD32");
        COLOR_NAMES.put("yelloworange",    "#FFB347");

        // ── SVG 扩展色（svgnames）──
        COLOR_NAMES.put("aliceblue",            "#F0F8FF");
        COLOR_NAMES.put("antiquewhite",         "#FAEBD7");
        COLOR_NAMES.put("bisque",               "#FFE4C4");
        COLOR_NAMES.put("blanchedalmond",       "#FFEBCD");
        COLOR_NAMES.put("burlywood",            "#DEB887");
        COLOR_NAMES.put("chartreuse",           "#7FFF00");
        COLOR_NAMES.put("chocolate",            "#D2691E");
        COLOR_NAMES.put("cornsilk",             "#FFF8DC");
        COLOR_NAMES.put("darkgoldenrod",        "#B8860B");
        COLOR_NAMES.put("darkkhaki",            "#BDB76B");
        COLOR_NAMES.put("darkolivegreen",       "#556B2F");
        COLOR_NAMES.put("darkorange",           "#FF8C00");
        COLOR_NAMES.put("darksalmon",           "#E9967A");
        COLOR_NAMES.put("darkseagreen",         "#8FBC8F");
        COLOR_NAMES.put("darkslateblue",        "#483D8B");
        COLOR_NAMES.put("darkslategray",        "#2F4F4F");
        COLOR_NAMES.put("darkturquoise",        "#00CED1");
        COLOR_NAMES.put("darkviolet",           "#9400D3");
        COLOR_NAMES.put("deeppink",             "#FF1493");
        COLOR_NAMES.put("deepskyblue",          "#00BFFF");
        COLOR_NAMES.put("dimgray",              "#696969");
        COLOR_NAMES.put("dodgerblue",           "#1E90FF");
        COLOR_NAMES.put("firebrick",            "#B22222");
        COLOR_NAMES.put("floralwhite",          "#FFFAF0");
        COLOR_NAMES.put("gainsboro",            "#DCDCDC");
        COLOR_NAMES.put("ghostwhite",           "#F8F8FF");
        COLOR_NAMES.put("honeydew",             "#F0FFF0");
        COLOR_NAMES.put("hotpink",              "#FF69B4");
        COLOR_NAMES.put("indianred",            "#CD5C5C");
        COLOR_NAMES.put("lavenderblush",        "#FFF0F5");
        COLOR_NAMES.put("lawngreen",            "#7CFC00");
        COLOR_NAMES.put("lemonchiffon",         "#FFFACD");
        COLOR_NAMES.put("lightblue",            "#ADD8E6");
        COLOR_NAMES.put("lightcoral",           "#F08080");
        COLOR_NAMES.put("lightcyan",            "#E0FFFF");
        COLOR_NAMES.put("lightgoldenrodyellow", "#FAFAD2");
        COLOR_NAMES.put("lightpink",            "#FFB6C1");
        COLOR_NAMES.put("lightsalmon",          "#FFA07A");
        COLOR_NAMES.put("lightseagreen",        "#20B2AA");
        COLOR_NAMES.put("lightskyblue",         "#87CEFA");
        COLOR_NAMES.put("lightslategray",       "#778899");
        COLOR_NAMES.put("lightsteelblue",       "#B0C4DE");
        COLOR_NAMES.put("lightyellow",          "#FFFFE0");
        COLOR_NAMES.put("linen",                "#FAF0E6");
        COLOR_NAMES.put("mediumaquamarine",     "#66CDAA");
        COLOR_NAMES.put("mediumblue",           "#0000CD");
        COLOR_NAMES.put("mediumorchid",         "#BA55D3");
        COLOR_NAMES.put("mediumpurple",         "#9370DB");
        COLOR_NAMES.put("mediumseagreen",       "#3CB371");
        COLOR_NAMES.put("mediumslateblue",      "#7B68EE");
        COLOR_NAMES.put("mediumspringgreen",    "#00FA9A");
        COLOR_NAMES.put("mediumturquoise",      "#48D1CC");
        COLOR_NAMES.put("mediumvioletred",      "#C71585");
        COLOR_NAMES.put("mintcream",            "#F5FFFA");
        COLOR_NAMES.put("mistyrose",            "#FFE4E1");
        COLOR_NAMES.put("moccasin",             "#FFE4B5");
        COLOR_NAMES.put("navajowhite",          "#FFDEAD");
        COLOR_NAMES.put("oldlace",              "#FDF5E6");
        COLOR_NAMES.put("olivedrab",            "#6B8E23");
        COLOR_NAMES.put("palegoldenrod",        "#EEE8AA");
        COLOR_NAMES.put("palegreen",            "#98FB98");
        COLOR_NAMES.put("paleturquoise",        "#AFEEEE");
        COLOR_NAMES.put("palevioletred",        "#DB7093");
        COLOR_NAMES.put("papayawhip",           "#FFEFD5");
        COLOR_NAMES.put("peachpuff",            "#FFDAB9");
        COLOR_NAMES.put("peru",                 "#CD853F");
        COLOR_NAMES.put("powderblue",           "#B0E0E6");
        COLOR_NAMES.put("rebeccapurple",        "#663399");
        COLOR_NAMES.put("rosybrown",            "#BC8F8F");
        COLOR_NAMES.put("saddlebrown",          "#8B4513");
        COLOR_NAMES.put("sandybrown",           "#F4A460");
        COLOR_NAMES.put("seashell",             "#FFF5EE");
        COLOR_NAMES.put("sienna",               "#A0522D");
        COLOR_NAMES.put("silver",               "#C0C0C0");
        COLOR_NAMES.put("slateblue",            "#6A5ACD");
        COLOR_NAMES.put("slategray",            "#708090");
        COLOR_NAMES.put("snow",                 "#FFFAFA");
        COLOR_NAMES.put("steelblue",            "#4682B4");
        COLOR_NAMES.put("tomato",               "#FF6347");
        COLOR_NAMES.put("wheat",                "#F5DEB3");
        COLOR_NAMES.put("whitesmoke",           "#F5F5F5");
    }

    // ========== 定界符归一化（支持多种 LaTeX 数学定界符） ==========

    /** 显示公式 \[...\] → $$...$$
     * (?<!\\) 负向后瞻排除 LaTeX 换行命令 \\[6pt]、\\[1em] 等，
     * 避免将 \\[ 后半截的 \[ 误识别为定界符。 */
    private static final Pattern DISPLAY_BRACKET = Pattern.compile(
            "(?<!\\\\)" + Pattern.quote("\\[") + "([\\s\\S]*?)" + Pattern.quote("\\]")
    );
    /** 显示公式环境 \begin{env}...\end{env} → $$...$$ */
    private static final Pattern DISPLAY_ENV = Pattern.compile(
            "\\\\begin\\{(equation\\*|equation|align\\*|align|gather\\*|gather|eqnarray\\*|eqnarray|displaymath)\\}"
                    + "([\\s\\S]*?)\\\\end\\{\\1\\}"
    );
    /** 行内公式 \(...\) → $...$ */
    private static final Pattern INLINE_PAREN = Pattern.compile(
            Pattern.quote("\\(") + "([\\s\\S]*?)" + Pattern.quote("\\)")
    );
    /** 行内公式环境 \begin{math}...\end{math} → $...$ */
    private static final Pattern INLINE_MATH_ENV = Pattern.compile(
            Pattern.quote("\\begin{math}") + "([\\s\\S]*?)" + Pattern.quote("\\end{math}")
    );

    /**
     * 将所有标准 LaTeX 数学定界符统一转换为 $...$ / $$...$$。
     * 顺序：先显示后行内，避免 $$ 被拆成单 $。
     */
    static String normalizeMathDelimiters(String text) {
        if (text == null || text.isEmpty()) return text;
        // 显示公式 \[...\] → $$...$$（\$ 是 Matcher 替换中的字面 $）
        text = DISPLAY_BRACKET.matcher(text).replaceAll("\\$\\$$1\\$\\$");
        // 显示公式环境 \begin{env}...\end{env} → $$...$$（$2 是组 2，组 1 是环境名）
        text = DISPLAY_ENV.matcher(text).replaceAll("\\$\\$$2\\$\\$");
        // 行内公式 \(...\) → $...$
        text = INLINE_PAREN.matcher(text).replaceAll("\\$$1\\$");
        // 行内公式环境 \begin{math}...\end{math} → $...$
        text = INLINE_MATH_ENV.matcher(text).replaceAll("\\$$1\\$");
        return text;
    }

    /**
     * 对整个 Markdown 文本执行完整预处理。
     * 顺序：定界符归一化 → 合并碎片 → 命名色转 hex → \color 着色域
     */
    public static String preprocess(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        String normalized = normalizeMathDelimiters(markdown);
        String merged = mergeFragmentedBlocks(normalized);
        String colorNamed = convertColorNamesInFormulas(merged);
        String result = convertColorInFormulas(colorNamed);
        Log.d("LatexPreprocess", "转换结果:\n" + result);
        return result;
    }

    // ========== 规则 2：合并碎片化的行列式/矩阵块 ==========

    /**
     * 合并连续、相同定界符、且含未闭合 \begin/\end 的 $$..$$ 碎片块。
     * 例如矩阵跨行：
     *   $$\begin{vmatrix}$$
     *   $$1 & 2 \\$$
     *   $$3 & 4$$
     *   $$\end{vmatrix}$$
     *   → 合并为单个 $$...$$ 块
     *
     * 不会合并独立的非碎片块（如相邻的普通行内公式）。
     */
    static String mergeFragmentedBlocks(String text) {
        Matcher m = LATEX_BLOCK.matcher(text);

        // 收集所有匹配的定界符、位置和内容
        int matchCount = 0;
        // 临时容器：每次 find 时扩展
        List<String> delims = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<String> bodies = new ArrayList<>();

        while (m.find()) {
            delims.add(m.group(1));   // "$" 或 "$$"
            spans.add(new int[]{m.start(), m.end()});
            bodies.add(m.group(2));
            matchCount++;
        }
        if (matchCount == 0) return text;

        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        int i = 0;

        while (i < matchCount) {
            // 追加当前位置到本块之间的原文
            sb.append(text, cursor, spans.get(i)[0]);

            // ---- 收集可合并的连续块 ----
            int runEnd = i;
            while (runEnd + 1 < matchCount) {
                // 条件 1：仅空白分隔
                String gap = text.substring(spans.get(runEnd)[1], spans.get(runEnd + 1)[0]);
                if (!gap.trim().isEmpty()) break;

                // 条件 2：相同定界符（$ 和 $$ 不混用）
                if (!delims.get(runEnd).equals(delims.get(runEnd + 1))) break;

                // 条件 3：至少一个块含未闭合的 \begin/\end 环境（matrix 碎片特征）
                boolean anyUnmatched = false;
                for (int k = i; k <= runEnd + 1; k++) {
                    if (hasUnmatchedEnv(bodies.get(k))) {
                        anyUnmatched = true;
                        break;
                    }
                }
                if (!anyUnmatched) break;

                runEnd++;
            }

            if (i == runEnd) {
                // 单一块，原样保留
                sb.append(text, spans.get(i)[0], spans.get(i)[1]);
            } else {
                // 多块合并（用 $$ 包裹）
                StringBuilder merged = new StringBuilder(bodies.get(i));
                for (int j = i + 1; j <= runEnd; j++) {
                    merged.append(' ').append(bodies.get(j));
                }
                sb.append("$$").append(merged.toString().trim()).append("$$");
            }

            cursor = spans.get(runEnd)[1];
            i = runEnd + 1;
        }

        // 尾部原文
        if (cursor < text.length()) {
            sb.append(text, cursor, text.length());
        }
        return sb.toString();
    }

    /**
     * 检查 latex 内容中 \begin{xxx} 和 \end{xxx} 的数量是否不相等
     * （即存在未闭合的环境，是碎片块的特征）。
     */
    static boolean hasUnmatchedEnv(String latex) {
        Matcher beginM = BEGIN_PATTERN.matcher(latex);
        int beginCount = 0;
        while (beginM.find()) beginCount++;

        Matcher endM = Pattern.compile("\\\\end\\{").matcher(latex);
        int endCount = 0;
        while (endM.find()) endCount++;

        return beginCount != endCount;
    }

    // ========== 规则 3：命名色 → #hex ==========

    /**
     * 在每个 $..$ / $$..$$ 公式内部，将 \colorbox{red}、\textcolor{darkblue}
     * 等命名色换为 #hex 码，避免 jlatexmath 按 radix 16 解析失败。
     */
    static String convertColorNamesInFormulas(String text) {
        Matcher m = LATEX_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (m.find()) {
            sb.append(text, cursor, m.start());
            String delim = m.group(1);
            String content = m.group(2);
            sb.append(delim).append(convertColorNames(content)).append(delim);
            cursor = m.end();
        }
        if (cursor < text.length()) {
            sb.append(text, cursor, text.length());
        }
        return sb.toString();
    }

    /**
     * 在单条公式内容中，把 \colorbox/color/textcolor 的命名色参数替换为 hex。
     * 对于 xcolor 混合色如 green!70!black，取 base 色名（! 之前的部分）查找。
     */
    static String convertColorNames(String latex) {
        Matcher m = COLOR_ARG_PATTERN.matcher(latex);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(latex, last, m.start());
            String cmd = m.group(1);
            String name = m.group(2);
            String hex = COLOR_NAMES.get(name.toLowerCase());

            // xcolor 混合色 "green!70!black"：取 base 色名 "green"
            if (hex == null) {
                int bang = name.indexOf('!');
                String base = bang > 0 ? name.substring(0, bang) : name;
                hex = COLOR_NAMES.get(base.toLowerCase());
            }

            if (hex != null) {
                sb.append('\\').append(cmd).append('{').append(hex).append('}');
            } else {
                sb.append(latex, m.start(), m.end());
            }
            last = m.end();
        }
        sb.append(latex, last, latex.length());
        return sb.toString();
    }

    // ========== 规则 1：\color → {\color ...} ==========

    /**
     * 在每个 $..$ / $$..$$ 公式内部，将 \color{xxx} 整体
     * 包裹为 {\color{xxx} ...}。
     */
    static String convertColorInFormulas(String text) {
        Matcher m = LATEX_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (m.find()) {
            sb.append(text, cursor, m.start());
            String delim = m.group(1);
            String content = m.group(2);
            sb.append(delim).append(convertColor(content)).append(delim);
            cursor = m.end();
        }
        if (cursor < text.length()) {
            sb.append(text, cursor, text.length());
        }
        return sb.toString();
    }

    /**
     * 将公式内容中所有 \color{xxx} 转为 \textcolor{xxx}{作用域内容}。
     * 保留 \color 之前的文本；通过花括号匹配确定 \color 的作用域边界；
     * 遇到下一个 \color{ 时提前结束作用域，由外层循环继续处理。
     *
     * 注意：作用域尾部空白/换行会被剥离到 \textcolor{} 的 } 外面，
     * 避免 $$ 被包进作用域内导致 Markwon 块级公式识别失败。
     */
    static String convertColor(String latex) {
        Matcher m = Pattern.compile("\\\\color\\{([^}]+)\\}").matcher(latex);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(latex, last, m.start());
            String color = m.group(1);
            int afterColor = m.end();
            int scopeEnd = findColorScopeEnd(latex, afterColor);

            // 剥离尾部空白/换行到 } 外面，使 $$ 能独立占行
            String scopeContent = latex.substring(afterColor, scopeEnd);
            String trimmedContent = scopeContent.stripTrailing();
            String trailingWhitespace = scopeContent.substring(trimmedContent.length());

            sb.append("\\textcolor{").append(color).append("}{")
              .append(trimmedContent).append("}")
              .append(trailingWhitespace);

            last = scopeEnd;
        }
        sb.append(latex, last, latex.length());
        return sb.toString();
    }

    /**
     * 查找 \color 的作用域结束位置（修复版）。
     * 跳过 \cmd{...} 命令体，避免 \begin{cases}、\sum_{...}、\text{...} 等
     * 内部花括号干扰层级计数。braceLevel 只追踪"属于当前裸作用域"的花括号。
     */
    private static int findColorScopeEnd(String latex, int start) {
        int braceLevel = 0;
        int i = start;
        while (i < latex.length()) {
            char c = latex.charAt(i);

            if (c == '\\') {
                // 遇到下一个裸 \color{ → 交给外层循环处理
                if (latex.startsWith("\\color{", i)) {
                    return i;
                }
                // 跳过反斜杠及命令名（字母序列）
                i++;
                while (i < latex.length() && Character.isLetter(latex.charAt(i))) {
                    i++;
                }
                // 命令后紧跟 { → 跳过整个命令参数块，不计入层级
                if (i < latex.length() && latex.charAt(i) == '{') {
                    i = skipBraceBlock(latex, i);
                }
                continue;
            }

            if (c == '{') {
                braceLevel++;
            } else if (c == '}') {
                braceLevel--;
                if (braceLevel < 0) {
                    return i;
                }
            }
            i++;
        }
        return latex.length();
    }

    /**
     * 从 start（指向 '{'）跳过整个匹配的花括号块，返回 '}' 之后的位置。
     * 内部递归跳过嵌套命令，正确处理 \cmd{...{...}...} 结构。
     */
    private static int skipBraceBlock(String latex, int start) {
        int level = 1;
        int i = start + 1;
        while (i < latex.length() && level > 0) {
            char c = latex.charAt(i);
            if (c == '\\') {
                i++;
                while (i < latex.length() && Character.isLetter(latex.charAt(i))) {
                    i++;
                }
                if (i < latex.length() && latex.charAt(i) == '{') {
                    i = skipBraceBlock(latex, i);
                }
                continue;
            }
            if (c == '{') {
                level++;
            } else if (c == '}') {
                level--;
            }
            i++;
        }
        return i;
    }
}
