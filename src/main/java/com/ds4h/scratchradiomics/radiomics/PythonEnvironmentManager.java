package com.ds4h.scratchradiomics.radiomics;
import com.ds4h.scratchradiomics.gui.SetupDialog;
import org.scijava.log.LogService;

import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Manages the isolated Python environment (venv) required by PyRadiomics.
 *
 * On the first plugin startup:<br>
 *   1. Checks whether Python 3.9 is already installed on the system.<br>
 *   2. If not found, silently downloads and installs Miniconda.<br>
 *   3. Creates an isolated venv in ScratchRadiomics/scratch_radiomics_env/<br>
 *   4. Installs PyRadiomics in the venv using pip.<br>
 *   5. Saves the venv Python path to a appInfo file for subsequent runs.<br>
 *
 * On subsequent runs:<br>
 *   - Reads the path from the appInfo file and verifies that the venv is still valid.<br>
 *   - If valid, skips the entire setup procedure.<br>
 *
 * The entire process is displayed in a dialog with a progress bar.<br>
 * No user interaction is required: no prompts, no choices.<br>
 *
 * The venv is completely isolated: it does not modify the system Python,
 * does not require administrator privileges, and does not interfere with other tools.
 */
public class PythonEnvironmentManager {
    private static final String ENV_DIR_NAME  = "scratch_radiomics_env";
    private static final String CONDA_DIR     = "miniforge3";
    private static final String CONDA_ENV     = "pyrad_env";
    private static final String APP_INFO_FILE = "appInfo.json";

    private static final String PYTHON_VERSION = "3.9";

    private static final String MINIFORGE_BASE =
            "https://github.com/conda-forge/miniforge/releases/latest/download/";
    private static final String MINIFORGE_WIN =
            MINIFORGE_BASE + "Miniforge3-Windows-x86_64.exe";
    private static final String MINIFORGE_MAC_ARM =
            MINIFORGE_BASE + "Miniforge3-MacOSX-arm64.sh";
    private static final String MINIFORGE_MAC_X86 =
            MINIFORGE_BASE + "Miniforge3-MacOSX-x86_64.sh";
    private static final String MINIFORGE_LINUX =
            MINIFORGE_BASE + "Miniforge3-Linux-x86_64.sh";

    private static final String[] CONDA_PACKAGES = {
            "numpy<2",
            "SimpleITK",
            "scikit-image",
            "PyWavelets",
            "six",
            "pykwalify",
            "cffi",
            "pip",
            "setuptools<60",
            "wheel",
            "vc",
            "vs2015_runtime",
            "libpython"
    };

    private final LogService log;
    private final Path       envDir;
    private final Path       condaDir;
    private final Path       appInfoFile;

    private volatile String  pythonPathResult = null;

    public PythonEnvironmentManager(LogService log) {
        this.log         = log;
        this.envDir      = resolveEnvDir();
        this.condaDir    = envDir.resolve(CONDA_DIR);
        this.appInfoFile = envDir.resolve(APP_INFO_FILE);
    }

    /**
     * Check if the setup environment is ready to start.
     *
     * @return The path of the appInfo.json file
     * @throws Exception
     */
    public String ensureReady() throws Exception {
        final String cached = readAppInfo();
        if (cached != null && isEnvValid(cached)) {
            log.info("[PythonEnvMgr] Env already ready: " + cached);
            return cached;
        }
        log.info("[PythonEnvMgr] Env not found — starting setup.");

        final CountDownLatch latch = new CountDownLatch(1);

        final SetupDialog dialog = new SetupDialog(this, latch);

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.setVisible(true);
        } else {
            SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
        }

        final boolean completed = latch.await(10, java.util.concurrent.TimeUnit.MINUTES);

        if (!completed) {
            throw new Exception("Python environment setup timed out after 10 minutes.");
        }

        if (dialog.failed())
            throw new Exception(
                    "Unable to configure the Python environment:\n" + dialog.getError());

        final String pythonPath = readAppInfoDirect();
        if (pythonPath == null)
            throw new Exception(
                    "Setup completed but appInfo.json was not written.\n" +
                            "Check that the plugin directory is writable:\n" + appInfoFile);
        return pythonPath;
    }

    /**
     * Run the setup to create the venv.
     *
     * @param cb The callback to inform the user of the progress.
     * @throws Exception
     */
    public void runSetup(ProgressCallback cb) throws Exception {
        Files.createDirectories(envDir);

        cb.step("Downloading Miniforge3 (conda-forge)…", 5);
        installMiniforge(cb);

        cb.step("Creating Python " + PYTHON_VERSION + " environment…", 42);
        createCondaEnv(cb);

        cb.step("Installing binary dependencies via conda-forge…", 55);
        installCondaPackages(cb);

        cb.step("Installing PyRadiomics via pip…", 80);
        installPyRadiomics(cb);

        cb.step("Verifying PyRadiomics…", 94);
        final String pythonPath = getEnvPython();
        verifyPyRadiomics(pythonPath);

        setPythonPathResult(pythonPath);

        try {
            Files.createDirectories(appInfoFile.getParent());
            writeAppInfo(pythonPath);
        } catch (Exception e) {
            log.warn("[PythonEnvMgr] writeAppInfo failed: " + e.getMessage());
        }

        cb.step("Ready!", 100);
    }

    private void installMiniforge(ProgressCallback cb) throws Exception {
        if (Files.exists(condaDir)) {
            log.info("[PythonEnvMgr] Miniforge3 already present at: " + condaDir);
            return;
        }

        final String os   = System.getProperty("os.name").toLowerCase();
        final String arch = System.getProperty("os.arch").toLowerCase();
        final boolean isWindows = os.contains("win");
        final boolean isMac     = os.contains("mac");

        final String url           = resolveMinforgeUrl(isWindows, isMac, arch);
        final String installerName = isWindows ? "miniforge_installer.exe"
                : "miniforge_installer.sh";
        final Path installer = envDir.resolve(installerName);

        cb.step("Downloading Miniforge3 (~90 MB)…", 6);
        downloadWithProgress(url, installer, cb, 6, 38);

        cb.step("Installing Miniforge3 (silent, no admin needed)…", 38);
        if (isWindows) {
            runCommand(List.of(
                    installer.toString(),
                    "/S",
                    "/JustMe",
                    "/AddToPath=0",
                    "/RegisterPython=0",
                    "/D=" + condaDir.toAbsolutePath()
            ), "Miniforge3 installer (Windows)");
        } else {
            runCommand(List.of(
                    "bash", installer.toString(),
                    "-b", "-p", condaDir.toAbsolutePath().toString()
            ), "Miniforge3 installer (Unix)");
        }
        Files.deleteIfExists(installer);

        if (!Files.exists(condaDir))
            throw new IOException("Miniforge3 installed but directory not found: " + condaDir);
        log.info("[PythonEnvMgr] Miniforge3 installed at: " + condaDir);
    }

    private String resolveMinforgeUrl(boolean isWindows, boolean isMac, String arch) {
        if (isWindows) return MINIFORGE_WIN;
        if (isMac) return (arch.contains("aarch64") || arch.contains("arm"))
                ? MINIFORGE_MAC_ARM : MINIFORGE_MAC_X86;
        return MINIFORGE_LINUX;
    }

    private void createCondaEnv(ProgressCallback cb) throws Exception {
        final String conda   = getCondaExe();
        final Path   envPath = condaDir.resolve("envs").resolve(CONDA_ENV);

        if (Files.exists(envPath)) {
            log.info("[PythonEnvMgr] Conda env already exists: " + envPath);
            return;
        }

        cb.step("Creating conda env with Python " + PYTHON_VERSION + "…", 44);
        runCommand(List.of(
                conda, "create", "-y",
                "-p", envPath.toString(),
                "python=" + PYTHON_VERSION,
                "--quiet"
        ), "conda create");
        log.info("[PythonEnvMgr] Conda env created: " + envPath);
    }

    private void installCondaPackages(ProgressCallback cb) throws Exception {
        final String conda   = getCondaExe();
        final Path   envPath = condaDir.resolve("envs").resolve(CONDA_ENV);

        final List<String> cmd = new ArrayList<>(List.of(
                conda, "install", "-y",
                "-p", envPath.toString(),
                "-c", "conda-forge",
                "--override-channels",
                "--quiet"
        ));
        cmd.addAll(List.of(CONDA_PACKAGES));

        runCommand(cmd, "conda install packages");
        log.info("[PythonEnvMgr] All packages installed via conda.");
    }

    private void installPyRadiomics(ProgressCallback cb) throws Exception {
        final boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        final String condaExe = isWin
                ? condaDir.resolve("condabin").resolve("conda.bat").toString()
                : condaDir.resolve("bin").resolve("conda").toString();

        cb.step("Installing PyRadiomics binary via Radiomics channel…", 85);

        final List<String> cmd = List.of(
                condaExe, "install", "-n", CONDA_ENV,
                "-c", "radiomics", "-c", "conda-forge",
                "pyradiomics", "-y"
        );

        runCommand(cmd, "conda install pyradiomics");
    }

    private String getCondaExe() {
        final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        return (isWindows
                ? condaDir.resolve("Scripts/conda.exe")
                : condaDir.resolve("bin/conda")).toString();
    }

    private String getEnvPython() {
        final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        final Path envPath = condaDir.resolve("envs").resolve(CONDA_ENV);
        return (isWindows
                ? envPath.resolve("python.exe")
                : envPath.resolve("bin/python3")).toString();
    }

    private boolean isEnvValid(String pythonPath) {
        if (!Files.exists(Path.of(pythonPath))) return false;
        try {
            final Process p = new ProcessBuilder(pythonPath, "-c",
                    "import radiomics; print('ok')")
                    .redirectErrorStream(false).start();
            final String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.endsWith("ok") && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private void verifyPyRadiomics(String pythonPath) throws Exception {
        final Process p = new ProcessBuilder(pythonPath, "-c",
                "import radiomics; print(radiomics.__version__)")
                .redirectErrorStream(false).start();
        final String version = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        if (p.exitValue() != 0 || version.isEmpty()) {
            final String err = new String(p.getErrorStream().readAllBytes()).trim();
            throw new Exception("PyRadiomics verification failed.\nError: " + err);
        }
        log.info("[PythonEnvMgr] PyRadiomics " + version + " verified.");
    }

    /** Called by SetupDialog to store the python path before latch release. */
    public void setPythonPathResult(String path) {
        this.pythonPathResult = path;
        log.info("[PythonEnvMgr] pythonPathResult set to: " + path);
    }

    /**
     * Reads the python path directly from the in-memory result set by
     * SetupDialog.done(), falling back to appInfo.json if not set.
     * Used by ensureReady() after the latch is released.
     */
    public String readAppInfoDirect() {
        if (pythonPathResult != null && !pythonPathResult.isBlank())
            return pythonPathResult;
        return readAppInfo();
    }

    private void writeAppInfo(String pythonPath) throws IOException {
        final String json = "{\n" +
                "  \"appName\": \"ScratchRadiomics\",\n" +
                "  \"pythonInstalled\": \"yes\",\n" +
                "  \"pythonPath\": \"" + pythonPath.replace("\\", "\\\\") + "\"\n" +
                "}";
        Files.createDirectories(appInfoFile.getParent());
        Files.writeString(appInfoFile, json);
        log.info("[PythonEnvMgr] appInfo.json written: " + appInfoFile);
    }

    private String readAppInfo() {
        try {
            if (!Files.exists(appInfoFile)) {
                log.info("[PythonEnvMgr] appInfo.json not found at: " + appInfoFile);
                return null;
            }
            final String json = Files.readString(appInfoFile, java.nio.charset.StandardCharsets.UTF_8).trim();
            log.info("[PythonEnvMgr] readAppInfo content: [" + json + "]");

            if (!json.contains("\"pythonInstalled\"") || !json.contains("\"yes\"")) {
                log.warn("[PythonEnvMgr] pythonInstalled=yes not found in file");
                return null;
            }

            final int keyIdx = json.indexOf("\"pythonPath\"");
            if (keyIdx < 0) {
                log.warn("[PythonEnvMgr] pythonPath key missing");
                return null;
            }
            final int colon = json.indexOf(':', keyIdx);
            if (colon < 0) return null;
            final int valStart = json.indexOf('"', colon) + 1;
            final int valEnd   = json.indexOf('"', valStart);
            if (valStart <= 0 || valEnd <= valStart) {
                log.warn("[PythonEnvMgr] could not extract pythonPath value");
                return null;
            }
            final String path = json.substring(valStart, valEnd).replace("\\\\", "\\");
            log.info("[PythonEnvMgr] readAppInfo path extracted: [" + path + "]");
            return path.isBlank() ? null : path;
        } catch (Exception e) {
            log.warn("[PythonEnvMgr] readAppInfo failed: " + e.getMessage());
            return null;
        }
    }

    private void runCommand(List<String> cmd, String label) throws Exception {
        log.debug("[PythonEnvMgr] " + label + ": " + String.join(" ", cmd));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        final Process p = pb.start();

        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        final Thread tOut = new Thread(() -> {
            try { stdout.append(new String(p.getInputStream().readAllBytes())); }
            catch (IOException ignored) {}
        });
        final Thread tErr = new Thread(() -> {
            try { stderr.append(new String(p.getErrorStream().readAllBytes())); }
            catch (IOException ignored) {}
        });
        tOut.start(); tErr.start();
        final int exit = p.waitFor();
        tOut.join(); tErr.join();

        final String combined = stdout + stderr.toString();
        if (!combined.isBlank())
            log.debug("[PythonEnvMgr] output: " + combined.trim());

        if (exit != 0)
            throw new IOException(label + " failed (exit " + exit + "):\n" +
                    combined.lines().limit(20).reduce("", (a, b) -> a + "\n" + b));
    }

    /*private void extractTarGz(Path tarGzFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (final InputStream fi = Files.newInputStream(tarGzFile);
             final BufferedInputStream bi = new BufferedInputStream(fi);
             final GZIPInputStream gzi = new java.util.zip.GZIPInputStream(bi);
             final TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                final Path entryPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (final OutputStream os = Files.newOutputStream(entryPath);
                         final BufferedOutputStream bos = new BufferedOutputStream(os)) {
                        ti.transferTo(bos);
                    }
                }
            }
        } catch (NoClassDefFoundError e) {
            extractTarGzSystem(tarGzFile, destDir);
        }
    }

    private void extractTarGzSystem(Path tarGzFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        final List<String> cmd =
                List.of("tar", "-xzf", tarGzFile.toAbsolutePath().toString(), "-C", destDir.toAbsolutePath().toString());
        try {
            runCommand(cmd, "tar extract");
        } catch (Exception ex) {
            throw new IOException("Failed to extract tar.gz: " + ex.getMessage(), ex);
        }
    }*/

    private void downloadWithProgress(String urlStr, Path dest,
                                      ProgressCallback cb,
                                      int from, int to) throws IOException {
        final URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);

        final int status = conn.getResponseCode();
        if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                || status == java.net.HttpURLConnection.HTTP_MOVED_PERM
                || status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
            final String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
        }

        try (final InputStream in  = conn.getInputStream();
             final OutputStream out = Files.newOutputStream(dest)) {
            final byte[] buf = new byte[8192];
            final long total = conn.getContentLengthLong();
            long downloaded = 0; int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    final int pct = from + (int)((downloaded * (to - from)) / total);
                    cb.step("Downloading… " + (downloaded / 1_048_576) +
                            " / " + (total / 1_048_576) + " MB", pct);
                }
            }
        }
    }

    /*private void patchRadiomicsInit(Path initPy) throws IOException {
        if (!Files.exists(initPy)) return;

        final String content = Files.readString(initPy, StandardCharsets.UTF_8);

        if (content.contains("# [ScratchRadiomics] Bypassed C extensions")) {
            log.info("[PythonEnvMgr] File already patched: " + initPy);
            return;
        }

        String patched = content.replaceAll(
                "(?m)^(\\s*)from\\s+radiomics\\s+import\\s+(_c\\w+)\\s+as\\s+(\\w+).*",
                "$1$3 = None  # [ScratchRadiomics] Bypassed $2"
        );

        patched = patched.replaceAll(
                "(?m)^(\\s*)from\\s+\\.\\s+import\\s+(_c\\w+).*",
                "$1$2 = None  # [ScratchRadiomics] Bypassed $2"
        );

        patched = patched.replaceAll(
                "(?m)^(\\s*)raise(\\s+e)?\\s*$",
                "$1pass  # [ScratchRadiomics] Suppressed C extension exception"
        );

        Files.writeString(initPy, patched, StandardCharsets.UTF_8);
        log.info("[PythonEnvMgr] Patch universale C-extensions applicata a: " + initPy);

        final Path pycacheDir = initPy.getParent().resolve("__pycache__");
        if (Files.exists(pycacheDir)) {
            deleteDir(pycacheDir);
            log.info("[PythonEnvMgr] Svuotata cache __pycache__ in: " + pycacheDir);
        }
    }*/

    private Path resolveEnvDir() {
        final String userHome = System.getProperty("user.home");
        final String os       = System.getProperty("os.name").toLowerCase();

        final Path dir;
        if (os.contains("win")) {
            final String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                dir = Path.of(appData, "ScratchRadiomics", ENV_DIR_NAME);
            } else {
                dir = Path.of(userHome, "ScratchRadiomics", ENV_DIR_NAME);
            }
        } else if (os.contains("mac")) {
            dir = Path.of(userHome, "Library", "Application Support",
                    "ScratchRadiomics", ENV_DIR_NAME);
        } else {
            dir = Path.of(userHome, ".ScratchRadiomics", ENV_DIR_NAME);
        }

        log.info("[PythonEnvMgr] envDir resolved to: " + dir.toAbsolutePath());
        return dir;
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {}
    }

    public interface ProgressCallback {
        void step(String message, int percent);
    }
}
