package alin.android.alinos.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型注册表：静态基线（assets/models.json）+ 动态 overlay（轮询 provider 得到）。
 *
 * <p>工作方式与 pi-ai 的 provider 模型一致：
 * <pre>
 *   currentModels(provider) = baseline(provider) 按 id 覆盖/追加 dynamic(provider)
 * </pre>
 *
 * <p>动态结果会持久化到 SharedPreferences（含 checkedAt 时间戳），
 * 下次启动先恢复缓存，再按需联网刷新。
 */
public final class ModelRegistry {

    private static final String TAG = "ModelRegistry";
    private static final String ASSET = "models.json";
    private static final String PREFS = "model_registry";
    private static final String KEY_DYNAMIC = "dynamic_models";

    private static volatile ModelRegistry sInstance;

    /** providerId → provider 信息（静态基线）。 */
    private final Map<String, ProviderInfo> providers = new LinkedHashMap<>();
    /** "provider/modelId" → 模型（静态基线）。 */
    private final Map<String, ModelInfo> baselineByKey = new HashMap<>();
    /** modelId → 模型列表（静态基线）。 */
    private final Map<String, List<ModelInfo>> byModelId = new HashMap<>();

    /** providerId → 动态发现的模型。 */
    private final Map<String, List<ModelInfo>> dynamic = new HashMap<>();
    /** providerId → 上次刷新时间。 */
    private final Map<String, Long> checkedAt = new HashMap<>();

    private int baselineCount;
    private String dataVersion = "";

    private ModelRegistry() {
    }

    public static ModelRegistry get(Context ctx) {
        if (sInstance == null) {
            synchronized (ModelRegistry.class) {
                if (sInstance == null) {
                    ModelRegistry r = new ModelRegistry();
                    Context app = ctx.getApplicationContext();
                    r.loadBaseline(app);
                    r.loadDynamic(app);
                    sInstance = r;
                }
            }
        }
        return sInstance;
    }

    // ---------------------------------------------------------------------
    // 加载
    // ---------------------------------------------------------------------

    private void loadBaseline(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject root = new JSONObject(sb.toString());
            dataVersion = root.optString("version", "");
            JSONObject provs = root.optJSONObject("providers");
            if (provs == null) return;

            java.util.Iterator<String> keys = provs.keys();
            while (keys.hasNext()) {
                String pid = keys.next();
                JSONObject pj = provs.optJSONObject(pid);
                if (pj == null) continue;

                ProviderInfo p = new ProviderInfo(pid, pj.optString("name", pid));
                JSONArray arr = pj.optJSONArray("models");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        ModelInfo m = parseModel(pid, arr.optJSONObject(i));
                        if (m == null) continue;
                        p.models.add(m);
                        baselineCount++;
                        baselineByKey.put(pid + "/" + m.id, m);
                        List<ModelInfo> list = byModelId.get(m.id);
                        if (list == null) {
                            list = new ArrayList<>();
                            byModelId.put(m.id, list);
                        }
                        list.add(m);
                    }
                }
                if (!p.models.isEmpty()) providers.put(pid, p);
            }
            Log.d(TAG, "静态基线: " + providers.size() + " 个 provider / " + baselineCount + " 个模型");
        } catch (Exception e) {
            Log.e(TAG, "加载 " + ASSET + " 失败", e);
        }
    }

    private void loadDynamic(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_DYNAMIC, null);
            if (json == null || json.isEmpty()) return;
            JSONObject root = new JSONObject(json);
            java.util.Iterator<String> keys = root.keys();
            int total = 0;
            while (keys.hasNext()) {
                String pid = keys.next();
                JSONObject entry = root.optJSONObject(pid);
                if (entry == null) continue;
                checkedAt.put(pid, entry.optLong("checkedAt", 0));
                JSONArray arr = entry.optJSONArray("models");
                List<ModelInfo> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        ModelInfo m = parseModel(pid, arr.optJSONObject(i));
                        if (m != null) list.add(m);
                    }
                }
                if (!list.isEmpty()) {
                    dynamic.put(pid, list);
                    total += list.size();
                }
            }
            Log.d(TAG, "动态缓存: " + dynamic.size() + " 个 provider / " + total + " 个模型");
        } catch (Exception e) {
            Log.w(TAG, "加载动态模型缓存失败", e);
        }
    }

    private static ModelInfo parseModel(String providerId, JSONObject mj) {
        if (mj == null) return null;
        String id = mj.optString("id", "");
        if (id.isEmpty()) return null;
        return new ModelInfo(
                providerId,
                id,
                mj.optString("name", id),
                mj.optString("api", "openai-completions"),
                mj.optString("baseUrl", ""),
                mj.optInt("contextWindow", 0),
                mj.optInt("maxTokens", 0),
                mj.optBoolean("reasoning", false),
                mj.optString("input", "text"),
                mj.optJSONObject("cost"));
    }

    // ---------------------------------------------------------------------
    // 动态 overlay
    // ---------------------------------------------------------------------

    /**
     * 写入某 provider 的动态模型列表（基线中已有的按 id 覆盖，新模型追加）。
     */
    public void setDynamic(String providerId, List<ModelInfo> models, long checkedAtMs) {
        if (providerId == null) return;
        if (models == null || models.isEmpty()) {
            dynamic.remove(providerId);
        } else {
            dynamic.put(providerId, models);
        }
        checkedAt.put(providerId, checkedAtMs);
    }

    public long checkedAt(String providerId) {
        Long t = checkedAt.get(providerId);
        return t == null ? 0 : t;
    }

    /** 某 provider 当前生效的模型（基线 + 动态 overlay）。 */
    public List<ModelInfo> models(String providerId) {
        List<ModelInfo> baseline = new ArrayList<>();
        ProviderInfo p = providers.get(providerId);
        if (p != null) baseline.addAll(p.models);

        List<ModelInfo> dyn = dynamic.get(providerId);
        if (dyn == null || dyn.isEmpty()) return baseline;

        List<ModelInfo> merged = new ArrayList<>(baseline);
        for (ModelInfo m : dyn) {
            int idx = -1;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).id.equals(m.id)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                // 动态条目只补充/覆盖，保留基线的定价信息
                merged.set(idx, mergeModels(merged.get(idx), m));
            } else {
                merged.add(m);
            }
        }
        return merged;
    }

    /** 动态条目优先，但基线有定价而动态没有时保留基线定价。 */
    private static ModelInfo mergeModels(ModelInfo base, ModelInfo dynEntry) {
        if (dynEntry.cost != null) return dynEntry;
        if (base.cost == null) return dynEntry;
        return new ModelInfo(
                base.providerId,
                dynEntry.id,
                !dynEntry.name.isEmpty() ? dynEntry.name : base.name,
                dynEntry.api.isEmpty() ? base.api : dynEntry.api,
                !dynEntry.baseUrl.isEmpty() ? dynEntry.baseUrl : base.baseUrl,
                dynEntry.contextWindow > 0 ? dynEntry.contextWindow : base.contextWindow,
                dynEntry.maxTokens > 0 ? dynEntry.maxTokens : base.maxTokens,
                dynEntry.reasoning || base.reasoning,
                base.input,
                base.cost);
    }

    /** 全部 provider 的当前模型（基线 + 动态）。 */
    public List<ModelInfo> models() {
        List<ModelInfo> out = new ArrayList<>();
        for (String pid : providers.keySet()) {
            out.addAll(models(pid));
        }
        // 仅有动态模型的 provider
        for (String pid : dynamic.keySet()) {
            if (!providers.containsKey(pid)) out.addAll(models(pid));
        }
        return out;
    }

    /** 把当前动态 overlay 持久化。 */
    public void persist(Context ctx) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<ModelInfo>> e : dynamic.entrySet()) {
                JSONObject entry = new JSONObject();
                entry.put("checkedAt", checkedAt(e.getKey()));
                JSONArray arr = new JSONArray();
                for (ModelInfo m : e.getValue()) {
                    JSONObject o = new JSONObject();
                    o.put("id", m.id);
                    o.put("name", m.name);
                    o.put("api", m.api);
                    if (!m.baseUrl.isEmpty()) o.put("baseUrl", m.baseUrl);
                    if (m.contextWindow > 0) o.put("contextWindow", m.contextWindow);
                    if (m.maxTokens > 0) o.put("maxTokens", m.maxTokens);
                    if (m.reasoning) o.put("reasoning", true);
                    if (m.cost != null) o.put("cost", m.cost);
                    arr.put(o);
                }
                entry.put("models", arr);
                root.put(e.getKey(), entry);
            }
            ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DYNAMIC, root.toString())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "持久化动态模型失败", e);
        }
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    public List<ProviderInfo> providers() {
        return new ArrayList<>(providers.values());
    }

    public ProviderInfo provider(String providerId) {
        return providers.get(providerId);
    }

    /** 按 provider + 模型 id 查找（含动态 overlay）。 */
    public ModelInfo model(String providerId, String modelId) {
        if (providerId != null && modelId != null) {
            for (ModelInfo m : models(providerId)) {
                if (m.id.equals(modelId)) return m;
            }
        }
        ModelInfo base = baselineByKey.get(providerId + "/" + modelId);
        if (base != null) return base;
        return modelById(modelId);
    }

    /** 只按模型 id 查找（取第一个匹配）。 */
    public ModelInfo modelById(String modelId) {
        if (modelId == null) return null;
        List<ModelInfo> list = byModelId.get(modelId);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /** 模糊搜索（模型 id / 名称 / provider）。 */
    public List<ModelInfo> search(String query) {
        if (query == null || query.trim().isEmpty()) return Collections.emptyList();
        String q = query.trim().toLowerCase();
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo m : models()) {
            if (m.id.toLowerCase().contains(q)
                    || (m.name != null && m.name.toLowerCase().contains(q))
                    || m.providerId.toLowerCase().contains(q)) {
                out.add(m);
            }
        }
        return out;
    }

    public int baselineCount() {
        return baselineCount;
    }

    public int providerCount() {
        return providers.size();
    }

    public String dataVersion() {
        return dataVersion;
    }
}
