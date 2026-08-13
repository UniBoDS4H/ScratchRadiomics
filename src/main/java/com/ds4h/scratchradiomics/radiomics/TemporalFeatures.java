package com.ds4h.scratchradiomics.radiomics;

import ij.measure.Calibration;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wound-healing temporal closure features (not in IBSI but wound-healing standard).
 *
 *  Ftemp.rwa         relative wound area  = A_t / A_0
 *  Ftemp.closure     closure rate         = (A_0 - A_t) / (A_0 * t)
 *  Ftemp.vel         migration velocity   (µm/h or calibrated)
 *  Ftemp.accel       acceleration index   (d²/dt² of closure rate)
 *  Ftemp.half_life   time to 50% closure  (interpolated, added to all frames)
 */
public class TemporalFeatures {
    private TemporalFeatures() {}

    public static void compute(List<RadiomicResult> results, Calibration cal) {
        if (results == null || results.isEmpty()) return;
        List<RadiomicResult> sorted = results.stream()
                .sorted(Comparator.comparingInt(r -> r.frameIndex))
                .collect(Collectors.toList());

        double area0 = sorted.get(0).get("Fmorph.vol_unit");
        if (Double.isNaN(area0) || area0 <= 0) return;

        double dt = cal.frameInterval > 0 ? cal.frameInterval : 1.0;
        int n = sorted.size();
        double[] cr = new double[n];

        for (int i = 0; i < n; i++) {
            RadiomicResult r = sorted.get(i);
            double af = r.get("Fmorph.vol_unit");
            double t  = i * dt;
            r.put("Ftemp.rwa",     af / area0);
            double closure = (t > 0 && area0 > 0) ? (area0 - af) / (area0 * t) : 0;
            r.put("Ftemp.closure", closure);
            cr[i] = closure;
            double vel = (t > 0) ? (Math.sqrt(area0) - Math.sqrt(af)) / t : 0;
            r.put("Ftemp.vel",     vel * cal.pixelWidth);
        }

        // Acceleration (central differences)
        for (int i = 0; i < n; i++) {
            double acc = (i == 0 || i == n - 1) ? 0
                    : (cr[i+1] - cr[i-1]) / (2.0 * dt);
            sorted.get(i).put("Ftemp.accel", acc);
        }

        // Half-life (linear interpolation)
        double hl = Double.NaN;
        for (int i = 1; i < n; i++) {
            double rwaPrev = sorted.get(i-1).get("Ftemp.rwa");
            double rwaCurr = sorted.get(i  ).get("Ftemp.rwa");
            if (rwaCurr <= 0.5 && rwaPrev > 0.5) {
                double frac = (rwaPrev - 0.5) / (rwaPrev - rwaCurr);
                hl = ((i - 1) + frac) * dt;
                break;
            }
        }
        final double halfLife = hl;
        sorted.forEach(r -> r.put("Ftemp.half_life", halfLife));
    }
}