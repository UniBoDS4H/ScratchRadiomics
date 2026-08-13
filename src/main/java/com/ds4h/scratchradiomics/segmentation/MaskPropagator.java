package com.ds4h.scratchradiomics.segmentation;

import ij.ImagePlus;
import ij.gui.Roi;
import org.scijava.log.LogService;

/**
 * Propagates a seed ROI across time-lapse frames.
 * Current strategy: clone + associate position (replace with active contour for production).
 */
public class MaskPropagator {
    private final ImagePlus  imp;
    private final LogService log;

    public MaskPropagator(ImagePlus imp, LogService log) { this.imp = imp; this.log = log; }

    public Roi[] propagate(Roi[] seedRois) {
        int nFrames = imp.getNFrames();
        Roi[] out = new Roi[nFrames];
        Roi seed = seedRois.length > 0 ? seedRois[0] : null;
        if (seed == null) { log.warn("[MaskPropagator] No seed ROI"); return seedRois; }
        for (int f = 0; f < nFrames; f++) {
            if (f < seedRois.length && seedRois[f] != null) { out[f] = seedRois[f]; seed = seedRois[f]; }
            else { out[f] = (Roi) seed.clone(); out[f].setPosition(f + 1); }
        }
        return out;
    }
}