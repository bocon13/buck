/*
 * Copyright 2016-present Facebook, Inc.
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

import static com.facebook.buck.zip.ZipCompressionLevel.DEFAULT_COMPRESSION_LEVEL;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import javax.annotation.Nullable;

public class JavadocJar extends AbstractBuildRule implements HasMavenCoordinates, HasSources {

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sourceFiles;

  private final ImmutableSortedMap<SourcePath, Path> docFiles;

  @AddToRuleKey
  // We only need the source paths to be part of the key.
  private final ImmutableSortedSet<SourcePath> docFilesSources;

  @AddToRuleKey
  private final Optional<String> mavenCoords;

  //FIXME this needs to be added to the rule key
  @AddToRuleKey(stringify = true)
  private final JavadocArgs javadocArgs;

  private final Path output;
  private final Path temp;
  private final Path docFilesStaging;

  public JavadocJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> sourceFiles,
      ImmutableSortedMap<SourcePath, Path> docFiles,
      JavadocArgs javadocArgs,
      Optional<String> mavenCoords) {
    super(params, resolver);
    this.sourceFiles = sourceFiles;
    this.mavenCoords = mavenCoords;
    this.docFiles = docFiles;
    this.javadocArgs = javadocArgs;
    this.docFilesSources = docFiles.keySet();

    BuildTarget target = params.getBuildTarget();
    this.output = getProjectFilesystem().getRootPath().getFileSystem().getPath(
        String.format(
            "%s/%s%s-javadoc.jar",
            getProjectFilesystem().getBuckPaths().getGenDir(),
            target.getBaseNameWithSlash(),
            target.getShortName()));

    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), target, "%s-javadoc");
    this.docFilesStaging = BuildTargets.getScratchPath(getProjectFilesystem(), target, "%s-docfiles");
  }

  public ImmutableSortedMap<SourcePath, Path> getDocFiles() {
    return docFiles;
  }

  public static Path getDocfileWithPath(SourcePathResolver resolver,
                                                          SourcePath sourcePath, Path basePath) {
    Path src = resolver.getRelativePath(sourcePath);
    if (basePath != null) {
      return basePath.relativize(src);
    } else {
      return Paths.get("doc-files").resolve(src.getFileName());
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), output, /* force deletion */ true));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), temp));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), docFilesStaging));

    for (Map.Entry<SourcePath, Path> pair : docFiles.entrySet()) {
      Path src = getResolver().getRelativePath(pair.getKey());
      Path dest = docFilesStaging.resolve(pair.getValue());
      steps.add(new MkdirStep(getProjectFilesystem(), dest.getParent()));
      steps.add(CopyStep.forFile(getProjectFilesystem(), src, dest));
    }

    Path pathToArgsList =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__args");
    Path pathToClasspath =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__classpath");
    Path pathToSrcsList =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__srcs");
    // args list, classpath and sources list will share a parent directory
    steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

    //TODO consider moving this inside of the JavadocStep
    ImmutableList<Path> srcs = FluentIterable.from(sourceFiles)
        //TODO this step may be done for us by the "javadoc" tool already
        .filter(new Predicate<SourcePath>() {
          @Override
          public boolean apply(@Nullable SourcePath input) {
            return getResolver().getRelativePath(input).toString().endsWith(".java");
          }
        })
        .transform(new Function<SourcePath, Path>() {
          @Override
          public Path apply(SourcePath input) {
            return getResolver().getAbsolutePath(input);
          }
        }).toList();

    steps.add(new JavadocStep(
        getProjectFilesystem(),
        temp,
        srcs,
        ImmutableSortedSet.copyOf(
            JavaLibraryClasspathProvider.getClasspathEntries(getDeps()).values()),
        javadocArgs,
        pathToArgsList,
        pathToClasspath,
        pathToSrcsList,
        docFilesStaging));

    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            output,
            ImmutableSet.<Path>of(),
            /* junk paths */ false,
            DEFAULT_COMPRESSION_LEVEL,
            temp));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return sourceFiles;
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  public static class JavadocArgs {
    private static final ImmutableList<ImmutableList<String>> DEFAULT_ARGS =
        ImmutableList.of(
          ImmutableList.of("-quiet"),
          ImmutableList.of("-protected"),
          ImmutableList.of("-encoding", "UTF-8"),
          ImmutableList.of("-charset", "UTF-8"),
          ImmutableList.of("-notimestamp")
        );
    private static final String JAVA_SE_LINK_FORMAT = "http://docs.oracle.com/javase/%d/docs/api";

    private final ImmutableList<ImmutableList<String>> args;

    private JavadocArgs(ImmutableList<ImmutableList<String>> args) {
      this.args = args;
    }

    public static Builder builder() {
      return new Builder();
    }

    public Iterable<String> getLines() {
      return FluentIterable.from(args)
          .transform(new Function<ImmutableList<String>, String>() {
            @Nullable
            @Override
            public String apply(@Nullable ImmutableList<String> line) {
              return line != null ?
                  Joiner.on(' ').join(FluentIterable.from(line).transform(Escaper.javacEscaper())) : null;
            }
          });
    }

    @Override
    public String toString() {
      return args.toString();
    }

    public static class Builder {
      private final ImmutableList.Builder<ImmutableList<String>> builder;

      private Builder() {
        // Seeding the args builder with the default args
        builder = ImmutableList.<ImmutableList<String>>builder().addAll(DEFAULT_ARGS);
      }

      public Builder addArg(String option) {
        builder.add(ImmutableList.of(option));
        return this;
      }

      public Builder addArg(String option, String... params) {
        builder.add(ImmutableList.<String>builder().add(option).add(params).build());
        return this;
      }

      public Builder addArg(String option, Iterable<String> joinedParams) {
        builder.add(ImmutableList.of(option, Joiner.on(':').join(joinedParams)));
        return this;
      }

      public Builder addArg(String option, String firstParam, Iterable<String> joinedParams) {
        builder.add(ImmutableList.of(option, firstParam, Joiner.on(':').join(joinedParams)));
        return this;
      }

      public Builder addArgLine(String line) {
        builder.add(ImmutableList.copyOf(line.split("\\s+")));
        return this;
      }

      public Builder addJavaSELink(String sourceVersion) {
        int javaVersion = Integer.parseInt(sourceVersion);
        if (javaVersion < 6) {
          throw new RuntimeException("Java version is less than 1.6");
        }
        addArg("-link", String.format(JAVA_SE_LINK_FORMAT, javaVersion));
        return this;
      }

      public JavadocArgs build() {
        return new JavadocArgs(builder.build());
      }
    }
  }

  private static class JavadocStep extends ShellStep {
    private final ProjectFilesystem filesystem;
    private final ImmutableList<Path> sources;
    private final ImmutableCollection<Path> classpath;
    private final JavadocArgs javadocArgs;
    private final Path pathToArgsList;
    private final Path pathToClasspath;
    private final Path pathToSrcsList;
    private final Path pathToDocfiles;

    public JavadocStep(
        ProjectFilesystem filesystem,
        Path workingDirectory,
        ImmutableList<Path> sources,
        ImmutableCollection<Path> classpath,
        JavadocArgs javadocArgs,
        Path pathToArgsList,
        Path pathToClasspath,
        Path pathToSrcsList,
        Path pathToDocfiles) {
      super(workingDirectory);
      this.filesystem = filesystem;
      this.sources = sources;
      this.classpath = classpath;
      this.pathToArgsList = pathToArgsList;
      this.pathToClasspath = pathToClasspath;
      this.pathToSrcsList = pathToSrcsList;
      this.javadocArgs = javadocArgs;
      this.pathToDocfiles = pathToDocfiles;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws InterruptedException, IOException {
      // Write the args file
      filesystem.writeLinesToPath(javadocArgs.getLines(), pathToArgsList);

      // Write the classpath file
      filesystem.writeContentsToPath("-classpath " +
          Joiner.on(':').join(FluentIterable.from(classpath)
              .transform(new Function<Path, String>() {
                @Nullable
                @Override
                public String apply(@Nullable Path input) {
                  return input != null ? input.toAbsolutePath().toString() : null;
                }
              })
              .transform(Escaper.javacEscaper())),
          pathToClasspath
      );

      // Write the sources file
      filesystem.writeLinesToPath(
          FluentIterable.from(sources)
              .transform(Functions.toStringFunction())
              .transform(Escaper.javacEscaper()),
          pathToSrcsList);

      // Run the "javadoc" command
      return super.execute(context);
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      return ImmutableList.<String>builder()
          .add("javadoc")
          .add("-sourcepath").add(pathToDocfiles.toAbsolutePath().toString())
          .add("@" + pathToArgsList.toAbsolutePath())
          .add("@" + pathToClasspath.toAbsolutePath())
          .add("@" + pathToSrcsList.toAbsolutePath())
          .build();
    }

    @Override
    public String getShortName() {
      return "javadoc";
    }
  }
}
