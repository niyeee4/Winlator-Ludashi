package com.winlator.cmod.aecs6;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class AfterEffectsCS6Manager {
    private static final String TAG = "AfterEffectsCS6Manager";
    public static final String SHORTCUT_NAME = "Adobe After Effects CS6";
    public static final String APP_DIR_NAME = "Adobe After Effects CS6 Portable";

    public interface ProgressListener {
        void onProgress(int progress);
    }

    public static File getAppDir(Container container) {
        if (container == null) return null;
        File cDrive = new File(container.getRootDir(), ".wine/drive_c");
        return new File(cDrive, APP_DIR_NAME);
    }

    public static File getLauncherExe(Container container) {
        File appDir = getAppDir(container);
        if (appDir == null) return null;

        File directExe = new File(appDir, "AdobeAfterEffectsPortable.exe");
        if (directExe.isFile()) return directExe;

        File nestedExe = new File(appDir, "Adobe After Effects CS6 Portable [Black General]/AdobeAfterEffectsPortable.exe");
        if (nestedExe.isFile()) return nestedExe;

        File directSupportExe = new File(appDir, "App/Ae/Support Files/AfterFX.exe");
        if (directSupportExe.isFile()) return directSupportExe;

        File nestedSupportExe = new File(appDir, "Adobe After Effects CS6 Portable [Black General]/App/Ae/Support Files/AfterFX.exe");
        if (nestedSupportExe.isFile()) return nestedSupportExe;

        return directExe;
    }

    public static boolean isInstalled(Container container) {
        if (container == null) return false;
        File launcher = getLauncherExe(container);
        return launcher != null && launcher.isFile();
    }

    public static boolean install(Context context, Container container, ProgressListener listener) {
        if (container == null) return false;
        File appDir = getAppDir(container);
        if (!appDir.exists()) appDir.mkdirs();

        boolean extracted = false;

        // 1. Try aecs6.tar.zst in assets
        long assetSize = FileUtils.getSize(context, "aecs6.tar.zst");
        if (assetSize > 0) {
            Log.d(TAG, "Extracting aecs6.tar.zst from assets...");
            extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "aecs6.tar.zst", appDir, (file, sz) -> {
                return file;
            });
        }

        // 2. Try aecs6.zip in assets
        if (!extracted && FileUtils.getSize(context, "aecs6.zip") > 0) {
            Log.d(TAG, "Extracting aecs6.zip from assets...");
            try (InputStream is = context.getAssets().open("aecs6.zip")) {
                extracted = extractZipStream(is, appDir, listener);
            } catch (Exception e) {
                Log.e(TAG, "Failed extracting aecs6.zip from assets", e);
            }
        }

        // 3. Try external storage candidates
        if (!extracted) {
            File[] searchLocations = new File[]{
                    new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Adobe After Effects CS6 Portable [Black General].zip"),
                    new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aecs6.zip"),
                    new File(Environment.getExternalStorageDirectory(), "Winlator/aecs6.tar.zst"),
                    new File(Environment.getExternalStorageDirectory(), "Winlator/aecs6.zip"),
                    new File("/workspaces/Winlator-Ludashi/aecs6/Adobe After Effects CS6 Portable [Black General].zip")
            };

            for (File cand : searchLocations) {
                if (cand.isFile() && cand.length() > 0) {
                    Log.d(TAG, "Extracting from external candidate: " + cand.getAbsolutePath());
                    if (cand.getName().endsWith(".tar.zst") || cand.getName().endsWith(".tzst")) {
                        extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, cand, appDir);
                    } else if (cand.getName().endsWith(".zip")) {
                        try (InputStream is = new BufferedInputStream(new FileInputStream(cand))) {
                            extracted = extractZipStream(is, appDir, listener);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed extracting external zip: " + cand.getAbsolutePath(), e);
                        }
                    }
                    if (extracted) break;
                }
            }
        }

        // If files unpacked into nested subfolder, flatten if appropriate
        File nestedFolder = new File(appDir, "Adobe After Effects CS6 Portable [Black General]");
        if (nestedFolder.isDirectory()) {
            File nestedExe = new File(nestedFolder, "AdobeAfterEffectsPortable.exe");
            if (nestedExe.isFile()) {
                File[] nestedFiles = nestedFolder.listFiles();
                if (nestedFiles != null) {
                    for (File f : nestedFiles) {
                        File dest = new File(appDir, f.getName());
                        f.renameTo(dest);
                    }
                }
            }
        }

        createShortcut(context, container);
        return isInstalled(container);
    }

    private static boolean extractZipStream(InputStream is, File targetDir, ProgressListener listener) {
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is, 64 * 1024))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Zip extraction error", e);
            return false;
        }
    }

    public static void createShortcut(Context context, Container container) {
        try {
            File desktopDir = container.getDesktopDir();
            if (!desktopDir.exists()) desktopDir.mkdirs();

            String winePrefix = container.getRootDir().getPath() + "/.wine";
            File launcherExe = getLauncherExe(container);
            String unixPath = launcherExe.getAbsolutePath();

            File desktopFile = new File(desktopDir, SHORTCUT_NAME + ".desktop");
            try (PrintWriter writer = new PrintWriter(new FileWriter(desktopFile))) {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + SHORTCUT_NAME);
                writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine \"" + unixPath + "\"");
                writer.println("Type=Application");
                writer.println("Icon=" + SHORTCUT_NAME);
                writer.println("StartupWMClass=AfterFX.exe");
                writer.println("container_id:" + container.id);
            }

            installIcons(context, container);
            Log.d(TAG, "Shortcut created successfully at: " + desktopFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create desktop shortcut", e);
        }
    }

    public static void installIcons(Context context, Container container) {
        try {
            Bitmap iconBitmap = null;
            try (InputStream is = context.getAssets().open("aecs6_icon.png")) {
                iconBitmap = BitmapFactory.decodeStream(is);
            } catch (Exception ignored) {}

            if (iconBitmap == null) return;

            int[] sizes = new int[]{64, 48, 32, 16};
            for (int sz : sizes) {
                File dir = container.getIconsDir(sz);
                if (!dir.exists()) dir.mkdirs();
                File iconDest = new File(dir, SHORTCUT_NAME + ".png");
                try (FileOutputStream fos = new FileOutputStream(iconDest)) {
                    Bitmap scaled = Bitmap.createScaledBitmap(iconBitmap, sz, sz, true);
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            }

            File externalStorage = Environment.getExternalStorageDirectory();
            File customIconsDir = new File(externalStorage, "Winlator/icons");
            if (!customIconsDir.exists()) customIconsDir.mkdirs();
            File userIcon = new File(customIconsDir, SHORTCUT_NAME + ".png");
            try (FileOutputStream fos = new FileOutputStream(userIcon)) {
                iconBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            File coversDir = new File(externalStorage, "Winlator/covers");
            if (!coversDir.exists()) coversDir.mkdirs();
            File cover = new File(coversDir, SHORTCUT_NAME + ".png");
            try (FileOutputStream fos = new FileOutputStream(cover)) {
                iconBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed installing icons", e);
        }
    }
}
