package com.ds4h.scratchradiomics.output;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import com.ds4h.scratchradiomics.radiomics.RadiomicResult;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/** Exports results to CSV. */
public class ResultsExporter {
    private ResultsExporter() {}

    /**
     * Export the results in CSV format.
     *
     * @param results The results to export
     * @param file The file to write
     * @throws IOException
     */
    public static void exportCsv(List<RadiomicResult> results, File file) throws IOException {
        if (results == null || results.isEmpty()) return;
        final Set<String> featureNames = new LinkedHashSet<>();
        for (final RadiomicResult r : results)
            featureNames.addAll(r.getFeatures().keySet());

        final List<String> header = new ArrayList<>();
        header.add("frame");
        header.add("roi_label");
        header.addAll(featureNames);

        try (final CSVPrinter p = new CSVPrinter(new FileWriter(file),
                CSVFormat.DEFAULT.builder().setHeader(header.toArray(new String[0])).build())) {
            for (final RadiomicResult r : results) {
                final List<Object> row = new ArrayList<>();
                row.add(r.frameIndex);
                row.add(r.roiLabel);
                for (final String n : featureNames) {
                    final double v = r.get(n);
                    row.add(Double.isNaN(v) ? "" : String.format("%.6f", v));
                }
                p.printRecord(row);
            }
        }
    }
}