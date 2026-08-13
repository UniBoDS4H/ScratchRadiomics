package com.ds4h.scratchradiomics.radiomics;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;

import java.awt.Rectangle;
import java.awt.geom.PathIterator;

/**
 * Morphological shape features — IBSI chapter 2.
 *
 * Feature names use IBSI identifiers where defined.
 *
 *  Fmorph.vol       area (px and calibrated)
 *  Fmorph.peri      perimeter
 *  Fmorph.circ      circularity  = 4π·A / P²
 *  Fmorph.comp      compactness  = P / (2·√(π·A))
 *  Fmorph.spher     sphericity (2D: same as circularity for planar ROI)
 *  Fmorph.asprat    aspect ratio (bounding box W/H)
 *  Fmorph.solid     solidity = A / A_convex
 *  Fmorph.elong     elongation = minor / major axis (via bounding box)
 *  Fmorph.flat      flatness (= elongation for 2D)
 *  Fmorph.feret_max maximum Feret diameter
 *  Fmorph.feret_min minimum Feret diameter
 *  fmorph.tort      edge tortuosity = P / Feret_max
 */
public class MorphologyFeatures {
    private MorphologyFeatures() {}

    public static void compute(ImagePlus imp, Roi roi, RadiomicResult r) {
        Calibration cal = imp.getCalibration();
        double pw = cal.pixelWidth, ph = cal.pixelHeight;

        double areaPx   = roi.getStatistics().area;
        double perimPx  = roi.getLength();
        Rectangle bb    = roi.getBounds();

        // ── Area / Perimeter ──────────────────────────────────────────────────
        r.put("Fmorph.vol_px",   areaPx);
        r.put("Fmorph.vol_unit", areaPx * pw * ph);
        r.put("Fmorph.peri_px",  perimPx);
        r.put("Fmorph.peri_unit",perimPx * pw);

        // ── Bounding box ─────────────────────────────────────────────────────
        r.put("Fmorph.bb_width_px",  (double) bb.width);
        r.put("Fmorph.bb_height_px", (double) bb.height);
        double asprat = bb.height > 0 ? (double) bb.width / bb.height : Double.NaN;
        r.put("Fmorph.asprat", asprat);

        // ── Shape descriptors ─────────────────────────────────────────────────
        double circ = perimPx > 0 ? (4.0 * Math.PI * areaPx) / (perimPx * perimPx) : Double.NaN;
        r.put("Fmorph.circ",   circ);
        r.put("Fmorph.spher",  circ);  // 2D special case
        double comp = areaPx > 0 ? perimPx / (2.0 * Math.sqrt(Math.PI * areaPx)) : Double.NaN;
        r.put("Fmorph.comp", comp);

        // ── Elongation / Flatness (via bbox major/minor) ──────────────────────
        double major = Math.max(bb.width, bb.height);
        double minor = Math.min(bb.width, bb.height);
        double elong = major > 0 ? minor / major : Double.NaN;
        r.put("Fmorph.elong", elong);
        r.put("Fmorph.flat",  elong);  // 2D: same value

        // ── Solidity ──────────────────────────────────────────────────────────
        double convexArea = convexHullArea(roi);
        r.put("Fmorph.solid", convexArea > 0 ? areaPx / convexArea : Double.NaN);
        r.put("Fmorph.convex_area_px", convexArea);

        // ── Feret diameters ───────────────────────────────────────────────────
        double[] feret = roi.getFeretValues();
        if (feret != null && feret.length >= 3) {
            r.put("Fmorph.feret_max_px",   feret[0]);
            r.put("Fmorph.feret_min_px",   feret[2]);
            r.put("Fmorph.feret_max_unit", feret[0] * pw);
            r.put("Fmorph.feret_min_unit", feret[2] * pw);
            // ── Edge tortuosity ───────────────────────────────────────────────
            r.put("Fmorph.tort", feret[0] > 0 ? perimPx / feret[0] : Double.NaN);
        }
    }

    /** Shoelace formula on the ROI polygon vertices (approximates convex hull area). */
    private static double convexHullArea(Roi roi) {
        try {
            ij.process.FloatPolygon fp = roi.getFloatPolygon();
            int n = fp.npoints;
            if (n < 3) return 0;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                sum += fp.xpoints[i] * fp.ypoints[j] - fp.xpoints[j] * fp.ypoints[i];
            }
            return Math.abs(sum) / 2.0;
        } catch (Exception e) { return 0; }
    }
}