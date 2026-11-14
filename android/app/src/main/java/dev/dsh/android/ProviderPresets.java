package dev.dsh.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Creates the web profile with a curated set of custom providers the first time
 * the engine runs. The profile patch is the same file the DSH Models page edits,
 * so once seeded it belongs entirely to the user: we never rewrite it.
 */
public final class ProviderPresets {
    private ProviderPresets() {
    }

    /**
     * Ensure {@code $DSH_HOME/profiles/web} exists before the first boot.
     * Returns true when a new profile was written.
     */
    public static boolean ensureProfile(Context context, File homeDir) throws Exception {
        File profile = new File(homeDir, "profiles/web");
        File manifest = new File(profile, "package.json");
        if (manifest.isFile()) {
            File patch = new File(profile, "cordis.patch.yml");
            if (!patch.isFile()) writeText(patch, patchYaml(context));
            return false;
        }
        if (!profile.mkdirs() && !profile.isDirectory()) {
            throw new IOException("cannot create profile directory: " + profile);
        }
        writeText(manifest, "{\n"
                + "  \"name\": \"dsh-profile-web\",\n"
                + "  \"private\": true,\n"
                + "  \"dependencies\": {},\n"
                + "  \"dsh\": {\n"
                + "    \"profile\": {\n"
                + "      \"bundles\": [\n"
                + "        \"@deepseek-ai/dsh-base\",\n"
                + "        \"@deepseek-ai/dsh-web-app\"\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
        writeText(new File(profile, "pnpm-workspace.yaml"),
                "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n");
        writeText(new File(profile, "cordis.yml"),
                "# dsh profile root. The tree is composed as patches: each bundle in\n"
                        + "# package.json's dsh.profile.bundles, then cordis.patch.yml.\n"
                        + "# Edit cordis.patch.yml, not this file.\n[]\n");
        writeText(new File(profile, "cordis.patch.yml"), patchYaml(context));
        return true;
    }

    /** Human-readable list for the native Settings screen. */
    public static String describe(Context context) {
        try {
            JSONObject root = new JSONObject(new String(readAsset(context, "provider-presets.json"),
                    StandardCharsets.UTF_8));
            JSONArray providers = root.getJSONArray("providers");
            StringBuilder text = new StringBuilder();
            text.append("These custom providers are seeded into your DSH profile on first launch:\n\n");
            for (int i = 0; i < providers.length(); i++) {
                JSONObject provider = providers.getJSONObject(i);
                text.append("• ").append(provider.getString("displayName"))
                        .append("  (").append(provider.getString("route")).append(")\n");
                text.append("    API key variable: ")
                        .append(provider.getString("apiKeyEnv")).append('\n');
                if (provider.has("baseURL")) {
                    text.append("    Endpoint: ").append(provider.getString("baseURL")).append('\n');
                }
            }
            text.append("\nOpen Settings → Models in the DSH interface to add a key or customize ")
                    .append("models. The shell writes this file only once and never rewrites your edits.");
            return text.toString();
        } catch (Exception error) {
            return "Provider presets are stored in provider-presets.json.";
        }
    }

    private static String patchYaml(Context context) throws Exception {
        JSONObject root = new JSONObject(new String(readAsset(context, "provider-presets.json"),
                StandardCharsets.UTF_8));
        JSONArray providers = root.getJSONArray("providers");
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Seeded by DSH Android on first launch.\n");
        yaml.append("# This is the same user patch layer Settings > Models edits. Add or remove\n");
        yaml.append("# providers on that page; this file is never rewritten by the shell.\n");
        yaml.append("- id: llm-pi-ai\n");
        yaml.append("  config:\n");
        yaml.append("    providers:\n");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject provider = providers.getJSONObject(i);
            yaml.append("      ").append(provider.getString("route")).append(":\n");
            yaml.append("        displayName: ").append(quote(provider.getString("displayName"))).append('\n');
            yaml.append("        apiKeyEnv: ").append(quote(provider.getString("apiKeyEnv"))).append('\n');
            if (provider.has("api")) {
                yaml.append("        api: ").append(quote(provider.getString("api"))).append('\n');
            }
            if (provider.has("baseURL")) {
                yaml.append("        baseURL: ").append(quote(provider.getString("baseURL"))).append('\n');
            }
            if (provider.has("models")) {
                yaml.append("        models:\n");
                JSONArray models = provider.getJSONArray("models");
                for (int m = 0; m < models.length(); m++) {
                    JSONObject model = models.getJSONObject(m);
                    yaml.append("          - id: ").append(quote(model.getString("id"))).append('\n');
                    yaml.append("            name: ").append(quote(model.getString("name"))).append('\n');
                    yaml.append("            contextWindow: ").append(model.getInt("contextWindow")).append('\n');
                    yaml.append("            maxTokens: ").append(model.getInt("maxTokens")).append('\n');
                }
            }
        }
        return yaml.toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
