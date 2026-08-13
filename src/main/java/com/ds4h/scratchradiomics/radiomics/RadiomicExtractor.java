package com.ds4h.scratchradiomics.radiomics;

import ij.ImagePlus;
import ij.gui.Roi;
import org.scijava.log.LogService;
import com.ds4h.scratchradiomics.radiomics.PyRadiomicsWrapper.PyRadiomicsUnavailableException;

import java.util.*;

/**
 * Orchestratore dell'estrazione radiomica IBSI-compliant via PyRadiomics.
 *
 * Pipeline per ogni (frame, ROI):
 *   1. PythonEnvironmentManager garantisce che il venv con PyRadiomics sia pronto.
 *   2. SHAPE_2D è calcolata da MorphologyFeatures (Java) — PyRadiomics la gestisce
 *      solo in 3D e i descrittori 2D del wound healing sono più accurati nativamente.
 *   3. Tutte le altre famiglie (FIRST_ORDER, GLCM, GLRLM, GLSZM, GLDZM, NGTDM,
 *      NGLDM) sono delegate a PyRadiomicsWrapper che lancia lo script Python.
 *   4. TEMPORAL è calcolata da TemporalFeatures (Java) sui risultati aggregati
 *      di tutti i frame — non ha senso delegarla a PyRadiomics che lavora
 *      su singole immagini.
 *
 * Se PyRadiomics non è disponibile (setup fallito), viene lanciata un'eccezione
 * che IbsiMeasureDialog mostra all'utente — nessun fallback silenzioso.
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
        int nFrames = imp.getNFrames();
        for (int f = 1; f <= nFrames; f++) {
            for (Roi roi : rois) {
                int roiFrame = roi.getPosition();
                if (roiFrame == 0 || roiFrame == f) {
                    String label = (roi.getName() != null && !roi.getName().isEmpty())
                            ? roi.getName() : "ROI_f" + f;
                    results.add(new RadiomicResult(f, label));
                }
            }
        }
    }

    /**
     * Esegue tutte le famiglie di feature selezionate.
     *
     * @param featureSets gruppi scelti dall'utente in IbsiMeasureDialog
     * @throws Exception se PyRadiomics non è disponibile o l'estrazione fallisce
     */
    public void compute(Set<IbsiFeatureSet> featureSets) throws Exception {
        ensurePythonReady();

        PyRadiomicsWrapper wrapper = new PyRadiomicsWrapper(log);
        for (RadiomicResult res : results) {
            Roi roi = getMatchingRoi(res);
            if (roi == null) {
                log.warn("[RadiomicExtractor] Nessuna ROI trovata per frame " +
                        res.frameIndex + " — skip.");
                continue;
            }

            imp.setPosition(1, 1, res.frameIndex);
            ImagePlus sliceImp = new ImagePlus(
                    "slice_f" + res.frameIndex,
                    imp.getProcessor().duplicate());
            sliceImp.setCalibration(imp.getCalibration());

            if (featureSets.contains(IbsiFeatureSet.SHAPE_2D)) {
                log.debug("[RadiomicExtractor] Shape 2D — frame " + res.frameIndex);
                MorphologyFeatures.compute(imp, roi, res);
            }

            Set<IbsiFeatureSet> pyRadSets = withoutShapeAndTemporal(featureSets);
            if (!pyRadSets.isEmpty()) {
                log.debug("[RadiomicExtractor] PyRadiomics — frame " + res.frameIndex);
                wrapper.extract(pythonCmd, sliceImp, roi, pyRadSets, res);
            }
        }

        if (featureSets.contains(IbsiFeatureSet.TEMPORAL) && imp.getNFrames() > 1) {
            log.debug("[RadiomicExtractor] Temporal features…");
            TemporalFeatures.compute(results, imp.getCalibration());
        }
    }

    public List<RadiomicResult> getResults() { return results; }

    private void ensurePythonReady() throws Exception {
        if (pythonCmd != null) return;

        PythonEnvironmentManager envMgr = new PythonEnvironmentManager(log);
        pythonCmd = envMgr.ensureReady();

        log.info("[RadiomicExtractor] Python pronto: " + pythonCmd);
    }

    private static Set<IbsiFeatureSet> withoutShapeAndTemporal(Set<IbsiFeatureSet> in) {
        Set<IbsiFeatureSet> out = new LinkedHashSet<>(in);
        out.remove(IbsiFeatureSet.SHAPE_2D);
        out.remove(IbsiFeatureSet.TEMPORAL);
        return out;
    }

    private Roi getMatchingRoi(RadiomicResult res) {
        for (Roi roi : rois) {
            int rp = roi.getPosition();
            String name = roi.getName() != null ? roi.getName() : "";
            if ((rp == 0 || rp == res.frameIndex) &&
                    (res.roiLabel.equals(name) ||
                            res.roiLabel.equals("ROI_f" + res.frameIndex)))
                return roi;
        }
        for (Roi roi : rois) if (roi.getPosition() == 0) return roi;
        return null;
    }
}