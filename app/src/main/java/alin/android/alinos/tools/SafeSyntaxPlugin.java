package alin.android.alinos.tools;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.commonmark.node.FencedCodeBlock;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.syntax.SyntaxHighlight;
import io.noties.markwon.syntax.Prism4jSyntaxHighlight;
import io.noties.markwon.syntax.Prism4jTheme;
import io.noties.prism4j.Prism4j;

/**
 * Markwon 代码块语法高亮插件 —— 带未知语言兜底降级策略。
 * <p>
 * 已配置语言 → Prism4j 彩色高亮不变；
 * 未知语言 / 无标注 → 纯文本展示，不抛异常、不错乱、不空白。
 * <p>
 * 对齐项目「能解析就渲染，解析失败原样输出」的统一设计思想。
 */
public class SafeSyntaxPlugin extends AbstractMarkwonPlugin {

    private final Prism4j prism4j;
    private final SyntaxHighlight delegate;

    public SafeSyntaxPlugin(@NonNull Prism4j prism4j, @NonNull Prism4jTheme theme) {
        this.prism4j = prism4j;
        // Prism4jSyntaxHighlight 是标准高亮引擎，只对已注册语言生效
        this.delegate = Prism4jSyntaxHighlight.create(prism4j, theme);
    }

    @Override
    public void configureConfiguration(@NonNull MarkwonConfiguration.Builder builder) {
        builder.syntaxHighlight(new SyntaxHighlight() {
            @NonNull
            @Override
            public CharSequence highlight(@Nullable String info, @NonNull String code) {
                // 无语言标注或空内容 → 原样返回
                if (info == null || code.isEmpty()) {
                    return code;
                }
                // 检查 Prism4j 是否有该语言的语法定义
                // null → 未注册语言，降级纯文本，免崩溃免错乱
                if (prism4j.grammar(info.trim().toLowerCase()) == null) {
                    return code;
                }
                // 已注册语言 → 委托标准高亮引擎
                return delegate.highlight(info, code);
            }
        });
    }

    @Override
    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
        builder.on(FencedCodeBlock.class, (visitor, fencedCodeBlock) -> {
            final String info = fencedCodeBlock.getInfo();
            final String literal = fencedCodeBlock.getLiteral().trim();
            visitor.builder().append(
                    visitor.configuration().syntaxHighlight().highlight(info, literal)
            );
        });
    }
}
