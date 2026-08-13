package com.ds4h.scratchradiomics.segmentation;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.scijava.log.LogService;
import java.util.Arrays;

/** Retrieves scratch ROIs from the ImageJ ROI Manager. */
public class RoiSegmenter {
    private final ImagePlus  imp;
    private final LogService log;

    public RoiSegmenter(ImagePlus imp, LogService log) { this.imp = imp; this.log = log; }

    public Roi[] getRoisFromManager() {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            log.warn("[RoiSegmenter] ROI Manager empty"); return new Roi[0];
        }
        Roi[] rois = rm.getRoisAsArray();
        log.info("[RoiSegmenter] " + rois.length + " ROIs retrieved");
        return rois;
    }

    public Roi[] getRoisForFrame(int frameIndex) {
        return Arrays.stream(getRoisFromManager())
                .filter(r -> r.getPosition() == 0 || r.getPosition() == frameIndex)
                .toArray(Roi[]::new);
    }
}