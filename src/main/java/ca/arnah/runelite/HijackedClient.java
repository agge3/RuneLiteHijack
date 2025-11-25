package ca.arnah.runelite;

import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.SplashScreen;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;

import com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.net.*;
import java.util.jar.*;

/**
 * @author Arnah
 * @since Nov 07, 2020
 *
 * @modified agge3, 2025-11-24
 */
public class HijackedClient {
	// no constructor passing - just inject dependency
	@Inject
	PluginManager pluginManager;
	@Inject
	EventBus eventBus;
	@Inject
	ConfigManager configManager;

	Logger log = LoggerFactory.getLogger(HijackedClient.class);

	enum ClassLoaderType {
		URL,
		BYTES;
	}

	private final int sleep;
	private List<String> paths;
	private final ClassLoaderType classLoaderType;

	HijackedClient() throws Exception {
		this.sleep = Integer.parseInt(System.getProperty("HijackedClient.sleep", "100"));
		this.paths = Arrays.stream(
					System.getProperty("HijackedClient.paths", "externalplugins,sideloaded-plugins")
						.split(","))
				.map(String::trim)
				.collect(Collectors.toList());
		this.classLoaderType = ClassLoaderType.valueOf(
				System.getProperty("HijackedClient.classLoaderType", "URL"));

		log.info("ClassLoaderType: {}", classLoaderType);
	}

	private List<Class<?>> loadUrls(List<Path> jarPaths) {
		List<Class<?>> classes = new ArrayList<>();

		try {
			List<ClassByte> cbs = new ArrayList<>();
			List<URL> urls = new ArrayList<>();

			// filter through jars and add as url + classbyte structure (to get
			// class names to be loaded)
			for (Path jarPath : jarPaths) {
				log.info("parsing jar: {}", jarPath);
				try {
					urls.add(jarPath.toUri().toURL());
					cbs.addAll(listFilesInJar(jarPath));
				} catch (Exception e) {
					log.warn("FAIL: parse jar: {}", jarPath);
				}
				log.info("SUCCESS: parsed jar: {}", jarPath);
			}

			ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
			for (ClassByte cb : cbs) {
				log.info("loading class: {}", cb.name);
				try {
					loader.loadClass(cb.name);
				} catch (Exception e) {
					log.warn("FAIL: load class: {}", cb.name);
				}
				if (cb.resource) {
					log.info("CONTINUE: class is resource: {}", cb.name);
					continue;
				}
				classes.add(loader.loadClass(cb.name));
				log.info("SUCCESS: loaded class: {}", cb.name);
			}
		} catch (Exception e) {
			log.error("loadUrls failed", e);
		}

		return classes;
	}

	private List<Class<?>> loadBytes(List<Path> jarPaths) {
		List<Class<?>> toLoad = new ArrayList<>();

		try {
			SimpleClassLoader simpleLoader = new SimpleClassLoader(getClass().getClassLoader());

			// filter through all jars and get (bytes, name, resource)
			List<ClassByte> classes = new ArrayList<>();
			for (Path jarPath : jarPaths) {
				classes.addAll(listFilesInJar(jarPath));
			}

			int numLoaded = 0;
			do {
				numLoaded = 0;
				for (int i1 = classes.size() - 1; i1 >= 0; i1--) {
					if (classes.get(i1).resource) {
						simpleLoader.resources.put(classes.get(i1).name,
							new ByteArrayInputStream(classes.get(i1).bytes));
						continue;
					}

					Class<?> loaded = simpleLoader.loadClass(classes.get(i1).name, classes.get(i1).bytes);
					if (loaded != null) {
						numLoaded++;
						classes.remove(i1);
					}
					if (loaded != null && loaded.getSuperclass() != null && loaded.getSuperclass().equals(Plugin.class)) {
						log.info("Loaded: " + loaded.getName());
						toLoad.add(loaded);
					}
				}
			} while(numLoaded != 0);
		} catch (Exception ex) {
			log.error("loadBytes failed", ex);
		}

		return toLoad;
	}

	private void loadPlugins() throws Exception {
		List<Path> jarPaths = findJars();
		if (jarPaths.isEmpty()) {
			log.warn("no external plugins found in paths: {}", paths);
			// throw EXCEPTION
		}

		try {
			List<Class<?>> classes = new ArrayList<>();

			switch (classLoaderType) {
			case URL:
	  			classes = loadUrls(jarPaths);
				break;
			case BYTES:
				classes = loadBytes(jarPaths);
				break;
			default:
				throw new IllegalStateException("unknown classloader type: " + classLoaderType);
			}

			final List<Plugin> loaded = pluginManager.loadPlugins(classes, null)
				.stream().filter(Objects::nonNull).collect(Collectors.toList());

			SwingUtilities.invokeAndWait(() -> {
				try {
					for (Plugin plugin : loaded) {
						pluginManager.loadDefaultPluginConfiguration(Collections.singleton(plugin));
						pluginManager.startPlugin(plugin);
						log.info("started plugin: {}", plugin.getClass().getSimpleName());
					}
				} catch (PluginInstantiationException e) {
					log.error("failed to instantiate external plugins", e);
				}
				eventBus.post(new ExternalPluginsChanged());
			});
		} catch (Exception e) {
			log.info("failed to load plugins", e);
			throw e;
		}
	}

	public void start() {
		// xxx do we need?
		eventBus.register(this);

		log.info("STARTED");

		new Thread(()-> {
			while (SplashScreen.isOpen()) {
				try {
					Thread.sleep(sleep);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}

			log.info("Splash Screen done");

			try {
				loadPlugins();
			} catch (Exception ex) {
				log.error("failed to load external plugins", ex);
			}
		}).start();
	}

	public List<Path> findJars()
	{
		List<Path> files = new ArrayList<>();

		try {
			for (String path : paths) {
				Files.createDirectories(RuneLite.RUNELITE_DIR.toPath().resolve(path));
			}
		} catch (IOException e) {
			log.error("Files.createDirectories failed on paths: {}", paths);
		}

		try {
			for (String path : paths) {
				try (Stream<Path> walkable = Files.walk(RuneLite.RUNELITE_DIR.toPath().resolve(path))) {
					walkable.filter(Files::isRegularFile)
							.filter(f -> f.toString().endsWith(".jar"))
							.forEach(files::add);
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		return files;
	}

	public List<ClassByte> listFilesInJar(Path jarPath)
	{
		List<ClassByte> classes = new ArrayList<>();
		try (JarFile jarFile1 = new JarFile(jarPath.toFile()))
		{
			jarFile1.stream().forEach(jarEntry ->
			{
				if (jarEntry == null || jarEntry.isDirectory()) {
					return;
				}
				if(!jarEntry.getName().endsWith(".class")) {
					try (InputStream inputStream = jarFile1.getInputStream(jarEntry)) {
						classes.add(new ClassByte(ByteStreams.toByteArray(inputStream),
								jarEntry.getName(), true));
						// xxx is the byte stream good now?
					} catch (IOException ioException) {
						log.error("Could not obtain resource entry for " + jarEntry.getName());
					}
					return;
				}
				try (InputStream inputStream = jarFile1.getInputStream(jarEntry)) {
					classes.add(new ClassByte(ByteStreams.toByteArray(inputStream),
							jarEntry.getName().replace('/', '.').substring(0,
									jarEntry.getName().length() - 6)));
				} catch (IOException ioException)
				{
					log.warn("Could not obtain class entry for {}", jarEntry.getName());
				}
			});
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}
		return classes;
	}
}
