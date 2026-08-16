package com.ds4h.scratchradiomics.gui;

import com.ds4h.scratchradiomics.output.ClosureCurvePlotter;
import ij.ImagePlus;
import ij.gui.Roi;
import org.scijava.log.StderrLogService;
import com.ds4h.scratchradiomics.radiomics.*;
import com.ds4h.scratchradiomics.output.ResultsExporter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * IBSI-compliant Measure dialog.
 *
 * Layout:
 *   LEFT  — feature group checkboxes (one per IbsiFeatureSet), with
 *            expandable sub-lists showing the individual feature names.
 *   RIGHT — live results table (populated after "Measure" is pressed).
 *   SOUTH — Measure / Export CSV / Close buttons + progress bar.
 *
 * Feature names are shown with their IBSI tag code to help users
 * cross-reference with the IBSI documentation.
 */
public class IbsiMeasureDialog extends JFrame {

    private final ImagePlus imp;
    private final Roi[]     rois;

    private final Map<IbsiFeatureSet, JCheckBox> groupChecks = new LinkedHashMap<>();

    private DefaultTableModel tableModel;
    private JTable            table;

    private JButton      btnMeasure;
    private JButton      btnExport;
    private JProgressBar progress;

    private List<RadiomicResult> lastResults;

    public IbsiMeasureDialog(ImagePlus imp, Roi[] rois) {
        super("IBSI Measures - " + imp.getTitle());
        this.imp  = imp;
        this.rois = rois;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUI();
        pack();
        setMinimumSize(new Dimension(820, 520));
        setLocationRelativeTo(null);
    }

    private void buildUI() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildFeaturePanel(), buildResultsPanel());
        split.setDividerLocation(260);
        split.setResizeWeight(0.3);

        JPanel south = new JPanel(new BorderLayout(8, 4));
        south.setBorder(new EmptyBorder(6, 8, 8, 8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnMeasure = new JButton("▶  Measure");
        btnExport  = new JButton("Export CSV…");
        JButton btnClose = new JButton("Close");
        btnExport.setEnabled(false);
        btnMeasure.setBackground(new Color(0xBBDEFB));
        btnMeasure.addActionListener( e -> runMeasure());
        btnExport.addActionListener(  e -> exportCsv());
        btnClose.addActionListener(   e -> dispose());
        buttons.add(btnMeasure); buttons.add(btnExport); buttons.add(btnClose);

        progress = new JProgressBar();
        progress.setStringPainted(true);
        progress.setString("Ready");

        south.add(buttons,  BorderLayout.WEST);
        south.add(progress, BorderLayout.CENTER);

        getContentPane().add(split,  BorderLayout.CENTER);
        getContentPane().add(south,  BorderLayout.SOUTH);
    }

    private JPanel buildFeaturePanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(8, 8, 8, 4));

        JPanel header = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Feature groups");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        JPanel selectBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton btnAll  = new JButton("All");
        JButton btnNone = new JButton("None");
        btnAll.setFont(btnAll.getFont().deriveFont(Font.PLAIN, 10f));
        btnNone.setFont(btnNone.getFont().deriveFont(Font.PLAIN, 10f));
        btnAll.addActionListener( e -> groupChecks.values().forEach(c -> c.setSelected(true)));
        btnNone.addActionListener(e -> groupChecks.values().forEach(c -> c.setSelected(false)));
        selectBtns.add(btnAll); selectBtns.add(btnNone);
        header.add(title,      BorderLayout.WEST);
        header.add(selectBtns, BorderLayout.EAST);
        p.add(header, BorderLayout.NORTH);

        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        checks.setBorder(new EmptyBorder(6, 0, 0, 0));

        boolean isStack = imp.getStackSize() > 1 || imp.getNFrames() > 1;

        for (IbsiFeatureSet fs : IbsiFeatureSet.values()) {
            if (fs == IbsiFeatureSet.TEMPORAL && !isStack) continue;

            JCheckBox chk = new JCheckBox(fs.label, true);
            chk.setFont(chk.getFont().deriveFont(Font.BOLD, 12f));
            chk.setToolTipText(fs.description);
            groupChecks.put(fs, chk);

            JLabel desc = new JLabel(
                    "<html><font size='2' color='#666666'>" + fs.description + "</font></html>");
            desc.setBorder(new EmptyBorder(0, 20, 6, 0));

            JPanel subPanel = buildSubFeatureList(fs);
            JButton btnToggle = new JButton("▶ features");
            btnToggle.setFont(btnToggle.getFont().deriveFont(Font.PLAIN, 10f));
            btnToggle.setMargin(new Insets(0, 4, 0, 4));
            btnToggle.addActionListener(ev -> {
                subPanel.setVisible(!subPanel.isVisible());
                btnToggle.setText(subPanel.isVisible() ? "▼ features" : "▶ features");
                pack();
            });
            subPanel.setVisible(false);

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.add(chk); row.add(btnToggle);

            checks.add(row);
            checks.add(desc);
            checks.add(subPanel);
            checks.add(Box.createVerticalStrut(4));
        }

        JScrollPane scroll = new JScrollPane(checks);
        scroll.setBorder(null);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    /** Builds a small indented list of feature names for a given group. */
    private JPanel buildSubFeatureList(IbsiFeatureSet fs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(0, 24, 4, 0));

        for (String name : featureNamesFor(fs)) {
            JLabel lbl = new JLabel("• " + name);
            lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 10f));
            lbl.setForeground(new Color(0x444444));
            p.add(lbl);
        }
        return p;
    }

    private JPanel buildResultsPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(new EmptyBorder(8, 4, 8, 8));

        JLabel title = new JLabel("Results");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setBorder(new EmptyBorder(0, 0, 4, 0));
        p.add(title, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(
                new Object[]{"Frame", "ROI", "Feature", "Value"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        table.getTableHeader().setFont(table.getFont().deriveFont(Font.BOLD));
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);

        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    private void runMeasure() {
        Set<IbsiFeatureSet> selected = new LinkedHashSet<>();
        groupChecks.forEach((fs, chk) -> { if (chk.isSelected()) selected.add(fs); });

        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Select at least one feature group.", "No features selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        btnMeasure.setEnabled(false);
        btnExport.setEnabled(false);
        tableModel.setRowCount(0);
        progress.setValue(0);
        progress.setString("Computing…");

        new SwingWorker<List<RadiomicResult>, Void>() {
            @Override
            protected List<RadiomicResult> doInBackground() throws Exception {
                RadiomicExtractor extractor = new RadiomicExtractor(
                        imp, rois, new StderrLogService());
                extractor.compute(selected);
                return extractor.getResults();
            }

            @Override
            protected void done() {
                try {
                    lastResults = get();

                    populateTable(lastResults);
                    progress.setValue(100);
                    progress.setString("Done - " + lastResults.size() + " row(s)");
                    btnExport.setEnabled(true);

                    if (selected.contains(IbsiFeatureSet.TEMPORAL)) {
                        String plotTitle = (imp.getShortTitle() != null && !imp.getShortTitle().isEmpty())
                                ? imp.getShortTitle()
                                : imp.getTitle();

                        ClosureCurvePlotter.plot(lastResults, plotTitle);
                    }

                } catch (Exception ex) {
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    progress.setString("Error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(IbsiMeasureDialog.this,
                            cause.getMessage(), "Measure failed", JOptionPane.ERROR_MESSAGE);
                } finally {
                    btnMeasure.setEnabled(true);
                }
            }
        }.execute();
    }

    /** Populates the table: one row per (frame, roi, feature). */
    private void populateTable(List<RadiomicResult> results) {
        tableModel.setRowCount(0);
        for (RadiomicResult r : results) {
            r.getFeatures().forEach((name, value) -> {
                tableModel.addRow(new Object[]{
                        r.frameIndex,
                        r.roiLabel,
                        name,
                        Double.isNaN(value) ? "-" : String.format("%.5g", value)
                });
            });
        }
    }

    private void exportCsv() {
        if (lastResults == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(
                imp.getTitle().replaceAll("[^a-zA-Z0-9._-]", "_") + "_ibsi.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ResultsExporter.exportCsv(lastResults, fc.getSelectedFile());
                JOptionPane.showMessageDialog(this,
                        "Saved: " + fc.getSelectedFile().getAbsolutePath(),
                        "Export complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        e.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Returns a representative list of feature names for each group,
     * using RadiomicsJ's actual column names (IBSI-aligned).
     * These are shown in the collapsible sub-panel of IbsiMeasureDialog
     * as a preview — the full list depends on the image and settings.
     */
    private static List<String> featureNamesFor(IbsiFeatureSet fs) {
        switch (fs) {
            case SHAPE_2D:
                return List.of(
                    "Shape2D_PixelSurface", "Shape2D_Perimeter",
                    "Shape2D_PerimeterToSurfaceRatio", "Shape2D_Sphericity",
                    "Shape2D_Circularity", "Shape2D_FerretAngle");
            case FIRST_ORDER:
                return List.of(
                    "Stats_MeanIntensity", "Stats_IntensityVariance",
                    "Stats_IntensitySkewness", "Stats_MedianIntensity",
                    "Stats_MinIntensity", "Stats_MaxIntensity",
                    "Stats_IntensityIQR", "Stats_IntensityEnergy",
                    "Histo_MeanDiscretisedIntensity", "Histo_DiscretisedIntensityEntropy",
                    "Histo_DiscretisedIntensityUniformity",
                    "LocalIntensity_LocalIntensityPeak", "LocalIntensity_GlobalIntensityPeak");
            case GLCM:
                return List.of(
                    "GLCM_JointMaximum", "GLCM_JointAverage", "GLCM_JointVariance",
                    "GLCM_JointEntropy", "GLCM_DifferenceAverage",
                    "GLCM_DifferenceVariance", "GLCM_DifferenceEntropy",
                    "GLCM_SumAverage", "GLCM_SumVariance", "GLCM_SumEntropy",
                    "GLCM_AngularSecondMoment", "GLCM_Contrast", "GLCM_Dissimilarity",
                    "GLCM_InverseDifference", "GLCM_NormalisedInverseDifference",
                    "GLCM_InverseDifferenceMoment", "GLCM_Correlation",
                    "GLCM_Autocorrelation", "GLCM_ClusterTendency",
                    "GLCM_ClusterShade", "GLCM_ClusterProminence",
                    "GLCM_InformationCorrelation1", "GLCM_InformationCorrelation2");
            case GLRLM:
                return List.of(
                    "GLRLM_ShortRunsEmphasis", "GLRLM_LongRunsEmphasis",
                    "GLRLM_LowGreyLevelRunEmphasis", "GLRLM_HighGreyLevelRunEmphasis",
                    "GLRLM_ShortRunLowGreyLevelEmphasis", "GLRLM_ShortRunHighGreyLevelEmphasis",
                    "GLRLM_LongRunLowGreyLevelEmphasis", "GLRLM_LongRunHighGreyLevelEmphasis",
                    "GLRLM_GreyLevelNonUniformity", "GLRLM_RunLengthNonUniformity",
                    "GLRLM_RunPercentage", "GLRLM_GreyLevelVariance",
                    "GLRLM_RunLengthVariance", "GLRLM_RunEntropy");
            case GLSZM:
                return List.of(
                    "GLSZM_SmallZoneEmphasis", "GLSZM_LargeZoneEmphasis",
                    "GLSZM_LowGreyLevelZoneEmphasis", "GLSZM_HighGreyLevelZoneEmphasis",
                    "GLSZM_GreyLevelNonUniformity", "GLSZM_ZoneSizeNonUniformity",
                    "GLSZM_ZonePercentage", "GLSZM_GreyLevelVariance",
                    "GLSZM_ZoneSizeVariance", "GLSZM_ZoneSizeEntropy",
                    // GLDZM
                    "GLDZM_SmallDistanceEmphasis", "GLDZM_LargeDistanceEmphasis",
                    "GLDZM_ZoneDistanceNonUniformity", "GLDZM_ZoneDistanceEntropy");
            case NGTDM:
                return List.of(
                    "NGTDM_Coarseness", "NGTDM_Contrast",
                    "NGTDM_Busyness", "NGTDM_Complexity", "NGTDM_Strength",
                    "NGLDM_LowDependenceEmphasis", "NGLDM_HighDependenceEmphasis",
                    "NGLDM_LowGreyLevelCountEmphasis", "NGLDM_HighGreyLevelCountEmphasis",
                    "NGLDM_DependenceCountNonUniformity", "NGLDM_DependenceCountEntropy");
            case TEMPORAL:
                return List.of(
                    "relative_wound_area", "closure_rate", "migration_velocity",
                    "acceleration_index", "wound_half_life");
            default:
                return List.of();
        }
    }
}