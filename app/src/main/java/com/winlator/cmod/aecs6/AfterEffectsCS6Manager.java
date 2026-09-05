package com.winlator.cmod.aecs6;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.system.Os;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineRegistryEditor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
            extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "aecs6.tar.zst", appDir, (file, sz) -> file);
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

        setupPluginLinksAndRegistry(container);
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

    public static void setupPluginLinksAndRegistry(Container container) {
        if (container == null) return;
        try {
            File rootDir = container.getRootDir();
            File cDrive = new File(rootDir, ".wine/drive_c");
            if (!cDrive.exists()) cDrive.mkdirs();

            // 1. Pre-create MediaCore plugin directories
            File commonMediaCore64 = new File(cDrive, "Program Files/Adobe/Common/Plug-ins/CS6/MediaCore");
            if (!commonMediaCore64.exists()) commonMediaCore64.mkdirs();

            File commonMediaCore32 = new File(cDrive, "Program Files (x86)/Adobe/Common/Plug-ins/CS6/MediaCore");
            if (!commonMediaCore32.exists()) commonMediaCore32.mkdirs();

            // 2. Pre-create Adobe folder in Program Files
            File pfAdobeDir = new File(cDrive, "Program Files/Adobe/Adobe After Effects CS6");
            if (!pfAdobeDir.exists()) pfAdobeDir.mkdirs();

            // 3. Locate Portable Support Files folder
            File appDir = getAppDir(container);
            File portableSupportFiles = new File(appDir, "App/Ae/Support Files");
            if (!portableSupportFiles.isDirectory()) {
                File nestedSupport = new File(appDir, "Adobe After Effects CS6 Portable [Black General]/App/Ae/Support Files");
                if (nestedSupport.isDirectory()) portableSupportFiles = nestedSupport;
            }

            if (portableSupportFiles.isDirectory()) {
                File portablePlugins = new File(portableSupportFiles, "Plug-ins");
                if (!portablePlugins.exists()) portablePlugins.mkdirs();
                File portableScripts = new File(portableSupportFiles, "Scripts");
                if (!portableScripts.exists()) portableScripts.mkdirs();
                File portablePresets = new File(portableSupportFiles, "Presets");
                if (!portablePresets.exists()) portablePresets.mkdirs();

                // Link C:\Program Files\Adobe\Adobe After Effects CS6\Support Files to portable Support Files
                File pfSupportFiles = new File(pfAdobeDir, "Support Files");
                if (!pfSupportFiles.exists()) {
                    try {
                        Os.symlink("../../../Adobe After Effects CS6 Portable/App/Ae/Support Files", pfSupportFiles.getAbsolutePath());
                        Log.d(TAG, "Linked Support Files symlink successfully.");
                    } catch (Exception e) {
                        Log.w(TAG, "Symlink Support Files failed, creating directory links instead", e);
                    }
                }

                // If pfSupportFiles exists as a directory (either symlinked or real folder):
                if (pfSupportFiles.isDirectory() && !FileUtils.isSymlink(pfSupportFiles)) {
                    linkChild(pfSupportFiles, "Plug-ins", "../../../../Adobe After Effects CS6 Portable/App/Ae/Support Files/Plug-ins");
                    linkChild(pfSupportFiles, "Scripts", "../../../../Adobe After Effects CS6 Portable/App/Ae/Support Files/Scripts");
                    linkChild(pfSupportFiles, "Presets", "../../../../Adobe After Effects CS6 Portable/App/Ae/Support Files/Presets");
                    linkChild(pfSupportFiles, "AfterFX.exe", "../../../../Adobe After Effects CS6 Portable/App/Ae/Support Files/AfterFX.exe");
                    linkChild(pfSupportFiles, "AfterFX.dll", "../../../../Adobe After Effects CS6 Portable/App/Ae/Support Files/AfterFX.dll");
                }

                // Link MediaCore into portable Plug-ins folder so AE scans MediaCore automatically
                File mediaCoreSymlink = new File(portablePlugins, "MediaCore");
                if (!mediaCoreSymlink.exists()) {
                    try {
                        Os.symlink("../../../../Program Files/Adobe/Common/Plug-ins/CS6/MediaCore", mediaCoreSymlink.getAbsolutePath());
                    } catch (Exception ignored) {}
                }

                File mediaCore32Symlink = new File(portablePlugins, "MediaCore-x86");
                if (!mediaCore32Symlink.exists()) {
                    try {
                        Os.symlink("../../../../Program Files (x86)/Adobe/Common/Plug-ins/CS6/MediaCore", mediaCore32Symlink.getAbsolutePath());
                    } catch (Exception ignored) {}
                }
            }

            // 4. Configure Wine Registry for Adobe After Effects CS6 install paths
            configureWineRegistry(rootDir);

            // 5. Fix portable reg settings if present
            fixPortableRegSettings(appDir);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup plugin links and registry", e);
        }
    }

    private static void linkChild(File parent, String name, String targetRelPath) {
        File child = new File(parent, name);
        if (!child.exists()) {
            try {
                Os.symlink(targetRelPath, child.getAbsolutePath());
            } catch (Exception ignored) {}
        }
    }

    private static void configureWineRegistry(File rootDir) {
        File[] regFiles = new File[]{
                new File(rootDir, ".wine/system.reg"),
                new File(rootDir, ".wine/user.reg")
        };

        String[] aeKeys = new String[]{
                "Software\\Adobe\\After Effects\\11.0",
                "Software\\Adobe\\After Effects\\11",
                "Software\\Wow6432Node\\Adobe\\After Effects\\11.0",
                "Software\\Wow6432Node\\Adobe\\After Effects\\11"
        };

        for (File regFile : regFiles) {
            if (!regFile.isFile()) continue;
            try (WineRegistryEditor reg = new WineRegistryEditor(regFile)) {
                for (String key : aeKeys) {
                    reg.setStringValue(key, "InstallPath", "C:\\Program Files\\Adobe\\Adobe After Effects CS6\\Support Files\\");
                    reg.setStringValue(key, "PluginInstallPath", "C:\\Program Files\\Adobe\\Adobe After Effects CS6\\Support Files\\Plug-ins\\");
                    reg.setStringValue(key, "CommonPluginInstallPath", "C:\\Program Files\\Adobe\\Common\\Plug-ins\\CS6\\MediaCore\\");
                    reg.setStringValue(key, "FFXInstallPath", "C:\\Program Files\\Adobe\\Adobe After Effects CS6\\Support Files\\Presets\\");
                }
                reg.setStringValue("Software\\Adobe\\DefaultLanguage\\CS6", "AdobeProductLanguage", "en_US");
                reg.setStringValue("Software\\Wow6432Node\\Adobe\\DefaultLanguage\\CS6", "AdobeProductLanguage", "en_US");
            } catch (Exception ignored) {}
        }
    }

    private static void fixPortableRegSettings(File appDir) {
        try {
            File regFile = new File(appDir, "Data/settings/AdobeAfterEffectsPortable.reg");
            if (regFile.isFile()) {
                byte[] bytes = FileUtils.read(regFile);
                if (bytes != null && bytes.length > 2) {
                    String regContent;
                    boolean isUtf16 = (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE);
                    if (isUtf16) {
                        regContent = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
                    } else {
                        regContent = new String(bytes, StandardCharsets.UTF_8);
                    }

                    if (regContent.contains("Users\\root\\Desktop")) {
                        regContent = regContent.replace(
                                "C:\\\\Users\\\\root\\\\Desktop\\\\Adobe After Effects\\\\Adobe After Effects CS6\\\\Adobe After Effects CS6 Portable [Black General]",
                                "C:\\\\Adobe After Effects CS6 Portable"
                        );
                        regContent = regContent.replace(
                                "C:\\Users\\root\\Desktop\\Adobe After Effects\\Adobe After Effects CS6\\Adobe After Effects CS6 Portable [Black General]",
                                "C:\\Adobe After Effects CS6 Portable"
                        );

                        byte[] outBytes = isUtf16
                                ? regContent.getBytes(StandardCharsets.UTF_16LE)
                                : regContent.getBytes(StandardCharsets.UTF_8);

                        try (FileOutputStream fos = new FileOutputStream(regFile)) {
                            if (isUtf16) {
                                fos.write(0xFF);
                                fos.write(0xFE);
                            }
                            fos.write(outBytes);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
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
                writer.println();
                writer.println("[Extra Data]");
                writer.println("screenSize=1920x1080");
                writer.println("graphicsDriver=freedreno");
                writer.println("graphicsDriverConfig=vulkanVersion=1.3;version=turnip26.2.0;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;syncFrame=0;disablePresentWait=0;resourceType=auto;bcnEmulation=auto;bcnEmulationType=software;bcnEmulationCache=0;gpuName=NVIDIA GeForce GTX 1080 Ti");
                writer.println("rendererNative=0");
                writer.println("rendererPresentMode=fifo");
                writer.println("rendererDriverId=system");
                writer.println("rendererFilterMode=0");
                writer.println("dxwrapper=dxvk+vkd3d");
                writer.println("dxwrapperConfig=version=1.10.3-arm64ec-async,framerate=0,async=1,asyncCache=0,maxFrameLatency=0,vkd3dVersion=None,vkd3dLevel=12_0,ddrawrapper=none,csmt=3,gpuName=NVIDIA GeForce GTX 1080 Ti,videoMemorySize=2048,strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl");
                writer.println("audioDriver=alsa");
                writer.println("hudMode=0");
                writer.println("showFPS=0");
                writer.println("box64Version=0.4.2");
                writer.println("box64Preset=COMPATIBILITY");
                writer.println("fexcoreVersion=2601");
                writer.println("fexcorePreset=COMPATIBILITY");
                writer.println("startupSelection=2");
                writer.println("midiSoundFont=wt_210k_G.sf2");
                writer.println("lc_all=en_US");
                writer.println("exclusiveXInput=0");
                writer.println("fullscreenStretched=0");
                writer.println("envVars=WINE_FAST_YIELD=1 WRAPPER_MAX_IMAGE_COUNT=0 VKD3D_SHADER_MODEL=6_6 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false mesa_glthread=true WINEESYNC=1 DXVK_DISABLE_TIMELINE_SEMAPHORES=1 MESA_GL_VERSION_OVERRIDE=4.6");
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
