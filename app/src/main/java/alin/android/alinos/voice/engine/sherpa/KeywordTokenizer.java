package alin.android.alinos.voice.engine.sherpa;

import android.content.Context;
import alin.android.alinos.log.AlinLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KWS 唤醒词 → tokens 转换器，完整复刻 sherpa-onnx 的 text2token（tokens_type = phone+ppinyin）。
 *
 * 支持：
 *  - 中文：汉字 → 带调拼音 → partial pinyin（声母 + 韵母），y/w 视为声母，零声母音节整体保留
 *  - 英文：查模型自带的 en.phone（CMU 发音词典）得到音素序列
 *
 * 安全兜底：生成的每个 token 必须存在于模型 tokens.txt（vocab）中，
 * 否则抛异常拒绝初始化 —— 绝不把无法解码的 keywords 喂给 native 层（native abort 无法 catch）。
 */
public class KeywordTokenizer {

    private static final String TAG = "KeywordTokenizer";

    // 声母表（含 y/w），顺序重要：zh/ch/sh 必须在 z/c/s 之前，长声母优先匹配
    private static final String[] INITIALS = {
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"
    };

    private static final java.util.regex.Pattern CJK_PATTERN =
            java.util.regex.Pattern.compile("^[\\u4e00-\\u9fff]+$");

    private static final java.util.regex.Pattern LETTER_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z]+$");

    /** 汉字 → 带调拼音（内置表，由 pypinyin 离线生成，与 sherpa 上游一致） */
    private final Map<String, String> mPinyin = new HashMap<>();

    /** 英文单词 → CMU 音素序列（模型自带 en.phone） */
    private final Map<String, String[]> mLexicon = new HashMap<>();

    /** 模型 tokens.txt 的词表（token → id） */
    private final Map<String, Integer> mVocab = new HashMap<>();

    private final Context mContext;

    public KeywordTokenizer(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 加载模型词表与英文词典。
     *
     * @param modelDir 实际模型目录（含 tokens.txt；en.phone 可选，缺失时英文词将无法识别）
     */
    public void load(File modelDir) throws Exception {
        loadVocab(new File(modelDir, "tokens.txt"));

        File lexiconFile = new File(modelDir, "en.phone");
        if (lexiconFile.exists()) {
            loadLexicon(lexiconFile);
        } else {
            AlinLog.w(TAG, "模型目录无 en.phone，英文唤醒词将不被支持: " + modelDir);
        }
        loadPinyinTable();
    }

    private void loadVocab(File tokensFile) throws Exception {
        mVocab.clear();
        BufferedReader br = new BufferedReader(new FileReader(tokensFile));
        String line;
        while ((line = br.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                mVocab.put(parts[0], Integer.parseInt(parts[1]));
            }
        }
        br.close();
        AlinLog.d(TAG, "vocab tokens: " + mVocab.size());
    }

    private void loadLexicon(File lexiconFile) throws Exception {
        mLexicon.clear();
        BufferedReader br = new BufferedReader(new FileReader(lexiconFile));
        String line;
        while ((line = br.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                String word = parts[0];
                String[] phones = new String[parts.length - 1];
                System.arraycopy(parts, 1, phones, 0, phones.length);
                mLexicon.put(word, phones);
            }
        }
        br.close();
        AlinLog.d(TAG, "lexicon words: " + mLexicon.size());
    }

    private void loadPinyinTable() throws Exception {
        mPinyin.clear();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(mContext.getAssets().open("kws_pinyin.txt"), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                mPinyin.put(parts[0], parts[1]);
            }
        }
        br.close();
        AlinLog.d(TAG, "pinyin table: " + mPinyin.size());
    }

    /**
     * 把唤醒词转换为 keywords.txt 内容（支持多个词，每词一行）。
     * 按汉字/英文字母分段：中文标点、空格等作为分隔符（如 "阿林，阿林" → 两个唤醒词）。
     *
     * @param keyword   原始唤醒词（可含中文和/或英文，逗号/空格分隔多个词）
     * @param score     提升分数（boosting score）
     * @param threshold 触发阈值
     * @return keywords.txt 内容，每行形如 "x iǎo ài t óng x ué :1.0 #0.7 @小爱同学"
     * @throws Exception 任一步骤无法转换（缺拼音/缺词典/token 不在词表）时抛出，调用方必须拒绝初始化
     */
    public String tokenize(String keyword, float score, float threshold) throws Exception {
        List<List<String>> wordsTokens = new ArrayList<>();

        for (String word : splitWords(keyword)) {
            if (word.isEmpty()) continue;
            List<String> toks = new ArrayList<>();

            if (CJK_PATTERN.matcher(word).matches()) {
                // 中文词：逐字转 partial pinyin
                for (int i = 0; i < word.length(); i++) {
                    String ch = String.valueOf(word.charAt(i));
                    String py = mPinyin.get(ch);
                    if (py == null) {
                        throw new Exception("汉字「" + ch + "」不在内置拼音表中，请换用常用字");
                    }
                    splitPpinyin(py, toks);
                }
            } else if (LETTER_PATTERN.matcher(word).matches()) {
                // 英文词：查 CMU 词典
                String key = word.toUpperCase(java.util.Locale.US);
                String[] phones = mLexicon.get(key);
                if (phones == null) {
                    throw new Exception("英文词「" + word + "」不在模型词典中");
                }
                for (String p : phones) toks.add(p);
            } else {
                throw new Exception("唤醒词含不支持的字符：" + word);
            }

            if (toks.isEmpty()) continue;

            // 安全兜底：所有 token 必须存在于模型词表，否则 native 解码会异常
            for (String t : toks) {
                if (!mVocab.containsKey(t)) {
                    throw new Exception("token「" + t + "」不在模型词表中，该唤醒词无法被此模型识别");
                }
            }
            wordsTokens.add(toks);
        }

        if (wordsTokens.isEmpty()) {
            throw new Exception("唤醒词为空");
        }

        StringBuilder sb = new StringBuilder();
        List<String> srcWords = splitWords(keyword);
        int wi = 0;
        for (int i = 0; i < srcWords.size() && wi < wordsTokens.size(); i++) {
            if (srcWords.get(i).isEmpty()) continue;
            List<String> toks = wordsTokens.get(wi++);
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.join(" ", toks))
                    .append(" :").append(score)
                    .append(" #").append(threshold)
                    .append(" @").append(srcWords.get(i));
        }
        return sb.toString();
    }

    /**
     * 按连续字符类型分段：汉字段 / 英文字母段 / 分隔符（标点、空格等直接跳过）。
     */
    private List<String> splitWords(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int mode = 0; // 0=无, 1=汉字, 2=英文
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int m = isCjkChar(c) ? 1
                    : ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) ? 2 : 0;
            if (m == 0) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                mode = 0;
            } else if (m == mode) {
                cur.append(c);
            } else {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                cur.append(c);
                mode = m;
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private boolean isCjkChar(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * partial pinyin 拆分：声母 + 韵母（带声调），y/w 视为声母，零声母音节整体保留。
     * 与 pypinyin to_initials/to_finals_tone(strict=False) 行为一致。
     */
    private void splitPpinyin(String py, List<String> out) {
        String initials = "";
        for (String i : INITIALS) {
            if (py.startsWith(i)) {
                initials = i;
                break;
            }
        }
        String finals = py.substring(initials.length());
        if (!initials.isEmpty()) {
            out.add(initials);
        }
        if (!finals.isEmpty()) {
            out.add(finals);
        } else if (initials.isEmpty()) {
            // ń / ḿ 等特殊音节
            out.add(py);
        }
    }
}
