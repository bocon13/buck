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
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;


public class JavadocJar extends AbstractJavaLibrary {

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sources;

  private final Path output;
  private final Path temp;

  private final String windowTitle;

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
    this.windowTitle = target.getShortName(); //FIXME what is the actual title that we want? this is name of target

  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), output, /* force deletion */ true));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), temp));

    ImmutableList<String> srcs = FluentIterable.from(sources)
        .filter(new Predicate<SourcePath>() {
          @Override
          public boolean apply(@Nullable SourcePath input) {
            return getResolver().getRelativePath(input).toString().endsWith(".java");
          }
        })
        .transform(new Function<SourcePath, String>() {
          @Override
          public String apply(SourcePath input) {
            return getResolver().getAbsolutePath(input).toString();
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


    steps.add(new JavadocStep(
        temp,
        windowTitle,
        ImmutableList.of("org.onosproject"), //subpackages
        null, //FIXME BOC sourcepaths; seems not required
        classpath,
        ImmutableList.of("onos.rsModel:a:\"onos model\""), //FIXME BOC
        ImmutableList.of("http://docs.oracle.com/javase/8/docs/api"), //link urls
        srcs));

    /*
    '-tag onos.rsModel:a:"onos model"',
        '-quiet',
        '-protected',
        '-encoding UTF-8',
        '-charset UTF-8',
        '-notimestamp',
        '-windowtitle "' + title + '"',
        '-link http://docs.oracle.com/javase/8/docs/api',
        '-subpackages ',
        ':'.join(pkgs),
        '-sourcepath ',
        ':'.join(sourcepath),
        ' -classpath ',
        ':'.join(['$(classpath %s)' % n for n in deps]),
        '-d $TMP',
     */

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
