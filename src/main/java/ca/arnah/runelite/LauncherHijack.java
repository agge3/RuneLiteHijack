package ca.arnah.runelite;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import javax.swing.UIManager;

/**
 * @author Arnah
 * @since Nov 07, 2020
 * @modified agge3, 2025-11-28
 * 
 * Unified approach that works with both:
 * 1. Official RuneLite.jar with launcher (uses URLClassLoader injection)
 * 2. Direct client launch from source build (uses direct loading)
 */
public class LauncherHijack {

    public LauncherHijack() {
        new Thread(() -> {
            ClassLoader targetClassLoader = null;
            boolean useLauncherApproach = false;
            
            System.out.println("Waiting for ClassLoader...");
            
            // APPROACH 1: Try to find the launcher's custom URLClassLoader
            // This works with official RuneLite.jar distribution
            for (int attempts = 0; attempts < 50; attempts++) { // Max 5 seconds
                targetClassLoader = (ClassLoader) UIManager.get("ClassLoader");
                if (targetClassLoader != null) {
                    // Check if this is the RuneLite client classloader
                    for (Package pack : targetClassLoader.getDefinedPackages()) {
                        if (pack.getName().equals("net.runelite.client.rs")) {
                            useLauncherApproach = true;
                            break;
                        }
                    }
                    if (useLauncherApproach) break;
                }
                try {
                    Thread.sleep(100);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            
            if (useLauncherApproach) {
                // ===== APPROACH 1: LAUNCHER WITH URLClassLoader =====
                System.out.println("Launcher ClassLoader found - using injection approach");
                injectViaLauncher(targetClassLoader);
            } else {
                // ===== APPROACH 2: DIRECT CLIENT LAUNCH =====
                System.out.println("Launcher ClassLoader not found - using direct loading approach");
                loadDirectly();
            }
        }).start();
    }

    /**
     * APPROACH 1: Official RuneLite.jar with launcher
     * - Launcher creates custom URLClassLoader
     * - We inject RuneLiteHijack.jar into that classloader
     * - Load ClientHijack from the injected classloader
     */
    private void injectViaLauncher(ClassLoader launcherClassLoader) {
        try {
            URLClassLoader classLoader = (URLClassLoader) launcherClassLoader;
            
            // Add our hijack JAR to the launcher's classloader
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            
            URI uri = LauncherHijack.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            if (uri.getPath().endsWith("classes/")) { // IntelliJ dev mode
                uri = uri.resolve("..");
            }
            if (!uri.getPath().endsWith(".jar")) {
                uri = uri.resolve("RuneLiteHijack.jar");
            }
            addUrl.invoke(classLoader, uri.toURL());
            System.out.println("Injected into launcher classloader: " + uri.getPath());
            
            // Load ClientHijack from the launcher's classloader
            Class<?> clazz = classLoader.loadClass(ClientHijack.class.getName());
            clazz.getConstructor().newInstance();
            System.out.println("ClientHijack instantiated via launcher classloader");
        } catch (Exception ex) {
            System.err.println("Failed to inject via launcher:");
            ex.printStackTrace();
        }
    }

    /**
     * APPROACH 2: Direct client launch (building from source)
     * - Both RuneLiteHijack.jar and client JAR are in classpath
     * - No custom URLClassLoader exists (Java 11+ system classloader)
     * - Load ClientHijack directly since it's already accessible
     */
    private void loadDirectly() {
        try {
            // Give the client time to initialize
            System.out.println("Waiting for client initialization...");
            Thread.sleep(3000);
            
            // Load ClientHijack directly from classpath
            // This works because both JARs are in -cp
            Class<?> clazz = Class.forName("ca.arnah.runelite.ClientHijack");
            clazz.getConstructor().newInstance();
            System.out.println("ClientHijack instantiated directly from classpath");
        } catch (Exception ex) {
            System.err.println("Failed to load ClientHijack directly:");
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Force disable JVM launcher (legacy)
        System.setProperty("runelite.launcher.nojvm", "true");
        // Enable reflection mode (required for external plugins)
        System.setProperty("runelite.launcher.reflect", "true");
        
        // Start the hijack thread
        new LauncherHijack();
        
        // Try to launch via official launcher first
        try {
            System.out.println("Attempting to launch via net.runelite.launcher.Launcher...");
            Class<?> clazz = Class.forName("net.runelite.launcher.Launcher");
            clazz.getMethod("main", String[].class).invoke(null, (Object) args);
            System.out.println("Launched via Launcher");
        } catch (ClassNotFoundException e) {
            // Expected when using client build from source
            System.out.println("Launcher not found (expected for source builds)");
            
            // Fall back to direct client launch
            try {
                System.out.println("Attempting to launch via net.runelite.client.RuneLite...");
                Class<?> clazz = Class.forName("net.runelite.client.RuneLite");
                clazz.getMethod("main", String[].class).invoke(null, (Object) args);
                System.out.println("Launched via Client");
            } catch (Exception e2) {
                System.err.println("Failed to launch client:");
                e2.printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("Unexpected error during launcher invocation:");
            e.printStackTrace();
        }
        
        System.out.println("LauncherHijack main() finished");
    }
}
