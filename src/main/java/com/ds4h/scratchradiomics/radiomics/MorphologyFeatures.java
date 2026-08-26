package com.ds4h.scratchradiomics.radiomics;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatPolygon;

import java.awt.Rectangle;
import java.awt.geom.PathIterator;

/**
 * Morphological shape features.<br/>
 *
 * Feature names use IBSI identifiers where defined.<br/><br/>
 *
 *  Fmorph.vol   ->    area (px and calibrated)<br/>
 *  Fmorph.peri  ->    perimeter<br/>
 *  Fmorph.circ  ->    circularity  = 4π·A / P²<br/>
 *  Fmorph.comp   ->   compactness  = P / (2·√(π·A))<br/>
 *  Fmorph.spher  ->   sphericity (2D: same as circularity for planar ROI)<br/>
 *  Fmorph.asprat ->   aspect ratio (bounding box W/H)<br/>
 *  Fmorph.solid  ->   solidity = A / A_convex<br/>
 *  Fmorph.elong  ->   elongation = minor / major axis (via bounding box)<br/>
 *  Fmorph.flat   ->   flatness (= elongation for 2D)<br/>
 *  Fmorph.feret_max -> maximum Feret diameter<br/>
 *  Fmorph.feret_min -> minimum Feret diameter<br/>
 *  fmorph.tort   ->   edge tortuosity = P / Feret_max<br/>
 */
public class MorphologyFeatures {
    private MorphologyFeatures() {}

    /**
     * Calculates the morphological shape features.
     *
     * @param imp The image of the wound.
     * @param roi The ROI of the wound.
     * @param r The result to return.
     */
    public static void compute(ImagePlus imp, Roi roi, RadiomicResult r) {
        final Calibration cal = imp.getCalibration();
        final double pw = cal.pixelWidth, ph = cal.pixelHeight;

        final double areaPx   = roi.getStatistics().area;
        final double perimPx  = roi.getLength();
        final Rectangle bb    = roi.getBounds();

        r.put("Fmorph.vol_px",   areaPx);
        r.put("Fmorph.vol_unit", areaPx * pw * ph);
        r.put("Fmorph.peri_px",  perimPx);
        r.put("Fmorph.peri_unit",perimPx * pw);

        r.put("Fmorph.bb_width_px", bb.width);
        r.put("Fmorph.bb_height_px", bb.height);
        final double asprat = bb.height > 0 ? (double) bb.width / bb.height : Double.NaN;
        r.put("Fmorph.asprat", asprat);

        final double circ = perimPx > 0 ? (4.0 * Math.PI * areaPx) / (perimPx * perimPx) : Double.NaN;
        r.put("Fmorph.circ",   circ);
        r.put("Fmorph.spher",  circ);
        final double comp = areaPx > 0 ? perimPx / (2.0 * Math.sqrt(Math.PI * areaPx)) : Double.NaN;
        r.put("Fmorph.comp", comp);

        final double major = Math.max(bb.width, bb.height);
        final double minor = Math.min(bb.width, bb.height);
        final double elong = major > 0 ? minor / major : Double.NaN;
        r.put("Fmorph.elong", elong);
        r.put("Fmorph.flat",  elong);

        final double convexArea = convexHullArea(roi);
        r.put("Fmorph.solid", convexArea > 0 ? areaPx / convexArea : Double.NaN);
        r.put("Fmorph.convex_area_px", convexArea);

        final double[] feret = roi.getFeretValues();
        if (feret != null && feret.length >= 3) {
            r.put("Fmorph.feret_max_px",   feret[0]);
            r.put("Fmorph.feret_min_px",   feret[2]);
            r.put("Fmorph.feret_max_unit", feret[0] * pw);
            r.put("Fmorph.feret_min_unit", feret[2] * pw);
            r.put("Fmorph.tort", feret[0] > 0 ? perimPx / feret[0] : Double.NaN);
        }
    }

    private static double convexHullArea(Roi roi) {
        try {
            final FloatPolygon fp = roi.getFloatPolygon();
            final int n = fp.npoints;
            if (n < 3) return 0;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                final int j = (i + 1) % n;
                sum += fp.xpoints[i] * fp.ypoints[j] - fp.xpoints[j] * fp.ypoints[i];
            }
            return Math.abs(sum) / 2.0;
        } catch (Exception e) {
            return 0;
        }
    }
}