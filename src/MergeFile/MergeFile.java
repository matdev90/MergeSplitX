package MergeFile;

import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import org.apache.pdfbox.rendering.PDFRenderer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import javax.imageio.IIOImage;
import javax.swing.Timer;

public class MergeFile extends JFrame {

    private long startTimeMillis;
    private JTextField fileField;
    private JTextField outputFolderField;
    private List<File> selectedFiles = new ArrayList<>();
    private boolean folderMode = false;
    private JProgressBar progressBar;
    private JTextArea logArea;
    private JButton cancelButton;
    private File outputBaseFolder = new File("Output"); // default

    private SwingWorker<Void, String> worker; // untuk proses gabung/split

    private int maxFileSizeKb = 200; // default 200 KB
    private int maxPdfSizeKb = 200; // default 200 KB
    private String gsCompressionLevel = "/ebook"; // default
    private String gsPath = "gs"; // default pakai "gs", kalau user set akan diganti full path

    private List<String> errorLogs = new ArrayList<>();

    private void logError(String context, Exception ex, File file) {
        String msg = "❌ Error pada " + context + " → File: " + file.getName() + " → " + ex.getMessage();
        errorLogs.add(msg);
        log(msg);
    }

    private void showFinalErrorReport() {
        if (!errorLogs.isEmpty()) {
            log("\n======================");
            log("📋 Rangkuman Error:");
            for (String err : errorLogs) {
                log(err);
            }
            log("======================\n");
            errorLogs.clear(); // reset setelah ditampilkan
        }
    }

    private void detectGhostscript() {
        if (!"gs".equals(gsPath)) {
            log("📌 Path Ghostscript sudah diset manual: " + gsPath);
            return;
        }

        if (isGsAvailable("gs")) {
            log("✅ Ghostscript ditemukan di PATH (gs).");
            gsPath = "gs";
            return;
        }

        // Scan otomatis folder C:\Program Files\gs\
        File baseDir = new File("C:\\Program Files\\gs");
        if (baseDir.exists() && baseDir.isDirectory()) {
            File[] versions = baseDir.listFiles(File::isDirectory);
            if (versions != null && versions.length > 0) {
                for (File ver : versions) {
                    File candidate = new File(ver, "bin\\gswin64c.exe");
                    if (candidate.exists()) {
                        gsPath = candidate.getAbsolutePath();
                        log("✅ Ghostscript otomatis terdeteksi di: " + gsPath);
                        return;
                    }
                }
            }
        }

        log("⚠️ Ghostscript tidak ditemukan otomatis. Silakan set manual lewat menu Pengaturan.");
    }

    private boolean isGsAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public MergeFile() {
        setTitle("MergeSplitX V.1");
        setSize(800, 450);
        setResizable(false);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 5)); // jarak antar panel lebih rapat

        // Pastikan folder default Output selalu ada
        if (!outputBaseFolder.exists()) {
            outputBaseFolder.mkdirs();
        }

        // === Menu bar ===
        JMenuBar menuBar = new JMenuBar();

        // Menu Pengaturan
        JMenu menu = new JMenu("Pengaturan");
        JMenuItem resetOutput = new JMenuItem("Reset Folder Output");
        resetOutput.addActionListener(e -> {
            outputBaseFolder = new File("Output");
            if (!outputBaseFolder.exists()) {
                outputBaseFolder.mkdirs();
            }
            outputFolderField.setText(outputBaseFolder.getAbsolutePath());
            log("🔄 Folder output dikembalikan ke default: " + outputBaseFolder.getAbsolutePath());
        });
        JMenuItem setMaxSize = new JMenuItem("Set Maksimal JPG (Kb)");
        setMaxSize.addActionListener(e -> {
            String input = JOptionPane.showInputDialog(
                    this,
                    "Masukkan ukuran maksimal file JPG (Kb):",
                    maxFileSizeKb
            );
            if (input != null && !input.trim().isEmpty()) {
                try {
                    maxFileSizeKb = Integer.parseInt(input.trim());
                    log("⚙️ Batas ukuran JPG diset: " + maxFileSizeKb + " Kb");
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Harap masukkan angka yang valid.");
                }
            }
        });

        JMenuItem setGsPath = new JMenuItem("Set Lokasi Ghostscript");
        setGsPath.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) {
                File chosen = chooser.getSelectedFile();
                gsPath = chosen.getAbsolutePath(); // simpan path Ghostscript
                JOptionPane.showMessageDialog(null,
                        "📌 Lokasi Ghostscript berhasil diset ke:\n" + gsPath);
            }
        });

        // --- Combo Box Level Kompresi ---
        JMenuItem setKompresiLevel = new JMenuItem("Set Level Kompresi PDF");
        setKompresiLevel.addActionListener(e -> {
            String[] options = {"/screen (ukuran kecil, kualitas rendah)",
                "/ebook (sedang, default)",
                "/printer (besar, kualitas tinggi)",
                "/prepress (paling tinggi)"};

            String pilih = (String) JOptionPane.showInputDialog(
                    this,
                    "Pilih level kompresi Ghostscript:",
                    "Pengaturan Kompresi",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[1]
            );

            if (pilih != null) {
                if (pilih.startsWith("/screen")) {
                    gsCompressionLevel = "/screen";
                } else if (pilih.startsWith("/ebook")) {
                    gsCompressionLevel = "/ebook";
                } else if (pilih.startsWith("/printer")) {
                    gsCompressionLevel = "/printer";
                } else if (pilih.startsWith("/prepress")) {
                    gsCompressionLevel = "/prepress";
                }

                JOptionPane.showMessageDialog(this,
                        "Level kompresi diatur ke " + gsCompressionLevel,
                        "Pengaturan", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        menu.add(setGsPath);
        menu.add(setKompresiLevel);
        menu.add(setMaxSize);
        menu.add(resetOutput);
        menuBar.add(menu);

        // Menu Keluar langsung dengan konfirmasi
        JMenu menuExit = new JMenu("Keluar");
        menuExit.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                int confirm = JOptionPane.showConfirmDialog(null,
                        "Apakah Anda yakin ingin keluar?",
                        "Konfirmasi Keluar",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
            }

            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        menuBar.add(menuExit);
        setJMenuBar(menuBar);

        // Panel atas (input file/folder)
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        fileField = new JTextField();
        fileField.setPreferredSize(new Dimension(500, 30));
        fileField.setEditable(false);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton chooseFileButton = new JButton("Cari File");
        JButton chooseFolderButton = new JButton("Cari Folder");
        chooseFileButton.setPreferredSize(new Dimension(130, 30));
        chooseFolderButton.setPreferredSize(new Dimension(130, 30));

        buttonPanel.add(chooseFileButton);
        buttonPanel.add(chooseFolderButton);

        topPanel.add(fileField, BorderLayout.CENTER);
        topPanel.add(buttonPanel, BorderLayout.EAST);

        // Panel tengah (pilih folder output)
        JPanel outputPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        outputFolderField = new JTextField(outputBaseFolder.getAbsolutePath());
        outputFolderField.setPreferredSize(new Dimension(300, 30));
        outputFolderField.setEditable(false);
        JButton chooseOutputButton = new JButton("Ganti Folder");
        chooseOutputButton.setPreferredSize(new Dimension(130, 30));

        outputPanel.add(new JLabel("Path Output:"));
        outputPanel.add(outputFolderField);
        outputPanel.add(chooseOutputButton);

        // Panel tombol bawah
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        JButton mergeButton = new JButton("Gabung PDF");
        mergeButton.setPreferredSize(new Dimension(130, 30));
        JButton splitButton = new JButton("Split PDF");
        splitButton.setPreferredSize(new Dimension(130, 30));
        JButton convertPdfToJpgButton = new JButton("PDF → JPG");
        convertPdfToJpgButton.setPreferredSize(new Dimension(130, 30));
        JButton compressPdfButton = new JButton("Kompres PDF");
        compressPdfButton.setPreferredSize(new Dimension(130, 30));

        cancelButton = new JButton("Batal");
        cancelButton.setEnabled(false);
        cancelButton.setPreferredSize(new Dimension(130, 30));

        bottomPanel.add(mergeButton);
        bottomPanel.add(splitButton);
        bottomPanel.add(convertPdfToJpgButton);
        bottomPanel.add(compressPdfButton);
        bottomPanel.add(cancelButton);

        // Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(750, 25));
        progressBar.setStringPainted(true);

        // Log area
        logArea = new JTextArea(10, 65);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        // Tambah ke frame
        add(topPanel, BorderLayout.NORTH);
        add(outputPanel, BorderLayout.CENTER);

        JPanel southPanel = new JPanel(new BorderLayout(5, 5));
        southPanel.add(bottomPanel, BorderLayout.NORTH);
        southPanel.add(progressBar, BorderLayout.CENTER);
        southPanel.add(scrollPane, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);

        // ===== Action Listeners =====
        // pilih file
        chooseFileButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                folderMode = false;
                selectedFiles.clear();
                File[] files = chooser.getSelectedFiles();
                for (File f : files) {
                    if (f.getName().toLowerCase().endsWith(".pdf")) {
                        selectedFiles.add(f);
                    }
                }
                fileField.setText("File terpilih: " + selectedFiles.size() + " file");
            }
        });

        // pilih folder
        chooseFolderButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                folderMode = true;
                selectedFiles.clear();
                File folder = chooser.getSelectedFile();
                File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
                if (files != null) {
                    selectedFiles.addAll(Arrays.asList(files));
                }
                fileField.setText("Path Asal: " + folder.getAbsolutePath()
                        + " (" + selectedFiles.size() + " file PDF)");
            }
        });

        // pilih folder output
        chooseOutputButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                outputBaseFolder = chooser.getSelectedFile();
                if (!outputBaseFolder.exists()) {
                    outputBaseFolder.mkdirs();
                }
                outputFolderField.setText(outputBaseFolder.getAbsolutePath());
                log("📂 Folder output diubah ke: " + outputBaseFolder.getAbsolutePath());
            }
        });

        // gabungkan
        mergeButton.addActionListener(e -> {
            if (selectedFiles.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Pilih file atau folder terlebih dahulu.");
                return;
            }

            // LANGSUNG PAKAI startWorker tanpa batch
            startWorker(true);
        });

        // pisahkan
        splitButton.addActionListener(e -> {
            if (selectedFiles.size() != 1) {
                JOptionPane.showMessageDialog(this, "Pilih satu file PDF untuk dipisahkan.");
                return;
            }
            startWorker(false);
        });

        // convert PDF ke JPG
        convertPdfToJpgButton.addActionListener(e -> {
            if (selectedFiles.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Pilih file PDF terlebih dahulu.");
                return;
            }
            if (selectedFiles.size() != 1) {
                JOptionPane.showMessageDialog(this, "Pilih satu file PDF untuk dikonversi ke JPG.");
                return;
            }
            startWorkerConvertPdfToJpg(selectedFiles.get(0));
        });

        // Kompres PDF
        compressPdfButton.addActionListener(e -> {
            if (selectedFiles.size() != 1) {
                JOptionPane.showMessageDialog(this, "Pilih satu file PDF untuk dikompres.");
                return;
            }
            startWorkerCompressPdf(selectedFiles.get(0));
        });

        // batal
        cancelButton.addActionListener(e -> {
            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
                log("❌ Proses dibatalkan oleh pengguna.");
            }
        });
        detectGhostscript();
    }

    private void startWorker(boolean isMerge) {
        progressBar.setValue(0);
        logArea.setText("");
        cancelButton.setEnabled(true);
        startTimeMillis = System.currentTimeMillis(); // gunakan field kelas, bukan lokal
        Timer timer = new javax.swing.Timer(1000, e -> {
            long elapsed = System.currentTimeMillis() - startTimeMillis;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            progressBar.setString("⏱ Waktu proses: " + minutes + " menit " + seconds + " detik");
        });
        timer.start();

        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

                    if (isMerge) {
                        // === MERGE ===
                        File parentFolder = selectedFiles.get(0).getParentFile();
                        String folderName = parentFolder.getName();

                        File outputDir = new File(outputBaseFolder,
                                "Hasil_Gabung/" + today + "/" + folderName);
                        if (!outputDir.exists()) {
                            outputDir.mkdirs();
                        }

                        if (folderMode) {
                            Map<String, List<File>> grouped = new HashMap<>();
                            for (File pdf : selectedFiles) {
                                String name = pdf.getName().replace(".pdf", "");
                                String sepNumber = name.contains("_") ? name.split("_")[0] : name;
                                grouped.computeIfAbsent(sepNumber, k -> new ArrayList<>()).add(pdf);
                            }

                            int total = grouped.size();
                            int count = 0;

                            for (Map.Entry<String, List<File>> entry : grouped.entrySet()) {
                                if (isCancelled()) {
                                    break;
                                }

                                String sepNumber = entry.getKey();
                                List<File> files = entry.getValue();
                                files.sort(Comparator.comparing(File::getName));

                                PDFMergerUtility merger = new PDFMergerUtility();
                                for (File f : files) {
                                    if (isCancelled()) {
                                        break;
                                    }
                                    try {
                                        merger.addSource(f);
                                        publish("Menggabungkan: " + f.getName());
                                    } catch (Exception ex) {
                                        logError("Gabung PDF", ex, f);
                                        continue; // lanjut ke file berikutnya
                                    }
                                    try {
                                        Thread.sleep(100); // simulasi proses per file
                                    } catch (InterruptedException ex) {
                                        if (isCancelled()) {
                                            break;
                                        }
                                    }
                                }

                                File outputFile = new File(outputDir, sepNumber + ".pdf");
                                merger.setDestinationFileName(outputFile.getAbsolutePath());
                                merger.mergeDocuments(null);

                                count++;
                                int progress = (int) ((count * 100.0f) / total);
                                setProgress(progress);
                                publish("✔️ Selesai: " + outputFile.getName());
                            }
                        } else {
                            if (selectedFiles.size() < 2) {
                                publish("⚠️ Minimal pilih 2 file untuk digabung.");
                                return null;
                            }

                            String outputName = selectedFiles.get(0).getName().replace(".pdf", "");
                            File outputFile = new File(outputDir, outputName + ".pdf");

                            PDFMergerUtility merger = new PDFMergerUtility();
                            int total = selectedFiles.size();
                            int count = 0;

                            for (File f : selectedFiles) {
                                if (isCancelled()) {
                                    break;
                                }
                                merger.addSource(f);
                                publish("Menggabungkan: " + f.getName());
                                count++;
                                int progress = (int) ((count * 100.0f) / total);
                                setProgress(progress);
                            }
                            merger.setDestinationFileName(outputFile.getAbsolutePath());
                            merger.mergeDocuments(null);
                            publish("✔️ Selesai: " + outputFile.getName());
                        }

                    } else {
                        // === SPLIT ===
                        File fileToSplit = selectedFiles.get(0);

                        File parentFolder = fileToSplit.getParentFile();
                        String folderName = parentFolder.getName();

                        File outputDir = new File(outputBaseFolder,
                                "Hasil_Split/" + today + "/" + folderName);
                        if (!outputDir.exists()) {
                            outputDir.mkdirs();
                        }

                        PDDocument document = PDDocument.load(fileToSplit);
                        int pageCount = document.getNumberOfPages();

                        for (int i = 0; i < pageCount; i++) {
                            if (isCancelled()) {
                                break;
                            }

                            try {
                                Thread.sleep(100); // simulasi proses per page
                            } catch (InterruptedException ex) {
                                if (isCancelled()) {
                                    break;
                                }
                            }

                            try {
                                PDDocument newDoc = new PDDocument();
                                newDoc.addPage(document.getPage(i));

                                File outFile = new File(outputDir,
                                        fileToSplit.getName().replace(".pdf", "") + "_" + (i + 1) + ".pdf");
                                newDoc.save(outFile);
                                newDoc.close();

                                int progress = (int) (((i + 1) * 100.0f) / pageCount);
                                setProgress(progress);
                                publish("Membuat: " + outFile.getName());
                            } catch (Exception ex) {
                                logError("Split PDF", ex, fileToSplit);
                                continue; // lanjut ke halaman berikutnya
                            }
                        }

                        document.close();
                    }

                } catch (IOException ex) {
                    publish("❌ Error: " + ex.getMessage());
                }
                // hitung total waktu
                long endTime = System.currentTimeMillis();
                long durationSec = (endTime - startTimeMillis) / 1000;
                long minutes = durationSec / 60;
                long seconds = durationSec % 60;
                publish("⏱ Total waktu: " + minutes + " menit " + seconds + " detik");

                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    log(msg);
                }
            }

            @Override
            protected void done() {
                cancelButton.setEnabled(false);
                if (timer != null) {
                    timer.stop();  // 🔴 stop timer
                }
                if (isCancelled()) {
                    log("❌ Proses dibatalkan.");
                } else {
                    log("✅ Proses selesai.");
                }
                showFinalErrorReport(); // 🔽 tampilkan rangkuman error kalau ada
                selectedFiles.clear();
                fileField.setText("");
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });

        worker.execute();
    }

    private void startWorkerCompressPdf(File pdfFile) {
        progressBar.setValue(0);
        logArea.setText("");
        cancelButton.setEnabled(true);

        startTimeMillis = System.currentTimeMillis();
        Timer timer = new javax.swing.Timer(1000, e -> {
            long elapsed = System.currentTimeMillis() - startTimeMillis;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            progressBar.setString("⏱ Waktu proses: " + minutes + " menit " + seconds + " detik");
        });
        timer.start();

        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                    File outputDir = new File(outputBaseFolder, "Hasil_Kompres/" + today + "/");
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }

                    File outFile = new File(outputDir, pdfFile.getName().replace(".pdf", "_compressed.pdf"));

                    // ===== Jalankan Ghostscript =====
                    List<String> command = new ArrayList<>();
                    command.add(gsPath); // gunakan path yang dipilih user, default "gs"
                    command.add("-sDEVICE=pdfwrite");
                    command.add("-dCompatibilityLevel=1.4");
                    command.add("-dPDFSETTINGS=" + gsCompressionLevel); // bisa diganti /screen, /ebook, /printer
                    command.add("-dNOPAUSE");
                    command.add("-dQUIET");
                    command.add("-dBATCH");
                    command.add("-sOutputFile=" + outFile.getAbsolutePath());
                    command.add(pdfFile.getAbsolutePath());

                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    // baca log GS
                    try (Scanner sc = new Scanner(process.getInputStream())) {
                        while (sc.hasNextLine()) {
                            if (isCancelled()) {
                                process.destroyForcibly();
                                break;
                            }
                            try {
                                Thread.sleep(100); // simulasi proses per page
                            } catch (InterruptedException ex) {
                                if (isCancelled()) {
                                    break;
                                }
                            }
                            publish("GS: " + sc.nextLine());
                        }
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        long fileSizeKb = outFile.length() / 1024;
                        if (fileSizeKb > maxPdfSizeKb) {
                            publish("⚠️ Ukuran hasil: " + fileSizeKb + " KB (target " + maxPdfSizeKb + " KB)");
                        } else {
                            publish("✔️ File terkompresi: " + outFile.getName() + " (" + fileSizeKb + " KB)");
                        }
                    } else {
                        logError("Kompres PDF", new RuntimeException("Exit code: " + exitCode), pdfFile);
                    }

                } catch (Exception ex) {
                    publish("❌ Error kompres: " + ex.getMessage());
                    ex.printStackTrace();
                }

                long endTime = System.currentTimeMillis();
                long durationSec = (endTime - startTimeMillis) / 1000;
                long minutes = durationSec / 60;
                long seconds = durationSec % 60;
                publish("⏱ Total waktu: " + minutes + " menit " + seconds + " detik");
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    log(msg);
                }
            }

            @Override
            protected void done() {
                if (timer != null) {
                    timer.stop();
                }
                cancelButton.setEnabled(false);
                if (isCancelled()) {
                    log("❌ Kompres dibatalkan.");
                } else {
                    log("✅ Kompres PDF selesai.");
                }
                showFinalErrorReport(); // 🔽 tampilkan rangkuman error kalau ada
                selectedFiles.clear();
                fileField.setText("");
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });

        worker.execute();
    }

    private void startWorkerConvertPdfToJpg(File pdfFile) {
        progressBar.setValue(0);
        logArea.setText("");
        cancelButton.setEnabled(true);

        startTimeMillis = System.currentTimeMillis();
        Timer timer = new javax.swing.Timer(1000, e -> {
            long elapsed = System.currentTimeMillis() - startTimeMillis;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            progressBar.setString("⏱ Waktu proses: " + minutes + " menit " + seconds + " detik");
        });
        timer.start();

        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                    File parentFolder = pdfFile.getParentFile();
                    String folderName = parentFolder.getName();

                    File outputDir = new File(outputBaseFolder,
                            "Hasil_PDF2JPG/" + today + "/");
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }

                    PDDocument document = PDDocument.load(pdfFile);
                    PDFRenderer pdfRenderer = new PDFRenderer(document);

                    int pageCount = document.getNumberOfPages();
                    for (int i = 0; i < pageCount; i++) {
                        if (isCancelled()) {
                            break;
                        }
                        try {
                            Thread.sleep(100); // simulasi proses per page
                        } catch (InterruptedException ex) {
                            if (isCancelled()) {
                                break;
                            }
                        }

                        try {
                            BufferedImage bim = pdfRenderer.renderImageWithDPI(i, 300); // resolusi 300 DPI
                            String baseName = pdfFile.getName().replaceFirst("[.][^.]+$", "");
                            String outputFileName;
                            if (pageCount == 1) {
                                outputFileName = baseName + ".jpg";
                            } else {
                                outputFileName = baseName + (i + 1) + ".jpg";
                            }
                            File outFile = new File(outputDir, outputFileName);

                            float quality = 1.0f;
                            while (true) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                ImageWriter jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next();
                                ImageWriteParam jpgWriteParam = jpgWriter.getDefaultWriteParam();
                                jpgWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                                jpgWriteParam.setCompressionQuality(quality);

                                try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                                    jpgWriter.setOutput(ios);
                                    jpgWriter.write(null, new IIOImage(bim, null, null), jpgWriteParam);
                                }
                                jpgWriter.dispose();

                                byte[] jpgBytes = baos.toByteArray();
                                long fileSizeKb = jpgBytes.length / 1024;

                                if (fileSizeKb <= maxFileSizeKb || quality <= 0.1f) {
                                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                        fos.write(jpgBytes);
                                    }
                                    break;
                                } else {
                                    quality -= 0.05f;
                                }
                            }

                            int progress = (int) (((i + 1) * 100.0f) / pageCount);
                            setProgress(progress);
                            publish("Membuat JPG: " + outFile.getName());

                        } catch (Exception ex) {
                            logError("Convert PDF → JPG", ex, pdfFile);
                            continue; // lanjut ke halaman berikutnya walau error
                        }
                    }

                    document.close();

                } catch (IOException ex) {
                    publish("❌ Error convert: " + ex.getMessage());
                }
                // hitung total waktu
                long endTime = System.currentTimeMillis();
                long durationSec = (endTime - startTimeMillis) / 1000;
                long minutes = durationSec / 60;
                long seconds = durationSec % 60;
                publish("⏱ Total waktu: " + minutes + " menit " + seconds + " detik");
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    log(msg);
                }
            }

            @Override
            protected void done() {
                if (timer != null) {
                    timer.stop();
                }
                cancelButton.setEnabled(false);
                if (isCancelled()) {
                    log("❌ Konversi dibatalkan.");
                } else {
                    log("✅ Konversi PDF ke JPG selesai.");
                }
                showFinalErrorReport();// 🔽 tampilkan rangkuman error kalau ada
                selectedFiles.clear();// 🔄 Kosongkan form setelah proses selesai
                fileField.setText("");
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });

        worker.execute();
    }

    private void log(String message) {
        if (logArea != null) {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } else {
            System.out.println(message); // fallback ke console kalau UI belum siap
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MergeFile().setVisible(true));
    }
}
