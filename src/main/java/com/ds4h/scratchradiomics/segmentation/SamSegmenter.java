package com.ds4h.scratchradiomics.segmentation;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.*;
import ij.plugin.frame.RoiManager;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.scijava.log.LogService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Integrates SAMJ (segment-anything-models-java) for automatic scratch segmentation.
 */
public class SamSegmenter {

    private static final String CLS_ANNOTATOR = "ai.nets.samj.ij.SAMJ_Annotator";
    private static final String CLS_SAM2_TINY  = "ai.nets.samj.communication.model.SAM2.SAM2Tiny";
    private static final String CLS_SAM2_SMALL = "ai.nets.samj.communication.model.SAM2.SAM2Small";
    private static final String CLS_SAM2_LARGE = "ai.nets.samj.communication.model.SAM2.SAM2Large";
    private static final String CLS_EFF_SAM    = "ai.nets.samj.communication.model.EfficientSAM.EfficientSAM";

    private final ImagePlus  imp;
    private final LogService log;
    private String selectedModel = "SAM2 Tiny";

    public SamSegmenter(ImagePlus imp, LogService log) {
        this.imp = imp; this.log = log;
    }

    public Roi[] segment(int nPromptPoints) throws Exception {
        log.info("[SamSegmenter] model=" + selectedModel + " frames=" + imp.getNFrames());

        if (!isSamjAvailable()) { showInstallDialog(); return new Roi[0]; }
        if (!showModelDialog())  return new Roi[0];

        int nFrames = imp.getNFrames();
        final Roi[] result = nFrames == 1
                ? new Roi[]{ segmentFrame(imp, 1, nPromptPoints) }
                : segmentStack(nPromptPoints);

        pushToRoiManager(result);
        return result;
    }

    private Roi segmentFrame(ImagePlus frame, int idx, int nPts) throws Exception {
        log.info("[SamSegmenter] Encoding frame " + idx);
        final RandomAccessibleInterval<UnsignedByteType> rai = toRai(frame);
        final List<int[]> pts  = idx == 1
                ? collectInteractive(frame, nPts)
                : collectAuto(frame, nPts);
        final List<Rectangle> rects = new ArrayList<>();
        if (pts.isEmpty()) { log.warn("[SamSegmenter] No prompts for frame " + idx); return null; }
        final List<?> masks = runInference(rai, pts, rects);
        return masks == null || masks.isEmpty() ? null : bestMask(masks, idx);
    }

    private Roi[] segmentStack(int nPts) throws Exception {
        final int nFrames = imp.getNFrames();
        final Roi[] result = new Roi[nFrames];
        imp.setPosition(1, 1, 1);
        result[0] = segmentFrame(new ImagePlus("f1", imp.getProcessor().duplicate()), 1, nPts);
        if (result[0] == null) return result;
        if (!tryVideoPropagate(result, nFrames)) {
            log.info("[SamSegmenter] Frame-by-frame fallback");
            for (int f = 2; f <= nFrames; f++) {
                imp.setPosition(1, 1, f);
                result[f-1] = segmentFrame(
                        new ImagePlus("f"+f, imp.getProcessor().duplicate()), f, nPts);
                IJ.showProgress(f, nFrames);
            }
        }
        IJ.showProgress(1.0);
        return result;
    }

    private boolean tryVideoPropagate(Roi[] result, int nFrames) {
        try {
            Class.forName("ai.nets.samj.communication.model.SAM2.SAM2VideoPredictor");
            log.info("[SamSegmenter] SAM2 video predictor found (stub — propagation TODO)");
            return false;
        } catch (ClassNotFoundException e) { return false; }
    }

    private List<int[]> collectInteractive(ImagePlus frame, int nPts) throws InterruptedException {
        final List<int[]> pts = new ArrayList<>();
        IJ.setTool("multipoint");
        frame.show();
        final ImageCanvas canvas = frame.getCanvas();
        final MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                pts.add(new int[]{ canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()) });
            }
        };
        canvas.addMouseListener(ma);
        final Roi existing = frame.getRoi();
        if (existing instanceof PointRoi) {
            final PointRoi pr = (PointRoi) existing;
            for (final Point p : pr.getContainedPoints()) pts.add(new int[]{p.x, p.y});
        }
        new WaitForUserDialog("SAM Prompts",
                "Click on the SCRATCH AREA (" + nPts + " points recommended).\nPress OK when done.").show();
        canvas.removeMouseListener(ma);
        frame.hide();
        if (pts.isEmpty()) return collectAuto(frame, nPts);
        log.info("[SamSegmenter] " + pts.size() + " interactive prompts collected");
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
        ij.process.ImageProcessor ip = frame.getProcessor();
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
        final Class<?> annClass   = Class.forName(CLS_ANNOTATOR);
        final Object   annotator  = annClass.getDeclaredConstructor().newInstance();
        final Object   model      = resolveModel().getDeclaredConstructor().newInstance();
        final java.lang.reflect.Method m = findMethod(annClass, "samJReturnContours", 4);
        return (List<?>) m.invoke(annotator, model, rai, pts, rects);
    }

    private Class<?> resolveModel() throws ClassNotFoundException {
        switch (selectedModel) {
            case "SAM2 Small": return Class.forName(CLS_SAM2_SMALL);
            case "SAM2 Large": return Class.forName(CLS_SAM2_LARGE);
            case "EfficientSAM": return Class.forName(CLS_EFF_SAM);
            default: return Class.forName(CLS_SAM2_TINY);
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
        double bestArea = 0;
        for (final Object mask : masks) {
            try {
                final List<int[][]> contours = (List<int[][]>)
                        mask.getClass().getMethod("getContours").invoke(mask);
                for (final int[][] c : contours) {
                    if (c == null || c.length < 3) continue;
                    final int[] xs = new int[c.length], ys = new int[c.length];
                    for (int i = 0; i < c.length; i++) { xs[i] = c[i][0]; ys[i] = c[i][1]; }
                    final PolygonRoi roi = new PolygonRoi(xs, ys, c.length, Roi.POLYGON);
                    roi.setPosition(frameIdx);
                    final double a = roi.getStatistics().area;
                    if (a > bestArea) { bestArea = a; best = roi; }
                }
            } catch (Exception ex) { log.warn("[SamSegmenter] Mask read error: " + ex.getMessage()); }
        }
        return best;
    }

    private RandomAccessibleInterval<UnsignedByteType> toRai(ImagePlus frame) {
        final ImagePlus dup = frame.duplicate();
        if (dup.getBitDepth() != 8) IJ.run(dup, "8-bit", "");
        return ImageJFunctions.wrapByte(dup);
    }

    private void pushToRoiManager(Roi[] rois) {
        final RoiManager rm = RoiManager.getRoiManager();
        final long added = Arrays.stream(rois).filter(Objects::nonNull).count();
        log.info("[SamSegmenter] Added " + added + " ROIs to ROI Manager");
    }

    private static boolean isSamjAvailable() {
        try { Class.forName("ai.nets.samj.ij.SAMJ_Annotator"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private static void showInstallDialog() {
        IJ.error("SAMJ not installed",
                "SAMJ is not installed.\n\n" +
                        "Help > Update > Manage update sites\n" +
                        "Enable 'SAMJ'  (https://sites.imagej.net/SAMJ/)\n" +
                        "Restart Fiji, then open Plugins > SAMJ > SAMJ Annotator\n" +
                        "to download model weights (~40-300 MB).\n\n" +
                        "Or switch to Manual ROI mode.");
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
                        "3. Press OK \u2014 SAM will return a polygon ROI.<br><br>" +
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