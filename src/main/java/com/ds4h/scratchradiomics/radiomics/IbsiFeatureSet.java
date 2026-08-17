package com.ds4h.scratchradiomics.radiomics;

/**
 * Enumeration of all IBSI-compliant feature groups supported by this plugin.
 *
 * Each constant maps to:
 *   - a human-readable label shown in IbsiMeasureDialog
 *   - the IBSI chapter reference
 *   - a short code prefix used in RadiomicResult keys
 *   - a boolean to decide if the radiomics is calculated by PyRadiomics or by RadiomicsJ
 *
 * Reference: Image Biomarker Standardisation Initiative (IBSI)
 *            https://ibsi.readthedocs.io
 */
public enum IbsiFeatureSet {

    SHAPE_2D(
            "2D Shape",
            "IBSI §3.1 - area, perimeter, circularity, Feret diameter, border tortuosity",
            "morph",
            false),

    FIRST_ORDER(
            "First-order statistics",
            "IBSI §3.4 - mean, standard deviation, skewness, kurtosis, energy, entropy",
            "stat",
            true),

    GLCM(
            "GLCM texture",
            "IBSI §3.6 - 25 gray-level co-occurrence matrix features (contrast, energy, correlation, ...)",
            "glcm",
            true),

    GLRLM(
            "GLRLM texture",
            "IBSI §3.7 - 16 gray-level run-length matrix features (SRE, LRE, GLNU, ...)",
            "glrlm",
            true),

    GLSZM(
            "GLSZM texture",
            "IBSI §3.8 - 16 gray-level size-zone matrix features (SZE, LZE, ZP, ...)",
            "glszm",
            true),

    GLDZM(
            "GLDZM texture",
            "IBSI §3.10 - 16 gray-level distance-zone matrix features (SDE, LDE, ...)",
            "gldzm",
            true),

    NGTDM(
            "NGTDM texture",
            "IBSI §3.9 - 5 neighbourhood gray-tone difference matrix features (coarseness, busyness, ...)",
            "ngtdm",
            true),

    NGLDM(
            "NGLDM texture",
            "IBSI §3.11 - 17 neighbouring gray-level dependence matrix features",
            "ngldm",
            true),

    TEMPORAL(
            "Temporal (closure dynamics)",
            "Wound healing: closure rate, migration velocity, wound half-life",
            "temp",
            false);

    public final String label;
    public final String description;
    public final String prefix;
    public final boolean delegatedToRadiomicsJ;

    IbsiFeatureSet(String label, String description,
                   String prefix, boolean delegatedToRadiomicsJ) {
        this.label = label;
        this.description = description;
        this.prefix = prefix;
        this.delegatedToRadiomicsJ = delegatedToRadiomicsJ;
    }

    @Override
    public String toString() {
        return label;
    }
}