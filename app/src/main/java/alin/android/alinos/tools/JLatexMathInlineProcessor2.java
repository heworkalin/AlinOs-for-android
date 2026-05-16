package alin.android.alinos.tools;

import androidx.annotation.Nullable;

import org.commonmark.node.Node;

import io.noties.markwon.ext.latex.JLatexMathNode;
import io.noties.markwon.inlineparser.InlineProcessor;

import java.util.regex.Pattern;

/**
 * 行内 $ 内联处理器，只处理 $..$ 行内公式（不碰 $$..$$ 块级公式）。
 *
 * 正则说明：
 *   (?<!\)       — 前导字符不是反斜杠（排除转义的 \$）
 *   \$(?!\$)     — 匹配一个单独的 $ 作为开头（排除 $$ 开头）
 *   ((?:\.|[^$\\])*) — 公式内容，不含未转义的 $ 或 \
 *   \$(?!\$)     — 匹配一个单独的 $ 作为结尾（排除 $$ 结尾）
 */
public class JLatexMathInlineProcessor2 extends InlineProcessor {

    private static final Pattern RE = Pattern.compile(
            "(?<!\\\\)\\$(?!\\$)((?:\\\\.|[^$\\\\])*)\\$(?!\\$)"
    );

    @Override
    public char specialCharacter() {
        return '$';
    }

    @Nullable
    @Override
    protected Node parse() {
        CharSequence latex = this.match(RE);
        if (latex == null) {
            return null;
        }

        int len = latex.length();
        // 根据第二个字符是否为 $ 判断定界符长度（1 或 2）
        int delim = (len > 1 && latex.charAt(1) == '$') ? 2 : 1;

        // 边界保护：有效公式至少需要两个定界符，且内容区非空
        if (delim >= len - delim) {
            return null;
        }

        JLatexMathNode node = new JLatexMathNode();
        node.latex(latex.subSequence(delim, len - delim).toString());
        return node;
    }
}
