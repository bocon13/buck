/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.nio.file.Path;
import java.util.List;

/**
 * Provides a base implementation for post compile steps.
 */
public abstract class BaseCompileToJarStepFactory implements CompileToJarStepFactory {

  @Override
  public void createCompileToJarStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Path pathToSrcsList,
      Optional<SuggestBuildRules> suggestBuildRules,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      Path outputJar,
      Optional<Path> usedClassesFile,
      /* output params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {

    createCompileStep(
        context,
        sourceFilePaths,
        invokingRule,
        resolver,
        filesystem,
        declaredClasspathEntries,
        outputDirectory,
        workingDirectory,
        pathToSrcsList,
        suggestBuildRules,
        usedClassesFile,
        steps,
        buildableContext);

    steps.addAll(Lists.newCopyOnWriteArrayList(addPostprocessClassesCommands(
        filesystem.getRootPath(),
        postprocessClassesCommands,
        outputDirectory)));

    steps.add(
        new JarDirectoryStep(
            filesystem,
            outputJar,
            ImmutableSortedSet.of(outputDirectory),
            mainClass.orNull(),
            manifestFile.orNull()));
  }

  /**
   * Adds a BashStep for each postprocessClasses command that runs the command followed by the
   * outputDirectory of javac outputs.
   *
   * The expectation is that the command will inspect and update the directory by
   * modifying, adding, and deleting the .class files in the directory.
   *
   * The outputDirectory should be a valid java root.  I.e., if outputDirectory
   * is buck-out/bin/java/abc/lib__abc__classes/, then a contained class abc.AbcModule
   * should be at buck-out/bin/java/abc/lib__abc__classes/abc/AbcModule.class
   *
   * @param postprocessClassesCommands the list of commands to post-process .class files.
   * @param outputDirectory the directory that will contain all the javac output.
   */
  @VisibleForTesting
  static ImmutableList<Step> addPostprocessClassesCommands(
      Path workingDirectory,
      List<String> postprocessClassesCommands,
      Path outputDirectory) {
    ImmutableList.Builder<Step> commands = new ImmutableList.Builder<Step>();
    for (final String postprocessClassesCommand : postprocessClassesCommands) {
      BashStep bashStep = new BashStep(
          workingDirectory,
          postprocessClassesCommand + " " + outputDirectory);
      commands.add(bashStep);
    }
    return commands.build();
  }
}
