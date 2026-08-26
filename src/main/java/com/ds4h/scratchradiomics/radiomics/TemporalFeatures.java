package com.ds4h.scratchradiomics.radiomics;

import ij.measure.Calibration;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wound-healing temporal closure features (not in IBSI but wound-healing standard).<br/>
 *
 *  Ftemp.rwa   ->      relative wound area  = A_t / A_0 <br/>
 *  Ftemp.closure  ->   closure rate         = (A_0 - A_t) / (A_0 * t)<br/>
 *  Ftemp.vel    ->     migration velocity   (µm/h or calibrated)<br/>
 *  Ftemp.accel   ->    acceleration index   (d²/dt² of closure rate)<br/>
 *  Ftemp.half_life ->  time to 50% closure  (interpolated, added to all frames)<br/>
 */
public class TemporalFeatures {
    private TemporalFeatures() {}

    /**
     * Calculate the temporal features.
     *
     * @param results The ROIs
     * @param cal The calibration
     */
    public static void compute(List<RadiomicResult> results, Calibration cal) {
        if (results == null || results.isEmpty()) return;
        final List<RadiomicResult> sorted = results.stream()
                .sorted(Comparator.comparingInt(r -> r.frameIndex))
                .collect(Collectors.toList());

        final double area0 = sorted.get(0).get("Fmorph.vol_unit");
        if (Double.isNaN(area0) || area0 <= 0) return;

        final double dt = cal.frameInterval > 0 ? cal.frameInterval : 1.0;
        final int n = sorted.size();
        final double[] cr = new double[n];

        for (int i = 0; i < n; i++) {
            final RadiomicResult r = sorted.get(i);
            final double af = r.get("Fmorph.vol_unit");
            final double t  = i * dt;
            r.put("Ftemp.rwa",     af / area0);
            final double closure = (t > 0 && area0 > 0) ? (area0 - af) / (area0 * t) : 0;
            r.put("Ftemp.closure", closure);
            cr[i] = closure;
            final double vel = (t > 0) ? (Math.sqrt(area0) - Math.sqrt(af)) / t : 0;
            r.put("Ftemp.vel",     vel * cal.pixelWidth);
        }

        for (int i = 0; i < n; i++) {
            final double acc = (i == 0 || i == n - 1) ? 0
                    : (cr[i+1] - cr[i-1]) / (2.0 * dt);
            sorted.get(i).put("Ftemp.accel", acc);
        }

        double hl = Double.NaN;
        for (int i = 1; i < n; i++) {
            final double rwaPrev = sorted.get(i-1).get("Ftemp.rwa");
            final double rwaCurr = sorted.get(i  ).get("Ftemp.rwa");
            if (rwaCurr <= 0.5 && rwaPrev > 0.5) {
                final double frac = (rwaPrev - 0.5) / (rwaPrev - rwaCurr);
                hl = ((i - 1) + frac) * dt;
                break;
            }
        }
        final double halfLife = hl;
        sorted.forEach(r -> r.put("Ftemp.half_life", halfLife));
    }
}