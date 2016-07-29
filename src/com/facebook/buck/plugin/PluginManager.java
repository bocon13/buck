package com.facebook.buck.plugin;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.PluginConfig;
import com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.Description;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Plugin manager responsible for loading plugins from jar files.
 *
 * The manager currently allows for the introduction of new build rule types, through the
 * introduction of Description types.
 */
public class PluginManager {
  private static final Logger LOG = Logger.get(PluginManager.class);

  private final PluginConfig config;
  private final ClassLoader buckClassLoader;
  private final Set<Class<? extends Description<?>>> descriptionClasses = Sets.newHashSet();
  private final Set<ClassLoader> classLoaders = Sets.newHashSet();

  public PluginManager(PluginConfig config) {
    this.config = config;
    this.buckClassLoader = ClassLoaderBootstrapper.getBuckClassLoader();
    initialize();
  }

  private void initialize() {
    Optional<String> pluginDirectory = config.getPluginDirectory();
    if (!pluginDirectory.isPresent()) {
      return;
    }

    File pluginDirFile = new File(pluginDirectory.get());
    File[] files = pluginDirFile.listFiles();
    if (files == null) {
      return;
    }

    for (File file : files) {
      loadPlugin(file);
    }
  }

  private void loadPlugin(File file) {
    List<String> classNames = Lists.newArrayList();
    try (JarFile jarFile = new JarFile(file)) {
      Enumeration<JarEntry> e = jarFile.entries();
      while (e.hasMoreElements()) {
        JarEntry je = e.nextElement();
        if (je.getName().endsWith(".class")) {
          String className = je.getName().replace('/', '.').replace(".class", "");
          classNames.add(className);
        }
      }
    } catch (IOException e) {
      LOG.debug("Skipping file: %s", file.getName());
      return;
    }

    final ClassLoader classLoader;
    try {
      URL[] classpath = new URL[]{file.toURI().toURL()};
      classLoader = new URLClassLoader(classpath, buckClassLoader);
      classLoaders.add(classLoader);
    } catch (MalformedURLException e) {
      LOG.error("Could not generate URL for file: %s", file.getName());
      return;
    }

    // Find any Description classes from the jar file
    for(String className : classNames) {
      try {
        Class<?> clazz = classLoader.loadClass(className);
        if (Description.class.isAssignableFrom(clazz)) {
          LOG.debug("Found rule description: %s", className); // FIXME remove
          @SuppressWarnings("unchecked")
          Class<? extends Description<?>> description = (Class<? extends Description<?>>) clazz;
          if (!descriptionClasses.add(description)) {
            LOG.warn("Duplicate rule description detected: %s", clazz.getName());
          }
        }
      } catch (ClassNotFoundException e) {
        LOG.debug("Skipping class: %s", className);
      }
    }
  }

  public Set<Description<?>> buildDescriptions() {
    ImmutableSet.Builder<Description<?>> descriptionBuilder = ImmutableSet.builder();
    for(Class<? extends Description<?>> clazz : descriptionClasses) {
      try {
        try {
          Constructor<? extends Description<?>> constructor = clazz.getConstructor(BuckConfig.class);
          descriptionBuilder.add(constructor.newInstance(config.getDelegate()));
        } catch (NoSuchMethodException | InvocationTargetException e) {
          // fallback to no arg constructor
          descriptionBuilder.add(clazz.newInstance());
        }
      } catch (InstantiationException | IllegalAccessException e) {
        LOG.error("Failed to instantiate rule description: %s", clazz.getName());
      }
    }
    return descriptionBuilder.build();
  }

  // this is a WIP to add a field to the rule key that contains a version hash
//  private void injectAdditionalBuildRule(Class<? extends Description<?>> descriptionClass) {
//    try {
//      descriptionClass.newInstance().createBuildRule().getClass();
//      descriptionClass.getMethod()
//    } catch (NoSuchBuildTargetException e) {
//      e.printStackTrace();
//    } catch (InstantiationException e) {
//      e.printStackTrace();
//    } catch (IllegalAccessException e) {
//      e.printStackTrace();
//    }
//  }

}
