package com.ds4h.scratchradiomics.radiomics;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import org.scijava.log.LogService;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Wrapper che delega l'estrazione radiomica a PyRadiomics via processo esterno.
 *
 * Flusso:
 *   1. Verifica che Python sia disponibile sul PATH e che PyRadiomics sia installato.
 *   2. Scrive immagine e maschera come TIFF temporanei.
 *   3. Lancia: python pyradiomics_runner.py image.tif mask.tif params.yaml
 *   4. Legge stdout come JSON e popola RadiomicResult.
 *   5. Pulisce i file temporanei.
 *
 * Se Python o PyRadiomics non sono disponibili, lancia PyRadiomicsUnavailableException
 * che RadiomicExtractor intercetta per attivare il fallback Java.
 *
 * Installazione PyRadiomics:
 *   pip install pyradiomics
 */
public class PyRadiomicsWrapper {
    public static class PyRadiomicsUnavailableException extends Exception {
        public PyRadiomicsUnavailableException(String message) { super(message); }
    }

    private static final int      TIMEOUT_SECONDS   = 120;
    private final LogService log;
    private Path runnerPath;

    public PyRadiomicsWrapper(LogService log) {
        this.log = log;
    }

    /**
     * Verifica che il Python fornito abbia PyRadiomics disponibile.
     * Il path viene ora fornito da PythonEnvironmentManager che ha già
     * configurato il venv — questo metodo è solo una sanity check finale.
     *
     * @param pythonPath path assoluto al Python del venv
     * @throws PyRadiomicsUnavailableException se PyRadiomics non risponde
     */
    public void checkAvailability(String pythonPath)
            throws PyRadiomicsUnavailableException {
        try {
            Process p = new ProcessBuilder(
                    pythonPath, "-c",
                    "import radiomics; print(radiomics.__version__)")
                    .redirectErrorStream(true).start();
            String version = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (p.exitValue() != 0 || version.isEmpty()) {
                throw new PyRadiomicsUnavailableException(
                        "PyRadiomics non risponde nel venv: " + pythonPath);
            }
            log.info("[PyRadiomicsWrapper] PyRadiomics " + version + " pronto.");
        } catch (PyRadiomicsUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new PyRadiomicsUnavailableException(
                    "Errore verifica PyRadiomics: " + e.getMessage());
        }
    }

    /**
     * Esegue PyRadiomics su una singola (immagine, maschera) e popola il
     * RadiomicResult con tutte le feature restituite.
     *
     * @param pythonCmd  comando Python verificato da checkAvailability()
     * @param sliceImp   ImagePlus della slice corrente (singolo frame)
     * @param roi        ROI che definisce la zona scratch
     * @param featureSets gruppi di feature selezionati dall'utente
     * @param result     RadiomicResult da popolare
     */
    public void extract(String pythonCmd,
                        ImagePlus sliceImp,
                        Roi roi,
                        Set<IbsiFeatureSet> featureSets,
                        RadiomicResult result)
            throws PyRadiomicsUnavailableException, IOException, InterruptedException {

        Path tmpDir = Files.createTempDirectory("wound_healing_");
        try {
            Path imgPath  = tmpDir.resolve("image.tif");
            Path maskPath = tmpDir.resolve("mask.tif");
            Path parPath  = tmpDir.resolve("params.yaml");

            saveSlice(sliceImp, imgPath);
            saveMask(sliceImp.getWidth(), sliceImp.getHeight(), roi, maskPath);
            writeParamsYaml(featureSets, parPath);

            ensureRunnerScript(tmpDir);

            log.info("[PyRadiomicsWrapper] Lancio: " + pythonCmd + " " +
                    runnerPath + " " + imgPath + " " + maskPath + " " + parPath);

            ProcessBuilder pb = new ProcessBuilder(
                    pythonCmd,
                    runnerPath.toString(),
                    imgPath.toString(),
                    maskPath.toString(),
                    parPath.toString())
                    .redirectErrorStream(false);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            try {
                Path pyPath      = Path.of(pythonCmd);
                Path envRoot     = pyPath.getParent();
                boolean isWin    = System.getProperty("os.name").toLowerCase().contains("win");
                Path sitePackages = isWin
                        ? envRoot.resolve("Lib/site-packages")
                        : envRoot.resolve("lib/python3.10/site-packages");
                if (Files.exists(sitePackages)) {
                    String existing = pb.environment().getOrDefault("PYTHONPATH", "");
                    String sep      = existing.isEmpty() ? "" : File.pathSeparator;
                    pb.environment().put("PYTHONPATH", sitePackages + sep + existing);
                    log.info("[PyRadiomicsWrapper] PYTHONPATH set to: " + sitePackages);
                } else {
                    log.warn("[PyRadiomicsWrapper] site-packages not found at: " + sitePackages);
                }
            } catch (Exception ex) {
                log.warn("[PyRadiomicsWrapper] Could not set PYTHONPATH: " + ex.getMessage());
            }

            Process proc = pb.start();

            String stdout = readStream(proc.getInputStream());
            String stderr = readStream(proc.getErrorStream());

            boolean finished = proc.waitFor(TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("PyRadiomics timeout dopo " + TIMEOUT_SECONDS + "s");
            }

            if (proc.exitValue() != 0) {
                log.warn("[PyRadiomicsWrapper] stderr: " + stderr);
                throw new IOException(
                        "PyRadiomics ha restituito exit code " + proc.exitValue() +
                                ":\n" + stderr.lines().limit(5)
                                .reduce("", (a, b) -> a + "\n" + b));
            }

            if (!stderr.isBlank()) {
                log.debug("[PyRadiomicsWrapper] stderr: " + stderr);
            }

            parseJson(stdout, result);

        } finally {
            deleteDir(tmpDir);
        }
    }

    /** Salva la slice come TIFF. */
    private static void saveSlice(ImagePlus imp, Path path) throws IOException {
        ImageProcessor ip = imp.getProcessor().duplicate();

        if (imp.getType() == ImagePlus.COLOR_RGB || ip instanceof ColorProcessor) {
            ip = ip.convertToByteProcessor(true);
        }

        ImagePlus copy = new ImagePlus("slice", ip);
        new FileSaver(copy).saveAsTiff(path.toString());
    }

    /** Costruisce la maschera binaria e la salva come TIFF (0/1 pixel). */
    private static void saveMask(int w, int h, Roi roi, Path path) throws IOException {
        ByteProcessor bp = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bp.set(x, y, roi.contains(x, y) ? 1 : 0);
        ImagePlus mask = new ImagePlus("mask", bp);
        new FileSaver(mask).saveAsTiff(path.toString());
    }

    /**
     * Scrive il file params.yaml per PyRadiomics.
     * Abilita solo le famiglie di feature selezionate dall'utente.
     * force2D è sempre true per il wound healing assay.
     */
    private static void writeParamsYaml(Set<IbsiFeatureSet> featureSets,
                                        Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# PyRadiomics params - generato da ScratchRadiomics\n");
        sb.append("setting:\n");
        sb.append("  force2D: true\n");
        sb.append("  force2Ddimension: 0\n");
        sb.append("  binCount: 64\n");
        sb.append("  label: 1\n");
        sb.append("  resampledPixelSpacing:\n");   // null = no resampling
        sb.append("  interpolator: sitkBSpline\n");
        sb.append("  preCrop: true\n\n");

        sb.append("featureClass:\n");
        if (featureSets.contains(IbsiFeatureSet.SHAPE_2D))
            sb.append("  shape2D:\n");
        if (featureSets.contains(IbsiFeatureSet.FIRST_ORDER))
            sb.append("  firstorder:\n");
        if (featureSets.contains(IbsiFeatureSet.GLCM))
            sb.append("  glcm:\n");
        if (featureSets.contains(IbsiFeatureSet.GLRLM))
            sb.append("  glrlm:\n");
        if (featureSets.contains(IbsiFeatureSet.GLSZM))
            sb.append("  glszm:\n");
        if (featureSets.contains(IbsiFeatureSet.GLDZM))
            sb.append("  gldm:\n");
        if (featureSets.contains(IbsiFeatureSet.NGTDM))
            sb.append("  ngtdm:\n");

        Files.writeString(path, sb.toString());
    }

    /**
     * Assicura che lo script pyradiomics_runner.py sia disponibile su disco.
     * Cerca prima nella stessa cartella del JAR del plugin, poi lo estrae
     * dalle risorse embedded nel JAR.
     */
    private void ensureRunnerScript(Path tmpDir) throws IOException {
        Path jarDir = getJarDirectory();
        if (jarDir != null) {
            Path candidate = jarDir.resolve("pyradiomics_runner.py");
            if (Files.exists(candidate)) {
                runnerPath = candidate;
                log.info("[PyRadiomicsWrapper] Script trovato nella cartella del JAR: " + runnerPath);
                return;
            }
        }

        String[] resourcePaths = {
                "/com/ds4h/scratchradiomics/pyradiomics_runner.py",
                "/com/ds4h/scratchradiomics/radiomics/pyradiomics_runner.py",
                "/pyradiomics_runner.py"
        };

        InputStream is = null;
        String loadedPath = null;

        for (String resPath : resourcePaths) {
            is = getClass().getResourceAsStream(resPath);
            if (is != null) {
                loadedPath = resPath;
                break;
            }
        }

        if (is == null) {
            throw new IOException(
                    "pyradiomics_runner.py non trovato nelle risorse del JAR.\n" +
                            "Verifica che il file sia presente in src/main/resources."
            );
        }

        try (InputStream stream = is) {
            runnerPath = tmpDir.resolve("pyradiomics_runner.py");
            Files.copy(stream, runnerPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[PyRadiomicsWrapper] Script estratto dal JAR (" + loadedPath + ") in: " + runnerPath);
        }
    }

    /** Restituisce la directory contenente il JAR del plugin. */
    private Path getJarDirectory() {
        try {
            return Path.of(
                            getClass().getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                    .getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parsa il JSON prodotto da pyradiomics_runner.py.
     *
     * Il formato è piatto:
     *   { "firstorder_Energy": 1234.5, "glcm_Contrast": 0.87, ... }
     *
     * Non usiamo librerie JSON esterne per non aggiungere dipendenze —
     * il formato è abbastanza semplice da parsare con una regex.
     */
    private static void parseJson(String json, RadiomicResult result) {
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}"))   json = json.substring(0, json.length() - 1);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*(?:[eE][+-]?[0-9]+)?)")
                .matcher(json);

        int count = 0;
        while (m.find()) {
            try {
                result.put("pyrad_" + m.group(1), Double.parseDouble(m.group(2)));
                count++;
            } catch (NumberFormatException ignored) {}
        }
        if (count == 0) {
            result.put("pyrad_parse_error", Double.NaN);
        }
    }

    private static String readStream(InputStream is) throws IOException {
        return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {}
    }
}
