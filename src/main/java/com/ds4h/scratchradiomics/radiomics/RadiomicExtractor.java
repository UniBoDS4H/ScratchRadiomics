package com.ds4h.scratchradiomics.radiomics;

import ij.ImagePlus;
import ij.gui.Roi;
import org.scijava.log.LogService;

import java.util.*;

/**
 * Orchestrates IBSI-compliant radiomics extraction via PyRadiomics.<br/>
 *
 * Pipeline for each (frame, ROI):<br/>
 *   1. PythonEnvironmentManager ensures that the venv with PyRadiomics is ready.<br/>
 *   2. SHAPE_2D is computed by MorphologyFeatures (Java) - PyRadiomics handles
 *      only 3D shape features, and the 2D descriptors used for wound healing
 *      are more accurately computed natively.<br/>
 *   3. All other feature families (FIRST_ORDER, GLCM, GLRLM, GLSZM, GLDZM, NGTDM,
 *      NGLDM) are delegated to PyRadiomicsWrapper, which runs the Python script.<br/>
 *   4. TEMPORAL is computed by TemporalFeatures (Java) from the aggregated
 *      results of all frames - it does not make sense to delegate it to
 *      PyRadiomics, which operates on individual images.<br/>
 *
 * If PyRadiomics is unavailable (setup failed), an exception is thrown
 * and displayed to the user by IbsiMeasureDialog - no silent fallback.
 */
public class RadiomicExtractor {

    private final ImagePlus  imp;
    private final Roi[]      rois;
    private final LogService log;

    private final List<RadiomicResult> results = new ArrayList<>();
    private String pythonCmd = null;

    public RadiomicExtractor(ImagePlus imp, Roi[] rois, LogService log) {
        this.imp  = imp;
        this.rois = rois;
        this.log  = log;
        initResults();
    }

    private void initResults() {
        final int totalFrames = Math.max(imp.getNFrames(), imp.getStackSize());

        for (int f = 1; f <= totalFrames; f++) {
            final Roi roi = findRoiForFrame(f);
            if (roi != null) {
                final String label = (roi.getName() != null && !roi.getName().isEmpty())
                        ? roi.getName() : "ROI_f" + f;
                results.add(new RadiomicResult(f, label));
            }
        }
    }

    private Roi findRoiForFrame(int frame) {
        for (final Roi roi : rois) {
            final int pos  = roi.getPosition();
            final int tPos = roi.getTPosition();
            final int zPos = roi.getZPosition();
            if (pos == frame || tPos == frame || zPos == frame) {
                return roi;
            }
        }

        if (frame <= rois.length) {
            return rois[frame - 1];
        }

        return null;
    }

    /**
     * Computes the radiomics.
     *
     * @param featureSets The radiomics required by the user.
     * @throws Exception
     */
    public void compute(Set<IbsiFeatureSet> featureSets) throws Exception {
        ensurePythonReady();

        final int totalFrames = Math.max(imp.getNFrames(), imp.getStackSize());
        final boolean needShapeForTemporal = featureSets.contains(IbsiFeatureSet.TEMPORAL);

        final PyRadiomicsWrapper wrapper = new PyRadiomicsWrapper(log);

        for (final RadiomicResult res : results) {
            final Roi roi = findRoiForFrame(res.frameIndex);
            if (roi == null) {
                log.warn("[RadiomicExtractor] No ROI found for frame " +
                        res.frameIndex + " - skip.");
                continue;
            }

            if (imp.isHyperStack()) {
                imp.setPosition(1, 1, res.frameIndex);
            } else {
                imp.setSlice(res.frameIndex);
            }

            final ImagePlus sliceImp = new ImagePlus(
                    "slice_f" + res.frameIndex,
                    imp.getProcessor().duplicate());
            sliceImp.setCalibration(imp.getCalibration());

            if (featureSets.contains(IbsiFeatureSet.SHAPE_2D) || needShapeForTemporal) {
                log.debug("[RadiomicExtractor] Shape 2D - frame " + res.frameIndex);
                MorphologyFeatures.compute(sliceImp, roi, res);
            }

            final Set<IbsiFeatureSet> pyRadSets = withoutShapeAndTemporal(featureSets);
            if (!pyRadSets.isEmpty()) {
                log.debug("[RadiomicExtractor] PyRadiomics - frame " + res.frameIndex);
                wrapper.extract(pythonCmd, sliceImp, roi, pyRadSets, res);
            }
        }

        if (featureSets.contains(IbsiFeatureSet.TEMPORAL) && totalFrames > 1) {
            log.debug("[RadiomicExtractor] Temporal features…");
            TemporalFeatures.compute(results, imp.getCalibration());
        }
    }

    public List<RadiomicResult> getResults() { return new ArrayList<>(results); }

    private void ensurePythonReady() throws Exception {
        if (pythonCmd != null) return;

        final PythonEnvironmentManager envMgr = new PythonEnvironmentManager(log);
        pythonCmd = envMgr.ensureReady();

        log.info("[RadiomicExtractor] Python ready: " + pythonCmd);
    }

    private static Set<IbsiFeatureSet> withoutShapeAndTemporal(Set<IbsiFeatureSet> in) {
        final Set<IbsiFeatureSet> out = new LinkedHashSet<>(in);
        out.remove(IbsiFeatureSet.SHAPE_2D);
        out.remove(IbsiFeatureSet.TEMPORAL);
        return out;
    }
}