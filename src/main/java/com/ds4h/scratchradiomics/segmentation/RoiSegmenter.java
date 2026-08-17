package com.ds4h.scratchradiomics.segmentation;

import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import org.scijava.log.LogService;

/** Retrieves scratch ROIs from the ImageJ ROI Manager. */
public class RoiSegmenter {
    private final LogService log;

    public RoiSegmenter(LogService log) {
        this.log = log;
    }

    /**
     * Retrieve the ROIs from the Fiji ROI Manager.
     *
     * @return The ROIs
     */
    public Roi[] getRoisFromManager() {
        final RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            log.warn("[RoiSegmenter] ROI Manager empty"); return new Roi[0];
        }
        final Roi[] rois = rm.getRoisAsArray();
        log.info("[RoiSegmenter] " + rois.length + " ROIs retrieved");
        return rois;
    }
}