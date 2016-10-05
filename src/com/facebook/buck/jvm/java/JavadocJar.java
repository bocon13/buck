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
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class JavadocJar extends AbstractJavaLibrary {

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sources;

  private final Path output;
  private final Path temp;
  private Optional<ImmutableList<Pair<String, ImmutableList<String>>>> groups = Optional.absent();

  public JavadocJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> sources,
      Optional<String> mavenCoords) {
    super(params,
        resolver,
        sources,
          /* resources */ ImmutableSet.<SourcePath>of(),
          /* generated source folder */ Optional.<Path>absent(),
          /* exported deps */ ImmutableSortedSet.<BuildRule>of(),
          /* provided deps */ ImmutableSortedSet.<BuildRule>of(),
          /* additional classpath entries */ ImmutableSet.<Path>of(),
          /* resources root */ Optional.<Path>absent(),
        mavenCoords
    );
    this.sources = sources;
    BuildTarget target = params.getBuildTarget();
    this.output = getProjectFilesystem().getRootPath().getFileSystem().getPath(
        String.format(
            "%s/%s%s-javadoc.jar",
            getProjectFilesystem().getBuckPaths().getGenDir(),
            target.getBaseNameWithSlash(),
            target.getShortName()));

    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), target, "%s-javadoc");
  }

  public void setGroups(Optional<ImmutableList<Pair<String, ImmutableList<String>>>> groups) {
    this.groups = groups;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), output, /* force deletion */ true));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), temp));

    ImmutableList<Path> srcs = FluentIterable.from(sources)
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

    ImmutableList<String> classpath = FluentIterable.from(getDeclaredClasspathEntries().values())
        .transform(new Function<Path, String>() {
          @Nullable
          @Override
          public String apply(@Nullable Path input) {
            return input != null ? input.toAbsolutePath().toString() : null;
          }
        }).toList();

    Path pathToArgsList =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__args");
    Path pathToSrcsList =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__srcs");
    // args and sources list will share a parent directory
    steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

    //----------------------------- consider moving this up to the constructor ---------------------
    JavadocArgs.Builder javadocArgs = JavadocArgs.builder()
        .addArg("-windowtitle", getBuildTarget().getShortName()) //FIXME what is the actual title that we want? this is name of target
        .addArg("-link", "http://docs.oracle.com/javase/8/docs/api") //FIXME from buckconfig + rule
        .addArg("-tag", "onos.rsModel:a:\"onos model\"") //FIXME from buckconfig + rule
        .addArg("-classpath", classpath);

    if (groups.isPresent()) {
      for (Pair<String, ImmutableList<String>> pair : groups.get()) {
        javadocArgs.addArg("-group", pair.getFirst(), pair.getSecond());
      }
    }
    //----------------------------- end block ------------------------------------------------------

    steps.add(new JavadocStep(
        getProjectFilesystem(),
        temp,
        srcs,
        javadocArgs.build(),
        pathToArgsList,
        pathToSrcsList
    ));

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
    return sources;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return ImmutableSortedSet.of(); //FIXME BOC
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return null;
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return Optional.absent(); //FIXME BOC
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return ImmutableSortedMap.of(); //FIXME BOC
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
//    private static final String JAVA_SE_LINK_FORMAT = "http://docs.oracle.com/javase/%d/docs/api";

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
              if (line == null) {
                return null;
              }
              return Joiner.on(' ').join(
                  FluentIterable.from(line).transform(Escaper.javacEscaper()));
            }
          });
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

      public JavadocArgs build() {
        return new JavadocArgs(builder.build());
      }
    }
  }

  private static class JavadocStep extends ShellStep {
    private final ProjectFilesystem filesystem;
    private final List<Path> sources;
    private final JavadocArgs javadocArgs;
    private final Path pathToArgsList;
    private final Path pathToSrcsList;

    public JavadocStep(
        ProjectFilesystem filesystem,
        Path workingDirectory,
        List<Path> sources,
        JavadocArgs javadocArgs,
        Path pathToArgsList,
        Path pathToSrcsList) {
      super(workingDirectory);
      this.filesystem = filesystem;
      this.sources = sources;
      this.pathToArgsList = pathToArgsList;
      this.pathToSrcsList = pathToSrcsList;
      this.javadocArgs = javadocArgs;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws InterruptedException, IOException {
      // Write the args file
      filesystem.writeLinesToPath(javadocArgs.getLines(), pathToArgsList);

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
          .add("@" + pathToArgsList.toAbsolutePath())
          .add("@" + pathToSrcsList.toAbsolutePath())
          .build();
    }

    @Override
    public String getShortName() {
      return "javadoc";
    }
  }
}
