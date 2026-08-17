package com.ds4h.scratchradiomics.preprocessing;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

/** Pre-processing pipeline applied before segmentation. */
public class Preprocessor {
    private final ImagePlus imp;
    public Preprocessor(ImagePlus imp) { this.imp = imp; }

    /** Normalize each frame to [0,1] (per-frame min-max). */
    public void normalize() {
        for (int f = 1; f <= imp.getNFrames(); f++) {
            imp.setPosition(1, 1, f);
            final ImageProcessor ip = imp.getProcessor();
            ip.resetMinAndMax();
            final double min = ip.getMin(), max = ip.getMax();
            if (max > min) {
                ip.subtract(min);
                ip.multiply(1.0 / (max - min));
            }
        }
        imp.updateAndDraw();
    }

    /** Rolling-ball background subtraction. */
    public void subtractBackground(int radius) {
        final String opts = "rolling=" + radius + (imp.getNFrames() > 1 ? " stack" : "");
        for (int f = 1; f <= imp.getNFrames(); f++) {
            imp.setPosition(1, 1, f);
            IJ.run(imp, "Subtract Background...", opts);
        }
        imp.updateAndDraw();
    }
}