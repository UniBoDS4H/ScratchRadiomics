package com.ds4h.scratchradiomics.radiomics;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper that delegates radiomics extraction to PyRadiomics via an external process.
 * Workflow: <br/>
 *   1. Checks that Python is available on the PATH and that PyRadiomics is installed.<br/>
 *   2. Writes the image and mask as temporary TIFF files.<br/>
 *   3. Launches: python pyradiomics_runner.py image.tif mask.tif params.yaml<br/>
 *   4. Reads stdout as JSON and populates RadiomicResult.<br/>
 *   5. Cleans up the temporary files.<br/>
 */
public class PyRadiomicsWrapper {
    private static final int      TIMEOUT_SECONDS   = 120;
    private final LogService log;
    private Path runnerPath;

    public PyRadiomicsWrapper(LogService log) {
        this.log = log;
    }

    /**
     * Run PyRadiomics on a single (image, mask) pair and populate the RadiomicResult with all returned features.
     *
     * @param pythonCmd   Python command
     * @param sliceImp    ImagePlus of the current slice (single frame)
     * @param roi         ROI defining the scratch area
     * @param featureSets Feature groups selected by the user
     * @param result      RadiomicResult to populate
     */
    public void extract(String pythonCmd,
                        ImagePlus sliceImp,
                        Roi roi,
                        Set<IbsiFeatureSet> featureSets,
                        RadiomicResult result)
            throws IOException, InterruptedException {

        final Path tmpDir = Files.createTempDirectory("scratchradiomics_");
        try {
            final Path imgPath  = tmpDir.resolve("image.tif");
            final Path maskPath = tmpDir.resolve("mask.tif");
            final Path parPath  = tmpDir.resolve("params.yaml");

            saveSlice(sliceImp, imgPath);
            saveMask(sliceImp.getWidth(), sliceImp.getHeight(), roi, maskPath);
            writeParamsYaml(featureSets, parPath);

            ensureRunnerScript(tmpDir);

            final ProcessBuilder pb = new ProcessBuilder(
                    pythonCmd,
                    runnerPath.toString(),
                    imgPath.toString(),
                    maskPath.toString(),
                    parPath.toString())
                    .redirectErrorStream(false);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            try {
                final Path pyPath      = Path.of(pythonCmd);
                final Path envRoot     = pyPath.getParent();
                final boolean isWin    = System.getProperty("os.name").toLowerCase().contains("win");
                final Path sitePackages = isWin
                        ? envRoot.resolve("Lib/site-packages")
                        : envRoot.resolve("lib/python3.10/site-packages");
                if (Files.exists(sitePackages)) {
                    final String existing = pb.environment().getOrDefault("PYTHONPATH", "");
                    final String sep      = existing.isEmpty() ? "" : File.pathSeparator;
                    pb.environment().put("PYTHONPATH", sitePackages + sep + existing);
                    log.info("[PyRadiomicsWrapper] PYTHONPATH set to: " + sitePackages);
                } else {
                    log.warn("[PyRadiomicsWrapper] site-packages not found at: " + sitePackages);
                }
            } catch (Exception ex) {
                log.warn("[PyRadiomicsWrapper] Could not set PYTHONPATH: " + ex.getMessage());
            }

            final Process proc = pb.start();

            final String stdout = readStream(proc.getInputStream());
            final String stderr = readStream(proc.getErrorStream());

            final boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("PyRadiomics timeout after " + TIMEOUT_SECONDS + "s");
            }

            if (proc.exitValue() != 0) {
                log.warn("[PyRadiomicsWrapper] stderr: " + stderr);
                throw new IOException(
                        "PyRadiomics returns exit code " + proc.exitValue() +
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

    private static void saveSlice(ImagePlus imp, Path path) {
        ImageProcessor ip = imp.getProcessor().duplicate();

        if (imp.getType() == ImagePlus.COLOR_RGB || ip instanceof ColorProcessor) {
            ip = ip.convertToByteProcessor(true);
        }

        final ImagePlus copy = new ImagePlus("slice", ip);
        new FileSaver(copy).saveAsTiff(path.toString());
    }

    private static void saveMask(int w, int h, Roi roi, Path path) {
        final ByteProcessor bp = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bp.set(x, y, roi.contains(x, y) ? 1 : 0);
        final ImagePlus mask = new ImagePlus("mask", bp);
        new FileSaver(mask).saveAsTiff(path.toString());
    }

    private static void writeParamsYaml(Set<IbsiFeatureSet> featureSets,
                                        Path path) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("# PyRadiomics params - generated by ScratchRadiomics\n");
        sb.append("setting:\n");
        sb.append("  force2D: true\n");
        sb.append("  force2Ddimension: 0\n");
        sb.append("  binCount: 64\n");
        sb.append("  label: 1\n");
        sb.append("  resampledPixelSpacing:\n");
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

    private void ensureRunnerScript(Path tmpDir) throws IOException {
        final Path jarDir = getJarDirectory();
        if (jarDir != null) {
            final Path candidate = jarDir.resolve("pyradiomics_runner.py");
            if (Files.exists(candidate)) {
                runnerPath = candidate;
                return;
            }
        }

        final String[] resourcePaths = {
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
                    "pyradiomics_runner.py not found in JAR resources.\n" +
                            "Check if the file exists in src/main/resources."
            );
        }

        try (final InputStream stream = is) {
            runnerPath = tmpDir.resolve("pyradiomics_runner.py");
            Files.copy(stream, runnerPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

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

    private static void parseJson(String json, RadiomicResult result) {
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}"))   json = json.substring(0, json.length() - 1);

        final Matcher m = Pattern
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
