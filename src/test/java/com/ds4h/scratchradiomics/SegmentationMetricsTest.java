package com.ds4h.scratchradiomics;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class SegmentationMetricsTest {

    private double[] loadCsvValues(String filePath) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        if (lines.isEmpty()) {
            return new double[0];
        }

        String[] headers = lines.get(0).split(",");
        int areaColIndex = -1;
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase("Area")) {
                areaColIndex = i;
                break;
            }
        }
        if (areaColIndex == -1) {
            areaColIndex = 0;
        }

        int targetCol = areaColIndex;
        if (lines.size() > 1) {
            String[] firstDataRow = lines.get(1).split(",");
            if (firstDataRow.length == headers.length + 1) {
                targetCol = areaColIndex + 1;
            }
        }

        final int finalColIndex = targetCol;
        return lines.stream()
                .skip(1)
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .mapToDouble(line -> {
                    String[] tokens = line.split(",");
                    return Double.parseDouble(tokens[finalColIndex].trim());
                })
                .toArray();
    }

    @Test
    public void testSegmentationAccuracy() throws Exception {
        String gtPath = "src/test/java/resources/gt_areas.csv";
        double[] areaGT = loadCsvValues(gtPath);

        String calculatedPath = "src/test/java/resources/calculated_areas.csv";
        double[] areaCalculated = loadCsvValues(calculatedPath);

        assertEquals(areaGT.length, areaCalculated.length);

        double sumPercentageDiff = 0.0;
        for (int i = 0; i < areaGT.length; i++) {
            double absDiff = Math.abs(areaCalculated[i] - areaGT[i]);
            sumPercentageDiff += (absDiff / areaGT[i]) * 100.0;
        }

        double mpad = sumPercentageDiff / areaGT.length;

        assertTrue("MPAD over threshold: " + mpad + "%", mpad < 10.0);
    }
}
