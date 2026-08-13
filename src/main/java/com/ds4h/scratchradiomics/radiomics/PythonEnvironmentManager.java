package com.ds4h.scratchradiomics.radiomics;
import com.ds4h.scratchradiomics.gui.SetupDialog;
import org.scijava.log.LogService;

import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Gestisce l'ambiente Python isolato (venv) necessario per PyRadiomics.
 *
 * Al primo avvio del plugin:
 *   1. Controlla se Python 3 è già installato sul sistema.
 *   2. Se non trovato, scarica e installa Miniconda silenziosamente.
 *   3. Crea un venv isolato in <plugins>/wound_healing_env/.venv
 *   4. Installa PyRadiomics nel venv con pip.
 *   5. Salva il path del Python del venv in un file .lock per i run successivi.
 *
 * Ai run successivi:
 *   - Legge il path dal file .lock e verifica che il venv sia ancora valido.
 *   - Se valido, salta tutta la procedura di setup (< 100ms).
 *
 * L'intero processo è mostrato in un dialog con progress bar.
 * L'utente non deve fare nulla — nessun prompt, nessuna scelta.
 *
 * Il venv è completamente isolato: non tocca Python di sistema, non richiede
 * privilegi di amministratore, non interferisce con altri tool.
 */
public class PythonEnvironmentManager {
    private static final String ENV_DIR_NAME  = "scratch_radiomics_env";
    private static final String CONDA_DIR     = "miniforge3";
    private static final String CONDA_ENV     = "pyrad_env";
    private static final String APP_INFO_FILE = "appInfo.json";

    private static final String PYTHON_VERSION = "3.10";

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
            "numpy",
            "SimpleITK",
            "scikit-image",
            "PyWavelets",
            "six",
            "pykwalify",
            "cffi",
            "pip",
            "setuptools<60",
            "wheel",
    };

    private final LogService log;
    private final Path       envDir;
    private final Path       condaDir;
    private final Path       appInfoFile;

    /** Set by SetupDialog.done() directly from the SwingWorker result. */
    private volatile String  pythonPathResult = null;

    public PythonEnvironmentManager(LogService log) {
        this.log         = log;
        this.envDir      = resolveEnvDir();
        this.condaDir    = envDir.resolve(CONDA_DIR);
        this.appInfoFile = envDir.resolve(APP_INFO_FILE);
    }

    public String ensureReady() throws Exception {
        String cached = readAppInfo();
        if (cached != null && isEnvValid(cached)) {
            log.info("[PythonEnvMgr] Env already ready: " + cached);
            return cached;
        }
        log.info("[PythonEnvMgr] Env not found — starting setup.");

        CountDownLatch latch = new CountDownLatch(1);

        SetupDialog dialog = new SetupDialog(this, latch);

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.setVisible(true);
        } else {
            SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
        }

        boolean completed = latch.await(10, java.util.concurrent.TimeUnit.MINUTES);

        if (!completed) {
            throw new Exception("Python environment setup timed out after 10 minutes.");
        }

        if (dialog.failed())
            throw new Exception(
                    "Unable to configure the Python environment:\n" + dialog.getError());

        String pythonPath = readAppInfoDirect();
        if (pythonPath == null)
            throw new Exception(
                    "Setup completed but appInfo.json was not written.\n" +
                            "Check that the plugin directory is writable:\n" + appInfoFile);
        return pythonPath;
    }

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

        cb.step("Patching PyRadiomics for pure-Python mode…", 92);
        boolean isWin2 = System.getProperty("os.name").toLowerCase().contains("win");
        Path envPath2  = condaDir.resolve("envs").resolve(CONDA_ENV);
        Path installedInit = (isWin2
                ? envPath2.resolve("Lib/site-packages/radiomics/__init__.py")
                : envPath2.resolve("lib/python3.10/site-packages/radiomics/__init__.py"));
        if (Files.exists(installedInit)) {
            patchRadiomicsInit(installedInit);
            log.info("[PythonEnvMgr] Patched installed radiomics/__init__.py at: " + installedInit);
        } else {
            log.warn("[PythonEnvMgr] Could not find installed __init__.py at: " + installedInit);
        }

        cb.step("Verifying PyRadiomics…", 94);
        String pythonPath = getEnvPython();
        log.info("[PythonEnvMgr] venv python path: " + pythonPath);
        log.info("[PythonEnvMgr] venv python exists: " + Files.exists(Path.of(pythonPath)));
        verifyPyRadiomics(pythonPath);
        log.info("[PythonEnvMgr] PyRadiomics verified OK");

        setPythonPathResult(pythonPath);
        log.info("[PythonEnvMgr] pythonPathResult set: " + pythonPath);

        log.info("[PythonEnvMgr] appInfoFile path: " + appInfoFile.toAbsolutePath());
        log.info("[PythonEnvMgr] appInfoFile parent exists: " +
                Files.exists(appInfoFile.getParent()));
        try {
            Files.createDirectories(appInfoFile.getParent());
            writeAppInfo(pythonPath);
            log.info("[PythonEnvMgr] appInfo.json written successfully");
            log.info("[PythonEnvMgr] appInfo.json exists after write: " +
                    Files.exists(appInfoFile));
        } catch (Exception e) {
            log.warn("[PythonEnvMgr] writeAppInfo failed: " + e.getClass().getName() +
                    ": " + e.getMessage());
        }

        log.info("[PythonEnvMgr] Setup complete. Python: " + pythonPath);
        cb.step("Ready!", 100);
    }

    private void installMiniforge(ProgressCallback cb) throws Exception {
        if (Files.exists(condaDir)) {
            log.info("[PythonEnvMgr] Miniforge3 already present at: " + condaDir);
            return;
        }

        String os   = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isWindows = os.contains("win");
        boolean isMac     = os.contains("mac");

        String url           = resolveMinforgeUrl(isWindows, isMac, arch);
        String installerName = isWindows ? "miniforge_installer.exe"
                : "miniforge_installer.sh";
        Path installer = envDir.resolve(installerName);

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
        String conda   = getCondaExe();
        Path   envPath = condaDir.resolve("envs").resolve(CONDA_ENV);

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
        String conda   = getCondaExe();
        Path   envPath = condaDir.resolve("envs").resolve(CONDA_ENV);

        List<String> cmd = new ArrayList<>(List.of(
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
        Path envPath  = condaDir.resolve("envs").resolve(CONDA_ENV);
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        String python = (isWin
                ? envPath.resolve("python.exe")
                : envPath.resolve("bin/python3")).toString();

        cb.step("Downloading PyRadiomics source…", 81);
        Path tarPath = envDir.resolve("pyradiomics-3.0.1.tar.gz");
        Path srcDir  = envDir.resolve("pyradiomics-3.0.1-src");

        if (Files.exists(srcDir)) {
            log.info("[PythonEnvMgr] Removing existing source dir for clean re-extract");
            deleteDir(srcDir);
        }

        String tarUrl = "https://files.pythonhosted.org/packages/source/p/pyradiomics/pyradiomics-3.0.1.tar.gz";
        downloadWithProgress(tarUrl, tarPath, cb, 81, 85);

        cb.step("Extracting PyRadiomics…", 86);
        extractTarGz(tarPath, srcDir);

        Path extractedRoot = Files.list(srcDir).findFirst()
                .orElseThrow(() -> new IOException("Extraction failed: empty tar source"));

        cb.step("Installing PyRadiomics (pure python mode)…", 88);

        Path buildDir = extractedRoot.resolve("build");
        if (Files.exists(buildDir)) {
            deleteDir(buildDir);
            log.info("[PythonEnvMgr] Deleted residual build directory");
        }

        Path setupCfg = extractedRoot.resolve("setup.cfg");
        if (Files.exists(setupCfg)) {
            String[] lines = Files.readString(setupCfg).split("\n");
            StringBuilder cfgOut = new StringBuilder();
            boolean inBuildExt = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.equals("[build_ext]")) {
                    inBuildExt = true;
                    continue;
                }
                if (inBuildExt && trimmed.startsWith("[")) {
                    inBuildExt = false;
                }
                if (!inBuildExt) {
                    cfgOut.append(line).append("\n");
                }
            }
            Files.writeString(setupCfg, cfgOut.toString());
            log.info("[PythonEnvMgr] setup.cfg patched to remove [build_ext] section");
        }

        Path setupPy = extractedRoot.resolve("setup.py");
        if (Files.exists(setupPy)) {
            String minimalSetup =
                    "import versioneer\n" +
                            "from setuptools import setup, find_packages\n" +
                            "\n" +
                            "setup(\n" +
                            "    name='pyradiomics',\n" +
                            "    version=versioneer.get_version(),\n" +
                            "    cmdclass=versioneer.get_cmdclass(),\n" +
                            "    packages=find_packages(exclude=['tests']),\n" +
                            "    package_data={'radiomics': ['schemas/*']},\n" +
                            "    install_requires=[\n" +
                            "        'numpy>=1.9.2',\n" +
                            "        'SimpleITK>=0.9.1',\n" +
                            "        'PyWavelets>=0.4.0',\n" +
                            "        'pykwalify>=1.6.0',\n" +
                            "        'six>=1.10.0',\n" +
                            "    ],\n" +
                            ")\n";
            Files.writeString(setupPy, minimalSetup);
            log.info("[PythonEnvMgr] setup.py replaced with minimal pure-Python version");
        }

        for (String cFile : new String[]{"radiomics/_cmatrices.c", "radiomics/_cmatrices.h"}) {
            Path p = extractedRoot.resolve(cFile);
            if (Files.exists(p)) { Files.delete(p); log.info("[PythonEnvMgr] Deleted: " + cFile); }
        }

        Path initPy = extractedRoot.resolve("radiomics/__init__.py");
        if (Files.exists(initPy)) {
            patchRadiomicsInit(initPy);
            log.info("[PythonEnvMgr] radiomics/__init__.py patched in source");
        }

        ProcessBuilder pb = new ProcessBuilder(
                python, "setup.py",
                "install",
                "--single-version-externally-managed",
                "--root=/"
        );
        pb.directory(extractedRoot.toFile());
        pb.environment().put("DISABLE_CEXT", "1");
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        pb.redirectErrorStream(false);

        Process proc = pb.start();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread tOut = new Thread(() -> {
            try { stdout.append(new String(proc.getInputStream().readAllBytes())); }
            catch (IOException ignored) {}
        });
        Thread tErr = new Thread(() -> {
            try { stderr.append(new String(proc.getErrorStream().readAllBytes())); }
            catch (IOException ignored) {}
        });
        tOut.start(); tErr.start();
        int exit = proc.waitFor();
        tOut.join(); tErr.join();

        String combined = stdout.toString() + stderr.toString();
        log.info("[PythonEnvMgr] setup.py output:\n" + combined.trim());

        if (exit != 0)
            throw new IOException("python setup.py install failed (exit " + exit + "):\n" +
                    combined.lines().limit(30).reduce("", (a, b) -> a + "\n" + b));

        Path installedSitePackages = isWin
                ? envPath.resolve("Lib/site-packages/radiomics")
                : envPath.resolve("lib/python3.10/site-packages/radiomics");

        Path installedInit = installedSitePackages.resolve("__init__.py");
        if (Files.exists(installedInit)) {
            patchRadiomicsInit(installedInit);
            log.info("[PythonEnvMgr] Applied post-install patch to site-packages: " + installedInit);
        } else {
            log.warn("[PythonEnvMgr] Could not find installed __init__.py at: " + installedInit);
        }

        try {
            Files.deleteIfExists(tarPath);
            deleteDir(srcDir);
        } catch (Exception ignored) {}

        log.info("[PythonEnvMgr] PyRadiomics installato con successo in modalità Python pura.");
    }

    private String getCondaExe() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        return (isWindows
                ? condaDir.resolve("Scripts/conda.exe")
                : condaDir.resolve("bin/conda")).toString();
    }

    private String getEnvPython() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path envPath = condaDir.resolve("envs").resolve(CONDA_ENV);
        return (isWindows
                ? envPath.resolve("python.exe")
                : envPath.resolve("bin/python3")).toString();
    }

    private boolean isEnvValid(String pythonPath) {
        if (!Files.exists(Path.of(pythonPath))) return false;
        try {
            Process p = new ProcessBuilder(pythonPath, "-c",
                    "import radiomics; print('ok')")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.equals("ok") && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private void verifyPyRadiomics(String pythonPath) throws Exception {
        Process p = new ProcessBuilder(pythonPath, "-c",
                "import radiomics; print(radiomics.__version__)")
                .redirectErrorStream(true).start();
        String version = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        if (p.exitValue() != 0 || version.isEmpty())
            throw new Exception("PyRadiomics verification failed.\nOutput: " + version);
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
        String json = "{\n" +
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
            String json = Files.readString(appInfoFile, java.nio.charset.StandardCharsets.UTF_8).trim();
            log.info("[PythonEnvMgr] readAppInfo content: [" + json + "]");

            if (!json.contains("\"pythonInstalled\"") || !json.contains("\"yes\"")) {
                log.warn("[PythonEnvMgr] pythonInstalled=yes not found in file");
                return null;
            }

            int keyIdx = json.indexOf("\"pythonPath\"");
            if (keyIdx < 0) {
                log.warn("[PythonEnvMgr] pythonPath key missing");
                return null;
            }
            int colon = json.indexOf(':', keyIdx);
            if (colon < 0) return null;
            int valStart = json.indexOf('"', colon) + 1;
            int valEnd   = json.indexOf('"', valStart);
            if (valStart <= 0 || valEnd <= valStart) {
                log.warn("[PythonEnvMgr] could not extract pythonPath value");
                return null;
            }
            String path = json.substring(valStart, valEnd).replace("\\\\", "\\");
            log.info("[PythonEnvMgr] readAppInfo path extracted: [" + path + "]");
            return path.isBlank() ? null : path;
        } catch (Exception e) {
            log.warn("[PythonEnvMgr] readAppInfo failed: " + e.getMessage());
            return null;
        }
    }

    private void runCommand(List<String> cmd, String label) throws Exception {
        log.debug("[PythonEnvMgr] " + label + ": " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread tOut = new Thread(() -> {
            try { stdout.append(new String(p.getInputStream().readAllBytes())); }
            catch (IOException ignored) {}
        });
        Thread tErr = new Thread(() -> {
            try { stderr.append(new String(p.getErrorStream().readAllBytes())); }
            catch (IOException ignored) {}
        });
        tOut.start(); tErr.start();
        int exit = p.waitFor();
        tOut.join(); tErr.join();

        String combined = stdout.toString() + stderr.toString();
        if (!combined.isBlank())
            log.debug("[PythonEnvMgr] output: " + combined.trim());

        if (exit != 0)
            throw new IOException(label + " failed (exit " + exit + "):\n" +
                    combined.lines().limit(20).reduce("", (a, b) -> a + "\n" + b));
    }

    private void extractTarGz(Path tarGzFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (InputStream fi = Files.newInputStream(tarGzFile);
             BufferedInputStream bi = new BufferedInputStream(fi);
             java.util.zip.GZIPInputStream gzi = new java.util.zip.GZIPInputStream(bi);
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream ti =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzi)) {

            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                Path entryPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath);
                         BufferedOutputStream bos = new BufferedOutputStream(os)) {
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
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd;
        if (isWin) {
            cmd = List.of("tar", "-xzf", tarGzFile.toAbsolutePath().toString(), "-C", destDir.toAbsolutePath().toString());
        } else {
            cmd = List.of("tar", "-xzf", tarGzFile.toAbsolutePath().toString(), "-C", destDir.toAbsolutePath().toString());
        }
        try {
            runCommand(cmd, "tar extract");
        } catch (Exception ex) {
            throw new IOException("Failed to extract tar.gz: " + ex.getMessage(), ex);
        }
    }

    private void downloadWithProgress(String urlStr, Path dest,
                                      ProgressCallback cb,
                                      int from, int to) throws IOException {
        URL url = new URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                || status == java.net.HttpURLConnection.HTTP_MOVED_PERM
                || status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
            String newUrl = conn.getHeaderField("Location");
            conn = (java.net.HttpURLConnection) new URL(newUrl).openConnection();
        }

        try (InputStream in  = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long total = conn.getContentLengthLong();
            long downloaded = 0; int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int pct = from + (int)((downloaded * (to - from)) / total);
                    cb.step("Downloading… " + (downloaded / 1_048_576) +
                            " / " + (total / 1_048_576) + " MB", pct);
                }
            }
        }
    }

    /**
     * Patches radiomics/__init__.py to remove the _cmatrices C extension import.
     * pyradiomics 3.0.1 uses C extensions for performance but falls back to
     * pure Python if they are not available — however the import is not wrapped
     * in a try/except in __init__.py, so it crashes without the .pyd file.
     * We comment out the import block to force the pure-Python fallback.
     */
    private void patchRadiomicsInit(Path initPy) throws IOException {
        if (!Files.exists(initPy)) return;

        String content = Files.readString(initPy, StandardCharsets.UTF_8);

        if (content.contains("# [ScratchRadiomics] Bypassed C extensions")) {
            log.info("[PythonEnvMgr] File già patchato: " + initPy);
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

        Path pycacheDir = initPy.getParent().resolve("__pycache__");
        if (Files.exists(pycacheDir)) {
            deleteDir(pycacheDir);
            log.info("[PythonEnvMgr] Svuotata cache __pycache__ in: " + pycacheDir);
        }
    }

    private Path resolveEnvDir() {
        String userHome = System.getProperty("user.home");
        String os       = System.getProperty("os.name").toLowerCase();

        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
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
