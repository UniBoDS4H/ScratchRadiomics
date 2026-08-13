/*
 * Wound Healing Scratch Assay Plugin for Fiji/ImageJ
 * Copyright (C) 2025 Your Name — GPLv3+
 */
package com.ds4h.scratchradiomics;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import com.ds4h.scratchradiomics.gui.MainDialog;

import javax.swing.*;

@Plugin(type = Command.class,
        menuPath = "Plugins > ScratchRadiomics",
        description = "Wound Healing Scratch Assay analysis with SAM segmentation " +
                "and IBSI-compliant radiomic features.")
public class ScratchRadiomicsPlugin implements Command {

    @Parameter
    LogService log;

    @Override
    public void run() {
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null && WindowManager.getImageCount() > 0) {
                imp = WindowManager.getImage(WindowManager.getIDList()[0]);
            }

            if (imp == null) {
                IJ.error("ScratchRadiomics",
                        "No image open.");
                return;
            }

            int type = imp.getType();
            if (type != ImagePlus.GRAY8 && type != ImagePlus.GRAY16 && type != ImagePlus.COLOR_RGB) {
                ij.IJ.error("ScratchRadiomics",
                        "Unsupported image type: " + imp.getTitle() + "\n" +
                                "Supported: 8-bit gray, 16-bit gray, RGB color.");
                return;
            }
            final ImagePlus finalImp = imp;
            if (SwingUtilities.isEventDispatchThread()) {
                new MainDialog(finalImp, log).setVisible(true);
            } else {
                SwingUtilities.invokeLater(
                        () -> new MainDialog(finalImp, log).setVisible(true));
            }
        } catch (Exception e) {
            IJ.log("[ScratchRadiomics] ERROR: " + e.getClass().getName() +
                    " - " + e.getMessage());
            IJ.handleException(e);
        }
    }

    /** IDE launcher — pure ImageJ1, no SciJava context needed. */
    public static void main(String[] args) {
        new ij.ImageJ();
        final String imagePath = System.getProperty("user.home") + "/Desktop/test_assay_A549_Serum_0.tif";
        final ImagePlus imp = IJ.openImage(imagePath);

        if (imp == null) {
            System.err.println("Error: image not found at path " + imagePath);
            return;
        }

        if (imp.getNFrames() == 1 && imp.getNSlices() > 1) {
            final int totalSlices = imp.getNSlices();
            imp.setDimensions(1, 1, totalSlices);
            imp.setOpenAsHyperStack(true);
        }

        imp.show();

        final ScratchRadiomicsPlugin plugin = new ScratchRadiomicsPlugin();
        plugin.log = new org.scijava.log.StderrLogService();
        plugin.run();
    }
}