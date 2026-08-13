package com.ds4h.scratchradiomics.gui;

import com.ds4h.scratchradiomics.radiomics.PythonEnvironmentManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.concurrent.CountDownLatch;

/**
 * Dialog modale con progress bar mostrato durante il setup iniziale.
 * L'utente vede cosa sta succedendo ma non deve interagire.
 * Si chiude automaticamente al termine o in caso di errore.
 */
public class SetupDialog extends JDialog {
    private final PythonEnvironmentManager manager;
    private final CountDownLatch latch;

    private final JLabel       lblStep;
    private final JProgressBar bar;
    private final JTextArea    logArea;
    private boolean            setupFailed = false;
    private String             errorMessage = "";

    public SetupDialog(PythonEnvironmentManager manager, CountDownLatch latch) {
        super((Frame) null, "ScratchRadiomics - Python Environment Setup", true);
        this.manager = manager;
        this.latch   = latch;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel title = new JLabel(
                "<html><b>Initial setup - first launch only</b></html>");
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 13f));

        JLabel sub = new JLabel(
                "<html>The plugin is configuring an isolated Python environment " +
                        "with PyRadiomics.<br>" +
                        "This process takes approximately 2-5 minutes and " +
                        "<b>requires no action from you</b>.<br>" +
                        "The environment is created only once.</html>");
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 12f));
        sub.setBorder(new EmptyBorder(4, 0, 12, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(sub,   BorderLayout.CENTER);

        lblStep = new JLabel("Starting…");
        lblStep.setFont(lblStep.getFont().deriveFont(Font.PLAIN, 12f));
        bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setPreferredSize(new Dimension(460, 22));

        JPanel progress = new JPanel(new BorderLayout(4, 4));
        progress.add(lblStep, BorderLayout.NORTH);
        progress.add(bar,     BorderLayout.CENTER);

        logArea = new JTextArea(6, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        logArea.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Details"));

        root.add(header,   BorderLayout.NORTH);
        root.add(progress, BorderLayout.CENTER);
        root.add(scroll,   BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setMinimumSize(new Dimension(500, 320));
        setLocationRelativeTo(null);
        setResizable(false);

        startSetup();
    }

    public void startSetup() {
        new SwingWorker<String, String[]>() {
            @Override
            protected String doInBackground() {
                try {
                    manager.runSetup((message, percent) -> {
                        publish(new String[]{message, String.valueOf(percent)});
                    });
                    return manager.readAppInfoDirect();
                } catch (Exception e) {
                    setupFailed  = true;
                    errorMessage = e.getMessage();
                    publish(new String[]{"ERROR: " + e.getMessage(), "-1"});
                    return null;
                }
            }

            @Override
            protected void process(java.util.List<String[]> chunks) {
                for (String[] chunk : chunks) {
                    String msg = chunk[0];
                    int pct    = Integer.parseInt(chunk[1]);

                    logArea.append(msg + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());

                    if (pct >= 0) {
                        bar.setValue(pct);
                        bar.setString(pct + "%");
                        lblStep.setText(msg);
                    } else {
                        bar.setString("Error");
                        lblStep.setForeground(Color.RED);
                        lblStep.setText("Configuration failed.");
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    if (result != null && !result.isBlank()) {
                        manager.setPythonPathResult(result);
                    }
                } catch (Exception ignored) {}

                latch.countDown();

                if (!setupFailed) {
                    bar.setValue(100);
                    bar.setString("100%");
                    lblStep.setText("Python environment ready!");
                    Timer t = new Timer(1500, e -> dispose());
                    t.setRepeats(false);
                    t.start();
                } else {
                    JButton btnClose = new JButton("Close");
                    btnClose.addActionListener(e -> dispose());
                    JPanel south = new JPanel();
                    south.add(btnClose);
                    getContentPane().add(south, BorderLayout.SOUTH);
                    pack();
                }
            }
        }.execute();
    }

    public boolean failed()      { return setupFailed; }
    public String  getError()    { return errorMessage; }
}
