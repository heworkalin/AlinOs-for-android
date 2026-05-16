package alin.android.alinos.dev;

import android.app.AlertDialog;
//import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;


import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.R;
import alin.android.alinos.adapter.ChatDevAdapter;
import alin.android.alinos.bean.ChatMessage;
import alin.android.alinos.tools.JLatexMathInlineProcessor2;
import alin.android.alinos.tools.LatexPreprocessor;
import alin.android.alinos.tools.SafeSyntaxPlugin;

import io.noties.markwon.Markwon;
import io.noties.markwon.core.CorePlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.syntax.Prism4jThemeDefault;

//import io.noties.prism4j.GrammarLocator;
import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;


/**
 * Agent 对话测试页面 — Markdown + LaTeX 混合渲染验证
 *
 * 覆盖测试项：
 *   标题 / 列表 / 表格 / 链接 / 引用 / 分割线 / 代码高亮 / 行内公式 / 块级公式 / 暗色模式
 */
@PrismBundle(include = {
        "java", "kotlin", "python", "cpp", "c",
        "javascript", "css", "json",
        "yaml", "sql", "go"
})
public class ChatActivityDev extends AppCompatActivity {

    private RecyclerView rvMessages;
    private EditText etInput;
    private TextView tvThemeToggle;

    private List<ChatMessage> messages;
    private ChatDevAdapter adapter;
    private View rootView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_dev);

        rootView = findViewById(android.R.id.content);
        rvMessages = findViewById(R.id.rv_messages);
        etInput = findViewById(R.id.et_input);
        Button btnSend = findViewById(R.id.btn_send);
        tvThemeToggle = findViewById(R.id.tv_theme_toggle);
        TextView tvAddTest = findViewById(R.id.tv_add_test);

        // 1. 创建 Markwon 实例（核心：builder → usePlugin → build）
        Markwon markwon = createMarkwon();

        // 2. 初始化消息列表
        messages = new ArrayList<>();
        adapter = new ChatDevAdapter(messages, markwon);
        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        rvMessages.setAdapter(adapter);

        // 3. 添加欢迎消息
        addAiMessage(getString(R.string.chat_dev_welcome));

        // 4. 绑定事件
        btnSend.setOnClickListener(v -> sendMessage());
        tvThemeToggle.setOnClickListener(v -> toggleTheme());
        tvAddTest.setOnClickListener(v -> showTestCasesDialog());
    }

    /** 构建 Markwon 实例，配置代码高亮、LaTeX 公式等全部插件 */
    private Markwon createMarkwon() {
        final float density = getResources().getDisplayMetrics().scaledDensity;
        final int latexTextSize = (int) (16F * density);

        // 使用 prism4j-bundler 编译生成的 GrammarLocatorDef
        Prism4j prism4j = new Prism4j(new GrammarLocatorDef());


        return Markwon.builder(this)
                .usePlugin(CorePlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create(parser ->
                        parser.addInlineProcessor(new JLatexMathInlineProcessor2())))
                .usePlugin(StrikethroughPlugin.create())
                //.usePlugin(LinkifyPlugin.create())   // import android.text.util.Linkify;但是这里默认不兼容这个方案直接使用这个，所以说直接注释掉。
                .usePlugin(TablePlugin.create(this))
                .usePlugin(TaskListPlugin.create(this))
                .usePlugin(new SafeSyntaxPlugin(prism4j, Prism4jThemeDefault.create()))
                .usePlugin(JLatexMathPlugin.create(latexTextSize, builder -> {
                    builder.inlinesEnabled(true);   // 保留行内公式（可选，若只用自定义处理器可设为 false）
                    builder.blocksEnabled(true);
                    builder.errorHandler((latex, error) -> {
                        Log.e("LaTeX", "公式渲染失败: " + latex, error);
                        return null;
                    });
                }))

                .build();
    }

    // ======================== 消息操作 ========================

    private void sendMessage() {
        String text = etInput.getText().toString().trim();

        // 检测不可见字符（零宽字符、控制字符等）
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (Character.getType(chars[i]) == Character.FORMAT ||
                (Character.isISOControl(chars[i]) && chars[i] != '\n')) {
                Log.w("Input", "发现不可见字符 @" + i + " : \\u" + Integer.toHexString(chars[i]));
            }
        }

        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show();
            return;
        }
        addUserMessage(text);
        addAiMessage(text);
        etInput.setText("");
    }

    private void addUserMessage(String content) {
        messages.add(new ChatMessage(ChatMessage.TYPE_USER, content));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.smoothScrollToPosition(messages.size() - 1);
    }

    private void addAiMessage(String content) {
        Log.d("ChatDebug", "AI消息内容: " + content);
        messages.add(new ChatMessage(ChatMessage.TYPE_AI, content));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.smoothScrollToPosition(messages.size() - 1);
    }

    // ======================== 暗色模式 ========================

    private void toggleTheme() {
        boolean dark = !adapter.isDarkMode();
        adapter.setDarkMode(dark);
        tvThemeToggle.setText(dark ? "☀️ 亮色" : "🌙 暗色");
        int bg = dark ? 0xFF121212 : 0xFFF5F5F5;
        rootView.setBackgroundColor(bg);
        rvMessages.setBackgroundColor(bg);
    }

    // ======================== 预置测试用例 ========================

    private void showTestCasesDialog() {
        String[] items = {
                "① 基础语法（标题/列表/引用/代码块）",
                "② 表格",
                "③ 代码高亮（Java / Python / Kotlin）",
                "④ LaTeX 行内公式",
                "⑤ LaTeX 块级公式",
                "⑥ 混合复杂场景",
                "⑦ 边缘测试（长文本/特殊字符）",
                "⑧ LaTeX 彩色/字号/背景",
                "⑨ 全部插入"
        };
        new AlertDialog.Builder(this)
                .setTitle("选择测试用例")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: addAiMessage(TEST_BASIC_SYNTAX); break;
                        case 1: addAiMessage(TEST_TABLE); break;
                        case 2: addAiMessage(TEST_CODE_HIGHLIGHT); break;
                        case 3: addAiMessage(TEST_INLINE_LATEX); break;
                        case 4: addAiMessage(TEST_BLOCK_LATEX); break;
                        case 5: addAiMessage(TEST_MIXED); break;
                        case 6: addAiMessage(TEST_EDGE); break;
                        case 7: addAiMessage(TEST_ALL_LATEX_BG_FONT); break;
                        case 8:
                            addAiMessage(TEST_BASIC_SYNTAX);
                            addAiMessage(TEST_TABLE);
                            addAiMessage(TEST_CODE_HIGHLIGHT);
                            addAiMessage(TEST_INLINE_LATEX);
                            addAiMessage(TEST_BLOCK_LATEX);
                            addAiMessage(TEST_MIXED);
                            addAiMessage(TEST_EDGE);
                            addAiMessage(TEST_ALL_LATEX_BG_FONT);
                            break;
                    }
                })
                .show();
    }

    // ---- 测试用例字符串常量 ----

    private static final String TEST_BASIC_SYNTAX =
            "# 一级标题\n\n"
            + "## 二级标题\n\n"
            + "### 三级标题\n\n"
            + "**粗体文本** 与 *斜体文本* 与 ~~删除线~~\n\n"
            + "无序列表：\n"
            + "- 苹果\n"
            + "- 香蕉\n"
            + "- 樱桃\n\n"
            + "有序列表：\n"
            + "1. 第一项\n"
            + "2. 第二项\n"
            + "3. 第三项\n\n"
            + "> 这是一段引用文本\n"
            + "> 引用第二行\n\n"
            + "行内代码：`System.out.println(\"Hello\");`\n\n"
            + "代码块：\n```\n"
            + "public class Hello {\n"
            + "    public static void main(String[] args) {\n"
            + "        System.out.println(\"Hello World\");\n"
            + "    }\n"
            + "}\n```\n\n"
            + "[访问 GitHub](https://github.com)";

    private static final String TEST_TABLE =
            "| 名称   | 价格   | 数量 |\n"
            + "|--------|:------:|:----|\n"
            + "| 苹果   | ¥5.0   | 100 |\n"
            + "| 香蕉   | ¥3.5   | 200 |\n"
            + "| 樱桃   | ¥12.0  | 50  |\n\n"
            + "右对齐 / 居中 / 左对齐测试：\n\n"
            + "| 左对齐 | 居中对齐 | 右对齐 |\n"
            + "|:-------|:--------:|-------:|\n"
            + "| A      | B        | C      |\n"
            + "| D      | E        | F      |";

    private static final String TEST_CODE_HIGHLIGHT =
            "**Java：**\n```java\n"
            + "public class Calculator {\n"
            + "    private int result;\n\n"
            + "    public int add(int a, int b) {\n"
            + "        return a + b;\n"
            + "    }\n\n"
            + "    public static void main(String[] args) {\n"
            + "        Calculator calc = new Calculator();\n"
            + "        System.out.println(calc.add(3, 4));\n"
            + "    }\n"
            + "}\n```\n\n"
            + "**Python：**\n```python\n"
            + "def fibonacci(n):\n"
            + "    a, b = 0, 1\n"
            + "    for _ in range(n):\n"
            + "        yield a\n"
            + "        a, b = b, a + b\n\n"
            + "for i, val in enumerate(fibonacci(10)):\n"
            + "    print(f\"F{i} = {val}\")\n"
            + "```\n\n"
            + "**Kotlin：**\n```kotlin\n"
            + "data class Person(val name: String, val age: Int)\n\n"
            + "fun main() {\n"
            + "    val people = listOf(\n"
            + "        Person(\"Alice\", 30),\n"
            + "        Person(\"Bob\", 25)\n"
            + "    )\n"
            + "    people.forEach { println(it) }\n"
            + "}\n```";

    private static final String TEST_INLINE_LATEX =
            "### 行内公式测试\n\n"
            + "爱因斯坦质能方程：$E=mc^2$\n\n"
            + "勾股定理：$a^2 + b^2 = c^2$\n\n"
            + "牛顿第二定律：$F = ma$\n\n"
            + "欧拉公式：$e^{i\\pi} + 1 = 0$\n\n"
            + "正态分布：$X \\sim N(\\mu, \\sigma^2)$\n\n"
            + "三角函数：$\\sin^2 \\theta + \\cos^2 \\theta = 1$";

    private static final String TEST_BLOCK_LATEX =
            "### 块级公式测试\n\n"
            + "**求根公式：**\n"
            + "$$x = {-b \\pm \\sqrt{b^2-4ac} \\over 2a}$$\n\n"
            + "**高斯积分：**\n"
            + "$$\\int_{-\\infty}^{\\infty} e^{-x^2} dx = \\sqrt{\\pi}$$\n\n"
            + "**求和公式：**\n"
            + "$$\\sum_{i=1}^n i = \\frac{n(n+1)}{2}$$\n\n"
            + "**泰勒展开：**\n"
            + "$$e^x = \\sum_{n=0}^{\\infty} \\frac{x^n}{n!}$$\n\n"
            + "**矩阵：**\n"
            + "$$\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}$$";

    @SuppressWarnings("StringBufferReplaceableByString")
    private static final String TEST_MIXED =
            "# Markdown+LaTeX 综合混合测试\n\n"
            + "日常学习中会用到各类数学物理公式，搭配表格整理更加清晰好用。\n\n"
            + "## 公式整理表格\n\n"
            + "| 公式名称 | 公式表达式 | 简单说明 |\n"
            + "| ---- | ---- | ---- |\n"
            + "| 勾股定理 | $\\colorbox{white}{\\normalsize \\color{black} a^2+b^2=c^2}$ | 直角三角形边长 |\n"
            + "| 质能方程 | $\\colorbox{yellow}{\\small \\color{red} E=mc^2}$ | 相对论能量公式 |\n"
            + "| 牛顿力学 | $\\colorbox{gray}{\\large \\color{blue} F=ma}$ | 经典力学定律 |\n"
            + "| 欧拉恒等式 | $\\colorbox{pink}{\\huge \\color{purple} e^{i\\pi}+1=0}$ | 最美数学公式 |\n"
            + "| 速度公式 | $\\colorbox{cyan}{\\tiny \\color{black} v=at}$ | 匀加速运动 |\n\n"
            + "## 正文内嵌行内公式\n\n"
            + "日常计算路程可以使用匀速公式$\\colorbox{orange}{\\normalsize \\color{green} S=vt}$，\n"
            + "结合基础变量公式$\\colorbox{white}{\\Large \\color{darkblue} P=FV}$完成整套运算。\n\n"
            + "## 大号独立块级公式展示\n\n"
            + "一元二次方程通用求根公式：\n"
            + "$$\\colorbox{#f8f8f8}{\\Large \\color{#111111} x = \\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}}$$\n\n"
            + "自然数连续求和公式：\n"
            + "$$\\colorbox{#f0f8ff}{\\large \\color{#005588} \\sum_{i=1}^n i=\\frac{n(n+1)}{2}}$$\n\n"
            + "三角函数基础恒等式：\n"
            + "$$\\colorbox{#fff5f5}{\\huge \\color{#bb2222} \\sin^2\\theta+\\cos^2\\theta=1}$$";

    private static final String TEST_EDGE =
            "### 边缘测试\n\n"
            + "**1. 极长文本：**\n\n"
            + "这是一段很长的文本用来测试换行效果。"
            + "重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·"
            + "重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·"
            + "重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·重复·重复。\n\n"
            + "**2. 深度嵌套列表：**\n"
            + "- 一级列表 A\n"
            + "  - 二级列表 A-1\n"
            + "    - 三级列表 A-1-a\n"
            + "      - 四级列表 A-1-a-i\n"
            + "- 一级列表 B\n"
            + "  1. 有序子项 1\n"
            + "  2. 有序子项 2\n\n"
            + "**3. 特殊字符：**\n\n"
            + "美元符号：$10.99 与 $20.00\n\n"
            + "特殊符号：© ® ™ ± × ÷ ≠ ≤ ≥ ∞\n\n"
            + "编程对比：true vs. `true`，null vs. `null`";

    private static final String TEST_ALL_LATEX_BG_FONT =
            "### 行内LaTeX统一样式批量测试\n\n"
            + "普通默认公式：$a^2+b^2=c^2$\n\n"
            + "黄色底小号红色：$\\colorbox{yellow}{\\small \\color{red} E=mc^2}$\n\n"
            + "灰色底大号蓝色：$\\colorbox{gray}{\\large \\color{blue} F=ma}$\n\n"
            + "粉色底超大紫色：$\\colorbox{pink}{\\huge \\color{purple} e^{i\\pi}+1=0}$\n\n"
            + "青色底极小黑色：$\\colorbox{cyan}{\\tiny \\color{black} v=at}$\n\n"
            + "橙色底标准绿色：$\\colorbox{orange}{\\normalsize \\color{green} S=vt}$\n\n"
            + "白色底更大深蓝：$\\colorbox{white}{\\Large \\color{darkblue} P=FV}$\n\n"
            + "黑色底巨大白色：$\\colorbox{black}{\\Huge \\color{white} \\Delta x=v_0 t}$\n\n"
            + "红色底大号黄色：$\\colorbox{red}{\\large \\color{yellow} \\sum_{n=1}^\\infty \\frac{1}{n^2}}$\n\n"
            + "蓝色底小号白色：$\\colorbox{blue}{\\small \\color{white} \\int_a^b f(x)dx}$\n\n"
            + "绿色底超大金色：$\\colorbox{green}{\\huge \\color{gold} \\lim_{x\\to 0} \\frac{\\sin x}{x}}$\n\n"
            + "紫色底极小青色：$\\colorbox{purple}{\\tiny \\color{cyan} \\nabla \\cdot \\vec{E}}$\n\n"
            + "橙色底更大白色：$\\colorbox{orange}{\\Large \\color{white} \\oint \\vec{B} \\cdot d\\vec{l}}$\n\n"
            + "灰色底巨大红色：$\\colorbox{gray}{\\Huge \\color{red} \\frac{d}{dx}e^x = e^x}$\n\n"
            + "粉色底标准深蓝：$\\colorbox{pink}{\\normalsize \\color{darkblue} \\binom{n}{k} = \\frac{n!}{k!(n-k)!}}$\n\n"
            + "青色底大号黑色：$\\colorbox{cyan}{\\large \\color{black} \\alpha^2 + \\beta^2 = \\gamma^2}$\n\n"
            + "棕色底超大白色：$\\colorbox{brown}{\\huge \\color{white} \\iiint_V \\rho \\, dV}$\n\n"
            + "浅绿底极小紫色：$\\colorbox{lime}{\\tiny \\color{purple} \\vec{F}=m\\vec{a}}$";
}
