package com.ds4h.scratchradiomics.radiomics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds all radiomic features for one (frame, ROI) pair. */
public class RadiomicResult {
    public final int    frameIndex;
    public final String roiLabel;
    private final Map<String, Double> features = new LinkedHashMap<>();

    public RadiomicResult(int frameIndex, String roiLabel) {
        this.frameIndex = frameIndex;
        this.roiLabel   = roiLabel;
    }

    public void put(String name, double value) {
        features.put(name, value);
    }

    public double get(String name) {
        return features.getOrDefault(name, Double.NaN);
    }

    public Map<String, Double> getFeatures() {
        return new LinkedHashMap<>(features);
    }

    @Override
    public String toString() {
        return "RadiomicResult[frame=" + frameIndex + ", roi=" + roiLabel +
                ", n=" + features.size() + "]";
    }
}