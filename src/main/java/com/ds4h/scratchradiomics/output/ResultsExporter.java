package com.ds4h.scratchradiomics.output;

import ij.measure.ResultsTable;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import com.ds4h.scratchradiomics.radiomics.RadiomicResult;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/** Exports results to ImageJ Results Table and/or CSV. */
public class ResultsExporter {
    private ResultsExporter() {}

    public static void exportCsv(List<RadiomicResult> results, File file) throws IOException {
        if (results == null || results.isEmpty()) return;
        Set<String> featureNames = new LinkedHashSet<>();
        for (RadiomicResult r : results) featureNames.addAll(r.getFeatures().keySet());

        List<String> header = new ArrayList<>();
        header.add("frame"); header.add("roi_label");
        header.addAll(featureNames);

        try (CSVPrinter p = new CSVPrinter(new FileWriter(file),
                CSVFormat.DEFAULT.builder().setHeader(header.toArray(new String[0])).build())) {
            for (RadiomicResult r : results) {
                List<Object> row = new ArrayList<>();
                row.add(r.frameIndex); row.add(r.roiLabel);
                for (String n : featureNames) {
                    double v = r.get(n);
                    row.add(Double.isNaN(v) ? "" : String.format("%.6f", v));
                }
                p.printRecord(row);
            }
        }
    }
}