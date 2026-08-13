package com.ds4h.scratchradiomics.gui;

import ij.IJ;
import ij.ImagePlus;
import org.scijava.log.LogService;
import com.ds4h.scratchradiomics.preprocessing.Preprocessor;
import com.ds4h.scratchradiomics.segmentation.MaskPropagator;
import com.ds4h.scratchradiomics.segmentation.RoiSegmenter;
import com.ds4h.scratchradiomics.segmentation.SamSegmenter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainDialog extends JFrame {

    private final ImagePlus  imp;
    private final LogService log;

    private static final int MODE_MANUAL = 0;
    private static final int MODE_SAM    = 1;
    private int segMode = MODE_MANUAL;

    // Pre-processing
    private JCheckBox chkNormalize;
    private JCheckBox chkBgSubtract;
    private JSpinner  spnBgRadius;

    // Segmentation
    private JRadioButton rbManual;
    private JRadioButton rbSam;
    private JPanel       panelSamOptions;
    private JSpinner     spnSamPoints;
    private JCheckBox    chkPropagate;

    // Bottom
    private JProgressBar progressBar;
    private JButton      btnSegment;
    private JButton      btnMeasure;
    private JPanel       manualHint;

    // ─────────────────────────────────────────────────────────────────────────

    public MainDialog(ImagePlus imp, LogService log) {
        super("ScratchRadiomics - " + imp.getTitle());
        this.imp = imp;
        this.log = log;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });
        buildUI();
        pack();
        setMinimumSize(new Dimension(420, 420));
        setLocationRelativeTo(null);
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        root.add(buildInfoPanel(),          BorderLayout.NORTH);
        root.add(buildMainPanel(),          BorderLayout.CENTER);
        root.add(buildActionBar(),          BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildInfoPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Image"));
        String html = String.format(
                "<html><b>%s</b> &nbsp; %d×%d px &nbsp; %s &nbsp; %d frames</html>",
                imp.getTitle(), imp.getWidth(), imp.getHeight(),
                bitDepthLabel(imp.getBitDepth()), imp.getNFrames());
        p.add(new JLabel(html), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildMainPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();

        // ── Pre-processing ────────────────────────────────────────────────────
        c.gridy = 0; p.add(sectionHeader("Pre-processing"), c);

        // Auto-detect fluorescence: if the image is RGB with a dominant
        // green/blue channel, suggest disabling pre-processing
        boolean looksLikeFluorescence = imp.getType() == ImagePlus.COLOR_RGB;

        c.gridy++; chkNormalize = new JCheckBox("Normalize intensity (0-1)", !looksLikeFluorescence);
        p.add(chkNormalize, c);

        c.gridy++; chkBgSubtract = new JCheckBox("Subtract background (rolling ball)", false);
        p.add(chkBgSubtract, c);

        if (looksLikeFluorescence) {
            c.gridy++;
            JLabel warnLbl = new JLabel(
                    "<html><font color='#B71C1C'>⚠ RGB/fluorescence image detected.<br>" +
                            "&nbsp;&nbsp;Pre-processing disabled by default.<br>" +
                            "&nbsp;&nbsp;Convert to 8-bit gray before enabling.</font></html>");
            warnLbl.setFont(warnLbl.getFont().deriveFont(Font.PLAIN, 11f));
            warnLbl.setBorder(new EmptyBorder(0, 4, 0, 0));
            p.add(warnLbl, c);
        }

        c.gridy++;
        JPanel bgRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bgRow.add(new JLabel("  Radius (px):"));
        spnBgRadius = new JSpinner(new SpinnerNumberModel(50, 5, 500, 5));
        spnBgRadius.setEnabled(false);
        bgRow.add(spnBgRadius);
        p.add(bgRow, c);
        chkBgSubtract.addActionListener(e -> spnBgRadius.setEnabled(chkBgSubtract.isSelected()));

        // ── Segmentation ──────────────────────────────────────────────────────
        c.gridy++; c.insets = new Insets(12, 0, 4, 0);
        p.add(sectionHeader("Segmentation"), c);
        c.insets = new Insets(2, 0, 2, 0);

        ButtonGroup bg = new ButtonGroup();
        rbManual = new JRadioButton("Manual ROI  (freehand / polygon)", true);
        rbSam    = new JRadioButton("Automatic - micro-SAM  (SAM2)", false);
        bg.add(rbManual); bg.add(rbSam);
        c.gridy++; p.add(rbManual, c);
        c.gridy++; p.add(rbSam, c);

        // SAM sub-options
        panelSamOptions = new JPanel(new GridBagLayout());
        panelSamOptions.setBorder(BorderFactory.createTitledBorder("micro-SAM options"));
        GridBagConstraints cs = gbc(); cs.insets = new Insets(2, 8, 2, 8);
        panelSamOptions.add(new JLabel("Prompt points per frame:"), cs);
        cs.gridy++;
        spnSamPoints = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
        spnSamPoints.setEnabled(false);
        panelSamOptions.add(spnSamPoints, cs);
        c.gridy++; c.insets = new Insets(4, 16, 2, 0);
        p.add(panelSamOptions, c);

        rbSam.addActionListener(e -> {
            segMode = MODE_SAM;
            setPanelEnabled(panelSamOptions, true);
            btnSegment.setVisible(true);
            manualHint.setVisible(false);
            pack();
        });
        rbManual.addActionListener(e -> {
            segMode = MODE_MANUAL;
            setPanelEnabled(panelSamOptions, false);
            btnSegment.setVisible(false);
            manualHint.setVisible(true);
            pack();
        });

        // Propagation
        c.gridy++; c.insets = new Insets(8, 0, 2, 0);
        chkPropagate = new JCheckBox(
                "Propagate mask across all frames (time-lapse)", imp.getNFrames() > 1);
        chkPropagate.setEnabled(imp.getNFrames() > 1);
        p.add(chkPropagate, c);

        // Filler
        c.gridy++; c.weighty = 1; p.add(new JPanel(), c);
        return p;
    }

    private JPanel buildActionBar() {
        JPanel p = new JPanel(new BorderLayout(6, 4));

        // Slot 1a — SAM segment button (visible only in SAM mode)
        btnSegment = new JButton("Run SAM");
        btnSegment.setToolTipText("Run pre-processing + micro-SAM segmentation");
        btnSegment.setBackground(new Color(0xE3F2FD));
        btnSegment.addActionListener(e -> runSegmentation());
        btnSegment.setVisible(false);   // hidden by default (manual mode is default)

        // Slot 1b — Manual mode hint panel (visible by default)
        manualHint = new JPanel(new BorderLayout(4, 0));
        manualHint.setBackground(new Color(0xF1F8E9));
        manualHint.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xAED581), 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        JLabel hintLbl = new JLabel(
                "<html><b>Draw ROI manually:</b> select Freehand tool, " +
                        "draw the scratch, press <b>T</b></html>");
        hintLbl.setFont(hintLbl.getFont().deriveFont(Font.PLAIN, 11f));
        manualHint.add(hintLbl,             BorderLayout.CENTER);

        // Step 1 container — swaps between SAM button and manual hint
        JPanel step1 = new JPanel(new BorderLayout());
        step1.add(btnSegment,  BorderLayout.CENTER);
        step1.add(manualHint,  BorderLayout.SOUTH);

        // Steps 2 and 3
        btnMeasure    = new JButton("Measure");

        btnMeasure.setToolTipText("Open IBSI Measure dialog");

        btnMeasure.setBackground(new Color(0xFFF8E1));

        btnMeasure.addActionListener(   e -> openMeasure());

        JPanel steps23 = new JPanel(new GridLayout(1, 2, 6, 0));
        steps23.add(btnMeasure);

        JPanel buttons = new JPanel(new BorderLayout(0, 4));
        buttons.add(step1,   BorderLayout.NORTH);
        buttons.add(steps23, BorderLayout.CENTER);

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Draw ROI on image → press T → then Edit ROIs or Measure");

        p.add(buttons,     BorderLayout.CENTER);
        p.add(progressBar, BorderLayout.SOUTH);
        return p;
    }

    // ── Segmentation pipeline ─────────────────────────────────────────────────

    private void runSegmentation() {
        btnSegment.setEnabled(false);
        progressBar.setString("Segmenting…");
        progressBar.setIndeterminate(true);

        new SwingWorker<ij.gui.Roi[], String>() {
            @Override
            protected ij.gui.Roi[] doInBackground() throws Exception {

                // Pre-processing — skip if both options are unchecked
                if (chkNormalize.isSelected() || chkBgSubtract.isSelected()) {
                    publish("Pre-processing…");
                    Preprocessor pre = new Preprocessor(imp);
                    if (chkNormalize.isSelected())  pre.normalize();
                    if (chkBgSubtract.isSelected()) pre.subtractBackground(
                            (int) spnBgRadius.getValue());
                }

                // Segmentation
                publish(segMode == MODE_SAM ? "Running micro-SAM…" : "Reading ROIs…");
                ij.gui.Roi[] rois;
                if (segMode == MODE_SAM) {
                    SamSegmenter sam = new SamSegmenter(imp, log);
                    rois = sam.segment((int) spnSamPoints.getValue());
                } else {
                    RoiSegmenter roiSeg = new RoiSegmenter(imp, log);
                    rois = roiSeg.getRoisFromManager();
                }

                if (rois == null || rois.length == 0)
                    throw new IllegalStateException(
                            "No ROIs found.\n" +
                                    "For manual mode: draw a ROI and press T to add it to the ROI Manager.\n" +
                                    "For SAM mode: click on the scratch when prompted.");

                // Propagation
                if (chkPropagate.isSelected() && imp.getNFrames() > 1) {
                    publish("Propagating across frames…");
                    MaskPropagator prop = new MaskPropagator(imp, log);
                    rois = prop.propagate(rois);
                }
                return rois;
            }

            @Override
            protected void process(java.util.List<String> msgs) {
                progressBar.setString(msgs.get(msgs.size() - 1));
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                try {
                    ij.gui.Roi[] rois = get();
                    progressBar.setValue(100);
                    progressBar.setString("Segmentation done - " + rois.length + " ROI(s)");
                    log.info("[MainDialog] Segmentation complete: " + rois.length + " ROIs");
                } catch (Exception ex) {
                    progressBar.setString("Error");
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    IJ.error("Segmentation failed", msg);
                    log.error("[MainDialog] " + msg);
                } finally {
                    btnSegment.setEnabled(true);
                }
            }
        }.execute();
    }

    private void openMeasure() {
        ij.gui.Roi[] rois = ij.plugin.frame.RoiManager.getRoiManager().getRoisAsArray();
        if (rois == null || rois.length == 0) {
            IJ.showMessage("Measure",
                    "No ROIs found in the ROI Manager.\n" +
                            "Segment the image first (Step 1), then edit ROIs (Step 2).");
            return;
        }
        IbsiMeasureDialog dlg = new IbsiMeasureDialog(imp, rois);
        dlg.setVisible(true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill   = GridBagConstraints.HORIZONTAL;
        c.weightx = 1; c.weighty = 0;
        c.insets  = new Insets(2, 0, 2, 0);
        return c;
    }

    private static JLabel sectionHeader(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 13f));
        lbl.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));
        return lbl;
    }

    private static void setPanelEnabled(JPanel panel, boolean enabled) {
        panel.setEnabled(enabled);
        for (Component comp : panel.getComponents()) {
            comp.setEnabled(enabled);
            if (comp instanceof JPanel) setPanelEnabled((JPanel) comp, enabled);
        }
    }

    private static String bitDepthLabel(int d) {
        switch (d) {
            case 8:
                return "8-bit";
            case 16:
                return "16-bit";
            case 24:
                return "RGB";
            case 32:
                return "32-bit float";
            default:
                return d + "-bit";
        }
    }
}