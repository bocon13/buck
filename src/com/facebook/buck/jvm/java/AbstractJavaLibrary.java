/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * FIXME BOC
 */
public abstract class AbstractJavaLibrary extends AbstractBuildRule
    implements JavaLibrary, HasClasspathEntries, ExportDependencies,
    SupportsInputBasedRuleKey {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  protected final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  protected final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey(stringify = true)
  protected final Optional<Path> resourcesRoot;
  @AddToRuleKey
  protected final Optional<String> mavenCoords;

  protected final Optional<Path> outputJar;

  protected final ImmutableSortedSet<BuildRule> exportedDeps;
  protected final ImmutableSortedSet<BuildRule> providedDeps;
  // Some classes need to override this when enhancing deps (see AndroidLibrary).
  protected final ImmutableSet<Path> additionalClasspathEntries;
  protected final Supplier<ImmutableSet<Path>>
      outputClasspathEntriesSupplier;
  protected final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;
  protected final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;
  protected final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      declaredClasspathEntriesSupplier;


  private final Optional<Path> generatedSourceFolder;

  public AbstractJavaLibrary(
      BuildRuleParams params,
      final SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      ImmutableSet<Path> additionalClasspathEntries,
      Optional<Path> resourcesRoot,
      Optional<String> mavenCoords) {
    super(params, resolver);

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    for (BuildRule dep : exportedDeps) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            params.getBuildTarget() + ": exported dep " +
            dep.getBuildTarget() + " (" + dep.getType() + ") " +
            "must be a type of java library.");
      }
    }

    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.exportedDeps = exportedDeps;
    this.providedDeps = providedDeps;
    this.additionalClasspathEntries = FluentIterable
        .from(additionalClasspathEntries)
        .transform(getProjectFilesystem().getAbsolutifier())
        .toSet();
    this.resourcesRoot = resourcesRoot;
    this.mavenCoords = mavenCoords;

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget(), getProjectFilesystem()));
    } else {
      this.outputJar = Optional.absent();
    }

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSet<Path>>() {
          @Override
          public ImmutableSet<Path> get() {
            return JavaLibraryClasspathProvider.getOutputClasspathJars(
                AbstractJavaLibrary.this,
                getResolver(),
                sourcePathForOutputJar());
          }
        });

    this.transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getTransitiveClasspathEntries(
                AbstractJavaLibrary.this,
                getResolver(),
                sourcePathForOutputJar());
          }
        });

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSet<JavaLibrary>>() {
              @Override
              public ImmutableSet<JavaLibrary> get() {
                return JavaLibraryClasspathProvider.getTransitiveClasspathDeps(
                    AbstractJavaLibrary.this,
                    sourcePathForOutputJar());
              }
            });

    this.declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getDeclaredClasspathEntries(
                AbstractJavaLibrary.this);
          }
        });

    this.generatedSourceFolder = generatedSourceFolder;
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return outputJar.transform(SourcePaths.getToBuildTargetSourcePath(getBuildTarget()));
  }

  public static Path getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "lib__%s__output");
  }

  static Path getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target, filesystem),
            target.getShortNameAndFlavorPostfix()));
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().deprecatedAllPaths(srcs));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   * compile the {@code srcs} associated with this rule.  This set only contains the classpath
   * entries for those rules that are declared as direct dependencies of this rule.
   */
  protected ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<Path> getOutputClasspathEntries() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return generatedSourceFolder;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return outputJar.orNull();
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }
}
