/*
 * ScratchRadiomics Plugin for Fiji/ImageJ
 * Copyright (C) 2025 DS4H — GPLv3+
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

/**
 *
 * @author Lorenzo Rigoni
 */
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

            final int type = imp.getType();
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
}