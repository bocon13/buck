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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
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
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;


public class JavadocJar extends AbstractBuildRule implements HasMavenCoordinates, HasSources, MavenPublishable {

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sources;

  private final Path output;
  private final Path temp;
  private final Optional<String> mavenCoords;

  private final String windowTitle;

  public JavadocJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> sources,
      Optional<String> mavenCoords) {
    super(params, resolver);
    this.sources = sources;
    BuildTarget target = params.getBuildTarget();
    this.output = getProjectFilesystem().getRootPath().getFileSystem().getPath(
        String.format(
            "%s/%s%s-javadoc.jar",
            getProjectFilesystem().getBuckPaths().getGenDir(),
            target.getBaseNameWithSlash(),
            target.getShortName()));

    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), target, "%s-javadoc");
    this.mavenCoords = mavenCoords;
    this.windowTitle = target.getShortName(); //FIXME what is the actual title that we want? this is name of target
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), output, /* force deletion */ true));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), temp));

    ImmutableList<String> srcs = FluentIterable.from(sources).transform(new Function<SourcePath, String>() {
      @Override
      public String apply(SourcePath input) {
        return getResolver().getAbsolutePath(input).toString();
      }
    }).toList();
    steps.add(new JavadocStep(temp, windowTitle, null, null, null, null, null, srcs));

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
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Iterable<HasMavenCoordinates> getMavenDeps() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<BuildRule> getPackagedDependencies() {
    return ImmutableList.of();
  }

  @Override
  public boolean hasTest() {
    return false;
  }

  @Override
  public BuildTarget getTest() {
    //FIXME
    return null;
  }


  private static class JavadocStep extends ShellStep {

    //private final String jdkDocsUrl = "http://docs.oracle.com/javase/8/docs/api";
    //       command.add("-tag").add("onos.rsModel:a:\"onos model\"");

    private final String windowTitle;
    private final List<String> subpackages;
    private final List<String> sourcepaths;
    private final List<String> classpath;
    private final List<String> tags;
    private final List<String> linkUrls;
    private final List<String> sources;

    public JavadocStep(
        Path workingDirectory,
        String windowTitle,
        List<String> subpackages,
        List<String> sourcepaths,
        List<String> classpath,
        List<String> tags,
        List<String> linkUrls,
        List<String> sources) {
      super(workingDirectory);
      this.windowTitle = windowTitle;
      this.subpackages = subpackages;
      this.sourcepaths = sourcepaths;
      this.classpath = classpath;
      this.tags = tags;
      this.linkUrls = linkUrls;
      this.sources = sources;
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      ImmutableList.Builder<String> command = ImmutableList.<String>builder()
          .add("javadoc")
          .add("-quiet")
          .add("-protected")
          .add("-encoding", "UTF-8")
          .add("-charset", "UTF-8")
          .add("-notimestamp");

      addArg(command, "-windowtitle", windowTitle);

      addRepeatingArg(command, "-link", linkUrls);
      addRepeatingArg(command, "-tag", tags);

      addJoinedArg(command, "-subpackages", ':', subpackages);
      addJoinedArg(command, "-sourcepath", ':', sourcepaths);
      addJoinedArg(command, "-classpath", ':', classpath);

      addRepeatingArg(command, null, sources);

      return command.build();
    }

    private static void addArg(ImmutableList.Builder<String> builder, String option, String param) {
      if (option != null) {
        builder.add(option);
      }
      if (param != null) {
        builder.add(param);
      }
    }

    private static void addRepeatingArg(ImmutableList.Builder<String> builder, String option,
                                        List<String> params) {
      if (params != null) {
        for (String param : params) {
          addArg(builder, option, param);
        }
      }
    }

    private static void addJoinedArg(ImmutableList.Builder<String> builder, String option,
                                     char separator, List<String> params) {
      if (params != null && !params.isEmpty()) {
        builder.add(option);
        builder.add(Joiner.on(separator).join(params));
      }
    }

    @Override
    public String getShortName() {
      return "javadoc";
    }
  }
}
