package dev.dsh.android;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the on-device DSH engine: payload extraction, profile seeding, the Node
 * child process, URL discovery, and recovery. One instance per app process.
 *
 * All mutating work runs on a single worker thread. State and log callbacks are
 * delivered on the main thread so activities can render without extra care.
 */
public final class Engine {
    public enum State {IDLE, EXTRACTING, STARTING, RUNNING, STOPPED, FAILED}

    public interface Listener {
        default void onState(State state, String message, int progressPercent) {
        }

        default void onReady(String url) {
        }

        default void onLog(String line) {
        }
    }

    public static final String DSH_VERSION = "0.2.2-rc.2";
    public static final String NODE_VERSION = "26.13.1";
    public static final String GIT_VERSION = "2.56.1";
    private static final String DSH_BIN = "node_modules/@deepseek-ai/dsh/lib/bin.js";
    private static final String SAFE_MODE_SCRIPT =
            "node_modules/@deepseek-ai/dsh/dsh-android-safe-mode.mjs";
    private static final String PAYLOAD_MARKER = ".payload-sha256";
    private static final Pattern URL_PATTERN =
            Pattern.compile("dsh web: (https?://\\S+)");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
    private static final long READY_TIMEOUT_MS = 180_000L;

    private static volatile Engine instance;

    public static Engine get(Context context) {
        Engine local = instance;
        if (local == null) {
            synchronized (Engine.class) {
                local = instance;
                if (local == null) {
                    local = new Engine(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dsh-engine");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();

    private volatile State state = State.IDLE;
    private volatile String message = "";
    private volatile int progress = -1;
    private volatile String url;
    private volatile String error;
    private volatile Process process;
    private volatile boolean stopping;
    private volatile long readyAt;

    private Engine(Context app) {
        this.app = app;
    }

    public Context context() {
        return app;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public int progress() {
        return progress;
    }

    public String url() {
        return url;
    }

    public String error() {
        return error;
    }

    public long readyAt() {
        return readyAt;
    }

    public int port() {
        String current = url;
        if (current == null) return -1;
        try {
            return new java.net.URI(current).getPort();
        } catch (Exception ignored) {
            return -1;
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        State current = state;
        String currentMessage = message;
        int currentProgress = progress;
        String currentUrl = url;
        main.post(() -> {
            listener.onState(current, currentMessage, currentProgress);
            if (current == State.RUNNING && currentUrl != null) listener.onReady(currentUrl);
        });
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Start the engine unless it is already starting or running. */
    public synchronized void start() {
        if (state == State.EXTRACTING || state == State.STARTING || state == State.RUNNING) return;
        int token = generation.incrementAndGet();
        worker.execute(() -> boot(token));
    }

    /** Stop the child process but keep the instance usable. */
    public void stop() {
        worker.execute(() -> {
            generation.incrementAndGet();
            doStop("Stopped");
        });
    }

    /** Stop and boot again, sequenced on the worker thread. */
    public void restart() {
        worker.execute(() -> {
            int token = generation.incrementAndGet();
            doStop("Restarting");
            boot(token);
        });
    }

    /** Stop, reset the profile patch (keeping a backup), and boot again. */
    public void enterSafeMode() {
        worker.execute(() -> {
            int token = generation.incrementAndGet();
            doStop("Preparing safe mode");
            try {
                runSafeMode();
            } catch (Throwable error) {
                fail("Safe mode failed: " + describe(error), error);
                return;
            }
            boot(token);
        });
    }

    /** Force a fresh extraction and boot. User data under dsh-home is kept. */
    public void reinstallEngine() {
        worker.execute(() -> {
            int token = generation.incrementAndGet();
            doStop("Reinstalling engine");
            deleteRecursively(engineDir());
            boot(token);
        });
    }

    private void boot(int token) {
        if (token != generation.get()) return;
        stopping = false;
        url = null;
        error = null;
        try {
            publish(State.EXTRACTING, "Unpacking the engine payload…", 0);
            ensureEngine(token);
            if (token != generation.get()) return;

            publish(State.EXTRACTING, "Preparing your DSH home…", 100);
            File home = homeDir();
            mkdirs(home);
            ProviderPresets.ensureProfile(app, home);

            publish(State.STARTING, "Starting DeepSeek Harness…", -1);
            launch(token);
        } catch (Throwable failure) {
            if (token == generation.get()) fail(describe(failure), failure);
        }
    }

    private void launch(int token) throws Exception {
        File engineDir = engineDir();
        File node = new File(app.getApplicationInfo().nativeLibraryDir, "libnode.so");
        File dsh = new File(engineDir, DSH_BIN);
        if (!node.isFile()) throw new IOException("bundled Node runtime missing (" + node + ")");
        if (!dsh.isFile()) throw new IOException("engine entry missing (" + dsh + ")");

        prepareRuntimeDirs();
        cleanupStaleNode();

        File workspace = workspaceDir();
        mkdirs(workspace);
        File home = homeDir();
        File tmp = new File(app.getCacheDir(), "tmp");
        mkdirs(tmp);

        List<String> command = new ArrayList<>(Arrays.asList(
                node.getAbsolutePath(),
                "--expose-internals",
                dsh.getAbsolutePath(),
                "web",
                "--no-open",
                "--host", "127.0.0.1",
                "--port", "0"));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace);
        Map<String, String> env = builder.environment();
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("HOME", new File(app.getFilesDir(), "home").getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PATH", app.getApplicationInfo().nativeLibraryDir
                + ":" + binDir().getAbsolutePath()
                + ":/system/bin:/system/xbin:/vendor/bin");
        env.put("LD_LIBRARY_PATH", app.getApplicationInfo().nativeLibraryDir);
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_PAGER", "cat");
        File gitCore = new File(binDir(), "git-core");
        if (gitCore.isDirectory()) env.put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        File gitTemplates = new File(engineDir(), "git/templates");
        if (gitTemplates.isDirectory()) {
            env.put("GIT_TEMPLATE_DIR", gitTemplates.getAbsolutePath());
        }
        env.put("NODE_OPTIONS", "--max-old-space-size=" + (isLowRam() ? 768 : 1536));
        if (!Prefs.telemetry(app)) env.put("DSH_TELEMETRY_DISABLED", "1");
        mkdirs(new File(env.get("HOME")));

        LogStore.append("engine", "starting node " + NODE_VERSION + " in " + workspace);
        long started = System.currentTimeMillis();
        Process child = builder.start();
        process = child;
        writePid(child);
        child.getOutputStream().close();

        Thread stdout = new Thread(() -> pump(child.getInputStream(), "stdout", token), "dsh-stdout");
        Thread stderr = new Thread(() -> pump(child.getErrorStream(), "stderr", token), "dsh-stderr");
        stdout.start();
        stderr.start();

        Thread waiter = new Thread(() -> {
            int code;
            try {
                code = child.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (process == child) process = null;
            deletePid();
            if (stopping || token != generation.get()) return;
            if (state != State.RUNNING) {
                fail("Engine exited before it was ready (code " + code + ").", null);
            } else {
                fail("Engine stopped unexpectedly (code " + code + ").", null);
            }
        }, "dsh-waiter");
        waiter.start();

        // A startup watchdog: a hung boot should not leave a spinner forever.
        main.postDelayed(() -> {
            if (token == generation.get() && state == State.STARTING) {
                fail("The engine did not become ready within "
                        + (READY_TIMEOUT_MS / 1000) + " seconds.", null);
            }
        }, READY_TIMEOUT_MS);

        long ignored = started;
    }

    private void pump(InputStream stream, String name, int token) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 8192)) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(name, line, token);
            }
        } catch (IOException ignored) {
            // Stream closes when the process exits.
        }
    }

    private void handleLine(String stream, String rawLine, int token) {
        String line = ANSI.matcher(rawLine).replaceAll("");
        if (line.isEmpty()) return;
        LogStore.appendEngine(stream, line);
        for (Listener listener : listeners) {
            main.post(() -> listener.onLog(line));
        }
        if (token != generation.get() || state == State.RUNNING) return;
        Matcher matcher = URL_PATTERN.matcher(line);
        if (matcher.find()) {
            String readyUrl = matcher.group(1);
            url = readyUrl;
            readyAt = System.currentTimeMillis();
            publish(State.RUNNING, "Connected", 100);
            for (Listener listener : listeners) {
                main.post(() -> listener.onReady(readyUrl));
            }
        }
    }

    private void doStop(String label) {
        stopping = true;
        Process child = process;
        process = null;
        if (child != null) {
            LogStore.append("engine", "stopping engine");
            child.destroy();
            try {
                child.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        deletePid();
        url = null;
        readyAt = 0;
        publish(State.STOPPED, label, -1);
    }

    private void fail(String reason, Throwable failure) {
        Process child = process;
        process = null;
        if (child != null) child.destroy();
        deletePid();
        error = reason;
        LogStore.append("engine", "ERROR: " + reason);
        if (failure != null) {
            LogStore.append("engine", android.util.Log.getStackTraceString(failure));
        }
        publish(State.FAILED, reason, -1);
    }

    private void publish(State next, String text, int percent) {
        state = next;
        message = text;
        progress = percent;
        if (next != State.FAILED) error = null;
        for (Listener listener : listeners) {
            main.post(() -> listener.onState(next, text, percent));
        }
    }

    // ------------------------------------------------------------------ dirs

    public File homeDir() {
        return new File(app.getFilesDir(), "dsh-home");
    }

    public File engineDir() {
        return new File(app.getFilesDir(), "engine");
    }

    public File binDir() {
        return new File(app.getFilesDir(), "bin");
    }

    public File workspaceDir() {
        File base = android.os.Environment.getExternalStorageDirectory();
        if (base == null) base = new File("/storage/emulated/0");
        File shared = new File(base, "DSH");
        try {
            if (shared.isDirectory() || shared.mkdirs()) return shared;
        } catch (SecurityException ignored) {
            // Fall through to app storage.
        }
        File fallback = new File(app.getFilesDir(), "workspace");
        try {
            mkdirs(fallback);
        } catch (IOException ignored) {
            // The caller reports an unusable workspace when the engine starts.
        }
        return fallback;
    }

    public boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return android.os.Environment.isExternalStorageManager();
        }
        return app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------- extraction

    private void ensureEngine(int token) throws Exception {
        File engine = engineDir();
        String expected = payloadSha();
        File marker = new File(engine, PAYLOAD_MARKER);
        if (marker.isFile()
                && expected.equals(readText(marker).trim())
                && new File(engine, DSH_BIN).isFile()) {
            LogStore.append("engine", "using extracted payload " + expected.substring(0, 12));
            return;
        }
        deleteRecursively(engine);
        mkdirs(engine);
        long available = engine.getUsableSpace();
        if (available < 500L * 1024 * 1024) {
            throw new IOException(String.format(Locale.US,
                    "Not enough free space to unpack the engine (%.0f MB available, about 600 MB needed).",
                    available / 1048576.0));
        }
        long total = payloadBytes();
        LogStore.append("engine", "extracting payload (" + total + " bytes)");
        Process tar = new ProcessBuilder("/system/bin/tar", "-xzf", "-", "-C",
                engine.getAbsolutePath()).redirectErrorStream(true).start();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    tar.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LogStore.append("tar", line);
                }
            } catch (IOException ignored) {
                // The tar process closes its output as it finishes.
            }
        }, "dsh-tar");
        drain.start();

        try (InputStream in = app.getAssets().open("dsh-engine.tar.gz");
             OutputStream out = tar.getOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int percent = (int) Math.min(99, done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        publish(State.EXTRACTING,
                                "Unpacking the engine payload… " + percent + "%", percent);
                    }
                }
                if (token != generation.get()) {
                    tar.destroy();
                    return;
                }
            }
        } catch (IOException brokenPipe) {
            // tar exits early on a corrupt archive; waitFor reports the code.
            LogStore.append("engine", "payload pipe closed: " + brokenPipe.getMessage());
        }
        int code = tar.waitFor();
        drain.join(2000);
        if (code != 0) {
            throw new IOException("engine extraction failed (tar exit " + code + ")");
        }
        if (!new File(engine, DSH_BIN).isFile()) {
            throw new IOException("engine extraction produced an incomplete payload");
        }
        writeText(marker, expected);
        writeSafeModeScript(engine);
        LogStore.append("engine", "payload extracted");
    }

    private void writeSafeModeScript(File engine) throws IOException {
        String script = "import { sanitizeProfile } from '@deepseek-ai/dsh-app-boot'\n"
                + "import { PROFILE_TEMPLATES } from '@deepseek-ai/dsh-official-bundles'\n"
                + "const dir = process.argv[2]\n"
                + "const backup = sanitizeProfile('dsh', dir, PROFILE_TEMPLATES.web.bundles)\n"
                + "console.log(backup ? `safe-mode: saved ${backup}` : 'safe-mode: no patch file')\n";
        // This script must live inside the dsh package: the production payload is
        // an isolated pnpm tree, and bare imports only resolve from there.
        File file = new File(engine, SAFE_MODE_SCRIPT);
        writeText(file, script);
    }

    private void runSafeMode() throws Exception {
        File engine = engineDir();
        File node = new File(app.getApplicationInfo().nativeLibraryDir, "libnode.so");
        File script = new File(engine, SAFE_MODE_SCRIPT);
        if (!script.isFile()) writeSafeModeScript(engine);
        File profile = new File(homeDir(), "profiles/web");
        LogStore.append("engine", "entering safe mode for " + profile);
        Process process = new ProcessBuilder(
                node.getAbsolutePath(), "--expose-internals", script.getAbsolutePath(),
                profile.getAbsolutePath())
                .directory(engine)
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) LogStore.append("safe-mode", line);
        }
        int code = process.waitFor();
        if (code != 0) throw new IOException("safe mode exited with code " + code);
        if (profile.isDirectory() && !new File(profile, "cordis.patch.yml").isFile()) {
            writeText(new File(profile, "cordis.patch.yml"), "[]\n");
        }
    }

    private String payloadSha() throws Exception {
        JSONObject manifest = new JSONObject(readAsset("dsh-engine.manifest.json"));
        return manifest.getString("sha256");
    }

    private long payloadBytes() {
        try {
            JSONObject manifest = new JSONObject(readAsset("dsh-engine.manifest.json"));
            return manifest.getLong("bytes");
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = app.getAssets().open(name);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- runtime

    private void prepareRuntimeDirs() throws Exception {
        mkdirs(homeDir());
        mkdirs(binDir());
        mkdirs(new File(app.getFilesDir(), "home"));
        mkdirs(new File(app.getCacheDir(), "tmp"));
        String nativeDir = app.getApplicationInfo().nativeLibraryDir;

        link("node", new File(nativeDir, "libnode.so"));
        File bash = new File(nativeDir, "libbash.so");
        link("bash", bash.isFile() ? bash : new File("/system/bin/sh"));
        link("sh", new File("/system/bin/sh"));
        File git = new File(nativeDir, "libgit.so");
        if (git.isFile()) {
            link("git", git);
            // Git execs its transport/transport helpers from GIT_EXEC_PATH. The
            // helpers are native APK libraries, exposed to git through symlinks.
            File gitCore = new File(binDir(), "git-core");
            mkdirs(gitCore);
            File remoteHttp = new File(nativeDir, "libgit-remote-http.so");
            linkTo(gitCore, "git-remote-http", remoteHttp);
            linkTo(gitCore, "git-remote-https", remoteHttp);
            linkTo(gitCore, "git-upload-pack", git);
            linkTo(gitCore, "git-receive-pack", git);
            linkTo(gitCore, "git-upload-archive", git);
        }
        seedGitConfig();
    }

    /** A persistent global config; never overwrites a user-edited file. */
    private void seedGitConfig() {
        File config = new File(app.getFilesDir(), "home/.gitconfig");
        if (config.isFile()) return;
        try {
            writeText(config, "[safe]\n"
                    + "\tdirectory = *\n"
                    + "[init]\n"
                    + "\tdefaultBranch = main\n"
                    + "[core]\n"
                    + "\tpager = cat\n");
        } catch (IOException error) {
            LogStore.append("engine", "cannot seed git config: " + error.getMessage());
        }
    }

    private void link(String name, File target) {
        linkTo(binDir(), name, target);
    }

    private void linkTo(File dir, String name, File target) {
        File link = new File(dir, name);
        if (!target.isFile() && !target.exists()) return;
        try {
            if (link.exists() || link.isFile()) link.delete();
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception error) {
            LogStore.append("engine", "cannot link " + name + ": " + error.getMessage());
        }
    }

    private File pidFile() {
        return new File(app.getFilesDir(), "engine.pid");
    }

    private void writePid(Process child) {
        try {
            long pid = processPid(child);
            if (pid > 0) writeText(pidFile(), Long.toString(pid));
        } catch (Exception ignored) {
            // PID cleanup is best-effort.
        }
    }

    private long processPid(Process child) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return child.pid();
            } catch (Throwable ignored) {
                // fall through to reflection
            }
        }
        try {
            java.lang.reflect.Field field = child.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            Object value = field.get(child);
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Throwable ignored) {
            // no pid available
        }
        return -1;
    }

    private void deletePid() {
        File file = pidFile();
        if (file.isFile() && !file.delete()) LogStore.append("engine", "cannot delete pid file");
    }

    private void cleanupStaleNode() {
        File file = pidFile();
        if (!file.isFile()) return;
        try {
            int pid = Integer.parseInt(readText(file).trim());
            LogStore.append("engine", "checking stale node pid " + pid);
            String cmdline = readProc(pid, "cmdline");
            String exe = readProc(pid, "exe");
            if (cmdline.contains("libnode.so") || exe.contains("libnode.so")) {
                LogStore.append("engine", "killing stale node pid " + pid);
                Os.kill(pid, 9);
            }
            deletePid();
        } catch (Exception error) {
            LogStore.append("engine", "stale pid check failed: " + error.getMessage());
            deletePid();
        }
    }

    private String readProc(int pid, String name) {
        File file = new File("/proc/" + pid + "/" + name);
        try {
            byte[] data = new byte[4096];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = in.read(data);
                if (read <= 0) return "";
                return new String(data, 0, read, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            return "";
        }
    }

    private boolean isLowRam() {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.isLowRamDevice();
    }

    // ------------------------------------------------------------------- io

    public static void mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create directory: " + dir);
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            LogStore.append("engine", "cannot delete " + file);
        }
    }

    public static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(Integer.MAX_VALUE, Math.max(1, file.length()))];
            int read = in.read(data);
            return read <= 0 ? "" : new String(data, 0, read, StandardCharsets.UTF_8);
        }
    }

    public static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) mkdirs(parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String describe(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isEmpty()) text = error.getClass().getSimpleName();
        return text;
    }
}
