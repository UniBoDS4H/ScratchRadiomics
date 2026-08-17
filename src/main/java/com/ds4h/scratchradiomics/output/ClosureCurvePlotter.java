package com.ds4h.scratchradiomics.output;

import ij.gui.Plot;
import com.ds4h.scratchradiomics.radiomics.RadiomicResult;

import java.awt.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Plots wound closure dynamics using ImageJ's built-in Plot. */
public class ClosureCurvePlotter {
    private ClosureCurvePlotter() {}

    /**
     * Plot the wound closure dynamics (relative wound area, closure rate and migration velocity).
     *
     * @param results The results obtained from the scratch assay analysis.
     * @param title The title of the window.
     */
    public static void plot(List<RadiomicResult> results, String title) {
        if (results == null || results.isEmpty()) return;
        final List<RadiomicResult> s = results.stream()
                .sorted(Comparator.comparingInt(r -> r.frameIndex)).collect(Collectors.toList());
        final int n = s.size();
        final double[] frames = new double[n], rwa = new double[n],
                cr = new double[n], vel = new double[n];
        for (int i = 0; i < n; i++) {
            frames[i] = s.get(i).frameIndex;
            rwa[i]    = nn(s.get(i).get("Ftemp.rwa"));
            cr[i]     = nn(s.get(i).get("Ftemp.closure"));
            vel[i]    = nn(s.get(i).get("Ftemp.vel"));
        }
        final Plot plot = new Plot("Closure Curve Plot - " + title, "Frame", "Value");
        plot.setLimits(frames[0], frames[n-1], -0.05, 1.1);

        plot.setColor(new Color(0x1565C0)); plot.setLineWidth(2);
        plot.addPoints(frames, rwa, Plot.LINE);
        plot.addLabel(0.02, 0.10, "Relative wound area (Ftemp.rwa)");

        final double crMax = maxAbs(cr);
        if (crMax > 0) {
            final double[] crN = norm(cr, crMax);
            plot.setColor(new Color(0xE65100)); plot.setLineWidth(1);
            plot.addPoints(frames, crN, Plot.LINE);
            plot.addLabel(0.02, 0.20, "Closure rate (normalised)");
        }
        final double velMax = maxAbs(vel);
        if (velMax > 0) {
            final double[] velN = norm(vel, velMax);
            plot.setColor(new Color(0x2E7D32)); plot.setLineWidth(1);
            plot.addPoints(frames, velN, Plot.LINE);
            plot.addLabel(0.02, 0.30, "Migration velocity (normalised)");
        }
        final double hl = nn(s.get(0).get("Ftemp.half_life"));
        if (hl > 0 && hl <= frames[n-1]) {
            plot.setColor(Color.RED); plot.setLineWidth(1);
            plot.drawLine(hl, 0, hl, 0.5);
            plot.addLabel(hl / frames[n-1] + 0.01, 0.45, "t½");
        }
        plot.setColor(Color.BLACK);
        plot.show();
    }

    private static double nn(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static double maxAbs(double[] a) {
        double m = 0;
        for(final double v : a)
            if(Math.abs(v) > m)
                m = Math.abs(v);
        return m;
    }

    private static double[] norm(double[] a, double max) {
        final double[] o = new double[a.length];
        for(int i = 0; i < a.length; i++)
            o[i] = a[i] / max;
        return o;
    }
}