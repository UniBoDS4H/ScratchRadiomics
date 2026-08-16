package com.ds4h.scratchradiomics.segmentation;

import ai.nets.samj.communication.model.*;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.*;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.scijava.log.LogService;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

/**
 * Integrates SAMJ (segment-anything-models-java) for automatic scratch segmentation.
 */
public class SamSegmenter {

    private final ImagePlus  imp;
    private final LogService log;
    private String selectedModel = "SAM2 Tiny";

    public SamSegmenter(ImagePlus imp, LogService log) {
        this.imp = imp; this.log = log;
    }

    public Roi[] segment(int nPromptPoints) throws Exception {
        int nFrames = Math.max(imp.getNFrames(), imp.getStackSize());
        log.info("[SamSegmenter] model=" + selectedModel + " total_frames=" + nFrames);

        if (!isSamjAvailable()) {
            boolean installed = installSamjAutomatically();
            if (!installed || !isSamjAvailable()) return new Roi[0];
        }

        if (!showModelDialog()) return new Roi[0];

        final Roi[] result = (nFrames == 1)
                ? new Roi[]{ segmentFrame(imp, 1, nPromptPoints) }
                : segmentStack(nPromptPoints, nFrames);

        pushToRoiManager(result);
        return result;
    }

    /**
     * Aggiunge automaticamente l'update site di SAMJ e scarica i componenti.
     */
    private boolean installSamjAutomatically() {
        log.info("[SamSegmenter] SAMJ non installato. Avvio ImageJ Updater...");
        int choice = JOptionPane.showConfirmDialog(
                null,
                "<html><b>SAMJ richiede l'installazione dei componenti di base.</b><br>" +
                        "Vuoi aprire l'Updater di Fiji per completare l'installazione automatica?</html>",
                "Installazione SAMJ",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE
        );

        if (choice == JOptionPane.YES_OPTION) {
            IJ.runPlugIn("net.imagej.updater.ImageJUpdater", "");
        }
        return false;
    }

    private Roi segmentFrame(ImagePlus frame, int idx, int nPts) throws Exception {
        log.info("[SamSegmenter] Encoding frame " + idx);
        final RandomAccessibleInterval<UnsignedByteType> rai = toRai(frame);
        final List<int[]> pts = (idx == 1)
                ? collectInteractive(frame, nPts)
                : collectAuto(frame, nPts);

        final List<Rectangle> rects = new ArrayList<>();
        if (pts.isEmpty()) {
            log.warn("[SamSegmenter] No prompts for frame " + idx);
            return null;
        }

        final List<?> masks = runInference(rai, pts, rects);
        return (masks == null || masks.isEmpty()) ? null : bestMask(masks, idx);
    }

    private Roi[] segmentStack(int nPts, int nFrames) throws Exception {
        final Roi[] result = new Roi[nFrames];

        setFramePosition(imp, 1);
        result[0] = segmentFrame(imp, 1, nPts);
        if (result[0] == null) return result;

        for (int f = 2; f <= nFrames; f++) {
            setFramePosition(imp, f);
            result[f - 1] = segmentFrame(imp, f, nPts);
            IJ.showProgress(f, nFrames);
        }
        IJ.showProgress(1.0);
        return result;
    }

    private void setFramePosition(ImagePlus image, int frameIdx) {
        if (image.isHyperStack()) {
            image.setPosition(1, 1, frameIdx);
        } else {
            image.setSlice(frameIdx);
        }
    }

    private List<int[]> collectInteractive(ImagePlus frame, int nPts) throws InterruptedException {
        final List<int[]> pts = new ArrayList<>();

        frame.show();
        frame.getWindow().toFront();

        IJ.setTool("multipoint");
        frame.killRoi();

        new WaitForUserDialog("SAM Prompts",
                "1. Select the Multipoint tool (already active).\n" +
                        "2. Click " + nPts + " points on the scratch area.\n" +
                        "3. Click OK on this window ONLY AFTER entering the points.").show();

        final Roi roi = frame.getRoi();
        if (roi instanceof PointRoi) {
            final PointRoi pr = (PointRoi) roi;
            for (final Point p : pr.getContainedPoints()) {
                pts.add(new int[]{ p.x, p.y });
            }
        }

        if (pts.isEmpty()) {
            return collectAuto(frame, nPts);
        }
        return pts;
    }

    private List<int[]> collectAuto(ImagePlus frame, int nPts) {
        final List<int[]> pts = new ArrayList<>();
        if (nPts <= 0) nPts = 3;
        final int w = frame.getWidth(), h = frame.getHeight();
        final int xStart = (int)(w * 0.2), xEnd = (int)(w * 0.8);
        final int step = (xEnd - xStart) / Math.max(1, nPts - 1);
        for (int i = 0; i < nPts; i++)
            pts.add(new int[]{ xStart + i * step, snapExtrema(frame, xStart + i * step, h/2, h/10) });
        log.info("[SamSegmenter] " + pts.size() + " auto prompts generated");
        return pts;
    }

    private int snapExtrema(ImagePlus frame, int x, int yCenter, int half) {
        ImageProcessor ip = frame.getProcessor();
        final int yMin = Math.max(0, yCenter - half), yMax = Math.min(frame.getHeight()-1, yCenter + half);
        double colMean = 0;
        for (int y = yMin; y <= yMax; y++) colMean += ip.getPixelValue(x, y);
        colMean /= (yMax - yMin + 1);
        final boolean bright = colMean > ip.getStatistics().mean;
        int bestY = yCenter; double bestVal = bright ? -1e9 : 1e9;
        for (int y = yMin; y <= yMax; y++) {
            final double v = ip.getPixelValue(x, y);
            if ((bright && v > bestVal) || (!bright && v < bestVal)) { bestVal = v; bestY = y; }
        }
        return bestY;
    }

    private List<?> runInference(RandomAccessibleInterval<?> rai,
                                 List<int[]> pts, List<Rectangle> rects) throws Exception {
        SAMModel model = resolveModel();

        if (!model.isInstalled()) {
            log.warn("[SamSegmenter] The Python environment for " + selectedModel + " is not installed.");

            JOptionPane.showMessageDialog(
                    null,
                    "<html><b>Python environment not ready for " + selectedModel + "</b><br><br>" +
                            "SAMJ has to configure Python/Mamba environment and downlead the weights<br>" +
                            "Open once <b>Plugins > SAMJ > SAMJ Annotator</b> from Fiji menu to complete the automated setup.</html>",
                    "Setup SAMJ richiesto",
                    JOptionPane.WARNING_MESSAGE
            );
            return Collections.emptyList();
        }

        try {
            Class<?> annClass = Class.forName("ai.nets.samj.ij.SAMJ_Annotator");
            Object annotator = annClass.getDeclaredConstructor().newInstance();

            java.lang.reflect.Method m = findMethod(annClass, "samJReturnContours", 4);
            return (List<?>) m.invoke(annotator, model, rai, pts, rects);

        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[SamSegmenter] Error during SAMJ inference (" + cause.getClass().getName() + ")", cause);

            if (cause instanceof NullPointerException) {
                throw new IllegalStateException(
                        "Python executable not found in Appose environment.\n" +
                                "Initialize the model by 'Plugins > SAMJ > SAMJ Annotator' in Fiji."
                );
            }

            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            throw new RuntimeException("SAMJ error: " + msg, cause);
        }
    }

    private SAMModel resolveModel() {
        switch (selectedModel) {
            case "SAM2 Small":  return new SAM2Small();
            case "SAM2 Large":  return new SAM2Large();
            case "EfficientSAM": return new EfficientSAM();
            default:            return new SAM2Tiny();
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> cls, String name, int params)
            throws NoSuchMethodException {
        for (final java.lang.reflect.Method m : cls.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        for (final java.lang.reflect.Method m : cls.getDeclaredMethods())
            if (m.getName().equals(name) && m.getParameterCount() == params) {
                m.setAccessible(true); return m;
            }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("unchecked")
    private Roi bestMask(List<?> masks, int frameIdx) {
        Roi best = null;
        double lowestMeanIntensity = Double.MAX_VALUE;

        double maxAllowedArea = (imp.getWidth() * imp.getHeight()) * 0.75;

        for (final Object mask : masks) {
            if (mask == null) continue;
            try {
                int[] xs = null;
                int[] ys = null;

                Method mX = findMethodIgnoreCase(mask.getClass(), "getContourX", "getContourXPoints", "getXContours", "getContourXs");
                Method mY = findMethodIgnoreCase(mask.getClass(), "getContourY", "getContourYPoints", "getYContours", "getContourYs");

                if (mX != null && mY != null) {
                    xs = toIntArray(mX.invoke(mask));
                    ys = toIntArray(mY.invoke(mask));
                }

                if (xs == null || ys == null) {
                    Method mContour = findMethodIgnoreCase(mask.getClass(), "getContour", "getContours", "getContourAsIntArray", "getSqueezedContour", "getPolygon");
                    if (mContour != null) {
                        Object contourObj = mContour.invoke(mask);
                        int[][] coords = parseContourObject(contourObj);
                        if (coords != null) {
                            xs = coords[0];
                            ys = coords[1];
                        }
                    }
                }

                if (xs == null || ys == null || xs.length < 3 || ys.length < 3) {
                    log.warn("[SamSegmenter] Impossible extract points from: " + mask.getClass().getName());
                    logClassMethods(mask);
                    continue;
                }

                final PolygonRoi roi = new PolygonRoi(xs, ys, Math.min(xs.length, ys.length), Roi.POLYGON);
                roi.setPosition(frameIdx);

                double area = roi.getStatistics().area;

                if (area > maxAllowedArea || area < 100) {
                    continue;
                }

                imp.setRoi(roi);
                double meanPixelValue = imp.getProcessor().getStats().mean;

                if (meanPixelValue < lowestMeanIntensity) {
                    lowestMeanIntensity = meanPixelValue;
                    best = roi;
                }

            } catch (Exception ex) {
                log.warn("[SamSegmenter] Mask parsing error: " + ex.getMessage(), ex);
            }
        }

        if (best != null) {
            best.setName("Scratch_t" + frameIdx);
        }
        return best;
    }

    private Method findMethodIgnoreCase(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equalsIgnoreCase(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    private int[][] parseContourObject(Object obj) {
        if (obj == null) return null;

        if (obj instanceof Polygon) {
            Polygon p = (Polygon) obj;
            int[] x = new int[p.npoints];
            int[] y = new int[p.npoints];
            System.arraycopy(p.xpoints, 0, x, 0, p.npoints);
            System.arraycopy(p.ypoints, 0, y, 0, p.npoints);
            return new int[][]{ x, y };
        }

        if (obj instanceof int[][]) {
            int[][] c = (int[][]) obj;
            if (c.length == 2) return c;
            if (c.length > 2 && c[0].length == 2) {
                int[] x = new int[c.length];
                int[] y = new int[c.length];
                for (int i = 0; i < c.length; i++) {
                    x[i] = c[i][0];
                    y[i] = c[i][1];
                }
                return new int[][]{ x, y };
            }
        }

        if (obj instanceof double[][]) {
            double[][] c = (double[][]) obj;
            if (c.length == 2) {
                return new int[][]{ toIntArray(c[0]), toIntArray(c[1]) };
            }
            if (c.length > 2 && c[0].length == 2) {
                int[] x = new int[c.length];
                int[] y = new int[c.length];
                for (int i = 0; i < c.length; i++) {
                    x[i] = (int) Math.round(c[i][0]);
                    y[i] = (int) Math.round(c[i][1]);
                }
                return new int[][]{ x, y };
            }
        }

        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (list.isEmpty()) return null;

            int[] x = new int[list.size()];
            int[] y = new int[list.size()];

            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                if (elem instanceof int[]) {
                    x[i] = ((int[]) elem)[0];
                    y[i] = ((int[]) elem)[1];
                } else if (elem instanceof Point) {
                    x[i] = ((Point) elem).x;
                    y[i] = ((Point) elem).y;
                } else if (elem instanceof double[]) {
                    x[i] = (int) Math.round(((double[]) elem)[0]);
                    y[i] = (int) Math.round(((double[]) elem)[1]);
                }
            }
            return new int[][]{ x, y };
        }

        return null;
    }

    private int[] toIntArray(Object array) {
        if (array instanceof int[]) return (int[]) array;
        if (array instanceof double[]) {
            double[] d = (double[]) array;
            int[] res = new int[d.length];
            for (int i = 0; i < d.length; i++) res[i] = (int) Math.round(d[i]);
            return res;
        }
        if (array instanceof float[]) {
            float[] f = (float[]) array;
            int[] res = new int[f.length];
            for (int i = 0; i < f.length; i++) res[i] = Math.round(f[i]);
            return res;
        }
        if (array instanceof List) {
            List<?> l = (List<?>) array;
            int[] res = new int[l.size()];
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) instanceof Number) {
                    res[i] = ((Number) l.get(i)).intValue();
                }
            }
            return res;
        }
        return null;
    }

    private void logClassMethods(Object obj) {
        StringBuilder sb = new StringBuilder("[SamSegmenter] Available methods in ").append(obj.getClass().getName()).append(":\n");
        for (Method m : obj.getClass().getMethods()) {
            if (m.getDeclaringClass() != Object.class) {
                sb.append("  - ").append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("()\n");
            }
        }
        log.info(sb.toString());
    }

    private RandomAccessibleInterval<UnsignedByteType> toRai(ImagePlus frame) {
        ij.process.ImageProcessor ip = frame.getProcessor();
        if (frame.getBitDepth() != 8) {
            ip = ip.convertToByteProcessor();
        }
        ImagePlus flat2d = new ImagePlus("frame_2d", ip);
        return ImageJFunctions.wrapByte(flat2d);
    }

    private void pushToRoiManager(Roi[] rois) {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            rm = new RoiManager();
        }
        rm.setVisible(true);

        int added = 0;
        for (Roi roi : rois) {
            if (roi != null) {
                rm.addRoi(roi);
                added++;
            }
        }
        log.info("[SamSegmenter] Added " + added + " ROI in ROI Manager");
    }

    private static boolean isSamjAvailable() {
        try { Class.forName("ai.nets.samj.ij.SAMJ_Annotator"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private boolean showModelDialog() {
        final String[] labels = { "SAM2 Tiny (~40 MB, fastest, CPU-friendly)",
                "SAM2 Small (~90 MB)",
                "SAM2 Large (~310 MB, most accurate)",
                "EfficientSAM (~25 MB)" };
        final String[] keys   = { "SAM2 Tiny", "SAM2 Small", "SAM2 Large", "EfficientSAM" };

        final JPanel p = new JPanel(new BorderLayout(8, 8));
        p.add(new JLabel("<html><b>Choose SAM2 model</b><br><br>" +
                        "1. Select model below.<br>" +
                        "2. Click the scratch area to place prompt points.<br>" +
                        "3. Press OK - SAM will return a polygon ROI.<br><br>" +
                        "<i>Tip: click inside the acellular (bright) scratch band.</i></html>"),
                BorderLayout.NORTH);
        final JComboBox<String> combo = new JComboBox<>(labels);
        p.add(combo, BorderLayout.CENTER);

        if (JOptionPane.showConfirmDialog(null, p, "SAMJ",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return false;

        selectedModel = keys[combo.getSelectedIndex()];
        log.info("[SamSegmenter] Model: " + selectedModel);
        return true;
    }
}